# Luciferase

**Last Updated**: 2026-01-12
**Status**: H3 Phase 1 Complete | Phase 7D - Entity Migration Coordination (In Progress)

![Build Status](https://github.com/hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)

3D spatial indexing and visualization library for Java 25.

## Overview

Luciferase is a spatial data structure library providing 3D indexing, collision detection, and visualization capabilities. Built with Java 25 and the Foreign Function & Memory (FFM) API.

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
| [simulation](simulation/README.md) | Distributed simulation with deterministic testing, entity migration, and simulation bubbles |
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
var neighbors = octree.kNearestNeighbors(position, 5, Float.MAX_VALUE);

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

## Testing

The project includes comprehensive testing infrastructure:
- **Unit tests**: 2200+ tests across all modules
- **Integration tests**: Distributed simulation scenarios
- **Performance benchmarks**: JMH-based benchmarking with automated metric extraction
- **Deterministic testing**: Clock interface injection for reproducible time-dependent tests

### Deterministic Testing

The simulation module supports deterministic testing through injectable Clock abstraction, enabling:
- **Reproducible scenarios**: Control time progression explicitly in tests
- **Time-travel debugging**: Set arbitrary time points for test scenarios
- **Controllable advancement**: Eliminate timing-dependent flakiness
- **Consistent CI results**: Remove non-deterministic timing dependencies

**Progress**: H3 Determinism Epic - Phase 1 complete (36/113 calls converted, 31.9%)

See [simulation/doc/H3_DETERMINISM_EPIC.md](simulation/doc/H3_DETERMINISM_EPIC.md) for architecture details and [simulation/doc/H3.7_PHASE1_COMPLETION.md](simulation/doc/H3.7_PHASE1_COMPLETION.md) for implementation progress.

## Development Status

### H3 Determinism Epic (Phase 1 Complete)

**Objective**: Enable deterministic, reproducible testing by eliminating non-deterministic time dependencies.

**Completed**: Phase 1 (8 files, 36 calls, 31.9%)
- ✅ Clock interface with TestClock implementation
- ✅ VonMessageFactory pattern for record classes
- ✅ Critical files converted (FakeNetworkChannel, GhostStateManager, EntityMigrationStateMachine, CrossProcessMigration, etc.)
- ✅ Flaky test handling with @DisabledIfEnvironmentVariable pattern

**Remaining**: Phases 2-4 (46 files, 77 calls, 68.1%)

### Phase 7D: Entity Migration Coordination (Ongoing)

**Objective**: Implement comprehensive entity migration coordination for distributed multi-bubble simulations with reliability guarantees and timeout-based recovery.

**Completed Milestones** (74+ tests, 8.8/10 avg quality):
- ✅ **Phase 7D Days 1-3** (R1-R3 blockers)
  - Day 1: MigrationStateListener FSM notification pattern (10 tests, 9/10 quality)
  - Day 2: MigrationCoordinator FSM/2PC bridge (10 tests, 8/10 quality)
  - Day 3: EventReprocessor gap detection with 30s timeout (12 tests, 9/10 quality)

- ✅ **Phase 7D.1 Parts 1-2** (Timeout Infrastructure)
  - Part 1 (Day 4): MigrationContext time tracking + Configuration timeouts (17 tests, 9/10 quality)
  - Part 2 (Day 5): Timeout detection & rollback processing (17 tests, 9.0/10 quality)

**In Progress**:
- Phase 7D.1 Part 3 (Day 6): RealTimeController integration + view stability

**Upcoming**:
- Phase 7D.2-7D.4: Ghost physics, view stability optimization, E2E testing (40+ tests)

See [PHASE_7D_DAY_BY_DAY_IMPLEMENTATION.md](simulation/doc/PHASE_7D_DAY_BY_DAY_IMPLEMENTATION.md) for detailed implementation plan and technical specifications.

## Documentation

### Architecture
- [High-Level Architecture](ARCHITECTURE.md) - System overview and design principles
- [Lucien Architecture](lucien/doc/LUCIEN_ARCHITECTURE.md) - Spatial indexing details
- [Simulation Architecture](simulation/doc/CONSOLIDATION_MASTER_OVERVIEW.md) - Distributed simulation design
- [H3 Determinism Epic](simulation/doc/H3_DETERMINISM_EPIC.md) - Deterministic testing architecture

### Development
- [Contributing Guidelines](CONTRIBUTING.md) - How to contribute
- [CLAUDE.md](CLAUDE.md) - Claude Code development guide
- [Performance Metrics](lucien/doc/PERFORMANCE_METRICS_MASTER.md) - Benchmarking results

### Current Work
- [Phase 7D Implementation Plan](simulation/doc/PHASE_7D_DAY_BY_DAY_IMPLEMENTATION.md) - Entity Migration Coordination
- [H3.7 Phase 1 Completion](simulation/doc/H3.7_PHASE1_COMPLETION.md) - Determinism progress

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines including:
- Code style and conventions
- Testing requirements (including deterministic time handling)
- Performance testing procedures
- Pull request process
- Architecture decision documentation

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
