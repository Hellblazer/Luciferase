# Spatial Index Performance Reality - December 2025

## Executive Summary

After extensive refactoring to ensure correctness, the performance characteristics of Octree vs Tetree have become clear. **Octree is vastly superior for insertions (1125x faster) while Tetree excels at queries (5x faster)**.

## The Root Cause

The performance difference stems from a fundamental algorithmic distinction:

### Index Methods Comparison

| Method | Returns | Time Complexity | Globally Unique | Used By |
|--------|---------|----------------|-----------------|---------|
| **Octree Morton** | `long` | O(1) | Yes (across all levels) | Octree |
| **Tet.consecutiveIndex()** | `long` | O(1) with cache | No (unique only within a level) | Nothing (internal only) |
| **Tet.tmIndex()** | `TetreeKey` | O(level) | Yes (across all levels) | Tetree |

### Why tmIndex() is Slow

The `tmIndex()` method must walk up the parent chain to build the globally unique index:
- Level 1: 3.4x slower than consecutiveIndex()
- Level 10: 35x slower
- Level 20: 140x slower

This is **not a bug** - it's required for correctness. The TM-index includes ancestor type information for global uniqueness across all levels.

## Actual Performance Measurements

### Insertion Performance
| Entities | Octree | Tetree | Octree Advantage |
|----------|--------|---------|------------------|
| 100 | 0.46 ms | 4.4 ms | 9.5x faster |
| 1K | 2.7 ms | 48 ms | 17.5x faster |
| 10K | 10.5 ms | 3,044 ms | 288x faster |
| 50K | 75 ms | 84,483 ms | **1,125x faster** |

### Query Performance
| Operation | Octree | Tetree | Tetree Advantage |
|-----------|--------|---------|------------------|
| k-NN (k=10) | 28 μs | 5.9 μs | 4.8x faster |
| Range Query | 28 μs | 5.6 μs | 5x faster |
| Frustum Culling | 250 μs | 50 μs | 5x faster |

### Memory Usage
- Octree: 100% (baseline)
- Tetree: 22% of Octree's memory

## What This Means for Users

### Choose Octree When:
1. **Real-time insertions** - Games, simulations with moving objects
2. **Bulk loading** - Importing large datasets
3. **Frequent updates** - Dynamic environments
4. **Balanced workloads** - Mix of insertions and queries

### Choose Tetree When:
1. **Static datasets** - Load once, query many times
2. **Memory constrained** - Embedded systems, mobile devices
3. **Query-heavy workloads** - Spatial databases, GIS systems
4. **Offline processing** - Where insertion time doesn't matter

## Optimization Attempts and Results

### What Worked:
- **TetreeLevelCache**: Improved some operations but can't fix O(level) parent walk
- **Bulk operations**: Help both implementations equally
- **Pre-sorting**: Improves spatial locality for both

### What Didn't Work:
- Cannot make tmIndex() O(1) without breaking correctness
- Parent chain caching helps but doesn't eliminate the traversal
- The fundamental algorithm difference cannot be optimized away

## Lessons Learned

1. **Correctness comes first**: The refactoring to use tmIndex() was necessary for correctness
2. **Algorithms matter**: No amount of optimization can overcome O(1) vs O(level) differences
3. **Trade-offs are real**: You can have fast insertion OR fast queries OR low memory, not all three
4. **Documentation drift**: Performance claims must be continuously validated

## Future Directions

### Potential Improvements:
1. **Hybrid approach**: Use Octree for insertion, convert to Tetree for querying
2. **Lazy indexing**: Defer tmIndex() computation until needed
3. **Specialized variants**: Different implementations for different use cases

### What Won't Change:
- Morton encoding will always be O(1)
- tmIndex() will always require parent traversal
- The fundamental trade-offs between the approaches

## Conclusion

Both spatial indices have their place:
- **Octree**: The workhorse for general-purpose spatial indexing
- **Tetree**: The specialist for query-intensive, memory-constrained applications

The key is choosing the right tool for your specific requirements.