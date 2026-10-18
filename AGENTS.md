# AGENTS

## 1. Project Overview
- Purpose: turn backend problems met in practice into reproducible experiments and keep hypothesis, conditions, results and interpretation as one evidence bundle. It is not a production ingestion service or a general benchmark; results hold only for the recorded environment.
- Repository: `engineering-lab`, a monorepo with a Java 25 / Spring Boot 4 root experiment platform and five restored modules in the independent `career-lab/` Gradle build.
- Career modules retain Java 21 / Spring Boot 3.3.4 and `com.portfolio.*` packages. Read [career-lab/README.md](career-lab/README.md); run `./scripts/career-lab.sh test` with Docker when modifying these modules or their build, in addition to the required root test.
- Current experiments: Excel ingestion, queue contention, and IO endpoint comparison, plus repeated-run series, baseline regression checks and parameter sweeps over them.
- Pipeline goal: build a parallel Excel ingestion pipeline with bounded queues for backpressure and chunked batch inserts for efficient database writes.
- Core architecture:
  - Use producer-consumer stages with explicit boundaries.
  - Use bounded queues between major stages.
  - Use configurable worker pools (`transform`, `insert`).
  - Use chunked inserts with size/time-based flush conditions.
- New code belongs under `com.pnomeer.lab`, including the entrypoint `EngineeringLabApplication` and the workbook generator.
- Read [docs/codex.md](docs/codex.md) when locating code, choosing verification commands, or checking implementation gaps. Read [docs/architecture.md](docs/architecture.md) for architectural changes; its target layout is not the current module structure.
- Standards below describe required design direction, not a claim that every capability already exists.

## 2. Development Workflow
For every logical unit of work, including documentation and Codex setup:
1. Inspect `git status --short` and relevant code. Preserve unrelated user changes and existing staged files.
2. Implement one coherent change, with relevant tests and documentation. For complex work, outline independently verifiable units and finish each before committing it.
3. Run `./gradlew test` before committing. Targeted tests are useful during development but do not replace this command.
4. Review the diff and run `git diff --check`. Stage only files or hunks belonging to the completed unit; inspect `git diff --cached`.
5. Create a Conventional Commit using section 4 and commit the completed unit without waiting for a separate commit request. Do not accumulate unrelated tasks into one commit.
6. Check `git status --short` and report the change, test result, and commit hash to the user (in Korean by default).

If tests or Git operations fail, resolve problems within scope and retry. If blocked by the environment or an unrelated failure, report the exact blocker and leave the task explicitly incomplete; never claim a passing test or successful commit. Do not disable tests to obtain a green build. Read-only questions do not require empty commits. Do not amend, reset, or push existing history unless requested.

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
- Keep subjects in English to match the existing history.
- Choose the scope by the changed responsibility: `pipeline`, `queue`, `validation`, `concurrency`, `io`, `core`, `metrics`, `ui`, `architecture`, `build`, or `codex` (extend when needed).
- Use `docs(codex)` for agent guidance; use `chore(codex)` for executable tooling/configuration changes.

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
