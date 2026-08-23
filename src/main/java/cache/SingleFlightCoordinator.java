package cache;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class SingleFlightCoordinator<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<K>> currentThreadKeys = ThreadLocal.withInitial(HashSet::new);

    private final AtomicLong loadAttempts = new AtomicLong();
    private final AtomicLong loadSuccessCount = new AtomicLong();
    private final AtomicLong loadFailureCount = new AtomicLong();
    private final AtomicLong coalescedLoadCount = new AtomicLong();
    private final AtomicLong totalLoadTimeNanos = new AtomicLong();

    V getOrLoad(
            K key,
            Function<? super K, ? extends V> loader,
            Duration ttl,
            Function<K, Optional<V>> cacheReader,
            Function<K, Optional<V>> cacheRechecker,
            BiConsumer<K, V> cacheWriter) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        if (currentThreadKeys.get().contains(key)) {
            throw new IllegalStateException("Recursive load detected for key: " + key);
        }

        Optional<V> existing = cacheReader.apply(key);
        if (existing.isPresent()) {
            return existing.get();
        }

        CompletableFuture<V> promise = new CompletableFuture<>();
        CompletableFuture<V> active = inFlight.putIfAbsent(key, promise);

        if (active == null) {
            long startNanos = System.nanoTime();
            loadAttempts.incrementAndGet();
            try {
                Optional<V> recheck = cacheRechecker.apply(key);
                if (recheck.isPresent()) {
                    V cached = recheck.get();
                    promise.complete(cached);
                    return cached;
                }

                currentThreadKeys.get().add(key);
                V loaded;
                try {
                    loaded = loader.apply(key);
                } finally {
                    currentThreadKeys.get().remove(key);
                }

                if (loaded == null) {
                    throw new NullPointerException("loader returned null for key: " + key);
                }

                cacheWriter.accept(key, loaded);
                promise.complete(loaded);
                loadSuccessCount.incrementAndGet();
                return loaded;
            } catch (Throwable t) {
                loadFailureCount.incrementAndGet();
                promise.completeExceptionally(t);
                throw unwrap(t);
            } finally {
                totalLoadTimeNanos.addAndGet(System.nanoTime() - startNanos);
                inFlight.remove(key, promise);
            }
        }

        try {
            V result = active.join();
            coalescedLoadCount.incrementAndGet();
            return result;
        } catch (CompletionException e) {
            throw unwrap(e.getCause() != null ? e.getCause() : e);
        }
    }

    long getLoadAttempts() {
        return loadAttempts.get();
    }

    long getLoadSuccessCount() {
        return loadSuccessCount.get();
    }

    long getLoadFailureCount() {
        return loadFailureCount.get();
    }

    long getCoalescedLoadCount() {
        return coalescedLoadCount.get();
    }

    long getTotalLoadTimeNanos() {
        return totalLoadTimeNanos.get();
    }

    double getAverageLoadLatencyNanos() {
        long attempts = loadAttempts.get();
        return attempts == 0 ? 0.0 : (double) totalLoadTimeNanos.get() / attempts;
    }

    private RuntimeException unwrap(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        return new RuntimeException(t);
    }
}
