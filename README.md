# Luciferase

![Build Status](https://github.com/hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)

3D spatial indexing and visualization library for Java 24.

## Overview

Luciferase is a spatial data structure library providing 3D indexing, collision detection, and visualization capabilities. Built with Java 24 and the Foreign Function & Memory (FFM) API.

## Features

- Spatial Indexing Systems
  - Octree: Cubic spatial subdivision using Morton space-filling curves (21 levels, 2 billion nodes)
  - Tetree: Tetrahedral spatial subdivision using TM-index curves (21 levels, matching Octree capacity)
  
- Capabilities
  - Multi-entity support per spatial location
  - Thread-safe concurrent operations
  - K-nearest neighbor search
  - Ray intersection and frustum culling
  - Collision detection with physics shapes
  - Adaptive tree balancing strategies
  
- Performance Optimizations
  - Memory-efficient data structures via FFM
  - Lock-free entity movement protocols
  - Object pooling for GC reduction
  - SIMD operations support
  
- Visualization & Rendering
  - JavaFX 3D visualization
  - LWJGL-based OpenGL rendering
  - ESVO (Efficient Sparse Voxel Octrees) core algorithms complete (Laine & Karras 2010 reference)
  - Stack-based ray traversal optimized for GPU architectures
  - Mesh generation and contour extraction

## Architecture

### Core Modules

| Module | Description |
|--------|-------------|
| [common](common/README.md) | Collections and geometry utilities |
| [resource](resource/README.md) | Shared resources, shaders, and configuration files |
| [lucien](lucien/README.md) | Core spatial indexing implementation (Octree, Tetree, collision detection) |
| [render](render/README.md) | ESVO implementation with LWJGL rendering, FFM integration |
| [gpu-test-framework](gpu-test-framework/README.md) | GPU testing infrastructure and benchmarking utilities |
| [sentry](sentry/README.md) | Delaunay tetrahedralization for kinetic point tracking |
| [portal](portal/README.md) | JavaFX 3D visualization and mesh handling |
| [von](von/README.md) | Distributed spatial perception framework |
| [simulation](simulation/README.md) | Animation and movement simulation |
| [grpc](grpc/README.md) | Protocol buffer definitions for serialization |

## Requirements

- Java 24 (uses stable FFM API)
- Maven 3.91+
- JavaFX 24 (for visualization)
- LWJGL 3 (for OpenGL rendering)

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

### ESVO Rendering

```java
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;

// Create ESVO octree with 8-byte nodes
var octreeData = new ESVOOctreeData(maxSizeBytes);
var root = new ESVONodeUnified(childDescriptor, contourDescriptor);
octreeData.setNode(0, root);

// Stack-based ray traversal (optimized for GPU architectures)
var traversal = new StackBasedRayTraversal(octreeData);
var intersections = traversal.traverse(ray);

// Serialize for efficient memory transfer
var gpuBuffer = ESVOSerializer.serialize(octreeData);
```

## Performance

> **Note**: Performance benchmarks should be run on your specific hardware for accurate results.
> 
> To run benchmarks on your system:
> ```bash
> mvn test -Pperformance
> # or for specific benchmarks:
> mvn test -pl lucien -Dtest=OctreeVsTetreeVsPrismBenchmark
> ```

Expected benchmark categories (with 10,000 entities):

| Operation | Description |
|-----------|-------------|
| Insertion | Entity insertion operations/sec |
| k-NN Query | k-nearest neighbor searches/sec |
| Ray Intersection | Ray-tree intersection tests/sec |
| Update | Entity position updates/sec |

## Documentation

- [Architecture Overview](lucien/doc/LUCIEN_ARCHITECTURE.md)
- [Performance Metrics](lucien/doc/PERFORMANCE_METRICS_MASTER.md)
- [ESVO Implementation](render/doc/ESVO_COMPLETION_SUMMARY.md)
- [Java 24 FFM Integration](render/doc/JAVA_24_FFM_PLAN.md)
- [API Documentation](https://hellblazer.github.io/Luciferase/) (Javadoc)

## Contributing

Contributions are welcome. Please read our [contributing guidelines](CONTRIBUTING.md) before submitting PRs.

## License

This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).

See [LICENSE](LICENSE) for details.

## Acknowledgments

- ESVO implementation based on Laine & Karras 2010 paper "Efficient Sparse Voxel Octrees"
- Inspired by [t8code](https://github.com/DLR-AMR/t8code) for tetrahedral indexing
- Built with [PrimeMover](https://github.com/Hellblazer/PrimeMover) simulation framework

## Contact

- **Author**: Hal Hildebrand
- **Email**: hal.hildebrand@gmail.com
- **GitHub**: [@Hellblazer](https://github.com/Hellblazer)
