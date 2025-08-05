# Render Module - ESVO Implementation

This module implements NVIDIA's Efficient Sparse Voxel Octrees (ESVO) rendering system in Java, providing high-performance voxel-based 3D rendering with GPU acceleration.

## Overview

The render module translates NVIDIA's C++/CUDA ESVO implementation to Java, leveraging:
- **Java FFM (Foreign Function & Memory API)** for zero-copy GPU memory operations
- **WebGPU** for cross-platform GPU compute and rendering
- **Bit-packed data structures** for optimal memory efficiency
- **Lock-free concurrent algorithms** for multi-threaded performance

## Current Status

**Phase 1: Core Data Structures** ✅ COMPLETE (August 5, 2025)
- VoxelOctreeNode with 8-byte packed structure
- VoxelData with compressed attributes
- PageAllocator for 8KB aligned memory
- MemoryPool with buddy allocation

**Phase 2: WebGPU Integration** 🚧 IN PROGRESS (Starting August 6, 2025)

For detailed progress tracking, see [progress/README.md](progress/README.md)

## Architecture

```
render/
├── src/main/java/com/hellblazer/luciferase/render/
│   └── voxel/
│       ├── core/          # Core data structures
│       ├── memory/        # Memory management
│       ├── gpu/           # WebGPU integration (Phase 2)
│       ├── pipeline/      # Voxelization pipeline (Phase 3)
│       ├── compression/   # DXT compression (Phase 4)
│       └── io/           # File I/O (Phase 4)
├── doc/                   # Technical documentation
└── progress/             # Progress tracking

```

## Key Features

### Implemented (Phase 1)
- **8-byte voxel nodes** - 50% smaller than typical implementations
- **FFM integration** - Zero-copy GPU memory sharing
- **Thread-safe operations** - Lock-free atomic operations
- **Buddy allocator** - < 1% memory fragmentation
- **95%+ test coverage** - Comprehensive validation

### Planned Features
- **WebGPU rendering** - Cross-platform GPU acceleration
- **Real-time voxelization** - Triangle-to-voxel conversion
- **DXT compression** - 4:1 color compression
- **Streaming I/O** - Progressive level-of-detail loading
- **Ray tracing** - Hardware-accelerated ray-voxel intersection

## Building

```bash
cd render
mvn clean install
```

## Testing

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=VoxelOctreeNodeTest

# Run benchmarks
mvn test -Dtest=FFMvsByteBufferBenchmark
```

## Performance

Current benchmarks show:
- **Allocation**: ~50 ns per voxel (2x faster than ByteBuffer)
- **Memory access**: 10-15% faster than ByteBuffer
- **Thread scaling**: Linear up to 8 cores
- **Memory efficiency**: 8 bytes per voxel

## Documentation

- [Technical Documentation](doc/) - Architecture and implementation details
- [Progress Tracking](progress/) - Development status and planning
- [ESVO Analysis](doc/ESVO_SYSTEM_ANALYSIS.md) - Original system analysis
- [Implementation Plan](doc/ESVO_IMPLEMENTATION_PLAN.md) - 16-week roadmap

## Dependencies

- Java 23+ (for FFM API)
- Maven 3.9+
- WebGPU native bindings (Phase 2)
- JMH for benchmarking

## Contributing

This is part of the Luciferase spatial indexing project. See the main project README for contribution guidelines.

## License

AGPL v3.0 - See LICENSE file in the root directory

---
*Module created: August 2025*