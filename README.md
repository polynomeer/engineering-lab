# engineering-lab

`engineering-lab` is a hands-on experimentation workspace for backend engineering topics.

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

## Configuration
Default config in `application.yml`:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

pipeline:
  queue:
    raw-capacity: 2000
    mapped-capacity: 2000
  threads:
    validator: 4
    inserter: 4
  insert:
    chunk-size: 500
  backpressure:
    offer-timeout-ms: 200
```

Meaning:
- `queue.raw-capacity` / `queue.mapped-capacity`: bounded queue sizes for backpressure.
- `threads.validator` / `threads.inserter`: worker pool sizes.
- `insert.chunk-size`: JDBC batch size per chunk/transaction.
- `backpressure.offer-timeout-ms`: enqueue timeout for timed offers.
- `spring.servlet.multipart.max-file-size`: max single upload size.
- `spring.servlet.multipart.max-request-size`: max multipart request size.

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
- Runtime DB is H2 (in-memory).
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
- see all generic experiment jobs live
- open per-job metric detail pages

```bash
open "http://localhost:8080/experiments/ui"
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

Default ports:
- blocking: `7031`
- virtual thread: `7032`
- selector NIO: `7033`

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
- Job store is in-memory (`ConcurrentHashMap`), so job history is lost on restart.
- Only the Excel pipeline experiment is implemented so far.
- No authentication or authorization on endpoints.
- No persistence of validation-error details beyond aggregated summary in status payload.
- Some legacy names remain during the migration, especially the Spring Boot application class and the `pipeline.*` property prefix.
