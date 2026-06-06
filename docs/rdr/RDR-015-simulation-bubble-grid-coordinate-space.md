---
id: RDR-015
title: Reconcile Simulation Bubble-Grid Coordinate Space — Revive the Dead Migration Path
status: accepted
date: 2026-06-06
accepted_date: 2026-06-06
reviewed-by: self
supersedes: []
related: [RDR-003]
beads: [Luciferase-0frcy.131]
---

# RDR-015: Reconcile Simulation Bubble-Grid Coordinate Space — Revive the Dead Migration Path

## Status

Draft (2026-06-06). Created from `Luciferase-0frcy.131`, a defect surfaced during the `Luciferase-0frcy`
simulation deep-review remediation (specifically while hardening the vacuous migration test `j6ybd`). The
migration subsystem is **dead on the live path**: `MultiBubbleSimulation.tick()` commits zero successful
migrations across every fixture probed. Root cause is a coordinate-space mismatch (below). This RDR scopes the
decision of how to reconcile the entity coordinate space with the bubble-grid spatial domain. Not yet
researched or gated.

## Context

`MultiBubbleSimulation` partitions space into "bubbles" (each a tetrahedral region keyed by a `TetreeKey`).
Entities live in bubbles, move under a behavior (e.g. `RandomWalkBehavior`), and when an entity leaves its
bubble it should *migrate* to the neighbor bubble that now contains it. `TetrahedralMigration.checkMigrations`
(called every tick) drives this: `TetrahedralContainmentChecker.checkMigrations(bubble)` emits a
`MigrationRecord` for any entity whose position is `!bounds.contains(position)` and which locates into a
*different existing* bubble; the router then routes and executes the migration, incrementing
`getTotalMigrations()` on success.

Two coordinate spaces are in play:
- **WorldBounds** — the small, user-facing entity coordinate range (e.g. `new WorldBounds(0, 100)`).
  `EntityPopulationManager.populateEntities` samples positions uniformly in this range, and
  `RandomWalkBehavior` keeps entities inside it.
- **RDGCS / tetree coordinates** — the spatial domain of the Tetree and of bubble bounds. Tet cell side
  lengths are powers of two up to `lengthAtLevel(0)` ≈ 2–3 million units.

`EntityDistribution.EntitySpec` documents its `position` as being "in RDGCS coordinates", but
`EntityPopulationManager` actually produces WorldBounds-scale positions, and nothing transforms between the
two.

## Problem Statement

The entity coordinate space (WorldBounds) is decoupled from the bubble-grid spatial domain (RDGCS/tetree).
Entities confined to a tiny WorldBounds corner can never escape their (astronomically larger) bubble bounds,
so the migration subsystem never produces a candidate and `getTotalMigrations()` is permanently 0. The bubble
grid is also not a spatial partition. The migration code, its metrics, and any consumer of bubble-to-bubble
migration are dead on the live path.

### Evidence (root cause, investigated 2026-06-06)

Diagnostic over 2000 deterministic ticks (8 bubbles, 200 entities, `WorldBounds(0, 30)`):
`total=200 entities, escapedBounds=0, migrations=0` — **zero** entities ever left their bubble bounds.

Chain:
1. `EntityPopulationManager.populateEntities` (`EntityPopulationManager.java:105-110`) samples
   `x = min + rand*size` for `WorldBounds(0, N)` with N ≈ 30–100.
2. `EntityDistribution.EntitySpec` (`EntityDistribution.java:67`) treats `position` as RDGCS; entities are
   assigned to bubbles via `Tetree.locateTetrahedron`.
3. Bubble bounds are the tet region at tetree SFC scale. Observed: `L1 [(-741455,0,0),(741455,1482910,
   741455)]`, `L2 [(-370728,..)]`, `L0 [(-1482910,..),(2965821,..)]` — tens of thousands to ~3,000,000 units.
4. `TetrahedralContainmentChecker.checkMigrations` (`TetrahedralContainmentChecker.java:110`) emits a record
   only when `!bounds.contains(position)`. Entities confined to `[0,N]^3` sit in a microscopic corner of every
   bubble's million-unit bounds → never escape → no candidates → `getTotalMigrations()` stays 0.

**Secondary defect:** `TetreeBubbleGrid.createBubbles` distributes bubbles across **mixed levels**
(`createTetAtLevel(level, idx)`): the L0 root bubble spatially contains everything, L1/L2 nest/overlap, and
**duplicate keys** were observed (two bubbles each at `CompactTetreeKey[L1,tm:A]` and `[L2,tm:A]`). The grid is
therefore not a same-level spatial partition; even an escaped entity has ambiguous containment, and
`locateDestinationBubble` (which returns the first level with a matching bubble) would resolve most positions
to the all-containing L0 root.

## Decision (candidate options — NOT yet locked; pending research + gate)

- **(A) Status quo / fence.** Document the migration path as non-functional and gate it off (or remove the
  dead metric), accepting that bubble-to-bubble migration is not a supported feature. Cheapest; abandons the
  feature.
- **(B) Same-level spatial partition over the world domain (preferred candidate).** Build the bubble grid as a
  set of *adjacent, same-level* tets that tile the entity world domain, so an entity crossing a bubble face
  lands in a real neighbor bubble. Requires `createBubbles` to produce a partition (one level, adjacent keys,
  no duplicates, no L0 catch-all) sized to where entities actually live, and the entity coordinate range to
  match that tiling. Revives migration with clean topology; aligns bubble neighbor-finding with
  `TetreeNeighborFinder` (RDR-014).
- **(C) Explicit world↔RDGCS transform.** Keep the mixed-level grid but introduce a single, consistently
  applied transform mapping WorldBounds ↔ RDGCS, applied in population, physics (RandomWalk clamps to
  WorldBounds), bubble-bounds comparison, and containment. Smaller surface than re-architecting the grid, but
  leaves the overlapping/duplicate-key grid topology (B's secondary fix) unaddressed.

## Approach (proposed, pending acceptance)

1. **Research:** confirm the intended coordinate contract — is `EntitySpec.position` meant to be RDGCS (as
   documented) or WorldBounds (as produced)? Determine whether any non-test consumer relies on the current
   WorldBounds positions. Verify the duplicate-key behaviour of `createTetAtLevel(level, idx)`.
2. **Decide B vs C** at the gate, with the secondary partition defect (mixed levels / duplicate keys) folded
   into B or filed separately under C.
3. **Implement** the chosen reconciliation across population, physics, bubble creation, and containment.
4. **Directed regression:** a test that places an entity adjacent to a shared bubble boundary with a velocity
   toward the neighbor and asserts a *successful* migration (`getTotalMigrations() > 0`), plus the existing
   conservation/no-duplication invariants (already pinned by `MultiBubbleSimulationMigrationTest`, j6ybd).

## Consequences / Risks

- **Blast radius:** touches population, physics/behavior clamping, bubble creation, containment, migration,
  and any ghost/neighbor logic keyed on bubble bounds. Option B is the larger change; Option C is narrower but
  leaves the grid topology defective.
- **Determinism:** the fix must keep tick-driven simulation deterministic (seeded behavior) so the new
  directed migration test is reproducible.
- **Interplay with RDR-003 (FCC-aligned spatial indexing):** the coordinate model should not contradict the
  RD/FCC overlay decisions; research must check for conflicts.
- **Doing nothing** keeps a dead-but-present subsystem (misleading metrics, latent consumer breakage).

## Acceptance Criteria (locked at gate, 2026-06-06)

Strengthened after the Layer-3 critique (2 Critical, 4 Significant) to pin Option B concretely rather than
generically. See `## Scope decision` below for the split/merge boundary (Critical-1).

1. **Single coordinate contract (documented).** Entity positions are WorldBounds-scale Cartesian everywhere
   (F1/F2). The aspirational `EntitySpec.position` "RDGCS coordinates" javadoc is corrected to say Cartesian.
   No world↔RDGCS scale transform is introduced (Option C rejected).
2. **Partition construction is specified, not aspirational** (resolves S-1). `createBubbles` (or its
   replacement) builds a SINGLE-level partition tiling the WorldBounds domain:
   - **Level selection:** the partition level `L` is chosen so a level-`L` Tet cell-edge ≤
     `WorldBounds.size() / cbrt(N_bubbles)` (i.e. cells are no coarser than the requested bubble granularity);
     reuse RDR-003's `SpatialLevelHeuristic` if/when shipped, otherwise compute `L` directly. `L` MUST be > 0
     (no L0 root bubble).
   - **Seeding + tiling:** seed from the level-`L` Tet containing the WorldBounds centre
     (`Tet.locatePointBeyRefinementFromRoot` / Morton conversion, the `PredatorPreyGridDemo` pattern), then
     BFS over **same-level face neighbors** (`TetreeNeighborFinder.findFaceNeighbor`), including a level-`L`
     tet iff its centroid lies inside WorldBounds; terminate when no uncovered in-bounds neighbor remains.
   - **Adjacency validation uses involution** (resolves S-3): BFS adjacency is confirmed by reciprocity
     (`faceNeighbor(faceNeighbor(t,f).dualFace).==t`), NEVER a shared-vertex count (the Bey-SFC face neighbor
     is non-conforming, sharing 0–3 vertices — CLAUDE.md).
3. **Well-formed-partition structural test** (resolves S-2). A named test
   (`TetreeBubbleGridPartitionTest`) asserts: every bubble key is at the same level `L`; no key is the L0 root
   `tmIndex`; no duplicate keys; and the face-neighbor BFS from any key reaches all others (the partition is
   connected). No bubble bounds nest/contain another.
4. **Router routes to the topologically-correct neighbor, not a catch-all** (resolves Critical-2, S-4).
   `TetrahedralContainmentChecker.locateDestinationBubble` and `TetrahedralMigrationRouter.routeMigration`
   replace their level-0-first `for level 0..10` scan with a direct lookup at the partition level
   `L` (`tetree.locateTetrahedron(position, L)`), so an escaped entity routes to the actual adjacent bubble.
5. **Directed regression is non-vacuous** (resolves Critical-2). A test places an entity adjacent to a KNOWN
   shared face with a specific neighbor bubble `B`, moving toward `B`, and asserts the entity migrates to **`B`
   specifically** — `destinationBubbleKey == expectedNeighborKey` — within a bounded deterministic tick count,
   NOT merely `getTotalMigrations() > 0` (which a catch-all router would also satisfy).
6. **Conservation/uniqueness preserved.** Exact entity conservation (no loss) and no-duplication continue to
   hold across migrations (`MultiBubbleSimulationMigrationTest`, j6ybd, stays green).
7. **Dynamic-topology follow-up filed.** A follow-up bead is created capturing the split/merge-vs-partition
   question (`## Scope decision`), referencing `BubbleSplitter.java:219` and the `TopologyExecutor` tick
   wiring, linked to `Luciferase-0frcy.131`.
8. **Bead hygiene.** `Luciferase-0frcy.131` closed; the secondary mixed-level/duplicate-key defect is resolved
   by AC-2/AC-3 (not merely tracked).

## Remaining Open Questions (for research)

- ~~Is the simulation intended to support genuine bubble-to-bubble migration in production?~~ **Resolved: F3.**
- ~~What is the authoritative coordinate space for entities, and which consumers depend on WorldBounds-scale
  positions?~~ **Resolved: F1, F2.**

## Research Findings (2026-06-06, code-verified)

Investigation via `codebase-deep-analyzer` over the `simulation` module. All findings high-confidence with
file:line evidence (F4 partition-construction medium). **Net: Option B is strongly preferred; Option C is
structurally blocked.** The Decision section above is updated accordingly.

**F1 — Authoritative coordinate space is WorldBounds-scale Cartesian.** The `EntitySpec.position` "RDGCS
coordinates" javadoc (`EntityDistribution.java:67`) is aspirational and was never implemented. Physics
(`EntityPhysicsManager.updateBubbleEntities` clamps to `worldBounds`) and `RandomWalkBehavior` (bounces at
`worldBounds.min()/max()`) treat positions as WorldBounds-scale Cartesian. `BubbleBounds.contains`
(`BubbleBounds.java:235`) DOES call `toRDG(position)` — but `toRDG` is a coordinate-basis ROTATION
(`(-x+y+z)/√2, …`), not a scale change, so a WorldBounds point (50,50,50) → RDGCS ≈ (0,35,35), still
microscopic vs the ±741 K bubble bounds. **The scale mismatch is the fatal defect; there is no scale transform
anywhere.** Critically, `toRDG` produces NEGATIVE values (e.g. `toRDG(1,0,0)=(-1,1,1)`), and the Tetree
requires non-negative coordinates (`SpatialNeighborIndex.java:84-87`) — so RDGCS entity positions are
structurally incompatible with Tetree insertion.

**F2 — Every non-test consumer uses WorldBounds-scale Cartesian.** Rescaling entity positions into RDGCS
(Option C) would break: `EntityVisualizationServer.getEntityDTOs` (`:478`) and
`MultiBubbleVisualizationServer.getAllEntityDTOs` (`:478`) (JSON/WebSocket emission to render clients),
`SimulationQueryService.getAllEntities` (EntitySnapshot), `EntityStreamConsumer.onMessage` →
`regionManager.updateEntity`, `TetreeGhostSyncAdapter.isNearBounds` (Cartesian distance vs ~20-unit
aoiRadius), and `SpatialNeighborIndex.insert` (`:146`, Tetree non-negative constraint). Option C's transform
must propagate through the rendering pipeline — the draft's "smaller surface" framing was wrong.

**F3 — Migration is a production feature, not a scaffold.** Wired into two production tick loops
(`MultiBubbleSimulation.tick` → `migration.checkMigrations`; `GridMultiBubbleSimulation.java:408`), exposed via
public observability (`getMigrationMetrics()` on both), and exercised by a dedicated benchmark
(`SimpleMigrationNode`). No `main()` runs it end-to-end today (the demos `PredatorPreyGridDemo` /
`EntityVisualizationServer` predate `MultiBubbleSimulation` integration), but it is clearly intended-live.
**Option A (fence/abandon) is therefore not acceptable.**

**F4 — `createBubbles` cannot produce a spatial partition (secondary defect confirmed).** It iterates
`level = 0..numLevels-1`, creating bubbles at MULTIPLE levels: `createTetAtLevel(0,0)` always returns the L0
root `Tet(0,0,0,0,0)` whose bounds cover the entire positive octant; L1+ bubbles are topological children
nested inside it. So any entity that locates into an L1 child also locates into L0, and
`TetrahedralContainmentChecker`'s level-0-first scan resolves almost everything to the all-containing root.
The `usedKeys` HashSet only blocks bit-identical keys, not the structural nesting. **`PredatorPreyGridDemo`
already side-steps `createBubbles`** with the correct Option-B pattern (fixed level, world-position → Morton
conversion, direct `grid.addBubble(bubble, key)`) — evidence the author knew `createBubbles` isn't a
partition. Option B's construction uses `TetreeNeighborFinder.findFaceNeighbor`/`findNeighborsAtLevel` (the
RDR-014 work) to enumerate adjacent same-level tets via reciprocity-correct BFS (validate adjacency by
involution, NOT shared-vertex count — the Bey-SFC face neighbor is non-conforming).

**F5 — RDR-003 reinforces Option B and conflicts with Option C.** RDR-003's `SpatialLevelHeuristic` is built
on the explicit premise that "VoN entity positions are placed directly into [Tetree absolute coordinate
space] without rescaling, so AoI radii are comparable to cell-edges 1:1" — i.e. WorldBounds-scale Cartesian.
Its RD/FCC overlay (`RDView`) is a **query-time** construct over a Cartesian-coordinate Tetree, not a
storage-time coordinate. Option B (entities stay Cartesian) is fully compatible; Option C (entities in RDGCS)
contradicts both the heuristic and the Tetree non-negative constraint (F1).

### Decision (updated post-research — proposed for gate)

**Option B — build the bubble grid as a same-level adjacent spatial partition tiling the entity (WorldBounds
Cartesian) domain.** Entity positions remain WorldBounds-scale Cartesian (the de-facto authoritative space,
F1/F2/F5); `createBubbles` is reworked to emit a single-level, non-overlapping, no-duplicate-key partition
sized to the world domain (formalizing the `PredatorPreyGridDemo` pattern, F4), so a face crossing routes to a
real neighbor bubble. Option A is rejected (F3: production feature); Option C is rejected (F1/F2/F5:
Tetree non-negative blocker + rendering blast radius + RDR-003 conflict).

## Scope decision — dynamic topology (split/merge) is out of scope (resolves Critical-1)

The Layer-3 critique correctly observed that `BubbleSplitter` creates split children at `parentLevel + 1`
(`BubbleSplitter.java:219`), so a split would re-introduce a finer-level bubble and break the same-level
partition invariant the moment dynamic topology fires — silently re-breaking migration.

**Decision: RDR-015 scopes ONLY the static migration partition.** The same-level partition invariant (AC-2/AC-3)
is an **initialization-and-migration** guarantee for a grid that does not split/merge during the run. The
interaction between dynamic topology (`TopologyExecutor` split/merge) and the same-level migration partition —
i.e. whether splits must be redefined as same-level **sibling replacement** (replace one level-`L` bubble with
its level-`L` neighbors) rather than inserting a level-`L+1` child, or whether the router must handle a
post-split mixed-level grid — is a **separate, larger decision deferred to a follow-up RDR**. This is an
explicit, not silent, scope boundary:

- The directed-migration regression (AC-5) runs on a freshly-constructed partition with no intervening
  split/merge, so it is valid and non-vacuous under this scope.
- A follow-up bead MUST be filed (AC-7) capturing the split/merge-vs-partition question, referencing
  `BubbleSplitter.java:219` and the `TopologyExecutor` tick wiring, so the deferral is tracked and a
  steady-state simulation that runs splits is not silently assumed to migrate correctly.

Rationale: reconciling the coordinate model + reviving static migration is already a substantial, self-contained
change (population docs, `createBubbles` rewrite, router fix, tests). Folding the dynamic-topology partition
contract into the same RDR would couple two independently-riskful changes; the static fix is a prerequisite for
the dynamic one regardless, so it is the correct first increment.
