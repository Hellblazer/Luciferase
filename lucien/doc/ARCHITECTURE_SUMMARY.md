# Lucien Architecture Summary

**Last Updated**: 2026-01-20
**Status**: Current

## Purpose

This document provides a high-level summary of the Luciferase lucien module architecture. For detailed information, see
the comprehensive documentation in this directory.

## Current State

The lucien module provides spatial indexing through a unified architecture supporting octree (cubic), tetrahedral, and
prismatic (anisotropic)
subdivision. The module uses inheritance to maximize code reuse while maintaining the unique characteristics of each
approach. All core features are complete, including S0-S5 tetrahedral subdivision with 100% geometric containment and
anisotropic prism subdivision with triangular/linear spatial indexing.

**Total Classes: 195 Java files** organized across 18 packages (including cache, simd, ghost, and neighbor detection)

## Package Overview

For detailed package structure and class descriptions, see [LUCIEN_ARCHITECTURE.md](./LUCIEN_ARCHITECTURE.md).

- **Root Package (30 classes)**: Core abstractions, spatial types, geometry utilities, performance optimization, SpatialIndexFactory
- **Cache Package (2 classes)**: K-NN query result caching optimization (NEW)
- **Entity Package (13 classes)**: Complete entity management infrastructure with EntityDynamics support
- **Octree Package (6 classes)**: Morton curve-based cubic spatial subdivision with internal utilities
- **SFC Package (5 classes)**: Space-filling curve flat array index with LITMAX/BIGMIN optimization
- **Tetree Package (34 classes)**: Tetrahedral spatial subdivision with 21-level support, extensive optimizations, and lazy evaluation
- **Prism Package (9 classes)**: Anisotropic spatial subdivision with Line/Triangle elements and PrismSubdivisionStrategy
- **Balancing Package (3 classes)**: Tree balancing strategies
- **Collision Package (30 classes)**: Comprehensive collision detection system with CCD and physics subpackages
- **Visitor Package (6 classes)**: Tree traversal visitor pattern implementation
- **Forest Package (26 classes)**: Multi-tree coordination with ghost layer for distributed spatial indexing
- **Neighbor Package (3 classes)**: Topological neighbor detection for spatial indices
- **Internal Package (4 classes)**: Entity caching and object pool utilities
- **Geometry Package (1 class)**: AABB intersection utilities
- **Occlusion Package (11 classes)**: Dynamic Scene Occlusion Culling (DSOC) with TBVs and hierarchical Z-buffer
- **Debug Package (4 classes)**: Debugging utilities for all spatial index types
- **Migration Package (1 class)**: Spatial index type conversion utilities
- **Profiler Package (1 class)**: Performance profiling utilities
- **SIMD Package (1 class)**: SIMD-optimized Morton code encoding (NEW)

## Key Architecture Components

### Inheritance Hierarchy

```

SpatialIndex<Key extends SpatialKey<Key>, ID, Content> (interface)
  └── AbstractSpatialIndex<Key, ID, Content> (base class with ~95% shared functionality)
      ├── Octree<ID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content>
      ├── SFCArrayIndex<ID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content>
      ├── Tetree<ID, Content> extends AbstractSpatialIndex<TetreeKey, ID, Content>
      └── Prism<ID, Content> extends AbstractSpatialIndex<PrismKey, ID, Content>
```

### Spatial Subdivision Strategies

- **Octree**: Isotropic cubic subdivision using Morton curve space-filling curves
- **Tetree**: Tetrahedral subdivision with S0-S5 characteristic tetrahedra
- **Prism**: Anisotropic subdivision combining 2D triangular and 1D linear elements for applications requiring fine

  horizontal granularity and coarse vertical granularity

### Major Features

- **Unified API**: All spatial indices share common operations through AbstractSpatialIndex
- **Entity Management**: Centralized through EntityManager with multi-entity support
- **Thread Safety**: ReadWriteLock-based concurrent access
- **Performance**: HashMap-based O(1) node access for all implementations
- **Type-Safe Keys**: SpatialKey architecture prevents mixing incompatible indices (MortonKey, TetreeKey with 21-level support, PrismKey)
- **Ghost Layer**: Complete distributed support with neighbor detection and gRPC communication
- **Dual Ghost Approach**: Both distance-based (forest) and topology-based (element) ghost detection

## What This Architecture Includes

### Core Features (January 2025)

- ✅ **Core Spatial Indexing**: Insert, remove, update, lookup operations  
- ✅ **Spatial Queries**: Bounded/bounding queries, k-NN search, range queries  
- ✅ **Entity Management**: Multi-entity support with spanning capabilities  
- ✅ **Thread Safety**: Concurrent access with read-write locks

### Enhanced Features (Completed)

- ✅ **Ray Intersection**: Complete ray traversal implementation (see [RAY_INTERSECTION_API.md](./RAY_INTERSECTION_API.md))  
- ✅ **Collision Detection**: Broad/narrow phase collision detection (see [COLLISION_DETECTION_API.md](./COLLISION_DETECTION_API.md))  
- ✅ **Tree Traversal**: Visitor pattern support (see [TREE_TRAVERSAL_API.md](./TREE_TRAVERSAL_API.md))  
- ✅ **Tree Balancing**: Dynamic balancing strategies (see [TREE_BALANCING_API.md](./TREE_BALANCING_API.md))  
- ✅ **Plane Intersection**: Arbitrary 3D plane queries (see [PLANE_INTERSECTION_API.md](./PLANE_INTERSECTION_API.md))  
- ✅ **Frustum Culling**: View frustum visibility determination (see [FRUSTUM_CULLING_API.md](./FRUSTUM_CULLING_API.md))  
- ✅ **Bulk Operations**: High-performance batch operations (see [BULK_OPERATIONS_API.md](./BULK_OPERATIONS_API.md))  
- ✅ **Ghost Layer**: Distributed spatial index support (see [GHOST_API.md](./GHOST_API.md))  
- ✅ **Neighbor Detection**: Topological neighbor finding for ghost creation

### Performance Optimizations

- ✅ **O(1) Operations**: SpatialIndexSet replaces TreeSet  
- ✅ **TetreeLevelCache**: Eliminates O(log n) level calculations  
- ✅ **Dynamic Level Selection**: Automatic optimization for data distribution  
- ✅ **Bulk Loading Mode**: 5-10x performance for large datasets
- ✅ **SpatialKey Architecture**: Type-safe keys with MortonKey, TetreeKey (21-level support), and PrismKey  
- ✅ **TetreeKey Encoding**: Dual implementation with CompactTetreeKey (levels 0-10) and ExtendedTetreeKey (levels 0-21) using innovative level 21 bit packing

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
- **[DSOC_API.md](./DSOC_API.md)**: Dynamic Scene Occlusion Culling for rendering optimization

#### Performance

- **[BULK_OPERATIONS_API.md](./BULK_OPERATIONS_API.md)**: High-performance batch operations
- **[SPATIAL_INDEX_PERFORMANCE_GUIDE.md](./SPATIAL_INDEX_PERFORMANCE_GUIDE.md)**: Performance tuning guide
- **[PERFORMANCE_METRICS_MASTER.md](./PERFORMANCE_METRICS_MASTER.md)**: Current performance baseline

### Implementation Guides

- **[TETREE_IMPLEMENTATION_GUIDE.md](./TETREE_IMPLEMENTATION_GUIDE.md)**: Tetrahedral tree specifics

## Design Philosophy

The current architecture prioritizes:

1. **Simplicity**: Essential functionality without unnecessary complexity
2. **Code Reuse**: 90% shared implementation through inheritance
3. **Maintainability**: Single place for algorithm changes
4. **Extensibility**: Easy addition of new spatial subdivision strategies (demonstrated by Octree, Tetree, and Prism

   implementations)

5. **Performance**: O(1) operations through HashMap-based storage
6. **Scalability**: Forest architecture for distributed and large-scale applications

## Performance Reality

### Important Performance Update

Previous performance claims were based on using the `consecutiveIndex()` method which is unique only within a level.
After refactoring to use the globally unique `tmIndex()` for correctness (unique across all levels), the performance
characteristics have changed dramatically.

### Current Performance Metrics

After ConcurrentSkipListMap integration (July 11, 2025), the performance characteristics have completely reversed.

For current performance metrics, see [PERFORMANCE_METRICS_MASTER.md](PERFORMANCE_METRICS_MASTER.md)

### Optimization Timeline

- **June 24**: Initial state - Tetree 770x slower (unusable)
- **June 28**: Subdivision fix + V2 tmIndex - reduced to 6-35x slower
- **June 28**: Parent cache - reduced to 3-7x slower
- **July 5**: Efficient child computation - 3x faster child lookups
- **July 11**: ConcurrentSkipListMap - Tetree now 2.1-6.2x FASTER for insertions
- **Result**: Complete performance reversal from concurrent optimizations

### Performance Reversal Explanation

- **Fundamental algorithms unchanged**: Octree still O(1), Tetree still O(level)
- **ConcurrentSkipListMap impact**: Favors simpler key comparisons (benefits Tetree)
- **Memory trade-off**: Tetree now uses 65-73% of Octree's memory (was 20-25%)
- **Concurrent benefits**: Lock-free reads and reduced contention favor Tetree

For detailed performance analysis, see:

- [PERFORMANCE_METRICS_MASTER.md](./PERFORMANCE_METRICS_MASTER.md) - Current performance baseline
- [PERFORMANCE_INDEX.md](./PERFORMANCE_INDEX.md) - Guide to all performance documentation

## Testing Coverage

**222 test files with 1,360 @Test methods** providing comprehensive coverage:

- Unit tests for all major operations across all spatial indices (Octree, Tetree, Prism)
- Integration tests for spatial queries
- Performance benchmarks (controlled by environment flag)
- Thread-safety tests for concurrent operations
- API-specific test suites for all features
- Prism-specific tests for anisotropic subdivision, collision detection, and ray intersection (47 Phase 5 tests)

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

### S0-S5 Tetrahedral Subdivision (July 6, 2025)

- Implemented correct S0-S5 subdivision for 100% containment
- Complete cube tiling with no gaps/overlaps
- Fixed visualization to show entities within their tetrahedra

---

*This summary reflects the current implemented architecture.*
