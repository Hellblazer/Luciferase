# Frustum Culling API Documentation

**Last Updated**: 2025-12-08
**Status**: Current

The Frustum Culling API provides efficient methods for determining which entities are visible within a 3D viewing
frustum. This is essential for rendering optimization in 3D graphics applications.

## Overview

Frustum culling allows you to:

- Find all entities potentially visible within a camera's view frustum
- Leverage hierarchical spatial indexing for efficient culling
- Early rejection of entire subtrees outside the frustum

## API Method

### Find All Visible Entities

```java
List<ID> frustumCullVisible(Frustum3D frustum)

```

Finds all entities that are potentially visible within the frustum. This performs frustum culling by testing spatial nodes against the frustum planes.

**Parameters:**

- `frustum` - The 3D viewing frustum to test against

**Returns:**

- List of entity IDs that are potentially visible within the frustum

**Example:**

```java
// Create a perspective frustum using camera parameters
Frustum3D frustum = new Frustum3D(
    nearPlane,    // Near plane
    farPlane,     // Far plane  
    leftPlane,    // Left plane
    rightPlane,   // Right plane
    topPlane,     // Top plane
    bottomPlane   // Bottom plane
);

// Find all visible entities
List<ID> visibleEntities = spatialIndex.frustumCullVisible(frustum);

// Retrieve content for visible entities
List<Content> visibleContent = spatialIndex.getEntities(visibleEntities);

// Render visible entities
for (int i = 0; i < visibleEntities.size(); i++) {
    ID entityId = visibleEntities.get(i);
    Content content = visibleContent.get(i);
    if (content != null) {
        render(entityId, content);
    }
}

```

## Creating Frustums

The `Frustum3D` class represents a viewing frustum defined by 6 planes:

```java
// Define frustum with 6 planes
Plane3D nearPlane = new Plane3D(normal, distance);
Plane3D farPlane = new Plane3D(normal, distance);
Plane3D leftPlane = new Plane3D(normal, distance);
Plane3D rightPlane = new Plane3D(normal, distance);
Plane3D topPlane = new Plane3D(normal, distance);
Plane3D bottomPlane = new Plane3D(normal, distance);

Frustum3D frustum = new Frustum3D(
    nearPlane, farPlane,
    leftPlane, rightPlane,
    topPlane, bottomPlane
);

```

## Use Cases

### 1. Basic Rendering Pipeline

```java
public void render(Camera camera) {
    Frustum3D frustum = camera.getFrustum();
    
    // Cull invisible entities
    List<ID> visibleIds = spatialIndex.frustumCullVisible(frustum);
    
    // Get entity content
    List<Content> visibleContent = spatialIndex.getEntities(visibleIds);
    
    // Render visible entities
    for (int i = 0; i < visibleIds.size(); i++) {
        if (visibleContent.get(i) != null) {
            renderEntity(visibleIds.get(i), visibleContent.get(i));
        }
    }
}

```

### 2. Shadow Map Generation

```java
public void generateShadowMap(Light light) {
    // Create frustum from light's perspective
    Frustum3D lightFrustum = createLightFrustum(light);
    
    // Find shadow casters
    List<ID> shadowCasters = spatialIndex.frustumCullVisible(lightFrustum);
    
    // Render to shadow map
    bindShadowFramebuffer();
    for (ID casterId : shadowCasters) {
        Content caster = spatialIndex.getEntity(casterId);
        if (caster != null) {
            renderToShadowMap(casterId, caster);
        }
    }
}

```

### 3. LOD System Integration

```java
public void renderWithLOD(Camera camera) {
    Frustum3D frustum = camera.getFrustum();
    Point3f cameraPos = camera.getPosition();
    
    // Get all visible entities
    List<ID> visible = spatialIndex.frustumCullVisible(frustum);
    
    // Get positions to calculate distances
    Map<ID, Point3f> positions = spatialIndex.getEntitiesWithPositions();
    
    // Group by distance for LOD
    Map<Integer, List<ID>> lodGroups = new HashMap<>();
    for (ID id : visible) {
        Point3f pos = positions.get(id);
        if (pos != null) {
            float distance = pos.distance(cameraPos);
            int lod = calculateLOD(distance);
            lodGroups.computeIfAbsent(lod, k -> new ArrayList<>()).add(id);
        }
    }
    
    // Render each LOD group
    for (Map.Entry<Integer, List<ID>> entry : lodGroups.entrySet()) {
        renderLOD(entry.getKey(), entry.getValue());
    }
}

```

## Performance Considerations

1. **Hierarchical Culling**: Tree nodes are tested before their contents
2. **Early Rejection**: Entire subtrees are skipped if outside frustum
3. **Spatial Coherence**: Nearby entities are processed together

## Implementation Details

### Octree

- Uses efficient frustum-AABB intersection tests
- Tests axis-aligned bounding boxes against frustum planes

### Tetree

- Calculates bounding boxes for tetrahedral nodes
- Tests frustum against AABB for efficiency

## Best Practices

1. **Cache Frustums**: Recompute only when camera changes
2. **Batch Entity Retrieval**: Use `getEntities()` for multiple IDs
3. **Combine with Other Culling**: Use with occlusion culling, backface culling
4. **Update Frustum Correctly**: Account for camera rotation and position
5. **Profile Performance**: Measure culling time vs rendering savings

## Example: Complete Rendering System

```java
public class RenderingSystem {
    private SpatialIndex<Key, ID, RenderableContent> spatialIndex;

    public void renderFrame(Camera camera) {
        // Update camera frustum
        Frustum3D frustum = camera.getFrustum();
        
        // Frustum culling
        List<ID> visibleIds = spatialIndex.frustumCullVisible(frustum);
        
        // Batch retrieve content
        List<RenderableContent> visibleContent = spatialIndex.getEntities(visibleIds);
        
        // Separate and render
        List<ID> opaqueIds = new ArrayList<>();
        List<ID> transparentIds = new ArrayList<>();
        
        for (int i = 0; i < visibleIds.size(); i++) {
            RenderableContent content = visibleContent.get(i);
            if (content != null) {
                if (content.isTransparent()) {
                    transparentIds.add(visibleIds.get(i));
                } else {
                    opaqueIds.add(visibleIds.get(i));
                }
            }
        }
        
        // Render opaque objects
        for (ID id : opaqueIds) {
            renderOpaque(id);
        }
        
        // Render transparent objects (consider sorting by distance)
        for (ID id : transparentIds) {
            renderTransparent(id);
        }
    }
}

```
