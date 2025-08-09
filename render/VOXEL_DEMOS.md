# Voxel Visualization Demos

This directory contains several voxel visualization demonstrations showcasing different aspects of the voxelization and sparse voxel octree system.

## Available Demos

### 1. Enhanced Voxel Visualization Demo
**Main Class:** `com.hellblazer.luciferase.render.demo.EnhancedVoxelVisualizationDemo$Launcher`

Advanced demo featuring:
- Side-by-side comparison of dense vs sparse voxel representations
- Real-time performance metrics and FPS tracking
- Memory usage comparison and compression ratio display
- LOD (Level-of-Detail) controls for octree visualization
- GPU-accelerated voxelization using WebGPU compute shaders
- Multiple geometry options (Cube, Sphere, Torus, Complex Mesh)
- Interactive 3D controls with mouse rotation and zoom

**To Run:**
```bash
./run-enhanced-voxel-demo.sh
```

Or directly with Maven:
```bash
mvn -pl render test-compile exec:java \
    -Dexec.mainClass="com.hellblazer.luciferase.render.demo.EnhancedVoxelVisualizationDemo\$Launcher" \
    -Dexec.classpathScope="test"
```

### 2. Simple Voxel Octree Demo
**Main Class:** `com.hellblazer.luciferase.render.demo.SimpleVoxelOctreeDemo`

Basic demonstration without GPU dependencies:
- Visual comparison of dense grid vs sparse octree
- Real-time statistics showing compression benefits
- Test pattern generation
- Animated rotation

**To Run:**
```bash
mvn -pl render test-compile exec:java \
    -Dexec.mainClass="com.hellblazer.luciferase.render.demo.SimpleVoxelOctreeDemo" \
    -Dexec.classpathScope="test"
```

### 3. Basic Voxel Visualization Demo
**Main Class:** `com.hellblazer.luciferase.render.demo.VoxelVisualizationDemo`

Original voxelization demo:
- GPU voxelization of triangle meshes
- Simple geometry generation
- Basic 3D visualization

**To Run:**
```bash
mvn -pl render test-compile exec:java \
    -Dexec.mainClass="com.hellblazer.luciferase.render.demo.VoxelVisualizationDemo" \
    -Dexec.classpathScope="test"
```

## Controls

All demos support the following mouse controls:
- **Left Click + Drag**: Rotate the view
- **Scroll Wheel**: Zoom in/out
- **Buttons**: Various geometry generation and test patterns

## Requirements

- Java 24+
- JavaFX 24
- WebGPU-compatible GPU (for GPU-accelerated demos)
- Maven 3.9+

## Performance Notes

- The Enhanced demo requires more memory due to dual visualization
- Grid sizes above 64x64x64 may require increased heap size
- GPU demos require WebGPU native library to be properly initialized

## Troubleshooting

If you encounter issues:

1. **WebGPU initialization fails**: Ensure WebGPU native libraries are available
2. **Out of memory**: Increase heap size with `-Xmx2g` or higher
3. **No visualization appears**: Check console for shader compilation errors
4. **Low FPS**: Reduce grid size or disable sparse mode for testing