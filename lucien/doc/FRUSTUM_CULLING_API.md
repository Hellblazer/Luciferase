# Frustum Culling API Documentation

The Frustum Culling API provides efficient methods for determining which entities are visible within a 3D viewing
frustum. This is essential for rendering optimization in 3D graphics applications.

## Overview

Frustum culling allows you to:

- Find all entities visible within a camera's view frustum
- Distinguish between entities fully inside vs intersecting the frustum
- Sort results by distance from camera for rendering order
- Limit results to a maximum distance for LOD systems

## API Methods

### Find All Visible Entities

```java
List<FrustumIntersection<ID, Content>> frustumCullVisible(Frustum3D frustum, Point3f cameraPosition)
```

Finds all entities that are at least partially visible within the frustum.

**Parameters:**

- `frustum` - The 3D viewing frustum
- `cameraPosition` - Camera position for distance sorting

**Returns:**

- List of visible entities sorted by distance from camera

**Example:**

```java
// Create a perspective frustum
Frustum3D frustum = Frustum3D.createPerspective(60.0f,      // Field of view (degrees)
                                                1.333f,     // Aspect ratio
                                                0.1f,       // Near plane distance
                                                1000.0f     // Far plane distance
                                               );

Point3f cameraPos = new Point3f(0, 100, 0);

// Find all visible entities
List<FrustumIntersection<ID, Content>> visible = spatialIndex.frustumCullVisible(frustum, cameraPos);

// Render in front-to-back order
for(
FrustumIntersection<ID, Content> entity :visible){

render(entity.entityId());
}
```

### Find Entities Completely Inside

```java
List<FrustumIntersection<ID, Content>> frustumCullInside(Frustum3D frustum, Point3f cameraPosition)
```

Finds only entities that are completely contained within the frustum. Useful for optimizations where partial visibility
isn't needed.

**Example:**

```java
// Find entities that don't need clipping
List<FrustumIntersection<ID, Content>> fullyVisible = spatialIndex.frustumCullInside(frustum, cameraPos);

// These can be rendered without clipping tests
for(
FrustumIntersection<ID, Content> entity :fullyVisible){

renderWithoutClipping(entity.entityId());
}
```

### Find Intersecting Entities

```java
List<FrustumIntersection<ID, Content>> frustumCullIntersecting(Frustum3D frustum, Point3f cameraPosition)
```

Finds entities that intersect the frustum boundary (partially visible). These entities may need special handling like
clipping.

**Example:**

```java
// Find entities that need clipping
List<FrustumIntersection<ID, Content>> needsClipping = spatialIndex.frustumCullIntersecting(frustum, cameraPos);

for(
FrustumIntersection<ID, Content> entity :needsClipping){

renderWithClipping(entity.entityId());
}
```

### Distance-Limited Culling

```java
List<FrustumIntersection<ID, Content>> frustumCullWithinDistance(Frustum3D frustum, Point3f cameraPosition,
                                                                 float maxDistance)
```

Finds visible entities within a maximum distance. Useful for LOD (Level of Detail) systems.

**Parameters:**

- `frustum` - The 3D viewing frustum
- `cameraPosition` - Camera position
- `maxDistance` - Maximum distance from camera

**Example:**

```java
// Different LOD ranges
float detailDistance = 50.0f;
float mediumDistance = 200.0f;
float farDistance = 1000.0f;

// Get entities by LOD
List<FrustumIntersection<ID, Content>> detailed = spatialIndex.frustumCullWithinDistance(frustum, cameraPos,
                                                                                         detailDistance);

List<FrustumIntersection<ID, Content>> medium = spatialIndex.frustumCullWithinDistance(frustum, cameraPos,
                                                                                       mediumDistance);

List<FrustumIntersection<ID, Content>> far = spatialIndex.frustumCullWithinDistance(frustum, cameraPos, farDistance);

// Render with appropriate detail level
renderHighDetail(detailed);

renderMediumDetail(medium);

renderLowDetail(far);
```

## FrustumIntersection Result

Each culling result contains:

```java
record FrustumIntersection<ID, Content>(
    ID entityId,
    Content content,
    float distanceToCamera,
    FrustumRelation relation,
    EntityBounds bounds
)
```

Where:

- `entityId` - The ID of the visible entity
- `content` - The entity's content
- `distanceToCamera` - Distance from camera (for sorting)
- `relation` - Relationship to frustum (INSIDE, INTERSECTING, OUTSIDE)
- `bounds` - Entity bounds if available

## Creating Frustums

### Perspective Frustum

```java
// From camera parameters
Frustum3D frustum = Frustum3D.createPerspective(
    fovY,           // Vertical field of view in degrees
    aspectRatio,    // Width/height ratio
    nearPlane,      // Near clipping plane distance
    farPlane        // Far clipping plane distance
);

// From projection matrix
Matrix4f projMatrix = createProjectionMatrix();
Frustum3D frustum = Frustum3D.fromProjectionMatrix(projMatrix);
```

### Orthographic Frustum

```java
Frustum3D frustum = Frustum3D.createOrthographic(
    left, right,    // Horizontal bounds
    bottom, top,    // Vertical bounds
    near, far       // Depth bounds
);
```

### Custom Frustum

```java
// Define frustum with 6 planes
Plane3D[] planes = new Plane3D[] {
    nearPlane, farPlane,
    leftPlane, rightPlane,
    topPlane, bottomPlane
};
Frustum3D frustum = new Frustum3D(planes);
```

## Use Cases

### 1. Basic Rendering Pipeline

```java
public void render(Camera camera) {
    Frustum3D frustum = camera.getFrustum();
    Point3f cameraPos = camera.getPosition();

    // Cull invisible entities
    List<FrustumIntersection<ID, Content>> visible = spatialIndex.frustumCullVisible(frustum, cameraPos);

    // Sort for optimal rendering (already sorted by distance)
    for (FrustumIntersection<ID, Content> entity : visible) {
        // Render front-to-back for opaque objects
        renderEntity(entity.entityId());
    }
}
```

### 2. Level of Detail (LOD) System

```java
public void renderWithLOD(Camera camera) {
    Frustum3D frustum = camera.getFrustum();
    Point3f cameraPos = camera.getPosition();

    // Define LOD distances
    float[] lodDistances = { 50, 150, 500, 1500 };

    for (int lod = 0; lod < lodDistances.length; lod++) {
        float maxDist = lodDistances[lod];
        float minDist = (lod > 0) ? lodDistances[lod - 1] : 0;

        List<FrustumIntersection<ID, Content>> entities = spatialIndex.frustumCullWithinDistance(frustum, cameraPos,
                                                                                                 maxDist);

        // Filter by minimum distance
        entities = entities.stream().filter(e -> e.distanceToCamera() >= minDist).collect(Collectors.toList());

        // Render with appropriate LOD
        renderLOD(entities, lod);
    }
}
```

### 3. Shadow Map Generation

```java
public void generateShadowMap(Light light) {
    // Create frustum from light's perspective
    Frustum3D lightFrustum = createLightFrustum(light);
    Point3f lightPos = light.getPosition();

    // Find shadow casters
    List<FrustumIntersection<ID, Content>> shadowCasters = spatialIndex.frustumCullVisible(lightFrustum, lightPos);

    // Render to shadow map
    bindShadowFramebuffer();
    for (FrustumIntersection<ID, Content> caster : shadowCasters) {
        renderToShadowMap(caster.entityId());
    }
}
```

### 4. Occlusion Culling Preparation

```java
public void prepareOcclusionQueries(Camera camera) {
    Frustum3D frustum = camera.getFrustum();
    Point3f cameraPos = camera.getPosition();

    // Get potentially visible set
    List<FrustumIntersection<ID, Content>> pvsSet = spatialIndex.frustumCullVisible(frustum, cameraPos);

    // Separate by visibility type
    List<FrustumIntersection<ID, Content>> fullyInside = pvsSet.stream().filter(
    e -> e.relation() == FrustumRelation.INSIDE).collect(Collectors.toList());

    List<FrustumIntersection<ID, Content>> needsTest = pvsSet.stream().filter(
    e -> e.relation() == FrustumRelation.INTERSECTING).collect(Collectors.toList());

    // Skip occlusion tests for fully visible
    renderWithoutOcclusionTest(fullyInside);

    // Perform occlusion queries for intersecting
    performOcclusionQueries(needsTest);
}
```

## Performance Considerations

1. **Hierarchical Culling**: Tree nodes are tested before their contents
2. **Early Rejection**: Entire subtrees are skipped if outside frustum
3. **Distance Sorting**: Results are pre-sorted by camera distance
4. **Spatial Coherence**: Nearby entities are processed together

## Implementation Details

### Octree

- Uses efficient frustum-AABB intersection tests
- Traverses nodes in front-to-back order

### Tetree

- Calculates bounding boxes for tetrahedral nodes
- Tests frustum against AABB for efficiency

## Best Practices

1. **Cache Frustums**: Recompute only when camera changes
2. **Use Appropriate Methods**:
    - `frustumCullVisible()` for general rendering
    - `frustumCullInside()` when clipping is expensive
    - `frustumCullWithinDistance()` for LOD systems
3. **Combine with Other Culling**: Use with occlusion culling, backface culling
4. **Update Frustum Correctly**: Account for camera rotation and position
5. **Profile Performance**: Measure culling time vs rendering savings

## Example: Complete Rendering System

```java
public class RenderingSystem {
    private SpatialIndex<ID, RenderableContent> spatialIndex;

    public void renderFrame(Camera camera, float deltaTime) {
        // Update camera frustum
        Frustum3D frustum = camera.getFrustum();
        Point3f cameraPos = camera.getPosition();

        // Frustum culling
        List<FrustumIntersection<ID, RenderableContent>> visible = spatialIndex.frustumCullVisible(frustum, cameraPos);

        // Separate opaque and transparent
        List<FrustumIntersection<ID, RenderableContent>> opaque = visible.stream().filter(
        e -> !e.content().isTransparent()).collect(Collectors.toList());

        List<FrustumIntersection<ID, RenderableContent>> transparent = visible.stream().filter(
        e -> e.content().isTransparent()).collect(Collectors.toList());

        // Render opaque front-to-back
        for (FrustumIntersection<ID, RenderableContent> entity : opaque) {
            renderOpaque(entity);
        }

        // Render transparent back-to-front
        Collections.reverse(transparent);
        for (FrustumIntersection<ID, RenderableContent> entity : transparent) {
            renderTransparent(entity);
        }
    }
}
```
