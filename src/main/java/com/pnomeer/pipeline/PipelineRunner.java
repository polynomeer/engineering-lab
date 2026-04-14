package com.pnomeer.pipeline;

import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;
import com.pnomeer.lab.experiments.pipeline.model.IngestItem;
import com.pnomeer.lab.experiments.pipeline.model.MappedRow;
import com.pnomeer.lab.experiments.pipeline.model.RawRow;
import com.pnomeer.lab.experiments.pipeline.model.ValidationError;
import com.pnomeer.lab.experiments.pipeline.parse.ExcelStreamingReader;
import com.pnomeer.lab.experiments.pipeline.queue.BoundedChannel;
import com.pnomeer.lab.experiments.pipeline.stage.Envelope;
import com.pnomeer.lab.experiments.pipeline.stage.ShutdownSignals;
import com.pnomeer.pipeline.config.PipelineProperties;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
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
import java.util.concurrent.atomic.LongAdder;
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

    public PipelineRunResult run(InputStream excelInputStream, Consumer<ExecutionMetricsSnapshot> progressListener)
            throws InterruptedException, IOException {
        return run(excelInputStream, RunMode.PIPELINE, progressListener);
    }

    public PipelineRunResult run(
            InputStream excelInputStream,
            RunMode runMode,
            Consumer<ExecutionMetricsSnapshot> progressListener)
            throws InterruptedException, IOException {
        if (runMode == RunMode.SINGLE_THREAD) {
            return runSingleThread(excelInputStream, progressListener);
        }
        return runPipelined(excelInputStream, progressListener);
    }

    private PipelineRunResult runPipelined(InputStream excelInputStream, Consumer<ExecutionMetricsSnapshot> progressListener)
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
        var batchCount = new AtomicInteger(0);
        var failedRowCount = new AtomicInteger(0);
        var retryCount = new AtomicInteger(0);
        var stageLatencyMetrics = new StageLatencyMetrics();
        var resourceMetricsTracker = new ResourceMetricsTracker(jdbcTemplate);

        ExecutorService validatorPool = Executors.newFixedThreadPool(validatorWorkers);
        ExecutorService inserterPool = Executors.newFixedThreadPool(inserterWorkers);
        ScheduledExecutorService metricsLogger = startMetricsLogger(
                rawChannel,
                mappedChannel,
                producedCount,
                mappedCount,
                insertedCount,
                batchCount,
                failedRowCount,
                retryCount,
                stageLatencyMetrics,
                resourceMetricsTracker,
                startNanos,
                progressListener);
        try {
            List<Future<?>> validatorFutures = startValidatorMapWorkers(
                    validatorPool,
                    validatorWorkers,
                    rawChannel,
                    mappedChannel,
                    validationErrors,
                    mappedCount,
                    failedRowCount,
                    stageLatencyMetrics);
            List<Future<?>> inserterFutures = startInsertWorkers(
                    inserterPool,
                    inserterWorkers,
                    mappedChannel,
                    insertedCount,
                    batchCount,
                    stageLatencyMetrics,
                    resourceMetricsTracker);

            produceFromExcel(excelInputStream, rawChannel, producedCount, stageLatencyMetrics);
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

    private PipelineRunResult runSingleThread(
            InputStream excelInputStream,
            Consumer<ExecutionMetricsSnapshot> progressListener)
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
        AtomicInteger batchCount = new AtomicInteger(0);
        AtomicInteger failedRowCount = new AtomicInteger(0);
        AtomicInteger retryCount = new AtomicInteger(0);
        StageLatencyMetrics stageLatencyMetrics = new StageLatencyMetrics();
        ResourceMetricsTracker resourceMetricsTracker = new ResourceMetricsTracker(jdbcTemplate);
        List<MappedRow> chunk = new ArrayList<>(chunkSize);

        AtomicLong lastNanos = new AtomicLong(startNanos);
        AtomicInteger lastProduced = new AtomicInteger(0);
        AtomicInteger lastMapped = new AtomicInteger(0);
        AtomicInteger lastInserted = new AtomicInteger(0);
        AtomicInteger lastBatches = new AtomicInteger(0);

        excelReader.readFirstSheet(excelInputStream, rawRow -> {
            producedCount.incrementAndGet();
            long parseStart = System.nanoTime();

            String col1 = cellAt(rawRow, 0);
            String col2 = cellAt(rawRow, 1);
            String col3 = cellAt(rawRow, 2);
            long validationStart = System.nanoTime();
            if (isBlank(col1)) {
                stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);
                validationErrors.add(new ValidationError(rawRow.getRowIndex(), "col1 is required", rawRow));
                failedRowCount.incrementAndGet();
                stageLatencyMetrics.addParseNanos(System.nanoTime() - parseStart);
                emitSingleThreadProgress(
                        startNanos,
                        lastNanos,
                        producedCount,
                        mappedCount,
                        insertedCount,
                        batchCount,
                        failedRowCount,
                        retryCount,
                        stageLatencyMetrics,
                        resourceMetricsTracker,
                        lastProduced,
                        lastMapped,
                        lastInserted,
                        lastBatches,
                        progressListener);
                return;
            }
            if (isBlank(col2)) {
                stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);
                validationErrors.add(new ValidationError(rawRow.getRowIndex(), "col2 is required", rawRow));
                failedRowCount.incrementAndGet();
                stageLatencyMetrics.addParseNanos(System.nanoTime() - parseStart);
                emitSingleThreadProgress(
                        startNanos,
                        lastNanos,
                        producedCount,
                        mappedCount,
                        insertedCount,
                        batchCount,
                        failedRowCount,
                        retryCount,
                        stageLatencyMetrics,
                        resourceMetricsTracker,
                        lastProduced,
                        lastMapped,
                        lastInserted,
                        lastBatches,
                        progressListener);
                return;
            }

            int col3Int;
            try {
                col3Int = Integer.parseInt(col3);
            } catch (NumberFormatException ex) {
                stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);
                validationErrors.add(new ValidationError(rawRow.getRowIndex(), "col3 must be integer", rawRow));
                failedRowCount.incrementAndGet();
                stageLatencyMetrics.addParseNanos(System.nanoTime() - parseStart);
                emitSingleThreadProgress(
                        startNanos,
                        lastNanos,
                        producedCount,
                        mappedCount,
                        insertedCount,
                        batchCount,
                        failedRowCount,
                        retryCount,
                        stageLatencyMetrics,
                        resourceMetricsTracker,
                        lastProduced,
                        lastMapped,
                        lastInserted,
                        lastBatches,
                        progressListener);
                return;
            }
            stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);

            long mappingStart = System.nanoTime();
            chunk.add(new MappedRow(rawRow.getRowIndex(), new IngestItem(col1, col2, col3Int)));
            mappedCount.incrementAndGet();
            stageLatencyMetrics.addMappingNanos(System.nanoTime() - mappingStart);
            if (chunk.size() >= chunkSize) {
                try {
                    flushChunk(chunk, insertedCount, batchCount, stageLatencyMetrics, resourceMetricsTracker);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while flushing chunk", ex);
                }
            }
            stageLatencyMetrics.addParseNanos(System.nanoTime() - parseStart);
            emitSingleThreadProgress(
                    startNanos,
                    lastNanos,
                    producedCount,
                    mappedCount,
                    insertedCount,
                    batchCount,
                    failedRowCount,
                    retryCount,
                    stageLatencyMetrics,
                    resourceMetricsTracker,
                    lastProduced,
                    lastMapped,
                    lastInserted,
                    lastBatches,
                    progressListener);
        });

        flushChunk(chunk, insertedCount, batchCount, stageLatencyMetrics, resourceMetricsTracker);
        emitSingleThreadProgress(
                startNanos,
                lastNanos,
                producedCount,
                mappedCount,
                insertedCount,
                batchCount,
                failedRowCount,
                retryCount,
                stageLatencyMetrics,
                resourceMetricsTracker,
                lastProduced,
                lastMapped,
                lastInserted,
                lastBatches,
                progressListener);

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
            AtomicInteger producedCount,
            StageLatencyMetrics stageLatencyMetrics)
            throws IOException, InterruptedException {
        excelReader.readFirstSheet(excelInputStream, rawRow -> {
            try {
                long parseStart = System.nanoTime();
                rawChannel.put(Envelope.data(rawRow));
                producedCount.incrementAndGet();
                stageLatencyMetrics.addParseNanos(System.nanoTime() - parseStart);
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
            AtomicInteger mappedCount,
            AtomicInteger failedRowCount,
            StageLatencyMetrics stageLatencyMetrics) {
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
                        long validationStart = System.nanoTime();
                        if (isBlank(col1)) {
                            stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);
                            validationErrors.add(new ValidationError(row.getRowIndex(), "col1 is required", row));
                            failedRowCount.incrementAndGet();
                            continue;
                        }
                        if (isBlank(col2)) {
                            stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);
                            validationErrors.add(new ValidationError(row.getRowIndex(), "col2 is required", row));
                            failedRowCount.incrementAndGet();
                            continue;
                        }

                        int col3Int;
                        try {
                            col3Int = Integer.parseInt(col3);
                        } catch (NumberFormatException ex) {
                            stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);
                            validationErrors.add(new ValidationError(row.getRowIndex(), "col3 must be integer", row));
                            failedRowCount.incrementAndGet();
                            continue;
                        }
                        stageLatencyMetrics.addValidationNanos(System.nanoTime() - validationStart);

                        long mappingStart = System.nanoTime();
                        IngestItem item = new IngestItem(col1, col2, col3Int);
                        mappedChannel.put(Envelope.data(new MappedRow(row.getRowIndex(), item)));
                        mappedCount.incrementAndGet();
                        stageLatencyMetrics.addMappingNanos(System.nanoTime() - mappingStart);
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
            AtomicInteger insertedCount,
            AtomicInteger batchCount,
            StageLatencyMetrics stageLatencyMetrics,
            ResourceMetricsTracker resourceMetricsTracker) {
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
                            flushChunk(chunk, insertedCount, batchCount, stageLatencyMetrics, resourceMetricsTracker);
                            return;
                        }
                        chunk.add(envelope.getPayload());
                        if (chunk.size() >= chunkSize) {
                            flushChunk(chunk, insertedCount, batchCount, stageLatencyMetrics, resourceMetricsTracker);
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        return futures;
    }

    private void flushChunk(
            List<MappedRow> chunk,
            AtomicInteger insertedCount,
            AtomicInteger batchCount,
            StageLatencyMetrics stageLatencyMetrics,
            ResourceMetricsTracker resourceMetricsTracker)
            throws InterruptedException {
        if (chunk.isEmpty()) {
            return;
        }
        long insertStart = System.nanoTime();
        long connectionAcquireStart = System.nanoTime();
        if (insertDelayMs > 0) {
            Thread.sleep(insertDelayMs);
        }

        Integer inserted = transactionTemplate.execute(status -> {
            long callbackStart = System.nanoTime();
            resourceMetricsTracker.recordConnectionWaitNanos(Math.max(0L, callbackStart - connectionAcquireStart));
            long dbBatchStart = System.nanoTime();
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
            resourceMetricsTracker.recordLockWaitEstimateNanos(System.nanoTime() - dbBatchStart);
            return normalizeBatchUpdateCount(updated, chunk.size());
        });
        insertedCount.addAndGet(inserted == null ? 0 : inserted);
        batchCount.incrementAndGet();
        stageLatencyMetrics.addInsertNanos(System.nanoTime() - insertStart);
        chunk.clear();
    }

    private ScheduledExecutorService startMetricsLogger(
            BoundedChannel<Envelope<RawRow>> rawChannel,
            BoundedChannel<Envelope<MappedRow>> mappedChannel,
            AtomicInteger producedCount,
            AtomicInteger mappedCount,
            AtomicInteger insertedCount,
            AtomicInteger batchCount,
            AtomicInteger failedRowCount,
            AtomicInteger retryCount,
            StageLatencyMetrics stageLatencyMetrics,
            ResourceMetricsTracker resourceMetricsTracker,
            long startNanos,
            Consumer<ExecutionMetricsSnapshot> progressListener) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicLong lastNanos = new AtomicLong(System.nanoTime());
        AtomicInteger lastProduced = new AtomicInteger(0);
        AtomicInteger lastMapped = new AtomicInteger(0);
        AtomicInteger lastInserted = new AtomicInteger(0);
        AtomicInteger lastBatches = new AtomicInteger(0);
        AtomicInteger lastRetries = new AtomicInteger(0);
        AtomicLong lastRawEnqueueWaitNanos = new AtomicLong(0L);
        AtomicLong lastMappedEnqueueWaitNanos = new AtomicLong(0L);
        AtomicLong peakProducedRatePerSec = new AtomicLong(0L);
        AtomicInteger stallEventCount = new AtomicInteger(0);
        AtomicInteger wasStalled = new AtomicInteger(0);
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            long deltaNanos = now - lastNanos.getAndSet(now);
            double seconds = Math.max(0.001d, deltaNanos / 1_000_000_000.0d);

            int produced = producedCount.get();
            int mapped = mappedCount.get();
            int inserted = insertedCount.get();
            int batches = batchCount.get();
            int failedRows = failedRowCount.get();
            int retries = retryCount.get();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(now - startNanos);
            int rawDepth = rawChannel.size();
            int mappedDepth = mappedChannel.size();
            double rawSaturationPct = percentage(rawDepth, rawChannel.capacity());
            double mappedSaturationPct = percentage(mappedDepth, mappedChannel.capacity());
            double rawEnqueueWaitMs = avgMillis(rawChannel.getCumulativeEnqueueWaitNanos(), rawChannel.getEnqueuedCount());
            double mappedEnqueueWaitMs = avgMillis(mappedChannel.getCumulativeEnqueueWaitNanos(), mappedChannel.getEnqueuedCount());
            double rawDequeueLatencyMs = avgMillis(rawChannel.getCumulativeDequeueWaitNanos(), rawChannel.getDequeuedCount());
            double mappedDequeueLatencyMs = avgMillis(mappedChannel.getCumulativeDequeueWaitNanos(), mappedChannel.getDequeuedCount());
            ResourceSnapshot resourceSnapshot = resourceMetricsTracker.sample();

            double producedRate = (produced - lastProduced.getAndSet(produced)) / seconds;
            double mappedRate = (mapped - lastMapped.getAndSet(mapped)) / seconds;
            double insertedRate = (inserted - lastInserted.getAndSet(inserted)) / seconds;
            double batchRate = (batches - lastBatches.getAndSet(batches)) / seconds;
            double retryRate = (retries - lastRetries.getAndSet(retries)) / seconds;
            double validationErrorRatePct = produced <= 0 ? 0.0d : (failedRows * 100.0d) / produced;
            long producedRateRounded = Math.round(producedRate);
            peakProducedRatePerSec.accumulateAndGet(producedRateRounded, Math::max);
            double producerSlowdownPct = peakProducedRatePerSec.get() <= 0L
                    ? 0.0d
                    : Math.max(0.0d, (1.0d - (producedRateRounded / (double) peakProducedRatePerSec.get())) * 100.0d);

            long currentRawEnqNanos = rawChannel.getCumulativeEnqueueWaitNanos();
            long currentMappedEnqNanos = mappedChannel.getCumulativeEnqueueWaitNanos();
            double enqueueBlockingTimeMs = (Math.max(0L, currentRawEnqNanos - lastRawEnqueueWaitNanos.getAndSet(currentRawEnqNanos))
                    + Math.max(0L, currentMappedEnqNanos - lastMappedEnqueueWaitNanos.getAndSet(currentMappedEnqNanos)))
                    / 1_000_000.0d;

            boolean stalled = (rawDepth > 0 || mappedDepth > 0) && producedRateRounded <= 0L && Math.round(insertedRate) <= 0L;
            if (stalled && wasStalled.getAndSet(1) == 0) {
                stallEventCount.incrementAndGet();
            }
            if (!stalled) {
                wasStalled.set(0);
            }
            double elapsedMinutes = Math.max(1.0d / 60.0d, elapsedMillis / 60000.0d);
            double stallFrequencyPerMin = stallEventCount.get() / elapsedMinutes;

            log.info(
                    "pipeline metrics rawQ={} mappedQ={} produced={} mapped={} inserted={} batches={} rate/s[rows={},records={},batch={}] errors[failedRows={},validationRate={}%%,retryRate={}s] latency[parse={}ms,validation={}ms,mapping={}ms,insert={}ms] queue[satRaw={}%%,satMapped={}%%,enqRaw={}ms,enqMapped={}ms,deqRaw={}ms,deqMapped={}ms] backpressure[block={}ms,slowdown={}%%,stall/min={}] resource[heap={}MB,peak={}MB,gcPause={}ms,dbActive={},dbAwaiting={},dbConnWait={}ms,dbLockWait={}ms]",
                    rawDepth,
                    mappedDepth,
                    produced,
                    mapped,
                    inserted,
                    batches,
                    Math.round(producedRate),
                    Math.round(insertedRate),
                    Math.round(batchRate),
                    failedRows,
                    Math.round(validationErrorRatePct),
                    Math.round(retryRate),
                    stageLatencyMetrics.avgParseMs(),
                    stageLatencyMetrics.avgValidationMs(),
                    stageLatencyMetrics.avgMappingMs(),
                    stageLatencyMetrics.avgInsertMs(),
                    Math.round(rawSaturationPct),
                    Math.round(mappedSaturationPct),
                    rawEnqueueWaitMs,
                    mappedEnqueueWaitMs,
                    rawDequeueLatencyMs,
                    mappedDequeueLatencyMs,
                    enqueueBlockingTimeMs,
                    producerSlowdownPct,
                    Math.round(stallFrequencyPerMin),
                    resourceSnapshot.heapUsedMb(),
                    resourceSnapshot.peakHeapMb(),
                    resourceSnapshot.gcPauseMs(),
                    resourceSnapshot.activeConnections(),
                    resourceSnapshot.awaitingConnections(),
                    resourceSnapshot.connectionWaitMs(),
                    resourceSnapshot.lockWaitMs());
            if (progressListener != null) {
                progressListener.accept(new ExecutionMetricsSnapshot(
                        rawDepth,
                        mappedDepth,
                        rawSaturationPct,
                        mappedSaturationPct,
                        rawEnqueueWaitMs,
                        mappedEnqueueWaitMs,
                        rawDequeueLatencyMs,
                        mappedDequeueLatencyMs,
                        enqueueBlockingTimeMs,
                        producerSlowdownPct,
                        stallFrequencyPerMin,
                        stallEventCount.get(),
                        resourceSnapshot.heapUsedMb(),
                        resourceSnapshot.heapMaxMb(),
                        resourceSnapshot.peakHeapMb(),
                        resourceSnapshot.gcPauseMs(),
                        resourceSnapshot.activeConnections(),
                        resourceSnapshot.awaitingConnections(),
                        resourceSnapshot.connectionWaitMs(),
                        resourceSnapshot.lockWaitMs(),
                        produced,
                        mapped,
                        inserted,
                        batches,
                        failedRows,
                        validationErrorRatePct,
                        retries,
                        Math.round(retryRate),
                        Math.round(producedRate),
                        Math.round(mappedRate),
                        Math.round(insertedRate),
                        Math.round(batchRate),
                        stageLatencyMetrics.avgParseMs(),
                        stageLatencyMetrics.avgValidationMs(),
                        stageLatencyMetrics.avgMappingMs(),
                        stageLatencyMetrics.avgInsertMs(),
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
            AtomicInteger batchCount,
            AtomicInteger failedRowCount,
            AtomicInteger retryCount,
            StageLatencyMetrics stageLatencyMetrics,
            ResourceMetricsTracker resourceMetricsTracker,
            AtomicInteger lastProduced,
            AtomicInteger lastMapped,
            AtomicInteger lastInserted,
            AtomicInteger lastBatches,
            Consumer<ExecutionMetricsSnapshot> progressListener) {
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
        int batches = batchCount.get();
        int failedRows = failedRowCount.get();
        int retries = retryCount.get();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(now - startNanos);

        long producedRate = Math.round((produced - lastProduced.getAndSet(produced)) / seconds);
        long mappedRate = Math.round((mapped - lastMapped.getAndSet(mapped)) / seconds);
        long insertedRate = Math.round((inserted - lastInserted.getAndSet(inserted)) / seconds);
        long batchRate = Math.round((batches - lastBatches.getAndSet(batches)) / seconds);
        double validationErrorRatePct = produced <= 0 ? 0.0d : (failedRows * 100.0d) / produced;

        progressListener.accept(new ExecutionMetricsSnapshot(
                0,
                0,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0,
                resourceMetricsTracker.heapUsedMb(),
                resourceMetricsTracker.heapMaxMb(),
                resourceMetricsTracker.peakHeapMb(),
                0.0d,
                resourceMetricsTracker.activeConnections(),
                resourceMetricsTracker.awaitingConnections(),
                resourceMetricsTracker.connectionWaitMs(),
                resourceMetricsTracker.lockWaitMs(),
                produced,
                mapped,
                inserted,
                batches,
                failedRows,
                validationErrorRatePct,
                retries,
                0L,
                producedRate,
                mappedRate,
                insertedRate,
                batchRate,
                stageLatencyMetrics.avgParseMs(),
                stageLatencyMetrics.avgValidationMs(),
                stageLatencyMetrics.avgMappingMs(),
                stageLatencyMetrics.avgInsertMs(),
                elapsedMillis));
    }

    private static double avgMillis(long cumulativeNanos, long count) {
        if (count <= 0L) {
            return 0.0d;
        }
        return (cumulativeNanos / 1_000_000.0d) / count;
    }

    private static double percentage(int value, int total) {
        if (total <= 0) {
            return 0.0d;
        }
        return (value * 100.0d) / total;
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

    private static final class ResourceMetricsTracker {
        private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        private final HikariPoolMXBean poolMxBean;
        private final AtomicLong peakHeapUsedBytes = new AtomicLong(0L);
        private final AtomicLong lastGcTimeMs = new AtomicLong(0L);
        private final LongAdder connectionWaitNanos = new LongAdder();
        private final LongAdder connectionWaitCount = new LongAdder();
        private final LongAdder lockWaitEstimateNanos = new LongAdder();
        private final LongAdder lockWaitEstimateCount = new LongAdder();

        private ResourceMetricsTracker(JdbcTemplate jdbcTemplate) {
            HikariPoolMXBean candidate = null;
            if (jdbcTemplate.getDataSource() instanceof HikariDataSource hikariDataSource) {
                candidate = hikariDataSource.getHikariPoolMXBean();
            }
            this.poolMxBean = candidate;
        }

        void recordConnectionWaitNanos(long nanos) {
            connectionWaitNanos.add(Math.max(0L, nanos));
            connectionWaitCount.increment();
        }

        void recordLockWaitEstimateNanos(long nanos) {
            lockWaitEstimateNanos.add(Math.max(0L, nanos));
            lockWaitEstimateCount.increment();
        }

        ResourceSnapshot sample() {
            long heapUsed = Math.max(0L, memoryMXBean.getHeapMemoryUsage().getUsed());
            long heapMax = Math.max(0L, memoryMXBean.getHeapMemoryUsage().getMax());
            peakHeapUsedBytes.accumulateAndGet(heapUsed, Math::max);
            long gcTimeMsNow = gcBeans.stream().mapToLong(gc -> Math.max(0L, gc.getCollectionTime())).sum();
            long gcPauseMs = Math.max(0L, gcTimeMsNow - lastGcTimeMs.getAndSet(gcTimeMsNow));

            int activeConnections = poolMxBean == null ? 0 : poolMxBean.getActiveConnections();
            int awaitingConnections = poolMxBean == null ? 0 : poolMxBean.getThreadsAwaitingConnection();

            return new ResourceSnapshot(
                    bytesToMb(heapUsed),
                    bytesToMb(heapMax),
                    bytesToMb(peakHeapUsedBytes.get()),
                    gcPauseMs,
                    activeConnections,
                    awaitingConnections,
                    averageMs(connectionWaitNanos, connectionWaitCount),
                    averageMs(lockWaitEstimateNanos, lockWaitEstimateCount));
        }

        double heapUsedMb() {
            return sample().heapUsedMb();
        }

        double heapMaxMb() {
            return sample().heapMaxMb();
        }

        double peakHeapMb() {
            return sample().peakHeapMb();
        }

        int activeConnections() {
            return sample().activeConnections();
        }

        int awaitingConnections() {
            return sample().awaitingConnections();
        }

        double connectionWaitMs() {
            return sample().connectionWaitMs();
        }

        double lockWaitMs() {
            return sample().lockWaitMs();
        }

        private static double bytesToMb(long bytes) {
            return bytes / (1024.0d * 1024.0d);
        }

        private static double averageMs(LongAdder totalNanos, LongAdder count) {
            long c = count.sum();
            if (c <= 0L) {
                return 0.0d;
            }
            return (totalNanos.sum() / 1_000_000.0d) / c;
        }
    }

    private record ResourceSnapshot(
            double heapUsedMb,
            double heapMaxMb,
            double peakHeapMb,
            double gcPauseMs,
            int activeConnections,
            int awaitingConnections,
            double connectionWaitMs,
            double lockWaitMs) {
    }

    private static final class StageLatencyMetrics {
        private final LongAdder parseNanos = new LongAdder();
        private final LongAdder parseCount = new LongAdder();
        private final LongAdder validationNanos = new LongAdder();
        private final LongAdder validationCount = new LongAdder();
        private final LongAdder mappingNanos = new LongAdder();
        private final LongAdder mappingCount = new LongAdder();
        private final LongAdder insertNanos = new LongAdder();
        private final LongAdder insertCount = new LongAdder();

        void addParseNanos(long nanos) {
            parseNanos.add(Math.max(0L, nanos));
            parseCount.increment();
        }

        void addValidationNanos(long nanos) {
            validationNanos.add(Math.max(0L, nanos));
            validationCount.increment();
        }

        void addMappingNanos(long nanos) {
            mappingNanos.add(Math.max(0L, nanos));
            mappingCount.increment();
        }

        void addInsertNanos(long nanos) {
            insertNanos.add(Math.max(0L, nanos));
            insertCount.increment();
        }

        double avgParseMs() {
            return avgMs(parseNanos, parseCount);
        }

        double avgValidationMs() {
            return avgMs(validationNanos, validationCount);
        }

        double avgMappingMs() {
            return avgMs(mappingNanos, mappingCount);
        }

        double avgInsertMs() {
            return avgMs(insertNanos, insertCount);
        }

        private static double avgMs(LongAdder nanos, LongAdder count) {
            long c = count.sum();
            if (c <= 0) {
                return 0.0d;
            }
            return (nanos.sum() / 1_000_000.0d) / c;
        }
    }
}
