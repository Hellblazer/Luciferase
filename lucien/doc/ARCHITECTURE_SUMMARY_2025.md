# Architecture Summary - June 24, 2025 (Updated)

## Purpose

This document provides a high-level summary of the Luciferase lucien module architecture as of June 2025. For
detailed information, see the comprehensive documentation in this directory.

## Current State

The lucien module provides spatial indexing through a unified architecture supporting both octree and tetrahedral
decomposition. Following major consolidation in January 2025, the module uses inheritance to maximize code reuse while
maintaining the unique characteristics of each approach. As of June 2025, all planned enhancements have been completed.

**Total Classes: 98 Java files** organized across 8 packages

## Package Overview

For detailed package structure and class descriptions, see [LUCIEN_ARCHITECTURE_2025.md](./LUCIEN_ARCHITECTURE_2025.md).

- **Root Package (27 classes)**: Core abstractions, spatial types, geometry utilities, performance optimization
- **Entity Package (12 classes)**: Complete entity management infrastructure
- **Octree Package (5 classes)**: Morton curve-based cubic spatial decomposition
- **Tetree Package (32 classes)**: Tetrahedral spatial decomposition with extensive optimizations
- **Balancing Package (3 classes)**: Tree balancing strategies
- **Collision Package (12 classes)**: Comprehensive collision detection system
- **Visitor Package (6 classes)**: Tree traversal visitor pattern implementation
- **Index Package (1 class)**: Additional indexing utilities

## Key Architecture Components

### Inheritance Hierarchy

```
SpatialIndex<Key extends SpatialKey<Key>, ID, Content> (interface)
  └── AbstractSpatialIndex<Key, ID, Content, NodeType> (base class with ~90% shared functionality)
      ├── Octree<ID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content, OctreeNode<ID>>
      └── Tetree<ID, Content> extends AbstractSpatialIndex<TetreeKey, ID, Content, TetreeNodeImpl<ID>>
```

### Major Features

- **Unified API**: Both octree and tetree share common operations through AbstractSpatialIndex
- **Entity Management**: Centralized through EntityManager with multi-entity support
- **Thread Safety**: ReadWriteLock-based concurrent access
- **Performance**: HashMap-based O(1) node access for both implementations
- **Type-Safe Keys**: SpatialKey architecture prevents mixing incompatible indices (June 2025)

## What This Architecture Includes

### Core Features (January 2025)

✅ **Core Spatial Indexing**: Insert, remove, update, lookup operations  
✅ **Spatial Queries**: Bounded/bounding queries, k-NN search, range queries  
✅ **Entity Management**: Multi-entity support with spanning capabilities  
✅ **Thread Safety**: Concurrent access with read-write locks

### Enhanced Features (Completed June 2025)

✅ **Ray Intersection**: Complete ray traversal implementation (
see [RAY_INTERSECTION_API.md](./RAY_INTERSECTION_API.md))  
✅ **Collision Detection**: Broad/narrow phase collision detection (
see [COLLISION_DETECTION_API.md](./COLLISION_DETECTION_API.md))  
✅ **Tree Traversal**: Visitor pattern support (see [TREE_TRAVERSAL_API.md](./TREE_TRAVERSAL_API.md))  
✅ **Tree Balancing**: Dynamic balancing strategies (see [TREE_BALANCING_API.md](./TREE_BALANCING_API.md))  
✅ **Plane Intersection**: Arbitrary 3D plane queries (see [PLANE_INTERSECTION_API.md](./PLANE_INTERSECTION_API.md))  
✅ **Frustum Culling**: View frustum visibility determination (see [FRUSTUM_CULLING_API.md](./FRUSTUM_CULLING_API.md))  
✅ **Bulk Operations**: High-performance batch operations (see [BULK_OPERATIONS_API.md](./BULK_OPERATIONS_API.md))

### Performance Optimizations (June 2025)

✅ **O(1) Operations**: SpatialIndexSet replaces TreeSet  
✅ **TetreeLevelCache**: Eliminates O(log n) level calculations  
✅ **Dynamic Level Selection**: Automatic optimization for data distribution  
✅ **Bulk Loading Mode**: 5-10x performance for large datasets
✅ **SpatialKey Architecture**: Type-safe keys with MortonKey and TetreeKey

## Documentation Structure

### Primary References

- **[LUCIEN_ARCHITECTURE_2025.md](./LUCIEN_ARCHITECTURE_2025.md)**: Comprehensive architecture guide
- **[SPATIAL_INDEX_CONSOLIDATION.md](./archived/SPATIAL_INDEX_CONSOLIDATION.md)**: January 2025 consolidation details

### API Documentation

#### Core APIs

- **[BASIC_OPERATIONS_API.md](./BASIC_OPERATIONS_API.md)**: Fundamental operations (insert, lookup, update, remove)
- **[ENTITY_MANAGEMENT_API.md](./ENTITY_MANAGEMENT_API.md)**: Entity lifecycle, bounds, and spanning
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
- **[PERFORMANCE_TUNING_GUIDE.md](./PERFORMANCE_TUNING_GUIDE.md)**: Optimization strategies
- **[SPATIAL_INDEX_OPTIMIZATION_GUIDE.md](./SPATIAL_INDEX_OPTIMIZATION_GUIDE.md)**: Implementation optimizations

### Implementation Guides

- **[TETREE_IMPLEMENTATION_GUIDE.md](./TETREE_IMPLEMENTATION_GUIDE.md)**: Tetrahedral tree specifics
- **[IMMEDIATE_PERFORMANCE_IMPROVEMENTS.md](./IMMEDIATE_PERFORMANCE_IMPROVEMENTS.md)**: Quick optimization wins

### Historical Context

- **[archived/](./archived/)**: Contains 35+ archived documents describing unimplemented features and completed work
  phases

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

### Current Performance Metrics (Post-Subdivision Fix - June 28, 2025)

Source: OctreeVsTetreeBenchmark.java (after fixing Tetree subdivision)

| Dataset | Operation | Octree  | Tetree  | Winner        | Improvement |
|---------|-----------|---------|---------|---------------|-------------|
| 100     | Insertion | ~8 μs   | ~48 μs  | Octree (6x)   | 38% better  |
| 1K      | Insertion | ~3 μs   | ~28 μs  | Octree (9.2x) | 84% better  |
| 10K     | Insertion | ~1 μs   | ~36 μs  | Octree (35x)  | 96% better  |
| 1K      | k-NN      | 3.22 μs | 0.81 μs | Tetree (4x)   | unchanged   |
| 10K     | k-NN      | 21.9 μs | 7.04 μs | Tetree (3.1x) | unchanged   |
| 10K     | Memory    | 12.9 MB | 12.6 MB | Similar       | now correct |

### Key Insight

- **Subdivision Fix**: Tetree was creating only 2 nodes instead of thousands due to missing subdivision logic
- **After Fix**: Performance improved 38-96%, memory usage now comparable to Octree
- **Remaining Gap**: Due to fundamental algorithmic difference:
    - **Octree**: Uses Morton encoding (simple bit interleaving) - always O(1)
    - **Tetree**: Uses tmIndex() which requires parent chain traversal - O(level)

For detailed performance analysis, see [PERFORMANCE_REALITY_JUNE_2025.md](./PERFORMANCE_REALITY_JUNE_2025.md)

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

## Recent Updates

### Geometric Subdivision Implementation (June 28, 2025)

- **Feature**: Added `geometricSubdivide()` method to Tet class for true geometric subdivision
- **Solution**: Created `subdivisionCoordinates()` method using V3 = anchor + (h,h,h) for compatibility
- **Performance**: ~0.04 μs per operation, 5.5x faster than 8 individual child() calls
- **Benefit**: 100% geometric containment of children within parent in subdivision space
- **Impact**: No breaking changes to existing coordinate system

### Bug Fixes (June 24, 2025)

1. **Collision Detection**: Fixed control flow in forEach loops (return → continue)
2. **Neighbor Finding**: Fixed distance calculations (centroids → entity positions)
3. **SpatialKey Implementation**: Resolved Tetree's non-unique SFC index issue

---

*This summary reflects the actual implemented architecture as of June 24, 2025. For historical context about planned but
unimplemented features, see the archived/ directory.*
