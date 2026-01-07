# Implementation Plan

## Scope
Build v1 of a parallel Excel ingestion pipeline with bounded queues, chunked batch inserts, and operational visibility.

## Milestones
1. **Core pipeline skeleton**
   - Define job model, pipeline stages, and cancellation contract.
   - Implement `BoundedQueue<T>` with blocking semantics and tests.
   - Add config model with sane defaults.

2. **Excel read + transform path**
   - Implement streaming Excel reader (sheet/row iterator).
   - Add transformer workers with schema mapping + required field validation.
   - Emit row-level validation errors to dead-letter sink.

3. **Batching + parallel inserts**
   - Implement batch assembler (`batch_size`, `max_batch_wait_ms`).
   - Implement insert worker pool with retry/backoff.
   - Add transaction-per-batch writes and idempotent insert strategy.

4. **Observability + hardening**
   - Add metrics and structured logs for each stage.
   - Add load test scenario with large workbook.
   - Tune thread counts, queue sizes, and batch size based on results.

## Work Breakdown
1. Define interfaces:
   - `RowSource`, `RowTransformer`, `BatchSink`, `DeadLetterSink`.
2. Implement queue and worker loop abstractions.
3. Wire stage orchestration in `JobController`.
4. Add graceful shutdown:
   - stop intake
   - drain queues
   - flush final partial batch
5. Add retries and failure policies.
6. Add metrics and dashboards/log queries.

## Default Tuning Targets (Initial)
- `batch_size`: 500
- `max_batch_wait_ms`: 200
- `transform_threads`: number of CPU cores
- `insert_threads`: 4 (adjust to DB pool)
- queue capacities: 2,000 rows each stage

## Testing Strategy
- Unit:
  - queue blocking behavior
  - batch flush rules
  - retry/backoff logic
- Integration:
  - end-to-end ingest for mixed-validity workbook
  - dead-letter output correctness
  - idempotent re-run behavior
- Performance:
  - compare throughput vs single-thread baseline
  - verify memory stays bounded at large input sizes

## Risks and Mitigations
- DB saturation -> cap `insert_threads`, monitor latency, auto-throttle if needed.
- Skewed row complexity -> queue depth metrics and adaptive batch timing.
- Large validation error volume -> bounded dead-letter writer and sampled logging.

## Definition of Done
- End-to-end pipeline implemented and configurable.
- Backpressure demonstrated with bounded queues in tests.
- Chunked parallel inserts with retries verified.
- Metrics/logs available for throughput, latency, queue depth, and failures.
