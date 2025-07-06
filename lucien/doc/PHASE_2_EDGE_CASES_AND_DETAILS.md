# Phase 2: Edge Cases and Implementation Details

## Critical Implementation Details

### 1. Vertex Ordering in Child Tetrahedra

The CHILD_VERTEX_PARENT_VERTEX table gives us vertex references, but we need to ensure proper ordering:

```java
// Child vertex assignments from connectivity table
byte[][] CHILD_VERTEX_PARENT_VERTEX = {
    { 4, 5, 6, 10 },   // Child 0: M01, M02, M03, Center
    { 0, 4, 5, 10 },   // Child 1: V0, M01, M02, Center
    { 4, 1, 7, 10 },   // Child 2: M01, V1, M12, Center
    { 5, 7, 2, 10 },   // Child 3: M02, M12, V2, Center
    { 6, 8, 9, 3 },    // Child 4: M03, M13, M23, V3
    { 10, 5, 6, 9 },   // Child 5: Center, M02, M03, M23
    { 4, 10, 8, 7 },   // Child 6: M01, Center, M13, M12
    { 10, 9, 8, 7 }    // Child 7: Center, M23, M13, M12
};
```

**Key Insight**: The vertex order in these arrays is already correct for maintaining proper tetrahedron orientation as defined by the connectivity tables.

### 2. Handling Grid Fitting Edge Cases

#### Case 1: Child Centroid Outside Grid Bounds
```java
private static Tet handleOutOfBounds(GeometricChild geoChild, Tet parent) {
    // If child centroid is outside valid grid bounds
    // Find the closest valid grid position
    
    byte level = (byte)(parent.l() + 1);
    int cellSize = 1 << (Tet.getMaxRefinementLevel() - level);
    int maxCoord = (1 << Tet.getMaxRefinementLevel()) - cellSize;
    
    Point3i clamped = new Point3i(
        Math.max(0, Math.min(maxCoord, (int)geoChild.centroid.x)),
        Math.max(0, Math.min(maxCoord, (int)geoChild.centroid.y)),
        Math.max(0, Math.min(maxCoord, (int)geoChild.centroid.z))
    );
    
    // Quantize to grid
    clamped.x = (clamped.x / cellSize) * cellSize;
    clamped.y = (clamped.y / cellSize) * cellSize;
    clamped.z = (clamped.z / cellSize) * cellSize;
    
    return new Tet(clamped.x, clamped.y, clamped.z, level, 
                   getChildType(parent.type(), geoChild.childIndex));
}
```

#### Case 2: Multiple Children Map to Same Grid Cell
```java
private static Tet[] resolveCollisions(Tet[] gridChildren, GeometricChild[] geoChildren) {
    // Detect collisions
    Map<Long, List<Integer>> cellToChildren = new HashMap<>();
    
    for (int i = 0; i < 8; i++) {
        long key = encodeGridPosition(gridChildren[i]);
        cellToChildren.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
    }
    
    // Resolve collisions
    for (List<Integer> collision : cellToChildren.values()) {
        if (collision.size() > 1) {
            // Keep child closest to its geometric centroid
            // Adjust others to nearby cells
            resolveCollision(gridChildren, geoChildren, collision);
        }
    }
    
    return gridChildren;
}
```

### 3. Containment Verification

#### Strict Containment Check
```java
private static boolean isStrictlyContained(Tet child, Tet parent) {
    // Get all 4 vertices of child
    Point3i[] childVerts = child.coordinates();
    Point3f[] parentVerts = getFloatVertices(parent);
    
    // Check each child vertex is inside parent
    for (Point3i cv : childVerts) {
        Point3f point = new Point3f(cv.x, cv.y, cv.z);
        
        // Use barycentric coordinates for exact test
        if (!TetrahedralGeometry.containsPoint(point, parentVerts)) {
            return false;
        }
    }
    
    return true;
}
```

#### Relaxed Containment (for edge cases)
```java
private static boolean isRelaxedContained(Tet child, Tet parent) {
    // Allow small tolerance for numerical errors
    final float EPSILON = 1e-6f;
    
    Point3i[] childVerts = child.coordinates();
    Point3f[] parentVerts = getFloatVertices(parent);
    
    // Expand parent slightly for tolerance
    Point3f[] expandedParent = expandTetrahedron(parentVerts, EPSILON);
    
    for (Point3i cv : childVerts) {
        Point3f point = new Point3f(cv.x, cv.y, cv.z);
        if (!TetrahedralGeometry.containsPoint(point, expandedParent)) {
            return false;
        }
    }
    
    return true;
}
```

### 4. Special Cases by Parent Type

Different parent types may have different optimal strategies:

```java
private static Point3f computeCenterByType(byte parentType, Point3f[] vertices, EdgeMidpoints midpoints) {
    // For some parent types, the octahedron center might work better
    switch (parentType) {
        case 0, 3:
            // Types 0 and 3: Use parent centroid
            return computeTetrahedronCentroid(vertices);
            
        case 1, 4:
            // Types 1 and 4: Use octahedron centroid
            return computeOctahedronCentroid(midpoints);
            
        case 2, 5:
            // Types 2 and 5: Use weighted average
            Point3f tetCenter = computeTetrahedronCentroid(vertices);
            Point3f octCenter = computeOctahedronCentroid(midpoints);
            return new Point3f(
                (tetCenter.x + octCenter.x) / 2,
                (tetCenter.y + octCenter.y) / 2,
                (tetCenter.z + octCenter.z) / 2
            );
            
        default:
            return computeTetrahedronCentroid(vertices);
    }
}
```

### 5. Debugging and Visualization Support

```java
public static class SubdivisionDebugInfo {
    public final Tet parent;
    public final GeometricChild[] geometricChildren;
    public final Tet[] gridChildren;
    public final Map<Integer, String> adjustments;
    public final ValidationResult validation;
    
    public void exportToObj(String filename) {
        // Export parent and children as OBJ file for visualization
    }
    
    public void printReport() {
        System.out.println("Parent: " + parent);
        System.out.println("Geometric Children:");
        for (int i = 0; i < 8; i++) {
            GeometricChild gc = geometricChildren[i];
            System.out.printf("  Child %d: centroid=(%.2f,%.2f,%.2f) type=%d%n",
                i, gc.centroid.x, gc.centroid.y, gc.centroid.z, gc.expectedType);
        }
        
        System.out.println("Grid Children:");
        for (int i = 0; i < 8; i++) {
            Tet child = gridChildren[i];
            System.out.printf("  Child %d: pos=(%d,%d,%d) type=%d contained=%b%n",
                i, child.x(), child.y(), child.z(), child.type(),
                isStrictlyContained(child, parent));
        }
        
        if (!adjustments.isEmpty()) {
            System.out.println("Adjustments made:");
            adjustments.forEach((i, msg) -> 
                System.out.println("  Child " + i + ": " + msg));
        }
    }
}
```

### 6. Performance Optimizations

```java
// Thread-local object pools to reduce allocation
private static final ThreadLocal<Point3f[]> VERTEX_POOL = 
    ThreadLocal.withInitial(() -> new Point3f[4]);

private static final ThreadLocal<Point3f[]> MIDPOINT_POOL = 
    ThreadLocal.withInitial(() -> new Point3f[6]);

// Cached connectivity lookups
private static final Map<Integer, byte[]> CHILD_TYPE_CACHE = 
    precomputeChildTypes();

// Fast containment check using bounding box first
private static boolean fastContainmentCheck(Tet child, Tet parent) {
    // Quick AABB check before expensive exact test
    if (!boundingBoxContains(parent, child)) {
        return false;
    }
    return isStrictlyContained(child, parent);
}
```

## Error Handling Strategy

### 1. Recoverable Errors
- Child slightly outside parent → Adjust to nearest valid position
- Grid position collision → Resolve by proximity
- Numerical precision issues → Use tolerance

### 2. Non-Recoverable Errors
- At max refinement level → Throw IllegalStateException
- Invalid parent type → Throw IllegalArgumentException
- Corrupted connectivity data → Throw IllegalStateException

### 3. Warning Conditions
- Volume conservation off by > 0.1% → Log warning
- Child adjustment needed → Log debug info
- Performance degradation → Log metrics

## Test Strategy

### Unit Tests
1. Test each child type assignment
2. Test edge midpoint calculations
3. Test center point variations
4. Test grid fitting edge cases

### Integration Tests
1. Test full subdivision at each level
2. Test all parent types (0-5)
3. Test boundary conditions
4. Test volume conservation

### Stress Tests
1. Subdivide to maximum depth
2. Test with extreme coordinates
3. Test concurrent subdivision
4. Measure performance metrics

---

*Edge cases documented: July 2025*
*Ready for implementation with full awareness of complexities*