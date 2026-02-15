# Core Spatial Index API Documentation

**Last Updated**: 2026-01-04
**Status**: Current

This document provides a comprehensive reference for the core spatial index operations including entity management, spatial queries, and node operations. It combines all fundamental functionality for working with Octree, Tetree, and Prism spatial indices.

## Overview

The Core Spatial Index API provides:

- **Entity Management**: Creation, updates, removal, and lifecycle management
- **Spatial Queries**: Region searches, nearest neighbors, and spatial relationships
- **Node Operations**: Direct node access and manipulation
- **Entity Properties**: Position, bounds, and collision shape management
- **Statistics**: Performance monitoring and index health metrics

## Table of Contents

1. [Entity Insertion](#entity-insertion)
2. [Entity Retrieval](#entity-retrieval)
3. [Entity Updates](#entity-updates)
4. [Entity Removal](#entity-removal)
5. [Spatial Queries](#spatial-queries)
6. [Node Operations](#node-operations)
7. [Entity Properties](#entity-properties)
8. [Entity Bounds and Spanning](#entity-bounds-and-spanning)
9. [Collision Shapes](#collision-shapes)
10. [Statistics and Monitoring](#statistics-and-monitoring)
11. [Advanced Topics](#advanced-topics)

## Entity Insertion

### Insert with Auto-Generated ID

```java

ID insert(Point3f position, byte level, Content content)

```

Inserts content at a position with an automatically generated entity ID.

**Parameters:**
- `position` - The 3D position
- `level` - Spatial refinement level (0-20)
- `content` - The content to store

**Returns:**
- Generated entity ID

**Example:**

```java

// Insert a game object
Point3f position = new Point3f(100, 50, 200);
GameObject gameObject = new GameObject("Player");
ID playerId = spatialIndex.insert(position, (byte) 10, gameObject);

```

### Insert with Explicit ID

```java

void insert(ID entityId, Point3f position, byte level, Content content)

```

Inserts content with a specific entity ID.

**Example:**

```java

// Use custom ID
ID customId = new LongEntityID(12345L);
spatialIndex.insert(customId, position, (byte) 10, gameObject);

// Or with UUID
UUID uuid = UUID.randomUUID();
ID uuidId = new UUIDEntityID(uuid);
spatialIndex.insert(uuidId, position, (byte) 10, gameObject);

```

### Insert with Bounds (Spanning)

```java

void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds)

```

Inserts a bounded entity that may span multiple spatial nodes.

**Example:**

```java

// Insert a large building that spans multiple nodes
EntityBounds buildingBounds = new EntityBounds(
    0, 0, 0,      // min corner
    50, 100, 50   // max corner
);

Building building = new Building("Skyscraper");
ID buildingId = new LongEntityID(12345L);

spatialIndex.insert(buildingId, buildingCenter, (byte) 8, building, buildingBounds);

```

## Entity Retrieval

### Check Entity Existence

```java

boolean containsEntity(ID entityId)

```

Checks if an entity exists in the spatial index.

**Example:**

```java

if (spatialIndex.containsEntity(playerId)) {
    // Entity exists
}

```

### Get Single Entity

```java

Content getEntity(ID entityId)

```

Retrieves the content associated with an entity ID.

**Returns:**
- Content object or null if not found

**Example:**

```java

GameObject player = spatialIndex.getEntity(playerId);
if (player != null) {
    player.update();
}

```

### Get Multiple Entities

```java

List<Content> getEntities(List<ID> entityIds)

```

Efficiently retrieves content for multiple entities.

**Example:**

```java

List<ID> teamIds = getTeamMemberIds();
List<GameObject> teamMembers = spatialIndex.getEntities(teamIds);

// Process team (null entries for not found)
for (int i = 0; i < teamMembers.size(); i++) {
    GameObject member = teamMembers.get(i);
    if (member != null) {
        member.applyTeamBonus();
    }
}

```

### Lookup at Position

```java

List<ID> lookup(Point3f position, byte level)

```

Finds all entities at a specific position and level.

**Example:**

```java

// Find entities at click position
Point3f clickPos = screenToWorld(mouseX, mouseY);
List<ID> entitiesAtClick = spatialIndex.lookup(clickPos, (byte) 10);

```

## Entity Updates

### Update Entity Position

```java

void updateEntity(ID entityId, Point3f newPosition, byte level)

```

Moves an entity to a new position efficiently.

**Example:**

```java

// Update player position each frame
Point3f newPos = calculatePlayerPosition(deltaTime);
spatialIndex.updateEntity(playerId, newPos, (byte) 10);

```

**Note:** This is equivalent to remove + insert but more efficient.

## Entity Removal

### Remove Entity

```java

boolean removeEntity(ID entityId)

```

Removes an entity from all spatial nodes.

**Returns:**
- true if entity was removed, false if not found

**Example:**

```java

// Remove destroyed object
if (gameObject.isDestroyed()) {
    boolean removed = spatialIndex.removeEntity(gameObject.getId());
    if (removed) {
        cleanupResources(gameObject);
    }
}

```

## Spatial Queries

### Find Entities in Region

```java

List<ID> entitiesInRegion(Spatial.Cube region)

```

Finds all entities within an axis-aligned bounding box.

**Example:**

```java

// Find entities in a room
Spatial.Cube room = new Spatial.Cube(
    0, 0, 0,      // min corner
    10, 5, 10     // max corner
);

List<ID> entitiesInRoom = spatialIndex.entitiesInRegion(room);

```

### Find Bounding Nodes

```java

Stream<SpatialNode<Key, ID>> bounding(Spatial volume)

```

Finds all nodes that intersect with a volume.

**Example:**

```java

// Find all nodes intersecting a sphere
Spatial.Sphere sphere = new Spatial.Sphere(center, radius);
List<SpatialNode<Key, ID>> intersectingNodes = spatialIndex.bounding(sphere)
    .collect(Collectors.toList());

```

### Find Bounded Nodes

```java

Stream<SpatialNode<Key, ID>> boundedBy(Spatial volume)

```

Finds nodes completely contained within a volume.

**Example:**

```java

// Find nodes completely inside a region
Spatial.Cube region = new Spatial.Cube(min, max);
spatialIndex.boundedBy(region)
    .forEach(node -> processNode(node));

```

### Find Enclosing Node

```java

SpatialNode<Key, ID> enclosing(Spatial volume)

```

Finds the smallest node that completely contains a volume.

**Example:**

```java

// Find node containing an object
EntityBounds objectBounds = getObjectBounds();
SpatialNode<Key, ID> containingNode = spatialIndex.enclosing(objectBounds);

```

### Find Node at Position

```java

SpatialNode<Key, ID> enclosing(Tuple3i point, byte level)

```

Finds the node at a specific discrete position and level.

**Example:**

```java

// Find node at grid position
Tuple3i gridPos = worldToGrid(worldPos);
SpatialNode<Key, ID> node = spatialIndex.enclosing(gridPos, (byte) 10);

```

## Node Operations

### Check Node Existence

```java

boolean hasNode(Key sfcIndex)

```

Checks if a node exists at a spatial key index.

**Example:**

```java

// For Octree, create a MortonKey
MortonKey key = MortonKey.fromCoordinates(x, y, z, level);
if (spatialIndex.hasNode(key)) {
    // Node exists at this location
}

// For Tetree, create a TetreeKey
TetreeKey key = TetreeKey.fromCoordinates(x, y, z, level);
if (spatialIndex.hasNode(key)) {
    // Node exists at this location
}

```

### Get All Nodes

```java

Stream<SpatialNode<Key, ID>> nodes()

```

Streams all nodes in the spatial index.

**Example:**

```java

// Count entities per node
Map<Key, Integer> entitiesPerNode = spatialIndex.nodes()
    .collect(Collectors.toMap(
        SpatialNode::sfcIndex, 
        node -> node.entityIds().size()
    ));

```

## Entity Properties

### Get Entity Position

```java

Point3f getEntityPosition(ID entityId)

```

Gets the current position of an entity.

**Example:**

```java

Point3f playerPos = spatialIndex.getEntityPosition(playerId);
Point3f enemyPos = spatialIndex.getEntityPosition(enemyId);
float distance = playerPos.distance(enemyPos);

```

### Get All Entities with Positions

```java

Map<ID, Point3f> getEntitiesWithPositions()

```

Retrieves all entities and their positions efficiently.

**Example:**

```java

// Render all entities
Map<ID, Point3f> allEntities = spatialIndex.getEntitiesWithPositions();

for (Map.Entry<ID, Point3f> entry : allEntities.entrySet()) {
    ID entityId = entry.getKey();
    Point3f position = entry.getValue();
    Content content = spatialIndex.getEntity(entityId);
    
    renderer.drawEntity(content, position);
}

```

## Entity Bounds and Spanning

### Get Entity Bounds

```java

EntityBounds getEntityBounds(ID entityId)

```

Retrieves the bounding box of an entity (if set).

**Example:**

```java

EntityBounds bounds = spatialIndex.getEntityBounds(buildingId);
if (bounds != null) {
    float width = bounds.getMaxX() - bounds.getMinX();
    float height = bounds.getMaxY() - bounds.getMinY();
    float depth = bounds.getMaxZ() - bounds.getMinZ();
    float volume = width * height * depth;
    
    log.info("Building volume: {}", volume);
}

```

### EntityBounds Class

```java

public class EntityBounds {
    private final float minX, minY, minZ;
    private final float maxX, maxY, maxZ;
    
    // Constructors
    public EntityBounds(float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ);
    
    public EntityBounds(Point3f center, float halfSize);
    
    // Utility methods
    public float getVolume();
    public Point3f getCenter();
    public boolean contains(Point3f point);
    public boolean intersects(EntityBounds other);
}

```

**Example Usage:**

```java

// Create bounds from center and half-size
Point3f center = new Point3f(50, 25, 50);
float halfSize = 10;
EntityBounds cubeBounds = new EntityBounds(center, halfSize);

// Create bounds from min/max corners  
EntityBounds customBounds = new EntityBounds(
    40, 15, 40,  // min corner
    60, 35, 60   // max corner
);

// Check intersection
if (cubeBounds.intersects(customBounds)) {
    handleOverlap();
}

```

### Get Entity Span Count

```java

int getEntitySpanCount(ID entityId)

```

Returns the number of nodes an entity spans.

**Example:**

```java

int spanCount = spatialIndex.getEntitySpanCount(buildingId);
if (spanCount > 10) {
    log.warn("Entity {} spans {} nodes - consider optimization", 
             buildingId, spanCount);
}

```

### Spanning Policies

The `EntitySpanningPolicy` enum defines how entities span multiple nodes:

```java

public enum EntitySpanningPolicy {
    STRICT,      // Entity exists only in containing node
    OVERLAP,     // Entity spans all overlapping nodes
    ADAPTIVE     // Automatic based on entity size
}

```

**Note**: The spanning policy is configured during spatial index construction.

## Collision Shapes

### Set Collision Shape

```java

void setCollisionShape(ID entityId, CollisionShape shape)

```

Sets a custom collision shape for accurate collision detection.

**Example:**

```java

// Set sphere collision for ball
SphereShape ballShape = new SphereShape(2.5f); // radius
spatialIndex.setCollisionShape(ballId, ballShape);

// Set box collision for crate
BoxShape crateShape = new BoxShape(1.0f, 1.0f, 1.0f); // half extents
spatialIndex.setCollisionShape(crateId, crateShape);

// Set mesh collision for complex object
MeshShape terrainShape = new MeshShape(terrainMesh);
spatialIndex.setCollisionShape(terrainId, terrainShape);

```

### Get Collision Shape

```java

CollisionShape getCollisionShape(ID entityId)

```

Retrieves the collision shape for an entity.

**Example:**

```java

CollisionShape shape = spatialIndex.getCollisionShape(entityId);
if (shape instanceof SphereShape) {
    SphereShape sphere = (SphereShape) shape;
    float radius = sphere.getRadius();
    // Use sphere-specific collision
} else if (shape == null) {
    // Default to AABB collision using bounds
}

```

## Statistics and Monitoring

### Get Entity Count

```java

int entityCount()

```

Gets the total number of unique entities.

**Example:**

```java

int totalEntities = spatialIndex.entityCount();
System.out.println("Total entities: " + totalEntities);

```

### Get Node Count

```java

int nodeCount()

```

Gets the total number of spatial nodes.

**Example:**

```java

int nodes = spatialIndex.nodeCount();
double avgEntitiesPerNode = (double) entityCount() / nodeCount();

```

### Get Comprehensive Stats

```java

EntityStats getStats()

```

Gets detailed statistics about the spatial index.

**Example:**

```java

EntityStats stats = spatialIndex.getStats();

System.out.println("Nodes: " + stats.nodeCount());
System.out.println("Entities: " + stats.entityCount());
System.out.println("Total references: " + stats.totalEntityReferences());
System.out.println("Max depth: " + stats.maxDepth());
System.out.println("Avg entities/node: " + stats.averageEntitiesPerNode());
System.out.println("Spanning factor: " + stats.entitySpanningFactor());

```

## Advanced Topics

### Entity ID Types

The system supports different ID types:

```java

// Long-based IDs (sequential)
public class LongEntityID implements EntityID {
    private final long id;
}

// UUID-based IDs (globally unique)
public class UUIDEntityID implements EntityID {
    private final UUID uuid;
}

// Custom ID implementation
public class CustomEntityID implements EntityID {
    private final String prefix;
    private final int sequence;
    
    @Override
    public int compareTo(EntityID other) {
        // Custom comparison logic
    }
}

```

### Entity ID Generation

```java

public interface EntityIDGenerator<ID extends EntityID> {
    ID generateID();
    ID createID(String representation);
}

// Sequential generator
public class SequentialLongIDGenerator implements EntityIDGenerator<LongEntityID> {
    private final AtomicLong counter = new AtomicLong(0);
    
    @Override
    public LongEntityID generateID() {
        return new LongEntityID(counter.incrementAndGet());
    }
}

```

## Complete Examples

### Entity Lifecycle Example

```java

// 1. Insert entity
Point3f startPos = new Point3f(0, 0, 0);
Enemy enemy = new Enemy("Goblin");
ID enemyId = spatialIndex.insert(startPos, (byte) 10, enemy);

// 2. Update position over time
for (int frame = 0; frame < 100; frame++) {
    Point3f newPos = enemy.calculateMovement(frame);
    spatialIndex.updateEntity(enemyId, newPos, (byte) 10);
}

// 3. Query nearby entities
List<ID> nearby = spatialIndex.kNearestNeighbors(
    spatialIndex.getEntityPosition(enemyId), 5, 50.0f);

// 4. Remove when defeated
if (enemy.isDefeated()) {
    spatialIndex.removeEntity(enemyId);
}

```

### Spatial Region Query Example

```java

// Define search region
Point3f min = new Point3f(100, 0, 100);
Point3f max = new Point3f(200, 50, 200);
Spatial.Cube searchRegion = new Spatial.Cube(min, max);

// Find all entities in region
List<ID> entitiesInRegion = spatialIndex.entitiesInRegion(searchRegion);

// Process each entity
for (ID entityId : entitiesInRegion) {
    Content content = spatialIndex.getEntity(entityId);
    Point3f position = spatialIndex.getEntityPosition(entityId);
    
    processEntity(entityId, content, position);
}

```

### Dynamic Entity System Example

```java

public class DynamicEntitySystem {
    private final SpatialIndex<ID, DynamicEntity> spatialIndex;
    
    public void updateEntities(float deltaTime) {
        Map<ID, Point3f> allPositions = spatialIndex.getEntitiesWithPositions();
        
        for (Map.Entry<ID, Point3f> entry : allPositions.entrySet()) {
            ID entityId = entry.getKey();
            Point3f oldPos = entry.getValue();
            DynamicEntity entity = spatialIndex.getEntity(entityId);
            
            if (entity.isMoving()) {
                Point3f newPos = entity.updatePosition(oldPos, deltaTime);
                spatialIndex.updateEntity(entityId, newPos, entity.getLevel());
            }
        }
    }
}

```

### Large Entity Management Example

```java

public class LargeEntityManager {
    
    public void insertLargeStructure(Structure structure) {
        // Calculate bounds from structure geometry
        EntityBounds bounds = calculateStructureBounds(structure);
        
        // Check spanning impact
        ID tempId = new LongEntityID(-1);
        spatialIndex.insert(tempId, structure.getCenter(), (byte) 8, structure, bounds);
        
        int spanCount = spatialIndex.getEntitySpanCount(tempId);
        spatialIndex.removeEntity(tempId);
        
        if (spanCount > 50) {
            // Too much spanning - use hierarchical representation
            insertHierarchical(structure);
        } else {
            // Acceptable spanning
            ID structureId = generateId();
            spatialIndex.insert(structureId, structure.getCenter(), 
                              (byte) 8, structure, bounds);
        }
    }
}

```

## Best Practices

1. **Choose Appropriate Level**: Higher levels = smaller cells = more precision
2. **Use Bounds for Large Entities**: Entities larger than a single cell should use bounds
3. **Batch Operations**: Use bulk operations for multiple entities
4. **Cache Positions**: Store positions locally if accessing frequently
5. **Clean Up**: Always remove entities when no longer needed
6. **ID Generation Strategy**:
   - Use sequential IDs for performance
   - Use UUIDs for distributed systems
   - Implement custom IDs for domain-specific needs
7. **Spanning Optimization**:
   - Monitor span counts for performance impact
   - Use adaptive policy for mixed entity sizes
   - Consider LOD for large spanning entities

## Performance Tips

- `updateEntity()` is more efficient than remove + insert
- Use `getEntities()` for batch lookups instead of multiple `getEntity()` calls
- Stream operations (`nodes()`, `bounding()`) are memory efficient for large datasets
- Pre-calculate spatial keys for repeated spatial queries
- Long IDs are faster than UUID comparisons
- Simple collision shapes (sphere, box) are faster than mesh shapes
- Monitor entity spanning factor - more spanning = more memory and update overhead
