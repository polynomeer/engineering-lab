package com.example.pipeline;

import com.example.pipeline.model.IngestItem;
import com.example.pipeline.model.MappedRow;
import com.example.pipeline.model.RawRow;
import com.example.pipeline.model.ValidationError;
import com.example.pipeline.queue.BoundedChannel;
import com.example.pipeline.stage.Envelope;
import com.example.pipeline.stage.ShutdownSignals;
import com.polynomeer.excelpipeline.config.PipelineProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class PipelineRunner {
    public static final int DUMMY_ROW_COUNT = 100;

    private final PipelineProperties properties;

    public PipelineRunner(PipelineProperties properties) {
        this.properties = properties;
    }

    public PipelineRunResult runDummyPipeline() throws InterruptedException {
        BoundedChannel<Envelope<RawRow>> rawChannel =
                new BoundedChannel<>(properties.getQueue().getRawCapacity(), properties);
        BoundedChannel<Envelope<MappedRow>> mappedChannel =
                new BoundedChannel<>(properties.getQueue().getMappedCapacity(), properties);

        int validatorWorkers = properties.getThreads().getValidator();
        int inserterWorkers = properties.getThreads().getInserter();

        var validationErrors = new ConcurrentLinkedQueue<ValidationError>();
        var insertedCount = new AtomicInteger(0);

        ExecutorService validatorPool = Executors.newFixedThreadPool(validatorWorkers);
        ExecutorService inserterPool = Executors.newFixedThreadPool(inserterWorkers);
        try {
            List<Future<?>> validatorFutures = startValidatorMapWorkers(
                    validatorPool, validatorWorkers, rawChannel, mappedChannel, validationErrors);
            List<Future<?>> inserterFutures = startInsertWorkers(
                    inserterPool, inserterWorkers, mappedChannel, insertedCount);

            produceDummyRows(rawChannel, DUMMY_ROW_COUNT);
            ShutdownSignals.publishEndSignals(rawChannel, validatorWorkers);

            waitForWorkers(validatorFutures);
            ShutdownSignals.publishEndSignals(mappedChannel, inserterWorkers);
            waitForWorkers(inserterFutures);

            return new PipelineRunResult(DUMMY_ROW_COUNT, insertedCount.get(), new ArrayList<>(validationErrors));
        } finally {
            validatorPool.shutdownNow();
            inserterPool.shutdownNow();
        }
    }

    private static void produceDummyRows(BoundedChannel<Envelope<RawRow>> rawChannel, int rowCount)
            throws InterruptedException {
        for (int i = 0; i < rowCount; i++) {
            String col1 = (i % 10 == 0) ? "" : "col1-" + i;
            String col2 = (i % 15 == 0) ? "" : "col2-" + i;
            RawRow row = new RawRow(i, List.of(col1, col2, String.valueOf(i)));
            rawChannel.put(Envelope.data(row));
        }
    }

    private static List<Future<?>> startValidatorMapWorkers(
            ExecutorService pool,
            int workerCount,
            BoundedChannel<Envelope<RawRow>> rawChannel,
            BoundedChannel<Envelope<MappedRow>> mappedChannel,
            ConcurrentLinkedQueue<ValidationError> validationErrors) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    while (true) {
                        Envelope<RawRow> envelope = rawChannel.take();
                        if (envelope.isEnd()) {
                            return;
                        }

                        RawRow row = envelope.getPayload();
                        String col1 = cellAt(row, 0);
                        String col2 = cellAt(row, 1);
                        String col3 = cellAt(row, 2);
                        if (isBlank(col1)) {
                            validationErrors.add(new ValidationError(row.getRowIndex(), "col1 is required", row));
                            continue;
                        }
                        if (isBlank(col2)) {
                            validationErrors.add(new ValidationError(row.getRowIndex(), "col2 is required", row));
                            continue;
                        }

                        int col3Int;
                        try {
                            col3Int = Integer.parseInt(col3);
                        } catch (NumberFormatException ex) {
                            validationErrors.add(new ValidationError(row.getRowIndex(), "col3 must be integer", row));
                            continue;
                        }

                        IngestItem item = new IngestItem(col1, col2, col3Int);
                        mappedChannel.put(Envelope.data(new MappedRow(row.getRowIndex(), item)));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        return futures;
    }

    private static List<Future<?>> startInsertWorkers(
            ExecutorService pool,
            int workerCount,
            BoundedChannel<Envelope<MappedRow>> mappedChannel,
            AtomicInteger insertedCount) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    while (true) {
                        Envelope<MappedRow> envelope = mappedChannel.take();
                        if (envelope.isEnd()) {
                            return;
                        }
                        insertedCount.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        return futures;
    }

    private static void waitForWorkers(List<Future<?>> futures) throws InterruptedException {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException ex) {
                throw new IllegalStateException("worker failed", ex.getCause());
            }
        }
    }

    private static String cellAt(RawRow row, int index) {
        return index < row.getCells().size() ? row.getCells().get(index) : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class PipelineRunResult {
        private final int producedCount;
        private final int insertedCount;
        private final List<ValidationError> validationErrors;

        public PipelineRunResult(int producedCount, int insertedCount, List<ValidationError> validationErrors) {
            this.producedCount = producedCount;
            this.insertedCount = insertedCount;
            this.validationErrors = List.copyOf(validationErrors);
        }

        public int getProducedCount() {
            return producedCount;
        }

        public int getInsertedCount() {
            return insertedCount;
        }

        public List<ValidationError> getValidationErrors() {
            return validationErrors;
        }
    }
}
