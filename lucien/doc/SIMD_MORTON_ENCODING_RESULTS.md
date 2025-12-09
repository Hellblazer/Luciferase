# SIMD Morton Encoding Performance Results

**Epic 1, Bead 1.3: Performance Benchmarking**  
**Date**: 2025-12-08  
**Platform**: ARM NEON (128-bit), Apple Silicon  
**Status**: ❌ Did not achieve target speedup

## Executive Summary

SIMD acceleration for Morton encoding **did not achieve the target 2-4x speedup**. The scalar magic bits implementation is already highly optimized, and SIMD overhead makes it slower rather than faster.

**Key Finding**: Not all algorithms benefit from SIMD. Morton encoding using magic bits is so efficient that vectorization overhead outweighs any parallelism benefits.

## Performance Results

### Single Encoding
- **Scalar**: 62.66 M ops/sec (15.96 ns/op)
- **SIMD**: 5.22 M ops/sec (191.74 ns/op)
- **Speedup**: **0.08x** (12x slower)

### Batch Encoding (10,000 coordinates)
- **Scalar Batch**: 544.01 M ops/sec
- **SIMD Batch**: 200.31 M ops/sec
- **Speedup**: **0.37x** (2.7x slower)

### Target
- **Expected**: 2-4x speedup
- **Actual**: 0.37x (2.7x slowdown)
- **Status**: ❌ FAILED

## Root Cause Analysis

### Why SIMD Is Slower

1. **Magic Bits Algorithm is Already Optimal**
   - Scalar implementation uses highly optimized bit manipulation
   - 6 steps of mask + shift operations
   - Compiler can optimize this to minimal instructions
   - Already runs at 62.66 M ops/sec

2. **SIMD Overhead Exceeds Benefits**
   - Vector broadcasts for each input
   - Multiple vector operations per encoding
   - Type conversions (IntVector → LongVector)
   - No data reuse between operations
   - Each operation is independent (no batching benefit)

3. **Memory Bandwidth Not the Bottleneck**
   - Morton encoding is compute-bound
   - The computation is so fast that SIMD setup overhead dominates
   - ARM NEON 128-bit vectors only give 4-way parallelism (int) or 2-way (long)
   - Not enough parallelism to overcome overhead

### Comparison to Baseline

From `PERFORMANCE_METRICS_MASTER.md`:
- **Baseline (June 2025)**: ~524 M ops/sec
- **Current Scalar**: 62.66 M ops/sec

**Note**: The baseline was measured differently (likely in a tight loop without method calls). Our scalar measurement of 62.66 M ops/sec is consistent with real-world usage including method call overhead.

## Technical Details

### Implementation Approach

**SIMD Morton Encoder** (`SIMDMortonEncoder.java`):
- Uses Java Vector API (jdk.incubator.vector)
- IntVector for coordinate loading
- LongVector for 64-bit Morton code computation
- Implements magic bits algorithm in SIMD
- Graceful fallback to scalar when unavailable

**Integration Points**:
- `Constants.calculateMortonIndex()` uses SIMD encoder
- Transparent to all calling code
- Runtime CPU detection
- Automatic fallback

### Why Magic Bits Doesn't Vectorize Well

The scalar magic bits algorithm:
```java
private static long splitBy3(long a) {
    long x = a & 0x1fffff;
    x = (x | x << 32) & 0x1f00000000ffff;
    x = (x | x << 16) & 0x1f0000ff0000ff;
    x = (x | x << 8)  & 0x100f00f00f00f00f;
    x = (x | x << 4)  & 0x10c30c30c30c30c3;
    x = (x | x << 2)  & 0x1249249249249249;
    return x;
}
```

This is:
- **6 operations total** (mask, shift-or, mask, shift-or, ...)
- **Highly pipelined** by modern CPUs
- **No data dependencies** between coordinates
- **Fits in registers** (no memory access)

SIMD version requires:
- **Vector broadcasts** (3x per coordinate)
- **Vector conversions** (IntVector → LongVector)
- **Same 6 operations** but on vectors
- **Extract result** from lane 0
- **Higher latency** per operation

The serial dependency chain in the algorithm means we can't parallelize across the 6 steps. We can only parallelize across multiple coordinates, but the overhead of setting up vectors exceeds the benefit.

## Recommendations

### 1. Keep Scalar Implementation

**Recommendation**: Revert Epic 1 changes and keep the scalar magic bits implementation.

**Rationale**:
- Scalar is 2.7x faster than SIMD
- Already well-optimized
- Zero overhead
- Works on all platforms

### 2. Alternative SIMD Opportunities

Better candidates for SIMD acceleration in Luciferase:

1. **Ray-AABB Intersection**
   - Multiple intersection tests per ray
   - True data parallelism
   - Higher computation per operation

2. **k-NN Distance Calculations**
   - Euclidean distance for many candidates
   - SIMD-friendly (sqrt, multiply, add)
   - Batch processing natural fit

3. **Frustum Culling**
   - Test many entities against frustum planes
   - Vector dot products
   - True SIMD workload

4. **Collision Detection Broad Phase**
   - AABB overlap tests for many pairs
   - Simple comparisons (good for SIMD)
   - High volume of work

### 3. Lessons Learned

**When SIMD Works**:
✅ High compute-to-setup ratio  
✅ Data parallel operations  
✅ Memory-bound workloads  
✅ Regular access patterns  

**When SIMD Doesn't Work**:
❌ Already optimal scalar code  
❌ Low compute-to-setup ratio  
❌ Irregular data access  
❌ Serial dependencies  

## Conclusion

SIMD acceleration for Morton encoding is **not beneficial** on ARM NEON. The scalar magic bits implementation is already highly optimized and should be retained.

**Status**: Epic 1 objective not achieved. Recommend closing Epic 1 and redirecting SIMD efforts to more suitable algorithms (ray intersection, k-NN, frustum culling).

## Files Created

1. `SIMDMortonEncoder.java` - SIMD implementation (can be removed)
2. `SIMDMortonEncoderTest.java` - Unit tests (can be removed)
3. `SIMDMortonIntegrationTest.java` - Integration tests (can be removed)
4. `SIMDMortonPerformanceTest.java` - Performance benchmark (retain for documentation)
5. `MortonEncodingSIMDBenchmark.java` - JMH benchmark (can be removed)

## Production Decision (Bead 1.4)

**Decision**: Revert SIMD integration from production code.

**Actions Taken**:
1. ✅ Reverted `Constants.calculateMortonIndex()` to use `MortonCurve.encode()` (scalar)
2. ✅ Retained SIMD implementation files for documentation/research
3. ✅ Retained performance test files showing negative results
4. ✅ Updated `.gitignore` to exclude large `*.dataset` files from repository

**Rationale**: SIMD implementation is 2.7x slower than scalar. No reason to keep it in production path.

**Code Status**:
- `SIMDMortonEncoder.java` - Retained for reference
- `VectorAPISupport.java` - Retained (may be useful for future SIMD work)
- Integration tests - Retained as documentation
- Performance tests - Retained to preserve benchmark data
- Production code - **REVERTED** to scalar implementation

## Next Steps

1. ✅ Document findings in this file
2. ✅ Close Epic 1 beads with findings (Bead 1.4 complete)
3. Consider Epic 2 focusing on ray intersection SIMD (better SIMD candidate)
4. Investigate tetrahedral SFC encoding SIMD potential (similar concerns likely apply)
