# Lock-Free Operations API

**Last Updated**: 2026-01-04
**Status**: Current

The Lock-Free Operations API provides high-performance concurrent spatial operations using atomic protocols and optimistic concurrency control. This API enables maximum throughput for entity updates in highly concurrent environments.

## Core Concepts

### Lock-Free Design Principles

The lock-free implementation follows these principles:

- **Atomic Operations**: All state changes use atomic primitives
- **Optimistic Concurrency**: Assume operations will succeed, retry on conflicts  
- **Wait-Free Reads**: Read operations never block
- **ABA Prevention**: Version numbers prevent ABA problems
- **Memory Ordering**: Proper ordering guarantees for concurrent access

### Performance Characteristics

Measured performance (July 2025 benchmarks):

- **Single-threaded Movement**: 101K movements/sec
- **Concurrent Movement (4 threads)**: 264K movements/sec  
- **Content Updates**: 1.69M updates/sec
- **Memory Efficiency**: 187 bytes per entity
- **Zero Conflicts**: No conflicts observed in stress testing

## Core Classes

### LockFreeEntityMover<ID, Content>

High-performance entity movement with atomic four-phase protocol.

```java

// Create lock-free entity mover
LockFreeEntityMover<LongEntityID, String> mover = 
    new LockFreeEntityMover<>(spatialIndex);

// Move entity atomically
Point3f oldPosition = new Point3f(100, 100, 100);
Point3f newPosition = new Point3f(200, 200, 200);
byte level = 10;

boolean success = mover.moveEntity(entityId, oldPosition, newPosition, level);
if (success) {
    // Movement completed successfully
} else {
    // Retry or handle conflict
}

// Batch movement operations
List<EntityMovement<LongEntityID>> movements = Arrays.asList(
    new EntityMovement<>(id1, oldPos1, newPos1, level),
    new EntityMovement<>(id2, oldPos2, newPos2, level)
);

List<Boolean> results = mover.moveEntitiesBatch(movements);

```text

**Four-Phase Atomic Protocol:**
1. **PREPARE**: Validate movement and reserve target location
2. **INSERT**: Atomically insert entity at new location  
3. **UPDATE**: Update entity's position metadata
4. **REMOVE**: Remove entity from old location

**Key Methods:**
- `moveEntity(ID, Point3f, Point3f, byte)` - Atomic entity movement
- `moveEntitiesBatch(List<EntityMovement>)` - Batch atomic movements
- `isMovementInProgress(ID)` - Check if entity is being moved
- `getMovementStatistics()` - Get performance metrics

### AtomicSpatialNode<ID>

Lock-free spatial node implementation using atomic collections.

```java

// Atomic spatial node operations
AtomicSpatialNode<LongEntityID> node = new AtomicSpatialNode<>();

// Add entity atomically
boolean added = node.addEntity(entityId);

// Remove entity atomically  
boolean removed = node.removeEntity(entityId);

// Check entity presence (wait-free)
boolean contains = node.containsEntity(entityId);

// Get entity count (wait-free)
int count = node.getEntityCount();

// Get all entities (wait-free snapshot)
Set<LongEntityID> entities = node.getEntities();

// Atomic batch operations
Set<LongEntityID> toAdd = Set.of(id1, id2, id3);
Set<LongEntityID> toRemove = Set.of(id4, id5);

BatchUpdateResult result = node.updateEntitiesBatch(toAdd, toRemove);

```text

**Implementation Details:**
- Uses `CopyOnWriteArraySet` for entity storage
- Atomic integer for entity count
- Lock-free iteration support
- Optimized for read-heavy workloads

### VersionedEntityState<ID, Content>

Immutable versioned state for optimistic concurrency control.

```java

// Create versioned entity state
Point3f position = new Point3f(100, 100, 100);
String content = "Player Character";
VersionedEntityState<LongEntityID, String> state = 
    new VersionedEntityState<>(entityId, position, content, 1L);

// Access state (immutable)
LongEntityID id = state.getEntityId();
Point3f pos = state.getPosition();
String data = state.getContent();
long version = state.getVersion();
long timestamp = state.getTimestamp();

// Create new version with updated position
VersionedEntityState<LongEntityID, String> newState = 
    state.withPosition(newPosition);

// Create new version with updated content  
VersionedEntityState<LongEntityID, String> updatedState = 
    state.withContent(newContent);

// Version comparison
boolean isNewer = newState.getVersion() > state.getVersion();

```text

**Versioning Properties:**
- **Monotonic Versions**: Versions always increase
- **Immutable State**: State objects never change after creation
- **Atomic Updates**: Version increments are atomic
- **Timestamp Tracking**: Automatic timestamp on creation

## Advanced Operations

### Concurrent Entity Updates

```java

// High-throughput content updates
ExecutorService executor = Executors.newFixedThreadPool(8);
List<CompletableFuture<Void>> futures = new ArrayList<>();

for (int i = 0; i < 10000; i++) {
    final int index = i;
    futures.add(CompletableFuture.runAsync(() -> {
        // Optimistic update with retry
        boolean success = false;
        int retries = 0;
        
        while (!success && retries < 3) {
            VersionedEntityState<LongEntityID, String> currentState = 
                spatialIndex.getEntityState(entityId);
            
            VersionedEntityState<LongEntityID, String> newState = 
                currentState.withContent("Updated content " + index);
            
            success = spatialIndex.compareAndUpdateState(
                entityId, currentState, newState);
            
            if (!success) {
                retries++;
                Thread.yield(); // Brief backoff
            }
        }
    }, executor));
}

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

```text

### Atomic Movement Protocols

```java

// Safe movement with conflict detection
public boolean safeMove(LongEntityID entityId, Point3f from, Point3f to, byte level) {
    // Phase 1: Prepare movement
    MovementContext context = mover.prepareMovement(entityId, from, to, level);
    if (!context.isValid()) {
        return false; // Cannot prepare movement
    }
    
    // Phase 2: Execute atomic movement
    try {
        return mover.executeMovement(context);
    } catch (MovementConflictException e) {
        // Handle conflict - entity moved by another thread
        return false;
    }
}

// Movement with position validation
public boolean validateAndMove(LongEntityID entityId, Point3f expectedPos, 
                              Point3f newPos, byte level) {
    // Atomic check-and-move
    return mover.moveEntityIfAt(entityId, expectedPos, newPos, level);
}

```text

### Batch Operations

```java

// High-performance batch movements
List<EntityMovement<LongEntityID>> movements = new ArrayList<>();

// Prepare batch movements
for (Entity entity : entitiesToMove) {
    Point3f newPos = calculateNewPosition(entity);
    movements.add(new EntityMovement<>(
        entity.getId(), 
        entity.getPosition(), 
        newPos, 
        entity.getLevel()
    ));
}

// Execute batch atomically
BatchMovementResult result = mover.moveEntitiesBatch(movements);

// Process results
for (int i = 0; i < movements.size(); i++) {
    if (result.isSuccess(i)) {
        // Movement succeeded
    } else {
        // Handle failure
        MovementFailureReason reason = result.getFailureReason(i);
        handleMovementFailure(movements.get(i), reason);
    }
}

```text

## Performance Optimization

### Memory Management

```java

// Efficient object reuse
ObjectPool<VersionedEntityState<LongEntityID, String>> statePool = 
    new ObjectPool<>(VersionedEntityState::new);

// Reuse state objects
VersionedEntityState<LongEntityID, String> state = statePool.acquire();
try {
    // Use state object
    state.initialize(entityId, position, content, version);
    // ... operations
} finally {
    statePool.release(state);
}

// Pre-allocated movement contexts
ObjectPool<MovementContext> contextPool = new ObjectPool<>(MovementContext::new);

```text

### Contention Reduction

```java

// Partitioned operations to reduce contention
int partitionCount = Runtime.getRuntime().availableProcessors();
List<LockFreeEntityMover<LongEntityID, String>> partitionedMovers = 
    createPartitionedMovers(spatialIndex, partitionCount);

// Route operations by entity ID hash
int partition = Math.abs(entityId.hashCode()) % partitionCount;
LockFreeEntityMover<LongEntityID, String> mover = partitionedMovers.get(partition);

boolean success = mover.moveEntity(entityId, oldPos, newPos, level);

```text

### Adaptive Backoff

```java

// Adaptive backoff for high contention scenarios
public class AdaptiveBackoffMover<ID extends EntityID, Content> {
    private final LockFreeEntityMover<ID, Content> mover;
    private final ExponentialBackoff backoff;
    
    public boolean moveEntityWithBackoff(ID entityId, Point3f from, Point3f to, byte level) {
        int attempt = 0;
        while (attempt < MAX_ATTEMPTS) {
            boolean success = mover.moveEntity(entityId, from, to, level);
            if (success) {
                backoff.onSuccess();
                return true;
            }
            
            attempt++;
            backoff.backoff(attempt);
        }
        
        return false; // Failed after max attempts
    }
}

```text

## Integration Examples

### Real-Time Game Updates

```java

// High-frequency entity updates for game simulation
public class GameEntityUpdater {
    private final LockFreeEntityMover<LongEntityID, GameEntity> mover;
    private final ScheduledExecutorService scheduler;
    
    public void startUpdates() {
        // Update entities at 60 FPS
        scheduler.scheduleAtFixedRate(this::updateAllEntities, 
                                    0, 16, TimeUnit.MILLISECONDS);
    }
    
    private void updateAllEntities() {
        List<EntityMovement<LongEntityID>> movements = new ArrayList<>();
        
        // Collect all entity movements for this frame
        for (GameEntity entity : activeEntities) {
            Point3f newPosition = entity.calculateNextPosition();
            movements.add(new EntityMovement<>(
                entity.getId(),
                entity.getPosition(),
                newPosition,
                entity.getLevel()
            ));
        }
        
        // Apply all movements atomically
        BatchMovementResult result = mover.moveEntitiesBatch(movements);
        
        // Update entity states based on results
        updateEntityStates(movements, result);
    }
}

```text

### Distributed System Integration

```java

// Lock-free operations for distributed spatial systems
public class DistributedSpatialNode {
    private final AtomicSpatialNode<UUIDEntityID> localNode;
    private final MessageBroker messageBroker;
    
    public boolean insertEntityFromRemote(UUIDEntityID entityId, 
                                        Point3f position, 
                                        String content) {
        // Atomic local insertion
        boolean inserted = localNode.addEntity(entityId);
        
        if (inserted) {
            // Notify other nodes asynchronously
            EntityInsertMessage message = new EntityInsertMessage(
                entityId, position, content, localNode.getNodeId());
            messageBroker.broadcast(message);
        }
        
        return inserted;
    }
    
    public void handleRemoteUpdate(EntityUpdateMessage message) {
        // Apply remote update atomically
        VersionedEntityState<UUIDEntityID, String> remoteState = 
            message.getEntityState();
        
        spatialIndex.updateEntityStateIfNewer(message.getEntityId(), remoteState);
    }
}

```text

### Physics Simulation

```java

// Lock-free physics updates
public class PhysicsSimulation {
    private final LockFreeEntityMover<LongEntityID, PhysicsEntity> mover;
    
    public void simulatePhysicsStep(float deltaTime) {
        // Concurrent physics calculations
        List<CompletableFuture<Point3f>> positionUpdates = physicsEntities.stream()
            .map(entity -> CompletableFuture.supplyAsync(() -> 
                calculatePhysicsPosition(entity, deltaTime)))
            .collect(Collectors.toList());
        
        // Wait for all calculations
        CompletableFuture.allOf(positionUpdates.toArray(new CompletableFuture[0]))
            .join();
        
        // Apply position updates atomically
        List<EntityMovement<LongEntityID>> movements = new ArrayList<>();
        for (int i = 0; i < physicsEntities.size(); i++) {
            PhysicsEntity entity = physicsEntities.get(i);
            Point3f newPosition = positionUpdates.get(i).join();
            
            movements.add(new EntityMovement<>(
                entity.getId(),
                entity.getPosition(),
                newPosition,
                entity.getLevel()
            ));
        }
        
        BatchMovementResult result = mover.moveEntitiesBatch(movements);
        
        // Handle collision detection for successful movements
        handleCollisions(movements, result);
    }
}

```text

## Monitoring and Diagnostics

### Performance Metrics

```java

// Get detailed performance statistics
MovementStatistics stats = mover.getMovementStatistics();

System.out.printf("Total movements: %d%n", stats.getTotalMovements());
System.out.printf("Successful movements: %d%n", stats.getSuccessfulMovements());
System.out.printf("Failed movements: %d%n", stats.getFailedMovements());
System.out.printf("Average latency: %.2f μs%n", stats.getAverageLatencyMicros());
System.out.printf("Throughput: %.0f ops/sec%n", stats.getThroughputPerSecond());
System.out.printf("Conflict rate: %.2f%%%n", stats.getConflictRate() * 100);

// Memory usage statistics
MemoryStatistics memStats = spatialIndex.getMemoryStatistics();
System.out.printf("Memory per entity: %d bytes%n", memStats.getBytesPerEntity());
System.out.printf("Total memory usage: %.2f MB%n", memStats.getTotalMemoryMB());

```text

### Conflict Analysis

```java

// Analyze movement conflicts
ConflictAnalyzer analyzer = new ConflictAnalyzer(mover);

// Track conflicts by region
Map<SpatialRegion, Integer> conflictsByRegion = analyzer.getConflictsByRegion();

// Identify hotspots
List<SpatialRegion> hotspots = analyzer.getHighConflictRegions(0.1f); // >10% conflict rate

// Adaptive partitioning based on conflicts
if (analyzer.getOverallConflictRate() > 0.05f) {
    // Increase partitioning in high-conflict areas
    spatialIndex.increasePartitioning(hotspots);
}

```text

## Best Practices

### 1. Contention Management

- Use partitioned operations for high-throughput scenarios
- Implement adaptive backoff for conflict resolution
- Monitor conflict rates and adjust partitioning

### 2. Memory Efficiency

- Reuse versioned state objects through object pools
- Use batch operations to reduce per-operation overhead
- Monitor memory usage per entity

### 3. Error Handling

- Always handle movement failures gracefully
- Implement retry logic with exponential backoff
- Use versioned state to detect and resolve conflicts

### 4. Performance Tuning

- Profile contention patterns in your specific workload
- Adjust batch sizes based on entity density
- Use appropriate thread pool sizes for your hardware

## Thread Safety Guarantees

All lock-free operations provide:

- **Linearizability**: Operations appear atomic to all threads
- **Wait-Freedom**: Read operations never block
- **Progress Guarantee**: At least one thread makes progress
- **ABA Protection**: Version numbers prevent ABA problems
- **Memory Safety**: No race conditions or data corruption

## Error Handling

```java

try {
    boolean success = mover.moveEntity(entityId, oldPos, newPos, level);
    if (!success) {
        // Handle movement failure
        MovementFailureReason reason = mover.getLastFailureReason(entityId);
        switch (reason) {
            case ENTITY_NOT_FOUND:
                // Entity doesn't exist at expected position
                break;
            case TARGET_OCCUPIED:
                // Target position is occupied
                break;
            case CONCURRENT_MODIFICATION:
                // Another thread modified entity concurrently
                break;
        }
    }
} catch (MovementException e) {
    // Handle unexpected movement errors
    logger.error("Movement failed", e);
}

```text

The Lock-Free Operations API provides maximum performance for concurrent spatial operations while maintaining correctness and thread safety through careful atomic protocol design.
