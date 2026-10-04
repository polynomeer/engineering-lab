package com.pnomeer.lab.experiments.pipeline.benchmark;

import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PipelinePerformanceTest {
    @TempDir Path directory;

    @Test
    void boundedHeapPipelineOverlapsControlledStorageLatency() throws Exception {
        var input = directory.resolve("input.xlsx");
        PipelineBenchmark.fixture(input, 6000, 10);
        var baseline = PipelineBenchmark.fork(directory.resolve("single"), input,
                PipelineRunner.RunMode.SINGLE_THREAD, 6000, 10, 60, 3);
        var candidate = PipelineBenchmark.fork(directory.resolve("parallel"), input,
                PipelineRunner.RunMode.PIPELINE, 6000, 10, 60, 3);
        assertThat(candidate.inputSha256()).isEqualTo(baseline.inputSha256());
        for (var result : new PipelineBenchmark.Case[]{baseline, candidate}) {
            assertThat(result.trials()).hasSize(3).allSatisfy(trial -> {
                assertThat(trial.maxHeapBytes()).isLessThanOrEqualTo(128L * 1024 * 1024);
                assertThat(trial.sampledHeapPeakBytes()).isPositive().isLessThanOrEqualTo(trial.maxHeapBytes());
                assertThat(trial.inserted()).isEqualTo(5400);
                assertThat(trial.errors()).isEqualTo(600);
                assertThat(trial.previewSize()).isEqualTo(16);
            });
        }
        // Deliberately limited claim: overlapping a simulated per-batch wait improves throughput.
        assertThat(PipelineBenchmark.median(candidate)).isLessThan(PipelineBenchmark.median(baseline));
    }
}
