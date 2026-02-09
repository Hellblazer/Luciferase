# Render Module

**Last Updated**: 2026-02-08
**Status**: Current
**Documentation**: See [doc/INDEX.md](doc/INDEX.md) for complete documentation index

---

## Overview

The Render module provides GPU-accelerated rendering pipelines for the Luciferase spatial data visualization system. It implements:

- **ESVO (Efficient Sparse Voxel Octrees)** for real-time ray traversal
- **DAG Compression** for 4.56x-15x memory reduction
- **GPU Acceleration** with multi-vendor support (NVIDIA, AMD, Intel, Apple)
- **Multi-resolution LOD** and beam optimization

---

## Quick Start

### Basic Rendering

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

### DAG Compression

```java
// Compress octree with DAG (4.56x-15x memory reduction)
var dag = DAGBuilder.from(svo).build();

System.out.printf("Compression: %.2fx%n", dag.getCompressionRatio());
System.out.printf("Memory saved: %d bytes%n",
    dag.getMetadata().memorySavedBytes());
```

### GPU Acceleration

```java
// Enable GPU acceleration (10x+ speedup)
var renderer = new DAGOpenCLRenderer(width, height);
renderer.setDAG(dag);
renderer.setCamera(camera);

// Execute GPU traversal
var frame = renderer.render();
```

**Full GPU Guide**: See [doc/PHASE_5_GPU_ACCELERATION_COMPLETE_GUIDE.md](doc/PHASE_5_GPU_ACCELERATION_COMPLETE_GUIDE.md)

---

## Key Features

### Memory Optimization
- ✅ **DAG Compression**: 4.56x-15x reduction via hash-based deduplication
- ✅ **Binary Serialization**: Efficient .dag file format
- ✅ **Absolute Addressing**: 13x traversal speedup

### GPU Acceleration
- ✅ **Multi-Vendor Support**: NVIDIA, AMD, Intel, Apple GPUs
- ✅ **Performance Measurement**: Built-in profiling framework
- ✅ **Adaptive Execution**: Automatic beam optimization decisions
- ✅ **Kernel Recompilation**: Vendor-specific build options

### Rendering
- ✅ **ESVO Ray Traversal**: Stack-based GPU traversal
- ✅ **Contour Extraction**: Surface approximation with averaged normals
- ✅ **Beam Optimization**: 2x2 ray packets for cache coherence
- ✅ **Debug Visualization**: Octree structure, ray paths, traversal stats

---

## Documentation

### 📚 Complete Index
**[doc/INDEX.md](doc/INDEX.md)** - Master documentation index with navigation by phase, topic, and role

### 🚀 Quick Guides
- **[DAG Integration Guide](doc/DAG_INTEGRATION_GUIDE.md)** - How to use DAG compression
- **[GPU Acceleration Guide](doc/PHASE_5_GPU_ACCELERATION_COMPLETE_GUIDE.md)** - GPU setup and usage
- **[Multi-Vendor Testing](doc/MULTI_VENDOR_GPU_TESTING_GUIDE.md)** - Testing across GPU vendors

### 📖 API References
- **[DAG API Reference](doc/DAG_API_REFERENCE.md)** - Complete DAG API documentation
- **[Phase 5 Technical Reference](doc/PHASE_5_TECHNICAL_REFERENCE.md)** - GPU acceleration API

### 🎯 By Phase
- **[Phase 2: DAG Compression](doc/PHASE_2_COMPLETION_SUMMARY.md)** - Memory optimization
- **[Phase 3: Serialization](doc/PHASE3_SERIALIZATION_COMPLETION.md)** - Binary format
- **[Phase 5: GPU Acceleration](doc/PHASE_5_DOCUMENTATION_INDEX.md)** - Complete GPU pipeline

---

## Architecture

### Core Components

#### Rendering Pipeline
- **`RenderPipeline`** - Main rendering orchestrator managing frame lifecycle
- **`ESVORenderer`** - Sparse voxel octree GPU renderer
- **`StackTraversal`** - GPU-optimized tree traversal using stack-based algorithms
- **`ContourExtractor`** - Geometric contour extraction from voxel data

#### DAG Compression
- **`DAGBuilder`** - Hash-based deduplication engine
- **`DAGOctreeData`** - Compressed DAG octree representation
- **`DAGSerializer/Deserializer`** - Binary .dag file format I/O
- **`CompressionMetrics`** - Performance tracking and analytics

#### GPU Acceleration
- **`DAGOpenCLRenderer`** - OpenCL-based GPU renderer
- **`GPUPerformanceProfiler`** - Performance measurement framework
- **`BeamOptimizationGate`** - Adaptive beam optimization decisions
- **`GPUVendorDetector`** - Multi-vendor GPU detection and capabilities

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
- **Lock-Free Metrics**: Atomic counters for performance tracking

---

## Testing

### Run All Tests

```bash
# Run all render tests
mvn test -pl render

# With GPU hardware (Tier 2 tests)
RUN_GPU_TESTS=true mvn test -pl render

# Vendor-specific (Tier 3 tests)
GPU_VENDOR=NVIDIA RUN_GPU_TESTS=true mvn test -pl render
```

### Test Categories

- **Unit Tests**: Core algorithm validation (1,303 tests)
- **Integration Tests**: GPU pipeline integration
- **Performance Tests**: Rendering benchmarks
- **Multi-Vendor Tests**: 3-tier testing strategy (34 tests)

**Test Guide**: See [doc/MULTI_VENDOR_GPU_TESTING_GUIDE.md](doc/MULTI_VENDOR_GPU_TESTING_GUIDE.md)

---

## Performance

### Achieved Metrics

| Metric | Target | Achieved | Status |
|--------|--------|----------|--------|
| DAG Compression | 10x+ | 4.56x-15x | ✅ PASS |
| DAG Traversal Speedup | <20% slower | 13x faster | ✅ EXCEEDED |
| GPU Speedup (100K rays) | <5ms | 4.5ms | ✅ EXCEEDED |
| GPU Speedup (1M rays) | <20ms | 18ms | ✅ EXCEEDED |
| Multi-Vendor Consistency | ≥90% | 93% | ✅ PASS |
| Node Reduction (tiling) | ≥30% | Validated | ✅ PASS |

### Optimization Tips

1. Use power-of-2 resolutions for better GPU utilization
2. Enable DAG compression for 4.56x-15x memory savings
3. Use beam optimization for coherent ray workloads
4. Profile with GPUPerformanceProfiler for bottleneck analysis
5. Adjust workgroup size based on GPU vendor

**Performance Guide**: See [doc/GPU_PERFORMANCE_MEASUREMENT_FRAMEWORK.md](doc/GPU_PERFORMANCE_MEASUREMENT_FRAMEWORK.md)

---

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
- Live metrics overlay

### With GPU Test Framework

Uses the framework for GPU testing:

- Shader compilation tests
- Compute kernel validation
- Memory transfer benchmarks

---

## Dependencies

### Core Dependencies
- **resource**: GPU resource management
- **common**: Shared utilities and geometry
- **gpu-test-framework** (test scope): GPU testing infrastructure

### External Dependencies
- **LWJGL**: OpenGL/OpenCL bindings
- **javax.vecmath**: Vector mathematics
- **JMH** (test scope): Performance benchmarking

---

## Configuration

### Render Configuration

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

### DAG Compression

```java
DAGBuilder.from(svo)
    .withHashAlgorithm(HashAlgorithm.SHA256)  // or XXHASH64
    .withCompressionStrategy(CompressionStrategy.BALANCED)
    .withValidation(true)
    .build();
```

### GPU Kernels

```java
// Kernel build options
var options = new KernelBuildOptions()
    .withDAGTraversal(true)
    .withAbsoluteAddressing(true)
    .withMaxDepth(23)
    .withWorkgroupSize(256);
```

### Shader Configuration

Shaders are loaded from `resources/shaders/`:

- Place custom shaders in this directory
- Use `#include` directives for common code
- Shaders are hot-reloaded in debug mode

---

## Troubleshooting

### Common Issues

**Black screen / No output**
- Check OpenGL context creation
- Verify shader compilation (check logs)
- Ensure octree data is loaded

**Poor performance**
- Profile with GPUPerformanceProfiler
- Check GPU memory usage
- Reduce octree depth or resolution
- Enable DAG compression

**Shader compilation errors**
- Enable shader debug logging
- Check GLSL version compatibility
- Verify uniform buffer bindings

**Multi-vendor issues**
- See [Multi-Vendor Testing Guide](doc/MULTI_VENDOR_GPU_TESTING_GUIDE.md)
- Check GPUVendorDetector for vendor-specific workarounds

### Debug Logging

```xml
<logger name="com.hellblazer.luciferase.render" level="DEBUG"/>
<logger name="com.hellblazer.luciferase.render.shader" level="TRACE"/>
```

---

## References

### Academic Papers
- Laine & Karras 2010: "Efficient Sparse Voxel Octrees"
- NVIDIA CUDA ESVO Sample Code

### Technical Specifications
- OpenGL 4.6 Specification
- OpenCL 3.0 Specification
- LWJGL Documentation

### Internal Documentation
- [Complete Documentation Index](doc/INDEX.md)
- [Phase 5 Documentation](doc/PHASE_5_DOCUMENTATION_INDEX.md)
- [Archived Documentation](doc/archive/README.md)

---

## License

Licensed under AGPL v3.0

---

**Module Status**: ✅ Production Ready
**Test Coverage**: 1,303 tests passing
**GPU Vendors**: NVIDIA, AMD, Intel, Apple
**Documentation**: Complete (15 guides, ~7,500 lines)
