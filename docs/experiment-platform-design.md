# Experiment Platform v2 설계

## 목적과 근거

Engineering Lab은 문제 재현 → 가설 → 통제된 실행 → 관측 → 비교 → 해석의 증거를 보존하는 개인 실험 플랫폼으로 발전한다. 사용자가 제공한 `engineering-lab-plan.md`를 방향 제안으로 검토하고 실제 코드에 맞게 조정했다. 문서 속 실험 수치·결론은 측정 결과로 취급하지 않는다.

현재 저장소는 Java 25 / Spring Boot 4 단일 Gradle 프로젝트이며 `career-lab`, `engineering-experiments` 하위 프로젝트는 없다. 공통 Experiment/Scenario/Result/MetricPoint 계약, 세 실험군, 작업 API와 비교 UI는 이미 존재한다. 당장은 패키지 구조와 기존 URL을 유지하고, 실행 데이터의 재현성과 영속성을 먼저 높인다.

## 도메인과 경계

| 개념 | 역할 | 도입 방법 |
| --- | --- | --- |
| Experiment | 실험 종류, 제목, 분류, 실행 코드 | 기존 `Experiment` 유지; registry에서 메타데이터 확장 |
| Scenario | 버전 관리되는 입력과 가설 | JSON/YAML v1 정의·가설·측정 조건·canonical SHA-256을 검증하고 parameters와 함께 보존 |
| Run | 특정 조건에서 발생한 한 번의 실행 | `runId`는 기존 `jobId`와 동일; `RunMetadata`와 `ExperimentResult`를 연결 |
| Observation | 실행에 속한 시계열 관측 | 기존 `MetricPoint` 유지, 저장 시 runId와 sequence를 부여 |
| Artifact | JFR·로그·입력·결과 파일에 대한 참조 | 경로, 미디어 타입, 바이트 수, checksum 저장; 실행 결과와 별도 관리 |
| Finding | 관측을 해석한 사람이 작성한 결론 | Markdown에 실행 ID, 사실, 가설, 해석, 한계를 분리 |

실험 코드는 core 계약에만 의존한다. HTTP, JDBC 영속 저장, 파일 경로, telemetry 라이브러리는 어댑터에서 처리한다. `core`에 Spring이나 DB 의존성을 추가하지 않는다. 물리적인 Gradle 모듈 분리는 경계가 안정된 뒤 결정한다.

## 첫 구현: 실행 메타데이터

`RunMetadata(runId, parameters, environment, git)`는 제출 전에 캡처한 불변 스냅샷이다. `RunEnvironment`에는 Java 버전·벤더·VM, OS 이름·버전·아키텍처, JVM이 보는 CPU 수, 최대 heap을 기록한다. CPU 모델·물리 RAM과 혼동하지 않는다. 전체 환경 변수, 시스템 프로퍼티, JVM 인자에는 비밀값이 있을 수 있으므로 일괄 수집하지 않는다.

`ExperimentRunner.run(experiment, scenario)`는 새 UUID를 만들고, 앱 서비스는 기존 jobId를 사용한다. `DurableExperimentRunner.prepare`가 제출 metadata를 먼저 저장하고 executor에서 `runPrepared`가 실행한다. 기존 API의 `runMetadata`는 대기/실패 상태에서도 조회된다.

현재 Git SHA/dirty/수집 오류와 Excel 입력 SHA-256·파일명·queue/worker/chunk/timeout 설정이 기록된다. 원본 workbook과 외부 서비스의 버전은 자동 보관하지 않는다.

## 영속 RunStore — 구현됨

별도 H2 file DB를 사용하며 ingestion용 in-memory DB와 분리한다. core의 RunStore 계약 뒤에 JDBC 어댑터를 두고 Spring/DB 의존성을 core에 추가하지 않는다. 실행 경로는 app → DurableExperimentRunner → Experiment이며, 관측은 bounded writer → RunStore로 전달한다.

구현된 v1 스키마:

- `lab_schema`: 저장소 schema version. 지원하지 않는 버전은 시작을 거부한다.
- `lab_run`: 단조 증가 seq, run_id PK, experiment_type, scenario_id, status, created/started/completed_at, metadata_json, summary_json, failure_message. parameters/environment/git은 metadata JSON에 함께 저장한다.
- `lab_metric`: (run_id, seq) PK/FK, point_json. 기존 metric 이름과 timestamp/gauges/counters/labels를 유지한다.
- `lab_artifact`: artifact_id PK, run_id FK, relative_path, media_type, byte_size, sha256. 파일 등록·조회 기능은 후속 단계에서 연결한다.

상태는 QUEUED → RUNNING → SUCCEEDED/FAILED이며 재시작 시 남은 실행은 INTERRUPTED로 복구한다. 메트릭은 bounded queue와 size/time/종료 flush, bounded retry/backoff를 사용하고 동일 sequence로 재전송해 중복을 막는다. writer의 실패를 전파하며 durable 완료 이전에는 성공을 기록하지 않는다. 원본 메트릭 전체는 DB에 저장하고 runner/기존 API의 preview만 제한한다.

설정 기본값, API cursor, 오류·종료·소유 잠금과 백업 정책은 [저장 가이드](run-storage.md)에 정의한다. 신규 제출을 막고 제한된 시간 동안 executor와 writer를 drain한다. 강제 종료나 timeout 후 미완료 실행의 성공을 보장하지 않는다. DB 자체가 접근 불가능하면 오류를 노출하고 재시작 복구를 적용한다.

Git probe는 timeout/출력 제한이 있다. 없는 Git·저장소나 probe 실패는 null과 이유를 남기고 clean으로 위장하지 않는다. 파일 export가 도입되면 임시 파일/원자적 이동과 관리 root 경계를 적용한다.

## API와 이행

현 `/experiments/*`, `/ingest/*`는 호환 어댑터로 유지한다. registry 조회와 공통 `/runs` 생성·조회는 구현되었고 artifact API는 후속 단계다.

- `GET /experiments`, `GET /experiments/{id}`: 등록된 실험의 설명·파라미터 schema·input 종류·지원 측정 모드. Git 관리 시나리오 파일은 CLI에서 선택한다.
- `POST /runs`: 엄격히 검증된 JSON/YAML 정의로 실행 생성. Excel은 multipart scenario/file과 input hash가 필요하다. [CLI와 정의 v1](scenarios.md)을 참조한다.
- `GET /runs?experimentType=...&limit=...&before=...`: 구현된 실행 페이지 조회.
- `GET /runs/{runId}`, `/runs/{runId}/metrics?after=...&limit=...`: 구현된 실행·메트릭 조회. `/runs/{runId}/artifacts`는 아직 미구현.
- 비교는 metric 단위·환경·입력 차이를 표시하고 서로 다른 조건의 숫자를 동일 조건인 것처럼 보여주지 않는다.

## 관측과 실험 방법

Metrics → JVM metrics → trace → JFR → logs 순으로 도입한다. 먼저 명시적 instrumentation을 제공하며, Java Agent는 별도 실험 주제로 남긴다. JFR/외부 인프라 실행은 명시적으로 선택하도록 한다.

시나리오 파일은 허용된 실험 ID와 명시적 파라미터 스키마로 검증하고 임의 클래스·명령 실행을 허용하지 않는다. warmup, 반복 횟수, 측정 기간, 시간 단위, seed와 입력 checksum을 기록한다. 기존 실험이 지원하지 않는 옵션은 무시하지 않고 거부한다.

예시 문서의 virtual-thread pinning 결론은 대상 JDK와 JFR 등 실제 관측으로 검증해야 하는 가설이다. 성능 개선도 실제 비교 실험 전에는 사실로 서술하지 않는다. Finding은 observed fact / reconstructed condition / hypothesis / new design / measured result를 구분한다.

## 검증 기준

첫 단계: ID 연결, 파라미터 snapshot 불변성, 실패 실행의 metadata 보존, 실제 JVM 정보, 기존 HTTP 응답 호환성, 전체 테스트 통과.

영속화 단계: 재시작 후 조회, 비정상 종료 복구, queue blocking, 크기·시간·종료 flush, 재시도 중 중복 방지, 저장 실패 노출, 페이지 제한, bounded memory 및 baseline 비교를 각각 테스트한다. 기존 pipeline retry/dead-letter 등 미구현 항목은 `docs/codex.md`를 참고하고 플랫폼 기능 완성과 혼동하지 않는다.
