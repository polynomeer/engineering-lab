package com.pnomeer.lab.registry.measurement;

import com.pnomeer.lab.registry.ExperimentCatalog;
import java.util.*;

public final class MeasurementCatalog {
    public record Snapshot(int schemaVersion, List<MeasurementContract> contracts) { }
    private final Map<String, String> encoded;
    private final Snapshot snapshot;
    public MeasurementCatalog(ExperimentCatalog active, List<MeasurementContribution> contributions) {
        if (contributions.size() > 256) throw new IllegalArgumentException("Too many measurement contributions");
        var all = new TreeMap<String, MeasurementContract>();
        var properties = new HashMap<String, MeasurementContract.Property>();
        for (var contribution : contributions) for (var contract : contribution.contracts()) {
            if (all.size() >= 4096 || all.putIfAbsent(contract.experimentId(), contract) != null)
                throw new IllegalArgumentException("Duplicate or excessive measurement contracts");
            contract.encode();
            for (var property : contract.properties()) {
                var old = properties.putIfAbsent(property.id(), property);
                if (old != null && !old.equals(property)) throw new IllegalArgumentException("Conflicting verification property: " + property.id());
            }
        }
        var ids = active.experiments().stream().map(e -> e.id()).collect(java.util.stream.Collectors.toSet());
        var contracts = all.values().stream().filter(c -> ids.contains(c.experimentId())).toList();
        snapshot = new Snapshot(1, contracts);
        var values = new HashMap<String, String>();
        contracts.forEach(c -> values.put(c.experimentId(), c.encode()));
        encoded = Map.copyOf(values);
    }
    public Snapshot snapshot() { return snapshot; }
    public Map<String, String> parameters(String experimentId) {
        String value = encoded.get(experimentId);
        return value == null ? Map.of() : Map.of(MeasurementContract.PARAMETER, value);
    }
}
