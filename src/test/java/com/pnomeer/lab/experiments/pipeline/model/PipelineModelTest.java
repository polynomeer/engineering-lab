package com.pnomeer.lab.experiments.pipeline.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineModelTest {

    @Test
    void rawRowRejectsNegativeRowIndex() {
        assertThrows(IllegalArgumentException.class, () -> new RawRow(-1, List.of("a")));
    }

    @Test
    void rawRowRejectsNullCells() {
        assertThrows(NullPointerException.class, () -> new RawRow(0, null));
    }

    @Test
    void rawRowUsesImmutableCellsCopy() {
        var source = new ArrayList<>(List.of("a", "b"));
        var row = new RawRow(1, source);
        source.add("c");

        assertEquals(List.of("a", "b"), row.getCells());
        assertThrows(UnsupportedOperationException.class, () -> row.getCells().add("x"));
    }

    @Test
    void ingestItemRejectsNullColumns() {
        assertThrows(NullPointerException.class, () -> new IngestItem(null, "b", 1));
        assertThrows(NullPointerException.class, () -> new IngestItem("a", null, 1));
    }

    @Test
    void mappedRowRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new MappedRow(-1, new IngestItem("a", "b", 1)));
        assertThrows(NullPointerException.class, () -> new MappedRow(1, null));
    }

    @Test
    void validationErrorRejectsInvalidArguments() {
        var raw = new RawRow(1, List.of("a", "b", "3"));
        assertThrows(IllegalArgumentException.class, () -> new ValidationError(-1, "bad", raw));
        assertThrows(NullPointerException.class, () -> new ValidationError(1, null, raw));
        assertThrows(NullPointerException.class, () -> new ValidationError(1, "bad", null));
    }
}
