# excel-pipeline

Parallel Excel ingestion pipeline with bounded queues, worker pools, and chunked DB inserts.

## API

### POST `/ingest/excel`
Starts an async ingestion job.

- Content type: `multipart/form-data`
- Form field: `file` (XLSX)
- Response: `202 Accepted`

Example response:

```json
{
  "jobId": "f9d8d1d1-9f0c-47ec-8f5f-86b41c3f8f27"
}
```

### GET `/ingest/jobs/{jobId}`
Returns current job status and counters.

Status values:
- `RUNNING`
- `SUCCEEDED`
- `FAILED`

Example response:

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

Jobs are stored in-memory (`ConcurrentHashMap`) in the current process.
