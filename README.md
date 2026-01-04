# Luciferase

**Last Updated**: 2026-01-04
**Status**: Current

![Build Status](https://github.com/hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)

3D spatial indexing and visualization library for Java 25.

## Overview

Luciferase is a spatial data structure library providing 3D indexing, collision detection, and visualization capabilities. Built with Java 24 and the Foreign Function & Memory (FFM) API.

## Features

- Spatial Indexing Systems
  - Octree: Cubic spatial subdivision using Morton space-filling curves (21 levels, 2 billion nodes)
  - Tetree: Tetrahedral spatial subdivision using TM-index curves (21 levels, matching Octree capacity)
  - SFCArrayIndex: Flat Morton-sorted array for memory-efficient static datasets (fastest inserts)
  - Prism: Anisotropic subdivision with triangular/linear elements (terrain, stratified data)
  
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
  - LITMAX/BIGMIN algorithm for efficient SFC range queries
  
- Visualization & Rendering
  - JavaFX 3D visualization
  - LWJGL-based OpenGL rendering
  - ESVO (Efficient Sparse Voxel Octrees) core algorithms complete (Laine & Karras 2010 reference)
  - Stack-based ray traversal optimized for GPU architectures
  - Mesh generation and contour extraction

## Architecture

### Core Modules

| Module | Description |
| -------- | ------------- |
| [common](common/README.md) | Collections and geometry utilities |
| [lucien](lucien/README.md) | Core spatial indexing implementation (Octree, Tetree, collision detection) |
| [render](render/README.md) | ESVO implementation with LWJGL rendering, FFM integration |
| [sentry](sentry/README.md) | Delaunay tetrahedralization for kinetic point tracking |
| [portal](portal/README.md) | JavaFX 3D visualization and mesh handling |
| [von](von/README.md) | Voronoi-based area-of-interest perception framework |
| [simulation](simulation/README.md) | Distributed simulation with adaptive volume sharding and simulation bubbles |
| [grpc](grpc/README.md) | Protocol buffer definitions for ghost layer synchronization |

### External Dependencies

| Module | Source | Description |
| -------- | -------- | ------------- |
| **resource** | [gpu-support](https://github.com/Hellblazer/gpu-support) | Shared resources, shaders, and configuration files |
| **gpu-test-framework** | [gpu-support](https://github.com/Hellblazer/gpu-support) | GPU testing infrastructure and benchmarking utilities |

## Requirements

- Java 25 (uses stable FFM API)
- Maven 3.9.1+
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
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.Ray3D;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

// Create an octree with custom configuration
var idGenerator = new SequentialLongIDGenerator();
var octree = new Octree<LongEntityID, String>(
    idGenerator,
    10,      // maxEntitiesPerNode
    (byte)10 // maxDepth
);

// Insert an entity
var position = new Point3f(10, 20, 30);
octree.insert(position, (byte)5, "My Entity");

// Find nearest neighbors
var neighbors = octree.findKNearestNeighbors(position, 5, Float.MAX_VALUE);

// Perform ray intersection
var ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
var hits = octree.rayIntersectAll(ray);
```

### ESVO Rendering

```java
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.io.ESVOSerializer;
import java.nio.file.Path;

// Create ESVO octree with 8-byte nodes
var octreeData = new ESVOOctreeData(maxSizeBytes);
var root = new ESVONodeUnified(childDescriptor, contourDescriptor);
octreeData.setNode(0, root);

// Serialize to file for GPU usage
try (var serializer = new ESVOSerializer()) {
    serializer.serialize(octreeData, Path.of("octree.esvo"));
}
```

## Performance

> **Note**: Performance benchmarks should be run on your specific hardware for accurate results.
>
> To run benchmarks on your system:
>
> ```bash
> mvn test -Pperformance
> # or for specific benchmarks:
> mvn test -pl lucien -Dtest=OctreeVsTetreeVsPrismBenchmark
> ```

Expected benchmark categories (with 10,000 entities):

| Operation | Description |
| ----------- | ------------- |
| Insertion | Entity insertion operations/sec |
| k-NN Query | k-nearest neighbor searches/sec |
| Ray Intersection | Ray-tree intersection tests/sec |
| Update | Entity position updates/sec |

## Documentation

- [Architecture Overview](lucien/doc/LUCIEN_ARCHITECTURE.md)
- [Performance Metrics](lucien/doc/PERFORMANCE_METRICS_MASTER.md)

## Contributing

Contributions are welcome. Please follow the project's code style and testing standards when submitting PRs.

## License

This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).

See [LICENSE](LICENSE) for details.

## Acknowledgments

- ESVO implementation based on Laine & Karras 2010 paper "Efficient Sparse Voxel Octrees"
- Inspired by [t8code](https://github.com/DLR-AMR/t8code) for tetrahedral indexing

## Contact

- **Author**: Hal Hildebrand
- **Email**: hal.hildebrand@gmail.com
- **GitHub**: [@Hellblazer](https://github.com/Hellblazer)
