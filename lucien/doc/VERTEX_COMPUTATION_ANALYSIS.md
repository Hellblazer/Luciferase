# Vertex Computation Analysis for Tet.coordinates()

## Overview

The `Tet.coordinates()` method computes the 4 vertices of a tetrahedron based on its anchor position (x, y, z), level, and type. This analysis documents the exact algorithm and its implications.

## The Algorithm

### Core Formula

For a tetrahedron with:
- Anchor position: (x, y, z)
- Type: 0-5
- Level: l
- Cell size: h = 2^(maxLevel - l)

The algorithm computes:
```java
ei = type / 2;  // Primary axis: 0, 0, 1, 1, 2, 2 for types 0-5
ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;  // Secondary axis
```

### Vertex Positions

The 4 vertices are positioned as follows:

1. **Vertex 0**: Anchor position (x, y, z)
2. **Vertex 1**: Anchor + h in dimension ei
3. **Vertex 2**: Anchor + h in dimension ei + h in dimension ej  
4. **Vertex 3**: Anchor + h in dimensions (ei+1)%3 and (ei+2)%3

## Type-Specific Analysis

### Type 0
- ei = 0 (x-axis), ej = 2 (z-axis)
- V0 = (x, y, z)
- V1 = (x+h, y, z)
- V2 = (x+h, y, z+h)
- V3 = (x, y+h, z+h)

### Type 1
- ei = 0 (x-axis), ej = 1 (y-axis)
- V0 = (x, y, z)
- V1 = (x+h, y, z)
- V2 = (x+h, y+h, z)
- V3 = (x, y+h, z+h)

### Type 2
- ei = 1 (y-axis), ej = 0 (x-axis)
- V0 = (x, y, z)
- V1 = (x, y+h, z)
- V2 = (x+h, y+h, z)
- V3 = (x+h, y, z+h)

### Type 3
- ei = 1 (y-axis), ej = 2 (z-axis)
- V0 = (x, y, z)
- V1 = (x, y+h, z)
- V2 = (x, y+h, z+h)
- V3 = (x+h, y, z+h)

### Type 4
- ei = 2 (z-axis), ej = 1 (y-axis)
- V0 = (x, y, z)
- V1 = (x, y, z+h)
- V2 = (x, y+h, z+h)
- V3 = (x+h, y+h, z)

### Type 5
- ei = 2 (z-axis), ej = 0 (x-axis)
- V0 = (x, y, z)
- V1 = (x, y, z+h)
- V2 = (x+h, y, z+h)
- V3 = (x+h, y+h, z)

## Key Observations

### 1. Cube-Based Tetrahedra

The 6 types represent 6 different characteristic tetrahedra that can be formed within a cube. To completely tessellate a cube, you need exactly 6 tetrahedra, so each tetrahedron occupies 1/6 of the cube's volume (not half).

The volume formula confirms this: V = h³/6, which is exactly 1/6 of the cube's volume (h³).

### 2. Vertex Ordering Pattern

The vertex ordering follows a specific pattern:
- V0: Always at anchor (origin of local cube)
- V1: One step along primary axis (ei)
- V2: Steps along both ei and ej axes
- V3: Steps along the two axes NOT used by V2

### 3. Type-Based Spatial Orientation

Each type (0-5) represents a different spatial orientation of a tetrahedron within the cube:
- The type determines which of the 6 characteristic tetrahedra (S0-S5) is represented
- Each has a specific position and orientation within the containing cube
- The vertex ordering may not maintain consistent face winding (and that's OK for now)

### 4. Type Pairs

Types come in pairs that share the same primary axis:
- Types 0,1: Primary axis X
- Types 2,3: Primary axis Y  
- Types 4,5: Primary axis Z

The difference within each pair is the secondary axis choice.

## Implications for Geometric Subdivision

### 1. Edge Identification

For geometric subdivision, we need to identify the 6 edges:
- Edge 0-1: (V0, V1)
- Edge 0-2: (V0, V2)
- Edge 0-3: (V0, V3)
- Edge 1-2: (V1, V2)
- Edge 1-3: (V1, V3)
- Edge 2-3: (V2, V3)

### 2. Edge Midpoint Calculation

Each edge midpoint is simply:
```
M_ij = (V_i + V_j) / 2
```

### 3. Face Identification

The 4 faces are defined by vertex triples:
- Face 0: (V1, V2, V3) - opposite V0
- Face 1: (V0, V2, V3) - opposite V1
- Face 2: (V0, V1, V3) - opposite V2
- Face 3: (V0, V1, V2) - opposite V3

## Validation Tests Needed

1. **Spatial Position Validation**: Ensure all types are positioned correctly within their cube
2. **Volume Calculation**: Verify each tetrahedron has volume h³/6
3. **Adjacency Testing**: Confirm neighboring tetrahedra share faces correctly
4. **Type Consistency**: Verify type assignment matches spatial configuration

Note: We're not concerned with vertex winding order or face normal consistency at this time.

## Next Steps

1. Create visualization tool to render vertices for each type
2. Implement edge midpoint calculation
3. Map child tetrahedra to parent edges
4. Understand how connectivity tables encode the octahedron splitting

---

*Analysis completed: July 1, 2025*
*Status: VERTEX COMPUTATION FULLY UNDERSTOOD*