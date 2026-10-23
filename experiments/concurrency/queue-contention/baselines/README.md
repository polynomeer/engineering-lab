# Queue contention baselines

`small-buffer.baseline.json` is the baseline file the nightly
[Performance regression](../../../../.github/workflows/regression.yml) workflow checks against.
It holds the raw measured values of one series and the environment it ran in.

The file is not committed yet. Generate it on the CI runner class, not on a workstation: run the
workflow manually with `refresh-baseline`, download `regression-evidence`, and commit the file
here. Until then the nightly job only measures and uploads a candidate file.
