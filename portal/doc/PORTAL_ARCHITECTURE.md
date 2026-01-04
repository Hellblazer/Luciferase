# Portal Module Architecture

**Last Updated**: 2026-01-04
**Status**: Current

## Overview

The Portal module provides comprehensive 3D visualization and mesh handling capabilities for the Luciferase spatial indexing system. Built on JavaFX 3D, it offers interactive visualization of spatial data structures, collision detection systems, and general 3D mesh operations.

## Module Structure

```text

portal/
├── src/main/java/com/hellblazer/luciferase/portal/
│   ├── mesh/              # 3D mesh handling and polyhedra
│   ├── collision/         # Collision visualization tools
│   ├── tree/              # Spatial index visualizations
│   └── (core classes)     # Camera, grids, transforms
├── doc/                   # Documentation
└── pom.xml               # Maven configuration

```text

## Core Components

### 1. Mesh Handling System

The mesh package provides a complete framework for working with 3D polygon meshes:

#### Core Classes

- **Mesh**: Fundamental mesh representation with vertices, normals, and faces
- **Face**: Polygon face supporting arbitrary vertex counts
- **Edge**: Edge representation for topology operations
- **MeshLoader**: OBJ and STL file format support

#### Polyhedra Generation

- **Polyhedron**: Base class with Conway operations (ambo, dual, expand, gyro, kis, propeller, reflect, snub, truncate)
- **Platonic Solids**: Tetrahedron, Cube, Octahedron, Dodecahedron, Icosahedron
- **Archimedean Solids**: Cuboctahedron, Icosidodecahedron, RhombicDodecahedron
- **Other Shapes**: Prism, Antiprism, Pyramid, Icosphere, Goldberg polyhedron

#### Structural Data (struct/)

- EdgeToAdjacentFace: Edge-face adjacency
- FaceToAdjacentFace: Face-face adjacency
- VertexToAdjacentFace: Vertex-face connectivity
- OrderedVertexToAdjacentEdge: Ordered edge rings
- OrderedVertexToAdjacentFace: Ordered face rings

### 2. Visualization Framework

The core visualization system provides interactive 3D scene management:

#### Base Classes

- **MagicMirror**: Primary base class for 3D applications with camera controls
- **Abstract3DApp**: Alternative base with different transform approach
- **SpatialIndexView<Key, ID, Content>**: Generic spatial index visualization

#### Camera and Navigation

- **CameraView**: First-person shooter style controls (WASD movement)
- **CameraBoom**: Camera manipulation with rotation order control
- **OrientedGroup/OrientedTxfm**: Composable transform chains

#### Grid Systems

- **CubicGrid**: Traditional orthogonal grid visualization
- **Tetrahedral**: Tetrahedral grid based on rhombic dodecahedron coordinates
- **Grid**: Abstract base providing axis visualization

### 3. Collision Visualization

Comprehensive tools for debugging and visualizing collision detection:

#### Core Components

- **CollisionVisualizer**: Main visualization engine with real-time rendering
- **CollisionShapeRenderer**: Low-level wireframe rendering utilities
- **CollisionDebugViewer**: Complete interactive demonstration application
- **SpatialIndexDebugVisualizer**: Spatial partitioning visualization

#### Supporting Tools

- **CollisionProfiler**: Performance analysis and timing statistics
- **CollisionEventRecorder**: Debug recording and replay functionality
- **WireframeBox**: Simple wireframe box rendering
- **PerformanceVisualization**: Real-time performance metrics display

### 4. Tree Visualizations

Specialized visualizations for spatial index structures:

- **OctreeView**: Octree visualization with Delaunay/Voronoi support
- **TetreeView**: Tetree visualization with tetrahedral rendering
- **OctreeInspector**: Detailed octree node inspection

## Key Features

### Interactive Controls

- Mouse: Drag to rotate, right-click to zoom, middle-click to pan
- Keyboard: Z (reset), X (toggle axes), V (toggle visibility)
- WASD: First-person navigation in CameraView mode
- Scroll wheel: Zoom with Ctrl/Shift modifiers

### Visualization Options

- Wireframe/solid toggle for meshes and shapes
- Level-based coloring (blue at level 0 to red at level 20)
- Entity and node visibility controls
- Adjustable opacity and entity sizes
- Selection highlighting with multi-select support

### Performance Features

- Lazy rendering with visibility culling
- Concurrent data structures for thread-safe updates
- Level range filtering to reduce rendered objects
- Render order control for proper layering

## Architecture Patterns

### Property-Based Reactive Design

- JavaFX properties for all configuration options
- Automatic UI updates via property listeners
- Bidirectional binding support

### Scene Graph Organization

```text

sceneRoot
├── nodeGroup      # Spatial index nodes
├── entityGroup    # Entity visualizations
├── queryGroup     # Query result overlays
└── overlayGroup   # UI overlays and indicators

```text

### Material and Rendering

- PhongMaterial for 3D shading effects
- Level-based material assignment
- Transparency support via opacity
- Specular highlights for enhanced visuals

### Extensibility

- Generic type parameters for different spatial structures
- Abstract methods for subclass customization
- Template method pattern for common operations
- Plugin-style collision shape renderers

## Usage Patterns

### Basic 3D Application

```java

public class MyApp extends MagicMirror {
    @Override
    protected void createScene() {
        // Add 3D content
    }
}

```text

### Spatial Index Visualization

```java

public class MyTreeView extends SpatialIndexView<MortonKey, UUID, Entity> {
    // Implement abstract methods
}

```text

### Collision Debugging

```java

CollisionVisualizer viz = new CollisionVisualizer();
viz.showShapes.set(true);
viz.showContacts.set(true);
scene.getChildren().add(viz.getVisualization());

```text

## Dependencies

- JavaFX 24: 3D rendering engine
- javax.vecmath: Vector mathematics
- Lucien module: Spatial index implementations
- JUnit 5: Testing framework

## Performance Considerations

1. **Rendering Limits**: Use level range filtering for large trees
2. **Update Frequency**: Batch updates when possible
3. **Memory Usage**: Large meshes should use indexed representation
4. **Thread Safety**: Use concurrent collections for multi-threaded updates

## Future Enhancements

1. WebGL export for browser-based visualization
2. VR/AR support for immersive debugging
3. Recording and playback of entire sessions
4. Performance heatmaps for spatial operations
5. Integration with external mesh editors
