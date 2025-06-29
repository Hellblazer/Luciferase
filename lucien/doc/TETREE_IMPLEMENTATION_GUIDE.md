# Tetree Implementation Guide

## Overview

This document provides comprehensive documentation of the Tetree (tetrahedral tree) implementation in Luciferase. The
Tetree is a spatial data structure based on tetrahedral decomposition of 3D space, implementing algorithms from t8code
and the paper "A tetrahedral space-filling curve for non-conforming adaptive meshes".

## Current Implementation Status (June 2025)

### Core Functionality Complete

- **Tetrahedral Space-Filling Curve (SFC)**: Full implementation matching t8code
- **Bey's Refinement Scheme**: 8 children per tetrahedron using vertex midpoints
- **Parent-Child Navigation**: O(1) operations using connectivity tables
- **Multi-Entity Support**: Multiple entities per tetrahedral node
- **Thread-Safe Operations**: Read-write locks for concurrent access

### Key Architectural Decisions

1. **Unified Architecture**: Both Octree and Tetree extend `AbstractSpatialIndex`
2. **SFC Index Encoding**: Direct encoding without level offsets (0 to 8^level - 1)
3. **Vertex-Based Child Generation**: Using t8code's midpoint algorithm
4. **Set-Based Node Storage**: `TetreeNodeImpl` uses HashSet for entity IDs

## Critical Implementation Details

### 1. Tetrahedral Space-Filling Curve (Tet SFC)

```java
// The Tet SFC index directly encodes the path from root
// Level 0: index = 0 (root)
// Level 1: indices 1-7 (8 children)
// Level 2: indices 8-63 (64 children)
// Level 3: indices 64-511 (512 children)

// IMPORTANT: Unlike Morton codes, NO level offset is used
public static byte tetLevelFromIndex(long index) {
    if (index == 0)
        return 0; // Root
    int highBit = 63 - Long.numberOfLeadingZeros(index);
    return (byte) ((highBit / 3) + 1);
}
```

### 2. Child Generation Algorithm (Bey's Refinement)

```java
// CRITICAL: Children are generated using vertex midpoints, NOT cube offsets
public Tet child(int childIndex) {
    // Get Bey child ID from Morton index
    byte beyChildId = TetreeConnectivity.getBeyChildId(type, childIndex);

    // Child 0 (interior) uses parent anchor directly
    if (beyChildId == 0) {
        return new Tet(x, y, z, childLevel, childType);
    }

    // Other children: midpoint between parent anchor and vertex
    byte vertex = TetreeConnectivity.getBeyVertex(beyChildId);
    Point3i vertexCoords = computeVertexCoordinates(vertex);

    // Child anchor = (parent anchor + parent vertex) / 2
    int childX = (x + vertexCoords.x) >> 1;
    int childY = (y + vertexCoords.y) >> 1;
    int childZ = (z + vertexCoords.z) >> 1;

    return new Tet(childX, childY, childZ, childLevel, childType);
}
```

### 3. Connectivity Tables

The implementation uses several lookup tables from t8code:

- **INDEX_TO_BEY_NUMBER**: Maps Morton index (0-7) to Bey child ID
- **BEY_ID_TO_VERTEX**: Maps Bey child ID to defining vertex
- **TYPE_TO_TYPE_OF_CHILD**: Child type based on parent type and Bey ID
- **PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID**: Many-to-one mappings (expected!)

### 4. Coordinate System Constraints

```java
// CRITICAL: All entity coordinates MUST be positive
// The tetrahedral SFC only works within the positive octant
// Ray origins can be negative, but entities cannot

private void validatePositiveCoordinates(Point3f point) {
    if (point.x < 0 || point.y < 0 || point.z < 0) {
        throw new IllegalArgumentException(
            "Tetree requires positive coordinates. Got: " + point);
    }
}
```

### 5. Tetrahedral vs Cubic Geometry

```java
// NEVER confuse these two calculations:

// CUBE CENTER (for Octree):
float centerX = origin.x + cellSize / 2.0f;

// TETRAHEDRON CENTROID (for Tetree):
Point3f centroid = new Point3f(
    (v0.x + v1.x + v2.x + v3.x) / 4.0f,
    (v0.y + v1.y + v2.y + v3.y) / 4.0f,
    (v0.z + v1.z + v2.z + v3.z) / 4.0f
);
```

## Common Pitfalls and Solutions

### 1. Index Range Confusion

**Problem**: Assuming Tet SFC uses level offsets like Morton codes
**Solution**: Use raw indices: 0 to (8^level - 1)

### 2. Child Position Calculation

**Problem**: Using cube-based offsets instead of vertex midpoints
**Solution**: Always use the t8code vertex-based algorithm

### 3. Many-to-One Mappings

**Problem**: Expecting bijective mappings in connectivity tables
**Solution**: Multiple children can share cube IDs - this is correct!

### 4. Coordinate Sign Issues

**Problem**: Negative entity coordinates
**Solution**: Transform to positive space or use Octree instead

## Testing and Validation

### Key Test Classes

- `TetreeValidatorTest`: Validates tetrahedral constraints
- `TetreeParityTest`: Ensures t8code algorithm parity
- `TetreeConnectivityTest`: Verifies lookup tables
- `SFCRoundTripTest`: Tests index ↔ tetrahedron conversion

### Validation Checklist

1. ✅ All children form valid subdivision families
2. ✅ Parent-child relationships are consistent
3. ✅ SFC indices round-trip correctly
4. ✅ Face neighbors are computed correctly
5. ✅ Tetrahedral containment tests work

## Future Considerations

### Potential Optimizations

1. **Packed Representations**: Use `TetreeBits.packTet()` for memory efficiency
2. **Batch Operations**: Process multiple entities together
3. **Spatial Caching**: Cache frequently accessed tetrahedra

### Known Limitations

1. **Positive Coordinates Only**: Fundamental SFC constraint
2. **Fixed Root Domain**: S0 tetrahedron only
3. **Memory Overhead**: 6 tetrahedra per cubic cell

## Algorithm References

1. **t8code**: https://github.com/DLR-AMR/t8code
2. **Bey's Refinement**: "Simplicial grid refinement: on Freudenthal's algorithm"
3. **Tetrahedral SFC**: "A tetrahedral space-filling curve for non-conforming adaptive meshes"

## Debugging Tips

### When Things Go Wrong

1. **Check coordinate signs**: Use `validatePositiveCoordinates()`
2. **Verify level bounds**: Max level is 21
3. **Inspect connectivity**: Use `TetreeValidator.validateFamily()`
4. **Trace SFC path**: Print intermediate indices during traversal

### Useful Debug Methods

```java
// Print tetrahedron details
System.out.println("Tet: "+tet);
System.out.

println("  Index: "+tet.index());
System.out.

println("  Vertices: "+Arrays.toString(tet.computeVertices()));
System.out.

println("  Centroid: "+tet.centroid());

// Validate subdivision
List<Tet> children = tet.children();
boolean valid = TetreeValidator.isValidSubdivisionFamily(children, tet);
```

## Maintenance Notes

### Code Organization

- `Tet.java`: Core tetrahedron operations (static geometric methods)
- `Tetree.java`: Spatial index implementation (extends AbstractSpatialIndex)
- `TetreeConnectivity.java`: Lookup tables and mappings from t8code
- `TetreeValidator.java`: Validation utilities (uses TetreeFamily for relationship checks)
- `TetreeBits.java`: Bitwise operations for SFC calculations
- `TetreeFamily.java`: Family and sibling relationship operations
- `TetreeIterator.java`: Tree traversal implementations (DFS, BFS, Morton, Level-order)
- `TetreeNeighborFinder.java`: Neighbor finding algorithms (face, edge, vertex)
- `TetreeSFCRayTraversal.java`: Specialized ray traversal using SFC properties
- `TetreeValidationUtils.java`: Centralized positive coordinate validation
- `TetreeHelper.java`: Helper utilities for spatial queries
- `TetrahedralSearchBase.java`: Base class for tetrahedral search operations

### When Modifying

1. **Never change**: Morton curve calculations, connectivity tables
2. **Test thoroughly**: Any changes to child/parent algorithms
3. **Document**: Add comments for non-obvious tetrahedral geometry
4. **Validate**: Run `TetreeValidatorTest` after changes

## Recent Code Cleanup (June 2025)

### Duplicate Code Elimination

The following duplications have been removed:

- **Geometric Methods**: Moved to static methods in `Tet.java` for sharing
- **Validation Methods**: Centralized in `TetreeValidationUtils.java`
- **Family Checks**: Using `TetreeFamily.isFamily()` instead of duplicates
- **Parent-Child Checks**: Using `TetreeFamily.isParentOf()` instead of duplicates

### Remaining Items for Future Enhancement

- `TetreeBits.computeCubeLevel()`: Additional t8code parity for type checking (line 264)
    - Note: Current implementation is functional for all production use cases
    - Full t8code parity would require implementing additional connectivity tables
    - Deferred as low priority given ~90% t8code parity already achieved

## Summary

The Tetree implementation is a sophisticated spatial data structure that correctly implements t8code's tetrahedral
algorithms. The key to understanding it is recognizing that tetrahedra are fundamentally different from cubes - they use
vertex-based refinement, have complex connectivity relationships, and require positive coordinates. When in doubt, refer
to t8code's implementation and this guide.
