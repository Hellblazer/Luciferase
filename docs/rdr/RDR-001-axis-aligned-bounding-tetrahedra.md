---
title: "Axis-Aligned Bounding Tetrahedra (AABT) for Tetree Spatial Index"
id: RDR-001
type: Architecture
status: closed
closed_date: 2026-03-22
close_reason: implemented
priority: medium
author: hal.hildebrand
reviewed-by: self
created: 2026-03-21
accepted_date: 2026-03-21
related_issues: []
---

# RDR-001: Axis-Aligned Bounding Tetrahedra (AABT) for Tetree Spatial Index

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

The Tetree spatial index subdivides space into S0-S5 characteristic tetrahedra that tile a cube, but all bounding volume queries use traditional Axis-Aligned Bounding Boxes (AABBs) — rectangular volumes aligned to orthogonal XYZ axes. Each S0-S5 tetrahedron occupies only 1/6 of its enclosing cube, meaning per-cell AABB containment tests have a theoretical worst-case false-positive rate of 5/6 at the same refinement level. For range queries, the practical overestimation depends on query shape and alignment — empirical benchmarking is needed to quantify the actual reduction (estimated 2-6x fewer candidates). Additionally, two intersection methods (`tetrahedronIntersectsVolume` at line 642 and `tetrahedronIntersectsVolumeBounds` at line 664) have incomplete tests that produce additional false positives. The existing `Spatial.aabt` type is structurally identical to `aabb` (6 floats with rectangular comparisons), providing no actual tetrahedral geometry.

We propose true Axis-Aligned Bounding Tetrahedra where "axis-aligned" means aligned to the **tetrahedral coordinate system** defined by the S0-S5 orderings, not traditional orthogonal coordinates.

## Context

### Background

The Tetree spatial index recursively subdivides space using 6 characteristic tetrahedra (S0-S5) that perfectly tile each cube. The S0-S5 types correspond to the 6 orderings of rescaled coordinates (u,v,w) within a cube:
- S0: v ≤ w < u
- S1: w < v < u
- S2: u ≤ w < v
- S3: u ≤ v ≤ w
- S4: w < u ≤ v
- S5: v < w ≤ u

Current bounding volume operations (range queries, frustum culling, collision detection) use AABBs, which are optimal for the Octree but geometrically inappropriate for the Tetree. Two intersection methods have known issues:
- `tetrahedronIntersectsVolume()` (line 642) — used by `spatialRangeQueryKeys()` — only tests vertex-in-AABB and center-in-tet, missing edge/face intersections
- `tetrahedronIntersectsVolumeBounds()` (line 664) — has a conservative `return true` fallback at line 724

A recent paper — "Axis-Aligned Relaxations for Mixed-Integer Nonlinear Programming" (Zhu, He, Tawarmalani, arxiv 2603.18458, March 2026) — formalizes the concept of axis-aligned regions over non-orthogonal coordinate systems and proves key properties about corner points, voxelization convergence, and convex hull construction that are directly applicable to tetrahedral bounding volumes.

### Technical Environment

- **Module**: lucien (190+ Java files, 18 packages)
- **Key classes**: `Tet.java`, `Spatial.java`, `TetreeKey`, `AbstractSpatialIndex`, `TetrahedralGeometry`
- **Existing infrastructure**: `containsUltraFast()` (barycentric containment), `TetrahedralGeometry.aabbIntersectsTetrahedron()` (SAT intersection), `locatePointBeyRefinementFromRoot()` (O(1) type selection)
- **Portal package**: Tetrahedral grid visualizations showing the coordinate system

## Research Findings

### Investigation

**Source: arxiv 2603.18458 (indexed in T3, 108 chunks)**

The paper constructs polyhedral relaxations by convexifying finite sets of strategically chosen points over axis-aligned regions. Three results transfer to tetrahedral bounding volumes:

1. **Corner point sufficiency**: Tight bounds on a convex region (tetrahedron) are achieved at its vertices — a standard result from convexity theory. The paper extends this to multilinear function values over axis-aligned regions, but the geometric containment property follows directly from convexity without requiring the paper's MINLP-specific theorems.

2. **Voxelization convergence**: Finer voxelization of a domain yields provably tighter relaxations. The Tetree's hierarchical refinement (level L gives cell size h = 2^(21-L)) is exactly this voxelization. S0-S5 cells ARE the voxels.

3. **Axis-aligned generalization**: "Axis-aligned" does not require orthogonal axes. The axes are defined by the coordinate hyperplanes of the ordering system. For S0-S5, the faces of each tetrahedron ARE these coordinate hyperplanes.

**Source: Codebase analysis**

- `Spatial.aabt` (Spatial.java:182) is a stub — structurally identical to `aabb`, all `containedBy()` and `intersects()` methods use AABB comparisons
- `containsUltraFast()` (Tet.java:1015) implements 4-determinant barycentric test — this IS the correct AABT containment primitive
- `TetrahedralGeometry.aabbIntersectsTetrahedron()` (line 46) implements SAT-based intersection — the correct AABT intersection primitive
- `locatePointBeyRefinementFromRoot()` (Tet.java:272) is O(targetLevel) — the type-selection block at lines 315-332 is O(1), but the full method walks levels. Not a performance concern for construction, but not O(1) as previously claimed
- `tetrahedronIntersectsVolume()` (line 642) — used by range queries (`spatialRangeQueryKeys` at lines 818, 1428) — only checks vertex-in-AABB and center-in-tet; misses edge/face intersections producing false negatives
- `tetrahedronIntersectsVolumeBounds()` (line 664) — line 724 has a `return true` fallback producing false positives
- `VolumeBounds.from()` (VolumeBounds.java) — switch has `default -> null`; will silently return null for any new `Spatial` implementor including `Tet`
- `Simplex.containedBy()` and `Simplex.intersects()` — both unconditionally return `false`, despite having a `TetreeKey` that could reconstruct a `Tet` for correct geometry

#### Dependency Source Verification

| Dependency | Source Searched? | Key Findings |
| --- | --- | --- |
| Tet.java (containsUltraFast) | Yes | 4-determinant barycentric test, correct for AABT containment |
| TetrahedralGeometry (SAT) | Yes | aabbIntersectsTetrahedron() already implements SAT intersection |
| Spatial.aabt | Yes | Stub — 6-float AABB disguised as AABT |
| arxiv 2603.18458 | Yes (T3) | Corner point theory, voxelization convergence, axis-aligned generalization all applicable |

### Key Discoveries

1. **Verified** — A true AABT in tetrahedral coordinates is represented by `(TetreeKey anchor, byte level)` or equivalently a `Tet` instance, not 6 floats. The Tet cell IS the minimal axis-aligned region in tetrahedral space.

2. **Verified** — The existing `containsUltraFast()` and `TetrahedralGeometry.aabbIntersectsTetrahedron()` are the correct primitives for AABT containment and intersection, respectively. They are already implemented but not wired to `Spatial.aabt`.

3. **Documented** — The paper's corner point theory justifies that checking all 4 tet vertices is sufficient for containment queries on convex regions (already done in `tetrahedronContainedInVolumeBounds()`).

4. **Verified** — Per-cell, each S0-S5 tet occupies 1/6 of its enclosing cube, giving a theoretical worst-case 5/6 false-positive rate. For range queries spanning multiple cells, practical overestimation depends on query shape, alignment, and level — estimated 2-6x reduction in candidates, to be confirmed by benchmark.

5. **Assumed** — A level-adaptive strategy (coarse AABT for frustum culling, fine AABB for point lookups) should be optimal. Needs benchmarking.

### Critical Assumptions

- [x] Corner point sufficiency for geometric containment — **Status**: Verified — **Method**: Standard convexity theory (tight bounds at vertices of convex polytopes); already implemented in `tetrahedronContainedInVolumeBounds()`
- [x] S0-S5 orderings define valid "axes" for axis-aligned regions — **Status**: Verified — **Method**: Source Search (Tet.java coordinate system matches paper's definition)
- [ ] AABT range queries will outperform AABB for queries touching >40 cells — **Status**: Unverified — **Method**: Spike (benchmark required)
- [ ] SAT test cost (~100 ops) is acceptable vs AABB test (6 comparisons) at query volumes of interest — **Status**: Unverified — **Method**: Spike (benchmark required)

## Proposed Solution

### Approach

Promote `aabt` from a 6-float record stub to a **sealed interface** that `Tet` implements directly. `Tet` already has the correct geometric primitives (`containsUltraFast()`, SAT intersection, `coordinates()`); the design simply wires them into the `Spatial` type hierarchy. The `Spatial` interface's `containedBy(aabt)` parameter type naturally becomes `containedBy(Tet)` since `Tet` is the primary `aabt` implementor.

### Technical Design

**Core design: `aabt` as sealed interface, `Tet` as implementor**

```java
// Current (wrong — aabt is just an aabb with a different name):
record aabt(float originX, float originY, float originZ,
            float extentX, float extentY, float extentZ) implements Spatial { ... }

// Proposed:
sealed interface aabt permits Tet /*, future: GeneralTet, TetUnion */ {
    boolean contains(float px, float py, float pz);
    boolean containsBound(aabt other);    // general: aabt-vs-aabt (not Tet-specific)
    boolean intersectsBound(aabt other);  // general: aabt-vs-aabt (not Tet-specific)
    float[][] vertices();  // corner points for containment checks
    VolumeBounds toVolumeBounds();  // AABB of tet vertices — transition bridge
}

// Tet implements both Spatial and aabt:
class Tet implements Spatial, aabt {
    // contains() → delegates to containsUltraFast()
    // containsBound() → check all 4 vertices of other via containsUltraFast()
    // intersectsBound() → SAT test via TetrahedralGeometry
    // vertices() → delegates to coordinates()
    // toVolumeBounds() → computes AABB from 4 vertices (for transition compatibility)
}

// Spatial interface updated:
interface Spatial {
    boolean containedBy(aabt bounds);   // parameter is now the sealed interface
    boolean intersects(aabt bounds);    // tet-native intersection
    // Keep float overload as AABB fast-path for backward compat
    boolean intersects(float oX, float oY, float oZ, float eX, float eY, float eZ);
}
```

**Key insight**: `Tet` IS the axis-aligned bounding tetrahedron — no wrapper needed. The sealed interface allows future extensions (e.g., `GeneralTet` with continuous position/scale, or `TetUnion` for complex query regions) while keeping `Tet` as the primary implementation.

**Representation sufficiency analysis**: The concern was raised that a single `Tet` (a quantized grid cell) is insufficient as a general bounding volume, analogous to how an octree cube cell is not an AABB. Deep analysis confirmed that `aabt` flows exclusively from **tree → query** (as a tree-node-bound parameter) and is never constructed as a standalone general-purpose bounding volume. Query volumes use the separate `Spatial` type hierarchy (`Sphere`, `aabb`, `Tetrahedron`). For tree-node bounds, `Tet` IS the correct type. If a general-purpose AABT is ever needed (arbitrary position/scale, not grid-quantized), `record GeneralTet(float x, float y, float z, float scale, byte type)` can be added as a new `permits` type on the sealed interface without changing existing code.

**Interface method naming**: Methods use general names (`containsBound`, `intersectsBound`) rather than Tet-specific names to ensure future `aabt` implementors compose naturally.

**Migration: atomic `containedBy(aabt)` callsite inventory**

The `aabt` record → sealed interface change must be atomic (single commit). All 9 callsites that currently read `.originX()` etc. must be updated simultaneously:

**Note on sealed interface circularity**: `Spatial` contains nested `aabt`, `aabt` permits `Tet`, and `Tet implements Spatial, aabt`. This circular type reference is legal in Java — implementing an interface declared inside another interface you also implement is permitted. The `sealed` permits clause works cross-package within the same JPMS module. However, to reduce coupling, `aabt` MAY be extracted to a top-level interface in package `lucien` rather than remaining nested in `Spatial` — this is an implementation-time decision that does not affect the design.

| # | Callsite | Current Implementation | New Implementation |
|---|----------|----------------------|-------------------|
| 1 | `Cube.containedBy(aabt)` | 6-float AABB comparison | `aabt.contains(cornerMin) && aabt.contains(cornerMax)` via `containsUltraFast` |
| 2 | `Sphere.containedBy(aabt)` | 6-float AABB comparison | Face-normal distance test: for each of 4 tet face normals, check `dot(normal, center) - d + radius ≤ 0` (sphere fully inside half-space). Note: this is exact for tet containment, unlike the 6 axis-aligned extremal point check which is only correct for AABB containment |
| 3 | `Parallelepiped.containedBy(aabt)` | 6-float AABB comparison | `aabt.contains()` on all 8 box corners |
| 4 | `Tetrahedron.containedBy(aabt)` | `vertexInBounds` with 6-float checks | `aabt.contains(a) && aabt.contains(b) && aabt.contains(c) && aabt.contains(d)` |
| 5 | `aabb.containedBy(aabt)` | 6-float AABB comparison | `aabt.contains()` on all 8 box corners |
| 6 | `aabt.containedBy(aabt)` | 6-float AABB comparison | Replaced by `Tet` — `aabt.containsBound(this)` |
| 7 | `BoundingBox.containedBy(aabt)` (RangeHandle.java) | 6-float AABB comparison | `aabt.toVolumeBounds()` then AABB compare, or `aabt.contains()` on corners |
| 8 | `VolumeBounds.from(aabt)` switch case | `case Spatial.aabt` reads 6 float accessors | **Delete** existing `case Spatial.aabt` branch (record accessors gone); **add** `case Tet tet -> tet.toVolumeBounds()`. Note: if `aabt` becomes a sealed interface, the compiler may require exhaustive matching of `aabt` subtypes in pattern switches |
| 9 | `Spatial.default intersects(aabt)` (lines 19-21) | `return intersects(aabp.originX, ...)` — dereferences record accessors | Rewrite to `return intersects(aabp)` where `intersects(aabt)` dispatches via `aabt.intersectsBound()` or the concrete `Spatial` impl, OR remove the default method and require each `Spatial` implementor to provide `intersects(aabt)` directly |

Additionally: `Simplex.containedBy(aabt)` currently returns `false` unconditionally — reconstruct `Tet` from `index` and delegate to `aabt.containsBound()`.

**AABT-based range query construction:**

The naive approach of locating corner-point TetreeKeys and taking `[min_key, max_key]` is incorrect — the SFC is space-filling but not Euclidean-ordered, so the interval includes many irrelevant tets and may miss valid ones. The correct approach uses the existing hierarchical Tetree traversal:

```text
// AABT range query for volume Q using Tet as the bounding primitive:
1. Start from root Tet
2. At each level, test children against Q using AABT intersection (SAT test)
3. Prune branches whose tets do not intersect Q
4. Collect leaf TetreeKeys that pass intersection → these are the SFC ranges
// This extends the existing spatialRangeQueryKeys() / RangeHandle / LazySFCRangeStream
// path, replacing the AABB intersection filter with the SAT-based tet intersection.
```

This produces a set of SFC ranges with zero false negatives. The existing `spatialRangeQueryKeys()` already does hierarchical range computation via `RangeHandle` — the change is to replace the AABB intersection test in the traversal with the SAT-based tet-tet intersection.

**Performance characteristics:**

| Operation | AABB (current) | True AABT (Tet) |
|-----------|----------------|-----------------|
| Single containment test | 6 comparisons | 4 determinants (~40 ops) |
| Intersection test | 6 comparisons | SAT: 13 axes, ~100 ops |
| Range query false positives | Theoretical worst-case 5/6 per cell | Reduced — exact amount TBD by benchmark |
| Memory per bound (heap) | ~40 bytes (16 header + 24 fields) | ~32 bytes (16 header + 14 fields + padding) |

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| `aabt` sealed interface | `Spatial.aabt` record (Spatial.java:182) | Replace: 6-float record → sealed interface |
| AABT containment | `Tet.containsUltraFast()` | Reuse: wire as `aabt.contains()` impl |
| AABT intersection | `TetrahedralGeometry.aabbIntersectsTetrahedron()` | Extend: generalize to tet-tet and tet-frustum |
| `Tet` class | `Tet.java` | Extend: `implements Spatial, aabt` |
| Range query traversal | `spatialRangeQueryKeys()` / `RangeHandle` / `LazySFCRangeStream` | Extend: replace AABB intersection filter with SAT-based tet intersection in hierarchical walk |
| `VolumeBounds.from()` | `VolumeBounds.java` | Extend: add `case Tet` branch using `toVolumeBounds()` |
| `Simplex` | `Simplex.java` | Fix: reconstruct `Tet` from `index`, delegate to `aabt` methods |
| `tetrahedronIntersectsVolume()` | `Tet.java:642` | Fix: add SAT-based edge/face intersection (currently only vertex + center tests) |
| `tetrahedronIntersectsVolumeBounds()` | `Tet.java:664` | Fix: replace line 724 `return true` with SAT test |

### Decision Rationale

The Tetree's native geometry is tetrahedral. Using AABBs for bounding volumes in a tetrahedral index is analogous to using circular bounds in a rectangular grid — geometrically mismatched. The existing codebase already has the correct primitives (containment, intersection, type selection); they just aren't composed into a coherent AABT abstraction.

The paper provides theoretical justification that axis-aligned regions in non-orthogonal coordinate systems yield provably tighter bounds than rectangular approximations, with convergence guarantees as resolution increases.

## Alternatives Considered

### Alternative 1: Tighter AABBs via multi-level refinement

**Description**: Keep AABB representation but use finer-grained AABBs (multiple smaller boxes) to approximate tetrahedra.

**Pros**:
- No API changes needed
- AABB intersection remains cheap (6 comparisons)

**Cons**:
- Needs O(k) boxes to approximate a tetrahedron with acceptable tightness
- Increases memory and query complexity
- Still fundamentally mismatched geometry

**Reason for rejection**: Treats the symptom (loose bounds) without addressing the cause (wrong geometry). Multiple AABBs converge slowly to tetrahedral shape.

### Alternative 2: Oriented Bounding Boxes (OBBs)

**Description**: Use arbitrarily oriented bounding boxes that can be rotated to fit tetrahedra more tightly.

**Pros**:
- Tighter fit than AABBs
- Well-studied in collision detection literature

**Cons**:
- OBB-OBB intersection is expensive (15 separating axis tests)
- No natural alignment to the Tetree's coordinate system
- Cannot leverage the S0-S5 structure

**Reason for rejection**: OBBs are general-purpose; we have a structured coordinate system that should be exploited.

### Alternative 3: Wrapper record `aabt(Tet tet)`

**Description**: Keep `aabt` as a record wrapping a `Tet` instance, delegating all geometric operations.

**Pros**:
- Minimal changes to existing `Spatial` interface
- Clear separation of concerns

**Cons**:
- Unnecessary indirection — `Tet` already IS the bounding tetrahedron
- Extra allocation per bounding volume
- Cannot leverage `Tet` identity for SFC range queries without unwrapping

**Reason for rejection**: `Tet` is the natural type for axis-aligned bounding tetrahedra. Wrapping it adds indirection without value. A sealed interface lets `Tet` serve directly as the `aabt` while allowing future extensions.

### Briefly Rejected

- **Bounding spheres**: Even looser than AABBs for tetrahedra; waste ~85% of volume.
- **Convex hull queries**: Exact but O(n log n) per query; too expensive for real-time.

## Trade-offs

### Consequences

- **Positive**: Estimated 2-6x reduction in false-positive candidates for range queries (theoretical worst-case 5/6 per cell; actual reduction depends on query shape — benchmark required)
- **Positive**: Geometrically correct abstraction aligned with Tetree's native coordinate system
- **Positive**: Enables frustum culling in tetrahedral space (tighter than current AABB frustum culling)
- **Positive**: Fixes existing bugs (`tetrahedronIntersectsVolume` false negatives, `tetrahedronIntersectsVolumeBounds` line 724 false positives, `Simplex` always-false, `VolumeBounds.from()` null gap)
- **Negative**: Per-test cost increases from 6 comparisons (AABB) to ~40-100 ops (AABT)
- **Negative**: API change — 9 callsites (7 `containedBy` + `VolumeBounds.from()` + `Spatial.default intersects`) must be migrated atomically in a single commit

### Risks and Mitigations

- **Risk**: AABT per-test overhead negates false-positive reduction for small query volumes.
  **Mitigation**: Level-adaptive strategy — use AABT for coarse queries (frustum culling, large ranges) and AABB for fine-grained point lookups. Benchmark to find crossover point. The crossover estimate (~40 cells) is speculative and must be validated by spike.

- **Risk**: Sealed interface migration breaks compilation.
  **Mitigation**: Atomic single-commit migration of all 9 callsites (see callsite inventory in Technical Design). The `toVolumeBounds()` bridge method on `aabt` allows callsites to fall back to AABB comparison during transition where tet-native tests are not yet beneficial.

- **Risk**: `VolumeBounds.from()` returns null for `Tet`, causing silent NPEs.
  **Mitigation**: Add `case Tet` branch to `VolumeBounds.from()` as part of the atomic migration commit.

### Failure Modes

- **Silent degradation**: If AABT intersection is incorrect, queries silently miss entities. Detectable by comparing AABT results against exhaustive AABB results in tests. The `tetrahedronIntersectsVolume` method (used by range queries) currently has this problem — must be fixed.
- **Silent null return**: `VolumeBounds.from(Tet)` returns null via `default -> null`. Any code path that passes a `Tet` as `Spatial` to `VolumeBounds.from()` will NPE. Fix: add explicit `case Tet` branch.
- **Performance regression**: If AABT overhead exceeds false-positive savings. Detectable via existing benchmark infrastructure (`OctreeVsTetreeBenchmark`).
- **Boundary double-counting/gaps**: Adjacent tets share faces. The `containsUltraFast` boundary handling (via `<=` comparisons in type selection) must be proven gap-free and overlap-free. Must be tested explicitly.

## Implementation Plan

### Prerequisites

- [ ] All Critical Assumptions verified (benchmark AABT vs AABB crossover)
- [ ] Review both `tetrahedronIntersectsVolume()` (line 642) and `tetrahedronIntersectsVolumeBounds()` (line 664)

### Minimum Viable Validation

A benchmark comparing AABT vs AABB range query candidate counts and wall-clock time across varying query volumes (1-cell to 1000-cell), demonstrating the crossover point where AABT becomes faster.

### Phase 1: Fix Existing Bugs (independent of AABT)

These can be committed separately, before the sealed interface migration.

#### Step 1: Fix `tetrahedronIntersectsVolume()` (line 642)

This is the method used by `spatialRangeQueryKeys()` (called at lines 818 and 1428). Currently only checks vertex-in-AABB and center-in-tet, missing edge/face intersections. Add the SAT test from `TetrahedralGeometry.aabbIntersectsTetrahedron()` as a fallback when the simple checks fail.

#### Step 2: Fix `tetrahedronIntersectsVolumeBounds()` line 724

Replace `return true` fallback with the SAT test from `TetrahedralGeometry.aabbIntersectsTetrahedron()`.

### Phase 2: Sealed Interface Migration (atomic commit)

**This must be a single atomic commit.** The `aabt` record cannot coexist with the `aabt` sealed interface — the moment the record is removed, all 9 callsites that read `.originX()` etc. will fail to compile.

#### Step 1: Define `aabt` sealed interface and implement on `Tet`

In a single commit:

1. Replace `record aabt(...)` with `sealed interface aabt permits Tet`
2. Add methods to `aabt`: `contains(float,float,float)`, `containsBound(Tet)`, `intersectsBound(Tet)`, `vertices()`, `toVolumeBounds()`
3. Have `Tet` implement `Spatial, aabt`:
   - `contains()` → delegates to `containsUltraFast()`
   - `containsBound()` → check all 4 vertices via `containsUltraFast()`
   - `intersectsBound()` → SAT test via `TetrahedralGeometry`
   - `vertices()` → delegates to `coordinates()`
   - `toVolumeBounds()` → computes AABB from 4 vertices (transition bridge)
4. Update all 9 callsites (see callsite inventory in Technical Design), including:
   - All 7 `containedBy(aabt)` implementations in `Spatial` inner types and `RangeHandle.BoundingBox`
   - `VolumeBounds.from()`: **delete** existing `case Spatial.aabt` branch (record accessors gone), **add** `case Tet tet -> tet.toVolumeBounds()`
   - `Spatial.default intersects(aabt)` (lines 19-21): rewrite or remove — currently calls `.originX()` etc. which won't exist on the sealed interface
6. Fix `Simplex.containedBy(aabt)` — reconstruct `Tet` from `index`, delegate to `aabt.containsBound()`
7. Fix `Simplex.intersects()` — reconstruct `Tet`, delegate to SAT intersection

### Phase 3: AABT Range Queries

#### Step 1: Design spike — validate hierarchical AABT traversal

Extend the existing `spatialRangeQueryKeys()` / `RangeHandle` / `LazySFCRangeStream` path to use SAT-based tet intersection instead of AABB intersection during the hierarchical tree walk. The correct algorithm is:

1. Start from root Tet
2. At each level, test children against query volume Q using SAT tet-intersection
3. Prune branches whose tets do not intersect Q
4. Collect leaf TetreeKeys that pass → these form the SFC ranges

**Note**: Corner-point SFC lookup (locating tets of Q's corners and taking `[min_key, max_key]`) is NOT correct — the SFC is not Euclidean-ordered, so the interval includes irrelevant tets and may miss valid ones.

#### Step 2: Benchmark AABT vs AABB candidate counts

Run comparative benchmark across query volumes to determine the crossover point where AABT traversal cost is offset by reduced false positives.

### Phase 4: Integration

#### Step 1: Level-adaptive strategy

Based on benchmark results, implement automatic selection of AABT vs AABB based on query volume size and tree level.

#### Step 2: Migrate collision detection and frustum culling

Update collision detection and frustum culling to use AABT where beneficial.

### New Dependencies

None — all required primitives exist in the codebase.

## Test Plan

- **Scenario**: AABT containment matches exhaustive point-in-tet test for all S0-S5 types — **Verify**: 100% agreement on 10K random points
- **Scenario**: AABT range query returns subset of AABB range query results — **Verify**: AABT results ⊆ AABB results for 100 random query volumes
- **Scenario**: AABT range query returns all true intersections — **Verify**: No false negatives vs exhaustive check
- **Scenario**: Benchmark AABT vs AABB candidate counts — **Verify**: AABT produces measurably fewer candidates (target TBD by spike)
- **Scenario**: `tetrahedronIntersectsVolume` fix eliminates false negatives — **Verify**: Before/after comparison with known edge/face intersection cases (tet edge crosses AABB face but no vertices inside)
- **Scenario**: `tetrahedronIntersectsVolumeBounds` line 724 fix eliminates false positives — **Verify**: Before/after comparison on known false-positive cases
- **Scenario**: `VolumeBounds.from(Tet)` returns correct AABB — **Verify**: Matches manual AABB computation from tet vertices for all S0-S5 types
- **Scenario**: `Simplex.containedBy/intersects` returns correct results — **Verify**: No longer unconditionally false; matches `Tet`-based geometric tests
- **Scenario**: `Sphere.containedBy(aabt)` uses face-normal distance test — **Verify**: Sphere near tet edge with all 6 axis-aligned extremals inside but surface protruding outside → correctly returns `false`
- **Scenario**: `Spatial.intersects(aabt)` dispatches correctly — **Verify**: All `Spatial` implementors' `intersects(aabt)` produces correct results (no residual record accessor calls)
- **Scenario**: Boundary handling — no gaps or double-counting at shared tet faces — **Verify**: For all 6 S0-S5 types in a cube, every point in the cube is contained by exactly one tet (test with points on shared faces and edges, including degenerate positions where u==v, v==w, u==w)

## Validation

### Testing Strategy

1. **Scenario**: Correctness — AABT containment and intersection agree with exhaustive geometric tests across all S0-S5 types and 21 levels
   **Expected**: Zero disagreements

2. **Scenario**: Tightness — AABT range queries return fewer candidates than AABB queries
   **Expected**: Measurably fewer candidates (target: 2-6x reduction)

3. **Scenario**: No regressions — Existing Tetree tests continue to pass
   **Expected**: All green

### Performance Expectations

AABT per-test cost is higher than AABB (~40-100 ops vs 6 comparisons). The win comes from processing fewer candidates. Empirical benchmarking required to determine crossover point — expected around 40+ cells per query based on operation count analysis.

## Finalization Gate

### Contradiction Check

No contradictions found between research findings, design principles, and proposed solution. The paper's theory directly supports the approach, and the codebase already contains the required primitives.

### Assumption Verification

Two assumptions remain unverified and require benchmarking before implementation:
1. AABT crossover point (~40 cells) — needs spike
2. SAT test cost acceptability — needs spike

### Scope Verification

The Minimum Viable Validation (benchmark comparing AABT vs AABB) is Phase 3 Step 2, executed after Phase 1 (bug fixes) and Phase 2 (sealed interface migration). The benchmark validates the AABT range query approach (Phase 3 Step 1) before proceeding to Phase 4 (integration). Phase 1 and Phase 2 do not depend on benchmark results — they fix existing bugs and establish the type-safe abstraction respectively. The unverified assumptions (crossover point, SAT cost) gate Phase 4 integration decisions, not the sealed interface migration.

### Cross-Cutting Concerns

- **Versioning**: N/A (internal API, not serialized)
- **Build tool compatibility**: N/A (no new dependencies)
- **Licensing**: N/A
- **Deployment model**: N/A (library)
- **IDE compatibility**: N/A
- **Incremental adoption**: Phase 1 fixes bugs independently. Phase 2 is an atomic sealed-interface migration. Phase 3-4 extend range queries and integration incrementally. The `toVolumeBounds()` bridge allows callsites to use AABB comparison during transition.
- **Secret/credential lifecycle**: N/A
- **Memory management**: `Tet` heap footprint is ~32 bytes (16-byte object header + 3×int + 2×byte + padding) vs `aabb` record ~40 bytes (16-byte header + 6×float). Comparable; `Tet` is slightly smaller.

### Proportionality

Document is right-sized for an architectural change affecting core spatial query paths.

## References

- Zhu, He, Tawarmalani. "Axis-Aligned Relaxations for Mixed-Integer Nonlinear Programming." arxiv 2603.18458, March 2026. (Indexed in T3) — voxelization convergence and axis-aligned generalization results apply; corner point sufficiency follows from standard convexity
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/Spatial.java:182` — current `aabt` stub (7 `containedBy(aabt)` + 1 `default intersects(aabt)` + declaration = 9 callsites)
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java:1015` — `containsUltraFast()`
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java:272` — `locatePointBeyRefinementFromRoot()` (O(targetLevel), type selection at 315-332)
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java:642` — `tetrahedronIntersectsVolume()` (incomplete — used by range queries at lines 818, 1428)
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java:664` — `tetrahedronIntersectsVolumeBounds()` (line 724 `return true` fallback)
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/TetrahedralGeometry.java:46` — SAT intersection
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/VolumeBounds.java` — `from()` switch with `default -> null`
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/Simplex.java` — `containedBy`/`intersects` return `false` unconditionally
- `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/RangeHandle.java:191` — `BoundingBox.containedBy(aabt)` reads 6-float accessors
- `lucien/doc/LUCIEN_ARCHITECTURE.md` — Architecture overview
- `lucien/doc/TETREE_T8CODE_PARTITION_ANALYSIS.md` — T8code partition analysis

## Revision History

### 2026-03-21: Initial Draft

Created from deep research synthesis of arxiv 2603.18458 and codebase analysis. Key finding: existing primitives (`containsUltraFast`, SAT intersection, type selection) are correct but not composed into a coherent AABT abstraction. `Spatial.aabt` is a stub requiring redesign.

### 2026-03-21: Design Revision — Sealed Interface

Revised core design from wrapper record `aabt(Tet)` to sealed interface `aabt` with `Tet` as primary implementor. Rationale: `Tet` IS the axis-aligned bounding tetrahedron — wrapping it adds indirection without value. Sealed interface allows future extensions (e.g., `TetUnion` for complex query regions).

### 2026-03-21: Gate Response — Address All Blocked Issues

Gate returned BLOCKED with 1 critical, 4 significant, 3 observations. All addressed:

1. **Critical — Atomic migration**: Added complete callsite inventory (8 sites) with specific migration strategy for each. Clarified that Phase 2 must be a single atomic commit. Added `toVolumeBounds()` bridge method for transition compatibility.
2. **Significant — AABT construction algorithm**: Replaced incorrect corner-point SFC lookup with hierarchical Tetree traversal extending existing `spatialRangeQueryKeys()`/`RangeHandle`/`LazySFCRangeStream`. Added design spike step before implementation.
3. **Significant — 6x inconsistency**: Replaced "6x" with accurate "theoretical worst-case 5/6 per cell, practical 2-6x TBD by benchmark." Removed internal inconsistencies (was 6x/5x/3x in different places).
4. **Significant — Wrong intersection method**: Added `tetrahedronIntersectsVolume()` (line 642) to scope — this is the method used by `spatialRangeQueryKeys()`, not `tetrahedronIntersectsVolumeBounds()`. Both methods now in Phase 1.
5. **Significant — VolumeBounds.from() null**: Added `case Tet` branch to `VolumeBounds.from()` as part of atomic migration scope. Added as failure mode.
6. **Observation — Memory estimate**: Corrected to ~32 bytes (Tet) vs ~40 bytes (aabb) with proper Java object header accounting.
7. **Observation — Simplex.containedBy**: Added to Phase 2 atomic migration — reconstruct `Tet` from `index`, delegate to `aabt` methods.
8. **Observation — Boundary handling**: Added explicit test scenario for shared-face gap/overlap-free invariant across all S0-S5 types.
9. **Paper citation**: Corrected corner point sufficiency to cite standard convexity theory, not the MINLP paper's theorem (which addresses function values, not geometric containment).

### 2026-03-21: Gate Response Round 2 — Address 3 New Issues

Gate round 2 returned BLOCKED with 1 new critical, 2 new significant (all 9 previous issues confirmed resolved). All addressed:

1. **Critical — Missed 9th callsite**: Added `Spatial.default intersects(aabt)` (lines 19-21) as callsite #9. It calls `.originX()` etc. which won't exist on the sealed interface. Must be rewritten or removed in the atomic commit.
2. **Significant — VolumeBounds.from() existing branch**: Clarified that the existing `case Spatial.aabt` branch must be **deleted** (not just augmented with `case Tet`), since it reads record accessors that won't exist on the sealed interface.
3. **Significant — Sphere.containedBy(aabt) geometry**: Replaced 6 axis-aligned extremal point check with face-normal signed distance test: `dot(normal, center) - d + radius ≤ 0` for each of 4 tet faces. The axis-aligned check was only correct for AABB containment, not tetrahedral.
4. **Observation — Phase ordering**: Clarified that benchmark is Phase 3 Step 2, gating Phase 4 integration decisions. Phases 1-2 (bug fixes + sealed interface) do not depend on benchmark results.
5. **Sealed interface circularity**: Added explicit note acknowledging the `Spatial` → `aabt` → `Tet` → `Spatial` circular type reference is legal in Java, with option to extract `aabt` as top-level interface to reduce coupling.

### 2026-03-22: Representation Sufficiency Analysis

Concern raised that a single `Tet` (quantized grid cell) is insufficient as AABT, analogous to how an octree cube cell is not an AABB. Deep analysis confirmed `aabt` flows exclusively from tree → query (never constructed as standalone bounding volume), so `Tet` IS sufficient for current architecture. Added representation sufficiency analysis to Technical Design. Renamed interface methods from Tet-specific (`containsTet`, `intersectsTet`) to general (`containsBound`, `intersectsBound`) for future extensibility. Future `GeneralTet(float x, float y, float z, float scale, byte type)` can be added as `permits` type if needed.
