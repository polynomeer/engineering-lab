package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.experiments.pipeline.model.ValidationError;
import java.util.*;

/** Counts all rejected rows but retains only a bounded diagnostic preview. */
final class ValidationErrors {
    private final int limit;
    private final List<ValidationError> preview = new ArrayList<>();
    private final Map<String, Long> reasons = new HashMap<>();
    ValidationErrors(int limit) { this.limit = limit; }
    synchronized void add(ValidationError error) {
        reasons.merge(error.getReason(), 1L, Long::sum);
        if (preview.size() < limit) preview.add(error);
    }
    synchronized List<ValidationError> preview() { return List.copyOf(preview); }
    synchronized Map<String, Long> reasons() { return Map.copyOf(reasons); }
}
