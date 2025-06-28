# Spatial Index Performance Update - June 28, 2025

## Executive Summary

This document presents the latest performance benchmarks for the Luciferase spatial indexing system after implementing the parent cache optimization. The benchmarks were run on June 28, 2025, on a Mac OS X aarch64 system with 16 processors and Java HotSpot(TM) 64-Bit Server VM 24.

## Key Performance Metrics

### OctreeVsTetreeBenchmark Results

#### Small Dataset (100 entities)
| Operation | Octree | Tetree | Winner | Performance Ratio |
|-----------|--------|--------|--------|-------------------|
| Insertion | 3.831 μs/entity | 29.468 μs/entity | Octree | 7.7x faster |
| k-NN Search | 0.718 μs | 0.457 μs | Tetree | 1.6x faster |
| Range Query | 0.389 μs | 0.485 μs | Octree | 1.2x faster |
| Update | 0.158 μs | 0.088 μs | Tetree | 1.8x faster |
| Removal | 0.051 μs | 0.008 μs | Tetree | 6.0x faster |
| Memory | 0.15 MB | 0.04 MB | Tetree | 25.9% of Octree |

#### Medium Dataset (1,000 entities)
| Operation | Octree | Tetree | Winner | Performance Ratio |
|-----------|--------|--------|--------|-------------------|
| Insertion | 2.769 μs/entity | 7.938 μs/entity | Octree | 2.9x faster |
| k-NN Search | 4.056 μs | 1.673 μs | Tetree | 2.4x faster |
| Range Query | 2.518 μs | 4.100 μs | Octree | 1.6x faster |
| Update | 0.003 μs | 0.007 μs | Octree | 2.8x faster |
| Removal | 0.001 μs | 0.000 μs | Tetree | 2.4x faster |
| Memory | 1.38 MB | 0.32 MB | Tetree | 23.1% of Octree |

#### Large Dataset (10,000 entities)
| Operation | Octree | Tetree | Winner | Performance Ratio |
|-----------|--------|--------|--------|-------------------|
| Insertion | 1.001 μs/entity | 4.785 μs/entity | Octree | 4.8x faster |
| k-NN Search | 37.673 μs | 10.434 μs | Tetree | 3.6x faster |
| Range Query | 21.914 μs | 56.534 μs | Octree | 2.6x faster |
| Update | 0.002 μs | 0.006 μs | Octree | 2.4x faster |
| Removal | 0.001 μs | 0.001 μs | Tetree | 2.1x faster |
| Memory | 12.90 MB | 3.15 MB | Tetree | 24.4% of Octree |

### Baseline Performance Benchmark Results

The baseline performance benchmark shows the impact of optimizations (bulk loading, deferred evaluation):

#### 100,000 Entity Dataset
| Implementation | Basic | Optimized | Speedup |
|----------------|-------|-----------|---------|
| Octree | 144 ms | 160 ms | 0.90x |
| Tetree | 3,910 ms | 92 ms | **42.50x** |

**Key Finding**: With optimizations enabled, Tetree insertion performance improves dramatically, achieving:
- 1,095,073 entities/sec with optimizations
- 25,576 entities/sec without optimizations

### Parent Cache Performance

The TetreeKeyCachePerformanceTest shows significant improvements from the parent cache:

- **67.3x speedup** for high-level (level 20) tetrahedra lookups
- Cache hit rate: 98-100% for repeated access patterns
- Average cached access time: 45.34 ns per call
- Uncached level 20 access: 0.65 ms per 100 calls
- Cached level 20 access: 0.10 ms per 1000 calls

### Performance Insights

1. **Insertion Performance**: Octree remains faster for individual insertions (2.9x to 7.7x), but with bulk optimizations, Tetree can achieve competitive throughput (>1M entities/sec).

2. **Query Performance**: Tetree consistently outperforms Octree for k-NN searches (1.6x to 3.6x faster), benefiting from better spatial locality.

3. **Memory Efficiency**: Tetree uses approximately 20-25% of the memory required by Octree across all dataset sizes.

4. **Optimization Impact**: The deferred evaluation and bulk loading optimizations provide massive improvements for Tetree (up to 42x speedup), making it viable for large-scale datasets.

5. **Parent Cache Effectiveness**: The parent cache provides up to 67x speedup for deep tree operations, effectively mitigating the O(level) cost of tmIndex() computation.

## Recommendations

1. **Use Octree when**:
   - Real-time individual insertions are critical
   - Range queries dominate the workload
   - Simple implementation is preferred

2. **Use Tetree when**:
   - k-NN search performance is critical
   - Memory efficiency is important
   - Bulk loading is possible
   - Query performance outweighs insertion speed

3. **Always enable optimizations**:
   - Use bulk loading when possible
   - Enable deferred evaluation for Tetree
   - Leverage the parent cache for deep tree operations

## Technical Details

### System Configuration
- Platform: Mac OS X aarch64
- JVM: Java HotSpot(TM) 64-Bit Server VM 24
- Processors: 16
- Memory: 512 MB heap

### Test Methodology
- Environment variable: `RUN_SPATIAL_INDEX_PERF_TESTS=true`
- Multiple warmup iterations before measurement
- Median values reported to reduce outlier impact
- Tests run with various dataset sizes and distributions

### Implementation Notes
- Both implementations use the generic `AbstractSpatialIndex` base class
- Tetree uses `tmIndex()` for globally unique spatial keys
- Octree uses Morton encoding for O(1) key generation
- Parent cache implemented in `TetreeKeyCache` class
- Bulk operations leverage `AbstractSpatialIndex.bulkAdd()` method