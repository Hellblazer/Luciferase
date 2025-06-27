# Standard Refinement in Tetree

## Overview

Standard refinement in the Tetree implementation refers to the hierarchical subdivision of tetrahedra following **Bey's tetrahedral refinement scheme**. This is a specific, deterministic way of subdividing tetrahedra that maintains certain geometric and topological properties.

## Key Concepts

### 1. Root Tetrahedron
- The spatial decomposition starts with a single **root tetrahedron** at level 0
- This root tetrahedron MUST be of **type 0**
- It covers the entire positive octant of 3D space
- Coordinates: (0, 0, 0) with the maximum spatial extent

### 2. Bey's Refinement Method
Each tetrahedron is subdivided into exactly 8 child tetrahedra by:
1. Adding a vertex at the midpoint of each edge
2. Connecting these midpoints to form 8 smaller tetrahedra
3. 4 children are at the corners (similar shape to parent)
4. 4 children are in the interior (inverted orientation)

### 3. Type System
The Tetree uses 6 tetrahedral types (0-5) that represent different orientations:
- These types tile together to fill a cubic cell
- Each type has a specific transformation rule for its children
- The type of a child depends on:
  - The parent's type
  - Which child position (0-7) it occupies

### 4. Type Transformation Rules

The `CHILD_TYPES` table defines how types transform during refinement:

```java
static final int[][] CHILD_TYPES = { 
    { 0, 0, 0, 0, 4, 5, 2, 1 },  // Parent type 0
    { 1, 1, 1, 1, 3, 2, 5, 0 },  // Parent type 1
    { 2, 2, 2, 2, 0, 1, 4, 3 },  // Parent type 2
    { 3, 3, 3, 3, 5, 4, 1, 2 },  // Parent type 3
    { 4, 4, 4, 4, 2, 3, 0, 5 },  // Parent type 4
    { 5, 5, 5, 5, 1, 0, 3, 4 }   // Parent type 5
};
```

For example:
- A type 0 parent's child at position 0 is type 0
- A type 0 parent's child at position 4 is type 4
- A type 0 parent's child at position 7 is type 1

### 5. Coordinate-Based Child Determination

Given a tetrahedron's coordinates at a specific level, the refinement path from root can be reconstructed:

1. Start at root (type 0)
2. For each level from 0 to target level-1:
   - Extract the bit at position (21 - 1 - level) from x, y, z coordinates
   - Combine these bits: `childIdx = (zBit << 2) | (yBit << 1) | xBit`
   - This gives the child index (0-7) at that level
   - Look up the new type: `newType = CHILD_TYPES[currentType][childIdx]`

### 6. The valid() Method

The `valid()` method checks if a tetrahedron could have been produced by standard refinement:

```java
public boolean valid() {
    // Root must be type 0
    if (l == 0) {
        return type == 0;
    }
    
    // For non-root, compute expected type from coordinates
    int expectedType = computeExpectedType();
    return type == expectedType;
}
```

This ensures that:
- The tetrahedron follows the exact refinement path from a type 0 root
- The type matches what would be computed from its coordinates
- The tetrahedron is part of the "standard" hierarchy

## Why Standard Refinement Matters

1. **Consistency**: Ensures all tetrahedra in the tree follow the same subdivision rules
2. **Predictability**: Given coordinates and level, the type is deterministic
3. **Spatial Locality**: The refinement preserves spatial relationships
4. **Compatibility**: Matches the t8code library's tetrahedral mesh refinement

## Contrast with Other Methods

### Freudenthal Decomposition
- Used in `locateFreudenthal()` method
- Divides a cubic cell into 6 tetrahedra based on geometric planes
- May assign different types than standard refinement
- Used for point location, not hierarchical refinement

### Direct Child Creation
- The `child()` method uses Bey vertex midpoint algorithm
- Follows t8code's connectivity tables
- May produce different type assignments than standard refinement
- This is why test data created via `child()` may fail `valid()` checks

## Example

Starting from root (0,0,0, level 0, type 0):
- Child 5 is at coordinates (1048576, 0, 1048576) at level 1
- According to standard refinement: 
  - Bits extracted: x=1, y=0, z=1 → childIdx = 5
  - Type transformation: CHILD_TYPES[0][5] = 5
  - So it should be type 5
- But `child(5)` method might return type 1 (due to different connectivity rules)
- And `locateFreudenthal(1048576, 0, 1048576, 1)` returns type 0

This illustrates why "valid" according to standard refinement is a specific concept that may differ from other type assignment methods in the codebase.