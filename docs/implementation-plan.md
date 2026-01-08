# Implementation Plan

## Milestones
1. Pipeline skeleton
- Define job lifecycle and stage boundaries (Parse -> Validate -> Map -> Insert).
- Implement bounded blocking queues between stages.
- Add fixed thread pool configuration per stage.

2. Parse/Validate/Map path
- Stream Excel rows without loading full workbook.
- Implement validation and mapping workers.
- Collect row-level errors with reason and row reference.

3. Insert path
- Implement chunk assembler (`batch_size`, max wait).
- Implement parallel insert workers with bounded retries.
- Ensure transaction-per-chunk behavior.

4. Graceful shutdown and observability
- Implement poison-pill shutdown across all stages.
- Flush final partial chunk on shutdown.
- Emit metrics for throughput, queue depth, retries, latency, and error counts.

## Definition of Done
- End-to-end ingestion runs Parse -> Validate -> Map -> Insert using bounded queues.
- Backpressure works: producer stages block when queues are full.
- Insert path performs chunked batch inserts with fixed insert worker pool.
- Poison-pill shutdown drains queues and exits cleanly.
- Row-level errors are collected without stopping valid-row processing.
- `./gradlew test` passes.
