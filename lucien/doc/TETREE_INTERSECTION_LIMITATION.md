# Tetree Volume Intersection Limitation

## Issue

The current `tetrahedronIntersectsVolumeBounds` implementation in `Tet.java` has a limitation where it can miss intersections in certain cases.

## Current Algorithm

The implementation checks:
1. Bounding box overlap (AABB test)
2. If any tetrahedron vertex is inside the volume bounds
3. If any volume corner is inside the tetrahedron

## Missing Cases

The algorithm misses cases where:
- The volume and tetrahedron intersect along edges or faces
- No vertices of either shape are inside the other
- Example: A small cube passing through a large tetrahedron face

## Example

Consider:
- Tetrahedron vertices: (0,0,0), (64,0,0), (64,0,64), (0,64,64)
- Query bounds: (49.9, 49.9, 49.9) to (50.1, 50.1, 50.1)

The bounds are entirely within the tetrahedron's bounding box and do intersect the tetrahedron volume, but:
- No tetrahedron vertices are inside the tiny bounds
- No bound corners are necessarily inside the tetrahedron (depends on exact tetrahedron shape)

## Full Solution

A complete implementation would need:
1. Edge-edge intersection tests (12 tetrahedron edges × 12 box edges)
2. Face-edge intersection tests
3. Separating Axis Theorem (SAT) for convex polyhedra

## Workaround

For spatial indexing purposes, we could:
1. Use the AABB test as a conservative approximation (may return false positives)
2. Rely on the post-filtering in `entitiesInRegion` to remove entities outside the exact bounds
3. Use larger query regions when precision is critical

## Implementation Note

The comment in the code acknowledges this: "More sophisticated intersection tests could be added here"

This is a known limitation that trades accuracy for performance. For most spatial indexing use cases, the current implementation with post-filtering provides adequate results.