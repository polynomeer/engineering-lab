package com.portfolio.mcplab.sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * [NEW-DESIGN] 세 가지 {@link ContractCodeAllocator} 구현을 같은 방식으로 동시 실행해
 * 비교하기 위한 테스트 전용 유틸리티. CLAUDE.md 지침("진짜 동시 요청 자체를 검증해야
 * 하는 경우는 실제 멀티스레드 테스트가 필요")에 따라 {@link ExecutorService} +
 * {@link CountDownLatch}로 여러 스레드를 동시에 출발시켜 실제 DB에 동시 요청을 보낸다.
 */
public final class ConcurrentAllocationHarness {

    private ConcurrentAllocationHarness() {
    }

    /**
     * threadCount개의 스레드가 동시에 {@code allocator.allocateBlock(seqKey, blockSize)}를
     * 한 번씩 호출한다. 모든 스레드는 {@link CountDownLatch} startGate로 동시에 출발한다.
     */
    public static List<AllocatedRange> runConcurrently(ContractCodeAllocator allocator,
                                                         String seqKey,
                                                         int blockSize,
                                                         int threadCount) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<AllocatedRange>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                startGate.await();
                return allocator.allocateBlock(seqKey, blockSize);
            });
        }

        try {
            // invokeAll이 태스크를 제출한 뒤 곧바로 게이트를 열어, 모든 스레드가 거의 동시에
            // allocateBlock을 호출하도록 만든다.
            List<Future<AllocatedRange>> futures = new ArrayList<>();
            for (Callable<AllocatedRange> task : tasks) {
                futures.add(pool.submit(task));
            }
            startGate.countDown();

            List<AllocatedRange> results = new ArrayList<>();
            for (Future<AllocatedRange> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException("동시 채번 호출 중 실패", e);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 결과 목록에서 서로 겹치는 구간 쌍을 모두 찾는다. */
    public static List<String> findOverlaps(List<AllocatedRange> ranges) {
        List<String> overlaps = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                AllocatedRange a = ranges.get(i);
                AllocatedRange b = ranges.get(j);
                if (a.overlaps(b)) {
                    overlaps.add("[%d,%d] overlaps [%d,%d]".formatted(
                            a.rangeStart(), a.rangeEnd(), b.rangeStart(), b.rangeEnd()));
                }
            }
        }
        return overlaps;
    }
}
