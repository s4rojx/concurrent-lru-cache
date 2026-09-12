package cache;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvictionPolicyTest {

    @Test
    void lruEvictsLeastRecentlyUsed() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(3, Duration.ofMinutes(1), 1, PolicyType.LRU)) {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("c", "3");

            cache.get("a");
            cache.put("d", "4");

            assertTrue(cache.get("a").isPresent(), "a must survive (MRU)");
            assertFalse(cache.get("b").isPresent(), "b must be evicted (LRU)");
            assertTrue(cache.get("c").isPresent(), "c must survive");
            assertTrue(cache.get("d").isPresent(), "d must survive");
        }
    }

    @Test
    void lruUpdateRefreshesRecency() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1, PolicyType.LRU)) {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("a", "updated");
            cache.put("c", "3");

            assertTrue(cache.get("a").isPresent());
            assertFalse(cache.get("b").isPresent());
            assertTrue(cache.get("c").isPresent());
        }
    }

    @Test
    void lfuEvictsLeastFrequentlyUsed() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(3, Duration.ofMinutes(1), 1, PolicyType.LFU)) {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("c", "3");

            cache.get("a");
            cache.get("a");
            cache.get("b");
            cache.put("d", "4");

            assertTrue(cache.get("a").isPresent(), "a (freq=3) must survive");
            assertTrue(cache.get("b").isPresent(), "b (freq=2) must survive");
            assertFalse(cache.get("c").isPresent(), "c (freq=1) must be evicted");
            assertTrue(cache.get("d").isPresent(), "d must be present");
        }
    }

    @Test
    void lfuTieBrokenByInsertionOrder() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1, PolicyType.LFU)) {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("c", "3");

            assertFalse(cache.get("a").isPresent(), "a (min-freq, oldest) must be evicted");
            assertTrue(cache.get("b").isPresent());
            assertTrue(cache.get("c").isPresent());
        }
    }

    @Test
    void lfuFrequencyTracksAcrossUpdates() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1, PolicyType.LFU)) {
            cache.put("a", "1");
            cache.get("a");
            cache.put("b", "2");
            cache.get("b");
            cache.get("b");
            cache.put("a", "updated");
            cache.put("c", "3");

            assertTrue(cache.get("a").isPresent(), "a (freq=3) must survive");
            assertTrue(cache.get("b").isPresent(), "b (freq=3) must survive");
            assertFalse(cache.get("c").isPresent(), "c (freq=1, just inserted) must be evicted");
        }
    }

    @Test
    void windowTinyLfuRejectsOneHitWonders() {
        int capacity = 10;
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(
                        capacity, Duration.ofMinutes(1), 1, PolicyType.WINDOW_TINY_LFU)) {

            for (int i = 0; i < 9; i++) {
                cache.put(i, "val-" + i);
            }
            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 9; i++) {
                    cache.get(i);
                }
            }

            cache.put(99, "one-hit");
            cache.put(100, "another");

            int hotSurvivors = 0;
            for (int i = 0; i < 9; i++) {
                if (cache.get(i).isPresent()) hotSurvivors++;
            }
            assertTrue(
                    hotSurvivors >= 7,
                    "At least 7 of 9 frequently-accessed items must survive; was " + hotSurvivors);
        }
    }

    @Test
    void windowTinyLfuCapacityIsNeverExceeded() {
        int capacity = 20;
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(
                        capacity, Duration.ofMinutes(1), 1, PolicyType.WINDOW_TINY_LFU)) {
            for (int i = 0; i < 200; i++) {
                cache.put(i % 50, "v-" + i);
                cache.get(i % 50);
            }
            assertTrue(
                    cache.size() <= capacity, "size must not exceed capacity; was " + cache.size());
        }
    }

    @Test
    void hitRateComparisonZipfianWorkload() {
        int capacity = 10_000;
        int keySpace = 100_000;
        int warmup = 50_000;
        int measured = 200_000;
        TestDistribution zipf = new TestDistribution(keySpace, true);

        double lruRate = measureHitRate(PolicyType.LRU, capacity, keySpace, warmup, measured, zipf);
        double lfuRate = measureHitRate(PolicyType.LFU, capacity, keySpace, warmup, measured, zipf);
        double wtlfuRate =
                measureHitRate(
                        PolicyType.WINDOW_TINY_LFU, capacity, keySpace, warmup, measured, zipf);

        assertTrue(lruRate > 0.30, "LRU hit rate on Zipfian must be > 30%; was " + lruRate);
        assertTrue(lfuRate > 0.30, "LFU hit rate on Zipfian must be > 30%; was " + lfuRate);
        assertTrue(
                wtlfuRate > 0.30,
                "Window-TinyLFU hit rate on Zipfian must be > 30%; was " + wtlfuRate);
    }

    @Test
    void hitRateComparisonUniformWorkload() {
        int capacity = 100;
        int keySpace = 1000;
        int warmup = 5_000;
        int measured = 45_000;
        TestDistribution uniform = new TestDistribution(keySpace, false);

        double lruRate =
                measureHitRate(PolicyType.LRU, capacity, keySpace, warmup, measured, uniform);
        double lfuRate =
                measureHitRate(PolicyType.LFU, capacity, keySpace, warmup, measured, uniform);
        double wtlfuRate =
                measureHitRate(
                        PolicyType.WINDOW_TINY_LFU, capacity, keySpace, warmup, measured, uniform);

        double expected = (double) capacity / keySpace;
        double tolerance = expected * 0.5;
        assertTrue(
                lruRate > expected - tolerance,
                "LRU hit rate must be close to theoretical; was " + lruRate);
    }

    @Test
    void hitRateComparisonDistributionShift() {
        int capacity = 10_000;
        int hotSetSize = 10_000;
        int keySpace = 100_000;
        int warmupOps = 50_000;
        int phase1Ops = 100_000;
        int phase2Ops = 100_000;
        int oldHotOffset = 0;
        int newHotOffset = 50_000;

        TestDistribution phase1Dist = new TestDistribution(hotSetSize, true);
        TestDistribution phase2Dist = new TestDistribution(hotSetSize, true);

        String[] keys = new String[keySpace];
        for (int i = 0; i < keySpace; i++) {
            keys[i] = "k" + i;
        }

        double lruRate =
                measureShiftHitRate(
                        PolicyType.LRU,
                        capacity,
                        keys,
                        phase1Dist,
                        oldHotOffset,
                        phase2Dist,
                        newHotOffset,
                        warmupOps,
                        phase1Ops,
                        phase2Ops);
        double lfuRate =
                measureShiftHitRate(
                        PolicyType.LFU,
                        capacity,
                        keys,
                        phase1Dist,
                        oldHotOffset,
                        phase2Dist,
                        newHotOffset,
                        warmupOps,
                        phase1Ops,
                        phase2Ops);
        double wtlfuRate =
                measureShiftHitRate(
                        PolicyType.WINDOW_TINY_LFU,
                        capacity,
                        keys,
                        phase1Dist,
                        oldHotOffset,
                        phase2Dist,
                        newHotOffset,
                        warmupOps,
                        phase1Ops,
                        phase2Ops);

        assertTrue(lruRate > 0.05, "LRU must achieve > 5% hit rate post-shift; was " + lruRate);
        assertTrue(
                wtlfuRate > 0.05,
                "Window-TinyLFU must achieve > 5% hit rate post-shift; was " + wtlfuRate);
        assertTrue(
                wtlfuRate >= lfuRate * 0.80,
                "Window-TinyLFU post-shift hit rate must be >= 80% of LFU's; LFU="
                        + lfuRate
                        + " WTLFU="
                        + wtlfuRate);
    }

    private double measureShiftHitRate(
            PolicyType policy,
            int capacity,
            String[] keys,
            TestDistribution phase1Dist,
            int phase1Offset,
            TestDistribution phase2Dist,
            int phase2Offset,
            int warmupOps,
            int phase1Ops,
            int phase2Ops) {

        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(capacity, Duration.ofMinutes(10), 1, policy)) {

            for (int i = 0; i < warmupOps; i++) {
                String k = keys[phase1Offset + phase1Dist.nextKey()];
                if (cache.get(k).isEmpty()) {
                    cache.put(k, "v");
                }
            }

            for (int i = 0; i < phase1Ops; i++) {
                String k = keys[phase1Offset + phase1Dist.nextKey()];
                if (cache.get(k).isEmpty()) {
                    cache.put(k, "v");
                }
            }

            long hits = 0;
            for (int i = 0; i < phase2Ops; i++) {
                String k = keys[phase2Offset + phase2Dist.nextKey()];
                Optional<String> result = cache.get(k);
                if (result.isPresent()) {
                    hits++;
                } else {
                    cache.put(k, "v");
                }
            }
            return (double) hits / phase2Ops;
        }
    }

    private double measureHitRate(
            PolicyType policy,
            int capacity,
            int keySpace,
            int warmup,
            int measured,
            TestDistribution dist) {

        String[] keys = new String[keySpace];
        for (int i = 0; i < keySpace; i++) {
            keys[i] = "k" + i;
        }

        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(capacity, Duration.ofMinutes(10), 1, policy)) {

            for (int i = 0; i < warmup; i++) {
                String k = keys[dist.nextKey()];
                if (cache.get(k).isEmpty()) {
                    cache.put(k, "v");
                }
            }
        }
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(capacity, Duration.ofMinutes(10), 1, policy)) {

            for (int i = 0; i < Math.min(capacity / 2, keySpace); i++) {
                cache.put(keys[i], "v");
            }

            long hits = 0;
            for (int i = 0; i < measured; i++) {
                String k = keys[dist.nextKey()];
                Optional<String> result = cache.get(k);
                if (result.isPresent()) {
                    hits++;
                } else {
                    cache.put(k, "v");
                }
            }
            return (double) hits / measured;
        }
    }

    @Test
    void lfuRespectsSizeUnderConcurrency() throws Exception {
        stressConcurrency(PolicyType.LFU, 100, 16, 1_000);
    }

    @Test
    void windowTinyLfuRespectsSizeUnderConcurrency() throws Exception {
        stressConcurrency(PolicyType.WINDOW_TINY_LFU, 100, 16, 1_000);
    }

    @Test
    void lruRespectsSizeUnderConcurrency() throws Exception {
        stressConcurrency(PolicyType.LRU, 100, 16, 1_000);
    }

    @Test
    void lfuNoExceptionsUnderMixedConcurrency() throws Exception {
        stressMixedConcurrency(PolicyType.LFU, 200, 12, 2_000);
    }

    @Test
    void windowTinyLfuNoExceptionsUnderMixedConcurrency() throws Exception {
        stressMixedConcurrency(PolicyType.WINDOW_TINY_LFU, 200, 12, 2_000);
    }

    private void stressConcurrency(PolicyType policy, int capacity, int threads, int opsPerThread)
            throws Exception {
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(capacity, PolicyType.valueOf(policy.name()))) {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Callable<Boolean>> tasks = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                tasks.add(
                        () -> {
                            barrier.await();
                            for (int i = 0; i < opsPerThread; i++) {
                                int key = (threadId * 37 + i) % (capacity * 3);
                                if (i % 3 == 0) {
                                    cache.put(key, "v-" + key);
                                } else {
                                    cache.get(key);
                                }
                            }
                            return true;
                        });
            }

            List<Future<Boolean>> results = executor.invokeAll(tasks);
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (Future<Boolean> f : results) {
                assertTrue(f.get());
            }

            assertTrue(
                    cache.size() <= capacity,
                    policy + ": size must not exceed capacity; was " + cache.size());
        }
    }

    private void stressMixedConcurrency(
            PolicyType policy, int capacity, int threads, int opsPerThread) throws Exception {
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(capacity, PolicyType.valueOf(policy.name()))) {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicInteger exceptionCount = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await();
                                        for (int op = 0; op < opsPerThread; op++) {
                                            int key = (threadId * 31 + op) % (capacity * 2);
                                            int choice = op % 10;
                                            if (choice < 5) {
                                                cache.get(key);
                                            } else if (choice < 8) {
                                                cache.put(key, "v" + key);
                                            } else {
                                                cache.remove(key);
                                            }
                                        }
                                    } catch (Exception e) {
                                        exceptionCount.incrementAndGet();
                                    }
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (Future<?> f : futures) {
                f.get();
            }

            assertEquals(0, exceptionCount.get(), policy + ": no exceptions expected");
            assertTrue(cache.size() <= capacity, policy + ": size must not exceed capacity");
        }
    }

    private static final class TestDistribution {
        private final int keySpaceSize;
        private final int[] zipfTable;
        private final boolean zipfian;

        TestDistribution(int keySpaceSize, boolean zipfian) {
            this.keySpaceSize = keySpaceSize;
            this.zipfian = zipfian;

            if (zipfian) {
                double exponent = 1.0;
                double[] weights = new double[keySpaceSize];
                double total = 0.0;
                for (int i = 0; i < keySpaceSize; i++) {
                    weights[i] = 1.0 / Math.pow(i + 1, exponent);
                    total += weights[i];
                }
                int lookupSize = 1 << 16;
                zipfTable = new int[lookupSize];
                double cumulative = 0.0;
                int tablePos = 0;
                for (int i = 0; i < keySpaceSize; i++) {
                    cumulative += weights[i] / total;
                    int upTo = (int) (cumulative * lookupSize);
                    while (tablePos < upTo && tablePos < lookupSize) {
                        zipfTable[tablePos++] = i;
                    }
                }
                while (tablePos < lookupSize) {
                    zipfTable[tablePos++] = keySpaceSize - 1;
                }
            } else {
                zipfTable = null;
            }
        }

        int nextKey() {
            if (zipfian) {
                int bucket =
                        java.util.concurrent.ThreadLocalRandom.current().nextInt(zipfTable.length);
                return zipfTable[bucket];
            } else {
                return java.util.concurrent.ThreadLocalRandom.current().nextInt(keySpaceSize);
            }
        }
    }
}
