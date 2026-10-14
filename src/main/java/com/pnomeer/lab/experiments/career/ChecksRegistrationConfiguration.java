package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.worker.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.pnomeer.lab.registry.ParameterSpec.choice;

/** DB-free, container-only deployment; sequence keeps its existing opt-in settings. */
@Configuration
@ConditionalOnProperty(name="lab.career.checks.enabled", havingValue="true")
public class ChecksRegistrationConfiguration {
    public static final List<String> ARCHITECTURE_VARIANTS = List.of("COMPLIANT", "VIOLATION");
    public static final List<String> VALIDATION_VARIANTS = List.of("VALID", "BLANK_NAME", "PAST_EXPIRY", "DOMAIN_REJECTION");
    public static final ExperimentDescriptor ARCHITECTURE = descriptor("architecture", "Architecture guardrails", ARCHITECTURE_VARIANTS);
    public static final ExperimentDescriptor VALIDATION = descriptor("validation", "Validation pipeline", VALIDATION_VARIANTS);
    private static ExperimentDescriptor descriptor(String name, String title, List<String> variants) {
        return new ExperimentDescriptor("career.mcp-platform." + name, title, "CAREER", "NONE",
                Map.of("variant", choice(variants.toArray(String[]::new))), List.of("SINGLE_PASS"));
    }
    public record Scenario(ScenarioDefinition definition, String hash) implements ExperimentScenario {
        public String scenarioId() { return definition.scenario().id(); }
        public Map<String,String> parameters() { return Map.of("variant", definition.parameters().get("variant").toString()); }
    }
    private ExperimentRegistration<Scenario> registration(ExperimentDescriptor descriptor, ScenarioDocuments documents) {
        return new ExperimentRegistration<>(descriptor, ignored -> new Experiment<>() {
            public String type() { return descriptor.id(); }
            public ExperimentSummary execute(Scenario scenario, Consumer<MetricPoint> sink) {
                throw new IllegalStateException("Checks require their registered container executor");
            }
        }, (definition, input) -> new Scenario(definition, documents.hash(definition)));
    }
    @Bean public ExperimentRegistration<Scenario> architectureRegistration(ScenarioDocuments documents) { return registration(ARCHITECTURE, documents); }
    @Bean public ExperimentRegistration<Scenario> validationRegistration(ScenarioDocuments documents) { return registration(VALIDATION, documents); }
    @Bean public ContainerWorkerRegistration architectureWorker(StorageProperties storage, JdbcRunStore store,
            @Value("${lab.career.checks.container.image-id}") String image,
            @Value("${lab.career.checks.container.artifact-sha256}") String hash,
            @Value("${lab.career.checks.recover-on-startup:false}") boolean recover) throws Exception {
        return worker("architecture", ARCHITECTURE, ARCHITECTURE_VARIANTS, storage, store, image, hash, recover);
    }
    @Bean public ContainerWorkerRegistration validationWorker(StorageProperties storage, JdbcRunStore store,
            @Value("${lab.career.checks.container.image-id}") String image,
            @Value("${lab.career.checks.container.artifact-sha256}") String hash,
            @Value("${lab.career.checks.recover-on-startup:false}") boolean recover) throws Exception {
        return worker("validation", VALIDATION, VALIDATION_VARIANTS, storage, store, image, hash, recover);
    }
    private ContainerWorkerRegistration worker(String name, ExperimentDescriptor descriptor, List<String> variants,
            StorageProperties storage, JdbcRunStore store, String image, String hash, boolean recover) throws Exception {
        Path root = Path.of(storage.root()).toAbsolutePath();
        var binding = new ContainerWorkerRegistration("mcp-" + name + "-worker",
                new ContainerWorkerManifest(image, hash, "mcp-platform", Set.of(descriptor.id())),
                Files.createDirectories(root.resolve("worker-workspaces")), root, Set.of(), scenario -> {
                    var input = (Scenario) scenario;
                    return new LocalJvmExecutor.Request(WireJson.object().put("variant", input.parameters().get("variant")), input.hash(), 0);
                }, ContainerWorkerResources.NONE, () -> {
                    try {
                        if (!image.equals(DockerContainerEngine.command("image", "inspect", image, "--format", "{{.Id}}").trim()))
                            return new LocalJvmRegistration.Readiness("INCOMPATIBLE", "CONTAINER_IMAGE_MISMATCH");
                        return new LocalJvmRegistration.Readiness("READY", null);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        return new LocalJvmRegistration.Readiness("UNAVAILABLE", "CONTAINER_ENGINE_OR_IMAGE_UNAVAILABLE");
                    }
                }, variants);
        if (recover) org.slf4j.LoggerFactory.getLogger(ChecksRegistrationConfiguration.class)
                .info("stage=container_startup_recovery registration_id={} result={}", binding.id(), binding.recover(store));
        return binding;
    }
}
