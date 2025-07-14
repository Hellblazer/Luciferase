# Ghost API

## Overview

The Ghost API provides comprehensive distributed spatial index support through ghost elements - non-local elements that maintain neighbor relationships with local elements. This enables efficient parallel computations without explicit communication during computation phases.

**Key Features**:
- Dual ghost approach (distance-based and topology-based)
- Complete neighbor detection for Octree and Tetree
- gRPC-based distributed communication
- Protocol Buffer serialization
- 5 ghost algorithms (MINIMAL, CONSERVATIVE, AGGRESSIVE, ADAPTIVE, CUSTOM)
- Virtual thread support for scalability

## Quick Start

### Basic Ghost Setup

```java
// Enable ghost layer on spatial index
spatialIndex.setGhostType(GhostType.FACES);
spatialIndex.createGhostLayer();

// Query including ghost elements
List<ID> nearbyEntities = spatialIndex.findEntitiesIncludingGhosts(position, radius);

// Get neighbor information including ghosts
List<NeighborInfo<Key>> neighbors = spatialIndex.findNeighborsIncludingGhosts(key);
```

### Distributed Ghost Communication

```java
// Create content serializer registry
ContentSerializerRegistry registry = new ContentSerializerRegistry();
registry.register(String.class, new StringContentSerializer());
registry.register(GameObject.class, new GameObjectSerializer());

// Initialize ghost communication manager
GhostCommunicationManager ghostManager = new GhostCommunicationManager(
    50051,          // Server port
    spatialIndex,   // Your spatial index
    registry        // Content serializers
);

// Start ghost service
ghostManager.startServer();

// Connect to remote spatial indices
ghostManager.addRemoteEndpoint("node1.example.com:50051");
ghostManager.addRemoteEndpoint("node2.example.com:50051");

// Synchronize ghosts
CompletableFuture<SyncResponse> sync = ghostManager.syncGhosts(
    Arrays.asList("tree1", "tree2"),
    GhostType.FACES
);

// Handle synchronization result
sync.thenAccept(response -> {
    System.out.println("Synchronized " + response.getTotalElements() + " ghosts");
});
```

## API Reference

### Ghost Types

```java
public enum GhostType {
    NONE,       // No ghost elements
    FACES,      // Face-adjacent neighbors only
    EDGES,      // Face and edge-adjacent neighbors
    VERTICES    // All neighbors (face, edge, vertex)
}
```

### Spatial Index Ghost Methods

```java
public interface SpatialIndex<Key, ID, Content> {
    // Ghost configuration
    void setGhostType(GhostType ghostType);
    GhostType getGhostType();
    
    // Ghost layer management
    void createGhostLayer();
    void updateGhostLayer();
    GhostLayer<Key, ID, Content> getGhostLayer();
    
    // Ghost-aware queries
    List<ID> findEntitiesIncludingGhosts(Point3f center, float radius);
    List<NeighborInfo<Key>> findNeighborsIncludingGhosts(Key spatialKey);
}
```

### ElementGhostManager

Manages element-level ghost detection and creation:

```java
// Create element ghost manager with algorithm selection
ElementGhostManager<Key, ID, Content> ghostManager = new ElementGhostManager<>(
    spatialIndex,
    neighborDetector,
    GhostType.FACES,
    GhostAlgorithm.CONSERVATIVE
);

// Create ghost layer
ghostManager.createGhostLayer();

// Update ghosts after element modification
ghostManager.updateElementGhosts(modifiedKey);

// Check if element is at boundary
boolean isBoundary = ghostManager.isBoundaryElement(key);

// Get all boundary elements
Set<Key> boundaryElements = ghostManager.getBoundaryElements();
```

### Ghost Algorithms

```java
public enum GhostAlgorithm {
    MINIMAL,      // Only direct neighbors (lowest memory)
    CONSERVATIVE, // Direct + second-level neighbors (balanced)
    AGGRESSIVE,   // Multiple levels for maximum performance
    ADAPTIVE,     // Learns from usage patterns
    CUSTOM        // User-provided algorithm
}
```

### Neighbor Detection

```java
// Get neighbor detector for your spatial index type
NeighborDetector<MortonKey> octreeDetector = new MortonNeighborDetector(octree);
NeighborDetector<TetreeKey> tetreeDetector = new TetreeNeighborDetector(tetree);

// Find neighbors by type
List<MortonKey> faceNeighbors = octreeDetector.findNeighbors(key, GhostType.FACES);
List<MortonKey> allNeighbors = octreeDetector.findNeighbors(key, GhostType.VERTICES);

// Check boundary status
Set<Direction> boundaryDirs = octreeDetector.getBoundaryDirections(key);
boolean atBoundary = octreeDetector.isBoundaryElement(key);
```

### Ghost Communication

```java
// Server-side ghost service
GhostExchangeServiceImpl service = new GhostExchangeServiceImpl(
    spatialIndex,
    contentSerializerRegistry
);

// Client-side ghost requests
GhostServiceClient client = new GhostServiceClient(
    "remote-host:50051",
    contentSerializerRegistry
);

// Request ghosts from remote
GhostBatch ghosts = client.requestGhosts(
    myRank,
    myTreeId,
    GhostType.FACES,
    boundaryKeys
);

// Stream ghost updates
StreamObserver<GhostUpdate> updateStream = client.streamGhostUpdates(
    new StreamObserver<GhostAck>() {
        @Override
        public void onNext(GhostAck ack) {
            if (!ack.getSuccess()) {
                log.error("Ghost update failed: {}", ack.getErrorMessage());
            }
        }
    }
);

// Send ghost update
updateStream.onNext(GhostUpdate.newBuilder()
    .setInsert(ghostElement)
    .build());
```

### Content Serialization

Implement custom serializers for your content types:

```java
public class GameObjectSerializer implements ContentSerializer<GameObject> {
    @Override
    public byte[] serialize(GameObject obj) {
        // Convert to bytes
        return obj.toByteArray();
    }
    
    @Override
    public GameObject deserialize(byte[] data) {
        // Reconstruct from bytes
        return GameObject.fromByteArray(data);
    }
}

// Register serializer
registry.register(GameObject.class, new GameObjectSerializer());
```

## Usage Patterns

### Pattern 1: Basic Ghost Layer

For single-process testing or simple ghost requirements:

```java
// Enable ghosts at initialization
Octree<LongEntityID, String> octree = new Octree<>(idGenerator, 10, (byte) 21);
octree.setGhostType(GhostType.FACES);
octree.createGhostLayer();

// Use ghost-aware queries
List<LongEntityID> nearby = octree.findEntitiesIncludingGhosts(position, 100.0f);
```

### Pattern 2: Distributed Forest with Ghosts

For multi-tree distributed systems:

```java
// Create adaptive forest with ghost support
AdaptiveForest<MortonKey, LongEntityID, Content> forest = new AdaptiveForest<>(
    entityManager,
    AdaptiveForest.Strategy.DENSITY_BASED,
    () -> new Octree<>(idGenerator, 10, (byte) 21)
);

// Enable ghosts
forest.setGhostType(GhostType.EDGES);
forest.updateGhosts();

// Distributed ghost synchronization
DistributedGhostManager<MortonKey, LongEntityID, Content> distManager = 
    new DistributedGhostManager<>(forest, ghostCommunicationManager);

distManager.synchronizeAllTrees();
```

### Pattern 3: Custom Ghost Algorithm

For specialized ghost requirements:

```java
// Implement custom ghost selection
GhostAlgorithm customAlgorithm = new GhostAlgorithm() {
    @Override
    public Set<Key> selectGhostCandidates(Key boundaryElement, 
                                          NeighborDetector<Key> detector) {
        // Custom logic based on application needs
        Set<Key> candidates = new HashSet<>();
        
        // Example: Only ghosts in positive direction
        for (Direction dir : Direction.values()) {
            if (dir.isPositive()) {
                candidates.addAll(detector.findNeighbors(boundaryElement, dir));
            }
        }
        
        return candidates;
    }
};

ElementGhostManager<Key, ID, Content> manager = new ElementGhostManager<>(
    spatialIndex,
    neighborDetector,
    GhostType.FACES,
    customAlgorithm
);
```

## Performance Considerations

### Ghost Performance Metrics

Based on benchmarks, the ghost layer achieves:

| Metric | Target | Achieved |
|--------|--------|----------|
| Memory overhead | < 2x local | 0.01x-0.25x |
| Creation overhead | < 10% | -95% to -99% |
| Serialization | High throughput | 4.8M-108M ops/sec |
| Network utilization | > 80% | Up to 100% |

### Optimization Tips

1. **Algorithm Selection**:
   - Use MINIMAL for memory-constrained environments
   - Use CONSERVATIVE for balanced performance (default)
   - Use AGGRESSIVE for read-heavy workloads
   - Use ADAPTIVE for mixed workloads

2. **Ghost Type Selection**:
   - FACES: Lowest overhead, suitable for most simulations
   - EDGES: Medium overhead, better coverage
   - VERTICES: Highest overhead, complete neighbor coverage

3. **Batch Operations**:
   ```java
   // Batch ghost updates for efficiency
   ghostManager.batchUpdateGhosts(Arrays.asList(key1, key2, key3));
   
   // Bulk synchronization
   ghostCommunicationManager.bulkSync(treeIds, GhostType.FACES);
   ```

4. **Virtual Thread Tuning**:
   ```java
   // Configure virtual thread executor for ghost service
   ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
   ghostManager.setExecutor(executor);
   ```

## Error Handling

```java
try {
    ghostManager.syncGhosts(treeIds, GhostType.FACES)
        .exceptionally(throwable -> {
            log.error("Ghost sync failed", throwable);
            // Fallback to local-only operation
            return null;
        });
} catch (GhostException e) {
    // Handle ghost-specific errors
    switch (e.getErrorType()) {
        case NETWORK_ERROR:
            // Retry with exponential backoff
            break;
        case SERIALIZATION_ERROR:
            // Check content serializer configuration
            break;
        case BOUNDARY_DETECTION_ERROR:
            // Verify spatial index state
            break;
    }
}
```

## Best Practices

1. **Initialize Early**: Set ghost type before inserting entities
2. **Update Strategically**: Update ghosts after bulk operations, not individual inserts
3. **Monitor Performance**: Use ghost statistics to tune algorithms
4. **Handle Failures**: Implement fallback for network failures
5. **Test Locally**: Use ElementGhostManager for single-process testing before distributed deployment

## See Also

- [Ghost Functionality Analysis](GHOST_FUNCTIONALITY_ANALYSIS.md) - Detailed comparison with t8code
- [Ghost Implementation Status](GHOST_IMPLEMENTATION_STATUS.md) - Current implementation state
- [Neighbor Detection](NEIGHBOR_DETECTION_API.md) - Detailed neighbor detection API
- [Forest Management API](FOREST_MANAGEMENT_API.md) - Multi-tree ghost coordination