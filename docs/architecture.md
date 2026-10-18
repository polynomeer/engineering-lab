# Architecture

The implemented platform baseline is described in [Experiment Platform v2](experiment-platform-design.md), with completed work in [the implementation plan](implementation-plan.md). The proposed next iteration is [Shared Dashboard and Career Integration](dashboard-integration-design.md), tracked in [its migration plan](dashboard-integration-plan.md); its completed and pending units are tracked separately. The layout below is the earlier architectural direction, not a list of existing Gradle modules.

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
the standalone frontend supports Run evidence browsing (D1) and schema-driven queue/IO
execution forms with presets and comparison exports (D2 first unit). Excel upload and progress adapters plus DB/IO tools are implemented.
Browser state import/export preserves legacy keys and commits selected settings in one localStorage write. Opt-in legacy redirects, a streaming standalone server and optional bootDashboardJar reuse the same SPA build. External worker integration remains planned work. Career build
projects are discovered from explicit `lab-module.properties` declarations, so new
projects do not require edits to a fixed Gradle include list.

## Implemented execution boundary and admission (D3)

`DurableExperimentRunner` retains admission/shutdown, Run creation/start/completion,
Git provenance, the bounded metric writer and preview, and all RunStore access.
The package-level `ExperimentExecutor` receives streaming metric, dead-letter and
artifact sinks, plus a metric-drain callback; it cannot mutate Run lifecycle.
`InProcessExecutor` owns experiment invocation and JVM/trace/JFR/log observation.
The runner captures the controller environment in the existing Run metadata.
Existing public runner constructors select this executor.
This execution boundary introduces no additional queue or retained evidence list.

Cleanup preserves trace close → JVM sampler stop/final capture → JFR finish/register/
close → metric drain → log finish/register → central Run completion. Drain failures
are recorded in the final log and fail the Run. Partial progress and interrupted
status cross the boundary; the runner restores interruption after persisting completion.
Observation points stay out of the bounded experiment preview, as before.

`ExperimentExecutors` provides immutable server-owned experiment-to-executor bindings,
per-executor active limits and an optional exclusive flag per binding. Scenario input
cannot override routing. Unknown executor references fail construction. Existing
experiments default to IN_PROCESS; production still registers only that executor.

`ExecutionAdmission` atomically applies the global and executor limits and excludes
all other executions while an exclusive Run is active. The ledger is bounded by the
global limit and keyed by Run ID, so nested runs on one thread still consume separate
slots. Duplicate execution attempts cannot fail the active owner's Run. Leases release
on all runner exits, and shutdown closes submission, drains admitted owners up to the
existing timeout, then interrupts them. Rejection persistence holds the admission lock.
No waiting queue is added; conflicting queued attempts are recorded FAILED as before.
Existing task-executor queues and the default global maximum of eight are unchanged.

The app still registers only IN_PROCESS. The existing metadata environment/Git always describe the
controller; the LocalJvmExecutor adapter now records actual worker environment/provenance through
the execution extension after HELLO; those facts remain unknown before the handshake.
Worker observations never reuse controller collectors. Routing isolation is verified
with a test executor and the LocalJvmExecutor integration uses a real Java 21 fixture JVM.

## Additive execution evidence (schema v3)

`lab_execution` stores immutable execution definitions and optional worker identity,
monotonic phase and evidence completeness. `lab_check` stores bounded immutable check
records with sequence cursors and unique check IDs. RunStore transactions serialize
these writes against the parent Run, reject terminal mutations and conflicting retries,
and prevent registered executions from succeeding with incomplete evidence. Restart
recovery retains partial evidence and clears completeness for interrupted Runs.

Existing metadata, Runs, metrics, dead letters and artifacts are unchanged. No execution
rows are inferred for old Runs. The app supplies catalog-owned module IDs to the runner,
which atomically creates Run/definition and records in-process phases. Successful completion
seals evidence after observation/storage cleanup; failed/interrupted runs remain incomplete.
Compatibility constructors without a catalog retain unrecorded execution semantics.
The additive execution/check endpoints and a separate schemaVersion=2 export are implemented.
Existing Run payloads and schemaVersion=1 export remain unchanged. Comparison adds effective
worker conditions/cautions inside its existing shape, with unknown worker facts left null.
The `/dashboard/v1` capabilities, modules and experiment extension endpoints now
project the registered catalog and enabled observation settings without changing legacy
APIs. Only IN_PROCESS is advertised; worker protocols remain empty and localJvm and
cancellation are false. Startup-validated in-process bindings report READY. The frontend
recognizes capability schema version 1 and falls back for absent or unknown versions,
while surfacing server errors. Dedicated execution/check panels and worker integration
remain pending.
The storage completion flag records the executor's assertion. The worker codec and process transport below are connected through a server-registered
LocalJvmExecutor adapter in integration tests; production catalog registration remains pending.

Opening v1/v2 takes a consistent H2 ZIP backup before additive DDL. The schema version
advances last, interrupted DDL is repeatable, and unknown versions fail before changes.
The automatic backup covers DB bytes only. Downgrade requires a separate restored pre-v3
storage copy, including artifact files; the v3 original must be retained because existing
legacy HTTP exports do not contain execution/check extensions; the separate v2 export does. See [storage recovery](run-storage.md).

## Worker wire contract (D4 first unit)

`contracts/worker/v1` defines the command/event JSON Schemas and shared synthetic fixtures.
`career-lab/worker-support` is a Java 21 library discovered through infrastructure metadata;
it has no Spring or root core dependency and does not register runnable experiments.
Its synchronous reader/writer bounds UTF-8 frame and total bytes, event counts and JSON
structure, checks expected identity and contiguous sequence, enforces phase order and
verifies final event counts. Invalid/truncated streams fail closed. RESULT and exit 0
are transport evidence only; a parent-side transport now verifies the manifest jar, launches an isolated copy, drains
bounded pipes, applies deadlines and stops its direct child. Interruptible evidence sinks
and their flush acknowledgement are tested with real Java 21 fixture processes. A
worker-side stdin monitor exposes parent disconnect. RunStore worker/metric/check mapping is now tested through the root runner; actual
worker packaging, JFR/log import, Docker readiness and container/descendant cleanup
remain pending; the runtime still advertises no LOCAL_JVM executor.
See [the wire contract](../contracts/worker/v1/README.md) for precise defaults and limits.

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

## Local JVM evidence adapter

The root build consumes only `worker-support` as a Java 21 Gradle composite dependency;
Career Boot applications keep their independent build. Root tests build a Java 21 fixture
jar and launch it against the Java 25 runner and H2. Both JDKs are required to build/test.
`ExperimentExecutor.Output` exposes worker/check sinks without granting Run lifecycle
access. `LocalJvmExecutor` registers HELLO identity, forwards phase and allowlisted metrics,
assigns independent check cursors, and waits for EOF/exit/metric drain/process cleanup
before returning an outcome. Only the central runner seals and completes a successful Run.
Failed Runs retain acknowledged partial evidence. An optional WorkerArtifactImporter verifies REPORT paths, sizes and hashes using pinned
secure directory handles, copies with bounded buffers, and registers only the verified
controller-owned copy. Per-run file/count/total limits apply. Unsupported kinds and
unconfigured importers fail closed. Report download integration remains pending. Opt-in Spring registration now binds the sequence deployment; default capabilities remain unchanged. Storage still requires adjacent phase transitions; a worker
that skips MEASURING must return failure directly from PREPARING, without a FINALIZING jump.


## Career sequence deployment

`mcp-platform` now shares its bounded `ConcurrentAllocationHarness` between existing
JUnit checks and a `labRuntime` source set. That source set packages a dedicated Java 21
Boot jar with worker-support and Testcontainers; the ordinary application runtime stays
unchanged. `sequenceWorkerManifest` emits a build-relative descriptor and streamed jar
SHA-256. The server must still register trusted paths before enabling this experiment.
The MySQL dependency is unpacked by Boot so Testcontainers can extract its configuration.

Each worker starts a fresh MySQL 8.0.39 container, uses the existing transactional allocator
beans, and emits metrics, expectation checks and a bounded JSON report. PREPARING covers
container/schema startup; MEASURING covers concurrent allocation; FINALIZING covers report
handoff and cleanup. RESULT follows context/container cleanup. BEFORE expects a synthetic
race (including its existing 50ms delay), not production throughput. Parent loss/deadline
records the first cancellation reason independently of the interrupt flag. Checks at stage
boundaries and an atomic completion point after cleanup prevent consumed interrupts from
becoming successful outcomes. A single 30-second forced-stop fallback bounds uncooperative
cleanup. Explicit Ryuk disabling rejects before database creation. Deployment fault tests
cover parent EOF, deadline, hard kill, report failure and cross-session container isolation
on a healthy Docker daemon. Daemon/reaper failures, restart reconciliation and arbitrary
process descendants still need deployment-level handling.
Production catalog registration and LOCAL_JVM capability are opt-in through `lab.career.sequence.enabled`.
See [deployment and readiness](career-worker-deployment.md). Additional module beans extend the server catalog
without modifying its central module list. Registered metric definitions are merged immutably into the HTTP
schema/comparison views. A deployment binding selects the external executor and worker evidence definition;
its preflight runs before Run creation. The current worker binding permits one exclusive run.

Registered workers now acquire a persistent, exclusive admission marker before process launch.
Only a successful executor outcome after transport cleanup and metric drain releases the matching
marker. Failure, interruption or controller death retains it, and registration readiness checks it
before cached deployment probes. This bounds repeat admission after uncertain cleanup; it does
not reconcile Docker resources or arbitrary descendants. The marker is one file per registration,
limited to 4 KiB, and requires the same administrator-owned workspace and registration ID across
restarts. See [recovery guarantees and operator procedure](worker-recovery.md).


`WorkerProcessTree` observes at most 128 descendant identities at 50ms intervals while the
controller lives. Cleanup stops the observer, checks start instants before signalling, and
uses the existing stop grace for graceful and forced termination. Uncertain tracking or
termination fails the execution and keeps the registration recovery marker. This is bounded
observation, not OS containment: pre-observation detachment, controller death and Docker
resources still need separate reconciliation. See [scope and limits](worker-recovery.md).


Sequence deployment now journals a random ownership token, Run, artifact hash and Docker daemon
identity before container creation, while holding a workspace file lock for the worker lifetime.
A separate bounded `--recover` process takes the same lock and validates the journal, deployment,
daemon, exact container name/image/labels and full ID before removal. It retains evidence and
never clears controller admission. This is operator-invoked reconciliation, not automatic startup
recovery or proof against late Docker requests. See [recovery contract](sequence-container-recovery.md).


Opt-in `lab.career.sequence.recover-on-startup` now runs once during registration after exclusive
RunStore ownership and interrupted-Run recovery. A separate controller receipt binds the exact
admission marker to a returned transport result and workspace. Only SUCCEEDED or sequence
EXPECTATION_NOT_MET outcomes qualify; uncertain preparation, cleanup, interruption and transport
failures stay blocked. A verified same-artifact recovery subprocess must confirm scoped resource
reconciliation before the matching receipt/marker can be released. Historical Run evidence is
unchanged. Missing receipts and arbitrary crash recovery still require operator reconciliation
and stronger isolation. See [startup contract](sequence-container-recovery.md#종료-확인-기록이-있는-실행의-시작-시-복구).


Startup reconciliation subprocesses now share `WorkerProcessTree` with experiment transport.
A valid recovery response is necessary but insufficient: observed descendants, the direct
process and IO must also finish cleanup before admission is released. The recovery path uses
2-second observer/graceful/forced waits and a further 2-second IO wait; interruption restores
the caller flag and fails recovery. This does not extend observation into OS containment or
make missing completion receipts eligible for automatic recovery.


The next containment path is a controller-owned, nonprivileged worker container with a
private PID namespace. The design keeps Docker resource creation outside the worker and
retains LOCAL_JVM compatibility. An isolated Docker fixture verifies reparented, separate-session
processes surviving controller death and scoped reclamation by a fresh JVM. This is a boundary
proof, not a production executor or authorization to clear legacy unconfirmed markers.
See [the lifecycle, compatibility and staged implementation design](worker-container-isolation.md).


The I2 container lifecycle adapter journals create/start intent before mutation and confirmed
IDs/responses afterward. Six bounded append-only records share an intent hash and an exclusive
file lock. Unconfirmed create/start requests require operator reconciliation; known-ID removal
can resume after response loss. The CLI backend pins the image/command and enforces the fixed
isolated-v1 profile before destructive actions. It retains all journal files and does not clear
central admission or alter Run evidence. Protocol transport and module resource provisioning
remain separate implementation units; see [I2 contract](worker-container-isolation.md#i2-수명주기-어댑터-계약).


I3 adds an explicitly wired container transport to the shared external-worker evidence mapper.
A confirmed I2 boundary hosts a non-TTY Java 21 exec process; protocol EOF, exit, evidence flush
and container removal are all required before returning a result. Bounded REPORT transfer
accepts one regular file and delegates hash verification to the existing artifact importer.
CONTAINER execution evidence has worker measurement scope, including unknown-worker handling
in comparisons. I5 adds opt-in container registration and admission recovery. Existing LOCAL_JVM recovery behavior is unchanged.
See [the I3 image, transport and compatibility contract](worker-container-isolation.md#i3-protocol-transport와-중앙-증거-연결).


I4 injects `ContainerWorkerResources` into the transport. The career adapter
`experiments/career/SequenceContainerResources` owns a per-run internal network and bounded
MySQL container; the Java 21 worker receives a fixed endpoint and ephemeral credentials.
Worker removal must be confirmed before DB removal, then the empty network is removed.
Resource journals survive failure; I5 reopens them under exclusive registration and RunStore ownership.
LOCAL_JVM keeps its existing Testcontainers ownership and recovery contract.
See [I4 resource contract](worker-container-isolation.md#i4-controller-소유-sequence-자원).


I5 introduces the `WorkerRegistration` routing/capability contract and the separate v2
`ContainerRecoveryGuard`. Its forced admission record binds deployment, resources and workspace
before external mutation. `ContainerWorkerRegistration.recover` checks terminal Run/execution
identity, reconciles the worker PID boundary, then module resources, before deleting only the
unchanged v2 marker. Sequence network recovery resumes confirmed deletion, never uncertain
create/start. Both execution modes block when either marker format remains. Interrupted evidence
is preserved; no recovery path seals or reruns it. The default remains LOCAL_JVM and startup
recovery is disabled unless explicitly enabled. See the [I5 contract](worker-container-isolation.md#i5-같은-저장소에서-시작-시-복구와-접수-재개).

## MCP check scenarios

D5-1 adds a separate DB-free Java 21 checks deployment. ArchUnit rules and deliberate violation fixtures live in `checkRuntime`, shared with JUnit but excluded from the ordinary application and sequence jar. The controller registers architecture and validation independently using container resources NONE and separate admission guards. `McpModuleConfiguration` advertises one module containing only enabled scenarios. Check-only registrations omit METRICS presentation; protocol v1 and sequence settings remain compatible. See [checks deployment and semantics](mcp-checks-worker.md).

## Equity scenarios

D5-2 adds one Java 21 equity deployment with three independently guarded registrations.
The controller owns an internal network plus a bounded MySQL or Redis dependency per Run.
The worker reuses existing deletion, lock and registration implementations without Docker access.
Registration uses a bounded completion window and lazy synthetic input; confirmed partial writes
remain visible on failure. Deletion distinguishes total duration from the longest JDBC step proxy;
Redis produces checks only. Sequence resource profiles and recovery journals remain unchanged.
See [deployment, metrics and failure semantics](equity-worker.md).

D5-3 adds a Java 21 batch-excel memory worker with one fresh JVM/MySQL per variant. Existing jobs and exporters are reused; a constant-space sampler records heap used without explicit GC around work only. Controller resource ownership is shared in CareerContainerResources; equity namespaces, profiles and environment contracts remain unchanged. Batch uses its own journal/profile. See [measurement and deployment contracts](batch-memory-worker.md).

## MDS scenarios

D5-4 adds a check-only Java 21 MDS worker for settlement guards and message idempotency. Existing Spring services and AOP run against a controller-owned MySQL using the unchanged CareerContainerResources lifecycle and a separate MDS journal/profile. Two concurrent deliveries and one explicit retry bound the fixture. Only confirmed MySQL duplicate-key errors enter duplicate handling; infrastructure failures propagate. The scenario does not claim SQS delivery semantics or crash-safe exactly-once processing. See [MDS contracts](mds-worker.md).

## Creator scenarios

D5-5 adds a check-only Java 21 Creator worker, with isolated Redis for session rotation and MySQL for mapping/post-commit scenarios. It uses unchanged controller resource ownership contracts. Refresh consumption and token rotation share one Lua invocation; explicit transfer updates only the selected creator’s programs with mapping/history in one transaction. Worker-only bounded recording clients inspect existing asynchronous listeners after draining their executor. External integrations remain stubs; no outbox or crash durability is claimed. See [Creator contracts](creator-worker.md).

## Worker retained storage admission (D6)

A controller-wide WorkerStorageBudget wraps registered worker executors. Preflight and execution
admission scan retained workspace/report bytes with bounded entry count/depth; synchronized leases
reserve headroom across callers. No cleanup is inferred from quota rejection. This is an admission
budget, not a filesystem hard quota, and excludes H2/uploads/controller captures and Docker volumes.
Managed REPORT downloads reuse the secure snapshot/hash verification used for JFR/logs.
See [operating limits](dashboard-operations.md).

## Shared bounded batch primitive (P5)

Root Excel and Career Equity registration now share the dependency-free Java 21
`career-lab/pipeline-support` library through composite/project dependencies.
Only batch capacity and synchronous success-before-clear behavior are shared.
Queues, admission, time flush, transactions, retry, dead-letter and shutdown
remain case-owned. See [the pipeline contract](pipeline-contract.md).
