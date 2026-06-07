---
id: RDR-018
title: Dynamic Topology (Split/Merge) vs the RDR-015 Single-Level Migration Partition
status: implemented
date: 2026-06-06
accepted_date: 2026-06-06
closed_date: 2026-06-07
reviewed-by: self
supersedes: []
related: [RDR-015, RDR-010, RDR-012, RDR-014]
beads: [Luciferase-9eyqy, Luciferase-0frcy]
---

# RDR-018: Dynamic Topology (Split/Merge) vs the RDR-015 Single-Level Migration Partition

## Status

**Implemented (closed 2026-06-07).** Option B shipped in full; all 8 implementation ACs landed through
stacked review (epic `Luciferase-3iony`), the original defect `Luciferase-9eyqy` is resolved, and the AC-7
scope ledger (`Luciferase-xtyki`) is reconciled with 3 explicit forward deferrals (all benign while split/merge
remain off the live tick path — F1/RDR-012 D2). Post-mortem: `docs/rdr/post-mortem/018-dynamic-topology-vs-single-level-partition.md`.

Created from `Luciferase-9eyqy`, the dynamic-topology follow-up that RDR-015 explicitly
deferred (RDR-015 `## Scope decision`, AC-7). **Decision direction locked: Option B (mixed-level hierarchical
router)** — see `## Decision` below. Research recorded + verified (`018-research-1`, `-verification`). **Gate
PASSED 2026-06-06** (0 critical; 5 significant + 4 observations from the Layer-3 critique all folded into the
Decision/Design/Alternatives/AC sections — see the `(gate Sn/On)` markers).

This RDR does **not revert RDR-015.** RDR-015's single-level partition remains correct as the *initial* grid
state and for a non-splitting run, and reviving static migration was a genuine prerequisite (you cannot route
across a refinement forest until you can route across a flat tiling). Option B *generalizes* the router from a
single fixed level to a refinement forest of leaf bubbles. The RDR-015 deferral was deliberate scope
discipline, not an error.

## Context

RDR-015 (closed, implemented) made the simulation bubble grid a **single-level spatial partition** (Option B):
`TetreeBubbleGrid.createBubbles(int, WorldBounds, long)` tiles the `WorldBounds`-Cartesian domain with
non-overlapping, no-duplicate-key cells, all at one partition level `L`, and publishes `L` via
`getPartitionLevel()` (`TetreeBubbleGrid.java:224,308`). Migration routing depends on that single level:

- `TetrahedralContainmentChecker.locateDestinationBubble` queries `tetree.locateTetrahedron(position, L)` and
  routes the escaped entity to the bubble at the resulting level-`L` key (`TetrahedralContainmentChecker.java:146-179`).
- `TetrahedralMigrationRouter` reads `getPartitionLevel()` for the same purpose (`TetrahedralMigrationRouter.java:93`).
- `EntityDistribution` places entities at level `L` (`EntityDistribution.java:100`).

RDR-015's single-level invariant was deliberately scoped as an **initialization-and-migration guarantee for a
grid that does not split or merge during the run.** The interaction with dynamic topology was deferred here.

## Problem

`TopologyExecutor` (`TopologyExecutor.java:111-112,243-269`) drives `BubbleSplitter.execute` and
`BubbleMerger.execute` during a run. Two of those operations break the single-level partition the router
assumes:

1. **Split inserts a finer level.** `BubbleSplitter` allocates the new child at `startLevel = parentLevel + 1`
   (`BubbleSplitter.java:219`, now `~229` after the hvjdj edits). The moment a split fires, the grid contains a
   level-`L+1` cell. The router still queries at `L`, so an entity inside the finer split cell locates to the
   level-`L` key — which after the split may have no bubble, or a stale parent — and is **mis-routed or
   dropped**. The split cell is invisible to migration.

2. **Merge can punch a coverage hole.** `BubbleMerger.execute` moves bubble2's entities into bubble1 and
   removes bubble2 from the grid (`BubbleMerger.java:307`). bubble2's spatial region is now untiled: an entity
   that later escapes into that region locates to a level-`L` key with no bubble → no destination → dropped.
   (Merge keeps bubble1's level, so unlike split it does not introduce a finer cell, but it violates the
   *coverage* half of the partition invariant.)

**Consequence:** a steady-state simulation that runs splits/merges **cannot be assumed to migrate correctly.**
This is latent (P2): split/merge are not on the default `tick()` path today, but the wiring exists and the
hole is silent.

### Why this is not a one-line fix

A level-`L` Kuhn tetrahedron has no *same-level* finer subdivision — Bey subdivision necessarily goes to
`L+1` (8 children). So "split a cell into smaller cells" is fundamentally a multi-level operation. Preserving
RDR-015's single-level invariant and supporting spatial subdivision-based load balancing are in direct
tension. That tension is the actual decision, and it has several genuinely different resolutions with
different semantics and cost — hence an RDR rather than a patch.

## Decision

**Option B — Mixed-level hierarchical router.** Keep true Bey subdivision (`L → L+1`) for splits and teach the
migration router to resolve a **refinement forest** of leaf bubbles at mixed levels, rather than querying one
fixed partition level. This is locked as the direction (pending gate). It is the only option that supports
genuine spatial-subdivision load balancing while staying faithful to the tetree's inherently multi-level
geometry, and it reuses lucien's existing cross-level machinery instead of inventing a parallel scheme.

Rationale: the over-capacity problem is spatial subdivision, and subdivision of a Kuhn tet is intrinsically
multi-level (Bey → `L+1`). Forcing a single level (Options A/C/D) either caps capacity, stops the world, or
declines the problem. The router complexity B reintroduces is the *correct* complexity — the old level-0-first
scan was a *wrong* multi-level router, not evidence that multi-level routing is wrong (see R1).

### Design (to be validated/refined at research + gate)

**Invariant restatement (replaces RDR-015's flat-tiling invariant).** The grid is a **leaf partition of a
tetree refinement forest**: the set of bubble keys forms a set of leaves such that (1) every point in the
**open interior** of `WorldBounds` is contained in exactly one leaf bubble (full coverage, no interior
overlap), and (2) a leaf is at level `≥ L` (the initial uniform level). Initial `createBubbles` produces the
uniform-`L` special case — RDR-015's partition is the depth-0 refinement of this forest, so RDR-015 stays valid
as the base case.

*Boundary precision (gate S1).* Points lying exactly on a 2D tet face are NOT covered by the "exactly one"
claim — tet face neighbors are non-conforming Bey-SFC (share 0–3 vertices; CLAUDE.md face-neighbor caveat), so
a face point can satisfy `contains12DOP` for more than one leaf. The disambiguator is `contains12DOP`'s
**closed-simplex strict-ordering tie-break** (`lucien/doc/AABT_12DOP_EXACT_CONTAINMENT.md`): face-boundary
points resolve deterministically to a single leaf. The no-overlap guarantee is therefore over open interiors;
coverage of the cube is exact because Bey subdivision tiles the parent with no gaps (verified by
`T8codeDtetOracleTest`). AC-3's regression must place test entities in cell interiors (not on faces) so it is
non-vacuous by construction rather than circumstantially.

**Routing algorithm (replaces the fixed-level query) — specified (gate S2).** To locate the destination for an
escaped entity at `position`:
1. Maintain a `maxLeafLevel` watermark in `TetreeBubbleGrid` (max level over the current leaf set), updated on
   every split (raise) and merge (recompute or lower). "Finest plausible level" = `maxLeafLevel`.
2. `locate = tetree.locateTetrahedron(position, maxLeafLevel)`. Walk **up** the parent-key chain
   (`key → parent key`, one level shallower per step) testing `bubbleGrid.containsBubble(key)` at each step;
   return the first key that has a bubble — the deepest existing leaf containing `position`.
3. **Termination:** each step strictly decreases the level (monotone toward L0), so the walk visits finitely
   many levels and halts. If it reaches below `L` (the base level) with no hit, `position` is outside all leaf
   regions → return `null` (out-of-`WorldBounds` / empty-cell contract, same "stay" semantics as today).

The walk uses the `TetreeKey` parent relationship (`getCoordBitsAtLevel`/`getTypeAtLevel` truncation +
`Tet.tetrahedron(key)`), **never** a level-0-first existing-bubble scan (R1 — that was the RDR-015 bug). The
equivalent "locate at `L`, descend to the child leaf" dual is rejected: descent must pick *which* child
contains the position at each step, which is exactly the per-step `contains12DOP`/`locateTetrahedron` work the
up-walk already does, with no watermark saved — the up-walk is the simpler correct form.

**Split (`BubbleSplitter`).** Already inserts the `L+1` child (`BubbleSplitter.java:219`). Under B this becomes
*correct* rather than a bug, but split must maintain the leaf-partition invariant: subdividing one leaf into
its Bey children must replace the parent leaf with a full set of child leaves covering the parent's region (no
parent-leaf left behind to overlap, no uncovered child region). Whether a split refines into all 8 Bey children
or a partial set is a research item (RQ-2/RQ-6).

**Merge (`BubbleMerger`).** Merge must be the inverse: replacing a set of sibling child leaves with their
parent leaf, restoring coverage. The current merge (`BubbleMerger.java:307` removes bubble2 and keeps bubble1)
does **not** restore coverage of bubble2's region — it punches the hole in problem #2. Under B, merge must
either re-home bubble2's region under bubble1's leaf (only valid if they are mergeable siblings whose union is
exactly the parent) or be rejected. Arbitrary two-bubble merge is not a leaf-forest operation.

**Affected call sites.**
- `TetrahedralContainmentChecker.locateDestinationBubble` (`:146-179`) — replace the single
  `locateTetrahedron(position, L)` with the up-walk / deepest-leaf resolution.
- `TetrahedralMigrationRouter` (`:93`) — same; stop treating `getPartitionLevel()` as the routing level.
- `TetreeBubbleGrid.getPartitionLevel()` (`:308`) — semantics change: it is now the *initial/base* level, not
  the routing level. Either rename to `getBaseLevel()` or restrict its use to init/distribution.
- `EntityDistribution` (`:100`) — initial placement at base level `L` is unchanged (init is still uniform).
- `AdaptiveSplitPolicy.performSplit` (gate O1) — a second wrong split path: hardcodes `new EnhancedBubble(..,
  (byte) 10, ..)` with no relation to any partition level. Test-only callers today, but it must be replaced or
  deleted alongside the `BubbleSplitter` redesign, not left as a latent third geometry model.
- Ghost/neighbor logic keyed on bubble bounds — must handle cross-level adjacency (RQ-5; reuse RDR-014) **and**
  invalidate `TetreeNeighborFinder`'s key-keyed neighbor cache when the leaf set changes (gate O2): after a
  split replaces a level-`L` leaf with 8 level-`L+1` children, the parent's cached neighbor set is stale.

## Alternatives considered (rejected)

### Option A — Same-level sibling load-shedding (redefine "split")
Keep the partition strictly single-level; redefine over-capacity as load redistribution to existing level-`L`
neighbors. **Rejected:** "split" no longer adds spatial capacity — total capacity is capped at
`N_cells × per-cell-cap`; a globally hot region saturates with nowhere to shed. Changes the meaning of
`SplitProposal` and its certifying consensus. Treats the symptom, not the spatial problem.

### Option C — Global re-partition (level bump)
On aggregate over-capacity, rebuild the entire partition at `L+1` uniformly. **Rejected:** stop-the-world,
coarse, expensive periodic re-tiling — not real dynamic topology. Kept only as the conceptual "preserve a flat
invariant at any cost" baseline.

### Option D — Fence dynamic topology (infrastructure-only)
Make split/merge fail-loud/no-op until a workload needs them. **Rejected as the end state** (it declines the
problem rather than solving it), but acceptable as an *interim* while B is built (RQ-1 found no near-term
splitting workload — F1). **The RDR-012 analogy applies to split ONLY, not merge** (gate S5): RDR-012 D2 fenced
code that was *already correct* and involution-validated but unconsumed — a documentation-only fence sufficed.
The current split path is similarly unconsumed on the live tick (F1), so a doc-only / fail-loud fence is fine
for it. The **merge is categorically different**: `BubbleMerger` is *actively wrong* (F4 coverage hole) and
independently reachable, so a documentation note is NOT sufficient — it must be either fixed (sibling-collapse
semantics) or **hard-fenced** (reject arbitrary two-bubble merge with a fail-loud + pinning test, AC-4). Do not
apply the D2 doc-only pattern to merge.

## Research Findings

Investigation 2026-06-06 (direct codebase verification). Findings are file:line-grounded.

**F1 — Split/merge are NOT on the live tick path (resolves RQ-1).** `MultiBubbleSimulation.tick()`
(`MultiBubbleSimulation.java:282-314`) runs: update entities → `migration.checkMigrations` → ghost sync →
duplicate reconcile → metrics. It never calls `TopologyExecutor`. No production code drives
`TopologyExecutor.execute`/`SplitProposal` on a run loop; `AdaptiveSplitPolicy.performSplit` has no production
callers (noted dead in n7io1). **Implication:** there is no current workload firing splits during a run, so
the silent mis-route (problem #1) is latent, not active. **Option D (fence) is the correct interim** — fencing
costs nothing live today — and B can be built deliberately. This makes the merge coverage-hole (F4) the only
*active* partition risk.

**F2 — DECISIVE: the current `BubbleSplitter` is a plane-based LOGICAL split, geometrically incompatible with a
refinement forest AND self-defeating under live migration (drives RQ-6 + the B redesign).** Migration is
*purely geometric*: escape = `srcTet.contains12DOP(position)` where `srcTet = Tet.tetrahedron(bubbleKey)`, and
routing = `locateTetrahedron(position, level)` (`TetrahedralContainmentChecker.java:107-129`). So **a bubble's
region IS its `TetreeKey`'s tet** — the key is the geometry. But `BubbleSplitter` partitions entities by a
signed-distance plane and keys the new bubble with a *single* `L+1` tet located at the *centroid* of the moved
entities (`BubbleSplitter.java:212-243`). Consequences:
- "Positive half of a parent tet along an arbitrary plane" is **not representable as a `TetreeKey`** — half a
  tet is not a tet. The plane-split model is fundamentally incompatible with the key-as-geometry contract.
- The moved entities are spread across the positive half of the parent `L` cell, but the new bubble's key is
  one tiny `L+1` tet at the centroid. Most moved entities are **not** `contains12DOP` of that `L+1` tet — so on
  the very next `checkMigrations` tick they flag as escaped and `locateDestinationBubble` routes them straight
  back to the parent/source. The split is **self-defeating even today** (masked only by F1).
- **Therefore Option B cannot be layered on the existing splitter.** B requires redesigning split as a true
  **Bey spatial refinement**: replace the parent `L` leaf with its 8 `L+1` children, assign each entity to the
  child that `contains12DOP` it. Then membership and geometric routing agree by construction. The
  `SplitPlaneStrategy` / signed-distance partition machinery becomes obsolete **for geometric key selection**
  (gate O4); the `calculate(BubbleBounds, List<EntityRecord>)` interface may survive in a *policy* role
  (load-imbalance diagnostic deciding *when* to refine), but no longer determines a bubble's region.

**F3 — Bey refinement primitives already exist in lucien (favorable for RQ-3).** `Tet.child(int 0..7)`
(`Tet.java:908`) and `Tet.geometricSubdivide()` → `BeySubdivision.subdivide` (`Tet.java:1516`) produce the 8
children; `tmIndex()` gives their keys; `contains12DOP` does per-entity assignment. The up-walk for routing
maps onto `TetreeKey.getCoordBitsAtLevel`/`getTypeAtLevel` (truncate-to-coarser) plus `Tet.tetrahedron(key)`.
B's routing and refinement reuse existing primitives — net-new code is the *leaf-set bookkeeping* and the
split/merge coverage rules, not the geometry.

**F4 — Merge coverage-hole confirmed and is the only ACTIVE risk (resolves part of RQ-4).**
`BubbleMerger.execute` moves bubble2's entities into bubble1 and `removeBubble(bubble2Id)`
(`BubbleMerger.java:307`), keeping bubble1's key. bubble2's tet region is then untiled; an entity later escaping
into it routes to a key with no bubble → dropped. Because merge (unlike split) *can* be reached independently
and removes coverage, it must be fixed or fenced **even under interim D**. Under B, merge must be the inverse of
Bey refinement: collapse a complete set of sibling child leaves back to the parent leaf — arbitrary two-bubble
merge is not a leaf-forest operation and should be rejected.

**F5 — Ghost layer is already topology-based, partially cross-level ready (favorable for RQ-5).**
`TetreeGhostSyncAdapter` uses `TetreeNeighborFinder` + `bounds.overlaps()` with variable neighbor count (4-12),
not a fixed-level grid config (`TetreeGhostSyncAdapter.java:49-67`). lucien's neighbor finder supports
cross-level face/edge/vertex neighbors (RDR-014). Open item: verify the adapter invokes the finder at each
bubble's *own* level rather than a single partition level, so a refined leaf's ghosts reach coarser neighbors.

**F6 — `getPartitionLevel()` is the routing authority that must change (confirms Decision call-sites).** Live
routing reads the scalar `partitionLevel` in exactly two places — `TetrahedralContainmentChecker.java:153` and
`TetrahedralMigrationRouter.java:93` — plus init placement at `EntityDistribution.java:100`. Only the first two
are routing; init placement stays uniform-`L`. Scope of the router change is small and localized.

### Research-driven recommendation (for the gate)

B remains the destination. Sequence: **(1)** interim — fence `TopologyExecutor` split/merge fail-loud (F1 makes
this free) and fix/fence the merge coverage-hole (F4, the only active risk); **(2)** B core — redesign
`BubbleSplitter` as Bey refinement (F2/F3), convert the router to deepest-leaf up-walk (F6), maintain the
leaf-partition invariant; **(3)** verify ghost cross-level (F5). The hvjdj "centroid skew" residual is
*subsumed* by F2 — the centroid-keyed split is replaced wholesale, not patched.

## Open research questions (remaining for gate)

*RQ-1 resolved by F1, RQ-3 by F3/F6, RQ-4 (merge) by F4, RQ-5 largely by F5. Genuinely open:*

2. **What is the per-cell capacity model?** `BubbleSplitter` triggers at >5000 entities; is the per-cell cap
   the binding constraint, or frame-time? Informs the split refinement policy (RQ-6).
6. **Split refinement policy:** refine the hot leaf into all 8 Bey children (uniform), or a partial/recursive
   set driven by density? F2 establishes refinement is the mechanism; the *granularity policy* is open. Whatever
   is chosen must maintain the leaf-partition coverage/no-overlap invariant.
5b. **Ghost cross-level verification (F5 residual):** confirm `TetreeGhostSyncAdapter` invokes
   `TetreeNeighborFinder` at each bubble's own level so a refined leaf's ghosts reach coarser neighbors (not at
   a single partition level).

## Scope

- **In scope:** the contract between `TopologyExecutor` split/merge and the migration partition + router; the
  coverage and level invariants the router may assume; which option to adopt as the first increment.
- **Out of scope (unless the chosen option requires it):** rebalancing *policy* (when to split/merge), the
  consensus path that certifies proposals, distributed/multi-node partition coordination.

## Acceptance criteria (provisional — finalized at gate)

The ACs are **ordered into the three stages** of the Research-driven recommendation. AC-0 and AC-4 (interim)
complete *before* the B-core ACs (AC-2.5/AC-1/AC-2/AC-3); AC-6 (ghost) follows B-core.

**Stage 1 — interim (before any B-core work):**
0. (gate S4) `TopologyExecutor.execute()` returns a documented failure result (not an exception, not a silent
   no-op) when called with a `SplitProposal` before B-core is complete; a unit test pins this behavior. Mirrors
   the RDR-012 D2 boundary-pinning pattern for the unconsumed split path.
4. (gate S5) The merge coverage-hole (problem #2 / F4) is either fixed (sibling-collapse semantics) or
   **hard-fenced** (arbitrary two-bubble merge rejected fail-loud), with a test pinning the chosen behavior. A
   documentation-only note is explicitly insufficient for merge.

**Stage 2 — B core:**
2.5. (gate S3) `BubbleSplitter` is redesigned as Bey spatial refinement: every entity assigned to a child
   bubble satisfies `child.contains12DOP(entity.position) == true` on the tick immediately following the split
   (the F2 self-defeating mode is gone), and `SplitPlaneStrategy` no longer selects keys. `AdaptiveSplitPolicy`'s
   parallel split path (O1) is replaced or deleted. **AC-3 depends on this.**
1. Option B's design validated: the leaf-partition invariant (open-interior, with the `contains12DOP`
   tie-break), the specified up-walk routing algorithm + `maxLeafLevel` watermark, and the split/merge
   coverage-maintenance rules are confirmed against the codebase, with the capacity model (RQ-2) stated.
2. The router's assumed invariants (leaf level `≥ L`, full interior coverage, no interior overlap over the
   refinement forest) are re-stated to match Option B, with file:line pointers at the `## Decision` call sites
   (incl. `getPartitionLevel()` → base-level rename).
3. A regression that runs the simulation **through at least one split and one merge** and asserts migration
   still routes every escaped entity to a real bubble (no drop, no mis-route) — the non-vacuous successor to
   RDR-015's static `DirectedMigrationRegressionTest`. **Prerequisite: AC-2.5** (against the current splitter
   this test would pass vacuously or false-fail — F2). Test entities placed in cell interiors (S1). Validated
   by involution reciprocity, never by shared-vertex count (non-conforming Bey-SFC — RDR-015 invariant).

**Stage 3 — ghost:**
6. (gate O2 / RQ-5b) `TetreeGhostSyncAdapter` invokes the neighbor finder at each bubble's own level, and the
   neighbor cache is invalidated when the leaf set changes (split/merge), with a test exercising a refined
   leaf's ghosts reaching a coarser neighbor.

7. If anything is deferred again, it is an **explicit** scope boundary with a tracking bead (no silent
   reduction — phase-review-gate discipline).

## Risks

- **R1 — Re-introducing the RDR-015 bug.** Option B must not resurrect the level-0-first catch-all scan that
  resolved every escaped entity to the L0 root. Mixed-level routing must descend via the key hierarchy.
- **R2 — Capacity illusion under Option A.** Load-shedding can mask saturation until a region globally
  saturates, then fail abruptly. Need an explicit "nowhere to shed" failure mode.
- **R3 — Coupling.** Folding rebalancing policy into this RDR would couple two independently-risky changes;
  keep policy out (mirrors RDR-015's increment discipline).
- **R4 — Ghost/distributed surface.** A mixed-level grid changes neighbor adjacency that ghost wiring depends
  on; under-scoping RQ-5 leaves the distributed path undefended.
