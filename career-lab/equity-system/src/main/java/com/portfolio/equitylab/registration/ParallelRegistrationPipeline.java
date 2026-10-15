package com.portfolio.equitylab.registration;

import com.portfolio.equitylab.registration.RowProcessor.MappedEquityRow;
import com.portfolio.equitylab.repository.EquityShareInsertDao;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;

/**
 * [FACT 기반 재현] "After" — facts/projects/equity-system.md 3번 항목의
 * Decision: "처리 흐름을 파싱–검증–매핑–삽입 단계로 분리한 파이프라인 구조.
 * DB 삽입은 청크 단위로 묶고 ThreadPool 기반 병렬 처리 + Backpressure 제어
 * 적용".
 *
 * ThreadPool 크기(8)와 동시 처리 청크 수 상한(Backpressure, 8)은
 * [NEW-DESIGN] — 실제 FLO 값이 아니라 이 랩을 위해 새로 정한 값이다.
 */
@Service
public class ParallelRegistrationPipeline {

    /** [NEW-DESIGN] 파싱·검증·매핑을 담당하는 워커 스레드 수. */
    static final int POOL_SIZE = 8;

    /** [NEW-DESIGN] 동시에 진행 가능한 청크 수 상한 — 이 이상은 제출을 막아 메모리·DB 과부하를 방지한다. */
    static final int MAX_IN_FLIGHT_CHUNKS = 8;

    private final RowProcessor rowProcessor;
    private final EquityShareInsertDao insertDao;

    public ParallelRegistrationPipeline(RowProcessor rowProcessor, EquityShareInsertDao insertDao) {
        this.rowProcessor = rowProcessor;
        this.insertDao = insertDao;
    }

    public record Result(int insertedRows, int maxInFlightChunks, int backpressureWaits, long backpressureNanos) {}

    public int register(List<RawEquityRow> rawRows, int chunkSize) {
        return registerMeasured(rawRows, chunkSize, POOL_SIZE, MAX_IN_FLIGHT_CHUNKS).insertedRows();
    }

    /** Bounded submission window: both pending work and retained futures are O(maxInFlight). */
    public Result registerMeasured(List<RawEquityRow> rows, int chunkSize, int workers, int maxInFlight) {
        if (chunkSize < 1 || chunkSize > 5000 || workers < 1 || workers > 16 || maxInFlight < 1 || maxInFlight > 32)
            throw new IllegalArgumentException("Invalid registration bounds");
        var pending = new java.util.HashSet<Future<Integer>>();
        int total = 0, peak = 0, waits = 0;
        long waitNanos = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            var completed = new java.util.concurrent.ExecutorCompletionService<Integer>(pool);
            try {
                int offset = 0;
                while (offset < rows.size() || !pending.isEmpty()) {
                    if (offset < rows.size() && pending.size() < maxInFlight) {
                        int end = Math.min(rows.size(), offset + chunkSize);
                        var chunk = rows.subList(offset, end);
                        pending.add(completed.submit(() -> processAndInsertChunk(chunk)));
                        peak = Math.max(peak, pending.size()); offset = end;
                    } else {
                        Future<Integer> done = completed.poll();
                        if (done == null) {
                            long start = System.nanoTime();
                            done = completed.take();
                            // Count only producer admission waits, not the final drain.
                            if (offset < rows.size()) { waits++; waitNanos += System.nanoTime() - start; }
                        }
                        pending.remove(done); total += done.get();
                    }
                }
            } catch (InterruptedException e) {
                pending.forEach(f -> f.cancel(true)); pool.shutdownNow();
                Thread.currentThread().interrupt(); throw new IllegalStateException("Registration interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                pending.forEach(f -> f.cancel(true)); pool.shutdownNow();
                throw new IllegalStateException("Registration chunk failed", e.getCause());
            }
        }
        return new Result(total, peak, waits, waitNanos);
    }

    private int processAndInsertChunk(List<RawEquityRow> chunk) {
        List<MappedEquityRow> mapped = new ArrayList<>(chunk.size());
        for (RawEquityRow raw : chunk) mapped.add(rowProcessor.process(raw));
        insertDao.insertBatch(mapped);
        return mapped.size();
    }
}
