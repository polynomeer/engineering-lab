package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.GitProvenance;
import com.pnomeer.lab.storage.MetricWriterOptions;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MeasurementReservationTest {
    private static DurableExperimentRunner runner() {
        return new DurableExperimentRunner(null, () -> GitProvenance.unknown("test"), new MetricWriterOptions(8, 4, 20, 2, 1, 2000), 5, 2);
    }

    private static Throwable prepareElsewhere(DurableExperimentRunner runner) throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            return pool.submit(() -> {
                try { runner.prepare(null, null, "other"); return null; }
                catch (Throwable t) { return t; }
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test void otherThreadsCannotPrepareWhileReservedAndCanAfterwards() throws Exception {
        try (var runner = runner()) {
            var reservation = runner.reserveMeasurement();
            assertInstanceOf(DurableExperimentRunner.MeasurementReserved.class, prepareElsewhere(runner));
            reservation.close();
            // Released: the other thread now gets past the reservation check (and fails later on the null experiment).
            assertFalse(prepareElsewhere(runner) instanceof DurableExperimentRunner.MeasurementReserved);
        }
    }

    @Test void nestedReservationsOnTheOwnerEndOnlyWhenAllClose() throws Exception {
        try (var runner = runner()) {
            var sweep = runner.reserveMeasurement();
            var series = runner.reserveMeasurement();
            series.close();
            series.close(); // closing twice must not release the outer holder
            assertInstanceOf(DurableExperimentRunner.MeasurementReserved.class, prepareElsewhere(runner));
            sweep.close();
            assertFalse(prepareElsewhere(runner) instanceof DurableExperimentRunner.MeasurementReserved);
        }
    }

    @Test void anotherThreadCannotTakeAHeldReservation() throws Exception {
        try (var runner = runner(); var pool = Executors.newSingleThreadExecutor()) {
            try (var held = runner.reserveMeasurement()) {
                var failure = pool.submit(() -> {
                    try { runner.reserveMeasurement().close(); return null; } catch (Throwable t) { return t; }
                }).get(5, TimeUnit.SECONDS);
                assertInstanceOf(DurableExperimentRunner.MeasurementReserved.class, failure);
            }
        }
    }
}
