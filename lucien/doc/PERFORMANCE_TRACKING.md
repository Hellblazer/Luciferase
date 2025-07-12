# Luciferase Performance Tracking

## Overview

This document tracks performance benchmarks and optimization results for the Luciferase spatial indexing library.

## Current Performance Baseline (July 11, 2025)

### Latest Benchmark Results

| Entity Count | Metric | Octree | Tetree | Difference |
|--------------|--------|--------|--------|-----------|
| **100 entities** | | | | |
| | Insertion | 0.47 μs/op | 0.22 μs/op | Tetree 2.1x faster |
| | K-NN Search | 7.3 μs/op | 4.6 μs/op | Tetree 1.6x faster |
| | Memory | 0.68 MB | 0.46 MB | Tetree uses 68% |
| **1,000 entities** | | | | |
| | Insertion | 5.5 μs/op | 1.0 μs/op | Tetree 5.5x faster |
| | K-NN Search | 14.2 μs/op | 12.9 μs/op | Tetree 1.1x faster |
| | Memory | 2.1 MB | 1.5 MB | Tetree uses 71% |
| **10,000 entities** | | | | |
| | Insertion | 31.0 μs/op | 5.0 μs/op | Tetree 6.2x faster |
| | K-NN Search | 18.5 μs/op | 22.2 μs/op | Octree 1.2x faster |
| | Memory | 13.7 MB | 10.0 MB | Tetree uses 73% |

*Note: July 11 results show significant improvements from concurrent optimizations*

## Previous Performance Baseline (July 2025)

### Octree vs Tetree Comparison

| Metric | Octree | Tetree | Winner |
|--------|--------|--------|--------|
| **Insertion** | 1.0 μs/op | 15.3 μs/op | Octree (15x faster) |
| **K-NN Search** | 20.9 μs/op | 6.1 μs/op | Tetree (3.4x faster) |
| **Range Query** | 22.6 μs/op | 5.9 μs/op | Tetree (3.8x faster) |
| **Memory Usage** | 13.6 MB | 2.9 MB | Tetree (79% less) |
| **Update** | Baseline | 3-5x faster | Tetree |
| **Removal** | Baseline | 4x faster | Tetree |

*Benchmark: 10,000 entities, random distribution*

### Key Performance Characteristics

**Octree**
- O(1) Morton encoding
- Predictable performance
- Better for frequent insertions
- Superior collision detection scaling

**Tetree**
- O(level) tmIndex computation
- Excellent query performance
- Memory efficient
- Better for read-heavy workloads

## Performance Optimization History

### Recent Optimizations (2025)

1. **Concurrent Optimization** (July 11)
   - ConcurrentSkipListMap replacing dual HashMap/TreeSet
   - 54-61% memory reduction
   - Eliminated ConcurrentModificationException
   - ObjectPool extended to all query operations

2. **Lock-Free Entity Updates** (July 11)
   - 264K movements/sec with 4 threads
   - 1.69M content updates/sec
   - 187 bytes per entity memory overhead
   - Zero conflicts with optimistic concurrency

3. **Lazy Evaluation** (July 8)
   - 99.5% memory reduction for large range queries
   - O(1) memory usage regardless of range size

4. **Parent Caching** (June 28)
   - 17.3x speedup for parent() operations
   - Reduces Tetree insertion gap

5. **V2 tmIndex Algorithm** (June 28)
   - 4x speedup over original implementation
   - Simplified from 70+ lines to 15 lines

6. **Bulk Operations** (June 2025)
   - 15.5x speedup for batch insertions
   - Parallel processing support

7. **Node Consolidation** (July 10)
   - Eliminated redundant wrapper classes
   - Reduced memory overhead
   - Simplified architecture

## Performance Trends

### Insertion Performance Evolution
- July 11: Tetree 2.1x-6.2x faster (concurrent optimizations)
- Early July: Octree 15x faster (before optimizations)
- June: Octree 7-10x faster (after V2 tmIndex)

### Memory Usage Evolution
- July 11: Tetree uses 65-73% of Octree's memory
- Early July: Tetree uses 21% of Octree's memory
- Concurrent optimizations traded some memory for thread safety

### Query Performance
- Consistently strong Tetree performance for k-NN at low entity counts
- Crossover point around 10K entities where Octree becomes competitive
- Range queries remain Tetree's strength across all scales

## Benchmark Suite

Run the following tests to verify performance:

```bash
# Primary comparison
mvn test -Dtest=OctreeVsTetreeBenchmark

# Quick validation
mvn test -Dtest=QuickPerformanceTest

# Collision performance
mvn test -Dtest=*CollisionPerformanceTest

# Ray intersection
mvn test -Dtest=*RayPerformanceTest

# Memory usage
mvn test -Dtest=*MemoryPerformanceTest
```

## Performance Guidelines

### When to Use Octree
- High insertion/update frequency
- Large-scale collision detection
- Predictable performance requirements
- Mixed read/write workloads

### When to Use Tetree
- Query-heavy applications
- Memory-constrained environments
- Smaller datasets (<10K entities)
- Natural tetrahedral geometry

### Optimization Tips
1. Use appropriate spatial level for entity size
2. Enable spanning only when necessary
3. Batch operations when possible
4. Monitor entity distribution for hot spots
5. Use simple collision shapes when possible

## Related Documentation

- [Spatial Index Architecture](./LUCIEN_ARCHITECTURE.md)
- [Core API Reference](./CORE_SPATIAL_INDEX_API.md)
- [Collision Detection Guide](./COLLISION_DETECTION_API.md)
- [K-NN Search Guide](./K_NEAREST_NEIGHBORS_API.md)