---
id: RDR-014
title: Cross-Level Tetrahedral Edge/Vertex Neighbor Traversal for TetreeNeighborFinder
status: draft
date: 2026-06-05
supersedes: []
related: [RDR-010, RDR-012]
beads: [Luciferase-7wzml.20]
---

# RDR-014: Cross-Level Tetrahedral Edge/Vertex Neighbor Traversal for TetreeNeighborFinder

## Status

Draft (2026-06-05). Created from `Luciferase-7wzml.20` (full-build review 2026-06-04, P1 / High,
HOLLOW STUBS). Not yet researched or gated — the Decision section records candidate approaches, not a
locked choice. Implementation is blocked on this RDR reaching `accepted`.

## Context

`lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/TetreeNeighborFinder.java` exposes two
public cross-level neighbor APIs that depend on three private helpers which are **hollow stubs**:

- `findEdgeNeighborsAtLevel(Tet, int edgeIndex, byte targetLevel)` — L392
- `findVertexNeighborsAtFinerLevels(Tet, int vertexIndex, byte startLevel)` — L399
- `findVertexNeighborsAtLevel(Tet, int vertexIndex, byte targetLevel)` — L407

Each allocates an empty `ArrayList` and returns it with a `// This is a placeholder for the complex
geometric calculation` comment (L395 / L403 / L411). No traversal is performed.

They are reached from the **public** API:

- `findEdgeNeighbors` (L88) calls `findEdgeNeighborsAtLevel` for the parent/coarser level (L127,
  guarded by `level > 0`) and the child/finer level (L133, guarded by `level < maxLevel`), `addAll`-ing
  the empty results into `uniqueNeighbors`.
- `findVertexNeighbors` (L274) calls `findVertexNeighborsAtLevel` in a coarser-level loop (L327) and
  `findVertexNeighborsAtFinerLevels` (L334).

Same-level face/edge neighbors **are** computed correctly via `findFaceNeighbor`, so the public methods
return a *partial-but-incomplete* set, not an empty one. A caller cannot distinguish "genuinely no
cross-level neighbor" from "not implemented" — the **ACK-success-with-dropped-data** pattern. Downstream
balancing / ghost / collision logic that relies on complete neighbor sets silently receives wrong
topology with no error.

### Why fail-loud was rejected (the constraint that makes this RDR-scale)

A Wave-6 (2026-06-04) attempt made the cross-level branches throw `UnsupportedOperationException`
(fail-loud instead of silent-incomplete). **It was reverted**: it broke 21 tests across 7 Tetree
collision/integration classes because `Tetree.findEntityNeighbors` (a `TetreeNeighborFinder` caller) and
the BFS path traverse cross-level neighbors in **normal production operation** (collision detection,
bounding-filtered queries, geometric correctness). Fail-loud converts a latent silent-incomplete-topology
into a hard crash on live paths.

So the cross-level branch is not a rare/exceptional path that can be fenced — it is exercised by routine
collision queries. A "logged degradation marker" is therefore also a poor fit: the incomplete case is the
*normal* case for any non-root, non-max-level tet, so any per-call log would fire constantly (noise, not
signal). That leaves only one correct disposition: **actually implement the cross-level traversal.**

### Why this is genuinely subtle (t8code territory)

Per `CLAUDE.md` and RDR-010/012, tetrahedral neighbor geometry is non-trivial:

- `Tet.faceNeighbor()` returns the **non-conforming Bey-SFC** face neighbor, which shares 0–3 vertices
  with the tet — it is **not** a conforming shared-triangle neighbor. Validating tet neighbors with a
  "shares ≥3 vertices" assertion is wrong (it produced a false "confirmed bug" during RDR-010 q3p D).
- The correct validation is **reciprocity / involution**:
  `neighbor(neighbor(e, f).dualFace) == e`, exercised by DFS over a refined tree — the pattern t8code's
  own `t8_gtest_face_neigh.cxx` uses.
- Tet type numbering is aligned to t8code dtet (RDR-010 Luciferase-4pd); cross-level child enumeration
  must use `TetreeConnectivity` / Bey tables consistently (cf. the RDR-010 deep cross-shape work in
  `Tet.tetBoundary` / `allShapeNeighbors`).

Cross-*level* edge and vertex neighbors are a strict generalization of the (already-subtle) same-level
face-neighbor problem, requiring DFS over the children of coarser tets that share the queried edge/vertex
and recursive descent into finer children — validated by involution, not vertex-count heuristics.

## Problem Statement

Implement `findEdgeNeighborsAtLevel`, `findVertexNeighborsAtLevel`, and
`findVertexNeighborsAtFinerLevels` so that `findEdgeNeighbors` / `findVertexNeighbors` return the
**complete** set of edge/vertex-incident neighbors across coarser and finer refinement levels, with the
result validated by reciprocity/involution. No throw on live paths; no silent incompleteness.

## Decision (candidate options — NOT yet locked; pending research + gate)

**D1. Implement cross-level edge/vertex traversal (the bead's primary fix).**
DFS over the children of the edge/vertex-incident coarser tets (for finer neighbors) and ascent to
parents whose subdivision touches the edge/vertex (for coarser neighbors), enumerated via
`TetreeConnectivity` Bey/child tables. Validate every produced neighbor pair by the involution
`neighbor(neighbor(e,f).dualFace) == e` over a refined-tree DFS test harness (mirroring
`t8_gtest_face_neigh.cxx`). Acceptance: non-vacuous tests asserting the **exact expected neighbor
count** (not `assertTrue(count >= 0)`) for hand-worked fixtures at multiple level deltas.

**D2. Scope question — how deep must cross-level traversal go?**
RDR-012 fenced the deep-tet path as infrastructure-only (`PyramidIndex` locate stops at the shallowest
tet leaf). This RDR must decide whether cross-level neighbor completeness is required only within the
production-live shallow band, or to full depth. Research must enumerate the actual `targetLevel` ranges
the live callers (`Tetree.findEntityNeighbors`, BFS, collision) pass, so the implementation covers the
real consumed range and does not over-build the unreachable deep band (consistent with RDR-012 D2).

**D3. Reuse vs. new traversal primitive.**
Determine whether the existing `allShapeNeighbors` / `tetBoundary` corner-walk (RDR-010 cjwr/2l04) and
`TetreeConnectivity` tables already provide the building blocks for edge/vertex incidence, or whether a
new edge/vertex-incidence enumeration is required. Prefer composing existing, oracle-validated
primitives.

## Approach (proposed, pending acceptance)

1. **Research (rdr-research):** (a) enumerate the live `targetLevel`/level-delta ranges the production
   callers pass (resolves D2 scope); (b) port or derive the t8code cross-level edge/vertex incidence
   enumeration; (c) confirm the involution test harness shape from `t8_gtest_face_neigh.cxx`.
2. **Test harness first (TDD):** a reciprocity/involution DFS over a refined Tetree that asserts
   `neighbor(neighbor(e,f).dualFace) == e` for edge and vertex neighbors across level boundaries, plus
   hand-worked exact-count fixtures.
3. **Implement** the three helpers using `TetreeConnectivity` Bey/child tables; keep same-level
   `findFaceNeighbor` behavior unchanged.
4. **Regression gate:** the 21 previously-broken Tetree collision/integration tests must stay green
   (they were the canary for "cross-level is a live path").
5. **Phase-review-gate** cross-walk before close.

## Consequences / Risks

- **Correctness-critical, geometry-subtle.** A plausible-but-wrong implementation silently corrupts
  collision/ghost topology — exactly the failure class the project's stacked-review + involution-oracle
  discipline exists to catch. The involution test harness is the non-negotiable guard.
- **Blast radius:** `TetreeNeighborFinder` feeds collision detection, balancing, and ghost layers.
  Any change must keep the 21 live-path tests green and add exact-count coverage.
- **Scope discipline (D2):** must not over-build the RDR-012-fenced deep band; cover the live range.
- **Until accepted+implemented**, `Luciferase-7wzml.20` remains OPEN and the cross-level neighbor sets
  remain partial. This is a *known, documented* limitation (this RDR + the bead), no longer a silent one
  — but it is **not** resolved by this RDR's creation alone.

## Open Questions (for rdr-research / gate)

1. What exact level-delta ranges do live callers pass? (Decides D2 — shallow-band vs full-depth.)
2. Do `allShapeNeighbors` / `tetBoundary` already enumerate edge/vertex incidence, or is new enumeration
   needed? (D3.)
3. Is the involution `dualFace` defined for edge/vertex neighbors as cleanly as for face neighbors, or is
   a different reciprocity relation required for edge/vertex incidence?
