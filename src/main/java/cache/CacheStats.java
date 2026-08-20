package cache;

/** Immutable snapshot of cache access and eviction statistics. */
public record CacheStats(
        long hitCount,
        long missCount,
        long evictionCount,
        long expiredRemovalCount,
        long totalRequests,
        double hitRate) {}
