package cache;

import java.time.Duration;
import java.util.Objects;

final class CacheNode<K, V> {
    private static final long NEVER_EXPIRES = Long.MAX_VALUE;

    final K key;
    V value;
    long expiresAtNanos;
    CacheNode<K, V> previous;
    CacheNode<K, V> next;

    CacheNode(K key, V value, Duration ttl) {
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
        this.expiresAtNanos = expiresAt(ttl);
    }

    boolean isExpired(long nowNanos) {
        return nowNanos >= expiresAtNanos;
    }

    void update(V newValue, Duration ttl) {
        value = Objects.requireNonNull(newValue, "value");
        expiresAtNanos = expiresAt(ttl);
    }

    private long expiresAt(Duration ttl) {
        if (ttl == null) {
            return NEVER_EXPIRES;
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        long now = System.nanoTime();
        long ttlNanos = ttl.toNanos();
        long expiresAt = now + ttlNanos;
        return expiresAt < now ? Long.MAX_VALUE : expiresAt;
    }
}
