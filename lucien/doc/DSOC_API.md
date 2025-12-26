# DSOC (Dynamic Scene Occlusion Culling) API Documentation

**Last Updated**: 2025-12-08
**Status**: Current

## Overview

The DSOC system provides efficient occlusion culling for dynamic scenes by maintaining Temporal Bounding Volumes (TBVs) for moving entities. DSOC is integrated at the AbstractSpatialIndex level, making it available for all spatial index implementations (Octree, Tetree, etc.).

## Core Components

### 1. DSOCConfiguration

Configures all aspects of the DSOC system with a fluent API pattern.

```java
// Create default configuration
DSOCConfiguration config = DSOCConfiguration.defaultConfig();

// Create high-performance configuration
DSOCConfiguration config = DSOCConfiguration.highPerformance()
    .withMaxTBVsPerEntity(1)
    .withUpdateCheckInterval(30)
    .withAggressiveCleanup(true);

// Create high-quality configuration
DSOCConfiguration config = DSOCConfiguration.highQuality()
    .withTBVRefreshThreshold(0.5f)
    .withPredictiveUpdates(true)
    .withPredictiveUpdateLookahead(60);

// Custom configuration
DSOCConfiguration config = new DSOCConfiguration()
    .withEnabled(true)
    .withTBVStrategy(AdaptiveTBVStrategy.defaultStrategy())
    .withMaxTBVsPerEntity(2)
    .withBatchUpdateSize(1000)
    .withEnableHierarchicalOcclusion(true)
    .withZPyramidLevels(6);

```

#### Key Configuration Options

- **enabled**: Enable/disable DSOC functionality
- **tbvStrategy**: Strategy for TBV creation and management
- **maxTBVsPerEntity**: Maximum TBVs allowed per entity
- **maxTotalTBVs**: Global TBV limit
- **tbvRefreshThreshold**: Quality threshold for TBV refresh (0.0-1.0)
- **updateCheckInterval**: Frames between update checks
- **enableHierarchicalOcclusion**: Use hierarchical Z-buffer
- **zPyramidLevels**: Number of levels in Z-buffer pyramid

### 2. Enabling DSOC

DSOC is enabled on a spatial index instance:

```java
// Create spatial index with DSOC
Octree<LongEntityID, String> octree = new Octree<>(idGenerator);
octree.enableDSOC(config, 1024, 768); // width, height for Z-buffer

// Check if DSOC is enabled
if (octree.isDSOCEnabled()) {
    // DSOC operations available
}

```

### 3. Camera Updates

Update camera matrices for occlusion testing:

```java
float[] viewMatrix = /* 4x4 view matrix */;
float[] projectionMatrix = /* 4x4 projection matrix */;
Point3f cameraPosition = new Point3f(x, y, z);

octree.updateCamera(viewMatrix, projectionMatrix, cameraPosition);

```

### 4. Frame Management

DSOC tracks frames for temporal coherence:

```java
// Advance to next frame
long currentFrame = octree.nextFrame();

// Get current frame
long frame = octree.getCurrentFrame();

```

### 5. Frustum Culling with DSOC

When DSOC is enabled, frustum culling automatically performs occlusion culling:

```java
// Create frustum
Frustum3D frustum = Frustum3D.createPerspective(
    cameraPos, lookAt, up,
    fov, aspectRatio, near, far
);

// Perform frustum culling with DSOC occlusion testing
List<FrustumIntersection<ID, Content>> visible = 
    octree.frustumCullVisible(frustum, cameraPosition);

```

### 6. Entity Visibility Management

Force immediate visibility updates for specific entities:

```java
// Force entity to be checked on next frame
octree.forceEntityUpdate(entityId);

```

### 7. Statistics and Monitoring

Get detailed DSOC performance statistics:

```java
Map<String, Object> stats = octree.getDSOCStatistics();

// Available statistics:
// - dsocEnabled: Whether DSOC is active
// - currentFrame: Current frame number
// - totalEntities: Total entity count
// - visibleEntities: Currently visible entities
// - hiddenWithTBV: Entities tracked by TBVs
// - activeTBVs: Number of active TBVs
// - entityOcclusionRate: Percentage of entities occluded
// - nodeOcclusionRate: Percentage of nodes occluded
// - tbvHitRate: TBV prediction accuracy
// - avgFrameTimeMs: Average frame processing time

// Reset statistics
octree.resetDSOCStatistics();

```

## TBV Strategies

### 1. AdaptiveTBVStrategy

Adapts TBV parameters based on entity behavior:

```java
TBVStrategy strategy = AdaptiveTBVStrategy.defaultStrategy();
// Or with custom parameters
TBVStrategy strategy = new AdaptiveTBVStrategy(
    minDuration: 30,
    maxDuration: 300,
    expansionFactor: 1.2f
);

```

### 2. FixedDurationTBVStrategy

Uses fixed duration for all TBVs:

```java
TBVStrategy strategy = new FixedDurationTBVStrategy(60); // 60 frames

```

### 3. VelocityBasedTBVStrategy

Calculates TBV size based on entity velocity:

```java
TBVStrategy strategy = new VelocityBasedTBVStrategy(
    velocityMultiplier: 1.5f,
    minDuration: 20,
    maxDuration: 200
);

```

## Complete Example

```java
public class DSOCExample {
    public void demonstrateDSOC() {
        // Create spatial index
        EntityIDGenerator<LongEntityID> idGenerator = new SequentialLongIDGenerator();
        Octree<LongEntityID, String> octree = new Octree<>(idGenerator);
        
        // Configure and enable DSOC
        DSOCConfiguration config = DSOCConfiguration.defaultConfig()
            .withEnabled(true)
            .withEnableHierarchicalOcclusion(true)
            .withTBVStrategy(AdaptiveTBVStrategy.defaultStrategy())
            .withUpdateCheckInterval(10);
            
        octree.enableDSOC(config, 1280, 720);
        
        // Add entities
        for (int i = 0; i < 1000; i++) {
            LongEntityID id = idGenerator.generateID();
            Point3f position = new Point3f(
                (float)(Math.random() * 1000),
                (float)(Math.random() * 1000),
                (float)(Math.random() * 1000)
            );
            octree.insert(id, position, (byte)10, "Entity" + i);
        }
        
        // Render loop
        while (rendering) {
            // Update camera
            float[] viewMatrix = calculateViewMatrix();
            float[] projMatrix = calculateProjectionMatrix();
            Point3f cameraPos = getCameraPosition();
            
            octree.updateCamera(viewMatrix, projMatrix, cameraPos);
            
            // Advance frame
            octree.nextFrame();
            
            // Create frustum
            Frustum3D frustum = createViewFrustum();
            
            // Get visible entities with occlusion culling
            List<FrustumIntersection<LongEntityID, String>> visible = 
                octree.frustumCullVisible(frustum, cameraPos);
            
            // Render visible entities
            for (FrustumIntersection<LongEntityID, String> item : visible) {
                renderEntity(item.getEntityId(), item.getContent());
            }
            
            // Update moving entities
            for (LongEntityID movingId : movingEntities) {
                Point3f newPos = calculateNewPosition(movingId);
                octree.updateEntity(movingId, newPos, (byte)10);
            }
            
            // Periodically check statistics
            if (frame % 100 == 0) {
                Map<String, Object> stats = octree.getDSOCStatistics();
                logPerformance(stats);
            }
        }
    }
}

```

## Performance Considerations

1. **Z-Buffer Resolution**: Higher resolution provides better occlusion accuracy but uses more memory
2. **Update Frequency**: Balance between accuracy and performance with updateCheckInterval
3. **TBV Strategy**: Choose based on scene characteristics:
   - Static scenes: Use longer durations
   - Highly dynamic: Use velocity-based or adaptive strategies
4. **Batch Operations**: Process multiple entities together for better performance

## Thread Safety

- All DSOC operations are thread-safe when the spatial index uses appropriate locking
- Statistics can be read concurrently with rendering
- Camera updates should be synchronized with frame advancement

## Memory Usage

- Z-Buffer: `width * height * levels * 4 bytes`
- Per-Entity overhead: ~200 bytes for visibility tracking
- Per-TBV overhead: ~300 bytes including bounding volume

## Integration with Existing Code

DSOC integrates seamlessly with existing spatial index operations:

```java
// All standard operations work with DSOC enabled
octree.insert(id, position, level, content);
octree.removeEntity(id);
octree.updateEntity(id, newPosition, level);
octree.findKNearestNeighbors(position, k);
octree.frustumCull(frustum); // Automatically includes occlusion

// DSOC-specific operations
octree.updateCamera(view, proj, pos);
octree.nextFrame();
octree.getDSOCStatistics();

```
