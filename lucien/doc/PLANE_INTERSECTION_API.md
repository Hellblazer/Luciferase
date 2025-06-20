# Plane Intersection API Documentation

The Plane Intersection API provides methods for finding entities that intersect with or are positioned relative to arbitrary 3D planes. This is useful for spatial partitioning, clipping operations, and visibility determination.

## Overview

Plane intersection queries allow you to:
- Find all entities intersecting a plane
- Find entities on either side of a plane
- Find entities within a certain distance of a plane
- Use tolerance values for robust intersection tests

## API Methods

### Basic Plane Intersection

```java
List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane)
```

Finds all entities that intersect with the given plane.

**Parameters:**
- `plane` - The 3D plane to test against

**Returns:**
- List of plane intersections sorted by distance from plane origin

**Example:**
```java
// Create a horizontal plane at y=100
Plane3D plane = new Plane3D(new Vector3f(0, 1, 0), new Point3f(0, 100, 0));

// Find all entities intersecting this plane
List<PlaneIntersection<ID, Content>> intersections = spatialIndex.planeIntersectAll(plane);

for (PlaneIntersection<ID, Content> intersection : intersections) {
    System.out.println("Entity " + intersection.entityId() + 
                      " intersects at distance " + intersection.signedDistance());
}
```

### Plane Intersection with Tolerance

```java
List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane, float tolerance)
```

Finds all entities within a tolerance distance of the plane. This is useful for handling floating-point precision issues.

**Parameters:**
- `plane` - The 3D plane to test against
- `tolerance` - Distance tolerance for intersection tests

**Returns:**
- List of plane intersections within tolerance

**Example:**
```java
// Find entities within 0.1 units of the plane
float tolerance = 0.1f;
List<PlaneIntersection<ID, Content>> nearPlane = 
    spatialIndex.planeIntersectAll(plane, tolerance);
```

### Directional Queries

```java
List<PlaneIntersection<ID, Content>> planeIntersectPositiveSide(Plane3D plane)
List<PlaneIntersection<ID, Content>> planeIntersectNegativeSide(Plane3D plane)
```

Find entities on a specific side of the plane. The positive side is in the direction of the plane normal.

**Example:**
```java
// Create a vertical plane facing right (+X direction)
Plane3D plane = new Plane3D(new Vector3f(1, 0, 0), new Point3f(50, 0, 0));

// Find entities to the right of the plane
List<PlaneIntersection<ID, Content>> rightSide = 
    spatialIndex.planeIntersectPositiveSide(plane);

// Find entities to the left of the plane
List<PlaneIntersection<ID, Content>> leftSide = 
    spatialIndex.planeIntersectNegativeSide(plane);
```

### Distance-Limited Queries

```java
List<PlaneIntersection<ID, Content>> planeIntersectWithinDistance(Plane3D plane, float maxDistance)
```

Find entities within a maximum distance from the plane (on both sides).

**Parameters:**
- `plane` - The 3D plane to test against
- `maxDistance` - Maximum distance from plane

**Example:**
```java
// Find entities within 10 units of the plane
float maxDistance = 10.0f;
List<PlaneIntersection<ID, Content>> nearby = 
    spatialIndex.planeIntersectWithinDistance(plane, maxDistance);
```

## PlaneIntersection Result

Each intersection result contains:

```java
record PlaneIntersection<ID, Content>(
    ID entityId,
    Content content,
    float signedDistance,
    PlaneRelation relation,
    EntityBounds bounds
)
```

Where:
- `entityId` - The ID of the intersecting entity
- `content` - The entity's content
- `signedDistance` - Signed distance from plane (negative = negative side)
- `relation` - Relationship to plane (POSITIVE_SIDE, NEGATIVE_SIDE, INTERSECTING)
- `bounds` - Entity bounds if available

## Use Cases

### 1. Spatial Partitioning
```java
// Partition space with axis-aligned plane
Plane3D partitionPlane = new Plane3D(new Vector3f(1, 0, 0), new Point3f(0, 0, 0));

List<PlaneIntersection<ID, Content>> leftPartition = 
    spatialIndex.planeIntersectNegativeSide(partitionPlane);
List<PlaneIntersection<ID, Content>> rightPartition = 
    spatialIndex.planeIntersectPositiveSide(partitionPlane);
```

### 2. Clipping Operations
```java
// Clip to viewing volume
Plane3D nearPlane = new Plane3D(new Vector3f(0, 0, -1), new Point3f(0, 0, -1));
Plane3D farPlane = new Plane3D(new Vector3f(0, 0, 1), new Point3f(0, 0, -100));

// Find entities between near and far planes
List<PlaneIntersection<ID, Content>> visible = 
    spatialIndex.planeIntersectPositiveSide(nearPlane).stream()
        .filter(e -> spatialIndex.planeIntersectNegativeSide(farPlane)
                                 .contains(e))
        .collect(Collectors.toList());
```

### 3. Mirror Operations
```java
// Find entities to mirror across YZ plane
Plane3D mirrorPlane = new Plane3D(new Vector3f(1, 0, 0), new Point3f(0, 0, 0));

List<PlaneIntersection<ID, Content>> toMirror = 
    spatialIndex.planeIntersectPositiveSide(mirrorPlane);

// Mirror positions
for (PlaneIntersection<ID, Content> entity : toMirror) {
    Point3f pos = spatialIndex.getEntityPosition(entity.entityId());
    Point3f mirrored = new Point3f(-pos.x, pos.y, pos.z);
    // Create mirrored entity...
}
```

### 4. Slice Visualization
```java
// Extract a slice through the data
float sliceY = 50.0f;
float thickness = 1.0f;

Plane3D slicePlane = new Plane3D(new Vector3f(0, 1, 0), new Point3f(0, sliceY, 0));

List<PlaneIntersection<ID, Content>> slice = 
    spatialIndex.planeIntersectWithinDistance(slicePlane, thickness / 2);
```

## Performance Considerations

1. **Tree Traversal**: Plane queries traverse only nodes that intersect the plane
2. **Distance Sorting**: Results are sorted by signed distance from plane
3. **Early Termination**: Traversal stops when nodes are beyond query bounds
4. **Optimization**: Use tolerance values to avoid numerical precision issues

## Implementation Details

### Octree
- Uses efficient plane-AABB intersection tests
- Traverses nodes in distance order from plane

### Tetree
- Tests plane against tetrahedral nodes
- Uses vertex distance calculations for intersection

## Best Practices

1. **Normalize Plane Normals**: Ensure plane normals are unit vectors
2. **Use Appropriate Tolerance**: Account for floating-point precision
3. **Combine Queries**: Use multiple planes for complex regions
4. **Cache Planes**: Reuse plane objects for repeated queries
5. **Consider Bounds**: Entity bounds affect intersection tests

## Example: Portal Culling

```java
public List<ID> getVisibleThroughPortal(Plane3D portalPlane, 
                                        float portalRadius,
                                        Point3f viewPoint) {
    // Find entities on the far side of portal
    List<PlaneIntersection<ID, Content>> farSide = 
        spatialIndex.planeIntersectPositiveSide(portalPlane);
    
    // Filter by distance from portal center
    Point3f portalCenter = portalPlane.getPointOnPlane();
    
    return farSide.stream()
        .filter(e -> {
            Point3f pos = spatialIndex.getEntityPosition(e.entityId());
            float dist = pos.distance(portalCenter);
            return dist <= portalRadius;
        })
        .map(PlaneIntersection::entityId)
        .collect(Collectors.toList());
}
```