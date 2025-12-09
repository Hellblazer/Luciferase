# K-Nearest Neighbors API Documentation

**Last Updated**: 2025-12-08
**Status**: Current

The K-Nearest Neighbors (k-NN) API provides efficient methods for finding the closest entities to a query point in 3D
space. This is essential for proximity queries, spatial clustering, and location-based services.

## Overview

The k-NN API allows you to:

- Find the k closest entities to any point in space
- Limit searches by maximum distance
- Efficiently query large spatial datasets
- Get results sorted by distance

## API Methods

### Basic k-NN Search

```java

List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance)

```text

Finds the k nearest entities to a query point within a maximum distance.

**Parameters:**

- `queryPoint` - The 3D point to search from
- `k` - Number of neighbors to find
- `maxDistance` - Maximum search radius

**Returns:**

- List of entity IDs sorted by distance (closest first)

**Example:**

```java

// Find 5 nearest enemies to player
Point3f playerPos = player.getPosition();
List<ID> nearestEnemies = spatialIndex.kNearestNeighbors(playerPos, 5, 100.0f  // Within 100 units
                                                        );

// Attack closest enemy
if(!nearestEnemies.

isEmpty()){
ID closestEnemy = nearestEnemies.get(0);
    player.

attack(closestEnemy);
}

```text

### Extended k-NN Search (Implementation Pattern)

While the core API provides basic k-NN, you can build extended functionality:

```java

// Get k-NN with distances
public List<EntityDistance<ID>> kNearestNeighborsWithDistance(Point3f queryPoint, int k, float maxDistance) {

    List<ID> neighbors = spatialIndex.kNearestNeighbors(queryPoint, k, maxDistance);
    List<EntityDistance<ID>> results = new ArrayList<>();

    for (ID entityId : neighbors) {
        Point3f entityPos = spatialIndex.getEntityPosition(entityId);
        float distance = queryPoint.distance(entityPos);
        results.add(new EntityDistance<>(entityId, distance));
    }

    return results;
}

```text

## Use Cases

### 1. Proximity Detection

```java

// Find nearby interactive objects
public List<InteractableObject> findInteractables(Point3f playerPos) {
    float interactionRadius = 5.0f;
    int maxInteractables = 10;

    List<ID> nearbyIds = spatialIndex.kNearestNeighbors(playerPos, maxInteractables, interactionRadius);

    List<InteractableObject> interactables = new ArrayList<>();
    for (ID id : nearbyIds) {
        Content content = spatialIndex.getEntity(id);
        if (content instanceof InteractableObject) {
            interactables.add((InteractableObject) content);
        }
    }

    return interactables;
}

```text

### 2. AI Target Selection

```java

// AI finds nearest targets
public ID selectTarget(Point3f aiPosition, TargetFilter filter) {
    int searchLimit = 20;
    float sightRange = 50.0f;

    List<ID> candidates = spatialIndex.kNearestNeighbors(aiPosition, searchLimit, sightRange);

    // Filter and select best target
    for (ID candidateId : candidates) {
        Content content = spatialIndex.getEntity(candidateId);
        if (filter.isValidTarget(content)) {
            // Check line of sight
            if (hasLineOfSight(aiPosition, candidateId)) {
                return candidateId;
            }
        }
    }

    return null; // No valid target found
}

```text

### 3. Spatial Clustering

```java

// Find cluster members around a seed point
public List<ID> findClusterMembers(Point3f seedPoint, float clusterRadius) {
    // Use large k to get all entities within radius
    int maxEntities = 1000;

    List<ID> members = spatialIndex.kNearestNeighbors(seedPoint, maxEntities, clusterRadius);

    // Further processing for density-based clustering
    return filterByDensity(members, seedPoint, clusterRadius);
}

```text

### 4. Collision Avoidance

```java

// Steering behavior for collision avoidance
public Vector3f calculateAvoidanceVector(ID entityId, float avoidanceRadius) {
    Point3f position = spatialIndex.getEntityPosition(entityId);

    // Find nearby entities to avoid
    List<ID> nearbyEntities = spatialIndex.kNearestNeighbors(position, 10, avoidanceRadius);

    Vector3f avoidance = new Vector3f(0, 0, 0);

    for (ID otherId : nearbyEntities) {
        if (!otherId.equals(entityId)) {
            Point3f otherPos = spatialIndex.getEntityPosition(otherId);

            // Calculate repulsion vector
            Vector3f toOther = new Vector3f();
            toOther.sub(position, otherPos);

            float distance = toOther.length();
            if (distance > 0) {
                toOther.normalize();
                float strength = 1.0f - (distance / avoidanceRadius);
                toOther.scale(strength);
                avoidance.add(toOther);
            }
        }
    }

    return avoidance;
}

```text

### 5. Spatial Sound System

```java

// Find nearest sound sources for 3D audio
public List<SoundSource> getNearestSounds(Point3f listenerPos) {
    float maxHearingDistance = 100.0f;
    int maxSimultaneousSounds = 32;

    List<ID> soundIds = spatialIndex.kNearestNeighbors(listenerPos, maxSimultaneousSounds, maxHearingDistance);

    List<SoundSource> sounds = new ArrayList<>();
    for (ID id : soundIds) {
        Content content = spatialIndex.getEntity(id);
        if (content instanceof SoundEmitter) {
            SoundEmitter emitter = (SoundEmitter) content;
            if (emitter.isPlaying()) {
                float distance = listenerPos.distance(spatialIndex.getEntityPosition(id));
                sounds.add(new SoundSource(emitter, distance));
            }
        }
    }

    return sounds;
}

```text

## Performance Optimization

### 1. Adaptive k Selection

```java

// Dynamically adjust k based on density
public List<ID> adaptiveKNN(Point3f queryPoint, float targetRadius) {
    // Start with small k
    int k = 10;
    float searchRadius = targetRadius;

    while (k < 1000) {
        List<ID> results = spatialIndex.kNearestNeighbors(queryPoint, k, searchRadius);

        // Check if we found enough neighbors
        if (results.size() < k) {
            // Found all entities within radius
            return results;
        }

        // Check if furthest neighbor is within target
        Point3f furthestPos = spatialIndex.getEntityPosition(results.get(results.size() - 1));
        float furthestDist = queryPoint.distance(furthestPos);

        if (furthestDist < targetRadius * 0.9f) {
            // Need more neighbors
            k *= 2;
        } else {
            // We have enough
            return results;
        }
    }

    return spatialIndex.kNearestNeighbors(queryPoint, k, searchRadius);
}

```text

### 2. Cached k-NN Queries

```java

public class CachedKNNQuery {
    private final SpatialIndex<ID, Content>  spatialIndex;
    private final Map<Point3f, CachedResult> cache          = new HashMap<>();
    private final float                      cacheTolerance = 0.1f;

    record CachedResult(List<ID> neighbors, long timestamp) {
    }

    public List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance) {
        // Check cache for nearby point
        for (Map.Entry<Point3f, CachedResult> entry : cache.entrySet()) {
            if (entry.getKey().distance(queryPoint) < cacheTolerance) {
                // Use cached result if recent
                if (System.currentTimeMillis() - entry.getValue().timestamp < 1000) {
                    return entry.getValue().neighbors;
                }
            }
        }

        // Perform new query
        List<ID> results = spatialIndex.kNearestNeighbors(queryPoint, k, maxDistance);
        cache.put(queryPoint, new CachedResult(results, System.currentTimeMillis()));

        // Limit cache size
        if (cache.size() > 1000) {
            clearOldestEntries();
        }

        return results;
    }
}

```text

### 3. Progressive k-NN Search

```java

// Find neighbors progressively for responsive UI
public class ProgressiveKNNSearch {

    public interface ProgressCallback {
        void onNeighborFound(ID entityId, float distance);

        boolean shouldContinue();
    }

    public void progressiveSearch(Point3f queryPoint, int targetK, float maxDistance, ProgressCallback callback) {

        int batchSize = 5;
        int found = 0;

        while (found < targetK && callback.shouldContinue()) {
            int k = Math.min(found + batchSize, targetK);

            List<ID> batch = spatialIndex.kNearestNeighbors(queryPoint, k, maxDistance);

            // Report new neighbors
            for (int i = found; i < batch.size(); i++) {
                ID entityId = batch.get(i);
                Point3f pos = spatialIndex.getEntityPosition(entityId);
                float distance = queryPoint.distance(pos);

                callback.onNeighborFound(entityId, distance);
            }

            found = batch.size();

            // Check if we found all available
            if (batch.size() < k) {
                break;
            }
        }
    }
}

```text

## Algorithm Details

The k-NN implementation uses a priority queue-based traversal:

1. **Priority Queue**: Nodes are visited in order of minimum distance to query point
2. **Pruning**: Subtrees beyond max distance are skipped
3. **Early Termination**: Search stops when k neighbors are found and no closer nodes exist
4. **Distance Calculation**: Uses Euclidean distance in 3D space

## Best Practices

1. **Choose Appropriate k**:
    - Small k (1-10) for targeted queries
    - Large k (50-100) for density analysis
    - Very large k (1000+) for complete radius searches

2. **Set Reasonable Max Distance**:
    - Improves performance by pruning search space
    - Use domain knowledge to set appropriate limits
    - Consider using adaptive distances based on density

3. **Handle Edge Cases**:
    - Empty results when no entities within range
    - Fewer than k results when insufficient entities
    - Duplicate distances may affect ordering

4. **Optimize for Use Case**:
    - Cache results for static scenes
    - Use progressive search for interactive applications
    - Batch queries when possible

5. **Consider Entity Bounds**:
    - k-NN uses entity positions (centers)
    - For bounded entities, distance is to center, not closest surface
    - May need custom distance calculations for large entities

## Example: Complete Proximity System

```java

public class ProximitySystem {
    private final SpatialIndex<ID, GameObject> spatialIndex;

    // Find all gameplay-relevant neighbors
    public ProximityInfo getProximityInfo(ID entityId) {
        Point3f position = spatialIndex.getEntityPosition(entityId);

        ProximityInfo info = new ProximityInfo();

        // Immediate threats (combat range)
        info.threats = findThreats(position, 5.0f, 5);

        // Nearby allies (support range)
        info.allies = findAllies(position, 20.0f, 10);

        // Interactable objects
        info.interactables = findInteractables(position, 3.0f, 5);

        // Environmental hazards
        info.hazards = findHazards(position, 10.0f, 10);

        return info;
    }

    private List<Enemy> findThreats(Point3f position, float range, int maxCount) {
        List<ID> nearbyIds = spatialIndex.kNearestNeighbors(position, maxCount, range);

        return nearbyIds.stream().map(id -> spatialIndex.getEntity(id)).filter(obj -> obj instanceof Enemy).map(
        obj -> (Enemy) obj).filter(enemy -> enemy.isHostile()).collect(Collectors.toList());
    }

    // Similar methods for allies, interactables, hazards...
}

```text

## Performance Characteristics

- **Time Complexity**: O(log n) average case for balanced trees
- **Space Complexity**: O(1) beyond the spatial index structure
- **Scalability**: Efficient for millions of entities
- **Cache Friendliness**: Traverses spatially coherent nodes
