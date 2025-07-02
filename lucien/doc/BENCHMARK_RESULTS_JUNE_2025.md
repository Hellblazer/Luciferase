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
   - Bulk loading at large scales

3. **Geometric Subdivision**:
   - Extremely fast operation (~0.04 μs)
   - 5.5x faster than equivalent grid operations
   - Guarantees 100% geometric containment
   - No breaking changes to existing system

## Recommendations

- **Use Octree for**: Real-time systems, frequent updates, predictable latency requirements
- **Use Tetree for**: Analytics, k-NN intensive workloads, memory-constrained environments
- **Use geometricSubdivide() when**: You need all 8 children with containment guarantees

---

*Benchmarks conducted: June 28, 2025*
*Assertions disabled for accurate performance measurements*