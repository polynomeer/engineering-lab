package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.storage.RunSeries;
import com.pnomeer.lab.core.storage.RunSweep;
import com.pnomeer.lab.scenario.ScenarioDefinition;
import com.pnomeer.lab.scenario.ScenarioDocuments;
import com.pnomeer.lab.scenario.ScenarioException;

import java.util.*;

/** Expands a base definition and a value grid into one validated definition per combination. */
public final class SweepGrid {
    private SweepGrid() { }

    public record Point(int position, Map<String, Object> overrides, ScenarioDefinition definition) { }

    public static Map<String, List<Object>> normalize(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) throw new ScenarioException("grid needs at least one parameter");
        if (raw.size() > 4) throw new ScenarioException("grid supports at most 4 parameters");
        var grid = new LinkedHashMap<String, List<Object>>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) throw new ScenarioException("grid keys must be parameter names");
            if (!(entry.getValue() instanceof List<?> values) || values.isEmpty() || values.size() > 12)
                throw new ScenarioException("grid." + key + " needs 1..12 values");
            var unique = new LinkedHashSet<Object>();
            for (Object value : values) {
                if (!(value instanceof String || value instanceof Boolean || ScenarioDocuments.isInteger(value)))
                    throw new ScenarioException("grid." + key + " values must be strings, booleans or integers");
                if (!unique.add(value instanceof Integer i ? (Object) i.longValue() : value)) throw new ScenarioException("grid." + key + " repeats a value");
            }
            grid.put(key, List.copyOf(values));
        }
        return grid;
    }

    public static List<Point> expand(ScenarioDefinition base, Map<String, List<Object>> grid, int repetitions, int warmup) {
        int points = grid.values().stream().mapToInt(List::size).reduce(1, Math::multiplyExact);
        if (points > RunSweep.MAX_POINTS) throw new ScenarioException("grid expands to " + points + " points; at most " + RunSweep.MAX_POINTS);
        if ((long) points * (repetitions + warmup) > RunSweep.MAX_RUNS)
            throw new ScenarioException("sweep would run " + (long) points * (repetitions + warmup) + " runs; at most " + RunSweep.MAX_RUNS);
        if (repetitions < 1 || repetitions > RunSeries.MAX_REPETITIONS || warmup < 0 || warmup > RunSeries.MAX_WARMUP)
            throw new ScenarioException("repetitions must be 1.." + RunSeries.MAX_REPETITIONS + " and warmup 0.." + RunSeries.MAX_WARMUP);
        String suffixLimit = base.scenario().id() + ".p" + (points - 1);
        if (suffixLimit.length() > 200) throw new ScenarioException("scenario id is too long for sweep point suffixes");
        var keys = List.copyOf(grid.keySet());
        var result = new ArrayList<Point>();
        int[] index = new int[keys.size()];
        for (int position = 0; position < points; position++) {
            var overrides = new LinkedHashMap<String, Object>();
            for (int k = 0; k < keys.size(); k++) overrides.put(keys.get(k), grid.get(keys.get(k)).get(index[k]));
            var parameters = new LinkedHashMap<>(base.parameters());
            parameters.putAll(overrides);
            var definition = new ScenarioDefinition(base.schemaVersion(), base.experiment(),
                    new ScenarioDefinition.Scenario(base.scenario().id() + ".p" + position, base.scenario().hypothesis()),
                    parameters, base.measurement(), base.input());
            result.add(new Point(position, overrides, definition));
            // Odometer order: the last grid key changes fastest.
            for (int k = keys.size() - 1; k >= 0; k--) {
                if (++index[k] < grid.get(keys.get(k)).size()) break;
                index[k] = 0;
            }
        }
        return result;
    }
}
