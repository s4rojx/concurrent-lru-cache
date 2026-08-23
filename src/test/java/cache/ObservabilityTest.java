package cache;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ObservabilityTest {

    static final class SkewedKey {
        private final String val;
        private final int fixedHash;

        SkewedKey(String val, int fixedHash) {
            this.val = val;
            this.fixedHash = fixedHash;
        }

        @Override
        public int hashCode() {
            return fixedHash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SkewedKey other)) return false;
            return val.equals(other.val);
        }
    }

    @Test
    void perSegmentMetricsReflectRealLoadDistribution() {
        try (ConcurrentLRUCache<SkewedKey, String> cache =
                new ConcurrentLRUCache<>(32, Duration.ofMinutes(1), 4)) {
            cache.put(new SkewedKey("k0-1", 0), "v");
            cache.put(new SkewedKey("k0-2", 0), "v");
            cache.put(new SkewedKey("k1-1", 1), "v");

            cache.get(new SkewedKey("k0-1", 0));
            cache.get(new SkewedKey("k0-2", 0));
            cache.get(new SkewedKey("k0-missing", 0));
            cache.get(new SkewedKey("k1-1", 1));

            List<SegmentStats> segmentStats = cache.getSegmentStats();
            assertEquals(4, segmentStats.size());

            SegmentStats seg0 = segmentStats.get(0);
            assertEquals(2, seg0.size());
            assertEquals(2, seg0.hitCount());
            assertEquals(1, seg0.missCount());
            assertEquals(3, seg0.totalRequests());
            assertEquals(2.0 / 3.0, seg0.hitRate(), 0.001);

            SegmentStats seg1 = segmentStats.get(1);
            assertEquals(1, seg1.size());
            assertEquals(1, seg1.hitCount());
            assertEquals(0, seg1.missCount());
            assertEquals(1, seg1.totalRequests());
            assertEquals(1.0, seg1.hitRate(), 0.001);

            SegmentStats seg2 = segmentStats.get(2);
            assertEquals(0, seg2.size());
            assertEquals(0, seg2.totalRequests());
        }
    }

    @Test
    void singleFlightCoalescingMetricsCountAvoidedLoads() throws Exception {
        final int threads = 20;
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
                                            "shared-key",
                                            k -> {
                                                try {
                                                    TimeUnit.MILLISECONDS.sleep(50);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return "computed";
                                            });
                                }));
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            for (Future<String> f : futures) {
                assertEquals("computed", f.get());
            }

            CacheStats stats = cache.getStats();
            assertEquals(1, stats.loadAttempts());
            assertEquals(1, stats.loadSuccessCount());
            assertEquals(0, stats.loadFailureCount());
            assertEquals(19, stats.coalescedLoadCount());
            assertEquals(19.0 / 20.0, stats.coalescingRatio(), 0.001);
            assertTrue(stats.totalLoadTimeNanos() > 0);
            assertTrue(stats.averageLoadLatencyNanos() > 0);
        }
    }

    @Test
    void singleFlightLoadFailureMetricsTrackErrors() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10)) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            cache.get(
                                    "fail",
                                    k -> {
                                        throw new IllegalStateException("error");
                                    }));

            CacheStats stats = cache.getStats();
            assertEquals(1, stats.loadAttempts());
            assertEquals(0, stats.loadSuccessCount());
            assertEquals(1, stats.loadFailureCount());
        }
    }

    @Test
    void windowTinyLFUPolicyMetricsReflectAdmissionDecisions() {
        try (ConcurrentLRUCache<Integer, Integer> cache =
                new ConcurrentLRUCache<>(
                        10, Duration.ofMinutes(1), 1, PolicyType.WINDOW_TINY_LFU)) {
            for (int i = 0; i < 50; i++) {
                cache.put(i, i);
            }

            PolicyStats policyStats = cache.getPolicyStats();
            assertEquals("WINDOW_TINY_LFU", policyStats.policyName());
            assertTrue(policyStats.admissions() > 0 || policyStats.rejections() > 0);
            assertTrue(policyStats.rejectionRate() >= 0.0 && policyStats.rejectionRate() <= 1.0);

            CacheStats stats = cache.getStats();
            assertEquals(policyStats, stats.policyStats());
            assertNotNull(stats.formattedSummary());
        }
    }

    @Test
    void micrometerBinderExportsAllMeters() {
        try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100)) {
            MeterRegistry registry = new SimpleMeterRegistry();
            ConcurrentLRUCacheMetrics.monitor(registry, cache, "test-cache", "env", "unit-test");

            cache.put("k1", "v1");
            cache.put("k2", "v2");
            cache.get("k1");
            cache.get("missing");

            cache.get("loaded", k -> "v-loaded");

            Gauge sizeGauge = registry.find("cache.size").tag("cache", "test-cache").gauge();
            assertNotNull(sizeGauge);
            assertEquals(3.0, sizeGauge.value());

            FunctionCounter hitCounter =
                    registry.find("cache.hits").tag("cache", "test-cache").functionCounter();
            assertNotNull(hitCounter);
            assertEquals(1.0, hitCounter.count());

            FunctionCounter missCounter =
                    registry.find("cache.misses").tag("cache", "test-cache").functionCounter();
            assertNotNull(missCounter);
            assertEquals(2.0, missCounter.count());

            FunctionCounter requestCounter =
                    registry.find("cache.requests").tag("cache", "test-cache").functionCounter();
            assertNotNull(requestCounter);
            assertEquals(3.0, requestCounter.count());

            FunctionCounter attemptCounter =
                    registry.find("cache.loads.attempts")
                            .tag("cache", "test-cache")
                            .functionCounter();
            assertNotNull(attemptCounter);
            assertEquals(1.0, attemptCounter.count());

            FunctionCounter coalescedCounter =
                    registry.find("cache.loads.coalesced")
                            .tag("cache", "test-cache")
                            .functionCounter();
            assertNotNull(coalescedCounter);
            assertEquals(0.0, coalescedCounter.count());
        }
    }
}
