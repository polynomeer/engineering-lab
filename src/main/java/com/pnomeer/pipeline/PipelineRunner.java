package com.pnomeer.pipeline;

import com.pnomeer.pipeline.model.IngestItem;
import com.pnomeer.pipeline.model.MappedRow;
import com.pnomeer.pipeline.model.RawRow;
import com.pnomeer.pipeline.model.ValidationError;
import com.pnomeer.pipeline.parse.ExcelStreamingReader;
import com.pnomeer.pipeline.queue.BoundedChannel;
import com.pnomeer.pipeline.stage.Envelope;
import com.pnomeer.pipeline.stage.ShutdownSignals;
import com.pnomeer.pipeline.config.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class PipelineRunner {
    private static final String INSERT_SQL = "insert into ingest_item(col1, col2, col3) values (?, ?, ?)";
    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final PipelineProperties properties;
    private final ExcelStreamingReader excelReader;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final long insertDelayMs;

    public enum RunMode {
        PIPELINE,
        SINGLE_THREAD
    }

    public PipelineRunner(
            PipelineProperties properties,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this(properties, new ExcelStreamingReader(), jdbcTemplate, transactionTemplate, 0L);
    }

    public PipelineRunner(
            PipelineProperties properties,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            long insertDelayMs) {
        this(properties, new ExcelStreamingReader(), jdbcTemplate, transactionTemplate, insertDelayMs);
    }

    public PipelineRunner(
            PipelineProperties properties,
            ExcelStreamingReader excelReader,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            long insertDelayMs) {
        this.properties = properties;
        this.excelReader = excelReader;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.insertDelayMs = Math.max(0L, insertDelayMs);
    }

    public PipelineRunResult run(InputStream excelInputStream) throws InterruptedException, IOException {
        return run(excelInputStream, RunMode.PIPELINE, null);
    }

    public PipelineRunResult run(InputStream excelInputStream, Consumer<ProgressSnapshot> progressListener)
            throws InterruptedException, IOException {
        return run(excelInputStream, RunMode.PIPELINE, progressListener);
    }

    public PipelineRunResult run(
            InputStream excelInputStream,
            RunMode runMode,
            Consumer<ProgressSnapshot> progressListener)
            throws InterruptedException, IOException {
        if (runMode == RunMode.SINGLE_THREAD) {
            return runSingleThread(excelInputStream, progressListener);
        }
        return runPipelined(excelInputStream, progressListener);
    }

    private PipelineRunResult runPipelined(InputStream excelInputStream, Consumer<ProgressSnapshot> progressListener)
            throws InterruptedException, IOException {
        long startNanos = System.nanoTime();
        BoundedChannel<Envelope<RawRow>> rawChannel =
                new BoundedChannel<>(properties.getQueue().getRawCapacity(), properties);
        BoundedChannel<Envelope<MappedRow>> mappedChannel =
                new BoundedChannel<>(properties.getQueue().getMappedCapacity(), properties);

        int validatorWorkers = properties.getThreads().getValidator();
        int inserterWorkers = properties.getThreads().getInserter();

        var validationErrors = new ConcurrentLinkedQueue<ValidationError>();
        var producedCount = new AtomicInteger(0);
        var mappedCount = new AtomicInteger(0);
        var insertedCount = new AtomicInteger(0);

        ExecutorService validatorPool = Executors.newFixedThreadPool(validatorWorkers);
        ExecutorService inserterPool = Executors.newFixedThreadPool(inserterWorkers);
        ScheduledExecutorService metricsLogger = startMetricsLogger(
                rawChannel,
                mappedChannel,
                producedCount,
                mappedCount,
                insertedCount,
                startNanos,
                progressListener);
        try {
            List<Future<?>> validatorFutures = startValidatorMapWorkers(
                    validatorPool, validatorWorkers, rawChannel, mappedChannel, validationErrors, mappedCount);
            List<Future<?>> inserterFutures = startInsertWorkers(inserterPool, inserterWorkers, mappedChannel, insertedCount);

            produceFromExcel(excelInputStream, rawChannel, producedCount);
            ShutdownSignals.publishEndSignals(rawChannel, validatorWorkers);

            waitForWorkers(validatorFutures);
            ShutdownSignals.publishEndSignals(mappedChannel, inserterWorkers);
            waitForWorkers(inserterFutures);

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new PipelineRunResult(
                    producedCount.get(),
                    insertedCount.get(),
                    new ArrayList<>(validationErrors),
                    rawChannel.getCumulativeEnqueueWaitNanos(),
                    mappedChannel.getCumulativeEnqueueWaitNanos(),
                    rawChannel.getEnqueuedCount(),
                    mappedChannel.getEnqueuedCount(),
                    elapsedMillis);
        } finally {
            metricsLogger.shutdownNow();
            validatorPool.shutdownNow();
            inserterPool.shutdownNow();
        }
    }

    private PipelineRunResult runSingleThread(InputStream excelInputStream, Consumer<ProgressSnapshot> progressListener)
            throws IOException, InterruptedException {
        long startNanos = System.nanoTime();
        int chunkSize = properties.getInsert().getChunkSize();
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("insert.chunkSize must be > 0");
        }

        List<ValidationError> validationErrors = new ArrayList<>();
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger mappedCount = new AtomicInteger(0);
        AtomicInteger insertedCount = new AtomicInteger(0);
        List<MappedRow> chunk = new ArrayList<>(chunkSize);

        AtomicLong lastNanos = new AtomicLong(startNanos);
        AtomicInteger lastProduced = new AtomicInteger(0);
        AtomicInteger lastMapped = new AtomicInteger(0);
        AtomicInteger lastInserted = new AtomicInteger(0);

        excelReader.readFirstSheet(excelInputStream, rawRow -> {
            producedCount.incrementAndGet();

            String col1 = cellAt(rawRow, 0);
            String col2 = cellAt(rawRow, 1);
            String col3 = cellAt(rawRow, 2);
            if (isBlank(col1)) {
                validationErrors.add(new ValidationError(rawRow.getRowIndex(), "col1 is required", rawRow));
                emitSingleThreadProgress(
                        startNanos, lastNanos, producedCount, mappedCount, insertedCount, lastProduced, lastMapped, lastInserted, progressListener);
                return;
            }
            if (isBlank(col2)) {
                validationErrors.add(new ValidationError(rawRow.getRowIndex(), "col2 is required", rawRow));
                emitSingleThreadProgress(
                        startNanos, lastNanos, producedCount, mappedCount, insertedCount, lastProduced, lastMapped, lastInserted, progressListener);
                return;
            }

            int col3Int;
            try {
                col3Int = Integer.parseInt(col3);
            } catch (NumberFormatException ex) {
                validationErrors.add(new ValidationError(rawRow.getRowIndex(), "col3 must be integer", rawRow));
                emitSingleThreadProgress(
                        startNanos, lastNanos, producedCount, mappedCount, insertedCount, lastProduced, lastMapped, lastInserted, progressListener);
                return;
            }

            chunk.add(new MappedRow(rawRow.getRowIndex(), new IngestItem(col1, col2, col3Int)));
            mappedCount.incrementAndGet();
            if (chunk.size() >= chunkSize) {
                try {
                    flushChunk(chunk, insertedCount);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while flushing chunk", ex);
                }
            }
            emitSingleThreadProgress(
                    startNanos, lastNanos, producedCount, mappedCount, insertedCount, lastProduced, lastMapped, lastInserted, progressListener);
        });

        flushChunk(chunk, insertedCount);
        emitSingleThreadProgress(
                startNanos, lastNanos, producedCount, mappedCount, insertedCount, lastProduced, lastMapped, lastInserted, progressListener);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return new PipelineRunResult(
                producedCount.get(),
                insertedCount.get(),
                List.copyOf(validationErrors),
                0L,
                0L,
                0L,
                0L,
                elapsedMillis);
    }

    private void produceFromExcel(
            InputStream excelInputStream,
            BoundedChannel<Envelope<RawRow>> rawChannel,
            AtomicInteger producedCount)
            throws IOException, InterruptedException {
        excelReader.readFirstSheet(excelInputStream, rawRow -> {
            try {
                rawChannel.put(Envelope.data(rawRow));
                producedCount.incrementAndGet();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while enqueueing raw row", ex);
            }
        });
    }

    private static List<Future<?>> startValidatorMapWorkers(
            ExecutorService pool,
            int workerCount,
            BoundedChannel<Envelope<RawRow>> rawChannel,
            BoundedChannel<Envelope<MappedRow>> mappedChannel,
            ConcurrentLinkedQueue<ValidationError> validationErrors,
            AtomicInteger mappedCount) {
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
                        mappedCount.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        return futures;
    }

    private List<Future<?>> startInsertWorkers(
            ExecutorService pool,
            int workerCount,
            BoundedChannel<Envelope<MappedRow>> mappedChannel,
            AtomicInteger insertedCount) {
        int chunkSize = properties.getInsert().getChunkSize();
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("insert.chunkSize must be > 0");
        }

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            futures.add(pool.submit(() -> {
                List<MappedRow> chunk = new ArrayList<>(chunkSize);
                try {
                    while (true) {
                        Envelope<MappedRow> envelope = mappedChannel.take();
                        if (envelope.isEnd()) {
                            flushChunk(chunk, insertedCount);
                            return;
                        }
                        chunk.add(envelope.getPayload());
                        if (chunk.size() >= chunkSize) {
                            flushChunk(chunk, insertedCount);
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        return futures;
    }

    private void flushChunk(List<MappedRow> chunk, AtomicInteger insertedCount) throws InterruptedException {
        if (chunk.isEmpty()) {
            return;
        }
        if (insertDelayMs > 0) {
            Thread.sleep(insertDelayMs);
        }

        Integer inserted = transactionTemplate.execute(status -> {
            int[][] updated = jdbcTemplate.batchUpdate(
                    INSERT_SQL,
                    chunk,
                    chunk.size(),
                    (ps, mappedRow) -> {
                        IngestItem item = mappedRow.getItem();
                        ps.setString(1, item.getCol1());
                        ps.setString(2, item.getCol2());
                        ps.setInt(3, item.getCol3Int());
                    });
            return normalizeBatchUpdateCount(updated, chunk.size());
        });
        insertedCount.addAndGet(inserted == null ? 0 : inserted);
        chunk.clear();
    }

    private ScheduledExecutorService startMetricsLogger(
            BoundedChannel<Envelope<RawRow>> rawChannel,
            BoundedChannel<Envelope<MappedRow>> mappedChannel,
            AtomicInteger producedCount,
            AtomicInteger mappedCount,
            AtomicInteger insertedCount,
            long startNanos,
            Consumer<ProgressSnapshot> progressListener) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicLong lastNanos = new AtomicLong(System.nanoTime());
        AtomicInteger lastProduced = new AtomicInteger(0);
        AtomicInteger lastMapped = new AtomicInteger(0);
        AtomicInteger lastInserted = new AtomicInteger(0);
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            long deltaNanos = now - lastNanos.getAndSet(now);
            double seconds = Math.max(0.001d, deltaNanos / 1_000_000_000.0d);

            int produced = producedCount.get();
            int mapped = mappedCount.get();
            int inserted = insertedCount.get();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(now - startNanos);

            double producedRate = (produced - lastProduced.getAndSet(produced)) / seconds;
            double mappedRate = (mapped - lastMapped.getAndSet(mapped)) / seconds;
            double insertedRate = (inserted - lastInserted.getAndSet(inserted)) / seconds;

            log.info(
                    "pipeline metrics rawQ={} mappedQ={} produced={} mapped={} inserted={} rate/s[p={},m={},i={}]",
                    rawChannel.size(),
                    mappedChannel.size(),
                    produced,
                    mapped,
                    inserted,
                    Math.round(producedRate),
                    Math.round(mappedRate),
                    Math.round(insertedRate));
            if (progressListener != null) {
                progressListener.accept(new ProgressSnapshot(
                        rawChannel.size(),
                        mappedChannel.size(),
                        produced,
                        mapped,
                        inserted,
                        Math.round(producedRate),
                        Math.round(mappedRate),
                        Math.round(insertedRate),
                        elapsedMillis));
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    private static void emitSingleThreadProgress(
            long startNanos,
            AtomicLong lastNanos,
            AtomicInteger producedCount,
            AtomicInteger mappedCount,
            AtomicInteger insertedCount,
            AtomicInteger lastProduced,
            AtomicInteger lastMapped,
            AtomicInteger lastInserted,
            Consumer<ProgressSnapshot> progressListener) {
        if (progressListener == null) {
            return;
        }

        long now = System.nanoTime();
        long deltaNanos = now - lastNanos.get();
        if (deltaNanos < TimeUnit.MILLISECONDS.toNanos(200)) {
            return;
        }
        lastNanos.set(now);
        double seconds = Math.max(0.001d, deltaNanos / 1_000_000_000.0d);

        int produced = producedCount.get();
        int mapped = mappedCount.get();
        int inserted = insertedCount.get();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(now - startNanos);

        long producedRate = Math.round((produced - lastProduced.getAndSet(produced)) / seconds);
        long mappedRate = Math.round((mapped - lastMapped.getAndSet(mapped)) / seconds);
        long insertedRate = Math.round((inserted - lastInserted.getAndSet(inserted)) / seconds);

        progressListener.accept(new ProgressSnapshot(
                0,
                0,
                produced,
                mapped,
                inserted,
                producedRate,
                mappedRate,
                insertedRate,
                elapsedMillis));
    }

    private static int normalizeBatchUpdateCount(int[][] updated, int fallbackCount) {
        if (updated.length == 0) {
            return fallbackCount;
        }
        return Arrays.stream(updated)
                .flatMapToInt(Arrays::stream)
                .map(value -> value > 0 ? value : 1)
                .sum();
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
        private final long rawChannelEnqueueWaitNanos;
        private final long mappedChannelEnqueueWaitNanos;
        private final long rawChannelEnqueuedCount;
        private final long mappedChannelEnqueuedCount;
        private final long elapsedMillis;

        public PipelineRunResult(
                int producedCount,
                int insertedCount,
                List<ValidationError> validationErrors,
                long rawChannelEnqueueWaitNanos,
                long mappedChannelEnqueueWaitNanos,
                long rawChannelEnqueuedCount,
                long mappedChannelEnqueuedCount,
                long elapsedMillis) {
            this.producedCount = producedCount;
            this.insertedCount = insertedCount;
            this.validationErrors = List.copyOf(validationErrors);
            this.rawChannelEnqueueWaitNanos = rawChannelEnqueueWaitNanos;
            this.mappedChannelEnqueueWaitNanos = mappedChannelEnqueueWaitNanos;
            this.rawChannelEnqueuedCount = rawChannelEnqueuedCount;
            this.mappedChannelEnqueuedCount = mappedChannelEnqueuedCount;
            this.elapsedMillis = elapsedMillis;
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

        public long getRawChannelEnqueueWaitNanos() {
            return rawChannelEnqueueWaitNanos;
        }

        public long getMappedChannelEnqueueWaitNanos() {
            return mappedChannelEnqueueWaitNanos;
        }

        public long getRawChannelEnqueuedCount() {
            return rawChannelEnqueuedCount;
        }

        public long getMappedChannelEnqueuedCount() {
            return mappedChannelEnqueuedCount;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }
    }

    public record ProgressSnapshot(
            int rawQueueSize,
            int mappedQueueSize,
            int producedCount,
            int mappedCount,
            int insertedCount,
            long producedRatePerSec,
            long mappedRatePerSec,
            long insertedRatePerSec,
            long elapsedMillis) {
    }
}
