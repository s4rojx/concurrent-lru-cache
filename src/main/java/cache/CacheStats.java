package cache;

import java.util.List;

public record CacheStats(
        long hitCount,
        long missCount,
        long evictionCount,
        long expiredRemovalCount,
        long totalRequests,
        double hitRate,
        long loadAttempts,
        long loadSuccessCount,
        long loadFailureCount,
        long coalescedLoadCount,
        long totalLoadTimeNanos,
        double averageLoadLatencyNanos,
        List<SegmentStats> segmentStats,
        PolicyStats policyStats) {

    public CacheStats(
            long hitCount,
            long missCount,
            long evictionCount,
            long expiredRemovalCount,
            long totalRequests,
            double hitRate) {
        this(
                hitCount,
                missCount,
                evictionCount,
                expiredRemovalCount,
                totalRequests,
                hitRate,
                0,
                0,
                0,
                0,
                0,
                0.0,
                List.of(),
                PolicyStats.empty());
    }

    public double missRate() {
        return totalRequests == 0 ? 0.0 : (double) missCount / totalRequests;
    }

    public double coalescingRatio() {
        long totalLoads = loadAttempts + coalescedLoadCount;
        return totalLoads == 0 ? 0.0 : (double) coalescedLoadCount / totalLoads;
    }

    public String formattedSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "CacheStats[requests=%d, hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, expired=%d]\n",
                        totalRequests,
                        hitCount,
                        missCount,
                        hitRate * 100,
                        evictionCount,
                        expiredRemovalCount));
        if (loadAttempts > 0 || coalescedLoadCount > 0) {
            sb.append(
                    String.format(
                            "SingleFlight[attempts=%d, success=%d, failures=%d, coalesced=%d, avgLatency=%.2fµs]\n",
                            loadAttempts,
                            loadSuccessCount,
                            loadFailureCount,
                            coalescedLoadCount,
                            averageLoadLatencyNanos / 1000.0));
        }
        if (policyStats != null && !"NONE".equals(policyStats.policyName())) {
            sb.append(
                    String.format(
                            "PolicyStats[policy=%s, admissions=%d, rejections=%d, rejectRate=%.2f%%]\n",
                            policyStats.policyName(),
                            policyStats.admissions(),
                            policyStats.rejections(),
                            policyStats.rejectionRate() * 100));
        }
        return sb.toString();
    }
}
