# Luciferase Performance Tracking

## Overview

This document tracks performance benchmarks and optimization results for the Luciferase spatial indexing library.

## Current Performance Baseline (July 12, 2025)

### Latest Three-Way Benchmark Results

| Entity Count | Metric | Octree | Tetree | Prism | Best |
|--------------|--------|--------|--------|--------|------|
| **100 entities** | | | | | |
| | Insertion | 0.47 μs/op | 0.22 μs/op | 0.73 μs/op | Tetree |
| | K-NN Search | 7.3 μs/op | 4.6 μs/op | 20.0 μs/op | Tetree |
| | Memory | 0.68 MB | 0.46 MB | 0.83 MB | Tetree |
| **1,000 entities** | | | | | |
| | Insertion | 4.46 ms | 1.0 μs/op | 6.86 ms | Tetree |
| | K-NN Search | 725.71 μs | 12.9 μs/op | 1995.79 μs | Tetree |
| | Range Query | 1776.96 μs | 881.0 μs | 2144.79 μs | Tetree |
| | Memory | 633.52 KB | 1.5 MB | 774.83 KB | Octree |
| **2,000 entities** | | | | | |
| | Memory | 633.52 KB | 590.13 KB | 774.83 KB | Tetree |
| **10,000 entities** | | | | | |
| | Insertion | 31.0 μs/op | 5.0 μs/op | 47.7 μs/op | Tetree |
| | K-NN Search | 18.5 μs/op | 22.2 μs/op | 50.9 μs/op | Octree |
| | Memory | 13.7 MB | 10.0 MB | 16.7 MB | Tetree |

*Note: July 12 results include new Prism benchmarks alongside July 11 concurrent optimizations*

## Previous Performance Baseline (July 2025)

### Three-Way Comparison Summary

| Metric | Octree | Tetree | Prism | Performance Ranking |
|--------|--------|--------|--------|-------------------|
| **Insertion** | 1.0 μs/op | 15.3 μs/op | 1.54 μs/op | Octree > Prism > Tetree |
| **K-NN Search** | 20.9 μs/op | 6.1 μs/op | 57.5 μs/op | Tetree > Octree > Prism |
| **Range Query** | 22.6 μs/op | 5.9 μs/op | 27.3 μs/op | Tetree > Octree > Prism |
| **Memory Usage** | 13.6 MB | 2.9 MB | 16.6 MB | Tetree > Octree > Prism |
| **Update** | Baseline | 3-5x faster | Similar to Octree | Tetree > Octree ≈ Prism |
| **Removal** | Baseline | 4x faster | Similar to Octree | Tetree > Octree ≈ Prism |

*Benchmark: 10,000 entities, random distribution*

### Prism vs Octree Detailed Results

| Entity Count | Metric | Octree | Prism | Prism vs Octree |
|--------------|--------|--------|--------|-----------------|
| **1,000** | Insertion | 4.46 ms | 6.86 ms | 1.54x slower |
| | K-NN Search | 725.71 μs | 1995.79 μs | 2.75x slower |
| | Range Query | 1776.96 μs | 2144.79 μs | 1.21x slower |
| | Memory | 633.52 KB | 774.83 KB | 1.22x higher |

### Prism vs Tetree Detailed Results

| Entity Count | Metric | Tetree | Prism | Prism vs Tetree |
|--------------|--------|--------|--------|-----------------|
| **1,000** | Insertion | 31.23 ms | 6.61 ms | 4.7x faster |
| | K-NN Search | 1081.79 μs | 2181.92 μs | 2.02x slower |
| | Range Query | 2016.21 μs | 2291.17 μs | 1.14x slower |
| | Memory | 590.13 KB | 760.70 KB | 1.29x higher |

### Key Performance Characteristics

**Octree**
- O(1) Morton encoding
- Predictable performance
- Better for frequent insertions
- Superior collision detection scaling
- Best overall insertion performance

**Tetree**
- O(level) tmIndex computation
- Excellent query performance
- Memory efficient
- Better for read-heavy workloads
- Best k-NN and range query performance

**Prism**
- Rectangular subdivision strategy
- Moderate insertion performance (1.54x slower than Octree)
- Query performance between Octree and Tetree
- Higher memory usage (22-29% more than Octree)
- Optimized for anisotropic data distributions
- Avoids cubic subdivision overhead for directional data

## Performance Optimization History

### Recent Optimizations (2025)

1. **Prism Spatial Index Integration** (July 12)
   - Added third spatial index option for anisotropic workloads
   - Rectangular subdivision strategy
   - Performance positioned between Octree and Tetree
   - 4.7x faster insertion than Tetree, 1.54x slower than Octree

2. **Concurrent Optimization** (July 11)
   - ConcurrentSkipListMap replacing dual HashMap/TreeSet
   - 54-61% memory reduction
   - Eliminated ConcurrentModificationException
   - ObjectPool extended to all query operations

3. **Lock-Free Entity Updates** (July 11)
   - 264K movements/sec with 4 threads
   - 1.69M content updates/sec
   - 187 bytes per entity memory overhead
   - Zero conflicts with optimistic concurrency

4. **Lazy Evaluation** (July 8)
   - 99.5% memory reduction for large range queries
   - O(1) memory usage regardless of range size

5. **Parent Caching** (June 28)
   - 17.3x speedup for parent() operations
   - Reduces Tetree insertion gap

6. **V2 tmIndex Algorithm** (June 28)
   - 4x speedup over original implementation
   - Simplified from 70+ lines to 15 lines

7. **Bulk Operations** (June 2025)
   - 15.5x speedup for batch insertions
   - Parallel processing support

8. **Node Consolidation** (July 10)
   - Eliminated redundant wrapper classes
   - Reduced memory overhead
   - Simplified architecture

## Performance Trends

### Insertion Performance Evolution
- July 12: Prism provides middle ground (1.54x slower than Octree, 4.7x faster than Tetree)
- July 11: Tetree 2.1x-6.2x faster (concurrent optimizations)
- Early July: Octree 15x faster (before optimizations)
- June: Octree 7-10x faster (after V2 tmIndex)

### Memory Usage Evolution
- July 12: Prism uses 22-29% more memory than Octree
- July 11: Tetree uses 65-73% of Octree's memory
- Early July: Tetree uses 21% of Octree's memory
- Concurrent optimizations traded some memory for thread safety

### Query Performance
- Consistently strong Tetree performance for k-NN at low entity counts
- Prism provides competitive performance for anisotropic data patterns
- Crossover point around 10K entities where Octree becomes competitive for k-NN
- Range queries remain Tetree's strength across all scales
- Prism shows potential for directional/streaming data workloads

## Benchmark Suite

Run the following tests to verify performance:

```bash
# Three-way comparison (Octree vs Tetree vs Prism)
mvn test -Dtest=OctreeVsTetreeBenchmark

# Prism-specific performance testing
mvn test -Dtest=*PrismPerformanceTest

# Anisotropic data workload testing
mvn test -Dtest=*AnisotropicTest

# Quick validation
mvn test -Dtest=QuickPerformanceTest

# Collision performance
mvn test -Dtest=*CollisionPerformanceTest

# Ray intersection
mvn test -Dtest=*RayPerformanceTest

# Memory usage
mvn test -Dtest=*MemoryPerformanceTest

# Full performance suite
mvn test -Dtest=*PerformanceTest
```

### Prism-Specific Benchmark Instructions

For anisotropic workload testing:
```bash
# Test directional data patterns
mvn test -Dtest=PrismAnisotropicWorkloadTest

# Compare against isotropic data
mvn test -Dtest=PrismIsotropicComparisonTest

# Memory efficiency for streaming data
mvn test -Dtest=PrismStreamingDataTest
```

## Performance Guidelines

### When to Use Octree
- High insertion/update frequency (best insertion performance)
- Large-scale collision detection
- Predictable performance requirements
- Mixed read/write workloads
- Isotropic data distributions

### When to Use Tetree
- Query-heavy applications (best k-NN and range performance)
- Memory-constrained environments (uses 65-73% of Octree's memory)
- Smaller datasets (<10K entities)
- Natural tetrahedral geometry
- Read-heavy workloads

### When to Use Prism
- Anisotropic data distributions
- Directional or streaming data patterns
- Applications with moderate insertion requirements
- When cubic subdivision creates overhead
- Workloads where rectangular decomposition fits naturally
- Medium-scale applications (between Octree and Tetree complexity)

### Performance Recommendations by Use Case

**High-Frequency Updates**: Octree > Prism > Tetree
**Query Performance**: Tetree > Octree > Prism  
**Memory Efficiency**: Tetree > Octree > Prism
**Anisotropic Data**: Prism > Octree > Tetree
**Mixed Workloads**: Profile all three for your specific data patterns

### Optimization Tips
1. Use appropriate spatial level for entity size
2. Enable spanning only when necessary
3. Batch operations when possible
4. Monitor entity distribution for hot spots
5. Use simple collision shapes when possible
6. For Prism: Align subdivision with data directionality
7. For anisotropic data: Test Prism against Octree baseline
8. Profile memory usage patterns for your specific workload

## Related Documentation

- [Spatial Index Architecture](./LUCIEN_ARCHITECTURE.md)
- [Core API Reference](./CORE_SPATIAL_INDEX_API.md)
- [Collision Detection Guide](./COLLISION_DETECTION_API.md)
- [K-NN Search Guide](./K_NEAREST_NEIGHBORS_API.md)