# MDS worker: settlement-guard / idempotency (D5-4)

## 실행과 결과 해석

두 실험은 Java 21 `mds-worker`에서 기존 mds-distribution의 Spring 서비스·AOP·JPA를
사용한다. 각 Run마다 controller가 새 MySQL과 내부 네트워크를 준비한다. 기존 독립
앱과 JUnit 테스트는 유지한다. 공통 대시보드, 중앙 CHECK/REPORT 저장, 확장 NDJSON
export 및 동일 저장소 재개방을 지원한다. 수치 메트릭을 만들지 않아 catalog에
METRICS presentation을 광고하지 않는다.

| 실험 (`career.mds-distribution.` 접두사) | variant | 기대 결과 |
| --- | --- | --- |
| settlement-guard | BEFORE | 활성 정산 락에도 가드 없는 수정 성공; 문제 재현 |
| settlement-guard | AFTER | 활성 락에서 수정 차단·원본 보존, 해제 후 수정 성공 |
| settlement-guard | TTL_RECOVERY | 해제 없이 TTL 만료·수정 허용, 후속 소유자 획득, 이전 소유자 해제 거절 |
| idempotency | SEQUENTIAL | 같은 메시지 반복 전달에도 앨범 1개·DONE·attempt 1 |
| idempotency | CONCURRENT | 두 스레드의 동시 전달 후 앨범 1개·DONE·attempt 1 |
| idempotency | RETRY_AFTER_FAILURE | 실패 후 FAILED/attempt 1/앨범 0 → 재시도 후 DONE/attempt 2/앨범 1 |
| idempotency | PROCESSING_FAILURE | 업무 실패를 남기고 재시도하지 않음; Run FAILED, 부분 증거 보존 |

PASS는 기대와 관측의 일치다. BEFORE의 PASS는 안전한 정산 보호를 의미하지 않는다.
PROCESSING_FAILURE에서 failed-attempt PASS는 실패 상태의 정확한 기록을 확인하고,
processing-completed FAIL은 업무 처리가 완료되지 않았음을 나타낸다. 이 Run을 성공
증거로 봉인하지 않는다. 정상 변형은 CHECK 4/4/7/2/3/3개, 실패 변형은 2개를 남긴다.

입력은 `variant` 하나이며 SINGLE_PASS만 허용한다. 파일·외부 endpoint 입력은 없다.
[JSON 예제](../experiments/career/mds-distribution/scenarios/)와 화면 preset은 동일하다.
정산 락은 일반 변형에서 30초, TTL_RECOVERY에서 2초이고 만료 확인은 최대 8초/25ms
간격이다. TTL을 DB에서 강제로 변경하지 않고 실제 경과를 기다린다. 만료된 행의
status는 LOCKED일 수 있으며 현재 시각에 대한 지연 평가로 비활성 취급한다.
컨테이너나 정산 프로세스를 실제로 죽이는 시험은 아니다.

메시지는 리스너를 직접 호출한다. 동시 변형은 두 고정 스레드·두 작업만 제출하고
5초 gate/30초 완료 대기·35초 종료 대기로 제한한다. 재시도 변형은 기존 실패 훅을
사용한 뒤 **동일 ID의 payload를 정상 제목으로 바꾸는 합성 fixture**다. 실제 SQS의
같은 payload 재전달·가시성 제한·ACK·DLQ·자동 backoff를 검증하지 않는다.

기존 상태 플래그/AOP/유니크 키 원리는 FACT 기반이며 TTL·고정 제목·동시 작업 수·
실패 훅과 실행 계약은 NEW-DESIGN이다. 가드 조회와 수정 사이 모든 경합을 원자적으로
막거나, 앨범 저장 후 DONE 기록 전 crash에서 exactly-once를 보장하는 실험은 아니다.
처리와 상태 기록은 기존 별도 트랜잭션을 유지한다. PROCESSING에 남은 메시지의 crash
복구도 이 단계에 추가하지 않았다.

## DB 실패를 중복으로 숨기지 않기

기존 tryInsertNew는 모든 DataAccessException을 중복으로 취급했다. D5-4에서는
cause의 MySQL duplicate-key 오류 1062/SQLState 23000만 FAILED 재시도 판단으로
넘기고, 연결 장애·데드락·다른 제약 오류는 전파한다. 리스너가 이를 정상 ACK처럼
반환하지 않으며 worker는 실패 CHECK/REPORT를 남긴다. DB 장애 전파 단위 테스트와
실제 MySQL의 순차/동시 중복·실패 재시도 회귀를 함께 실행한다.

REPORT `mds-report.json`은 중앙 사본으로 보존한다. 현재 UI와 확장 export에서는
hash/크기/참조를 제공하며 다운로드는 D6 범위다. 업무 실패 시에도 확인된 증거를
조회/export할 수 있고 동일 저장소 재개방 후 그대로 유지한다.

## 배포·설정·자원

```bash
./scripts/career-lab.sh test :mds-distribution:mdsWorkerManifest
```

manifest: `career-lab/mds-distribution/build/libs/mds-worker-manifest.json`.
[공통 이미지 계약](worker-container-isolation.md)에 따라 Java 21 JRE의
`/opt/lab/worker.jar`에 배포 jar를 넣고 아래 전체 image ID와 jar SHA-256을 설정한다.
검증 기준은 eclipse-temurin:21-jre와 mysql:8.0.39다. requiresDocker=false는 worker
안에서 Docker client를 사용하지 않는다는 뜻이며 controller에는 Docker가 필요하다.

```yaml
lab:
  career:
    mds:
      enabled: true                     # 기본 false
      recover-on-startup: false          # 기본 false
      container:
        image-id: sha256:<64자리 worker image ID>
        artifact-sha256: <64자리 jar SHA-256>
        mysql-image-id: sha256:<64자리 MySQL image ID>
```

기본 image/hash는 빈 값이므로 활성화 전에 채운다. 준비되지 않은 이미지나 미해결
이전 실행은 접수를 차단한다. `/app/experiments/career.mds-distribution.settlement-guard`
또는 `idempotency`에서 preset을 선택한다. Run 상세에서 CHECK와 worker 환경,
REPORT 참조 및 실행·검증 포함 내보내기를 확인한다.

`MdsContainerResources`는 기존 CareerContainerResources 계약을 사용한다.
namespace는 mds-distribution-mysql, DB는 mds_lab, alias는 mds-db다. worker에
실행별 임시 암호만 전달하며 socket·외부 포트는 없다. worker 256MiB/heap 128MiB,
MySQL 512MiB/data tmpfs 256MiB의 기존 경계를 유지한다. 새로운 공통 자원 동작을
추가하지 않았다. journal은 mds-distribution-mysql-resources 아래에 남긴다.
worker 제거 확인 → DB → 비어 있는 network 순서로 정리하고, ID/소유권/engine/profile
불일치나 생성·시작 결과 미확인은 운영자 확인 상태로 유지한다. 시작 시 복구는 opt-in이며
실험을 다시 실행하거나 미완료 증거를 성공 처리하지 않는다.

## 검증 재현

두 Gradle wrapper는 공유 worker-support 산출물 때문에 순차 실행한다.

```bash
./scripts/career-lab.sh test :mds-distribution:mdsWorkerManifest
./gradlew test mdsContainerTest
```

mdsContainerTest는 실제 Java 21/MySQL로 schema 거절 → 일곱 변형 → 부분 실패 →
CHECK/REPORT/export → 자원 제거 → 동일 저장소 재개방을 확인한다.
API 응답은 `build/verification/d5-4`에 남긴다. frontend 서버를 실행하고
[Node 버전](../frontend/README.md)을 맞춘 뒤:

```bash
cd frontend
npm test
npm run build
MDS_EVIDENCE=../build/verification/d5-4 npm run test:e2e -- mds.spec.ts
```

브라우저는 실제 API 응답 snapshot 일곱 개와 preset 폼 두 개를 검사한다.
브라우저가 실제 컨테이너 실행을 새로 제출하는 시험과 구분한다. MDS_EVIDENCE를
생략하면 snapshot 검증은 명시적으로 건너뛴다.

최종 검증 (2026-10-15): Career 전체 144건(미변경 일부 결과 재사용), 루트 352건,
`mdsContainerTest` 실제 컨테이너 7건, frontend 단위 17건·타입 검사·빌드가 통과했다.
실제 API snapshot 7종·preset 2종·기존 Career 화면 4종의 브라우저 13건도
실패/건너뜀 없이 통과했다. 기존 선택 실행 실서버 브라우저 2건은 대상에서 제외했다.
375px 가로 넘침 검증과 desktop/mobile 캡처를 확인했다. 처음 모듈 테스트에서
worker jar 시스템 속성 이름 오타로 배포 테스트 2건이 실패했으나 설정을 수정하고
전체 suite로 재검증했다. 테스트를 비활성화하거나 기대 결과를 완화하지 않았다.
정상/업무 실패의 CHECK·REPORT·raw export와 저장소 재개방, 실행 소유 자원 제거를
확인했다. 테스트 worker·DB·network·이미지 태그가 남지 않았고 Vite 서버도 종료했다.
