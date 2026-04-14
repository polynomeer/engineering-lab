# Architecture

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
