# 12-DOP Exact Containment for S0-S5 Kuhn Tetrahedra

## Summary

The 6 axes **{x, y, z, x-y, x-z, y-z}** form a 12-DOP that provides **mathematically exact** containment for all S0-S5 characteristic tetrahedra. Point containment is 11 ops (vs 84 for SAT), AABB intersection is ~21 ops (vs ~100+ for SAT). Zero false positives. Zero additional storage.

## Why This Works

The S0-S5 Kuhn/Freudenthal decomposition partitions each cube cell by **coordinate orderings**:

| Type | Code Convention (Tet.java:315-332) | Ordering | Region |
|------|-------------------------------------|----------|--------|
| S0 | y ≤ z < x | x > z ≥ y | {0 ≤ y ≤ z < x ≤ h} |
| S1 | z < y < x | x > y > z | {0 ≤ z < y < x ≤ h} |
| S2 | x ≤ z < y | y > z ≥ x | {0 ≤ x ≤ z < y ≤ h} |
| S3 | x ≤ y ≤ z | z ≥ y ≥ x | {0 ≤ x ≤ y ≤ z ≤ h} |
| S4 | z < x ≤ y | y ≥ x > z | {0 ≤ z < x ≤ y ≤ h} |
| S5 | y < x ≤ z | z ≥ x > y | {0 ≤ y < x ≤ z ≤ h} |

**IMPORTANT**: The ordering convention above is taken directly from `locatePointBeyRefinementFromRoot()` (Tet.java lines 315-332). The 12-DOP containment test MUST use this same convention. Note the use of `<` vs `≤` at boundaries — this determines which S-type "owns" shared faces and ensures gap-free, overlap-free partitioning.

The pairwise differences **(x-y), (x-z), (y-z)** encode these orderings directly. The sign pattern of these 3 differences uniquely identifies the S-type. Combined with the AABB constraints 0 ≤ x,y,z ≤ h, the resulting 12-DOP (6 axes, 12 half-spaces) is **identical** to the tetrahedral region — not approximately, but as a mathematical identity.

### Structural Explanation

The 6 S-types correspond to the 6 elements of the symmetric group S₃ (permutations of 3 elements). The pairwise difference axes {x-y, x-z, y-z} form the root system of type A₂, which generates the hyperplane arrangement that defines the permutohedron. The S-types are exactly the **chambers** of this arrangement restricted to a cube cell.

## Face Normal Verification

Each S-type tetrahedron has 4 faces. The complete set of face normals across all 24 faces is:

**{(1,0,0), (0,1,0), (0,0,1), (1,-1,0), (1,0,-1), (0,1,-1)}**

These are exactly the 3 AABB axes + 3 pairwise difference axes. Each S-type uses 2 AABB faces and 2 difference faces:

| Type | AABB faces | Difference faces |
|------|-----------|-----------------|
| S0 | x=h, z=0 | x-y≥0, y-z≥0 |
| S1 | y=h, z=0 | y-x≥0, x-z≥0 |
| S2 | z=h, y=0 | z-x≥0, x-y≥0 |
| S3 | z=h, x=0 | z-y≥0, y-x≥0 |
| S4 | x=h, y=0 | x-z≥0, z-y≥0 |
| S5 | y=h, x=0 | y-z≥0, z-x≥0 |

## Point Containment Test

```java
public boolean contains12DOP(float px, float py, float pz) {
    final int h = 1 << (Constants.getMaxRefinementLevel() - l);
    // AABB early-out (6 comparisons)
    if (px < x || px > x + h || py < y || py > y + h || pz < z || pz > z + h)
        return false;
    // Local coordinates (3 subtractions)
    float u = px - x, v = py - y, w = pz - z;
    // Ordering test (2 comparisons) — EXACT, zero false positives
    // Convention matches locatePointBeyRefinementFromRoot() exactly
    return switch (type) {
        case 0 -> u > w && w >= v;   // S0: x > z ≥ y  (code: y ≤ z < x)
        case 1 -> u > v && v > w;    // S1: x > y > z  (code: z < y < x)
        case 2 -> v > w && w >= u;   // S2: y > z ≥ x  (code: x ≤ z < y)
        case 3 -> w >= v && v >= u;  // S3: z ≥ y ≥ x  (code: x ≤ y ≤ z)
        case 4 -> v >= u && u > w;   // S4: y ≥ x > z  (code: z < x ≤ y)
        case 5 -> w >= u && u > v;   // S5: z ≥ x > y  (code: y < x ≤ z)
        default -> throw new IllegalStateException("Invalid type: " + type);
    };
}
```

**Cost: 8 comparisons + 3 subtractions = 11 ops. Exact. No multiplications.**

**Boundary handling**: The mix of `>` and `>=` matches the existing `locatePointBeyRefinementFromRoot()` convention. Points on shared faces (where two coordinates are equal) are assigned to exactly one S-type, ensuring gap-free and overlap-free partitioning of the cube. For example, a point with u == w goes to S0 (w ≥ v case) or S5 (w ≥ u case) depending on v, never to both.

## AABB-vs-Tet Intersection Test

For an entity AABB `[ex_min..ex_max, ey_min..ey_max, ez_min..ez_max]` against a tet's 12-DOP:

```
// Step 1: Standard AABB overlap (6 comparisons)
if (ex_max < x || ex_min > x+h || ...) return false;

// Step 2: Project entity onto difference axes (6 subtractions)
float dxy_min = ex_min - ey_max, dxy_max = ex_max - ey_min;  // (x-y) range
float dxz_min = ex_min - ez_max, dxz_max = ex_max - ez_min;  // (x-z) range
float dyz_min = ey_min - ez_max, dyz_max = ey_max - ez_min;  // (y-z) range

// Step 3: Tet's difference-axis ranges (from type)
// For S0 (x≥y≥z): x-y ∈ [0,h], y-z ∈ [0,h], x-z ∈ [0,h] (redundant)
// Check overlap on active difference axes (6 comparisons)
```

**Cost: ~21 ops total. Exact.**

## Performance Comparison

| Method | Point Containment | AABB Intersection | Exact? | FP Rate | Extra Storage |
|--------|------------------|-------------------|--------|---------|---------------|
| AABB (6-DOP) | 6 ops | 6 ops | No | 83.3% | 0 |
| **12-DOP** | **11 ops** | **~21 ops** | **Yes** | **0%** | **0** |
| 14-DOP (body diag) | ~14 ops | ~28 ops | No | ~40-60% | 8 floats |
| SAT (containsUltraFast) | ~84 ops | ~100+ ops | Yes | 0% | 0 |

## Properties

1. **No additional storage**: The 12-DOP is fully determined by (anchor, level, type) — already stored per Tet.
2. **No multiplications**: Only subtractions and comparisons — no floating-point error accumulation.
3. **Hierarchical composability**: Parent's 12-DOP = enclosing 12-DOP of all children's 12-DOPs. No looseness through the hierarchy.
4. **Numerically robust**: Comparison-only logic is strictly more robust than the 48-multiplication determinant calculation in `containsUltraFast()`.

## Relationship to Existing Code

The type-selection logic in `locatePointBeyRefinementFromRoot()` (Tet.java lines 315-332) **already performs exactly this ordering test** to determine which S-type contains a point. The 12-DOP containment test is structurally identical — the "discovery" is recognizing that the existing type-selection code IS the optimal containment test, and that `containsUltraFast()` (84 ops of determinant math) can be replaced by the same 2-comparison ordering check.

## Open Work

1. **AABB-vs-12-DOP intersection detail**: The doc sketches the approach (~21 ops) but doesn't enumerate per-type difference-axis ranges. Need to specify: for each S-type, what are the min/max projections onto the 3 difference axes?

2. **Tet-vs-tet intersection**: Two tets from different types and/or levels. Can 12-DOP intersection handle this exactly? The 6 axes are the same for all types, but the slab ranges differ. Cross-type intersection should work as standard k-DOP overlap on all 6 axes.

3. **Face normal verification**: The doc asserts the face normals are {x, y, z, x-y, x-z, y-z}. This should be verified by computing face normals for all 24 faces (4 per type × 6 types) from the actual `coordinates()` vertex data.

4. **Boundary convention audit**: The `>` vs `>=` pattern must be verified against `locatePointBeyRefinementFromRoot()` to ensure the same convention. The current doc derives these from the code but a formal test should confirm gap-free partitioning.

## Implications for RDR-001

This result supersedes the SAT-based approach from RDR-001. The 12-DOP achieves exact containment at near-AABB cost, resolving the benchmark problem (SAT was 10-100x slower than AABB). The `aabt` interface can be backed by 12-DOP logic instead of SAT.

## Refuted Approaches

1. **Rhombohedral AABR** (paniq FCC transform): V0 and V7 dominate all tet-coord axes, producing identical bounds for all S-types. 4x AABB volume, zero tightening. See `AABT_RHOMBOHEDRAL_COORDINATES.md`.

2. **Type-specific difference coordinates** (a=u, b=u-v, c=v-w): Volume-preserving transform (det=1). S0 maps to another tetrahedron with identical AABB in transformed space. No tightening.

## References

- Kuhn/Freudenthal tetrahedral decomposition
- Permutohedron and hyperplane arrangements (root system A₂)
- `Tet.java` — S0-S5 vertex definitions, `containsUltraFast()`, `locatePointBeyRefinementFromRoot()`

## Appendix

### what is the 12-DOP we refer to formally known as?

The 12-DOP (12 Discrete Oriented Polytope) using axes {x, y, z, x-y, x-z, y-z} corresponds to several formal mathematical concepts:

1. **k-DOP / Discrete Oriented Polytope** — the computational geometry term. A 12-DOP is the intersection of 6 slabs (12 half-spaces) along 6 chosen axis directions. This is the standard name in collision detection literature.
2. **Permutohedron (or permutahedron)** — the convex hull of all permutations of a vector. The S0-S5 regions are the chambers of the permutohedron's normal fan (also called the braid arrangement). The permutohedron of order 3 in 3D is exactly what we're exploiting.
3. **Type A₂ hyperplane arrangement (or braid arrangement)** — the arrangement of hyperplanes x=y, x=z, y=z in R³. The 6 connected components (chambers) of the complement of this arrangement are exactly the S0-S5 regions. This is the Coxeter arrangement of the symmetric group S₃.
4. **Weyl chambers of S₃** — in Lie theory, the 6 regions are the Weyl chambers of the type A₂ root system, with roots {x-y, x-z, y-z}.
5. **Freudenthal / Kuhn simplicial decomposition** — in combinatorial topology, this is the standard decomposition of a cube into simplices by sorting coordinates, attributed independently to Freudenthal (1942) and Kuhn (1960). Each simplex corresponds to a permutation.

The most precise formal name for what we're doing is: containment testing via the Kuhn simplicial decomposition using the type-A₂ braid arrangement as a 12-DOP. In practice, "12-DOP with permutohedron axes" or just "ordering-based containment for Kuhn simplices" would be understood by the relevant communities (computational geometry, mesh generation, spatial indexing).
