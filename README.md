# engineering-lab

`engineering-lab` is evolving into a reproducible experimentation platform for backend engineering: reproduce problems, test hypotheses, observe runs, and preserve evidence behind decisions.

See the [platform design](docs/experiment-platform-design.md) and [implementation plan](docs/implementation-plan.md) for the staged roadmap and current completion status.

The current implemented experiment is an Excel ingestion pipeline with:
- bounded queues for backpressure
- fixed worker pools
- streaming XLSX parsing
- validation and mapping
- chunked JDBC inserts
- live dashboards and benchmark-style metrics

The repository is being restructured so experiments live under shared platform code:
- `com.pnomeer.lab.app.*` for the app and dashboards
- `com.pnomeer.lab.core.*` for shared execution contracts
- `com.pnomeer.lab.metrics.*` for reusable runtime metrics
- `com.pnomeer.lab.experiments.pipeline.*` for the Excel pipeline experiment

There is also a second non-pipeline experiment now:
- `com.pnomeer.lab.experiments.concurrency.*` for queue contention benchmarking
- `com.pnomeer.lab.experiments.io.*` for blocking, virtual-thread, and selector IO labs

## Prerequisites
- JDK `25` (project toolchain is set to Java 25 in `build.gradle`).
- macOS/Linux shell (examples below use `bash`/`zsh`).
- `curl` for API testing.

## Working with Codex
- [AGENTS.md](AGENTS.md): repository rules, required tests, and one Conventional Commit per completed unit of work.
- [Codex repository guide](docs/codex.md): code map, verification commands, and current implementation gaps.

## Current layout
- `src/main/java/com/pnomeer/lab/app/ingest` : REST API, job state, live dashboards
- `src/main/java/com/pnomeer/lab/core` : shared experiment contracts and execution state
- `src/main/java/com/pnomeer/lab/metrics` : reusable execution metric snapshot types
- `src/main/java/com/pnomeer/lab/experiments/pipeline` : Excel ingestion experiment
- `src/main/java/com/pnomeer/lab/experiments/concurrency` : queue contention experiment
- `src/main/java/com/pnomeer/lab/experiments/io` : blocking IO / virtual thread / selector IO lab
- `src/main/java/com/pnomeer/pipeline/ExcelPipelineApplication.java` : Spring Boot entrypoint
- `src/main/resources/application.yml` : app and pipeline defaults
- `src/main/resources/schema.sql` : runtime DB schema

## JVM observations

Optional process-wide JVM start/end snapshots can be enabled with `./gradlew bootRun --args='--lab.observation.jvm-enabled=true'` (default: off). Add `--lab.observation.jvm-interval-ms=1000` for capped periodic sampling (default cap: 1000 samples per Run). `/metrics/schema` describes known metric units and scope. See the [observation guide](docs/observability.md) for capture costs, limitations and verification.

## Run evidence and findings

Open `/runs/ui` to browse experiments, inspect persisted runs and compare two runs with their environment, input, parameters and metric units. Terminal runs can be exported as NDJSON. Artifact references retain user-provided file metadata; file collection/hosting is not implemented.

See the [evidence guide](docs/run-evidence.md) and [Finding template](docs/findings/TEMPLATE.md) to preserve observations separately from interpretation.

## Versioned scenarios

The [scenario guide](docs/scenarios.md) documents the experiment registry, strict JSON/YAML schemas, common `POST /runs`, and HTTP CLI. Start the server with `./gradlew bootRun`, then use another terminal:

```bash
./gradlew -q lab --args='list'
./gradlew -q lab --args='validate experiments/concurrency/queue-contention/scenarios/small-buffer.yaml'
./gradlew -q lab --args='run experiments/concurrency/queue-contention/scenarios/small-buffer.yaml --wait'
```

`GET /experiments` exposes all three schemas. Declarative runs preserve the canonical definition, hypothesis, and definition SHA-256 alongside the effective parameters. Only `SINGLE_PASS` measurement is currently supported; unsupported warmup/duration options fail validation.

## Run metadata

Jobs in `/experiments/jobs`, `/experiments/jobs/{jobId}`, `/ingest/jobs`, and `/ingest/jobs/{jobId}` now include `runMetadata`:

- `runId`: the existing `jobId`, shared with the runner result.
- `parameters`: an immutable copy of the scenario parameters taken before execution.
- `environment`: Java version/vendor/VM, OS name/version/architecture, available processors, and maximum JVM heap bytes.
- `git`: commit SHA, tracked/untracked dirty state, and a collection error when unavailable. Runtime Git probes have a two-second command timeout and 64 KiB output limit; unknown is never reported as clean.
- Excel parameters also include `input.sha256`, `input.fileName`, and the pipeline queue/worker/batch/timeout configuration. The hash identifies the uploaded bytes; it does not archive the workbook.

The snapshot is persisted before dispatch, so queued, successful, failed, and rejected jobs retain it. Existing response fields are unchanged. Legacy job endpoints map QUEUED to RUNNING and INTERRUPTED to FAILED; `/runs` exposes the full lifecycle.

Run state, metadata, summaries, and metrics survive application restarts in a separate H2 file DB under `.lab/`. CPU count means processors available to the JVM; maximum heap is not physical system RAM. Environment variables and full JVM arguments are not collected.

## Run persistence

See the [run storage guide](docs/run-storage.md) for defaults, recovery behavior, paginated `/runs` APIs, backup, and verification.

## Configuration
Default config in `application.yml`:

```yaml
spring:
  servlet:
    multipart:
      file-size-threshold: 0
      max-file-size: 50MB
      max-request-size: 50MB

pipeline:
  shutdown-timeout-ms: 30000
  error-preview-limit: 100
  queue:
    raw-capacity: 2000
    mapped-capacity: 2000
  threads:
    validator: 4
    inserter: 4
  insert:
    chunk-size: 500
    flush-interval-ms: 1000
    max-attempts: 3
    retry-backoff-ms: 100
  backpressure:
    offer-timeout-ms: 200
```

Meaning:
- `shutdown-timeout-ms`: PIPELINE drain deadline and, separately, executor cleanup deadline; default 30000 ms, range 1–60000.
- `error-preview-limit`: retained validation row preview, default 100, range 0–1000; totals and durable dead letters include every rejected row.
- `queue.raw-capacity` / `queue.mapped-capacity`: bounded queue sizes for backpressure.
- `threads.validator` / `threads.inserter`: worker pool sizes.
- `insert.chunk-size`: JDBC batch size per chunk/transaction.
- `insert.flush-interval-ms`: partial batch residence interval, default 1000 ms, range 1–60000 ms.
- `insert.max-attempts`: total batch attempts including the initial write, default 3, range 1–10 (1 disables retry).
- `insert.retry-backoff-ms`: initial retry delay, default 100 ms, range 1–60000 ms; doubles per retry with a 60000 ms cap.
- `backpressure.offer-timeout-ms`: enqueue timeout for timed offers.
- `spring.servlet.multipart.max-file-size`: max single upload size.
- `spring.servlet.multipart.max-request-size`: max multipart request size.

In PIPELINE mode, each insert worker flushes on size, elapsed time since accepting its first row, or end of input, whichever comes first. New rows do not reset the deadline. The interval excludes upstream queue waiting and is not a database completion timeout; scheduling and database latency can add time. SINGLE_THREAD checks the deadline at each row callback (including invalid rows) and flushes at end of input, so blocked input delays its time-based flush.

Each batch uses its own transaction. Only transient data-access failures inside the batch body are retried after a successful rollback, so partial writes cannot accumulate across these attempts. Permanent errors and uncertain commit/rollback failures are propagated without replay. This does not deduplicate separate runs or recover an uncertain commit. Successful run summaries include `retry.count`; in-progress metrics use the same counter. Exhausted insert workers abort the channels to release blocked producers/consumers; this is fail-fast termination, not durable queue drain or resumable ingestion.

Validation failures from app-managed Excel runs are persisted with their row index, reason and cell strings in the evidence DB. After a Run terminates, page through `/runs/{runId}/dead-letters?after=-1&limit=100`. Storage failures or records above 64 KiB fail the Run; stored rows remain available after restart. See [storage and dead-letter behavior](docs/run-storage.md). Only a bounded preview of rejected rows is retained in memory; totals and reason counts remain exact.

Uploads are staged to temporary files with an 8 KiB buffer and SHA-256 calculation. Application staging accepts at most 16 files of up to 50 MiB; oversize requests return 413 and staging saturation returns 503. Scenario uploads may set a smaller `lab.scenario.max-input-bytes`. Normal completion, execution failure and submission rejection release staged files. The parser uses a separate temporary file for file-based POI access and validates ZIP expansion first: at most 1024 entries, 256 MiB total expanded content and 8 MiB non-worksheet metadata (including shared strings/styles). Rows are limited to 16384 cells and 65536 characters. Exceeding a parser limit fails the Run. See [input lifecycle and limits](docs/run-storage.md).

On input failure or coordinator interruption, PIPELINE drains already accepted rows and flushes partial batches within its drain deadline. It then waits for workers and any in-flight progress callback to finish. Storage/worker failure aborts further processing. Failed/interrupted Runs retain acknowledged counts and `rows.unconfirmed`; `shutdown.unfinishedExecutors` identifies workers whose termination could not be confirmed. SINGLE_THREAD flushes a safe partial batch after input failure, but does not replay a failed callback/batch. This is cooperative shutdown, not forced cancellation of arbitrary blocking input/DB calls. See [shutdown contract](docs/run-storage.md).

## Run locally
1. Run tests first:

```bash
./gradlew test
```

2. Start the app:

```bash
./gradlew bootRun
```

3. Service base URL:
- `http://localhost:8080`

Notes:
- The ingestion data DB is H2 (in-memory). Run evidence uses a separate H2 file DB under `.lab/`.
- Schema is auto-initialized from `src/main/resources/schema.sql`.
- The Spring Boot entrypoint class is still named `ExcelPipelineApplication` while the project is being migrated.

Home dashboard:

```bash
open "http://localhost:8080/ui"
```

This page links the current lab surfaces:
- pipeline ingestion
- generic experiment jobs
- IO comparison lab

## Current HTTP API

The app now exposes two experiment surfaces:
- `/ingest/*` for the Excel ingestion experiment
- `/experiments/*` for generic experiment jobs

### 1) Start ingestion
`POST /ingest/excel`

- Content type: `multipart/form-data`
- Form field name: `file`
- Expected file type: `.xlsx`
- Optional query param: `mode=PIPELINE|SINGLE_THREAD` (default `PIPELINE`)
- Response: `202 Accepted`

Example:

```bash
curl -i -X POST "http://localhost:8080/ingest/excel" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/absolute/path/to/sample.xlsx"
```

Single-thread baseline example:

```bash
curl -i -X POST "http://localhost:8080/ingest/excel?mode=SINGLE_THREAD" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/absolute/path/to/sample.xlsx"
```

Response body:

```json
{
  "jobId": "f9d8d1d1-9f0c-47ec-8f5f-86b41c3f8f27",
  "mode": "PIPELINE"
}
```

### 2) Check job status
`GET /ingest/jobs/{jobId}`

Example:

```bash
curl -s "http://localhost:8080/ingest/jobs/f9d8d1d1-9f0c-47ec-8f5f-86b41c3f8f27"
```

Response shape:

```json
{
  "jobId": "f9d8d1d1-9f0c-47ec-8f5f-86b41c3f8f27",
  "status": "SUCCEEDED",
  "experimentType": "pipeline.excel-ingestion",
  "scenarioId": "PIPELINE",
  "producedCount": 1200,
  "insertedCount": 1180,
  "validationErrorCount": 20,
  "errorSummary": {
    "col1 is required": 12,
    "col2 is required": 8
  },
  "failureMessage": null
}
```

Statuses:
- `RUNNING`: job still processing.
- `SUCCEEDED`: processing complete (may include row-level validation errors).
- `FAILED`: pipeline failed; check `failureMessage`.

### 3) View progress timeline (visualization data)
`GET /ingest/jobs/{jobId}/timeline`

Returns sampled snapshots captured during pipeline execution:
- queue sizes (`rawQueueSize`, `mappedQueueSize`)
- counters (`producedCount`, `mappedCount`, `insertedCount`)
- per-second rates (`producedRatePerSec`, `mappedRatePerSec`, `insertedRatePerSec`)
- elapsed time (`elapsedMillis`)

Example:

```bash
curl -s "http://localhost:8080/ingest/jobs/<jobId>/timeline"
```

### 4) View shared metric timeline
`GET /ingest/jobs/{jobId}/metrics`

Returns generic metric points that can be reused by future experiments.

Example:

```bash
curl -s "http://localhost:8080/ingest/jobs/<jobId>/metrics"
```

### 5) Open live visualization page
`GET /ingest/ui/{jobId}`

This page polls status + timeline endpoints and renders:
- queue sizes over time
- throughput (produced/mapped/inserted rows/sec)
- current counters and status

Open in browser:

```bash
open "http://localhost:8080/ingest/ui/<jobId>"
```

### 6) Open all-jobs live dashboard
`GET /ingest/ui`

Shows all jobs in real time (RUNNING/SUCCEEDED/FAILED), live queue/rate stats, and links to per-job detail charts.

```bash
open "http://localhost:8080/ingest/ui"
```

### 7) Open DB live view
`GET /ingest/ui/db`

Shows live data from table `ingest_item` with:
- total row count
- configurable row limit
- newest rows first (auto-refresh)

```bash
open "http://localhost:8080/ingest/ui/db"
```

## Generic experiment API

### 1) Start queue contention
`POST /experiments/concurrency/queue-contention`

Request body:

```json
{
  "scenarioId": "queue-contention-demo",
  "queueCapacity": 2,
  "producerThreads": 2,
  "consumerThreads": 1,
  "itemsPerProducer": 100,
  "consumerDelayMs": 2
}
```

Example:

```bash
curl -s -X POST "http://localhost:8080/experiments/concurrency/queue-contention" \
  -H "Content-Type: application/json" \
  -d '{
    "scenarioId":"queue-contention-demo",
    "queueCapacity":2,
    "producerThreads":2,
    "consumerThreads":1,
    "itemsPerProducer":100,
    "consumerDelayMs":2
  }'
```

### 2) List experiment jobs
`GET /experiments/jobs`

```bash
curl -s "http://localhost:8080/experiments/jobs"
```

### 3) Get experiment job
`GET /experiments/jobs/{jobId}`

```bash
curl -s "http://localhost:8080/experiments/jobs/<jobId>"
```

### 4) Get experiment metrics
`GET /experiments/jobs/{jobId}/metrics`

```bash
curl -s "http://localhost:8080/experiments/jobs/<jobId>/metrics"
```

### 5) Open experiment dashboard
`GET /experiments/ui`

This page lets you:
- start a queue contention job
- start an IO endpoint comparison job
- see all generic experiment jobs live
- open per-job metric detail pages

```bash
open "http://localhost:8080/experiments/ui"
```

### 6) Start IO endpoint comparison
`POST /experiments/io/endpoint-comparison`

Request body:

```json
{
  "scenarioId": "io-comparison-demo",
  "requests": 12,
  "delayMs": 20,
  "pinning": false,
  "pinDelayMs": 20
}
```

Example:

```bash
curl -s -X POST "http://localhost:8080/experiments/io/endpoint-comparison" \
  -H "Content-Type: application/json" \
  -d '{
    "scenarioId":"io-comparison-demo",
    "requests":12,
    "delayMs":20,
    "pinning":false,
    "pinDelayMs":20
  }'
```

## IO lab

The repository also includes standalone IO comparison code based on:
- blocking socket server
- virtual-thread socket server
- selector-based NIO server
- simple concurrent load client
- Spring MVC style virtual-thread endpoint
- Spring reactive endpoint

Classes:
- `com.pnomeer.lab.experiments.io.BlockingEchoServer`
- `com.pnomeer.lab.experiments.io.VirtualThreadEchoServer`
- `com.pnomeer.lab.experiments.io.NioSelectorEchoServer`
- `com.pnomeer.lab.experiments.io.LoadTestClient`

Run with Gradle:

```bash
./gradlew runBlockingEchoServer
./gradlew runVirtualThreadEchoServer
./gradlew runNioSelectorEchoServer
./gradlew runIoLoadTest --args="127.0.0.1 7031 300 30"
```

Run with extra options:

```bash
./gradlew runBlockingEchoServer --args="7031 100"
./gradlew runVirtualThreadEchoServer --args="7032 100"
./gradlew runNioSelectorEchoServer --args="7033 false"
```

Default ports:
- blocking: `7031`
- virtual thread: `7032`
- selector NIO: `7033`

Extra args:
- blocking server: `port delayMs`
- virtual-thread server: `port delayMs`
- selector NIO server: `port preserveMessageBoundaries`

Example comparisons:

```bash
./gradlew runBlockingEchoServer
./gradlew runIoLoadTest --args="127.0.0.1 7031 300 30"
```

```bash
./gradlew runVirtualThreadEchoServer
./gradlew runIoLoadTest --args="127.0.0.1 7032 300 30"
```

```bash
./gradlew runNioSelectorEchoServer
./gradlew runIoLoadTest --args="127.0.0.1 7033 300 30"
```

Message-boundary break experiment:

```bash
./gradlew runNioSelectorEchoServer --args="7033 false"
```

With `false`, the selector server intentionally stops accumulating partial lines correctly. This makes it easier to observe:
- one logical line arriving in multiple chunks
- multiple lines arriving together
- why frameworks need explicit framing and buffering logic

Spring-based comparison endpoints:

- `GET /lab/io/virtual-thread/echo?msg=hello&delayMs=100`
- `GET /lab/io/virtual-thread/echo?msg=hello&delayMs=100&pinning=true&pinDelayMs=100`
- `GET /lab/io/reactive/echo?msg=hello&delayMs=100`

Examples:

```bash
curl "http://localhost:8080/lab/io/virtual-thread/echo?msg=hello&delayMs=100"
curl "http://localhost:8080/lab/io/virtual-thread/echo?msg=hello&delayMs=100&pinning=true&pinDelayMs=100"
curl "http://localhost:8080/lab/io/reactive/echo?msg=hello&delayMs=100"
```

The virtual-thread endpoint keeps blocking-style code and dispatches work onto a dedicated virtual-thread executor.
The reactive endpoint uses `Mono.delay(...)` to demonstrate non-blocking wait semantics inside the Spring app.
If you set `pinning=true`, the virtual-thread endpoint enters a `synchronized` block and sleeps inside it, which makes it easier to observe carrier-thread pinning behavior.

Browser dashboard:

```bash
open "http://localhost:8080/lab/io/ui"
```

This page runs a lightweight sequential benchmark from the browser and compares:
- average latency
- total elapsed time
- requests per second
- per-request latency samples
- optional virtual-thread pinning mode

## Expected XLSX format
- First sheet only is parsed.
- Row `0` is treated as header and skipped.
- Data rows should contain:
  - column 0: `col1` (required string)
  - column 1: `col2` (required string)
  - column 2: `col3Int` (integer)

Rows with invalid required fields are not inserted and are counted in `validationErrorCount`.

## Quick end-to-end verification
1. Start app (`./gradlew bootRun`).
2. Upload test file with `POST /ingest/excel`.
3. Poll `GET /ingest/jobs/{jobId}` every second until status != `RUNNING`.
4. Confirm:
- `status` is `SUCCEEDED`
- `insertedCount` matches expected valid rows
- `validationErrorCount` matches expected invalid rows

Polling example:

```bash
JOB_ID="<job-id>"
while true; do
  RESPONSE="$(curl -s "http://localhost:8080/ingest/jobs/$JOB_ID")"
  echo "$RESPONSE"
  echo "$RESPONSE" | rg -q '"status":"RUNNING"' || break
  sleep 1
done
```

## Generate a test XLSX file
Use the helper script:

```bash
./scripts/create-test-xlsx.sh [output_path] [row_count]
```

Examples:

```bash
./scripts/create-test-xlsx.sh
./scripts/create-test-xlsx.sh /tmp/sample.xlsx 500
```

## Current limitations
- Run evidence persists locally; ingestion rows and uploaded workbook bytes are not archived. A checksum identifies inputs but cannot recreate them.
- Legacy job lists show at most the newest 100 runs; metric previews default to the latest 200 points. Use the paginated `/runs` API for full evidence.
- Run storage is single-application local storage. Schema v1 is supported; unknown versions fail startup. Artifact registration/export APIs remain future work.
- Implemented experiment families are Excel ingestion, queue contention, and IO endpoint comparison; other families in the architecture plan remain future work.
- No authentication or authorization on endpoints.
- No persistence of validation-error details beyond aggregated summary in status payload.
- Some legacy names remain during the migration, especially the Spring Boot application class and the `pipeline.*` property prefix.

### 선택적 명시적 Trace

`--lab.trace.enabled=true --lab.trace.max-spans=1000`으로 전체 실행 및 IO 요청별 span을 기록한다(기본 OFF, 최대 10000개). `/runs/{id}/traces`와 NDJSON export에서 조회한다. Excel/큐 내부 단계 계측은 후속 과제다. 페이지 cursor 처리와 계측 비용·저장 상한은 [관측 가이드](docs/observability.md)를 참고한다.

### 선택적 JFR 수집

`--lab.jfr.enabled=true`로 CPU load·GC·GC heap summary를 녹화한다(기본 OFF, 60초, 최종 파일 최대 16 MiB). `.lab/jfr/` 파일과 checksum을 Run artifact로 자동 등록한다. 프로세스 전체 관측이며 저장 상한·외부 녹화 간섭·보관 및 비용 측정은 [관측 가이드](docs/observability.md)를 참고한다. 원문 서버 로그 수집은 아직 미지원이다.

### 선택적 Run 로그

`--lab.logs.enabled=true`로 실행 시작·결과·관측/저장 실패를 NDJSON artifact에 기록한다(기본 OFF, 최대 100개 이벤트/64 KiB). 고정 이벤트와 사유 코드만 기록하며 예외 메시지·입력 데이터는 포함하지 않는다. 파일 위치와 완료 경계, 누락 집계·보관 정책은 [관측 가이드](docs/observability.md)를 참고한다.

### 관측 파일 다운로드

`/runs/ui` 상세의 Artifacts에서 Download JFR/Download log를 선택한다. 종료 Run의 JFR·로그는 `GET /runs/{runId}/artifacts/{artifactId}/download`로 받는다. 등록된 크기와 SHA-256을 먼저 검증하며, 기본 상한은 파일당 256 MiB·동시 2개다. 관리 경로 제한과 설정/오류 응답은 [증거 가이드](docs/run-evidence.md)를 참고한다.
