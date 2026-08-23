package cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class SingleFlightCoordinator<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    V getOrLoad(
            K key,
            Function<? super K, ? extends V> loader,
            Duration ttl,
            Function<K, Optional<V>> cacheReader,
            BiConsumer<K, V> cacheWriter) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        Optional<V> existing = cacheReader.apply(key);
        if (existing.isPresent()) {
            return existing.get();
        }

        CompletableFuture<V> promise = new CompletableFuture<>();
        CompletableFuture<V> active = inFlight.putIfAbsent(key, promise);

        if (active == null) {
            try {
                Optional<V> recheck = cacheReader.apply(key);
                if (recheck.isPresent()) {
                    V cached = recheck.get();
                    promise.complete(cached);
                    return cached;
                }

                V loaded = loader.apply(key);
                if (loaded == null) {
                    throw new NullPointerException("loader returned null for key: " + key);
                }

                cacheWriter.accept(key, loaded);
                promise.complete(loaded);
                return loaded;
            } catch (Throwable t) {
                promise.completeExceptionally(t);
                throw unwrap(t);
            } finally {
                inFlight.remove(key, promise);
            }
        }

        try {
            return active.join();
        } catch (CompletionException e) {
            throw unwrap(e.getCause() != null ? e.getCause() : e);
        }
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
