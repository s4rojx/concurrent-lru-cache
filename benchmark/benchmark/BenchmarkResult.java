package benchmark;

record BenchmarkResult(
        int threads,
        long operations,
        long throughputOpsPerSecond,
        double averageLatencyMillis,
        double hitRate,
        double memoryUsageMegabytes) {}
