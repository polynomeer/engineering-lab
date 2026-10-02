# Experiment Platform v2 설계

## 목적과 근거

Engineering Lab은 문제 재현 → 가설 → 통제된 실행 → 관측 → 비교 → 해석의 증거를 보존하는 개인 실험 플랫폼으로 발전한다. 사용자가 제공한 `engineering-lab-plan.md`를 방향 제안으로 검토하고 실제 코드에 맞게 조정했다. 문서 속 실험 수치·결론은 측정 결과로 취급하지 않는다.

현재 저장소는 Java 25 / Spring Boot 4 단일 Gradle 프로젝트이며 `career-lab`, `engineering-experiments` 하위 프로젝트는 없다. 공통 Experiment/Scenario/Result/MetricPoint 계약, 세 실험군, 작업 API와 비교 UI는 이미 존재한다. 당장은 패키지 구조와 기존 URL을 유지하고, 실행 데이터의 재현성과 영속성을 먼저 높인다.

## 도메인과 경계

| 개념 | 역할 | 도입 방법 |
| --- | --- | --- |
| Experiment | 실험 종류, 제목, 분류, 실행 코드 | 기존 `Experiment` 유지; registry에서 메타데이터 확장 |
| Scenario | 버전 관리되는 입력과 가설 | 기존 `ExperimentScenario.parameters()`를 실행 시 복사; 추후 파일 정의와 해시 추가 |
| Run | 특정 조건에서 발생한 한 번의 실행 | `runId`는 기존 `jobId`와 동일; `RunMetadata`와 `ExperimentResult`를 연결 |
| Observation | 실행에 속한 시계열 관측 | 기존 `MetricPoint` 유지, 저장 시 runId와 sequence를 부여 |
| Artifact | JFR·로그·입력·결과 파일에 대한 참조 | 경로, 미디어 타입, 바이트 수, checksum 저장; 실행 결과와 별도 관리 |
| Finding | 관측을 해석한 사람이 작성한 결론 | Markdown에 실행 ID, 사실, 가설, 해석, 한계를 분리 |

실험 코드는 core 계약에만 의존한다. HTTP, JDBC 영속 저장, 파일 경로, telemetry 라이브러리는 어댑터에서 처리한다. `core`에 Spring이나 DB 의존성을 추가하지 않는다. 물리적인 Gradle 모듈 분리는 경계가 안정된 뒤 결정한다.

## 첫 구현: 실행 메타데이터

`RunMetadata(runId, parameters, environment)`는 실행 직전에 캡처한 불변 스냅샷이다. `RunEnvironment`에는 Java 버전·벤더·VM, OS 이름·버전·아키텍처, JVM이 보는 CPU 수, 최대 heap을 기록한다. CPU 모델·물리 RAM과 혼동하지 않는다. 전체 환경 변수, 시스템 프로퍼티, JVM 인자에는 비밀값이 있을 수 있으므로 일괄 수집하지 않는다.

`ExperimentRunner.run(experiment, scenario)`는 새 UUID를 만들고, 작업 서비스는 기존 jobId를 넘기는 overload를 사용한다. 성공과 실패 결과 모두 동일한 메타데이터를 보존한다. generic job 및 ingestion 응답에 `runMetadata`를 추가한다. 기존 필드와 응답 형식은 유지하며, 실행 결과가 아직 없는 동안은 null이다. 제출·스케줄링 실패까지 메타데이터를 저장하는 lifecycle은 영속 RunStore 단계에서 처리한다.

이 스냅샷만으로 완전한 재현성을 보장하지 않는다. Git SHA/dirty 여부, 실행 설정 전체, 입력 checksum, 외부 서비스 버전은 다음 단계에서 추가한다. 특히 Excel 시나리오의 현 parameters는 mode와 payload 크기만 포함한다.

## 다음 구현: 영속 RunStore

첫 저장 어댑터는 현재 JDBC 기술과 호환되는 별도 H2 file DB를 사용한다. ingestion 실험 DB와 분리하여 실험 데이터 초기화가 실행 증거를 지우지 않게 한다. SQLite/PostgreSQL 이식은 `RunStore` 경계 뒤에서 가능하도록 한다. 새 DB 드라이버나 서버 운영을 첫 단계의 전제로 두지 않는다.

예정 스키마(아직 생성하지 않음):

- `lab_run`: run_id PK, experiment_type, scenario_id, schema_version, status, created/started/completed_at, parameters_json, environment_json, git_commit(nullable), git_dirty(nullable), provenance_error(nullable), summary_json, failure_message.
- `lab_metric`: (run_id, sequence) PK/FK, timestamp, gauges_json, counters_json, labels_json. 기존 metric 이름을 임의로 변경하지 않는다.
- `lab_artifact`: artifact_id PK, run_id FK, relative_path, media_type, byte_size, sha256.

`RunStore`는 실행 생성, 상태 전이, bounded metric batch append, 완료 결과 저장, 단건 조회, 페이지 조회를 제공한다. 메트릭 재시도는 `(run_id, sequence)`로 중복 쓰기를 방지한다. 제한된 queue와 size/time flush, bounded retry/backoff, 배압·저장 실패 메트릭을 갖춘 writer를 사용한다. 저장 실패를 성공 실행으로 숨기지 않고 사용자에게 구분해서 보고한다. 무제한 timeline을 저장 완료까지 메모리에 쌓는 방식은 금지한다.

프로세스 재시작 시 남은 실행은 별도 `INTERRUPTED` 상태로 전환하고 자동 재실행하지 않는다. 현재 `ExperimentStatus`/기존 UI와의 호환 매핑을 이 단계에서 함께 추가한다. 종료 시 새 제출을 막고 제한된 시간 동안 queue drain/flush 후 남은 실패를 기록한다.

설정은 `lab.storage.*`에 root, metric queue capacity, batch size, flush interval, retry attempts/backoff, shutdown timeout, query page limit을 정의하고 기본값·문서를 함께 추가한다. `.lab/`은 로컬 실행 데이터로 ignore하며 이 경로가 실제 도입될 때 설정한다. 파일 export는 임시 파일 작성 후 원자적 이동으로 완료 상태를 표시한다. artifact 경로는 관리 root 밖으로 나갈 수 없다.

Git 정보는 명령 timeout과 출력 제한을 갖춘 로컬 provider로 캡처한다. Git이 없는 배포 환경은 null과 이유를 남긴다. dirty 상태는 tracked 및 untracked 변경을 포함한다. 알 수 없는 상태를 clean으로 기록하지 않는다. Git 정보 수집 실패가 실험 자체를 중단시키지는 않는다.

## API와 이행

현 `/experiments/*`, `/ingest/*`는 호환 어댑터로 유지한다. 다음 API는 영속화·registry 단계에서 추가할 예정이며 아직 사용할 수 없다.

- `GET /experiments`: 등록된 실험과 지원 시나리오.
- `POST /runs`: 실험 ID와 검증된 시나리오로 실행 생성.
- `GET /runs?experimentType=...&limit=...&cursor=...`: 제한된 페이지 조회.
- `GET /runs/{runId}`, `/runs/{runId}/metrics`, `/runs/{runId}/artifacts`: 실행과 페이지 단위 증거 조회.
- 비교는 metric 단위·환경·입력 차이를 표시하고 서로 다른 조건의 숫자를 동일 조건인 것처럼 보여주지 않는다.

## 관측과 실험 방법

Metrics → JVM metrics → trace → JFR → logs 순으로 도입한다. 먼저 명시적 instrumentation을 제공하며, Java Agent는 별도 실험 주제로 남긴다. JFR/외부 인프라 실행은 명시적으로 선택하도록 한다.

시나리오 파일은 허용된 실험 ID와 명시적 파라미터 스키마로 검증하고 임의 클래스·명령 실행을 허용하지 않는다. warmup, 반복 횟수, 측정 기간, 시간 단위, seed와 입력 checksum을 기록한다. 기존 실험이 지원하지 않는 옵션은 무시하지 않고 거부한다.

예시 문서의 virtual-thread pinning 결론은 대상 JDK와 JFR 등 실제 관측으로 검증해야 하는 가설이다. 성능 개선도 실제 비교 실험 전에는 사실로 서술하지 않는다. Finding은 observed fact / reconstructed condition / hypothesis / new design / measured result를 구분한다.

## 검증 기준

첫 단계: ID 연결, 파라미터 snapshot 불변성, 실패 실행의 metadata 보존, 실제 JVM 정보, 기존 HTTP 응답 호환성, 전체 테스트 통과.

영속화 단계: 재시작 후 조회, 비정상 종료 복구, queue blocking, 크기·시간·종료 flush, 재시도 중 중복 방지, 저장 실패 노출, 페이지 제한, bounded memory 및 baseline 비교를 각각 테스트한다. 기존 pipeline retry/dead-letter 등 미구현 항목은 `docs/codex.md`를 참고하고 플랫폼 기능 완성과 혼동하지 않는다.
