package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.app.run.RunEvidenceController;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingFile;
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

class JfrRecorderTest {
    @TempDir Path root;
    private JfrRecorder recorder(boolean enabled, int seconds) {
        return new JfrRecorder(root, new JfrProperties(enabled, seconds, 1024 * 1024));
    }
    private DurableExperimentRunner runner(RunStore store, JfrRecorder recorder) {
        return new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"),
                new MetricWriterOptions(1, 1, 10, 1, 1, 5000), 1, 1, null, 0, 1, false, 1, recorder);
    }
    private Experiment<ExperimentScenario> experiment(boolean fail) {
        return new Experiment<>() {
            public String type() { return "test"; }
            public ExperimentSummary execute(ExperimentScenario scenario, java.util.function.Consumer<MetricPoint> sink) {
                System.gc(); // Produce real allowlisted events; no timing or performance assertion.
                if (fail) throw new IllegalStateException("secret-input");
                return ExperimentSummary.empty();
            }
        };
    }

    @Test void persistsReadableCheckedArtifactsForSuccessAndFailureAcrossRestart() throws Exception {
        Set<Long> before = recordingIds();
        try (var store = new JdbcRunStore(root, 10, 1); var runner = runner(store, recorder(true, 60))) {
            for (boolean fail : List.of(false, true)) {
                String id = fail ? "failed" : "success";
                var result = runner.run(experiment(fail), () -> "test", id);
                assertEquals(fail ? ExperimentStatus.FAILED : ExperimentStatus.SUCCEEDED, result.status());
                assertEquals("BASIC_V1", result.runMetadata().parameters().get("observation.jfr.mode"));
                var artifact = store.artifacts(id).getFirst();
                Path path = root.resolve(artifact.relativePath());
                assertEquals(Files.size(path), artifact.byteSize());
                assertTrue(artifact.byteSize() <= 1024 * 1024);
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))), artifact.sha256());
                int events = 0;
                try (var recording = new RecordingFile(path)) {
                    while (recording.hasMoreEvents()) {
                        var event = recording.readEvent();
                        assertTrue(JfrRecorder.EVENTS.contains(event.getEventType().getName()));
                        assertNull(event.getStackTrace());
                        events++;
                    }
                }
                assertTrue(events > 0);
                assertEquals(artifact.byteSize(), result.summary().counters().get("observation.jfr.bytes"));
                var report = Path.of("build/reports/observability/jfr-" + id + ".json");
                Files.createDirectories(report.getParent());
                Files.writeString(report, JsonMapper.builder().build().writeValueAsString(result.summary().counters()));
            }
        }
        assertEquals(before, recordingIds());
        try (var store = new JdbcRunStore(root, 10, 1)) {
            var properties = new StorageProperties(root.toString(),1,1,10,1,1,1000,10,1,1,".",100);
            var mvc = MockMvcBuilders.standaloneSetup(new RunEvidenceController(store, properties)).build();
            mvc.perform(get("/runs/success/artifacts")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].relativePath").value(org.hamcrest.Matchers.startsWith("jfr/")));
            String exported = mvc.perform(get("/runs/failed/export")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(exported.contains("artifact-reference"));
            assertTrue(exported.contains(store.artifacts("failed").getFirst().sha256()));
        }
    }

    @Test void disabledCreatesNoFilesOrRecordingAndInvalidConfigurationIsRejected() throws Exception {
        assertNull(recorder(false, 60).start("off"));
        assertFalse(Files.exists(root.resolve("jfr")));
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store, recorder(false,60))) {
            var result = runner.run(experiment(false), () -> "test", "off");
            assertEquals("OFF", result.runMetadata().parameters().get("observation.jfr.mode"));
            assertTrue(store.artifacts("off").isEmpty());
            assertFalse(result.summary().counters().containsKey("observation.jfr.bytes"));
        }
        assertThrows(IllegalArgumentException.class, () -> new JfrProperties(true,0,1024*1024));
        assertThrows(IllegalArgumentException.class, () -> new JfrProperties(true,60,0));
    }

    @Test void stopsAtDurationAndClosesSessionWithoutRetainingRecordings() throws Exception {
        Set<Long> before = recordingIds();
        try (var session = recorder(true,1).start("timer")) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (FlightRecorder.getFlightRecorder().getRecordings().stream()
                    .anyMatch(r -> !before.contains(r.getId()) && r.getState() == RecordingState.RUNNING)) {
                assertTrue(System.nanoTime() < deadline, "JFR duration timer did not stop recording");
                Thread.sleep(20);
            }
            assertTrue(Files.exists(root.resolve(session.finish().relativePath())));
            assertEquals(1L, session.counters().get("observation.jfr.durationLimitReached"));
            assertThrows(IllegalStateException.class, session::finish);
        }
        assertEquals(before, recordingIds());
    }

    @Test void hardCopyLimitNeverWritesBeyondBudget() throws Exception {
        var output = new ByteArrayOutputStream();
        assertEquals(8192, JfrRecorder.copyBounded(new ByteArrayInputStream(new byte[8192]), output, 8192));
        output.reset();
        assertThrows(IOException.class, () -> JfrRecorder.copyBounded(new ByteArrayInputStream(new byte[8193]), output, 8192));
        assertEquals(8192, output.size());
    }

    @Name("lab.test.ForeignEvent") @Enabled(false) @StackTrace(false)
    static class ForeignEvent extends Event { String secret = "not-for-artifact"; }

    @Test void rejectsSharedForeignEventsAndRemovesPartialFile() throws Exception {
        Set<Long> before = recordingIds();
        try (var foreign = new Recording(); var session = recorder(true,60).start("foreign")) {
            foreign.enable(ForeignEvent.class);
            foreign.start();
            new ForeignEvent().commit();
            foreign.stop();
            assertThrows(IOException.class, session::finish);
        }
        try (var files = Files.list(root.resolve("jfr"))) { assertEquals(0, files.count()); }
        assertEquals(before, recordingIds());
    }

    @Test void startupFailurePreventsExperimentAndIsNotReportedAsSuccess() throws Exception {
        Files.writeString(root.resolve("jfr"), "not a directory");
        var ran = new AtomicBoolean();
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store, recorder(true,60))) {
            var result = runner.run(new Experiment<ExperimentScenario>() {
                public String type() { return "test"; }
                public ExperimentSummary execute(ExperimentScenario scenario, java.util.function.Consumer<MetricPoint> sink) {
                    ran.set(true); return ExperimentSummary.empty();
                }
            }, () -> "test", "failure");
            assertFalse(ran.get());
            assertEquals(ExperimentStatus.FAILED, result.status());
            assertTrue(store.artifacts("failure").isEmpty());
        }
    }

    @Test void registrationFailureMarksRunFailedAndKeepsCaptureForRecovery() throws Exception {
        Set<Long> before = recordingIds();
        try (var actual = new JdbcRunStore(root,10,1)) {
            var store = org.mockito.Mockito.spy(actual);
            org.mockito.Mockito.doThrow(new IllegalStateException("registration unavailable"))
                    .when(store).registerArtifact(org.mockito.ArgumentMatchers.any());
            try (var runner = runner(store, recorder(true,60))) {
                var result = runner.run(experiment(false), () -> "test", "registration");
                assertEquals(ExperimentStatus.FAILED, result.status());
                assertTrue(result.failureMessage().contains("JFR capture or registration failed"));
                assertTrue(actual.artifacts("registration").isEmpty());
                try (var files = Files.list(root.resolve("jfr"))) {
                    assertEquals(1, files.filter(p -> p.toString().endsWith(".jfr")).count());
                }
            }
        }
        assertEquals(before, recordingIds());
    }

    private Set<Long> recordingIds() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream().map(Recording::getId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
