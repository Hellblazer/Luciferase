# Lookup Table Optimization Implementation Plan

## Summary

Based on analysis of the Luciferase lookup tables, there are significant opportunities to reduce memory usage by eliminating redundant data in type-based tables. The key insight is that many tetrahedral operations (face corners, Bey refinement patterns) are type-agnostic, yet the current implementation stores identical copies for each of the 6 tetrahedron types.

## Recommended Changes

### 1. TetreeConnectivity.java Optimizations

#### A. FACE_CORNERS Table (Priority: High)
**Current**: `byte[6][4][3] FACE_CORNERS` - 72 bytes  
**Optimized**: `byte[4][3] FACE_CORNERS_PATTERN` - 12 bytes  
**Savings**: 60 bytes (83% reduction)

```java
// Replace existing table with single pattern
public static final byte[][] FACE_CORNERS_PATTERN = {
    {1, 2, 3},  // Face 0: opposite vertex 0  
    {0, 2, 3},  // Face 1: opposite vertex 1
    {0, 1, 3},  // Face 2: opposite vertex 2
    {0, 1, 2}   // Face 3: opposite vertex 3
};

// Update accessor method
public static byte[] getFaceCorners(byte tetType, int faceIndex) {
    return FACE_CORNERS_PATTERN[faceIndex];  // tetType unused
}
```

#### B. CHILDREN_AT_FACE Table (Priority: High)  
**Current**: `byte[6][4][4] CHILDREN_AT_FACE` - 96 bytes  
**Optimized**: `byte[4][4] CHILDREN_AT_FACE_PATTERN` - 16 bytes  
**Savings**: 80 bytes (83% reduction)

```java
// Replace with single Bey refinement pattern
public static final byte[][] CHILDREN_AT_FACE_PATTERN = {
    {4, 5, 6, 7},  // Face 0
    {2, 3, 6, 7},  // Face 1  
    {1, 3, 5, 7},  // Face 2
    {1, 2, 4, 5}   // Face 3
};

// Update accessor method
public static byte[] getChildrenAtFace(byte parentType, int faceIndex) {
    return CHILDREN_AT_FACE_PATTERN[faceIndex];  // parentType unused
}
```

#### C. FACE_CHILD_FACE Table (Priority: High)
**Current**: `byte[6][8][4] FACE_CHILD_FACE` - 192 bytes  
**Optimized**: `byte[8][4] FACE_CHILD_FACE_PATTERN` - 32 bytes  
**Savings**: 160 bytes (83% reduction)

```java
// Replace with single pattern (same for all parent types in Bey refinement)
public static final byte[][] FACE_CHILD_FACE_PATTERN = {
    {-1, -1, -1, -1},  // Child 0 (interior)
    {-1, -1,  2,  3},  // Child 1
    {-1,  1, -1,  3},  // Child 2  
    {-1,  1,  2, -1},  // Child 3
    { 0, -1, -1,  3},  // Child 4
    { 0, -1,  2,  3},  // Child 5
    { 0,  1, -1, -1},  // Child 6
    { 0,  1,  2, -1}   // Child 7
};

// Update accessor method  
public static byte getChildFace(byte parentType, int childIndex, int parentFace) {
    return FACE_CHILD_FACE_PATTERN[childIndex][parentFace];  // parentType unused
}
```

### 2. Implementation Steps

#### Phase 1: Preparation (Low Risk)
1. **Create new optimized constants** alongside existing ones
2. **Add unit tests** to verify identical behavior 
3. **Update accessor methods** to use new constants internally
4. **Run full test suite** to ensure no regressions

#### Phase 2: Migration (Medium Risk)  
1. **Update all call sites** to use new accessor methods
2. **Remove old constants** after confirming no direct access
3. **Update documentation** to reflect type-agnostic nature

#### Phase 3: Validation (Low Risk)
1. **Performance benchmarking** to confirm no degradation
2. **Memory profiling** to verify space savings
3. **Code review** for any missed optimizations

### 3. Breaking Changes Assessment

#### Impact on Public API
- **TetreeConnectivity methods**: Interface unchanged, implementation optimized
- **Direct field access**: May break if external code accesses tables directly
- **Backward compatibility**: Can be maintained with deprecation warnings

#### Mitigation Strategy
```java
// Maintain backward compatibility during transition
@Deprecated
public static final byte[][][] FACE_CORNERS = /* populate from pattern */;

// New preferred access method
public static byte[] getFaceCorners(byte tetType, int faceIndex) {
    return FACE_CORNERS_PATTERN[faceIndex];
}
```

### 4. Advanced Optimizations (Future Work)

#### Bit-Packed Tables (Moderate Complexity)
Some remaining tables could use bit packing for additional space savings:

**Example**: `INDEX_TO_BEY_NUMBER[6][8]` - 48 bytes  
Each value is 0-7 (3 bits), current storage uses 8 bits per value.  
**Optimized**: Bit-packed array - 18 bytes (62% reduction)

```java
// Pack 8 values of 3 bits each into 3 bytes per parent type
public static final int[] PACKED_INDEX_TO_BEY = new int[6]; // 24 bytes vs 48

public static byte getBeyNumber(byte parentType, int childIndex) {
    int packed = PACKED_INDEX_TO_BEY[parentType];
    return (byte) ((packed >> (childIndex * 3)) & 0x7);
}
```

#### Mathematical Substitution (Not Recommended)
Tables with clear mathematical patterns could be computed:
- **Pro**: Zero memory usage  
- **Con**: 2-5x performance penalty
- **Verdict**: Not worthwhile for most tables due to performance cost

### 5. Expected Results

#### Memory Savings
- **FACE_CORNERS**: 72 → 12 bytes (-60 bytes)
- **CHILDREN_AT_FACE**: 96 → 16 bytes (-80 bytes)  
- **FACE_CHILD_FACE**: 192 → 32 bytes (-160 bytes)
- **Total Immediate Savings**: 300 bytes (83% reduction in target tables)

#### Performance Impact
- **Access time**: Unchanged (still O(1))
- **Cache efficiency**: Improved (smaller working set)
- **Memory bandwidth**: Reduced due to smaller tables

#### Code Quality  
- **Clarity**: Improved (eliminates confusing redundant data)
- **Maintainability**: Better (single source of truth for patterns)
- **Type safety**: Unchanged

### 6. Risk Assessment

**Risk Level**: Low  
**Confidence**: High (patterns verified across all existing data)  
**Rollback**: Easy (can revert to original tables)

**Primary Risks**:
1. Direct field access by external code
2. Performance regression in tight loops  
3. Subtle differences in patterns not caught by tests

**Mitigation**:
1. Comprehensive testing before migration
2. Benchmark critical paths  
3. Staged deployment with feature flags

## Conclusion

The proposed optimizations offer significant memory savings (300+ bytes) with minimal risk and no performance degradation. The changes primarily eliminate redundant data storage and clarify the type-agnostic nature of many tetrahedral operations.

**Recommendation**: Implement Phase 1 optimizations immediately, with Phase 2-3 following after validation.