# Architecture

The implemented platform baseline is described in [Experiment Platform v2](experiment-platform-design.md), with completed work in [the implementation plan](implementation-plan.md). The proposed next iteration is [Shared Dashboard and Career Integration](dashboard-integration-design.md), tracked in [its migration plan](dashboard-integration-plan.md); it is not implemented yet. The layout below is the earlier architectural direction, not a list of existing Gradle modules.

## Current repository build boundaries

The root platform remains a Java 25 / Spring Boot 4 / Gradle 9.3.1 application.
`career-lab/` is an independent Gradle 8.14.2 multi-project build containing
`equity-system`, `batch-excel`, `mcp-platform`, `mds-distribution`, and `creator-studio`.
These restored modules retain Java 21, Spring Boot 3.3.4, their original sources,
and Testcontainers integration tests. Separate wrappers prevent their build plugins
and runtime dependencies from affecting the platform. They are repository modules,
not Git submodules, and are not registered in the platform Run registry.
See [Career Lab](../career-lab/README.md) for execution and recovery provenance.

## Implemented experiment extension boundary

Built-in experiment packages own their `ExperimentModule` metadata providers and
Spring registration configurations. A shared ServiceLoader catalog serves server
and offline CLI validation; startup verifies runtime bindings against that catalog.
`GET /modules` exposes ownership without changing existing experiment IDs or APIs.
New module metrics extend the catalog without replacing existing definitions.
See [the extension guide](adding-experiments.md). This is package-level modularity;
the frontend and external worker integration remain planned work. Career build
projects are discovered from explicit `lab-module.properties` declarations, so new
projects do not require edits to a fixed Gradle include list.

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

Retry exhaustion aborts both channels. Blocking queue operations check the first failure at intervals of at most 100 ms, and worker interruption propagates as failure. Executor shutdown now waits within a deadline and records partial progress; the termination contract below specifies drain and abort boundaries.

## Durable validation dead letters

The durable runner supplies a run-scoped `ExecutionEvidence` sink through the experiment contract; registry wrappers forward it. Pipeline validators synchronously persist a rejected row before updating exact reason counts and a bounded in-memory preview. Direct/local runner calls retain bounded in-memory previews and exact totals but do not persist dead letters. This sink adds no asynchronous queue or accumulating buffer.

The evidence DB migrates schema v1 to v2 additively under its existing exclusive ownership lock. `lab_dead_letter` stores `(run_id, row_index)` and an immutable JSON record. A transaction locks the Run, accepts writes only while RUNNING, and treats identical repeated rows as idempotent; conflicting records fail. Persisted rows survive later execution failure. There is no atomic transaction spanning valid-row ingestion and dead-letter storage, nor recovery guarantee for rows not yet acknowledged when the process stops.

Rows contain the parser's first-sheet, zero-based index, validation stage, fixed reason and formatted cell strings (including empty gaps), not workbook formulas/styles. JSON is limited to 64 KiB per row with pre-serialization bounds. Oversized rows and storage errors fail the Run without truncating evidence. API pages are limited to 100 rows and terminal Runs, preventing concurrent validators' out-of-order writes from falling behind a pagination cursor. Original cell values are intentionally retained in the local evidence DB/API, never added to structured failure logs.

## Bounded input and validation memory

HTTP uploads transfer ownership of a file-backed `InputPayload` to the submission service. A process-wide nonblocking 16-slot admission limit includes uploads being staged, queued and running. Size/hash calculation uses an 8 KiB buffer; prepare/bind/rejection failures close the payload, and the execution task closes it in finally. Local byte-array constructors remain for tests and callers that already own bytes; HTTP never uses them.

The parser copies its InputStream to a second bounded temporary file to preserve the existing reader contract, then opens POI in read-only file mode. ZIP expansion is scanned with an 8 KiB buffer before shared strings/styles are loaded: total 256 MiB, non-worksheet metadata 8 MiB, 1024 entries, compressed input 50 MiB. SAX limits cell text accumulation and completed raw rows have cell/character caps. This trades additional disk IO and an expansion scan for explicit package/row memory bounds. These bounds do not claim a fixed total JVM heap or throughput improvement.

ValidationErrors synchronizes a bounded preview and the three fixed validation reason counters. Every rejected row still reaches the durable sink before it is counted; truncating the preview cannot change Run totals. Shutdown durability, crash leftovers, total heap measurement and throughput comparison remain separate units.

## Pipeline termination contract

Input failure and coordinator interruption stop production but permit bounded drain of acknowledged rows: raw END signals, validator completion, mapped END signals, insert completion. One monotonic deadline covers that drain. Interrupts during drain are recorded, cleared temporarily and restored after cleanup. A separate deadline covers executor termination; no new progress callbacks start, and an in-flight callback may finish normally. Callback failures abort the channels and cannot be hidden by scheduled-task cancellation. Additional suppressed failures are capped at eight.

Worker/DB/dead-letter failure or drain timeout aborts further writes and interrupts workers. No uncertain batch is replayed. `PartialExecutionFailure` carries acknowledged row/retry counts and reason totals to both runners; durable Runs distinguish INTERRUPTED from FAILED and retain this summary. `rows.unconfirmed` is the nonnegative difference between acknowledged produced rows and acknowledged inserts/validation failures. It is not a durable row checkpoint or a promise that an uncertain DB commit did not happen. If any executor survives cleanup, `shutdown.progress=UNCONFIRMED` warns that counts may still change.

SINGLE_THREAD has no stage executors: input failure flushes a safe existing chunk, while callback/storage failure does not replay the chunk. Its IO remains cooperative. Buffer copying and ZIP preflight observe interrupts between reads; arbitrary blocked InputStream/JDBC methods cannot be forcibly stopped by this contract. Process crash recovery, late writes from uncooperative drivers and durable replay of unconfirmed rows require separate mechanisms.
