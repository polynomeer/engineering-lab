package com.pnomeer.lab.core;

import java.util.Objects;
import java.util.function.Consumer;

/** Run-scoped evidence sink. Returning from the sink acknowledges persistence. */
public record ExecutionEvidence(String runId, Consumer<DeadLetter> deadLetters) {
    public ExecutionEvidence { Objects.requireNonNull(runId); Objects.requireNonNull(deadLetters); }
}
