# 12-DOP Slab Ranges for S0-S5 Kuhn Tetrahedra

## Derivation

Each S-type tetrahedron has 4 vertices projected onto the 3 difference axes:
- `d_xy = u - v` (x-y axis)
- `d_xz = u - w` (x-z axis)
- `d_yz = v - w` (y-z axis)

where `u = px - anchor_x`, `v = py - anchor_y`, `w = pz - anchor_z`, and `h = cellSize`.

### Vertex Projections

| Vertex | (u, v, w) | d_xy | d_xz | d_yz |
|--------|-----------|------|------|------|
| V0 | (0, 0, 0) | 0 | 0 | 0 |
| V1 | (h, 0, 0) | h | h | 0 |
| V2 | (0, h, 0) | -h | 0 | h |
| V3 | (h, h, 0) | 0 | h | h |
| V4 | (0, 0, h) | 0 | -h | -h |
| V5 | (h, 0, h) | h | 0 | -h |
| V6 | (0, h, h) | -h | -h | 0 |
| V7 | (h, h, h) | 0 | 0 | 0 |

Note: V0 and V7 project to the origin on all 3 difference axes (shared by all 6 types).

## Complete Slab Range Table

| Type | Vertices | Ordering | d_xy | d_xz | d_yz |
|------|----------|----------|------|------|------|
| **S0** | V0,V1,V3,V7 | x ≥ y ≥ z | **[0, h]** | **[0, h]** | **[0, h]** |
| **S1** | V0,V2,V3,V7 | y ≥ x ≥ z | **[-h, 0]** | **[0, h]** | **[0, h]** |
| **S2** | V0,V4,V5,V7 | z ≥ x ≥ y | **[0, h]** | **[-h, 0]** | **[-h, 0]** |
| **S3** | V0,V4,V6,V7 | z ≥ y ≥ x | **[-h, 0]** | **[-h, 0]** | **[-h, 0]** |
| **S4** | V0,V1,V5,V7 | x ≥ z ≥ y | **[0, h]** | **[0, h]** | **[-h, 0]** |
| **S5** | V0,V2,V6,V7 | y ≥ z ≥ x | **[-h, 0]** | **[-h, 0]** | **[0, h]** |

### Key property

Every slab is either **[0, h]** or **[-h, 0]**. The sign encodes whether the corresponding coordinate difference is non-negative or non-positive in the type's ordering:

- `[0, h]` ↔ first coord ≥ second coord in the ordering
- `[-h, 0]` ↔ first coord ≤ second coord in the ordering

## Sign Encoding

Each type's 3 slab signs can be encoded as 3 bits (0 = non-negative `[0,h]`, 1 = non-positive `[-h,0]`):

| Type | d_xy | d_xz | d_yz | Bits |
|------|------|------|------|------|
| S0 | + | + | + | 000 |
| S1 | - | + | + | 100 |
| S2 | + | - | - | 011 |
| S3 | - | - | - | 111 |
| S4 | + | + | - | 001 |
| S5 | - | - | + | 110 |

Note: S0 and S3 are complementary (all signs flipped). Same for S1↔S2 and S4↔S5.

## AABB-vs-Tet Intersection Test

For an entity AABB `[ex_min, ex_max] × [ey_min, ey_max] × [ez_min, ez_max]`:

### Step 1: Standard AABB overlap (6 comparisons)
```
if (ex_max < anchor_x || ex_min > anchor_x + h) return false;
if (ey_max < anchor_y || ey_min > anchor_y + h) return false;
if (ez_max < anchor_z || ez_min > anchor_z + h) return false;
```

### Step 2: Compute entity projections onto difference axes (6 subtractions)
```
float dxy_min = ex_min - ey_max;  float dxy_max = ex_max - ey_min;
float dxz_min = ex_min - ez_max;  float dxz_max = ex_max - ez_min;
float dyz_min = ey_min - ez_max;  float dyz_max = ey_max - ez_min;
```

### Step 3: Check slab overlap (6 comparisons)

For each difference axis, check overlap with the type's slab:

- If slab is `[0, h]`: overlap iff `dxx_max >= 0 && dxx_min <= h`
- If slab is `[-h, 0]`: overlap iff `dxx_max >= -h && dxx_min <= 0`

Total cost: **6 + 6 + 6 = 18 ops** (not 21 as estimated in the RDR — the slabs being [0,h] or [-h,0] simplifies the bounds).

### Branchless encoding

Since each slab is either `[0, h]` or `[-h, 0]`, define `lo = sign ? -h : 0` and `hi = sign ? 0 : h`:

```java
// Per-type sign table (could be a lookup or switch)
// sign[axis] = 0 means [0,h], sign[axis] = 1 means [-h,0]
int lo_xy = sign_xy * (-h);  int hi_xy = (1 - sign_xy) * h;
// Check: dxy_max >= lo_xy && dxy_min <= hi_xy
```

Or more simply, since `lo = -sign*h` and `hi = (1-sign)*h`:
```java
return dxy_max >= -sign_xy * h && dxy_min <= (1 - sign_xy) * h
    && dxz_max >= -sign_xz * h && dxz_min <= (1 - sign_xz) * h
    && dyz_max >= -sign_yz * h && dyz_min <= (1 - sign_yz) * h;
```

## Redundancy Note

For **point containment**, the 3rd difference axis is always redundant because `d_xz = d_xy + d_yz`. But for **AABB intersection**, the axes are NOT redundant because the AABB projects independently onto each axis (the min/max of the sum ≠ sum of the min/max). All 3 axes must be checked to avoid false positives.

---

## Tet-vs-Tet 12-DOP Intersection

### Global Slab Derivation

For a tet with anchor `(ax, ay, az)`, cell size `h`, and S-type `t`, the **global** slab on each difference axis is:

| Sign | Local slab | Global slab |
|------|-----------|-------------|
| + (d_xy, d_xz, or d_yz) | [0, h] | [anchor_diff, anchor_diff + h] |
| − (d_xy, d_xz, or d_yz) | [−h, 0] | [anchor_diff − h, anchor_diff] |

where `anchor_diff` = the corresponding anchor coordinate difference (e.g. `ax - ay` for d_xy).

Compactly, for sign `s` (0 = +, 1 = −):

```
lo = anchor_diff - s * h
hi = anchor_diff + (1 - s) * h
```

Two slabs `[lo1, hi1]` and `[lo2, hi2]` overlap under closed convention (≥) iff:
```
lo1 ≤ hi2  &&  lo2 ≤ hi1
```

### Same-Cube Same-Level Analysis

When two tets share the same anchor and cell size (`h1 = h2 = h`), anchor differences cancel. The overlap condition on each axis simplifies to comparing the local slabs:

| Type pair | Shared signs? | d_xy overlap | d_xz overlap | d_yz overlap | Verdict |
|-----------|--------------|-------------|-------------|-------------|---------|
| S0 vs S1  | d_xz=+, d_yz=+ same; d_xy differ | [0,h]∩[−h,0]={0} | full | full | touch at face u=v |
| S0 vs S2  | d_xy=+ same; d_xz,d_yz differ | full | [0,h]∩[−h,0]={0} | [0,h]∩[−h,0]={0} | touch at edge u=v=w? — only if both zero simultaneously → vertex V0/V7 |
| S0 vs S3  | all signs differ | {0} | {0} | {0} | touch only at V0 or V7 (vertex contact) |
| S0 vs S4  | d_xy=+, d_xz=+ same; d_yz differ | full | full | [0,h]∩[−h,0]={0} | touch at face u=w |
| S0 vs S5  | d_xz differs, d_xy=+, d_yz differ | full | {0} | {0} | vertex contact |
| S1 vs S2  | d_xz,d_yz signs complement of S0's | {0} | full | full | face contact (u=v) |
| ... (all other pairs follow by complementarity) | | | | | |

**Key insight**: Because every local slab is either `[0,h]` or `[-h,0]`, two same-cube same-level slabs **always overlap** under the closed (≥) convention: same-sign slabs overlap fully, opposite-sign slabs touch at 0. Combined with the AABB (trivially true for same-cube tets), every pair of S-types within the same cube intersects — at least at a shared face, edge, or vertex. This is correct: the 6 Kuhn tetrahedra tile the unit cube without gaps, meeting at shared faces.

### Algorithm

```java
public boolean intersectsTet12DOP(Tet other) {
    final int h1 = 1 << (MAX_LEVEL - l);
    final int h2 = 1 << (MAX_LEVEL - other.l);
    // AABB check (6 comparisons)
    if (x + h1 < other.x || other.x + h2 < x) return false;
    if (y + h1 < other.y || other.y + h2 < y) return false;
    if (z + h1 < other.z || other.z + h2 < z) return false;
    // Global slab bounds per axis (via slabBounds12DOP helper)
    int[] s1 = slabBounds12DOP(x, y, z, l, type);      // [lo_xy,hi_xy, lo_xz,hi_xz, lo_yz,hi_yz]
    int[] s2 = slabBounds12DOP(other.x, other.y, other.z, other.l, other.type);
    // Slab overlap on each difference axis
    if (s1[0] > s2[1] || s2[0] > s1[1]) return false;  // d_xy
    if (s1[2] > s2[3] || s2[2] > s1[3]) return false;  // d_xz
    if (s1[4] > s2[5] || s2[4] > s1[5]) return false;  // d_yz
    return true;
}
```

**Cost**: 6 AABB comparisons + 6 subtractions (in helper) + 6 slab comparisons = **18 operations** (same as AABB-vs-tet).

### Correctness Properties

- **Closed convention**: Face-touching tets (slab boundary at 0) return `true`. Consistent with `contains12DOP` and `containsUltraFast`.
- **Symmetry**: `A.intersectsTet12DOP(B) == B.intersectsTet12DOP(A)` by construction (overlap is symmetric).
- **Same-cube coverage**: All 36 pairs (6×6 including self) return `true` within a cube — consistent with the gap-free tiling of the cube.
- **AABB dominates for distant tets**: The AABB check provides fast rejection; the slab check refines for nearby tets with potential separation on a difference axis.