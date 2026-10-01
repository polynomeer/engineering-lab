package com.pnomeer.lab.experiments.pipeline;

public final class PipelineExperimentCapture {
    private volatile PipelineRunner.PipelineRunResult runResult;

    public PipelineRunner.PipelineRunResult getRunResult() {
        return runResult;
    }

    public void setRunResult(PipelineRunner.PipelineRunResult runResult) {
        this.runResult = runResult;
    }
}
