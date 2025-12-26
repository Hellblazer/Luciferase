# Luciferase Portal - Visualization Suite

**Last Updated**: 2025-12-25
**Status**: Current

Interactive JavaFX visualization applications for spatial data structures, physics, and geometry.

## Quick Start

### Launch the Master Menu

The easiest way to run any demo is through the master launcher:

```bash
mvn clean compile -pl portal
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.LuciferaseLauncher"
```

This opens a menu where you can select which demo to launch.

## Available Demos

### 1. ESVO Octree Inspector

**Class**: `com.hellblazer.luciferase.portal.esvo.OctreeInspectorApp.Launcher`

Interactive octree visualization with:

- Procedural geometry generation (sphere, cube, torus, fractals)
- Ray casting with visualization
- LOD (Level of Detail) controls
- Screenshot capture (S key)
- Frame sequence recording
- Real-time performance metrics

**Direct launch**:
```bash
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.esvo.OctreeInspectorApp\$Launcher"
```

### 2. Collision Debug Viewer

**Class**: `com.hellblazer.luciferase.portal.collision.CollisionDebugViewer`

Physics and collision detection visualization with:

- Multiple collision shapes (sphere, box, capsule)
- Real-time contact point visualization
- Penetration vector display
- Velocity and force vectors
- AABB visualization
- Interactive physics simulation

**Direct launch**:
```bash
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.collision.CollisionDebugViewer"
```

### 3. Tetree Inspector

**Class**: `com.hellblazer.luciferase.portal.mesh.explorer.TetreeInspector`

Tetrahedral space partitioning tree visualization.

**Direct launch**:
```bash
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.mesh.explorer.TetreeInspector"
```

### 4. Grid Inspector

**Class**: `com.hellblazer.luciferase.portal.mesh.explorer.grid.GridInspector`

Cubic grid and neighborhood exploration.

**Direct launch**:
```bash
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.mesh.explorer.grid.GridInspector"
```

### 5. Geometry Viewer

**Class**: `com.hellblazer.luciferase.portal.mesh.explorer.GeometryViewer`

3D geometry and mesh visualization.

**Direct launch**:
```bash
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.mesh.explorer.GeometryViewer"
```

### 6. RD Grid Viewer

**Class**: `com.hellblazer.luciferase.portal.mesh.explorer.RDGridViewer`

Reaction-diffusion grid visualization.

**Direct launch**:
```bash
mvn exec:java -pl portal -Dexec.mainClass="com.hellblazer.luciferase.portal.mesh.explorer.RDGridViewer"
```

## Common Controls

Most demos support these common controls:

- **Mouse drag**: Rotate camera
- **Mouse wheel**: Zoom in/out
- **WASD keys**: Move camera (when first-person mode enabled)
- **X**: Toggle axes
- **G**: Toggle grid
- **R**: Reset camera

See individual demo help text for specific controls.

## Requirements

- Java 24+
- JavaFX 24
- Maven 3.9.1+

## Output Directories

When using screenshot/recording features:

- **screenshots/**: Screenshot PNG files with timestamps
- **recordings/**: Frame sequence PNG files for video creation
