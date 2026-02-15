# Lucien API Documentation Index

Complete guide to all APIs in the Lucien spatial indexing module.

**Total APIs**: 16 (including Ghost, Neighbor Detection, and DSOC)

## Quick Start Guide

**New to Lucien?** Start here:

1. **[Core Spatial Index API](CORE_SPATIAL_INDEX_API.md)** - Learn the fundamentals
2. **[Entity Management API](ENTITY_MANAGEMENT_API.md)** - Understand entity lifecycle
3. **[K-Nearest Neighbors API](K_NEAREST_NEIGHBORS_API.md)** - Your first spatial queries
4. **[Forest Management API](FOREST_MANAGEMENT_API.md)** - Scale to multi-tree operations

## Complete API Reference

### 🏗️ **Foundation APIs**

Essential APIs for all spatial indexing operations:

| API                                                     | Purpose                           | Key Classes                                    | Use When                                         |
| --------------------------------------------------------- | ----------------------------------- | ------------------------------------------------ | -------------------------------------------------- |
| **[Core Spatial Index API](CORE_SPATIAL_INDEX_API.md)** | Basic spatial operations          | `SpatialIndex`, `AbstractSpatialIndex`         | Starting any spatial indexing project            |
| **[Entity Management API](ENTITY_MANAGEMENT_API.md)**   | Entity lifecycle management       | `EntityManager`, `EntityBounds`, ID generators | Managing entities in spatial indexes             |
| **[Bulk Operations API](BULK_OPERATIONS_API.md)**       | High-performance batch operations | `BulkOperationProcessor`, batch methods        | Inserting/updating large datasets (5-10x faster) |
| **[Prism API](PRISM_API.md)**                           | Anisotropic spatial indexing      | `Prism`, `PrismKey`, triangular subdivision  | Layered data, atmospheric/geological modeling    |

### 🔍 **Query APIs**

Specialized spatial query operations:

| API                                                       | Purpose                       | Key Features                                       | Performance            |
| ----------------------------------------------------------- | ------------------------------- | ---------------------------------------------------- | ------------------------ |
| **[K-Nearest Neighbors API](K_NEAREST_NEIGHBORS_API.md)** | Proximity queries             | k-NN search, radius queries, distance calculations | 0.5-20.2 μs per query  |
| **[Ray Intersection API](RAY_INTERSECTION_API.md)**       | Ray casting and line-of-sight | Ray-volume intersection, traversal, physics        | ~10ms for 10K entities |
| **[Plane Intersection API](PLANE_INTERSECTION_API.md)**   | 3D plane queries              | Arbitrary plane intersection, spatial cutting      | Millisecond response   |
| **[Frustum Culling API](FRUSTUM_CULLING_API.md)**         | Graphics visibility queries   | View frustum culling, occlusion queries            | Graphics optimization  |

### ⚡ **Advanced Features**

High-performance and specialized operations:

| API                                                        | Purpose                         | Key Benefits                              | When to Use                     |
| ------------------------------------------------------------ | --------------------------------- | ------------------------------------------- | --------------------------------- |
| **[Collision Detection API](COLLISION_DETECTION_API.md)**  | Physics and collision systems   | Multi-shape collision, broad/narrow phase | Games, simulations, physics     |
| **[Lock-Free Operations API](LOCKFREE_OPERATIONS_API.md)** | High-performance concurrent ops | 264K movements/sec, atomic protocols      | High-concurrency scenarios      |
| **[Tree Traversal API](TREE_TRAVERSAL_API.md)**            | Visitor pattern tree walking    | Custom traversal strategies, filtering    | Complex tree analysis           |
| **[Tree Balancing API](TREE_BALANCING_API.md)**            | Dynamic tree optimization       | Automatic rebalancing, performance tuning | Maintaining optimal performance |
| **[Ghost API](GHOST_API.md)**                              | Distributed spatial indexing    | gRPC communication, 5 ghost algorithms    | Distributed simulations         |
| **[Neighbor Detection API](NEIGHBOR_DETECTION_API.md)**    | Topological neighbor finding    | O(1) for Octree, face/edge/vertex support | Ghost creation, optimization    |
| **[DSOC API](DSOC_API.md)**                                | Dynamic scene occlusion culling | TBVs, hierarchical Z-buffer, auto-disable  | Rendering optimization          |

### 🌲 **Forest Management**

Multi-tree coordination and specialized forest types:

| API                                                   | Purpose                 | Key Features                         | Scale                              |
| ------------------------------------------------------- | ------------------------- | -------------------------------------- | ------------------------------------ |
| **[Forest Management API](FOREST_MANAGEMENT_API.md)** | Multi-tree coordination | Distributed indexing, load balancing | Massive datasets, multiple regions |

**Forest Specializations Included:**

- **GridForest** - Uniform spatial partitioning
- **AdaptiveForest** - Dynamic density-based adaptation
- **HierarchicalForest** - Level-of-detail management
- **DynamicForestManager** - Runtime tree operations
- **GhostZoneManager** - Boundary synchronization

## API Selection Guide

### 📊 **By Use Case**

**Game Development:**

- Start: [Core Spatial Index](CORE_SPATIAL_INDEX_API.md) + [Entity Management](ENTITY_MANAGEMENT_API.md)
- Add: [Collision Detection](COLLISION_DETECTION_API.md) + [Ray Intersection](RAY_INTERSECTION_API.md)
- Scale: [Lock-Free Operations](LOCKFREE_OPERATIONS_API.md) + [Forest Management](FOREST_MANAGEMENT_API.md)

**Scientific Simulation:**

- Start: [Core Spatial Index](CORE_SPATIAL_INDEX_API.md) + [Bulk Operations](BULK_OPERATIONS_API.md)
- Add: [K-Nearest Neighbors](K_NEAREST_NEIGHBORS_API.md) + [Tree Balancing](TREE_BALANCING_API.md)
- Scale: [Forest Management](FOREST_MANAGEMENT_API.md) + [Ghost API](GHOST_API.md)

**Graphics/Rendering:**

- Start: [Core Spatial Index](CORE_SPATIAL_INDEX_API.md) + [Entity Management](ENTITY_MANAGEMENT_API.md)
- Add: [Frustum Culling](FRUSTUM_CULLING_API.md) + [Ray Intersection](RAY_INTERSECTION_API.md)
- Optimize: [DSOC API](DSOC_API.md) + [Tree Traversal](TREE_TRAVERSAL_API.md)

**High-Frequency Trading/Real-Time:**

- Start: [Core Spatial Index](CORE_SPATIAL_INDEX_API.md) + [Lock-Free Operations](LOCKFREE_OPERATIONS_API.md)
- Add: [K-Nearest Neighbors](K_NEAREST_NEIGHBORS_API.md)
- Scale: [Forest Management](FOREST_MANAGEMENT_API.md)

### 🚀 **By Performance Requirements**

**Ultra-High Performance (μs response times):**

- [Lock-Free Operations API](LOCKFREE_OPERATIONS_API.md) - 264K ops/sec concurrent
- [Bulk Operations API](BULK_OPERATIONS_API.md) - 5-10x faster batch processing
- [K-Nearest Neighbors API](K_NEAREST_NEIGHBORS_API.md) - Sub-microsecond queries

**High Throughput (thousands of ops/sec):**

- [Core Spatial Index API](CORE_SPATIAL_INDEX_API.md) - Optimized core operations
- [Entity Management API](ENTITY_MANAGEMENT_API.md) - Efficient entity lifecycle
- [Forest Management API](FOREST_MANAGEMENT_API.md) - Distributed load balancing

**Large Scale (millions of entities):**

- [Forest Management API](FOREST_MANAGEMENT_API.md) - Multi-tree coordination
- [Tree Balancing API](TREE_BALANCING_API.md) - Maintain performance at scale
- [Bulk Operations API](BULK_OPERATIONS_API.md) - Efficient bulk loading

### 📏 **By Implementation Type**

**Octree (Cubic Subdivision):**

- Best for: Range queries (1.4-6x faster), simple integration
- Use with: [Core Spatial Index](CORE_SPATIAL_INDEX_API.md), [Ray Intersection](RAY_INTERSECTION_API.md)

**Tetree (Tetrahedral Subdivision):**

- Best for: Insertions (2-6x faster), memory efficiency (27-35% less), k-NN queries
- Use with: [Lock-Free Operations](LOCKFREE_OPERATIONS_API.md), [Entity Management](ENTITY_MANAGEMENT_API.md)

**Prism (Triangular Prism Subdivision):**

- Best for: Anisotropic data (fine horizontal, coarse vertical), layered structures
- Use with: [Prism API](PRISM_API.md), [Core Spatial Index](CORE_SPATIAL_INDEX_API.md)

**Forest (Multi-Tree):**

- Best for: Massive scale, complex domains, specialized requirements
- Use with: [Forest Management](FOREST_MANAGEMENT_API.md) + any core APIs

## Integration Patterns

### 🔄 **Progressive Integration**

**Level 1 - Basic Operations:**

```java

// Core spatial indexing
SpatialIndex + EntityManager + basic queries

```

APIs: [Core Spatial Index](CORE_SPATIAL_INDEX_API.md), [Entity Management](ENTITY_MANAGEMENT_API.md)

**Level 2 - Advanced Queries:**

```java

// Add specialized query capabilities  
+ k-NN search + ray intersection + collision detection

```

APIs: [K-Nearest Neighbors](K_NEAREST_NEIGHBORS_API.md), [Ray Intersection](RAY_INTERSECTION_API.md), [Collision Detection](COLLISION_DETECTION_API.md)

**Level 3 - Performance Optimization:**

```java

// High-performance operations
+ bulk operations + lock-free updates + tree balancing

```

APIs: [Bulk Operations](BULK_OPERATIONS_API.md), [Lock-Free Operations](LOCKFREE_OPERATIONS_API.md), [Tree Balancing](TREE_BALANCING_API.md)

**Level 4 - Scale and Specialization:**

```java

// Multi-tree and advanced features
+ forest management + specialized forests + advanced traversal

```

APIs: [Forest Management](FOREST_MANAGEMENT_API.md), [Tree Traversal](TREE_TRAVERSAL_API.md)

## Code Examples by API

### Quick Integration Examples

**Basic Setup:**

```java

// See: Core Spatial Index API
Octree<LongEntityID, GameObject> spatialIndex = new Octree<>(idGen, 10, (byte) 20);
EntityManager<LongEntityID, GameObject> entityManager = new EntityManager<>(spatialIndex, idGen);

```

**High-Performance Queries:**

```java  

// See: K-Nearest Neighbors API + Lock-Free Operations API
List<LongEntityID> nearest = spatialIndex.kNearestNeighbors(position, 10);
LockFreeEntityMover<LongEntityID, GameObject> mover = new LockFreeEntityMover<>(spatialIndex);

```

**Multi-Tree Setup:**

```java

// See: Forest Management API
Forest<MortonKey, LongEntityID, GameObject> forest = new Forest<>();
AdaptiveForest<MortonKey, LongEntityID, GameObject> adaptive = new AdaptiveForest<>(config, treeFactory);

```

**Physics Integration:**

```java

// See: Collision Detection API + Ray Intersection API  
CollisionSystem<LongEntityID, GameObject> collision = new CollisionSystem<>(spatialIndex);
Optional<RayIntersection<LongEntityID, GameObject>> hit = spatialIndex.rayIntersectFirst(ray);

```

**Distributed Ghost Support:**

```java

// See: Ghost API + Neighbor Detection API
spatialIndex.setGhostType(GhostType.FACES);
spatialIndex.createGhostLayer();
GhostCommunicationManager ghostManager = new GhostCommunicationManager(50051, spatialIndex, registry);
ghostManager.syncGhosts(Arrays.asList("tree1", "tree2"), GhostType.FACES);

```

## Performance Reference

### Benchmark Data (July 2025)

| Operation        | Octree          | Tetree          | Winner                                        |
| ------------------ | ----------------- | ----------------- | ----------------------------------------------- |
| **Insertion**    | 1.5-2.0 μs/op   | 0.24-0.95 μs/op | **Tetree 2-6x faster**                        |
| **k-NN Query**   | 15.8-18.2 μs/op | 7.8-19.0 μs/op  | **Mixed, Tetree better for smaller datasets** |
| **Range Query**  | 2.1-14.2 μs/op  | 13.0-19.9 μs/op | **Octree 1.4-6x faster**                      |
| **Memory Usage** | 100%            | 65-73%          | **Tetree 27-35% less memory**                 |

**Concurrent Performance:**

- **Lock-Free Updates**: 264K movements/sec (4 threads)
- **Content Updates**: 1.69M updates/sec
- **Bulk Operations**: 5-10x faster than individual operations

## Migration and Upgrade Paths

### From Single Tree to Forest

1. Start with [Core Spatial Index API](CORE_SPATIAL_INDEX_API.md)
2. Add [Forest Management API](FOREST_MANAGEMENT_API.md)
3. Migrate data using bulk operations from [Bulk Operations API](BULK_OPERATIONS_API.md)

### Performance Optimization Journey

1. Profile with [Tree Balancing API](TREE_BALANCING_API.md)
2. Add [Bulk Operations API](BULK_OPERATIONS_API.md) for large datasets
3. Upgrade to [Lock-Free Operations API](LOCKFREE_OPERATIONS_API.md) for concurrency

### Adding Advanced Features

1. Spatial queries: [K-Nearest Neighbors](K_NEAREST_NEIGHBORS_API.md) → [Ray Intersection](RAY_INTERSECTION_API.md)
2. Physics: [Collision Detection API](COLLISION_DETECTION_API.md)
3. Graphics: [Frustum Culling API](FRUSTUM_CULLING_API.md)

## Best Practices

### 🎯 **API Selection**

- **Start Simple**: Begin with [Core Spatial Index](CORE_SPATIAL_INDEX_API.md)

  and [Entity Management](ENTITY_MANAGEMENT_API.md)

- **Add Incrementally**: Introduce APIs as needed, don't over-engineer
- **Profile First**: Use [Tree Balancing API](TREE_BALANCING_API.md) to identify bottlenecks

### ⚡ **Performance**

- **Batch Operations**: Use [Bulk Operations API](BULK_OPERATIONS_API.md) for large datasets
- **Concurrent Access**: Apply [Lock-Free Operations API](LOCKFREE_OPERATIONS_API.md) for high concurrency
- **Memory Efficiency**: Consider Tetree implementation for memory-constrained environments

### 🌲 **Scaling**

- **Multi-Tree**: Upgrade to [Forest Management API](FOREST_MANAGEMENT_API.md) for large-scale applications
- **Specialization**: Use AdaptiveForest or HierarchicalForest for specific requirements
- **Load Balancing**: Implement proper tree sizing and distribution

## Support and Resources

### Implementation Support

- **Architecture Guide**: [LUCIEN_ARCHITECTURE.md](LUCIEN_ARCHITECTURE.md)
- **Performance Guide**: [SPATIAL_INDEX_PERFORMANCE_GUIDE.md](SPATIAL_INDEX_PERFORMANCE_GUIDE.md)
- **Implementation Details**: [TETREE_IMPLEMENTATION_GUIDE.md](TETREE_IMPLEMENTATION_GUIDE.md)

### Performance Resources

- **Benchmark Results**: [SPATIAL_INDEX_PERFORMANCE_COMPARISON.md](SPATIAL_INDEX_PERFORMANCE_COMPARISON.md)
- **Performance Metrics**: [PERFORMANCE_METRICS_MASTER.md](PERFORMANCE_METRICS_MASTER.md)
- **Optimization Guide**: [LAZY_EVALUATION_USAGE_GUIDE.md](LAZY_EVALUATION_USAGE_GUIDE.md)
- **DSOC Performance Testing**: [DSOC Performance Testing Guide](DSOC_PERFORMANCE_TESTING_GUIDE.md)

### Troubleshooting

- **Known Limitations**: [TM_INDEX_LIMITATIONS_AND_SOLUTIONS.md](TM_INDEX_LIMITATIONS_AND_SOLUTIONS.md)
- **Project Status**: [PROJECT_STATUS.md](PROJECT_STATUS.md)

---

**Last Updated**: August 2025  
**API Count**: 16 comprehensive APIs covering all spatial indexing functionality  
**Status**: Production Ready ✅
