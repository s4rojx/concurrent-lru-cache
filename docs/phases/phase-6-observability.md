# Phase 6 — Observability

Priority: P1
Gate: Recommended

Before starting: confirm AGENTS.md is loaded in context.
When finished: fill out the PHASE GATE TEMPLATE from AGENTS.md and STOP. Do not begin the next phase file until I confirm the gate passed.

---

# 15. PHASE 6 — OBSERVABILITY

## Objective

Make cache behavior measurable.

Current metrics include basic counters.

Upgrade only where useful.

Potential metrics:

```text
requests
hits
misses
hitRate
evictions
expirations
loads
loadFailures
loadLatency
currentSize
```

Latency percentiles may be added if the measurement mechanism is correct.

Do not add a complicated observability framework merely for appearance.

A simple metrics API plus optional integration is preferable.

If Micrometer/Prometheus is introduced, justify it in:

```text
docs/06_OBSERVABILITY.md
```
