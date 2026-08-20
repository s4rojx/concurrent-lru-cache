# Concurrent LRU Cache Interview Preparation Guide

This guide is meant to be your final preparation material for explaining this project in an interview. It explains the project from the basics, then moves into the exact code structure, every important class, how each operation works, concurrency decisions, TTL expiration, tests, benchmarks, and likely interview questions.

## 1. One-Minute Project Explanation

This project is a thread-safe in-memory LRU cache built in Java 21.

A cache stores frequently used data in memory so the application can read it quickly instead of recomputing it or fetching it from a slower place like a database or network service. This cache has a fixed capacity. When the cache becomes full, it removes the least recently used entry. It also supports optional TTL, which means an entry can automatically expire after a certain time.

The core design uses two data structures together:

- `ConcurrentHashMap<K, CacheNode<K,V>>` for fast O(1) key lookup.
- A custom `DoublyLinkedList<K,V>` for fast O(1) recency tracking.

The most recently used item is kept at the head of the linked list. The least recently used item is kept at the tail. When a key is accessed or updated, its node moves to the front. When the cache exceeds capacity, the tail node is removed.

Because multiple threads can call the cache at the same time, the project uses `ReentrantReadWriteLock` to protect the map and linked list from becoming inconsistent. Metrics such as hits, misses, evictions, and total requests are stored using `AtomicLong`.

## 2. Problem This Project Solves

Imagine an application repeatedly asks for the same data:

- user profiles
- product details
- session information
- configuration values
- search results
- expensive computation results

Fetching or computing the same data again and again is slow. A cache makes the common path faster.

But a cache has limits:

- Memory is finite.
- Old data may become stale.
- Many threads may access it at the same time.

This project handles those concerns by providing:

- Capacity-based eviction using LRU.
- Time-based expiration using TTL.
- Thread-safe access.
- Runtime metrics to understand performance.
- Tests and benchmarks to prove behavior.

## 3. What LRU Means

LRU means Least Recently Used.

The idea is simple: if the cache is full and a new item must be added, remove the item that has not been used for the longest time.

Example with capacity 3:

```text
put A
put B
put C

Cache recency order:
Most recent -> C, B, A <- Least recent

get A

Cache recency order:
Most recent -> A, C, B <- Least recent

put D

Cache is over capacity, so remove B.

Final cache:
Most recent -> D, A, C <- Least recent
```

In this project:

- The front/head of the linked list means most recently used.
- The back/tail of the linked list means least recently used.
- `get` on an existing key moves that key to the front.
- `put` on an existing key updates the value and moves it to the front.
- `put` on a new key inserts it at the front.
- If capacity is exceeded, the tail is evicted.

## 4. Why Two Data Structures Are Needed

An LRU cache needs two abilities:

1. Find an item by key quickly.
2. Update the usage order quickly.

A hash map is good for fast lookup but does not naturally maintain LRU order.

A linked list is good for reordering nodes but is slow if we need to search by key.

So the project combines them:

```text
HashMap:
key -> node

Doubly linked list:
head <-> node <-> node <-> tail
```

The map points directly to the linked-list node. That means the cache can find the node in O(1), remove it from its current position in O(1), and move it to the front in O(1).

This is the standard design for an efficient LRU cache.

## 5. High-Level Architecture

```text
Client threads
    |
    v
ConcurrentLRUCache public API
    |
    |-- ReentrantReadWriteLock protects shared structure
    |
    |-- ConcurrentHashMap stores key -> node
    |
    |-- DoublyLinkedList stores recency order
    |
    |-- CacheNode stores key, value, expiry time, previous, next
    |
    |-- AtomicLong metrics track hits, misses, evictions, requests
    |
    |-- ExpirationManager runs periodic TTL cleanup
```

The most important invariant is:

```text
Every live cache entry must exist in both the map and the linked list.
```

The map and list must never drift apart. That is why structural updates are done while holding the same write lock.

## 6. Project Structure

```text
.
|-- README.md
|-- pom.xml
|-- .gitignore
|-- docs/
|   |-- architecture.md
|   |-- benchmark-results.md
|   `-- interview-preparation.md
|-- src/
|   |-- main/java/cache/
|   |   |-- ConcurrentLRUCache.java
|   |   |-- CacheNode.java
|   |   |-- DoublyLinkedList.java
|   |   |-- ExpirationManager.java
|   |   `-- CacheStats.java
|   `-- test/java/cache/
|       `-- ConcurrentLRUCacheTest.java
`-- benchmark/
    `-- benchmark/
        |-- CacheBenchmark.java
        |-- BenchmarkResult.java
        `-- BenchmarkReportGenerator.java
```

## 7. File-by-File Explanation

### README.md

This is the project introduction. It explains:

- What the project is.
- The main features.
- A small usage example.
- The concurrency model.
- Time complexity.
- How to build, test, and benchmark.

In an interview, use the README as your top-level summary. It tells the interviewer that the project is a Java 21 cache library with LRU eviction, TTL expiration, metrics, tests, and benchmark support.

### pom.xml

This is the Maven build file. It defines how the project is compiled, tested, formatted, and benchmarked.

Important parts:

- Java version: `maven.compiler.release` is `21`.
- Testing: JUnit Jupiter is used for unit and concurrency tests.
- Logging: SLF4J API and Logback runtime are included.
- Formatting: Spotless with Google Java Format AOSP style.
- Coverage: JaCoCo generates test coverage reports.
- Benchmark source: `build-helper-maven-plugin` adds the `benchmark` directory as a source root.
- Benchmark execution: `exec-maven-plugin` runs `benchmark.CacheBenchmark`.

Interview explanation:

```text
The build is intentionally simple. Maven compiles the library, JUnit validates behavior, JaCoCo reports coverage, Spotless enforces formatting, and the benchmark package can be run as a small performance runner.
```

### .gitignore

This keeps generated and local-only files out of Git:

- `target/`
- `.class` files
- log files
- IDE metadata such as `.idea/`, `.vscode/`, and `.iml`

This matters because build outputs and editor settings should not be committed.

### docs/architecture.md

This document explains the architecture in a short form. It describes:

- The map plus linked-list design.
- The lock-based concurrency model.
- O(1) operation complexity.
- TTL cleanup behavior.

This guide you are reading expands that document into detailed interview preparation.

### docs/benchmark-results.md

This file stores the latest benchmark report. The current checked content says the benchmark used:

- 200 threads
- 1,000,000 operations
- 3,706,386 operations per second
- 0.0499 ms average latency
- 98.30% hit rate
- 57.40 MB memory usage

How to explain it:

```text
The benchmark is not a replacement for production load testing, but it gives a quick local signal that the cache handles many concurrent operations with high throughput and bounded capacity.
```

### src/main/java/cache/ConcurrentLRUCache.java

This is the main class and the heart of the project. Users interact with this class.

Main responsibilities:

- Store entries.
- Serve `put`, `get`, `remove`, `containsKey`, `size`, and `clear`.
- Enforce capacity.
- Maintain LRU order.
- Handle lazy expiration.
- Start scheduled expiration cleanup.
- Track metrics.
- Shut down background resources.

Important fields:

```java
private final int capacity;
private final ConcurrentHashMap<K, CacheNode<K, V>> entries;
private final DoublyLinkedList<K, V> recency;
private final ReentrantReadWriteLock lock;
private final AtomicLong hits;
private final AtomicLong misses;
private final AtomicLong evictions;
private final AtomicLong expiredRemovals;
private final AtomicLong requests;
private final ExpirationManager<K, V> expirationManager;
```

Simple meaning:

- `capacity`: maximum number of entries.
- `entries`: fast key lookup.
- `recency`: tracks most-recent to least-recent order.
- `lock`: protects the map and linked list.
- `hits`: successful `get` calls.
- `misses`: failed `get` calls.
- `evictions`: number of entries removed because capacity was exceeded.
- `expiredRemovals`: number of entries removed because TTL expired.
- `requests`: total number of `get` calls.
- `expirationManager`: background scheduled cleanup.

#### Constructor

There are two constructors:

```java
public ConcurrentLRUCache(int capacity)
public ConcurrentLRUCache(int capacity, Duration cleanupInterval)
```

The first constructor uses a default cleanup interval of 1 minute. The second lets tests or users choose a custom interval.

The constructor validates that capacity is positive. It then creates the map, linked list, lock, metrics, and expiration manager.

Interview explanation:

```text
I validate capacity at construction time because a cache with zero or negative capacity does not make sense. I also start the expiration manager when the cache is created so expired entries can be cleaned in the background.
```

#### put(K key, V value)

This stores a value without TTL. Internally it calls:

```java
put(key, value, null);
```

`null` TTL means the entry never expires.

#### put(K key, V value, Duration ttl)

This stores a value with optional TTL.

Flow:

1. Validate key is not null.
2. Validate value is not null.
3. Validate TTL is positive if provided.
4. Take the write lock.
5. Check whether the key already exists.
6. If it exists, update the node and move it to the front.
7. If it does not exist, create a new node.
8. Add the new node to the map.
9. Add the new node to the front of the linked list.
10. Evict overflow if size is greater than capacity.
11. Release the write lock.

Why write lock?

Because `put` changes the map and linked list. Those two structures must be updated together.

Example:

```text
Capacity = 2

put("a", "alpha")
List: a

put("b", "bravo")
List: b -> a

put("c", "charlie")
List before eviction: c -> b -> a
Evict tail: a
List after eviction: c -> b
```

#### get(K key)

This retrieves a value.

Flow:

1. Validate key is not null.
2. Increment total request count.
3. Take the write lock.
4. Find the node in the map.
5. If missing, increment misses and return `Optional.empty()`.
6. If present but expired, remove it, increment expired removals, increment misses, and return `Optional.empty()`.
7. If present and not expired, move it to the front.
8. Increment hits.
9. Return `Optional.of(value)`.
10. Release the write lock.

Why does `get` use the write lock?

Because a successful `get` changes recency order by moving the node to the front. Even though the user thinks of `get` as a read operation, internally it mutates the linked list.

Interview-ready line:

```text
In an LRU cache, a read is also a write to the recency structure, so `get` must acquire the write lock.
```

#### remove(K key)

This removes a key manually.

Flow:

1. Validate key is not null.
2. Take the write lock.
3. Look up the node.
4. If missing, return `null`.
5. If present, remove from both map and linked list.
6. Return the removed value.

Returning `null` for missing keys is a design choice. `get` uses `Optional<V>`, while `remove` returns the removed value or `null`.

#### containsKey(K key)

This checks whether a non-expired key exists.

Flow:

1. Validate key is not null.
2. Take the read lock.
3. Look up the node.
4. Return true only if the node exists and is not expired.

Important detail:

`containsKey` does not remove expired nodes. It only reports false for expired nodes. Actual removal can happen through `get` or scheduled cleanup.

Why read lock?

It does not change recency order and does not modify the data structures.

#### size()

This returns the number of entries currently in the map.

Important detail:

Expired entries may still be counted until they are removed by lazy access or background cleanup.

Interview explanation:

```text
Size reflects the current map size. Since expiration can be lazy, an expired entry may remain briefly until cleanup runs or the key is accessed.
```

#### clear()

This removes all entries.

Flow:

1. Take the write lock.
2. Clear the map.
3. Clear the linked list.
4. Release the lock.

This is O(n) because clearing the linked list walks nodes to detach links.

#### shutdown() and close()

The cache starts a scheduled cleanup thread. That thread must be stopped when the cache is no longer needed.

The class implements `AutoCloseable`, so users can write:

```java
try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100)) {
    cache.put("x", "value");
}
```

At the end of the `try` block, `close()` is called automatically, which calls `shutdown()`.

Interview explanation:

```text
The cache owns a background executor, so it implements AutoCloseable to encourage safe cleanup through try-with-resources.
```

#### removeExpiredEntries()

This is the cleanup task used by `ExpirationManager`.

Flow:

1. Take the write lock.
2. Get current time using `System.nanoTime()`.
3. Scan all cache nodes.
4. Collect expired nodes.
5. For each expired node, confirm the map still points to that exact node.
6. Remove it from the map and linked list.
7. Increment expiration metrics.
8. Log how many were removed.
9. Release the lock.

Why collect first, then remove?

It avoids modifying the map while iterating over `entries.values()`.

Why check `entries.get(node.key) == node`?

This ensures the cleanup removes the exact node it inspected. It is a defensive check against stale references if a key was replaced.

#### evictOverflow()

This enforces capacity.

Flow:

1. While map size is greater than capacity:
2. Remove the tail node from the linked list.
3. Remove the same node from the map.
4. Increment eviction count.

The tail is always the least recently used node.

#### removeNode(CacheNode<K,V> node)

This helper removes one node from both structures:

```java
entries.remove(node.key, node);
recency.remove(node);
```

The two-argument `remove(key, value)` form is important because it removes only if the current mapped value is that same node.

### src/main/java/cache/CacheNode.java

This class represents one cache entry.

Fields:

```java
final K key;
V value;
long expiresAtNanos;
CacheNode<K, V> previous;
CacheNode<K, V> next;
```

Meaning:

- `key`: the cache key.
- `value`: the cached value.
- `expiresAtNanos`: expiration timestamp based on `System.nanoTime()`.
- `previous`: previous node in the doubly linked list.
- `next`: next node in the doubly linked list.

Why store key inside the node?

When evicting the tail, the list gives us the node. To remove it from the map, we need its key.

#### Expiration logic

If TTL is null, the node never expires:

```java
private static final long NEVER_EXPIRES = Long.MAX_VALUE;
```

If TTL is provided, expiration time is:

```java
now + ttl.toNanos()
```

The project uses `System.nanoTime()` instead of wall-clock time.

Why `System.nanoTime()`?

`System.nanoTime()` is monotonic and better for measuring elapsed time. Wall-clock time can move forward or backward if the system clock changes.

#### Overflow protection

The code checks:

```java
return expiresAt < now ? Long.MAX_VALUE : expiresAt;
```

If adding TTL overflows the long value, the expiration is treated as never expiring. This prevents a very large TTL from accidentally becoming an already-expired timestamp.

### src/main/java/cache/DoublyLinkedList.java

This class maintains LRU order.

Important fields:

```java
private CacheNode<K, V> head;
private CacheNode<K, V> tail;
```

Meaning:

- `head`: most recently used.
- `tail`: least recently used.

#### addToFront(node)

Adds a node as the new head.

Used when:

- a new key is inserted
- an existing key is accessed
- an existing key is updated

#### moveToFront(node)

Moves an existing node to the head.

If the node is already head, nothing happens.

Otherwise:

1. Remove it from its current position.
2. Add it to the front.

#### remove(node)

Removes a node from wherever it currently is.

Cases handled:

- Removing the head.
- Removing the tail.
- Removing a middle node.
- Removing the only node.

After removal, the node's `previous` and `next` references are set to null. This avoids stale links and makes the node detached.

#### removeTail()

Removes and returns the least recently used node.

This is used for eviction.

#### clear()

Walks through the list and nulls all previous and next pointers, then sets head and tail to null.

### src/main/java/cache/ExpirationManager.java

This class owns the background cleanup executor.

It creates a single daemon thread:

```java
Executors.newSingleThreadScheduledExecutor(...)
```

The thread name is:

```text
cache-expiration-1
cache-expiration-2
...
```

Why daemon thread?

A daemon thread does not keep the JVM alive by itself. This is useful for background maintenance work.

The cleanup is scheduled with fixed delay:

```java
scheduleWithFixedDelay(cleanupTask, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS)
```

Fixed delay means:

- Wait for the initial delay.
- Run cleanup.
- After cleanup finishes, wait the delay again.
- Run cleanup again.

It validates that cleanup interval is positive.

### src/main/java/cache/CacheStats.java

This is a Java record:

```java
public record CacheStats(
        long hitCount,
        long missCount,
        long evictionCount,
        long expiredRemovalCount,
        long totalRequests,
        double hitRate) {}
```

It is an immutable snapshot of metrics.

Why a record?

Records are concise data carriers in modern Java. They automatically provide constructor, accessors, `equals`, `hashCode`, and `toString`.

### src/test/java/cache/ConcurrentLRUCacheTest.java

This file contains JUnit tests for behavior and thread safety.

Test coverage includes:

- Basic operations.
- LRU eviction.
- Updating an existing key refreshes recency.
- Lazy TTL expiration on `get`.
- Scheduled cleanup removal.
- Statistics tracking.
- Constructor and argument validation.
- Concurrent mixed workload.

Important interview point:

```text
The tests are not only checking happy paths. They also check expiration, eviction order, invalid inputs, metrics, and concurrent access.
```

### benchmark/benchmark/CacheBenchmark.java

This is a simple benchmark runner.

It tests thread counts:

```java
int[] threadCounts = {1, 10, 50, 100, 200};
```

Each thread performs 5,000 operations. The key space is 10,000 keys.

The benchmark workload:

- 70% `get`
- 30% `put`

It measures:

- total operations
- throughput
- average latency
- hit rate
- memory usage

It uses a `CountDownLatch` so all worker threads begin at the same time. That makes the benchmark more realistic than starting each worker independently.

### benchmark/benchmark/BenchmarkResult.java

This is a package-private record that stores benchmark output:

```java
record BenchmarkResult(
        int threads,
        long operations,
        long throughputOpsPerSecond,
        double averageLatencyMillis,
        double hitRate,
        double memoryUsageMegabytes) {}
```

It keeps benchmark data structured before rendering.

### benchmark/benchmark/BenchmarkReportGenerator.java

This renders benchmark results as Markdown and writes them to:

```text
docs/benchmark-results.md
```

It uses `NumberFormat` with `Locale.US` so large numbers are readable, such as `1,000,000`.

## 8. Operation Flows in Detail

### Put New Key

```text
put("a", "alpha")

1. Validate key and value.
2. Acquire write lock.
3. entries.get("a") returns null.
4. Create CacheNode("a", "alpha", ttl).
5. entries.put("a", node).
6. recency.addToFront(node).
7. If size > capacity, evict tail.
8. Release write lock.
```

Result:

```text
Map:
a -> node(a)

List:
head/tail -> a
```

### Put Existing Key

```text
put("a", "updated")

1. Validate inputs.
2. Acquire write lock.
3. Find existing node.
4. Update value and expiration.
5. Move node to front.
6. Release write lock.
```

This does not create a duplicate node. It updates the existing node.

### Get Existing Non-Expired Key

```text
get("a")

1. Increment total requests.
2. Acquire write lock.
3. Find node.
4. Check expiry.
5. Move node to front.
6. Increment hits.
7. Return Optional.of(value).
8. Release write lock.
```

The move-to-front step is what makes it LRU.

### Get Missing Key

```text
get("missing")

1. Increment total requests.
2. Acquire write lock.
3. Node is not found.
4. Increment misses.
5. Return Optional.empty().
6. Release write lock.
```

### Get Expired Key

```text
get("a")

1. Increment total requests.
2. Acquire write lock.
3. Find node.
4. node.isExpired(now) is true.
5. Remove node from map and list.
6. Increment expired removals.
7. Increment misses.
8. Return Optional.empty().
9. Release write lock.
```

Expired entries are treated as cache misses.

### Eviction

```text
Capacity = 2
Current list: b -> a
put("c", "charlie")

After insert:
c -> b -> a

Size is 3, capacity is 2.
Remove tail: a.

Final:
c -> b
```

The cache removes `a` because it is least recently used.

## 9. Concurrency Model

The project uses:

```java
ReentrantReadWriteLock
```

A read-write lock has:

- Read lock: multiple readers can hold it at the same time.
- Write lock: only one writer can hold it, and it excludes readers.

In this cache:

- `put` uses write lock.
- `get` uses write lock.
- `remove` uses write lock.
- `clear` uses write lock.
- `removeExpiredEntries` uses write lock.
- `containsKey` uses read lock.
- `size` uses read lock.

The surprising part is `get`.

Normally, `get` sounds like a read. But in an LRU cache, `get` changes recency order. That means it mutates the linked list. Therefore it must use the write lock.

Why use `ConcurrentHashMap` if there is also a lock?

The map is thread-safe by itself, but the full cache state is more than the map. The linked list is not thread-safe. The lock protects compound operations involving both structures. `ConcurrentHashMap` still gives safe map semantics and efficient lookup, but correctness depends on the lock around combined map/list updates.

Interview answer:

```text
I used a concurrent map for efficient key lookup, but I still need a lock because the map and linked list must be updated atomically as one cache state. Without the lock, one thread could update the map while another observes or changes the list, causing broken recency order or stale nodes.
```

## 10. Metrics

The cache tracks:

- Hits: successful `get`.
- Misses: missing or expired `get`.
- Evictions: removals caused by capacity overflow.
- Expired removals: removals caused by TTL expiration.
- Total requests: total `get` calls.
- Hit rate: hits divided by total requests.

Metrics use `AtomicLong` because multiple threads can update them safely.

Hit rate formula:

```text
hitRate = hits / totalRequests
```

If total requests is zero, hit rate is `0.0` to avoid division by zero.

Important detail:

Only `get` increments total requests. `put`, `remove`, `containsKey`, and `size` do not count as requests.

## 11. TTL Expiration

TTL means Time To Live.

When a key is inserted with TTL:

```java
cache.put("session:42", "active", Duration.ofMinutes(5));
```

The cache calculates an expiry timestamp. After that time, the entry is considered expired.

This project removes expired entries in two ways:

1. Lazy expiration during `get`.
2. Scheduled background cleanup.

### Lazy Expiration

When `get` finds a node, it checks:

```java
node.isExpired(System.nanoTime())
```

If expired, the node is removed immediately and returned as a miss.

Benefit:

- Fast and simple.
- No expired value is returned.

Limitation:

- If nobody accesses the key, it may remain in memory until scheduled cleanup.

### Scheduled Cleanup

The expiration manager periodically calls `removeExpiredEntries()`.

Benefit:

- Expired keys can be removed even if nobody calls `get` on them.

Limitation:

- Cleanup scans all entries, so it is O(n).

Good interview wording:

```text
The cache combines lazy expiration for correctness on access with scheduled cleanup for memory hygiene.
```

## 12. Complexity Analysis

| Operation | Average Complexity | Why |
| --- | ---: | --- |
| `get` | O(1) | Map lookup plus linked-list move |
| `put` | O(1) | Map insert/update plus linked-list insert/move |
| `remove` | O(1) | Map lookup plus linked-list unlink |
| `containsKey` | O(1) | Map lookup and expiry check |
| `size` | O(1) | Map size |
| `clear` | O(n) | Clears map and walks list |
| scheduled cleanup | O(n) | Scans all entries |

The main design goal is O(1) `get` and `put`, because those are the most common cache operations.

## 13. Why the Design Is Correct

The correctness depends on these invariants:

- Every active key in `entries` points to exactly one `CacheNode`.
- Every active node in the linked list is reachable from the map.
- The head is always the most recently used node.
- The tail is always the least recently used node.
- `get` and update operations move nodes to the head.
- Capacity overflow removes from the tail.
- Expired entries are never returned from `get`.
- Structural changes to map and list happen under the write lock.

If you can explain these invariants clearly, the interviewer will understand that you know the design deeply.

## 14. Example Walkthrough

Use this in interviews when asked, "Can you walk me through the cache?"

```java
try (ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(2)) {
    cache.put("A", "Apple");
    cache.put("B", "Banana");
    cache.get("A");
    cache.put("C", "Cherry");
}
```

Step by step:

```text
put A:
List: A
Map: A

put B:
List: B -> A
Map: A, B

get A:
A is found.
A becomes most recently used.
List: A -> B
Map: A, B

put C:
C is inserted at front.
List: C -> A -> B
Size is 3, capacity is 2.
Evict tail B.
List: C -> A
Map: A, C
```

Final result:

- `A` remains because it was recently accessed.
- `B` is evicted because it was least recently used.
- `C` remains because it was just inserted.

## 15. Testing Strategy

The test class covers the important behavior.

### Basic Operations Test

Checks:

- `put`
- `get`
- `containsKey`
- `size`
- `remove`
- `clear`

This proves the normal API works.

### LRU Eviction Test

Checks that when capacity is exceeded, the least recently used key is removed.

Important scenario:

```text
put a
put b
get a
put c
```

Expected:

- `b` is removed.
- `a` stays because `get a` refreshed it.

### Update Recency Test

Checks that updating an existing key also refreshes its recency.

Why this matters:

If updating did not move the key to the front, an actively updated key could be evicted incorrectly.

### Lazy Expiration Test

Checks that an expired key is removed when accessed.

Expected:

- `get` returns empty.
- `containsKey` returns false.
- miss count increases.
- expired removal count increases.

### Scheduled Cleanup Test

Checks that background cleanup removes expired entries even without accessing them directly.

Expected:

- expired key is gone.
- non-expired key remains.
- size reflects cleanup.

### Statistics Test

Checks:

- hit count
- miss count
- total requests
- hit rate
- eviction count

### Validation Test

Checks invalid inputs:

- zero capacity
- zero cleanup interval
- null key
- null value
- zero TTL

### Concurrent Workload Test

Creates 24 worker threads. Each worker performs 2,000 operations. The workload mixes:

- mostly `get`
- some `put`
- occasional `containsKey`

Expected:

- all tasks complete
- size never exceeds capacity
- total request count is correct

This test gives confidence that the cache remains stable under concurrent access.

## 16. Benchmark Strategy

The benchmark is a practical performance smoke test.

Setup:

- Capacity/key space: 10,000
- Preloaded entries: 5,000
- Operations per thread: 5,000
- Thread counts: 1, 10, 50, 100, 200
- Workload: 70% reads, 30% writes

Measured values:

- throughput
- average latency
- hit rate
- memory usage

How to explain the latest result:

```text
The latest benchmark report shows 1,000,000 operations with 200 threads, about 3.7 million ops/sec, about 0.0499 ms average latency, and about 98.30% hit rate. This suggests the cache performs well for a local in-memory workload, though real production performance would depend on hardware, JVM warmup, GC behavior, and access patterns.
```

Do not oversell the benchmark. Say it is a useful local benchmark, not a complete production benchmark.

## 17. Commands to Know

Run tests:

```bash
mvn test
```

Run coverage:

```bash
mvn jacoco:report
```

Run formatting check:

```bash
mvn spotless:check
```

Run benchmark:

```bash
mvn -DskipTests compile exec:java
```

Read benchmark output:

```text
docs/benchmark-results.md
```

Read coverage output:

```text
target/site/jacoco/index.html
```

## 18. Interview Explanation Script

Use this when asked, "Tell me about your project."

```text
I built a thread-safe in-memory LRU cache in Java 21. The cache supports fixed capacity, least-recently-used eviction, optional TTL expiration, metrics, tests, and a small benchmark runner.

The core design combines a ConcurrentHashMap and a custom doubly linked list. The map gives O(1) lookup from key to node. The linked list maintains recency order, with the most recently used node at the head and the least recently used node at the tail. When a key is read or updated, I move its node to the front. When capacity is exceeded, I remove the tail.

For concurrency, I used ReentrantReadWriteLock. Operations that modify structure, like put, get, remove, clear, eviction, and cleanup, use the write lock. Even get uses the write lock because reading a key updates recency order. Pure checks like size and containsKey use the read lock. Metrics use AtomicLong.

For TTL, each node stores an expiration timestamp based on System.nanoTime. Expired entries are removed lazily during get and also through a scheduled background cleanup task. The cache implements AutoCloseable so the cleanup executor can be shut down safely.

I validated the project with JUnit tests for basic operations, eviction order, updates, TTL, statistics, invalid inputs, and concurrent workloads. I also included a benchmark that measures throughput, latency, hit rate, and memory usage under different thread counts.
```

## 19. Common Interview Questions and Answers

### What is an LRU cache?

An LRU cache removes the least recently used item when the cache is full. Recently accessed or updated entries are kept longer because they are more likely to be used again.

### Why did you use a hash map?

The hash map gives O(1) lookup by key. Without it, finding a key in the linked list would be O(n).

### Why did you use a doubly linked list?

A doubly linked list allows O(1) removal and insertion when we already have the node reference. Since the map gives us the node directly, we can move any node to the front quickly.

### Why not use only `LinkedHashMap`?

`LinkedHashMap` can implement LRU behavior, but this project is designed to demonstrate the internal mechanics: explicit node management, custom recency list, TTL handling, metrics, and concurrency control. Also, `LinkedHashMap` is not thread-safe by default.

### Why does `get` use a write lock?

Because `get` updates recency order. In an LRU cache, reading an entry makes it recently used, so the node must move to the front of the linked list. That is a structural modification.

### Why use `ConcurrentHashMap` if you already use locks?

The cache state includes both a map and a linked list. The lock protects operations that must update both together. The concurrent map gives safe and efficient map operations, but it does not remove the need to protect compound cache state.

### What happens when an entry expires?

If an expired entry is accessed through `get`, it is removed and counted as a miss. The background cleanup task also periodically scans and removes expired entries.

### Why use `System.nanoTime()` for TTL?

TTL is based on elapsed time, not calendar time. `System.nanoTime()` is monotonic and safer for measuring durations because it is not affected by system clock changes.

### What is the complexity of `get` and `put`?

Both are O(1) on average. `get` does a map lookup and linked-list move. `put` does a map insert or update and linked-list insert or move. Eviction removes the tail in O(1).

### What are the tradeoffs of this design?

The design is simple and correct, but successful `get` operations require the write lock. Under very high read concurrency, this can limit parallelism because reads mutate recency order. A more advanced design could shard the cache or use lock-free/segmented structures.

### Does `size()` remove expired entries?

No. `size()` returns the current map size. Expired entries may remain briefly until accessed or cleaned by the scheduled cleanup task.

### Why implement `AutoCloseable`?

The cache owns a scheduled executor for cleanup. Implementing `AutoCloseable` lets callers use try-with-resources so the background executor is shut down automatically.

### What happens if TTL is zero or negative?

The cache rejects it with `IllegalArgumentException`. TTL must be positive because zero or negative lifetime would be confusing and likely a user error.

### What happens if key or value is null?

The cache rejects null keys and null values using `Objects.requireNonNull`.

### How are metrics made thread-safe?

Metrics are stored in `AtomicLong`, so increments and reads are safe across threads.

### How would you improve this project?

Possible improvements:

- Add maximum idle time separate from TTL.
- Add weighted capacity based on memory or item cost.
- Add removal listeners.
- Add async refresh.
- Add segmented locks for better parallelism.
- Add configurable cleanup thread factory.
- Add more detailed benchmark tooling such as JMH.
- Add optional `Optional<V>` return type for `remove` for API consistency.

## 20. Deep-Dive Topics

### Why map and list updates must be atomic

Suppose a new key is added to the map but not yet added to the list. Another thread might see it in the map, but eviction would not know where it is in recency order.

Or suppose a node is removed from the list but still exists in the map. A later `get` may try to move a detached or stale node.

That is why all compound structure changes happen under one write lock.

### Why removing by key and node is safer

The code uses:

```java
entries.remove(node.key, node);
```

This removes the entry only if the map still points to that exact node. It prevents accidental removal if the same key was somehow replaced with another node.

### Why the linked list stores nodes instead of keys

Each node stores:

- key
- value
- expiry
- previous pointer
- next pointer

This keeps all entry metadata in one object. The map points to this object, and the list connects these objects.

### Why scheduled cleanup is O(n)

The cleanup task scans all entries to check expiration. There is no separate priority queue ordered by expiration time. This keeps implementation simpler but means cleanup cost grows with cache size.

A more advanced version could maintain an expiration heap or timing wheel, but then the design becomes more complex.

### Why `getHitRate()` reads atomics separately

`getHitRate()` reads total requests and hits from atomics. In heavy concurrency, the values could change between reads. That is acceptable because metrics are approximate real-time observations, not transactional business data.

## 21. Limitations to Mention Honestly

Strong candidates can explain limitations clearly.

- `get` requires a write lock, so read-heavy workloads may still serialize successful reads.
- Scheduled cleanup scans all entries, which is O(n).
- Benchmark is custom and simple; JMH would be better for precise JVM benchmarking.
- `size()` may include expired entries until cleanup removes them.
- `remove` returns `null` instead of `Optional<V>`, while `get` returns `Optional<V>`.
- There is no maximum memory weight; capacity is based only on entry count.
- There is no callback/listener when entries are removed.

These are not failures. They are reasonable tradeoffs for a clear and maintainable implementation.

## 22. How to Defend Design Choices

### If asked, "Why not fully lock-free?"

Answer:

```text
Lock-free LRU is much harder because every access changes shared ordering. I chose a simpler lock-based design to guarantee correctness. The code keeps operations O(1), and the benchmark shows good local throughput. If requirements demanded higher parallelism, I would consider segmented LRU or approximate LRU.
```

### If asked, "Why not use synchronized?"

Answer:

```text
I used ReentrantReadWriteLock because some operations, like containsKey and size, can safely run under a read lock. A simple synchronized block would serialize every operation. The read-write lock gives more flexibility while keeping correctness.
```

### If asked, "Why not evict expired entries before LRU entries?"

Answer:

```text
Currently expiration is handled during get and scheduled cleanup, while put enforces capacity through LRU eviction. One possible improvement would be to remove expired entries before evicting a valid LRU entry. The current design favors simple O(1) put behavior instead of scanning for expired entries during every put.
```

### If asked, "Can this be used in production?"

Answer:

```text
The project demonstrates production-style concerns like thread safety, TTL, metrics, cleanup, tests, and benchmarks. For real production use, I would add more operational features, tune concurrency based on workload, use JMH for benchmarking, and consider mature libraries like Caffeine if the goal is maximum reliability and performance.
```

## 23. Important Code Paths to Memorize

Memorize these five flows:

1. `put` new key: create node, map insert, list front insert, evict tail if needed.
2. `put` existing key: update node, refresh TTL, move to front.
3. `get` hit: request++, move to front, hit++, return value.
4. `get` miss/expired: request++, remove if expired, miss++, return empty.
5. cleanup: scan entries, remove expired nodes, increment expired removals.

## 24. Simple Mental Model

Think of the cache like a bookshelf:

- The front shelf has the books you just used.
- The back shelf has the books you have not touched recently.
- When you read a book, you move it to the front.
- When the shelf is full and a new book arrives, remove the book at the back.
- A sticky note on each book says when it expires.
- A background cleaner occasionally removes expired books.

In code:

- Books are `CacheNode`.
- The shelf order is `DoublyLinkedList`.
- The index card lookup is `ConcurrentHashMap`.
- The shelf lock is `ReentrantReadWriteLock`.
- The cleaner is `ExpirationManager`.

## 25. Final Revision Checklist

Before the interview, make sure you can answer:

- What is the purpose of the project?
- Why is a cache useful?
- What does LRU mean?
- Why use a hash map and doubly linked list together?
- Why is `get` a write operation internally?
- How does eviction work?
- How does TTL work?
- What does the background cleanup thread do?
- What are the important thread-safety decisions?
- What are the main tests?
- What do the benchmark numbers mean?
- What are the limitations?
- How would you improve it?

## 26. Short Final Answer for Interview

If you need a crisp final answer, use this:

```text
My project is a thread-safe Java 21 LRU cache. It stores key-value pairs in memory with fixed capacity and optional TTL expiration. Internally it uses a ConcurrentHashMap for O(1) lookup and a custom doubly linked list for O(1) recency updates. The head of the list is the most recently used entry, and the tail is the least recently used entry. On get or update, the node moves to the head. When capacity is exceeded, the tail is evicted.

For concurrency, I use ReentrantReadWriteLock because the map and list must stay consistent. Operations like put, get, remove, cleanup, and clear use the write lock because they mutate structure. containsKey and size use the read lock. Metrics are tracked with AtomicLong.

TTL is handled using System.nanoTime. Expired entries are removed lazily during get and periodically by a scheduled cleanup thread. The cache implements AutoCloseable so that cleanup resources can be shut down safely.

I tested basic operations, LRU eviction, updates, TTL expiration, scheduled cleanup, metrics, validation, and concurrent workloads. I also added a benchmark runner to measure throughput, latency, hit rate, and memory usage under different thread counts.
```

