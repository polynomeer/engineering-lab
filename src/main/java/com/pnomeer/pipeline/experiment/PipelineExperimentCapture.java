package com.pnomeer.pipeline.experiment;

import com.pnomeer.pipeline.PipelineRunner;

public final class PipelineExperimentCapture {
    private volatile PipelineRunner.PipelineRunResult runResult;

    public PipelineRunner.PipelineRunResult getRunResult() {
        return runResult;
    }

    public void setRunResult(PipelineRunner.PipelineRunResult runResult) {
        this.runResult = runResult;
    }
}
