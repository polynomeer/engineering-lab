# Architecture: Parallel Excel Ingestion Pipeline

## Goal
Ingest large Excel files quickly and safely using parallel processing, bounded queues for backpressure, and chunked batch inserts to the destination database.

## Non-Goals
- Real-time streaming from external sources.
- Arbitrary ETL transformations in v1.
- Unbounded memory growth for peak throughput.

## High-Level Flow
1. File intake validates file metadata and creates an ingestion job.
2. Reader stage parses workbook/sheets and emits row records.
3. Transformer stage normalizes rows, applies schema mapping, and validates required fields.
4. Bounded queue buffers records between stages and applies backpressure when full.
5. Batcher stage groups rows into fixed-size chunks.
6. Writer pool performs parallel batch inserts with retry policy.
7. Commit/summary stage records counts, failures, and job status.

## Components
- `JobController`: owns lifecycle, cancellation, and status.
- `ExcelReader`: streams rows sheet-by-sheet (not full workbook in memory).
- `TransformerWorkers`: CPU-bound normalization/validation workers.
- `BoundedQueue<T>`: thread-safe queue with max capacity and blocking push/pop.
- `BatchAssembler`: builds `N`-row chunks or time-based flushes.
- `InsertWorkers`: executes DB batch inserts in parallel with idempotent write strategy.
- `MetricsLogger`: emits queue depth, throughput, latency, errors, retries.

## Concurrency Model
- Producer-consumer pipeline with separate worker pools per stage.
- Configurable:
  - `reader_threads` (usually 1-2)
  - `transform_threads` (CPU cores)
  - `insert_threads` (DB connection budget)
- Bounded queues between stages:
  - `raw_row_queue_capacity`
  - `transformed_row_queue_capacity`
  - `batch_queue_capacity`

## Backpressure Strategy
- Queue `push` blocks when full.
- Upstream stages naturally slow down under downstream pressure.
- Optional timeout on `push`; timeout increments pressure metric and can trigger throttling logs.
- Avoid dropping records silently.

## Batch Insert Strategy
- Chunk size (`batch_size`) tuned by DB limits and transaction cost.
- Flush conditions:
  - `batch_size` reached, or
  - `max_batch_wait_ms` elapsed.
- Insert mode:
  - Prefer upsert with stable natural key or generated idempotency key.
  - Transaction per chunk for clear failure boundaries.

## Error Handling
- Row-level validation errors go to dead-letter output with reason and row location.
- Batch insert failures retried with exponential backoff and bounded attempts.
- If retries exhausted:
  - mark batch failed,
  - keep job running if `continue_on_error=true`,
  - otherwise fail fast and stop pipeline.

## Observability
Minimum metrics:
- rows read/sec, transformed/sec, inserted/sec
- queue depth and queue wait time
- batch sizes and insert latency
- retry count and failed row count
- end-to-end job duration and success ratio

## Configuration (v1)
- `batch_size`
- `max_batch_wait_ms`
- `reader_threads`
- `transform_threads`
- `insert_threads`
- queue capacities per stage
- `max_insert_retries`
- `continue_on_error`

## Acceptance Criteria
- Handles target file size without OOM.
- Sustained throughput better than single-thread baseline.
- Queue depth remains bounded under load.
- Retries and dead-letter behavior verified with fault injection.
