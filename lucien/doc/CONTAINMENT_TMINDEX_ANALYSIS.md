# Containment vs TM-Index Analysis

## Executive Summary

The Tetree implementation uses two different vertex computation methods:
1. **t8code coordinates** (used by `contains()` and `locate()`) - where V3 position depends on tetrahedron type
2. **subdivision coordinates** (used by `subdivisionCoordinates()`) - where V3 = anchor + (h,h,h) for all types

**Key Finding**: We CANNOT change `contains()` to use subdivision coordinates without breaking the fundamental tetrahedral decomposition structure, even though the tm-index encoding itself would remain correct.

## Detailed Analysis

### 1. Vertex Computation Differences

The two coordinate systems differ only in how they compute vertex V3:

```
Type 0: t8code V3 = (x, y+h, z+h)    vs   subdiv V3 = (x+h, y+h, z+h)
Type 1: t8code V3 = (x, y+h, z+h)    vs   subdiv V3 = (x+h, y+h, z+h)
Type 2: t8code V3 = (x+h, y, z+h)    vs   subdiv V3 = (x+h, y+h, z+h)
Type 3: t8code V3 = (x+h, y, z+h)    vs   subdiv V3 = (x+h, y+h, z+h)
Type 4: t8code V3 = (x+h, y+h, z)    vs   subdiv V3 = (x+h, y+h, z+h)
Type 5: t8code V3 = (x+h, y+h, z)    vs   subdiv V3 = (x+h, y+h, z+h)
```

The subdivision V3 is always at the "far corner" of the cube, while t8code V3 depends on the tetrahedron type.

### 2. TM-Index Encoding

The tm-index encodes:
- **Anchor coordinates** (x, y, z)
- **Level** 
- **Type hierarchy** (the type at each level from root to current)

**Critical insight**: The tm-index does NOT encode vertex positions! It only encodes the anchor, level, and type information. This means changing how vertices are computed would not affect the tm-index encoding itself.

### 3. The Fundamental Problem

While the tm-index encoding would remain correct, changing `contains()` would break the spatial decomposition:

1. **Type Assignment Inconsistency**: A point P might be contained in type 3 using t8code vertices but in type 5 using subdivision vertices.

2. **locate() Would Break**: The `locate()` method uses `contains()` to determine which tetrahedron (and thus which type) contains a point. Different vertex computation = different type assignment.

3. **Space-Filling Curve Property Lost**: The tetrahedral SFC relies on consistent type assignment. If the same point can be in different types depending on vertex computation, the SFC property breaks down.

### 4. Why Subdivision Coordinates Exist

The subdivision coordinates with V3 = anchor + (h,h,h) exist specifically for:
- **Geometric subdivision operations** where 100% containment is critical
- **Bey refinement** algorithm compatibility
- **Ensuring children are geometrically contained within parents**

They are NOT meant to replace the t8code coordinates for spatial indexing operations.

### 5. The Correct Solution

The current implementation is correct:
- **Spatial operations** (contains, locate) use t8code coordinates
- **Subdivision operations** use subdivision coordinates
- Each coordinate system serves its specific purpose

## Implications

1. **Cannot "fix" containment by changing to subdivision coordinates** - this would fundamentally break the tetrahedral decomposition

2. **The 62.5% containment issue is inherent** to the t8code tetrahedral decomposition when measuring with subdivision geometry

3. **Both coordinate systems are needed** - they serve different purposes and cannot be unified

## Recommendation

Accept that:
- The t8code coordinate system defines the actual spatial decomposition
- The subdivision coordinate system is only for geometric operations
- The apparent "containment issue" is not a bug but a fundamental property of how t8code decomposes space

The tm-index correctly encodes the tetrahedral hierarchy regardless of which vertex computation is used, but the spatial decomposition itself requires consistent use of t8code coordinates.