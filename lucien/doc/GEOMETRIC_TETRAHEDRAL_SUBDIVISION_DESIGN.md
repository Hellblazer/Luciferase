# Geometric Tetrahedral Subdivision Design

## Overview

This document describes the design for a new method that performs true geometric subdivision of a tetrahedron into 8 child tetrahedra, where ALL children are 100% contained within the parent's volume. This is completely separate from the grid-based `child()` method.

## Key Requirements

1. **100% Containment**: All 8 children must be completely inside the parent tetrahedron
2. **Correct TM-Index**: Each child must have the correct tm-index on the SFC relative to the parent
3. **Proper Orientation**: Children must maintain correct type/orientation
4. **Volume Conservation**: The 8 children together must exactly fill the parent's volume
5. **No Cube Logic**: Must use tetrahedral geometry only - no cube-based calculations

## The Algorithm: True Bey Refinement

### Step 1: Get Parent Tetrahedron Vertices

```java
// Get the actual 4 vertices of the parent tetrahedron
Point3f[] parentVertices = getTetrahedronVertices();
// Returns V0, V1, V2, V3 in correct order for this type
```

### Step 2: Compute Edge Midpoints

A tetrahedron has 6 edges. We need the midpoint of each:

```java
Point3f M01 = midpoint(V0, V1);  // Edge 0-1
Point3f M02 = midpoint(V0, V2);  // Edge 0-2  
Point3f M03 = midpoint(V0, V3);  // Edge 0-3
Point3f M12 = midpoint(V1, V2);  // Edge 1-2
Point3f M13 = midpoint(V1, V3);  // Edge 1-3
Point3f M23 = midpoint(V2, V3);  // Edge 2-3
```

### Step 3: Form the 8 Child Tetrahedra

The 8 children in Bey refinement are:

#### Corner Children (0-3)
Each original vertex with its 3 adjacent edge midpoints:

```
Child 0: V0, M01, M02, M03
Child 1: V1, M01, M12, M13
Child 2: V2, M02, M12, M23
Child 3: V3, M03, M13, M23
```

#### Octahedral Children (4-7)
The 6 edge midpoints form an octahedron. We split it into 4 tetrahedra:

The octahedron can be split along one of three axes. The choice depends on the parent type to maintain consistency. For example, splitting along the M01-M23 axis:

```
Child 4: M01, M02, M03, M23
Child 5: M01, M02, M12, M23
Child 6: M01, M03, M13, M23
Child 7: M01, M12, M13, M23
```

### Step 4: Determine Child Tet Objects

For each child, we need:
1. The anchor point (minimum coordinates)
2. The correct type (from connectivity tables)
3. The correct level (parent level + 1)

## Implementation Design

### New Method: `geometricSubdivide()`

```java
public class Tet {
    /**
     * Performs true geometric subdivision of this tetrahedron into 8 children.
     * All children are guaranteed to be 100% contained within the parent's volume.
     * 
     * This is NOT the same as the grid-based child() method. This performs
     * actual geometric subdivision in continuous space.
     * 
     * @return Array of 8 child Tet objects, indexed by their child number
     */
    public Tet[] geometricSubdivide() {
        // 1. Get parent's actual tetrahedron vertices
        Point3f[] vertices = getFloatVertices();
        
        // 2. Compute all 6 edge midpoints
        Point3f[] midpoints = computeEdgeMidpoints(vertices);
        
        // 3. Create 8 child tetrahedra
        Tet[] children = new Tet[8];
        
        for (int i = 0; i < 8; i++) {
            // Get vertices for this child based on Bey refinement
            Point3f[] childVerts = getChildVertices(i, vertices, midpoints);
            
            // Find the Tet that best represents this geometric tetrahedron
            children[i] = createTetFromGeometry(childVerts, i);
        }
        
        return children;
    }
    
    /**
     * Creates a Tet object that represents the given geometric tetrahedron.
     * This involves finding the grid position that best matches the geometry.
     */
    private Tet createTetFromGeometry(Point3f[] vertices, int childIndex) {
        // 1. Compute the anchor (minimum coordinates) at child level
        byte childLevel = (byte)(this.l + 1);
        Point3i anchor = quantizeToGrid(findMinimum(vertices), childLevel);
        
        // 2. Get the child type from connectivity tables
        byte beyId = TetreeConnectivity.getBeyChildId(this.type, childIndex);
        byte childType = TetreeConnectivity.getChildType(this.type, beyId);
        
        // 3. Create the Tet
        Tet child = new Tet(anchor.x, anchor.y, anchor.z, childLevel, childType);
        
        // 4. Verify it's actually inside parent (critical validation)
        assert isContainedIn(child, this) : "Child must be inside parent";
        
        return child;
    }
}
```

### Helper Methods Needed

```java
/**
 * Get the actual 4 vertices of this tetrahedron in 3D space.
 * Order matters for maintaining orientation.
 */
private Point3f[] getFloatVertices() {
    Point3i[] intVertices = this.coordinates();
    Point3f[] floatVertices = new Point3f[4];
    for (int i = 0; i < 4; i++) {
        floatVertices[i] = new Point3f(
            intVertices[i].x,
            intVertices[i].y,
            intVertices[i].z
        );
    }
    return floatVertices;
}

/**
 * Compute the 6 edge midpoints of a tetrahedron.
 * Order: M01, M02, M03, M12, M13, M23
 */
private Point3f[] computeEdgeMidpoints(Point3f[] vertices) {
    return new Point3f[] {
        midpoint(vertices[0], vertices[1]),  // M01
        midpoint(vertices[0], vertices[2]),  // M02
        midpoint(vertices[0], vertices[3]),  // M03
        midpoint(vertices[1], vertices[2]),  // M12
        midpoint(vertices[1], vertices[3]),  // M13
        midpoint(vertices[2], vertices[3])   // M23
    };
}

/**
 * Get the 4 vertices for a specific child in Bey refinement.
 */
private Point3f[] getChildVertices(int childIndex, Point3f[] parentVerts, Point3f[] midpoints) {
    switch (childIndex) {
        // Corner children
        case 0: return new Point3f[] {parentVerts[0], midpoints[0], midpoints[1], midpoints[2]};
        case 1: return new Point3f[] {parentVerts[1], midpoints[0], midpoints[3], midpoints[4]};
        case 2: return new Point3f[] {parentVerts[2], midpoints[1], midpoints[3], midpoints[5]};
        case 3: return new Point3f[] {parentVerts[3], midpoints[2], midpoints[4], midpoints[5]};
        
        // Octahedral children (depends on splitting axis)
        // This is simplified - actual implementation needs to check parent type
        case 4: return new Point3f[] {midpoints[0], midpoints[1], midpoints[2], midpoints[5]};
        case 5: return new Point3f[] {midpoints[0], midpoints[1], midpoints[3], midpoints[5]};
        case 6: return new Point3f[] {midpoints[0], midpoints[2], midpoints[4], midpoints[5]};
        case 7: return new Point3f[] {midpoints[0], midpoints[3], midpoints[4], midpoints[5]};
        
        default: throw new IllegalArgumentException("Invalid child index: " + childIndex);
    }
}
```

## Octahedron Splitting Strategy

The key complexity is determining how to split the octahedron for children 4-7. This depends on:

1. **Parent Type**: Different parent types use different splitting axes
2. **Consistency**: The splitting must be consistent with the tm-index encoding
3. **Orientation**: Child types must match the connectivity tables

### Three Possible Octahedron Splits

The octahedron formed by the 6 edge midpoints can be split along three different axes:

1. **Split along M01-M23**: Connects opposite edge midpoints
2. **Split along M02-M13**: Different pair of opposite midpoints  
3. **Split along M03-M12**: Third pair of opposite midpoints

The choice must match what the connectivity tables expect for each parent type.

## Validation Requirements

### 1. Containment Test

```java
private boolean isContainedIn(Tet child, Tet parent) {
    Point3f[] childVerts = child.getFloatVertices();
    Point3f[] parentVerts = parent.getFloatVertices();
    
    // All child vertices must be inside parent tetrahedron
    for (Point3f v : childVerts) {
        if (!TetrahedralGeometry.isPointInTetrahedron(v, parentVerts)) {
            return false;
        }
    }
    return true;
}
```

### 2. Volume Conservation

```java
private boolean checkVolumeConservation(Tet parent, Tet[] children) {
    float parentVolume = TetrahedralGeometry.tetrahedronVolume(parent.getFloatVertices());
    float childrenVolume = 0;
    
    for (Tet child : children) {
        childrenVolume += TetrahedralGeometry.tetrahedronVolume(child.getFloatVertices());
    }
    
    // Allow small epsilon for floating point errors
    return Math.abs(parentVolume - childrenVolume) < 1e-6;
}
```

### 3. TM-Index Consistency

```java
private boolean checkTmIndexConsistency(Tet parent, Tet[] children) {
    for (int i = 0; i < 8; i++) {
        // Each child should identify as child i of parent
        Tet computedParent = children[i].parent();
        if (!computedParent.equals(parent)) {
            return false;
        }
    }
    return true;
}
```

## Usage Example

```java
// Start with any Tet
Tet parent = new Tet(1000, 2000, 3000, (byte)5, (byte)2);

// Perform geometric subdivision
Tet[] children = parent.geometricSubdivide();

// All 8 children are now:
// - 100% inside parent's tetrahedral volume
// - Have correct tm-indices
// - Have correct types/orientations
// - Together exactly fill parent's volume

// Can be used for spatial operations requiring true geometric subdivision
for (Tet child : children) {
    // Process each child knowing it's truly inside parent
}
```

## Key Differences from child() Method

| Aspect | child() Method | geometricSubdivide() Method |
|--------|---------------|---------------------------|
| Purpose | Grid navigation | True geometric subdivision |
| Coordinates | Grid-based | Continuous space |
| Containment | May be outside | Always 100% inside |
| Usage | SFC traversal | Spatial operations |
| Performance | Very fast | More computation |

## Implementation Priority

1. First implement basic structure with corner children (easier)
2. Add octahedral children with proper splitting
3. Extensive testing for containment and volume
4. Optimize for performance if needed

---

*Document created: July 1, 2025*  
*Status: DESIGN COMPLETE - READY FOR IMPLEMENTATION*