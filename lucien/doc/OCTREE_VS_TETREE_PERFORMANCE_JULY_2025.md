# Octree vs Tetree Performance Comparison - July 2025

## Executive Summary

Performance benchmarks run on July 8, 2025 show mixed results between Octree and Tetree implementations:

- **Insertion**: Octree is 2.9x to 15.3x faster (performance gap increases with entity count)
- **K-NN Search**: Tetree is 2.2x to 3.4x faster
- **Range Queries**: Tetree is 2.5x to 3.8x faster  
- **Updates**: Mixed results (Tetree faster at 100 entities, Octree faster at 10K)
- **Memory**: Tetree uses only 20-23% of Octree's memory footprint

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

## Historical Context

Previous benchmarks showed much larger performance gaps (770x slower insertion). Recent optimizations have improved this to 2.3-11.4x:
- Parent caching: 17.3x speedup
- V2 tmIndex: 4x speedup  
- Subdivision fixes: 38-96% improvement
- Level caching: O(1) level extraction

While significant improvements have been made, the fundamental O(level) vs O(1) algorithmic difference remains.