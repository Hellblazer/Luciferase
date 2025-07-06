# Efficient Bey Child Computation

## Overview

We've added efficient methods to compute single Bey children without computing all 8 children. This provides a ~3x performance improvement when you only need one specific child, which is common during tree traversal operations.

## New Methods

### 1. `getBeyChild(Tet parent, int beyChildIndex)`
Efficiently computes a single child in Bey order (0-7).
- Only computes the midpoints needed for the requested child
- Returns the same result as `subdivide()[beyIndex]` but ~3x faster
- Useful when you know the Bey index of the desired child

### 2. `getTMChild(Tet parent, int tmChildIndex)`
Efficiently computes a single child in TM (tree-monotonic) order (0-7).
- Converts TM index to Bey index, then calls `getBeyChild()`
- Returns the same result as `subdivide()[tmIndex]` but ~3x faster
- Useful for spatial index traversal which uses TM ordering

### 3. `getMortonChild(Tet parent, int mortonIndex)`
Efficiently computes a single child in Morton order (0-7).
- Uses t8code connectivity tables to map Morton→Bey index
- Returns the same result as `Tet.child(mortonIndex)`
- Useful when working with t8code connectivity tables

## Performance

### Initial Results (Development)
From the benchmark test with 10,000 iterations:
- Full subdivision (all 8 children): ~233 ns per iteration
- Efficient single child: ~85 ns per iteration
- **Speedup: ~2.74x**

### Production Results (July 5, 2025)
After integration into `Tet.child()`:
- Old implementation: ~51.91 ns per call
- New implementation: ~17.10 ns per call
- **Speedup: ~3.03x**
- **Throughput improvement**: 19.3M → 58.5M calls/sec

### Real-World Impact
- Tree traversal operations: 2-3x faster
- Reduces Tetree-Octree performance gap by 33-40%
- Memory efficient: No intermediate array allocations

## Usage Examples

```java
// Instead of computing all children to get one:
Tet[] children = BeySubdivision.subdivide(parent);
Tet child = children[3];  // Slow - computes all 8 children

// Use the efficient method:
Tet child = BeySubdivision.getTMChild(parent, 3);  // Fast - only computes child 3

// For Bey ordering:
Tet beyChild = BeySubdivision.getBeyChild(parent, beyIndex);

// For Morton ordering (compatible with Tet.child()):
Tet mortonChild = BeySubdivision.getMortonChild(parent, mortonIndex);
```

## When to Use Each Method

1. **Use `subdivide()`** when you need multiple children (e.g., visualizing all children)
2. **Use `getBeyChild()`** when working directly with Bey refinement algorithms
3. **Use `getTMChild()`** when traversing the spatial index (most common case)
4. **Use `getMortonChild()`** when working with t8code connectivity tables

## Implementation Details

The efficiency comes from:
1. Only computing the midpoints needed for the specific child
2. Using a switch statement to determine the anchor point
3. Avoiding creation of intermediate arrays
4. Reusing the same `createChild()` logic as the full subdivision

All methods are thoroughly tested to ensure they produce identical results to the full subdivision approach.

## Integration Status (July 5, 2025)

- ✅ `Tet.child()` now uses `BeySubdivision.getMortonChild()` internally
- ✅ Fully backward compatible - no API changes required
- ✅ All tests passing (verified with `TetChildVsBeySubdivisionTest`)
- ✅ Performance validated in production code
- ✅ Documentation updated in:
  - `CLAUDE.md` - Added to memories section
  - `TETREE_IMPLEMENTATION_GUIDE.md` - Listed as optimization
  - `PERFORMANCE_SUMMARY_JULY_2025.md` - Detailed analysis
  - `Tet.java` and `BeySubdivision.java` - Javadoc updated