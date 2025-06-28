# Spatial Index Performance Reality - June 2025 (Latest Benchmarks)

## Executive Summary

Based on comprehensive benchmarking with OctreeVsTetreeBenchmark, the performance characteristics are now well-established:
- **Octree is 3-9x faster for insertions** due to O(1) Morton encoding
- **Tetree is 2-3.5x faster for k-NN queries** due to better spatial locality
- **Tetree uses 70-75% less memory** with more efficient spatial decomposition

**Data Source**: Performance metrics from OctreeVsTetreeBenchmark.java run on June 28, 2025.

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

## Actual Performance Measurements (Latest Benchmarks - June 28, 2025)

### Insertion Performance

| Entity Count | Octree | Tetree | Octree Advantage |
|-------------|---------|---------|------------------|
| 100 | 3.32 μs/entity | 28.59 μs/entity | **8.6x faster** |
| 1,000 | 2.51 μs/entity | 7.64 μs/entity | **3.0x faster** |
| 10,000 | 1.06 μs/entity | 4.68 μs/entity | **4.4x faster** |

**Throughput**: Octree achieves ~950K entities/sec while Tetree manages ~35-200K entities/sec depending on dataset size.

### k-Nearest Neighbor (k-NN) Search

| Entity Count | Octree | Tetree | Tetree Advantage |
|-------------|---------|---------|------------------|
| 100 | 0.75 μs | 0.38 μs | **2.0x faster** |
| 1,000 | 4.08 μs | 1.61 μs | **2.5x faster** |
| 10,000 | 37.61 μs | 10.70 μs | **3.5x faster** |

**Note**: Tetree's tetrahedral decomposition provides better spatial locality for neighbor searches.

### Range Query Performance

| Entity Count | Octree | Tetree | Winner |
|-------------|---------|---------|---------|
| 100 | 0.39 μs | 0.52 μs | Octree **1.3x faster** |
| 1,000 | 2.12 μs | 4.02 μs | Octree **1.9x faster** |
| 10,000 | 21.59 μs | 53.24 μs | Octree **2.5x faster** |

**Note**: Octree's AABB-based calculations are more efficient for range queries.

### Update Performance

| Entity Count | Octree | Tetree | Winner |
|-------------|---------|---------|---------|
| 100 | 0.14 μs | 0.07 μs | Tetree **2.0x faster** |
| 1,000 | 0.003 μs | 0.008 μs | Octree **3.2x faster** |
| 10,000 | 0.002 μs | 0.005 μs | Octree **2.3x faster** |

### Memory Usage

| Entity Count | Octree | Tetree | Tetree Memory % |
|-------------|---------|---------|-----------------|
| 100 | 0.15 MB | 0.04 MB | **28.7%** |
| 1,000 | 1.40 MB | 0.34 MB | **24.0%** |
| 10,000 | 12.90 MB | 3.16 MB | **24.5%** |

**Key Finding**: Tetree consistently uses 70-75% less memory than Octree.

## What This Means for Users

### Choose Octree When:

1. **Real-time insertions** - Games, simulations with moving objects
2. **Bulk loading** - Importing large datasets
3. **Frequent updates** - Dynamic environments
4. **Balanced workloads** - Mix of insertions and queries

### Choose Tetree When:

1. **Static datasets** - Load once, query many times
2. **Memory constrained** - Embedded systems, mobile devices
3. **Query-heavy workloads** - Spatial databases, GIS systems
4. **Offline processing** - Where insertion time doesn't matter

## Optimization Attempts and Results

### What Worked:

- **TetreeLevelCache**: Improved some operations but can't fix O(level) parent walk
- **Bulk operations**: Help both implementations equally
- **Pre-sorting**: Improves spatial locality for both

### What Didn't Work:

- Cannot make tmIndex() O(1) without breaking correctness
- Parent chain caching helps but doesn't eliminate the traversal
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
