package com.pnomeer.lab.registry;

import com.pnomeer.lab.scenario.*;
import java.util.List;

public record ParameterSpec(String type, Long minimum, Long maximum, List<String> values, String unit, Long defaultValue) {
    public ParameterSpec { values = List.copyOf(values); }
    public static ParameterSpec integer(long min, long max, String unit) { return new ParameterSpec("integer", min, max, List.of(), unit, null); }
    public static ParameterSpec optionalInteger(long min, long max, String unit, long defaultValue) {
        return new ParameterSpec("integer", min, max, List.of(), unit, defaultValue);
    }
    public static ParameterSpec bool() { return new ParameterSpec("boolean", null, null, List.of(), null, null); }
    public static ParameterSpec choice(String... values) { return new ParameterSpec("string", null, null, List.of(values), null, null); }
    public void validate(String key, Object value) {
        boolean valid = switch (type) {
            case "integer" -> ScenarioDocuments.isInteger(value) && ((Number)value).longValue() >= minimum && ((Number)value).longValue() <= maximum;
            case "boolean" -> value instanceof Boolean;
            case "string" -> value instanceof String && values.contains(value);
            default -> false;
        };
        if (!valid) throw new ScenarioException("Invalid parameter: " + key);
    }
}
