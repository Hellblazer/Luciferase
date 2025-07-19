# Delaunay Tetrahedralization Invariants

This document lists all invariants that must be maintained by any implementation of the 3D Delaunay tetrahedralization algorithm.

## Structural Invariants

### 1. Tetrahedron Structure
- Every tetrahedron has exactly 4 vertices (a, b, c, d)
- Vertices are ordered such that {a, b, c} are positively oriented with respect to d
- No tetrahedron can have duplicate vertices

### 2. Neighbor Connectivity
- Every tetrahedron has 4 neighbor slots (nA, nB, nC, nD)
- Neighbor opposite vertex X is the tetrahedron sharing face opposite X
- **Bidirectional Property**: If T1.neighbor[i] = T2, then ∃j such that T2.neighbor[j] = T1
- Neighbors can be null only at convex hull boundary

### 3. Vertex-Tetrahedron Adjacency
- Every vertex maintains reference to at least one containing tetrahedron
- A vertex's adjacent tetrahedron must actually contain that vertex
- The star of a vertex (all containing tetrahedra) forms a connected set

## Geometric Invariants

### 4. Delaunay Property
- No vertex lies strictly inside the circumsphere of any tetrahedron
- Vertices on the circumsphere are allowed (co-spherical points)

### 5. Convex Hull Property
- The set of all tetrahedra forms a convex hull of all vertices
- No gaps or overlaps in the tetrahedralization

### 6. Orientation Consistency
- For any face shared by two tetrahedra, the face has opposite orientation in each
- Walking around any edge yields consistent tetrahedron ordering

## Algorithmic Invariants

### 7. Locate Correctness
- locate(p) returns null iff p is outside the convex hull
- locate(p) returns a tetrahedron T such that p is inside or on boundary of T
- The walk algorithm terminates (no infinite loops)

### 8. Flip Operation Invariants

#### flip1to4 (Point in Tetrahedron)
- Input: 1 tetrahedron + 1 interior point
- Output: 4 tetrahedra sharing the new vertex
- Volume is preserved: vol(input) = Σ vol(outputs)
- Neighbor relationships with external tetrahedra preserved

#### flip2to3 (Point on Face)
- Input: 2 tetrahedra sharing a face + point on face
- Output: 3 tetrahedra sharing an edge through the new vertex
- External neighbor relationships preserved

#### flip3to2 (Removing Degree-3 Edge)
- Input: 3 tetrahedra around an edge
- Output: 2 tetrahedra sharing a face
- Only valid if edge has exactly 3 incident tetrahedra

### 9. Insertion Invariants
- After inserting vertex v, v appears in at least one tetrahedron
- The convex hull either stays same or expands to include v
- All invariants 1-8 are maintained after insertion

## Special Case Invariants

### 10. Boundary Handling
- Tetrahedra on convex hull have at least one null neighbor
- The set of boundary faces forms a closed surface

### 11. Degeneracy Handling
- Coplanar points handled correctly (no zero-volume tetrahedra)
- Collinear points handled correctly
- Duplicate points handled correctly (or rejected)

## Implementation-Specific Invariants

### 12. Index Integrity (for SoA implementations)
- All vertex indices in tetrahedra are valid (0 ≤ idx < vertex_count)
- All neighbor indices are valid or -1 (representing null)
- Deleted tetrahedra have all fields cleared/marked

### 13. Memory Consistency
- No dangling references to deleted tetrahedra
- No memory leaks from unreferenced tetrahedra
- Reused slots properly initialized

## Verification Methods

Each invariant can be verified through specific checks:

1. **Structure**: Validate vertex indices are distinct and in range
2. **Connectivity**: For each neighbor relationship, verify bidirectionality
3. **Delaunay**: Use in-sphere tests on all vertex-tetrahedron pairs
4. **Convexity**: Verify no overlaps and complete coverage
5. **Algorithmic**: Test with known inputs and expected outputs

## Testing Strategy

1. **Unit tests**: Verify each invariant after each operation
2. **Property-based tests**: Random operations should maintain all invariants
3. **Stress tests**: Large datasets should not violate invariants
4. **Comparison tests**: Results should match OO implementation exactly