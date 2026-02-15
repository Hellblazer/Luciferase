# Render Module

**Last Updated**: 2026-01-04
**Status**: Current

## Overview

The Render module provides GPU-accelerated rendering pipelines for the Luciferase spatial data visualization system. It implements ESVO (Efficient Sparse Voxel Octrees) for real-time ray traversal and voxel rendering using LWJGL's OpenGL and compute shader capabilities.

## Key Components

### Core Rendering Pipeline

- **`RenderPipeline`** - Main rendering orchestrator managing frame lifecycle
- **`ESVORenderer`** - Sparse voxel octree GPU renderer
- **`StackTraversal`** - GPU-optimized tree traversal using stack-based algorithms
- **`ContourExtractor`** - Geometric contour extraction from voxel data

### ESVO Implementation

The module implements the Laine & Karras 2010 ESVO algorithm:

- **Sparse Indexing**: 8-byte node structure with child and contour descriptors
- **Stack-Based Traversal**: 23-level stack for efficient GPU ray traversal
- **Beam Optimization**: 2x2 ray packets for cache coherence
- **Contour Support**: Geometric approximation using averaged normals

### Shader Systems

#### Compute Shaders

- `esvo_traverse.comp` - Ray-octree intersection kernel
- `stack_compact.comp` - Stack memory compaction
- `contour_gen.comp` - Contour generation from voxel data

#### Vertex/Fragment Shaders

- `voxel.vert/frag` - Voxel cube rendering
- `ray_debug.vert/frag` - Ray visualization for debugging
- `wireframe.vert/frag` - Octree structure visualization

## Architecture

### Memory Management

The render module integrates with the resource module for GPU memory management:

```java

// Automatic GPU buffer management
var bufferManager = new GPUBufferManager(resourceManager);
var nodeBuffer = bufferManager.allocateNodeBuffer(nodeCount);
var rayBuffer = bufferManager.allocateRayBuffer(rayCount);

```

### Thread Safety

- **Command Buffer Recording**: Thread-local command buffers
- **Resource Synchronization**: Fence-based GPU synchronization
- **Double Buffering**: Prevents pipeline stalls

## Features

### Current Implementation

- ✅ ESVO sparse voxel octree rendering
- ✅ Stack-based GPU ray traversal
- ✅ Contour-based surface approximation
- ✅ Multi-resolution level-of-detail
- ✅ Debug visualization tools

### In Progress

- ⚠️ Unified node structure (see ARCHITECTURAL_GUARDRAILS.md)
- ⚠️ Full CUDA reference compliance
- ⚠️ Beam optimization implementation

### Future Enhancements

- WebGPU backend support
- Vulkan rendering pipeline
- Distributed GPU rendering
- Real-time voxelization

## Usage

### Basic Rendering Setup

```java

// Initialize render pipeline
var config = RenderConfig.builder()
    .resolution(1920, 1080)
    .maxOctreeDepth(10)
    .enableContours(true)
    .build();

var pipeline = new RenderPipeline(config);

// Load octree data
var octree = OctreeLoader.load("model.octree");
pipeline.setOctree(octree);

// Render frame
pipeline.renderFrame(camera, lights);

```

### ESVO Ray Traversal

```java

// Setup ESVO traversal
var esvo = new ESVORenderer(resourceManager);
esvo.setOctree(octreeData);
esvo.setCamera(camera);

// Execute GPU traversal
var intersections = esvo.traverse(rays);

```

### Debug Visualization

```java

// Enable debug overlays
pipeline.setDebugMode(DebugMode.SHOW_OCTREE_STRUCTURE);
pipeline.setDebugMode(DebugMode.SHOW_RAY_PATHS);
pipeline.setDebugMode(DebugMode.SHOW_TRAVERSAL_STATS);

```

## Testing

### Unit Tests

```bash

# Run all render tests

mvn test -pl render

# Run specific test suites

mvn test -pl render -Dtest=ESVOTraversalTest
mvn test -pl render -Dtest=ContourExtractionTest

```

### Performance Tests

```bash

# Run performance benchmarks

mvn test -pl render -Dtest=RenderBenchmark

# GPU performance profiling

mvn test -pl render -Dtest=GPUProfileTest -Pgpu-profile

```

### Validation Tests

The module includes architectural validation tests that ensure compliance with the CUDA reference implementation. These tests are **intentionally failing** until architectural issues are resolved. See `src/test/java/com/hellblazer/luciferase/esvo/validation/README_ARCHITECTURAL_GUARDRAILS.md` for details.

## Integration

### With Lucien Module

The render module visualizes spatial data structures from lucien:

- Octree visualization
- Tetree rendering
- Collision shape display
- Spatial query visualization

### With Portal Module

Provides rendering backend for JavaFX 3D visualization:

- Offscreen rendering to JavaFX images
- Interactive camera controls
- Real-time updates

### With GPU Test Framework

Uses the framework for GPU testing:

- Shader compilation tests
- Compute kernel validation
- Memory transfer benchmarks

## Performance

### Benchmarks

> **Note**: Performance metrics need to be updated with actual measurements from current hardware.
>
> To run benchmarks on your system:
> ```bash
> mvn test -pl render -Dtest=ESVOPerformanceBenchmark
> mvn test -pl render -Dtest=ESVOJMHBenchmark -Pperformance
> ```

Expected metrics to measure:

- **Ray Throughput**: Grays/sec
- **Node Throughput**: Nodes/sec
- **Frame Time**: ms @ various resolutions
- **Memory Usage**: MB for various node counts

### Optimization Tips

1. Use power-of-2 resolutions for better GPU utilization
2. Enable contour caching for static scenes
3. Adjust stack size based on octree depth
4. Use beam optimization for coherent rays

## GPU Testing Framework

The render module uses the `gpu-test-framework` module for GPU-related testing:

```xml

<dependency>
    <groupId>com.hellblazer.luciferase</groupId>
    <artifactId>gpu-test-framework</artifactId>
    <scope>test</scope>
</dependency>

```

### Running Tests

```bash

# Run all render tests

mvn test -pl render

# Run with GPU profiling

mvn test -pl render -Dgpu.profile=true

# Run specific test class

mvn test -pl render -Dtest=ESVORendererTest

```

### Test Categories

- **Unit Tests**: Core algorithm validation
- **Integration Tests**: GPU pipeline integration
- **Performance Tests**: Rendering benchmarks using gpu-test-framework
- **Visual Tests**: Render output validation

## Dependencies

### Core Dependencies

- **resource**: GPU resource management
- **common**: Shared utilities and geometry
- **gpu-test-framework** (test scope): GPU testing infrastructure

### External Dependencies

- **LWJGL**: OpenGL/OpenCL bindings
- **javax.vecmath**: Vector mathematics
- **JMH** (test scope): Performance benchmarking

## Configuration

### Render Configuration Options

```java

RenderConfig.builder()
    .resolution(width, height)
    .maxOctreeDepth(23)          // CUDA reference: 23 levels
    .stackSize(64)               // Per-ray stack allocation
    .enableContours(true)        // Surface approximation
    .enableBeamOptimization(true) // 2x2 ray packets
    .shadowRays(4)               // Soft shadows
    .ambientOcclusion(true)      // Screen-space AO
    .build();

```

### Shader Configuration

Shaders are loaded from `resources/shaders/`:

- Place custom shaders in this directory
- Use `#include` directives for common code
- Shaders are hot-reloaded in debug mode

## Troubleshooting

### Common Issues

**Black screen / No output**
- Check OpenGL context creation
- Verify shader compilation (check logs)
- Ensure octree data is loaded

**Poor performance**
- Profile with `GPUProfileTest`
- Check GPU memory usage
- Reduce octree depth or resolution

**Shader compilation errors**
- Enable shader debug logging
- Check GLSL version compatibility
- Verify uniform buffer bindings

### Debug Logging

```xml

<logger name="com.hellblazer.luciferase.render" level="DEBUG"/>
<logger name="com.hellblazer.luciferase.render.shader" level="TRACE"/>

```

## References

- Laine & Karras 2010: "Efficient Sparse Voxel Octrees"
- NVIDIA CUDA ESVO Sample Code
- OpenGL 4.6 Specification
- LWJGL Documentation

## License

Licensed under AGPL v3.0
