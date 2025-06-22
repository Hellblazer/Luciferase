# Tet SFC Index Non-Uniqueness Analysis

**Date**: 2025-06-21  
**Status**: CRITICAL ARCHITECTURAL ISSUE  
**Impact**: Spatial index implementation

## Executive Summary

The Tet SFC index is **NOT** unique across all levels. The same index value can represent different tetrahedra at different levels, which has critical implications for the spatial index implementation.

## Key Findings

### 1. Index Ambiguity
- **Index 0** exists at ALL levels (0-21), representing different-sized tetrahedra
- **Index 1-7** exist at level 1 and can also exist at higher levels
- The same index represents different spatial regions at different levels

### 2. No Level Encoding
Unlike Morton codes which can encode level information through bit offsets, the Tet SFC index does NOT encode level information. The `index()` method in Tet.java (lines 772-809) computes indices by encoding the path from root with NO level offset.

### 3. T8code Consistency
This is consistent with the t8code reference implementation, which ALWAYS requires both index and level to uniquely identify a tetrahedron. The t8code never uses index alone as a unique identifier.

## Implications for Spatial Index

The current `AbstractSpatialIndex` uses `Map<Long, NodeType>` where the key is just the SFC index. This means:

1. **Collision Risk**: Entities at different levels with the same SFC index would collide
2. **Data Loss**: Later insertions would overwrite earlier ones
3. **Incorrect Queries**: Spatial queries might return wrong results

## Example

```java
// These are DIFFERENT tetrahedra with the SAME index
Tet tet1 = Tet.tetrahedron(7, (byte)1);   // Large tet at level 1
Tet tet2 = Tet.tetrahedron(7, (byte)10);  // Small tet at level 10

// In current spatial index:
spatialIndex.put(tet1.index(), node1);  // Stores with key 7
spatialIndex.put(tet2.index(), node2);  // OVERWRITES node1!
```

## Solutions

### ✅ Implemented Solution: TetreeSpatialKey

We've implemented a level encoding solution that ensures uniqueness:

```java
// Encode both index and level into a single long
public class TetreeSpatialKey {
    // Bits [58-0]: SFC index (59 bits)
    // Bits [63-59]: Level (5 bits, range 0-31)
    
    public static long encode(long sfcIndex, byte level);
    public static long extractIndex(long spatialKey);
    public static byte extractLevel(long spatialKey);
}
```

Usage in Tetree:
```java
// Old (collision risk):
protected long calculateSpatialIndex(Point3f position, byte level) {
    Tet tet = locate(position, level);
    return tet.index();  // NOT unique across levels!
}

// New (collision-free):
protected long calculateSpatialIndex(Point3f position, byte level) {
    Tet tet = locate(position, level);
    return TetreeSpatialKey.encode(tet.index(), level);  // Unique!
}
```

### Other Options Considered

1. **Composite Key**: Would require changing Map<Long, Node> to Map<SpatialKey, Node>
2. **Fixed Level**: Too restrictive for general use
3. **Level-Aware Node Storage**: Would complicate the implementation

## Implementation Status

✅ **Completed**:
- Created `TetreeSpatialKey` utility class
- Updated `Tetree.calculateSpatialIndex()` to encode level
- Updated `Tetree.getLevelFromIndex()` to decode from spatial key
- Updated neighbor-finding methods to use spatial keys
- Created comprehensive test suite

🔄 **In Progress**:
- Updating remaining methods in Tetree to decode spatial keys
- Migrating AbstractSpatialIndex usage patterns

## Test Evidence

The `TetIndexUniquenessTest` demonstrates:
- Index 0 exists at all 22 levels
- Children indices (1-7) exist at multiple levels
- Same index at different levels represents different spatial regions

## Conclusion

The Tet SFC index alone is NOT sufficient as a unique key in spatial data structures. Level information MUST be included to ensure uniqueness. This is a fundamental property of the tetrahedral space-filling curve and matches the t8code implementation.