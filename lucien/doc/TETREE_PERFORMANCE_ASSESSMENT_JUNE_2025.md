# Tetree Performance Assessment - June 2025

## Executive Summary

This document provides a comprehensive assessment of Tetree performance based on the latest benchmarks from
OctreeVsTetreeBenchmark (June 28, 2025). The results show significant improvements after fixing subdivision logic,
with Tetree now only 3-9x slower for insertions while maintaining its 2-3.5x advantage for k-NN queries.

## Current Performance Metrics (Latest Benchmarks - June 28, 2025)

### Insertion Performance

| Dataset Size | Octree        | Tetree        | Performance Gap | Notes                          |
|--------------|---------------|---------------|-----------------|--------------------------------|
| 100 entities | 3.83 μs/entity | 29.47 μs/entity | **7.7x slower**  | Small dataset                  |
| 1K entities  | 2.77 μs/entity | 7.94 μs/entity  | **2.9x slower**  | After all optimizations        |
| 10K entities | 1.00 μs/entity | 4.79 μs/entity  | **4.8x slower**  | Scales better than expected    |

### Query Performance

| Operation   | Dataset | Octree   | Tetree  | Tetree Advantage |
|-------------|---------|----------|---------|------------------|
| k-NN        | 100     | 0.72 μs  | 0.46 μs | **1.6x faster**  |
| k-NN        | 1K      | 4.06 μs  | 1.67 μs | **2.4x faster**  |
| k-NN        | 10K     | 37.67 μs | 10.43 μs| **3.6x faster**  |
| Range Query | 100     | 0.39 μs  | 0.49 μs | **1.2x slower**  |
| Range Query | 1K      | 2.52 μs  | 4.10 μs | **1.6x slower**  |
| Range Query | 10K     | 21.91 μs | 56.53 μs| **2.6x slower**  |

### Memory Efficiency

| Dataset Size | Octree  | Tetree  | Tetree Memory % |
|--------------|---------|---------|------------------|
| 100 entities | 0.15 MB | 0.04 MB | **25.9%**        |
| 1K entities  | 1.38 MB | 0.32 MB | **23.1%**        |
| 10K entities | 12.90 MB| 3.15 MB | **24.4%**        |

## Root Cause Analysis

### The Fundamental Difference

1. **Octree Morton Encoding**:
    - Simple bit interleaving of x, y, z coordinates
    - Always O(1) operation
    - No parent chain traversal needed

2. **Tetree tmIndex**:
    - Requires walking up the parent chain to compute global uniqueness
    - O(level) operation where level can be up to 21
    - Cannot be optimized to O(1) due to algorithm requirements

### Performance Breakdown (After Optimizations)

For a typical Tetree insertion:

- **6.8%** - tmIndex computation (reduced from ~90% through caching)
- **82.8%** - Core insertion logic (entity management, tree updates)
- **10.4%** - Finding the containing tetrahedron

## Impact of Optimization Phases

### Phase 1: TetreeKey Caching

- **Implementation**: 64K entry cache for TetreeKey values
- **Impact**: Reduced insertion time by ~93%
- **Result**: Gap reduced from 1125x to 83x slower

### Phase 2: Bulk Operation Optimization

- **Implementation**: Region pre-computation, spatial locality caching
- **Impact**: Additional 8% improvement for bulk operations
- **Result**: Gap reduced to 76x slower for bulk inserts

### Phase 3: Advanced Optimizations

- **Implementation**: Thread-local caching, parent chain caching
- **Impact**: Excellent for concurrent workloads (99.4% hit rate)
- **Result**: Modest overall improvement, significant for multi-threaded scenarios

### Phase 4: Parent Cache Optimization (June 28, 2025)

- **Implementation**: Direct parent cache with 16K entries + 64K parent type cache
- **Impact**: 
  - 17.3x speedup for individual parent() calls
  - 19.13x speedup for parent chain walking
  - 67.3x speedup for level 20 operations
- **Cache Performance**: 58-96% hit rate with spatial locality
- **Result**: Further reduces insertion gap from 4-9x to 2.9-7.7x

### Combined Result

- **Total Improvement**: 96% reduction from original performance gap
- **Final State**: 2.9-7.7x slower for insertions (was 1125x)
- **Key Achievement**: Made Tetree competitive for many real-world use cases
- **Bulk Operations**: Tetree can exceed Octree performance (1.09M vs 860K entities/sec)

## Use Case Recommendations

### When to Use Tetree

✅ **Ideal Use Cases**:

- Applications with query-heavy workloads
- Spatial clustering with frequent neighbor searches
- Memory-constrained environments
- Scientific simulations with localized interactions

❌ **Avoid For**:

- High-frequency insertion scenarios
- Real-time data ingestion
- Applications requiring consistent insertion performance
- Large-scale streaming data

### When to Use Octree

✅ **Ideal Use Cases**:

- Balanced insert/query workloads
- Real-time spatial indexing
- Uniform data distribution
- Simple spatial partitioning needs

❌ **Avoid For**:

- Memory-critical applications
- Query-dominant workloads
- Highly clustered spatial data

## Configuration for Optimal Performance

```java
// For single-threaded, query-heavy workloads
Tetree<ID, Content> tetree = new Tetree<>(idGenerator);
tetree.

setPerformanceMonitoring(false); // Disable for production

// For multi-threaded scenarios
Tetree<ID, Content> tetree = new Tetree<>(idGenerator);
tetree.

setThreadLocalCaching(true);     // Enable thread-local caches
tetree.

setPerformanceMonitoring(true);  // Monitor during development

// For bulk loading
List<ID> ids = tetree.insertBatch(positions, contents, level);
```

## Future Considerations

### Cannot Be Fixed

- The O(level) nature of tmIndex computation
- Parent chain traversal requirement
- Global uniqueness constraint

### Could Be Optimized

- Entity management overhead (82.8% of insertion time)
- Memory pooling for frequently created objects
- Batch tree update strategies
- Lazy subdivision evaluation

## Conclusion

After extensive optimization efforts, Tetree remains:

- **Excellent for queries**: 3-11x faster than Octree
- **Poor for insertions**: 70-350x slower than Octree
- **Memory efficient**: For datasets under 10K entities

The fundamental algorithmic differences mean Tetree will never match Octree's insertion performance. However, for
applications where query performance is paramount and insertions are infrequent, Tetree remains a superior choice.

## Benchmark Details

All benchmarks performed on:

- Platform: darwin aarch64
- Java: 23
- Test Date: June 2025
- Test Classes: `OctreeVsTetreeBenchmark`, `SpatialIndexBenchmark`

Raw benchmark data available in:

- `/lucien/src/test/java/com/hellblazer/luciferase/lucien/performance/`
