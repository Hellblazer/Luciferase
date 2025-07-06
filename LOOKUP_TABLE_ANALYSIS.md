# Lookup Table Space Optimization Analysis

## Executive Summary

The Luciferase codebase contains several 2D lookup tables that could potentially be optimized to 1D arrays using mathematical functions. While the space savings would be modest (typically 50-75% of original size), the performance trade-offs vary significantly depending on the access patterns and computational complexity of the mapping functions.

## Current Lookup Tables Analysis

### 1. **TetreeConnectivity Tables** (Most Significant)

#### `PARENT_TYPE_TO_CHILD_TYPE[6][8]` - 48 bytes
**Current**: 6 parent types × 8 children = 48 bytes
**Optimized**: Single array of 48 bytes with function `index = parentType * 8 + childIndex`
**Space Savings**: 0% (no savings - already optimal)
**Recommendation**: Keep as-is (already optimal)

#### `FACE_CORNERS[6][4][3]` - 72 bytes
**Current**: 6 types × 4 faces × 3 corners = 72 bytes
**Analysis**: All tetrahedron types use identical face corner patterns:
- Face 0: [1,2,3] (opposite vertex 0)
- Face 1: [0,2,3] (opposite vertex 1) 
- Face 2: [0,1,3] (opposite vertex 2)
- Face 3: [0,1,2] (opposite vertex 3)

**Optimized**: Single array of 12 bytes `FACE_CORNERS_PATTERN[4][3]`
```java
public static byte getFaceCorner(byte tetType, int faceIndex, int cornerIndex) {
    return FACE_CORNERS_PATTERN[faceIndex][cornerIndex];
}
```
**Space Savings**: 83% (72 → 12 bytes)
**Performance**: Same (O(1) access)
**Recommendation**: ✅ **IMPLEMENT** - Significant space savings with no performance cost

#### `CHILDREN_AT_FACE[6][4][4]` - 96 bytes
**Current**: 6 types × 4 faces × 4 children = 96 bytes
**Analysis**: All tetrahedron types use identical patterns for Bey refinement:
- Face 0: [4,5,6,7]
- Face 1: [2,3,6,7]
- Face 2: [1,3,5,7]
- Face 3: [1,2,4,5]

**Optimized**: Single array of 16 bytes `CHILDREN_AT_FACE_PATTERN[4][4]`
**Space Savings**: 83% (96 → 16 bytes)
**Performance**: Same (O(1) access)
**Recommendation**: ✅ **IMPLEMENT** - Significant space savings with no performance cost

#### `FACE_CHILD_FACE[6][8][4]` - 192 bytes
**Current**: 6 types × 8 children × 4 faces = 192 bytes
**Analysis**: All tetrahedron types use identical patterns in Bey refinement
**Optimized**: Single array of 32 bytes `FACE_CHILD_FACE_PATTERN[8][4]`
**Space Savings**: 83% (192 → 32 bytes)
**Performance**: Same (O(1) access)
**Recommendation**: ✅ **IMPLEMENT** - Significant space savings with no performance cost

### 2. **Constants.java Tables**

#### `CUBE_ID_TYPE_TO_PARENT_TYPE[8][6]` - 48 bytes
**Current**: 8 cube IDs × 6 types = 48 bytes
**Analysis**: Each cube ID (0-7) represents a 3-bit pattern (x,y,z). Could use bit manipulation.

**Optimized**: Mathematical function using bit operations
```java
public static byte getCubeIdTypeToParentType(byte cubeId, byte type) {
    // Complex bit manipulation based on cube ID pattern
    // Analysis shows non-trivial mathematical relationship
}
```
**Space Savings**: 100% (48 → 0 bytes)
**Performance**: Likely 2-3x slower due to bit operations
**Recommendation**: ❌ **DON'T IMPLEMENT** - Minimal space savings, significant performance cost

#### `TYPE_TO_TYPE_OF_CHILD_MORTON[6][8]` - 48 bytes
**Similar analysis to PARENT_TYPE_TO_CHILD_TYPE**
**Recommendation**: ❌ **DON'T IMPLEMENT** - Already optimal

#### `PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[6][8]` - 48 bytes
**Recommendation**: ❌ **DON'T IMPLEMENT** - Complex pattern, minimal savings

#### `PARENT_TYPE_LOCAL_INDEX_TO_TYPE[6][8]` - 48 bytes
**Recommendation**: ❌ **DON'T IMPLEMENT** - Complex pattern, minimal savings

### 3. **Tet.java Tables**

#### `LOCAL_INDICES[6][8]` - 48 bytes
**Analysis**: Each parent type has different permutation pattern
**Recommendation**: ❌ **DON'T IMPLEMENT** - No clear mathematical pattern

### 4. **Special Cases**

#### `INDEX_TO_BEY_NUMBER[6][8]` - 48 bytes in TetreeConnectivity
**Analysis**: Different permutation for each parent type
**Recommendation**: ❌ **DON'T IMPLEMENT** - Complex permutation patterns

#### `BEY_ID_TO_VERTEX[8]` - 8 bytes
**Already optimal** - single dimension array

## Optimization Recommendations

### High Priority (Implement)
1. **`FACE_CORNERS`** - Reduce from 72 to 12 bytes (83% savings)
2. **`CHILDREN_AT_FACE`** - Reduce from 96 to 16 bytes (83% savings)  
3. **`FACE_CHILD_FACE`** - Reduce from 192 to 32 bytes (83% savings)

### Total Potential Savings
- **Current size**: ~360 bytes in redundant tables
- **Optimized size**: ~60 bytes  
- **Total savings**: 300 bytes (83% reduction in target tables)

## Implementation Strategy

### Phase 1: Eliminate Redundant Type-Based Tables
Many tables repeat identical patterns across all tetrahedron types because Bey refinement is type-agnostic.

```java
// Before: 6 × 4 × 3 = 72 bytes
public static final byte[][][] FACE_CORNERS = { /* 6 identical copies */ };

// After: 4 × 3 = 12 bytes  
public static final byte[][] FACE_CORNERS_PATTERN = {
    {1, 2, 3}, {0, 2, 3}, {0, 1, 3}, {0, 1, 2}
};

public static byte getFaceCorner(byte tetType, int faceIndex, int cornerIndex) {
    return FACE_CORNERS_PATTERN[faceIndex][cornerIndex];
}
```

### Phase 2: Validate Performance Impact
- Run existing benchmarks to ensure no performance regression
- The optimizations should maintain O(1) access time
- Memory access patterns remain cache-friendly

## Conclusion

**Worth Implementing**: Yes, for the redundant type-based tables
**Space Savings**: ~300 bytes (modest but meaningful for embedded use)
**Performance Impact**: None (maintains O(1) access)
**Development Effort**: Low (straightforward refactoring)

The analysis reveals that the most significant opportunity is eliminating redundant copies of identical patterns across tetrahedron types, rather than complex mathematical transformations. This provides substantial space savings while maintaining performance.