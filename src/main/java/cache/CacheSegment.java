package cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One shard of the segmented cache.
 *
 * <p>Each segment owns:
 *
 * <ul>
 *   <li>A plain {@link HashMap} for key → node lookup (protected by {@link #lock}).
 *   <li>A {@link DoublyLinkedList} for LRU ordering (protected by {@link #lock}).
 *   <li>A {@link ReentrantLock} that guards all structural mutations.
 *   <li>A local capacity (total cache capacity / number of segments).
 * </ul>
 *
 * <p>All public methods on this class must be called while holding {@link #lock}. The caller is
 * responsible for lock acquisition and release.
 *
 * <p><strong>LRU ordering guarantee:</strong> ordering is per-segment, not globally exact. See
 * {@code docs/03_CONCURRENCY.md} for the full tradeoff discussion.
 */
final class CacheSegment<K, V> {

    final ReentrantLock lock = new ReentrantLock();

    private final int capacity;
    private final Map<K, CacheNode<K, V>> map;
    private final DoublyLinkedList<K, V> lru;

    CacheSegment(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>(Math.max(capacity * 2, 16));
        this.lru = new DoublyLinkedList<>();
    }

    /**
     * Returns the value for {@code key} if present and not expired, moves it to the front of the
     * local LRU list, and returns a result record indicating whether it was a hit, miss, or
     * expired-removal. Caller must hold {@link #lock}.
     */
    GetResult<V> get(K key, long nowNanos) {
        CacheNode<K, V> node = map.get(key);
        if (node == null) {
            return GetResult.miss();
        }
        if (node.isExpired(nowNanos)) {
            removeNode(node);
            return GetResult.expired();
        }
        lru.moveToFront(node);
        return GetResult.hit(node.value);
    }

    /**
     * Inserts or updates {@code key} → {@code value} with optional {@code ttl}. Evicts the local
     * LRU tail if the segment exceeds its capacity. Returns {@code true} if an eviction occurred.
     * Caller must hold {@link #lock}.
     */
    boolean put(K key, V value, Duration ttl) {
        CacheNode<K, V> existing = map.get(key);
        if (existing != null) {
            existing.update(value, ttl);
            lru.moveToFront(existing);
            return false;
        }
        CacheNode<K, V> node = new CacheNode<>(key, value, ttl);
        map.put(key, node);
        lru.addToFront(node);
        return evictIfOverCapacity();
    }

    /**
     * Removes {@code key} from the segment. Returns the removed value, or {@code null} if the key
     * was not present. Caller must hold {@link #lock}.
     */
    V remove(K key) {
        CacheNode<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        removeNode(node);
        return node.value;
    }

    /**
     * Returns {@code true} if {@code key} is present and not expired. Does NOT update recency —
     * preserves the original {@code containsKey} semantics. Caller must hold {@link #lock}.
     */
    boolean containsKey(K key, long nowNanos) {
        CacheNode<K, V> node = map.get(key);
        return node != null && !node.isExpired(nowNanos);
    }

    /** Returns the number of entries in this segment. Caller must hold {@link #lock}. */
    int size() {
        return map.size();
    }

    /** Clears all entries in this segment. Caller must hold {@link #lock}. */
    void clear() {
        map.clear();
        lru.clear();
    }

    /**
     * Removes all expired entries from this segment. Returns the count of entries removed. Caller
     * must hold {@link #lock}.
     */
    int removeExpired(long nowNanos) {
        List<CacheNode<K, V>> expired = new ArrayList<>();
        for (CacheNode<K, V> node : map.values()) {
            if (node.isExpired(nowNanos)) {
                expired.add(node);
            }
        }
        for (CacheNode<K, V> node : expired) {
            // Guard against the node having been replaced since we collected it.
            if (map.get(node.key) == node) {
                removeNode(node);
            }
        }
        return expired.size();
    }

    // -------------------------------------------------------------------------
    // Private helpers — all called under the segment lock.
    // -------------------------------------------------------------------------

    private boolean evictIfOverCapacity() {
        if (map.size() <= capacity) {
            return false;
        }
        CacheNode<K, V> tail = lru.removeTail();
        if (tail != null) {
            map.remove(tail.key, tail);
        }
        return true;
    }

    private void removeNode(CacheNode<K, V> node) {
        map.remove(node.key, node);
        lru.remove(node);
    }

    // -------------------------------------------------------------------------
    // Result type for get() — avoids multiple return-path boolean flags.
    // -------------------------------------------------------------------------

    enum GetStatus {
        HIT,
        MISS,
        EXPIRED
    }

    static final class GetResult<V> {
        final GetStatus status;
        final V value;

        private GetResult(GetStatus status, V value) {
            this.status = status;
            this.value = value;
        }

        static <V> GetResult<V> hit(V value) {
            return new GetResult<>(GetStatus.HIT, value);
        }

        static <V> GetResult<V> miss() {
            return new GetResult<>(GetStatus.MISS, null);
        }

        static <V> GetResult<V> expired() {
            return new GetResult<>(GetStatus.EXPIRED, null);
        }

        boolean isHit() {
            return status == GetStatus.HIT;
        }

        Optional<V> toOptional() {
            return isHit() ? Optional.of(value) : Optional.empty();
        }
    }
}
