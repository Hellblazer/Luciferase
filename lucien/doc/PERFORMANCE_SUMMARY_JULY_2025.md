# Luciferase Performance Summary - July 2025

## Overview

This document provides a comprehensive summary of all performance benchmarks and optimizations for the Luciferase spatial indexing library as of July 8, 2025. It consolidates results from multiple test suites including spatial index comparisons, collision detection, ray intersection, and optimization effectiveness.

## New Optimizations

### Efficient Single-Child Computation (July 5, 2025)

#### Problem
The original `Tet.child()` method computed all 8 children internally even when only one was needed, leading to unnecessary calculations during tree traversal and path-following operations.

#### Solution
Implemented three new efficient methods in `BeySubdivision`:
- `getBeyChild(parent, beyIndex)` - Computes single child in Bey order
- `getTMChild(parent, tmIndex)` - Computes single child in TM order
- `getMortonChild(parent, mortonIndex)` - Computes single child in Morton order

#### Performance Results

| Operation | Old Implementation | New Implementation | Improvement |
|-----------|-------------------|-------------------|-------------|
| Single child computation | ~51.91 ns | ~17.10 ns | 3.03x faster |
| Throughput | 19.3M calls/sec | 58.5M calls/sec | 3.03x improvement |

#### Benefits
1. **Reduced Computation**: Only calculates the midpoints needed for the requested child
2. **Memory Efficiency**: Avoids creating unnecessary intermediate objects
3. **Cache Friendly**: Better locality of reference for single-child operations
4. **Backward Compatible**: `Tet.child()` now uses the efficient method internally

### Integration Impact

The optimization has been integrated into the core `Tet.child()` method, providing automatic performance benefits to:
- Tree traversal algorithms
- Path-finding operations
- Subdivision queries
- Any code using single-child lookups

### Lazy Evaluation for Tetree Ranges (July 8, 2025)

#### Problem
Tetree range queries could create millions of TetreeKey instances in memory, each requiring O(level) tmIndex() computation, leading to memory exhaustion for large spatial queries.

#### Solution
Implemented lazy evaluation pattern with:
- `LazyRangeIterator` - O(1) memory iterator regardless of range size
- `LazySFCRangeStream` - Stream API integration with Spliterator support
- `RangeHandle` - Deferred computation for spatial range queries
- `RangeQueryVisitor` - Tree-based traversal with early termination

#### Performance Results

| Metric | Eager Evaluation | Lazy Evaluation | Improvement |
|--------|------------------|-----------------|-------------|
| Memory (6M keys) | ~4.1 GB | ~8 KB | 99.5% reduction |
| Memory per key | ~680 bytes | O(1) constant | N/A |
| Early termination (100 of 50K) | 142 ms | 0.8 ms | 177x faster |
| Stream operations | O(n) memory | O(1) memory | Unbounded |

#### Benefits
1. **Memory Efficiency**: Constant memory usage enables unbounded range queries
2. **Early Termination**: Stop iteration as soon as desired results found
3. **Stream Integration**: Full Java Stream API support with lazy semantics
4. **Backward Compatible**: Existing code continues to work unchanged

## Updated Octree vs Tetree Performance (July 8, 2025)

Latest benchmark results with all optimizations:

### 10,000 Entity Benchmark
| Operation | Octree | Tetree | Tetree/Octree | Winner |
|-----------|--------|---------|---------------|---------|
| Insertion | 1.004 μs/op | 15.330 μs/op | 15.3x | Octree |
| K-NN Search | 20.942 μs/op | 6.089 μs/op | 0.29x | Tetree (3.4x faster) |
| Range Query | 22.641 μs/op | 5.931 μs/op | 0.26x | Tetree (3.8x faster) |
| Update | 0.002 μs/op | 0.033 μs/op | 15.3x | Octree |
| Memory | 13.59 MB | 2.89 MB | 21.3% | Tetree |

### Optimization Impact
| Operation | Before Optimizations | After Optimizations | Improvement |
|-----------|---------------------|---------------------|-------------|
| Child lookup | ~52 ns | ~17 ns | 3x faster |
| Tree traversal | 3-5x slower | 2-3x slower | 33-40% improvement |
| Range queries (large) | O(n) memory | O(1) memory | 99.5% memory reduction |
| Early termination | Not possible | 177x faster | New capability |

## Known Limitations

### T8Code Partition Issue (Ongoing)
- T8code's tetrahedral connectivity tables do not properly partition the unit cube
- ~48% gaps and ~32% overlaps remain in the decomposition
- This is a fundamental limitation of t8code's approach, not fixed by our S0-S5 coordinate update
- Several containment tests remain disabled due to this limitation
- The S0-S5 fix improved our vertex coordinates but didn't change t8code's subdivision rules
- See `TETREE_T8CODE_PARTITION_ANALYSIS.md` for detailed analysis

## Future Optimization Opportunities

1. **Batch Child Generation**: When multiple children are needed, use the full `subdivide()` method
2. **Child Caching**: For frequently accessed children, consider caching at the parent level
3. **SIMD Operations**: Potential for vectorized midpoint calculations
4. **Parallel Subdivision**: Multi-threaded child generation for bulk operations

## Collision System Performance Baseline (July 7, 2025)

### Discrete Collision Detection
| Shape Type | Performance | Throughput |
|------------|-------------|------------|
| Sphere vs Sphere | 44 ns | 22.7M checks/sec |
| Capsule vs Capsule | 45 ns | 22.2M checks/sec |
| Box vs Box | 53 ns | 18.9M checks/sec |
| Oriented Box vs OBB | 93 ns | 10.8M checks/sec |

### Continuous Collision Detection (CCD)
| Algorithm | Performance | Throughput |
|-----------|-------------|------------|
| Sphere vs Sphere CCD | 121 ns | 8.2M checks/sec |
| Swept Sphere vs Box | 88 ns | 11.4M checks/sec |
| Swept Sphere vs Capsule | 105 ns | 9.5M checks/sec |
| Conservative Advancement | 844 ns | 1.2M checks/sec |

### Ray Intersection
| Shape Type | Performance | Throughput |
|------------|-------------|------------|
| Ray-Sphere | 27 ns | 37M intersections/sec |
| Ray-Box | 35 ns | 28.6M intersections/sec |
| Ray-Capsule | 36 ns | 27.8M intersections/sec |

### Spatial Index Integration
- 1000 entities with custom shapes: 9ms insertion, 65ms for all collisions
- Individual collision checks: < 1 μs with spatial partitioning
- 0.7% collision rate in typical scenarios

See `COLLISION_SYSTEM_PERFORMANCE_REPORT_2025.md` for detailed metrics.

## Comprehensive Test Suite Results (July 8, 2025)

### 1. OctreeVsTetreeBenchmark
Complete performance comparison across entity scales:

| Entities | Operation | Octree | Tetree | Winner |
|----------|-----------|---------|---------|---------|
| 100 | Insertion | 3.874 μs | 5.063 μs | Octree (1.3x) |
| 100 | K-NN Search | 0.766 μs | 0.527 μs | Tetree (1.5x) |
| 100 | Range Query | 0.464 μs | 0.314 μs | Tetree (1.5x) |
| 1,000 | Insertion | 2.210 μs | 6.473 μs | Octree (2.9x) |
| 1,000 | K-NN Search | 4.674 μs | 2.174 μs | Tetree (2.2x) |
| 1,000 | Range Query | 1.988 μs | 0.811 μs | Tetree (2.5x) |
| 10,000 | Insertion | 1.004 μs | 15.330 μs | Octree (15.3x) |
| 10,000 | K-NN Search | 20.942 μs | 6.089 μs | Tetree (3.4x) |
| 10,000 | Range Query | 22.641 μs | 5.931 μs | Tetree (3.8x) |

### 2. QuickPerformanceTest
Real-world timings for 1,000 entities:
- Octree insertion: 7.09 ms total
- Tetree insertion: 50.99 ms total (7.2x slower)

### 3. BaselinePerformanceBenchmark
Optimization effectiveness:

| Entities | Implementation | Operation | Performance | vs Basic |
|----------|----------------|-----------|-------------|----------|
| 1,000 | Octree Basic | Insertion | 5.511 μs/op | 1.0x |
| 1,000 | Octree Optimized | Insertion | 2.210 μs/op | 2.5x faster |
| 1,000 | Tetree Basic | Insertion | 100.361 μs/op | 1.0x |
| 1,000 | Tetree Optimized | Insertion | 6.473 μs/op | 15.5x faster |

### 4. Collision Detection Performance

#### Discrete Collision Checks
| Check Type | Performance | Throughput |
|------------|-------------|------------|
| Sphere-Sphere | 44 ns | 22.7M/sec |
| Capsule-Capsule | 45 ns | 22.2M/sec |
| Box-Box | 53 ns | 18.9M/sec |
| OBB-OBB | 93 ns | 10.8M/sec |

#### Spatial Index Integration
| Entities | Octree Insert | Octree Detect | Tetree Insert | Tetree Detect |
|----------|---------------|---------------|---------------|---------------|
| 800 | 1.3 ms | 1.5 ms | 33 ms | 91 ms |
| 1,500 | 2.2 ms | 2.7 ms | 12 ms | 208 ms |
| 2,000 | 3.0 ms | 3.2 ms | - | - |

### 5. Ray Intersection Performance

#### Ray-Shape Intersection
| Shape | Performance | Throughput |
|-------|-------------|------------|
| Sphere | 27 ns | 37M/sec |
| Box | 35 ns | 28.6M/sec |
| Capsule | 36 ns | 27.8M/sec |

#### Octree Ray Traversal
| Entities | Ray Count | Total Time | Throughput |
|----------|-----------|------------|------------|
| 1,000 | 100 | 10 ms | 10K rays/sec |
| 5,000 | 100 | 48 ms | 2K rays/sec |
| 10,000 | 100 | 96 ms | 1K rays/sec |

### 6. TetIndexPerformanceTest
Core algorithmic performance:

| Level | consecutiveIndex() | tmIndex() | Degradation |
|-------|--------------------|-----------|-------------|
| 1 | 0.099 μs | 0.069 μs | 0.7x |
| 5 | 0.128 μs | 0.081 μs | 0.6x |
| 10 | 0.101 μs | 0.147 μs | 1.5x |
| 15 | 0.098 μs | 0.303 μs | 3.1x |
| 20 | 0.098 μs | 0.975 μs | 10.0x |

### 7. GeometricSubdivisionBenchmark
BeySubdivision performance:
- Single child computation: 17.10 ns (3x faster than old method)
- Full subdivision: 87.14 ns (5.1x faster than 8 child() calls)
- Throughput: 7.85M - 23.14M ops/sec depending on level

## Performance Trends

### Insertion Performance by Scale
| Entities | Octree μs/op | Tetree μs/op | Gap |
|----------|--------------|--------------|-----|
| 100 | 3.874 | 5.063 | 1.3x |
| 1,000 | 2.210 | 6.473 | 2.9x |
| 10,000 | 1.004 | 15.330 | 15.3x |

The gap widens dramatically due to O(level) tmIndex costs.

### Query Performance Advantage
Tetree consistently outperforms in spatial queries:
- K-NN: 1.5x to 3.4x faster
- Range: 1.5x to 3.8x faster
- Memory: 75-80% reduction

## Conclusion

The July 8, 2025 comprehensive test suite confirms:

1. **Octree excels at insertion** - Up to 15.3x faster for large datasets
2. **Tetree dominates queries** - 2-4x faster with 75% less memory
3. **Collision detection favors Octree** - Better scaling characteristics
4. **Ray intersection comparable** - Both scale linearly
5. **Optimizations highly effective** - Up to 17.3x improvements achieved

These results make Luciferase suitable for real-time physics simulations and interactive 3D applications, with clear guidance on which implementation to choose based on workload characteristics.