# Entity Management API Documentation

The Entity Management API provides comprehensive methods for managing entity lifecycle, properties, bounds, and spanning
behavior in the spatial index. This includes entity creation, updates, queries, and advanced features like multi-node
spanning.

## Overview

The Entity Management API covers:

- Entity lifecycle (creation, updates, removal)
- Entity properties (position, bounds, content)
- Entity spanning across multiple spatial nodes
- Entity statistics and monitoring
- Collision shape management

## Entity Lifecycle

### Entity Creation

#### With Auto-Generated ID

```java
ID insert(Point3f position, byte level, Content content)
```

Creates an entity with an automatically generated ID.

**Example:**

```java
// Simple entity creation
Point3f position = new Point3f(100, 50, 200);
Vehicle vehicle = new Vehicle("Car", 1500.0f);
ID vehicleId = spatialIndex.insert(position, (byte) 10, vehicle);
```

#### With Explicit ID

```java
void insert(ID entityId, Point3f position, byte level, Content content)
```

Creates an entity with a specific ID.

**Example:**

```java
// Using custom ID
UUID uuid = UUID.randomUUID();
ID customId = new UUIDEntityID(uuid);
spatialIndex.

insert(customId, position, (byte)10,vehicle);
```

#### With Bounds (Spanning Support)

```java
void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds)
```

Creates a bounded entity that may span multiple nodes.

**Example:**

```java
// Large building that spans multiple nodes
EntityBounds buildingBounds = new EntityBounds(0, 0, 0,      // min corner
                                               50, 100, 50   // max corner  
);

Building building = new Building("Skyscraper");
ID buildingId = new LongEntityID(12345L);

spatialIndex.

insert(buildingId, buildingCenter, (byte)8,building,buildingBounds);
```

### Entity Updates

#### Position Update

```java
void updateEntity(ID entityId, Point3f newPosition, byte level)
```

Efficiently moves an entity to a new position.

**Example:**

```java
// Update moving vehicle
Point3f newPosition = vehicle.calculatePosition(deltaTime);
spatialIndex.

updateEntity(vehicleId, newPosition, (byte)10);
```

### Entity Removal

```java
boolean removeEntity(ID entityId)
```

Removes an entity from all spatial nodes.

**Example:**

```java
// Remove destroyed entity
if(gameObject.isDestroyed()){
boolean removed = spatialIndex.removeEntity(gameObject.getId());
    if(removed){

cleanupResources(gameObject);
    }
    }
```

## Entity Properties

### Content Retrieval

#### Single Entity

```java
Content getEntity(ID entityId)
```

Retrieves the content for a specific entity.

**Example:**

```java
GameObject obj = spatialIndex.getEntity(entityId);
if(obj !=null){
obj.

update(deltaTime);
}
```

#### Multiple Entities

```java
List<Content> getEntities(List<ID> entityIds)
```

Efficiently retrieves content for multiple entities.

**Example:**

```java
// Get all team members
List<ID> teamIds = team.getMemberIds();
List<GameObject> teamMembers = spatialIndex.getEntities(teamIds);

// Process team (null entries for not found)
for(
int i = 0; i <teamMembers.

size();

i++){
GameObject member = teamMembers.get(i);
    if(member !=null){
member.

applyTeamBonus();
    }
    }
```

### Position Queries

#### Get Entity Position

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

#### Get All Entities with Positions

```java
Map<ID, Point3f> getEntitiesWithPositions()
```

Retrieves all entities and their positions efficiently.

**Example:**

```java
// Render all entities
Map<ID, Point3f> allEntities = spatialIndex.getEntitiesWithPositions();

for(
Map.Entry<ID, Point3f> entry :allEntities.

entrySet()){
ID entityId = entry.getKey();
Point3f position = entry.getValue();
Content content = spatialIndex.getEntity(entityId);
    
    renderer.

drawEntity(content, position);
}
```

### Entity Existence

```java
boolean containsEntity(ID entityId)
```

Checks if an entity exists in the spatial index.

**Example:**

```java
// Validate entity before operations
if(spatialIndex.containsEntity(targetId)){

performAction(targetId);
}else{
log.

warn("Target entity {} not found",targetId);
}
```

## Entity Bounds Management

### Get Entity Bounds

```java
EntityBounds getEntityBounds(ID entityId)
```

Retrieves the bounding box of an entity.

**Example:**

```java
EntityBounds bounds = spatialIndex.getEntityBounds(buildingId);
if(bounds !=null){
float width = bounds.getMaxX() - bounds.getMinX();
float height = bounds.getMaxY() - bounds.getMinY();
float depth = bounds.getMaxZ() - bounds.getMinZ();
float volume = width * height * depth;
    
    log.

info("Building volume: {}",volume);
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

## Entity Spanning

Entity spanning allows large entities to exist in multiple spatial nodes simultaneously.

### Get Span Count

```java
int getEntitySpanCount(ID entityId)
```

Returns the number of nodes an entity spans.

**Example:**

```java
int spanCount = spatialIndex.getEntitySpanCount(largeShipId);
if(spanCount >10){
log.

warn("Entity {} spans {} nodes - consider optimization",
     largeShipId, spanCount);
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

**Note**: The spanning policy is configured during spatial index construction and cannot be changed at runtime.

**Example Usage:**

```java
// Configure spanning policy during construction
Octree<ID, Content> octree = new Octree<>(
    idGenerator, 
    maxEntitiesPerNode,
    maxDepth,
    EntitySpanningPolicy.ADAPTIVE  // Set policy here
);

// Insert with bounds triggers spanning based on policy
EntityBounds shipBounds = calculateShipBounds(shipModel);
spatialIndex.insert(shipId, shipCenter, level, ship, shipBounds);
```

## Collision Shape Management

### Set Collision Shape

```java
void setCollisionShape(ID entityId, CollisionShape shape)
```

Sets a custom collision shape for accurate collision detection.

**Example:**

```java
// Set sphere collision for ball
SphereShape ballShape = new SphereShape(2.5f); // radius
spatialIndex.

setCollisionShape(ballId, ballShape);

// Set box collision for crate
BoxShape crateShape = new BoxShape(1.0f, 1.0f, 1.0f); // half extents
spatialIndex.

setCollisionShape(crateId, crateShape);

// Set mesh collision for complex object
MeshShape terrainShape = new MeshShape(terrainMesh);
spatialIndex.

setCollisionShape(terrainId, terrainShape);
```

### Get Collision Shape

```java
CollisionShape getCollisionShape(ID entityId)
```

Retrieves the collision shape for an entity.

**Example:**

```java
CollisionShape shape = spatialIndex.getCollisionShape(entityId);
if(shape instanceof SphereShape){
SphereShape sphere = (SphereShape) shape;
float radius = sphere.getRadius();
// Use sphere-specific collision
}else if(shape ==null){
// Default to AABB collision using bounds
}
```

## Entity Statistics

### Get Comprehensive Stats

```java
EntityStats getStats()
```

Returns detailed statistics about the spatial index.

**Example:**

```java
EntityStats stats = spatialIndex.getStats();

log.

info("Spatial Index Statistics:");
log.

info("  Total Entities: {}",stats.entityCount());
log.

info("  Total Nodes: {}",stats.nodeCount());
log.

info("  Entity References: {}",stats.totalEntityReferences());
log.

info("  Max Tree Depth: {}",stats.maxDepth());
log.

info("  Avg Entities/Node: {:.2f}",stats.averageEntitiesPerNode());
log.

info("  Entity Spanning Factor: {:.2f}",stats.entitySpanningFactor());
```

### Entity Count

```java
int entityCount()
```

Returns the total number of unique entities.

**Example:**

```java
int before = spatialIndex.entityCount();

insertNewEntities();

int after = spatialIndex.entityCount();
log.

info("Inserted {} new entities",after -before);
```

## Advanced Entity Management

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
    private final int    sequence;

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

## Use Cases

### 1. Dynamic Entity System

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

### 2. Large Entity Management

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
            spatialIndex.insert(structureId, structure.getCenter(), (byte) 8, structure, bounds);
        }
    }
}
```

### 3. Entity Lifecycle Manager

```java
public class EntityLifecycleManager {
    private final SpatialIndex<ID, GameObject> spatialIndex;
    private final Map<ID, EntityMetadata>      metadata = new HashMap<>();

    public ID spawn(GameObject object, Point3f position, byte level) {
        // Generate ID
        ID entityId = generateId();

        // Store metadata
        metadata.put(entityId, new EntityMetadata(System.currentTimeMillis(), object.getType(), level));

        // Insert into spatial index
        if (object.hasBounds()) {
            spatialIndex.insert(entityId, position, level, object, object.getBounds());
        } else {
            spatialIndex.insert(entityId, position, level, object);
        }

        // Set collision shape if needed
        if (object.hasCustomCollision()) {
            spatialIndex.setCollisionShape(entityId, object.getCollisionShape());
        }

        return entityId;
    }

    public void destroy(ID entityId) {
        // Remove from spatial index
        if (spatialIndex.removeEntity(entityId)) {
            // Clean up metadata
            metadata.remove(entityId);

            // Notify listeners
            notifyEntityDestroyed(entityId);
        }
    }

    public void migrate(ID entityId, Point3f newPosition, byte newLevel) {
        GameObject object = spatialIndex.getEntity(entityId);
        if (object != null) {
            // Update position efficiently
            spatialIndex.updateEntity(entityId, newPosition, newLevel);

            // Update metadata
            EntityMetadata meta = metadata.get(entityId);
            if (meta != null) {
                meta.lastUpdate = System.currentTimeMillis();
                meta.level = newLevel;
            }
        }
    }
}
```

## Best Practices

1. **ID Generation Strategy**:
    - Use sequential IDs for performance
    - Use UUIDs for distributed systems
    - Implement custom IDs for domain-specific needs

2. **Bounds Management**:
    - Set bounds for entities larger than a single cell
    - Update bounds when entity size changes
    - Consider hierarchical representation for very large entities

3. **Spanning Optimization**:
    - Monitor span counts for performance impact
    - Use adaptive policy for mixed entity sizes
    - Consider LOD for large spanning entities

4. **Batch Operations**:
    - Use batch methods for multiple entities
    - Group related updates together
    - Leverage bulk operations for initialization

5. **Lifecycle Management**:
    - Always clean up removed entities
    - Track entity metadata separately if needed
    - Use weak references for external caches

## Performance Considerations

- **Updates vs Remove/Insert**: `updateEntity()` is optimized for position changes
- **Batch Queries**: `getEntities()` is more efficient than multiple `getEntity()` calls
- **Spanning Impact**: More spanning = more memory and update overhead
- **ID Types**: Long IDs are faster than UUID comparisons
- **Collision Shapes**: Simple shapes (sphere, box) are faster than mesh shapes
