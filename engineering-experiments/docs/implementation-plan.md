# Implementation Plan

## Objective
Restructure the current Excel pipeline project into `engineering-lab` without losing the working pipeline demo, benchmark views, or existing observability.

## Phased Plan

### 1. Reframe the project
- Rename project-facing docs and terminology from product/service language to experiment-platform language.
- Define `engineering-lab` as the repository identity.
- Keep the current Excel flow as the first experiment.

### 2. Extract shared execution contracts
- Introduce shared interfaces and models in `lab-core`:
  - `Experiment`
  - `ExperimentRunner`
  - `ExperimentScenario`
  - `ExperimentResult`
  - `TimelinePoint`
- Move job lifecycle handling to shared code instead of pipeline-only code.

### 3. Extract reusable metrics and dashboard models
- Move common metric snapshots and progress reporting out of pipeline-specific classes.
- Normalize shared dashboard sections:
  - throughput
  - latency
  - queue/backpressure
  - resource usage
  - stability/errors
  - scalability
- Keep experiment-specific fields optional.

### 4. Isolate the current pipeline implementation
- Move current Excel ingestion code under `experiments.pipeline`.
- Keep parser, queue, stage, and insert logic inside the pipeline experiment package.
- Adapt existing API/UI to launch the pipeline through shared experiment contracts.

### 5. Add a second experiment family
- Add one non-pipeline experiment, such as:
  - queue contention benchmark
  - JDBC batch-size comparison
  - parsing throughput comparison
- Reuse the same runner, metrics, and dashboard components.

### 6. Split modules if complexity justifies it
- Start as a package-level refactor first.
- Split into Gradle modules only after shared boundaries stabilize:
  - `app`
  - `lab-core`
  - `lab-metrics`
  - `lab-dashboard`
  - `experiments-*`

## Definition of Done
- The repository is documented as `engineering-lab`, not as a single-purpose pipeline app.
- Shared execution and dashboard concerns are clearly separated from experiment-specific code.
- The current Excel pipeline runs as `experiments.pipeline`.
- At least one additional non-pipeline experiment can be added without changing shared infrastructure contracts.
- Existing dashboards and benchmark-style views remain reusable.
- `./gradlew test` passes after the refactor.
