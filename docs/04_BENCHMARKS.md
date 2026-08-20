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
| **Warmup** | 5 iterations × 1.0 second per trial |
| **Measurement** | 10 iterations × 1.0 second per trial |
| **Forks** | 3 independent JVM forks per benchmark run ($3 \times 10 = 30$ measurement iterations) |
| **State Scope** | `@State(Scope.Benchmark)` (shared cache instance across all threads) |

### Workload Parameters & Apples-to-Apples Verification

To ensure strict experimental control and an honest head-to-head comparison:
- **Cache Capacity:** Exactly 10,000 entries for both `ConcurrentLRUCache` and `Caffeine`.
- **Key Space:** Exactly 10,000 pre-generated string keys (`key-0` to `key-9999`) stored in a pre-allocated array (preventing key allocation overhead during measurement).
- **Pre-warming:** Exactly 5,000 entries pre-populated in both caches before measurement begins (50% initial fill).
- **Workload Mixes:**
  - `READ_HEAVY`: ~95% `get()`, ~5% `put()`
  - `BALANCED`: ~80% `get()`, ~20% `put()`
  - `WRITE_HEAVY`: ~50% `get()`, ~50% `put()`
- **Key Distributions:**
  - `UNIFORM`: Uniformly distributed random access across the 10,000 keys.
  - `ZIPFIAN`: Power-law distribution ($s=1.0$) with pre-computed 65,536-entry CDF lookup table for fast $O(1)$ sampling.
- **Expiration:** Neither cache uses TTL during this throughput benchmark, isolating pure eviction and concurrency mechanics.

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

## 3. Workload Benchmark Results (3 Forks × 10 Iterations = 30 Data Points, 8 Threads)

All benchmarks below were executed with 8 concurrent worker threads, comparing `ConcurrentLRUCache` (16 segments) against `Caffeine` under identical configurations:

| Key Distribution | Workload Mix | Caffeine Throughput (ops/s) | ConcurrentLRUCache (ops/s) | Ratio (% of Caffeine) |
|---|---|---|---|---|
| **UNIFORM** | **READ_HEAVY (95/5)** | $3,506,751 \pm 289,410$ | $2,442,149 \pm 90,068$ | **69.6%** |
| **UNIFORM** | **BALANCED (80/20)** | $3,430,754 \pm 381,421$ | $2,467,653 \pm 43,825$ | **71.9%** |
| **UNIFORM** | **WRITE_HEAVY (50/50)** | $3,803,844 \pm 185,679$ | $2,879,588 \pm 68,199$ | **75.7%** |
| **ZIPFIAN** | **READ_HEAVY (95/5)** | $3,504,951 \pm 423,780$ | $2,315,287 \pm 50,443$ | **66.1%** |
| **ZIPFIAN** | **BALANCED (80/20)** | $3,913,499 \pm 460,444$ | $2,494,541 \pm 47,758$ | **63.7%** |
| **ZIPFIAN** | **WRITE_HEAVY (50/50)** | $3,683,251 \pm 370,371$ | $2,809,590 \pm 52,527$ | **76.3%** |

---

## 4. Thread Scaling Benchmark Results (3 Forks × 10 Iterations = 30 Data Points)

Workload fixed at **READ_HEAVY (95% GET / 5% PUT)** with **UNIFORM** key distribution across varying thread counts:

| Thread Count | Caffeine Throughput (ops/s) | ConcurrentLRUCache Throughput (ops/s) | ConcurrentLRUCache Relative Scaling |
|---|---|---|---|
| **1 Thread** | $4,305,805 \pm 319,829$ | $2,500,286 \pm 114,670$ | **1.00x** (baseline) |
| **2 Threads** | $4,109,883 \pm 213,769$ | $1,797,161 \pm 58,284$ | **0.72x** |
| **4 Threads** | $4,524,772 \pm 548,241$ | $2,281,041 \pm 110,973$ | **0.91x** |
| **8 Threads** | $3,706,489 \pm 449,834$ | $2,404,518 \pm 139,966$ | **0.96x** |
| **12 Threads** | $3,547,933 \pm 241,937$ | $2,625,942 \pm 62,695$ | **1.05x** |
| **16 Threads** | $3,665,445 \pm 240,169$ | $2,758,073 \pm 111,393$ | **1.10x** |
| **32 Threads** | $3,262,700 \pm 183,534$ | $2,753,263 \pm 143,002$ | **1.10x** |

### Variance and Scaling Analysis
1. **Low Variance Across Forks:** Running 3 independent forks with 10 measurement iterations each ($N=30$) narrowed `ConcurrentLRUCache` error margins to $\pm 1.7\%$ to $\pm 5.8\%$, providing statistically robust results.
2. **2-Thread Dip on Hybrid CPU Architecture:** On 1 thread, `ConcurrentLRUCache` incurs no thread synchronization or lock contention ($\sim 2.50\text{M ops/s}$). When concurrency increases to 2 threads on Intel's hybrid architecture (4 P-cores + 4 E-cores), inter-core lock transitions and cache-line bouncing introduce overhead before thread parallelism catches up across the 16 independent segments (reaching peak throughput of $\sim 2.76\text{M ops/s}$ at 16 threads).
3. **Thread Capping at 32 Threads:** Scaling was evaluated up to 32 threads. Beyond 32 threads ($>2.5\times$ the machine's 12 logical processors), operating system context switching and thread scheduling overhead dominate, obscuring cache algorithm performance.

---

## 5. Architectural Comparison: ConcurrentLRUCache vs. Caffeine

### Why Caffeine Leads on Read-Heavy Uniform Workloads
- **Lock-Free Read Path:** Caffeine uses an asynchronous, lock-free ring buffer (`StripedBuffer` / `MPSC` queue) inspired by CPU cache hierarchies. Reads in Caffeine only perform a concurrent hash table lookup and append a record to a thread-local ring buffer without acquiring locks or updating doubly-linked list pointers synchronously.
- **Synchronous Mutation in ConcurrentLRUCache:** In `ConcurrentLRUCache`, even a successful `get()` requires updating the entry's recency in the segment's `DoublyLinkedList`. Although sharded across 16 segments, each read acquires an exclusive `ReentrantLock` for that segment. Under purely read-heavy uniform traffic, this lock acquisition and pointer mutation imposes a higher per-operation cost.

### Where ConcurrentLRUCache is Strongly Competitive
- **Write-Heavy and High-Mutation Workloads (75% – 76% of Caffeine):** When writes and updates occur, Caffeine must also acquire locks and execute maintenance tasks. The per-segment lock model of `ConcurrentLRUCache` shards writes cleanly with near-zero coordination between segments, achieving **2.81M – 2.88M ops/s**.
- **Simplicity and Strict Invariants:** `ConcurrentLRUCache` maintains strict structural invariants with simple, bounded memory footprints and immediate per-segment eviction without requiring background maintenance buffers or thread pool drainage.

---

## 6. How to Reproduce

To run the JMH benchmark suite:

```powershell
# 1. Build the fat benchmark jar
mvn clean package -DskipTests

# 2. Run the workload benchmarks (3 forks, 5 warmup, 10 measurement, 8 threads)
java -jar target/benchmarks.jar "jmh.CacheBenchmarkJmh" -f 3 -wi 5 -i 10 -w 1 -r 1 -t 8 -rf json -rff docs/benchmarks/jmh-workload.json

# 3. Run the thread-scaling benchmarks (3 forks, 5 warmup, 10 measurement, 1 to 32 threads)
java -jar target/benchmarks.jar "jmh.ThreadScalingBenchmark" -f 3 -wi 5 -i 10 -w 1 -r 1 -rf json -rff docs/benchmarks/jmh-scaling.json
```
