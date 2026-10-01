# Phase 4 — TTL and Expiration Hardening

## Overview & Audit Summary

Phase 4 audited and hardened the interaction between per-entry TTL expiration, the background scheduled cleanup (`ExpirationManager`), and the pluggable eviction policies (`LRUPolicy`, `LFUPolicy`, `WindowTinyLFUPolicy`) introduced in Phase 3.

### 1. What Was Audited

1. **Lazy Expiration & Policy Cleanup (`get()` path):**
   - Verified that when an expired entry is encountered during `get()`, `CacheSegment.removeNode()` removes it from both the internal `Map` and the active `EvictionPolicy` (`policy.onRemove(node)`).
   - Confirmed across all three policies (`LRU`, `LFU`, `WINDOW_TINY_LFU`) that lazy expiration leaves no dangling references.

2. **Scheduled Cleanup (`removeExpiredEntries()` via `ExpirationManager`):**
   - Verified that periodic background sweeps acquire segment locks and call `CacheSegment.removeExpired()`, properly triggering `policy.onRemove(node)` for every expired entry.

3. **TTL Updates on Existing Keys:**
   - Verified that calling `put(key, newValue, newTtl)` on an existing key replaces the old TTL unconditionally (both shortening and lengthening expiration).
   - Verified that `put()` on an entry whose TTL has already expired (but not yet lazily removed or swept) updates the node in-place with the fresh TTL and value.

4. **TTL Edge Cases & Bounds:**
   - Verified that `Duration.ZERO` and negative durations are rejected at API boundaries with `IllegalArgumentException`.
   - Verified that a race between `containsKey()` and `get()` (entry expiring between the two calls) safely returns `Optional.empty()` without exceptions or null leaks.

5. **Lifecycle & Shutdown:**
   - Verified that `close()` or `shutdown()` during an active background sweep terminates cleanly without throwing exceptions or leaking daemon threads.

---

## Bugs Found and Fixed

### Bug 1: `removeExpired` Metric Overcounting
- **Location:** `CacheSegment.removeExpired(long nowNanos)`
- **Issue:** The method previously collected expired nodes into a list and returned `expired.size()` unconditionally. If an entry was concurrently replaced or altered, the `map.get(node.key) == node` guard would correctly skip removing it, but the return value still counted it as removed.
- **Fix:** Switched to returning an explicit `removed` counter incremented only when `removeNode(node)` actually executes.

### Bug 2: Window-TinyLFU Expired Window Victim Dangling Reference
- **Location:** `CacheSegment.evictIfOverCapacity()` & `WindowTinyLFUPolicy`
- **Issue:** Under capacity pressure in `WINDOW_TINY_LFU` mode, when the window queue overflowed, the window tail was removed from the map and unconditionally promoted to the main/probation queue via `wtlfu.promoteWindowVictimToMain(windowVictim)`. If this window victim had already expired, it was admitted to the main region policy structures despite being removed from the map, leaving a dangling node in probation.
- **Fix:** Added expiration check in `evictIfOverCapacity()`. If `windowVictim.isExpired()`, it is routed to `wtlfu.discardWindowVictim(windowVictim)` (removing it from window and queue tracking) rather than promoted to probation.

---

## Race Condition & Ordering Decisions

### Expiration Always Wins Over Eviction
When capacity pressure and entry expiration occur simultaneously:
- **During `get(key)`:** Expiration check runs first. If expired, the entry is immediately evicted as expired (miss + expired removal count increment), never updated as an active recency access.
- **During `evictIfOverCapacity()`:** If the eviction candidate is already expired, it is discarded immediately as an expired entry rather than promoted or counted as a policy eviction victim.
- **Why:** Preserves cache correctness invariants: expired entries must never be returned, promoted into protected/probation tiers, or treated as valid hot data.

---

## Verification & Test Suite

All 46 tests across the test suite pass:
- `ConcurrentLRUCacheTest`: 17 tests (basic operations, concurrency stress, shutdown, metrics)
- `EvictionPolicyTest`: 15 tests (eviction order, hit rates on Zipfian/uniform/shift workloads, concurrency)
- `TtlHardeningTest`: 14 tests (lazy/scheduled cleanup on all policies, TTL replacement, race conditions, shutdown safety, metric accuracy)

### Specific Tests in `TtlHardeningTest`
1. `lazyExpiryRemovesFromPolicyForAllPolicies` — validates lazy expiry cleanups across LRU, LFU, and Window-TinyLFU
2. `scheduledCleanupUpdatesAllPolicies` — validates background sweeps across all 3 policies
3. `putOnExistingKeyFullyReplacesOldTtl` — confirms TTL replacement (short to long)
4. `putOnExistingKeyReplacesLongTtlWithShort` — confirms TTL replacement (long to short)
5. `putOnExpiredKeyRefreshesIt` — confirms in-place refresh on expired entries
6. `zeroDurationTtlIsRejectedAtPut` — validates `Duration.ZERO` validation
7. `negativeDurationTtlIsRejectedAtPut` — validates negative duration validation
8. `containsKeyThenGetRaceDoesNotThrowOrReturnStale` — validates inter-call expiration safety
9. `expirationAndEvictionRaceDoesNotCorruptState` — concurrent stress testing expiration + capacity eviction interleaving
10. `windowTinyLfuExpiredWindowVictimDoesNotDangleInPolicy` — regression test for W-TinyLFU expired window victim discard
11. `closeWhileScheduledSweepIsRunningDoesNotThrow` — tests `close()` during active sweep
12. `closeTwiceDoesNotThrow` — idempotent shutdown test
13. `foregroundOpsWorkAfterShutdown` — foreground access remains operational after executor shutdown
14. `expiredRemovalCountAccurateAfterScheduledSweep` — verifies exact count reporting in `CacheStats`

---

## Phase Gate

```
PHASE: 4 — TTL / Expiration Hardening

What changed:
  - Audited TTL expiration interactions across all 3 eviction policies (LRU, LFU, Window-TinyLFU).
  - Fixed removeExpired() return count bug in CacheSegment.
  - Fixed WindowTinyLFU expired window victim promotion bug (added discardWindowVictim()).
  - Created src/test/java/cache/TtlHardeningTest.java with 14 comprehensive tests.
  - Documented expiration invariants and ordering semantics in docs/06_TTL_HARDENING.md.

Why:
  - Ensure expired entries never leave dangling references in any eviction policy.
  - Maintain absolute consistency of eviction policies and cache metrics under concurrent expiration and eviction pressure.

Tests:
  - passed: 46/46 (17 ConcurrentLRUCacheTest + 15 EvictionPolicyTest + 14 TtlHardeningTest)
  - failed: 0
  - skipped: 0

Benchmark:
  - Phase 4 focused strictly on correctness, race-condition safety, and metric precision.
  - No new performance optimization was made; verified that all 46 functional and concurrency stress tests pass cleanly without deadlocks or resource leaks.

Architecture:
  - changed: No breaking changes; internal expiration cleanup hardened.
  - documented: Yes (docs/06_TTL_HARDENING.md).

Compatibility:
  - preserved: Yes (all constructors and public APIs retain full compatibility).

Known limitations:
  - Scheduled cleanup runs periodically (default 1 min); unaccessed expired keys remain in memory until next sweep or get().

Next phase: Phase 5 — Single-Flight Cache Loading (Request Coalescing)
```
