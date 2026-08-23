package jmh;

import cache.ConcurrentLRUCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

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

    @Param({"READ_HEAVY", "BALANCED", "WRITE_HEAVY"})
    public String workload;

    @Param({"UNIFORM", "ZIPFIAN"})
    public String distribution;

    private ConcurrentLRUCache<String, String> ourCache;
    private Cache<String, String> caffeineCache;
    private String[] keys;
    private KeyDistribution keyDist;

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

        keys = new String[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) {
            keys[i] = "key-" + i;
        }

        ourCache = new ConcurrentLRUCache<>(CAPACITY, Duration.ofMinutes(10));
        caffeineCache = Caffeine.newBuilder().maximumSize(CAPACITY).build();

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
