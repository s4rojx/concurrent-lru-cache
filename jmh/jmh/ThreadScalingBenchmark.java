package jmh;

import cache.ConcurrentLRUCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Thread-scaling benchmark: measures aggregate throughput as active thread count increases.
 *
 * <p>Workload is fixed at read-heavy / uniform (GET_PROB=0.95) to isolate the concurrency
 * variable. Each method is annotated with a distinct {@code @Threads(N)} value so JMH runs that
 * exact number of concurrent threads for that benchmark row.
 *
 * <p>Thread counts: 1, 2, 4, 8, 12, 16, 32. Counts 64–256 are omitted because this machine has 12
 * logical processors (8 P-cores + 4 E-cores); above 32 threads the OS scheduler overhead dominates
 * and results do not reflect cache concurrency design. See docs/04_BENCHMARKS.md §4 for discussion.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(
        value = 1,
        jvmArgs = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
public class ThreadScalingBenchmark {

    private static final int CAPACITY = 10_000;
    private static final int KEY_SPACE = 10_000;
    private static final double GET_PROB = 0.95;

    private ConcurrentLRUCache<String, String> ourCache;
    private Cache<String, String> caffeineCache;
    private String[] keys;
    private KeyDistribution keyDist;

    @Setup(Level.Trial)
    public void setup() {
        keyDist = new KeyDistribution(KEY_SPACE, false);
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
    @Threads(1)
    public void ourCache_t1(Blackhole bh) {
        doOp(bh);
    }

    @Benchmark
    @Threads(2)
    public void ourCache_t2(Blackhole bh) {
        doOp(bh);
    }

    @Benchmark
    @Threads(4)
    public void ourCache_t4(Blackhole bh) {
        doOp(bh);
    }

    @Benchmark
    @Threads(8)
    public void ourCache_t8(Blackhole bh) {
        doOp(bh);
    }

    @Benchmark
    @Threads(12)
    public void ourCache_t12(Blackhole bh) {
        doOp(bh);
    }

    @Benchmark
    @Threads(16)
    public void ourCache_t16(Blackhole bh) {
        doOp(bh);
    }

    @Benchmark
    @Threads(32)
    public void ourCache_t32(Blackhole bh) {
        doOp(bh);
    }

    @Benchmark
    @Threads(1)
    public void caffeine_t1(Blackhole bh) {
        doCaffeineOp(bh);
    }

    @Benchmark
    @Threads(2)
    public void caffeine_t2(Blackhole bh) {
        doCaffeineOp(bh);
    }

    @Benchmark
    @Threads(4)
    public void caffeine_t4(Blackhole bh) {
        doCaffeineOp(bh);
    }

    @Benchmark
    @Threads(8)
    public void caffeine_t8(Blackhole bh) {
        doCaffeineOp(bh);
    }

    @Benchmark
    @Threads(12)
    public void caffeine_t12(Blackhole bh) {
        doCaffeineOp(bh);
    }

    @Benchmark
    @Threads(16)
    public void caffeine_t16(Blackhole bh) {
        doCaffeineOp(bh);
    }

    @Benchmark
    @Threads(32)
    public void caffeine_t32(Blackhole bh) {
        doCaffeineOp(bh);
    }

    private void doOp(Blackhole bh) {
        String key = keys[keyDist.nextKey()];
        if (Math.random() < GET_PROB) {
            bh.consume(ourCache.get(key));
        } else {
            ourCache.put(key, "v");
        }
    }

    private void doCaffeineOp(Blackhole bh) {
        String key = keys[keyDist.nextKey()];
        if (Math.random() < GET_PROB) {
            bh.consume(caffeineCache.getIfPresent(key));
        } else {
            caffeineCache.put(key, "v");
        }
    }
}
