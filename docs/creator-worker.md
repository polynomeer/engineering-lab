# Creator worker: session-rotation / mapping-integrity / post-commit (D5-5)

## 실행 범위와 결과

Java 21 creator worker가 기존 Creator 서비스·Spring 트랜잭션·이벤트 리스너를 호출한다.
session-rotation은 Redis, mapping-integrity/post-commit은 MySQL을 실행별로 새로 만든다.
공통 대시보드의 preset, 중앙 CHECK/REPORT 저장, 확장 NDJSON export와 동일 저장소
재개방을 사용한다. CHECK 전용 실험으로 수치 METRICS presentation을 광고하지 않는다.
기존 독립 앱과 JUnit 테스트는 유지한다.

| 실험 (`career.creator-studio.` 접두사) | variant | 확인할 결과 | CHECK 수 |
| --- | --- | --- | --- |
| session-rotation | BEFORE | refresh 후 옛 access token도 계속 통과하는 문제 | 3 |
| session-rotation | AFTER | 이전 access 거절, refresh 재사용/기기 불일치 거절, 연속 교체 | 6 |
| session-rotation | CONCURRENT | 같은 refresh token의 두 요청 중 하나만 성공 | 4 |
| mapping-integrity | BEFORE | 기존 Creator의 producer 덮어쓰기로 콘텐츠 접근이 끊기는 가설 | 2 |
| mapping-integrity | AFTER | 중복 가입·미확인 이관 거절, 매핑/콘텐츠/이력 보존 | 3 |
| mapping-integrity | TRANSFER | 대상 Creator의 Program·매핑·이력 변경, 다른 Creator 콘텐츠 보존 | 5 |
| post-commit | COMMIT | 커밋 후 두 stub 각각 1회 호출 | 5 |
| post-commit | ROLLBACK | Program 없음, 두 stub 미호출 | 5 |
| post-commit | RETRY | Braze 2회 실패 후 3번째 성공, Mixpanel 1회 성공 | 5 |
| post-commit | EXHAUSTED | Braze 3회 실패, DB 커밋·Mixpanel 성공 유지, Run FAILED | 5 |

입력은 `variant` 하나이며 SINGLE_PASS만 허용한다. 파일·외부 주소 입력은 없다.
[JSON 예제](../experiments/career/creator-studio/scenarios/)와 화면 preset은 동일하다.
PASS는 명시한 기대와 관측의 일치다. BEFORE PASS는 안전한 설계라는 의미가 아니다.
EXHAUSTED에서는 앞선 커밋·호출 횟수 검사가 PASS여도 delivery-completed가 FAIL이며
전체 Run은 FAILED/증거 미완료다. 실제 외부 호출 성공을 주장하지 않는다.

## 사실과 설계, 이번 보완

세션 개선안은 당시 식별·제안까지만 진행됐던 설계의 재구현이다. 매핑 BEFORE는
확정된 장애 원인이 아닌 producer_id 덮어쓰기 가설을 재현한다. Post-commit 구조는
FACT 기반이고 고정 fixture·스레드 수·시간 제한·stub 실패 주입은 NEW-DESIGN이다.

D5-5에서는 기존 서비스의 두 경계를 보완했다.

- ImprovedSessionManager는 refresh 조회/삭제와 회전을 하나의 Lua 실행으로 묶는다.
  올바른 기기의 미사용 refresh만 소비하고 이전 access 삭제·새 access/refresh·역인덱스를
  함께 기록한다. 잘못된 기기 요청은 refresh를 소비하지 않는다. 1초 미만 TTL은 거절한다.
  worker는 access 30초/refresh 60초를 사용한다. 토큰은 opaque 문자열이며 원문을
  CHECK/REPORT에 넣지 않는다. JWT 검증·refresh family 탈취 탐지·동시 issue·Redis Cluster·
  Redis 장애 시 내구성은 검증 범위 밖이다.
- transferContent=true는 이제 해당 Creator가 만든, 원래 Producer에 속한 Program만
  bulk UPDATE로 이관한다. 매핑·이력과 같은 트랜잭션을 사용한다. 기존 구현은 확인
  플래그만 받고 Program을 옮기지 않았다. 이관 중 이력 저장 실패 시 모든 변경 rollback과
  다른 Creator 콘텐츠 보존을 실제 MySQL 테스트로 확인한다. 이는 새로 정한 소유자별
  이관 정책이며 Episode/Clip, 동시 이관·생성 경합 및 관리자 인증은 다루지 않는다.

세션 동시 변형은 고정 스레드 2개·작업 2개, 5초 시작 gate/10초 결과·종료 대기로
제한한다. DB fixture는 Member/Creator 2개, Program 2개 수준이며 전체 이력/이벤트를
무제한 누적하지 않는다.

## Post-commit 관측 방법

실제 Braze/Mixpanel 요청은 없다. worker 전용 조건부 설정의 @Primary stub은 호출
횟수·성공 횟수·마지막 이벤트 한 개·커밋 가시성만 기록한다. 기존 no-op client와
리스너의 @Async/@Retryable/@TransactionalEventListener를 그대로 사용한다.
일반 앱은 worker source set/설정을 포함하지 않는다.

외부 트랜잭션 안에서 기존 createProgram을 호출하고 커밋 전에 stub 호출이 0임을
확인한다. ROLLBACK은 동일 트랜잭션을 rollback-only로 표시한다. 트랜잭션 완료 후
해당 executor를 shutdown하고 최대 10초 동안 queue의 작업 종료를 기다려 최종
호출 횟수를 읽는다. 임의의 짧은 sleep만으로 미호출을 판정하지 않는다.
각 callback은 별도 스레드에서 실제 DB의 커밋된 Program을 조회하고 이벤트 내용도
대조한다. 기존 executor는 core 4/max 8/queue 100이며 fixture는 이벤트 한 개뿐이다.

재시도는 기존 최대 3 attempts(첫 시도 포함), 500ms·1,000ms backoff를 유지한다.
EXHAUSTED에서 기존 @Recover는 로그만 남기지만 worker는 stub의 성공 횟수 0을
관측해 FAILED를 기록한다. DB 커밋은 유지되며 Mixpanel은 독립적으로 성공한다.
outbox, 프로세스 crash 후 재전달, 실제 API 멱등성·전달 보장 검증은 범위 밖이다.

## 배포와 자원

```bash
./scripts/career-lab.sh test :creator-studio:creatorWorkerManifest
```

manifest: `career-lab/creator-studio/build/libs/creator-worker-manifest.json`.
[공통 이미지 계약](worker-container-isolation.md)에 따라 Java 21 JRE의
`/opt/lab/worker.jar`에 배포 jar를 넣고 전체 image ID와 jar SHA-256을 설정한다.
검증 이미지 기준은 eclipse-temurin:21-jre, mysql:8.0.39, redis:7.4-alpine이다.
requiresDocker=false는 worker 내부에 Docker client가 없다는 뜻이며 controller에는
Docker가 필요하다.

```yaml
lab:
  career:
    creator:
      enabled: true                     # 기본 false
      recover-on-startup: false          # 기본 false
      container:
        image-id: sha256:<64자리 worker image ID>
        artifact-sha256: <64자리 jar SHA-256>
        mysql-image-id: sha256:<64자리 MySQL image ID>
        redis-image-id: sha256:<64자리 Redis image ID>
```

빈 기본 image/hash를 채우고 활성화한다. 준비되지 않은 이미지·미해결 이전 실행은
새 접수를 차단한다. `/app/experiments/career.creator-studio.session-rotation` 등에서
preset을 선택하고 Run 상세의 CHECK·worker 환경·REPORT 참조·확장 내보내기를 확인한다.

CreatorContainerResources는 기존 CareerContainerResources 계약을 사용한다.
namespace는 creator-studio-redis/mysql, alias는 creator-db다. MySQL은 creator_lab과
실행별 임시 암호를 사용한다. worker 256MiB/heap 128MiB, MySQL 512MiB/data tmpfs
256MiB, Redis 256MiB/maxmemory 32MiB(noeviction)/data tmpfs 1MiB의 기존 경계를
유지한다. worker socket·외부 포트가 없고 Redis RDB/AOF는 비활성이다.
worker 제거 확인 → 의존 컨테이너 → 비어 있는 network 순서로 정리한다. 자원 ID·
소유권·engine·profile 불일치나 미확인 생성/시작은 운영자 확인 상태로 유지한다.
시작 시 복구는 opt-in이며 기존 Run을 다시 실행하거나 미완료 증거를 성공 처리하지 않는다.

`creator-report.json`은 중앙 사본으로 보존한다. 현재 화면과 export에는 hash/크기/참조를
제공하며 D6 관리 REPORT 다운로드를 지원한다. 실패 Run도 확인된 증거를 조회할 수 있다.

## 검증 재현

두 Gradle wrapper는 공유 worker-support 산출물 때문에 순차 실행한다.

```bash
./scripts/career-lab.sh test :creator-studio:creatorWorkerManifest
./gradlew test creatorContainerTest
```

creatorContainerTest는 실제 Java 21/Redis/MySQL로 schema 거절 → 열 변형 → 부분 실패 →
CHECK/REPORT/export → 자원 정리 → 동일 저장소 재개방을 확인한다. API 응답은
`build/verification/d5-5`에 남긴다. frontend 서버와 [Node 버전](../frontend/README.md)을
준비한 뒤:

```bash
cd frontend
npm test
npm run build
CREATOR_EVIDENCE=../build/verification/d5-5 npm run test:e2e -- creator.spec.ts
```

브라우저는 실제 API snapshot 열 개와 preset 폼 세 개를 검사한다. 브라우저가 직접
실제 Docker 실행을 접수하는 검증과 구분한다. CREATOR_EVIDENCE를 생략하면 snapshot
검증은 명시적으로 건너뛴다.

검증 결과 (2026-10-15): Career 전체 150개(변경 없는 task 성공 결과 재사용 포함),
루트 `./gradlew test` 379개, 실제 `creatorContainerTest` 10개가 실패·건너뜀 없이
통과했다. frontend unit 17개와 typecheck/build, 실제 API snapshot 10개·preset 3개·
기존 Career 화면 회귀 4개의 브라우저 검증(총 17개)도 통과했다. 브라우저 명령은
`DASHBOARD_URL=http://127.0.0.1:5195 CREATOR_EVIDENCE=../build/verification/d5-5 npm run test:e2e -- creator.spec.ts career.spec.ts --grep-invert 'real Career|stored Career'`다.
별도 실제 Career 서버가 필요한 기존 두 테스트는 이 명령의 선택 범위에서 제외했다.
EXHAUSTED 데스크톱과 TRANSFER 모바일 캡처를 확인했고 페이지 가로 넘침 없이
검증 표를 표시한다(모바일 표 내부 가로 스크롤). 컨테이너 테스트는 모든 변형의
worker·의존 DB/Redis·network 제거와 저장소 재개방을 검증했고, 종료 후 조회에서도
시험 자원·임시 이미지 태그가 남지 않았다.

D6의 현재 동작·제한·운영 명령은 [통합 운영 가이드](dashboard-operations.md)를 따른다.
