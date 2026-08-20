# Repository Layout

Recorded: Phase 0 — Repository Discovery and Baseline

---

## Top-Level Structure

```text
concurrent-lru/
├── README.md                            — user-facing project description
├── pom.xml                              — Maven build configuration
├── .gitignore
├── benchmark/
│   └── benchmark/                       — hand-rolled benchmark (added via build-helper-maven-plugin)
│       ├── CacheBenchmark.java
│       ├── BenchmarkResult.java
│       └── BenchmarkReportGenerator.java
├── docs/
│   ├── 00_REPO_LAYOUT.md
│   ├── 01_BASELINE.md
│   ├── 02_ARCHITECTURE.md
│   ├── 03_CONCURRENCY.md
│   ├── architecture.md                  — original architecture description
│   ├── benchmark-results.md             — benchmark run output
│   ├── feynman-overview.md
│   └── interview-preparation.md
└── src/
    ├── main/java/cache/
    │   ├── ConcurrentLRUCache.java      — main public class
    │   ├── CacheNode.java               — internal linked-list node
    │   ├── DoublyLinkedList.java        — internal LRU list
    │   ├── CacheStats.java              — value record for metrics snapshot
    │   └── ExpirationManager.java       — scheduled background cleanup
    └── test/java/cache/
        └── ConcurrentLRUCacheTest.java  — 8 JUnit 5 tests
```

---

## Build System

| Property | Value |
|---|---|
| Build tool | Apache Maven 3.9.12 |
| Java version (runtime) | JDK 24.0.2 (Oracle) |
| Java version (compiler target) | 21 (`maven.compiler.release=21`) |
| JUnit | 5.10.3 |
| Logging | SLF4J 2.0.13 + Logback 1.5.6 |
| Coverage | JaCoCo 0.8.15 |
| Formatting | Spotless 2.43.0 (Google Java Format 1.22.0, AOSP style) |
| Benchmark source | Added via `build-helper-maven-plugin` from `benchmark/` directory |

### Key Maven Goals

| Command | Purpose |
|---|---|
| `mvn test` | Compile and run all tests |
| `mvn jacoco:report` | Generate HTML coverage report under `target/site/jacoco` |
| `mvn spotless:check` | Verify code formatting |
| `mvn spotless:apply` | Auto-fix formatting |
| `mvn -DskipTests compile exec:java` | Run benchmark via `benchmark.CacheBenchmark.main()` |

---

## Package Structure

All production classes live in `cache.*`. There is no sub-package separation as of Phase 0.

| Class | Visibility | Purpose |
|---|---|---|
| `ConcurrentLRUCache<K,V>` | `public final` | Main API — put, get, remove, containsKey, size, clear, stats, shutdown |
| `CacheNode<K,V>` | `final` (package) | Internal doubly linked list node; carries key, value, TTL |
| `DoublyLinkedList<K,V>` | `final` (package) | Mutable doubly linked list used for LRU ordering |
| `CacheStats` | `public record` | Immutable snapshot of hits, misses, evictions, expiredRemovals, requests, hitRate |
| `ExpirationManager<K,V>` | `public final` | Owns the `ScheduledExecutorService`; calls a `Runnable` on a fixed delay |
