# Axis-Aligned Bounding Tetrahedra via Rhombohedral Coordinates

## Motivation

RDR-001 demonstrated that exact tetrahedral geometry (SAT intersection) is 10-100x slower than AABB comparisons, despite achieving 50-80% candidate reduction. The per-test cost of SAT (~100 ops) swamps the savings from fewer candidates.

This document explores an alternative: work in a **tetrahedral coordinate system** where bounding volumes are "axis-aligned" — meaning they reduce to simple min/max comparisons, exactly like AABBs in orthogonal coordinates.

## The Tetrahedral Coordinate System

Source: [paniq's rhombohedral lattice gist](https://gist.github.com/paniq/3afdb420b5d94bf99e36) (indexed in T3)

### Conversion Formulas

**Cartesian → Tetrahedral** (integer-exact):
```
tx = y + z
ty = z + x
tz = x + y
```

**Tetrahedral → Cartesian** (integer-exact):
```
x = (-tx + ty + tz) / 2
y = ( tx - ty + tz) / 2
z = ( tx + ty - tz) / 2
```

The transform matrix has determinant 2 (non-singular, invertible). For unit-preserving conversions, multiply by `D = sqrt(2)/2`.

### Properties

- Linear bijection — translations and uniform scale preserved
- Integer-exact for grid operations (no floating point needed)
- 12-point vertex neighborhood maps to FCC lattice diagonals
- Rotations require tetrahedral dot/cross products (not preserved by simple rotation matrices)

## Key Insight: "Axis-Aligned" in Tet Coords → Rhombohedron

In orthogonal coordinates, "axis-aligned" means bounded by planes perpendicular to X, Y, Z. The region `minX ≤ x ≤ maxX, minY ≤ y ≤ maxY, minZ ≤ z ≤ maxZ` is a **box** (6 rectangular faces).

In tetrahedral coordinates, the same construction — `minTx ≤ tx ≤ maxTx, minTy ≤ ty ≤ maxTy, minTz ≤ tz ≤ maxTz` — produces a **rhombohedron** in Cartesian space (6 rhombus faces). This is NOT a tetrahedron, but it IS the natural "axis-aligned bounding shape" in this coordinate system.

The rhombohedron is to tetrahedral coordinates what the box is to orthogonal coordinates.

## Operations — All O(1), Same Cost as AABB

### Representation

```
record AABR(float minTx, float minTy, float minTz, float maxTx, float maxTy, float maxTz)
```

6 floats — identical to AABB. ("AABR" = Axis-Aligned Bounding Rhombohedron, or we may continue calling it "AABT" since it's the natural bounding volume in tetrahedral space.)

### Point Containment

Convert point (px, py, pz) to tetrahedral coordinates:
```
tx = py + pz
ty = pz + px
tz = px + py
```

Then test:
```
minTx ≤ tx ≤ maxTx && minTy ≤ ty ≤ maxTy && minTz ≤ tz ≤ maxTz
```

**Cost: 3 additions + 6 comparisons** — comparable to AABB (6 comparisons, no additions needed since point is already in the right coords).

### Intersection Test

Two AABRs intersect iff their intervals overlap on all three tetrahedral axes:
```
!(a.maxTx < b.minTx || a.minTx > b.maxTx ||
  a.maxTy < b.minTy || a.minTy > b.maxTy ||
  a.maxTz < b.minTz || a.minTz > b.maxTz)
```

**Cost: 6 comparisons** — identical to AABB.

### Containment Test (A contains B)

```
a.minTx ≤ b.minTx && b.maxTx ≤ a.maxTx &&
a.minTy ≤ b.minTy && b.maxTy ≤ a.maxTy &&
a.minTz ≤ b.minTz && b.maxTz ≤ a.maxTz
```

**Cost: 6 comparisons** — identical to AABB.

## Tightness: Rhombohedron vs AABB vs Tetrahedron

### Volume Ratios (to be benchmarked empirically)

For a single S0 tetrahedron within its enclosing cube:

| Bounding Volume | Relative Volume | Containment Cost |
|----------------|----------------|-----------------|
| AABB (cube) | 1.0 (baseline) | 6 comparisons |
| Rhombohedron (AABR) | TBD — expected ~0.5-0.7 | 6 comparisons + 3 additions |
| Exact tetrahedron | 1/6 ≈ 0.167 | 4 determinants (~84 ops) |

The rhombohedron should be significantly tighter than the AABB while having essentially the same computational cost. It won't be as tight as the exact tetrahedron, but the 10-100x cost difference makes the rhombohedron the practical sweet spot.

## Relationship to S0-S5 Decomposition

The S0-S5 characteristic tetrahedra are defined by orderings of rescaled coordinates (u, v, w) within a cube cell:

| Type | Ordering |
|------|----------|
| S0 | u ≥ v ≥ w |
| S1 | v > u ≥ w |
| S2 | w ≥ u > v |
| S3 | w ≥ v ≥ u |
| S4 | u ≥ w > v |
| S5 | v > w ≥ u |

Each S-type tetrahedron sits inside its enclosing cube. The rhombohedron (AABR) of a tet in tetrahedral coordinates is tighter than the cube's AABB because the tetrahedral axes are aligned with the tetrahedron's natural geometry.

**The S0 tetrahedron's AABR**: To compute, convert all 4 vertices of an S0 tet to tetrahedral coordinates and take min/max on each axis. This gives a rhombohedron that tightly bounds the tetrahedron — tighter than the AABB but with identical test cost.

## Conversion to/from Current Tet Representation

A `Tet(x, y, z, level, type)` can compute its AABR by:
1. Get the 4 vertices via `coordinates()`
2. Convert each vertex to tetrahedral coordinates: `(vy+vz, vz+vx, vx+vy)`
3. Take min/max across all 4 vertices on each tetrahedral axis

This is a one-time O(1) computation (4 vertices × 3 additions + 3 min/max passes).

## Status: REFUTED

The paniq FCC transform produces identical AABR bounds for all 6 S-types within a cube — zero tightening over AABB. V0 and V7 (shared by all S-types) dominate min/max on all 3 tet axes. The rhombohedron is actually 4x larger than the AABB in Cartesian volume. See critique findings below.

The FCC transform may still be useful for **neighbor-finding** (ghost-layer construction in the Forest module), but NOT for bounding volumes.

**Next direction**: Type-specific difference coordinates and/or k-DOP. See follow-up analysis.

## Open Questions (moot — hypothesis refuted)

1. **Exact tightness ratio**: What is the volume of the rhombohedron vs the AABB for each S-type? Need to compute with concrete numbers.
2. **Entity bounds**: Can entity bounds (currently AABB) be expressed as AABR for tighter Tetree queries?
3. **Mixed queries**: When the query is a Cartesian AABB and the tree uses AABR, is the intersection test still O(1)?
4. **Integration with existing code**: Can the current `aabt` interface be backed by AABR instead of exact Tet geometry?

## References

- paniq's gist: https://gist.github.com/paniq/3afdb420b5d94bf99e36 (T3: `8aacbffc2832d10e`)
- RDR-001: `docs/rdr/RDR-001-axis-aligned-bounding-tetrahedra.md`
- RDR-001 post-mortem: `docs/rdr/post-mortem/001-axis-aligned-bounding-tetrahedra.md`
- Tet.java: S0-S5 vertex definitions, `coordinates()`, `containsUltraFast()`
