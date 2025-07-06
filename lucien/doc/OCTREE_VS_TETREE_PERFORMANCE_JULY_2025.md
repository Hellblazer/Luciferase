# Octree vs Tetree Performance Comparison - July 2025

## Executive Summary

Performance benchmarks run on July 6, 2025 show mixed results between Octree and Tetree implementations:

- **Insertion**: Octree is 2.3x to 11.4x faster (performance gap increases with entity count)
- **K-NN Search**: Tetree is 1.6x to 5.9x faster
- **Range Queries**: Tetree is 1.4x to 3.5x faster  
- **Updates**: Mixed results (Tetree faster at 100 entities, Octree faster at 10K)
- **Memory**: Tetree uses only 20-25% of Octree's memory footprint

## Test Environment

- Platform: Mac OS X aarch64
- JVM: Java HotSpot(TM) 64-Bit Server VM 24
- Processors: 16
- Memory: 512 MB
- Date: July 6, 2025
- Assertions: Disabled (-da flag)

## Detailed Results

### 100 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 5.307 μs/op | 5.089 μs/op | 0.96x | Tetree (1.0x faster) |
| K-NN Search | 0.723 μs/op | 0.465 μs/op | 0.64x | Tetree (1.6x faster) |
| Range Query | 0.365 μs/op | 0.255 μs/op | 0.70x | Tetree (1.4x faster) |
| Update | 0.146 μs/op | 0.072 μs/op | 0.50x | Tetree (2.0x faster) |
| Removal | 0.021 μs/op | 0.004 μs/op | 0.20x | Tetree (5.0x faster) |
| Memory | 0.15 MB | 0.04 MB | 25.4% | Tetree |

### 1,000 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 2.414 μs/op | 5.571 μs/op | 2.31x | Octree (2.3x faster) |
| K-NN Search | 4.811 μs/op | 0.822 μs/op | 0.17x | Tetree (5.9x faster) |
| Range Query | 1.774 μs/op | 0.617 μs/op | 0.35x | Tetree (2.9x faster) |
| Update | 0.003 μs/op | 0.003 μs/op | 1.06x | Octree (1.1x faster) |
| Removal | 0.000 μs/op | 0.000 μs/op | 0.45x | Tetree (2.2x faster) |
| Memory | 1.39 MB | 0.28 MB | 19.9% | Tetree |

### 10,000 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 1.097 μs/op | 12.481 μs/op | 11.38x | Octree (11.4x faster) |
| K-NN Search | 20.160 μs/op | 6.248 μs/op | 0.31x | Tetree (3.2x faster) |
| Range Query | 20.146 μs/op | 5.791 μs/op | 0.29x | Tetree (3.5x faster) |
| Update | 0.002 μs/op | 0.026 μs/op | 12.48x | Octree (12.5x faster) |
| Removal | 0.001 μs/op | 0.002 μs/op | 1.83x | Octree (1.8x faster) |
| Memory | 12.90 MB | 2.64 MB | 20.5% | Tetree |

## Analysis

### Key Findings

1. **Insertion Performance Gap**: The Tetree insertion performance degrades significantly with larger datasets, from near-parity at 100 entities to 11.4x slower at 10K entities. This is due to the O(level) cost of tmIndex() operations.

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