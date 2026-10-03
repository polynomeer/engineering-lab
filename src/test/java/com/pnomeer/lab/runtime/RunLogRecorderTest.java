package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.app.run.RunEvidenceController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.pnomeer.lab.runtime.RunLogRecorder.*;

class RunLogRecorderTest {
    @TempDir Path root;
    private final JsonMapper json = JsonMapper.builder().build();
    private RunLogRecorder recorder(int events, int bytes) {
        return new RunLogRecorder(root, new RunLogProperties(true, events, bytes));
    }
    private DurableExperimentRunner runner(RunStore store, RunLogRecorder recorder) {
        return new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"),
                new MetricWriterOptions(1,1,10,1,1,5000),1,1,null,0,1,false,1,null,recorder);
    }
    private Experiment<ExperimentScenario> experiment() {
        return new Experiment<>() {
            public String type() { return "test"; }
            public ExperimentSummary execute(ExperimentScenario scenario, java.util.function.Consumer<MetricPoint> sink) throws Exception {
                sink.accept(new MetricPoint(1,Map.of(),Map.of("count",1L),Map.of()));
                if (scenario.scenarioId().equals("fail")) throw new IllegalStateException("secret-input");
                if (scenario.scenarioId().equals("interrupt")) throw new InterruptedException("secret-input");
                return ExperimentSummary.empty();
            }
        };
    }
    @Test void preservesSuccessFailureInterruptionAndExportAfterRestart() throws Exception {
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store,recorder(100,65536))) {
            for (String id : List.of("ok","fail","interrupt")) {
                try {
                    var result = runner.run(experiment(), () -> id, id);
                    assertEquals(id.equals("ok") ? ExperimentStatus.SUCCEEDED : ExperimentStatus.FAILED,result.status());
                    assertEquals(id.equals("interrupt"), Thread.currentThread().isInterrupted());
                    assertEquals("LIFECYCLE_V1",result.runMetadata().parameters().get("observation.logs.mode"));
                    assertEquals(3L,result.summary().counters().get("observation.logs.events"));
                    assertEquals(1L,result.summary().counters().get("storage.metrics.written"));
                    var ref = store.artifacts(id).getFirst();
                    byte[] bytes = Files.readAllBytes(root.resolve(ref.relativePath()));
                    assertEquals(bytes.length,ref.byteSize());
                    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),ref.sha256());
                    String content = new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                    assertFalse(content.contains("secret-input"));
                    var lines = content.lines().map(json::readTree).toList();
                    assertEquals("STARTED",lines.getFirst().get("event").asText());
                    assertEquals(id.equals("ok") ? "SUCCEEDED" : id.equals("fail") ? "FAILED" : "INTERRUPTED",lines.get(1).get("event").asText());
                    assertEquals("EVIDENCE_FINISHED",lines.getLast().get("event").asText());
                    assertTrue(lines.stream().allMatch(line -> id.equals(line.get("job_id").asText())));
                } finally { Thread.interrupted(); }
            }
        }
        try (var store = new JdbcRunStore(root,10,1)) {
            assertEquals(RunStatus.INTERRUPTED,store.find("interrupt").orElseThrow().status());
            var properties = new StorageProperties(root.toString(),1,1,10,1,1,1000,10,1,1,".",100);
            var mvc = MockMvcBuilders.standaloneSetup(new RunEvidenceController(store,properties)).build();
            mvc.perform(get("/runs/ok/artifacts")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].mediaType").value("application/x-ndjson"));
            String exported = mvc.perform(get("/runs/fail/export")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(exported.contains(store.artifacts("fail").getFirst().sha256()));
        }
    }
    @Test void boundsEventsAndBytesWhileReservingTerminalEvent() throws Exception {
        for (int cap : List.of(2,10000)) {
            try (var session = recorder(cap,4096).start("bounded")) {
                for (int i=0;i<1000;i++) session.record(Stage.EXECUTION,Event.SUCCEEDED,Reason.NONE);
                var ref = session.finish(Reason.NONE);
                var lines = Files.readAllLines(root.resolve(ref.relativePath()));
                assertTrue(lines.size() <= cap);
                assertTrue(ref.byteSize() <= 4096);
                assertEquals("EVIDENCE_FINISHED",json.readTree(lines.getLast()).get("event").asText());
                assertEquals(1002-lines.size(),json.readTree(lines.getLast()).get("droppedEvents").asInt());
                assertTrue(session.counters().get("observation.logs.dropped") > 0);
                assertThrows(IllegalStateException.class, () -> session.record(Stage.JVM,Event.FAILED,Reason.JVM_FAILED));
            }
        }
    }
    @Test void disabledModeCreatesNoLogAndRejectsInvalidSettings() throws Exception {
        var recorder = new RunLogRecorder(root,new RunLogProperties(false,100,65536));
        assertNull(recorder.start("off"));
        assertFalse(Files.exists(root.resolve("logs")));
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store,recorder)) {
            var result = runner.run(experiment(),()->"off","off");
            assertEquals("OFF",result.runMetadata().parameters().get("observation.logs.mode"));
            assertTrue(store.artifacts("off").isEmpty());
            assertFalse(result.summary().counters().containsKey("observation.logs.events"));
        }
        assertThrows(IllegalArgumentException.class,()->new RunLogProperties(true,1,4096));
        assertThrows(IllegalArgumentException.class,()->new RunLogProperties(true,2,4095));
    }
    @Test void writeFailureIsLatchedUntilFinalizationAndStillDrainsMetrics() throws Exception {
        var closed = new AtomicBoolean();
        var recorder = new RunLogRecorder(root,new RunLogProperties(true,100,65536),path -> new OutputStream() {
            public void write(int value) throws IOException { throw new IOException("disk failed"); }
            public void close() { closed.set(true); }
        });
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store,recorder)) {
            var result = runner.run(experiment(),()->"ok","broken");
            assertEquals(ExperimentStatus.FAILED,result.status());
            assertTrue(result.failureMessage().contains("Run log capture or registration failed"));
            assertEquals(1L,result.summary().counters().get("storage.metrics.written"));
            assertTrue(store.artifacts("broken").isEmpty());
        }
        assertTrue(closed.get());
        try (var files = Files.list(root.resolve("logs"))) { assertEquals(0,files.count()); }
    }
    @Test void registrationFailureKeepsCompletedFileAndFailsRun() throws Exception {
        try (var actual = new JdbcRunStore(root,10,1)) {
            var store = org.mockito.Mockito.spy(actual);
            org.mockito.Mockito.doThrow(new IllegalStateException("registration failed"))
                    .when(store).registerArtifact(org.mockito.ArgumentMatchers.any());
            try (var runner = runner(store,recorder(100,65536))) {
                assertEquals(ExperimentStatus.FAILED,runner.run(experiment(),()->"ok","registration").status());
                assertTrue(actual.artifacts("registration").isEmpty());
                try (var files = Files.list(root.resolve("logs"))) {
                    assertEquals(1,files.filter(path -> path.toString().endsWith(".ndjson")).count());
                }
            }
        }
    }
    @Test void storageDrainFailureIsIncludedInFinalLog() throws Exception {
        try (var actual = new JdbcRunStore(root,10,1)) {
            var store = org.mockito.Mockito.spy(actual);
            org.mockito.Mockito.doThrow(new IllegalStateException("metric write failed"))
                    .when(store).appendMetrics(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList());
            try (var runner = runner(store,recorder(100,65536))) {
                assertEquals(ExperimentStatus.FAILED,runner.run(experiment(),()->"ok","drain").status());
                var ref = actual.artifacts("drain").getFirst();
                var lines = Files.readAllLines(root.resolve(ref.relativePath()));
                assertEquals("STORAGE_FAILED",json.readTree(lines.getLast()).get("reason").asText());
            }
        }
    }
    @Test void startupFailurePreventsWorkAndAbandonedSessionDeletesPartialFile() throws Exception {
        try (var session = recorder(100,65536).start("abandoned")) {
            session.record(Stage.JVM,Event.FAILED,Reason.JVM_FAILED);
        }
        try (var files = Files.list(root.resolve("logs"))) { assertEquals(0,files.count()); }
        Files.delete(root.resolve("logs"));
        Files.writeString(root.resolve("logs"),"not a directory");
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store,recorder(100,65536))) {
            var result = runner.run(experiment(),()->"ok","startup");
            assertEquals(ExperimentStatus.FAILED,result.status());
            assertEquals(0L,result.summary().counters().get("storage.metrics.written"));
            assertTrue(store.artifacts("startup").isEmpty());
        }
    }
    @Test void fatalExperimentErrorStillClosesAndFinalizesLog() throws Exception {
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store,recorder(100,65536))) {
            var fatal = new AssertionError("private detail");
            var experiment = new Experiment<ExperimentScenario>() {
                public String type() { return "test"; }
                public ExperimentSummary execute(ExperimentScenario scenario, java.util.function.Consumer<MetricPoint> sink) {
                    throw fatal;
                }
            };
            assertSame(fatal,assertThrows(AssertionError.class,()->runner.run(experiment,()->"error","error")));
            var ref = store.artifacts("error").getFirst();
            String content = Files.readString(root.resolve(ref.relativePath()));
            assertFalse(content.contains("private detail"));
            assertTrue(content.contains("EXECUTION_FAILED"));
            try (var files = Files.list(root.resolve("logs"))) {
                assertEquals(0,files.filter(path -> path.toString().endsWith(".partial")).count());
            }
        }
    }
    @Test void concurrentSessionsKeepRunIdsSeparateAndReportCollectionCost() throws Exception {
        long started = System.nanoTime();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var recorder = recorder(100,65536);
            var tasks = new ArrayList<java.util.concurrent.Future<ArtifactReference>>();
            for (String id : List.of("a","b")) tasks.add(executor.submit(() -> {
                try (var session = recorder.start(id)) {
                    for (int i=0;i<100;i++) session.record(Stage.EXECUTION,Event.SUCCEEDED,Reason.NONE);
                    return session.finish(Reason.NONE);
                }
            }));
            long maxBytes = 0;
            for (var task : tasks) {
                var ref = task.get();
                maxBytes = Math.max(maxBytes,ref.byteSize());
                for (String line : Files.readAllLines(root.resolve(ref.relativePath()))) {
                    assertEquals(ref.runId(),json.readTree(line).get("job_id").asText());
                }
            }
            var report = Path.of("build/reports/observability/run-logs.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report,json.writeValueAsString(Map.of("runs",2,"attemptedEventsPerRun",102,
                    "maxArtifactBytes",maxBytes,"elapsedNanosIncludingReadVerification",System.nanoTime()-started)));
        }
    }
}
