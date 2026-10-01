# Phase 2 — JMH Benchmarking

Priority: P0
Gate: Mandatory

Before starting: confirm AGENTS.md is loaded in context.
When finished: fill out the PHASE GATE TEMPLATE from AGENTS.md and STOP. Do not begin the next phase file until I confirm the gate passed.

---

# 11. PHASE 2 — JMH BENCHMARKING

## Objective

Replace the hand-written benchmark as the authoritative performance measurement system.

Use JMH.

The existing benchmark may remain as a simple demonstration if useful, but JMH becomes the source of performance claims.

## Required workloads

### Thread scaling

At minimum:

```text
1
2
4
8
16
32
64
128
256
```

Use only practical values supported by the environment.

### Access patterns

```text
Read-heavy
~95% GET / ~5% PUT

Balanced
~80% GET / ~20% PUT

Write-heavy
~50% GET / ~50% PUT
```

### Key distributions

```text
Uniform
Zipfian
```

Zipfian testing is especially useful because real caches often have hot and cold keys.

## Benchmark methodology

Use:

- warmup iterations
- measurement iterations
- multiple forks
- controlled state
- stable key sets
- clear cache configuration
- identical workloads for comparisons

Do not benchmark startup/setup as steady-state throughput.

## Comparison

Benchmark against Caffeine when technically practical.

The comparison must use:

```text
same JDK
same machine
same key workload
same thread count
same operation mix
same capacity
same expiration configuration
```

Do not tune your implementation and Caffeine differently to manufacture results.

## Required outputs

Create:

```text
docs/benchmarks/
docs/04_BENCHMARKS.md
```

Include:

- methodology
- environment
- raw results
- throughput
- latency where meaningful
- thread scaling
- hit rate
- workload description
- comparison
- limitations

If Caffeine wins:

**report that honestly.**

That is preferable to an exaggerated claim.
