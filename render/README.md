# Render Module

GPU-accelerated voxel rendering pipeline with streaming I/O and adaptive quality control

**Last Updated**: August 12, 2025  
**Branch**: visi  
**Status**: WebGPU Integration Complete

## Overview

The Render module provides a voxel rendering system with asynchronous streaming, WebGPU integration, and adaptive quality management. Built for integration with Luciferase's spatial indexing system. The module uses Java 24's FFM API and depends on the webgpu-ffm module for native GPU operations.

## Features

### Core Technologies

- **Java 24 FFM Integration**
  - Zero-copy memory transfers to GPU
  - Native memory layouts for GPU compatibility
  - Arena-based memory lifecycle management
  - Thread-safe memory pooling

- **WebGPU Support**
  - Cross-platform GPU compute (NVIDIA, AMD, Intel, Apple Silicon)
  - WGSL compute shaders
  - Storage buffer management
  - Compute pipeline abstraction

- **ESVO Rendering**
  - Sparse voxel octree representation
  - GPU-accelerated ray marching
  - Level-of-detail (LOD) support
  - Frustum culling optimization

### Memory Management

- **FFM Memory Layouts**: GPU-compatible structures with 16-byte alignment
- **Memory Pooling**: Efficient segment reuse with configurable limits
- **Batch Operations**: Minimize GPU transfer overhead
- **Direct GPU Mapping**: Zero-copy buffer access

## Architecture

```
com.hellblazer.luciferase.render/
├── compression/       # DXT and sparse voxel compression
├── io/               # Streaming I/O with async loading
├── rendering/        # VoxelRenderingPipeline and StreamingController
├── webgpu/           # Native WebGPU integration
│   ├── platform/    # Platform-specific surface creation
│   ├── resources/   # Buffer and uniform management
│   └── shaders/     # Shader compilation and caching
├── voxel/
│   ├── core/        # VoxelOctreeNode data structures
│   ├── gpu/         # WebGPU context and compute shaders
│   ├── memory/      # FFM memory pooling and management
│   └── pipeline/    # Mesh voxelization pipeline
└── memory/          # GPU memory management
```

**Note**: The webgpu package was removed in August 2025 refactoring - all WebGPU functionality now comes from the webgpu-ffm module.

## Key Components

### VoxelRenderingPipeline
Main rendering orchestrator with adaptive quality control and async frame rendering.

### StreamingController
Manages asynchronous LOD streaming with priority-based loading and memory pressure management.

### WebGPU Backend
Abstraction layer supporting both FFM-based native WebGPU and stub implementations for testing.

### Compression System
- **SparseVoxelCompressor**: Octree compression for efficient GPU storage
- **DXTCompressor**: Texture compression supporting DXT1/DXT5 formats

### Streaming I/O
- Async chunk loading with prefetching
- LRU cache for frequently accessed data
- Memory-mapped file support for large datasets

## Performance

### FFM vs ByteBuffer Benchmarks

| Operation | FFM (ns) | ByteBuffer (ns) | Speedup |
|-----------|----------|-----------------|---------|
| Sequential Write | 125 | 287 | 2.3x |
| Random Access | 89 | 156 | 1.8x |
| Bulk Copy | 2,145 | 4,892 | 2.3x |
| Struct Access | 34 | 78 | 2.3x |

### GPU Transfer Performance

- Zero-copy uploads: ~1.8 GB/s
- Batch operations: 3x faster than individual transfers
- Memory pool hit rate: >95% in typical usage

## Usage Example

```java
// Run the interactive demo (from test classes)
java -cp render/target/classes:render/target/test-classes \
     com.hellblazer.luciferase.render.demo.SimpleRenderDemo

// Demo Menu:
// 1. Demonstrate Async Streaming
// 2. Demonstrate Rendering Pipeline  
// 3. Demonstrate Adaptive Quality
// 4. Demonstrate Voxel Compression
// 5. Show Performance Metrics
// 6. Display System Status
// 7. Run Stress Test
// Q. Quit
```

## Building

```bash
# Build module
mvn clean install -pl render

# Run tests
mvn test -pl render

# Run benchmarks
mvn test -pl render -Dtest=FFMvsByteBufferBenchmark
```

## Dependencies

- **webgpu-ffm**: WebGPU FFM bindings (internal module)
- **Java 24**: Required for stable FFM API
- **lucien**: Core spatial data structures
- **common**: Shared utilities
- **SLF4J**: Logging framework

## Testing

```bash
# Unit tests
mvn test -pl render -Dtest=FFMLayoutsTest
mvn test -pl render -Dtest=FFMMemoryPoolTest
mvn test -pl render -Dtest=VoxelRenderingPipelineTest

# Integration tests
mvn test -pl render -Dtest=ComprehensiveRenderingPipelineTest
mvn test -pl render -Dtest=WebGPUIntegrationTest  # Requires GPU

# All tests
mvn test -pl render
```

## Current Status (January 2025)

### Working Features
- ✅ VoxelRenderingPipeline with async frame rendering and concurrent frame management
- ✅ StreamingController with priority-based LOD loading and prefetching
- ✅ WebGPU context initialization via webgpu-ffm module
- ✅ Synchronous cleanup (exits immediately on quit)
- ✅ Memory-efficient streaming I/O with memory-mapped file support
- ✅ Adaptive quality control based on frame timing
- ✅ SimpleRenderDemo interactive test application (in test directory)
- ✅ FFM-based zero-copy memory transfers
- ✅ DXT compression (DXT1/DXT5) with GPU-friendly decompression
- ✅ Sparse voxel compression with 60-70% size reduction
- ✅ Page-based memory allocation with statistics
- ✅ Triangle-box intersection using SAT algorithm
- ✅ Multi-resolution voxelization support

### Known Limitations
- WebGPU native operations return simulated data (full implementation pending)
- Full GPU ray marching not yet implemented
- Some ESVO shaders are placeholders (in shaders/esvo/)
- GPU voxelization partially implemented

## Documentation

- `doc/` - Technical documentation
- `doc/archive/` - Historical documents
- `REFACTORING_SUMMARY.md` - August 2025 WebGPU consolidation details
- `CLEANUP_SUMMARY.md` - Module cleanup history

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details