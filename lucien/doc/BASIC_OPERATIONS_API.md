# Basic Operations API Documentation

This document covers the fundamental operations of the Spatial Index system including insertion, lookup, update, removal, and basic spatial queries.

## Overview

The basic operations provide the foundation for all spatial indexing functionality:
- Single entity insertion with auto-generated or explicit IDs
- Entity lookup and existence checking
- Position updates for moving entities
- Entity removal
- Basic spatial queries (region, bounding, enclosing)

## Insertion Operations

### Insert with Auto-Generated ID

```java
ID insert(Point3f position, byte level, Content content)
```

Insert content at a position with an automatically generated entity ID.

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
ID playerId = spatialIndex.insert(position, (byte)10, gameObject);
```

### Insert with Explicit ID

```java
void insert(ID entityId, Point3f position, byte level, Content content)
```

Insert content with a specific entity ID.

**Example:**
```java
// Use custom ID
ID customId = new LongEntityID(12345L);
spatialIndex.insert(customId, position, (byte)10, gameObject);
```

### Insert with Bounds (Spanning)

```java
void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds)
```

Insert a bounded entity that may span multiple spatial nodes.

**Example:**
```java
// Insert a large building that spans multiple nodes
EntityBounds buildingBounds = new EntityBounds(
    0, 0, 0,      // min corner
    50, 100, 50   // max corner
);

spatialIndex.insert(buildingId, buildingCenter, (byte)8, building, buildingBounds);
```

## Lookup Operations

### Check Entity Existence

```java
boolean containsEntity(ID entityId)
```

Check if an entity exists in the spatial index.

**Example:**
```java
if (spatialIndex.containsEntity(playerId)) {
    // Entity exists
}
```

### Get Entity Content

```java
Content getEntity(ID entityId)
```

Retrieve the content associated with an entity ID.

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

Retrieve content for multiple entities efficiently.

**Example:**
```java
List<ID> teamIds = getTeamMemberIds();
List<GameObject> teamMembers = spatialIndex.getEntities(teamIds);
```

### Lookup at Position

```java
List<ID> lookup(Point3f position, byte level)
```

Find all entities at a specific position and level.

**Example:**
```java
// Find entities at click position
Point3f clickPos = screenToWorld(mouseX, mouseY);
List<ID> entitiesAtClick = spatialIndex.lookup(clickPos, (byte)10);
```

## Entity Properties

### Get Entity Position

```java
Point3f getEntityPosition(ID entityId)
```

Get the current position of an entity.

**Example:**
```java
Point3f playerPos = spatialIndex.getEntityPosition(playerId);
float distanceToOrigin = playerPos.distance(new Point3f(0, 0, 0));
```

### Get Entity Bounds

```java
EntityBounds getEntityBounds(ID entityId)
```

Get the bounding box of an entity (if set).

**Example:**
```java
EntityBounds bounds = spatialIndex.getEntityBounds(buildingId);
if (bounds != null) {
    float volume = bounds.getVolume();
}
```

### Get All Entities with Positions

```java
Map<ID, Point3f> getEntitiesWithPositions()
```

Get all entities and their positions in one call.

**Example:**
```java
// Render all entities
Map<ID, Point3f> allEntities = spatialIndex.getEntitiesWithPositions();
for (Map.Entry<ID, Point3f> entry : allEntities.entrySet()) {
    renderAt(entry.getKey(), entry.getValue());
}
```

## Update Operations

### Update Entity Position

```java
void updateEntity(ID entityId, Point3f newPosition, byte level)
```

Move an entity to a new position.

**Example:**
```java
// Update player position each frame
Point3f newPos = calculatePlayerPosition(deltaTime);
spatialIndex.updateEntity(playerId, newPos, (byte)10);
```

**Note:** This is equivalent to remove + insert but more efficient.

## Removal Operations

### Remove Entity

```java
boolean removeEntity(ID entityId)
```

Remove an entity from all spatial nodes.

**Returns:**
- true if entity was removed, false if not found

**Example:**
```java
// Remove destroyed object
if (gameObject.isDestroyed()) {
    spatialIndex.removeEntity(gameObject.getId());
}
```

## Spatial Query Operations

### Find Entities in Region

```java
List<ID> entitiesInRegion(Spatial.Cube region)
```

Find all entities within an axis-aligned bounding box.

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
Stream<SpatialNode<ID>> bounding(Spatial volume)
```

Find all nodes that intersect with a volume.

**Example:**
```java
// Find all nodes intersecting a sphere
Spatial.Sphere sphere = new Spatial.Sphere(center, radius);

List<SpatialNode<ID>> intersectingNodes = 
    spatialIndex.bounding(sphere)
                .collect(Collectors.toList());
```

### Find Bounded Nodes

```java
Stream<SpatialNode<ID>> boundedBy(Spatial volume)
```

Find nodes completely contained within a volume.

**Example:**
```java
// Find nodes completely inside a region
Spatial.Cube region = new Spatial.Cube(min, max);

spatialIndex.boundedBy(region)
            .forEach(node -> processNode(node));
```

### Find Enclosing Node

```java
SpatialNode<ID> enclosing(Spatial volume)
```

Find the smallest node that completely contains a volume.

**Example:**
```java
// Find node containing an object
EntityBounds objectBounds = getObjectBounds();
SpatialNode<ID> containingNode = spatialIndex.enclosing(objectBounds);
```

### Find Node at Position

```java
SpatialNode<ID> enclosing(Tuple3i point, byte level)
```

Find the node at a specific discrete position and level.

**Example:**
```java
// Find node at grid position
Tuple3i gridPos = worldToGrid(worldPos);
SpatialNode<ID> node = spatialIndex.enclosing(gridPos, (byte)10);
```

## Node Operations

### Check Node Existence

```java
boolean hasNode(long mortonIndex)
```

Check if a node exists at a Morton index.

**Example:**
```java
long mortonIndex = MortonCurve.encode(x, y, z, level);
if (spatialIndex.hasNode(mortonIndex)) {
    // Node exists at this location
}
```

### Get All Nodes

```java
Stream<SpatialNode<ID>> nodes()
```

Stream all nodes in the spatial index.

**Example:**
```java
// Count entities per node
Map<Long, Integer> entitiesPerNode = 
    spatialIndex.nodes()
                .collect(Collectors.toMap(
                    SpatialNode::mortonIndex,
                    node -> node.entityIds().size()
                ));
```

### Get Spatial Map

```java
NavigableMap<Long, Set<ID>> getSpatialMap()
```

Get a navigable view of the spatial index for range queries.

**Example:**
```java
// Find nodes in Morton index range
NavigableMap<Long, Set<ID>> spatialMap = spatialIndex.getSpatialMap();

long startIndex = MortonCurve.encode(0, 0, 0, level);
long endIndex = MortonCurve.encode(100, 100, 100, level);

Map<Long, Set<ID>> nodesInRange = 
    spatialMap.subMap(startIndex, endIndex);
```

## Statistics

### Get Entity Count

```java
int entityCount()
```

Get the total number of unique entities.

**Example:**
```java
int totalEntities = spatialIndex.entityCount();
System.out.println("Total entities: " + totalEntities);
```

### Get Node Count

```java
int nodeCount()
```

Get the total number of spatial nodes.

**Example:**
```java
int nodes = spatialIndex.nodeCount();
double avgEntitiesPerNode = (double) entityCount() / nodeCount();
```

### Get Comprehensive Stats

```java
EntityStats getStats()
```

Get detailed statistics about the spatial index.

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

## Entity Spanning

### Get Span Count

```java
int getEntitySpanCount(ID entityId)
```

Get the number of nodes an entity spans.

**Example:**
```java
int spanCount = spatialIndex.getEntitySpanCount(buildingId);
if (spanCount > 10) {
    // Entity spans many nodes, might need optimization
}
```

## Examples

### Complete Entity Lifecycle
```java
// 1. Insert entity
Point3f startPos = new Point3f(0, 0, 0);
Enemy enemy = new Enemy("Goblin");
ID enemyId = spatialIndex.insert(startPos, (byte)10, enemy);

// 2. Update position over time
for (int frame = 0; frame < 100; frame++) {
    Point3f newPos = enemy.calculateMovement(frame);
    spatialIndex.updateEntity(enemyId, newPos, (byte)10);
}

// 3. Query nearby entities
List<ID> nearby = spatialIndex.kNearestNeighbors(
    spatialIndex.getEntityPosition(enemyId), 
    5, 
    50.0f
);

// 4. Remove when defeated
if (enemy.isDefeated()) {
    spatialIndex.removeEntity(enemyId);
}
```

### Spatial Region Query
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

### Efficient Batch Lookup
```java
// Get all entities and positions in one call
Map<ID, Point3f> allEntities = spatialIndex.getEntitiesWithPositions();

// Filter by distance
Point3f center = new Point3f(500, 50, 500);
float radius = 100.0f;

Map<ID, Point3f> nearbyEntities = allEntities.entrySet().stream()
    .filter(entry -> entry.getValue().distance(center) <= radius)
    .collect(Collectors.toMap(
        Map.Entry::getKey,
        Map.Entry::getValue
    ));
```

## Best Practices

1. **Choose Appropriate Level**: Higher levels = smaller cells = more precision
2. **Use Bounds for Large Entities**: Entities larger than a single cell should use bounds
3. **Batch Operations**: Use bulk operations for multiple entities
4. **Cache Positions**: Store positions locally if accessing frequently
5. **Clean Up**: Always remove entities when no longer needed

## Performance Tips

- `updateEntity()` is more efficient than remove + insert
- Use `getEntities()` for batch lookups instead of multiple `getEntity()` calls
- Stream operations (`nodes()`, `bounding()`) are memory efficient for large datasets
- Pre-calculate Morton indices for repeated spatial queries