# Spatial Index Optimization Guide

This guide explains how to optimize spatial index operations for maximum performance in the Luciferase system.

## Overview

The Luciferase system provides two spatial index implementations:

- **Octree**: Uses Morton encoding (simple bit interleaving)
- **Tetree**: Uses tetrahedral decomposition with TM-index

## Performance Trade-offs

### Key Performance Characteristics (July 2025 Update)

**CURRENT STATE**: After all optimizations, Tetree is now competitive for most use cases.

| Operation       | Octree         | Tetree          | Notes                            |
|-----------------|----------------|-----------------|----------------------------------|
| **Insertion**   | ~2.5 μs/entity | ~7.8 μs/entity  | Octree is **3-7x faster**        |
| **k-NN Search** | ~4.1 μs        | ~2.2 μs         | Tetree is **1.9x faster**        |
| **Range Query** | ~21.7 μs       | ~207 μs         | Octree is **9.6x faster**        |
| **Update**      | ~0.002 μs      | ~0.005 μs       | Octree is **2.5x faster**        |
| **Memory**      | Baseline       | 20% of Octree   | Tetree is **80% more efficient** |
| **Child Lookup**| Baseline       | 3x faster       | After July 5 optimization        |
| **Batch Insert**| Baseline       | Superior        | Tetree **74-296x faster**        |

### Root Cause Analysis

The performance difference stems from fundamental algorithmic differences:

1. **Octree (Morton Encoding)**:
    - Simple bit interleaving: O(1) operation
    - Direct coordinate to index mapping
    - No tree traversal required

2. **Tetree (TM-Index)**:
    - Requires parent chain traversal: O(level) operation
    - Despite optimizations, fundamental O(level) cost remains
    - Necessary for global uniqueness across levels

### Optimization History

The Tetree has undergone massive performance improvements:
- **June 24, 2025**: Initial state - 770x slower than Octree
- **June 28, 2025**: V2 tmIndex + parent cache - reduced to 3-7x slower
- **July 5, 2025**: Efficient child computation - 3x faster child lookups
- **Total improvement**: 256-385x performance gain

## Optimization Techniques

### 1. Bulk Operations

Configure bulk operations for large insertions:

```java
BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                .withBatchSize(10000)
                                                .withDeferredSubdivision(true)
                                                .withPreSortByMorton(true);

spatialIndex.

configureBulkOperations(config);
```

### 2. Dynamic Level Selection

Automatically choose optimal insertion levels:

```java
// Enable dynamic level selection
spatialIndex.insertBulk(positions, contents); // Level chosen automatically
```

### 3. Adaptive Subdivision

Prevent unnecessary tree depth:

```java
config.withAdaptiveSubdivision(true)
      .

withSubdivisionThreshold(16); // Max entities per node
```

## Choosing Between Octree and Tetree

### Use Octree When:

- **Insertion performance is critical** (real-time systems)
- **Updates are frequent** (moving entities)
- **Bulk loading large datasets** (millions of entities)
- **Memory is not a primary concern**

### Use Tetree When:

- **Query performance is paramount** (read-heavy workloads)
- **Memory efficiency is critical** (embedded systems)
- **Spatial locality is important** (better cache performance)
- **Insertion happens infrequently** (static datasets)

## Performance Optimization Checklist

1. **Profile First**: Measure your specific use case
2. **Choose the Right Index**: Octree for write-heavy, Tetree for read-heavy
3. **Batch Operations**: Always batch when inserting multiple entities
4. **Pre-sort Data**: Use Morton sorting for better spatial locality
5. **Configure Appropriately**: Use BulkOperationConfig for large operations
6. **Monitor Memory**: Track memory usage, especially for large datasets

## Implementation Notes

### Caching and Optimization

- TetreeLevelCache provides O(1) lookups for frequently used values
- Cache overhead is minimal (~120KB)
- Cannot overcome fundamental O(level) cost of tmIndex()

### Key Classes

- `AbstractSpatialIndex`: Base implementation with shared optimizations
- `Octree`: Fast insertion using Morton encoding
- `Tetree`: Memory-efficient with superior query performance
- `BulkOperationConfig`: Configuration for bulk operations
- `TetreeLevelCache`: Performance optimizations for Tetree

## Common Pitfalls

1. **Assuming Tetree is always faster**: Only true for queries, not insertions
2. **Not batching operations**: Single insertions are much slower
3. **Ignoring memory patterns**: Poor spatial locality hurts performance
4. **Over-optimizing**: Profile first, optimize what matters

## Future Considerations

While optimizations have improved performance, the fundamental algorithmic differences remain:

- Octree will always be faster for insertions due to O(1) Morton encoding
- Tetree will maintain query performance advantages due to better spatial locality
- Hybrid approaches might be worth exploring for specific use cases
