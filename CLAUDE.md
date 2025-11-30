# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Session Initialization

First, read the MCP guide: memory-bank-mcp:memory_bank_read({projectName: 'MCP_Documentation', fileName: 'complete-mcp-guide.md'})

Spawn multiple parallel subtasks of claude whenever applicable. Leverage the capabilities of the configured MCP servers where applicable.

## Build Commands

- **Build the project**: `mvn clean install`
- **Run tests**: `mvn test`
- **Run a specific test**: `mvn test -Dtest=ClassName`
- **Skip tests during build**: `mvn clean install -DskipTests`

## Quick Commands

- **Run specific module tests**: `mvn test -pl <module-name>`
- **Performance benchmarks**: `mvn test -Pperformance`
- **Specific benchmark**: `mvn test -pl lucien -Dtest=OctreeVsTetreeVsPrismBenchmark`
- **Single test without retries**: `mvn test -pl <module> -Dtest=TestName -Dsurefire.rerunFailingTestsCount=0`
- **Check compilation errors**: Use `mcp jetbrains.findProjectProblems` (no need to compile when IDE is available)

## Requirements

- Java 24 (uses stable FFM API)
- Maven 3.91+
- JavaFX 24 (for visualization)
- LWJGL 3 (for OpenGL rendering)
- Project is licensed under AGPL v3.0

## Logging

The project uses SLF4J for logging with Logback as the implementation:

- Main implementation classes use `private static final Logger log = LoggerFactory.getLogger(ClassName.class);`
- Test classes have logback-test.xml configuration in `lucien/src/test/resources/`
- Debug logging enabled for spatial index operations, performance metrics, and construction progress
- Test and benchmark classes retain System.out for direct console output
- **Important**: SLF4J doesn't support Python-style format specifiers like {:.2f}, use correct SLF4J format specifiers

## Architecture Overview

Luciferase is a 3D spatial data structure and visualization library with these core modules:

### Module Structure

| Module | Description |
|--------|-------------|
| **common** | Optimized collections (`FloatArrayList`, `OaHashSet`), geometry utilities |
| **grpc** | Protocol buffer definitions for serialization |
| **lucien** | Core spatial indexing (Octree, Tetree, Prism) - 185 Java files, 17 packages |
| **render** | ESVO implementation with LWJGL rendering, FFM integration |
| **sentry** | Delaunay tetrahedralization for kinetic point tracking |
| **portal** | JavaFX 3D visualization and mesh handling |
| **von** | Distributed spatial perception framework |
| **simulation** | Animation and movement simulation |
| **gpu-test-framework** | GPU testing infrastructure and benchmarking |
| **resource** | Shared resources, shaders, and configuration files |
| **dyada-java** | Mathematical utilities and data structures |
| **e2e-test** | End-to-end integration testing |

### Lucien Module - Spatial Indexing

**Total**: 185 Java files organized across 17 packages (expanded from 98 in June 2025)

- **Root Package** (29 classes): Core abstractions, spatial types, geometry utilities, performance optimization
- **Entity Management** (13 classes): Complete entity lifecycle management
- **Octree** (6 classes): Morton curve-based cubic subdivision with O(1) operations
- **Tetree** (34 classes): Tetrahedral subdivision with S0-S5 characteristic tetrahedra, 21-level support
- **Prism** (8 classes): Anisotropic spatial subdivision with triangular/linear elements
- **Collision** (29 classes): Comprehensive collision detection with CCD and physics subpackages
- **Balancing** (4 classes): Tree balancing strategies
- **Visitor** (6 classes): Tree traversal visitor pattern
- **Forest** (27 classes): Multi-tree coordination, ghost layer, distributed support
- **Neighbor** (3 classes): Topological neighbor detection
- **Lockfree** (3 classes): Lock-free concurrent operations
- **Occlusion** (9+ classes): Dynamic Scene Occlusion Culling (DSOC) with adaptive Z-buffer
- **Debug** (4 classes): Debugging utilities for all spatial index types
- **Internal** (4 classes): Entity caching and object pool utilities
- **Geometry** (1 class): AABB intersection utilities
- **Migration** (1 class): Spatial index type conversion utilities
- **Profiler** (1 class): Performance profiling utilities

### Key Architecture (Current State)

- **Generic SpatialKey Design**: `AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>` with type-safe spatial keys
- **Dual Key Types**: `MortonKey` for Octree, `TetreeKey` for Tetree - both extend `SpatialKey<Key>`
- **Unified API**: 95% shared functionality across spatial index types via generics
- **Thread-Safe**: ConcurrentSkipListMap for O(log n) operations with concurrent access
- **Multi-Entity Support**: Multiple entities per spatial location with CopyOnWriteArrayList
- **Feature Complete**: Ray intersection, collision detection, frustum culling, spatial range queries, k-NN search

### Critical Context Files

When working with Octree implementation, use the C++ code in the [Octree directory](./Octree) as the reference implementation.

The Java implementation uses a unified generic architecture:
- **Generic Base**: `AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>`
- **Type-Safe Keys**: `MortonKey` (Octree) and `TetreeKey` (Tetree) provide spatial indexing
- **Shared Operations**: ConcurrentSkipListMap storage, k-NN search, range queries, collision detection
- **Multi-Entity Support**: Built-in support for multiple entities per spatial location
- **Update Operations**: Efficient entity movement and spatial reorganization
- **Code Reuse**: ~95% of functionality shared through generic inheritance

For accurate architecture documentation, see:
- `lucien/doc/LUCIEN_ARCHITECTURE.md` - Complete architecture overview
- `lucien/doc/ARCHITECTURE_SUMMARY.md` - Complete class inventory
- `lucien/doc/PERFORMANCE_METRICS_MASTER.md` - Current performance metrics
- `render/doc/ESVO_COMPLETION_SUMMARY.md` - ESVO implementation status

Historical documents (archived, describe unimplemented or superseded features):
- `lucien/archived/SPATIAL_INDEX_CONSOLIDATION.md` - Details of the consolidation changes
- `lucien/archived/TETREE_PORTING_PLAN.md` - Planned features never built
- `lucien/archived/TETRAHEDRAL_DOMAIN_ANALYSIS.md` - Technical analysis for unimplemented features
- `lucien/archived/TETREE_OCTREE_ANALYSIS.md` - Comparison of architectures

### Dependencies

- **JavaFX 24**: For 3D visualization
- **LWJGL 3**: OpenGL, GLFW, OpenCL bindings
- **javax.vecmath**: Vector mathematics
- **gRPC/Protobuf**: For distributed communication
- **JUnit 5**: Testing framework
- **JMH**: Benchmarking framework
- **PrimeMover**: Custom simulation framework dependency

## Performance Testing

- **Process**: Follow `lucien/doc/PERFORMANCE_METRICS_MASTER.md` for standardized benchmarking
- **Run benchmarks**: `mvn clean test -Pperformance`
- **Full workflow**: `mvn clean verify -Pperformance-full` (runs tests, extracts metrics, updates docs)
- **Key benchmark**: Run `OctreeVsTetreeBenchmark` for comparative metrics
- **Important**: Disable Java assertions when running performance tests to reduce overhead
- **Current Performance**: Tetree 1.9x-6.2x faster for insertions, Octree better for queries (see PERFORMANCE_METRICS_MASTER.md)

## Critical Architecture Notes

### Geometric Distinctions

**CUBE vs TETRAHEDRON Center Calculations:**
- **CUBE CENTER**: `origin.x + cellSize / 2.0f` (simple offset from origin)
- **TETRAHEDRON CENTROID**: `(v0 + v1 + v2 + v3) / 4.0f` (average of 4 vertices)
- **NEVER** use cube center formula for tetrahedron calculations
- **ALWAYS** use `tet.coordinates()` to get actual tetrahedron vertices for centroid calculation
- **Octree uses cubes**: center = origin + cellSize/2 in each dimension
- **Tetree uses tetrahedra**: centroid = average of the 4 vertices from tet.coordinates()

### S0-S5 Tetrahedral Subdivision

- **S0-S5 Pattern**: 6 tetrahedra perfectly tile a cube using specific vertex combinations
- **Coordinate System**: All types share V0 (origin) and V7 (opposite corner)
- **Containment**: Uses `containsUltraFast()` with special handling for mirrored tetrahedra (types 1,3,4)
- **Location**: Tet.java coordinates() method
- **Validation**: TetS0S5SubdivisionTest validates implementation
- **Result**: 100% containment rate with perfect cube tiling (no gaps/overlaps)

### TET SFC Level Encoding

- **consecutiveIndex()** method: Builds the SFC index by encoding the path from root, adding 3 bits per level
- **Returns**: long, O(1) with caching, unique only within a level
- **tmIndex()**: Returns TetreeKey, O(level) due to parent chain walk, globally unique across all levels
- **Performance Impact**: tmIndex() is O(level), cannot be fixed (required for global uniqueness)

### Concurrent Architecture

- **Storage**: Single ConcurrentSkipListMap for thread-safe operations
- **Memory Savings**: 54-61% reduction vs dual HashMap/TreeSet approach
- **Entity Storage**: CopyOnWriteArrayList for thread-safe iteration
- **Lock-Free**: Optimistic concurrency control for entity updates
- **ObjectPool**: Used for k-NN, collision, ray intersection, frustum culling to reduce GC pressure

### T8code Partition Limitation

- **t8code tetrahedra fundamentally don't partition the cube**: ~48% gaps and ~32% overlaps
- **This is a fundamental limitation**, not a bug in our implementation
- **Affected Tests**: Disabled tests expecting proper cube partitioning
- **Documentation**: See `lucien/doc/TETREE_T8CODE_PARTITION_ANALYSIS.md` for details

### DSOC (Dynamic Spatial Occlusion Culling)

- **Default**: Disabled by default (use `DSOCConfiguration.defaultConfig().withEnabled(true)` to enable)
- **Auto-disable**: Monitors performance, prevents >20% overhead
- **Lazy Allocation**: 3+ occluder threshold for Z-buffer allocation
- **Adaptive Sizing**: Z-buffer scales from 128x128 to 2048x2048 based on scene
- **Performance**: Provides 2.0x speedup when properly configured with sufficient occlusion

## Testing Configuration

- **Dynamic Ports**: Always use dynamic (random) listening ports in tests to avoid port conflicts
- **GPU Tests**: Require `dangerouslyDisableSandbox: true` since sandbox blocks GPU/OpenCL access
- **Test Output**: Use `VERBOSE_TESTS` env var to enable test output (suppressed by default)
- **Port Conflicts**: Design interfaces/functions so dynamic port assignments are easily configured

## Common Development Patterns

### Maven Dependency Management

When adding dependencies to this multi-module project:
1. For multiple artifacts from same groupId: Create version property in root pom.xml (e.g., `<jmh.version>1.37</jmh.version>`)
2. For single dependencies: Use version directly in dependencyManagement (no property needed)
3. Add all dependencies to root pom.xml `<dependencyManagement>` section with versions
4. In module pom.xml files, reference dependencies WITHOUT version tags
5. This ensures consistent versions across modules

### JavaFX Applications

- **Always use Launcher inner class pattern** for Application.launch()
- **Never use -XstartOnFirstThread** in module-level configuration (conflicts with JavaFX threading)

### Correctness Validation

- **Morton Curve**: Use `MortonValidationTest` to validate SFC constraints before doubting implementation
- **Tetrahedral Geometry**: Use tetrahedral geometry, not AABB approximations for Tet and Tetree
- **Documentation**: Refer to `TETREE_CUBE_ID_GAP_ANALYSIS_CORRECTED.md` for Tet.cubeId correctness proof

## Historical Reference

For detailed historical bug fixes and implementation notes (not essential for day-to-day development), see:
- **HISTORICAL_FIXES_REFERENCE.md** - Complete archive of dated bug fixes and optimizations

This includes detailed information about:
- T8CODE parent-child cycle fix (June 2025)
- Cache key collision fix (June 2025)
- Performance optimizations (June-July 2025)
- Concurrent architecture refactoring (July 2025)
- Lock-free entity updates (July 2025)
- DSOC optimization (July 2025)
- Render module test fixes (August 2025)
