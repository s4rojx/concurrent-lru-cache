# 08 — Observability

## Table of Contents

1. [Why Observability Matters for a Cache](#1-why-observability-matters-for-a-cache)
2. [Metrics Architecture Overview](#2-metrics-architecture-overview)
3. [Global Cache Metrics](#3-global-cache-metrics)
4. [Per-Segment Metrics](#4-per-segment-metrics)
5. [Eviction Policy Telemetry](#5-eviction-policy-telemetry)
6. [Single-Flight Coalescing Metrics](#6-single-flight-coalescing-metrics)
7. [Micrometer Integration](#7-micrometer-integration)
8. [Design Decisions](#8-design-decisions)
9. [API Reference](#9-api-reference)
10. [Test Coverage](#10-test-coverage)

---

## 1. Why Observability Matters for a Cache

A cache without metrics is a black box. Without observability you cannot answer:

- Is the cache actually being hit? What is the real hit rate?
- Is a single hot segment absorbing all load while others are idle?
- Are expired entries being cleaned up, or are they silently inflating memory?
- Did the Window-TinyLFU admission gate reject any scan-pollution entries?
- Are concurrent cache misses causing a stampede, and is single-flight coalescing preventing redundant backend calls?
- Has the application configured capacity correctly relative to its working set?

These questions are not answerable with only the raw `put/get/remove` API. A cache with deep observability becomes a tool for tuning, debugging, and capacity planning — not just a data structure.

The design goal of this observability layer is:

> **Expose every meaningful internal event as a named, typed counter or gauge — at the granularity necessary to diagnose problems — without adding mandatory runtime dependencies or locking overhead beyond what is already paid.**

---

## 2. Metrics Architecture Overview

```
ConcurrentLRUCache
        │
        ├── AtomicLong: hits, misses, evictions, expiredRemovals, requests
        │
        ├── CacheSegment[0..N-1]
        │       ├── long: hits, misses, evictions, expiredRemovals, requests   (plain longs, read under segment lock)
        │       └── EvictionPolicy.getStats() ──► PolicyStats
        │
        └── SingleFlightCoordinator
                └── AtomicLong: loadAttempts, loadSuccessCount,
                                loadFailureCount, coalescedLoadCount,
                                totalLoadTimeNanos
```

Metrics live at three levels:

| Level | Who owns it | Thread safety |
|---|---|---|
| Global | `ConcurrentLRUCache` (`AtomicLong`) | Lock-free atomic |
| Per-segment | `CacheSegment` (plain `long`) | Read/written under segment `ReentrantLock` |
| Single-flight | `SingleFlightCoordinator` (`AtomicLong`) | Lock-free atomic |

All three levels are aggregated into the immutable `CacheStats` record returned by `getStats()`.

---

## 3. Global Cache Metrics

Accessed via `ConcurrentLRUCache`:

```java
cache.getHitCount()        // total get() calls that returned a value
cache.getMissCount()       // total get() calls that returned empty (including expired)
cache.getEvictionCount()   // total capacity-eviction events across all segments
cache.getTotalRequests()   // total get() calls issued (hits + misses)
cache.getHitRate()         // hits / totalRequests, or 0.0 if no requests yet
```

Or as a single snapshot:

```java
CacheStats stats = cache.getStats();

stats.hitCount()             // long
stats.missCount()            // long
stats.evictionCount()        // long
stats.expiredRemovalCount()  // total expired entries removed (lazy + scheduled)
stats.totalRequests()        // long
stats.hitRate()              // double in [0.0, 1.0]
stats.missRate()             // 1 - hitRate (derived)
```

### Invariant

At all times after measurement:

```
totalRequests == hitCount + missCount
hitRate == hitCount / totalRequests  (when totalRequests > 0)
```

This invariant is validated by `metricsConsistencyUnderConcurrency` under 16-thread concurrent access.

### Interpretation guide

| Hit rate | Meaning |
|---|---|
| > 90% | Cache is well-sized for the workload. |
| 70–90% | Typical healthy cache. May improve with larger capacity or better eviction policy. |
| 50–70% | Underperforming. Consider capacity increase, key access pattern analysis, or policy switch. |
| < 50% | Cache may be too small, workload may be too uniform, or capacity is dominated by scan traffic. |

---

## 4. Per-Segment Metrics

### Problem: aggregate metrics hide hot-segment imbalance

With 16 segments, all keys hash deterministically to a single segment. If the application's key distribution is skewed — or if a custom key type has a poor `hashCode()` — one segment may absorb 80% of traffic while 15 others sit idle.

An aggregate hit rate of 75% could mask one segment at 10% and 15 segments at 80%.

### How it works

Every `CacheSegment` maintains its own plain-`long` counters:

```java
private long hits;
private long misses;
private long evictions;
private long expiredRemovals;
private long requests;
```

These are plain longs (not atomic) because all reads and writes happen while the segment's `ReentrantLock` is already held — there is no additional overhead beyond the existing segment lock acquisition.

### Accessing per-segment data

```java
// All segments at once
List<SegmentStats> allStats = cache.getSegmentStats();

// Single segment
SegmentStats seg = cache.getSegmentStats(0);

System.out.printf(
    "Segment %d: size=%d/%d hits=%d misses=%d hitRate=%.1f%%%n",
    seg.segmentIndex(),
    seg.size(),
    seg.capacity(),
    seg.hitCount(),
    seg.missCount(),
    seg.hitRate() * 100
);
```

### `SegmentStats` record fields

| Field | Type | Description |
|---|---|---|
| `segmentIndex` | `int` | Segment index (0 to N-1) |
| `size` | `int` | Current number of entries in this segment |
| `capacity` | `int` | Maximum entries for this segment |
| `hitCount` | `long` | Successful `get()` calls in this segment |
| `missCount` | `long` | Missed `get()` calls (including expired) |
| `evictionCount` | `long` | Capacity evictions from this segment |
| `expiredRemovalCount` | `long` | TTL expirations removed from this segment |
| `totalRequests` | `long` | `hitCount + missCount` for this segment |
| `hitRate` | `double` | `hitCount / totalRequests`, or 0.0 if no requests |

### Diagnosing hot-segment imbalance

```java
List<SegmentStats> stats = cache.getSegmentStats();
long maxRequests = stats.stream().mapToLong(SegmentStats::totalRequests).max().orElse(0);
long minRequests = stats.stream().mapToLong(SegmentStats::totalRequests).min().orElse(0);
double imbalanceRatio = maxRequests == 0 ? 1.0 : (double) minRequests / maxRequests;

if (imbalanceRatio < 0.3) {
    // Warning: significant hot-segment skew detected.
    // Check key hashCode() distribution.
}
```

---

## 5. Eviction Policy Telemetry

### Overview

LRU and LFU eviction are transparent — they always evict the least recently or least frequently used entry without an admission decision. There is nothing to instrument beyond eviction count.

**Window-TinyLFU is different.** It contains an active admission filter: when an item is promoted from the window queue to the main (probation) queue, it competes against the current probation tail. If the window victim's estimated frequency is lower than the probation tail's, it is **rejected** and discarded without entering the main cache.

This admission gate is the mechanism that protects against scan pollution — but only if it is actually firing. Policy telemetry makes that visible.

### `PolicyStats` record fields

| Field | Type | Description |
|---|---|---|
| `policyName` | `String` | `"LRU"`, `"LFU"`, or `"WINDOW_TINY_LFU"` |
| `admissions` | `long` | Items admitted from window queue to probation queue |
| `rejections` | `long` | Items rejected by Count-Min Sketch admission gate |
| `rejectionRate` | `double` | `rejections / (admissions + rejections)`, or 0.0 |

### Accessing policy stats

```java
PolicyStats ps = cache.getPolicyStats();

System.out.printf(
    "Policy: %s | Admissions: %d | Rejections: %d | Rejection Rate: %.1f%%%n",
    ps.policyName(),
    ps.admissions(),
    ps.rejections(),
    ps.rejectionRate() * 100
);
```

### Interpretation

| Rejection rate | Interpretation |
|---|---|
| 0% | No scan pressure detected, or window never overflowed. Cache may be oversized or workload is scan-free. |
| 5–30% | Normal. The admission gate is filtering light scan traffic. |
| > 50% | High scan pressure. Window-TinyLFU is actively protecting the main segment from being overwritten. |

> [!NOTE]
> LRU and LFU always return `PolicyStats.empty()` (`policyName = "NONE"`, all zeros) because they have no admission gate. Only `WINDOW_TINY_LFU` populates non-zero policy stats.

---

## 6. Single-Flight Coalescing Metrics

### The problem being measured

When many threads concurrently request the same missing key, a naive cache triggers one backend load per thread — a **cache stampede**. Single-flight coalescing ensures exactly one loader executes while all concurrent callers await the shared result.

The coalescing metrics quantify how effective this protection is.

### Metrics

| Metric | Type | Description |
|---|---|---|
| `loadAttempts` | `long` | Number of leader loader executions initiated (the one thread that won the race to load) |
| `loadSuccessCount` | `long` | Leader loads that completed successfully |
| `loadFailureCount` | `long` | Leader loads that threw an exception or returned null |
| `coalescedLoadCount` | `long` | Follower requests that joined an in-flight load instead of starting their own |
| `totalLoadTimeNanos` | `long` | Cumulative wall-clock nanoseconds spent inside leader loader functions |
| `averageLoadLatencyNanos` | `double` | `totalLoadTimeNanos / loadAttempts` |
| `coalescingRatio()` | `double` | `coalescedLoads / (loadAttempts + coalescedLoads)` — fraction of load requests that were coalesced |

### Accessing coalescing metrics

```java
CacheStats stats = cache.getStats();

System.out.printf(
    "Load attempts: %d | Successes: %d | Failures: %d%n",
    stats.loadAttempts(),
    stats.loadSuccessCount(),
    stats.loadFailureCount()
);
System.out.printf(
    "Coalesced: %d | Coalescing ratio: %.1f%% | Avg load latency: %.2f ms%n",
    stats.coalescedLoadCount(),
    stats.coalescingRatio() * 100,
    stats.averageLoadLatencyNanos() / 1_000_000.0
);
```

### Concrete example

20 threads concurrently miss the same key. Single-flight fires once:

```
loadAttempts        = 1     (one leader called the database)
loadSuccessCount    = 1
loadFailureCount    = 0
coalescedLoadCount  = 19    (19 followers waited for the leader's result)
coalescingRatio     = 19/20 = 95%
```

Without single-flight, all 20 threads would have called the backend — 20x the load at the source.

### Interpreting coalescing ratio

| Ratio | Interpretation |
|---|---|
| 0% | No concurrent misses occurred, or only one thread ever calls `get(key, loader)`. Stampede protection is in place but hasn't needed to fire. |
| 40–80% | Moderate stampede pressure. Single-flight is actively preventing redundant backend calls. |
| > 80% | High concurrent miss traffic on the same keys. The coalescing benefit is significant. |

> [!IMPORTANT]
> `loadFailureCount > 0` means a backend loader threw an exception or returned null. The failed load is removed from the in-flight map, so the next caller can retry. This is **intentional** — failed loads are never cached, so subsequent requests are always retried.

---

## 7. Micrometer Integration

### Rationale

[Micrometer](https://micrometer.io) is the standard metrics facade for Java applications. It decouples metric definition from metric export, allowing the same metrics to be shipped to Prometheus, Datadog, CloudWatch, InfluxDB, or any other monitoring system by swapping the `MeterRegistry` implementation — with zero changes to the cache or application code.

### Dependency policy

`micrometer-core` is declared as `<optional>true</optional>` in `pom.xml`:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <version>1.12.5</version>
    <optional>true</optional>
</dependency>
```

This means:
- Applications that use Micrometer automatically get the `ConcurrentLRUCacheMetrics` binder without any extra configuration.
- Applications that do **not** use Micrometer incur **zero transitive dependency** on Micrometer at runtime.
- The core cache library (`ConcurrentLRUCache`) has no compile-time or runtime dependency on Micrometer — only `ConcurrentLRUCacheMetrics` does.

### Exported meters

| Meter name | Type | Description |
|---|---|---|
| `cache.size` | `Gauge` | Current number of entries in the cache |
| `cache.requests` | `FunctionCounter` | Total `get()` calls issued |
| `cache.hits` | `FunctionCounter` | Total cache hits |
| `cache.misses` | `FunctionCounter` | Total cache misses (including expired) |
| `cache.evictions` | `FunctionCounter` | Total capacity evictions |
| `cache.expirations` | `FunctionCounter` | Total TTL-expired entries removed |
| `cache.loads.attempts` | `FunctionCounter` | Total single-flight leader load executions |
| `cache.loads.coalesced` | `FunctionCounter` | Total follower requests coalesced into a single load |

All meters are tagged with `cache=<cacheName>` plus any additional user-supplied tags.

### Usage

```java
// Register with Spring Boot's auto-configured registry
ConcurrentLRUCacheMetrics.monitor(registry, cache, "my-cache");

// With additional custom tags
ConcurrentLRUCacheMetrics.monitor(registry, cache, "my-cache", "env", "production", "region", "us-east-1");

// As a MeterBinder (for manual binding)
new ConcurrentLRUCacheMetrics(cache, "my-cache", Tags.of("env", "production"))
        .bindTo(registry);
```

### Prometheus scrape example

Once registered, Prometheus will see:

```
cache_size{cache="my-cache",env="production"} 8432.0
cache_requests_total{cache="my-cache",env="production"} 1250000.0
cache_hits_total{cache="my-cache",env="production"} 987500.0
cache_misses_total{cache="my-cache",env="production"} 262500.0
cache_evictions_total{cache="my-cache",env="production"} 43200.0
cache_expirations_total{cache="my-cache",env="production"} 12100.0
cache_loads_attempts_total{cache="my-cache",env="production"} 262500.0
cache_loads_coalesced_total{cache="my-cache",env="production"} 87431.0
```

### Why `FunctionCounter` instead of `Counter`

Micrometer's `FunctionCounter` binds to an existing monotonic value (the cache's internal `AtomicLong`). This means:

- No double-counting — the Micrometer binding reads the same counter the cache itself increments.
- No synchronization overhead — `FunctionCounter.count()` just reads the `AtomicLong`.
- The cache is not required to know about Micrometer during normal operation.

### Why `Gauge` for size

`cache.size` is a **point-in-time** snapshot, not a monotonic counter. It goes up and down as entries are added and evicted. A `Gauge` captures this correctly; a `Counter` would only capture increments and would not reflect evictions or clears.

---

## 8. Design Decisions

### Decision 1 — Segment counters are plain longs, not AtomicLongs

Segment counters (`hits`, `misses`, etc. in `CacheSegment`) are plain primitive `long` fields, not `AtomicLong`.

**Reason:** Every read and write to these fields happens while the segment's `ReentrantLock` is already acquired. The lock provides the necessary happens-before guarantee. Using `AtomicLong` inside a lock would pay atomic CAS overhead for zero additional safety benefit.

**Tradeoff:** Reading segment stats with `getSegmentStats()` still requires acquiring each segment's lock sequentially. This is a momentary, per-segment lock acquisition — the same cost as any other per-key operation.

### Decision 2 — Global counters are AtomicLongs

The global `hits`, `misses`, `evictions`, `expiredRemovals`, and `requests` counters in `ConcurrentLRUCache` are `AtomicLong` because they are updated by multiple threads **after** the segment lock is released (e.g., `hits.incrementAndGet()` after `segment.lock.unlock()`).

This provides a fast, non-blocking path for global metric aggregation without holding segment locks for metric updates.

### Decision 3 — No latency histograms inside the cache

The cache does not maintain internal p50/p99 latency histograms. The reasons:

1. **High-frequency hot path.** Every `get()` call would pay histogram bucket update cost.
2. **Memory.** HDR histograms are large; one per segment would significantly increase memory footprint.
3. **Micrometer already provides this.** Applications that need latency distributions can wrap `cache.get()` or `cache.get(key, loader)` in a Micrometer `Timer` at the call site. This keeps histogram granularity under the application's control.

### Decision 4 — `expiredRemovals` counts both lazy and scheduled removals

The `expiredRemovals` counter (both global and per-segment) is incremented by:
- Lazy expiration during `get()` — when an entry is found expired on access.
- Scheduled background sweep — when `ExpirationManager` triggers `removeExpired()` on each segment.

This means `expiredRemovals` tells you the total number of TTL-expired entries that were ever cleaned up, regardless of whether the cleanup was triggered by a user request or the background thread.

### Decision 5 — `getStats()` acquires all segment locks

```java
public CacheStats getStats() {
    List<SegmentStats> segStats = getSegmentStats(); // acquires each segment lock
    PolicyStats polStats = getPolicyStats();          // acquires each segment lock again
    ...
}
```

This means `getStats()` is not free — it acquires 2N segment locks (N = 16 by default) sequentially. This is intentional: it provides a **consistent snapshot** of segment state at the point of the call.

For production monitoring at high frequency (e.g., scraping every second), use the global `AtomicLong` accessors (`getHitCount()`, `getMissCount()`, etc.) which are lock-free, or rely on the Micrometer binder which reads the same atomics.

---

## 9. API Reference

### `ConcurrentLRUCache` — metric methods

```java
long   getHitCount()                     // global hits
long   getMissCount()                    // global misses
long   getEvictionCount()                // global capacity evictions
long   getTotalRequests()                // global get() call count
double getHitRate()                      // hits / totalRequests

List<SegmentStats> getSegmentStats()     // per-segment snapshot (acquires all segment locks)
SegmentStats getSegmentStats(int index)  // single segment snapshot
PolicyStats  getPolicyStats()            // eviction policy admission stats
CacheStats   getStats()                  // full snapshot (global + segments + policy + SF metrics)
```

### `CacheStats` — full snapshot record

```java
long   hitCount()
long   missCount()
long   evictionCount()
long   expiredRemovalCount()
long   totalRequests()
double hitRate()
double missRate()                         // derived: missCount / totalRequests

long   loadAttempts()                     // single-flight leader executions
long   loadSuccessCount()
long   loadFailureCount()
long   coalescedLoadCount()               // follower requests piggy-backed on leader
long   totalLoadTimeNanos()
double averageLoadLatencyNanos()
double coalescingRatio()                  // coalescedLoads / (attempts + coalesced)

List<SegmentStats> segmentStats()
PolicyStats        policyStats()
String             formattedSummary()     // human-readable multi-line string
```

### `ConcurrentLRUCacheMetrics` — Micrometer binder

```java
// Static factory (simplest usage)
ConcurrentLRUCacheMetrics.monitor(MeterRegistry registry, ConcurrentLRUCache<?,?> cache, String cacheName, String... tags)

// Constructor for manual binding
new ConcurrentLRUCacheMetrics(ConcurrentLRUCache<?,?> cache, String cacheName, Iterable<Tag> tags)
        .bindTo(registry);
```

---

## 10. Test Coverage

Observability is validated by `ObservabilityTest` (5 tests):

| Test | What it verifies |
|---|---|
| `perSegmentMetricsReflectRealLoadDistribution` | Forces key hash skew to segments 0 and 1 using a custom `SkewedKey`. Validates that each segment's `hitCount`, `missCount`, `totalRequests`, and `hitRate` match exactly the operations issued to that segment. |
| `singleFlightCoalescingMetricsCountAvoidedLoads` | 20 threads concurrently miss the same key. Verifies `loadAttempts=1`, `coalescedLoadCount=19`, `coalescingRatio=0.95`. |
| `singleFlightLoadFailureMetricsTrackErrors` | Loader throws `IllegalStateException`. Verifies `loadAttempts=1`, `loadSuccessCount=0`, `loadFailureCount=1`. |
| `windowTinyLFUPolicyMetricsReflectAdmissionDecisions` | Inserts 50 entries into a capacity-10 cache with `WINDOW_TINY_LFU`. Verifies that `PolicyStats` is populated with valid `admissions + rejections` and that `rejectionRate` is in `[0.0, 1.0]`. |
| `micrometerBinderExportsAllMeters` | Binds to a `SimpleMeterRegistry`. Verifies all 8 meters are registered and report correct values after a defined sequence of `put`, `get`, and `get(key, loader)` calls. |
