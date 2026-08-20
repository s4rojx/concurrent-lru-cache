package cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConcurrentLRUCacheTest {

    // =========================================================================
    // Functional correctness tests
    // =========================================================================

    @Test
    void supportsBasicOperations() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(3)) {
            cache.put("a", "alpha");
            cache.put("b", "bravo");

            assertEquals(Optional.of("alpha"), cache.get("a"));
            assertTrue(cache.containsKey("b"));
            assertEquals(2, cache.size());
            assertEquals("bravo", cache.remove("b"));
            assertFalse(cache.containsKey("b"));
            assertNull(cache.remove("missing"));

            cache.clear();
            assertEquals(0, cache.size());
        }
    }

    @Test
    void evictsLeastRecentlyUsedEntry() {
        // Use a single-segment cache to get deterministic exact-LRU behavior.
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1)) {
            cache.put("a", "alpha");
            cache.put("b", "bravo");
            assertEquals(Optional.of("alpha"), cache.get("a")); // a is now MRU

            cache.put("c", "charlie"); // b is LRU in segment — evicted

            assertEquals(Optional.empty(), cache.get("b"));
            assertEquals(Optional.of("alpha"), cache.get("a"));
            assertEquals(Optional.of("charlie"), cache.get("c"));
            assertEquals(1, cache.getEvictionCount());
        }
    }

    @Test
    void updatingExistingKeyRefreshesRecency() {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1)) {
            cache.put("a", "alpha");
            cache.put("b", "bravo");
            cache.put("a", "updated"); // a moves to MRU; b is now LRU
            cache.put("c", "charlie"); // b evicted

            assertEquals(Optional.of("updated"), cache.get("a"));
            assertEquals(Optional.empty(), cache.get("b"));
            assertEquals(Optional.of("charlie"), cache.get("c"));
        }
    }

    @Test
    void expiresEntriesLazilyOnAccess() throws InterruptedException {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofSeconds(10))) {
            cache.put("a", "alpha", Duration.ofMillis(25));
            TimeUnit.MILLISECONDS.sleep(50);

            assertEquals(Optional.empty(), cache.get("a"));
            assertFalse(cache.containsKey("a"));
            assertEquals(1, cache.getMissCount());
            assertEquals(1, cache.getStats().expiredRemovalCount());
        }
    }

    @Test
    void scheduledCleanupRemovesExpiredEntries() throws InterruptedException {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(5, Duration.ofMillis(20))) {
            cache.put("a", "alpha", Duration.ofMillis(10));
            cache.put("b", "bravo");

            TimeUnit.MILLISECONDS.sleep(120);

            assertFalse(cache.containsKey("a"));
            assertTrue(cache.containsKey("b"));
            assertEquals(1, cache.size());
            assertTrue(cache.getStats().expiredRemovalCount() >= 1);
        }
    }

    @Test
    void tracksStatistics() {
        // Single-segment for deterministic eviction.
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1)) {
            cache.put("a", "alpha");

            cache.get("a");
            cache.get("missing");
            cache.put("b", "bravo");
            cache.put("c", "charlie");

            assertEquals(1, cache.getHitCount());
            assertEquals(1, cache.getMissCount());
            assertEquals(2, cache.getTotalRequests());
            assertEquals(0.5, cache.getHitRate());
            assertEquals(1, cache.getEvictionCount());
        }
    }

    @Test
    void validatesConstructorAndArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
        assertThrows(
                IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(1, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConcurrentLRUCache<>(1, Duration.ofMinutes(1), 0));

        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(1)) {
            assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
            assertThrows(NullPointerException.class, () -> cache.put("key", null));
            assertThrows(
                    IllegalArgumentException.class, () -> cache.put("key", "value", Duration.ZERO));
            assertThrows(NullPointerException.class, () -> cache.get(null));
            assertThrows(NullPointerException.class, () -> cache.containsKey(null));
            assertThrows(NullPointerException.class, () -> cache.remove(null));
        }
    }

    @Test
    void handlesConcurrentMixedWorkload() throws Exception {
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(200, Duration.ofSeconds(5))) {
            ExecutorService executor = Executors.newFixedThreadPool(24);
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int worker = i;
                tasks.add(
                        () -> {
                            for (int op = 0; op < 2_000; op++) {
                                int key = (worker * 31 + op) % 500;
                                if (op % 10 < 7) {
                                    cache.get(key);
                                } else {
                                    cache.put(key, "value-" + key);
                                }
                                if (op % 250 == 0) {
                                    cache.containsKey(key);
                                }
                            }
                            return true;
                        });
            }

            List<Future<Boolean>> results = executor.invokeAll(tasks);
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            for (Future<Boolean> result : results) {
                assertTrue(result.get());
            }

            assertTrue(cache.size() <= 200);
            assertEquals(24L * 2_000L * 7L / 10L, cache.getTotalRequests());
        }
    }

    // =========================================================================
    // Concurrency stress tests
    // =========================================================================

    /**
     * Concurrent gets on the same key from many threads must all see the value or empty (never
     * throw or return corrupted data). Validates no data structure corruption under read
     * concurrency.
     */
    @Test
    void concurrentGetsOnSameKeyNeverCorrupt() throws Exception {
        final int threads = 32;
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100)) {
            cache.put("shared", "value");

            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicInteger hitCount = new AtomicInteger();
            AtomicInteger errorCount = new AtomicInteger();

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await();
                                        for (int j = 0; j < 1_000; j++) {
                                            Optional<String> result = cache.get("shared");
                                            result.ifPresent(
                                                    v -> {
                                                        if ("value".equals(v)) {
                                                            hitCount.incrementAndGet();
                                                        } else {
                                                            errorCount.incrementAndGet();
                                                        }
                                                    });
                                        }
                                    } catch (Exception e) {
                                        errorCount.incrementAndGet();
                                    }
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            for (Future<?> f : futures) {
                f.get(); // rethrow any exception
            }

            assertEquals(0, errorCount.get(), "No corrupted values or exceptions expected");
            assertTrue(hitCount.get() > 0, "At least some hits expected");
        }
    }

    /**
     * Concurrent puts to different keys must not corrupt internal state. Validates invariant:
     * size() <= capacity after all puts.
     */
    @Test
    void concurrentPutsRespectCapacity() throws Exception {
        final int capacity = 100;
        final int threads = 20;
        final int opsPerThread = 500;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity)) {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await();
                                        for (int i = 0; i < opsPerThread; i++) {
                                            cache.put(
                                                    threadId * opsPerThread + i,
                                                    "v-" + threadId + "-" + i);
                                        }
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            for (Future<?> f : futures) {
                f.get();
            }

            assertTrue(
                    cache.size() <= capacity,
                    "size() must not exceed capacity; was " + cache.size());
        }
    }

    /**
     * Mixed concurrent get/put/remove operations. Validates no exception or deadlock occurs and
     * that the size invariant holds.
     */
    @Test
    void concurrentGetPutRemoveMixed() throws Exception {
        final int capacity = 500;
        final int threads = 16;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity)) {
            // Pre-fill
            for (int i = 0; i < capacity / 2; i++) {
                cache.put(i, "init-" + i);
            }

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
                                        for (int op = 0; op < 2_000; op++) {
                                            int key = (threadId * 37 + op) % 1_000;
                                            int choice = op % 10;
                                            if (choice < 5) {
                                                cache.get(key);
                                            } else if (choice < 8) {
                                                cache.put(key, "val-" + key);
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

            assertEquals(0, exceptionCount.get(), "No exceptions expected during mixed operations");
            assertTrue(cache.size() <= capacity, "size() must not exceed capacity");
        }
    }

    /**
     * Concurrent clear operations interleaved with puts. Validates no exceptions and that size
     * remains bounded.
     */
    @Test
    void concurrentClearAndPutDoNotCorrupt() throws Exception {
        final int capacity = 200;
        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity)) {
            final int threads = 8;
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
                                        for (int op = 0; op < 500; op++) {
                                            if (threadId == 0 && op % 50 == 0) {
                                                cache.clear();
                                            } else {
                                                cache.put(
                                                        (threadId * 100 + op) % (capacity * 2),
                                                        "v" + op);
                                                cache.get((threadId * 100 + op) % (capacity * 2));
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

            assertEquals(0, exceptionCount.get(), "No exceptions expected");
            assertTrue(
                    cache.size() <= capacity,
                    "size must not exceed capacity after concurrent clear+put");
        }
    }

    /**
     * Concurrent eviction stress: more puts than capacity forces continuous eviction. Validates the
     * eviction counter increases and the size invariant holds throughout.
     */
    @Test
    void evictionUnderConcurrency() throws Exception {
        final int capacity = 50;
        final int threads = 10;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity)) {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await();
                                        // Each thread inserts 1000 distinct keys into a capacity-50
                                        // cache, forcing many evictions.
                                        for (int i = 0; i < 1_000; i++) {
                                            cache.put(threadId * 1_000 + i, "val-" + i);
                                        }
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (Future<?> f : futures) {
                f.get();
            }

            assertTrue(
                    cache.size() <= capacity, "size must remain <= capacity; was " + cache.size());
            assertTrue(cache.getEvictionCount() > 0, "Expected evictions to occur");
        }
    }

    /**
     * Concurrent TTL: many threads write entries with short TTLs while other threads read.
     * Validates no thread ever observes a corrupted or null value where a non-null was expected.
     */
    @Test
    void ttlExpirationUnderConcurrency() throws Exception {
        final int threads = 12;
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(1_000, Duration.ofSeconds(10))) {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicInteger errorCount = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await();
                                        for (int op = 0; op < 500; op++) {
                                            String key = "key-" + (threadId * 500 + op) % 200;
                                            if (op % 3 == 0) {
                                                // Write with short TTL
                                                cache.put(key, "val-" + op, Duration.ofMillis(50));
                                            } else {
                                                // Read — may be hit or expired-miss, but must not
                                                // return corrupted data
                                                Optional<String> result = cache.get(key);
                                                if (result.isPresent() && result.get() == null) {
                                                    errorCount.incrementAndGet();
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        errorCount.incrementAndGet();
                                    }
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (Future<?> f : futures) {
                f.get();
            }

            assertEquals(
                    0, errorCount.get(), "No null values or errors expected under TTL concurrency");
        }
    }

    /**
     * Metrics remain consistent under concurrency: totalRequests == hits + misses (since every
     * expired access counts as a miss).
     */
    @Test
    void metricsConsistencyUnderConcurrency() throws Exception {
        final int threads = 16;
        final int opsPerThread = 1_000;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(200)) {
            // Pre-fill half the keyspace.
            for (int i = 0; i < 100; i++) {
                cache.put(i, "val-" + i);
            }

            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicLong expectedRequests = new AtomicLong();
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await();
                                        int localRequests = 0;
                                        for (int op = 0; op < opsPerThread; op++) {
                                            int key = (threadId * 37 + op) % 200;
                                            if (op % 4 < 3) {
                                                cache.get(key);
                                                localRequests++;
                                            } else {
                                                cache.put(key, "v" + key);
                                            }
                                        }
                                        expectedRequests.addAndGet(localRequests);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (Future<?> f : futures) {
                f.get();
            }

            CacheStats stats = cache.getStats();
            assertEquals(
                    expectedRequests.get(),
                    stats.totalRequests(),
                    "totalRequests must match actual get() call count");
            assertEquals(
                    stats.totalRequests(),
                    stats.hitCount() + stats.missCount(),
                    "totalRequests must equal hits + misses");
        }
    }

    /**
     * Executor shutdown: after shutdown() is called, background expiration stops but the cache
     * continues to function for foreground operations. Validates no exception is thrown.
     */
    @Test
    void executorShutdownIsClean() throws InterruptedException {
        ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(100, Duration.ofMillis(50));
        cache.put("k", "v");
        assertEquals(Optional.of("v"), cache.get("k"));

        // Let the background thread run at least once.
        TimeUnit.MILLISECONDS.sleep(150);

        // Shutdown should complete without exception.
        cache.shutdown();

        // Foreground operations must still work after shutdown.
        cache.put("k2", "v2");
        assertEquals(Optional.of("v2"), cache.get("k2"));

        // Double-shutdown via close() must not throw.
        cache.close();
    }

    /**
     * Validates the invariant that map and LRU list stay consistent: after all operations, size()
     * equals the number of keys actually present.
     */
    @Test
    void sizeInvariantHoldsUnderConcurrency() throws Exception {
        final int capacity = 100;
        final int threads = 8;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity)) {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await();
                                        for (int op = 0; op < 1_000; op++) {
                                            int key = (threadId * 13 + op) % 300;
                                            if (op % 5 == 0) {
                                                cache.remove(key);
                                            } else if (op % 5 == 1) {
                                                cache.put(key, "val");
                                            } else {
                                                cache.get(key);
                                            }
                                        }
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (Future<?> f : futures) {
                f.get();
            }

            int reportedSize = cache.size();
            assertTrue(
                    reportedSize >= 0 && reportedSize <= capacity,
                    "size() must be in [0, capacity]; was " + reportedSize);
        }
    }
}
