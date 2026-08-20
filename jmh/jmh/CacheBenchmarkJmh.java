package jmh;

import cache.ConcurrentLRUCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH throughput benchmarks comparing ConcurrentLRUCache against Caffeine.
 *
 * <p><b>Methodology decisions:</b>
 *
 * <ul>
 *   <li>Throughput mode (ops/second) is the primary metric; it aggregates the work done across all
 *       concurrent threads, which is the relevant figure for a shared cache.
 *   <li>{@code @State(Scope.Benchmark)} means one instance is shared across all threads within a
 *       fork — this is correct for a concurrent cache test. Per-thread state would hide contention.
 *   <li>Keys are pre-generated strings and stored in an array so benchmark iterations do not
 *       measure string creation or GC pressure from key allocation.
 *   <li>The cache is pre-warmed with 50% of the key space before measurement to give a realistic
 *       mix of hits and misses from the first measured iteration.
 *   <li>Caffeine is configured with the same capacity and no expiration to match our cache's
 *       no-TTL configuration. No loader is used (manual get/put only), keeping the operation
 *       structure identical to our implementation.
 * </ul>
 *
 * <p><b>Why JMH instead of System.nanoTime()?</b> See docs/04_BENCHMARKS.md §2.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(
        value = 2,
        jvmArgs = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
public class CacheBenchmarkJmh {

    private static final int CAPACITY = 10_000;
    private static final int KEY_SPACE = 10_000;

    // ---------- benchmark parameters ----------

    @Param({"READ_HEAVY", "BALANCED", "WRITE_HEAVY"})
    public String workload;

    @Param({"UNIFORM", "ZIPFIAN"})
    public String distribution;

    // ---------- shared state ----------

    private ConcurrentLRUCache<String, String> ourCache;
    private Cache<String, String> caffeineCache;
    private String[] keys;
    private KeyDistribution keyDist;

    // get fraction derived from workload parameter
    private double getProb;

    @Setup(Level.Trial)
    public void setup() {
        getProb =
                switch (workload) {
                    case "READ_HEAVY" -> 0.95;
                    case "BALANCED" -> 0.80;
                    case "WRITE_HEAVY" -> 0.50;
                    default -> throw new IllegalArgumentException("Unknown workload: " + workload);
                };

        boolean zipf = "ZIPFIAN".equals(distribution);
        keyDist = new KeyDistribution(KEY_SPACE, zipf);

        // Pre-build key strings once to avoid allocation during measurement.
        keys = new String[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) {
            keys[i] = "key-" + i;
        }

        ourCache = new ConcurrentLRUCache<>(CAPACITY, Duration.ofMinutes(10));
        caffeineCache = Caffeine.newBuilder().maximumSize(CAPACITY).build();

        // Pre-warm both caches with 50% of the key space.
        for (int i = 0; i < KEY_SPACE / 2; i++) {
            String k = keys[i];
            ourCache.put(k, "val-" + i);
            caffeineCache.put(k, "val-" + i);
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        ourCache.shutdown();
    }

    // ---------- benchmarks ----------

    @Benchmark
    public void ourCache(Blackhole bh) {
        String key = keys[keyDist.nextKey()];
        if (Math.random() < getProb) {
            Optional<String> v = ourCache.get(key);
            bh.consume(v);
        } else {
            ourCache.put(key, "v");
        }
    }

    @Benchmark
    public void caffeineCache(Blackhole bh) {
        String key = keys[keyDist.nextKey()];
        if (Math.random() < getProb) {
            String v = caffeineCache.getIfPresent(key);
            bh.consume(v);
        } else {
            caffeineCache.put(key, "v");
        }
    }
}
