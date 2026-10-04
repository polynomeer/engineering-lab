package com.portfolio.equitylab.registration;

import com.portfolio.equitylab.registration.RowProcessor.MappedEquityRow;
import com.portfolio.equitylab.repository.EquityShareInsertDao;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
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

    public int register(List<RawEquityRow> rawRows, int chunkSize) {
        List<List<RawEquityRow>> chunks = partition(rawRows, chunkSize);
        Semaphore backpressure = new Semaphore(MAX_IN_FLIGHT_CHUNKS);

        try (ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE)) {
            List<Future<Integer>> futures = new ArrayList<>(chunks.size());
            for (List<RawEquityRow> chunk : chunks) {
                acquireUninterruptibly(backpressure);
                futures.add(pool.submit(() -> {
                    try {
                        return processAndInsertChunk(chunk);
                    } finally {
                        backpressure.release();
                    }
                }));
            }

            int total = 0;
            for (Future<Integer> future : futures) {
                total += awaitResult(future);
            }
            return total;
        }
    }

    private int processAndInsertChunk(List<RawEquityRow> chunk) {
        List<MappedEquityRow> mapped = new ArrayList<>(chunk.size());
        for (RawEquityRow raw : chunk) {
            mapped.add(rowProcessor.process(raw));
        }
        insertDao.insertBatch(mapped);
        return mapped.size();
    }

    private static List<List<RawEquityRow>> partition(List<RawEquityRow> rows, int chunkSize) {
        List<List<RawEquityRow>> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += chunkSize) {
            chunks.add(rows.subList(i, Math.min(i + chunkSize, rows.size())));
        }
        return chunks;
    }

    private static void acquireUninterruptibly(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("backpressure 대기 중 인터럽트됨", e);
        }
    }

    private static int awaitResult(Future<Integer> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("청크 처리 대기 중 인터럽트됨", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("청크 처리 중 오류", e.getCause());
        }
    }
}
