# Ray Intersection API Documentation

**Last Updated**: 2025-12-08
**Status**: Current

## Overview

The Ray Intersection API provides efficient ray tracing capabilities for spatial queries in Octree, Tetree, and Prism
implementations. This API is designed for applications requiring line-of-sight queries, visibility checks, and ray-based
spatial interactions.

## Core Concepts

### Ray3D

The `Ray3D` class represents a ray in 3D space with:

- **Origin**: Starting point (must have positive coordinates)
- **Direction**: Normalized direction vector
- **Max Distance**: Optional maximum distance (default: unbounded)

```java
// Create a bounded ray
Ray3D ray = new Ray3D(origin, direction, maxDistance);

// Create an unbounded ray
Ray3D unboundedRay = new Ray3D(origin, direction);

// Create a ray from two points
Ray3D pointToPoint = Ray3D.fromPoints(start, end);

```

### RayIntersection

The `RayIntersection` record contains detailed information about a ray-entity intersection:

- **entityId**: The ID of the intersected entity
- **content**: The entity's content
- **distance**: Distance from ray origin to intersection
- **intersectionPoint**: 3D point of intersection
- **normal**: Surface normal at intersection
- **bounds**: Entity bounds (if available)

## API Methods

### 1. Find All Intersections

```java
List<RayIntersection<ID, Content>> rayIntersectAll(Ray3D ray)

```

Finds all entities that intersect with the given ray, sorted by distance from the ray origin.

**Example:**

```java
Ray3D ray = Ray3D.fromPointsUnbounded(new Point3f(0, 0, 0), new Point3f(100, 100, 100));
List<RayIntersection<LongEntityID, String>> intersections = spatialIndex.rayIntersectAll(ray);

for(
RayIntersection<LongEntityID, String> hit :intersections){
System.out.

println("Hit entity "+hit.entityId() +" at distance "+hit.

distance());
}

```

### 2. Find First Intersection

```java
Optional<RayIntersection<ID, Content>> rayIntersectFirst(Ray3D ray)

```

Finds the closest entity that intersects with the ray. This method is optimized for early termination.

**Example:**

```java
Optional<RayIntersection<LongEntityID, String>> firstHit = spatialIndex.rayIntersectFirst(ray);
if(firstHit.

isPresent()){
RayIntersection<LongEntityID, String> hit = firstHit.get();
    System.out.

println("First hit at distance: "+hit.distance());
}

```

### 3. Find Intersections Within Distance

```java
List<RayIntersection<ID, Content>> rayIntersectWithin(Ray3D ray, float maxDistance)

```

Finds all entities that intersect with the ray within a specified maximum distance.

**Example:**

```java
// Find all intersections within 100 units
List<RayIntersection<LongEntityID, String>> nearHits = spatialIndex.rayIntersectWithin(ray, 100.0f);

```

## Intersection Types

### Point Entity Intersection

For entities without bounds, ray-sphere intersection is performed with a small radius (0.1f):

- If ray starts inside the sphere: distance = 0.0
- Otherwise: distance to sphere surface

### Bounded Entity Intersection

For entities with bounds, ray-AABB (Axis-Aligned Bounding Box) intersection is performed:

- Returns distance to the nearest face of the bounding box
- Provides accurate intersection point and normal

## Performance Characteristics

### Algorithm Features

1. **Numerical Stability**: Uses a geometrically stable ray-sphere intersection algorithm that handles distant entities

   correctly

2. **Early Termination**: `rayIntersectFirst` stops traversal once the closest intersection is found
3. **Sorted Results**: All intersection lists are sorted by distance for consistent behavior
4. **Spatial Pruning**: Only traverses spatial nodes that intersect with the ray

### Complexity

- **Time Complexity**: O(log n) average case for spatial traversal + O(k log k) for sorting k intersections
- **Space Complexity**: O(k) where k is the number of intersections found

## Usage Patterns

### Line-of-Sight Queries

```java
public boolean hasLineOfSight(Point3f from, Point3f to, ID excludeEntity) {
    Ray3D ray = Ray3D.fromPoints(from, to);
    Optional<RayIntersection<ID, Content>> hit = spatialIndex.rayIntersectFirst(ray);

    return hit.isEmpty() || hit.get().entityId().equals(excludeEntity);
}

```

### Projectile Simulation

```java
public void simulateProjectile(Point3f origin, Vector3f velocity, float maxRange) {
    Ray3D trajectory = new Ray3D(origin, velocity, maxRange);

    Optional<RayIntersection<ID, Content>> impact = spatialIndex.rayIntersectFirst(trajectory);
    if (impact.isPresent()) {
        Point3f impactPoint = impact.get().intersectionPoint();
        Vector3f impactNormal = impact.get().normal();
        // Handle collision at impact point
    }
}

```

### Area Scanning

```java
public List<ID> scanCone(Point3f apex, Vector3f direction, float angle, float range) {
    List<ID> entitiesInCone = new ArrayList<>();

    // Create multiple rays in a cone pattern
    for (Vector3f rayDir : generateConeDirections(direction, angle)) {
        Ray3D scanRay = new Ray3D(apex, rayDir, range);
        List<RayIntersection<ID, Content>> hits = spatialIndex.rayIntersectAll(scanRay);

        for (RayIntersection<ID, Content> hit : hits) {
            entitiesInCone.add(hit.entityId());
        }
    }

    return entitiesInCone.stream().distinct().collect(Collectors.toList());
}

```

## Best Practices

1. **Ray Validation**: Ensure ray origin has positive coordinates (required by spatial indices)
2. **Direction Normalization**: Ray directions are automatically normalized, but pre-normalized vectors improve

   performance

3. **Bounded Rays**: Use bounded rays when possible to limit search space
4. **Entity Bounds**: Provide entity bounds for more accurate intersection tests
5. **Batch Queries**: When performing multiple ray queries, consider spatial locality for better cache performance

## Error Handling

- **IllegalArgumentException**: Thrown for rays with negative origin coordinates
- **Empty Results**: Methods return empty collections/optionals when no intersections found
- **Floating-Point Precision**: Small epsilon values used for numerical stability

## Thread Safety

Ray intersection queries are thread-safe for concurrent reads. The spatial index uses read-write locks to ensure
consistency during concurrent operations.

## Integration Example

```java
public class VisibilitySystem {
    private final SpatialIndex<LongEntityID, GameObject> spatialIndex;

    public Set<LongEntityID> getVisibleEntities(Point3f viewerPos, Vector3f viewDir, float fov, float viewDistance) {
        Set<LongEntityID> visible = new HashSet<>();

        // Generate rays for field of view
        int raysPerDimension = 10;
        for (int i = 0; i < raysPerDimension; i++) {
            for (int j = 0; j < raysPerDimension; j++) {
                Vector3f rayDir = calculateRayDirection(viewDir, fov, i, j, raysPerDimension);
                Ray3D viewRay = new Ray3D(viewerPos, rayDir, viewDistance);

                List<RayIntersection<LongEntityID, GameObject>> hits = spatialIndex.rayIntersectAll(viewRay);

                for (RayIntersection<LongEntityID, GameObject> hit : hits) {
                    visible.add(hit.entityId());
                }
            }
        }

        return visible;
    }
}

```
