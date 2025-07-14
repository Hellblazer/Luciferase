# Neighbor Detection API

## Overview

The Neighbor Detection API provides topological neighbor finding for spatial indices, enabling efficient identification of adjacent elements in 3D space. This API is essential for ghost element creation, collision detection optimization, and spatial queries.

**Key Features**:
- Face, edge, and vertex neighbor detection
- Boundary element identification
- O(1) performance for Octree (Morton codes)
- Support for both Octree and Tetree spatial indices
- Thread-safe operations

## Quick Start

```java
// Create neighbor detector for your spatial index type
Octree<LongEntityID, String> octree = new Octree<>(idGenerator, 10, (byte) 21);
NeighborDetector<MortonKey> detector = new MortonNeighborDetector(octree);

// Find face neighbors
List<MortonKey> faceNeighbors = detector.findNeighbors(key, GhostType.FACES);

// Find all neighbors (faces, edges, vertices)
List<MortonKey> allNeighbors = detector.findNeighbors(key, GhostType.VERTICES);

// Check if element is at boundary
boolean atBoundary = detector.isBoundaryElement(key);

// Get boundary directions
Set<Direction> boundaryDirs = detector.getBoundaryDirections(key);
```

## API Reference

### NeighborDetector Interface

```java
public interface NeighborDetector<Key extends SpatialKey<Key>> {
    
    /**
     * Find neighbors based on ghost type.
     * 
     * @param key The spatial key to find neighbors for
     * @param ghostType Type of neighbors (FACES, EDGES, VERTICES)
     * @return List of neighbor keys
     */
    List<Key> findNeighbors(Key key, GhostType ghostType);
    
    /**
     * Find neighbors with ownership information.
     * 
     * @param key The spatial key
     * @param ghostType Type of neighbors
     * @return List of neighbors with owner rank information
     */
    default List<NeighborInfo<Key>> findNeighborsWithInfo(Key key, GhostType ghostType);
    
    /**
     * Check if element is at a boundary.
     * 
     * @param key The spatial key
     * @return true if element has missing neighbors
     */
    boolean isBoundaryElement(Key key);
    
    /**
     * Get directions where element is at boundary.
     * 
     * @param key The spatial key
     * @return Set of boundary directions
     */
    Set<Direction> getBoundaryDirections(Key key);
}
```

### Direction Enumeration

```java
public enum Direction {
    // Primary face directions
    LEFT(-1, 0, 0),      // -X
    RIGHT(1, 0, 0),      // +X
    DOWN(0, -1, 0),      // -Y
    UP(0, 1, 0),         // +Y
    BACK(0, 0, -1),      // -Z
    FRONT(0, 0, 1),      // +Z
    
    // Edge directions (12 total)
    LEFT_DOWN(-1, -1, 0),
    LEFT_UP(-1, 1, 0),
    // ... etc
    
    // Vertex directions (8 total)
    LEFT_DOWN_BACK(-1, -1, -1),
    LEFT_DOWN_FRONT(-1, -1, 1),
    // ... etc
    
    public boolean isFace();
    public boolean isEdge();
    public boolean isVertex();
    public boolean isPositive();
    public Point3f getOffset();
}
```

### NeighborInfo Record

```java
public record NeighborInfo<Key extends SpatialKey<Key>>(
    Key neighborKey,
    int ownerRank,      // Process that owns this neighbor
    boolean isLocal,    // True if neighbor is on same process
    Direction direction // Direction from source to neighbor
) {}
```

## Implementation Details

### Morton Neighbor Detector (Octree)

Efficient O(1) neighbor finding using bit manipulation:

```java
public class MortonNeighborDetector implements NeighborDetector<MortonKey> {
    
    @Override
    public List<MortonKey> findNeighbors(MortonKey key, GhostType ghostType) {
        List<MortonKey> neighbors = new ArrayList<>();
        
        // Decode Morton key to coordinates
        int level = key.level();
        long morton = key.mortonCode();
        int[] coords = MortonCurve.decode3D(morton);
        
        // Add face neighbors
        if (ghostType.includesFaces()) {
            for (Direction dir : Direction.faceDirections()) {
                MortonKey neighbor = computeNeighbor(coords, level, dir);
                if (neighbor != null) {
                    neighbors.add(neighbor);
                }
            }
        }
        
        // Add edge neighbors
        if (ghostType.includesEdges()) {
            // Similar for edge directions
        }
        
        // Add vertex neighbors
        if (ghostType.includesVertices()) {
            // Similar for vertex directions
        }
        
        return neighbors;
    }
}
```

### Tetree Neighbor Detector

Uses connectivity tables and type transitions:

```java
public class TetreeNeighborDetector implements NeighborDetector<TetreeKey> {
    
    @Override
    public List<TetreeKey> findNeighbors(TetreeKey key, GhostType ghostType) {
        List<TetreeKey> neighbors = new ArrayList<>();
        
        // Reconstruct Tet from key
        Tet tet = keyToTet(key);
        
        // Find face neighbors using connectivity
        if (ghostType.includesFaces()) {
            for (int face = 0; face < 4; face++) {
                TetreeKey neighbor = findFaceNeighbor(tet, face);
                if (neighbor != null) {
                    neighbors.add(neighbor);
                }
            }
        }
        
        // Edge and vertex neighbors use similar approach
        
        return neighbors;
    }
    
    /**
     * Convert TetreeKey back to Tet for neighbor computation.
     */
    public Tet keyToTet(TetreeKey key);
}
```

## Usage Patterns

### Pattern 1: Simple Neighbor Queries

```java
// Find all face neighbors
NeighborDetector<MortonKey> detector = new MortonNeighborDetector(octree);
List<MortonKey> faceNeighbors = detector.findNeighbors(key, GhostType.FACES);

// Process each neighbor
for (MortonKey neighbor : faceNeighbors) {
    // Check if neighbor exists
    if (octree.containsSpatialKey(neighbor)) {
        // Process existing neighbor
    } else {
        // Neighbor doesn't exist - potential ghost
    }
}
```

### Pattern 2: Boundary Detection

```java
// Identify all boundary elements
Set<MortonKey> boundaryElements = new HashSet<>();
for (MortonKey key : octree.getSpatialKeys()) {
    if (detector.isBoundaryElement(key)) {
        boundaryElements.add(key);
        
        // Get specific boundary directions
        Set<Direction> dirs = detector.getBoundaryDirections(key);
        System.out.println("Boundary at: " + dirs);
    }
}
```

### Pattern 3: Ghost Creation

```java
// Use neighbor detection for ghost creation
ElementGhostManager<MortonKey, ID, Content> ghostManager = 
    new ElementGhostManager<>(octree, detector, GhostType.FACES);

// Detector is used internally to find ghost candidates
ghostManager.createGhostLayer();
```

### Pattern 4: Collision Optimization

```java
// Use neighbors for collision detection optimization
public List<CollisionPair> findPotentialCollisions(Key element) {
    List<CollisionPair> pairs = new ArrayList<>();
    
    // Only check neighbors instead of entire tree
    List<Key> neighbors = detector.findNeighbors(element, GhostType.VERTICES);
    
    for (Key neighbor : neighbors) {
        if (spatialIndex.containsSpatialKey(neighbor)) {
            // Check collision between element and neighbor
            pairs.add(new CollisionPair(element, neighbor));
        }
    }
    
    return pairs;
}
```

## Performance Characteristics

### Morton Neighbor Detection (Octree)

| Operation | Complexity | Typical Time |
|-----------|------------|--------------|
| Face neighbors | O(1) | ~10 ns |
| Edge neighbors | O(1) | ~15 ns |
| Vertex neighbors | O(1) | ~20 ns |
| Boundary check | O(1) | ~50 ns |

### Tetree Neighbor Detection

| Operation | Complexity | Typical Time |
|-----------|------------|--------------|
| Face neighbors | O(level) | ~200 ns |
| Edge neighbors | O(level) | ~300 ns |
| Vertex neighbors | O(level) | ~400 ns |
| Key to Tet | O(level) | ~150 ns |

## Advanced Features

### Custom Neighbor Filtering

```java
// Filter neighbors based on custom criteria
List<MortonKey> filteredNeighbors = detector.findNeighbors(key, GhostType.FACES)
    .stream()
    .filter(neighbor -> {
        // Only neighbors at same or finer level
        return neighbor.level() >= key.level();
    })
    .collect(Collectors.toList());
```

### Neighbor Caching

```java
// Cache frequently accessed neighbors
public class CachedNeighborDetector<Key> implements NeighborDetector<Key> {
    private final NeighborDetector<Key> delegate;
    private final Map<Key, List<Key>> cache = new ConcurrentHashMap<>();
    
    @Override
    public List<Key> findNeighbors(Key key, GhostType ghostType) {
        String cacheKey = key + ":" + ghostType;
        return cache.computeIfAbsent(cacheKey, 
            k -> delegate.findNeighbors(key, ghostType));
    }
}
```

### Multi-Level Neighbors

```java
// Find neighbors at different levels
public List<Key> findMultiLevelNeighbors(Key key, GhostType type) {
    List<Key> neighbors = new ArrayList<>();
    
    // Same level neighbors
    neighbors.addAll(detector.findNeighbors(key, type));
    
    // Parent level neighbors
    Key parent = key.parent();
    if (parent != null) {
        neighbors.addAll(detector.findNeighbors(parent, type));
    }
    
    // Child level neighbors (if subdivided)
    for (int i = 0; i < 8; i++) {
        Key child = key.child(i);
        neighbors.addAll(detector.findNeighbors(child, type));
    }
    
    return neighbors;
}
```

## Implementation Notes

### Octree Specifics

- Uses direct bit manipulation on Morton codes
- No tree traversal required
- Handles boundary wrapping for periodic domains
- Efficient for uniform grids

### Tetree Specifics

- Requires reconstruction of Tet from TetreeKey
- Uses connectivity tables for type transitions
- More complex due to tetrahedral geometry
- Handles S0-S5 subdivision correctly

## Error Handling

```java
try {
    List<Key> neighbors = detector.findNeighbors(key, ghostType);
} catch (IllegalArgumentException e) {
    // Invalid key or ghost type
    log.error("Invalid neighbor query: {}", e.getMessage());
} catch (IllegalStateException e) {
    // Spatial index in invalid state
    log.error("Spatial index error: {}", e.getMessage());
}
```

## Best Practices

1. **Cache Detectors**: Create once and reuse for multiple queries
2. **Batch Queries**: Process multiple keys together for better cache utilization
3. **Level Awareness**: Consider level differences in multi-resolution trees
4. **Memory Efficiency**: Use iterators for large neighbor sets
5. **Thread Safety**: Detectors are thread-safe, but spatial index modifications require synchronization

## See Also

- [Ghost API](GHOST_API.md) - Using neighbor detection for ghost creation
- [Core Spatial Index API](CORE_SPATIAL_INDEX_API.md) - Spatial index operations
- [Collision Detection API](COLLISION_DETECTION_API.md) - Using neighbors for collision optimization