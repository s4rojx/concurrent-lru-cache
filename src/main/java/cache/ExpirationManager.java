package cache;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExpirationManager<K, V> implements AutoCloseable {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ScheduledExecutorService executor;

    ExpirationManager(Duration cleanupInterval, Runnable cleanupTask) {
        Objects.requireNonNull(cleanupInterval, "cleanupInterval");
        Objects.requireNonNull(cleanupTask, "cleanupTask");
        if (cleanupInterval.isZero() || cleanupInterval.isNegative()) {
            throw new IllegalArgumentException("cleanupInterval must be positive");
        }

        executor =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            Thread thread =
                                    new Thread(
                                            task,
                                            "cache-expiration-" + THREAD_COUNTER.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });
        long intervalNanos = cleanupInterval.toNanos();
        executor.scheduleWithFixedDelay(
                cleanupTask, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void close() {
        shutdown();
    }
}
