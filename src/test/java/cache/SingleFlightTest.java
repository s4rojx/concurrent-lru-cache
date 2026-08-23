package cache;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightTest {

    @Test
    void concurrentRequestsForSameMissingKeyInvokeLoaderOnlyOnce() throws Exception {
        final int threads = 20;
        final AtomicInteger loadCount = new AtomicInteger();
        final CountDownLatch startLatch = new CountDownLatch(1);

        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100)) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    startLatch.await();
                                    return cache.get(
                                            "heavy-key",
                                            k -> {
                                                loadCount.incrementAndGet();
                                                try {
                                                    TimeUnit.MILLISECONDS.sleep(50);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return "computed-val";
                                            });
                                }));
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            for (Future<String> f : futures) {
                assertEquals("computed-val", f.get());
            }

            assertEquals(
                    1,
                    loadCount.get(),
                    "loader must be invoked exactly once across concurrent threads");
            assertEquals("computed-val", cache.get("heavy-key").orElse(null));
        }
    }

    @Test
    void cacheHitBypassesLoader() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            cache.put("k", "v1");
            AtomicInteger loadCount = new AtomicInteger();

            String result =
                    cache.get(
                            "k",
                            k -> {
                                loadCount.incrementAndGet();
                                return "v2";
                            });

            assertEquals("v1", result);
            assertEquals(0, loadCount.get());
        }
    }

    @Test
    void differentKeysLoadInParallel() throws Exception {
        final int keyCount = 4;
        final long delayMs = 150;

        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100)) {
            ExecutorService executor = Executors.newFixedThreadPool(keyCount);
            CyclicBarrier barrier = new CyclicBarrier(keyCount);
            List<Future<String>> futures = new ArrayList<>();

            long start = System.nanoTime();
            for (int i = 0; i < keyCount; i++) {
                final String key = "key-" + i;
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await();
                                    return cache.get(
                                            key,
                                            k -> {
                                                try {
                                                    TimeUnit.MILLISECONDS.sleep(delayMs);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return "val-" + k;
                                            });
                                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            for (int i = 0; i < keyCount; i++) {
                assertEquals("val-key-" + i, futures.get(i).get());
            }

            assertTrue(
                    elapsedMs < delayMs * keyCount,
                    "Parallel loads should take much less than sequential time; was "
                            + elapsedMs
                            + "ms");
        }
    }

    @Test
    void loaderExceptionPropagatesToAllWaitersAndAllowsRetry() throws Exception {
        final int threads = 10;
        final AtomicInteger attemptCount = new AtomicInteger();
        final CountDownLatch startLatch = new CountDownLatch(1);

        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100)) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    startLatch.await();
                                    return cache.get(
                                            "failing-key",
                                            k -> {
                                                attemptCount.incrementAndGet();
                                                throw new IllegalStateException(
                                                        "database unreachable");
                                            });
                                }));
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            int exceptionCount = 0;
            for (Future<String> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IllegalStateException
                            && "database unreachable".equals(e.getCause().getMessage())) {
                        exceptionCount++;
                    }
                }
            }

            assertEquals(
                    threads, exceptionCount, "all waiters must receive the underlying exception");
            assertEquals(
                    1,
                    attemptCount.get(),
                    "failing loader should only execute once per in-flight batch");

            String recovered = cache.get("failing-key", k -> "recovered-val");
            assertEquals(
                    "recovered-val",
                    recovered,
                    "subsequent request must be allowed to retry successfully");
        }
    }

    @Test
    void nullLoaderThrowsNullPointerExceptionForLeaderAndFollowers() throws Exception {
        final int threads = 8;
        final CountDownLatch leaderRunning = new CountDownLatch(1);
        final CountDownLatch allowLeaderFinish = new CountDownLatch(1);
        final CountDownLatch startLatch = new CountDownLatch(1);

        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    startLatch.await();
                                    return cache.get(
                                            "null-key",
                                            k -> {
                                                leaderRunning.countDown();
                                                try {
                                                    allowLeaderFinish.await();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return null;
                                            });
                                }));
            }

            startLatch.countDown();
            leaderRunning.await(5, TimeUnit.SECONDS);
            allowLeaderFinish.countDown();

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            int npeCount = 0;
            for (Future<String> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof NullPointerException
                            && e.getCause().getMessage().contains("loader returned null")) {
                        npeCount++;
                    }
                }
            }

            assertEquals(
                    threads,
                    npeCount,
                    "leader and all follower threads must receive NullPointerException");
            assertFalse(
                    cache.containsKey("null-key"), "null values must never be stored in the cache");

            String valid = cache.get("null-key", k -> "valid");
            assertEquals("valid", valid, "subsequent call must succeed after null loader failure");
        }
    }

    @Test
    void reentrantLoadOnSameKeyThrowsIllegalStateExceptionWithoutDeadlock() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    cache.get(
                                            "reentrant-key",
                                            k -> cache.get("reentrant-key", k2 -> "inner-val")));

            assertTrue(
                    ex.getMessage().contains("Recursive load detected for key: reentrant-key"),
                    "must detect recursive load on same key");

            String recovered = cache.get("reentrant-key", k -> "recovered");
            assertEquals(
                    "recovered",
                    recovered,
                    "cache must remain functional for the key after recursive error");
        }
    }

    @Test
    void leaderRechecksCacheAfterWinningRaceToAvoidRedundantLoad() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            SingleFlightCoordinator<String, String> coordinator = new SingleFlightCoordinator<>();
            AtomicInteger loadCount = new AtomicInteger();
            AtomicBoolean firstCheck = new AtomicBoolean(true);

            String result =
                    coordinator.getOrLoad(
                            "race-key",
                            k -> {
                                loadCount.incrementAndGet();
                                return "from-loader";
                            },
                            null,
                            k -> {
                                if (firstCheck.getAndSet(false)) {
                                    return java.util.Optional.empty();
                                }
                                return java.util.Optional.of("from-concurrent-put");
                            },
                            (k, v) -> cache.put(k, v));

            assertEquals("from-concurrent-put", result);
            assertEquals(
                    0,
                    loadCount.get(),
                    "loader must not be invoked if re-check finds cached value");
        }
    }

    @Test
    void singleFlightWithCustomTtlExpiresCorrectly() throws Exception {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            String val = cache.get("ttl-key", k -> "expiring", Duration.ofMillis(30));
            assertEquals("expiring", val);

            TimeUnit.MILLISECONDS.sleep(60);
            assertFalse(cache.containsKey("ttl-key"));

            AtomicInteger reloadCount = new AtomicInteger();
            String fresh =
                    cache.get(
                            "ttl-key",
                            k -> {
                                reloadCount.incrementAndGet();
                                return "fresh";
                            });
            assertEquals("fresh", fresh);
            assertEquals(1, reloadCount.get());
        }
    }

    @Test
    void recursiveLoadOnDifferentKeysDoesNotDeadlock() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            String val =
                    cache.get(
                            "outer",
                            k -> {
                                String inner = cache.get("inner", k2 -> "inner-val");
                                return "outer-" + inner;
                            });

            assertEquals("outer-inner-val", val);
            assertEquals("inner-val", cache.get("inner").orElse(null));
        }
    }

    @Test
    void singleFlightUnderMixedConcurrencyAndEviction() throws Exception {
        final int capacity = 50;
        final int threads = 16;
        final int opsPerThread = 500;

        try (ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity)) {
            ConcurrentHashMap<Integer, AtomicInteger> loadCounters = new ConcurrentHashMap<>();
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
                                            int key = (threadId * 17 + i) % 100;
                                            cache.get(
                                                    key,
                                                    k -> {
                                                        loadCounters
                                                                .computeIfAbsent(
                                                                        k, x -> new AtomicInteger())
                                                                .incrementAndGet();
                                                        return "val-" + k;
                                                    });
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

            assertTrue(cache.size() <= capacity, "cache size must not exceed capacity");
        }
    }
}
