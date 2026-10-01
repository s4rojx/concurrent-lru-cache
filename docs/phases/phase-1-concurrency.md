# Phase 1 — Concurrency Redesign

Priority: P0
Gate: Mandatory

Before starting: confirm AGENTS.md is loaded in context.
When finished: fill out the PHASE GATE TEMPLATE from AGENTS.md and STOP. Do not begin the next phase file until I confirm the gate passed.

---

# 10. PHASE 1 — CONCURRENCY REDESIGN

## Objective

Remove unnecessary global contention while preserving correctness.

## First question

Verify whether successful `get()` requires the global write lock because of LRU mutation.

If confirmed, design the replacement.

Preferred architecture:

```text
Cache
 ├── Segment[]
 │    ├── Map
 │    ├── local LRU structure
 │    └── local lock
 └── global metrics
```

## Implementation Sequence

1. Write concurrency design document.
2. Define guarantees.
3. Define segment selection.
4. Define segment capacity.
5. Refactor state ownership.
6. Implement segmented access.
7. Preserve O(1) expected lookup.
8. Preserve thread safety.
9. Handle eviction locally.
10. Handle removal/clear correctly.
11. Handle executor shutdown correctly.
12. Update metrics.
13. Add concurrency stress tests.
14. Run full suite.
15. Benchmark before/after.

## Required documentation

Create/update:

```text
docs/03_CONCURRENCY.md
```

Include:

- old design
- bottleneck
- new design
- lock ownership
- happens-before assumptions
- race analysis
- capacity allocation
- eviction semantics
- exact vs approximate ordering
- tradeoffs

## Tests

Must include tests for:

```text
concurrent get
concurrent put
concurrent get/put
concurrent remove
concurrent clear
eviction under concurrency
TTL under concurrency
metrics under concurrency
executor shutdown
```

Tests must be deterministic enough to diagnose failures.

## Definition of Done

- No unexplained shared mutable state.
- No data structure corruption under stress.
- Existing behavior remains valid unless explicitly documented.
- New concurrency model is documented.
- Benchmark comparison exists.
- No performance claim without evidence.
