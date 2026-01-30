# excel-pipeline

Parallel Excel ingestion pipeline with bounded queues, worker pools, streaming XLSX parsing, validation/mapping, and chunked JDBC inserts.

## What this service does
- Accepts an uploaded `.xlsx` file.
- Reads the first sheet in streaming mode (header row skipped).
- Validates each row (`col1` and `col2` required).
- Maps valid rows to domain objects.
- Inserts rows in chunks into `ingest_item(col1, col2, col3)`.
- Tracks async job state in an in-memory job store.

## Prerequisites
- JDK `25` (project toolchain is set to Java 25 in `build.gradle`).
- macOS/Linux shell (examples below use `bash`/`zsh`).
- `curl` for API testing.

## Project layout (important files)
- `src/main/java/com/polynomeer/excelpipeline/api` : REST API + in-memory job tracking
- `src/main/java/com/pnomeer/pipeline` : pipeline runner/stages
- `src/main/resources/application.yml` : pipeline configuration defaults
- `src/main/resources/schema.sql` : DB schema for runtime startup

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

## API manual

### 1) Start ingestion
`POST /ingest/excel`

- Content type: `multipart/form-data`
- Form field name: `file`
- Expected file type: `.xlsx`
- Response: `202 Accepted`

Example:

```bash
curl -i -X POST "http://localhost:8080/ingest/excel" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/absolute/path/to/sample.xlsx"
```

Response body:

```json
{
  "jobId": "f9d8d1d1-9f0c-47ec-8f5f-86b41c3f8f27"
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

### 4) Open live visualization page
`GET /ingest/ui/{jobId}`

This page polls status + timeline endpoints and renders:
- queue sizes over time
- throughput (produced/mapped/inserted rows/sec)
- current counters and status

Open in browser:

```bash
open "http://localhost:8080/ingest/ui/<jobId>"
```

### 5) Open all-jobs live dashboard
`GET /ingest/ui`

Shows all jobs in real time (RUNNING/SUCCEEDED/FAILED), live queue/rate stats, and links to per-job detail charts.

```bash
open "http://localhost:8080/ingest/ui"
```

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

## Limitations (current)
- Job store is in-memory (`ConcurrentHashMap`), so job history is lost on restart.
- No authentication/authorization on endpoints.
- No persistence of validation-error details beyond aggregated summary in status payload.
