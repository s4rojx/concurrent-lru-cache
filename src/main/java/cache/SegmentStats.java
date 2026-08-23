package cache;

public record SegmentStats(
        int segmentIndex,
        int size,
        int capacity,
        long hitCount,
        long missCount,
        long evictionCount,
        long expiredRemovalCount,
        long totalRequests,
        double hitRate) {}
