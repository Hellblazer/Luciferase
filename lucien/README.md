# Lucien - 3D Spatial Indexing Module

Spatial indexing for 3D applications with octree and tetree implementations.

## Quick Start

```java
// Create a spatial index
Octree<LongEntityID, GameObject> spatialIndex = new Octree<>(new SequentialLongIDGenerator(), 10,
                                                             // max entities per node
                                                             (byte) 20 // max depth
);

// Insert entities
Point3f position = new Point3f(100, 50, 200);
GameObject player = new GameObject("Player");
LongEntityID playerId = spatialIndex.insert(position, (byte) 10, player);

// Find nearest neighbors
List<LongEntityID> nearby = spatialIndex.kNearestNeighbors(position, 5, 100.0f);

// Ray intersection
Ray3D ray = new Ray3D(origin, direction);
Optional<RayIntersection<LongEntityID, GameObject>> hit = spatialIndex.rayIntersectFirst(ray);
```

## Features

### Core Capabilities

- **Unified Architecture**: Single API for both octree and tetree implementations
- **Multi-Entity Support**: Multiple entities per spatial location
- **Entity Spanning**: Large entities can span multiple spatial nodes
- **Thread-Safe**: Concurrent access with read-write locks
- **High Performance**: O(1) node access, optimized algorithms
- **S0-S5 Decomposition**: Tetree uses standard 6-tetrahedra cube tiling

### Advanced Features

- **Ray Intersection**: Fast ray traversal for line-of-sight queries
- **Collision Detection**: Broad and narrow phase collision detection
- **Tree Traversal**: Visitor pattern with multiple strategies
- **Dynamic Balancing**: Automatic tree optimization
- **Plane Intersection**: Arbitrary 3D plane queries
- **Frustum Culling**: View frustum visibility for graphics
- **Bulk Operations**: 5-10x faster batch insertions

## Performance

**Updated July 2025**: Latest benchmarks after S0-S5 decomposition implementation.

| Operation         | Octree        | Tetree        | Tetree Advantage  |
|-------------------|---------------|---------------|-------------------|
| Insert (100)      | 5.3 μs/op     | 5.1 μs/op     | 1.0x faster       |
| Insert (1K)       | 2.4 μs/op     | 5.6 μs/op     | 2.3x slower       |
| Insert (10K)      | 1.1 μs/op     | 12.5 μs/op    | 11.4x slower      |
| k-NN (10K)        | 20.2 μs/op    | 6.2 μs/op     | 3.2x faster       |
| Range Query (10K) | 20.1 μs/op    | 5.8 μs/op     | 3.5x faster       |
| Memory (10K)      | 12.9 MB       | 2.6 MB        | 80% less memory   |

**Key Insight**: Choose Octree for insertion-heavy workloads, Tetree for query-heavy and memory-constrained applications.

## Choosing Between Octree and Tetree

### Use Octree When:
- Fast insertion is critical (2-11x faster)
- Workload is update-heavy
- Simple, predictable performance is needed
- Using existing Morton curve tools/algorithms

### Use Tetree When:
- Memory efficiency matters (80% reduction)
- Workload is query-heavy (3-5x faster searches)
- Working with tetrahedral meshes or geometry
- Need better spatial locality for queries

## Documentation

### Getting Started

- [Architecture Summary](doc/ARCHITECTURE_SUMMARY_2025.md) - Overview of the system
- [Basic Operations API](doc/BASIC_OPERATIONS_API.md) - Core operations guide

### Core APIs

- [Entity Management API](doc/ENTITY_MANAGEMENT_API.md) - Entity lifecycle and properties
- [K-Nearest Neighbors API](doc/K_NEAREST_NEIGHBORS_API.md) - Proximity queries
- [Bulk Operations API](doc/BULK_OPERATIONS_API.md) - High-performance batch operations

### Advanced Features

- [Ray Intersection API](doc/RAY_INTERSECTION_API.md) - Ray casting and line-of-sight
- [Collision Detection API](doc/COLLISION_DETECTION_API.md) - Physics integration
- [Tree Traversal API](doc/TREE_TRAVERSAL_API.md) - Visitor patterns
- [Tree Balancing API](doc/TREE_BALANCING_API.md) - Dynamic optimization
- [Plane Intersection API](doc/PLANE_INTERSECTION_API.md) - 3D plane queries
- [Frustum Culling API](doc/FRUSTUM_CULLING_API.md) - Graphics visibility

### Implementation Details

- [Complete Architecture Guide](doc/LUCIEN_ARCHITECTURE_2025.md) - Detailed technical documentation
- [Tetree Implementation Guide](doc/TETREE_IMPLEMENTATION_GUIDE.md) - Tetrahedral specifics
- [Performance Tuning Guide](doc/PERFORMANCE_TUNING_GUIDE.md) - Optimization strategies
- [Spatial Index Optimization Guide](doc/SPATIAL_INDEX_OPTIMIZATION_GUIDE.md) - Implementation optimizations

### Performance Testing

- [Performance Testing Plan](doc/SPATIAL_INDEX_PERFORMANCE_TESTING_PLAN_2025.md) - Benchmarking framework

### Status Documents (July 2025)

- [Architecture Summary](doc/ARCHITECTURE_SUMMARY_2025.md) - Complete system overview
- [Octree vs Tetree Performance](doc/OCTREE_VS_TETREE_PERFORMANCE_JULY_2025.md) - Latest benchmarks
- [T8code Neighbor Analysis](doc/T8CODE_NEIGHBOR_ANALYSIS.md) - Neighbor functionality study
- [Tet Neighbor Implementation Plan](doc/TET_NEIGHBOR_IMPLEMENTATION_PLAN.md) - Upcoming enhancements

## Architecture Overview

```
SpatialIndex<Key extends SpatialKey<Key>, ID, Content> (interface)
  └── AbstractSpatialIndex<Key, ID, Content, NodeType> (90% shared code)
      ├── Octree<ID, Content> (Morton curve-based)
      └── Tetree<ID, Content> (Tetrahedral SFC with S0-S5 decomposition)
```

### Key Components

- **98 Java files** organized in 8 packages
- **Entity Management**: Centralized lifecycle with ID generation
- **Spatial Queries**: Bounded, bounding, enclosing, k-NN
- **Performance Optimizations**: O(1) operations, caching, bulk loading
- **Geometric Accuracy**: S0-S5 tetrahedral decomposition with 100% containment

## Usage Examples

### Basic Operations

```java
// Insert with bounds for spanning
EntityBounds bounds = new EntityBounds(0, 0, 0, 50, 100, 50);
spatialIndex.insert(buildingId, center, level, building, bounds);

// Update position
spatialIndex.updateEntity(entityId, newPosition, level);

// Remove entity
spatialIndex.removeEntity(entityId);
```

### Spatial Queries

```java
// Find entities in region
Spatial.Cube region = new Spatial.Cube(min, max);
List<ID> entitiesInRegion = spatialIndex.entitiesInRegion(region);

// Collision detection
List<CollisionPair<ID, Content>> collisions = spatialIndex.findAllCollisions();

// Frustum culling for rendering
Frustum3D frustum = camera.getFrustum();
List<FrustumIntersection<ID, Content>> visible = spatialIndex.frustumCullVisible(frustum, cameraPos);
```

### Performance Optimization

```java
// Bulk loading for large datasets
spatialIndex.enableBulkLoading();

List<Point3f> millionPoints = loadPointCloud();
List<Content> millionContents = loadContents();
spatialIndex.insertBatch(millionPoints, millionContents, level);

spatialIndex.finalizeBulkLoading();
```

## Requirements

- Java 23+
- Maven 3.91+

## Testing

Run tests with:

```bash
mvn test
```

For performance tests:

```bash
RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test
```

## License

AGPL v3.0 - See LICENSE file for details

## Status

The spatial indexing implementation is feature-complete as of July 2025:
- Unified architecture with type-safe spatial keys
- S0-S5 tetrahedral decomposition with 100% geometric containment
- Comprehensive test coverage and documentation
- Performance-optimized with caching and bulk operations
- Active development on t8code-compatible neighbor finding
