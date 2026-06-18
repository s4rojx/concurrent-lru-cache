package benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Locale;

public final class BenchmarkReportGenerator {
    private BenchmarkReportGenerator() {}

    static String render(BenchmarkResult result) {
        NumberFormat integer = NumberFormat.getIntegerInstance(Locale.US);
        return """
                # Benchmark Results

                | Metric | Value |
                | --- | ---: |
                | Threads | %s |
                | Operations | %s |
                | Throughput | %s ops/sec |
                | Average Latency | %.4f ms |
                | Hit Rate | %.2f%% |
                | Memory Usage | %.2f MB |
                """
                .formatted(
                        integer.format(result.threads()),
                        integer.format(result.operations()),
                        integer.format(result.throughputOpsPerSecond()),
                        result.averageLatencyMillis(),
                        result.hitRate() * 100.0,
                        result.memoryUsageMegabytes());
    }

    static void write(Path reportPath, BenchmarkResult result) throws IOException {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, render(result), StandardCharsets.UTF_8);
    }
}
