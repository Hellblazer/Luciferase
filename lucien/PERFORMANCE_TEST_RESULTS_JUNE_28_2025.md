# Performance Test Results - June 28, 2025

## Executive Summary

Performance tests conducted on June 28, 2025 confirm that **Octree significantly outperforms Tetree for insertions** while **Tetree excels at queries**. The performance gap is less extreme than previously reported but still substantial.

**Data Source**: All performance comparisons in this document are from `OctreeVsTetreeBenchmark.java` unless otherwise noted.

## Test Environment

- **Platform**: macOS (Darwin 24.5.0)
- **Architecture**: Apple Silicon (aarch_64)
- **Java Version**: 23
- **Test Framework**: JUnit 5 with custom performance harness
- **Test Control**: `RUN_SPATIAL_INDEX_PERF_TESTS=true`

## Key Findings

### 1. Insertion Performance (Post-Subdivision Fix - June 28, 2025)

**After fixing Tetree subdivision, performance has improved dramatically:**

| Dataset Size | Octree Time | Tetree Time | Octree Advantage | Improvement |
|--------------|-------------|-------------|------------------|-------------|
| 100          | ~0.8 ms     | ~4.8 ms     | **6.0x faster**  | 38% better  |
| 1,000        | ~3.0 ms     | ~27.6 ms    | **9.2x faster**  | 84% better  |
| 10,000       | ~10.5 ms    | ~363 ms     | **34.6x faster** | 96% better  |

**Per-Entity Insertion Time:**
| Dataset Size | Octree      | Tetree      | Previous Tetree | 
|--------------|-------------|-------------|-----------------|
| 100          | ~8 μs       | ~48 μs      | (was 74.9 μs)   |
| 1,000        | ~3 μs       | ~28 μs      | (was 173.4 μs)  |
| 10,000       | ~1 μs       | ~36 μs      | (was 807.6 μs)  |

**Note**: The dramatic improvement is due to fixing Tetree's subdivision logic. Previously, Tetree was creating only 2 nodes for 1000 entities (vs 6,430 for Octree), causing severe performance degradation.

### 2. Query Performance (OctreeVsTetreeBenchmark Results - June 28, 2025)

**Tetree maintains its advantage for spatial queries:**

| Dataset | Operation | Octree  | Tetree  | Tetree Advantage |
|---------|-----------|---------|---------|------------------|
| 100     | k-NN      | 0.93 μs | 0.43 μs | **2.2x faster**  |
| 100     | Range     | 0.47 μs | 0.33 μs | **1.4x faster**  |
| 1,000   | k-NN      | 3.22 μs | 0.81 μs | **4.0x faster**  |
| 1,000   | Range     | 2.00 μs | 0.55 μs | **3.6x faster**  |
| 10,000  | k-NN      | 21.9 μs | 7.04 μs | **3.1x faster**  |
| 10,000  | Range     | 21.4 μs | 5.49 μs | **3.9x faster**  |

### 3. Memory Usage (Post-Subdivision Fix - June 28, 2025)

**After fixing subdivision, memory usage is now comparable between implementations:**

| Dataset Size | Octree Memory | Tetree Memory | Tetree Usage    |
|--------------|---------------|---------------|-----------------|
| 100          | 0.15 MB       | 0.16 MB       | **102.9%**      |
| 1,000        | 1.39 MB       | 1.28 MB       | **92.1%**       |
| 10,000       | 12.91 MB      | 12.64 MB      | **97.9%**       |

**Resolution of Previous Discrepancy**: 
- **Before fix**: Tetree used only 20% of Octree memory because it created 500x fewer nodes (bug)
- **After fix**: Tetree uses 92-103% of Octree memory with proper node distribution
- Both implementations now create appropriate numbers of nodes for spatial partitioning
- Small variations are due to different spatial decomposition strategies (tetrahedral vs cubic)

### 4. Bulk Operation Optimization

**Note**: These results are from specialized bulk operation tests, not OctreeVsTetreeBenchmark.

**Both implementations benefit from bulk operations, but Tetree sees larger gains:**

**Octree Bulk Performance:**
- 1K entities: 1.65x speedup with optimization
- 10K entities: 2.05x speedup with optimization
- 50K entities: 2.64x speedup with optimization

**Tetree Bulk Performance:**
- 1K entities: 16x speedup with optimization
- 10K entities: 10.5x speedup with optimization
- 50K entities: 10.2x speedup with optimization

Even with bulk optimization, Tetree remains 15-50x slower than Octree for insertions.

### 5. Spatial Distribution Impact

**Note**: These results are from spatial distribution-specific tests, not OctreeVsTetreeBenchmark.

**Performance varies significantly based on data distribution:**

**Best Case (DIAGONAL distribution):**
- Octree: 269,181 ops/sec
- Tetree: 87,311 ops/sec (3.1x slower)

**Worst Case (CLUSTERED distribution):**
- Octree: 159,541 ops/sec
- Tetree: 8,110 ops/sec (19.7x slower)

## Root Cause Analysis

The performance difference is fundamental:

1. **Octree**: Uses Morton encoding - O(1) bit interleaving operation
2. **Tetree**: Uses tmIndex() - O(level) parent chain traversal
3. **Memory**: Tetree's complex tetrahedral structure requires ~200x more memory per node

## Recommendations

### Use Octree When:
- **Insertion performance matters** (real-time systems, streaming data)
- **Memory is constrained** (embedded systems, large datasets)
- **Balanced workload** (mix of insertions and queries)
- **Dynamic updates** (moving objects, frequent changes)

### Use Tetree When:
- **Query performance is critical** (spatial databases, GIS)
- **Dataset is static** (load once, query many)
- **Memory is abundant** (server environments)
- **Accuracy matters** (tetrahedral geometry provides better spatial properties)

### Optimization Strategies:
1. **Enable bulk operations** for both implementations when loading data
2. **Use lazy evaluation** for Tetree to defer tmIndex() computation
3. **Pre-sort data** by spatial locality before insertion
4. **Consider hybrid approach**: Build with Octree, convert to Tetree for querying

## Conclusion

The performance tests after fixing Tetree subdivision show:
- **Octree is 6x to 35x faster for insertions** (was 9.7x to 770x before fix)
- **Tetree is 3-4x faster for queries** (unchanged - already excellent)
- **Memory usage is now comparable** (92-103% - was 20% due to subdivision bug)
- **Both benefit from bulk optimization**, with proper tree structures

The choice between Octree and Tetree is now clearer:
- **Choose Octree**: For dynamic insertions, frequent updates, or when insertion speed is critical
- **Choose Tetree**: For static datasets with query-heavy workloads where 3-4x faster queries justify slower insertions
- **Performance gap explained**: The remaining 6-35x insertion performance difference is due to fundamental algorithmic differences (O(1) Morton encoding vs O(level) tmIndex computation), not implementation bugs