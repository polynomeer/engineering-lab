package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.experiments.career.SequenceContainerResources;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class SequenceContainerIntegrationTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) static Path build;
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path root;
    @BeforeEach void diagnosticWorkspace() { System.out.println("sequence_controller_test_workspace=" + root); }
    static final String tag = "engineering-lab-sequence-worker-test:" + UUID.randomUUID();
    static String image, mysqlImage, hash;
    @BeforeAll static void image() throws Exception {
        var descriptor = WireJson.parse(Files.readAllBytes(Path.of(System.getProperty("lab.sequence.manifest"))));
        Path jar = Path.of(System.getProperty("lab.sequence.manifest")).getParent().resolve(descriptor.path("jarFile").asText());
        hash = descriptor.path("artifactSha256").asText();
        mysqlImage = docker("image", "inspect", "mysql:8.0.39", "--format", "{{.Id}}").trim();
        image = ContainerFixtureImage.create(build, jar, tag, SequenceContainerIntegrationTest::docker);
    }
    @AfterAll static void removeImages() throws Exception { removeTag(tag); }

    private DurableExperimentRunner runner(RunStore store, String variant, boolean wrongPassword) throws Exception {
        ContainerWorkerResources resources = new SequenceContainerResources(mysqlImage);
        if (wrongPassword) {
            var delegate = resources;
            resources = (command, workspace, artifact) -> {
                var actual = delegate.open(command, workspace, artifact);
                return new ContainerWorkerResources.Session() {
                    public void prepare() throws Exception { actual.prepare(); }
                    public String networkId() { return actual.networkId(); }
                    public Map<String,String> environment() { var env = new HashMap<>(actual.environment()); env.put("LAB_SEQUENCE_DB_PASSWORD", "f".repeat(64)); return env; }
                    public void finish(boolean removed) throws Exception { actual.finish(removed); }
                };
            };
        }
        var executor = new ContainerWorkerExecutor(new ContainerWorkerManifest(image, hash, "mcp-platform", Set.of("career.mcp-platform.sequence")),
                new WorkerProcess.Limits(Duration.ofMinutes(4), Duration.ofSeconds(5), 1024 * 1024, ProtocolLimits.DEFAULT),
                Files.createDirectories(root.resolve("workspaces")), root.resolve("db"),
                Set.of("sequence.elapsedNanos", "sequence.overlapPairs", "sequence.finalValue"),
                ignored -> new LocalJvmExecutor.Request(WireJson.object().put("variant", variant).put("workers", 10).put("blockSize", 10), "a".repeat(64), 1), resources);
        return new DurableExperimentRunner(store, () -> GitProvenance.unknown("controller"), new MetricWriterOptions(8,4,20,2,1,2000),
                new ExperimentExecutors("CONTAINER", Map.of("CONTAINER", new ExperimentExecutors.Registration(executor,1)), Map.of()),5,2,
                Map.of("career.mcp-platform.sequence", new RunExecution.Definition(RunExecution.Kind.CONTAINER,"mcp-platform",1,RunExecution.MeasurementScope.WORKER_PROCESS)));
    }
    private final Experiment<ExperimentScenario> experiment = new Experiment<>() {
        public String type() { return "career.mcp-platform.sequence"; }
        public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> points) { throw new AssertionError("Expected container worker"); }
    };
    @ParameterizedTest @ValueSource(strings={"BEFORE", "ATOMIC_UPDATE", "SELECT_FOR_UPDATE"})
    void controllerOwnsDatabaseAndNetworkWhileWorkerProducesEvidence(String variant) throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"),20,4); var runner = runner(store, variant, false)) {
            assertEquals(ExperimentStatus.SUCCEEDED, runner.run(experiment, () -> "container-sequence", "run").status(),
                    () -> store.find("run").orElseThrow().failureMessage());
            assertTrue(store.execution("run").orElseThrow().evidenceComplete());
            assertTrue(store.execution("run").orElseThrow().worker().environment().javaVersion().startsWith("21"));
            assertEquals(2, store.checks("run",-1,20).size());
            assertTrue(store.checks("run",-1,20).stream().allMatch(c -> c.status() == RunCheck.Status.PASS));
            assertEquals(1, store.metrics("run",-1,20).size());
            var report = WireJson.parse(Files.readAllBytes(root.resolve("db").resolve(store.artifacts("run").getFirst().relativePath())));
            assertEquals(variant, report.path("variant").asText());
            assertEquals(10, report.path("ranges").size());
            assertEquals(variant.equals("BEFORE"), report.path("overlapPairs").asInt() > 0);
            assertResourcesRemoved();
        }
    }
    @Test void rejectedDatabaseAuthenticationCannotSealAndStillCleansControllerResources() throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"),20,4); var runner = runner(store,"ATOMIC_UPDATE",true)) {
            assertEquals(ExperimentStatus.FAILED, runner.run(experiment, () -> "bad-auth", "run").status());
            assertFalse(store.execution("run").orElseThrow().evidenceComplete());
            assertResourcesRemoved();
        }
    }
    private void assertResourcesRemoved() throws Exception {
        try (var paths = Files.list(root.resolve("workspaces"))) {
            Path workspace = paths.findFirst().orElseThrow();
            for (Path journal : List.of(workspace.resolve("container-lifecycle"), workspace.resolve("sequence-resources/mysql"))) {
                assertTrue(Files.isRegularFile(journal.resolve("removed.json")), journal.toString());
                String id = WireJson.parse(Files.readAllBytes(journal.resolve("created.json"))).path("id").asText();
                assertTrue(new DockerContainerEngine().inspect(id).isEmpty());
            }
            Path network = workspace.resolve("sequence-resources");
            assertTrue(Files.isRegularFile(network.resolve("removed.json")));
            String id = WireJson.parse(Files.readAllBytes(network.resolve("created.json"))).path("id").asText();
            assertTrue(docker("network","ls","--no-trunc","--quiet","--filter","id=" + id).isBlank());
        }
    }

    private static org.springframework.context.ConfigurableApplicationContext start(Path storage, boolean recover) {
        return new org.springframework.boot.builder.SpringApplicationBuilder(com.pnomeer.pipeline.ExcelPipelineApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run("--lab.storage.root=" + storage,
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN", "--lab.career.sequence.enabled=true",
                        "--lab.career.sequence.execution-kind=CONTAINER", "--lab.career.sequence.recover-on-startup=" + recover,
                        "--lab.career.sequence.container.image-id=" + image, "--lab.career.sequence.container.artifact-sha256=" + hash,
                        "--lab.career.sequence.container.mysql-image-id=" + mysqlImage);
    }
    private static com.pnomeer.lab.experiments.career.SequenceRegistrationConfiguration.Scenario scenario() {
        return new com.pnomeer.lab.experiments.career.SequenceRegistrationConfiguration.Scenario(
                new com.pnomeer.lab.scenario.ScenarioDefinition(1,"career.mcp-platform.sequence",
                        new com.pnomeer.lab.scenario.ScenarioDefinition.Scenario("restart","Atomic allocation"),
                        Map.of("variant","ATOMIC_UPDATE","workers",2,"blockSize",10),
                        new com.pnomeer.lab.scenario.ScenarioDefinition.Measurement("SINGLE_PASS"),null),"a".repeat(64));
    }
    @Test void killedControllerRecoversSameStoreAndAdmitsNextRunWithoutSealingPartialEvidence() throws Exception {
        Path storage = root.resolve("restart-storage"); Files.createDirectories(storage);
        var launch = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m",
                "-cp",System.getProperty("lab.controller.classpath"),CrashController.class.getName(),storage.toString(),image,mysqlImage,hash)
                .redirectErrorStream(true).redirectOutput(root.resolve("controller.log").toFile());
        for(String key:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH")) launch.environment().remove(key);
        Process child=launch.start();
        try {
            long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
            while(!Files.exists(storage.resolve("ready-to-kill")) && child.isAlive() && System.nanoTime()<deadline) Thread.sleep(50);
            assertTrue(Files.exists(storage.resolve("ready-to-kill")), () -> "Controller did not reach persisted HELLO: " + root.resolve("controller.log"));
            assertThrows(IllegalStateException.class,()->new JdbcRunStore(storage,20,4));
            child.destroyForcibly(); assertTrue(child.waitFor(10,TimeUnit.SECONDS));
            StoredRun original;
            RunExecution evidence;
            Path workspace;
            try(var app=start(storage,false)) {
                var binding=app.getBean(ContainerWorkerRegistration.class);
                assertFalse(binding.readiness().get().ready());
                var store=app.getBean(RunStore.class);
                original=store.find("crash-run").orElseThrow(); evidence=store.execution("crash-run").orElseThrow();
                assertEquals(RunStatus.INTERRUPTED,original.status()); assertNotNull(evidence.worker()); assertFalse(evidence.evidenceComplete());
                var active=ContainerJournal.json(ContainerRecoveryGuard.marker(binding.workspaces(),binding.id()));
                workspace=binding.workspaces().resolve(active.path("workspace").asText());
                String workerId=ContainerJournal.json(workspace.resolve("container-lifecycle/created.json")).path("id").asText();
                assertTrue(new DockerContainerEngine().inspect(workerId).isPresent());
                assertThrows(LocalJvmRegistration.WorkerUnavailableException.class,()->app.getBean(DurableExperimentRunner.class).run(experiment,scenario(),"blocked-run"));
                assertTrue(store.find("blocked-run").isEmpty());
                assertThrows(Exception.class,()->{try(var second=start(storage,true)) { fail("Shared store must reject"); }});
            }
            try(var app=start(storage,true)) {
                var binding=app.getBean(ContainerWorkerRegistration.class);
                assertFalse(new ContainerRecoveryGuard(binding.workspaces(),binding.id()).pending());
                assertTrue(binding.readiness().get().ready());
                var store=app.getBean(RunStore.class);
                assertEquals(original,store.find("crash-run").orElseThrow()); assertEquals(evidence,store.execution("crash-run").orElseThrow());
                for(String path:List.of("container-lifecycle","sequence-resources/mysql","sequence-resources"))
                    assertTrue(Files.isRegularFile(workspace.resolve(path).resolve("removed.json")),path);
                var catalog=app.getBean(com.pnomeer.lab.app.run.DashboardCatalogController.class);
                assertEquals("CONTAINER",catalog.experiment("career.mcp-platform.sequence").executionKind());
                assertTrue(catalog.capabilities().executionKinds().contains("CONTAINER"));
                assertFalse(catalog.capabilities().features().get("localJvm"));
                assertEquals(ExperimentStatus.SUCCEEDED,app.getBean(DurableExperimentRunner.class).run(experiment,scenario(),"after-restart").status());
                assertTrue(store.execution("after-restart").orElseThrow().evidenceComplete());
                assertEquals(2,store.checks("after-restart",-1,20).size());
            }
        } finally {
            if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));}
            // Scoped production reconciliation; never remove resources by names or global labels.
            try(var app=start(storage,true)) {
                assertFalse(new ContainerRecoveryGuard(storage.resolve("worker-workspaces"),"sequence-worker").pending());
            }
        }
    }
    public static class CrashController {
        public static void main(String[] args) throws Exception {
            Path storage=Path.of(args[0]); image=args[1];mysqlImage=args[2];hash=args[3];
            try(var app=start(storage,false)) {
                var store=app.getBean(RunStore.class);
                RunStore paused=(RunStore)java.lang.reflect.Proxy.newProxyInstance(RunStore.class.getClassLoader(),new Class<?>[]{RunStore.class},(proxy,method,values)->{
                    Object result;
                    try { result=method.invoke(store,values); }
                    catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    if(method.getName().equals("registerWorker")) {
                        Thread.sleep(1000); // Let H2's committed page flush finish before external SIGKILL.
                        Files.writeString(storage.resolve("ready-to-kill"),"HELLO persisted; resources remain owned");
                        Thread.sleep(Long.MAX_VALUE);
                    }
                    return result;
                });
                var binding=app.getBean(ContainerWorkerRegistration.class);
                try(var runner=new DurableExperimentRunner(paused,()->GitProvenance.unknown("crash fixture"),new MetricWriterOptions(8,4,20,2,1,2000),
                        new ExperimentExecutors("CONTAINER",Map.of("CONTAINER",new ExperimentExecutors.Registration(binding.executor(),1)),Map.of()),5,2,
                        Map.of("career.mcp-platform.sequence",new RunExecution.Definition(RunExecution.Kind.CONTAINER,"mcp-platform",1,RunExecution.MeasurementScope.WORKER_PROCESS)))) {
                    runner.run(new Experiment<ExperimentScenario>() {
                        public String type(){return "career.mcp-platform.sequence";}
                        public ExperimentSummary execute(ExperimentScenario s,Consumer<MetricPoint> out){throw new AssertionError();}
                    },scenario(),"crash-run");
                    throw new IllegalStateException("Expected controller kill");
                }
            }
        }
    }
    private static void removeTag(String reference) throws Exception {
        String[] lookup = {"image","ls","--filter","reference="+reference,"--format","{{.Repository}}:{{.Tag}}"};
        if (docker(lookup).isBlank()) return;
        Process process = new ProcessBuilder("docker","image","rm",reference).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { process.waitFor(20,TimeUnit.SECONDS); }
        finally { if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));} }
        assertTrue(docker(lookup).isBlank());
    }
    private static String docker(String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker"));command.addAll(List.of(args));
        Path log = Files.createTempFile(build,"docker-",".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(process.waitFor(60,TimeUnit.SECONDS)); String result=Files.readString(log);assertEquals(0,process.exitValue(),result);return result; }
        finally { if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));} }
    }
}
