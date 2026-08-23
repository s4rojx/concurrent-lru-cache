package cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;

public final class ConcurrentLRUCacheMetrics implements MeterBinder {

    private final ConcurrentLRUCache<?, ?> cache;
    private final String cacheName;
    private final Iterable<Tag> tags;

    public static void monitor(
            MeterRegistry registry,
            ConcurrentLRUCache<?, ?> cache,
            String cacheName,
            String... tags) {
        new ConcurrentLRUCacheMetrics(cache, cacheName, Tags.of(tags)).bindTo(registry);
    }

    public ConcurrentLRUCacheMetrics(
            ConcurrentLRUCache<?, ?> cache, String cacheName, Iterable<Tag> tags) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.cacheName = Objects.requireNonNull(cacheName, "cacheName");
        this.tags = tags != null ? tags : Tags.empty();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Iterable<Tag> cacheTags = Tags.concat(tags, "cache", cacheName);

        Gauge.builder("cache.size", cache, ConcurrentLRUCache::size)
                .tags(cacheTags)
                .description("The number of entries currently in the cache")
                .register(registry);

        FunctionCounter.builder("cache.requests", cache, c -> c.getStats().totalRequests())
                .tags(cacheTags)
                .description("The total number of cache requests")
                .register(registry);

        FunctionCounter.builder("cache.hits", cache, c -> c.getStats().hitCount())
                .tags(cacheTags)
                .description("The number of cache hits")
                .register(registry);

        FunctionCounter.builder("cache.misses", cache, c -> c.getStats().missCount())
                .tags(cacheTags)
                .description("The number of cache misses")
                .register(registry);

        FunctionCounter.builder("cache.evictions", cache, c -> c.getStats().evictionCount())
                .tags(cacheTags)
                .description("The number of evicted cache entries")
                .register(registry);

        FunctionCounter.builder("cache.expirations", cache, c -> c.getStats().expiredRemovalCount())
                .tags(cacheTags)
                .description("The number of expired entries removed")
                .register(registry);

        FunctionCounter.builder(
                        "cache.loads.coalesced", cache, c -> c.getStats().coalescedLoadCount())
                .tags(cacheTags)
                .description("The number of duplicate loads avoided via single-flight coalescing")
                .register(registry);

        FunctionCounter.builder("cache.loads.attempts", cache, c -> c.getStats().loadAttempts())
                .tags(cacheTags)
                .description("The number of loader invocations attempted")
                .register(registry);
    }
}
