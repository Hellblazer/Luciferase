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

## Research Findings (2026-06-05, code-verified)

### F1 — Live scope is EDGE neighbors at ±1 level only (resolves D2)

- The production callers of `TetreeNeighborFinder.findEdgeNeighbors` are `Tetree.findEntityNeighbors`
  (`Tetree.java:422`, loops 6 edges) and `Tetree.addNeighboringNodes` (`Tetree.java:1390`, loops 6
  edges); the latter is reached from collision (`CollisionEngine.java:491`) and k-NN
  (`KnnSearcher.java:487`) via `AbstractSpatialIndex` BFS. These are the live paths.
- `findEdgeNeighbors` queries cross-level at **±1 only**: parent `level-1`
  (`TetreeNeighborFinder.java:127`, guard `level > 0` L125) and child `level+1` (L133, guard
  `level < getMaxRefinementLevel()` L132). **Not arbitrary depth.**
- `findVertexNeighbors` (`TetreeNeighborFinder.java:274`) is **test-only in production** —
  `addNeighboringNodes` calls only `findEdgeNeighbors`. Its cross-level traversal spans full depth
  (coarser loop `level-1..0` L324-329; finer `level+1` L334), but no live caller exercises it.
- Max refinement level = 21 (`MortonCurve.java:14`); pure-Tetree entities (`minTetLevel = -1`) can sit
  at any level 0..21. No `minTetLevel` guard exists in the neighbor methods.
- **Conclusion (D2):** the live production requirement is **edge neighbors at parent/child (±1)** only.
  Vertex neighbors and deeper-than-±1 edge traversal are test-contract scope, not live — so they may be
  implemented to satisfy their existing tests (`TetreeEdgeNeighborTest`, `TetreeVertexNeighborTest`)
  without over-building, and the RDR-012 deep-tet fence is respected (no new deep insertion).

### F2 — No new connectivity tables needed; derive from existing Bey/face tables (resolves D3)

- **Edge incidence** is NOT directly tabled but is fully derivable: each of the 6 edges touches exactly
  2 faces (edge→face map at `TetreeNeighborFinder.java:104-110`); children touching edge E =
  `CHILDREN_AT_FACE[type][F1] ∩ CHILDREN_AT_FACE[type][F2]` (`TetreeConnectivity.java:274`). Building
  blocks: `getChildrenAtFace`, `getChildFace` (used at `TetreeNeighborFinder.java:367/378`), and the
  existing working cross-level FACE traversal `findDescendantsAtLevel` (`:359/380`) — reuse it.
- **Vertex incidence** is partially tabled: `CHILD_VERTEX_PARENT_VERTEX`
  (`TetreeConnectivity.java:346-363`) gives each Bey child's 4 vertices as parent-vertex / edge-midpoint
  / center references. Invert it (parent-vertex → containing Bey children); optionally cache or add a
  read-only `VERTEX_TO_BEY_CHILDREN` companion table if hot.
- **Conclusion (D3):** compose existing primitives; no genuinely new enumeration logic. Reuse
  `findDescendantsAtLevel` as the cross-level traversal skeleton.

### F3 — Validation is SYMMETRIC MEMBERSHIP, not dualFace involution (resolves Q3)

- Face involution (`neighbor(neighbor(e,f).dualFace) == e`) relies on a one-to-one face↔dualFace map
  (`Tet.faceNeighbor` dualFace at `Tet.java:1440/1456`; oracle `T8codeDtetFaceNeighborOracleTest.java:182-192`).
- Edges/vertices are **one-to-many**: an edge is shared by a RING of tets, a vertex by a STAR; return
  types are `List` (`Tetree.java:374/553`) and there is no `dualEdge`/`dualVertex`. So the involution
  form does not apply.
- The correct, already-in-repo validation is **symmetric membership**: for every neighbor `n` of `e`,
  `e ∈ neighbors(n)`. Pattern: `PyramidNeighborParityTest.reciprocitySweep`
  (`PyramidNeighborParityTest.java:115-144`), already applied to `findEdgeNeighbors`/`findVertexNeighbors`
  for pyramids — directly adaptable to Tetree.
- **Conclusion (Q3):** the test harness is a DFS (depth 4-5) over a refined Tetree asserting
  symmetric-membership reciprocity for all 6 edges and 4 vertices, PLUS hand-worked exact-count fixtures
  (ring/star size) at a small fixed level — not `assertTrue(count >= 0)`, and NOT a dualFace involution.

### F4 — Authoritative edge→face table; the Finder's inline table is WRONG (gate Critical, resolved)

Two parallel implementations carry edge→face tables, and they **conflict**:

- `TetreeNeighborDetector.EDGE_FACES` (`TetreeNeighborDetector.java:68-75`):
  `e0→{2,3}, e1→{1,3}, e2→{1,2}, e3→{0,3}, e4→{0,2}, e5→{0,1}`
- `TetreeNeighborFinder` inline (`TetreeNeighborFinder.java:104-110`):
  `e0→{0,2}, e1→{0,3}, e2→{1,3}, e3→{0,1}, e4→{1,2}, e5→{2,3}`

Both classes use the **same** edge→vertex-pair numbering (`EDGE_VERTICES`,
`TetreeNeighborDetector.java:50-57`): `e0=(0,1) e1=(0,2) e2=(0,3) e3=(1,2) e4=(1,3) e5=(2,3)`, so this is
**not** a numbering-convention artifact. Faces follow the t8code convention face *i* = opposite vertex *i*
(`TetreeConnectivity.java:242-259`, `FACE_CORNERS`): `f0={1,2,3} f1={0,2,3} f2={0,1,3} f3={0,1,2}`.

Deriving the ground truth — edge `(va,vb)` is bounded by exactly the faces containing **both** va and vb:

| edge | verts | bounding faces (derived) |
|------|-------|--------------------------|
| 0 | (0,1) | {2,3} |
| 1 | (0,2) | {1,3} |
| 2 | (0,3) | {1,2} |
| 3 | (1,2) | {0,3} |
| 4 | (1,3) | {0,2} |
| 5 | (2,3) | {0,1} |

The derived mapping **matches `TetreeNeighborDetector.EDGE_FACES` exactly**; the
`TetreeNeighborFinder` inline table is **incorrect** (e.g. edge 0 should be {2,3}, not {0,2}). This is a
latent defect in the existing same-level edge code too, masked because the F2 intersection
`CHILDREN_AT_FACE[F1] ∩ CHILDREN_AT_FACE[F2]` is commutative and some wrong-pair intersections happen to
coincide, and because no test asserts exact cross-level edge counts.

**Resolution:** the implementation MUST use the derived/`EDGE_FACES` table as the single authoritative
source (promote it into `TetreeConnectivity` as the canonical `EDGE_FACES` and have both
`TetreeNeighborFinder` and `TetreeNeighborDetector` consume it), and **correct the wrong inline table**.
t8code parity of the edge→face table is an acceptance gate (below).

## Decision (updated post-research — proposed for gate)

- **D1' (implement, scoped):** Implement `findEdgeNeighborsAtLevel` for the ±1 live case first
  (parent/child edge incidence via `CHILDREN_AT_FACE` ∩ edge-faces, traversing with
  `findDescendantsAtLevel`). Implement `findVertexNeighborsAtLevel` /
  `findVertexNeighborsAtFinerLevels` to satisfy their existing test contract (inverted
  `CHILD_VERTEX_PARENT_VERTEX`). No new deep-tet insertion (RDR-012 fence intact).
- **D2' RESOLVED:** live = edge ±1; vertex + deeper = test-contract scope, not live. Cover the consumed
  range; do not over-build the deep band.
- **D3' RESOLVED:** reuse existing tables/primitives; no new connectivity tables (optional
  `VERTEX_TO_BEY_CHILDREN` cache only if profiling shows a hot path).
- **Validation RESOLVED:** symmetric-membership reciprocity DFS + exact-count fixtures, adapting
  `PyramidNeighborParityTest.reciprocitySweep`. Keep the 21 previously-broken live tests green.
- **Edge→face table RESOLVED (F4):** the derived/`TetreeNeighborDetector.EDGE_FACES` mapping is
  authoritative; the `TetreeNeighborFinder` inline table (L104-110) is wrong and is corrected as part of
  this work. Promote a single canonical `EDGE_FACES` into `TetreeConnectivity`; both neighbor classes
  consume it.
- **Vertex scope RESOLVED:** D1' governs — implement `findVertexNeighborsAtLevel` /
  `findVertexNeighborsAtFinerLevels` **full-depth** to satisfy the existing `TetreeVertexNeighborTest`
  contract (vertex incidence via inverted `CHILD_VERTEX_PARENT_VERTEX`). Note: a *separate* working
  same-level vertex impl already exists in `TetreeNeighborDetector.findVertexNeighbors` (the ghost path);
  the stubs being fixed are the `TetreeNeighborFinder` cross-level helpers only.

## Acceptance Criteria

1. The three helpers (`findEdgeNeighborsAtLevel`, `findVertexNeighborsAtLevel`,
   `findVertexNeighborsAtFinerLevels`) perform real traversal; no empty-list placeholders remain.
2. **Edge→face t8code parity:** a single canonical `EDGE_FACES` table (the F4-derived mapping) is the
   only source; the wrong `TetreeNeighborFinder` inline table is removed. A test asserts the table equals
   the geometric derivation (each edge → the two faces containing both its vertices) for the face
   convention in `FACE_CORNERS`.
3. **Symmetric-membership reciprocity** DFS (depth 4–5 refined Tetree) over all 6 edges and 4 vertices:
   for every neighbor `n` of `e`, `e ∈ neighbors(n)` (adapt `PyramidNeighborParityTest.reciprocitySweep`).
4. **Non-vacuous exact-count fixtures:** at least one concrete `(type, level, edge)` and one
   `(type, level, vertex)` assert the **exact** coarser+finer neighbor count (the count is now mechanically
   derivable from `CHILDREN_AT_FACE` ∩ `EDGE_FACES` for edges and the inverted
   `CHILD_VERTEX_PARENT_VERTEX` for vertices) — explicitly guarding against the empty-set-both-sides
   vacuity that bare reciprocity admits. `assertTrue(count >= 0)` is forbidden.
5. The 21 previously-broken Tetree collision/integration tests stay green (live-path canary).
6. No deep-tet insertion (RDR-012 fence): the helpers are read-only neighbor queries.

## Remaining Open Question (safe to defer to implementation)

1. The specific numeric ring/star counts for the criterion-4 fixtures — mechanically derivable from the
   now-authoritative tables during implementation; the *requirement* (assert an exact count for ≥1 edge
   and ≥1 vertex case) is locked above, only the literal numbers are derived in-code.
