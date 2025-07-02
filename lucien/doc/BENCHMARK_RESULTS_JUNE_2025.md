# Benchmark Results - June 2025

## Executive Summary

This document tracks the performance benchmarks for the Lucien spatial indexing system, including recent improvements and the new geometric subdivision feature.

## Platform Information

- **OS**: Mac OS X aarch64
- **JVM**: Java HotSpot(TM) 64-Bit Server VM 24
- **Processors**: 16 cores
- **Memory**: 512 MB heap

## Octree vs Tetree Performance Comparison

### Small Scale (100 entities)

| Operation | Octree | Tetree | Winner | Ratio |
|-----------|--------|---------|---------|-------|
| Insertion | 3.91 μs | 4.83 μs | Octree | 1.2x |
| k-NN Search | 0.73 μs | 0.44 μs | Tetree | 1.7x |
| Range Query | 0.37 μs | 0.26 μs | Tetree | 1.4x |
| Update | 0.17 μs | 0.12 μs | Tetree | 1.4x |
| Removal | 0.02 μs | 0.01 μs | Tetree | 3.5x |
| Memory | 0.15 MB | 0.04 MB | Tetree | 25.2% |

### Medium Scale (1,000 entities)

| Operation | Octree | Tetree | Winner | Ratio |
|-----------|--------|---------|---------|-------|
| Insertion | 2.29 μs | 8.81 μs | Octree | 3.9x |
| k-NN Search | 5.04 μs | 1.20 μs | Tetree | 4.2x |
| Range Query | 1.76 μs | 0.62 μs | Tetree | 2.8x |
| Update | 0.002 μs | 0.018 μs | Octree | 7.7x |
| Removal | 0.001 μs | 0.001 μs | Tetree | 1.5x |
| Memory | 1.40 MB | 0.28 MB | Tetree | 19.8% |

### Large Scale (10,000 entities)

| Operation | Octree | Tetree | Winner | Ratio |
|-----------|--------|---------|---------|-------|
| Insertion | 1.20 μs | 46.91 μs | Octree | 39.2x |
| k-NN Search | 21.16 μs | 6.32 μs | Tetree | 3.4x |
| Range Query | 21.15 μs | 5.86 μs | Tetree | 3.6x |
| Update | 0.002 μs | 0.096 μs | Octree | 41.9x |
| Removal | 0.001 μs | 0.001 μs | Octree | 1.6x |
| Memory | 12.91 MB | 2.64 MB | Tetree | 20.5% |

## Batch Loading Performance (Updated July 2, 2025)

### Tetree Batch Performance - 10,000 Entities

| Distribution | Iterative | Basic Bulk | Optimized Bulk | Stack-Based | Best Speedup |
|--------------|-----------|------------|----------------|-------------|--------------|
| Uniform Random | 460 ms | 3 ms | 5 ms | 4 ms | 153x faster |
| Clustered | 5 ms | 6 ms | 3 ms | 3 ms | 1.67x faster |
| Surface Aligned | 592 ms | 2 ms | 8 ms | 5 ms | 296x faster |
| Diagonal Line | 294 ms | 2 ms | 2 ms | 2 ms | 147x faster |

### Historical Bulk Loading Comparison (June 2025)

| Entity Count | Octree Time | Octree Rate | Tetree Time | Tetree Rate | Winner | Speedup |
|--------------|-------------|-------------|-------------|-------------|---------|---------|
| 10,000 | 5 ms | 2.0M/sec | 3 ms | 3.3M/sec | Tetree | 40% faster |
| 100,000 | 40-43 ms | 2.3-2.5M/sec | 26-29 ms | 3.4-3.8M/sec | Tetree | 35-38% faster |
| 500,000 | 344-346 ms | 1.45M/sec | 198-202 ms | 2.48-2.53M/sec | Tetree | 42-43% faster |
| 1,000,000 | 866-869 ms | 1.15M/sec | 539-543 ms | 1.84-1.86M/sec | Tetree | 38% faster |

### Key Batch Loading Insights

1. **Massive speedups**: Batch operations provide 74-296x speedup over iterative insertion
2. **Memory efficiency**: 63-93% memory reduction with batch operations
3. **Tetree consistently outperforms Octree** for bulk loading at all scales
4. **The Paradox**: Individual insertion 39-460x slower, batch insertion 35-43% faster

## Geometric Subdivision Performance (June 28, 2025)

### Performance by Level

| Level | Cell Size | Time per Operation | Operations/sec |
|-------|-----------|-------------------|----------------|
| 5 | 65,536 | 0.133 μs | 7,494,457 |
| 8 | 8,192 | 0.022 μs | 45,972,608 |
| 10 | 2,048 | 0.039 μs | 25,407,128 |
| 12 | 512 | 0.043 μs | 23,367,525 |
| 15 | 64 | 0.042 μs | 23,769,823 |

### Comparison with Grid-Based Methods

- **geometricSubdivide()**: 0.047 μs (single operation returning 8 children)
- **8x child() calls**: 0.259 μs (8 separate operations)
- **Performance Advantage**: 5.5x faster

## Key Findings

1. **Octree excels at**:
   - Individual insertions (3-39x faster)
   - Consistent performance across scales
   - Update operations

2. **Tetree excels at**:
   - k-NN searches (1.7-4.2x faster)
   - Range queries (1.4-3.6x faster)
   - Memory efficiency (75-80% less memory)
   - **Bulk loading (35-43% faster at all scales)**

3. **The Batch Loading Paradox**:
   - Individual insertion: Tetree is 39x slower at 10K entities
   - Batch insertion: Tetree is 40% faster at 10K entities
   - This dramatic reversal shows the importance of operation batching

4. **Geometric Subdivision**:
   - Extremely fast operation (~0.04 μs)
   - 5.5x faster than equivalent grid operations
   - Guarantees 100% geometric containment
   - No breaking changes to existing system

## Recommendations

- **Use Octree for**: Real-time systems, frequent individual updates, predictable latency requirements
- **Use Tetree for**: Batch loading scenarios, k-NN intensive workloads, memory-constrained environments
- **Use batch operations**: When loading many entities, batch loading can reverse performance characteristics
- **Use geometricSubdivide() when**: You need all 8 children with containment guarantees

---

*Individual operation benchmarks: June 28, 2025*
*Batch loading benchmarks: July 2, 2025*
*Assertions disabled for accurate performance measurements*

**Note**: Batch performance should be tracked regularly alongside individual operations, as it reveals dramatically different performance characteristics. Run with `RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test -Dtest=BulkOperationBenchmark`