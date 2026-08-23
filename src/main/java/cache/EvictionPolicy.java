package cache;

interface EvictionPolicy<K, V> {

    void onInsert(CacheNode<K, V> node);

    void onAccess(CacheNode<K, V> node);

    void onRemove(CacheNode<K, V> node);

    CacheNode<K, V> evictionCandidate();

    void clear();

    default PolicyStats getStats() {
        return PolicyStats.empty();
    }
}
