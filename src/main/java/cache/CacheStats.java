package cache;

public record CacheStats(
        long hitCount,
        long missCount,
        long evictionCount,
        long expiredRemovalCount,
        long totalRequests,
        double hitRate) {}
