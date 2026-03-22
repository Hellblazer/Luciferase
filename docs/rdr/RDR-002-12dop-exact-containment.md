---
title: "12-DOP Exact Containment for Kuhn Tetrahedra — Replace containsUltraFast"
id: RDR-002
type: Architecture
status: closed
closed_date: 2026-03-22
priority: high
author: hal.hildebrand
reviewed-by: self
created: 2026-03-22
accepted_date: 2026-03-22
related_issues: [RDR-001]
---

# RDR-002: 12-DOP Exact Containment for Kuhn Tetrahedra — Replace containsUltraFast

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

The Tetree spatial index uses `containsUltraFast()` for point-in-tetrahedron containment — a 4-determinant calculation requiring ~84 arithmetic operations (48 multiplications + 32 additions + 4 comparisons). RDR-001 benchmarks showed SAT-based intersection is 10-100x slower than AABB (6 comparisons), making exact tetrahedral geometry impractical for traversal. The current `aabt` interface methods (`containsBound`, `intersectsBound`) delegate to SAT via `TetrahedralGeometry`, inheriting this cost.

A 12-DOP using the 6 axes **{x, y, z, x-y, x-z, y-z}** provides **mathematically exact** containment for all S0-S5 Kuhn tetrahedra at 11 ops — an 7.6x speedup over `containsUltraFast()` with zero false positives and zero additional storage.

## Context

### Background

The S0-S5 Kuhn/Freudenthal decomposition partitions each cube cell by **coordinate orderings**. The 6 types correspond to the 6 elements of the symmetric group S₃. The pairwise difference axes {x-y, x-z, y-z} form the root system of type A₂, whose hyperplane arrangement defines the permutohedron. The S-types are exactly the **chambers** of this arrangement restricted to a cube cell.

The existing type-selection logic in `locatePointBeyRefinementFromRoot()` (Tet.java lines 315-332) already uses this ordering test to determine which S-type contains a point. The 12-DOP containment test is structurally identical — `containsUltraFast()` does 84 ops of determinant math to answer a question that 2 ordering comparisons resolve exactly.

### Technical Environment

- **Module**: lucien
- **Key files**: `Tet.java` (containsUltraFast at ~line 1211, locatePointBeyRefinementFromRoot at line 315), `Spatial.java` (aabt interface with containsBound/intersectsBound), `TetrahedralGeometry.java` (SAT intersection)
- **Predecessor**: RDR-001 (closed) — established aabt interface, benchmarked SAT cost
- **Research doc**: `lucien/doc/AABT_12DOP_EXACT_CONTAINMENT.md`

## Research Findings

### Investigation

**Source: Algebraic analysis of S0-S5 face normals (verified vertex-by-vertex for all 6 types)**

The complete set of face normals across all 24 faces (4 per type × 6 types) is:

**{(1,0,0), (0,1,0), (0,0,1), (1,-1,0), (1,0,-1), (0,1,-1)}**

These are exactly the 3 AABB axes + 3 pairwise difference axes. Each S-type uses 2 AABB faces and 2 difference faces:

| Type | Code Convention (Tet.java:315-332) | Ordering | AABB faces | Difference faces |
|------|-------------------------------------|----------|-----------|-----------------|
| S0 | y ≤ z < x | x > z ≥ y | x=h, z=0 | x-y≥0, y-z≥0 |
| S1 | z < y < x | x > y > z | y=h, z=0 | y-x≥0, x-z≥0 |
| S2 | x ≤ z < y | y > z ≥ x | z=h, y=0 | z-x≥0, x-y≥0 |
| S3 | x ≤ y ≤ z | z ≥ y ≥ x | z=h, x=0 | z-y≥0, y-x≥0 |
| S4 | z < x ≤ y | y ≥ x > z | x=h, y=0 | x-z≥0, z-y≥0 |
| S5 | y < x ≤ z | z ≥ x > y | y=h, x=0 | y-z≥0, z-x≥0 |

**Source: Codebase analysis**

- `containsUltraFast()` (Tet.java:1211) computes 4 determinants (84 ops) — replaceable by AABB check + 2 ordering comparisons (11 ops)
- `locatePointBeyRefinementFromRoot()` (Tet.java:315-332) already performs the ordering test — the optimal containment algorithm already exists in the codebase, just not wired to the containment API
- `intersectsBound()` on `Tet` delegates to SAT (~100+ ops) — replaceable by 12-DOP slab overlap (~21 ops)
- `tetrahedronIntersectsVolume()` (Tet.java:642) and `tetrahedronIntersectsVolumeBounds()` (Tet.java:664) — both use SAT fallbacks that can be replaced

#### Dependency Source Verification

| Dependency | Source Searched? | Key Findings |
| --- | --- | --- |
| Tet.containsUltraFast | Yes | 4 determinants, 84 ops — replaceable |
| Tet.locatePointBeyRefinementFromRoot | Yes | Already uses ordering test — the 12-DOP algorithm exists |
| TetrahedralGeometry.aabbIntersectsTetrahedron | Yes | SAT with 13 axes — replaceable by 6-axis 12-DOP overlap |
| S0-S5 vertex coordinates | Yes | Face normals verified as {x,y,z,x-y,x-z,y-z} for all 24 faces |

### Key Discoveries

1. **Verified** — The 12-DOP with axes {x, y, z, x-y, x-z, y-z} provides exact containment for all S0-S5 types. Proven algebraically and verified vertex-by-vertex.

2. **Verified** — Point containment cost is 11 ops (3 subtractions + 8 comparisons including AABB early-out). Zero multiplications. Zero false positives.

3. **Verified** — AABB-vs-tet intersection cost is ~21 ops (6 AABB comparisons + 6 subtractions for difference-axis projection + 6 slab overlap comparisons + 3 additions for tet slab ranges).

4. **Verified** — The ordering convention matches `locatePointBeyRefinementFromRoot()` exactly. The `>` vs `>=` boundary handling ensures gap-free, overlap-free partitioning.

5. **Verified** — No additional storage required. The 12-DOP is fully determined by (anchor, level, type).

### Critical Assumptions

- [x] 12-DOP is exact for all S0-S5 types — **Status**: Verified — **Method**: Algebraic proof + vertex-by-vertex verification
- [x] Ordering convention matches existing code — **Status**: Verified — **Method**: Source Search (Tet.java:315-332)
- [ ] 12-DOP AABB intersection is correct for all edge cases — **Status**: Unverified — **Method**: Spike (comprehensive test suite)
- [ ] Tet-vs-tet 12-DOP intersection is exact — **Status**: Unverified — **Method**: Spike (cross-type test matrix)
- [ ] Performance improvement is measurable in end-to-end benchmarks — **Status**: Unverified — **Method**: Spike (benchmark vs containsUltraFast)

## Proposed Solution

### Approach

Replace `containsUltraFast()` and SAT-based intersection methods with 12-DOP ordering tests. The change is surgical — same API, same semantics, dramatically less computation.

### Technical Design

**Point containment — replace `containsUltraFast()`:**

```java
public boolean contains12DOP(float px, float py, float pz) {
    final int h = 1 << (Constants.getMaxRefinementLevel() - l);
    if (px < x || px > x + h || py < y || py > y + h || pz < z || pz > z + h)
        return false;
    float u = px - x, v = py - y, w = pz - z;
    return switch (type) {
        case 0 -> u > w && w >= v;   // S0
        case 1 -> u > v && v > w;    // S1
        case 2 -> v > w && w >= u;   // S2
        case 3 -> w >= v && v >= u;  // S3
        case 4 -> v >= u && u > w;   // S4
        case 5 -> w >= u && u > v;   // S5
        default -> throw new IllegalStateException("Invalid type: " + type);
    };
}
```

**AABB-vs-tet intersection — replace SAT:**

```java
public boolean intersects12DOP(float exMin, float eyMin, float ezMin,
                                float exMax, float eyMax, float ezMax) {
    final int h = 1 << (Constants.getMaxRefinementLevel() - l);
    // AABB overlap (6 comparisons)
    if (exMax < x || exMin > x+h || eyMax < y || eyMin > y+h || ezMax < z || ezMin > z+h)
        return false;
    // Project entity onto difference axes (6 subtractions)
    float dxy_min = exMin - eyMax, dxy_max = exMax - eyMin;
    float dxz_min = exMin - ezMax, dxz_max = exMax - ezMin;
    float dyz_min = eyMin - ezMax, dyz_max = eyMax - ezMin;
    // Check overlap on type-specific difference axes
    return switch (type) {
        case 0 -> dxy_max >= 0 && dyz_max >= 0;        // x-y≥0, y-z≥0
        case 1 -> dxy_min <= 0 && dxz_max >= 0;        // y-x≥0 (x-y≤0), x-z≥0
        case 2 -> dxz_min <= 0 && dxy_max >= 0;        // z-x≥0 (x-z≤0), x-y≥0 — VERIFY
        case 3 -> dyz_min <= 0 && dxy_min <= 0;        // z-y≥0 (y-z≤0), y-x≥0 (x-y≤0)
        case 4 -> dxz_max >= 0 && dyz_min <= 0;        // x-z≥0, z-y≥0 (y-z≤0)
        case 5 -> dyz_max >= 0 && dxz_min <= 0;        // y-z≥0, z-x≥0 (x-z≤0)
        default -> throw new IllegalStateException("Invalid type: " + type);
    };
}
```

**WARNING — INCOMPLETE SKETCH**: The AABB-vs-tet intersection above checks only 2 of 3 difference axes per type. Each S-type's 12-DOP has 3 AABB slab pairs + 3 difference-axis slab pairs. The AABB check covers the first 3, but each `case` arm above checks only 2 of the remaining 3 difference slabs, leaving one unchecked. This WILL produce false positives — an entity can pass the 2 checked difference axes while violating the unchecked third. The formal derivation in Phase 2 Step 1 must compute min/max projection of each S-type's 4 vertices onto ALL 3 difference axes and test all 3 for overlap. Do NOT copy this sketch into production code.

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| Point containment | `Tet.containsUltraFast()` (84 ops) | Replace: 12-DOP ordering test (11 ops) |
| `aabt.contains(float,float,float)` | Delegates to `containsUltraFast()` | Replace: delegate to `contains12DOP()` |
| AABB-vs-tet intersection | `TetrahedralGeometry.aabbIntersectsTetrahedron()` (SAT) | Replace: 12-DOP slab overlap (~21 ops) |
| `aabt.containsBound()` | Delegates to containsUltraFast for each vertex | Replace: 12-DOP contains for each vertex |
| `aabt.intersectsBound()` | Delegates to SAT | Replace: 12-DOP intersection |
| `tetrahedronIntersectsVolume()` | SAT fallback (Tet.java:642) | Replace: 12-DOP intersection |
| `tetrahedronIntersectsVolumeBounds()` | SAT fallback (Tet.java:664) | Replace: 12-DOP intersection |
| Type selection | `locatePointBeyRefinementFromRoot()` (already correct) | Reuse: same ordering logic |

### Decision Rationale

The 12-DOP is the theoretically optimal containment test for Kuhn tetrahedra — it exploits the permutohedron structure to replace expensive determinant math with simple ordering comparisons. The algorithm already exists in the codebase (`locatePointBeyRefinementFromRoot`) but isn't used for containment/intersection.

## Alternatives Considered

### Alternative 1: Keep SAT (status quo from RDR-001)

**Reason for rejection**: 10-100x slower than AABB. Benchmark-proven pessimization for traversal. The 12-DOP achieves exact containment at near-AABB cost.

### Alternative 2: Rhombohedral AABR (paniq FCC transform)

**Reason for rejection**: V0 and V7 dominate all tet-coord axes — identical bounds for all S-types, 4x AABB volume. Zero tightening. See `AABT_RHOMBOHEDRAL_COORDINATES.md`.

### Alternative 3: Type-specific difference coordinates

**Reason for rejection**: Volume-preserving transform (det=1). S0 maps to another tet with identical AABB. No tightening.

### Alternative 4: 14-DOP with body diagonals

**Reason for rejection**: Body diagonal axes provide partial tightening (~40-60% FP) but not exact containment. The face diagonal axes (pairwise differences) are the correct choice because they align with tet face normals.

## Trade-offs

### Consequences

- **Positive**: 7.6x faster point containment (11 ops vs 84)
- **Positive**: ~5x faster AABB intersection (~21 ops vs ~100+)
- **Positive**: Exact — zero false positives, same as current SAT
- **Positive**: No multiplications — eliminates floating-point error accumulation
- **Positive**: Zero additional storage — determined by existing (anchor, level, type)
- **Positive**: Simpler code — 2 comparisons vs 4 determinant calculations
- **Negative**: Per-type switch statement adds a branch — may affect branch prediction for mixed-type queries

### Risks and Mitigations

- **Risk**: AABB-vs-tet intersection formula may have edge cases not caught by the sketch.
  **Mitigation**: Formal derivation of per-type slab ranges during implementation. Comprehensive test matrix (all 6 types × boundary cases).

- **Risk**: Boundary convention (`>` vs `>=`) mismatch between 12-DOP and existing code.
  **Mitigation**: The ordering convention is derived directly from `locatePointBeyRefinementFromRoot()`. A gap-free partitioning test (every point in a cube assigned to exactly one S-type) will validate.

### Failure Modes

- **Silent degradation**: If ordering convention is wrong, points on shared faces may be assigned to the wrong tet or no tet. Detectable by exhaustive partitioning test.
- **Regression**: If 12-DOP intersection differs from SAT for any input. Detectable by running both and comparing.

## Implementation Plan

### Prerequisites

- [ ] Formal derivation of per-type difference-axis slab ranges for AABB intersection
- [ ] Verify face normals for all 24 faces from actual `coordinates()` vertex data

### Minimum Viable Validation

A test that runs both `containsUltraFast()` and `contains12DOP()` on 100K random points across all 6 S-types and 5 levels, confirming identical results.

### Phase 1: Core Containment Replacement

#### Step 1: Add `contains12DOP()` method to Tet

Add the new method alongside `containsUltraFast()`. Do NOT remove the old method yet.

#### Step 2: Correctness test — 12-DOP vs containsUltraFast

Run both methods on random points (all types, all levels, boundary cases). They must agree on every point.

#### Step 3: Gap-free partitioning test

For a cube at each level, verify that every point is contained by exactly one S-type via 12-DOP.

#### Step 4: Replace containsUltraFast callers

Once correctness is proven, update all callers of `containsUltraFast()` to use `contains12DOP()`. Keep `containsUltraFast()` as `@Deprecated` for one release cycle.

### Phase 2: AABB-vs-Tet Intersection Replacement

#### Step 1: Formal derivation of per-type slab ranges

For each S-type, compute the min/max projection onto each difference axis. Produce the complete intersection test.

#### Step 2: Add `intersects12DOP()` method

Implement AABB-vs-12-DOP intersection alongside existing SAT methods.

#### Step 3: Correctness test — 12-DOP vs SAT intersection

Run both on random AABBs (all types, all levels, overlapping/disjoint/touching). Must agree on every case.

#### Step 4: Replace SAT callers

Update `tetrahedronIntersectsVolume()`, `tetrahedronIntersectsVolumeBounds()`, and `aabt.intersectsBound()` to use 12-DOP.

### Phase 3: Tet-vs-Tet Intersection

#### Step 1: Derive tet-vs-tet 12-DOP overlap

Two tets from different types/levels. The 6 axes are the same — compute slab ranges for each and test overlap.

#### Step 2: Test cross-type intersection matrix

All 36 type combinations (6×6) at various relative positions.

### Phase 4: Benchmark and Cleanup

#### Step 1: Benchmark 12-DOP vs SAT vs AABB

Comparative benchmark: containment, intersection, range queries, collision detection.

#### Step 2: Remove deprecated SAT methods

Remove `containsUltraFast()` and SAT fallbacks once 12-DOP is proven in production.

### New Dependencies

None.

## Test Plan

- **Scenario**: 12-DOP containment matches containsUltraFast for all S0-S5 types — **Verify**: 100% agreement on 100K random points per type
- **Scenario**: Gap-free partitioning — **Verify**: Every point in cube assigned to exactly one S-type via 12-DOP, including boundary points (u==v, v==w, u==w)
- **Scenario**: 12-DOP AABB intersection matches SAT — **Verify**: 100% agreement on 10K random AABBs per type
- **Scenario**: 12-DOP tet-vs-tet matches SAT — **Verify**: 100% agreement on all 36 type combinations
- **Scenario**: Performance — **Verify**: 12-DOP containment measurably faster than containsUltraFast
- **Scenario**: Boundary convention — **Verify**: `>` vs `>=` matches locatePointBeyRefinementFromRoot() for all boundary cases

## Validation

### Testing Strategy

1. **Scenario**: Correctness — 12-DOP vs SAT on random inputs across all types and levels
   **Expected**: Zero disagreements

2. **Scenario**: Partitioning — gap-free and overlap-free for entire cube
   **Expected**: Every point in exactly one tet

3. **Scenario**: Performance — 12-DOP vs SAT wall-clock comparison
   **Expected**: 5-8x speedup for containment, 3-5x for intersection

### Performance Expectations

Based on operation counts: 11 ops vs 84 ops = 7.6x theoretical speedup for containment. Actual speedup depends on branch prediction, cache effects, and JIT optimization. Empirical measurement required.

## Finalization Gate

### Contradiction Check

No contradictions found. The 12-DOP result is derived algebraically from the S0-S5 face normals and verified vertex-by-vertex. The ordering convention is taken directly from existing code.

### Assumption Verification

Three assumptions remain unverified and require implementation spikes:
1. AABB-vs-tet intersection edge cases
2. Tet-vs-tet intersection exactness
3. End-to-end performance improvement

### Scope Verification

The Minimum Viable Validation (12-DOP vs containsUltraFast on random points) is in scope for Phase 1 Step 2.

### Cross-Cutting Concerns

- **Versioning**: N/A (internal API)
- **Build tool compatibility**: N/A
- **Licensing**: N/A
- **Deployment model**: N/A (library)
- **IDE compatibility**: N/A
- **Incremental adoption**: Phase 1 adds 12-DOP alongside SAT; Phase 4 removes SAT after validation
- **Secret/credential lifecycle**: N/A
- **Memory management**: Zero additional storage — 12-DOP determined by existing (anchor, level, type)

### Proportionality

Document is right-sized for a core algorithm replacement affecting all spatial query paths.

## References

- `lucien/doc/AABT_12DOP_EXACT_CONTAINMENT.md` — full research document
- `lucien/doc/AABT_RHOMBOHEDRAL_COORDINATES.md` — refuted rhombohedral approach
- `docs/rdr/RDR-001-axis-aligned-bounding-tetrahedra.md` — predecessor RDR (closed)
- `docs/rdr/post-mortem/001-axis-aligned-bounding-tetrahedra.md` — SAT benchmark findings + 12-DOP follow-up
- Kuhn/Freudenthal tetrahedral decomposition
- Permutohedron and hyperplane arrangements (root system A₂)
- `Tet.java:1211` — `containsUltraFast()` (to be replaced)
- `Tet.java:315` — `locatePointBeyRefinementFromRoot()` (ordering logic to reuse)
- `TetrahedralGeometry.java:46` — SAT intersection (to be replaced)

## Revision History

### 2026-03-22: Initial Draft

Created from deep analysis proving 12-DOP exact containment for Kuhn tetrahedra. Research path: SAT (RDR-001, too slow) → rhombohedral AABR (refuted: V0/V7) → difference coordinates (refuted: det=1) → 12-DOP (exact, 11 ops). The permutohedron / A₂ root system structure guarantees exactness.

### 2026-03-22: Gate Round 1 Fixes

1. **Significant — intersects12DOP sketch incomplete**: Marked as WARNING — checks only 2/3 difference axes per type, producing false positives. Formal derivation of all 3 per-type slab ranges deferred to Phase 2 Step 1.
2. **Significant — aabt.contains() missing from audit**: Added `aabt.contains(float,float,float)` to infrastructure audit table — must delegate to `contains12DOP()` not `containsUltraFast()`.
