# Entity Management API

The Entity Management API provides centralized entity lifecycle management for spatial indexing operations. This API handles entity creation, identification, spatial bounds, and lifecycle operations across both single trees and forest configurations.

## Core Entity Classes

### EntityManager<ID, Content>

The central entity management class that handles entity lifecycle operations.

```java
// Create entity manager with ID generator
SequentialLongIDGenerator idGenerator = new SequentialLongIDGenerator();
EntityManager<LongEntityID, GameObject> entityManager = 
    new EntityManager<>(spatialIndex, idGenerator);

// Insert entity at position
Point3f position = new Point3f(100, 50, 200);
GameObject player = new GameObject("Player");
LongEntityID playerId = entityManager.insertEntity(position, (byte) 10, player);

// Insert entity with bounds (for spanning entities)
EntityBounds bounds = new EntityBounds(-25, -50, -25, 25, 50, 25);
LongEntityID buildingId = entityManager.insertEntity(position, (byte) 8, building, bounds);

// Update entity position
entityManager.updateEntity(playerId, newPosition, (byte) 10);

// Remove entity
entityManager.removeEntity(playerId);
```

**Key Methods:**
- `insertEntity(Point3f, byte, Content)` - Insert point entity
- `insertEntity(Point3f, byte, Content, EntityBounds)` - Insert bounded entity
- `updateEntity(ID, Point3f, byte)` - Update entity position
- `removeEntity(ID)` - Remove entity from spatial index
- `getEntity(ID)` - Retrieve entity by ID
- `getAllEntities()` - Get all managed entities
- `getEntityPosition(ID)` - Get current entity position
- `getEntityBounds(ID)` - Get entity spatial bounds

### Entity<ID, Content>

Represents a spatial entity with position, content, and optional bounds.

```java
// Create entity
LongEntityID entityId = new LongEntityID(42L);
Point3f position = new Point3f(10, 20, 30);
String content = "Player Character";
EntityBounds bounds = new EntityBounds(-1, -1, -1, 1, 1, 1);

Entity<LongEntityID, String> entity = new Entity<>(entityId, position, content, bounds);

// Entity properties
Point3f pos = entity.getPosition();
String data = entity.getContent();
EntityBounds entityBounds = entity.getBounds();
boolean hasCustomBounds = entity.hasCustomBounds();
```

### EntityBounds

Defines spatial boundaries for entities that span multiple spatial cells.

```java
// Create entity bounds (relative to entity position)
EntityBounds bounds = new EntityBounds(
    -50.0f, -25.0f, -30.0f,  // min offsets (x, y, z)
     50.0f,  25.0f,  30.0f   // max offsets (x, y, z)
);

// Check if point is within bounds (relative to entity position)
boolean contains = bounds.contains(relativePoint);

// Get absolute bounds for entity at position
Point3f entityPos = new Point3f(100, 100, 100);
Spatial.Cube absoluteBounds = bounds.getAbsoluteBounds(entityPos);

// Bounds properties
float width = bounds.getWidth();   // max.x - min.x
float height = bounds.getHeight(); // max.y - min.y  
float depth = bounds.getDepth();   // max.z - min.z
float volume = bounds.getVolume(); // width * height * depth
```

**Common Bound Patterns:**
```java
// Point entity (no bounds)
EntityBounds pointBounds = EntityBounds.POINT; // (0,0,0) to (0,0,0)

// Cube entity
EntityBounds cubeBounds = EntityBounds.cube(5.0f); // -5 to +5 in all dimensions

// Box entity  
EntityBounds boxBounds = EntityBounds.box(10.0f, 5.0f, 2.0f); // custom dimensions

// Sphere entity (approximated as cube)
EntityBounds sphereBounds = EntityBounds.sphere(3.0f); // radius 3.0
```

## Entity Identification

### EntityID Hierarchy

```java
// Base interface
public interface EntityID extends Comparable<EntityID> {
    String asString();
}

// Implementations
LongEntityID longId = new LongEntityID(12345L);
UUIDEntityID uuidId = new UUIDEntityID(UUID.randomUUID());
```

### LongEntityID

Efficient long-based entity identification.

```java
// Create long-based ID
LongEntityID id1 = new LongEntityID(42L);
LongEntityID id2 = LongEntityID.of(100L);

// Comparison and operations
boolean equal = id1.equals(id2);
int comparison = id1.compareTo(id2);
String stringRep = id1.asString(); // "42"
long value = id1.getValue();
```

### UUIDEntityID

UUID-based entity identification for distributed systems.

```java
// Create UUID-based ID
UUIDEntityID id1 = new UUIDEntityID();                    // Random UUID
UUIDEntityID id2 = new UUIDEntityID(UUID.randomUUID());   // Specific UUID
UUIDEntityID id3 = UUIDEntityID.fromString("123e4567-e89b-12d3-a456-426614174000");

// Properties
UUID uuid = id1.getUUID();
String stringRep = id1.asString();
```

## ID Generation

### EntityIDGenerator<ID>

Interface for ID generation strategies.

```java
public interface EntityIDGenerator<ID extends EntityID> {
    ID generateID();
    void reset();        // Reset generator state
    long getCount();     // Get generation count
}
```

### SequentialLongIDGenerator

Generates sequential long-based IDs for high-performance scenarios.

```java
// Create sequential generator
SequentialLongIDGenerator generator = new SequentialLongIDGenerator();

// Generate IDs
LongEntityID id1 = generator.generateID(); // ID: 1
LongEntityID id2 = generator.generateID(); // ID: 2
LongEntityID id3 = generator.generateID(); // ID: 3

// Generator state
long count = generator.getCount(); // 3
generator.reset(); // Start over from 1

// Start from specific value
SequentialLongIDGenerator custom = new SequentialLongIDGenerator(1000L);
LongEntityID id = custom.generateID(); // ID: 1001
```

**Thread Safety:**
```java
// Thread-safe sequential generation
SequentialLongIDGenerator generator = new SequentialLongIDGenerator();

ExecutorService executor = Executors.newFixedThreadPool(10);
List<Future<LongEntityID>> futures = new ArrayList<>();

for (int i = 0; i < 1000; i++) {
    futures.add(executor.submit(() -> generator.generateID()));
}

// All IDs will be unique across threads
```

### UUIDGenerator

Generates UUID-based IDs for distributed systems.

```java
// Create UUID generator  
UUIDGenerator generator = new UUIDGenerator();

// Generate unique IDs
UUIDEntityID id1 = generator.generateID();
UUIDEntityID id2 = generator.generateID();

// UUIDs are globally unique
assert !id1.equals(id2);
```

## Entity Operations

### EntityData<ID, Content>

Wrapper for entity data with metadata.

```java
// Create entity data
EntityData<LongEntityID, String> data = new EntityData<>(
    entityId, 
    position, 
    content, 
    System.currentTimeMillis() // timestamp
);

// Access data
LongEntityID id = data.getEntityId();
Point3f pos = data.getPosition();
String content = data.getContent();
long timestamp = data.getTimestamp();
```

### EntityDistance<ID, Content>

Entity with distance information for proximity queries.

```java
// Created by k-NN queries
List<EntityDistance<LongEntityID, String>> nearest = 
    spatialIndex.kNearestNeighborsWithDistance(queryPoint, 5);

for (EntityDistance<LongEntityID, String> result : nearest) {
    LongEntityID id = result.getEntityId();
    String content = result.getContent();
    float distance = result.getDistance();
    Point3f position = result.getPosition();
    
    System.out.printf("Entity %s at distance %.2f%n", id, distance);
}
```

## Entity Spanning Policies

### EntitySpanningPolicy

Controls how large entities are handled across multiple spatial cells.

```java
public enum EntitySpanningPolicy {
    SINGLE_CELL,    // Entity placed in one cell only
    SPAN_CELLS,     // Entity spans multiple cells
    REPLICATE       // Entity replicated in all intersecting cells
}

// Configure spanning policy
spatialIndex.setEntitySpanningPolicy(EntitySpanningPolicy.SPAN_CELLS);

// Insert large entity that spans multiple cells
EntityBounds largeBounds = new EntityBounds(-100, -100, -100, 100, 100, 100);
entityManager.insertEntity(centerPosition, (byte) 5, largeBuilding, largeBounds);
```

**Policy Behaviors:**
- **SINGLE_CELL**: Entity stored in one cell based on center position
- **SPAN_CELLS**: Entity stored in all cells it intersects  
- **REPLICATE**: Entity duplicated in each intersecting cell

## Integration Examples

### Basic Entity Lifecycle

```java
// Setup
SequentialLongIDGenerator idGen = new SequentialLongIDGenerator();
Octree<LongEntityID, GameObject> spatialIndex = new Octree<>(idGen, 10, (byte) 20);
EntityManager<LongEntityID, GameObject> entityManager = 
    new EntityManager<>(spatialIndex, idGen);

// Create and insert entities
GameObject player = new GameObject("Player");
LongEntityID playerId = entityManager.insertEntity(
    new Point3f(0, 0, 0), (byte) 10, player);

GameObject building = new GameObject("Building");
EntityBounds buildingBounds = new EntityBounds(-50, -25, -50, 50, 75, 50);
LongEntityID buildingId = entityManager.insertEntity(
    new Point3f(200, 0, 200), (byte) 8, building, buildingBounds);

// Query nearby entities
List<LongEntityID> nearby = spatialIndex.kNearestNeighbors(
    new Point3f(10, 0, 10), 5, 100.0f);

// Update entity position
entityManager.updateEntity(playerId, new Point3f(10, 0, 10), (byte) 10);

// Remove entity
entityManager.removeEntity(buildingId);
```

### Forest Integration

```java
// Entity manager for forest operations
Forest<MortonKey, LongEntityID, GameObject> forest = new Forest<>();
ForestEntityManager<LongEntityID, GameObject> forestEM = 
    new ForestEntityManager<>(forest);

// Configure entity assignment strategy
forestEM.setAssignmentStrategy(AssignmentStrategy.SPATIAL_BOUNDS);

// Insert entities - automatically assigned to appropriate trees
LongEntityID id1 = forestEM.insertEntity(new Point3f(100, 0, 100), player);
LongEntityID id2 = forestEM.insertEntity(new Point3f(500, 0, 500), npc);

// Cross-tree operations handled automatically
forestEM.updateEntityPosition(id1, new Point3f(600, 0, 600)); // May migrate trees
List<LongEntityID> nearbyAcrossTrees = forestEM.findKNearestNeighbors(
    new Point3f(400, 0, 400), 10);
```

### Batch Operations

```java
// Bulk entity insertion for performance
List<Point3f> positions = generatePositions(10000);
List<GameObject> entities = generateEntities(10000);

// Enable bulk loading
spatialIndex.enableBulkLoading();

// Batch insert
List<LongEntityID> ids = entityManager.insertBatch(positions, entities, (byte) 10);

// Finalize bulk loading
spatialIndex.finalizeBulkLoading();
```

### Custom Entity Types

```java
// Custom entity with rich data
public class GameEntity {
    private String name;
    private EntityType type;
    private Properties properties;
    private List<Component> components;
    
    // ... getters and setters
}

// Use with entity manager
EntityManager<UUIDEntityID, GameEntity> gameEntityManager = 
    new EntityManager<>(spatialIndex, new UUIDGenerator());

GameEntity dragon = new GameEntity("Ancient Dragon", EntityType.NPC);
UUIDEntityID dragonId = gameEntityManager.insertEntity(
    dragonPosition, (byte) 5, dragon, largeBounds);
```

## Performance Considerations

### ID Generation Performance

```java
// Sequential IDs: ~100M IDs/second (single thread)
SequentialLongIDGenerator fastGen = new SequentialLongIDGenerator();

// UUID IDs: ~2M IDs/second (single thread)  
UUIDGenerator secureGen = new UUIDGenerator();

// Choose based on requirements:
// - Sequential: High performance, single system
// - UUID: Distributed systems, global uniqueness
```

### Memory Efficiency

```java
// Efficient entity storage
EntityBounds.POINT;                    // Singleton for point entities
EntityBounds.cube(size);               // Cached common sizes
entity.hasCustomBounds();              // Avoid bounds overhead for point entities

// Bulk operations reduce per-entity overhead
entityManager.insertBatch(positions, contents, level);
```

### Thread Safety

All entity management operations are thread-safe:

```java
// Concurrent entity operations
ExecutorService executor = Executors.newFixedThreadPool(8);

// Safe concurrent insertions
executor.submit(() -> entityManager.insertEntity(pos1, level, content1));
executor.submit(() -> entityManager.insertEntity(pos2, level, content2));
executor.submit(() -> entityManager.updateEntity(id, newPos, level));
```

## Best Practices

### 1. ID Generator Selection
- Use `SequentialLongIDGenerator` for single-system, high-performance scenarios
- Use `UUIDGenerator` for distributed systems or when global uniqueness is required
- Reset generators appropriately when restarting systems

### 2. Entity Bounds
- Use `EntityBounds.POINT` for point entities to minimize memory overhead
- Define bounds relative to entity center position
- Consider spanning policy impact on query performance

### 3. Batch Operations
- Use bulk loading for large initial datasets
- Batch related operations when possible
- Enable bulk loading before large insertions, disable afterward

### 4. Entity Lifecycle
- Remove entities promptly when no longer needed
- Update positions incrementally rather than remove/reinsert
- Use appropriate precision levels for different entity types

## Error Handling

```java
try {
    entityManager.insertEntity(position, level, content);
} catch (IllegalArgumentException e) {
    // Invalid position, level, or content
} catch (EntityAlreadyExistsException e) {
    // Entity ID already exists
} catch (SpatialIndexException e) {
    // General spatial index error
}

// Safe entity retrieval
Optional<Entity<ID, Content>> entity = entityManager.getEntitySafe(entityId);
if (entity.isPresent()) {
    // Process entity
}
```