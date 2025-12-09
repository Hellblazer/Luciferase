# Portal Module

**Last Updated**: 2025-12-08
**Status**: Current

JavaFX 3D visualization and mesh handling for Luciferase

## Overview

Portal provides interactive 3D visualization capabilities for spatial data structures, including real-time rendering of octrees, tetrees, meshes, and collision shapes. Built on JavaFX 24, it offers both immediate mode and retained mode rendering with camera controls and debug overlays.

## Features

### Visualization Components

- **Spatial Index Rendering**
  - Octree node visualization with level coloring
  - Tetree tetrahedral mesh display
  - Entity position and bounds rendering
  - Tree traversal animation

- **Mesh Generation & Display**
  - Triangle mesh generation from voxels
  - Wireframe and solid rendering modes
  - Normal visualization
  - UV mapping support

- **Interactive Controls**
  - Orbit camera with mouse control
  - First-person camera mode
  - Zoom and pan navigation
  - Entity selection and highlighting

- **Debug Overlays**
  - Performance metrics (FPS, node count)
  - Spatial statistics display
  - Collision shape visualization
  - Ray cast debugging

### Advanced Features

- **Material System**: PBR materials with textures
- **Lighting**: Directional, point, and ambient lights
- **Post-Processing**: FXAA anti-aliasing
- **LOD System**: Level-of-detail mesh switching
- **Animation**: Keyframe and procedural animation

## Architecture

```text

com.hellblazer.luciferase.portal/
├── view/
│   ├── View3D              # Main 3D viewport
│   ├── Camera              # Camera controllers
│   ├── MeshView            # Mesh rendering
│   └── DebugView           # Debug overlays
├── mesh/
│   ├── MeshGenerator       # Voxel to mesh conversion
│   ├── MeshOptimizer       # Mesh simplification
│   └── MeshExporter        # Export to OBJ/PLY
├── material/
│   ├── Material            # Material properties
│   ├── Texture             # Texture loading
│   └── Shader              # Custom shaders
└── control/
    ├── CameraController    # Camera input handling
    ├── SelectionTool       # Entity selection
    └── MeasurementTool     # Distance/volume tools

```text

## Usage Examples

### Basic 3D View Setup

```java

import com.hellblazer.luciferase.portal.view.View3D;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Viewer extends Application {
    @Override
    public void start(Stage stage) {
        // Create 3D view
        var view3D = new View3D(800, 600);
        
        // Load spatial data
        var octree = loadOctree();
        view3D.setSpatialIndex(octree);
        
        // Setup scene
        var scene = new Scene(view3D, 800, 600);
        stage.setScene(scene);
        stage.setTitle("Luciferase 3D Viewer");
        stage.show();
        
        // Start rendering
        view3D.startAnimation();
    }
}

```text

### Mesh Generation

```java

import com.hellblazer.luciferase.portal.mesh.MeshGenerator;

// Generate mesh from voxel octree
var generator = new MeshGenerator();
var mesh = generator.generateFromOctree(octree);

// Optimize mesh
var optimizer = new MeshOptimizer();
mesh = optimizer.simplify(mesh, 0.5); // 50% reduction

// Display mesh
var meshView = new MeshView(mesh);
meshView.setMaterial(new PhongMaterial(Color.BLUE));
view3D.addMesh(meshView);

```text

### Camera Control

```java

// Setup orbit camera
var camera = new OrbitCamera();
camera.setTarget(new Point3D(0, 0, 0));
camera.setDistance(100);
camera.setAzimuth(45);
camera.setElevation(30);

view3D.setCamera(camera);

// Enable mouse control
camera.enableMouseControl(scene);

// Animate camera
var timeline = new Timeline(
    new KeyFrame(Duration.ZERO, 
        new KeyValue(camera.azimuthProperty(), 0)),
    new KeyFrame(Duration.seconds(10), 
        new KeyValue(camera.azimuthProperty(), 360))
);
timeline.setCycleCount(Timeline.INDEFINITE);
timeline.play();

```text

### Debug Visualization

```java

// Enable debug overlays
view3D.setDebugMode(true);

// Show octree nodes
view3D.setShowOctreeNodes(true);
view3D.setOctreeNodeColor(depth -> 
    Color.hsb(depth * 20, 0.8, 1.0));

// Show collision shapes
view3D.setShowCollisionShapes(true);

// Display statistics
view3D.setShowStatistics(true);
var stats = view3D.getStatistics();
System.out.println("FPS: " + stats.getFps());
System.out.println("Nodes: " + stats.getNodeCount());

```text

### Material and Lighting

```java

// Create PBR material
var material = new PBRMaterial();
material.setBaseColor(Color.rgb(200, 100, 50));
material.setMetallic(0.7);
material.setRoughness(0.3);
material.setNormalMap(loadTexture("normal.png"));

// Setup lighting
var sunLight = new DirectionalLight(Color.WHITE);
sunLight.setDirection(new Point3D(-1, -1, -1));

var fillLight = new AmbientLight(Color.rgb(50, 50, 80));

view3D.addLight(sunLight);
view3D.addLight(fillLight);

```text

## Performance

### Rendering Benchmarks

| Scene Complexity | FPS (JavaFX) | Draw Calls | Triangles |
| ----------------- | -------------- | ------------ | ----------- |
| Simple (1K nodes) | 60 | 50 | 10K |
| Medium (10K nodes) | 60 | 200 | 100K |
| Complex (100K nodes) | 30 | 800 | 1M |
| Extreme (1M nodes) | 15 | 2000 | 5M |

### Optimization Techniques

- **Frustum Culling**: Only render visible nodes
- **Level-of-Detail**: Reduce detail for distant objects
- **Instanced Rendering**: Batch similar geometry
- **Occlusion Culling**: Skip hidden objects
- **Mesh Optimization**: Simplify complex meshes

## Keyboard Shortcuts

| Key | Action |
| ----- | -------- |
| W/S | Move forward/backward |
| A/D | Move left/right |
| Q/E | Move up/down |
| Mouse Drag | Rotate camera |
| Scroll | Zoom in/out |
| F | Focus on selection |
| G | Toggle grid |
| H | Toggle UI |
| Space | Reset view |

## Building

```bash

# Build module

mvn clean install -pl portal

# Run demo application

mvn javafx:run -pl portal

# Package as standalone app

mvn javafx:jlink -pl portal

```text

## Dependencies

- **JavaFX 24**: 3D graphics and UI
- **lucien**: Spatial data structures
- **common**: Shared utilities
- **javax.vecmath**: 3D mathematics

## Testing

```bash

# Run tests

mvn test -pl portal

# Run with visualization (requires display)

mvn test -pl portal -DargLine="-Djava.awt.headless=false"

```text

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details
