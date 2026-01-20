# Luciferase

**Last Updated**: 2026-01-20
**Status**: Post-Stabilization: Feature Development Phase | Health 8/10 ✅ | 2,365 Tests All Passing ✅

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

- Dynamic Topology (Phase 9) 🆕
  - Automatic bubble splitting when entity density exceeds 5000/bubble
  - Automatic bubble merging when entity density falls below 500/bubble
  - Boundary adaptation following entity cluster movement
  - Byzantine-fault-tolerant consensus for topology changes
  - 100% entity retention across all topology operations
  - Operational metrics with Prometheus-compatible naming
  - See [Dynamic Topology Documentation](simulation/src/test/java/com/hellblazer/luciferase/simulation/topology/README.md)

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

Performance has been comprehensively validated and documented (see [PERFORMANCE_CONSOLIDATION_REPORT.md](lucien/doc/PERFORMANCE_CONSOLIDATION_REPORT.md)):

**Key Results**:
- **Tetree**: 1.9x-6.2x faster for insertions vs Octree
- **Octree**: 3.2x-8.3x faster for range queries
- **k-NN Cache**: 50-102x speedup (validated with 18.1M test queries)
- **SFCArrayIndex**: 2-3x faster for small datasets (<10K entities)
- **Tetree Dominance**: Best performance at 50K+ entity scale

**Running Benchmarks**:

```bash
# Run all performance benchmarks
mvn test -Pperformance

# Run specific spatial index comparison
mvn test -pl lucien -Dtest=OctreeVsTetreeVsPrismBenchmark

# Run with verbose output
VERBOSE_TESTS=1 mvn test -Pperformance
```

**Performance Thresholds** (full test suite under system load):
- **ForestConcurrencyTest**: 45-second timeout (5 threads, 30 ops/thread)
- **MultiBubbleLoadTest**: P99 <50ms (under CI contention)
- **VolumeAnimatorGhostTest**: 150% overhead (temporary, optimization planned)

See [PERFORMANCE_METRICS_MASTER.md](lucien/doc/PERFORMANCE_METRICS_MASTER.md) for comprehensive benchmarking methodology and [TEST_FRAMEWORK_GUIDE.md](TEST_FRAMEWORK_GUIDE.md) for test configuration details.

## Testing

Comprehensive testing infrastructure with **2,365 all tests passing** ✅:
- **Unit Tests**: 2,200+ tests across all modules
- **Integration Tests**: Distributed simulation scenarios with full CI validation
- **Performance Benchmarks**: JMH-based benchmarking with automated metric extraction
- **Deterministic Testing**: Clock interface injection (57/57 clock tests passing ✅)
- **Flaky Test Handling**: @DisabledIfEnvironmentVariable pattern for probabilistic tests
- **CI/CD Pipeline**: 6 parallel test batches completing in 9-12 minutes (2.38x speedup)

### Deterministic Testing with Clock Interface

The simulation module supports deterministic testing through injectable Clock abstraction, enabling:
- **Reproducible scenarios**: Control time progression explicitly in tests (Phase 1: 36/113 calls complete)
- **Time-travel debugging**: Set arbitrary time points for test scenarios
- **Controllable advancement**: Eliminate timing-dependent flakiness
- **Consistent CI results**: Remove non-deterministic timing dependencies

**Implementation**: Clock interface properly documented with corrected TestClock API
- ✅ Clock.system() for production use
- ✅ TestClock for deterministic tests with `setTime()` and `advance()` methods
- ✅ VonMessageFactory pattern for record class timestamp injection
- ✅ All 57 clock interface tests passing

See [TEST_FRAMEWORK_GUIDE.md](TEST_FRAMEWORK_GUIDE.md) for comprehensive test patterns and [H3_DETERMINISM_EPIC.md](simulation/doc/H3_DETERMINISM_EPIC.md) for detailed architecture.

## Development Status

### Current Phase: Post-Stabilization Feature Development

**Project Health**: 8/10 ✅
- All 2,365 tests passing in full parallel test suite
- 6 parallel CI test batches complete in 9-12 minutes
- Zero version conflicts across 40+ dependencies
- PrimeMover 1.0.6 deployed with clock drift fixes

### Recent Milestones (2026-01-20)

#### Documentation Consolidation Complete ✅
Comprehensive cleanup and accuracy audit of entire repository:
- **1,500+ lines** of new documentation created
- **9 comprehensive guides** for test framework, performance metrics, PrimeMover, and CI/CD
- **100% accuracy verified** across architecture and APIs
- **Critical fixes**: Ghost overhead 0.01x-0.25x (not 100%+), TestClock API corrections, architecture class counts

**New Documentation**:
- [TEST_FRAMEWORK_GUIDE.md](TEST_FRAMEWORK_GUIDE.md) - Complete test patterns with specific thresholds
- [PERFORMANCE_CONSOLIDATION_REPORT.md](lucien/doc/PERFORMANCE_CONSOLIDATION_REPORT.md) - 8 major claims verified
- [PRIMEMOVER_1_0_6_UPGRADE.md](docs/PRIMEMOVER_1_0_6_UPGRADE.md) - Clock drift fixes and improvements
- [DEPENDENCY_VERSIONS_CONSOLIDATED.md](docs/DEPENDENCY_VERSIONS_CONSOLIDATED.md) - 40+ deps, zero conflicts
- [MAVEN_PARALLEL_CI_OPTIMIZATION.md](docs/MAVEN_PARALLEL_CI_OPTIMIZATION.md) - CI architecture (2.38x speedup)

### H3 Determinism Epic (Phase 1 Complete)

**Objective**: Enable deterministic, reproducible testing by eliminating non-deterministic time dependencies.

**Completed**: Phase 1 (36/113 calls, 31.9%)
- ✅ Clock interface with TestClock implementation (57/57 tests passing)
- ✅ VonMessageFactory pattern for record classes
- ✅ Critical files converted (FakeNetworkChannel, GhostStateManager, EntityMigrationStateMachine, etc.)
- ✅ Flaky test handling with @DisabledIfEnvironmentVariable pattern
- ✅ Documentation corrected and verified

**Remaining**: Phases 2-4 (77/113 calls, 68.1%)

**See Also**: [H3_DETERMINISM_EPIC.md](simulation/doc/H3_DETERMINISM_EPIC.md) - Complete architecture and implementation guide

## Documentation

### Architecture & Design
- [CLAUDE.md](CLAUDE.md) - Development guide and architectural decisions
- [Lucien Architecture](lucien/doc/LUCIEN_ARCHITECTURE.md) - Spatial indexing details (195 classes, 18 packages)
- [Lucien Architecture Summary](lucien/doc/ARCHITECTURE_SUMMARY.md) - High-level overview with inheritance hierarchy
- [H3 Determinism Epic](simulation/doc/H3_DETERMINISM_EPIC.md) - Deterministic testing architecture and implementation

### Testing & Quality
- [TEST_FRAMEWORK_GUIDE.md](TEST_FRAMEWORK_GUIDE.md) - Comprehensive test patterns and thresholds
- [Performance Consolidation Report](lucien/doc/PERFORMANCE_CONSOLIDATION_REPORT.md) - Accuracy audit of all performance claims
- [Performance Metrics Master](lucien/doc/PERFORMANCE_METRICS_MASTER.md) - Benchmarking results and methodology

### Operations & CI/CD
- [Maven Parallel CI Optimization](docs/MAVEN_PARALLEL_CI_OPTIMIZATION.md) - CI/CD architecture (6 parallel batches, 2.38x speedup)
- [Dependency Versions Consolidated](docs/DEPENDENCY_VERSIONS_CONSOLIDATED.md) - Complete dependency inventory
- [PrimeMover 1.0.6 Upgrade](docs/PRIMEMOVER_1_0_6_UPGRADE.md) - Clock drift fixes and improvements

### Current Development
- [Phase 7D Implementation Plan](simulation/doc/PHASE_7D_DAY_BY_DAY_IMPLEMENTATION.md) - Entity Migration Coordination
- [Phase 9 Dynamic Topology](simulation/src/test/java/com/hellblazer/luciferase/simulation/topology/README.md) - Topology adaptation details

## Recent Milestones

### Documentation Consolidation (2026-01-20) ✅

**Comprehensive repository cleanup with 100% accuracy verification**
- Audited and corrected all architecture documentation (195 Java files across 18 packages)
- Fixed critical accuracy issues: Ghost overhead 0.01x-0.25x, TestClock APIs, performance variance context
- Verified 8 major performance claims through 18.1M test queries
- Created complete test framework guide with specific thresholds for all performance tests
- Documented PrimeMover 1.0.6 upgrades (clock drift fixes, virtual time improvements)
- Consolidated dependency management (40+ dependencies, zero conflicts)
- Optimized CI/CD (6 parallel batches, 2.38x speedup, 9-12 minutes total)

### Phase 9: Dynamic Topology Adaptation (2026-01-15) ✅

Self-adapting spatial topology where bubble boundaries respond to entity distribution:
- **Automatic Splitting**: Density >5000 entities triggers split with atomic redistribution
- **Automatic Merging**: Density <500 entities triggers merge with duplicate detection
- **Boundary Adaptation**: Bubble centers follow entity cluster movement
- **Byzantine Consensus**: Committee voting with 30s cooldown and pre-validation
- **100% Entity Retention**: Snapshot/rollback guarantees no entity loss
- **Performance**: <1s splits, <500ms merges/moves, <200ms consensus
- **Test Coverage**: 105+ tests across detection, consensus, execution, and validation

[📚 Phase 9 Documentation](simulation/src/test/java/com/hellblazer/luciferase/simulation/topology/README.md)

### Stabilization Sprints (2026-01-11 to 2026-01-20) ✅

**Sprint A: Test Stabilization** ✅ COMPLETE
- ✅ Converted 136 wall-clock instances to Clock interface (deterministic time)
- ✅ Fixed TOCTTOU race conditions in test suite
- ✅ Resolved GitHub Actions cache conflicts
- ✅ **Achieved 5/5 consecutive clean CI runs** - All 2,365 tests passing

**Sprint B: Complexity Reduction** (In progress)
- Target: MultiBubbleSimulation refactoring (558 → 150 LOC facade)
- Health Target: >8/10 (Current: 8/10 ✅)
- Strategy: NO NEW FEATURES until health maintained

### Phase 8: Consensus-Coordinated Migration ✅

Byzantine-fault-tolerant entity migration across bubbles with 100% retention guarantees.

---

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
