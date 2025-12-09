# Spatial Index Performance Comparison - July 2025

## Executive Summary

Comprehensive performance benchmarks including Octree, Tetree, and Prism spatial indices show distinct performance characteristics for each implementation.

For current performance metrics, see [PERFORMANCE_METRICS_MASTER.md](PERFORMANCE_METRICS_MASTER.md)

## Test Environment

- Platform: Mac OS X aarch64
- JVM: Java HotSpot(TM) 64-Bit Server VM 24
- Processors: 16
- Memory: 512 MB
- Date: July 25, 2025 (updated from July 11, 2025)
- Assertions: Disabled (-da flag)

## Performance Reversal After Concurrent Optimizations

The July 11, 2025 benchmarks show a complete reversal in insertion performance compared to July 8. After implementing ConcurrentSkipListMap and other concurrent optimizations, Tetree now outperforms Octree for insertions at all entity counts. This dramatic shift demonstrates how concurrent data structure choices can fundamentally alter performance characteristics.

### Changes That Led to Reversal

1. **ConcurrentSkipListMap Integration**: Replaced separate HashMap/TreeSet with single ConcurrentSkipListMap
2. **Memory Architecture Change**: Consolidated data structures reduced Octree's efficiency advantage
3. **Lock-Free Operations**: Tetree's simpler key structure benefits more from lock-free concurrent access
4. **ObjectPool Optimization**: Reduced allocation overhead helps Tetree more than Octree

## Analysis

### Key Findings

1. **Insertion Performance Reversal Continues**: Tetree outperforms Octree by 1.8x to 5.7x for insertions as of July 25. The ConcurrentSkipListMap continues to favor Tetree's simpler key comparison operations.

2. **Range Query Performance**: Octree dominates range queries with 3.2x to 8.3x better performance across all scales. This represents a significant advantage for spatial range operations.

3. **Memory Convergence**: Tetree and Octree memory usage has converged significantly. Tetree now uses 80-99% of Octree's memory, with the difference becoming negligible at larger scales (only 1% difference at 10K entities).

4. **Update Performance**: Tetree consistently outperforms Octree for updates by 1.7x to 3.0x across all scales, showing strong performance for entity movement operations.

### Root Cause Analysis

The performance reversal appears to be due to:

- **ConcurrentSkipListMap Characteristics**: The skip list structure favors simpler key comparisons, benefiting Tetree
- **Reduced Lock Contention**: Tetree's operations may have less contention in the concurrent structure
- **Memory Layout**: Consolidated data structures may have better cache locality for Tetree operations
- **tmIndex() Cost Amortized**: The overhead of O(level) operations is offset by concurrent structure benefits

The fundamental O(level) vs O(1) algorithmic difference remains, but is overshadowed by concurrent data structure performance characteristics.

### Prism Analysis

Prism represents a third approach to spatial indexing using rectangular subdivision:

1. **Insertion Performance**: Prism is consistently slower than both alternatives, ranging from 1.42x slower than Octree to 4x slower than Tetree. This suggests overhead from its more complex subdivision logic.

2. **Query Performance**: Prism shows the weakest query performance, being 2.58-2.78x slower for k-NN searches compared to the best performer. This may be due to less efficient spatial locality in its rectangular subdivision.

3. **Memory Usage**: Prism has the highest memory footprint, using 22-29% more memory than Octree and significantly more than Tetree. The rectangular subdivision strategy appears to create more overhead.

4. **Use Case**: Prism may be better suited for anisotropic data distributions where cubic or tetrahedral subdivision creates inefficiencies, though current benchmarks use uniform random distributions.

### Use Case Recommendations

**Choose Octree when:**

- Range query performance is critical (3.2x to 8.3x faster)
- K-NN search performance at scale (>10K entities) matters
- Cube-based spatial subdivision fits the problem domain
- Predictable, consistent performance is required
- Memory differences are not significant

**Choose Tetree when:**

- Insertion performance is the primary concern (1.8x to 5.7x faster)
- Update/movement performance matters (1.7x to 3.0x faster)
- Working with concurrent workloads
- Tetrahedral geometry provides natural fit for problem domain
- Overall write-heavy workloads dominate

**Choose Prism when:**

- Data exhibits strong directional bias or anisotropy
- Rectangular subdivision matches the problem domain
- Memory usage is not a primary concern
- Custom subdivision strategies are needed
- Working with streaming or columnar data patterns

## Additional Performance Metrics

### Collision Detection Performance

| Entity Count | Octree Insertion | Octree Detection | Tetree Insertion | Tetree Detection |
| -------------- | ------------------ | ------------------ | ------------------ | ------------------ |
| 800 | 1.3 ms | 1.5 ms | 33 ms | 91 ms |
| 1,500 | 2.2 ms | 2.7 ms | 12 ms | 208 ms |
| 2,000 | 3.0 ms | 3.2 ms | - | - |

Octree maintains consistent performance while Tetree degrades significantly with scale.

### Ray Intersection Performance

| Rays | Entities | Octree Time | Throughput |
| ------ | ---------- | ------------- | ------------ |
| 100 | 1,000 | 10 ms | 10K rays/sec |
| 100 | 5,000 | 48 ms | 2K rays/sec |
| 100 | 10,000 | 96 ms | 1K rays/sec |

Both implementations scale linearly with entity count for ray operations.

### TMIndex Performance Degradation

| Level | tmIndex Time | Degradation vs Level 1 |
| ------- | -------------- | ------------------------ |
| 1 | 0.069 μs | 1.0x |
| 5 | 0.081 μs | 1.2x |
| 10 | 0.147 μs | 2.1x |
| 15 | 0.303 μs | 4.4x |
| 20 | 0.975 μs | 14.1x |

This O(level) degradation was the core reason for Tetree's insertion performance issues in the July 8 benchmarks, but concurrent optimizations have reversed the performance characteristics.

### Optimization Effectiveness

| Optimization | Impact | Benefit |
| -------------- | -------- | --------- |
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

### Prism Integration (July 12, 2025)

- Added as third spatial index option
- Positioned between Octree and Tetree for most operations
- Higher memory usage than both alternatives
- Designed for anisotropic data distributions

### Latest Performance Update (July 25, 2025)

- Tetree maintains insertion advantage (1.8x to 5.7x faster)
- Octree shows strong range query performance (3.2x to 8.3x faster)
- Memory usage nearly converged (only 1-20% difference)
- Update performance favors Tetree (1.7x to 3.0x faster)
- DSOC tests fixed to match current implementation behavior

### Optimization Timeline

- Parent caching: 17.3x speedup
- V2 tmIndex: 4x speedup  
- Subdivision fixes: 38-96% improvement
- Level caching: O(1) level extraction
- Single-child optimization: 3x speedup
- Lazy evaluation: Prevents memory exhaustion
- ConcurrentSkipListMap: Reversed insertion performance advantage
- Prism addition: Provides alternative for directional data

The fundamental algorithmic differences remain, but concurrent data structure characteristics and use case fit now dominate performance.
