# TM-Index Fix Summary

## Problem
The `entitiesInRegion` spatial range queries were returning 0 entities due to incorrect TM-index implementation.

## Root Causes

1. **Misunderstanding of Index Types**:
   - `index()`: Consecutive index, unique only within a level
   - `tmIndex()`: Should be globally unique Tetrahedral Morton index (space-filling curve)

2. **Incorrect Implementation**:
   - The original `tmIndex()` was using a path-based encoding
   - The `tetrahedron(BigInteger)` decoder was using bit-interleaving
   - These were incompatible approaches

3. **Missing Global Uniqueness**:
   - TM-index must encode both spatial position AND type hierarchy
   - Without ancestor types, the index wasn't globally unique

## Solution

### 1. Fixed `tmIndex()` Method
- Now properly interleaves coordinate bits with type information
- Includes complete ancestor type hierarchy for global uniqueness
- Based on the TMIndexSimple reference implementation
- Creates 6-bit patterns per level (3 coord bits + 3 type bits)

### 2. Fixed `tetrahedron(BigInteger, byte)` Decoder
- Properly extracts interleaved coordinate and type bits
- Reconstructs coordinates from binary representation
- Matches the encoding scheme exactly

### 3. Added Edge Intersection Tests
- Enhanced `tetrahedronIntersectsVolumeBounds` with line-AABB tests
- Checks tetrahedron edges against bounding box
- Reduces false negatives for spatial queries

## Results

- All spatial range tests now pass
- Round-trip conversion works correctly
- `entitiesInRegion` properly returns entities at all levels
- The TM-index is now truly a globally unique space-filling curve

## Key Insights

1. **TM-index vs Consecutive Index**: The TM-index must be globally unique across all levels, while the consecutive index is only unique within a level.

2. **Bit Interleaving**: The TM-index uses a Morton-like bit interleaving scheme that preserves spatial locality while encoding the complete type hierarchy.

3. **Many-to-One Mapping**: Due to the tetrahedral SFC properties, multiple coordinate representations may map to the same canonical form, but the TM-index handles this correctly.

## Performance Note

The current implementation shows that for small node counts (~1000), brute force iteration is faster than the optimized spatial range query. The optimization becomes beneficial with larger datasets or when implementing the full tetrahedral SFC range computation (currently simplified to avoid memory issues).