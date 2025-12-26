# Lucien Module

**Last Updated**: 2025-12-08
**Status**: Current

Core spatial indexing and collision detection for Luciferase

## Overview

Lucien provides the fundamental spatial data structures and algorithms for 3D spatial indexing, including both Octree (cubic) and Tetree (tetrahedral) implementations with unified APIs through Java generics.

## Features

### Spatial Index Types

- **Octree**: Morton curve-based cubic subdivision
  - 21-level depth support (~2 billion nodes)
  - O(1) Morton encoding/decoding
  - Optimal for axis-aligned queries

- **Tetree**: Tetrahedral space-filling curve subdivision  
  - 21-level depth matching Octree capacity
  - TM-index curve navigation
  - Better space utilization for irregular data

### Core Capabilities

- **Unified Generic Architecture**: `AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>`
- **Multi-Entity Support**: Multiple entities per spatial location
- **Thread-Safe Operations**: Fine-grained locking with ReadWriteLock
- **Advanced Queries**:
  - K-nearest neighbor search
  - Range queries
  - Ray intersection
  - Frustum culling
  - Collision detection

### Performance Features

- HashMap-based O(1) node access
- Object pooling for GC reduction
- Lock-free entity movement
- Adaptive tree balancing
- Bulk operation support

## Package Structure

```text
com.hellblazer.luciferase.lucien/
├── core/           # Core spatial index classes (27 classes)
├── entity/         # Entity management (12 classes)
├── grid/           # Octree implementation (5 classes)
├── tetree/         # Tetree implementation (32 classes)
├── collision/      # Collision detection (12 classes)
├── balancing/      # Tree balancing strategies (3 classes)
├── visitor/        # Visitor pattern traversal (6 classes)
└── index/          # TM-index implementation (1 class)

```

## Usage Examples

### Basic Octree Operations

```java
// Create an Octree
var octree = new Octree();
octree.setMaxDepth(10);

// Insert entity
var entityId = UUID.randomUUID();
var position = new Point3f(10, 20, 30);
var bounds = new Bounds3f(position, 1.0f);
octree.insert(entityId, bounds, position);

// Find neighbors
var neighbors = octree.findKNearestNeighborsAtPosition(position, 5);

// Update entity position
var newPosition = new Point3f(15, 25, 35);
octree.update(entityId, newPosition);

// Remove entity
octree.remove(entityId);

```

### Tetree with Collision Detection

```java
// Create Tetree with collision system
var tetree = new Tetree();
var collisionSystem = new CollisionSystem<>(tetree);

// Add collision shape
var shape = new SphereShape(5.0f);
collisionSystem.addCollisionShape(entityId, shape);

// Detect collisions
var collisions = collisionSystem.detectCollisions();
for (var collision : collisions) {
    System.out.println("Collision between " + 
        collision.getEntityA() + " and " + collision.getEntityB());
}

```

### Advanced Queries

```java
// Ray intersection
var ray = new Ray3f(origin, direction);
var hits = octree.intersectRay(ray, maxDistance);

// Frustum culling
var frustum = camera.getFrustum();
var visible = octree.queryFrustum(frustum);

// Range query
var center = new Point3f(0, 0, 0);
var radius = 50.0f;
var inRange = octree.findEntitiesInRadius(center, radius);

```

## Performance Benchmarks

Results for 10,000 entities on Apple M1:

| Operation | Octree | Tetree |
| ----------- | -------- | -------- |
| Insert | 285K ops/sec | 541K ops/sec |
| Update | 189K ops/sec | 364K ops/sec |
| Remove | 298K ops/sec | 476K ops/sec |
| k-NN (k=10) | 5.5K ops/sec | 3.1K ops/sec |
| Ray Cast | 26K ops/sec | 15K ops/sec |
| Collision | 419 ops/sec | 312 ops/sec |

## Thread Safety

All spatial index operations are thread-safe through:

- ConcurrentSkipListMap for node storage
- CopyOnWriteArrayList for entity lists
- Fine-grained locking strategies
- Lock-free movement protocols

## Memory Management

- Object pooling reduces GC pressure
- Typical memory usage: 187 bytes per entity
- Bulk operations minimize allocations
- Configurable node capacity limits

## Testing

```bash
# Run all Lucien tests

mvn test -pl lucien

# Run specific test

mvn test -pl lucien -Dtest=OctreeTest

# Run performance benchmarks

mvn test -pl lucien -Pperformance

```

## Dependencies

- `common` module for optimized collections
- `javax.vecmath` for 3D mathematics
- SLF4J/Logback for logging
- JUnit 5 for testing

## Documentation

- [Architecture Overview](doc/LUCIEN_ARCHITECTURE.md)
- [Performance Metrics](doc/PERFORMANCE_METRICS_MASTER.md)

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details
