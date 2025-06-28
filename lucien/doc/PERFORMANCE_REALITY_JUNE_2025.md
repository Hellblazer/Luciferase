# Spatial Index Performance Reality - June 2025 (Updated)

## Executive Summary

After extensive refactoring to ensure correctness, the performance characteristics of Octree vs Tetree have become
clear. **Octree is vastly superior for insertions (up to 770x faster) while Tetree excels at queries (3-4x faster)**.

**Data Source**: Performance metrics from OctreeVsTetreeBenchmark.java run on June 28, 2025.

## The Root Cause

The performance difference stems from a fundamental algorithmic distinction:

### Index Methods Comparison

| Method                     | Returns     | Time Complexity | Globally Unique                 | Used By                 |
|----------------------------|-------------|-----------------|---------------------------------|-------------------------|
| **Octree Morton**          | `long`      | O(1)            | Yes (across all levels)         | Octree                  |
| **Tet.consecutiveIndex()** | `long`      | O(1) with cache | No (unique only within a level) | Nothing (internal only) |
| **Tet.tmIndex()**          | `TetreeKey` | O(level)        | Yes (across all levels)         | Tetree                  |

### Why tmIndex() is Slow

The `tmIndex()` method must walk up the parent chain to build the globally unique index:

- Level 1: 3.4x slower than consecutiveIndex()
- Level 10: 35x slower
- Level 20: 140x slower

This is **not a bug** - it's required for correctness. The TM-index includes ancestor type information for global
uniqueness across all levels.

## Actual Performance Measurements (Post-Subdivision Fix - June 28, 2025)

### Insertion Performance

| Entities | Octree Time | Tetree Time | Octree Advantage | Improvement | Per-Entity (Tetree) |
|----------|-------------|-------------|------------------|-------------|---------------------|
| 100      | ~0.8 ms     | ~4.8 ms     | **6.0x faster**  | 38% better  | ~48 μs              |
| 1K       | ~3.0 ms     | ~27.6 ms    | **9.2x faster**  | 84% better  | ~28 μs              |
| 10K      | ~10.5 ms    | ~363 ms     | **34.6x faster** | 96% better  | ~36 μs              |

**Note**: Performance dramatically improved after fixing Tetree's subdivision logic. Previously, Tetree was creating only 2 nodes for 1000 entities instead of properly subdividing like Octree.

### Query Performance

| Entities | Operation | Octree  | Tetree  | Tetree Advantage |
|----------|-----------|---------|---------|------------------|
| 100      | k-NN      | 0.93 μs | 0.43 μs | **2.2x faster**  |
| 100      | Range     | 0.47 μs | 0.33 μs | **1.4x faster**  |
| 1K       | k-NN      | 3.22 μs | 0.81 μs | **4.0x faster**  |
| 1K       | Range     | 2.00 μs | 0.55 μs | **3.6x faster**  |
| 10K      | k-NN      | 21.9 μs | 7.04 μs | **3.1x faster**  |
| 10K      | Range     | 21.4 μs | 5.49 μs | **3.9x faster**  |

### Memory Usage

| Entities | Octree Memory | Tetree Memory | Tetree Usage % |
|----------|---------------|---------------|----------------|
| 100      | 0.15 MB       | 0.16 MB       | **102.9%**     |
| 1K       | 1.39 MB       | 1.28 MB       | **92.1%**      |
| 10K      | 12.91 MB      | 12.64 MB      | **97.9%**      |

**Note**: After fixing the subdivision bug, memory usage is now comparable. The previous 20% usage was due to Tetree creating 500x fewer nodes than it should have.

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
