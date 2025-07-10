# Luciferase Performance Tracking

## Overview

This document tracks performance benchmarks and optimization results for the Luciferase spatial indexing library.

## Current Performance Baseline (July 2025)

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

1. **Lazy Evaluation** (July 8)
   - 99.5% memory reduction for large range queries
   - O(1) memory usage regardless of range size

2. **Parent Caching** (June 28)
   - 17.3x speedup for parent() operations
   - Reduces Tetree insertion gap

3. **V2 tmIndex Algorithm** (June 28)
   - 4x speedup over original implementation
   - Simplified from 70+ lines to 15 lines

4. **Bulk Operations** (June 2025)
   - 15.5x speedup for batch insertions
   - Parallel processing support

5. **Node Consolidation** (July 10)
   - Eliminated redundant wrapper classes
   - Reduced memory overhead
   - Simplified architecture

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