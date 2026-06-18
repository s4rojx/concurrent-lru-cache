package cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConcurrentLRUCache<K, V> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentLRUCache.class);
    private static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private final int capacity;
    private final ConcurrentHashMap<K, CacheNode<K, V>> entries;
    private final DoublyLinkedList<K, V> recency;
    private final ReentrantReadWriteLock lock;
    private final AtomicLong hits;
    private final AtomicLong misses;
    private final AtomicLong evictions;
    private final AtomicLong expiredRemovals;
    private final AtomicLong requests;
    private final ExpirationManager<K, V> expirationManager;

    public ConcurrentLRUCache(int capacity) {
        this(capacity, DEFAULT_CLEANUP_INTERVAL);
    }

    public ConcurrentLRUCache(int capacity, Duration cleanupInterval) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new ConcurrentHashMap<>(capacity);
        this.recency = new DoublyLinkedList<>();
        this.lock = new ReentrantReadWriteLock();
        this.hits = new AtomicLong();
        this.misses = new AtomicLong();
        this.evictions = new AtomicLong();
        this.expiredRemovals = new AtomicLong();
        this.requests = new AtomicLong();
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

        lock.writeLock().lock();
        try {
            CacheNode<K, V> existing = entries.get(key);
            if (existing != null) {
                existing.update(value, ttl);
                recency.moveToFront(existing);
                return;
            }

            CacheNode<K, V> node = new CacheNode<>(key, value, ttl);
            entries.put(key, node);
            recency.addToFront(node);
            evictOverflow();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        requests.incrementAndGet();

        lock.writeLock().lock();
        try {
            CacheNode<K, V> node = entries.get(key);
            if (node == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }

            if (node.isExpired(System.nanoTime())) {
                removeNode(node);
                expiredRemovals.incrementAndGet();
                misses.incrementAndGet();
                return Optional.empty();
            }

            recency.moveToFront(node);
            hits.incrementAndGet();
            return Optional.of(node.value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        lock.writeLock().lock();
        try {
            CacheNode<K, V> node = entries.get(key);
            if (node == null) {
                return null;
            }
            removeNode(node);
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");
        lock.readLock().lock();
        try {
            CacheNode<K, V> node = entries.get(key);
            return node != null && !node.isExpired(System.nanoTime());
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
            recency.clear();
        } finally {
            lock.writeLock().unlock();
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
        lock.writeLock().lock();
        try {
            long now = System.nanoTime();
            List<CacheNode<K, V>> expired = new ArrayList<>();
            for (CacheNode<K, V> node : entries.values()) {
                if (node.isExpired(now)) {
                    expired.add(node);
                }
            }
            for (CacheNode<K, V> node : expired) {
                if (entries.get(node.key) == node) {
                    removeNode(node);
                    expiredRemovals.incrementAndGet();
                }
            }
            if (!expired.isEmpty()) {
                LOGGER.debug("Removed {} expired cache entries", expired.size());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void evictOverflow() {
        while (entries.size() > capacity) {
            CacheNode<K, V> tail = recency.removeTail();
            if (tail == null) {
                return;
            }
            entries.remove(tail.key, tail);
            evictions.incrementAndGet();
        }
    }

    private void removeNode(CacheNode<K, V> node) {
        entries.remove(node.key, node);
        recency.remove(node);
    }

    private void validateTtl(Duration ttl) {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }
}
