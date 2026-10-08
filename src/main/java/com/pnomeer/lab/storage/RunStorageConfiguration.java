package com.pnomeer.lab.storage;

import com.pnomeer.lab.runtime.DurableExperimentRunner;
import com.pnomeer.lab.runtime.GitProvenanceProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({ArtifactDownloadProperties.class, StorageProperties.class, com.pnomeer.lab.runtime.ObservationProperties.class, com.pnomeer.lab.runtime.TraceProperties.class, com.pnomeer.lab.runtime.JfrProperties.class, com.pnomeer.lab.runtime.RunLogProperties.class})
public class RunStorageConfiguration {
    @Bean(destroyMethod = "close")
    public JdbcRunStore runStore(StorageProperties properties) {
        var store = new JdbcRunStore(Path.of(properties.root()), properties.pageLimit(), properties.batchSize());
        try { store.recoverInterrupted(); return store; }
        catch (RuntimeException ex) { store.close(); throw ex; }
    }
    @Bean(name = "experimentRunner", destroyMethod = "close")
    public DurableExperimentRunner experimentRunner(JdbcRunStore store, StorageProperties properties, com.pnomeer.lab.runtime.ObservationProperties observation, com.pnomeer.lab.runtime.TraceProperties trace, com.pnomeer.lab.runtime.JfrProperties jfr, com.pnomeer.lab.runtime.RunLogProperties logs, com.pnomeer.lab.registry.ExperimentCatalog catalog) {
        var moduleIds = new java.util.HashMap<String, String>();
        catalog.modules().forEach(entry -> entry.experiments().forEach(experiment -> moduleIds.put(experiment.id(), entry.module().id())));
        return new DurableExperimentRunner(store,
                new GitProvenanceProvider(Path.of(properties.gitDirectory()), Duration.ofMillis(properties.gitTimeoutMs())),
                properties.writerOptions(), properties.previewLimit(), properties.maxActiveRuns(),
                observation.jvmEnabled() ? com.pnomeer.lab.metrics.JvmSnapshot::capture : null,
                observation.jvmIntervalMs(), observation.maxPeriodicSamples(), trace.enabled(), trace.maxSpans(), new com.pnomeer.lab.runtime.JfrRecorder(Path.of(properties.root()), jfr),
                new com.pnomeer.lab.runtime.RunLogRecorder(Path.of(properties.root()), logs), moduleIds);
    }
}
