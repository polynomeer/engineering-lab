package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Retries only callback failures after a successful rollback, never uncertain commits. */
final class RetryingBatchTransaction {
    private final TransactionTemplate transaction;
    private final int maxAttempts;
    private final long initialBackoffMs;

    RetryingBatchTransaction(TransactionTemplate template, PipelineProperties.Insert settings) {
        transaction = new TransactionTemplate(template.getTransactionManager(), template);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        maxAttempts = settings.getMaxAttempts();
        initialBackoffMs = settings.getRetryBackoffMs();
    }

    <T> T execute(Supplier<T> batch, AtomicInteger retries) throws InterruptedException {
        return execute(batch, retries, elapsed -> { });
    }

    <T> T execute(Supplier<T> batch, AtomicInteger retries, java.util.function.LongConsumer connectionWait)
            throws InterruptedException {
        long delay = initialBackoffMs;
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Batch insert interrupted");
            long attemptStart = System.nanoTime();
            try {
                return transaction.execute(status -> {
                    connectionWait.accept(System.nanoTime() - attemptStart);
                    try { return batch.get(); }
                    catch (TransientDataAccessException failure) { throw new RolledBackBatch(failure); }
                });
            } catch (RolledBackBatch failure) {
                if (attempt >= maxAttempts) throw (TransientDataAccessException) failure.getCause();
                Thread.sleep(delay);
                retries.incrementAndGet();
                delay = Math.min(60000, delay * 2);
            }
        }
    }

    private static final class RolledBackBatch extends RuntimeException {
        RolledBackBatch(TransientDataAccessException cause) { super(cause); }
    }
}
