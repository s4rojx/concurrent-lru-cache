package cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConcurrentLRUCache<K, V> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentLRUCache.class);
    private static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofMinutes(1);

    static final int DEFAULT_NUM_SEGMENTS = 16;

    private final int totalCapacity;
    private final CacheSegment<K, V>[] segments;
    private final int numSegments;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expiredRemovals = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();

    private final ExpirationManager<K, V> expirationManager;
    private final SingleFlightCoordinator<K, V> coordinator = new SingleFlightCoordinator<>();

    public ConcurrentLRUCache(int capacity) {
        this(capacity, DEFAULT_CLEANUP_INTERVAL, DEFAULT_NUM_SEGMENTS, PolicyType.LRU);
    }

    public ConcurrentLRUCache(int capacity, Duration cleanupInterval) {
        this(capacity, cleanupInterval, DEFAULT_NUM_SEGMENTS, PolicyType.LRU);
    }

    public ConcurrentLRUCache(int capacity, Duration cleanupInterval, int numSegments) {
        this(capacity, cleanupInterval, numSegments, PolicyType.LRU);
    }

    public ConcurrentLRUCache(int capacity, PolicyType policy) {
        this(capacity, DEFAULT_CLEANUP_INTERVAL, DEFAULT_NUM_SEGMENTS, policy);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentLRUCache(
            int capacity, Duration cleanupInterval, int numSegments, PolicyType policy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (numSegments <= 0) {
            throw new IllegalArgumentException("numSegments must be positive");
        }
        validateCleanupInterval(cleanupInterval);

        this.totalCapacity = capacity;
        this.numSegments = numSegments;
        this.segments = new CacheSegment[numSegments];

        int base = capacity / numSegments;
        int remainder = capacity % numSegments;
        for (int i = 0; i < numSegments; i++) {
            int segCapacity = Math.max(base + (i < remainder ? 1 : 0), 1);
            this.segments[i] = new CacheSegment<>(segCapacity, createPolicy(policy, segCapacity));
        }

        this.expirationManager =
                new ExpirationManager<>(cleanupInterval, this::removeExpiredEntries);
    }

    public void put(K key, V value) {
        put(key, value, null);
    }

    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        validateTtl(ttl);

        CacheSegment<K, V> segment = segmentFor(key);
        segment.lock.lock();
        try {
            boolean evicted = segment.put(key, value, ttl);
            if (evicted) {
                evictions.incrementAndGet();
            }
        } finally {
            segment.lock.unlock();
        }
    }

    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        requests.incrementAndGet();

        CacheSegment<K, V> segment = segmentFor(key);
        segment.lock.lock();
        try {
            CacheSegment.GetResult<V> result = segment.get(key, System.nanoTime());
            switch (result.status) {
                case HIT -> hits.incrementAndGet();
                case MISS -> misses.incrementAndGet();
                case EXPIRED -> {
                    expiredRemovals.incrementAndGet();
                    misses.incrementAndGet();
                }
            }
            return result.toOptional();
        } finally {
            segment.lock.unlock();
        }
    }

    public V get(K key, Function<? super K, ? extends V> loader) {
        return get(key, loader, null);
    }

    public V get(K key, Function<? super K, ? extends V> loader, Duration ttl) {
        validateTtl(ttl);
        return coordinator.getOrLoad(key, loader, ttl, this::get, (k, v) -> put(k, v, ttl));
    }

    public V remove(K key) {
        Objects.requireNonNull(key, "key");

        CacheSegment<K, V> segment = segmentFor(key);
        segment.lock.lock();
        try {
            return segment.remove(key);
        } finally {
            segment.lock.unlock();
        }
    }

    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");

        CacheSegment<K, V> segment = segmentFor(key);
        segment.lock.lock();
        try {
            return segment.containsKey(key, System.nanoTime());
        } finally {
            segment.lock.unlock();
        }
    }

    public int size() {
        int total = 0;
        for (CacheSegment<K, V> segment : segments) {
            segment.lock.lock();
            try {
                total += segment.size();
            } finally {
                segment.lock.unlock();
            }
        }
        return total;
    }

    public void clear() {
        for (CacheSegment<K, V> segment : segments) {
            segment.lock.lock();
        }
        try {
            for (CacheSegment<K, V> segment : segments) {
                segment.clear();
            }
        } finally {
            for (int i = segments.length - 1; i >= 0; i--) {
                segments[i].lock.unlock();
            }
        }
    }

    public void shutdown() {
        expirationManager.shutdown();
    }

    public long getHitCount() {
        return hits.get();
    }

    public long getMissCount() {
        return misses.get();
    }

    public long getEvictionCount() {
        return evictions.get();
    }

    public long getTotalRequests() {
        return requests.get();
    }

    public double getHitRate() {
        long total = requests.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    public CacheStats getStats() {
        return new CacheStats(
                hits.get(),
                misses.get(),
                evictions.get(),
                expiredRemovals.get(),
                requests.get(),
                getHitRate());
    }

    @Override
    public void close() {
        shutdown();
    }

    void removeExpiredEntries() {
        long nowNanos = System.nanoTime();
        int totalRemoved = 0;
        for (CacheSegment<K, V> segment : segments) {
            segment.lock.lock();
            try {
                totalRemoved += segment.removeExpired(nowNanos);
            } finally {
                segment.lock.unlock();
            }
        }
        if (totalRemoved > 0) {
            expiredRemovals.addAndGet(totalRemoved);
            LOGGER.debug("Removed {} expired cache entries", totalRemoved);
        }
    }

    private CacheSegment<K, V> segmentFor(K key) {
        int index = (key.hashCode() & 0x7FFF_FFFF) % numSegments;
        return segments[index];
    }

    private static <K, V> EvictionPolicy<K, V> createPolicy(PolicyType type, int segCapacity) {
        return switch (type) {
            case LRU -> new LRUPolicy<>();
            case LFU -> new LFUPolicy<>();
            case WINDOW_TINY_LFU -> new WindowTinyLFUPolicy<>(segCapacity);
        };
    }

    private static void validateTtl(Duration ttl) {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    private static void validateCleanupInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("cleanupInterval must be positive");
        }
    }
}
