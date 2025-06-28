# Tetree Performance Assessment - June 2025

## Executive Summary

This document provides a comprehensive assessment of Tetree performance based on the latest benchmarks from
OctreeVsTetreeBenchmark (June 28, 2025). The results show significant improvements after fixing subdivision logic,
with Tetree now only 3-9x slower for insertions while maintaining its 2-3.5x advantage for k-NN queries.

## Current Performance Metrics (Latest Benchmarks - June 28, 2025)

### Insertion Performance

| Dataset Size | Octree        | Tetree        | Performance Gap | Notes                          |
|--------------|---------------|---------------|-----------------|--------------------------------|
| 100 entities | 3.3 μs/entity | 28.6 μs/entity | **8.6x slower**  | Small dataset                  |
| 1K entities  | 2.5 μs/entity | 7.6 μs/entity  | **3.0x slower**  | After all optimizations        |
| 10K entities | 1.1 μs/entity | 4.7 μs/entity  | **4.4x slower**  | Scales better than expected    |

### Query Performance

| Operation   | Dataset | Octree   | Tetree  | Tetree Advantage |
|-------------|---------|----------|---------|------------------|
| k-NN        | 100     | 0.75 μs  | 0.38 μs | **2.0x faster**  |
| k-NN        | 1K      | 4.08 μs  | 1.61 μs | **2.5x faster**  |
| k-NN        | 10K     | 37.61 μs | 10.70 μs| **3.5x faster**  |
| Range Query | 100     | 0.39 μs  | 0.52 μs | **1.3x slower**  |
| Range Query | 1K      | 2.12 μs  | 4.02 μs | **1.9x slower**  |
| Range Query | 10K     | 21.59 μs | 53.24 μs| **2.5x slower**  |

### Memory Efficiency

| Dataset Size | Octree  | Tetree  | Tetree Memory % |
|--------------|---------|---------|------------------|
| 100 entities | 0.15 MB | 0.04 MB | **28.7%**        |
| 1K entities  | 1.40 MB | 0.34 MB | **24.0%**        |
| 10K entities | 12.90 MB| 3.16 MB | **24.5%**        |

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

### Combined Result

- **Total Improvement**: 94% reduction in performance gap
- **Final State**: 70-75x slower for typical workloads
- **Key Achievement**: Shifted bottleneck from tmIndex to core insertion logic

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
