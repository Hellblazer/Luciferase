# Octree vs Tetree Performance Comparison - July 2025

## Executive Summary

Comprehensive performance benchmarks updated July 11, 2025 show significant performance shifts after concurrent optimizations:

- **Insertion**: Tetree is now 2.1x to 6.2x faster (complete reversal from July 8)
- **K-NN Search**: Tetree is 1.1x to 1.6x faster at lower counts, Octree 1.2x faster at 10K
- **Range Queries**: Tetree maintains 1.4x to 3.8x advantage
- **Updates**: Mixed results (Tetree faster at 100 entities, Octree faster at 10K)
- **Memory**: Tetree now uses 65-73% of Octree's memory (up from 20-23%)
- **Collision Detection**: Octree handles 2K entities efficiently, Tetree degrades at scale
- **Ray Intersection**: Both scale linearly, Octree more consistent

## Test Environment

- Platform: Mac OS X aarch64
- JVM: Java HotSpot(TM) 64-Bit Server VM 24
- Processors: 16
- Memory: 512 MB
- Date: July 11, 2025 (updated from July 8, 2025)
- Assertions: Disabled (-da flag)

## Detailed Results

### 100 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 12.584 μs/op | 5.924 μs/op | 0.47x | Tetree (2.1x faster) |
| K-NN Search | 0.827 μs/op | 0.754 μs/op | 0.91x | Tetree (1.1x faster) |
| Range Query | 0.464 μs/op | 0.314 μs/op | 0.68x | Tetree (1.5x faster) |
| Update | 0.131 μs/op | 0.093 μs/op | 0.71x | Tetree (1.4x faster) |
| Removal | 0.023 μs/op | 0.010 μs/op | 0.43x | Tetree (2.3x faster) |
| Memory | 0.16 MB | 0.12 MB | 73.1% | Tetree |

### 1,000 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 17.892 μs/op | 4.721 μs/op | 0.26x | Tetree (3.8x faster) |
| K-NN Search | 4.153 μs/op | 2.635 μs/op | 0.63x | Tetree (1.6x faster) |
| Range Query | 1.988 μs/op | 0.811 μs/op | 0.41x | Tetree (2.5x faster) |
| Update | 0.003 μs/op | 0.004 μs/op | 1.10x | Octree (1.1x faster) |
| Removal | 0.001 μs/op | 0.000 μs/op | 0.59x | Tetree (1.7x faster) |
| Memory | 1.44 MB | 0.94 MB | 65.3% | Tetree |

### 10,000 Entities

| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 36.871 μs/op | 5.968 μs/op | 0.16x | Tetree (6.2x faster) |
| K-NN Search | 19.234 μs/op | 23.457 μs/op | 1.22x | Octree (1.2x faster) |
| Range Query | 22.641 μs/op | 5.931 μs/op | 0.26x | Tetree (3.8x faster) |
| Update | 0.002 μs/op | 0.033 μs/op | 15.29x | Octree (15.3x faster) |
| Removal | 0.001 μs/op | 0.003 μs/op | 2.36x | Octree (2.4x faster) |
| Memory | 13.59 MB | 9.12 MB | 67.1% | Tetree |

## Performance Reversal After Concurrent Optimizations

The July 11, 2025 benchmarks show a complete reversal in insertion performance compared to July 8. After implementing ConcurrentSkipListMap and other concurrent optimizations, Tetree now outperforms Octree for insertions at all entity counts. This dramatic shift demonstrates how concurrent data structure choices can fundamentally alter performance characteristics.

### Changes That Led to Reversal

1. **ConcurrentSkipListMap Integration**: Replaced separate HashMap/TreeSet with single ConcurrentSkipListMap
2. **Memory Architecture Change**: Consolidated data structures reduced Octree's efficiency advantage
3. **Lock-Free Operations**: Tetree's simpler key structure benefits more from lock-free concurrent access
4. **ObjectPool Optimization**: Reduced allocation overhead helps Tetree more than Octree

## Analysis

### Key Findings

1. **Insertion Performance Reversal**: Tetree now outperforms Octree by 2.1x to 6.2x for insertions, a complete reversal from the July 8 results. The ConcurrentSkipListMap appears to favor Tetree's simpler key comparison operations.

2. **Search Performance Moderation**: K-NN search advantages have moderated. Tetree maintains small advantages at lower entity counts but Octree is 1.2x faster at 10K entities.

3. **Memory Trade-off**: Tetree now uses 65-73% of Octree's memory (up from 20-25%), likely due to ConcurrentSkipListMap overhead and consolidated data structures.

4. **Update Performance**: Results remain mixed, with Tetree performing better at low entity counts but Octree dominating at high counts.

### Root Cause Analysis

The performance reversal appears to be due to:
- **ConcurrentSkipListMap Characteristics**: The skip list structure favors simpler key comparisons, benefiting Tetree
- **Reduced Lock Contention**: Tetree's operations may have less contention in the concurrent structure
- **Memory Layout**: Consolidated data structures may have better cache locality for Tetree operations
- **tmIndex() Cost Amortized**: The overhead of O(level) operations is offset by concurrent structure benefits

The fundamental O(level) vs O(1) algorithmic difference remains, but is overshadowed by concurrent data structure performance characteristics.

### Use Case Recommendations

**Choose Octree when:**
- K-NN search performance at scale (>10K entities) is critical
- Update performance matters at scale
- Memory usage differences (65-73% vs 100%) are not significant
- Cube-based spatial decomposition fits the problem domain

**Choose Tetree when:**
- Insertion performance is the primary concern
- Range query performance is important
- Working with concurrent workloads
- Tetrahedral geometry provides natural fit for problem domain
- Memory savings of 27-35% are valuable

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

This O(level) degradation was the core reason for Tetree's insertion performance issues in the July 8 benchmarks, but concurrent optimizations have reversed the performance characteristics.

### Optimization Effectiveness

| Optimization | Impact | Benefit |
|--------------|--------|---------|
| Parent caching | 17.3x speedup | Reduces parent() from O(level) to O(1) |
| Single-child computation | 3.0x speedup | 17 ns vs 52 ns per child |
| V2 tmIndex | 4.0x speedup | Simplified algorithm |
| Lazy evaluation | 99.5% memory reduction | Enables unbounded ranges |
| Bulk operations | 15.5x speedup (Tetree) | Amortizes tmIndex cost |
| ConcurrentSkipListMap | Performance reversal | Tetree now 2.1-6.2x faster for insertions |
| ObjectPool integration | Reduced GC pressure | Significant allocation reduction |

## Historical Context

### Pre-Concurrent Optimization (July 8, 2025)
- Octree was 1.3x to 15.3x faster for insertions
- Tetree used only 20-23% of Octree's memory
- Performance gap increased with entity count

### Post-Concurrent Optimization (July 11, 2025)
- Complete reversal: Tetree now 2.1x to 6.2x faster for insertions
- Memory usage increased to 65-73% of Octree
- ConcurrentSkipListMap fundamentally changed performance dynamics

### Optimization Timeline
- Parent caching: 17.3x speedup
- V2 tmIndex: 4x speedup  
- Subdivision fixes: 38-96% improvement
- Level caching: O(1) level extraction
- Single-child optimization: 3x speedup
- Lazy evaluation: Prevents memory exhaustion
- ConcurrentSkipListMap: Reversed insertion performance advantage

The fundamental O(level) vs O(1) algorithmic difference remains, but concurrent data structure characteristics now dominate performance.