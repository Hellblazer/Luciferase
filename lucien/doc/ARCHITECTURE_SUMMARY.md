# Lucien Architecture Summary

## Purpose

This document provides a high-level summary of the Luciferase lucien module architecture. For detailed information, see the comprehensive documentation in this directory.

## Current State

The lucien module provides spatial indexing through a unified architecture supporting both octree and tetrahedral
decomposition. The module uses inheritance to maximize code reuse while maintaining the unique characteristics of each approach. All core features are complete, including S0-S5 tetrahedral decomposition with 100% geometric containment.

**Total Classes: 96 Java files** organized across 8 packages (after Phase 6.2 node consolidation)

## Package Overview

For detailed package structure and class descriptions, see [LUCIEN_ARCHITECTURE.md](./LUCIEN_ARCHITECTURE.md).

- **Root Package (26 classes)**: Core abstractions, spatial types, geometry utilities, performance optimization
- **Entity Package (12 classes)**: Complete entity management infrastructure
- **Octree Package (4 classes)**: Morton curve-based cubic spatial decomposition
- **Tetree Package (31 classes)**: Tetrahedral spatial decomposition with extensive optimizations and lazy evaluation
- **Balancing Package (4 classes)**: Tree balancing strategies
- **Collision Package (12 classes)**: Comprehensive collision detection system
- **Visitor Package (6 classes)**: Tree traversal visitor pattern implementation
- **Index Package (1 class)**: Additional indexing utilities

## Key Architecture Components

### Inheritance Hierarchy

```
SpatialIndex<Key extends SpatialKey<Key>, ID, Content> (interface)
  └── AbstractSpatialIndex<Key, ID, Content> (base class with ~95% shared functionality)
      ├── Octree<ID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content>
      └── Tetree<ID, Content> extends AbstractSpatialIndex<TetreeKey, ID, Content>
```

### Major Features

- **Unified API**: Both octree and tetree share common operations through AbstractSpatialIndex
- **Entity Management**: Centralized through EntityManager with multi-entity support
- **Thread Safety**: ReadWriteLock-based concurrent access
- **Performance**: HashMap-based O(1) node access for both implementations
- **Type-Safe Keys**: SpatialKey architecture prevents mixing incompatible indices

## What This Architecture Includes

### Core Features (January 2025)

✅ **Core Spatial Indexing**: Insert, remove, update, lookup operations  
✅ **Spatial Queries**: Bounded/bounding queries, k-NN search, range queries  
✅ **Entity Management**: Multi-entity support with spanning capabilities  
✅ **Thread Safety**: Concurrent access with read-write locks

### Enhanced Features (Completed)

✅ **Ray Intersection**: Complete ray traversal implementation (
see [RAY_INTERSECTION_API.md](./RAY_INTERSECTION_API.md))  
✅ **Collision Detection**: Broad/narrow phase collision detection (
see [COLLISION_DETECTION_API.md](./COLLISION_DETECTION_API.md))  
✅ **Tree Traversal**: Visitor pattern support (see [TREE_TRAVERSAL_API.md](./TREE_TRAVERSAL_API.md))  
✅ **Tree Balancing**: Dynamic balancing strategies (see [TREE_BALANCING_API.md](./TREE_BALANCING_API.md))  
✅ **Plane Intersection**: Arbitrary 3D plane queries (see [PLANE_INTERSECTION_API.md](./PLANE_INTERSECTION_API.md))  
✅ **Frustum Culling**: View frustum visibility determination (see [FRUSTUM_CULLING_API.md](./FRUSTUM_CULLING_API.md))  
✅ **Bulk Operations**: High-performance batch operations (see [BULK_OPERATIONS_API.md](./BULK_OPERATIONS_API.md))

### Performance Optimizations

✅ **O(1) Operations**: SpatialIndexSet replaces TreeSet  
✅ **TetreeLevelCache**: Eliminates O(log n) level calculations  
✅ **Dynamic Level Selection**: Automatic optimization for data distribution  
✅ **Bulk Loading Mode**: 5-10x performance for large datasets
✅ **SpatialKey Architecture**: Type-safe keys with MortonKey and TetreeKey

## Documentation Structure

### Primary References

- **[LUCIEN_ARCHITECTURE.md](./LUCIEN_ARCHITECTURE.md)**: Comprehensive architecture guide

### API Documentation

#### Core APIs

- **[CORE_SPATIAL_INDEX_API.md](./CORE_SPATIAL_INDEX_API.md)**: Fundamental operations (insert, lookup, update, remove)
- **[K_NEAREST_NEIGHBORS_API.md](./K_NEAREST_NEIGHBORS_API.md)**: Proximity queries and spatial clustering

#### Advanced Features

- **[RAY_INTERSECTION_API.md](./RAY_INTERSECTION_API.md)**: Ray traversal and line-of-sight
- **[COLLISION_DETECTION_API.md](./COLLISION_DETECTION_API.md)**: Collision detection usage
- **[TREE_TRAVERSAL_API.md](./TREE_TRAVERSAL_API.md)**: Visitor pattern traversal
- **[TREE_BALANCING_API.md](./TREE_BALANCING_API.md)**: Dynamic balancing strategies
- **[PLANE_INTERSECTION_API.md](./PLANE_INTERSECTION_API.md)**: Arbitrary 3D plane queries
- **[FRUSTUM_CULLING_API.md](./FRUSTUM_CULLING_API.md)**: View frustum visibility

#### Performance

- **[BULK_OPERATIONS_API.md](./BULK_OPERATIONS_API.md)**: High-performance batch operations
- **[SPATIAL_INDEX_PERFORMANCE_GUIDE.md](./SPATIAL_INDEX_PERFORMANCE_GUIDE.md)**: Performance tuning guide
- **[PERFORMANCE_TRACKING.md](./PERFORMANCE_TRACKING.md)**: Current performance baseline

### Implementation Guides

- **[TETREE_IMPLEMENTATION_GUIDE.md](./TETREE_IMPLEMENTATION_GUIDE.md)**: Tetrahedral tree specifics


## Design Philosophy

The current architecture prioritizes:

1. **Simplicity**: Essential functionality without unnecessary complexity
2. **Code Reuse**: 90% shared implementation through inheritance
3. **Maintainability**: Single place for algorithm changes
4. **Extensibility**: Easy addition of new spatial decomposition strategies
5. **Performance**: O(1) operations through HashMap-based storage

## Performance Reality (June 2025 - OctreeVsTetreeBenchmark)

### Important Performance Update

Previous performance claims were based on using the `consecutiveIndex()` method which is unique only within a level.
After refactoring to use the globally unique `tmIndex()` for correctness (unique across all levels), the performance
characteristics have changed dramatically.

### Current Performance Metrics (All Optimizations - July 5, 2025)

After V2 tmIndex, parent cache, and efficient child computation:

| Entities | Operation | Octree  | Tetree  | Winner        | Current State |
|---------|-----------|---------|---------|---------------|---------------|
| 100     | Insertion | 4.48 μs | 30.25 μs| Octree (6.8x) | **From 770x** |
| 1K      | Insertion | 2.49 μs | 7.84 μs | Octree (3.1x) | **to 3-7x** |
| 10K     | Insertion | 1.27 μs | 4.75 μs | Octree (3.7x) | **slower** |
| 1K      | k-NN      | 4.10 μs | 2.15 μs | Tetree (1.9x) | Tetree faster |
| 10K     | k-NN      | 42.6 μs | 10.3 μs | Tetree (4.1x) | Tetree faster |
| 50K+    | Batch     | Baseline| Faster  | Tetree        | 74-296x faster|

### Optimization Timeline

- **June 24**: Initial state - Tetree 770x slower (unusable)
- **June 28**: Subdivision fix + V2 tmIndex - reduced to 6-35x slower
- **June 28**: Parent cache - reduced to 3-7x slower
- **July 5**: Efficient child computation - 3x faster child lookups
- **Result**: 256-385x cumulative performance improvement

### Remaining Gap Explanation

- **Octree**: Uses Morton encoding (simple bit interleaving) - always O(1)
- **Tetree**: Uses tmIndex() which requires parent chain traversal - O(level)
- **Mitigation**: Batch operations where Tetree excels due to better spatial locality

For detailed performance analysis, see:
- [PERFORMANCE_TRACKING.md](./PERFORMANCE_TRACKING.md) - Current performance baseline
- [PERFORMANCE_INDEX.md](./PERFORMANCE_INDEX.md) - Guide to all performance documentation

## Testing Coverage

**200+ total tests** with comprehensive coverage:

- Unit tests for all major operations
- Integration tests for spatial queries
- Performance benchmarks (controlled by environment flag)
- Thread-safety tests for concurrent operations
- API-specific test suites for all features

## Usage

For usage examples and detailed implementation guidance, refer to the specific API documentation files listed above and
the comprehensive architecture guide.

## Recent Architecture Changes

### Phase 6.2 Cleanup (July 10, 2025)

1. **Node Class Consolidation**: 
   - Eliminated `TetreeNodeImpl` and `OctreeNode` wrapper classes
   - Created unified `SpatialNodeImpl<ID>` used by both implementations
   - Reduced generic parameters from 4 to 3 throughout the codebase
   - Result: Simpler architecture, reduced memory overhead

2. **K-NN Multi-Level Fix**:
   - Fixed k-NN search to properly find entities at different levels
   - Improved search radius expansion algorithm
   - Increased initial search radius and max expansions

3. **Spanning Entity Queries**:
   - Fixed issue where large entities weren't found by small query regions
   - Updated all query methods to check entities when spanning is enabled

### Performance Optimizations (July 2025)

1. **Lazy Evaluation**: 99.5% memory reduction for large range queries
2. **Parent Caching**: 17.3x speedup for parent() operations  
3. **V2 tmIndex**: 4x speedup over original implementation
4. **Bulk Operations**: 15.5x speedup for batch insertion

### S0-S5 Tetrahedral Decomposition (July 6, 2025)

- Implemented correct S0-S5 decomposition for 100% containment
- Complete cube tiling with no gaps/overlaps
- Fixed visualization to show entities within their tetrahedra

---

*This summary reflects the current implemented architecture.*
