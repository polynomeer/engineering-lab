# Refactor Sequence

## Goal
Move from the current pipeline-first layout to `engineering-lab` with the least disruption to working APIs, dashboards, and tests.

## Sequence

### 1. Rename project-facing language
- Update docs and README to describe the repository as `engineering-lab`.
- Treat the current Excel ingestion flow as the first experiment, not the whole product.
- Do not rename packages yet.

### 2. Introduce shared core contracts
- Add `com.pnomeer.lab.core` for reusable execution contracts.
- Define shared types for:
  - experiment identity
  - scenario input
  - run status
  - timeline snapshots
  - result summary
- Keep current pipeline code unchanged while these types are introduced.

### 3. Adapt job orchestration to shared contracts
- Refactor job state and async execution to depend on shared contracts instead of pipeline-only types.
- Keep existing `/ingest/*` endpoints working during the transition.
- Avoid changing dashboard payloads until shared models are stable.

### 4. Move pipeline into `experiments.pipeline`
- Relocate pipeline-specific code:
  - parser
  - queue
  - stage
  - insert logic
  - pipeline models
- Add adapter code so the pipeline implements the shared experiment contract.

### 5. Extract shared metrics and dashboard models
- Move generic progress and metric snapshots out of `PipelineRunner`.
- Normalize shared dashboard sections:
  - throughput
  - latency
  - queue/backpressure
  - resource
  - stability
  - scalability
- Keep pipeline-only fields as optional extensions.

### 6. Add a second experiment
- Add one unrelated experiment using the same job runner and dashboard infrastructure.
- Good candidates:
  - queue contention
  - JDBC batch-size comparison
  - parser throughput comparison
- Use it to validate that the shared contracts are not pipeline-specific.

### 7. Rename packages and modules last
- Rename base package to `com.pnomeer.lab` after shared contracts are adopted.
- Split into Gradle modules only after package boundaries stabilize.
- Avoid package churn while contracts are still moving.

## Guardrails
- Keep `./gradlew test` green after each step.
- Prefer adapters over large in-place rewrites.
- Do not break the current live UI while refactoring internals.
- Validate reuse by adding another experiment early, not by over-generalizing first.
