# Why T8Code Has Gaps and Overlaps

## The Fundamental Problem

The t8code library attempts to decompose a cube into 6 tetrahedra, but these tetrahedra don't perfectly tessellate (fill) the cube. This creates two issues:

1. **Gaps** (~48% of volume) - Points inside the cube that aren't contained by any tetrahedron
2. **Overlaps** (~32% of volume) - Points contained by multiple tetrahedra

## Why This Happens

### 1. The Six Characteristic Tetrahedra

T8code creates 6 tetrahedra (types 0-5) from a cube's 8 vertices. Each tetrahedron is formed by selecting 4 of the 8 vertices in a specific pattern:

```
Cube vertices:
V0 = (0,0,0)  V1 = (1,0,0)  V2 = (1,1,0)  V3 = (0,1,0)
V4 = (0,0,1)  V5 = (1,0,1)  V6 = (1,1,1)  V7 = (0,1,1)

Type 0: V0-V1-V5-V7  (vertices 0,1,5,7)
Type 1: V0-V1-V2-V7  (vertices 0,1,2,7)
Type 2: V0-V2-V3-V7  (vertices 0,2,3,7)
Type 3: V0-V3-V4-V7  (vertices 0,3,4,7)
Type 4: V0-V4-V5-V7  (vertices 0,4,5,7)
Type 5: V0-V5-V6-V7  (vertices 0,5,6,7)
```

### 2. The Geometric Issue

While each tetrahedron has volume = cube_volume/6 (suggesting they should fill the cube), their specific vertex arrangements create problems:

- **Gaps occur** because certain regions of the cube aren't covered by any of the 6 tetrahedra
- **Overlaps occur** because some regions are covered by multiple tetrahedra

### 3. Visual Example

Consider the point (0.1, 0.2, 0.3) in a unit cube:
- This point is clearly inside the cube bounds
- But it's not contained by ANY of the 6 tetrahedra
- This is a gap in the partition scheme

Consider the point (0.5, 0, 0.5) on an edge:
- This point is contained by BOTH type 3 and type 5 tetrahedra
- This is an overlap in the partition scheme

## Why T8Code Uses This Scheme Anyway

Despite these issues, t8code uses this decomposition because:

1. **Space-Filling Curve Property**: The 6 tetrahedra can be ordered to create a continuous path through space
2. **Hierarchical Refinement**: Each tetrahedron can be subdivided into 8 children using the Bey refinement scheme
3. **Consistent Indexing**: The tm-index encoding works with this specific decomposition

## Impact on Spatial Indexing

The gaps and overlaps cause several issues:

1. **Containment Ambiguity**: Points may not be contained by any tetrahedron (gaps) or by multiple tetrahedra (overlaps)
2. **Visualization Issues**: Entities appear to "float" without visible containers when they fall in gaps
3. **Spatial Queries**: Range queries and nearest neighbor searches must account for these irregularities

## The Subdivision Coordinates Workaround

To ensure geometric containment during subdivision, we use a different vertex computation for subdivision operations:
- T8code coordinates: Used for spatial indexing and type determination
- Subdivision coordinates: Used for geometric subdivision to ensure child containment

This dual system maintains the space-filling curve properties while improving geometric behavior during subdivision.

## Conclusion

The gaps and overlaps are not bugs but fundamental properties of the t8code tetrahedral decomposition. Any system using t8code must accept these limitations as the price for having a hierarchical, space-filling tetrahedral decomposition.