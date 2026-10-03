# Codex repository guide

이 문서는 코드 위치와 작업 검증을 위한 안내다. 작업·커밋 규칙의 기준은 루트 [AGENTS.md](../AGENTS.md)이며, 작업에 필요한 부분만 읽는다.

## 현재 구조

Java 25, Spring Boot 4.0.3, Gradle Wrapper 9.3.1을 사용한다. 데이터 접근은 Spring JDBC, 실행 DB는 H2, XLSX 파싱은 Apache POI 기반이다. 현재는 단일 Gradle 프로젝트다. `settings.gradle`의 이름은 아직 `excel-pipeline`이고, 앱 이름은 `engineering-lab`이다.

아래 Java 경로는 `src/main/java/` 기준이며, 테스트는 `src/test/java/`의 대응 패키지에 있다.

| 경로 | 책임 / 수정 시 확인할 대상 |
| --- | --- |
| `com/pnomeer/pipeline/ExcelPipelineApplication.java` | Spring Boot 진입점, `com.pnomeer` 컴포넌트 스캔 |
| `com/pnomeer/lab/core/` | `Experiment`, `ExperimentScenario`, 결과·상태·메트릭 계약, `RunMetadata`/`RunEnvironment`, `LocalExperimentRunner`, 명시적 `TraceRecorder` |
| `com/pnomeer/lab/metrics/` | 공용 실행 메트릭 스냅샷, `MetricCatalog` 단위·범위·의미, `JvmSnapshot` |
| `com/pnomeer/lab/runtime/` | 영속 runner, 제출/종료 lifecycle, Git provenance, JVM/trace/JFR/log 설정과 `JfrRecorder`·`RunLogRecorder` artifact 수집 |
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

- 기존 UI의 HTML/CSS/JavaScript는 컨트롤러의 Java text block에 있다. `/runs/ui`는 `RunUiController`와 `src/main/resources/static/run-browser/`의 HTML/CSS/JS를 사용한다. 별도 Node 빌드가 없으므로 UI 변경 시 해당 컨트롤러와 테스트를 함께 확인한다. Java 문자열 escaping과 브라우저 동작을 모두 고려한다.
- `src/main/resources/application.yml`: 런타임 기본값. 프로퍼티 변경 시 대응 properties와 README/운영 문서를 함께 갱신한다. 시나리오 schema/범위는 `ExperimentDescriptors`와 [시나리오 가이드](scenarios.md)를 함께 수정한다.
- ingestion DB 스키마 변경 시 `src/main/resources/schema.sql`과 `src/test/resources/schema.sql`을 함께 확인한다. 실행 evidence 스키마는 `src/main/resources/lab/schema-v1.sql`이며 별도 버전 전환이 필요하다.
- `docs/architecture.md`, `docs/implementation-plan.md`, `docs/refactor-sequence.md`는 아키텍처 방향과 마이그레이션 계획이다. 계획에 나오는 모듈·기능을 구현 완료로 간주하지 않는다.

## 실행 흐름

- Excel: `IngestionController` → `IngestionService` → `DurableExperimentRunner` → `PipelineExperiment` → `PipelineRunner`.
- 파이프라인 모드: 첫 시트 파싱 → bounded raw queue → 검증·매핑 worker → bounded mapped queue → insert worker별 chunk → JDBC 트랜잭션. 비교용 `SINGLE_THREAD` 모드도 있다.
- 공통 실험: `ExperimentController` → `ExperimentJobService` → `DurableExperimentRunner` → 해당 실험. 메트릭과 결과를 공통 모델로 반환한다.
- 실행 metadata는 jobId, 파라미터, JVM/OS, Git provenance를 보존한다. Excel은 input SHA-256과 설정도 포함한다. 앱 runner는 `runtime/DurableExperimentRunner`, 저장소는 `storage/JdbcRunStore`다. [저장 가이드](run-storage.md)를 참조한다.
- 선언적 실행은 `POST /runs` → `ExperimentRegistry`의 typed binding → `DurableExperimentRunner`를 거친다. 정의/hash/가설도 metadata에 저장한다. Git 예제는 `experiments/*/*/scenarios/`에 있다.
- 서버 실행·메트릭·결과는 `.lab/`의 별도 H2 DB에 보관된다. 브라우저 localStorage의 UI 상태·프리셋과 별개다. core의 `LocalExperimentRunner`는 저장소 없는 단위 테스트/로컬 실행용이며 앱 bean으로 사용하지 않는다.

## 실행과 검증

저장소 루트에서 Gradle Wrapper를 사용한다. JDK 25가 필요하며, 최초 실행에는 Gradle 배포본과 Maven 의존성 다운로드가 필요하다. 로컬 경로·인증정보를 저장소 설정에 고정하지 않는다.

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
| 공통 실행 모델 | `LocalExperimentRunnerTest`, `ExperimentExecutionStateTest`, `DurableExperimentRunnerTest` |
| 선언적 실행 | `ScenarioDocumentsTest`, `ScenarioApiIntegrationTest`, `LabCliIntegrationTest`, `LabCliTest` |
| 영속 저장 | `JdbcRunStoreTest`, `BoundedMetricWriterTest`, `RunRestartIntegrationTest`, `MetricWriterPerformanceTest` |
| 큐 경합 / IO | `QueueContentionExperimentTest`, `IoEndpointComparisonExperimentTest` |
| Run 로그 | `RunLogRecorderTest`, `LabCliIntegrationTest`; [관측 가이드](observability.md) |
| JFR 수집 | `JfrRecorderTest`, `LabCliIntegrationTest`; [관측 가이드](observability.md) |
| 명시적 Trace | `TraceRecorderTest`, `TraceIntegrationTest`, `LabCliIntegrationTest`; [관측 가이드](observability.md) |
| JVM 관측 | `JvmObservationTest`, `JvmPeriodicObservationTest`, `JvmSnapshotTest`, `MetricCatalogTest`; [관측 가이드](observability.md) |
| Run 증거 | `RunEvidenceTest`, `ArtifactDownloadTest`, `RunUiIntegrationTest`; [증거 가이드](run-evidence.md), [Finding 템플릿](findings/TEMPLATE.md) |
| API / UI | 해당 `*ControllerTest`, `ExperimentJobServiceTest`; UI 동작 변경은 브라우저에서도 확인 |

테스트 리포트는 `build/reports/tests/test/index.html`에 생성된다. UI 확인 경로는 `/runs/ui`, `/ui`, `/ingest/ui`, `/experiments/ui`, `/lab/io/ui`다. XLSX 샘플 생성은 `./scripts/create-test-xlsx.sh /tmp/sample.xlsx 500`을 사용한다. 상세 API와 IO 실행 명령은 [README](../README.md)를 참조한다.

## 구현 상태와 남은 요구사항

아래는 이 가이드를 작성하며 확인한 차이다. 관련 기능을 구현하면 이 목록도 갱신한다. 별도 요청 없이 이 목록 전체를 구현하는 것으로 작업 범위를 확대하지 않는다.

- insert chunk는 크기 충족 또는 종료 신호에서 flush된다. 시간 기반 flush와 insert 재시도/backoff는 아직 구현되어 있지 않다. retry 메트릭 필드만으로 재시도 지원을 판단하지 않는다.
- 검증 오류는 행 문맥을 가진 메모리 컬렉션에 모이며 API에는 집계가 노출된다. 영속 dead-letter 출력은 아직 없다.
- 단계 사이 큐는 bounded지만 업로드 파일은 `byte[]`로 읽고, 검증 오류도 메모리에 누적된다. 앱의 실행 이력은 영속화했고 metric writer와 preview는 bounded이다. 전체 경로의 bounded memory가 보장된 상태는 아니다.
- 정상 종료 신호와 partial chunk flush는 존재한다. 실패·중단 경로까지 queue drain과 데이터 보존이 보장되는지는 별도 검증이 필요하다.
- pipeline 통합 테스트는 검증 오류, backpressure, 모드 결과 일치를 검증한다. metric writer에는 bounded buffer와 batch 처리량의 제어된 비교 테스트가 있다. 전체 Excel ingestion의 heap/throughput 비교 테스트는 여전히 별도 과제다.

## 작업 단위 커밋

변경 → 관련 검증 → 전체 테스트 → diff 검토 → 해당 변경만 stage → 커밋 → 결과 보고 순서를 따른다. 기존 규칙에 따라 `<type>(<scope>): <short summary>` 형식을 사용하고, 영문 현재형 제목을 72자 미만으로 작성한다.

예: `docs(codex): document repository workflow and code map`.

새 Codex 작업도 루트 `AGENTS.md`를 기준으로 진행한다. 이 구조는 모델·개인 전역 설정을 고정하지 않는 저장소 지침 방식이며, [공식 OpenAI 안내](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)의 간결한 지침과 필요한 문서만 참조하는 원칙을 따른다.
