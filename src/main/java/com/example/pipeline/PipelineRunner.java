package com.example.pipeline;

import com.example.pipeline.model.IngestItem;
import com.example.pipeline.model.MappedRow;
import com.example.pipeline.model.RawRow;
import com.example.pipeline.model.ValidationError;
import com.example.pipeline.parse.ExcelStreamingReader;
import com.example.pipeline.queue.BoundedChannel;
import com.example.pipeline.stage.Envelope;
import com.example.pipeline.stage.ShutdownSignals;
import com.polynomeer.excelpipeline.config.PipelineProperties;
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

public class PipelineRunner {
    private static final String INSERT_SQL = "insert into ingest_item(col1, col2, col3) values (?, ?, ?)";

    private final PipelineProperties properties;
    private final ExcelStreamingReader excelReader;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PipelineRunner(
            PipelineProperties properties,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this(properties, new ExcelStreamingReader(), jdbcTemplate, transactionTemplate);
    }

    public PipelineRunner(
            PipelineProperties properties,
            ExcelStreamingReader excelReader,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.excelReader = excelReader;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public PipelineRunResult run(InputStream excelInputStream) throws InterruptedException, IOException {
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
            List<Future<?>> inserterFutures = startInsertWorkers(inserterPool, inserterWorkers, mappedChannel, insertedCount);

            int producedCount = produceFromExcel(excelInputStream, rawChannel);
            ShutdownSignals.publishEndSignals(rawChannel, validatorWorkers);

            waitForWorkers(validatorFutures);
            ShutdownSignals.publishEndSignals(mappedChannel, inserterWorkers);
            waitForWorkers(inserterFutures);

            return new PipelineRunResult(producedCount, insertedCount.get(), new ArrayList<>(validationErrors));
        } finally {
            validatorPool.shutdownNow();
            inserterPool.shutdownNow();
        }
    }

    private int produceFromExcel(InputStream excelInputStream, BoundedChannel<Envelope<RawRow>> rawChannel)
            throws IOException, InterruptedException {
        return excelReader.readFirstSheet(excelInputStream, rawRow -> {
            try {
                rawChannel.put(Envelope.data(rawRow));
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

    private void flushChunk(List<MappedRow> chunk, AtomicInteger insertedCount) {
        if (chunk.isEmpty()) {
            return;
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
