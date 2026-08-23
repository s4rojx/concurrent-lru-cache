package cache;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * O(1) LFU eviction using a doubly-linked list of frequency buckets, each holding the set of keys
 * at that frequency level. On tie, the least-recently-inserted key at the minimum frequency is
 * chosen as the eviction candidate (FIFO within each bucket via LinkedHashSet).
 *
 * <p>Memory overhead: O(n) additional space — one frequency counter and one bucket membership
 * reference per cached entry.
 *
 * <p>No frequency decay is applied. Under a shifting workload, old high-frequency keys can become
 * "stuck" at high counts and resist eviction even after they go cold. This is the known trade-off
 * of pure LFU vs. Window-TinyLFU, which handles it via the window region.
 */
final class LFUPolicy<K, V> implements EvictionPolicy<K, V> {

    private final Map<K, CacheNode<K, V>> nodeMap = new HashMap<>();
    private final Map<K, Integer> freqMap = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<>();
    private int minFreq = 0;

    @Override
    public void onInsert(CacheNode<K, V> node) {
        nodeMap.put(node.key, node);
        freqMap.put(node.key, 1);
        buckets.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(node.key);
        minFreq = 1;
    }

    @Override
    public void onAccess(CacheNode<K, V> node) {
        K key = node.key;
        int freq = freqMap.getOrDefault(key, 0);
        if (freq == 0) {
            return;
        }
        LinkedHashSet<K> bucket = buckets.get(freq);
        if (bucket != null) {
            bucket.remove(key);
            if (bucket.isEmpty()) {
                buckets.remove(freq);
                if (minFreq == freq) {
                    minFreq = freq + 1;
                }
            }
        }
        int newFreq = freq + 1;
        freqMap.put(key, newFreq);
        buckets.computeIfAbsent(newFreq, k -> new LinkedHashSet<>()).add(key);
    }

    @Override
    public void onRemove(CacheNode<K, V> node) {
        K key = node.key;
        Integer freq = freqMap.remove(key);
        nodeMap.remove(key);
        if (freq != null) {
            LinkedHashSet<K> bucket = buckets.get(freq);
            if (bucket != null) {
                bucket.remove(key);
                if (bucket.isEmpty()) {
                    buckets.remove(freq);
                }
            }
        }
    }

    @Override
    public CacheNode<K, V> evictionCandidate() {
        LinkedHashSet<K> minBucket = buckets.get(minFreq);
        if (minBucket == null || minBucket.isEmpty()) {
            return null;
        }
        K victimKey = minBucket.iterator().next();
        return nodeMap.get(victimKey);
    }

    @Override
    public void clear() {
        nodeMap.clear();
        freqMap.clear();
        buckets.clear();
        minFreq = 0;
    }
}
