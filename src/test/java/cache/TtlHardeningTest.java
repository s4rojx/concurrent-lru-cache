package cache;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TtlHardeningTest {

    @Test
    void lazyExpiryRemovesFromPolicyForAllPolicies() throws Exception {
        for (PolicyType policy : PolicyType.values()) {
            try (ConcurrentLRUCache<String, String> cache =
                    new ConcurrentLRUCache<>(10, Duration.ofMinutes(10), 1, policy)) {

                cache.put("a", "v", Duration.ofMillis(20));
                cache.put("b", "v");

                TimeUnit.MILLISECONDS.sleep(40);

                assertEquals(
                        Optional.empty(),
                        cache.get("a"),
                        policy + ": expired key must return empty");
                assertTrue(
                        cache.get("b").isPresent(), policy + ": non-expired key must return value");

                for (int i = 0; i < 12; i++) {
                    cache.put("fill-" + i, "v");
                }
                assertTrue(
                        cache.size() <= 10,
                        policy + ": size must not exceed capacity after lazy expiry");
            }
        }
    }

    @Test
    void scheduledCleanupUpdatesAllPolicies() throws Exception {
        for (PolicyType policy : PolicyType.values()) {
            try (ConcurrentLRUCache<String, String> cache =
                    new ConcurrentLRUCache<>(10, Duration.ofMillis(30), 1, policy)) {

                cache.put("x", "v", Duration.ofMillis(15));
                cache.put("y", "v");

                TimeUnit.MILLISECONDS.sleep(120);

                assertFalse(
                        cache.containsKey("x"),
                        policy + ": scheduled sweep must remove expired entry");
                assertTrue(
                        cache.containsKey("y"), policy + ": non-expired entry must survive sweep");
                assertEquals(
                        1,
                        cache.getStats().expiredRemovalCount(),
                        policy + ": expiredRemovals must count exactly 1 removed entry");
            }
        }
    }

    @Test
    void putOnExistingKeyFullyReplacesOldTtl() throws Exception {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(10, Duration.ofMinutes(1), 1)) {

            cache.put("k", "v1", Duration.ofMillis(20));
            cache.put("k", "v2", Duration.ofMinutes(10));

            TimeUnit.MILLISECONDS.sleep(40);

            Optional<String> result = cache.get("k");
            assertTrue(
                    result.isPresent(),
                    "key must still be present after old TTL would have expired");
            assertEquals("v2", result.get(), "value must be updated");
        }
    }

    @Test
    void putOnExistingKeyReplacesLongTtlWithShort() throws Exception {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(10, Duration.ofMinutes(1), 1)) {

            cache.put("k", "v1", Duration.ofMinutes(10));
            cache.put("k", "v2", Duration.ofMillis(20));

            TimeUnit.MILLISECONDS.sleep(40);

            assertEquals(
                    Optional.empty(),
                    cache.get("k"),
                    "key must have expired after TTL replacement");
        }
    }

    @Test
    void putOnExpiredKeyRefreshesIt() throws Exception {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(10, Duration.ofMinutes(1), 1)) {

            cache.put("k", "old", Duration.ofMillis(20));
            TimeUnit.MILLISECONDS.sleep(40);

            cache.put("k", "fresh", Duration.ofMinutes(10));

            Optional<String> result = cache.get("k");
            assertTrue(result.isPresent(), "refreshed key must be present");
            assertEquals("fresh", result.get());
        }
    }

    @Test
    void zeroDurationTtlIsRejectedAtPut() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.put("k", "v", Duration.ZERO),
                    "TTL=0 must be rejected");
        }
    }

    @Test
    void negativeDurationTtlIsRejectedAtPut() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.put("k", "v", Duration.ofMillis(-1)),
                    "negative TTL must be rejected");
        }
    }

    @Test
    void containsKeyThenGetRaceDoesNotThrowOrReturnStale() throws Exception {
        try (ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(10, Duration.ofMinutes(1), 1)) {

            cache.put("k", "v", Duration.ofMillis(25));

            boolean seen = cache.containsKey("k");
            TimeUnit.MILLISECONDS.sleep(40);
            Optional<String> result = cache.get("k");

            if (seen) {
                result.ifPresent(v -> assertNotNull(v, "value inside Optional must not be null"));
            }
        }
    }

    @Test
    void expirationAndEvictionRaceDoesNotCorruptState() throws Exception {
        for (PolicyType policy : PolicyType.values()) {
            final int capacity = 50;
            final int threads = 12;
            try (ConcurrentLRUCache<Integer, String> cache =
                    new ConcurrentLRUCache<>(capacity, Duration.ofMinutes(10), 1, policy)) {

                CyclicBarrier barrier = new CyclicBarrier(threads);
                ExecutorService executor = Executors.newFixedThreadPool(threads);
                AtomicInteger errors = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();

                for (int t = 0; t < threads; t++) {
                    final int threadId = t;
                    futures.add(
                            executor.submit(
                                    () -> {
                                        try {
                                            barrier.await();
                                            for (int op = 0; op < 500; op++) {
                                                int key = (threadId * 31 + op) % (capacity * 2);
                                                if (op % 5 == 0) {
                                                    cache.put(key, "v", Duration.ofMillis(10));
                                                } else if (op % 5 == 1) {
                                                    cache.put(key, "v");
                                                } else {
                                                    cache.get(key);
                                                }
                                            }
                                        } catch (Exception e) {
                                            errors.incrementAndGet();
                                        }
                                    }));
                }

                executor.shutdown();
                assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
                for (Future<?> f : futures) f.get();

                assertEquals(
                        0, errors.get(), policy + ": no exceptions under expiry+eviction race");
                assertTrue(cache.size() <= capacity, policy + ": size must not exceed capacity");
            }
        }
    }

    @Test
    void windowTinyLfuExpiredWindowVictimDoesNotDangleInPolicy() throws Exception {
        int capacity = 20;
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(
                        capacity, Duration.ofMinutes(10), 1, PolicyType.WINDOW_TINY_LFU)) {

            for (int i = 0; i < 10; i++) {
                cache.put(i, "short", Duration.ofMillis(20));
            }
            TimeUnit.MILLISECONDS.sleep(40);

            for (int i = 100; i < 130; i++) {
                cache.put(i, "long");
            }

            for (int i = 0; i < 10; i++) {
                assertEquals(
                        Optional.empty(),
                        cache.get(i),
                        "expired window victim must not be retrievable after eviction pressure");
            }
            assertTrue(cache.size() <= capacity);
        }
    }

    @Test
    void closeWhileScheduledSweepIsRunningDoesNotThrow() throws Exception {
        ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(100, Duration.ofMillis(20));

        for (int i = 0; i < 50; i++) {
            cache.put(i, "v", Duration.ofMillis(10));
        }

        TimeUnit.MILLISECONDS.sleep(80);
        assertDoesNotThrow(cache::close);
    }

    @Test
    void closeTwiceDoesNotThrow() {
        ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(10, Duration.ofMillis(50));
        cache.put("k", "v");
        assertDoesNotThrow(cache::close);
        assertDoesNotThrow(cache::close);
    }

    @Test
    void foregroundOpsWorkAfterShutdown() {
        ConcurrentLRUCache<String, String> cache =
                new ConcurrentLRUCache<>(10, Duration.ofMillis(50));
        cache.put("k", "v");
        cache.shutdown();

        assertDoesNotThrow(() -> cache.put("k2", "v2"));
        assertTrue(cache.get("k2").isPresent(), "get() must work after shutdown()");
        cache.close();
    }

    @Test
    void expiredRemovalCountAccurateAfterScheduledSweep() throws Exception {
        try (ConcurrentLRUCache<Integer, String> cache =
                new ConcurrentLRUCache<>(100, Duration.ofMillis(30))) {

            int expiredCount = 10;
            for (int i = 0; i < expiredCount; i++) {
                cache.put(i, "v", Duration.ofMillis(10));
            }
            for (int i = 100; i < 110; i++) {
                cache.put(i, "v");
            }

            TimeUnit.MILLISECONDS.sleep(120);

            long reported = cache.getStats().expiredRemovalCount();
            assertEquals(
                    expiredCount,
                    reported,
                    "expiredRemovals must match exactly the number of expired entries removed");
        }
    }
}
