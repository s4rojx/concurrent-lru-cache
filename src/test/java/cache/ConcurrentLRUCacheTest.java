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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(2)) {
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
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(2)) {
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
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(2)) {
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
}
