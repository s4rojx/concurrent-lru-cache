package cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

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

    V remove(K key) {
        CacheNode<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        removeNode(node);
        return node.value;
    }

    boolean containsKey(K key, long nowNanos) {
        CacheNode<K, V> node = map.get(key);
        return node != null && !node.isExpired(nowNanos);
    }

    int size() {
        return map.size();
    }

    void clear() {
        map.clear();
        lru.clear();
    }

    int removeExpired(long nowNanos) {
        List<CacheNode<K, V>> expired = new ArrayList<>();
        for (CacheNode<K, V> node : map.values()) {
            if (node.isExpired(nowNanos)) {
                expired.add(node);
            }
        }
        for (CacheNode<K, V> node : expired) {
            // Guard against node replacement during iteration.
            if (map.get(node.key) == node) {
                removeNode(node);
            }
        }
        return expired.size();
    }

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
        // Invariant: node is removed from both the map and the LRU list together.
        map.remove(node.key, node);
        lru.remove(node);
    }

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
