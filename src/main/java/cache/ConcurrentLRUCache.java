package cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread-safe, capacity-bounded LRU cache with optional per-entry TTL expiration.
 *
 * <h2>Concurrency design (Phase 1)</h2>
 *
 * <p>The cache is divided into {@code numSegments} independent {@link CacheSegment} instances. Each
 * segment owns its own {@link java.util.HashMap}, {@link DoublyLinkedList}, and {@link
 * java.util.concurrent.locks.ReentrantLock}. A key is routed to a segment by:
 *
 * <pre>
 *   segment_index = (key.hashCode() &amp; 0x7FFF_FFFF) % numSegments
 * </pre>
 *
 * <p>Operations on different segments proceed in parallel without any shared lock. Metrics are
 * tracked with {@link AtomicLong} counters and are updated outside the segment lock.
 *
 * <h2>LRU ordering guarantee</h2>
 *
 * <p><strong>Ordering is per-segment, not globally exact.</strong> Each segment independently
 * maintains recency order for its own entries. Eviction is driven by local overflow within a
 * segment, not by global recency across all entries. See {@code docs/03_CONCURRENCY.md} for the
 * full tradeoff discussion.
 *
 * <h2>TTL semantics</h2>
 *
 * <ul>
 *   <li>Lazy expiration: checked on every {@code get()}.
 *   <li>Scheduled cleanup: runs periodically via {@link ExpirationManager}.
 * </ul>
 *
 * @param <K> key type (must not be {@code null})
 * @param <V> value type (must not be {@code null})
 */
public final class ConcurrentLRUCache<K, V> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentLRUCache.class);
    private static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofMinutes(1);

    /** Default number of segments. 16 allows up to 16 threads to operate in parallel. */
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

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public ConcurrentLRUCache(int capacity) {
        this(capacity, DEFAULT_CLEANUP_INTERVAL);
    }

    public ConcurrentLRUCache(int capacity, Duration cleanupInterval) {
        this(capacity, cleanupInterval, DEFAULT_NUM_SEGMENTS);
    }

    /**
     * Creates a segmented LRU cache.
     *
     * @param capacity total maximum number of entries across all segments
     * @param cleanupInterval how often the background expiration sweep runs
     * @param numSegments number of independent shards; more segments = more parallelism
     */
    @SuppressWarnings("unchecked")
    public ConcurrentLRUCache(int capacity, Duration cleanupInterval, int numSegments) {
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

        // Distribute capacity as evenly as possible.
        int base = capacity / numSegments;
        int remainder = capacity % numSegments;
        for (int i = 0; i < numSegments; i++) {
            int segCapacity = base + (i < remainder ? 1 : 0);
            // Each segment must hold at least 1 entry.
            this.segments[i] = new CacheSegment<>(Math.max(segCapacity, 1));
        }

        this.expirationManager =
                new ExpirationManager<>(cleanupInterval, this::removeExpiredEntries);
    }

    // -------------------------------------------------------------------------
    // Public API — unchanged from v1
    // -------------------------------------------------------------------------

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

    /**
     * Returns the current number of entries across all segments. This is a snapshot — individual
     * segment sizes may change concurrently.
     */
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

    /**
     * Clears all entries from all segments. Acquires segment locks in index order to prevent
     * deadlock with any other operation that might hold multiple segment locks.
     */
    public void clear() {
        // Lock all segments in order before clearing any.
        for (CacheSegment<K, V> segment : segments) {
            segment.lock.lock();
        }
        try {
            for (CacheSegment<K, V> segment : segments) {
                segment.clear();
            }
        } finally {
            // Release in reverse order (good practice, though not required for correctness here).
            for (int i = segments.length - 1; i >= 0; i--) {
                segments[i].lock.unlock();
            }
        }
    }

    public void shutdown() {
        expirationManager.shutdown();
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Package-private — used by ExpirationManager callback
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CacheSegment<K, V> segmentFor(K key) {
        int index = (key.hashCode() & 0x7FFF_FFFF) % numSegments;
        return segments[index];
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
