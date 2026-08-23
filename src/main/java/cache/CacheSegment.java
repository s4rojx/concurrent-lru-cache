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
    private final EvictionPolicy<K, V> policy;

    private long hits;
    private long misses;
    private long evictions;
    private long expiredRemovals;
    private long requests;

    CacheSegment(int capacity, EvictionPolicy<K, V> policy) {
        this.capacity = capacity;
        this.map = new HashMap<>(Math.max(capacity * 2, 16));
        this.policy = policy;
    }

    GetResult<V> get(K key, long nowNanos) {
        requests++;
        CacheNode<K, V> node = map.get(key);
        if (node == null) {
            misses++;
            return GetResult.miss();
        }
        if (node.isExpired(nowNanos)) {
            removeNode(node);
            misses++;
            expiredRemovals++;
            return GetResult.expired();
        }
        policy.onAccess(node);
        hits++;
        return GetResult.hit(node.value);
    }

    GetResult<V> getQuietly(K key, long nowNanos) {
        CacheNode<K, V> node = map.get(key);
        if (node == null) {
            return GetResult.miss();
        }
        if (node.isExpired(nowNanos)) {
            removeNode(node);
            expiredRemovals++;
            return GetResult.expired();
        }
        policy.onAccess(node);
        return GetResult.hit(node.value);
    }

    boolean put(K key, V value, Duration ttl) {
        CacheNode<K, V> existing = map.get(key);
        if (existing != null) {
            existing.update(value, ttl);
            policy.onAccess(existing);
            return false;
        }
        CacheNode<K, V> node = new CacheNode<>(key, value, ttl);
        map.put(key, node);
        policy.onInsert(node);
        boolean evicted = evictIfOverCapacity();
        if (evicted) {
            evictions++;
        }
        return evicted;
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
        policy.clear();
    }

    int removeExpired(long nowNanos) {
        List<CacheNode<K, V>> expired = new ArrayList<>();
        for (CacheNode<K, V> node : map.values()) {
            if (node.isExpired(nowNanos)) {
                expired.add(node);
            }
        }
        int removed = 0;
        for (CacheNode<K, V> node : expired) {
            if (map.get(node.key) == node) {
                removeNode(node);
                removed++;
            }
        }
        expiredRemovals += removed;
        return removed;
    }

    SegmentStats getStats(int segmentIndex) {
        double hitRate = requests == 0 ? 0.0 : (double) hits / requests;
        return new SegmentStats(
                segmentIndex,
                map.size(),
                capacity,
                hits,
                misses,
                evictions,
                expiredRemovals,
                requests,
                hitRate);
    }

    PolicyStats getPolicyStats() {
        return policy.getStats();
    }

    private boolean evictIfOverCapacity() {
        if (map.size() <= capacity) {
            return false;
        }
        if (policy instanceof WindowTinyLFUPolicy<K, V> wtlfu && wtlfu.isWindowOverCapacity()) {
            CacheNode<K, V> windowVictim = policy.evictionCandidate();
            if (windowVictim != null) {
                map.remove(windowVictim.key, windowVictim);
                if (!windowVictim.isExpired(System.nanoTime())) {
                    wtlfu.promoteWindowVictimToMain(windowVictim);
                } else {
                    wtlfu.discardWindowVictim(windowVictim);
                }
                if (map.size() <= capacity) {
                    return true;
                }
            }
        }
        CacheNode<K, V> victim = policy.evictionCandidate();
        if (victim != null) {
            map.remove(victim.key, victim);
            policy.onRemove(victim);
        }
        return true;
    }

    private void removeNode(CacheNode<K, V> node) {
        map.remove(node.key, node);
        policy.onRemove(node);
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
