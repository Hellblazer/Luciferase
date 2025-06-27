# Comparison: Java Tet.tmIndex() vs t8code t8_dtet_linear_id

## Overview

This document compares the Java implementation of `Tet.tmIndex()` with the t8code reference implementation `t8_dtet_linear_id`. Both functions serve the same purpose: generating a globally unique identifier for tetrahedra across all refinement levels in a hierarchical spatial decomposition.

## Function Signatures

### Java (Tet.java)
```java
public TetreeKey tmIndex() {
    // Returns TetreeKey containing level and BigInteger index
}
```

### t8code (t8_dtet_bits.h)
```c
t8_linearidx_t t8_dtet_linear_id (const t8_dtet_t * t, int level);
// Returns linear position on a grid of given level
// Note: "This id is not the Morton index"
```

## Algorithm Comparison

### Core Algorithm (Both Implementations)

1. **Parent Chain Walking**: Both walk from the current element up to the root
2. **Hierarchical Encoding**: Both encode the complete path from root to element
3. **O(level) Complexity**: Both have linear time complexity relative to depth

### Java Implementation Details

```java
// Walk up the parent chain
while (current.l() > 1) {
    current = current.parent();
    if (current != null) {
        ancestorTypes.addFirst(current.type());
    }
}

// Build index by interleaving bits
for (int bit = 0; bit < maxBits; bit++) {
    // Extract coordinate bits and add type information
    int childIndex = computeChildIndex(xBit, yBit, zBit);
    result = result.shiftLeft(4);
    result = result.or(BigInteger.valueOf(bits));
}
```

### t8code Implementation Details

```c
// Walk up from level to root
for (i = level; i > 0; i--) {
    cid = compute_cubeid (t, i);
    id |= ((t8_linearidx_t) t8_dtri_type_cid_to_Iloc[type_temp][cid]) << exponent;
    exponent += T8_DTRI_DIM;  // multiply with 8 for 3D
    type_temp = t8_dtri_cid_type_to_parenttype[cid][type_temp];
}
```

## Key Similarities

1. **Global Uniqueness**: Both generate unique IDs across all refinement levels
2. **Parent Chain Dependency**: Both require walking the parent chain
3. **Type Information**: Both incorporate tetrahedral type in the encoding
4. **Not Simple Morton**: Neither uses simple Morton encoding (as noted in t8code docs)

## Key Differences

### 1. Data Types
- **Java**: Returns `TetreeKey` with level and `BigInteger` for arbitrary precision
- **t8code**: Returns `t8_linearidx_t` (likely 64-bit integer)

### 2. Caching Strategy
- **Java**: Extensive caching with `TetreeLevelCache` for:
  - Cached TetreeKey results
  - Cached parent chains
  - Type transition caching
- **t8code**: No visible caching in the examined implementation

### 3. Bit Manipulation
- **Java**: Uses `BigInteger` operations with explicit bit shifting
- **t8code**: Uses native integer operations with lookup tables

### 4. Implementation Complexity
- **Java**: More complex due to caching layers and BigInteger operations
- **t8code**: Simpler direct implementation with lookup tables

## Performance Implications

### Fundamental O(level) Cost
Both implementations share the same fundamental performance characteristic:
- Must walk parent chain from current level to root
- Cannot be optimized to O(1) while maintaining global uniqueness
- Cost increases linearly with tree depth

### Java Performance Reality (from CLAUDE.md)
- At level 1: tmIndex() is 3.4x slower than consecutiveIndex()
- At level 20: tmIndex() is ~140x slower than consecutiveIndex()
- Insertion: 483 μs/entity (vs 1.3 μs for Octree)
- This matches the expected O(level) behavior

### Why This Cannot Be Fixed
The parent chain walk is intrinsic to the algorithm because:
1. Global uniqueness requires encoding the complete path
2. Parent types cannot be determined without walking up
3. Each level's type affects the final index value

## Conclusion

The Java `Tet.tmIndex()` implementation correctly follows the same algorithmic approach as t8code's `t8_dtet_linear_id`. The O(level) performance characteristic is not a bug or implementation issue, but rather a fundamental requirement of generating globally unique identifiers for elements in a hierarchical tetrahedral decomposition. The performance difference between Tetree and Octree insertions (372x slower) is primarily due to this algorithmic difference, where Octree can use simple O(1) Morton encoding while Tetree requires O(level) parent chain walking.