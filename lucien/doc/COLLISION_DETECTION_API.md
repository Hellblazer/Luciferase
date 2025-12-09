# Collision Detection API Documentation

**Last Updated**: 2025-12-08
**Status**: Current

## Overview

The Collision Detection API provides efficient broad-phase and narrow-phase collision detection for entities in spatial
indices. This API supports both point-based and bounded entities, with optimized algorithms for different entity
configurations.

## Core Concepts

### CollisionPair

The `CollisionPair` record represents a detected collision between two entities:

- **entity1Id/entity2Id**: IDs of colliding entities
- **entity1Content/entity2Content**: Content of colliding entities
- **entity1Bounds/entity2Bounds**: Bounds of entities (may be null)
- **contactPoint**: Point where collision occurs
- **contactNormal**: Normal vector at contact point
- **penetrationDepth**: How deeply entities interpenetrate

### CollisionShape

Custom collision shapes for narrow-phase detection:

- Provides precise collision detection beyond AABB
- Supports complex geometries
- Automatically updates bounds when translated

Available shape types:

- **SphereShape**: Simple sphere collision
- **BoxShape**: Axis-aligned bounding box
- **OrientedBoxShape**: Arbitrarily rotated box
- **CapsuleShape**: Cylinder with hemispherical caps
- **MeshShape**: Triangle mesh for complex geometry (NEW)

## API Methods

### 1. Find All Collisions

```java

List<CollisionPair<ID, Content>> findAllCollisions()

```text

Detects all collisions between entities in the spatial index. Results are sorted by penetration depth (deepest first).

**Example:**

```java

List<CollisionPair<LongEntityID, String>> collisions = spatialIndex.findAllCollisions();
for(
CollisionPair<LongEntityID, String> collision :collisions){
System.out.

println("Collision between "+collision.entity1Id() +
" and "+collision.

entity2Id() +
" with penetration: "+collision.

penetrationDepth());
}

```text

### 2. Find Collisions for Specific Entity

```java

List<CollisionPair<ID, Content>> findCollisions(ID entityId)

```text

Finds all entities colliding with a specific entity. More efficient than checking all pairs.

**Example:**

```java

LongEntityID playerId = new LongEntityID(42);
List<CollisionPair<LongEntityID, String>> playerCollisions = spatialIndex.findCollisions(playerId);

```text

### 3. Check Specific Pair

```java

Optional<CollisionPair<ID, Content>> checkCollision(ID entityId1, ID entityId2)

```text

Checks if two specific entities are colliding. Returns detailed collision information if they are.

**Example:**

```java

Optional<CollisionPair<LongEntityID, String>> collision = spatialIndex.checkCollision(entity1, entity2);
if(collision.

isPresent()){

handleCollision(collision.get());
}

```text

### 4. Find Collisions in Region

```java

List<CollisionPair<ID, Content>> findCollisionsInRegion(Spatial region)

```text

Finds all collisions occurring within a specific spatial region.

**Example:**

```java

Spatial.Cube region = new Spatial.Cube(0, 0, 0, 100); // 100x100x100 cube at origin
List<CollisionPair<LongEntityID, String>> regionalCollisions = spatialIndex.findCollisionsInRegion(region);

```text

### 5. Set Custom Collision Shape

```java

void setCollisionShape(ID entityId, CollisionShape shape)

CollisionShape getCollisionShape(ID entityId)

```text

Associates a custom collision shape with an entity for precise narrow-phase detection.

**Example:**

```java

// Create a sphere collision shape
CollisionShape sphere = new SphereShape(center, radius);
spatialIndex.

setCollisionShape(entityId, sphere);

```text

## Collision Detection Phases

### Broad Phase

The spatial index provides efficient broad-phase detection by:

1. Using spatial partitioning to reduce collision checks
2. Only checking entities in the same or neighboring spatial nodes
3. Supporting entity bounds for early rejection

### Narrow Phase

For precise collision detection:

1. **AABB vs AABB**: Fast axis-aligned bounding box checks
2. **Custom Shapes**: Delegate to CollisionShape implementations
3. **Point Collisions**: Use configurable threshold (default 0.1f)

## Collision Types

### 1. Point-Point Collisions

```java

// Two entities without bounds
// Collision occurs when distance <= threshold (0.1f)
Point3f pos1 = entity1.getPosition();
Point3f pos2 = entity2.getPosition();
if (pos1.distance(pos2) <= 0.1f) {
    // Collision detected
}

```text

### 2. AABB-AABB Collisions

```java

// Two bounded entities
// Standard AABB intersection test
if (bounds1.intersects(bounds2)) {
    // Calculate penetration depth and contact info
}

```text

### 3. Point-AABB Collisions

```java

// Mixed: one point entity, one bounded entity
// Point must be inside bounds or within threshold
if (bounds.contains(point) || bounds.distanceTo(point) <= 0.1f) {
    // Collision detected
}

```text

### 4. Custom Shape Collisions

The collision system uses Java 23 pattern matching for clean, maintainable collision detection:

```java

public class CollisionDetector {
    public static CollisionResult detectCollision(CollisionShape shape1, CollisionShape shape2) {
        return switch (shape1) {
            case SphereShape sphere1 -> switch (shape2) {
                case SphereShape sphere2 -> sphereVsSphere(sphere1, sphere2);
                case BoxShape box -> sphereVsBox(sphere1, box);
                case OrientedBoxShape obb -> sphereVsOrientedBox(sphere1, obb);
                case CapsuleShape capsule -> sphereVsCapsule(sphere1, capsule);
                case MeshShape mesh -> sphereVsMesh(sphere1, mesh);
            };
            case BoxShape box1 -> switch (shape2) {
                // ... handle all box collisions
            };
            // ... handle other shape types
        };
    }
}

```text

Each shape simply delegates to the CollisionDetector:

```java

public class SphereShape extends CollisionShape {
    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return CollisionDetector.detectCollision(this, other);
    }
}

```text

### 5. Mesh Collisions

```java

// Create a triangle mesh
TriangleMeshData meshData = new TriangleMeshData();

// Add vertices
int v0 = meshData.addVertex(new Point3f(0, 0, 0));
int v1 = meshData.addVertex(new Point3f(1, 0, 0));
int v2 = meshData.addVertex(new Point3f(0, 1, 0));
int v3 = meshData.addVertex(new Point3f(0, 0, 1));

// Add triangles
meshData.addTriangle(v0, v1, v2);
meshData.addTriangle(v0, v1, v3);
meshData.addTriangle(v0, v2, v3);
meshData.addTriangle(v1, v2, v3);

// Create mesh collision shape
MeshShape mesh = new MeshShape(position, meshData);
spatialIndex.setCollisionShape(entityId, mesh);

```text

The mesh collision system features:

- **BVH Acceleration**: Bounding Volume Hierarchy for efficient triangle queries
- **Triangle-primitive tests**: Accurate collision detection with all shape types
- **Ray intersection**: Fast ray-mesh intersection using BVH traversal
- **Mesh-mesh collision**: Triangle-triangle intersection tests

## Performance Optimization

### Spatial Optimization

- **Neighbor Search**: Only checks adjacent spatial nodes
- **Bounded Entity Optimization**: Uses spatial range queries for large entities
- **Early Rejection**: AABB checks before detailed collision tests

### Algorithmic Features

1. **Sorted Results**: Collisions sorted by penetration depth for priority handling
2. **Duplicate Prevention**: Each pair checked only once
3. **Sparse Storage**: Only non-empty nodes are traversed

### Complexity

- **findAllCollisions**: O(n × m) where n is nodes, m is avg entities per node
- **findCollisions**: O(k × m) where k is nodes containing the entity
- **checkCollision**: O(1) to O(log n) depending on entity locations

## Usage Patterns

### Basic Collision Response

```java

public void handleCollisions() {
    List<CollisionPair<ID, GameObject>> collisions = spatialIndex.findAllCollisions();

    for (CollisionPair<ID, GameObject> collision : collisions) {
        // Apply separation
        Vector3f separation = new Vector3f(collision.contactNormal());
        separation.scale(collision.penetrationDepth() * 0.5f);

        // Move entities apart
        moveEntity(collision.entity1Id(), separation);
        moveEntity(collision.entity2Id(), separation.negate());
    }
}

```text

### Continuous Collision Detection

```java

public void moveWithCollisionDetection(ID entityId, Vector3f velocity, float deltaTime) {
    Point3f currentPos = spatialIndex.getEntityPosition(entityId);
    Point3f targetPos = new Point3f(currentPos);
    targetPos.scaleAdd(deltaTime, velocity, currentPos);

    // Create swept bounds
    EntityBounds sweptBounds = createSweptBounds(currentPos, targetPos);

    // Check for collisions along path
    spatialIndex.updateEntity(entityId, targetPos, level);
    List<CollisionPair<ID, Content>> collisions = spatialIndex.findCollisions(entityId);

    if (!collisions.isEmpty()) {
        // Handle collision, adjust position
        resolveCollisions(entityId, collisions, velocity);
    }
}

```text

### Trigger Volumes

```java

public class TriggerSystem {
    private final Map<ID, Set<ID>> triggeredPairs = new HashMap<>();

    public void updateTriggers() {
        List<CollisionPair<ID, GameObject>> collisions = spatialIndex.findAllCollisions();

        for (CollisionPair<ID, GameObject> collision : collisions) {
            if (isTrigger(collision.entity1Content()) || isTrigger(collision.entity2Content())) {
                UnorderedPair<ID> pair = new UnorderedPair<>(collision.entity1Id(), collision.entity2Id());

                if (!triggeredPairs.containsKey(pair)) {
                    // New trigger event
                    onTriggerEnter(collision);
                    triggeredPairs.put(pair, new HashSet<>());
                }
            }
        }

        // Check for exit events
        cleanupExitedTriggers(collisions);
    }
}

```text

## Best Practices

1. **Entity Bounds**: Always provide bounds for non-point entities for accurate detection
2. **Collision Shapes**: Use custom shapes for complex objects requiring precise detection
3. **Update Frequency**: Balance between accuracy and performance when checking collisions
4. **Spatial Distribution**: Ensure entities are well-distributed to minimize collision checks
5. **Penetration Resolution**: Handle deepest penetrations first for stability

## Thread Safety

Collision detection methods are thread-safe for concurrent reads. The spatial index uses read-write locks to ensure
consistency.

## Advanced Features

### Custom Collision Filtering

```java

public interface CollisionFilter {
    boolean shouldCollide(ID entity1, ID entity2);
}

public List<CollisionPair<ID, Content>> findFilteredCollisions(CollisionFilter filter) {
    List<CollisionPair<ID, Content>> allCollisions = findAllCollisions();
    return allCollisions.stream()
        .filter(c -> filter.shouldCollide(c.entity1Id(), c.entity2Id()))
        .collect(Collectors.toList());
}

```text

### Collision Layers

```java

public class LayeredCollisionSystem {
    private final Map<ID, Integer> entityLayers = new HashMap<>();
    private final boolean[][] layerMatrix = new boolean[32][32];
    
    public List<CollisionPair<ID, Content>> findLayeredCollisions() {
        return findFilteredCollisions((id1, id2) -> {
            int layer1 = entityLayers.getOrDefault(id1, 0);
            int layer2 = entityLayers.getOrDefault(id2, 0);
            return layerMatrix[layer1][layer2];
        });
    }
}

```text

## Integration Example

```java

public class PhysicsEngine {
    private final SpatialIndex<LongEntityID, PhysicsBody> spatialIndex;

    public void simulateStep(float deltaTime) {
        // Update positions based on velocity
        updatePositions(deltaTime);

        // Detect collisions
        List<CollisionPair<LongEntityID, PhysicsBody>> collisions = spatialIndex.findAllCollisions();

        // Resolve collisions
        for (CollisionPair<LongEntityID, PhysicsBody> collision : collisions) {
            resolveCollision(collision);
        }

        // Apply forces and constraints
        applyForces(deltaTime);
    }

    private void resolveCollision(CollisionPair<LongEntityID, PhysicsBody> collision) {
        PhysicsBody body1 = collision.entity1Content();
        PhysicsBody body2 = collision.entity2Content();

        // Calculate impulse
        Vector3f impulse = calculateImpulse(body1, body2, collision.contactPoint(), collision.contactNormal(),
                                            collision.penetrationDepth());

        // Apply impulse
        body1.applyImpulse(impulse);
        body2.applyImpulse(impulse.negate());
    }
}

```text
