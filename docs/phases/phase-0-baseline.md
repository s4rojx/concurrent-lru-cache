# Phase 0 — Repository Discovery and Baseline

Priority: P0
Gate: Mandatory

Before starting: confirm AGENTS.md is loaded in context.
When finished: fill out the PHASE GATE TEMPLATE from AGENTS.md and STOP. Do not begin the next phase file until I confirm the gate passed.

---

# 9. PHASE 0 — REPOSITORY DISCOVERY AND BASELINE

## Objective

Understand exactly what exists before changing it.

## Agent Tasks

1. Inspect repository structure.
2. Read the README.
3. Read architecture documentation.
4. Inspect production classes.
5. Inspect tests.
6. Inspect benchmark code.
7. Inspect Maven configuration.
8. Identify public API.
9. Identify concurrency model.
10. Identify lifecycle/resource ownership.
11. Identify existing metrics.
12. Identify TTL behavior.
13. Run all existing validation commands.

Create/update:

```text
AGENTS.md
docs/00_REPO_LAYOUT.md
docs/01_BASELINE.md
docs/02_ARCHITECTURE.md
```

## Baseline checklist

Record:

```text
[ ] Java version
[ ] Maven version/configuration
[ ] public classes
[ ] public methods
[ ] constructor behavior
[ ] thread-safety assumptions
[ ] TTL semantics
[ ] executor lifecycle
[ ] metric definitions
[ ] test count
[ ] coverage
[ ] benchmark methodology
[ ] current benchmark results
```

## Exit Criteria

The agent can explain:

> "Here is exactly how the current cache works, where state lives, how concurrency is controlled, how expiration works, and what the current tests actually prove."

If it cannot, STOP and continue investigation.
