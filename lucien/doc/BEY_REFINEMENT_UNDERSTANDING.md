# Understanding Bey Refinement: The Critical Insight

## Executive Summary

**CRITICAL INSIGHT**: Bey refinement is NOT about subdividing a tetrahedron into 8 smaller tetrahedra at cube positions. It's about choosing which axis of the octahedron to use when subdividing the tetrahedron. All 8 children must be geometrically INSIDE the parent tetrahedron.

**UPDATE (July 2025)**: The `child()` method serves a different purpose - grid-based navigation for the SFC. 
For true geometric subdivision, see `GEOMETRIC_SUBDIVISION_COMPREHENSIVE_PLAN.md` and 
`GEOMETRIC_TETRAHEDRAL_SUBDIVISION_DESIGN.md` which describe the separate `geometricSubdivide()` method.

## The Fundamental Misunderstanding

### What We Were Doing Wrong

The current implementation (`Tet.child()`) incorrectly:
1. Takes the parent's anchor point (in cube grid coordinates)
2. Computes a vertex of the parent's **cube** (not tetrahedron)
3. Places child at midpoint between anchor and cube vertex
4. Results in children OUTSIDE the parent tetrahedron

### What Bey Refinement Actually Is

Bey refinement is a specific tetrahedral subdivision algorithm where:

1. **Edge Midpoints**: Place new vertices at the midpoints of the tetrahedron's 6 edges
2. **Octahedron Formation**: These 6 edge midpoints form an octahedron inside the parent
3. **Axis Selection**: Choose one of three axes to split the octahedron
4. **8 Children Total**:
   - 4 corner tetrahedra (one at each original vertex)
   - 4 tetrahedra from the split octahedron
5. **All Inside Parent**: Every child tetrahedron is geometrically contained within the parent

## The Geometric Construction

### Step 1: Identify Parent Tetrahedron Vertices

Given a parent tetrahedron with vertices V0, V1, V2, V3:
- These are the ACTUAL tetrahedron vertices
- NOT the cube vertices
- Must use `tet.coordinates()` to get actual vertices

### Step 2: Compute Edge Midpoints

The 6 edges of a tetrahedron are:
- Edge 0-1: M01 = (V0 + V1) / 2
- Edge 0-2: M02 = (V0 + V2) / 2  
- Edge 0-3: M03 = (V0 + V3) / 2
- Edge 1-2: M12 = (V1 + V2) / 2
- Edge 1-3: M13 = (V1 + V3) / 2
- Edge 2-3: M23 = (V2 + V3) / 2

### Step 3: Form the Octahedron

The 6 edge midpoints form an octahedron with:
- 8 faces (all triangular)
- 6 vertices (the edge midpoints)
- 12 edges

### Step 4: Choose Splitting Axis

The octahedron can be split along one of three axes:
- This choice is encoded in the Bey refinement tables
- Different parent types may use different axes
- The axis determines how the 4 octahedral children are formed

### Step 5: Create 8 Children

The 8 children are:
1. **4 Corner Tetrahedra**: Each original vertex + 3 adjacent edge midpoints
   - Child 0: V0, M01, M02, M03
   - Child 1: V1, M01, M12, M13
   - Child 2: V2, M02, M12, M23
   - Child 3: V3, M03, M13, M23

2. **4 Octahedral Tetrahedra**: From splitting the central octahedron
   - The exact configuration depends on the chosen axis
   - All use combinations of the 6 edge midpoints

## Why This Matters

### Geometric Correctness
- All children are INSIDE the parent (mandatory for spatial indexing)
- Children tile the parent volume completely (no gaps)
- Minimal overlap between children
- Preserves tetrahedral shape quality

### Spatial Index Integrity
- Point location algorithms assume children are inside parent
- Range queries depend on proper containment
- Collision detection requires accurate bounds
- Ray traversal needs correct geometric relationships

### TM-Index Consistency
- The space-filling curve encoding assumes proper subdivision
- Parent-child relationships must be geometrically consistent
- Level-based indexing requires containment properties

## Current Code Issues

### `Tet.child()` Method (lines 647-675)
```java
// WRONG: Uses cube vertex instead of tetrahedron vertex
Point3i vertexCoords = computeVertexCoordinates(vertex);

// WRONG: Places child between anchor and cube vertex
int childX = (x + vertexCoords.x) >> 1;
```

### `computeVertexCoordinates()` Method (lines 1783-1813)
- Returns cube vertices, not tetrahedron vertices
- Uses type-based offset calculation for cube geometry
- Completely inappropriate for tetrahedral subdivision

## The Correct Algorithm

```java
public Tet child(int childIndex) {
    // 1. Get actual tetrahedron vertices
    Point3i[] parentVertices = this.coordinates();
    
    // 2. Compute all 6 edge midpoints
    Point3i[] edgeMidpoints = computeEdgeMidpoints(parentVertices);
    
    // 3. Determine which vertices form this child
    // (using Bey refinement tables and octahedron splitting)
    Point3i[] childVertices = getChildVertices(childIndex, parentVertices, edgeMidpoints);
    
    // 4. Compute child anchor (minimum coordinates)
    Point3i childAnchor = computeAnchor(childVertices);
    
    // 5. Get child type from connectivity tables
    byte childType = TetreeConnectivity.getChildType(type, childIndex);
    
    return new Tet(childAnchor.x, childAnchor.y, childAnchor.z, 
                   (byte)(l + 1), childType);
}
```

## Critical Implementation Notes

1. **Never use cube coordinates** for tetrahedral operations
2. **Always work with actual tetrahedron vertices**
3. **Edge midpoints are geometric, not grid-based**
4. **All children must be inside parent bounds**
5. **The octahedron splitting is key to Bey refinement**

## References

- Bey, J. (1995). "Tetrahedral grid refinement"
- t8code implementation (German Aerospace Center)
- Original Bey refinement paper describing octahedral splitting

---

*Document created: July 1, 2025*  
*Status: CRITICAL UNDERSTANDING DOCUMENTED*