# Running WebGPU Demos

## Main End-to-End Demo: WebGPUVoxelDemo

The primary demonstration application that shows the complete WebGPU rendering pipeline with surface creation, window management, and voxel rendering.

⚠️ **CRITICAL: This demo REQUIRES the `-XstartOnFirstThread` flag on macOS!** Without this flag, the surface configuration will fail and you'll see only a black canvas.

### Location
`render/src/main/java/com/hellblazer/luciferase/render/webgpu/WebGPUVoxelDemo.java`

### Features
- GLFW window creation with platform-specific surface
- WebGPU context initialization
- Instanced voxel rendering
- Camera controls (mouse drag to rotate, scroll to zoom)
- Real-time FPS counter
- Phong lighting with material colors
- Sphere of colored voxels (32x32x32 grid)

### Running the Demo

#### Method 1: Using the provided script
```bash
./run-webgpu-demo.sh
```

#### Method 2: Direct Java execution (required on macOS)
```bash
# Build first
mvn clean compile package -DskipTests
mvn dependency:copy-dependencies -pl render

# Run with platform-specific flags
# macOS:
java -XstartOnFirstThread -cp "render/target/classes:webgpu-ffm/target/classes:render/target/dependency/*" \
     com.hellblazer.luciferase.render.webgpu.WebGPUVoxelDemo

# Linux/Windows:
java -cp "render/target/classes:webgpu-ffm/target/classes:render/target/dependency/*" \
     com.hellblazer.luciferase.render.webgpu.WebGPUVoxelDemo
```

**Note**: Maven exec:java does NOT work on macOS due to -XstartOnFirstThread requirement

#### Method 3: Building and running JAR
```bash
mvn clean package -DskipTests
java -cp "render/target/render-0.0.1-SNAPSHOT.jar:render/target/dependency/*" \
  com.hellblazer.luciferase.render.webgpu.WebGPUVoxelDemo
```

### Controls
- **Mouse Drag**: Rotate camera around voxels
- **Scroll**: Zoom in/out
- **Space**: Toggle auto-rotation
- **R**: Regenerate voxel data
- **ESC**: Exit application

### Requirements
- Java 24 with FFM API support
- WebGPU-capable GPU
- Platform support:
  - macOS: Metal backend
  - Linux: Vulkan backend via X11/Wayland
  - Windows: D3D12/Vulkan backend

### Architecture Components Used

1. **WebGPUContext**: Manages WebGPU initialization and surface creation
2. **InstancedVoxelRenderer**: Efficient instanced rendering of voxels
3. **RenderPipelineBuilder**: Constructs the render pipeline
4. **ShaderManager**: Compiles and manages WGSL shaders
5. **UniformBufferManager**: Updates camera and lighting uniforms
6. **CommandBufferManager**: Records and submits render commands
7. **BufferPool**: Manages GPU buffer allocation

### Expected Output
- Window: 1400x900 pixels titled "WebGPU Voxel Demo"
- Renders a rotating sphere of colored voxels
- FPS counter in console output
- Smooth 60 FPS rendering (hardware dependent)

## Other Demo Applications

### WebGPUEnhancedVoxelDemo
Location: `render/src/test/java/com/hellblazer/luciferase/render/demo/WebGPUEnhancedVoxelDemo.java`
- Extended version with more features
- Test harness for advanced rendering techniques

### WebGPUNativeVoxelDemo  
Location: `webgpu-ffm/src/test/java/com/hellblazer/luciferase/webgpu/demo/WebGPUNativeVoxelDemo.java`
- Lower-level demo using direct FFM bindings
- Useful for testing wrapper functionality

## Troubleshooting

### Common Issues

1. **"Failed to initialize GLFW"**
   - Ensure GLFW native libraries are available
   - Check display server on Linux (X11/Wayland)

2. **"Failed to get adapter"**
   - Verify WebGPU/Dawn drivers are installed
   - Check GPU compatibility

3. **"Surface creation failed"**
   - Platform-specific surface issue
   - Check window manager compatibility

4. **Validation Errors**
   - Non-fatal warnings about pipeline layouts
   - Demo should still run despite warnings

### Debug Output
Enable verbose logging:
```bash
mvn exec:java -pl render \
  -Dexec.mainClass="com.hellblazer.luciferase.render.webgpu.WebGPUVoxelDemo" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```