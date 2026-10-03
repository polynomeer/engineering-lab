package com.pnomeer.lab.registry;

import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.experiments.concurrency.*;
import com.pnomeer.lab.experiments.io.*;
import com.pnomeer.lab.experiments.pipeline.*;
import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ScenarioProperties.class)
public class RegistryConfiguration {
    @Bean public ScenarioDocuments scenarioDocuments(ScenarioProperties properties) { return new ScenarioDocuments(properties.maxDocumentBytes()); }

    @Bean
    public ExperimentRegistry experimentRegistry(QueueContentionExperiment queue, IoEndpointComparisonExperiment io,
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        return new ExperimentRegistry(List.of(
                new ExperimentRegistration<>(ExperimentDescriptors.QUEUE, definition -> queue,
                        (d, input) -> new QueueContentionScenario(d.scenario().id(), integer(d,"queueCapacity"),
                                integer(d,"producerThreads"), integer(d,"consumerThreads"), integer(d,"itemsPerProducer"), integer(d,"consumerDelayMs"))),
                new ExperimentRegistration<>(ExperimentDescriptors.IO, definition -> io,
                        (d, input) -> new IoEndpointComparisonScenario(d.scenario().id(), integer(d,"requests"), integer(d,"delayMs"),
                                (Boolean)d.parameters().get("pinning"), integer(d,"pinDelayMs"))),
                new ExperimentRegistration<>(ExperimentDescriptors.PIPELINE,
                        d -> new PipelineExperiment(new PipelineRunner(pipelineProperties(d), jdbc, transactions)),
                        (d, input) -> new PipelineExperimentScenario(input, PipelineRunner.RunMode.valueOf((String)d.parameters().get("mode")),
                                progress -> { }, new PipelineExperimentCapture(), Path.of(d.input().file()).getFileName().toString(), d.scenario().id()))));
    }
    private static int integer(ScenarioDefinition d, String key) { return ((Number)d.parameters().get(key)).intValue(); }
    private static PipelineProperties pipelineProperties(ScenarioDefinition d) {
        var p = new PipelineProperties();
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
