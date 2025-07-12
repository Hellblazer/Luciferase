# Prism Spatial Index API Documentation

## Overview

The Prism spatial index provides anisotropic spatial decomposition combining fine horizontal granularity with coarse vertical granularity. This design is ideal for applications where horizontal precision is more important than vertical precision, such as terrain systems, urban planning, and layered environmental data.

The Prism implementation follows a hybrid approach, combining:
- **2D Triangular Decomposition**: For horizontal (x,y) space using 4-way subdivision
- **1D Linear Decomposition**: For vertical (z) space using 2-way subdivision
- **Composite Space-Filling Curve**: Preserving spatial locality across both dimensions

## Table of Contents

1. [Core Concepts](#core-concepts)
2. [Triangular Constraint](#triangular-constraint)
3. [Core Classes](#core-classes)
4. [Spatial Index Operations](#spatial-index-operations)
5. [Triangular Prism Geometry](#triangular-prism-geometry)
6. [Specialized Query Operations](#specialized-query-operations)
7. [Neighbor Finding](#neighbor-finding)
8. [Ray Intersection](#ray-intersection)
9. [Collision Detection](#collision-detection)
10. [Performance Considerations](#performance-considerations)
11. [Best Practices](#best-practices)
12. [Complete Examples](#complete-examples)

## Core Concepts

### Anisotropic Decomposition

The Prism structure provides different spatial granularities for different dimensions:

- **Horizontal (X,Y)**: Fine-grained triangular decomposition with 4-way subdivision
- **Vertical (Z)**: Coarse-grained linear decomposition with 2-way subdivision

This creates a **composite 8-way subdivision** per prism level:
- 4 triangle children × 2 line children = 8 total children

### Triangular Constraint

All coordinates must satisfy the constraint **x + y < worldSize** in normalized space. This confines entities to a triangular region in the horizontal plane.

### Space-Filling Curve

The composite space-filling curve combines triangular and linear indices:

```java
long compositeIndex = (lineIndex << triangleBits) | triangleIndex;
```

This preserves spatial locality while enabling efficient range operations.

## Triangular Constraint

### Coordinate System

The Prism uses a **triangular coordinate system** in the horizontal plane:

```java
// Valid coordinates (in normalized [0,1) space)
float worldSize = 1.0f;
float x = 0.3f, y = 0.4f;  // x + y = 0.7 < 1.0 ✓

// Invalid coordinates
float x = 0.7f, y = 0.5f;  // x + y = 1.2 > 1.0 ✗
```

### World Coordinate Validation

```java
public void validateCoordinates(float x, float y, float worldSize) {
    if (x < 0 || x >= worldSize) {
        throw new IllegalArgumentException("X coordinate out of bounds: " + x);
    }
    if (y < 0 || y >= worldSize) {
        throw new IllegalArgumentException("Y coordinate out of bounds: " + y);
    }
    if (x + y >= worldSize) {
        throw new IllegalArgumentException("Triangular constraint violated: x + y = " + (x + y));
    }
}
```

### Converting to Triangular Region

```java
// Method to clamp coordinates to triangular region
public Point3f clampToTriangularRegion(Point3f point, float worldSize) {
    float x = Math.max(0, Math.min(point.x, worldSize - 0.001f));
    float y = Math.max(0, Math.min(point.y, worldSize - 0.001f));
    float z = Math.max(0, Math.min(point.z, worldSize - 0.001f));
    
    // Ensure triangular constraint
    if (x + y >= worldSize) {
        float total = worldSize - 0.001f;
        float ratio = total / (x + y);
        x *= ratio;
        y *= ratio;
    }
    
    return new Point3f(x, y, z);
}
```

## Core Classes

### 1. Prism&lt;ID, Content&gt;

The main spatial index class extending `AbstractSpatialIndex`.

```java
public class Prism<ID extends EntityID, Content> 
    extends AbstractSpatialIndex<PrismKey, ID, Content>
```

#### Constructors

```java
// Default constructor (worldSize=1.0, maxLevel=21)
public Prism(EntityIDGenerator<ID> idGenerator)

// Full configuration
public Prism(EntityIDGenerator<ID> idGenerator, float worldSize, int maxLevel)
```

#### Key Methods

```java
// Calculate spatial index for position
protected PrismKey calculateSpatialIndex(Point3f position, byte level)

// Distance estimation for k-NN searches
protected float estimateNodeDistance(PrismKey prismKey, Point3f queryPoint)

// Ray intersection testing
protected boolean doesRayIntersectNode(PrismKey prismKey, Ray3D ray)

// Frustum culling support
protected boolean doesFrustumIntersectNode(PrismKey prismKey, Frustum3D frustum)
```

**Example:**
```java
// Create Prism spatial index
SequentialLongIDGenerator idGen = new SequentialLongIDGenerator();
Prism<LongEntityID, GameObject> prism = new Prism<>(idGen, 1000.0f, 16);

// Insert entity
Point3f position = new Point3f(100, 200, 50); // Note: 100+200 < 1000 ✓
GameObject player = new GameObject("Player");
LongEntityID playerId = prism.insert(position, (byte) 10, player);
```

### 2. PrismKey

Composite spatial key combining Triangle and Line components.

```java
public final class PrismKey implements SpatialKey<PrismKey>
```

#### Key Fields

```java
private final Triangle triangle;    // Horizontal (x,y) component
private final Line line;            // Vertical (z) component
```

#### Core Methods

```java
// Create from world coordinates
public static PrismKey fromWorldCoordinates(float worldX, float worldY, 
                                           float worldZ, int level)

// Get composite space-filling curve index
public long consecutiveIndex()

// Hierarchical navigation
public PrismKey parent()
public PrismKey child(int childIndex)  // 0-7

// Spatial properties
public boolean contains(float worldX, float worldY, float worldZ)
public float[] getCentroid()
public float getVolume()
public float[] getWorldBounds()
```

**Example:**
```java
// Create PrismKey from coordinates
PrismKey key = PrismKey.fromWorldCoordinates(0.3f, 0.4f, 0.5f, 10);

// Check containment
boolean contains = key.contains(0.31f, 0.41f, 0.51f);

// Get spatial properties
float[] centroid = key.getCentroid();     // [centerX, centerY, centerZ]
float volume = key.getVolume();
float[] bounds = key.getWorldBounds();    // [minX, minY, minZ, maxX, maxY, maxZ]

// Navigate hierarchy
PrismKey parent = key.parent();
PrismKey child0 = key.child(0);           // First child in Morton order
```

### 3. Triangle

2D triangular element for horizontal decomposition.

```java
public final class Triangle
```

#### Key Features

- **4-way subdivision** in horizontal plane
- **Type system** (0 or 1) for orientation handling
- **Space-filling curve** with spatial locality preservation
- **Coordinate system**: (x, y, n) where n is auxiliary coordinate

#### Core Methods

```java
// Create from world coordinates
public static Triangle fromWorldCoordinate(float worldX, float worldY, int level)

// Space-filling curve index
public long consecutiveIndex()

// Hierarchical operations
public Triangle parent()
public Triangle child(int childIndex)  // 0-3

// Spatial queries
public boolean contains(float worldX, float worldY)
public float[] getCentroidWorldCoordinates()
public float[] getWorldBounds()

// Neighbor finding
public Triangle[] neighbors()           // 3 edge neighbors
public Triangle neighbor(int edge)      // Specific edge neighbor
```

**Example:**
```java
// Create triangle at specific level
Triangle triangle = Triangle.fromWorldCoordinate(0.3f, 0.4f, 10);

// Check properties
boolean contains = triangle.contains(0.31f, 0.41f);
float[] centroid = triangle.getCentroidWorldCoordinates();
int level = triangle.getLevel();
int type = triangle.getType();

// Find neighbors
Triangle[] neighbors = triangle.neighbors();
Triangle rightNeighbor = triangle.neighbor(0);
```

### 4. Line

1D linear element for vertical decomposition.

```java
public final class Line
```

#### Key Features

- **2-way subdivision** along Z-axis
- **Binary space-filling curve** (simple coordinate-based)
- **Efficient neighbor finding** (up to 2 neighbors)

#### Core Methods

```java
// Create from world coordinate
public static Line fromWorldCoordinate(float worldZ, int level)

// Space-filling curve index (simply the Z coordinate)
public long consecutiveIndex()

// Hierarchical operations
public Line parent()
public Line child(int childIndex)  // 0-1

// Spatial queries
public boolean contains(float worldZ)
public float getCentroidWorldCoordinate()
public float[] getWorldBounds()

// Neighbor finding
public Line[] neighbors()           // Up to 2 neighbors
public Line neighbor(int direction) // -1 (below) or +1 (above)
```

**Example:**
```java
// Create line element
Line line = Line.fromWorldCoordinate(0.5f, 10);

// Check containment
boolean contains = line.contains(0.51f);

// Get properties
float center = line.getCentroidWorldCoordinate();
float[] bounds = line.getWorldBounds();  // [minZ, maxZ]

// Find neighbors
Line[] neighbors = line.neighbors();
Line below = line.neighbor(-1);
Line above = line.neighbor(1);
```

### 5. PrismGeometry

Geometric utilities for prism calculations.

```java
public final class PrismGeometry
```

#### Key Methods

```java
// Centroid calculation
public static float[] computeCentroid(PrismKey prismKey)

// Bounding box computation
public static float[] computeBoundingBox(PrismKey prismKey)

// Vertex generation (6 vertices for triangular prism)
public static List<Point3f> getVertices(PrismKey prismKey)

// Containment testing
public static boolean contains(PrismKey prismKey, Point3f point)

// Volume calculation
public static float computeVolume(PrismKey prismKey)
```

**Example:**
```java
PrismKey prism = PrismKey.fromWorldCoordinates(0.3f, 0.4f, 0.5f, 10);

// Get geometric properties
float[] centroid = PrismGeometry.computeCentroid(prism);
float[] bounds = PrismGeometry.computeBoundingBox(prism);
List<Point3f> vertices = PrismGeometry.getVertices(prism);
float volume = PrismGeometry.computeVolume(prism);

// Test containment
Point3f testPoint = new Point3f(0.31f, 0.41f, 0.51f);
boolean isInside = PrismGeometry.contains(prism, testPoint);
```

## Spatial Index Operations

### Standard AbstractSpatialIndex Operations

The Prism inherits all standard spatial index operations:

```java
// Entity insertion
ID insert(Point3f position, byte level, Content content)
void insert(ID entityId, Point3f position, byte level, Content content)

// Entity retrieval
Content getEntity(ID entityId)
List<Content> getEntities(List<ID> entityIds)
boolean containsEntity(ID entityId)

// Entity updates
void updateEntity(ID entityId, Point3f newPosition, byte level)

// Entity removal
boolean removeEntity(ID entityId)

// k-Nearest neighbors
List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance)

// Range queries
List<ID> entitiesInRegion(Spatial region)
Stream<SpatialNode<PrismKey, ID>> bounding(Spatial volume)

// Ray intersection
List<ID> rayIntersection(Ray3D ray, float maxDistance)

// Frustum culling
List<ID> frustumIntersection(Frustum3D frustum)
```

### Prism-Specific Optimizations

The Prism spatial index includes several optimizations for the anisotropic structure:

```java
// Cell size at specific level
public float getCellSizeAtLevelFloat(byte level)

// Efficient parent/child navigation
protected PrismKey getParentIndex(PrismKey childKey)
protected PrismKey getChildIndex(PrismKey parentKey, int childIdx)
protected List<PrismKey> getAllChildren(PrismKey parentKey)
```

## Triangular Prism Geometry

### Prism Structure

A triangular prism has:
- **6 vertices**: 3 on bottom triangle, 3 on top triangle
- **5 faces**: 2 triangular faces (top/bottom), 3 quadrilateral side faces
- **9 edges**: 3 vertical edges, 3 bottom edges, 3 top edges

### Vertex Ordering

```java
// Bottom triangle vertices (Z = minZ)
vertices[0] = (minX, minY, minZ)  // Bottom-left
vertices[1] = (maxX, minY, minZ)  // Bottom-right  
vertices[2] = (minX, maxY, minZ)  // Top-left

// Top triangle vertices (Z = maxZ)
vertices[3] = (minX, minY, maxZ)  // Bottom-left (top level)
vertices[4] = (maxX, minY, maxZ)  // Bottom-right (top level)
vertices[5] = (minX, maxY, maxZ)  // Top-left (top level)
```

### Face Definitions

```java
// Triangular faces
int FACE_TRIANGLE_BOTTOM = 3;  // Vertices 0,1,2
int FACE_TRIANGLE_TOP = 4;     // Vertices 3,4,5

// Quadrilateral side faces  
int FACE_QUAD_0 = 0;  // Vertices 1,2,4,5
int FACE_QUAD_1 = 1;  // Vertices 0,2,3,5
int FACE_QUAD_2 = 2;  // Vertices 0,1,3,4
```

## Specialized Query Operations

The Prism spatial index provides specialized queries optimized for the triangular structure:

### 1. Triangular Region Queries

```java
public Set<ID> findInTriangularRegion(Triangle searchTriangle, float minZ, float maxZ)
```

Finds all entities within a triangular region in the XY plane and a Z range.

**Example:**
```java
// Create search triangle
Triangle searchArea = Triangle.fromWorldCoordinate(0.3f, 0.4f, 8); // Larger area at level 8

// Find entities in triangular column
Set<ID> entities = prism.findInTriangularRegion(searchArea, 100.0f, 200.0f);

// Process results
for (ID entityId : entities) {
    GameObject obj = prism.getEntity(entityId);
    obj.highlight();
}
```

### 2. Vertical Layer Queries

```java
public Set<ID> findInVerticalLayer(float minZ, float maxZ)
```

Efficiently finds all entities within a horizontal layer, leveraging the coarse vertical decomposition.

**Example:**
```java
// Find all entities on ground level
Set<ID> groundEntities = prism.findInVerticalLayer(0.0f, 10.0f);

// Find entities in specific building floor
float floorHeight = 3.0f;
int floor = 5;
float minZ = floor * floorHeight;
float maxZ = (floor + 1) * floorHeight;
Set<ID> floorEntities = prism.findInVerticalLayer(minZ, maxZ);
```

### 3. Triangular Prism Volume Queries

```java
public Set<ID> findInTriangularPrism(Triangle searchTriangle, float minZ, float maxZ)
```

Combines triangular region and vertical layer queries for precise volume searches.

**Example:**
```java
// Define search volume
Triangle searchTriangle = Triangle.fromWorldCoordinate(0.2f, 0.3f, 10);
float minZ = 50.0f;
float maxZ = 100.0f;

// Find entities in triangular prism volume
Set<ID> entitiesInVolume = prism.findInTriangularPrism(searchTriangle, minZ, maxZ);

// Apply area effect
for (ID entityId : entitiesInVolume) {
    GameObject obj = prism.getEntity(entityId);
    obj.applyAreaEffect("healing", 25);
}
```

## Neighbor Finding

The `PrismNeighborFinder` class provides comprehensive neighbor finding algorithms:

### Face Neighbors

```java
// Find neighbor across a specific face (0-4)
public static PrismKey findFaceNeighbor(PrismKey prism, int face)

// Find all face neighbors
public static List<PrismKey> findAllFaceNeighbors(PrismKey prism)
```

**Example:**
```java
PrismKey prism = PrismKey.fromWorldCoordinates(0.3f, 0.4f, 0.5f, 10);

// Find neighbors across each face
PrismKey rightNeighbor = PrismNeighborFinder.findFaceNeighbor(prism, 0);  // Quad face 0
PrismKey belowNeighbor = PrismNeighborFinder.findFaceNeighbor(prism, 3);  // Bottom triangle
PrismKey aboveNeighbor = PrismNeighborFinder.findFaceNeighbor(prism, 4);  // Top triangle

// Get all face neighbors at once
List<PrismKey> faceNeighbors = PrismNeighborFinder.findAllFaceNeighbors(prism);
```

### Edge and Vertex Neighbors

```java
// Find neighbors sharing an edge
public static Set<PrismKey> findEdgeNeighbors(PrismKey prism)

// Find neighbors sharing a vertex
public static Set<PrismKey> findVertexNeighbors(PrismKey prism)
```

**Example:**
```java
// Find edge neighbors (share a common edge)
Set<PrismKey> edgeNeighbors = PrismNeighborFinder.findEdgeNeighbors(prism);

// Find vertex neighbors (share a common vertex)  
Set<PrismKey> vertexNeighbors = PrismNeighborFinder.findVertexNeighbors(prism);

// Use for smooth interpolation
for (PrismKey neighbor : edgeNeighbors) {
    float neighborValue = getFieldValue(neighbor);
    interpolatedValue += neighborValue * edgeWeight;
}
```

### Cross-Level Neighbors

```java
// Find neighbors at different refinement levels
public static List<PrismKey> findCrossLevelNeighbors(PrismKey prism, int maxLevelDifference)
```

**Example:**
```java
// Find neighbors within 2 levels of current prism
List<PrismKey> crossLevelNeighbors = PrismNeighborFinder.findCrossLevelNeighbors(prism, 2);

// Use for adaptive mesh refinement
for (PrismKey neighbor : crossLevelNeighbors) {
    int levelDiff = Math.abs(neighbor.getLevel() - prism.getLevel());
    if (levelDiff > 1) {
        // Consider refinement or coarsening
        scheduleAdaptiveRefinement(neighbor);
    }
}
```

## Ray Intersection

The `PrismRayIntersector` provides accurate ray-prism intersection algorithms:

### Basic Ray Intersection

```java
public static IntersectionResult intersectRayPrism(Ray3D ray, PrismKey prism)
```

**IntersectionResult fields:**
- `boolean hit` - Whether intersection occurred
- `float tNear, tFar` - Ray parameters for entry/exit points  
- `Point3f nearPoint, farPoint` - World coordinates of intersection points
- `int nearFace, farFace` - Which faces were hit

**Example:**
```java
// Cast ray from camera
Point3f rayOrigin = camera.getPosition();
Vector3f rayDirection = camera.getForwardVector();
Ray3D ray = new Ray3D(rayOrigin, rayDirection);

// Test intersection with prism
PrismKey prism = PrismKey.fromWorldCoordinates(0.3f, 0.4f, 0.5f, 10);
PrismRayIntersector.IntersectionResult result = PrismRayIntersector.intersectRayPrism(ray, prism);

if (result.hit) {
    System.out.println("Ray hit at t=" + result.tNear + ", face=" + result.nearFace);
    System.out.println("Entry point: " + result.nearPoint);
    System.out.println("Exit point: " + result.farPoint);
}
```

### Fast AABB Culling

```java
public static boolean intersectRayAABB(Ray3D ray, PrismKey prism)
```

**Example:**
```java
// Quick culling test before detailed intersection
if (PrismRayIntersector.intersectRayAABB(ray, prism)) {
    // Do detailed intersection test
    PrismRayIntersector.IntersectionResult result = 
        PrismRayIntersector.intersectRayPrism(ray, prism);
}
```

### Entry/Exit Point Finding

```java
public static float[] findEntryExitPoints(Ray3D ray, PrismKey prism)
```

**Example:**
```java
// For volume rendering or transparency
float[] entryExit = PrismRayIntersector.findEntryExitPoints(ray, prism);
if (entryExit != null) {
    float tEntry = entryExit[0];
    float tExit = entryExit[1];
    
    // Sample volume between entry and exit
    sampleVolume(ray, tEntry, tExit);
}
```

## Collision Detection

The `PrismCollisionDetector` provides comprehensive collision detection using Separating Axis Theorem (SAT):

### Prism-Prism Collision

```java
public static CollisionResult testPrismPrismCollision(PrismKey prism1, PrismKey prism2)
```

**CollisionResult fields:**
- `boolean collides` - Whether collision occurred
- `float penetrationDepth` - How deeply objects interpenetrate
- `Vector3f separationAxis` - Direction to separate objects
- `Point3f contactPoint` - World coordinate of contact

**Example:**
```java
// Test collision between two prisms
PrismKey prism1 = PrismKey.fromWorldCoordinates(0.3f, 0.4f, 0.5f, 10);
PrismKey prism2 = PrismKey.fromWorldCoordinates(0.31f, 0.41f, 0.51f, 10);

PrismCollisionDetector.CollisionResult result = 
    PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);

if (result.collides) {
    // Resolve collision
    Vector3f separation = new Vector3f(result.separationAxis);
    separation.scale(result.penetrationDepth * 0.5f);
    
    // Move prisms apart
    separatePrisms(prism1, prism2, separation);
}
```

### Prism-Sphere Collision

```java
public static CollisionResult testPrismSphereCollision(PrismKey prism, 
                                                      Point3f sphereCenter, 
                                                      float sphereRadius)
```

**Example:**
```java
// Test collision with sphere (player, projectile, etc.)
Point3f playerPos = new Point3f(100, 150, 50);
float playerRadius = 5.0f;

PrismCollisionDetector.CollisionResult result = 
    PrismCollisionDetector.testPrismSphereCollision(building, playerPos, playerRadius);

if (result.collides) {
    // Player collided with building
    handleBuildingCollision(result);
}
```

### Batch Collision Detection

```java
public static Set<PrismKey> findCollidingPrisms(PrismKey prism, 
                                               Collection<PrismKey> candidates)
```

**Example:**
```java
// Find all collisions for moving object
PrismKey movingObject = getMovingObjectPrism();
Collection<PrismKey> staticObjects = getStaticObjectPrisms();

Set<PrismKey> collisions = PrismCollisionDetector.findCollidingPrisms(
    movingObject, staticObjects);

// Handle each collision
for (PrismKey collidingPrism : collisions) {
    handleStaticCollision(movingObject, collidingPrism);
}
```

## Performance Considerations

### Memory Usage

The Prism has different memory characteristics compared to Octree/Tetree:

- **Space-Filling Curve**: Composite index requires more computation than simple Morton codes
- **Dual Components**: Each PrismKey stores both Triangle and Line components
- **Anisotropic Structure**: Different subdivision rates may lead to uneven memory distribution

### Performance Metrics

Based on the anisotropic structure:

- **Insertion**: Moderate performance due to composite key calculation
- **k-NN Search**: Good performance due to triangular locality
- **Range Queries**: Excellent for horizontal layers, good for triangular regions
- **Memory Efficiency**: Dependent on entity distribution pattern

### Optimization Strategies

```java
// 1. Batch operations for better cache locality
List<ID> entityIds = Arrays.asList(id1, id2, id3, id4);
List<Point3f> positions = Arrays.asList(pos1, pos2, pos3, pos4);
List<Content> contents = Arrays.asList(obj1, obj2, obj3, obj4);
prism.insertBatch(entityIds, positions, level, contents);

// 2. Use appropriate levels for different entity types
byte groundLevel = 8;    // Coarse for large terrain features
byte objectLevel = 12;   // Fine for game objects
byte detailLevel = 16;   // Very fine for small details

// 3. Leverage triangular region queries for area effects
Triangle effectArea = Triangle.fromWorldCoordinate(0.3f, 0.4f, 8);
Set<ID> affectedEntities = prism.findInTriangularRegion(effectArea, minZ, maxZ);
```

## Best Practices

### 1. Coordinate System Design

```java
// Always validate triangular constraint
public class TriangularCoordinateValidator {
    public static Point3f validateAndClamp(Point3f point, float worldSize) {
        // Clamp to positive bounds
        float x = Math.max(0, Math.min(point.x, worldSize - 0.001f));
        float y = Math.max(0, Math.min(point.y, worldSize - 0.001f));
        float z = Math.max(0, Math.min(point.z, worldSize - 0.001f));
        
        // Enforce triangular constraint
        if (x + y >= worldSize) {
            float scale = (worldSize - 0.001f) / (x + y);
            x *= scale;
            y *= scale;
        }
        
        return new Point3f(x, y, z);
    }
}
```

### 2. Level Selection Strategy

```java
// Choose levels based on application requirements
public class PrismLevelStrategy {
    public static byte selectLevel(EntityType type, float entitySize) {
        return switch (type) {
            case TERRAIN_FEATURE -> (byte) 6;   // Large features, coarse level
            case BUILDING -> (byte) 8;          // Medium structures
            case VEHICLE -> (byte) 10;          // Moving objects
            case CHARACTER -> (byte) 12;        // Fine positioning
            case PROJECTILE -> (byte) 14;       // Very fine tracking
            case PARTICLE -> (byte) 16;         // Finest detail
        };
    }
}
```

### 3. Efficient Query Patterns

```java
// Use triangular region queries for natural area effects
public class AreaEffectSystem {
    public void applyTriangularEffect(Point3f center, float radius, float height) {
        // Create search triangle
        Triangle searchArea = Triangle.fromWorldCoordinate(
            center.x, center.y, calculateLevelForRadius(radius));
        
        // Apply effect to triangular column
        Set<ID> entities = prism.findInTriangularRegion(
            searchArea, center.z, center.z + height);
        
        entities.forEach(this::applyEffect);
    }
    
    private byte calculateLevelForRadius(float radius) {
        // Choose level where triangle size ≈ desired radius
        return (byte) Math.max(0, Math.min(21, 
            Math.round(Math.log(worldSize / radius) / Math.log(2))));
    }
}
```

### 4. Memory Management

```java
// Monitor memory usage and entity distribution
public class PrismMemoryMonitor {
    public void analyzeDistribution(Prism<ID, Content> prism) {
        Map<Integer, Integer> entitiesPerLevel = new HashMap<>();
        Map<Integer, Integer> nodesPerLevel = new HashMap<>();
        
        prism.nodes().forEach(node -> {
            int level = node.sfcIndex().getLevel();
            int entityCount = node.entityIds().size();
            
            entitiesPerLevel.merge(level, entityCount, Integer::sum);
            nodesPerLevel.merge(level, 1, Integer::sum);
        });
        
        // Log distribution analysis
        for (int level = 0; level <= 21; level++) {
            int entities = entitiesPerLevel.getOrDefault(level, 0);
            int nodes = nodesPerLevel.getOrDefault(level, 0);
            if (nodes > 0) {
                log.info("Level {}: {} nodes, {} entities, avg {:.1f} entities/node",
                        level, nodes, entities, (double) entities / nodes);
            }
        }
    }
}
```

## Complete Examples

### 1. Terrain System

```java
public class TriangularTerrainSystem {
    private final Prism<LongEntityID, TerrainChunk> terrainIndex;
    private final float worldSize = 2048.0f; // 2km × 2km triangular world
    
    public TriangularTerrainSystem() {
        SequentialLongIDGenerator idGen = new SequentialLongIDGenerator();
        this.terrainIndex = new Prism<>(idGen, worldSize, 16);
    }
    
    public void generateTerrain() {
        // Generate terrain in triangular pattern
        float chunkSize = 64.0f; // 64-meter chunks
        byte level = calculateLevelForSize(chunkSize);
        
        for (float x = 0; x < worldSize; x += chunkSize) {
            for (float y = 0; y < worldSize - x; y += chunkSize) {
                // Only generate in valid triangular region
                if (x + y < worldSize) {
                    Point3f chunkPos = new Point3f(x, y, 0);
                    TerrainChunk chunk = generateTerrainChunk(x, y);
                    terrainIndex.insert(chunkPos, level, chunk);
                }
            }
        }
    }
    
    public List<TerrainChunk> getVisibleTerrain(Frustum3D frustum) {
        return terrainIndex.frustumIntersection(frustum).stream()
            .map(terrainIndex::getEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    public float getHeightAt(float x, float y) {
        if (x + y >= worldSize) return 0; // Outside triangular region
        
        Point3f queryPoint = new Point3f(x, y, 100); // High Z for lookup
        List<LongEntityID> chunks = terrainIndex.lookup(queryPoint, (byte) 8);
        
        if (!chunks.isEmpty()) {
            TerrainChunk chunk = terrainIndex.getEntity(chunks.get(0));
            return chunk.getHeightAt(x, y);
        }
        return 0;
    }
}
```

### 2. Urban Planning System

```java
public class UrbanPlanningSystem {
    private final Prism<UUIDEntityID, Building> buildingIndex;
    private final float citySize = 10000.0f; // 10km triangular city
    
    public void placeBuildingsInDistrict(DistrictPlan district) {
        Triangle districtArea = district.getBoundaryTriangle();
        float minHeight = district.getMinHeight();
        float maxHeight = district.getMaxHeight();
        
        // Check for existing buildings in district
        Set<UUIDEntityID> existing = buildingIndex.findInTriangularRegion(
            districtArea, minHeight, maxHeight);
        
        if (!existing.isEmpty()) {
            log.warn("District already has {} buildings", existing.size());
            return;
        }
        
        // Place new buildings
        for (BuildingPlan plan : district.getBuildingPlans()) {
            Point3f position = plan.getPosition();
            
            // Validate position is in triangular region
            if (position.x + position.y >= citySize) {
                log.warn("Building position outside city bounds: {}", position);
                continue;
            }
            
            Building building = new Building(plan);
            EntityBounds bounds = plan.getBounds();
            
            UUIDEntityID buildingId = idGenerator.generateID();
            buildingIndex.insert(buildingId, position, (byte) 10, building, bounds);
        }
    }
    
    public List<Building> findBuildingsInView(Point3f viewpoint, Vector3f direction, 
                                            float viewDistance, float viewAngle) {
        // Create frustum for view
        Frustum3D viewFrustum = createViewFrustum(viewpoint, direction, 
                                                 viewDistance, viewAngle);
        
        return buildingIndex.frustumIntersection(viewFrustum).stream()
            .map(buildingIndex::getEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}
```

### 3. Atmospheric Simulation

```java
public class AtmosphericLayerSystem {
    private final Prism<LongEntityID, AtmosphericData> atmosphereIndex;
    private final float simulationSize = 1000.0f; // 1km triangular area
    private final int numLayers = 20; // 20 atmospheric layers
    
    public void initializeAtmosphere() {
        float layerThickness = simulationSize / numLayers;
        
        for (int layer = 0; layer < numLayers; layer++) {
            float minZ = layer * layerThickness;
            float maxZ = (layer + 1) * layerThickness;
            
            // Create atmospheric data for this layer
            AtmosphericData layerData = new AtmosphericData(layer, minZ, maxZ);
            Point3f layerCenter = new Point3f(simulationSize / 3, simulationSize / 3, 
                                             (minZ + maxZ) / 2);
            
            atmosphereIndex.insert(layerCenter, (byte) 6, layerData);
        }
    }
    
    public void simulateWeatherEffect(Point3f center, float radius, WeatherType weather) {
        // Find affected atmospheric layers
        Set<LongEntityID> affectedLayers = atmosphereIndex.findInVerticalLayer(
            center.z - radius, center.z + radius);
        
        for (LongEntityID layerId : affectedLayers) {
            AtmosphericData layer = atmosphereIndex.getEntity(layerId);
            Point3f layerPos = atmosphereIndex.getEntityPosition(layerId);
            
            float distance = center.distance(layerPos);
            if (distance <= radius) {
                float intensity = 1.0f - (distance / radius);
                layer.applyWeatherEffect(weather, intensity);
            }
        }
    }
    
    public AtmosphericData getAtmosphereAt(Point3f position) {
        List<LongEntityID> layers = atmosphereIndex.lookup(position, (byte) 6);
        if (!layers.isEmpty()) {
            return atmosphereIndex.getEntity(layers.get(0));
        }
        return AtmosphericData.getDefault();
    }
}
```

### 4. Resource Distribution System

```java
public class ResourceDistributionSystem {
    private final Prism<LongEntityID, ResourceDeposit> resourceIndex;
    private final float regionSize = 5000.0f; // 5km triangular mining region
    
    public void distributeResources(ResourceType type, int count) {
        Random random = new Random();
        byte level = 12; // Fine granularity for resource placement
        
        for (int i = 0; i < count; i++) {
            // Generate random position in triangular region
            float x, y;
            do {
                x = random.nextFloat() * regionSize;
                y = random.nextFloat() * regionSize;
            } while (x + y >= regionSize); // Ensure triangular constraint
            
            float z = random.nextFloat() * 100; // Underground depth
            
            Point3f position = new Point3f(x, y, z);
            ResourceDeposit deposit = new ResourceDeposit(type, 
                calculateResourceAmount(type, position));
            
            resourceIndex.insert(position, level, deposit);
        }
    }
    
    public List<ResourceDeposit> findResourcesNear(Point3f explorerPos, float searchRadius) {
        return resourceIndex.kNearestNeighbors(explorerPos, 10, searchRadius).stream()
            .map(resourceIndex::getEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    public Map<ResourceType, Integer> analyzeRegion(Triangle region, float minDepth, float maxDepth) {
        Set<LongEntityID> deposits = resourceIndex.findInTriangularRegion(region, minDepth, maxDepth);
        
        return deposits.stream()
            .map(resourceIndex::getEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(
                ResourceDeposit::getType,
                Collectors.summingInt(ResourceDeposit::getAmount)
            ));
    }
}
```

These examples demonstrate the Prism spatial index's strengths in handling anisotropic spatial data with fine horizontal precision and coarse vertical granularity, making it ideal for layered environmental systems, urban planning, and terrain-based applications.