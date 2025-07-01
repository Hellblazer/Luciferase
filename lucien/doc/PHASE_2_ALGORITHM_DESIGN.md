# Phase 2: Geometric Subdivision Algorithm Design

## Overview

This document presents the detailed design for implementing true geometric subdivision of tetrahedra, where all 8 children are guaranteed to be 100% contained within the parent tetrahedron while maintaining correct SFC indices.

## Core Components

### 1. GeometricSubdivision Class Structure

```java
package com.hellblazer.luciferase.lucien.tetree;

/**
 * Performs true geometric subdivision of tetrahedra following Bey refinement.
 * All children are guaranteed to be contained within the parent's volume.
 */
public class GeometricSubdivision {
    
    /**
     * Represents a geometrically subdivided child tetrahedron
     */
    public static class GeometricChild {
        public final Point3f[] vertices;      // 4 vertices in 3D space
        public final int childIndex;          // 0-7, position in parent
        public final byte expectedType;       // Type from connectivity tables
        public final Point3f centroid;        // Geometric center
        
        public GeometricChild(Point3f[] vertices, int childIndex, byte expectedType) {
            this.vertices = vertices;
            this.childIndex = childIndex;
            this.expectedType = expectedType;
            this.centroid = computeCentroid(vertices);
        }
        
        private static Point3f computeCentroid(Point3f[] verts) {
            return new Point3f(
                (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4.0f,
                (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4.0f,
                (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4.0f
            );
        }
    }
    
    /**
     * Edge midpoint cache to avoid recomputation
     */
    private static class EdgeMidpoints {
        final Point3f m01, m02, m03, m12, m13, m23;
        
        EdgeMidpoints(Point3f[] vertices) {
            m01 = midpoint(vertices[0], vertices[1]);
            m02 = midpoint(vertices[0], vertices[2]);
            m03 = midpoint(vertices[0], vertices[3]);
            m12 = midpoint(vertices[1], vertices[2]);
            m13 = midpoint(vertices[1], vertices[3]);
            m23 = midpoint(vertices[2], vertices[3]);
        }
        
        Point3f get(int index) {
            return switch(index) {
                case 4 -> m01;
                case 5 -> m02;
                case 6 -> m03;
                case 7 -> m12;
                case 8 -> m13;
                case 9 -> m23;
                default -> throw new IllegalArgumentException("Invalid midpoint index: " + index);
            };
        }
    }
}
```

### 2. Core Subdivision Algorithm

```java
/**
 * Subdivides a parent tetrahedron into 8 children using Bey refinement.
 * 
 * @param parent The parent Tet to subdivide
 * @return Array of 8 GeometricChild objects
 */
public static GeometricChild[] subdivide(Tet parent) {
    // Step 1: Get parent vertices in 3D space
    Point3f[] parentVertices = getFloatVertices(parent);
    
    // Step 2: Compute edge midpoints (cached for reuse)
    EdgeMidpoints midpoints = new EdgeMidpoints(parentVertices);
    
    // Step 3: Compute center point (reference point 10)
    Point3f center = computeCenter(parent, parentVertices, midpoints);
    
    // Step 4: Create 8 children using connectivity tables
    GeometricChild[] children = new GeometricChild[8];
    
    for (int i = 0; i < 8; i++) {
        Point3f[] childVerts = assembleChildVertices(
            i, parent.type(), parentVertices, midpoints, center
        );
        
        byte expectedType = getExpectedChildType(parent.type(), i);
        
        children[i] = new GeometricChild(childVerts, i, expectedType);
    }
    
    return children;
}

/**
 * Assembles vertices for a specific child based on connectivity tables
 */
private static Point3f[] assembleChildVertices(
        int childIndex, byte parentType, 
        Point3f[] parentVerts, EdgeMidpoints midpoints, Point3f center) {
    
    byte[] vertexRefs = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[childIndex];
    Point3f[] childVerts = new Point3f[4];
    
    for (int i = 0; i < 4; i++) {
        byte ref = vertexRefs[i];
        childVerts[i] = switch(ref) {
            case 0, 1, 2, 3 -> new Point3f(parentVerts[ref]);  // Parent vertex
            case 4, 5, 6, 7, 8, 9 -> new Point3f(midpoints.get(ref));  // Edge midpoint
            case 10 -> new Point3f(center);  // Center point
            default -> throw new IllegalStateException("Invalid vertex reference: " + ref);
        };
    }
    
    return childVerts;
}
```

### 3. Center Point Computation

The center point (reference 10) needs careful consideration:

```java
/**
 * Computes the center point for octahedron splitting.
 * 
 * Three possible interpretations:
 * 1. Centroid of parent tetrahedron
 * 2. Centroid of the octahedron formed by edge midpoints
 * 3. Point that minimizes distance to all edge midpoints
 * 
 * We'll use option 1 (parent centroid) as it's simplest and most intuitive.
 */
private static Point3f computeCenter(Tet parent, Point3f[] vertices, EdgeMidpoints midpoints) {
    // Option 1: Centroid of parent tetrahedron
    return new Point3f(
        (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f,
        (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f,
        (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f
    );
    
    // Alternative Option 2: Centroid of octahedron
    // return new Point3f(
    //     (midpoints.m01.x + midpoints.m02.x + midpoints.m03.x + 
    //      midpoints.m12.x + midpoints.m13.x + midpoints.m23.x) / 6.0f,
    //     ... same for y and z
    // );
}
```

### 4. Grid Fitting Algorithm

This is the most challenging part - mapping geometric tetrahedra back to grid positions:

```java
/**
 * Maps a geometric child tetrahedron to the best grid position
 */
public static class GridFitter {
    
    /**
     * Fits a geometric child to the grid while ensuring containment
     */
    public static Tet fitToGrid(GeometricChild geoChild, Tet parent) {
        byte childLevel = (byte)(parent.l() + 1);
        
        // Strategy 1: Use centroid for initial position
        Point3i gridPos = quantizeToGrid(geoChild.centroid, childLevel);
        
        // Strategy 2: Ensure the grid position is valid
        gridPos = ensureValidGridPosition(gridPos, childLevel);
        
        // Strategy 3: Get child type from connectivity
        byte childType = getChildType(parent.type(), geoChild.childIndex);
        
        // Create candidate Tet
        Tet candidate = new Tet(gridPos.x, gridPos.y, gridPos.z, childLevel, childType);
        
        // Strategy 4: Validate containment
        if (!isContainedIn(candidate, parent)) {
            candidate = adjustForContainment(candidate, parent, geoChild);
        }
        
        return candidate;
    }
    
    /**
     * Quantizes a 3D point to the nearest grid position at given level
     */
    private static Point3i quantizeToGrid(Point3f point, byte level) {
        int cellSize = 1 << (Tet.getMaxRefinementLevel() - level);
        
        // Round to nearest grid position
        int x = Math.round(point.x / cellSize) * cellSize;
        int y = Math.round(point.y / cellSize) * cellSize;
        int z = Math.round(point.z / cellSize) * cellSize;
        
        return new Point3i(x, y, z);
    }
    
    /**
     * Ensures grid position is properly aligned
     */
    private static Point3i ensureValidGridPosition(Point3i pos, byte level) {
        int cellSize = 1 << (Tet.getMaxRefinementLevel() - level);
        
        // Ensure alignment to grid
        pos.x = (pos.x / cellSize) * cellSize;
        pos.y = (pos.y / cellSize) * cellSize;
        pos.z = (pos.z / cellSize) * cellSize;
        
        // Ensure non-negative
        pos.x = Math.max(0, pos.x);
        pos.y = Math.max(0, pos.y);
        pos.z = Math.max(0, pos.z);
        
        return pos;
    }
    
    /**
     * Adjusts grid position to ensure containment within parent
     */
    private static Tet adjustForContainment(Tet candidate, Tet parent, GeometricChild geoChild) {
        // This is complex - may need to search nearby grid positions
        // Start with positions closer to parent anchor
        
        int cellSize = 1 << (Tet.getMaxRefinementLevel() - candidate.l());
        Point3i parentAnchor = new Point3i(parent.x(), parent.y(), parent.z());
        
        // Try positions moving toward parent anchor
        int[] offsets = {0, -cellSize, cellSize, -2*cellSize, 2*cellSize};
        
        for (int dx : offsets) {
            for (int dy : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    
                    Point3i adjusted = new Point3i(
                        candidate.x() + dx,
                        candidate.y() + dy,
                        candidate.z() + dz
                    );
                    
                    if (isValidPosition(adjusted, candidate.l())) {
                        Tet adjusted_tet = new Tet(
                            adjusted.x, adjusted.y, adjusted.z,
                            candidate.l(), candidate.type()
                        );
                        
                        if (isContainedIn(adjusted_tet, parent)) {
                            return adjusted_tet;
                        }
                    }
                }
            }
        }
        
        // If no valid position found, return original
        // (may need to log warning)
        return candidate;
    }
}
```

### 5. Validation Framework Design

```java
/**
 * Validates geometric subdivision results
 */
public static class SubdivisionValidator {
    
    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public final Map<String, Object> metrics;
        
        public ValidationResult(boolean valid, List<String> errors, Map<String, Object> metrics) {
            this.valid = valid;
            this.errors = errors;
            this.metrics = metrics;
        }
    }
    
    /**
     * Comprehensive validation of subdivision results
     */
    public static ValidationResult validate(Tet parent, Tet[] children) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> metrics = new HashMap<>();
        
        // 1. Containment validation
        ValidationResult containment = validateContainment(parent, children);
        errors.addAll(containment.errors);
        metrics.putAll(containment.metrics);
        
        // 2. Volume conservation
        ValidationResult volume = validateVolumeConservation(parent, children);
        errors.addAll(volume.errors);
        metrics.putAll(volume.metrics);
        
        // 3. SFC consistency
        ValidationResult sfc = validateSFCConsistency(parent, children);
        errors.addAll(sfc.errors);
        metrics.putAll(sfc.metrics);
        
        // 4. No overlap validation
        ValidationResult overlap = validateNoOverlap(children);
        errors.addAll(overlap.errors);
        metrics.putAll(overlap.metrics);
        
        return new ValidationResult(errors.isEmpty(), errors, metrics);
    }
}
```

## API Design

### Public API in Tet Class

```java
public class Tet {
    /**
     * Performs geometric subdivision of this tetrahedron.
     * All 8 children are guaranteed to be contained within this tetrahedron's volume.
     * 
     * @return Array of 8 child Tet objects
     * @throws IllegalStateException if at max refinement level
     */
    public Tet[] geometricSubdivide() {
        if (l >= getMaxRefinementLevel()) {
            throw new IllegalStateException("Cannot subdivide at max refinement level");
        }
        
        // Use GeometricSubdivision to create geometric children
        GeometricSubdivision.GeometricChild[] geoChildren = 
            GeometricSubdivision.subdivide(this);
        
        // Map to grid positions
        Tet[] gridChildren = new Tet[8];
        for (int i = 0; i < 8; i++) {
            gridChildren[i] = GridFitter.fitToGrid(geoChildren[i], this);
        }
        
        // Validate results
        SubdivisionValidator.ValidationResult validation = 
            SubdivisionValidator.validate(this, gridChildren);
        
        if (!validation.valid) {
            throw new IllegalStateException(
                "Geometric subdivision failed validation: " + 
                String.join(", ", validation.errors)
            );
        }
        
        return gridChildren;
    }
}
```

## Key Design Decisions

### 1. Center Point Definition
- Using parent tetrahedron centroid as the center point
- Simple, intuitive, and guaranteed to be inside parent
- Alternative: octahedron centroid (more complex, similar results)

### 2. Grid Fitting Strategy
- Start with centroid-based positioning
- Search nearby grid positions if needed
- Prioritize positions closer to parent anchor
- Accept best effort if perfect containment impossible

### 3. Validation Approach
- Comprehensive validation with detailed metrics
- Throw exception if critical properties violated
- Log warnings for minor issues
- Provide detailed error messages

### 4. Performance Considerations
- Cache edge midpoints (computed once, used multiple times)
- Minimize object creation in hot paths
- Consider pooling Point3f objects
- Lazy validation for production use

## Next Steps

1. Implement GeometricSubdivision class
2. Implement GridFitter with basic strategy
3. Implement SubdivisionValidator
4. Create comprehensive test suite
5. Optimize based on profiling

---

*Design completed: July 2025*
*Ready for Phase 3: Implementation*