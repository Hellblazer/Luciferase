---
title: "Decompose the AbstractSpatialIndex God-Class"
id: RDR-008
type: Architecture
status: closed
priority: medium
author: hal.hildebrand
reviewed-by: self
created: 2026-05-24
accepted_date: 2026-05-25
implemented_date: 2026-05-28
closed_date: 2026-05-28
post_mortem: docs/rdr/post-mortem/008-decompose-abstractspatialindex.md
related_issues: [Luciferase-x5i, RDR-002, RDR-003, RDR-007, Luciferase-aos]
---

# RDR-008: Decompose the AbstractSpatialIndex God-Class

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

`AbstractSpatialIndex` (`lucien/src/main/java/.../AbstractSpatialIndex.java`) is **5,767 lines with 135 public methods**. It is the shared generic base for Octree, Tetree, Prism, and SFCArrayIndex, and it has accreted essentially every cross-cutting spatial-index concern into one type: entity lifecycle, k-NN search (SFC-pruning + expanding-radius variants), range queries, ray intersection, frustum/plane culling, collision detection, stream/leaf/level accessors, the KNN cache integration, locking strategy, bulk operations, neighbor traversal, and tree balancing hooks.

A 5.7k-LOC / 135-method base class is hard to reason about, hard to test in isolation, and a magnet for the kind of latent bugs Tranches A–C fixed (lazy-stream-under-lock, kNN TOCTOU, containment-vs-intersection — all lived here or in close collaborators). Any change risks wide blast radius because everything is one class.

The goal is to decompose it into cohesive collaborators **without breaking the generic `SpatialIndex<Key, ID, Content>` API contract** or the four existing subclasses (Octree/Tetree/Prism/SFCArrayIndex), which rely on its protected template methods.

## Context

### Background

360-review architecture finding (`a1542d17`, T2 `luciferase/360-review-2026-05-23-summary`): "AbstractSpatialIndex is 5,750 LOC / 135 public methods (god class)." This is the largest single-file architectural debt in the codebase. It is explicitly RDR-scope and a likely multi-PR arc — not a single refactor commit.

Tranches A–C touched this file repeatedly (stream materialization under lock, KNNCache lock collapse, kNN pruning, containment predicate) — evidence that its size concentrates risk. RDR-002 (12-DOP containment) and RDR-003 (FCC/RD indexing) both interact with its query surface, so the decomposition must preserve the seams those rely on.

### Technical Environment

- **Module**: `lucien`
- **Key file**: `lucien/src/main/java/.../AbstractSpatialIndex.java` (5,767 LOC, 135 public methods)
- **Subclasses bound to its protected template methods** (must not break):
  - `octree/Octree.java`, `tetree/Tetree.java`, `prism/Prism.java`, `sfc/SFCArrayIndex.java`
  - Each overrides `calculateSpatialIndex`, `isNodeContainedInVolume`, `shouldContinueKNNSearch`, `getNodeBounds`, `doesNodeIntersectVolume`, etc.
- **Identifiable cohesive clusters** (candidate extraction boundaries):
  - Entity lifecycle (insert/remove/update/move) + `EntityManager` collaboration
  - k-NN search (SFC range-pruning + expanding-radius fallback + `KNNCache`)
  - Region/range queries (`entitiesInRegion`, `spatialRangeQuery`, `getSpatialIndexRange`)
  - Ray intersection / frustum / plane culling
  - Collision detection entry points
  - Stream accessors (`leafStream`/`levelStream`/`nodeStream`) + locking
  - Tree balancing hooks
- **Storage/concurrency**: single `ConcurrentSkipListMap`, `ReadWriteLock`, `spatialVersion` AtomicLong, `KNNCache` (per CLAUDE.md "Concurrent Architecture").

## Approach

> Candidate directions below; resolved by research (see [Research Findings](#research-findings)) into the phased recommendation that follows. This is a phased, multi-PR decomposition.

1. **Map the method surface** — bucket all 135 public + the protected template methods into the cohesive clusters above; identify shared state each cluster touches (spatialIndex map, lock, spatialVersion, entityManager, knnCache).
2. **Pick a decomposition style** — Strategy/delegate objects (e.g. `KnnSearcher`, `RangeQueryEngine`, `RayIntersector`, `CollisionEngine`) holding references to the shared storage + lock, vs. mixin-style interfaces with default methods, vs. composition with a small `SpatialIndexCore` holding state and feature objects delegating to it. Must keep the public `SpatialIndex` contract and the subclass template-method hooks intact.
3. **Preserve the subclass seam** — Octree/Tetree/Prism/SFCArrayIndex override protected hooks; the decomposition must route those hooks to the right collaborator without forcing subclass rewrites (or with a clearly-scoped, mechanical subclass update).
4. **Phase it** — extract one cluster at a time behind green tests (e.g. k-NN first, since it is self-contained and was a recent bug site), each phase its own PR. Define the phase boundaries + the `/conexus:phase-review-gate` checkpoints up front.
5. **No behavior change** — this is a structural refactor; the full lucien suite (2400+ tests) must stay green at every phase. Performance parity verified via `-Pperformance` benchmarks.

### Recommended direction (pending gate)

Adopt **decomposition style (iii): core + feature-objects**, because research showed it minimizes subclass churn — the subclasses keep overriding the same protected hooks and never learn that collaborators exist.

- **Structure.** Extract a `SpatialIndexCore` holding the six-field shared nucleus (`spatialIndex`, `lock`, `spatialVersion`, `knnCache`, `entityManager`, `entityCache`). `AbstractSpatialIndex` becomes a thin **façade** that implements a `SpatialGeometry<Key>` callback interface (the ~21 protected template hooks stay on the façade) and delegates to feature objects, each holding `SpatialIndexCore` + the `SpatialGeometry<Key>` callback: `DsocController`, `GhostCoordinator`, `KnnSearcher`, a frustum/plane/ray culler, `CollisionEngine`, `EntityLifecycleManager`. The public `SpatialIndex<Key,ID,Content>` contract is untouched. Concurrency lives in one auditable place (`SpatialIndexCore`).
- **⚠️ "Subclasses unchanged" holds only through Phase 4 — the collision phase forces a scoped Tetree change (corrected).** The earlier blanket "subclasses are unchanged" claim is **false** for the collision and entity-lifecycle phases: `Tetree` does not merely override template *hooks*, it overrides the *public* `findAllCollisions`/`findCollisions` (`Tetree.java:157,169,187,207`) with geometry-specific logic that reaches **directly** into `spatialIndex`, `lock`, and `entityManager` under `lock.readLock()`. Once those fields move into `SpatialIndexCore`, the overrides no longer compile unmodified. Resolution: the façade **retains `protected` access to the nucleus** (either the fields stay `protected` on the façade with `SpatialIndexCore` wrapping access, or the façade exposes `protected` accessors) so subclass overrides keep compiling — **and** `Tetree`'s collision overrides are refactored as a **known, scoped task inside Phase 5** (collision), not assumed free. Net: subclasses are genuinely untouched through Phases 1–4 (DSOC, ghost, k-NN, culling); Phase 5 carries a bounded Tetree change; `insertBatch` (which toggles a Tetree-private flag then calls `super`) is unaffected.
- **Keep region/range queries and stream accessors *in the façade*** — they are tightly bound to the `spatialIndex`+`lock` nucleus and read as core responsibilities, not collaborators. **Success criterion (auditable):** the residual façade after all six phases is expected to be ≈70 methods (region/range ~20 + streams ~11 + ~21 template hooks + core/config ~20) — large but a coherent geometry-interface + core-accessor surface, no longer a god class. State the post-decomposition method count as an explicit acceptance target.
- **Phase ordering** (each its own PR, behind a `/conexus:phase-review-gate`, full lucien suite green + `-Pperformance` parity at every step):
  1. **DSOC (cluster k)** — cleanest first cut: 10+ dedicated private fields, **zero template hooks**, touches only the lock. No cross-RDR dependency.
  2. **Distributed-ghost (cluster i)** — **dual-purpose: depends on RDR-007 Phase 0.** Prerequisite: RDR-007's interface inversion (the `:5183-5214` gRPC FQN types must hide behind an interface before extraction). This is **not the same PR** as RDR-007 Phase 0 — that phase introduces the interfaces (minimum to unblock the move); this phase is the full `GhostCoordinator` extraction and lands after it. Both are owned by the shared bead **`Luciferase-aos`** (which defines the interface contract); sequence RDR-007 Phase 0 first.
  3. **k-NN + KNNCache (cluster b)** — moderate hooks (`shouldContinueKNNSearch`, `estimateNodeDistance`, `calculateSpatialIndex`); recent bug site (Tranches B–C, RDR-003).
  4. **Frustum + plane + ray (clusters d+e bundled)** — shared traversal hooks, high internal cohesion.
  5. **Collision (cluster f)** — self-contained once 1–4 are out, **and includes the scoped `Tetree` collision-override refactor** noted above. Phase 5 testing MUST add a `Tetree`-specific cross-tetrahedra collision test — that same-cell-tetrahedron path is unique to `Tetree` (the logic beyond `super.findAllCollisions()`) and is invisible to the generic suite.
  6. **Entity lifecycle (cluster a)** — broadest shared-state footprint (7 fields incl. `knnCache`/`spatialVersion`), most disruptive, deliberately last.
- **Scope correction:** the file is **~195 methods across 11 clusters**, not 135/7 — the RDR's count undershot, and the **DSOC** and **distributed-ghost** clusters are first-class extraction targets the original list omitted.

## Research Findings

> Investigation 2026-05-25 (`codebase-deep-analyzer`). Full detail in T2 `luciferase_rdr/008-research-1`.

1. **~195 methods, 11 cohesive clusters.** Largest: entity lifecycle (~48), collision (~26), frustum/plane (~22), balancing/subdivision (~22), **distributed-ghost (~22)**, core/config (~20), region/range (~20); plus k-NN (~13), **DSOC (~13)**, stream accessors (~11), ray (~9).
2. **Two-field nucleus + a coupling pair.** `spatialIndex` (`ConcurrentNavigableMap`, `:99`) and `lock` (`ReentrantReadWriteLock`, `:101`) are touched by every cluster; `spatialVersion` (`:137`) + `knnCache` (`:138`) couple entity-lifecycle to k-NN. Other state is cluster-dedicated (DSOC `:116-119`, ghost `:150-155`, balancing `:103,145-147`). Stream accessors **materialize under the read lock** (`.collect(...).stream()`) — the prior lazy-stream-after-unlock hazard is already fixed.
3. **~21 protected template hooks; each serves exactly one cluster, no hook-splitting needed.** All four subclasses override the core ~15 (`calculateSpatialIndex`, `shouldContinueKNNSearch`, `getNodeBounds`, `doesNodeIntersectVolume`, `doesRayIntersectNode`, frustum/plane variants, …). This is the seam any decomposition must route.
4. **Style (iii) wins on subclass churn.** A feature object (`KnnSearcher`) must call subclass-overridden hooks (`shouldContinueKNNSearch` at `:4320`, `calculateSpatialIndex` at `:1428` + 8 sites) — passed as a `SpatialGeometry<Key>` callback. Style (iii) (and the architecturally-equivalent delegate-with-`this` style (i)) keeps subclasses unchanged; mixin-with-defaults (ii) is worst (needs 6+ field accessors on the public interface).
5. **Cleanest first extraction = DSOC** (dedicated state, zero hooks). **Distributed-ghost is the highest-leverage** because it discharges RDR-007's `AbstractSpatialIndex` blocker simultaneously — but only after RDR-007's interface inversion.

6. **Cross-RDR — RDR-010 (Pyramid Spatial Index) sequencing constraint discharged** (post-implementation finding 2026-05-28, from RDR-010 architecture survey). The decomposed `AbstractSpatialIndex<Key, ID, Content>` façade is the extension seam for a future `PyramidIndex` — subclass-and-initialize-collaborators (`SpatialIndexCore`, `DsocController`, `GhostCoordinator`, `KnnSearcher`, `Culler`, `CollisionEngine`, `EntityLifecycleManager`), no abstract methods to implement beyond the ~42 protected template hooks (item 3). The closure of P6 satisfies RDR-010's hard sequencing constraint; PyramidIndex implementation can proceed without further wait. Architecture survey detail in T2 `Luciferase/rdr-010-pyramid-index-research`.

7. **Cross-RDR — `Forest` already heterogeneous** (post-implementation finding 2026-05-28). `Forest.addTree(AbstractSpatialIndex<Key, ID, Content>)` accepts any concrete spatial index — Octree, Tetree, Prism, or (future) Pyramid — without homogeneity constraint. The remaining piece for Algorithm 5.1 hybrid-forest partitioning (Knapp 2026 §5.1) is a per-shape `N_shape(ℓ)` weight hook on `TreeNode` (currently only tracks `entityCount`). This is a small future extension that has no PyramidIndex prerequisite — it could land independently for Octree/Tetree heterogeneous forests today. Cross-reference RDR-010 Approach §4.

## Open Questions

- ~~Delegation/Strategy vs. interface-with-defaults vs. core+feature-objects — which best preserves the generic contract and minimizes subclass churn?~~ **Resolved (recommended):** core+feature-objects (style iii); subclasses are unchanged through Phases 1–4 because the façade keeps the `SpatialGeometry<Key>` hooks **and retains `protected` access to the nucleus**. Phase 5 carries a bounded `Tetree` collision-override refactor (those overrides touch the nucleus directly — see the corrected Structure note). Mixin-with-defaults rejected (leaks field accessors onto the public interface).
- ~~Can collaborators share the lock/version/map by reference safely, or does extraction force a concurrency rethink?~~ **Resolved:** Share by reference via `SpatialIndexCore`. The lazy-stream-under-lock hazard is already fixed (stream accessors materialize inside the lock), so no concurrency rethink is forced. The one cross-feature ordering path — `EntityLifecycleManager` increments `spatialVersion`, `KnnSearcher` reads it for cache invalidation — is already correct without extra synchronization: the **write-lock release** in `EntityLifecycleManager` *happens-before* the subsequent **read-lock acquire** in `KnnSearcher` (Java Memory Model monitor semantics), guaranteeing `spatialVersion` visibility. Centralizing the nucleus makes this auditable in one place.
- ~~Phase ordering: k-NN first or entity-lifecycle first?~~ **Resolved:** Neither — **DSOC first** (zero hooks, dedicated state), then distributed-ghost (dual-purpose with RDR-007), then k-NN; entity-lifecycle **last** (broadest shared state).
- ~~How many phases / PRs, and what are the `/conexus:phase-review-gate` boundaries?~~ **Resolved (proposed):** 6 phases (DSOC → distributed-ghost → k-NN → frustum/plane/ray → collision → entity-lifecycle), a gate at each.
- ~~Does RDR-003's future FCC query surface impose constraints on where the query seams should fall?~~ **Partially open:** region/range + stream accessors are recommended to stay in the façade (core); whether RDR-003's FCC query work needs a dedicated query collaborator should be revisited when RDR-003 implementation resumes.

**New cross-RDR constraint from research:** Phase 2 (distributed-ghost extraction) **depends on** RDR-007's Phase 0 dependency inversion of `AbstractSpatialIndex` (`:5183-5214`) — they are *not* the same PR. RDR-007 Phase 0 introduces the interfaces and severs the FQN references (minimum to unblock the module move); RDR-008 Phase 2 is the larger `GhostCoordinator` feature-object extraction that lands after it. Both are owned by the shared coordination bead **`Luciferase-aos`**, which defines the interface contract before either RDR begins Phase 1+.

## Decision

Accepted 2026-05-25 (gate PASSED, self-reviewed). Locked:

1. **Style:** core + feature-objects. `SpatialIndexCore` holds the six-field nucleus; `AbstractSpatialIndex` becomes a façade implementing `SpatialGeometry<Key>` (keeping the ~21 subclass template hooks) **and retaining `protected` access to the nucleus** so subclass overrides keep compiling. Feature objects: `DsocController`, `GhostCoordinator`, `KnnSearcher`, frustum/plane/ray culler, `CollisionEngine`, `EntityLifecycleManager`. Region/range queries and stream accessors stay in the façade.

   **P3 refinement (2026-05-28, user-directed):** the single unified `SpatialGeometry<Key>` was split into per-cluster sub-interfaces after the P2 stacked review observed the disjoint per-feature surfaces. Concretely: `occlusion.FrustumGeometry<Key,ID,Content>` (consumed by `DsocController`), `cache.KnnProvider<Key,ID>` (the k-NN service `KnnSearcher` offers to consumers like `GhostCoordinator`), and `cache.KnnGeometry<Key,ID>` (façade ops `KnnSearcher` consumes during search). `AbstractSpatialIndex` now `implements KnnProvider<Key,ID>` transiently — P3-main will move that role to `KnnSearcher` itself. Each future feature object adds its own narrow sub-interface in the cluster's package. Commit `28a9e776`, bead `Luciferase-x5i.6`.

   **P6 refinement (2026-05-28, applied at extraction):** the entity-lifecycle cluster splits the per-cluster sub-interface into TWO seams — `entity.EntityLifecycleGeometry<Key,ID,Content>` (the subclass-overridden hooks: calculateSpatialIndex, getCellSizeAtLevel, insertWithSpanning, validateSpatialConstraints, hasChildren, handleNodeSubdivision, onNodeRemoved, cleanupEmptyNode) and `entity.EntityLifecycleHost<Key,ID,Content>` (the facade-internal infrastructure: bulk config/processor/builder, parallel ops, node pool, deferred-subdivision manager, spanning policy, the volatile DSOC controller for the P1 updateEntity seam, the ghost-update hook, the auto-balance hook, the stack-builder target). This "two-seam" application of the P3 refinement is unique to the broadest cluster; the narrower clusters (P1–P5) used a single sub-interface because their callback footprint was uniformly subclass-overridden + cluster-resident state, with no third category of facade-internal infrastructure to route. Bead `Luciferase-x5i.12`.

   **P6 also discharged the P3 substantive-critic Significant#1 obligation** (carried forward from P3 through P5): the mutable `FineGrainedLockingStrategy<ID,Content>` field moved from the façade into `SpatialIndexCore` (the only nucleus field that is volatile/mutable), with `core.lockingStrategy()` / `core.setLockingStrategy(...)` accessors. `KnnSearcher`'s and `CollisionEngine`'s `Supplier<FineGrainedLockingStrategy>` constructor arguments are gone; both now read `core.lockingStrategy()` at call time, picking up any `configureFineGrainedLocking` replacement via the volatile field.
2. **Phases** (6, each its own PR behind `/conexus:phase-review-gate`, full lucien suite green + `-Pperformance` parity each): P1 DSOC → P2 distributed-ghost (depends on RDR-007 Phase 0, owned by `Luciferase-aos`; not the same PR) → P3 k-NN → P4 frustum/plane/ray → P5 collision (**includes the scoped `Tetree` collision-override refactor + a Tetree-specific cross-tetrahedra collision test**) → P6 entity-lifecycle.
3. **Subclasses are genuinely unchanged through P1–P4;** P5 carries a bounded, explicit `Tetree` change; P6 again leaves all four subclasses byte-for-byte unchanged. Residual façade ≈70 methods was the predicted acceptance target; the realized count is **117 public + 42 protected = 159 methods at the outer-class indent** (measured 2026-05-28 via awk against the post-P6 façade). The original prediction undershot reality by ~2x — it broke down as "region/range ~20 + streams ~11 + ~21 template hooks + core/config ~20" and **omitted the public ghost API surface (~22 delegators) and the public DSOC API surface (~10 methods) entirely**, and underestimated the protected template-hook + internal-helper count by roughly 2x (~42 actual vs. ~21 predicted). The realized breakdown: ~50 SpatialIndex-contract public methods (the `SpatialIndex<Key,ID,Content>` interface bind), ~22 ghost API delegators (1-line delegators to `GhostCoordinator`), ~10 DSOC API public methods (the cluster's user-facing surface), ~15 configuration + stats public methods, ~7 stream + region accessors (locked on the façade by item 1), and ~42 protected template hooks + facade-internal helpers (subclass extension points + cross-cluster plumbing). Every entity-lifecycle method moved out as required; the residual is the cost of preserving the public `SpatialIndex` contract + the P1/P2 cluster-public surfaces + the subclass extension points + the locked-on-façade region/stream accessors. **Not a scope reduction** — a documented realized-vs-predicted delta. T2 `Luciferase/rdr-008-phase6-progress` carries the full breakdown.

## Consequences

- **Positive:** turns a 5.7k-LOC / ~195-method god class into a façade + cohesive collaborators with the concurrency nucleus auditable in one place; the public `SpatialIndex` contract and (through P4) the subclasses are untouched.
- **Cost / risk:** P5 forces a bounded `Tetree` collision refactor (its overrides reach the nucleus directly) — scoped and test-covered, not free. The façade's `protected` nucleus re-exposure is a partial-bypass risk to police at each phase-review-gate. P2 is gated on RDR-007 Phase 0.
- **No behavior change** is the invariant; any test/benchmark regression at a phase boundary blocks that phase.
