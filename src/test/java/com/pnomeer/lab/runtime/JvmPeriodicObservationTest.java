package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.metrics.JvmSnapshot;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JvmPeriodicObservationTest {
    @TempDir Path root;
    @Test void capsPeriodicSamplesAndPersistsEndAfterSamplerStops() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (var store = new JdbcRunStore(root,20,1);
                 var runner = new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"),
                         new MetricWriterOptions(1,1,10,1,1,1000),1,1,JvmSnapshot::capture,100,2)) {
                var experiment = new Experiment<ExperimentScenario>() {
                    public String type() { return "test"; }
                    public ExperimentSummary execute(ExperimentScenario s, java.util.function.Consumer<MetricPoint> sink) throws Exception {
                        // Wait for the cap through persisted evidence, not timing-dependent sleeps.
                        while (store.metrics("periodic",-1,20).size() < 3) Thread.sleep(10);
                        sink.accept(new MetricPoint(1,Map.of(),Map.of("work",1L),Map.of()));
                        return ExperimentSummary.empty();
                    }
                };
                var result = runner.run(experiment, () -> "test", "periodic");
                assertEquals(ExperimentStatus.SUCCEEDED,result.status());
                assertEquals("PERIODIC",result.runMetadata().parameters().get("observation.jvm.mode"));
                assertEquals("100",result.runMetadata().parameters().get("observation.jvm.intervalMs"));
                assertEquals(2L,result.summary().counters().get("observation.jvm.periodicSamples"));
                assertEquals(1L,result.summary().counters().get("observation.jvm.capReached"));
                assertEquals(4L,result.summary().counters().get("observation.jvm.samples"));
                var points = store.metrics("periodic",-1,20);
                assertEquals(5,points.size());
                assertEquals("end",points.getLast().point().labels().get("phase"));
                assertEquals(1L,ExperimentMetricPreview.read(store,"periodic",1).getFirst().counters().get("work"));
            }
            try (var store = new JdbcRunStore(root,20,1)) {
                assertEquals(5,store.metrics("periodic",-1,20).size());
            }
        });
    }
    @Test void shutdownWaitsForInFlightPublicationBeforeEndWithoutCatchupQueue() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var phases = new CopyOnWriteArrayList<String>();
        var session = new JvmObservationSession(JvmSnapshot::capture, point -> {
            String phase = point.labels().get("phase");
            if (phase.equals("periodic")) {
                entered.countDown();
                try { if (!release.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
            phases.add(phase);
        },100,100,2000,"stop");
        try (var executor = Executors.newSingleThreadExecutor()) {
            session.start();
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var finish = executor.submit(session::finish);
            try { assertThrows(TimeoutException.class, () -> finish.get(50,TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            finish.get(2,TimeUnit.SECONDS);
            assertEquals(List.of("start","periodic","end"),phases);
            assertEquals(0L,session.counters().get("observation.jvm.capReached"));
        } finally { release.countDown(); }
    }
    @Test void stopTimeoutInterruptsBlockedPublisherAndDoesNotPublishEnd() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var phases = new CopyOnWriteArrayList<String>();
        var session = new JvmObservationSession(JvmSnapshot::capture, p -> {
            String phase = p.labels().get("phase");
            if (phase.equals("periodic")) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ex) { interrupted.countDown(); throw new IllegalStateException(ex); }
            }
            phases.add(phase);
        },100,2,50,"timeout");
        session.start(); assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class,session::finish);
        assertTrue(interrupted.await(2,TimeUnit.SECONDS));
        assertEquals(List.of("start"),phases);
    }
    @Test void propagatesBackgroundFailureAndValidatesConfiguration() throws Exception {
        var failed = new CountDownLatch(1);
        var session = new JvmObservationSession(JvmSnapshot::capture, p -> {
            if (p.labels().get("phase").equals("periodic")) {
                failed.countDown(); throw new IllegalStateException("disk failed");
            }
        },100,2,1000,"failure");
        session.start(); assertTrue(failed.await(2,TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class,session::finish);
        assertEquals(1L,session.counters().get("observation.jvm.samples"));
        assertThrows(IllegalArgumentException.class, () -> new ObservationProperties(true,1,100));
        assertThrows(IllegalArgumentException.class, () -> new ObservationProperties(true,100,10001));
        assertThrows(IllegalArgumentException.class, () -> new ObservationProperties(true,100,0));
    }
}
