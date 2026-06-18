package benchmark;

import cache.ConcurrentLRUCache;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CacheBenchmark {
    private static final int OPERATIONS_PER_THREAD = 5_000;
    private static final int KEY_SPACE = 10_000;

    private CacheBenchmark() {}

    public static void main(String[] args) throws Exception {
        int[] threadCounts = {1, 10, 50, 100, 200};
        List<BenchmarkResult> results = new ArrayList<>();
        for (int threadCount : threadCounts) {
            BenchmarkResult result = run(threadCount);
            results.add(result);
            System.out.println(BenchmarkReportGenerator.render(result));
        }
        writeLatest(results.get(results.size() - 1));
    }

    static BenchmarkResult run(int threadCount) throws InterruptedException {
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(KEY_SPACE, Duration.ofSeconds(30))) {
            for (int i = 0; i < KEY_SPACE / 2; i++) {
                cache.put(i, "value-" + i);
            }

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicLong latencyNanos = new AtomicLong();
            long totalOperations = (long) threadCount * OPERATIONS_PER_THREAD;
            long memoryBefore = usedMemory();
            long startTime = System.nanoTime();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(
                        () -> {
                            await(start);
                            ThreadLocalRandom random = ThreadLocalRandom.current();
                            for (int op = 0; op < OPERATIONS_PER_THREAD; op++) {
                                int key = random.nextInt(KEY_SPACE);
                                long operationStart = System.nanoTime();
                                if (random.nextInt(100) < 70) {
                                    cache.get(key);
                                } else {
                                    cache.put(key, "value-" + key);
                                }
                                latencyNanos.addAndGet(System.nanoTime() - operationStart);
                            }
                        });
            }

            start.countDown();
            executor.shutdown();
            boolean finished = executor.awaitTermination(2, TimeUnit.MINUTES);
            if (!finished) {
                executor.shutdownNow();
                throw new IllegalStateException("benchmark timed out");
            }

            long elapsedNanos = System.nanoTime() - startTime;
            long memoryAfter = usedMemory();
            long throughput = Math.round(totalOperations / (elapsedNanos / 1_000_000_000.0));
            double averageLatencyMillis = latencyNanos.get() / (double) totalOperations / 1_000_000.0;
            double memoryMegabytes = Math.max(0, memoryAfter - memoryBefore) / 1024.0 / 1024.0;
            return new BenchmarkResult(
                    threadCount,
                    totalOperations,
                    throughput,
                    averageLatencyMillis,
                    cache.getHitRate(),
                    memoryMegabytes);
        }
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void writeLatest(BenchmarkResult result) throws IOException {
        BenchmarkReportGenerator.write(Path.of("docs", "benchmark-results.md"), result);
    }
}
