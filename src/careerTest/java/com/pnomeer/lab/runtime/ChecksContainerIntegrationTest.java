package com.pnomeer.lab.runtime;

import com.pnomeer.lab.app.run.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.worker.*;
import com.pnomeer.pipeline.ExcelPipelineApplication;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChecksContainerIntegrationTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) static Path build;
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path root;
    static final String tag = "engineering-lab-checks-worker-test:" + UUID.randomUUID();
    static String image, hash;
    @BeforeAll static void image() throws Exception {
        Path manifest = Path.of(System.getProperty("lab.checks.manifest"));
        var descriptor = WireJson.parse(Files.readAllBytes(manifest));
        hash = descriptor.path("artifactSha256").asText();
        image = ContainerFixtureImage.create(build, manifest.getParent().resolve(descriptor.path("jarFile").asText()), tag,
                ChecksContainerIntegrationTest::docker);
    }
    @AfterAll static void cleanup() throws Exception { removeTag(tag); }
    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ExcelPipelineApplication.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--lab.storage.root=" + root, "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                        "--lab.career.checks.enabled=true", "--lab.career.checks.container.image-id=" + image,
                        "--lab.career.checks.container.artifact-sha256=" + hash, "--lab.career.checks.recover-on-startup=true");
    }
    private MockMvc mvc(ConfigurableApplicationContext app) {
        return MockMvcBuilders.standaloneSetup(app.getBean(ScenarioRunController.class), app.getBean(ExecutionEvidenceController.class),
                app.getBean(DashboardCatalogController.class), app.getBean(ExperimentCatalogController.class))
                .setControllerAdvice(new ScenarioExceptionHandler()).build();
    }
    @ParameterizedTest @CsvSource({"architecture,COMPLIANT,SUCCEEDED,4", "architecture,VIOLATION,FAILED,4",
            "validation,VALID,SUCCEEDED,3", "validation,BLANK_NAME,SUCCEEDED,3",
            "validation,PAST_EXPIRY,SUCCEEDED,3", "validation,DOMAIN_REJECTION,SUCCEEDED,3"})
    void actualContainerThroughApiExportsChecksAndReopensStore(String name, String variant, RunStatus status, int count) throws Exception {
        String file = name + "-" + variant.toLowerCase().replace('_','-');
        String document = Files.readString(Path.of("experiments/career/mcp-platform/scenarios/" + file + ".json"));
        String id;
        StoredRun original;
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        Path evidence = Files.createDirectories(Path.of(System.getProperty("lab.checks.evidence")));
        try (var app = start()) {
            var api = mvc(app); var store = app.getBean(RunStore.class);
            for (String invalid : List.of(document.replace(variant,"UNKNOWN"), document.replace("SINGLE_PASS","WARMUP")))
                api.perform(post("/runs").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
            assertTrue(store.list(null, Long.MAX_VALUE, 20).isEmpty());
            var catalog = app.getBean(DashboardCatalogController.class);
            assertEquals(List.of("SUMMARY","EXECUTION","CHECK_RESULTS"), catalog.experiment("career.mcp-platform." + name).presentation());
            assertEquals("CONTAINER", catalog.experiment("career.mcp-platform." + name).executionKind());
            String submission = api.perform(post("/runs").contentType("application/json").content(document))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            id = WireJson.parse(submission.getBytes(java.nio.charset.StandardCharsets.UTF_8)).path("runId").asText();
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            do {
                original = store.find(id).orElseThrow();
                if (original.completedAtEpochMs() > 0) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            assertTrue(original.completedAtEpochMs() > 0);
            assertEquals(status, original.status(), original.failureMessage());
            assertEquals(status == RunStatus.SUCCEEDED, store.execution(id).orElseThrow().evidenceComplete());
            assertEquals(count, store.checks(id,-1,20).size());
            assertEquals(variant.equals("VIOLATION") ? 3 : 0, store.checks(id,-1,20).stream().filter(c -> c.status() == RunCheck.Status.FAIL).count());
            assertTrue(store.metrics(id,-1,20).isEmpty());
            assertTrue(store.execution(id).orElseThrow().worker().environment().javaVersion().startsWith("21"));
            assertEquals(1,store.artifacts(id).size());
            var report = WireJson.parse(Files.readAllBytes(root.resolve(store.artifacts(id).getFirst().relativePath())));
            assertEquals(variant,report.path("variant").asText());
            assertEquals(count,report.path("checks").size());
            String exported = api.perform(get("/dashboard/v1/runs/" + id + "/export")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertEquals(count, exported.lines().filter(s -> s.contains("\"type\":\"check\"")).count());
            Files.writeString(evidence.resolve(file + ".ndjson"),exported);
            Map<String,Object> snapshot = new LinkedHashMap<>();
            snapshot.put("run",original); snapshot.put("execution",app.getBean(ExecutionEvidenceController.class).execution(id));
            snapshot.put("checks",app.getBean(ExecutionEvidenceController.class).checks(id,-1,20));
            snapshot.put("artifacts",store.artifacts(id)); snapshot.put("report",json.readTree(report.toString()));
            Files.writeString(evidence.resolve(file + ".json"),json.writeValueAsString(snapshot));
            try (var paths = Files.list(root.resolve("worker-workspaces"))) {
                var workspace = paths.filter(p -> Files.isDirectory(p.resolve("container-lifecycle"))).findFirst().orElseThrow();
                Path journal = workspace.resolve("container-lifecycle");
                assertTrue(Files.isRegularFile(journal.resolve("removed.json")));
                String container = WireJson.parse(Files.readAllBytes(journal.resolve("created.json"))).path("id").asText();
                assertTrue(new DockerContainerEngine().inspect(container).isEmpty());
                assertFalse(Files.exists(workspace.resolve("sequence-resources")));
            }
        }
        try (var app = start()) {
            var store = app.getBean(RunStore.class);
            assertEquals(original,store.find(id).orElseThrow());
            var api = mvc(app);
            api.perform(get("/dashboard/v1/runs/" + id + "/checks")).andExpect(status().isOk()).andExpect(jsonPath("$.checks.length()").value(count));
            assertEquals(status == RunStatus.SUCCEEDED,store.execution(id).orElseThrow().evidenceComplete());
            assertTrue(app.getBeansOfType(ContainerWorkerRegistration.class).values().stream().allMatch(w -> w.readiness().get().ready()));
        }
    }
    private static void removeTag(String reference) throws Exception {
        String[] lookup = {"image","ls","--filter","reference="+reference,"--format","{{.Repository}}:{{.Tag}}"};
        if (docker(lookup).isBlank()) return;
        Process process = new ProcessBuilder("docker","image","rm",reference).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS); }
        finally { if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));} }
        assertTrue(docker(lookup).isBlank());
    }
    private static String docker(String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker"));command.addAll(List.of(args));
        Path log = Files.createTempFile(build,"docker-",".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)); String result=Files.readString(log);assertEquals(0,process.exitValue(),result);return result; }
        finally { if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));} }
    }
}
