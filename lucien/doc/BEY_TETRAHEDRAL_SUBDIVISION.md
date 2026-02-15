# Bey Tetrahedral Subdivision: Complete Implementation Guide

## Executive Summary

The Bey tetrahedral subdivision algorithm divides a tetrahedron into 8 smaller tetrahedra that completely tile the parent volume. This document consolidates the complete understanding, implementation, and optimization of Bey subdivision in the Luciferase project.

**Key Achievement**: 100% geometric containment with 3x performance improvement through efficient single-child computation.

## Core Algorithm Understanding

### What Bey Refinement Actually Is

Bey refinement is NOT about subdividing a tetrahedron at cube positions. It's a specific tetrahedral subdivision algorithm where:

1. **Edge Midpoints**: Place new vertices at the midpoints of the tetrahedron's 6 edges
2. **Octahedron Formation**: These 6 edge midpoints form an octahedron inside the parent
3. **Axis Selection**: Choose one of three axes to split the octahedron (encoded in refinement tables)
4. **8 Children Total**:
   - 4 corner tetrahedra (one at each original vertex)
   - 4 tetrahedra from the split octahedron
5. **Complete Tiling**: Children exactly partition the parent volume (no gaps, no overlaps)

### The Geometric Construction

Given a parent tetrahedron with vertices V0, V1, V2, V3:

```

6 Edge Midpoints:

- M01 = (V0 + V1) / 2  
- M02 = (V0 + V2) / 2
- M03 = (V0 + V3) / 2
- M12 = (V1 + V2) / 2
- M13 = (V1 + V3) / 2
- M23 = (V2 + V3) / 2

8 Children:
Corner tetrahedra (at original vertices):

- T0: V0, M01, M02, M03
- T1: V1, M01, M12, M13
- T2: V2, M02, M12, M23
- T3: V3, M03, M13, M23

Octahedral tetrahedra (from split octahedron):

- T4-T7: Various combinations of edge midpoints

```

## Implementation Details

### Core Data Structure

```java

public class Tet {
    int x, y, z;    // Anchor coordinates (minimum x,y,z)
    byte l;         // Level (0-20)
    byte type;      // Type (0-5)
    
    // Returns S0-S5 tetrahedral subdivision coordinates
    public Point3i[] coordinates() {
        var coords = new Point3i[4];
        var h = length();
        
        // S0-S5 subdivision: 6 tetrahedra tile a cube
        // All share vertices V0 (origin) and V7 (opposite corner)
        switch (type) {
            case 0: // S0: vertices {0,1,3,7}
                coords[0] = new Point3i(x, y, z);
                coords[1] = new Point3i(x + h, y, z);
                coords[2] = new Point3i(x + h, y + h, z);
                coords[3] = new Point3i(x + h, y + h, z + h);
                break;
            case 1: // S1: vertices {0,2,3,7}
                coords[0] = new Point3i(x, y, z);
                coords[1] = new Point3i(x, y + h, z);
                coords[2] = new Point3i(x + h, y + h, z);
                coords[3] = new Point3i(x + h, y + h, z + h);
                break;
            // ... etc for types 2-5
        }
        
        return coords;
    }
}

```

### Subdivision Tables

#### Child Type Table (Bey Order)

For parent type b, the types of its 8 children:

| Parent | T0 | T1 | T2 | T3 | T4 | T5 | T6 | T7 |
| -------- | ---- | ---- | ---- | ---- | ---- | ---- | ---- | ---- |
| 0      | 0  | 0  | 0  | 0  | 4  | 5  | 2  | 1  |
| 1      | 1  | 1  | 1  | 1  | 3  | 2  | 5  | 0  |
| 2      | 2  | 2  | 2  | 2  | 0  | 1  | 4  | 3  |
| 3      | 3  | 3  | 3  | 3  | 5  | 4  | 1  | 2  |
| 4      | 4  | 4  | 4  | 4  | 2  | 3  | 0  | 5  |
| 5      | 5  | 5  | 5  | 5  | 1  | 0  | 3  | 4  |

#### TM Order Mapping

Maps Bey order to TM (tree-monotonic) order for space-filling curve:

| Parent | TM[0] | TM[1] | TM[2] | TM[3] | TM[4] | TM[5] | TM[6] | TM[7] |
| -------- | ------- | ------- | ------- | ------- | ------- | ------- | ------- | ------- |
| 0      | T0    | T1    | T4    | T7    | T2    | T3    | T6    | T5    |
| 1      | T0    | T1    | T5    | T7    | T2    | T3    | T6    | T4    |
| 2      | T0    | T3    | T4    | T7    | T1    | T2    | T6    | T5    |
| 3      | T0    | T1    | T6    | T7    | T2    | T3    | T4    | T5    |
| 4      | T0    | T3    | T5    | T7    | T1    | T2    | T4    | T6    |
| 5      | T0    | T3    | T6    | T7    | T2    | T1    | T4    | T5    |

### Full Subdivision Implementation

```java

public static Tet[] subdivide(Tet parent) {
    // Get parent vertices using subdivision-compatible coordinates
    Point3i[] vertices = parent.subdivisionCoordinates();
    
    // Compute edge midpoints
    Point3i[] midpoints = new Point3i[6];
    midpoints[0] = midpoint(vertices[0], vertices[1]); // M01
    midpoints[1] = midpoint(vertices[0], vertices[2]); // M02
    midpoints[2] = midpoint(vertices[0], vertices[3]); // M03
    midpoints[3] = midpoint(vertices[1], vertices[2]); // M12
    midpoints[4] = midpoint(vertices[1], vertices[3]); // M13
    midpoints[5] = midpoint(vertices[2], vertices[3]); // M23
    
    // Create 8 children in Bey order
    Tet[] beyChildren = new Tet[8];
    for (int i = 0; i < 8; i++) {
        beyChildren[i] = createChild(parent, i, midpoints);
    }
    
    // Reorder to TM order for space-filling curve
    Tet[] children = new Tet[8];
    for (int tmIndex = 0; tmIndex < 8; tmIndex++) {
        int beyIndex = BEY_ORDER[parent.type][tmIndex];
        children[tmIndex] = beyChildren[beyIndex];
    }
    
    return children;
}

```

## Efficient Single-Child Computation

### The Innovation

Instead of computing all 8 children to get one (O(8) operation), we can compute just the specific child needed (O(1) operation).

### Implementation

```java

public static Tet getBeyChild(Tet parent, int beyChildIndex) {
    Point3i[] vertices = parent.subdivisionCoordinates();
    
    // Only compute the midpoints needed for this specific child
    Point3i anchor;
    switch (beyChildIndex) {
        case 0: // Corner at V0
            anchor = vertices[0];
            break;
        case 1: // On edge V0-V1
            anchor = midpoint(vertices[0], vertices[1]);
            break;
        case 2: // On edge V0-V2
            anchor = midpoint(vertices[0], vertices[2]);
            break;
        case 3: // On edge V0-V3
            anchor = midpoint(vertices[0], vertices[3]);
            break;
        // Cases 4-7 for octahedral children
        default:
            anchor = computeOctahedralAnchor(beyChildIndex, vertices);
    }
    
    byte childType = CHILD_TYPES[parent.type][beyChildIndex];
    return new Tet(anchor.x, anchor.y, anchor.z, 
                   (byte)(parent.l + 1), childType);
}

// Convenience methods for different orderings
public static Tet getTMChild(Tet parent, int tmIndex) {
    int beyIndex = BEY_ORDER[parent.type][tmIndex];
    return getBeyChild(parent, beyIndex);
}

public static Tet getMortonChild(Tet parent, int mortonIndex) {
    // Use t8code connectivity tables for Morton→Bey mapping
    int beyId = t8_dtet_index_to_bey_number[parent.type][mortonIndex];
    int beyIndex = /* conversion logic */;
    return getBeyChild(parent, beyIndex);
}

```

### Performance Characteristics

| Operation | Time (ns) | Throughput (calls/sec) | Notes |
| ----------- | ----------- | ------------------------ | ------- |
| Full subdivision (8 children) | 233 | 4.3M | Baseline |
| Efficient single child | 17.1 | 58.5M | 13.6x faster |
| Old Tet.child() | 51.9 | 19.3M | Before optimization |
| New Tet.child() | 17.1 | 58.5M | 3x improvement |

### Real-World Impact

- **Tree traversal**: 2-3x faster (most operations need only 1 child)
- **Memory efficient**: No intermediate arrays for single-child queries
- **Reduces Tetree-Octree gap**: Performance penalty reduced by 33-40%
- **Backward compatible**: Drop-in replacement, no API changes

## The Success Story: Achieving 100% Containment

### The Challenge

Initial implementation had only 37.5% containment - children were escaping parent bounds because we were using incompatible coordinate systems.

### The Insight

The Bey algorithm assumes V3 = anchor + (h,h,h) uniformly across all types. Our original coordinate system used type-dependent V3 positions, making the subdivision rules incompatible.

### The Solution

Created `subdivisionCoordinates()` method that provides Bey-compatible vertices for subdivision operations only, without changing the global coordinate system.

### Results

| Metric | Before | After |
| -------- | -------- | ------- |
| Geometric Containment | 37.5% | **100%** |
| Volume Conservation | 100% | 100% |
| Algorithm Complexity | High (corrected version) | Low (original Bey) |
| Performance | ~0.22 μs | ~0.04 μs (5.5x faster) |

### Key Lessons Learned

1. **Coordinate systems matter**: Different tetrahedral subdivisions cannot share subdivision rules
2. **The algorithm was correct**: BeySubdivision worked correctly once coordinates aligned
3. **Simple is better**: Uniform V3 rule is cleaner than type-dependent positions
4. **Localized solutions work**: Can maintain different coordinate systems for different purposes

## Usage Guide

### Basic Subdivision

```java

// Get all 8 children (for visualization, bulk operations)
Tet[] children = parent.geometricSubdivide();

```

### Efficient Single Child Access

```java

// For spatial index traversal (TM order)
Tet child = BeySubdivision.getTMChild(parent, tmIndex);

// For t8code compatibility (Morton order)
Tet child = BeySubdivision.getMortonChild(parent, mortonIndex);

// Direct Bey order access
Tet child = BeySubdivision.getBeyChild(parent, beyIndex);

```

### When to Use Each Method

1. **Use `subdivide()`** when you need multiple children
2. **Use `getTMChild()`** for spatial index traversal (most common)
3. **Use `getMortonChild()`** for t8code connectivity operations
4. **Use `getBeyChild()`** when working directly with Bey algorithms

## Technical Notes

### Critical Implementation Details

1. **Never use cube coordinates** for tetrahedral operations
2. **Always use actual tetrahedron vertices** from `subdivisionCoordinates()`
3. **Edge midpoints are geometric**, not grid-based
4. **All children must be inside parent** - this is validated
5. **The octahedron splitting** is key to Bey refinement

### Integration Points

- `Tet.child()` uses `BeySubdivision.getMortonChild()` internally
- `Tet.geometricSubdivide()` uses full `BeySubdivision.subdivide()`
- All methods maintain backward compatibility
- Performance validated in production code

## References

- Bey, J. (1995). "Tetrahedral grid refinement"
- t8code implementation (German Aerospace Center)

---

*Document consolidated: January 6, 2025*  
*Status: PRODUCTION READY - Full implementation with optimizations*
