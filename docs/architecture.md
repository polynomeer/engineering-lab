# Architecture

## Goal
Build a parallel Excel ingestion pipeline that is fast, memory-bounded, and reliable under load.

## Pipeline
1. Parse: stream rows from workbook sheets.
2. Validate: apply required-field and type checks.
3. Map: transform validated rows into DB-ready records.
4. Insert: write records using chunked batch inserts.

Each stage communicates through a bounded blocking queue.

## Concurrency Model
- Fixed thread pools per stage (`parse`, `validate/map`, `insert`).
- Bounded queues enforce backpressure: producers block when downstream is saturated.
- No unbounded buffers in memory.

## Insert Strategy
- Insert workers build chunks up to `batch_size`.
- Flush a chunk when:
  - size reaches `batch_size`, or
  - max wait time is reached.
- Use one transaction per chunk.

## Shutdown
- Use poison-pill sentinels for orderly termination.
- Producers send poison pills downstream after input completion.
- Consumers drain queued work, flush final partial chunks, then exit.

## Failure Policy
- Validation and mapping failures are handled at row level.
- Row errors are collected with row identifier and reason.
- Valid rows continue through the pipeline.
- Insert retries are bounded; exhausted failures are recorded in job summary.

## Operational Signals
Track queue depth, stage throughput, insert latency, batch size, retry count, and row-error count.
