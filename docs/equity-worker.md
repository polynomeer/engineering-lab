# Equity worker: deletion / redis-lock / registration (D5-2)

## 동작과 측정 범위

세 실험은 Java 21 `equity-worker` 배포물에서 기존 equity-system의 DAO·서비스·잠금 구현을 사용한다.
대시보드·중앙 Run 저장·worker protocol v1·확장 export를 공유하며 CONTAINER로만 실행한다.
기존 JUnit benchmark와 독립 Boot 앱은 그대로 사용할 수 있다.

| 실험 ID 접미사 (`career.equity-system.`) | variant | 확인할 결과 |
| --- | --- | --- |
| deletion | BEFORE | 단일 트랜잭션 삭제; 대상 건수·비대상 37행 보존 |
| deletion | AFTER | 복합 인덱스 + 기존 5% 대형 유통사 경로 + 5,000행 청크; 같은 결과 |
| redis-lock | BEFORE | TTL 없음, 지연된 A 해제가 B의 잠금을 지우는 문제 재현 |
| redis-lock | AFTER | TTL 설정·실제 만료, A의 해제에서 B 보존, B의 정상 해제 |
| registration | BEFORE | 순차 등록, 고정 배치 1,000행 |
| registration | AFTER | 설정한 청크·worker·최대 진행 청크 수로 등록 |
| registration | INVALID_ROW | 마지막 행의 share=101 거절, Run FAILED 및 확인된 부분 저장 건수 |

PASS는 해당 variant의 기대와 관측이 일치한다는 뜻이다. Redis BEFORE의 PASS는
안전한 잠금이라는 뜻이 아니다. 소유자 교체는 테스트가 기존 키를 삭제한 뒤 B가
획득하는 결정적 순서로 주입한다. BEFORE가 TTL로 스스로 만료됐다는 주장은 하지 않는다.
AFTER 만료 검사는 별도 150ms TTL 키를 최대 5초 동안 실제 Redis에서 조회한다.
소유권 검사 키에는 30초 TTL을 사용한다.

FACT 기반 재현은 복합 인덱스·청크 삭제, 토큰 비교 Lua 해제·TTL, 병렬 등록과
backpressure의 동작이다. worker, 입력 규모·날짜·고정 지분율, 파라미터 상한,
기존 RowProcessor의 행당 2ms 대기, 측정/저장 계약은 NEW-DESIGN이다.
과거 업무 처리량·성능 개선율을 재현하는 측정이 아니다.

## 입력과 경계

`variant` 외에는 아래 정수 파라미터를 명시한다. SINGLE_PASS만 지원하며 파일 입력은 없다.

| 실험 | 파라미터 | 허용 범위 | 화면/sample 기본값 |
| --- | --- | --- | --- |
| deletion | rows | 1–50,000 | 12,000 |
| registration | rows | 1–10,000 | 200 |
| registration | chunkSize | 1–1,000 | 20 |
| registration | workers | 1–8 | 2 |
| registration | maxInFlight | 1–16 | 2 |

BEFORE 등록에서는 chunkSize/workers/maxInFlight를 사용하지 않는다. 같은 schema로
비교할 수 있게 입력은 보존한다. AFTER/INVALID_ROW에서만 적용한다. 입력 fixture는
lazy List이며 실제 매핑 버퍼·대기 작업·보관 Future 수는 최대 진행 청크 수에 비례한다.
기존 `register(rows, chunkSize)`는 worker 8/진행 청크 8의 기본값을 유지한다.
라이브러리 `registerMeasured` 자체의 상한은 chunkSize 1–5,000, workers 1–16,
maxInFlight 1–32이며 worker schema는 위 표처럼 더 좁게 제한한다.
완료된 Future 전체를 누적하지 않고 제한된 창에서 회수한다. 실패 시 진행 작업을
취소하고 종료를 기다린 후 실패를 전달한다.

이 실험은 기존 Career 등록 구현의 부분 커밋 동작도 보여준다. 잘못된 마지막 행은
해당 청크의 insert 전에 거절되지만 이전 청크는 남을 수 있다. 전체 rollback,
재시도·행 재처리·멱등성을 새로 보장하지 않는다. 오류는 CHECK/REPORT와 Run FAILED로
기록한다. 루트 Excel ingestion의 영속 dead-letter 처리와는 별도 실험이다.
DB 오류를 성공이나 0건으로 바꾸지 않는다.

## 메트릭과 증거

모든 메트릭은 worker의 한 실행 최종값이다. 여러 snapshot을 합산하지 않는다.

| 키 | 단위 | 의미 |
| --- | --- | --- |
| deletion.elapsedNanos | ns | 서비스 삭제 호출 전체; schema/seed/index 생성 제외 |
| deletion.maxSingleStepMs | ms | 가장 긴 JDBC 삭제 단계(커밋 포함)의 시간; 트랜잭션 시간의 대용 지표 |
| deletion.deletedRows | rows | 확인된 삭제 건수 |
| deletion.chunks | chunks | 삭제 호출 수; 정확한 청크 배수일 때 마지막 빈 호출 포함 |
| registration.elapsedNanos | ns | 등록 호출 및 실패 시 진행 작업 종료 대기; 합성 2ms 행 대기 포함 |
| registration.storedRows | rows | 실행 종료 후 실제 DB COUNT |
| registration.maxInFlightChunks | chunks | 제출 후 아직 회수하지 않은 청크의 최대 수; 실행 스레드 수/DB 연결 수와 다름 |
| registration.backpressureWaits | waits | 다음 청크를 제출하려는데 창이 찼고 완료 작업도 없어 기다린 횟수 |
| registration.backpressureNanos | ns | 위 제출 대기의 합; 마지막 drain 대기는 제외 |

단일 삭제 단계 시간은 DB의 실제 잠금 점유를 직접 계측한 값이 아니다. ms 절삭으로
짧은 호출은 0일 수 있다. AFTER 총시간이 BEFORE보다 빠르다는 assertion은 없다.
등록 BEFORE 및 실패 경로에서 수집되지 않은 admission 메트릭은 생략한다.
실행 중 queue-depth 시계열이나 CPU 성능 측정으로 해석하지 않는다.
Redis는 CHECK만 생성하고 METRICS presentation을 광고하지 않는다.

각 실행은 체크, 허용된 메트릭, `equity-report.json` REPORT를 중앙 저장소에 남긴다.
REPORT는 크기/hash 검증 후 복사된다. 확장 NDJSON export에는 참조를 제공하며 UI에서 중앙 사본을 다운로드한다.
D6 관리 REPORT 다운로드를 지원한다. 실패 Run도 부분 증거를 조회/export할 수 있고
`evidenceComplete=false`를 유지한다. 같은 저장소 재시작 후 조회할 수 있다.

## 배포와 실행

```bash
./scripts/career-lab.sh test :equity-system:equityWorkerManifest
```

manifest는 `career-lab/equity-system/build/libs/equity-worker-manifest.json`에 생성된다.
[공통 이미지 계약](worker-container-isolation.md#i3-protocol-transport와-중앙-증거-연결)에
맞게 Java 21 JRE 이미지의 `/opt/lab/worker.jar`에 equity worker jar를 넣는다.
worker의 `requiresDocker=false`는 Docker client가 내부에 없다는 뜻이며 controller에는
Docker가 필요하다. worker 이미지와 MySQL/Redis 의존 이미지 모두 로컬에 준비한 뒤
`docker image inspect <image> --format '{{.Id}}'`의 전체 image ID를 설정한다.
통합 검증 기준 이미지는 `mysql:8.0.39`, `redis:7.4-alpine`, `eclipse-temurin:21-jre`다.

```yaml
lab:
  career:
    equity:
      enabled: true                     # 기본 false
      recover-on-startup: false          # 기본 false; v2 기록의 명시적 복구
      container:
        image-id: sha256:<64자리 worker image ID>
        artifact-sha256: <64자리 jar SHA-256>
        mysql-image-id: sha256:<64자리 MySQL image ID>
        redis-image-id: sha256:<64자리 Redis image ID>
```

활성화 전에 기본 빈 값을 채워야 한다. 각 시나리오는 독립 접수 guard를 사용한다.
미준비 이미지 또는 미해결 이전 실행은 새 접수를 차단한다. 기본 sequence/checks
설정·기록·실행 방식은 변경하지 않는다. `/modules`에는 활성화 시 equity-system이 추가된다.

화면 `/app/experiments/career.equity-system.deletion` 등에서 preset을 선택한다.
[시나리오 JSON](../experiments/career/equity-system/scenarios/)은 일곱 변형을 제공한다.
설정한 서버에 제출한 뒤 Run 상세에서 worker 환경, 체크, 원본 메트릭, 확장 export를 확인한다.

## 자원 소유권과 복구

`EquityContainerResources`가 실행별 내부 bridge 네트워크와 MySQL 또는 Redis 하나를
소유한다. worker에는 Docker socket·host mount·외부 포트가 없고 고정 alias와
MySQL의 실행별 임시 암호만 전달한다. MySQL은 512MiB 컨테이너/256MiB data tmpfs,
Redis는 256MiB 컨테이너/32MiB maxmemory(noeviction)/1MiB data tmpfs다. Redis
RDB/AOF 저장은 끄며 이미지의 익명 볼륨 대신 명시적 tmpfs를 사용한다.
worker 자체의 공통 256MiB 경계와 128MiB JVM heap 설정은 유지한다.

자원 journal은 `equity-mysql-resources` 또는 `equity-redis-resources` 아래에 남긴다.
확인된 worker 제거 → 의존 컨테이너 제거 → 비어 있는 네트워크 제거 순서를 따른다.
전체 ID·소유권·엔진·설정 검증에 실패하거나 생성/시작 응답이 미확인인 경우 운영자
확인을 유지한다. 복구는 기존 Run을 재실행하거나 증거를 성공으로 봉인하지 않는다.
별도 adapter로 구현해 sequence-v1 journal과 프로파일은 변경하지 않았다.

## 재현 검증

두 Gradle wrapper는 **순차 실행**한다.

```bash
./scripts/career-lab.sh test :equity-system:equityWorkerManifest
./gradlew test equityContainerTest
```

`equityContainerTest`는 실제 Java 21 worker와 MySQL/Redis로 schema 거절 → API 제출 →
7개 variant → 부분 실패 → metrics/checks/REPORT/export → 자원 제거 → 같은 저장소
재개방을 검사한다. 별도 이미지 태그와 모든 실행 소유 자원만 정리한다.
성공한 API 응답은 `build/verification/d5-2`에 기록한다.

frontend 서버를 실행한 뒤 Node 버전을 [frontend README](../frontend/README.md)에 맞춰:

```bash
cd frontend
npm test
npm run build
EQUITY_EVIDENCE=../build/verification/d5-2 npm run test:e2e -- equity.spec.ts
```

브라우저 검증은 실제 컨테이너/API 응답을 기록한 7개 snapshot과 3개 preset 폼을
검사한다. 브라우저 자체가 새 Docker 실행을 제출하는 end-to-end 검증과 구분한다.
EQUITY_EVIDENCE를 생략하면 snapshot 테스트는 명시적으로 건너뛴다.

최종 검증 (2026-10-15): Career 136건(미변경 일부 결과 재사용), 루트 324건,
실제 equity 컨테이너 7건, frontend 단위 17건·타입 검사·빌드가 통과했다.
실제 응답 7종·preset 3종·기존 Career 화면 4종의 브라우저 14건도 실패/건너뜀 없이
통과했다. 기존 선택 실행 실서버 브라우저 2건은 이 회귀 대상에서 제외했다.
375px 가로 넘침과 desktop/mobile 캡처를 확인했고 실행 소유 컨테이너·네트워크·이미지를
정리했다. INVALID_ROW 실행에서 확인된 부분 저장은 180/200행이었다. 소규모 삭제
fixture에서 AFTER의 총시간과 최대 단계 시간이 모두 BEFORE보다 길게 관측돼,
단일 실행의 수치를 일반적인 성능 개선 근거로 삼지 않는다는 해석도 유지한다.

D6의 현재 동작·제한·운영 명령은 [통합 운영 가이드](dashboard-operations.md)를 따른다.
