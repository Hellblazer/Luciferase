# Spatial Index Performance Reality - June 28, 2025 (Post-Parent Cache)

## Executive Summary

Based on comprehensive benchmarking with OctreeVsTetreeBenchmark after implementing parent cache optimization:
- **Octree is 2.9-7.7x faster for insertions** due to O(1) Morton encoding
- **Tetree is 1.6-3.6x faster for k-NN queries** due to better spatial locality
- **Tetree uses 75-77% less memory** with more efficient spatial decomposition
- **Parent cache provides up to 67x speedup** for deep tree operations

**Data Source**: Performance metrics from OctreeVsTetreeBenchmark.java run on June 28, 2025, Mac OS X aarch64, 16 processors, Java 24.

## The Root Cause

The performance difference stems from a fundamental algorithmic distinction:

### Index Methods Comparison

| Method                     | Returns     | Time Complexity | Globally Unique                 | Used By                 |
|----------------------------|-------------|-----------------|---------------------------------|-------------------------|
| **Octree Morton**          | `long`      | O(1)            | Yes (across all levels)         | Octree                  |
| **Tet.consecutiveIndex()** | `long`      | O(1) with cache | No (unique only within a level) | Nothing (internal only) |
| **Tet.tmIndex()**          | `TetreeKey` | O(level)        | Yes (across all levels)         | Tetree                  |

### Why tmIndex() is Slow

The `tmIndex()` method must walk up the parent chain to build the globally unique index:

- Level 1: 3.4x slower than consecutiveIndex()
- Level 10: 35x slower
- Level 20: 140x slower

This is **not a bug** - it's required for correctness. The TM-index includes ancestor type information for global
uniqueness across all levels.

## Actual Performance Measurements (Post-Parent Cache - June 28, 2025)

### Insertion Performance

| Entity Count | Octree | Tetree | Octree Advantage |
|-------------|---------|---------|------------------|
| 100 | 3.83 μs/entity | 29.47 μs/entity | **7.7x faster** |
| 1,000 | 2.77 μs/entity | 7.94 μs/entity | **2.9x faster** |
| 10,000 | 1.00 μs/entity | 4.79 μs/entity | **4.8x faster** |

**Throughput**: Octree achieves ~770K entities/sec while Tetree manages ~35-210K entities/sec for individual insertions.

### k-Nearest Neighbor (k-NN) Search

| Entity Count | Octree | Tetree | Tetree Advantage |
|-------------|---------|---------|------------------|
| 100 | 0.72 μs | 0.46 μs | **1.6x faster** |
| 1,000 | 4.06 μs | 1.67 μs | **2.4x faster** |
| 10,000 | 37.67 μs | 10.43 μs | **3.6x faster** |

**Note**: Tetree's tetrahedral decomposition provides better spatial locality for neighbor searches.

### Range Query Performance

| Entity Count | Octree | Tetree | Winner |
|-------------|---------|---------|---------|
| 100 | 0.39 μs | 0.49 μs | Octree **1.2x faster** |
| 1,000 | 2.52 μs | 4.10 μs | Octree **1.6x faster** |
| 10,000 | 21.91 μs | 56.53 μs | Octree **2.6x faster** |

**Note**: Octree's AABB-based calculations are more efficient for range queries.

### Update Performance

| Entity Count | Octree | Tetree | Winner |
|-------------|---------|---------|---------|
| 100 | 0.16 μs | 0.09 μs | Tetree **1.8x faster** |
| 1,000 | 0.003 μs | 0.007 μs | Octree **2.8x faster** |
| 10,000 | 0.002 μs | 0.006 μs | Octree **2.4x faster** |

### Memory Usage

| Entity Count | Octree | Tetree | Tetree Memory % |
|-------------|---------|---------|-----------------|
| 100 | 0.15 MB | 0.04 MB | **25.9%** |
| 1,000 | 1.38 MB | 0.32 MB | **23.1%** |
| 10,000 | 12.90 MB | 3.15 MB | **24.4%** |

**Key Finding**: Tetree consistently uses 75-77% less memory than Octree.

### Bulk Operation Performance (100K entities)

| Implementation | Basic | Optimized | Speedup |
|----------------|-------|-----------|---------|
| Octree | 695K entities/sec | 860K entities/sec | 1.2x |
| Tetree | 25K entities/sec | **1.09M entities/sec** | **42.5x** |

**Critical Insight**: With bulk operations and deferred evaluation, Tetree can actually outperform Octree for large dataset loading!

### Parent Cache Performance

- **67.3x speedup** for high-level (level 20) tetrahedra lookups
- Cache hit rate: 98-100% for repeated access patterns
- Average cached access time: 45.34 ns per call
- Warmup time: ~1.37 ms for initial cache population

## What This Means for Users

### Choose Octree When:

1. **Real-time individual insertions** - Games, simulations with moving objects
2. **Frequent single updates** - Dynamic environments
3. **Range query dominated** - Spatial overlap detection
4. **Simplicity matters** - Easier to understand and debug

### Choose Tetree When:

1. **Bulk loading available** - Can achieve >1M entities/sec with optimizations
2. **Memory constrained** - Uses 75% less memory
3. **k-NN query performance critical** - 1.6-3.6x faster neighbor searches
4. **Read-heavy workloads** - Spatial databases, GIS systems

## Optimization Impact

### What Worked:

- **Parent Cache**: 67x speedup for deep tree operations
- **Bulk operations**: 42.5x speedup for Tetree insertion
- **Deferred evaluation**: Delays tmIndex() computation until needed
- **TetreeLevelCache**: O(1) level extraction and type transitions

### What Didn't Work:

- Cannot make tmIndex() O(1) without breaking correctness
- Parent chain walk is fundamental to the algorithm
- The fundamental algorithm difference cannot be optimized away

## Lessons Learned

1. **Correctness comes first**: The refactoring to use tmIndex() was necessary for correctness
2. **Algorithms matter**: No amount of optimization can overcome O(1) vs O(level) differences
3. **Trade-offs are real**: You can have fast insertion OR fast queries OR low memory, not all three
4. **Documentation drift**: Performance claims must be continuously validated

## Future Directions

### Potential Improvements:

1. **Hybrid approach**: Use Octree for insertion, convert to Tetree for querying
2. **Lazy indexing**: Defer tmIndex() computation until needed
3. **Specialized variants**: Different implementations for different use cases

### What Won't Change:

- Morton encoding will always be O(1)
- tmIndex() will always require parent traversal
- The fundamental trade-offs between the approaches

## Conclusion

Both spatial indices have their place:

- **Octree**: The workhorse for general-purpose spatial indexing
- **Tetree**: The specialist for query-intensive, memory-constrained applications

The key is choosing the right tool for your specific requirements.
