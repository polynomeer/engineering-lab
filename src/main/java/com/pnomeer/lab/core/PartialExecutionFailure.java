package com.pnomeer.lab.core;

/** A failed execution with acknowledged progress, not a resumable checkpoint. */
public final class PartialExecutionFailure extends RuntimeException {
    private final ExperimentSummary summary;
    private final boolean interrupted;
    public PartialExecutionFailure(Throwable cause, ExperimentSummary summary, boolean interrupted) {
        super(interrupted ? "Execution interrupted; see partial progress" : "Execution failed; see partial progress", cause);
        this.summary = summary; this.interrupted = interrupted;
    }
    public ExperimentSummary summary() { return summary; }
    public boolean interrupted() { return interrupted; }
}
