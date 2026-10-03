package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RetryingBatchTransactionTest {
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:retry_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final PipelineProperties.Insert settings = new PipelineProperties.Insert();

    RetryingBatchTransactionTest() {
        jdbc.execute("create table sample(item integer)");
        settings.setRetryBackoffMs(20);
    }
    private RetryingBatchTransaction writer(DataSourceTransactionManager manager) {
        return new RetryingBatchTransaction(new TransactionTemplate(manager), settings);
    }
    private int rows() { return jdbc.queryForObject("select count(*) from sample", Integer.class); }

    @Test void rollsBackPartialWritesBeforeRetryAndBacksOffExponentially() throws Exception {
        var attempts = new AtomicInteger(); var retries = new AtomicInteger();
        long start = System.nanoTime();
        int result = writer(new DataSourceTransactionManager(dataSource)).execute(() -> {
            jdbc.update("insert into sample values (1)");
            if (attempts.incrementAndGet() < 3) throw new CannotAcquireLockException("injected");
            return 1;
        }, retries);
        assertEquals(1, result); assertEquals(1, rows());
        assertEquals(3, attempts.get()); assertEquals(2, retries.get());
        assertTrue(System.nanoTime() - start >= 60_000_000L);
    }

    @Test void exhaustionAndPermanentErrorsDoNotLeavePartialWrites() {
        for (boolean transientFailure : new boolean[]{true, false}) {
            var attempts = new AtomicInteger(); var retries = new AtomicInteger();
            assertThrows(DataAccessException.class, () -> writer(new DataSourceTransactionManager(dataSource)).execute(() -> {
                attempts.incrementAndGet(); jdbc.update("insert into sample values (1)");
                if (transientFailure) throw new CannotAcquireLockException("injected");
                throw new DataIntegrityViolationException("injected");
            }, retries));
            assertEquals(transientFailure ? 3 : 1, attempts.get());
            assertEquals(transientFailure ? 2 : 0, retries.get()); assertEquals(0, rows());
        }
    }

    @Test void eachAttemptIsIndependentOfAnAmbientTransaction() {
        var manager = new DataSourceTransactionManager(dataSource);
        var attempts = new AtomicInteger();
        new TransactionTemplate(manager).executeWithoutResult(outer -> {
            try {
                writer(manager).execute(() -> {
                    jdbc.update("insert into sample values (1)");
                    if (attempts.incrementAndGet() == 1) throw new CannotAcquireLockException("injected");
                    return 1;
                }, new AtomicInteger());
            } catch (InterruptedException ex) { throw new AssertionError(ex); }
            outer.setRollbackOnly();
        });
        assertEquals(2, attempts.get()); assertEquals(1, rows());
    }

    @Test void uncertainCommitIsNeverReplayed() {
        var manager = new DataSourceTransactionManager(dataSource) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new TransientDataAccessResourceException("lost commit acknowledgement");
            }
        };
        var attempts = new AtomicInteger(); var retries = new AtomicInteger();
        assertThrows(TransientDataAccessResourceException.class, () -> writer(manager).execute(() -> {
            attempts.incrementAndGet(); return jdbc.update("insert into sample values (1)");
        }, retries));
        assertEquals(1, attempts.get()); assertEquals(0, retries.get()); assertEquals(1, rows());
    }

    @Test void failedRollbackIsNeverReplayed() {
        var manager = new DataSourceTransactionManager(dataSource) {
            @Override protected void doRollback(DefaultTransactionStatus status) {
                super.doRollback(status);
                throw new TransientDataAccessResourceException("rollback acknowledgement lost");
            }
        };
        var retries = new AtomicInteger(); var attempts = new AtomicInteger();
        assertThrows(TransientDataAccessResourceException.class, () -> writer(manager).execute(() -> {
            attempts.incrementAndGet(); jdbc.update("insert into sample values (1)");
            throw new CannotAcquireLockException("injected");
        }, retries));
        assertEquals(1, attempts.get()); assertEquals(0, retries.get());
    }

    @Test void interruptionDuringBackoffStopsBeforeAnotherAttempt() {
        var retries = new AtomicInteger();
        try {
            assertThrows(InterruptedException.class, () -> writer(new DataSourceTransactionManager(dataSource)).execute(() -> {
                jdbc.update("insert into sample values (1)");
                Thread.currentThread().interrupt();
                throw new CannotAcquireLockException("injected");
            }, retries));
            assertEquals(0, retries.get()); assertEquals(0, rows());
        } finally { Thread.interrupted(); }
    }

    @Test void oneAttemptDisablesRetriesAndSettingsRejectInvalidValues() {
        settings.setMaxAttempts(1);
        var retries = new AtomicInteger();
        assertThrows(CannotAcquireLockException.class, () -> writer(new DataSourceTransactionManager(dataSource)).execute(() -> {
            throw new CannotAcquireLockException("injected");
        }, retries));
        assertEquals(0, retries.get());
        assertThrows(IllegalArgumentException.class, () -> settings.setMaxAttempts(0));
        assertThrows(IllegalArgumentException.class, () -> settings.setMaxAttempts(11));
        assertThrows(IllegalArgumentException.class, () -> settings.setRetryBackoffMs(0));
        assertThrows(IllegalArgumentException.class, () -> settings.setRetryBackoffMs(60001));
    }
}
