# CLAUDE.md

@AGENTS.md

## Claude Code notes

`AGENTS.md` above is the single source of truth for workflow, coding standards, testing, and the commit convention (section 4). This file only adds Claude Code specifics; change shared rules in `AGENTS.md`, not here.

- Locate code and pick starting tests from [docs/codex.md](docs/codex.md) (code map, execution flow, per-area test table, known gaps). Read only the section you need.
- Gradle toolchains select JDK 25 (root) and JDK 21 (worker/career) automatically; the shell's default `java` may differ.
- Never run root `./gradlew ...` and `./scripts/career-lab.sh ...` at the same time: both write the shared `career-lab/worker-support` build output.
- Docker suites (`careerSequenceTest`, `workerContainerIsolationTest`, `containerWorkerTest`, `sequenceContainerTest`, `checksContainerTest`, `equityContainerTest`) are outside `./gradlew test`. Run them only when the change touches that area. Never run `careerDaemonRecoveryTest` without explicit user approval (privileged engine restart).
- `frontend/` uses Node 24.19.0 / npm 11.6.0. Verify with `npm run typecheck`, `npm test`, `npm run build` (see [frontend/README.md](frontend/README.md)); check UI behavior changes in the browser as well.
- `.lab/` holds local run evidence, backups, and deployment state (gitignored). Do not delete or edit it. Production rollout needs backup and separate acceptance per [docs/releases/](docs/releases/), not just source edits.
- Commits: one logical unit per commit, `<type>(<scope>): <summary>` per `AGENTS.md` section 4. Use scope `claude` for changes to `CLAUDE.md` or `.claude/` (`docs(claude)` for guidance, `chore(claude)` for settings). Report results to the user in Korean.
