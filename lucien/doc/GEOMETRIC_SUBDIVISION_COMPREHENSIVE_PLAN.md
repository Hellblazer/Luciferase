# Comprehensive Plan: Geometric Tetrahedral Subdivision Implementation

## Overview

This plan details the implementation of a `geometricSubdivide()` method that performs true geometric subdivision of tetrahedra while maintaining SFC index consistency and ensuring 100% containment.

## Phase 1: Foundation Analysis (Days 1-2)

### 1.1 Understand Current Vertex Computation

**Task**: Analyze how `Tet.coordinates()` computes vertices

```java
// Document the exact algorithm
// Verify vertex ordering for each type (0-5)
// Confirm vertices form valid tetrahedra
```

**Deliverables**:
- Document: "VERTEX_COMPUTATION_ANALYSIS.md"
- Test: "VertexComputationValidationTest.java"
- Findings on vertex ordering by type

### 1.2 Decode Connectivity Tables

**Task**: Understand octahedron splitting rules

```java
// Analyze TetreeConnectivity tables
// Determine splitting axis for each parent type
// Map child indices to vertex assignments
```

**Deliverables**:
- Document: "OCTAHEDRON_SPLITTING_RULES.md"
- Diagram: Visual representation of splits
- Child vertex assignment matrices

### 1.3 Study Bey Refinement Mathematics

**Task**: Deep dive into Bey's original algorithm

**Research**:
- Original Bey paper on tetrahedral refinement
- Edge midpoint calculations
- Octahedron formation and splitting
- Volume preservation proofs

**Deliverables**:
- Document: "BEY_REFINEMENT_MATHEMATICS.md"
- Reference implementation sketches

## Phase 2: Algorithm Design (Days 3-4)

### 2.1 Design Core Subdivision Algorithm

**Components**:

```java
public class GeometricTetSubdivision {
    // Core subdivision logic
    public static class SubdividedTet {
        Point3f[] vertices;      // Actual geometric vertices
        byte childIndex;         // Index in parent (0-7)
        byte expectedType;       // Type from connectivity
        Tet gridTet;            // Best grid representation
    }
    
    // Main subdivision method
    public static SubdividedTet[] subdivide(Tet parent) {
        // 1. Get parent vertices
        // 2. Compute edge midpoints
        // 3. Form 8 child tetrahedra
        // 4. Map to grid representation
        // 5. Validate containment
    }
}
```

### 2.2 Design Grid Fitting Algorithm

**Challenge**: Map geometric tetrahedra to grid cells

```java
public class GridFitter {
    // Find best grid cell for geometric tetrahedron
    public static Tet fitToGrid(Point3f[] vertices, 
                                byte level, 
                                byte expectedType,
                                Tet parent) {
        // 1. Compute geometric centroid
        // 2. Find candidate grid cells
        // 3. Select cell ensuring containment
        // 4. Validate type consistency
    }
}
```

### 2.3 Design Validation Framework

**Validation Levels**:

1. **Geometric Validation**
   - All vertices inside parent
   - No vertices outside bounds
   - Volume conservation

2. **SFC Validation**
   - Correct tm-indices
   - Parent-child consistency
   - Type correctness

3. **Topological Validation**
   - Proper adjacencies
   - No gaps or overlaps
   - Face matching

**Deliverables**:
- Design document: "GEOMETRIC_SUBDIVISION_DESIGN_FINAL.md"
- API specification
- Validation criteria

## Phase 3: Implementation (Days 5-8)

### 3.1 Implement Geometric Primitives

```java
public class TetrahedronGeometry {
    // Existing
    public static boolean isPointInTetrahedron(Point3f p, Point3f[] tet);
    public static float tetrahedronVolume(Point3f[] tet);
    
    // New
    public static Point3f[] computeEdgeMidpoints(Point3f[] tet);
    public static Point3f centroid(Point3f[] tet);
    public static BoundingBox bounds(Point3f[] tet);
    public static boolean tetrahedronInsideTetrahedron(
        Point3f[] inner, Point3f[] outer);
}
```

### 3.2 Implement Subdivision Logic

```java
public class Tet {
    /**
     * Performs geometric subdivision ensuring all children
     * are 100% contained within parent volume.
     */
    public Tet[] geometricSubdivide() {
        // Phase 1: Geometric subdivision
        Point3f[] vertices = this.getFloatVertices();
        Point3f[] midpoints = TetrahedronGeometry.computeEdgeMidpoints(vertices);
        
        // Phase 2: Create child tetrahedra
        GeometricTetSubdivision.SubdividedTet[] geoChildren = 
            createGeometricChildren(vertices, midpoints);
        
        // Phase 3: Fit to grid
        Tet[] gridChildren = new Tet[8];
        for (int i = 0; i < 8; i++) {
            gridChildren[i] = fitChildToGrid(geoChildren[i]);
        }
        
        // Phase 4: Validate
        validateSubdivision(gridChildren);
        
        return gridChildren;
    }
}
```

### 3.3 Implement Child Creation

```java
private GeometricTetSubdivision.SubdividedTet[] createGeometricChildren(
        Point3f[] vertices, Point3f[] midpoints) {
    
    SubdividedTet[] children = new SubdividedTet[8];
    
    // Corner children (0-3)
    children[0] = createCornerChild(0, vertices[0], 
        midpoints[0], midpoints[1], midpoints[2]);
    children[1] = createCornerChild(1, vertices[1], 
        midpoints[0], midpoints[3], midpoints[4]);
    children[2] = createCornerChild(2, vertices[2], 
        midpoints[1], midpoints[3], midpoints[5]);
    children[3] = createCornerChild(3, vertices[3], 
        midpoints[2], midpoints[4], midpoints[5]);
    
    // Octahedral children (4-7)
    int splitAxis = determineSplitAxis(this.type);
    children[4] = createOctaChild(4, splitAxis, midpoints);
    children[5] = createOctaChild(5, splitAxis, midpoints);
    children[6] = createOctaChild(6, splitAxis, midpoints);
    children[7] = createOctaChild(7, splitAxis, midpoints);
    
    return children;
}
```

### 3.4 Implement Grid Fitting

```java
private Tet fitChildToGrid(GeometricTetSubdivision.SubdividedTet geoChild) {
    byte childLevel = (byte)(this.l + 1);
    
    // Strategy 1: Centroid-based fitting
    Point3f centroid = TetrahedronGeometry.centroid(geoChild.vertices);
    Point3i gridPos = quantizeToGrid(centroid, childLevel);
    
    // Strategy 2: Ensure containment
    gridPos = adjustForContainment(gridPos, geoChild, this);
    
    // Create Tet with correct type
    byte childType = getChildType(this.type, geoChild.childIndex);
    Tet candidate = new Tet(gridPos.x, gridPos.y, gridPos.z, 
                           childLevel, childType);
    
    // Validate and adjust if needed
    if (!isFullyContained(candidate, this)) {
        candidate = findContainedAlternative(geoChild, this, childLevel);
    }
    
    return candidate;
}
```

## Phase 4: Validation Implementation (Days 9-10)

### 4.1 Containment Validation

```java
public class SubdivisionValidator {
    public static boolean validateContainment(Tet parent, Tet[] children) {
        Point3f[] parentVerts = parent.getFloatVertices();
        
        for (Tet child : children) {
            Point3f[] childVerts = child.getFloatVertices();
            for (Point3f v : childVerts) {
                if (!TetrahedronGeometry.isPointInTetrahedron(v, parentVerts)) {
                    return false;
                }
            }
        }
        return true;
    }
}
```

### 4.2 Volume Conservation

```java
public static boolean validateVolume(Tet parent, Tet[] children) {
    float parentVol = TetrahedronGeometry.volume(parent.getFloatVertices());
    float childVolSum = 0;
    
    for (Tet child : children) {
        childVolSum += TetrahedronGeometry.volume(child.getFloatVertices());
    }
    
    float tolerance = parentVol * 0.001f; // 0.1% tolerance
    return Math.abs(parentVol - childVolSum) < tolerance;
}
```

### 4.3 SFC Consistency

```java
public static boolean validateSFC(Tet parent, Tet[] children) {
    // Each child should identify parent correctly
    for (int i = 0; i < 8; i++) {
        Tet computedParent = children[i].parent();
        if (!computedParent.equals(parent)) {
            return false;
        }
        
        // Child should be accessible via child(i)
        Tet sfcChild = parent.child(i);
        if (!children[i].tmIndex().equals(sfcChild.tmIndex())) {
            return false;
        }
    }
    return true;
}
```

## Phase 5: Testing (Days 11-12)

### 5.1 Unit Tests

```java
public class GeometricSubdivisionTest {
    @Test
    void testAllTypesSubdivide() {
        // Test each parent type (0-5)
        // At multiple levels
        // Verify containment
    }
    
    @Test
    void testVolumeConservation() {
        // Verify sum of children equals parent
        // Test at different scales
    }
    
    @Test
    void testSFCConsistency() {
        // Verify tm-indices correct
        // Test parent-child relationships
    }
    
    @Test
    void testEdgeCases() {
        // Test at grid boundaries
        // Test at max refinement level
        // Test degenerate cases
    }
}
```

### 5.2 Visual Validation

```java
public class SubdivisionVisualizer {
    // Render parent and children
    // Color code by child index
    // Show containment graphically
    // Highlight any issues
}
```

### 5.3 Performance Tests

```java
public class SubdivisionBenchmark {
    // Measure subdivision time
    // Compare with simple child() calls
    // Profile memory usage
    // Test at various levels
}
```

## Phase 6: Integration (Days 13-14)

### 6.1 Update Dependent Systems

- Review TetreeSubdivisionStrategy
- Update spatial query methods
- Ensure ray traversal works
- Fix any broken tests

### 6.2 Documentation

- Complete API documentation
- Create usage examples
- Document limitations
- Migration guide if needed

### 6.3 Final Validation

- Run full test suite
- Stress test edge cases
- Validate performance
- Get code review

## Risk Mitigation

### Technical Risks

1. **Grid Resolution Limits**
   - Mitigation: Sub-cell precision algorithms
   - Fallback: Document limitations

2. **Performance Impact**
   - Mitigation: Optimize hot paths
   - Fallback: Cache results

3. **Numerical Precision**
   - Mitigation: Use robust geometric predicates
   - Fallback: Adjustable tolerances

### Implementation Risks

1. **Complexity**
   - Mitigation: Incremental implementation
   - Fallback: Simplified version first

2. **Testing Coverage**
   - Mitigation: Comprehensive test suite
   - Fallback: Visual validation tools

## Success Criteria

1. ✓ All 8 children 100% inside parent
2. ✓ Correct tm-indices maintained
3. ✓ Volume conservation within 0.1%
4. ✓ All unit tests pass
5. ✓ Performance acceptable (<10ms per subdivision)
6. ✓ No regression in existing functionality

## Timeline Summary

- Days 1-2: Foundation Analysis
- Days 3-4: Algorithm Design
- Days 5-8: Core Implementation
- Days 9-10: Validation Implementation
- Days 11-12: Testing
- Days 13-14: Integration and Polish

Total: 14 working days (approximately 3 weeks)

---

*Plan created: July 1, 2025*
*Status: READY FOR EXECUTION*