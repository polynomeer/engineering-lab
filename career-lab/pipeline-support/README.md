# Pipeline support

Java 21 dependency-free batch primitive shared by Excel ingestion (root Java 25)
and Equity registration (Java 21). Infrastructure library, not an executable experiment.

See [the pipeline contract](../../docs/pipeline-contract.md) for ownership, bounds,
flush, failure and compatibility rules. `BoundedBatch` is thread-confined and hands
its synchronous sink a read-only borrowed view. Only successful writes clear the batch;
failed writes are retained but never automatically retried.

Run `./scripts/career-lab.sh :pipeline-support:test` from the repository root.
Both full verification commands also cover this library. Run the Career and root
wrappers sequentially because they share this library's build directory.
