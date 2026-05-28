---
title: "Post-Mortem: Decompose the AbstractSpatialIndex God-Class"
rdr: RDR-008
status: implemented
implemented_date: 2026-05-28
duration: 4 days (P0 → G6, 2026-05-25 → 2026-05-28)
epic_bead: Luciferase-x5i
author: hal.hildebrand
---

# RDR-008 Post-Mortem

## Outcome

`AbstractSpatialIndex` decomposed from a 5,851-LOC god class into a façade plus six cohesive feature-object collaborators. Cumulative facade shrinkage: **5851 → 2916 lines (-2935 net, 50% reduction)** across six phases shipped as seven PRs.

| Phase | Bead | Feature object | Sub-interfaces | PR | LOC delta (facade) |
|-------|------|----------------|----------------|----|--------------------|
| P0 | x5i.1 | `SpatialIndexCore` (nucleus) | — | #130 | additive |
| P1 | x5i.2 | `DsocController` | `occlusion.FrustumGeometry` | #132 | -188 |
| P2 | x5i.4 | `GhostCoordinator` | (façade back-ref concession) | #133 | -494 |
| P3 | x5i.6 | `KnnSearcher` | `cache.KnnProvider`, `cache.KnnGeometry` | #134 + #135 | -550 |
| P4 | x5i.8 | `Culler` | `cull.CullGeometry`, `cull.FrustumCullProvider` | #136 | -415 |
| P5 | x5i.10 | `CollisionEngine` | `collision.CollisionGeometry` | #138 | -654 |
| P6 | x5i.12 | `EntityLifecycleManager` | `entity.EntityLifecycleGeometry`, `entity.EntityLifecycleHost` | #139 | -534 |

All six phase-review-gates PASSED. All four concrete spatial indices (Octree, Tetree, Prism, SFCArrayIndex) remained byte-for-byte unchanged through P0–P4 + P6; P5 carried a single bounded, explicit Tetree collision-override refactor as the only sanctioned subclass change in the entire arc.

## What worked

### Phased extraction was correct
Six phases, each its own PR behind a `/conexus:phase-review-gate`, with the full lucien suite (2,400+ tests) green at every step. The ordering — DSOC first (cleanest cut, zero hooks), then distributed-ghost (RDR-007 prerequisite), then k-NN, then bundled cull, then collision (with its bounded Tetree change), then entity-lifecycle (broadest) — was driven by research (T2 `luciferase_rdr/008-research-1`) and held without revision. No phase had to be re-ordered or re-cut.

### Per-cluster sub-interface refinement (P3)
The P2 stacked review observed that a single `SpatialGeometry<Key>` interface was acquiring disjoint per-feature surfaces. The P3 refinement (commit `28a9e776`) split it into per-cluster sub-interfaces in each cluster's package: `FrustumGeometry` in `occlusion`, `KnnProvider`/`KnnGeometry` in `cache`, later `CullGeometry`/`FrustumCullProvider` in `cull`, `CollisionGeometry` in `collision`. The narrow-seam principle propagated cleanly through P4 and P5.

### Stacked review caught real bugs
The standing rule from `feedback_review-stacking.md` (BOTH `code-review-expert` AND `substantive-critic` at each phase boundary, before push) earned its keep three times:

- **P5 #1** — `createPairKey` hash-collision deduplication divergence. The base sweep used `HashSet<UnorderedPair<ID>>` (compareTo-ordering); the Tetree cross-tet supplement used `HashSet<String>` keyed by `hashCode`-ordering. Hash collisions could duplicate cross-tet pairs. Fixed by switching to `UnorderedPair`.
- **P5 #2** — TOCTOU window between `super.findAllCollisions()` (released the read lock) and the Tetree cross-tet sweep (re-acquired the read lock). A concurrent writer in the lock-release window could mutate the index between the two sections. Fixed by acquiring the read lock BEFORE `super` and holding it across both sections (`ReentrantReadWriteLock` allows re-entrant read).
- **P6 #1** — `EntityLifecycleManager.insertAtPosition` initially called `callback.createNode()` directly instead of `host.nodePool().acquire()`, bypassing pool reuse. `SpatialNodePoolIntegrationTest` fired ("hits: 0, allocations: 100"). Caught by the suite as the FIRST verification pass before reviewers were dispatched.

The discipline pattern — **suite first, then dispatch reviewers, then fix all findings, then push** — works because each layer catches different bug classes (suite catches behavior regressions, code-review-expert catches surface defects, substantive-critic catches structural defects).

### `SpatialIndexCore` as the nucleus
P0 introduced `SpatialIndexCore<Key,ID,Content>` as an additive view over the façade's six (later seven) nucleus fields without moving anything. Subsequent phases consumed `core` as a shared reference for the storage + concurrency primitives, leaving the concurrency-critical fields auditable in one place. The pattern held with one in-flight modification at P6 (relocating the mutable `lockingStrategy` into the core), which discharged the P3 substantive-critic Significant#1 obligation cleanly.

## What we revised mid-arc

### P2 façade back-reference concession
The original P2 plan extracted `GhostCoordinator` cleanly via `core` + a sub-interface. Implementation discovered that `GhostBoundaryDetector` + `DistributedGhostManager` both take the concrete `AbstractSpatialIndex` in their constructors. Resolution: `GhostCoordinator` holds an `AbstractSpatialIndex` back-reference solely to pass through to those collaborators. Documented as a P2 concession in the RDR and `GhostCoordinator`'s class javadoc. Follow-up bead `Luciferase-703` is filed for the interface inversion as separable work.

### P5 bounded Tetree change
The original blanket "subclasses are unchanged" claim was false for the collision phase: `Tetree`'s collision overrides reach directly into the nucleus fields (`spatialIndex`, `lock`, `entityManager`) under `lock.readLock()`. Once those moved behind `core`, the overrides would not compile unmodified. Resolution: scope a bounded Tetree refactor inside P5 (only the collision overrides + the `isEntityInAabt` helper; everything else in Tetree.java untouched). The RDR §Decision item 1 was updated with a corrected "Subclasses unchanged holds only through Phase 4" note before P5 implementation began.

### P6 two-seam refinement
P6 (entity-lifecycle) is the broadest cluster — it consumes every subclass extension point AND a great deal of facade-internal infrastructure (bulk config/processor/builder, parallel ops, node pool, deferred-subdivision manager, spanning policy, the volatile DSOC controller for the `updateEntity` seam, ghost-update hook, auto-balance hook). A single sub-interface would either be too narrow (force a concrete-façade back-reference like P2) or too broad (15+ unrelated methods). Resolution: split into TWO sub-interfaces — `EntityLifecycleGeometry` for subclass-overridden hooks + `EntityLifecycleHost` for facade-internal infrastructure. The "two-seam" pattern is unique to the broadest cluster; documented in RDR §Decision item 1 as the P6 refinement note.

### P6 lockingStrategy migration
The P3 substantive-critic raised a Significant#1 concern: the `Supplier<FineGrainedLockingStrategy<ID,Content>>` constructor argument on `KnnSearcher` (and later `CollisionEngine`) was a code smell — the mutable strategy reference belonged in `SpatialIndexCore` so feature objects could read it directly via `core.lockingStrategy()`. The migration was deferred to P6 because the relocation depended on stabilizing the broader entity-lifecycle path (`configureFineGrainedLocking` lives on the façade and `core.setLockingStrategy(...)` is called under the façade write lock). P6 discharged it cleanly: KnnSearcher's and CollisionEngine's Supplier ctor args are gone.

## What surprised us

### Residual method count: 159, not ~70
The RDR §Decision item 3 acceptance target was "residual façade ≈70 methods (region/range ~20 + streams ~11 + ~21 template hooks + core/config ~20)". The realized count is **117 public + 42 protected = 159 outer-class methods** — **2.3× the prediction**.

Root cause of the gap, from the post-extraction measurement (T2 `Luciferase/rdr-008-final-method-count`):

| Category | Predicted | Realized |
|----------|-----------|----------|
| `SpatialIndex<Key,ID,Content>` contract public | (not separated) | ~50 |
| Public ghost API delegators | **omitted** | ~22 |
| Public DSOC API methods | **omitted** | ~10 |
| Public configuration + stats | ~20 (lumped with core/config) | ~15 |
| Stream + region accessors | ~31 | ~7 |
| Protected template hooks + internal helpers | ~21 | ~42 |

The prediction simply omitted the public ghost and DSOC API surfaces — both of which are 1-line delegators to their feature objects, but they ARE public methods on the façade by API contract — and underestimated the protected template-hook + internal-helper count by 2×. Stream and region accessors came in well below prediction because much of the predicted region surface was actually facade-internal protected helpers that moved with their owning clusters during extraction.

**This is documented as a realized-vs-predicted delta in RDR §Decision item 3, not a scope reduction.** Every entity-lifecycle method moved out as required; the residual reflects the cost of preserving the public `SpatialIndex` contract + the P1/P2 cluster-public surfaces + the locked-on-façade region/stream accessors + the subclass extension points. Future RDRs that estimate method counts on the façade should account for cluster-public APIs explicitly and assume protected hooks at ~2× the count of subclass-overridden hooks.

### Suite-first verification catches a different class of bug
The P6 first-verification-pass found the `nodePool.acquire()` bypass BEFORE reviewers were dispatched. The reviewers would likely also have caught it, but the suite ran in 25 seconds vs. ~5 minutes per reviewer; the cost difference is two orders of magnitude. Lesson: when stacked-reviewing a refactor, **always** run the suite first as the cheapest verification — review-after-suite-pass is a strictly better workflow than review-then-suite.

### Stale `.class` files cause silent failures
At P3 and P5, stale `.class` files in `lucien/target` caused `NoSuchMethodError` at runtime (compiled against pre-extraction signatures) and surfaced tests whose source files no longer existed. `mvn -pl lucien clean test-compile` clears them. This is a Maven incremental-compile gotcha worth knowing — when cross-PR signature changes are in flight (P6 changed KnnSearcher's and CollisionEngine's ctor signatures), a stale benchmark/test class file may surface as a runtime error even if `mvn compile` passes.

## Outstanding follow-ups

All P3 priority, all non-blocking:

- **`Luciferase-703`** — interface-invert `GhostBoundaryDetector` + `DistributedGhostManager` off the concrete `AbstractSpatialIndex`, retiring the P2 façade back-reference concession.
- **`Luciferase-ts8`** — narrow `StackBasedTreeBuilder.buildTree` to accept `SpatialIndex<Key,ID,Content>` instead of `AbstractSpatialIndex<Key,ID,Content>`, retiring the type leak in `EntityLifecycleHost.stackBuilderTarget()`.
- **`Luciferase-vpl`** — hoist `estimateSFCRange` to the `SpatialKey` interface, eliminating the `instanceof MortonKey/TetreeKey` dispatch in `KnnSearcher.performKNNSFCRangePruning`.

## Lessons for future decomposition work

1. **Estimate method counts conservatively.** Account for cluster-public APIs even when they're 1-line delegators; assume protected hooks at ~2× the subclass-overridden subset.
2. **Per-cluster sub-interfaces compose better than a unified seam.** The P3 refinement should be the default, not a late discovery.
3. **A two-seam (Geometry + Host) split is the right pattern when one cluster touches both subclass extension and façade infrastructure.** Don't force everything through a single callback.
4. **Carrying-forward obligations across phases works.** The P3 substantive-critic Significant#1 explicitly carried forward through P3/P4/P5 and was discharged in P6 with a documented obligation in the prior gate verdict.
5. **Run the suite as the first verification pass before dispatching reviewers.** Catches a different bug class and is two orders of magnitude faster.
6. **Stacked review (`code-review-expert` + `substantive-critic`) catches genuine bugs at every phase boundary.** This arc demonstrated three real fixes pre-merge that the suite alone passed.
7. **Document scope changes inline in the RDR `§Decision` as refinement notes.** The P3 refinement note, the P5 corrected Structure note, and the P6 refinement note + realized-vs-predicted method-count documentation were all critical for the phase-review-gates and for this post-mortem to reconstruct the arc.

## Knowledge artifacts

T2 entries (permanent):
- `Luciferase/rdr-008-phase0-progress` through `rdr-008-phase6-progress` — per-phase implementation snapshots
- `Luciferase/rdr-008-phase1-gate` through `rdr-008-phase6-gate` — per-phase gate verdicts
- `Luciferase/rdr-008-final-method-count` — G6 acceptance measurement
- `Luciferase/rdr-008-continuation` — final ARC-CLOSED resume pointer
- This post-mortem at `docs/rdr/post-mortem/008-decompose-abstractspatialindex.md`
