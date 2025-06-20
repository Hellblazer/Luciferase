# Lucien - 3D Spatial Indexing Module

Lucien provides high-performance spatial indexing for 3D applications through a unified architecture supporting both octree (cubic) and tetree (tetrahedral) decomposition strategies.

## Quick Start

```java
// Create a spatial index
Octree<LongEntityID, GameObject> spatialIndex = new Octree<>(
    new SequentialLongIDGenerator(),
    10,     // max entities per node
    (byte)20 // max depth
);

// Insert entities
Point3f position = new Point3f(100, 50, 200);
GameObject player = new GameObject("Player");
LongEntityID playerId = spatialIndex.insert(position, (byte)10, player);

// Find nearest neighbors
List<LongEntityID> nearby = spatialIndex.kNearestNeighbors(
    position, 5, 100.0f
);

// Ray intersection
Ray3D ray = new Ray3D(origin, direction);
Optional<RayIntersection<LongEntityID, GameObject>> hit = 
    spatialIndex.rayIntersectFirst(ray);
```

## Features

### Core Capabilities
- **Unified Architecture**: Single API for both octree and tetree implementations
- **Multi-Entity Support**: Multiple entities per spatial location
- **Entity Spanning**: Large entities can span multiple spatial nodes
- **Thread-Safe**: Concurrent access with read-write locks
- **High Performance**: O(1) node access, optimized algorithms

### Advanced Features
- **Ray Intersection**: Fast ray traversal for line-of-sight queries
- **Collision Detection**: Broad and narrow phase collision detection
- **Tree Traversal**: Visitor pattern with multiple strategies
- **Dynamic Balancing**: Automatic tree optimization
- **Plane Intersection**: Arbitrary 3D plane queries
- **Frustum Culling**: View frustum visibility for graphics
- **Bulk Operations**: 5-10x faster batch insertions

## Performance

Surprising discovery: **Tetree outperforms Octree** in most scenarios:

| Operation | Octree | Tetree | Improvement |
|-----------|--------|--------|-------------|
| Bulk insert 100K | 346 ms | 34 ms | **10x faster** |
| k-NN (k=10) | 1.2 ms | 0.4 ms | **3x faster** |
| Memory/entity | 350 bytes | 280 bytes | **20% less** |

## Documentation

### Getting Started
- [Architecture Summary](doc/ARCHITECTURE_SUMMARY_2025.md) - High-level overview
- [Basic Operations API](doc/BASIC_OPERATIONS_API.md) - Insert, lookup, update, remove
- [Quick Start Examples](doc/IMMEDIATE_PERFORMANCE_IMPROVEMENTS.md) - Common usage patterns

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

### Status Documents (June 2025)
- [Current State](CURRENT_STATE_JUNE_2025.md) - Latest implementation status
- [Documentation Update Summary](DOCUMENTATION_UPDATE_SUMMARY_JUNE_2025.md) - Recent changes
- [Memory Optimization Fix](MEMORY_OPTIMIZATION_FIX_JUNE_2025.md) - Performance improvements

## Architecture Overview

```
SpatialIndex<ID, Content> (interface)
  └── AbstractSpatialIndex (90% shared code)
      ├── Octree (Morton curve-based)
      └── Tetree (Tetrahedral SFC)
```

### Key Components
- **34 core classes** organized in 4 packages
- **Entity Management**: Centralized lifecycle with ID generation
- **Spatial Queries**: Bounded, bounding, enclosing, k-NN
- **Performance Optimizations**: O(1) operations, caching, bulk loading

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
List<CollisionPair<ID, Content>> collisions = 
    spatialIndex.findAllCollisions();

// Frustum culling for rendering
Frustum3D frustum = camera.getFrustum();
List<FrustumIntersection<ID, Content>> visible = 
    spatialIndex.frustumCullVisible(frustum, cameraPos);
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

As of June 2025, all planned enhancements have been completed:
- ✅ All spatial index features implemented
- ✅ Comprehensive API documentation
- ✅ 200+ tests with full coverage
- ✅ Performance optimizations complete
- ✅ Architecture documentation current

The lucien module is production-ready for 3D spatial indexing applications.