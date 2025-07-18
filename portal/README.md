# Portal Module

The Portal module provides comprehensive 3D visualization and mesh handling capabilities for the Luciferase spatial indexing system. Built on JavaFX 3D, it offers interactive visualization tools for debugging spatial data structures, collision detection systems, and general 3D mesh operations.

## Features

- **3D Mesh Handling**: Load, create, and manipulate polygon meshes with support for OBJ and STL formats
- **Collision Visualization**: Debug collision detection with real-time shape rendering and contact visualization
- **Spatial Index Visualization**: Interactive viewers for Octree and Tetree data structures
- **Interactive Camera Controls**: Multiple camera systems including first-person and orbital controls
- **Polyhedra Generation**: Create and transform geometric shapes using Conway operations

## Quick Start

### Basic 3D Application

```java
import com.hellblazer.luciferase.portal.MagicMirror;

public class MyApp extends MagicMirror {
    @Override
    protected void createScene() {
        Box box = new Box(100, 100, 100);
        box.setMaterial(new PhongMaterial(Color.BLUE));
        root.getChildren().add(box);
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
```

### Loading a 3D Model

```java
MeshView model = MeshLoader.loadMeshView("model.obj");
scene.getChildren().add(model);
```

### Visualizing Collision Detection

```java
CollisionVisualizer visualizer = new CollisionVisualizer();
visualizer.showShapes.set(true);
visualizer.showContacts.set(true);
scene.getChildren().add(visualizer.getVisualization());
```

## Documentation

Detailed documentation is available in the `portal/doc/` directory:

- [**Portal Architecture**](doc/PORTAL_ARCHITECTURE.md) - Complete module architecture and component overview
- [**Mesh Handling Guide**](doc/MESH_HANDLING_GUIDE.md) - Working with 3D meshes, file formats, and polyhedra
- [**Collision Visualization Guide**](doc/COLLISION_VISUALIZATION_GUIDE.md) - Debugging collision detection systems
- [**Visualization Framework Guide**](doc/VISUALIZATION_FRAMEWORK_GUIDE.md) - Creating interactive 3D applications

Additional documentation:
- [Transform-Based Architecture](doc/TRANSFORM_BASED_ARCHITECTURE.md) - Transform system design
- [Spatial Index Visualization Plan](doc/SPATIAL_INDEX_VISUALIZATION_PLAN.md) - Visualizing spatial data structures
- [Transform Verification Guide](doc/TRANSFORM_VERIFICATION_GUIDE.md) - Testing transform implementations

## Module Structure

```
portal/
├── src/main/java/com/hellblazer/luciferase/portal/
│   ├── mesh/              # 3D mesh handling and polyhedra
│   │   ├── polyhedra/     # Geometric shape generation
│   │   ├── struct/        # Mesh topology structures
│   │   └── util/          # Mesh utilities
│   ├── collision/         # Collision visualization tools
│   ├── tree/              # Spatial index visualizations
│   └── (core classes)     # Camera, grids, transforms
└── doc/                   # Documentation
```

## Key Components

### Mesh Package
- **Mesh**: Core mesh representation with vertices, normals, and faces
- **MeshLoader**: OBJ and STL file format support
- **Polyhedron**: Base class for geometric shapes with Conway operations
- **Face/Edge**: Topology primitives for mesh manipulation

### Collision Package
- **CollisionVisualizer**: Real-time collision shape and contact rendering
- **CollisionDebugViewer**: Interactive collision testing application
- **CollisionProfiler**: Performance analysis tools
- **SpatialIndexDebugVisualizer**: Spatial partitioning visualization

### Visualization Framework
- **MagicMirror**: Base class for 3D applications with camera controls
- **SpatialIndexView**: Generic spatial index visualization
- **CameraView**: First-person camera controls
- **CubicGrid/Tetrahedral**: 3D grid visualization systems

## Dependencies

- JavaFX 24 - 3D rendering engine
- javax.vecmath - Vector mathematics
- Lucien module - Spatial index implementations

## Examples

### Creating a Polyhedron

```java
// Create a truncated icosahedron (soccer ball)
Polyhedron soccerBall = new Icosahedron(1.0).truncate();
MeshView view = new MeshView(soccerBall.toTriangleMesh());
```

### Spatial Index Visualization

```java
OctreeView view = new OctreeView(octree);
view.showEmptyNodes.set(false);
view.nodeOpacity.set(0.3);
view.minLevel.set(0);
view.maxLevel.set(10);
```

### Interactive Camera

```java
CameraView camera = new CameraView(scene);
scene.setOnKeyPressed(e -> {
    switch (e.getCode()) {
        case W: camera.moveForward(1.0); break;
        case S: camera.moveBackward(1.0); break;
        case A: camera.strafeLeft(1.0); break;
        case D: camera.strafeRight(1.0); break;
    }
});
```

## Performance Considerations

- Use level filtering for large spatial indices
- Enable visibility culling for complex scenes
- Batch updates when modifying multiple objects
- Use appropriate mesh LOD for viewing distance

## Future Enhancements

- WebGL export for browser visualization
- VR/AR support for immersive debugging
- Session recording and playback
- Performance heatmap overlays
- External mesh editor integration