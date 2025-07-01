# Octahedron Splitting Rules Analysis

## Overview

This document analyzes how the connectivity tables encode the octahedron splitting in Bey refinement, based on the TetreeConnectivity class.

## Key Data Structures

### 1. CHILD_VERTEX_PARENT_VERTEX Table

This table maps child vertices to parent reference points:

```java
public static final byte[][] CHILD_VERTEX_PARENT_VERTEX = {
    // Child 0 (interior octahedron)
    { 4, 5, 6, 10 },   // Vertices at edge midpoints and center
    // Child 1 (corner at vertex 0)
    { 0, 4, 5, 10 },   // Vertex 0 of parent, plus edge midpoints
    // Child 2 (corner at vertex 1)
    { 4, 1, 7, 10 },   // Vertex 1 of parent, plus edge midpoints
    // Child 3 (corner at vertex 2)
    { 5, 7, 2, 10 },   // Vertex 2 of parent, plus edge midpoints
    // Child 4 (corner at vertex 3)
    { 6, 8, 9, 3 },    // Vertex 3 of parent, plus edge midpoints
    // Child 5
    { 10, 5, 6, 9 },   // Mixed corners and center
    // Child 6
    { 4, 10, 8, 7 },   // Mixed corners and center
    // Child 7
    { 10, 9, 8, 7 }    // Edge midpoints and center
};
```

### Parent Reference Points

The numbers refer to:
- 0-3: Parent vertices (V0, V1, V2, V3)
- 4-9: Edge midpoints
- 10: Some kind of center point

### Edge Numbering (Inferred)

Based on the patterns, the edge midpoints appear to be:
- 4: Edge 0-1 midpoint
- 5: Edge 0-2 midpoint  
- 6: Edge 0-3 midpoint
- 7: Edge 1-2 midpoint
- 8: Edge 1-3 midpoint
- 9: Edge 2-3 midpoint

## Child Classification

### Corner Children (1-4)

These are anchored at parent vertices:
- Child 1: At parent vertex 0 (includes V0, M01, M02, center)
- Child 2: At parent vertex 1 (includes V1, M01, M12, center)
- Child 3: At parent vertex 2 (includes V2, M02, M12, center)
- Child 4: At parent vertex 3 (includes V3, M03, M13, M23)

### Octahedral Children (0, 5-7)

These form from splitting the central octahedron:
- Child 0: Interior octahedron (M01, M02, M03, center)
- Child 5: (center, M02, M03, M23)
- Child 6: (M01, center, M13, M12)
- Child 7: (center, M23, M13, M12)

## The "Center" Point (10)

The reference point 10 appears to be a center point, but its exact definition needs clarification. It could be:
1. The centroid of the parent tetrahedron
2. A specific point related to the octahedron splitting
3. The centroid of the octahedron formed by edge midpoints

## Octahedron Splitting Pattern

Based on the child definitions, the octahedron appears to be split as follows:

1. The 6 edge midpoints form an octahedron
2. Point 10 (center) is used to split this octahedron
3. The splitting creates 4 tetrahedra from the octahedron

### Splitting Analysis

Looking at children 0, 5, 6, 7:
- They all include the center point (10)
- They partition the edge midpoints among them
- This suggests the octahedron is split by connecting opposite edges through the center

## INDEX_TO_BEY_NUMBER Mapping

This table maps child indices to Bey numbers, which varies by parent type:

```java
public static final byte[][] INDEX_TO_BEY_NUMBER = {
    // Parent type 0
    { 0, 1, 4, 5, 2, 7, 6, 3 },
    // Parent type 1
    { 0, 1, 5, 4, 7, 2, 6, 3 },
    // ... etc
};
```

Different parent types use different mappings, which suggests:
1. The octahedron splitting axis varies by parent type
2. Child ordering is adjusted to maintain consistency

## BEY_ID_TO_VERTEX Mapping

```java
public static final byte[] BEY_ID_TO_VERTEX = { 0, 1, 2, 3, 1, 1, 2, 2 };
```

This maps Bey child IDs to vertices:
- Bey IDs 0-3: Map to vertices 0-3 (corner children)
- Bey IDs 4-7: Map to vertices 1,1,2,2 (octahedral children)

## Implications for Geometric Subdivision

### Required Computations

1. **Edge Midpoints**: Compute all 6 edge midpoints geometrically
2. **Center Point**: Determine what point 10 represents
3. **Child Assembly**: Use CHILD_VERTEX_PARENT_VERTEX to assemble each child's vertices

### Parent Type Dependency

The INDEX_TO_BEY_NUMBER table shows that child ordering depends on parent type. This affects:
- Which child gets which Bey ID
- How the octahedron is split
- The final arrangement of children

### Next Steps

1. Determine the exact definition of reference point 10
2. Implement edge midpoint calculation
3. Create vertex assembly based on the connectivity tables
4. Test with known examples to verify correctness

---

*Analysis completed: July 1, 2025*
*Status: CONNECTIVITY TABLES DECODED*