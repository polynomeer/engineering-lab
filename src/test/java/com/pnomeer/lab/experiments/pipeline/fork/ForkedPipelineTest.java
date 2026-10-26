package com.pnomeer.lab.experiments.pipeline.fork;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ForkedPipelineTest {
    @Test void resolvesRelativeClasspathEntriesBecauseTheChildRunsElsewhere() {
        String relative = "build/libs/app.jar" + File.pathSeparator + File.pathSeparator + "/abs/lib.jar";
        String resolved = ForkedPipeline.absoluteClasspath(relative);
        var entries = resolved.split(File.pathSeparator);
        assertEquals(2, entries.length);
        assertEquals(Path.of("build/libs/app.jar").toAbsolutePath().normalize().toString(), entries[0]);
        assertEquals("/abs/lib.jar", entries[1]);
    }

    /** The JVM prints its OutOfMemoryError notice on stdout, between protocol lines; the run must name the heap limit. */
    @Test void reportsAHeapLimitFailureAsOutOfMemory() throws Exception {
        var payload = com.pnomeer.lab.core.input.InputPayload.stage(java.nio.file.Files.newInputStream(
                Path.of("experiments/pipeline/excel-ingestion/scenarios/fixtures/orders-100k.xlsx")), com.pnomeer.lab.core.input.InputPayload.MAX_BYTES);
        var config = new java.util.HashMap<String, String>(java.util.Map.of("pipeline.queue.raw-capacity", "1000", "pipeline.queue.mapped-capacity", "1000",
                "pipeline.threads.validator", "2", "pipeline.threads.inserter", "4", "pipeline.insert.flush-interval-ms", "1000",
                "pipeline.insert.max-attempts", "3", "pipeline.insert.retry-backoff-ms", "100", "pipeline.insert.chunk-size", "500",
                "pipeline.backpressure.offer-timeout-ms", "200", "pipeline.shutdown-timeout-ms", "30000"));
        config.put("pipeline.error-preview-limit", "100");
        config.put("pipeline.insert.delay-ms", "0");
        try (payload) {
            var failure = assertThrows(java.io.IOException.class, () -> ForkedPipeline.run(payload,
                    com.pnomeer.lab.experiments.pipeline.PipelineRunner.RunMode.PIPELINE, config, 32, p -> { }, d -> { }));
            assertEquals("Forked JVM ran out of heap at -Xmx32m (OutOfMemoryError)", failure.getMessage());
        }
    }
}
