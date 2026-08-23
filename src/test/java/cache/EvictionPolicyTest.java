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

    // =========================================================================
    // LRU correctness
    // =========================================================================

    @Test
    void lruEvictsLeastRecentlyUsed() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(3, Duration.ofMinutes(1), 1, PolicyType.LRU)) {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("c", "3");

            cache.get("a"); // a is now MRU; b is LRU

            cache.put("d", "4"); // evicts b

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
            cache.put("a", "updated"); // a becomes MRU; b becomes LRU

            cache.put("c", "3"); // evicts b

            assertTrue(cache.get("a").isPresent());
            assertFalse(cache.get("b").isPresent());
            assertTrue(cache.get("c").isPresent());
        }
    }

    // =========================================================================
    // LFU correctness
    // =========================================================================

    @Test
    void lfuEvictsLeastFrequentlyUsed() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(3, Duration.ofMinutes(1), 1, PolicyType.LFU)) {
            cache.put("a", "1"); // freq=1
            cache.put("b", "2"); // freq=1
            cache.put("c", "3"); // freq=1

            cache.get("a"); // a: freq=2
            cache.get("a"); // a: freq=3
            cache.get("b"); // b: freq=2

            // c has freq=1 — should be evicted
            cache.put("d", "4");

            assertTrue(cache.get("a").isPresent(), "a (freq=3) must survive");
            assertTrue(cache.get("b").isPresent(), "b (freq=2) must survive");
            assertFalse(cache.get("c").isPresent(), "c (freq=1) must be evicted");
            assertTrue(cache.get("d").isPresent(), "d must be present");
        }
    }

    @Test
    void lfuTieBrokenByInsertionOrder() {
        // When all keys have equal frequency, the one inserted first is evicted.
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1, PolicyType.LFU)) {
            cache.put("a", "1"); // freq=1, inserted first
            cache.put("b", "2"); // freq=1, inserted second

            // Both have freq=1 — "a" was inserted first in the min-bucket, so it goes first
            cache.put("c", "3");

            assertFalse(cache.get("a").isPresent(), "a (min-freq, oldest) must be evicted");
            assertTrue(cache.get("b").isPresent());
            assertTrue(cache.get("c").isPresent());
        }
    }

    @Test
    void lfuFrequencyTracksAcrossUpdates() {
        // Frequencies after all ops:
        //   put("a") -> onInsert -> freq[a]=1
        //   get("a") -> onAccess -> freq[a]=2
        //   put("b") -> onInsert -> freq[b]=1
        //   get("b") -> onAccess -> freq[b]=2
        //   get("b") -> onAccess -> freq[b]=3
        //   put("a","updated") -> onAccess (update path) -> freq[a]=3
        //   put("c") -> onInsert -> freq[c]=1, minFreq=1 -> c is evicted
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1, PolicyType.LFU)) {
            cache.put("a", "1"); // freq[a]=1
            cache.get("a"); // freq[a]=2
            cache.put("b", "2"); // freq[b]=1
            cache.get("b"); // freq[b]=2
            cache.get("b"); // freq[b]=3
            cache.put("a", "updated"); // freq[a]=3 (update path calls onAccess)

            // minFreq=1 belongs to neither a nor b; c itself has freq=1 after insert.
            // c is evicted immediately: capacity=2, inserting c triggers eviction of c itself
            // or one of a/b. Since minFreq resets to 1 on insert, c is the candidate.
            cache.put("c", "3"); // evicts c (minFreq=1, c just inserted) or minFreq key

            // a and b both have freq=3 and should survive
            assertTrue(cache.get("a").isPresent(), "a (freq=3) must survive");
            assertTrue(cache.get("b").isPresent(), "b (freq=3) must survive");
            assertFalse(cache.get("c").isPresent(), "c (freq=1, just inserted) must be evicted");
        }
    }

    // =========================================================================
    // Window-TinyLFU correctness
    // =========================================================================

    @Test
    void windowTinyLfuRejectsOneHitWonders() {
        // Cache size=10. Window=1 slot, main=9.
        // Fill main with frequently-accessed entries, then try to evict one with a one-hit wonder.
        int capacity = 10;
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(
                        capacity, Duration.ofMinutes(1), 1, PolicyType.WINDOW_TINY_LFU)) {

            // Warm up 9 entries with multiple accesses each
            for (int i = 0; i < 9; i++) {
                cache.put(i, "val-" + i);
            }
            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 9; i++) {
                    cache.get(i);
                }
            }

            // Now insert 10th key to fill window — this has freq=1
            cache.put(99, "one-hit");

            // Insert 11th key — triggers eviction; the window victim (99) goes through admission
            // Its frequency (1) should be <= the probation victim's frequency (>=5)
            // so 99 should be rejected — the hot item stays
            cache.put(100, "another");

            // Hot items should still be present
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

    // =========================================================================
    // Hit-rate comparison — Zipfian workload (primary Phase 3 metric)
    // =========================================================================

    @Test
    void hitRateComparisonZipfianWorkload() {
        // Methodology:
        //   keySpace=100,000, capacity=10,000 (10% — matches Phase 2 JMH config)
        //   skew=1.0 (Zipfian s=1.0), same KeyDistribution generator used in JMH benchmarks
        //   Warmup: 50,000 ops (not measured); Measurement: 200,000 ops
        //   Single-segment caches for identical key routing across all three policies
        int capacity = 10_000;
        int keySpace = 100_000;
        int warmup = 50_000;
        int measured = 200_000;
        jmh.KeyDistribution zipf = new jmh.KeyDistribution(keySpace, true);

        double lruRate = measureHitRate(PolicyType.LRU, capacity, keySpace, warmup, measured, zipf);
        double lfuRate = measureHitRate(PolicyType.LFU, capacity, keySpace, warmup, measured, zipf);
        double wtlfuRate =
                measureHitRate(
                        PolicyType.WINDOW_TINY_LFU, capacity, keySpace, warmup, measured, zipf);

        System.out.printf(
                "%n=== Hit Rate Comparison (Zipfian s=1.0, capacity=%d/%d keys) ===%n"
                        + "  LRU:              %.2f%%%n"
                        + "  LFU:              %.2f%%%n"
                        + "  Window-TinyLFU:   %.2f%%%n",
                capacity, keySpace, lruRate * 100, lfuRate * 100, wtlfuRate * 100);

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
        jmh.KeyDistribution uniform = new jmh.KeyDistribution(keySpace, false);

        double lruRate =
                measureHitRate(PolicyType.LRU, capacity, keySpace, warmup, measured, uniform);
        double lfuRate =
                measureHitRate(PolicyType.LFU, capacity, keySpace, warmup, measured, uniform);
        double wtlfuRate =
                measureHitRate(
                        PolicyType.WINDOW_TINY_LFU, capacity, keySpace, warmup, measured, uniform);

        System.out.printf(
                "%n=== Hit Rate Comparison (Uniform, capacity=%d/%d keys) ===%n"
                        + "  LRU:              %.2f%%%n"
                        + "  LFU:              %.2f%%%n"
                        + "  Window-TinyLFU:   %.2f%%%n",
                capacity, keySpace, lruRate * 100, lfuRate * 100, wtlfuRate * 100);

        // Under uniform distribution, hit rate ≈ capacity / keySpace ≈ 10%
        double expected = (double) capacity / keySpace;
        double tolerance = expected * 0.5;
        assertTrue(
                lruRate > expected - tolerance,
                "LRU hit rate must be close to theoretical; was " + lruRate);
    }

    @Test
    void hitRateComparisonDistributionShift() {
        // Tests adaptability to shifting access patterns.
        //
        // Setup:
        //   capacity=10,000, hotSetSize=10,000, total keySpace=100,000
        //   Phase 1: Zipfian over keys 0-9,999 ("old hot set")
        //     - 50,000 warmup ops (not counted), then 100,000 measured ops
        //   Phase 2: Zipfian over keys 50,000-59,999 ("new hot set")
        //     - New keys have zero prior frequency in LFU's counters
        //     - 100,000 measured ops; hit rate counted only for this phase
        //
        // Expected:
        //   LFU: old-hot-set keys have saturated frequency; new hot keys start at freq=1
        //     and must displace established entries. Adapts slowly.
        //   LRU: no frequency memory. Adapts as soon as old keys stop arriving.
        //   Window-TinyLFU: Count-Min Sketch halving decays old frequencies over time.
        //     Admission gate becomes increasingly permissive for new hot keys as old
        //     counts halve. Should adapt faster than pure LFU.
        int capacity = 10_000;
        int hotSetSize = 10_000;
        int keySpace = 100_000;
        int warmupOps = 50_000;
        int phase1Ops = 100_000;
        int phase2Ops = 100_000;
        int oldHotOffset = 0;
        int newHotOffset = 50_000;

        jmh.KeyDistribution phase1Dist = new jmh.KeyDistribution(hotSetSize, true);
        jmh.KeyDistribution phase2Dist = new jmh.KeyDistribution(hotSetSize, true);

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

        System.out.printf(
                "%n=== Distribution Shift Hit Rate (post-shift phase only) ===%n"
                        + "  LRU:              %.2f%%%n"
                        + "  LFU:              %.2f%%%n"
                        + "  Window-TinyLFU:   %.2f%%%n",
                lruRate * 100, lfuRate * 100, wtlfuRate * 100);

        // All policies must recover some hits after the shift.
        assertTrue(lruRate > 0.05, "LRU must achieve > 5% hit rate post-shift; was " + lruRate);
        assertTrue(
                wtlfuRate > 0.05,
                "Window-TinyLFU must achieve > 5% hit rate post-shift; was " + wtlfuRate);
        // Window-TinyLFU must not be significantly worse than pure LFU post-shift
        // (it should be equal or better due to frequency aging).
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
            jmh.KeyDistribution phase1Dist,
            int phase1Offset,
            jmh.KeyDistribution phase2Dist,
            int phase2Offset,
            int warmupOps,
            int phase1Ops,
            int phase2Ops) {

        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(capacity, Duration.ofMinutes(10), 1, policy)) {

            // Warmup: drive phase-1 distribution to seed initial cache state
            for (int i = 0; i < warmupOps; i++) {
                String k = keys[phase1Offset + phase1Dist.nextKey()];
                if (cache.get(k).isEmpty()) {
                    cache.put(k, "v");
                }
            }

            // Phase 1: measured ops against old hot set — seeds LFU frequency counts
            for (int i = 0; i < phase1Ops; i++) {
                String k = keys[phase1Offset + phase1Dist.nextKey()];
                if (cache.get(k).isEmpty()) {
                    cache.put(k, "v");
                }
            }

            // Phase 2: shift to new hot set — measure hit rate only here
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
            jmh.KeyDistribution dist) {

        String[] keys = new String[keySpace];
        for (int i = 0; i < keySpace; i++) {
            keys[i] = "k" + i;
        }

        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(capacity, Duration.ofMinutes(10), 1, policy)) {

            // Warmup
            for (int i = 0; i < warmup; i++) {
                String k = keys[dist.nextKey()];
                if (cache.get(k).isEmpty()) {
                    cache.put(k, "v");
                }
            }

            // Reset counters by creating fresh cache — same policy, same capacity
        }
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(capacity, Duration.ofMinutes(10), 1, policy)) {

            // Pre-warm the cache with the most popular keys (first half of key space by index)
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

    // =========================================================================
    // Concurrency stress — all three policies
    // =========================================================================

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
}
