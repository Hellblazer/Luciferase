# Lucien - 3D Spatial Indexing Module

Spatial indexing for 3D applications with octree, tetree, and prism implementations.

## Quick Start

### Octree (Cubic Subdivision)

```java
// Create an octree spatial index
Octree<LongEntityID, GameObject> octree = new Octree<>(new SequentialLongIDGenerator(), 10,
                                                       // max entities per node
                                                       (byte) 21 // max depth (21 levels)
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

### Tetree (Tetrahedral Subdivision) - Recommended

```java
// Create a tetree spatial index (2-6x faster insertions, 27-35% less memory)
Tetree<LongEntityID, GameObject> tetree = new Tetree<>(new SequentialLongIDGenerator(), 10,
                                                       // max entities per node
                                                       (byte) 21 // max depth (full 21-level support)
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

### Prism (Anisotropic Subdivision)

```java
// Create a prism spatial index (triangular constraint: x + y < worldSize)
Prism<LongEntityID, GameObject> prism = new Prism<>(new SequentialLongIDGenerator(), 10,
                                                    // max entities per node
                                                    (byte) 21 // max depth (21 levels)
);

// Insert entities (slower than both Octree and Tetree)
Point3f position = new Point3f(100, 50, 200);
GameObject building = new GameObject("Building");
LongEntityID buildingId = prism.insert(position, (byte) 10, building);

// Find nearest neighbors (good for anisotropic data)
List<LongEntityID> nearby = prism.kNearestNeighbors(position, 5, 100.0f);

// Same unified API as Octree and Tetree
Ray3D ray = new Ray3D(origin, direction);
Optional<RayIntersection<LongEntityID, GameObject>> hit = prism.rayIntersectFirst(ray);
```

> **📖 [View Complete API Documentation](doc/API_DOCUMENTATION_INDEX.md)** - 13 comprehensive APIs with examples,
> performance data, and integration guides

## Features

### Core Capabilities

- **Unified Architecture**: Single API for octree, tetree, and prism implementations
- **Multi-Entity Support**: Multiple entities per spatial location
- **Entity Spanning**: Large entities can span multiple spatial nodes
- **Thread-Safe**: Concurrent access with read-write locks
- **High Performance**: O(1) node access, optimized algorithms
- **S0-S5 Subdivision**: Tetree uses standard 6-tetrahedra cube tiling
- **Full 21-Level Support**: Tetree now matches Octree capacity with innovative bit packing

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
- **Ghost Layer**: Complete distributed support with gRPC communication
- **Neighbor Detection**: Topological neighbor finding for Octree and Tetree

## Performance

**Updated July 12, 2025**: Added Prism spatial index for anisotropic data applications.

| Operation        | Octree  | Tetree  | Prism | Best Choice |
|------------------|---------|---------|-------|-------------|
| Operation         | Octree     | Tetree     | Prism      | Best Choice      |
|-------------------|------------|------------|------------|------------------|
| Insert (1K)       | 25.84ms    | 4.64ms     | -          | **Octree**       |
| k-NN (1K)         | -          | -          | -          | **Octree**       |
| Range Query (1K)  | -          | -          | -          | **Octree**       |
| Memory (2K)       | 430KB      | 287KB      | -          | **Tetree**       |

**Relative Performance** (vs Octree):

- **Tetree**: 5.5x faster insertion, 3.4x slower k-NN, 1.1x faster range, 36% less memory
- **Prism**: Data not available in current benchmark

## Choosing Between Spatial Index Types

### Use Tetree When (Recommended for Most Use Cases):

- **Fastest insertion performance** (2-6x faster than Octree after July 2025 optimizations)
- **Memory efficiency is important** (27-35% less memory than Octree)
- Working with positive coordinate space only
- Need fast entity updates and removals
- Bulk loading large datasets
- Complex geometric operations on tetrahedral meshes

### Use Octree When:

- **Range queries are critical** (1.4-6x faster than Tetree)
- Need support for negative coordinates
- Working with existing Morton curve tools/algorithms
- Uniform cubic subdivision matches your use case
- Predictable, well-understood performance characteristics needed

### Use Prism When:

- **Anisotropic data patterns** (non-uniform spatial distribution)
- **Terrain or urban modeling applications**
- **Horizontal precision is more important than vertical**
- Data naturally fits triangular constraint (x + y < worldSize)
- 2D triangular subdivision combined with 1D linear subdivision is preferred
- Acceptable trade-off of 1.5x slower insertion for specialized geometry

## Documentation

### 📚 **[Complete API Documentation Index](doc/API_DOCUMENTATION_INDEX.md)**

**Start here for comprehensive API guidance** - Complete reference with 13 specialized APIs, performance data,
integration patterns, and use case guides.

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
- [Performance Metrics Master](doc/PERFORMANCE_METRICS_MASTER.md) - Latest benchmarks
- [Lazy Evaluation Usage Guide](doc/LAZY_EVALUATION_USAGE_GUIDE.md) - Deferred operations
- [TM Index Limitations](doc/TM_INDEX_LIMITATIONS_AND_SOLUTIONS.md) - Current constraints

## Architecture Overview

```
SpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content> (interface)
  └── AbstractSpatialIndex<Key, ID, Content> (90% shared code)
      ├── Octree<ID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content>
      ├── Tetree<ID, Content> extends AbstractSpatialIndex<TetreeKey, ID, Content>
      └── Prism<ID, Content> extends AbstractSpatialIndex<PrismKey, ID, Content>
```

### Key Components

- **150 Java files** organized in 12 packages (core, entity, octree, tetree, prism, collision, balancing, visitor,
  forest, lockfree, internal, geometry)
- **Entity Management**: Centralized lifecycle with multiple ID generation strategies
- **Spatial Queries**: k-NN, range, ray intersection, collision detection, frustum culling
- **Performance Optimizations**: ConcurrentSkipListMap, ObjectPools, lock-free updates, lazy evaluation
- **Geometric Accuracy**: S0-S5 tetrahedral subdivision with 100% containment
- **Forest Architecture**: Complete multi-tree management with adaptive and hierarchical specializations

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

### Forest Management

```java
// Create a forest for multi-tree management
Forest<MortonKey, LongEntityID, String> forest = new Forest<>();

// Add multiple trees with different spatial index types
Octree<LongEntityID, String> tree1 = new Octree<>(idGenerator, 10, (byte) 21);
Tetree<LongEntityID, String> tree2 = new Tetree<>(idGenerator, 10, (byte) 21);
Prism<LongEntityID, String> tree3 = new Prism<>(idGenerator, 10, (byte) 21);

TreeMetadata metadata = TreeMetadata.builder()
    .name("region_north")
    .treeType(TreeMetadata.TreeType.OCTREE)
    .property("region", "north")
    .build();

String treeId1 = forest.addTree(tree1, metadata);
String treeId2 = forest.addTree(tree2);
String treeId3 = forest.addTree(tree3);

// Forest-wide queries
List<LongEntityID> nearestInForest = forest.findKNearestNeighbors(new Point3f(100, 200, 300), 10);

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
        () -> new Octree<>(idGenerator, 10, (byte) 21));

// Enable automatic tree splitting/merging based on load
manager.enableAutoManagement(60000); // Check every minute
```

### Distributed Ghost Support

```java
// Enable ghost layer for distributed operations
spatialIndex.setGhostType(GhostType.FACES);
spatialIndex.createGhostLayer();

// Query including ghost elements
List<ID> nearbyIncludingGhosts = spatialIndex.findEntitiesIncludingGhosts(position, radius);

// Distributed ghost synchronization with gRPC
GhostCommunicationManager ghostManager = new GhostCommunicationManager(
    50051,  // Server port
    spatialIndex,
    new ContentSerializerRegistry()
);

// Start ghost service
ghostManager.startServer();

// Connect to remote spatial index
ghostManager.addRemoteEndpoint("remote-host:50051");

// Synchronize ghosts
CompletableFuture<SyncResponse> sync = ghostManager.syncGhosts(
    Arrays.asList("tree1", "tree2"),
    GhostType.FACES
);
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

- ✅ **Three Spatial Index Types**: Tetree (fastest insertions, memory efficient), Octree (fastest queries), Prism (anisotropic data)
- ✅ **Complete Forest Implementation**: Adaptive and hierarchical forests with 15 test classes
- ✅ **Lock-Free Concurrency**: 264K entity movements/sec with atomic protocols
- ✅ **S0-S5 Tetrahedral Subdivision**: 100% geometric containment achieved
- ✅ **Comprehensive API Coverage**: 14 specialized APIs for all spatial operations
- ✅ **Unified Architecture**: Single API across all three spatial index implementations
- ✅ **Ghost Layer**: Complete distributed support with gRPC and Protocol Buffers
- ✅ **Extensive Test Coverage**: Full test coverage with performance benchmarks
- ✅ **Clean Documentation**: 27 active docs, comprehensive performance analysis
