# Phase 3 — Pluggable Eviction Policies

Priority: P1
Gate: Mandatory

Before starting: confirm AGENTS.md is loaded in context.
When finished: fill out the PHASE GATE TEMPLATE from AGENTS.md and STOP. Do not begin the next phase file until I confirm the gate passed.

---

# 12. PHASE 3 — PLUGGABLE EVICTION

## Objective

Transform the project from one hard-coded LRU implementation into a small caching engine with a clean eviction abstraction.

Target conceptual interface:

```text
EvictionPolicy<K,V>
    onAccess(...)
    onInsert(...)
    onRemove(...)
    evictionCandidate(...)
```

The exact API must be adapted to the existing repository after inspection.

## Required Policies

### 1. LRU

Refactor the current behavior.

### 2. LFU

Implement a correct frequency-aware policy.

Document:

- frequency tracking
- tie-breaking
- memory overhead
- update cost
- aging/decay if used

### 3. TinyLFU / Window TinyLFU

Only implement this if:

- the agent understands the algorithm,
- correctness can be tested,
- benchmark results justify the added complexity,
- the implementation remains understandable at the user's level.

If not, STOP before implementing it and ask whether to proceed.

Do not implement a superficial "TinyLFU" that is merely an LFU counter with a different class name.

## Benchmark

Compare policies using:

```text
Uniform workload
Zipfian workload
Different cache capacities
Different hit/miss patterns
```

Primary metric:

```text
hit rate
```

Secondary:

```text
throughput
latency
memory overhead
```

## Documentation

Create:

```text
docs/05_EVICTION_POLICIES.md
```

Explain:

```text
LRU
 ↓
temporal locality

LFU
 ↓
frequency

TinyLFU
 ↓
admission + frequency + recency
```

Do not claim one policy is universally superior.
