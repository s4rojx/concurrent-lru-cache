# Baseline — Phase 0

Recorded: Phase 0 — Repository Discovery and Baseline

---

## Environment

| Property | Value |
|---|---|
| OS | Windows 11 |
| JDK | Oracle JDK 24.0.2 |
| Maven | 3.9.12 |
| Compiler target | Java 21 |

---

## Build Status

**`mvn clean test`** → **BUILD SUCCESS**

All 8 tests passed, 0 failures, 0 errors, 0 skipped.

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

---

## Public API Surface

### `ConcurrentLRUCache<K, V>` (implements `AutoCloseable`)

```java
public ConcurrentLRUCache(int capacity)
public ConcurrentLRUCache(int capacity, Duration cleanupInterval)

public void put(K key, V value)
public void put(K key, V value, Duration ttl)
public Optional<V> get(K key)
public V remove(K key)
public boolean containsKey(K key)
public int size()
public void clear()
public void shutdown()
public void close()                    // delegates to shutdown()

public long getHitCount()
public long getMissCount()
public long getEvictionCount()
public long getTotalRequests()
public double getHitRate()
public CacheStats getStats()

// package-private (used by ExpirationManager callback)
void removeExpiredEntries()
```

### `CacheStats` (Java record)

```java
public record CacheStats(
    long hitCount,
    long missCount,
    long evictionCount,
    long expiredRemovalCount,
    long totalRequests,
    double hitRate)
```

---

## Concurrency Model (as found)

### Global State

| Field | Type | Purpose |
|---|---|---|
| `entries` | `ConcurrentHashMap<K, CacheNode<K,V>>` | Key → node lookup |
| `recency` | `DoublyLinkedList<K,V>` | LRU ordering (mutable, not thread-safe) |
| `lock` | `ReentrantReadWriteLock` | Guards all structural mutations |
| `hits/misses/evictions/expiredRemovals/requests` | `AtomicLong` | Metrics (lock-free) |

### **Critical Finding — `get()` Takes the Write Lock**

Confirmed from `ConcurrentLRUCache.java` lines 81–101:

```java
public Optional<V> get(K key) {
    Objects.requireNonNull(key, "key");
    requests.incrementAndGet();

    lock.writeLock().lock();     // ← WRITE lock on every get
    try {
        CacheNode<K, V> node = entries.get(key);
        ...
        recency.moveToFront(node);   // ← mutates linked list
        hits.incrementAndGet();
        return Optional.of(node.value);
    } finally {
        lock.writeLock().unlock();
    }
}
```

**Root cause:** `DoublyLinkedList.moveToFront()` mutates pointer fields on `CacheNode` objects. Because the list is not thread-safe, the write lock is required even for successful cache hits.

**Consequence:** All concurrent reads serialize on the single write lock. There is zero read-read parallelism for cache hits. Under high concurrency this becomes the primary throughput bottleneck.

### Lock Usage Summary

| Operation | Lock |
|---|---|
| `put` | Write lock |
| `get` | **Write lock** (due to LRU mutation) |
| `remove` | Write lock |
| `containsKey` | Read lock |
| `size` | Read lock |
| `clear` | Write lock |
| `removeExpiredEntries` (background) | Write lock |

### Note on `containsKey`

`containsKey` uses the read lock and does NOT update recency — it checks expiry but does not move the node to the front. This is a minor semantic inconsistency: a `containsKey` call does not refresh the LRU position of an entry.

---

## TTL Semantics

- TTL is optional per entry. `null` → no expiry.
- Stored as `expiresAtNanos = System.nanoTime() + ttl.toNanos()`.
- Sentinel value `Long.MAX_VALUE` means "never expires".
- Overflow guard: if `now + ttlNanos < now` (nanos overflow), falls back to `Long.MAX_VALUE`.
- **Lazy expiration:** Checked during `get()`. Expired entry is removed and counted as a miss.
- **Scheduled cleanup:** `ExpirationManager` runs `removeExpiredEntries()` on a fixed delay (default 1 minute, configurable per constructor). The cleanup acquires the write lock and scans all entries.
- `containsKey` checks expiry but does NOT remove the expired entry or increment any counter.

---

## Resource Lifecycle

- `ExpirationManager` owns a `ScheduledExecutorService` (single daemon thread, named `cache-expiration-N`).
- `shutdown()` / `close()` calls `executor.shutdownNow()`.
- The executor thread is a daemon thread so it will not prevent JVM exit.
- No `awaitTermination` is called — shutdown is fire-and-forget.

---

## Metrics

| Metric | When incremented |
|---|---|
| `requests` | On every `get()` call |
| `hits` | On a successful, non-expired `get()` |
| `misses` | On a key-not-found or expired `get()` |
| `evictions` | When an entry is removed by `evictOverflow()` during `put()` |
| `expiredRemovals` | When an entry is lazily removed in `get()`, or during background cleanup |

`hitRate = hits / requests`. This can be misleading when `requests = 0` (returns 0.0).

---

## Test Coverage

8 tests in `ConcurrentLRUCacheTest`:

| Test | What it proves |
|---|---|
| `supportsBasicOperations` | put, get, containsKey, size, remove, clear |
| `evictsLeastRecentlyUsedEntry` | LRU eviction ordering |
| `updatingExistingKeyRefreshesRecency` | put on existing key moves to front |
| `expiresEntriesLazilyOnAccess` | TTL lazy eviction during get |
| `scheduledCleanupRemovesExpiredEntries` | Background expiration cleanup |
| `tracksStatistics` | Hits, misses, requests, hitRate, evictions |
| `validatesConstructorAndArguments` | Null/invalid arg rejection |
| `handlesConcurrentMixedWorkload` | 24-thread mixed get/put stress test (does not crash) |

**Gaps in existing tests:**
- No test for concurrent `remove` or `clear` under contention
- No test for TTL under concurrent access
- No invariant test (map size == list size)
- No test that evicted entries are not subsequently returned
- No executor shutdown test
- The concurrency test only checks "it didn't crash" — not correctness invariants

---

## Benchmark Results (Baseline — Measured This Session)

**Methodology:** Hand-rolled benchmark (`CacheBenchmark`). 70% get, 30% put. Key space 10,000. Cache capacity 10,000. Pre-warmed with 5,000 entries. `ThreadLocalRandom` key selection. No JVM warmup. Single run. Measured with `System.nanoTime()`.

**Limitations of this methodology:** No JVM warmup, no JIT stabilization, no forked JVM, no multiple measurement iterations. Results have significant variance. JMH will be introduced in Phase 2.

| Threads | Operations | Throughput (ops/sec) | Avg Latency (ms) | Hit Rate |
|---|---|---|---|---|
| 1 | 5,000 | 355,915 | 0.0016 | 53.19% |
| 10 | 50,000 | 398,915 | 0.0230 | 74.60% |
| 50 | 250,000 | 611,446 | 0.0709 | 93.37% |
| 100 | 500,000 | 683,468 | 0.1299 | 96.63% |
| 200 | 1,000,000 | 875,756 | 0.2127 | 98.35% |

**Observation:** Throughput increases with thread count because the JVM is not CPU-saturated at low thread counts given the short per-operation time. At 200 threads we see average latency climbing to 0.21 ms per operation — a sign of contention on the global write lock. The lock contention will worsen further as thread count grows beyond 200. This is the bottleneck Phase 1 addresses.

---

## Known Architectural Limitations

1. **Global write lock on get()** — all reads serialize, no parallelism.
2. **No eviction policy abstraction** — LRU is hardcoded, not pluggable.
3. **No single-flight cache loading** — cache stampede is possible.
4. **Coarse metrics** — no latency percentiles.
5. **`containsKey` does not update recency** — semantic gap.
6. **No observability / structured metrics** — only raw counters.
7. **Benchmark methodology is weak** — no JMH, no warmup, no forks.
