---
id: RDR-015
title: Reconcile Simulation Bubble-Grid Coordinate Space — Revive the Dead Migration Path
status: draft
date: 2026-06-06
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

## Acceptance Criteria (to be finalised at gate)

1. A documented, single coordinate contract: where entity positions live, and (if Option C) the explicit
   world↔RDGCS transform, applied consistently across population, physics, bubble bounds, and containment.
2. Bubble grid is a well-formed spatial structure for the chosen option — at minimum, no duplicate keys; for
   Option B, a same-level adjacent partition with no all-containing root catch-all.
3. A directed regression: an entity placed adjacent to a shared bubble boundary, moving toward the neighbor,
   produces a **successful** migration (`getTotalMigrations() > 0`) within a bounded, deterministic tick count.
4. Entity conservation (exact, no loss) and no-duplication continue to hold across migrations (regression in
   `MultiBubbleSimulationMigrationTest` stays green).
5. `Luciferase-0frcy.131` closed; the secondary mixed-level / duplicate-key defect resolved or explicitly
   tracked.

## Remaining Open Questions (for research)

- Is the simulation intended to support genuine bubble-to-bubble migration in production, or is it a
  test/demo scaffold? (Determines whether Option A is acceptable.)
- What is the authoritative coordinate space for entities — and does any renderer/ghost/query consumer depend
  on the current WorldBounds-scale positions?
