# Career sequence 중앙 API 연결

첫 Career 실험 `career.mcp-platform.sequence`를 별도 Java 21 worker로 실행하고,
Java 25 플랫폼의 기존 Run 저장·조회·비교 API에 결과를 보관한다. 기본값은 비활성이다.
독립 대시보드의 실행·검증 결과 화면을 지원한다. [화면 사용법](standalone-dashboard.md#career-sequence-실행과-검증-결과)을 참고한다. 오프라인 CLI의 Career 검증은 아직 연결하지 않았다.
기존 `/runs/ui`와 Run 조회 API에서 접수된 실행을 조회할 수 있다.

## 빌드와 검증

Java 25/21과 실행 중인 Docker가 필요하다. 두 wrapper는 worker-support 산출물을 공유하므로
명령을 순서대로 실행한다. 실제 worker 빌드는 중앙 앱 빌드에 자동 포함되지 않는다.

```bash
./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest
./gradlew test
./gradlew careerSequenceTest
```

마지막 명령은 별도 `src/careerTest` 통합 검증이다. 실제 Spring 등록과 HTTP 접수 경로로
세 variant를 실행하고, worker/controller 환경 분리·CHECK·보고서·비교 단위와 DB 재조회를
확인한다. 실제 DB 배포 테스트는 느린 MySQL 초기화를 포함해 실행당 최대 5분을 허용한다. 동시 채번 자체의 30초 제한과 중단 후 정리 제한은 바꾸지 않는다. 기본 `test`는 Docker 없이 등록·거절·probe와 기존 플랫폼을 검증한다.

## 서버 설정

서버 관리자가 신뢰하는 빌드만 등록한다. 생성된 descriptor와 jar를 같은 디렉터리에 둔다.
아래 Java 경로를 설치된 JDK 21 실행 파일의 **절대 경로**로 바꾼다.

```bash
LAB_CAREER_SEQUENCE_ENABLED=true \
LAB_CAREER_SEQUENCE_MANIFEST="$PWD/career-lab/mcp-platform/build/libs/sequence-worker-manifest.json" \
LAB_CAREER_SEQUENCE_JAVA_EXECUTABLE="/absolute/jdk21/bin/java" \
./gradlew bootRun
```

| 설정 | 기본값 / 계약 |
| --- | --- |
| `lab.career.sequence.recover-on-startup` | `false`; true이면 종료 확인 기록이 있는 sequence만 시작 시 한 번 재조정하며, 성공 시 접수 차단 해제 |
| `lab.career.sequence.enabled` | `false`; true일 때만 모듈·실험·실행기를 등록 |
| `lab.career.sequence.manifest` | 빈 문자열; 활성화 시 관리자 소유 descriptor 파일 필요 |
| `lab.career.sequence.java-executable` | 빈 문자열; 활성화 시 Java 21 절대 경로 필요 |

descriptor는 16 KiB 이하이며 schema/protocol 1, Java 21, mcp-platform, 실험 ID,
heap 256 MiB, Docker 요구와 단일 jar 파일명·SHA-256을 검증한다. descriptor 누락/잘못된 형식은
서버 시작 실패다. 파일명은 같은 디렉터리의 jar만 허용한다. HTTP 입력으로 실행 파일·jar·heap·명령을
변경할 수 없다. 교체 배포는 worker와 descriptor를 함께 배포하고 서버를 재시작한다.

## 준비 상태와 접수

`GET /dashboard/v1/modules`와 `/dashboard/v1/experiments/career.mcp-platform.sequence`가
준비 상태를 제공한다. `READY`, `NOT_BUILT`(jar 없음), `INCOMPATIBLE`(hash/Java/protocol 오류),
`DOCKER_UNAVAILABLE`을 구분한다. 모듈 응답의 reason은 경로·인증정보가 없는 진단 코드다.

probe는 검증한 jar 사본을 동일 Java/Docker 환경에서 `--probe`로 실행한다. stdout은 최대
4 KiB, 프로세스 응답 대기는 최대 60초, 결과 캐시는 5초다. 최초 조회/접수는 Docker 준비 때문에
지연될 수 있다. probe 종료/입출력 정리가 확인되지 않으면 추가 probe를 중단하고 `PROBE_CLEANUP_UNCONFIRMED`를 보고한다. 관리자가 원인을 확인하고 앱을 재시작해야 한다. probe는 MySQL 실험 DB를 만들지 않지만 Testcontainers가 Ryuk을 시작할 수 있다.
`TESTCONTAINERS_RYUK_DISABLED=true`이면 준비되지 않은 상태로 보고한다.

준비되지 않은 worker의 새 접수는 HTTP 503이며 Run을 만들지 않는다. 준비 상태는 시점 관측이므로
접수 직후 Docker가 중단되면 기록된 Run은 FAILED가 될 수 있다. 실제 실행 때 jar hash를 다시
검증한다. worker는 배타 실행으로 한 번에 하나만 허용하며, 다른 실행과 충돌하면 접수된 Run을
기존 admission 정책에 따라 실패 처리한다. 무제한 대기 큐를 추가하지 않는다.

## 실행과 결과

```bash
curl -H 'Content-Type: application/json' \
  --data-binary @experiments/career-sequence/scenarios/atomic-update.json \
  http://localhost:8080/runs
```

variant는 `BEFORE`, `ATOMIC_UPDATE`, `SELECT_FOR_UPDATE`, workers는 2–16,
blockSize는 1–10,000이다. 응답의 runId로 다음 API를 조회한다.

- `/runs/{runId}`: 상태·요약·서버 환경
- `/dashboard/v1/runs/{runId}/execution`: 실제 Java 21 worker 환경·배포 hash·증거 완료 상태
- `/dashboard/v1/runs/{runId}/checks`: 겹침·구간 검증
- `/runs/{runId}/metrics`, `/runs/{runId}/artifacts`: 메트릭과 검증된 중앙 보고서 참조
- `/runs/compare?baseline={beforeId}&candidate={afterId}`: 측정 조건·실행 환경·값 비교

sequence 메트릭은 모듈이 단위와 의미를 선언하며 `/metrics/schema`와 비교 API에 반영한다.
BEFORE의 경합 재현용 50ms 지연 때문에 elapsedNanos를 업무 성능 개선율로 해석하지 않는다.
worker의 JFR/trace/일반 로그/JVM 관측 capability는 false다. controller 관측을 worker 측정값으로
대신 표시하지 않는다. 보고서 다운로드 화면, 저장 용량 quota, 작업 폴더 자동 정리는 후속 범위다.

## 복구 범위

정상 정리와 부모 연결 종료/deadline/강제 종료의 MySQL 회수는
[worker 장애 처리](../career-lab/mcp-platform/README.md#중단과-장애-처리)를 따른다.
등록 worker는 실행 전 영속 기록을 만들고 성공·정리 확인 후 제거한다. 실행 중이거나 실패·강제 종료 후 기록이 남으면 `INCOMPATIBLE` / `WORKER_ACTIVE_OR_RECOVERY_REQUIRED`로 새 접수를 차단한다. [복구 기록과 운영자 해제 절차](worker-recovery.md)를 따른다. Docker/Ryuk 자체 장애, 호스트 재시작 이후 잔여물 자동 조정과 일반 자손 프로세스 정리는 남아 있다.
Run 저장소의 기존 재시작 복구는 중단된 Run을 실패로 처리하며 성공으로 추정하지 않는다.
EOF/exit/증거 저장이 모두 확인되어야 성공 증거가 완료된다. 과거 Run 조회에는 worker 실행이 필요 없다.

새 sequence worker는 컨테이너 생성 전에 소유권 기록과 배타 잠금을 만든다. controller 정지 후 같은 배포물의 `--recover <workspace> <runId>`로 해당 컨테이너를 회수할 수 있다. [복구 명령·검사·한계](sequence-container-recovery.md)를 따르며 접수 차단 해제는 별도 운영 절차다.

시작 시 복구는 `LAB_CAREER_SEQUENCE_RECOVER_ON_STARTUP=true`로 켠다. RunStore 배타 잠금과
중단 Run 복원 이후 실행하므로 복구 중 sequence 접수는 열리지 않는다. 완료 기록 없는 강제 종료는
자동 해제하지 않는다. [대상 조건·시간/출력 한도·결과 코드](sequence-container-recovery.md#종료-확인-기록이-있는-실행의-시작-시-복구)를 따른다.

Docker 통신 장애만 재현하려면 `./gradlew careerSequenceTest --tests '*SequenceRecoveryTransportTest'`를 사용한다.
실제 배포물과 테스트 전용 Docker API를 연결하며 공유 엔진을 중단하지 않는다.
[장애별 검증 범위](sequence-container-recovery.md#docker-통신-장애-회귀-검증)를 확인한다.

실제 엔진 강제 종료·재시작은 승인된 환경에서 별도 `careerDaemonRecoveryTest`로 검증한다.
[전용 privileged 엔진의 실행 옵션·검증 범위·정리 확인](sequence-container-recovery.md#독립-docker-엔진-재시작-검증)을 따른다.


## CONTAINER 실행과 시작 시 복구 (I5)

기본값은 `lab.career.sequence.enabled=false`, 활성화했을 때의 실행 방식은 `LOCAL_JVM`,
`recover-on-startup=false`다. 명시적으로 `execution-kind=CONTAINER`를 선택하면 controller가
worker·MySQL·internal network를 소유한다. UI/API의 실행 종류도 CONTAINER가 되며
worker 환경·부분 증거·준비 상태 차단은 기존 대시보드에서 표시한다. 시나리오 입력으로
실행 방식·이미지·네트워크·접속 정보를 바꾸지 못한다.

먼저 Career sequence manifest/jar를 만든 뒤 Java 21 JRE, `sh`, `sha256sum`, `tar`, `sleep`을
포함하고 `/opt/lab/worker.jar`에 그 jar가 들어 있는 신뢰한 이미지를 준비한다. 이미지 내부 jar의
SHA-256은 manifest의 `artifactSha256`과 일치해야 한다. MySQL 8.0.39 이미지를 로컬에
준비하고 각 `docker image inspect <이미지> --format '{{.Id}}'`의 전체 ID를 아래 값에 사용한다.
자동 pull은 하지 않는다. 이미지 tag가 아닌 `sha256:` 전체 ID가 필수다.

```bash
./gradlew bootRun --args='--lab.career.sequence.enabled=true --lab.career.sequence.execution-kind=CONTAINER --lab.career.sequence.container.image-id=sha256:<worker-image-id-64hex> --lab.career.sequence.container.artifact-sha256=<jar-sha256-64hex> --lab.career.sequence.container.mysql-image-id=sha256:<mysql-image-id-64hex> --lab.career.sequence.recover-on-startup=true'
```

예시의 placeholder를 실제 값으로 교체한다. 세 container 설정의 기본값은 빈 문자열이며
CONTAINER 선택 시 유효한 값이 없으면 시작을 거절한다. LOCAL_JVM의 manifest/java-executable
설정은 그대로 유지하고 CONTAINER 모드에서는 사용하지 않는다. readiness는 고정 이미지의
로컬 존재/엔진 응답을 확인하며 실제 jar hash·Java 버전·DB 접속 검증은 실행 경계 안에서 한다.

같은 storage root와 `worker-workspaces`를 유지해 재시작한다. opt-out이면 미복구 v2 marker가
접수를 막고, opt-in이면 중단 Run 복원 후 worker→DB→network를 검증·회수한다. 모두 확인된
v2 실행만 접수를 재개하며 이전 Run을 성공 처리하거나 재실행하지 않는다. v1 LOCAL_JVM
기록을 v2로 바꾸지 않는다. Docker 불가·기록 손상·배포 변경·생성/시작 미확인은 접수 차단을
유지한다. 기존 배포 설정과 기록을 보존해 조사하고 [I5 복구 계약](worker-container-isolation.md#i5-같은-저장소에서-시작-시-복구와-접수-재개)을 따른다.

```bash
./scripts/career-lab.sh :mcp-platform:sequenceWorkerManifest
./gradlew test sequenceContainerTest containerWorkerTest workerContainerIsolationTest careerSequenceTest
```

두 Gradle wrapper는 순차 실행한다. Docker 검증은 공유 daemon을 재시작하지 않으며
privileged 권한을 사용하지 않는다.
