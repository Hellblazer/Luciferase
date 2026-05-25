---
title: "Decompose the AbstractSpatialIndex God-Class"
id: RDR-008
type: Architecture
status: draft
priority: medium
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-24
related_issues: [Luciferase-x5i, RDR-002, RDR-003]
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

> To be completed in `/nx:rdr-research` + design. This is expected to be a phased, multi-PR decomposition. Initial candidate directions:

1. **Map the method surface** — bucket all 135 public + the protected template methods into the cohesive clusters above; identify shared state each cluster touches (spatialIndex map, lock, spatialVersion, entityManager, knnCache).
2. **Pick a decomposition style** — Strategy/delegate objects (e.g. `KnnSearcher`, `RangeQueryEngine`, `RayIntersector`, `CollisionEngine`) holding references to the shared storage + lock, vs. mixin-style interfaces with default methods, vs. composition with a small `SpatialIndexCore` holding state and feature objects delegating to it. Must keep the public `SpatialIndex` contract and the subclass template-method hooks intact.
3. **Preserve the subclass seam** — Octree/Tetree/Prism/SFCArrayIndex override protected hooks; the decomposition must route those hooks to the right collaborator without forcing subclass rewrites (or with a clearly-scoped, mechanical subclass update).
4. **Phase it** — extract one cluster at a time behind green tests (e.g. k-NN first, since it is self-contained and was a recent bug site), each phase its own PR. Define the phase boundaries + the `/nx:phase-review-gate` checkpoints up front.
5. **No behavior change** — this is a structural refactor; the full lucien suite (2400+ tests) must stay green at every phase. Performance parity verified via `-Pperformance` benchmarks.

## Open Questions

- Delegation/Strategy vs. interface-with-defaults vs. core+feature-objects — which best preserves the generic contract and minimizes subclass churn?
- Can collaborators share the lock/version/map by reference safely, or does extraction force a concurrency rethink (the TOCTOU/lock work in Tranches B–C is relevant)?
- Phase ordering: k-NN first (self-contained, recent bug site) or entity-lifecycle first (most foundational)?
- How many phases / PRs, and what are the `/nx:phase-review-gate` boundaries?
- Does RDR-003's future FCC query surface impose constraints on where the query seams should fall?

## Decision

_Pending research + gate._

## Consequences

_Pending._
