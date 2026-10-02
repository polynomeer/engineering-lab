# engineering-lab

A monorepo hosting multiple independent lab projects. Each module is a hands-on engineering experiment or a from-scratch reconstruction of a real production problem, built to prove specific claims with working code and tests (including Testcontainers-backed MySQL/Redis integration tests, not just unit tests).

## Modules

| Module | Description |
|---|---|
| [engineering-experiments](engineering-experiments/) | Hands-on experimentation workspace for backend engineering topics — Excel ingestion pipeline (bounded queues, worker pools, streaming parsing), queue-contention concurrency benchmarking, and blocking/virtual-thread/NIO-selector IO labs, with live dashboards and benchmark-style metrics. |
| [career-lab/equity-system](career-lab/equity-system/) | Reconstruction of an equity-share system's transaction-stability/concurrency improvements — bulk deletion, Redis distributed locking, parallel registration pipeline. |
| [career-lab/batch-excel](career-lab/batch-excel/) | Reconstruction of a large-batch/Excel processing optimization project. |
| [career-lab/mcp-platform](career-lab/mcp-platform/) | Reconstruction of a music content platform (MCP) overhaul — contract code issuance concurrency, ArchUnit architecture guardrails, validation pipeline. |
| [career-lab/mds-distribution](career-lab/mds-distribution/) | Reconstruction of a global Music Distribution System (MDS) — settlement lock guard and idempotent event processing. |
| [career-lab/creator-studio](career-lab/creator-studio/) | Reconstruction of a Creator Studio session-rotation and Creator-Producer mapping-integrity project. |

`career-lab/` groups the 5 reconstruction labs — each a from-scratch rebuild of a real production Problem → Decision story — separately from `engineering-experiments`, which is open-ended experimentation rather than fact reconstruction. Each module's own `README.md` has full detail: architecture, API/usage, how to run it locally, and (for the 5 reconstruction labs) the Problem → Decision → Impact story and `[FACT 기반 재현]` / `[NEW-DESIGN]` annotation convention they use to separate reproduced facts from new implementation decisions.

## Build

This is a single Gradle multi-project build (one root wrapper and `settings.gradle` for all 6 modules). Run every module's tests at once:

```bash
JAVA_HOME=<a JDK 21 home> ./gradlew test
```

Docker must be running locally for the Testcontainers-backed modules (`equity-system`, `batch-excel`, `mcp-platform`, `mds-distribution`, `creator-studio` all spin up MySQL/Redis containers in their tests).

### Running a single module's tests

Target one subproject with Gradle's `:<module>:test` task syntax instead of running the whole suite:

```bash
JAVA_HOME=<a JDK 21 home> ./gradlew :engineering-experiments:test
JAVA_HOME=<a JDK 21 home> ./gradlew :career-lab:equity-system:test
JAVA_HOME=<a JDK 21 home> ./gradlew :career-lab:batch-excel:test
JAVA_HOME=<a JDK 21 home> ./gradlew :career-lab:mcp-platform:test
JAVA_HOME=<a JDK 21 home> ./gradlew :career-lab:mds-distribution:test
JAVA_HOME=<a JDK 21 home> ./gradlew :career-lab:creator-studio:test
```
