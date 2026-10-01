# AGENTS

## 1. Project Overview
- Goal: build a parallel Excel ingestion pipeline with bounded queues for backpressure and chunked batch inserts for efficient database writes.
- Core architecture:
  - Use producer-consumer stages with explicit boundaries.
  - Use bounded queues between major stages.
  - Use configurable worker pools (`transform`, `insert`).
  - Use chunked inserts with size/time-based flush conditions.

## 2. Development Workflow
After every completed task:
1. Run tests (`./gradlew test`).
2. Stage modified files.
3. Create a Conventional Commit message.
4. Commit the changes.

## 3. Coding Standards
- Keep memory bounded; stream workbooks when possible instead of loading full files.
- Prefer deterministic, idempotent writes.
- Make backpressure visible through metrics before optimizing.
- Fail rows precisely; do not allow silent data loss.
- Include configuration for queue capacities, batch size, wait timeout, and worker counts.
- Implement retry/backoff for batch inserts with bounded attempts.
- Route validation failures to dead-letter output with row context.
- Implement graceful shutdown that drains queues and flushes partial batches.
- Emit metrics for throughput, queue depth, insert latency, retries, and failures.
- Use structured logs with `job_id`, stage, and failure reason.

## 4. Commit Message Convention
Use Conventional Commits:

`<type>(<scope>): <short summary>`

Allowed types:
- `feat`
- `fix`
- `refactor`
- `test`
- `docs`
- `chore`
- `perf`

Rules:
- Use present tense.
- Keep subject line under 72 characters.
- Do not include trailing punctuation.
- Add a body when the change is complex.
- Keep one logical change per commit.

Examples:
- `feat(pipeline): add parallel insert worker`
- `fix(validation): prevent null value in required column`
- `refactor(queue): extract bounded channel abstraction`
- `test(pipeline): add backpressure behavior test`
- `docs(architecture): update pipeline design`

## 5. Testing Rules
- Required pre-commit command: `./gradlew test`.
- Cover queue blocking semantics and batch flush behavior with unit tests.
- Cover end-to-end ingestion and dead-letter output with integration tests.
- Include a performance test that demonstrates bounded memory and better throughput than a single-thread baseline.

## 6. Operational Guidelines for AI Agents
- Do not introduce unbounded in-memory buffers.
- Do not bypass retry or dead-letter handling for convenience.
- Do not ship configuration changes without updating defaults and documentation.
