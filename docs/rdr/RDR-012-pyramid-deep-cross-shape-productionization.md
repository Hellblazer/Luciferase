---
title: "PyramidIndex Deep Cross-Shape — Productionize the Deep-Tet Path, or Mark It Infrastructure-Only"
id: RDR-012
type: Architecture
status: draft
priority: low
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-31
related_issues: [RDR-010]
---

# RDR-012: PyramidIndex Deep Cross-Shape Productionization

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

RDR-010 implemented deep pyramid-rooted tet cross-shape connectivity (`l > minTetLevel`) — `Tet.faceNeighborElement` via the ported `t8_dpyramid_tet_boundary` corner-walk (cjwr A), deep tet SFC keys (`encode(Tet)`/`elementFromKey` accept `minTetLevel < level`, cjwr B), and full-depth edge/vertex neighbors (`allShapeNeighbors`, 2l04). This machinery is implemented and **involution-tested**.

But it is **dark machinery**: `PyramidIndex`'s locate primitive stops at the shallowest tet leaf, so **deep tet keys are never inserted into the live index**. The deep neighbor code is reachable only via direct refinement and tests, never through normal index operation. RDR-010's Close section states this honestly. Two consequences:

1. **Capability with no consumer.** Validated infrastructure that production never exercises is a maintenance liability and a correctness blind spot — it can rot undetected because no live path covers it.
2. **No independent t8code oracle for the deep path** (gap-axis-4). The deep tet-return branch of `tetBoundary` is *counted*, not validated face-by-face against an independent `t8_dpyramid_tet_boundary` port. The planned oracle bead `Luciferase-kyz9` was closed won't-do. So the deep corner-walk rests on self-consistency (involution), never on table parity with t8code.

This RDR decides the deep path's fate: make it live, or formally fence it off.

## Context

- The live, shipping capability is the **shallow hex↔tet boundary** (`l == minTetLevel`) — fully exercised and sufficient for RDR-010's stated need.
- "Closed won't-do" beads from RDR-010 that bound this decision: `Luciferase-9hse` (locate-deep-tet primitive), `Luciferase-kyz9` (deep-FACE completeness oracle), `Luciferase-tjdc` (production distributed pyramid bootstrap). They are reopen-if-needed.
- Reference: `t8_dpyramid_bits.c` `t8_dpyramid_tet_boundary:822`, `t8_dpyramid_is_inside_root:883`, `t8_dpyramid_face_neighbour:599`. Luciferase: `Tet.tetBoundary:1899`, `PyramidNeighborDetector`.
- This RDR pairs with RDR-011 (SFC) and the P1 face-neighbor oracle from the remediation plan — the P1 oracle, if built, also covers the deep path and would partly subsume the kyz9 gap.

## Decision (DRAFT — gate question open)

**Gate question:** Is there (or will there be) a workload that needs deep tet elements *inserted into and queried through* the live `PyramidIndex`?

- **Direction D1 — Productionize.** Extend the locate primitive (reopen `Luciferase-9hse`) so refinement past `minTetLevel` inserts deep tet keys; wire k-NN / range / neighbor queries to traverse them; build the deep-path t8code oracle (reopen `kyz9`) as the acceptance gate. Cost: touches `PyramidIndex` insert/locate invariants — significant and risk-bearing. Benefit: the deep machinery becomes real, covered, and useful.
- **Direction D2 — Mark infrastructure-only (recommended absent a consumer).** Formally document (architecture docs + a class-level marker / `@ApiStatus`-style note) that deep cross-shape is validated *topology infrastructure* with no live consumer, not a production query path. Add a single guard test pinning the boundary (locate does not emit deep keys) so the "shallow-only live" contract can't silently drift. Keep the involution tests as the deep-path regression guard. Cost: ~minimal. Benefit: removes the blind-spot ambiguity; honest scope.
- **Direction D3 — Minimal hardening, defer productionization.** D2 + build the deep-path t8code parity oracle anyway (the P1 oracle), so the dark machinery is at least *correct* against t8code even while unconsumed. Splits the difference: no productionization risk, but closes the correctness blind spot.

**Recommendation pending gate:** D3 — pair with the P1 face-neighbor oracle (one harness validates both shallow and deep against t8code), and document infrastructure-only status. Escalate to D1 only when a concrete deep-insertion workload appears.

## Approach (if D2/D3)

1. Document deep cross-shape as infrastructure-only in `CLAUDE.md` (correcting the stale "fail-loud guarded" text — see remediation P0), RDR-010 cross-refs, and a class-level note on the deep-path methods.
2. Add a boundary-pinning test: assert `PyramidIndex` locate/insert never emits keys with `minTetLevel < level` under normal refinement.
3. (D3) Build the whole-domain t8code face-neighbor parity oracle (shared with remediation P1) covering the deep tet-return branch — closing the `kyz9` gap without productionizing.

## Risks / Open Questions

- **D1 risk:** deep insertion changes index cardinality and locate invariants; spanning, balancing, and ghost wiring all assume shallow-only today. High blast radius.
- **D2/D3 risk:** infrastructure that stays unconsumed may still rot; the boundary-pinning + involution + (D3) parity tests are the mitigation.
- Open: does the hybrid-mesh / CFD use case RDR-010 motivates ever require deep tet *queries*, or only deep *geometry* at construction time? The answer decides D1 vs D3.

## References

- Gap analysis 2026-05-31, axes 3, 4, 6 (T1 scratch `gap-axis-3/4/6`; T2 `rdr/pyramid-t8code-remediation-plan-2026-05-31`).
- RDR-010 Close section (honest scope caveat); post-mortem `docs/rdr/post-mortem/010-pyramid-spatial-index.md`.
- t8code `main@76a5347b`: `t8_dpyramid_bits.c`.
