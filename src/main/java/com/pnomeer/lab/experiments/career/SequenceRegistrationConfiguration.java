package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.storage.StorageProperties;
import com.pnomeer.lab.worker.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static com.pnomeer.lab.registry.ParameterSpec.*;

@Configuration
@ConditionalOnProperty(name="lab.career.sequence.enabled", havingValue="true")
public class SequenceRegistrationConfiguration {
    public static final String ID = "career.mcp-platform.sequence";
    public static final List<String> VARIANTS = List.of("BEFORE", "ATOMIC_UPDATE", "SELECT_FOR_UPDATE");
    public static final ExperimentDescriptor DESCRIPTOR = new ExperimentDescriptor(ID, "Contract sequence allocation", "CAREER", "NONE",
            Map.of("variant", choice(VARIANTS.toArray(String[]::new)), "workers", integer(2,16,"threads"),
                    "blockSize", integer(1,10000,"codes")), List.of("SINGLE_PASS"));
    public record Scenario(ScenarioDefinition definition, String hash) implements ExperimentScenario {
        public String scenarioId() { return definition.scenario().id(); }
        public Map<String,String> parameters() {
            var map = new HashMap<String,String>(); definition.parameters().forEach((k,v) -> map.put(k,v.toString())); return Map.copyOf(map);
        }
    }
    @Bean public ExperimentModule sequenceModule() {
        return new ExperimentModule() {
            public ModuleDescriptor module() { return new ModuleDescriptor("mcp-platform", "MCP platform", "career"); }
            public List<ExperimentDescriptor> experiments() { return List.of(DESCRIPTOR); }
            public List<com.pnomeer.lab.metrics.MetricCatalog.Definition> metrics() {
                return List.of(metric("sequence.elapsedNanos", "ns", "Harness duration; excludes startup; BEFORE includes a synthetic 50ms delay"),
                        metric("sequence.overlapPairs", "pairs", "Overlapping allocation pairs; BEFORE expects an anomaly"),
                        metric("sequence.finalValue", "codes", "Final database sequence value"));
            }
            private com.pnomeer.lab.metrics.MetricCatalog.Definition metric(String name, String unit, String meaning) {
                return new com.pnomeer.lab.metrics.MetricCatalog.Definition(name, unit, "worker-run", "RUN_FINAL", meaning + "; use the final value, never sum snapshots");
            }
        };
    }
    @Bean public ExperimentRegistration<Scenario> sequenceRegistration(ScenarioDocuments documents) {
        return new ExperimentRegistration<>(DESCRIPTOR, ignored -> new Experiment<>() {
            public String type() { return ID; }
            public ExperimentSummary execute(Scenario scenario, Consumer<MetricPoint> sink) {
                throw new IllegalStateException("Sequence requires its registered worker executor");
            }
        }, (definition, input) -> new Scenario(definition, documents.hash(definition)));
    }
    @Bean
    @ConditionalOnProperty(name="lab.career.sequence.execution-kind", havingValue="LOCAL_JVM", matchIfMissing=true)
    public LocalJvmRegistration sequenceWorker(StorageProperties storage, com.pnomeer.lab.storage.JdbcRunStore store,
            @Value("${lab.career.sequence.manifest}") String manifestFile,
            @Value("${lab.career.sequence.java-executable}") String javaExecutable,
            @Value("${lab.career.sequence.recover-on-startup:false}") boolean recoverOnStartup) throws Exception {
        Path descriptor = Path.of(manifestFile).toAbsolutePath().normalize();
        byte[] bytes;
        try (var input = Files.newInputStream(descriptor)) { bytes = input.readNBytes(16385); }
        if (bytes.length > 16384) throw new IllegalArgumentException("Deployment descriptor exceeds limit");
        var data = WireJson.parse(bytes);
        String jarFile = data.path("jarFile").asText();
        if (data.path("schemaVersion").asInt() != 1 || data.path("protocolVersion").asInt() != 1
                || data.path("javaMajor").asInt() != 21 || !data.path("requiresDocker").asBoolean()
                || !data.path("moduleId").asText().equals("mcp-platform") || data.path("experimentIds").size() != 1
                || !data.path("experimentIds").path(0).asText().equals(ID)
                || !jarFile.matches("[A-Za-z0-9._-]+\\.jar") || data.path("heapMiB").asInt() != 256)
            throw new IllegalArgumentException("Incompatible sequence deployment descriptor");
        var manifest = new WorkerManifest(Path.of(javaExecutable), descriptor.getParent().resolve(jarFile),
                data.path("artifactSha256").asText(), "mcp-platform", Set.of(ID), 256);
        Path root = Path.of(storage.root()).toAbsolutePath();
        var binding = new LocalJvmRegistration("sequence-worker", manifest, Files.createDirectories(root.resolve("worker-workspaces")), root,
                Set.of("sequence.elapsedNanos", "sequence.overlapPairs", "sequence.finalValue"), scenario -> {
                    var input = (Scenario) scenario;
                    var parameters = WireJson.object();
                    input.definition().parameters().forEach((key, value) -> {
                        if (value instanceof Number number) parameters.put(key, number.intValue()); else parameters.put(key, value.toString());
                    });
                    return new LocalJvmExecutor.Request(parameters, input.hash(), 0);
                }, new WorkerReadiness(manifest, Duration.ofSeconds(60)), VARIANTS);
        // Bean dependency ensures exclusive storage ownership and interrupted-Run recovery precede this action.
        if (recoverOnStartup) {
            // Only these sequence paths finish DB startup and context/container cleanup without cancellation.
            var result = new WorkerStartupRecovery(outcome ->
                    outcome.status().equals("SUCCEEDED") && outcome.reason().isEmpty()
                    || outcome.status().equals("FAILED") && outcome.reason().equals("EXPECTATION_NOT_MET"))
                    .recover(binding, store);
            org.slf4j.LoggerFactory.getLogger(SequenceRegistrationConfiguration.class)
                    .info("stage=worker_startup_recovery registration_id={} result={}", binding.id(), result);
        }
        return binding;
    }
    public enum ExecutionMode { LOCAL_JVM, CONTAINER }
    @Bean public ExecutionMode sequenceExecutionMode(@Value("${lab.career.sequence.execution-kind:LOCAL_JVM}") ExecutionMode mode) { return mode; }
    @Bean
    @ConditionalOnProperty(name="lab.career.sequence.execution-kind", havingValue="CONTAINER")
    public ContainerWorkerRegistration sequenceContainerWorker(StorageProperties storage, com.pnomeer.lab.storage.JdbcRunStore store,
            @Value("${lab.career.sequence.container.image-id}") String imageId,
            @Value("${lab.career.sequence.container.artifact-sha256}") String artifactSha256,
            @Value("${lab.career.sequence.container.mysql-image-id}") String mysqlImageId,
            @Value("${lab.career.sequence.recover-on-startup:false}") boolean recoverOnStartup) throws Exception {
        Path root = Path.of(storage.root()).toAbsolutePath();
        var manifest = new ContainerWorkerManifest(imageId, artifactSha256, "mcp-platform", Set.of(ID));
        var binding = new ContainerWorkerRegistration("sequence-worker", manifest, Files.createDirectories(root.resolve("worker-workspaces")), root,
                Set.of("sequence.elapsedNanos", "sequence.overlapPairs", "sequence.finalValue"), scenario -> {
                    var input = (Scenario)scenario;
                    var parameters = WireJson.object();
                    input.definition().parameters().forEach((key, value) -> {
                        if (value instanceof Number number) parameters.put(key, number.intValue()); else parameters.put(key, value.toString());
                    });
                    return new LocalJvmExecutor.Request(parameters, input.hash(), 0);
                }, new SequenceContainerResources(mysqlImageId), () -> {
                    try {
                        for (String image : List.of(imageId, mysqlImageId))
                            if (!image.equals(DockerContainerEngine.command("image", "inspect", image, "--format", "{{.Id}}").trim()))
                                return new LocalJvmRegistration.Readiness("INCOMPATIBLE", "CONTAINER_IMAGE_MISMATCH");
                        return new LocalJvmRegistration.Readiness("READY", null);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        return new LocalJvmRegistration.Readiness("UNAVAILABLE", "CONTAINER_ENGINE_OR_IMAGE_UNAVAILABLE");
                    }
                }, VARIANTS);
        // JdbcRunStore has already acquired its owner lock and marked interrupted Runs before recovery.
        if (recoverOnStartup) org.slf4j.LoggerFactory.getLogger(SequenceRegistrationConfiguration.class)
                .info("stage=container_startup_recovery registration_id={} result={}", binding.id(), binding.recover(store));
        return binding;
    }

}
