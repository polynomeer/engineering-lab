package com.pnomeer.lab.registry.measurement;

import java.util.List;

/** Trusted Spring registration; no user-supplied executable definitions. */
public record MeasurementContribution(List<MeasurementContract> contracts) {
    public MeasurementContribution {
        if (contracts == null || contracts.size() > 256) throw new IllegalArgumentException("Too many measurement contracts");
        contracts = List.copyOf(contracts);
    }
}
