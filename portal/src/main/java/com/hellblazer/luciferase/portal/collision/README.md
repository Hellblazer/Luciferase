# Collision Debug and Visualization System

**Last Updated**: 2025-12-08
**Status**: Current

## Overview

This package provides a comprehensive collision detection debugging and visualization system for the Luciferase collision engine. It implements **Phase 6: Debug and Visualization Tools** with complete wireframe rendering, performance profiling, and event recording capabilities.

## Components

### 1. **CollisionShapeRenderer**
Renders collision shapes as wireframe visualizations.

```java
// Render a sphere wireframe
var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
Node wireframe = CollisionShapeRenderer.renderWireframe(sphere, Color.CYAN);

// Create contact point visualization
Node contact = CollisionShapeRenderer.createContactPoint(
    contactPoint, contactNormal, Color.YELLOW
);
```

### 2. **CollisionVisualizer**
Complete real-time visualization system with property bindings.

```java
var visualizer = new CollisionVisualizer();

// Add shapes and bodies
visualizer.addShape(sphere);
visualizer.addRigidBody(rigidBody);

// Configure visualization
visualizer.showWireframesProperty().set(true);
visualizer.showContactPointsProperty().set(true);
visualizer.wireframeColorProperty().set(Color.BLUE);

// Add to scene
scene.getChildren().add(visualizer.getRootGroup());

// Update each frame
visualizer.update();
```

### 3. **CollisionProfiler**
Thread-safe performance profiling for collision operations.

```java
var profiler = CollisionProfiler.getInstance();

// Time operations
var context = profiler.startTiming("collision_detection");
// ... collision detection code ...
context.stop();

// Record collision pairs
profiler.recordCollisionPair("Sphere", "Box", true);

// Generate performance report
String report = profiler.generateReport();
System.out.println(report);
```

### 4. **CollisionEventRecorder**
Record and replay collision events for debugging.

```java
var recorder = new CollisionEventRecorder();

// Start recording
recorder.isRecordingProperty().set(true);

// Record collision events
recorder.recordCollision(shapeA, shapeB, contactPoint, normal, depth);
recorder.nextFrame();

// Stop and replay
recorder.isRecordingProperty().set(false);
recorder.startReplay();
recorder.stepReplay(); // Step through recorded events
```

### 5. **SpatialIndexDebugVisualizer**
Visualize spatial partitioning and collision hotspots.

```java
var spatialVisualizer = new SpatialIndexDebugVisualizer();

// Track collision shapes
spatialVisualizer.addCollisionShape(shape, entityId);

// Record collision hotspots
spatialVisualizer.recordCollision(collisionPoint);

// Configure visualization
spatialVisualizer.showNodesProperty().set(true);
spatialVisualizer.minLevelProperty().set(0);
spatialVisualizer.maxLevelProperty().set(10);
```

### 6. **CollisionDebugViewer**
Complete interactive demonstration application.

```java
public class MyCollisionDemo extends CollisionDebugViewer {
    public static void main(String[] args) {
        launch(args);
    }
}
```

## Features

### Visualization Features
- ✅ **Wireframe rendering** for all collision shapes (Sphere, Box, OrientedBox, Capsule, Mesh, ConvexHull, Heightmap)
- ✅ **Contact point visualization** with normal vectors
- ✅ **Penetration vector display** showing collision depth
- ✅ **Velocity and force vector** visualization
- ✅ **AABB bounding box** display
- ✅ **Spatial index visualization** with level-based coloring

### Performance Analysis
- ✅ **Real-time timing statistics** for all collision operations
- ✅ **Collision pair frequency** tracking and hit rate analysis
- ✅ **Hot path detection** for operations >1ms
- ✅ **Frame rate monitoring** and bottleneck identification
- ✅ **Comprehensive performance reports** with timing breakdowns

### Debugging Tools
- ✅ **Event recording and replay** with deterministic capture
- ✅ **Frame-by-frame stepping** through collision scenarios
- ✅ **Session save/load** for reproducible test cases
- ✅ **Collision hotspot tracking** for spatial analysis
- ✅ **Interactive controls** for all visualization aspects

### Integration
- ✅ **JavaFX-based** visualization using the existing portal framework
- ✅ **Thread-safe implementation** suitable for production debugging
- ✅ **Property-based configuration** with live updates
- ✅ **Observable collections** for data binding
- ✅ **Comprehensive test coverage** with integration tests

## Usage Example

```java
public class CollisionDebugExample extends Abstract3DApp {
    
    private CollisionVisualizer visualizer;
    private CollisionProfiler profiler;
    private CollisionEventRecorder recorder;
    
    @Override
    protected void setupScene() {
        // Initialize visualization system
        visualizer = new CollisionVisualizer();
        profiler = CollisionProfiler.getInstance();
        recorder = new CollisionEventRecorder();
        
        // Add visualizer to scene
        sceneRoot.getChildren().add(visualizer.getRootGroup());
        
        // Create collision shapes
        var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var box = new BoxShape(new Point3f(2, 0, 0), new Vector3f(1, 1, 1));
        
        visualizer.addShape(sphere);
        visualizer.addShape(box);
        
        // Start recording
        recorder.isRecordingProperty().set(true);
        
        // Animation loop
        var timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Profile collision detection
                var frameContext = profiler.startFrame();
                var detectionContext = profiler.startTiming("collision_detection");
                
                // Test collision
                var collision = sphere.collidesWith(box);
                profiler.recordCollisionPair("Sphere", "Box", collision.collides);
                
                if (collision.collides) {
                    // Record and visualize collision
                    recorder.recordCollision(sphere, box, 
                        collision.contactPoint, collision.contactNormal, 
                        collision.penetrationDepth);
                    visualizer.addContact(collision.contactPoint, 
                        collision.contactNormal, collision.penetrationDepth);
                }
                
                detectionContext.stop();
                recorder.nextFrame();
                frameContext.stop();
                
                // Update visualizations
                visualizer.update();
            }
        };
        timer.start();
    }
}
```

## Configuration

### Visualization Properties
- `showWireframes`: Display collision shape wireframes
- `showContactPoints`: Display collision contact points
- `showPenetrationVectors`: Display penetration depth vectors
- `showVelocityVectors`: Display velocity vectors for rigid bodies
- `showAABBs`: Display axis-aligned bounding boxes
- `wireframeColor`: Color for wireframe rendering
- `contactPointColor`: Color for contact point visualization
- `vectorScale`: Scale factor for vector visualizations

### Performance Profiling
- Automatic timing of all collision operations
- Collision pair frequency tracking
- Hot path detection (>1ms operations)
- Comprehensive performance reporting
- Frame rate monitoring

### Event Recording
- Deterministic capture of collision events
- Complete state recording (positions, velocities, orientations)
- Frame-accurate replay functionality
- Session persistence with save/load
- Export to text format for analysis

## Building and Running

The collision visualization system is part of the portal module:

```bash
# Compile
mvn compile -pl portal

# Run tests
mvn test -pl portal

# Run the demo application
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.collision.CollisionDebugViewer"
```

## Dependencies

- **JavaFX 24**: For 3D visualization and UI components
- **Lucien module**: For collision detection classes
- **Java 23**: For pattern matching and modern language features

## Notes

- All visualization runs on the JavaFX Application Thread
- Performance profiling uses thread-safe concurrent data structures
- Event recording captures complete deterministic state
- The system is designed for both development debugging and production monitoring
- Visualization properties support data binding for interactive controls