# Architecture

The next platform iteration is specified in [Experiment Platform v2](experiment-platform-design.md), with tracked work in [the implementation plan](implementation-plan.md). The layout below is the earlier architectural direction, not a list of existing Gradle modules.

## Goal
Evolve this repository from a single Excel pipeline app into `engineering-lab`: a reusable platform for hands-on experiments, benchmarks, dashboards, and implementation exercises across multiple topics.

## Top-Level Direction
- Treat each domain as an experiment package, not as the project identity.
- Keep dashboards, metrics, scenario execution, and benchmark reporting reusable.
- Allow unrelated practice code to coexist without polluting shared runtime infrastructure.

## Target Structure
```text
engineering-lab
  app/                    # Spring Boot entrypoint, REST API, web UI
  lab-core/               # shared experiment contracts and execution model
  lab-metrics/            # throughput, latency, resource, error metrics
  lab-dashboard/          # reusable dashboard models and rendering helpers
  fixtures/               # test data generators and sample inputs
  experiments/
    pipeline/             # current Excel ingestion work
    concurrency/          # queues, threads, locks, backpressure exercises
    database/             # batch insert, indexing, lock contention, tx tests
    parsing/              # CSV/XLSX/JSON streaming experiments
    algorithms/           # caches, rate limiters, data structures, etc.
```

## Responsibility Boundaries

### `app`
- Hosts HTTP endpoints and live dashboards.
- Starts experiments asynchronously.
- Persists only lightweight job state and result summaries.

### `lab-core`
- Defines common contracts:
  - `Experiment`
  - `Scenario`
  - `ExperimentRunner`
  - `ExperimentResult`
  - `TimelinePoint`
- Owns job lifecycle, execution flow, and result publication.

### `lab-metrics`
- Provides reusable collectors for:
  - throughput
  - latency
  - queue depth and backpressure
  - memory and GC
  - DB connection and lock wait
  - failure and retry signals
- Must not depend on a specific experiment type.

### `lab-dashboard`
- Converts experiment results into a common dashboard model.
- Reuses the same charting and status panels across experiments.
- Experiment-specific pages extend shared widgets rather than duplicating them.

### `fixtures`
- Generates XLSX/CSV/JSON samples and synthetic workloads.
- Supplies repeatable inputs for tests, demos, and benchmarks.

### `experiments/*`
- Contains topic-specific logic only.
- Implements shared `lab-core` contracts.
- Can add domain-specific metrics, but should publish through the shared result model.

## Package Naming
Use `com.pnomeer.lab` as the base package.

Recommended package layout:
```text
com.pnomeer.lab.app
com.pnomeer.lab.core
com.pnomeer.lab.metrics
com.pnomeer.lab.dashboard
com.pnomeer.lab.experiments.pipeline
com.pnomeer.lab.experiments.database
com.pnomeer.lab.experiments.concurrency
```

## Reuse Model
- Shared infrastructure lives outside experiment packages.
- New experiments should only need:
  - scenario definition
  - execution logic
  - optional domain-specific result fields
- Benchmark runners and dashboards consume common result types, so pipeline code is only one client of the platform.

## Migration Principle
- Move reusable concerns first: job execution, metrics, dashboard models.
- Move pipeline-specific code under `experiments.pipeline` second.
- Add a second non-pipeline experiment early to validate that the platform boundaries are real.

## Pipeline batch retry boundary

Each insert attempt runs in a new transaction, suspending an ambient transaction if present. A private exception marks transient failures raised inside the batch callback. Only that marker returning through a successful rollback permits retry; acquisition, commit and rollback errors never enter the retry loop. The same bounded chunk remains with its worker until commit succeeds, then counts advance and the chunk is cleared. This provides duplicate-free replay after confirmed rollback, not cross-run deduplication or uncertain-commit recovery.

Retry exhaustion aborts both channels. Blocking queue operations check the first failure at intervals of at most 100 ms, and worker interruption propagates as failure. Existing executor shutdown cancels remaining work. This does not guarantee durable drain, recovery of queued rows, or cancellation of a blocked input/DB call; those remain a separate lifecycle unit.

## Durable validation dead letters

The durable runner supplies a run-scoped `ExecutionEvidence` sink through the experiment contract; registry wrappers forward it. Pipeline validators synchronously persist a rejected row before adding it to the existing in-memory result collection. Direct/local runner calls retain their previous in-memory behavior. This sink adds no asynchronous queue or accumulating buffer.

The evidence DB migrates schema v1 to v2 additively under its existing exclusive ownership lock. `lab_dead_letter` stores `(run_id, row_index)` and an immutable JSON record. A transaction locks the Run, accepts writes only while RUNNING, and treats identical repeated rows as idempotent; conflicting records fail. Persisted rows survive later execution failure. There is no atomic transaction spanning valid-row ingestion and dead-letter storage, nor recovery guarantee for rows not yet acknowledged when the process stops.

Rows contain the parser's first-sheet, zero-based index, validation stage, fixed reason and formatted cell strings (including empty gaps), not workbook formulas/styles. JSON is limited to 64 KiB per row with pre-serialization bounds. Oversized rows and storage errors fail the Run without truncating evidence. API pages are limited to 100 rows and terminal Runs, preventing concurrent validators' out-of-order writes from falling behind a pagination cursor. Original cell values are intentionally retained in the local evidence DB/API, never added to structured failure logs.
