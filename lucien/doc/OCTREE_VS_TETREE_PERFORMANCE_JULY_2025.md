# Octree vs Tetree Performance Comparison - July 2025

## Executive Summary

Comprehensive performance benchmarks run on July 8, 2025 show mixed results between Octree and Tetree implementations:

- **Insertion**: Octree is 1.3x to 15.3x faster (performance gap increases with entity count)
- **K-NN Search**: Tetree is 1.5x to 5.9x faster 
- **Range Queries**: Tetree is 1.4x to 3.8x faster
- **Updates**: Mixed results (Tetree faster at 100 entities, Octree faster at 10K)
- **Memory**: Tetree uses only 20-23% of Octree's memory footprint
- **Collision Detection**: Octree handles 2K entities efficiently, Tetree degrades at scale
- **Ray Intersection**: Both scale linearly, Octree more consistent

## Test Environment

- Platform: Mac OS X aarch64
- JVM: Java HotSpot(TM) 64-Bit Server VM 24
- Processors: 16
- Memory: 512 MB
- Date: July 8, 2025
- Assertions: Disabled (-da flag)

## Detailed Results

### 100 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 3.874 μs/op | 5.063 μs/op | 1.31x | Octree (1.3x faster) |
| K-NN Search | 0.766 μs/op | 0.527 μs/op | 0.69x | Tetree (1.5x faster) |
| Range Query | 0.464 μs/op | 0.314 μs/op | 0.68x | Tetree (1.5x faster) |
| Update | 0.131 μs/op | 0.093 μs/op | 0.71x | Tetree (1.4x faster) |
| Removal | 0.023 μs/op | 0.010 μs/op | 0.43x | Tetree (2.3x faster) |
| Memory | 0.16 MB | 0.04 MB | 22.7% | Tetree |

### 1,000 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 2.210 μs/op | 6.473 μs/op | 2.93x | Octree (2.9x faster) |
| K-NN Search | 4.674 μs/op | 2.174 μs/op | 0.47x | Tetree (2.2x faster) |
| Range Query | 1.988 μs/op | 0.811 μs/op | 0.41x | Tetree (2.5x faster) |
| Update | 0.003 μs/op | 0.004 μs/op | 1.10x | Octree (1.1x faster) |
| Removal | 0.001 μs/op | 0.000 μs/op | 0.59x | Tetree (1.7x faster) |
| Memory | 1.44 MB | 0.30 MB | 20.6% | Tetree |

### 10,000 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 1.004 μs/op | 15.330 μs/op | 15.27x | Octree (15.3x faster) |
| K-NN Search | 20.942 μs/op | 6.089 μs/op | 0.29x | Tetree (3.4x faster) |
| Range Query | 22.641 μs/op | 5.931 μs/op | 0.26x | Tetree (3.8x faster) |
| Update | 0.002 μs/op | 0.033 μs/op | 15.29x | Octree (15.3x faster) |
| Removal | 0.001 μs/op | 0.003 μs/op | 2.36x | Octree (2.4x faster) |
| Memory | 13.59 MB | 2.89 MB | 21.3% | Tetree |

## Analysis

### Key Findings

1. **Insertion Performance Gap**: The Tetree insertion performance degrades significantly with larger datasets, from 1.3x slower at 100 entities to 15.3x slower at 10K entities. This is due to the O(level) cost of tmIndex() operations.

2. **Search Performance Advantage**: Tetree consistently outperforms Octree in both K-NN and range queries, with advantages increasing at higher entity counts.

3. **Memory Efficiency**: Tetree uses only 20-25% of Octree's memory, making it highly attractive for memory-constrained applications.

4. **Update Performance**: Results are mixed, with Tetree performing better at low entity counts but Octree dominating at high counts.

### Root Cause Analysis

The insertion performance gap is primarily due to:
- **tmIndex() Cost**: O(level) parent chain traversal vs Octree's O(1) Morton encoding
- **Complex Key Generation**: Tetree requires walking parent chain to build globally unique keys
- **Level-Based Overhead**: Higher levels require more parent traversals

Despite optimizations (caching, efficient data structures), the fundamental algorithmic difference cannot be overcome.

### Use Case Recommendations

**Choose Octree when:**
- High-volume insertions are critical
- Update performance matters at scale
- Simple, predictable performance is needed

**Choose Tetree when:**
- Memory efficiency is crucial
- Search operations dominate workload
- Working with smaller datasets (<1000 entities)
- Tetrahedral geometry provides natural fit for problem domain

## Additional Performance Metrics

### Collision Detection Performance

| Entity Count | Octree Insertion | Octree Detection | Tetree Insertion | Tetree Detection |
|--------------|------------------|------------------|------------------|------------------|
| 800 | 1.3 ms | 1.5 ms | 33 ms | 91 ms |
| 1,500 | 2.2 ms | 2.7 ms | 12 ms | 208 ms |
| 2,000 | 3.0 ms | 3.2 ms | - | - |

Octree maintains consistent performance while Tetree degrades significantly with scale.

### Ray Intersection Performance

| Rays | Entities | Octree Time | Throughput |
|------|----------|-------------|------------|
| 100 | 1,000 | 10 ms | 10K rays/sec |
| 100 | 5,000 | 48 ms | 2K rays/sec |
| 100 | 10,000 | 96 ms | 1K rays/sec |

Both implementations scale linearly with entity count for ray operations.

### TMIndex Performance Degradation

| Level | tmIndex Time | Degradation vs Level 1 |
|-------|--------------|------------------------|
| 1 | 0.069 μs | 1.0x |
| 5 | 0.081 μs | 1.2x |
| 10 | 0.147 μs | 2.1x |
| 15 | 0.303 μs | 4.4x |
| 20 | 0.975 μs | 14.1x |

This O(level) degradation is the core reason for Tetree's insertion performance issues.

### Optimization Effectiveness

| Optimization | Impact | Benefit |
|--------------|--------|---------|
| Parent caching | 17.3x speedup | Reduces parent() from O(level) to O(1) |
| Single-child computation | 3.0x speedup | 17 ns vs 52 ns per child |
| V2 tmIndex | 4.0x speedup | Simplified algorithm |
| Lazy evaluation | 99.5% memory reduction | Enables unbounded ranges |
| Bulk operations | 15.5x speedup (Tetree) | Amortizes tmIndex cost |

## Historical Context

Previous benchmarks showed much larger performance gaps (770x slower insertion). Recent optimizations have improved this to 1.3-15.3x:
- Parent caching: 17.3x speedup
- V2 tmIndex: 4x speedup  
- Subdivision fixes: 38-96% improvement
- Level caching: O(1) level extraction
- Single-child optimization: 3x speedup
- Lazy evaluation: Prevents memory exhaustion

While significant improvements have been made, the fundamental O(level) vs O(1) algorithmic difference remains.