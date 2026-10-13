# Codex repository guide

이 문서는 코드 위치와 작업 검증을 위한 안내다. 작업·커밋 규칙의 기준은 루트 [AGENTS.md](../AGENTS.md)이며, 작업에 필요한 부분만 읽는다.

## 현재 구조

루트 플랫폼은 Java 25, Spring Boot 4.0.3, Gradle Wrapper 9.3.1을 사용하는 단일 Gradle 프로젝트다. 데이터 접근은 Spring JDBC, 실행 DB는 H2, XLSX 파싱은 Apache POI 기반이다. `settings.gradle`의 이름은 아직 `excel-pipeline`이고, 앱 이름은 `engineering-lab`이다.

`career-lab/`에는 Java 21 / Spring Boot 3.3.4 / Gradle 8.14.2 기반의 독립 다중 프로젝트가 있으며, `com.portfolio.*` 패키지와 원본 빌드 경계를 유지한다. [Career Lab 안내](../career-lab/README.md)를 읽고 Docker를 실행한 뒤 `./scripts/career-lab.sh test`로 다섯 모듈을 검증한다. 루트 테스트는 이 모듈들을 포함하지 않는다.

아래 Java 경로는 `src/main/java/` 기준이며, 테스트는 `src/test/java/`의 대응 패키지에 있다.

| 경로 | 책임 / 수정 시 확인할 대상 |
| --- | --- |
| `com/pnomeer/pipeline/ExcelPipelineApplication.java` | Spring Boot 진입점, `com.pnomeer` 컴포넌트 스캔 |
| `com/pnomeer/lab/core/` | `Experiment`, `ExperimentScenario`, 결과·상태·메트릭 계약, `RunMetadata`/`RunEnvironment`, `LocalExperimentRunner`, 명시적 `TraceRecorder` |
| `com/pnomeer/lab/metrics/` | 공용 실행 메트릭 스냅샷, `MetricCatalog` 단위·범위·의미, `JvmSnapshot` |
| `com/pnomeer/lab/runtime/` | 영속 runner, `ExperimentExecutor`/`InProcessExecutor`, `ExperimentExecutors` 선택·`ExecutionAdmission` 제한, 제출/종료 lifecycle, Git provenance, JVM/trace/JFR/log 설정과 `JfrRecorder`·`RunLogRecorder` artifact 수집 |
| `com/pnomeer/lab/storage/` | H2 RunStore, bounded writer, 설정·소유권·스키마 검사 |
| `com/pnomeer/lab/app/run/` | registry 조회, 공통 시나리오 제출, `/runs` 조회·비교·export·artifact 참조·검증 다운로드와 오류 응답 |
| `com/pnomeer/lab/registry/`, `scenario/`, `cli/` | typed registry, JSON/YAML 정의 검증, HTTP CLI |
| `com/pnomeer/lab/experiments/pipeline/` | `PipelineRunner`, 파이프라인 어댑터, 설정, 스트리밍 파서, bounded channel, 종료 신호 |
| `com/pnomeer/lab/experiments/concurrency/` | 큐 경합 실험과 시나리오 |
| `com/pnomeer/lab/experiments/io/` | IO 비교 실험, blocking / virtual-thread / selector 서버, 부하 클라이언트 |
| `com/pnomeer/lab/app/ingest/` | `/ingest/*`, 비동기 ingestion 작업 상태, 메트릭 변환, 대시보드 |
| `com/pnomeer/lab/app/experiment/` | `/experiments/*`, 공통 실험 작업 API, 비교·프리셋·이력 UI |
| `com/pnomeer/lab/app/io/` | `/lab/io/*`, IO 데모 API·UI, virtual-thread executor |
| `com/pnomeer/lab/app/HomeController.java` | `/`, `/ui` 홈 |
| `com/pnomeer/pipeline/tools/` | 테스트 XLSX 생성 도구 |

- 기존 UI의 HTML/CSS/JavaScript는 컨트롤러의 Java text block에 있다. `/runs/ui`는 `RunUiController`와 `src/main/resources/static/run-browser/`의 HTML/CSS/JS를 사용한다. 기존 화면 변경 시 해당 컨트롤러와 테스트를 함께 확인한다. `frontend/`에는 별도 React/TypeScript/Vite Run 조회 화면이 있으며 [frontend 안내](../frontend/README.md)의 Node 버전과 검증 명령을 따른다. Java 문자열 escaping과 브라우저 동작을 모두 고려한다.
- `src/main/resources/application.yml`: 런타임 기본값. 프로퍼티 변경 시 대응 properties와 README/운영 문서를 함께 갱신한다. 시나리오 schema/범위는 각 실험의 `*ExperimentModule`과 [시나리오 가이드](scenarios.md)를 함께 수정한다.
- ingestion DB 스키마 변경 시 `src/main/resources/schema.sql`과 `src/test/resources/schema.sql`을 함께 확인한다. 실행 evidence 스키마는 `src/main/resources/lab/schema-v1.sql`과 additive `schema-v2.sql`/`schema-v3.sql`이며 v1/v2→v3 이행 전에 DB ZIP 백업을 만든다. [저장 가이드](run-storage.md)의 복원 절차를 따른다. 추가 변경도 명시적인 버전 전환이 필요하다.
- `docs/architecture.md`, `docs/implementation-plan.md`, `docs/refactor-sequence.md`는 아키텍처 방향과 마이그레이션 계획이다. 계획에 나오는 모듈·기능을 구현 완료로 간주하지 않는다.

## 실험 확장

[실험 추가 가이드](adding-experiments.md)를 따른다. 모듈별 `ExperimentModule` SPI가 schema/metric을 제공하고 `*RegistrationConfiguration`이 실행 factory bean을 등록한다. 공통 registry와 CLI에 실험별 분기를 추가하지 않는다. `GET /modules`로 내장 모듈 소유 관계를 조회한다.

## 다음 구조 변경

독립 프론트엔드와 Career Lab 연동은 [설계](dashboard-integration-design.md)와 [계획](dashboard-integration-plan.md)을 따른다. 등록 확장 기반과 Career 빌드 자동 발견은 구현했고, 독립 frontend의 D1 Run 조회와 D2 큐·IO·Excel 실행 화면과 DB/IO 도구는 구현했고 브라우저 상태 이행도 구현했다. 기존 URL/배포 호환도 구현했다. [배포 가이드](dashboard-deployment.md)의 opt-in 설정을 따르며 D3의 in-process executor 경계와 서버 등록 기반 선택·중앙 실행 제한도 구현했다. 기본 앱은 IN_PROCESS만 등록하고 설정으로 Career sequence LOCAL_JVM을 추가한다. v3 execution/check 저장·페이지 조회와 자동 백업/이행은 구현했고, 앱의 자동 기록과 execution/check 조회·확장 export·비교도 연결했다. capabilities/catalog extension과 구형/신형 화면·API 세 가지 호환 조합은 검증했다. `DashboardCatalogControllerTest`가 설정 반영과 추가 모듈 발견을 검증한다. D4 첫 단위의 JSON Schema·fixture·Java 21 worker-support codec도 구현했다. manifest 기반 별도 JVM transport·deadline·직접 자식 종료·증거 저장 callback도 구현했다. 중앙 RunStore 어댑터를 실제 Java 21 fixture와 H2로 검증했다. REPORT 파일의 경로·크기·hash 검증과 중앙 사본/참조 보존도 구현했다. 첫 Career sequence 배포 jar·공용 하네스·격리 MySQL 실행/정상 정리를 구현했다. 실제 sequence worker의 중단 상태 보존·부모 EOF/deadline/강제 종료·보고서 실패·Ryuk 정리 검증도 추가했다. sequence 선택적 중앙 catalog/API 등록과 실제 배포물의 Docker readiness probe도 연결했다. [배포·검증 명령](career-worker-deployment.md)을 따른다. 독립 대시보드의 준비 상태·실행/check 패널과 worker/controller 환경 구분도 연결했다. 다음은 daemon/재시작 장애 복구 보강이다. 작업 시작 시 해당 계획의 완료 항목을 확인하고, 기존 route/API·브라우저 설정·Run 데이터 호환과 worker/controller 관측 구분을 유지한다.

통신 계약은 [worker v1](../contracts/worker/v1/README.md), Java 21 코드와 검증은 [worker-support](../career-lab/worker-support/README.md)를 따른다. 루트 통합 테스트에서 라이브러리를 실행하지만 Career sequence를 설정으로 등록했을 때만 capability의 외부 worker 지원을 켠다.

## 실행 흐름

- Excel: `IngestionController` → `IngestionService` → `DurableExperimentRunner` → `InProcessExecutor` → `PipelineExperiment` → `PipelineRunner`.
- 파이프라인 모드: 첫 시트 파싱 → bounded raw queue → 검증·매핑 worker → bounded mapped queue → insert worker별 chunk → JDBC 트랜잭션. 비교용 `SINGLE_THREAD` 모드도 있다.
- 공통 실험: `ExperimentController` → `ExperimentJobService` → `DurableExperimentRunner` → `InProcessExecutor` → 해당 실험. 메트릭과 결과를 공통 모델로 반환한다.
- 실행 metadata는 jobId, 파라미터, JVM/OS, Git provenance를 보존한다. Excel은 input SHA-256과 설정도 포함한다. 앱 runner는 `runtime/DurableExperimentRunner`, 저장소는 `storage/JdbcRunStore`다. [저장 가이드](run-storage.md)를 참조한다.
- 선언적 실행은 `POST /runs` → `ExperimentRegistry`의 typed binding → `DurableExperimentRunner`를 거친다. 정의/hash/가설도 metadata에 저장한다. Git 예제는 `experiments/*/*/scenarios/`에 있다.
- 서버 실행·메트릭·결과는 `.lab/`의 별도 H2 DB에 보관된다. 브라우저 localStorage의 UI 상태·프리셋과 별개다. core의 `LocalExperimentRunner`는 저장소 없는 단위 테스트/로컬 실행용이며 앱 bean으로 사용하지 않는다.

## 실행과 검증

저장소 루트에서 Gradle Wrapper를 사용한다. JDK 25와 공통 worker 라이브러리용 JDK 21이 필요하며, 최초 실행에는 Gradle 배포본과 Maven 의존성 다운로드가 필요하다. 로컬 경로·인증정보를 저장소 설정에 고정하지 않는다.

```bash
./gradlew test
./gradlew bootRun
```

macOS에서 JDK 선택이 필요한 경우 설치된 Java 25를 확인한 뒤 실행한다.

```bash
/usr/libexec/java_home -V
JAVA_HOME="$(/usr/libexec/java_home -v 25)" ./gradlew test
```

개발 중에는 대상 테스트를 좁힐 수 있지만, 커밋 전에는 전체 `./gradlew test`를 실행한다.

```bash
./gradlew test --tests 'com.pnomeer.lab.experiments.pipeline.*'
./gradlew test --tests 'com.pnomeer.lab.app.experiment.ExperimentControllerTest'
```

| 변경 영역 | 검증 시작점 |
| --- | --- |
| 큐 / 종료 신호 | `BoundedChannelTest`, `StageShutdownTest` |
| Excel / DB 쓰기 | `PipelineRunnerIntegrationTest`, 모델·시나리오 테스트 |
| 공통 실행 모델 | `LocalExperimentRunnerTest`, `ExperimentExecutionStateTest`, `DurableExperimentRunnerTest`, `ExperimentExecutorTest`, `ExecutorRoutingTest`, `ExecutionAdmissionTest` |
| 선언적 실행 | `ScenarioDocumentsTest`, `ScenarioApiIntegrationTest`, `LabCliIntegrationTest`, `LabCliTest` |
| 영속 저장 | `JdbcRunStoreTest`, `ExecutionStoreTest`, `RunSchemaMigrationTest`, `BoundedMetricWriterTest`, `RunRestartIntegrationTest`, `MetricWriterPerformanceTest` |
| 큐 경합 / IO | `QueueContentionExperimentTest`, `IoEndpointComparisonExperimentTest` |
| Run 로그 | `RunLogRecorderTest`, `LabCliIntegrationTest`; [관측 가이드](observability.md) |
| JFR 수집 | `JfrRecorderTest`, `LabCliIntegrationTest`; [관측 가이드](observability.md) |
| 명시적 Trace | `TraceRecorderTest`, `TraceIntegrationTest`, `LabCliIntegrationTest`; [관측 가이드](observability.md) |
| JVM 관측 | `JvmObservationTest`, `JvmPeriodicObservationTest`, `JvmSnapshotTest`, `MetricCatalogTest`; [관측 가이드](observability.md) |
| Run 증거 | `RunEvidenceTest`, `ExecutionEvidenceControllerTest`, `RecordedExecutionTest`, `LocalJvmExecutorTest`, `WorkerArtifactImporterTest`, `ArtifactDownloadTest`, `RunUiIntegrationTest`; [증거 가이드](run-evidence.md), [Finding 템플릿](findings/TEMPLATE.md) |
| API / UI | 해당 `*ControllerTest`, `ExperimentJobServiceTest`; UI 동작 변경은 브라우저에서도 확인 |

테스트 리포트는 `build/reports/tests/test/index.html`에 생성된다. UI 확인 경로는 `/runs/ui`, `/ui`, `/ingest/ui`, `/experiments/ui`, `/lab/io/ui`다. XLSX 샘플 생성은 `./scripts/create-test-xlsx.sh /tmp/sample.xlsx 500`을 사용한다. 상세 API와 IO 실행 명령은 [README](../README.md)를 참조한다.

## 구현 상태와 남은 요구사항

아래는 이 가이드를 작성하며 확인한 차이다. 관련 기능을 구현하면 이 목록도 갱신한다. 별도 요청 없이 이 목록 전체를 구현하는 것으로 작업 범위를 확대하지 않는다.

- insert chunk는 크기·시간·종료 조건으로 flush된다. 시간 기본값은 1000 ms이며 SINGLE_THREAD는 행 callback에서만 기한을 확인한다. insert는 batch 본문의 일시적 오류를 성공적으로 롤백한 경우 제한된 지수 backoff로 재시도한다(기본 총 3회, 100 ms 시작, 60000 ms 상한). 커밋·롤백 오류는 재시도하지 않는다. 별도 Run/불확실한 커밋의 재실행 멱등성은 보장하지 않는다.
- 앱 Run의 검증 오류는 evidence DB의 dead-letter로 즉시 영속화되며 종료 Run의 `/runs/{runId}/dead-letters`에서 행 문맥을 페이지 조회한다. 행당 64 KiB 초과·저장 실패는 Run 실패다. 직접/local 실행은 영속 저장 없이 제한된 미리보기와 전체 집계를 제공한다. 앱도 `pipeline.error-preview-limit`(기본 100, 0–1000)만 원본 오류 행을 보유한다.
- HTTP 업로드는 8 KiB 버퍼의 임시 파일로 처리하며 최대 16개/파일당 50 MiB로 제한한다. XLSX는 파일 기반 POI와 압축 해제/metadata/행 크기 상한을 사용한다. 오류 미리보기·큐·metric writer는 bounded다. [전체 ingestion benchmark](pipeline-benchmark.md)는 독립 128 MiB JVM에서 1만/10만 행을 비교하며, 측정 범위와 한계는 [Finding](findings/2026-10-04-pipeline-throughput-heap.md)에 남겼다.
- PIPELINE은 입력 실패·coordinator 중단 후 받은 행을 제한 시간 안에 drain/flush하고 worker와 진행 callback 종료를 기다린다. 저장/worker 실패는 abort하고 실패 Run에 확인된 집계·rows.unconfirmed·shutdown.unfinishedExecutors를 남긴다. SINGLE_THREAD는 입력 실패 시 안전한 partial batch만 flush한다. 비협조적 I/O의 강제 종료·프로세스 강제 종료·미확인 행 재처리는 보장하지 않는다.
- pipeline 통합 테스트는 검증 오류, backpressure, 모드 결과 일치를 검증한다. metric writer에는 bounded buffer와 batch 처리량의 제어된 비교 테스트가 있다. `PipelinePerformanceTest`는 128 MiB JVM에서 전체 ingestion·영속 dead-letter·제어된 batch 지연의 처리량 개선을 검증한다. `./gradlew pipelineBenchmark`로 기본 H2/지연/입력 크기별 원시 측정과 Run export를 생성한다. 일반 환경의 성능 우위나 RSS 상한을 보장하지 않는다.

## 작업 단위 커밋

변경 → 관련 검증 → 전체 테스트 → diff 검토 → 해당 변경만 stage → 커밋 → 결과 보고 순서를 따른다. 기존 규칙에 따라 `<type>(<scope>): <short summary>` 형식을 사용하고, 영문 현재형 제목을 72자 미만으로 작성한다.

예: `docs(codex): document repository workflow and code map`.

새 Codex 작업도 루트 `AGENTS.md`를 기준으로 진행한다. 이 구조는 모델·개인 전역 설정을 고정하지 않는 저장소 지침 방식이며, [공식 OpenAI 안내](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)의 간결한 지침과 필요한 문서만 참조하는 원칙을 따른다.

루트 `settings.gradle`은 `career-lab/worker-support`만 composite build로 가져온다. 다섯 Career Boot 앱은 포함하지 않는다. 루트 테스트는 Java 21 테스트 worker jar를 자동 빌드하고 실행한다. 두 wrapper는 같은 worker-support 산출물 경로를 사용하므로 루트와 Career 검증을 동시에 실행하지 않는다. LOCAL_JVM capability는 기본 false이며 sequence 등록을 활성화하면 true다. 실제 Docker 수직 검증은 `./gradlew careerSequenceTest`로 별도 실행한다.

등록 LOCAL_JVM의 비정상 종료 후 접수 보호는 `WorkerRecoveryGuard`와 `LocalJvmRegistration`에서 담당한다. `WorkerRecoveryGuardTest`, `LocalJvmRegistrationTest`로 강제 종료 기록 보존·실패 후 재접수 거절·정상 해제를 검증한다. [운영 복구 절차](worker-recovery.md)를 따르며 기본값은 자동 복구 비활성이다. `WorkerStartupRecovery`가 종료 확인 기록이 있는 sequence만 opt-in으로 시작 시 재조정하며, 나머지는 운영자 확인이 필요하다.

sequence 컨테이너 소유권 기록·복구 명령은 Career `mcp-platform`의 `labRuntime` 내 `SequenceContainerRecovery`와 `SequenceWorker --recover`에 있다. 변경 시 `SequenceContainerRecoveryTest`, 실제 배포 CLI 통합 검증과 기존 lifecycle 회귀를 확인한다. 중앙 시작 시 복구는 `WorkerStartupRecoveryTest`와 별도 `careerSequenceTest`의 `SequenceStartupRecoveryTest`로 검증한다. `SequenceRecoveryTransportTest`는 실제 배포물의 Docker HTTP 장애를 루프백 서버로 주입하며, 실제 엔진 중단/재시작 검증과 구분한다. [운영 절차와 자동 복구 제한](sequence-container-recovery.md)을 따른다.

실제 private 엔진 재시작 fixture는 `SequenceDaemonRecoveryTest`와 별도 `careerDaemonRecoveryTest`다. 일반 suite에서는 제외한다. 2026-10-13 승인된 privileged 실행에서 강제 종료·재시작·소유 자원 회수·테스트 컨테이너와 익명 볼륨 삭제를 검증했다. [독립 엔진 절차](sequence-container-recovery.md#독립-docker-엔진-재시작-검증)의 명시적 승인과 옵션 없이 실행하지 않는다. 완료 기록 없는 실행과 OS 수준 격리는 미검증 범위다.

시작 시 복구 명령의 프로세스 정리도 공개 `WorkerProcessTree`를 재사용한다. `WorkerStartupRecoveryTest`가 정상 응답·시간 초과·호출 인터럽트에서 실제 자손 강제 종료·무관한 프로세스 보존을 검증한다. 정리 대기는 단계별 2초이며 OS 격리나 완료 기록 없는 실행의 자동 복구로 해석하지 않는다.

`./gradlew workerContainerIsolationTest`는 로컬 `busybox:1.37`로 비특권 PID 공간·분리 프로세스·controller 사망 후 새 JVM의 자원 회수를 검증한다. 일반 suite와 별도이며 이미지 자동 다운로드와 privileged 권한은 사용하지 않는다. 운영 실행 경로는 [I1–I5 계획](worker-container-isolation.md)에 따라 추가하며, I2 어댑터의 실제 Docker 검증도 이 task에 포함한다.

I2 수명주기 구현은 `runtime/WorkerContainerLifecycle`(배타 잠금·단계별 영속 기록)과 `DockerContainerEngine`(bounded CLI·격리/소유권 확인)에 있다. `WorkerContainerLifecycleTest`와 `workerContainerIsolationTest`로 검증한다. 아직 중앙 executor/marker 해제에는 연결하지 않는다. 다음은 I3 실제 Java 21 protocol transport 연결이다.
