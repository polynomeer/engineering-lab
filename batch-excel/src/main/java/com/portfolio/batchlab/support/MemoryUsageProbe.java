package com.portfolio.batchlab.support;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [NEW-DESIGN] career-hub design/batch-excel-optimization.md의 성능 목표(NF-1, NF-3)는
 * "총 처리시간"이 아니라 "힙 메모리 피크"다. equity-system-lab에서 "청크로 나누면
 * 무조건 총 처리시간이 빨라진다"는 가정이 실측으로 틀렸다는 걸 배운 뒤(README 참고),
 * 이 랩은 처음부터 시간이 아니라 MemoryMXBean 기반 힙 사용량을 직접 측정한다.
 *
 * 별도 스레드로 짧은 간격마다 heap used를 샘플링해 "관측된 최대값"을 추적한다 —
 * GC가 언제 도는지는 우리가 제어할 수 없으므로, 피크를 정확히 잡으려면 작업이
 * 끝난 뒤 한 번 재는 것으로는 부족하고(이미 GC로 회수된 뒤일 수 있음) 작업이
 * 진행되는 동안 계속 관찰해야 한다.
 *
 * 완벽한 측정 도구는 아니다 — JFR(Java Flight Recorder)이 더 정밀하지만, 이
 * 랩에서는 샘플링 주기를 짧게 두는 것으로 충분히 실용적인 근사치를 얻는다는
 * 판단이다. 측정 전 baseline을 잡을 때 System.gc()를 호출하는데, 이는 "즉시 전체
 * GC"를 보장하지 않는 힌트일 뿐이라는 점도 README에 정직하게 남긴다.
 *
 * [NEW-DESIGN] 실제로 처음에는 5ms마다 System.gc() 없이 heap used만 스냅샷했는데,
 * 그러면 "영속성 컨텍스트가 실제로 들고 있는 살아있는 객체(live set)"가 아니라
 * "아직 GC가 돌지 않아 잠깐 쌓인 가비지"까지 피크에 섞여 들어가, Tasklet 방식과
 * Chunk 방식의 차이가 실제보다 훨씬 작게(11%대) 측정됐다 — README의 "실행하며
 * 실제로 배운 것" 참고. 그래서 샘플마다 System.gc()를 먼저 호출해 강제로 정리를
 * 유도한 뒤 heap used를 읽는 방식으로 바꿨다 — 매 샘플이 사실상 "그 시점의 대략적인
 * live set"에 가까워지도록 한 것이다. 샘플 간격을 100ms로 늘린 이유도 System.gc()
 * 호출 자체의 오버헤드를 고려해서다.
 */
public final class MemoryUsageProbe {

    private static final MemoryMXBean MEMORY_MX_BEAN = ManagementFactory.getMemoryMXBean();
    private static final long SAMPLING_INTERVAL_MS = 100;

    private final AtomicLong peakUsedBytes = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread samplerThread;
    private long baselineUsedBytes;

    public void start() {
        // best-effort GC 힌트. 측정 시작 전 이전 시나리오가 남긴 쓰레기를 최대한 정리한다.
        System.gc();
        sleepQuietly(200);
        baselineUsedBytes = MEMORY_MX_BEAN.getHeapMemoryUsage().getUsed();
        peakUsedBytes.set(baselineUsedBytes);
        running.set(true);

        samplerThread = new Thread(() -> {
            while (running.get()) {
                System.gc(); // 힌트 — 가비지를 최대한 걷어내고 live set에 가까운 값을 읽기 위해
                long used = MEMORY_MX_BEAN.getHeapMemoryUsage().getUsed();
                peakUsedBytes.updateAndGet(prev -> Math.max(prev, used));
                sleepQuietly(SAMPLING_INTERVAL_MS);
            }
        }, "memory-usage-probe");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    public Result stop() {
        running.set(false);
        try {
            if (samplerThread != null) {
                samplerThread.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long peak = peakUsedBytes.get();
        return new Result(baselineUsedBytes, peak, peak - baselineUsedBytes);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Result(long baselineUsedBytes, long peakUsedBytes, long deltaBytes) {

        public double deltaMegabytes() {
            return deltaBytes / (1024.0 * 1024.0);
        }

        public double peakMegabytes() {
            return peakUsedBytes / (1024.0 * 1024.0);
        }

        @Override
        public String toString() {
            return String.format(
                    "baseline=%.1fMB, peak=%.1fMB, delta=%.1fMB",
                    baselineUsedBytes / (1024.0 * 1024.0), peakMegabytes(), deltaMegabytes());
        }
    }
}
