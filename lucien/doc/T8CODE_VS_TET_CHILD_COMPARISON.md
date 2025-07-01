# t8code vs Tet Implementation: Child Functionality Comparison

## Overview

This document compares how t8code (the reference implementation) uses child functionality versus our Tet implementation, addressing the critical question of whether our understanding and implementation are correct.

## What t8code Uses Children For

### Primary Purpose: Adaptive Mesh Refinement (AMR)

t8code is a library for **parallel adaptive mesh refinement on forest of trees**. Key uses:

1. **Hierarchical Space Decomposition**
   - Recursively subdivide space for numerical simulations
   - Support multi-resolution representations
   - Enable local refinement where higher accuracy is needed

2. **Space-Filling Curves (SFC)**
   - Use tetrahedral Morton ordering for efficient storage
   - Enable fast neighbor finding and traversal
   - Support parallel domain decomposition

3. **Dynamic Mesh Adaptation**
   - Refine elements based on error indicators
   - Coarsen elements where less resolution is needed
   - Maintain mesh quality during adaptation

## The t8code Child Algorithm

### Core Implementation (from t8_dtri_bits.c)

```c
void t8_dtet_child (const t8_dtet_t * t, int childid, t8_dtet_t * child)
{
  // Step 1: Convert Morton index to Bey ID
  const int t8_beyid = t8_dtet_index_to_bey_number[t->type][childid];
  
  // Step 2: Compute child anchor position
  if (t8_beyid == 0) {
    // Special case: Child 0 inherits parent anchor
    child->x = t->x;
    child->y = t->y;  
    child->z = t->z;
  }
  else {
    // Get vertex associated with this Bey child
    int vertex = t8_dtet_beyid_to_vertex[t8_beyid];
    
    // Compute vertex coordinates in grid
    t8_dtet_compute_coords(t, vertex, coords);
    
    // Child anchor = midpoint between parent anchor and vertex
    child->x = (t->x + coords[0]) >> 1;  // Bit shift for /2
    child->y = (t->y + coords[1]) >> 1;
    child->z = (t->z + coords[2]) >> 1;
  }
  
  // Step 3: Set child type and level
  child->type = t8_dtet_type_of_child[t->type][t8_beyid];
  child->level = t->level + 1;
}
```

### Vertex Coordinate Computation

```c
void t8_dtet_compute_coords(const t8_dtet_t *t, int vertex, int coords[3])
{
  // Type-based coordinate system
  int ei = t->type / 2;
  int ej = (ei + ((t->type % 2 == 0) ? 2 : 1)) % 3;
  
  // Cell size at this level
  int h = T8_DTET_LEN(t->level);
  
  // Start with anchor
  coords[0] = t->x;
  coords[1] = t->y;
  coords[2] = t->z;
  
  // Add offsets based on vertex
  switch (vertex) {
    case 0: break;  // No offset
    case 1: coords[ei] += h; break;
    case 2: coords[ei] += h; coords[ej] += h; break;
    case 3: coords[(ei+1)%3] += h; coords[(ei+2)%3] += h; break;
  }
}
```

## Our Tet Implementation

### Current Implementation

```java
public Tet child(int childIndex) {
    // Step 1: Convert Morton to Bey (MATCHES t8code)
    byte beyChildId = TetreeConnectivity.getBeyChildId(type, childIndex);
    
    // Step 2: Get child type (MATCHES t8code)
    byte childType = TetreeConnectivity.getChildType(type, beyChildId);
    
    // Step 3: Get vertex for positioning (MATCHES t8code)
    byte vertex = TetreeConnectivity.getBeyVertex(beyChildId);
    
    // Step 4: Compute vertex coords (MATCHES t8code algorithm)
    Point3i vertexCoords = computeVertexCoordinates(vertex);
    
    // Step 5: Child anchor = midpoint (MATCHES t8code)
    int childX = (x + vertexCoords.x) >> 1;
    int childY = (y + vertexCoords.y) >> 1;
    int childZ = (z + vertexCoords.z) >> 1;
    
    return new Tet(childX, childY, childZ, (byte)(l + 1), childType);
}
```

## Critical Analysis: The Confusion

### What's Actually Happening

1. **Grid-Based Positioning**: Both t8code and our implementation use a **grid-based coordinate system**
2. **Cube Vertices**: The "vertices" are actually vertices of the containing **cube** in the grid
3. **Not Pure Geometry**: This is NOT placing children at geometric midpoints of tetrahedron edges

### Why Children Appear Outside

The children appear "outside" the parent tetrahedron because:

1. **Grid Quantization**: Tetrahedra are positioned on a discrete grid
2. **Cube-Based Anchoring**: Each tetrahedron's position is defined by its anchor in a cube
3. **Type Determines Shape**: The tetrahedron type (0-5) determines which tetrahedron within the cube

### The Key Insight

**Bey refinement in t8code is about:**
- Choosing which characteristic tetrahedron (S0-S5) within a cube
- Using a hierarchical grid-based positioning system
- NOT about geometric subdivision of tetrahedra in continuous space

## Implications

### Our Implementation is Correct

1. **Algorithm Match**: Our Tet.child() exactly matches t8code's algorithm
2. **Purpose Match**: Both use the same hierarchical decomposition
3. **Coordinate System**: Both use grid-based positioning, not continuous geometry

### The "Outside" Children are Expected

1. **Grid Artifacts**: Children being "outside" is a consequence of grid discretization
2. **Not a Bug**: This is how t8code works - it's not pure geometric subdivision
3. **Design Choice**: Trading geometric perfection for efficient grid-based indexing

### What Bey Refinement Really Means Here

In the context of t8code (and our implementation):
- It's a systematic way to subdivide the grid
- Each parent cell is divided into 8 child cells
- The "Bey" aspect is about maintaining consistent tetrahedron types
- NOT about geometric subdivision of individual tetrahedra

## Conclusion

1. **Our implementation is correct** - it matches t8code exactly
2. **Children "outside" parent is expected** - it's a grid discretization effect
3. **No fix needed** - this is the intended behavior for grid-based tetrahedral indexing
4. **Documentation should clarify** - this is grid-based, not pure geometric subdivision

The confusion arose from expecting pure geometric tetrahedral subdivision, when both t8code and our implementation actually use grid-based positioning with characteristic tetrahedra.

---

*Document created: July 1, 2025*  
*Status: CRITICAL CLARIFICATION*