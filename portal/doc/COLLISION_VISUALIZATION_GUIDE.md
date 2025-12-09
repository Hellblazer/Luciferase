# Collision Visualization Guide

## Overview

The collision visualization system provides comprehensive tools for debugging and analyzing collision detection in real-time. It supports all collision shape types and offers various visualization modes for different debugging scenarios.

## Core Components

### CollisionVisualizer

The main visualization engine that provides real-time rendering of collision data:

```java

CollisionVisualizer visualizer = new CollisionVisualizer();

// Configure visualization options
visualizer.showShapes.set(true);
visualizer.showContacts.set(true);
visualizer.showPenetrationVectors.set(true);
visualizer.showAABBs.set(false);
visualizer.showVelocityVectors.set(true);

// Add to scene
scene.getChildren().add(visualizer.getVisualization());

```text

### CollisionDebugViewer

A complete application for interactive collision testing:

```java

public class MyCollisionTest extends CollisionDebugViewer {
    @Override
    protected void setupInitialShapes() {
        // Add test shapes
        addShape(new Sphere(1.0f), new Vector3f(0, 5, 0));
        addShape(new Box(2, 1, 2), new Vector3f(0, 0, 0));
    }
}

```text

## Supported Shape Types

All collision shapes can be visualized:

### Basic Shapes

- **Sphere**: Wireframe sphere with adjustable segments
- **Box**: Axis-aligned bounding box
- **OrientedBox**: Rotated box with orientation
- **Capsule**: Cylinder with hemisphere caps

### Complex Shapes

- **Mesh**: Triangle mesh visualization
- **ConvexHull**: Convex hull wireframe
- **Heightmap**: Terrain visualization

## Visualization Features

### Shape Rendering

```java

// Customize shape appearance
visualizer.setShapeColor(Color.GREEN);
visualizer.setShapeOpacity(0.7);
visualizer.setWireframeLineWidth(2.0);

```text

### Contact Points

Contact points are shown as small spheres with normal vectors:

```java

// Configure contact visualization
visualizer.showContacts.set(true);
visualizer.setContactColor(Color.RED);
visualizer.setNormalLength(0.5); // Length of normal arrows

```text

### Penetration Vectors

Shows penetration depth and direction for overlapping shapes:

```java

visualizer.showPenetrationVectors.set(true);
visualizer.setPenetrationColor(Color.YELLOW);

```text

### Velocity Vectors

Displays velocity and force vectors for moving objects:

```java

visualizer.showVelocityVectors.set(true);
visualizer.setVelocityScale(0.1); // Scale factor for vector length

```text

## Spatial Index Visualization

The SpatialIndexDebugVisualizer shows spatial partitioning:

```java

SpatialIndexDebugVisualizer<MortonKey, UUID, Entity> indexViz = 
    new SpatialIndexDebugVisualizer<>(octree);

// Configure level display
indexViz.setMinLevel(0);
indexViz.setMaxLevel(10);
indexViz.setNodeOpacity(0.3);

// Show collision hotspots
indexViz.showHotspots.set(true);
indexViz.setHotspotThreshold(10); // Min collisions for hotspot

```text

## Performance Monitoring

### CollisionProfiler

Track performance metrics:

```java

CollisionProfiler profiler = new CollisionProfiler();

// Start profiling
profiler.startOperation("broadPhase");
// ... perform operation
profiler.endOperation("broadPhase");

// Get statistics
String report = profiler.generateReport();
System.out.println(report);

```text

### Performance Visualization

Real-time performance display:

```java

PerformanceVisualization perfViz = new PerformanceVisualization();
perfViz.showFPS.set(true);
perfViz.showTimings.set(true);
perfViz.showMemoryUsage.set(true);

// Add to overlay
overlayGroup.getChildren().add(perfViz.getOverlay());

```text

## Debug Recording

Record and replay collision scenarios:

```java

CollisionEventRecorder recorder = new CollisionEventRecorder();

// Start recording
recorder.startRecording();

// Run simulation...

// Stop and save
recorder.stopRecording();
recorder.saveSession("debug_session.json");

// Load and replay
recorder.loadSession("debug_session.json");
recorder.startReplay();

```text

## Interactive Controls

The CollisionDebugViewer provides these controls:

### Keyboard

- **Space**: Pause/resume simulation
- **R**: Reset simulation
- **G**: Toggle gravity
- **W**: Toggle wireframe
- **C**: Toggle contacts
- **P**: Toggle penetration vectors
- **V**: Toggle velocity vectors
- **B**: Toggle AABBs

### Mouse

- **Left Click**: Select shape
- **Ctrl+Click**: Multi-select
- **Right Drag**: Add force to selected shape
- **Scroll**: Zoom camera

## Common Usage Patterns

### Basic Setup

```java

// Create visualization components
CollisionVisualizer visualizer = new CollisionVisualizer();
CollisionProfiler profiler = new CollisionProfiler();

// Configure visualization
visualizer.showShapes.set(true);
visualizer.showContacts.set(true);

// Add to collision system
collisionSystem.addListener(new CollisionListener() {
    @Override
    public void onCollision(CollisionEvent event) {
        visualizer.addCollision(event);
        profiler.recordCollision(event);
    }
});

```text

### Custom Shape Rendering

```java

// Add custom shape renderer
visualizer.addShapeRenderer(MyCustomShape.class, 
    (shape, material) -> {
        // Create custom wireframe
        Group wireframe = new Group();
        // ... build wireframe
        wireframe.setMaterial(material);
        return wireframe;
    }
);

```text

### Debugging Specific Collisions

```java

// Highlight specific collision pairs
visualizer.setHighlightFilter((shapeA, shapeB) -> {
    return shapeA.getType() == ShapeType.SPHERE && 
           shapeB.getType() == ShapeType.BOX;
});

// Color code by collision frequency
visualizer.setColorMapper((collision) -> {
    int frequency = profiler.getCollisionFrequency(collision);
    return Color.hsb(120 - Math.min(frequency, 120), 1, 1);
});

```text

## Performance Tips

1. **Limit Visualization Range**: Only visualize shapes within camera view
2. **Use Level Filtering**: For spatial indices, limit displayed levels
3. **Batch Updates**: Update visualization once per frame, not per collision
4. **Disable Unused Features**: Turn off visualization features you don't need
5. **Use Recording**: Record complex scenarios for offline analysis

## Troubleshooting

### Common Issues

1. **Performance Impact**: Visualization can slow down simulations
   - Solution: Use sampling (visualize every Nth collision)
   
2. **Visual Clutter**: Too many shapes make debugging difficult
   - Solution: Use filters to show only relevant collisions
   
3. **Missing Collisions**: Some collisions not visualized
   - Solution: Check update frequency and ensure listener is registered

### Debug Workflow

1. Start with basic shape visualization
2. Enable contact points to verify collision detection
3. Add penetration vectors to debug resolution
4. Use recording for complex scenarios
5. Profile to identify performance bottlenecks
