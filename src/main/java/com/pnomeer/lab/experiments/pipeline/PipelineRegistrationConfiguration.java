package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.scenario.*;
import org.springframework.context.annotation.*;
import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;

@Configuration
public class PipelineRegistrationConfiguration {
    @Bean
    public ExperimentRegistration<PipelineExperimentScenario> pipelineRegistration(JdbcTemplate jdbc, TransactionTemplate transactions) {
        return new ExperimentRegistration<>(PipelineExperimentModule.DESCRIPTOR,
                d -> new PipelineExperiment(new PipelineRunner(pipelineProperties(d), jdbc, transactions)),
                (d, input) -> new PipelineExperimentScenario(input,
                        PipelineRunner.RunMode.valueOf((String)d.parameters().get("mode")), progress -> { },
                        new PipelineExperimentCapture(), Path.of(d.input().file()).getFileName().toString(), d.scenario().id()));
    }
    private static int integer(ScenarioDefinition d, String key) { return ((Number)d.parameters().get(key)).intValue(); }
    private static PipelineProperties pipelineProperties(ScenarioDefinition d) {
        var p = new PipelineProperties();
        p.setShutdownTimeoutMs(((Number)d.parameters().getOrDefault("shutdownTimeoutMs",30000)).longValue());
        p.setErrorPreviewLimit(((Number)d.parameters().getOrDefault("errorPreviewLimit",100)).intValue());
        p.getQueue().setRawCapacity(integer(d,"rawCapacity"));
        p.getQueue().setMappedCapacity(integer(d,"mappedCapacity"));
        p.getThreads().setValidator(integer(d,"validatorThreads"));
        p.getThreads().setInserter(integer(d,"inserterThreads"));
        p.getInsert().setMaxAttempts(((Number)d.parameters().getOrDefault("maxAttempts",3)).intValue());
        p.getInsert().setRetryBackoffMs(((Number)d.parameters().getOrDefault("retryBackoffMs",100)).longValue());
        p.getInsert().setChunkSize(integer(d,"chunkSize"));
        p.getInsert().setFlushIntervalMs(((Number)d.parameters().getOrDefault("flushIntervalMs",1000)).longValue());
        p.getBackpressure().setOfferTimeoutMs((long)integer(d,"offerTimeoutMs"));
        return p;
    }
}
