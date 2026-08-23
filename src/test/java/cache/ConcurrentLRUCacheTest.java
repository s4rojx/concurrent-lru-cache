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
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(2, Duration.ofMinutes(1), 1)) {
            cache.put("a", "alpha");
            cache.put("b", "bravo");
            assertEquals(Optional.of("alpha"), cache.get("a"));

            cache.put("c", "charlie");

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
            cache.put("a", "updated");
            cache.put("c", "charlie");

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
                f.get();
            }

            assertEquals(0, errorCount.get(), "No corrupted values or exceptions expected");
            assertTrue(hitCount.get() > 0, "At least some hits expected");
        }
    }

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

    @Test
    void concurrentGetPutRemoveMixed() throws Exception {
        final int capacity = 500;
        final int threads = 16;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity)) {
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
                                                cache.put(key, "val-" + op, Duration.ofMillis(50));
                                            } else {
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

    @Test
    void metricsConsistencyUnderConcurrency() throws Exception {
        final int threads = 16;
        final int opsPerThread = 1_000;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(200)) {
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

    @Test
    void executorShutdownIsClean() throws InterruptedException {
        ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(100, Duration.ofMillis(50));
        cache.put("k", "v");
        assertEquals(Optional.of("v"), cache.get("k"));

        TimeUnit.MILLISECONDS.sleep(150);

        cache.shutdown();

        cache.put("k2", "v2");
        assertEquals(Optional.of("v2"), cache.get("k2"));

        cache.close();
    }

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
