# Lucien - 3D Spatial Indexing Module

Spatial indexing for 3D applications with octree and tetree implementations.

## Quick Start

### Octree (Cubic Decomposition)
```java
// Create an octree spatial index
Octree<LongEntityID, GameObject> octree = new Octree<>(new SequentialLongIDGenerator(), 10,
                                                        // max entities per node
                                                        (byte) 20 // max depth
);

// Insert entities
Point3f position = new Point3f(100, 50, 200);
GameObject player = new GameObject("Player");
LongEntityID playerId = octree.insert(position, (byte) 10, player);

// Find nearest neighbors
List<LongEntityID> nearby = octree.kNearestNeighbors(position, 5, 100.0f);

// Ray intersection
Ray3D ray = new Ray3D(origin, direction);
Optional<RayIntersection<LongEntityID, GameObject>> hit = octree.rayIntersectFirst(ray);
```

### Tetree (Tetrahedral Decomposition) - Recommended
```java
// Create a tetree spatial index (2-6x faster insertions, 27-35% less memory)
Tetree<LongEntityID, GameObject> tetree = new Tetree<>(new SequentialLongIDGenerator(), 10,
                                                        // max entities per node
                                                        (byte) 20 // max depth
);

// Insert entities (faster than Octree)
Point3f position = new Point3f(100, 50, 200);
GameObject npc = new GameObject("NPC");
LongEntityID npcId = tetree.insert(position, (byte) 10, npc);

// Find nearest neighbors (faster for smaller datasets)
List<LongEntityID> nearby = tetree.kNearestNeighbors(position, 5, 100.0f);

// Same unified API as Octree
Ray3D ray = new Ray3D(origin, direction);
Optional<RayIntersection<LongEntityID, GameObject>> hit = tetree.rayIntersectFirst(ray);
```

> **📖 [View Complete API Documentation](doc/API_DOCUMENTATION_INDEX.md)** - 12 comprehensive APIs with examples, performance data, and integration guides

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
- **Collision Detection**: Broad and narrow phase collision detection with physics response
- **Tree Traversal**: Visitor pattern with multiple strategies
- **Dynamic Balancing**: Automatic tree optimization
- **Plane Intersection**: Arbitrary 3D plane queries
- **Frustum Culling**: View frustum visibility for graphics
- **Bulk Operations**: 5-10x faster batch insertions with ObjectPool optimization
- **Lock-Free Updates**: 264K entity movements/sec with atomic movement protocol
- **Forest Management**: Multi-tree coordination for distributed spatial indexing
- **Adaptive Forest**: Dynamic density-based subdivision and merging
- **Hierarchical Forest**: Level-of-detail management with distance-based LOD

## Performance

**Updated July 11, 2025**: Major performance reversal after concurrent optimizations - Tetree now faster for insertions!

| Operation         | Octree     | Tetree     | Tetree Advantage    |
|-------------------|------------|------------|---------------------|
| Insert (100)      | 2.0 μs/op  | 0.95 μs/op | **2.1x faster**     |
| Insert (1K)       | 1.8 μs/op  | 0.33 μs/op | **5.5x faster**     |
| Insert (10K)      | 1.5 μs/op  | 0.24 μs/op | **6.2x faster**     |
| k-NN (100)        | 12.5 μs/op | 7.8 μs/op  | **1.6x faster**     |
| k-NN (1K)         | 18.2 μs/op | 16.5 μs/op | **1.1x faster**     |
| k-NN (10K)        | 15.8 μs/op | 19.0 μs/op | 1.2x slower         |
| Range Query (100) | 2.1 μs/op  | 13.0 μs/op | 6.2x slower         |
| Range Query (1K)  | 8.5 μs/op  | 17.8 μs/op | 2.1x slower         |
| Range Query (10K) | 14.2 μs/op | 19.9 μs/op | 1.4x slower         |
| Memory Usage      | 100%       | 65-73%     | **27-35% less**     |

**Major Update**: Concurrent optimizations (ConcurrentSkipListMap, ObjectPools, lock-free updates) completely reversed performance characteristics. Tetree is now the preferred choice for most use cases.

## Choosing Between Octree and Tetree

### Use Tetree When (Recommended):

- **Fast insertion is needed (2-6x faster after July 2025 optimizations)**
- **Memory efficiency matters (27-35% less memory)**
- **Concurrent access is required (superior thread safety)**
- k-NN queries are primary workload (1.1-1.6x faster for smaller datasets)
- Working with tetrahedral meshes or geometry

### Use Octree When:

- Range queries are the primary workload (1.4-6x faster)
- Simple, predictable performance is needed
- Using existing Morton curve tools/algorithms
- Working with legacy systems expecting cubic decomposition

## Documentation

### 📚 **[Complete API Documentation Index](doc/API_DOCUMENTATION_INDEX.md)**

**Start here for comprehensive API guidance** - Complete reference with 12 specialized APIs, performance data, integration patterns, and use case guides.

### Getting Started

- [Architecture Summary](doc/ARCHITECTURE_SUMMARY.md) - Overview of the system
- [Core Spatial Index API](doc/CORE_SPATIAL_INDEX_API.md) - Basic operations guide

### Core APIs

- [Core Spatial Index API](doc/CORE_SPATIAL_INDEX_API.md) - Entity management and core operations
- [K-Nearest Neighbors API](doc/K_NEAREST_NEIGHBORS_API.md) - Proximity queries
- [Bulk Operations API](doc/BULK_OPERATIONS_API.md) - High-performance batch operations

### Advanced Features

- [Ray Intersection API](doc/RAY_INTERSECTION_API.md) - Ray casting and line-of-sight
- [Collision Detection API](doc/COLLISION_DETECTION_API.md) - Physics integration
- [Tree Traversal API](doc/TREE_TRAVERSAL_API.md) - Visitor patterns
- [Tree Balancing API](doc/TREE_BALANCING_API.md) - Dynamic optimization
- [Plane Intersection API](doc/PLANE_INTERSECTION_API.md) - 3D plane queries
- [Frustum Culling API](doc/FRUSTUM_CULLING_API.md) - Graphics visibility
- [Forest Management API](doc/FOREST_MANAGEMENT_API.md) - Multi-tree coordination and specialized forests
- [Entity Management API](doc/ENTITY_MANAGEMENT_API.md) - Entity lifecycle and identification
- [Lock-Free Operations API](doc/LOCKFREE_OPERATIONS_API.md) - High-performance concurrent operations

### Implementation Details

- [Complete Architecture Guide](doc/LUCIEN_ARCHITECTURE.md) - Detailed technical documentation
- [Tetree Implementation Guide](doc/TETREE_IMPLEMENTATION_GUIDE.md) - Tetrahedral specifics
- [Spatial Index Performance Guide](doc/SPATIAL_INDEX_PERFORMANCE_GUIDE.md) - Performance tuning
- [Octree vs Tetree Performance](doc/OCTREE_VS_TETREE_PERFORMANCE.md) - Latest benchmarks
- [Lazy Evaluation Usage Guide](doc/LAZY_EVALUATION_USAGE_GUIDE.md) - Deferred operations
- [TM Index Limitations](doc/TM_INDEX_LIMITATIONS_AND_SOLUTIONS.md) - Current constraints

## Architecture Overview

```
SpatialIndex<Key extends SpatialKey<Key>, ID, Content> (interface)
  └── AbstractSpatialIndex<Key, ID, Content, NodeType> (90% shared code)
      ├── Octree<ID, Content> (Morton curve-based)
      └── Tetree<ID, Content> (Tetrahedral SFC with S0-S5 decomposition)
```

### Key Components

- **109 Java files** organized in 9 packages (core, entity, octree, tetree, collision, balancing, visitor, forest, index)
- **Entity Management**: Centralized lifecycle with multiple ID generation strategies
- **Spatial Queries**: k-NN, range, ray intersection, collision detection, frustum culling
- **Performance Optimizations**: ConcurrentSkipListMap, ObjectPools, lock-free updates, lazy evaluation
- **Geometric Accuracy**: S0-S5 tetrahedral decomposition with 100% containment
- **Forest Architecture**: Complete multi-tree management with adaptive and hierarchical specializations

## Usage Examples

### Basic Operations

```java
// Insert with bounds for spanning
EntityBounds bounds = new EntityBounds(0, 0, 0, 50, 100, 50);
spatialIndex.

insert(buildingId, center, level, building, bounds);

// Update position
spatialIndex.

updateEntity(entityId, newPosition, level);

// Remove entity
spatialIndex.

removeEntity(entityId);
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

### Forest Management

```java
// Create a forest for multi-tree management
Forest<MortonKey, LongEntityID, String> forest = new Forest<>();

// Add multiple trees
Octree<LongEntityID, String> tree1 = new Octree<>(idGenerator, 10, (byte) 20);
Octree<LongEntityID, String> tree2 = new Octree<>(idGenerator, 10, (byte) 20);

TreeMetadata metadata = TreeMetadata.builder()
    .name("region_north")
    .treeType(TreeMetadata.TreeType.OCTREE)
    .property("region", "north")
    .build();

String treeId1 = forest.addTree(tree1, metadata);
String treeId2 = forest.addTree(tree2);

// Forest-wide queries
List<LongEntityID> nearestInForest = forest.findKNearestNeighbors(
    new Point3f(100, 200, 300), 10);

// Grid forest for uniform partitioning
GridForest<MortonKey, LongEntityID, String> gridForest = 
    GridForest.createOctreeGrid(
        new Point3f(0, 0, 0),         // origin
        new Vector3f(1000, 1000, 1000), // total size
        4, 4, 4                       // 4x4x4 grid
    );

// Dynamic forest management
DynamicForestManager<MortonKey, LongEntityID, String> manager = 
    new DynamicForestManager<>(forest, entityManager, 
        () -> new Octree<>(idGenerator, 10, (byte) 20));

// Enable automatic tree splitting/merging based on load
manager.enableAutoManagement(60000); // Check every minute
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

**Production Ready** - Feature-complete as of July 2025:

- ✅ **Performance Breakthrough**: Concurrent optimizations make Tetree 2-6x faster for insertions
- ✅ **Complete Forest Implementation**: Adaptive and hierarchical forests with 15 test classes
- ✅ **Lock-Free Concurrency**: 264K entity movements/sec with atomic protocols
- ✅ **S0-S5 Tetrahedral Decomposition**: 100% geometric containment achieved
- ✅ **Comprehensive API Coverage**: 9 specialized APIs for all spatial operations
- ✅ **787 Tests Passing**: Full test coverage with performance benchmarks
- ✅ **Clean Documentation**: 24 active docs, 161+ archived historical documents
