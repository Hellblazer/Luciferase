# Luciferase

![Build Status](https://github.com/hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)

A high-performance 3D spatial indexing and visualization library for Java 24+

## Overview

Luciferase is a comprehensive spatial data structure library providing efficient 3D indexing, collision detection, and visualization capabilities. Built with Java 24's cutting-edge features including the Foreign Function & Memory (FFM) API, it offers both CPU and GPU-accelerated spatial operations.

## Features

- **Dual Spatial Indexing Systems**
  - **Octree**: Cubic spatial subdivision using Morton space-filling curves (21 levels, ~2 billion nodes)
  - **Tetree**: Tetrahedral spatial subdivision using TM-index curves (21 levels, matching Octree capacity)
  
- **Advanced Capabilities**
  - Multi-entity support per spatial location
  - Thread-safe concurrent operations
  - K-nearest neighbor search
  - Ray intersection and frustum culling
  - Collision detection with physics shapes
  - Adaptive tree balancing strategies
  
- **Performance Optimizations**
  - Zero-copy GPU memory transfers via FFM
  - Lock-free entity movement protocols
  - Object pooling for GC reduction
  - SIMD operations support
  
- **Visualization & Rendering**
  - JavaFX 3D visualization
  - WebGPU integration for GPU compute
  - ESVO (Efficient Sparse Voxel Octrees) rendering pipeline
  - Real-time mesh generation

## Architecture

### Core Modules

| Module | Description |
|--------|-------------|
| **lucien** | Core spatial indexing implementation (Octree, Tetree, collision detection) |
| **render** | WebGPU-based voxel rendering with FFM integration |
| **sentry** | Delaunay tetrahedralization for kinetic point tracking |
| **portal** | JavaFX 3D visualization and mesh handling |
| **common** | Optimized collections and geometry utilities |
| **von** | Distributed spatial perception framework |
| **simulation** | Animation and movement simulation |
| **grpc** | Protocol buffer definitions for serialization |

## Requirements

- **Java 24+** (uses stable FFM API)
- **Maven 3.91+**
- **JavaFX 24** (for visualization)
- **Optional**: WebGPU runtime for GPU acceleration

## Build Instructions

```bash
# Clone the repository
git clone https://github.com/Hellblazer/Luciferase.git
cd Luciferase

# Build with Maven wrapper
./mvnw clean install

# Run tests
./mvnw test

# Run benchmarks (optional)
./mvnw test -Pperformance
```

## Quick Start

### Basic Octree Usage

```java
import com.hellblazer.luciferase.lucien.grid.Octree;

// Create an octree with bounds
var octree = new Octree(
    new Point3f(-100, -100, -100),  // min bounds
    new Point3f(100, 100, 100),     // max bounds
    10                               // max depth
);

// Insert an entity
var entityId = UUID.randomUUID();
var position = new Point3f(10, 20, 30);
octree.insert(entityId, position);

// Find nearest neighbors
var neighbors = octree.findKNearestNeighbors(position, 5);

// Perform ray intersection
var ray = new Ray3f(origin, direction);
var hits = octree.intersectRay(ray);
```

### WebGPU Voxel Rendering

```java
import com.hellblazer.luciferase.render.voxel.gpu.VoxelGPUManager;

// Initialize GPU manager
var gpuManager = new VoxelGPUManager(webGPUDevice);

// Upload voxel octree to GPU
var nodeCount = gpuManager.uploadOctree(voxelRoot);

// Render frame
gpuManager.render(camera, renderTarget);
```

## Performance

Benchmark results on Apple M1 (10,000 entities):

| Operation | Octree | Tetree |
|-----------|--------|--------|
| Insertion | 285K/sec | 541K/sec |
| k-NN Query | 5.5K/sec | 3.1K/sec |
| Ray Intersection | 26K/sec | 15K/sec |
| Update | 189K/sec | 364K/sec |

## Documentation

- [Architecture Overview](lucien/doc/LUCIEN_ARCHITECTURE.md)
- [Performance Metrics](lucien/doc/PERFORMANCE_METRICS_MASTER.md)
- [Java 24 FFM Integration](render/doc/JAVA_24_FFM_PLAN.md)
- [API Documentation](https://hellblazer.github.io/Luciferase/) (Javadoc)

## Contributing

Contributions are welcome! Please read our [contributing guidelines](CONTRIBUTING.md) before submitting PRs.

## License

This project is licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0).

See [LICENSE](LICENSE) for details.

## Acknowledgments

- Uses [webgpu-java](https://github.com/myworldvw/webgpu-java) for WebGPU bindings
- Inspired by [t8code](https://github.com/DLR-AMR/t8code) for tetrahedral indexing
- Built with [PrimeMover](https://github.com/Hellblazer/PrimeMover) simulation framework

## Contact

- **Author**: Hal Hildebrand
- **Email**: hal.hildebrand@gmail.com
- **GitHub**: [@Hellblazer](https://github.com/Hellblazer)
