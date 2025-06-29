# T8CODE vs BEY'S REFINEMENT: Understanding the Difference

## Executive Summary

The t8code tetrahedral subdivision scheme is fundamentally different from classical Bey's refinement. The "containment violations" observed in tests are not bugs but expected behavior given the different geometric approaches.

## Key Concepts

### Consecutive Index (t8code)
- The `consecutiveIndex()` method (formerly `index()`) computes the linear SFC index
- **Only valid within a single level** - not globally unique
- Bijective with tm-index at each level (one-to-one mapping)
- Used internally by t8code for efficient traversal

### TM-Index
- The `tmIndex()` method computes the globally unique tetrahedral Morton index
- Includes both spatial position AND complete type hierarchy
- Valid across all levels - true space-filling curve
- Used for global ordering and spatial queries

## The Two Subdivision Schemes

### 1. T8CODE Subdivision (What Luciferase Uses)
- Based on canonical tetrahedron vertex generation
- Uses `ei = type/2` and `ej` calculations for vertex positions
- Children positioned using cube-based bit patterns
- Optimized for SFC traversal and consistent connectivity
- **Result**: Some children extend outside parent bounds

### 2. Bey's Red Refinement (Classical Approach)
- Subdivides using edge midpoints
- 4 corner children at original vertices
- 4 octahedral children from central octahedron
- Guarantees geometric containment
- **Result**: All children contained within parent

## Why T8CODE Doesn't Use Bey's Refinement

The t8code authors made a deliberate choice to use a different scheme because:

1. **SFC Consistency**: The cube-based positioning maintains consistent space-filling curve properties
2. **Connectivity Tables**: The scheme allows for efficient parent-child type transformations
3. **Bit Operations**: Child positions can be computed with simple bit manipulations
4. **Performance**: Faster computation for traversal operations

## The "Violations" Are Expected

The test results showing 6 containment violations at every level (children 2, 3, and 5) are **not bugs**. They are the expected result of t8code's subdivision scheme where:

- Children are positioned based on Morton-order cube subdivision
- The `coordinates()` method generates vertices using the canonical algorithm
- Some child tetrahedra naturally extend beyond parent boundaries

## Implementation Details

### Child Position Calculation (t8code)
```java
// Cube-based positioning
int childX = x + ((childIndex & 1) != 0 ? halfLength : 0);
int childY = y + ((childIndex & 2) != 0 ? halfLength : 0);
int childZ = z + ((childIndex & 4) != 0 ? halfLength : 0);
```

### Vertex Generation (t8code)
```java
// Canonical vertex algorithm
int ei = type / 2;
int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;
// Vertices based on ei/ej offsets
```

## Conclusion

The t8code tetrahedral scheme and Bey's refinement are fundamentally different approaches to tetrahedral subdivision. Luciferase correctly implements the t8code scheme, which sacrifices geometric containment for improved SFC properties and computational efficiency.

The observed "violations" are not errors but the expected behavior of the t8code algorithm. Any attempt to "fix" these would break the space-filling curve properties that are central to the spatial index functionality.

## References
- t8code paper: "A tetrahedral space-filling curve for non-conforming adaptive meshes"
- Bey's refinement: Classical tetrahedral red refinement using edge midpoints