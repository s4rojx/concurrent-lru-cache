# Benchmark Methodology and JMH Performance Analysis

---

## 1. Overview and JMH Configuration

This document records the official JMH (Java Microbenchmark Harness) performance characterization for **`ConcurrentLRUCache`** and provides a head-to-head comparison against **Caffeine** (`com.github.ben-manes.caffeine:caffeine:3.1.8`).

JMH replaces the hand-rolled `System.nanoTime()` benchmark runner as the authoritative source of performance claims.

### Test Environment

| Parameter | Specification |
|---|---|
| **OS** | Microsoft Windows 11 Home (x86_64) |
| **CPU** | 13th Gen Intel(R) Core(TM) i5-13420H (8 physical cores: 4 P-cores + 4 E-cores / 12 logical processors) |
| **JDK** | Oracle JDK 24.0.2 (build 24.0.2+12-54), 64-Bit Server VM |
| **Compiler Target** | Java 21 (`maven.compiler.release=21`) |
| **JMH Version** | 1.37 |
| **JVM Arguments** | `-Xms512m -Xmx512m -XX:+UseG1GC` |
| **Benchmark Mode** | `Throughput` (operations / second) |
| **Blackhole Mode** | Compiler Blackholes (JMH auto-detected) |
| **Warmup** | 3 iterations × 2.0 seconds per trial |
| **Measurement** | 5 iterations × 2.0 seconds per trial |
| **Forks** | 1 forked JVM per benchmark run |
| **State Scope** | `@State(Scope.Benchmark)` (shared cache instance across all threads) |

### Workload Parameters

- **Cache Capacity:** 10,000 entries
- **Key Space:** 10,000 pre-generated string keys (`key-0` to `key-9999`)
- **Pre-warming:** 5,000 entries pre-populated prior to measurement (50% fill)
- **Workload Mixes:**
  - `READ_HEAVY`: ~95% `get()`, ~5% `put()`
  - `BALANCED`: ~80% `get()`, ~20% `put()`
  - `WRITE_HEAVY`: ~50% `get()`, ~50% `put()`
- **Key Distributions:**
  - `UNIFORM`: Keys sampled uniformly at random across the key space.
  - `ZIPFIAN`: Power-law distribution (exponent $s=1.0$) with pre-computed 65,536-entry CDF lookup table for fast $O(1)$ sampling.

---

## 2. Why JMH Instead of `System.nanoTime()`?

A common pitfall in Java benchmarking is wrapping cache operations in a `for` loop with `System.nanoTime()`. Such hand-rolled harnesses produce misleading results due to multiple JVM runtime complexities:

1. **JIT Compilation and Tiered Compilation Warm-up:**
   - The JVM uses tiered compilation (C1 interpreter $\rightarrow$ C1 client $\rightarrow$ C2 server JIT). Early iterations execute interpreted code or unoptimized bytecode.
   - JMH guarantees dedicated warm-up iterations where JIT compilation, method inlining, loop unrolling, and branch prediction stabilize before data collection begins.

2. **Dead-Code Elimination (DCE):**
   - In a hand-rolled loop like `cache.get(key);`, the C2 compiler detects that the return value is unused and may optimize away the entire method call.
   - JMH provides `Blackhole.consume()`, which uses compiler-level intrinsics to consume values without allowing DCE.

3. **Constant Folding and Escape Analysis:**
   - Hand-rolled benchmarks often generate test inputs in loop bodies where the JIT can prove invariants or scalar-replace objects via escape analysis.
   - JMH's `@State` management ensures inputs and cache instances are treated as non-foldable heap state.

4. **Thread Synchronization and Measurement Skew:**
   - In hand-rolled multi-threaded tests, threads started via `Thread.start()` or `ExecutorService` rarely begin execution at the exact same instant; faster-starting threads run in isolation while later threads spin up, distorting contention measurements.
   - JMH coordinates thread iterations using internal rendezvous barriers so all worker threads measure simultaneously.

5. **Garbage Collection Pauses and Statistical Rigor:**
   - JMH computes statistical metrics (mean, standard deviation, error margins, and confidence intervals) across multiple iterations and forks, isolating outlier runs caused by GC pauses.

---

## 3. Workload Benchmark Results (8 Threads)

All benchmarks below were executed with 8 concurrent worker threads, comparing `ConcurrentLRUCache` (16 segments) against `Caffeine` under identical configurations:

| Key Distribution | Workload Mix | Caffeine Throughput (ops/s) | ConcurrentLRUCache (ops/s) | Ratio (% of Caffeine) |
|---|---|---|---|---|
| **UNIFORM** | **READ_HEAVY (95/5)** | $3,979,438 \pm 453,255$ | $2,653,713 \pm 324,726$ | **66.7%** |
| **UNIFORM** | **BALANCED (80/20)** | $3,407,539 \pm 3,199,849$ | $2,823,222 \pm 563,134$ | **82.9%** |
| **UNIFORM** | **WRITE_HEAVY (50/50)** | $3,506,088 \pm 2,204,396$ | $3,022,457 \pm 298,535$ | **86.2%** |
| **ZIPFIAN** | **READ_HEAVY (95/5)** | $3,436,653 \pm 1,140,874$ | $2,875,560 \pm 325,160$ | **83.7%** |
| **ZIPFIAN** | **BALANCED (80/20)** | $3,310,887 \pm 1,657,701$ | $3,044,615 \pm 399,979$ | **92.0%** |
| **ZIPFIAN** | **WRITE_HEAVY (50/50)** | $3,800,045 \pm 2,472,879$ | $3,425,756 \pm 536,156$ | **90.2%** |

---

## 4. Thread Scaling Benchmark Results

Workload fixed at **READ_HEAVY (95% GET / 5% PUT)** with **UNIFORM** key distribution across varying thread counts:

| Thread Count | Caffeine Throughput (ops/s) | ConcurrentLRUCache Throughput (ops/s) | ConcurrentLRUCache Scaling Factor |
|---|---|---|---|
| **1 Thread** | $3,626,783 \pm 1,320,191$ | $1,434,404 \pm 1,149,090$ | **1.00x** (baseline) |
| **2 Threads** | $4,070,884 \pm 1,528,623$ | $1,585,224 \pm 709,793$ | **1.11x** |
| **4 Threads** | $3,506,795 \pm 691,659$ | $1,981,952 \pm 356,353$ | **1.38x** |
| **8 Threads** | $3,797,842 \pm 2,732,355$ | $2,310,748 \pm 341,522$ | **1.61x** |
| **12 Threads** | $3,259,960 \pm 1,434,063$ | $2,486,171 \pm 191,257$ | **1.73x** |
| **16 Threads** | $2,947,409 \pm 753,698$ | $2,596,925 \pm 362,737$ | **1.81x** |
| **32 Threads** | $3,316,167 \pm 2,533,356$ | $2,696,519 \pm 366,176$ | **1.88x** |

### Note on Thread Count Capping
Thread counts were tested up to 32 threads. Counts beyond 32 (e.g. 64, 128, 256) were omitted because the test hardware has 12 logical processors (8 physical cores). Beyond $2.5\times$ to $3\times$ hardware core capacity, operating system context switching and scheduler thread preemption become the dominant factor, distorting algorithm-level cache concurrency measurements.

---

## 5. Architectural Comparison: ConcurrentLRUCache vs. Caffeine

### Why Caffeine Wins on Read-Heavy Uniform Workloads
- **Lock-Free Read Path:** Caffeine uses an asynchronous, lock-free ring buffer (`StripedBuffer` / `MPSC` queue) inspired by CPU cache hierarchies. Reads in Caffeine only perform a concurrent hash table lookup and append a record to a thread-local ring buffer without acquiring locks or updating doubly-linked list pointers synchronously.
- **Synchronous Mutation in ConcurrentLRUCache:** In `ConcurrentLRUCache`, even a successful `get()` requires updating the entry's recency in the segment's `DoublyLinkedList`. Although sharded across 16 segments, each read acquires an exclusive `ReentrantLock` for that segment. Under purely read-heavy uniform traffic, this lock acquisition and pointer mutation imposes a higher per-operation cost.

### Where ConcurrentLRUCache is Strongly Competitive
- **Balanced and Write-Heavy Workloads (83% – 92% of Caffeine):** When writes and updates occur, Caffeine must also acquire locks and execute maintenance tasks. The per-segment lock model of `ConcurrentLRUCache` shards writes cleanly with near-zero coordination between segments, achieving **3.02M – 3.43M ops/s**.
- **Zipfian Hot-Key Workloads:** Under skewed distributions, hot entries remain at the head of their respective segment LRU lists, resulting in high cache hit rates and minimal eviction overhead (**3.43M ops/s** in write-heavy Zipfian).
- **Simplicity and Predictability:** `ConcurrentLRUCache` maintains strict structural invariants with simple, bounded memory footprints and immediate per-segment eviction without requiring background maintenance buffers or thread pool drainage.

---

## 6. How to Reproduce

To run the JMH benchmark suite:

```powershell
# 1. Build the fat benchmark jar
mvn clean package -DskipTests

# 2. Run the workload benchmarks (8 threads, uniform + Zipfian)
java -jar target/benchmarks.jar "jmh.CacheBenchmarkJmh" -f 1 -wi 3 -i 5 -w 2 -r 2 -t 8 -rf json -rff docs/benchmarks/jmh-workload.json

# 3. Run the thread-scaling benchmarks (1 to 32 threads)
java -jar target/benchmarks.jar "jmh.ThreadScalingBenchmark" -f 1 -wi 3 -i 5 -w 2 -r 2 -rf json -rff docs/benchmarks/jmh-scaling.json
```
