# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- **Build the project**: `mvn clean install`
- **Run tests**: `mvn test`
- **Run a specific test**: `mvn test -Dtest=ClassName`
- **Skip tests during build**: `mvn clean install -DskipTests`

## Logging

The project uses SLF4J for logging with Logback as the implementation:
- Main implementation classes use `private static final Logger log = LoggerFactory.getLogger(ClassName.class);`
- Test classes have logback-test.xml configuration in `lucien/src/test/resources/`
- Debug logging enabled for spatial index operations, performance metrics, and construction progress
- Test and benchmark classes retain System.out for direct console output

## Requirements

- Java 23+ (configured in maven.compiler.source/target)
- Maven 3.91+
- Project is licensed under AGPL v3.0

## Architecture Overview

Luciferase is a 3D spatial data structure and visualization library with these core modules:

### Lucien Module - Spatial Indexing (34 classes total)
- **Core** (13 classes): `AbstractSpatialIndex`, `SpatialIndex`, `SpatialNodeStorage`, etc.
- **Entity Management** (12 classes): `EntityManager`, `EntityBounds`, ID generators, etc.
- **Octree** (3 classes): Morton curve-based cubic decomposition
- **Tetree** (6 classes): Tetrahedral space-filling curve decomposition

### Other Modules
- **common**: Optimized collections (`FloatArrayList`, `OaHashSet`), geometry utilities
- **sentry**: Delaunay tetrahedralization for kinetic point tracking
- **portal**: JavaFX 3D visualization and mesh handling
- **grpc**: Protocol buffer definitions for serialization
- **von**: Distributed spatial perception
- **simulation**: Animation and movement framework

### Key Architecture (June 2025)
- **Unified Design**: `AbstractSpatialIndex` provides 90% shared functionality
- **O(1) Performance**: HashMap storage, SpatialIndexSet for fast operations
- **Thread-Safe**: ReadWriteLock for concurrent access
- **Multi-Entity Support**: Multiple entities per spatial location
- **Feature Complete**: Ray intersection, collision detection, frustum culling, etc.

### Critical Context Files

When working with Octree implementation use the C++ code in the [Octree directory](./Octree) as the reference implementation.

The Java implementation uses a unified architecture:
- Both Octree and Tetree use HashMap for O(1) node access
- Common k-NN search and range queries in AbstractSpatialIndex
- Supports multi-entity storage and entity spanning
- Includes update operations for moving entities
- ~90% of functionality is shared through inheritance
The architecture prioritizes code reuse and consistency.

For accurate architecture documentation, see:
- `lucien/doc/LUCIEN_ARCHITECTURE_2025.md` - Complete architecture overview (January 2025)
- `lucien/archived/SPATIAL_INDEX_CONSOLIDATION.md` - Details of the consolidation changes
- `lucien/doc/ARCHITECTURE_SUMMARY_2025.md` - Complete class inventory

Historical documents (describe unimplemented features):
- `lucien/archived/TETREE_PORTING_PLAN.md` - Archived, planned features never built
- `lucien/archived/TETRAHEDRAL_DOMAIN_ANALYSIS.md` - Technical analysis for unimplemented features
- `lucien/archived/TETREE_OCTREE_ANALYSIS.md` - Comparison of architectures

### Dependencies

- **JavaFX 24**: For 3D visualization
- **javax.vecmath**: Vector mathematics
- **gRPC/Protobuf**: For distributed communication
- **JUnit 5**: Testing framework
- **PrimeMover**: Custom simulation framework dependency

## Memories

- The morton curve calculations are correct. Do not change them. The calculateMortonIndex is correct do not change it
- Constants.toLevel is correct. do not change it
- TetrahedralGeometry is fully integrated with TetrahedralSearchBase methods
- All documentation has been cleaned up to reflect current state (January 2025)
- The lucien module contains 34 classes total (not 60+ as originally planned)
- AbstractSpatialIndex provides common functionality for both Octree and Tetree:
    - Contains the spatialIndex Map<Long, NodeType> field
    - Contains the sortedSpatialIndices NavigableSet<Long> field
    - Implements k-NN search algorithm
    - Implements spatial range query logic
    - Both Octree and Tetree now use HashMap for spatial storage
    - Thread-safe implementation using ReadWriteLock (January 2025)
- use the test MortonValidationTest to validate morton SFC constraints and behavior before you start doubting the implementation of these functions
- **CRITICAL GEOMETRIC DISTINCTION - Cube vs Tetrahedron Center Calculations:**
    - **CUBE CENTER**: `origin.x + cellSize / 2.0f` (simple offset from origin)
    - **TETRAHEDRON CENTROID**: `(v0 + v1 + v2 + v3) / 4.0f` (average of 4 vertices)
    - **NEVER** use cube center formula for tetrahedron calculations
    - **ALWAYS** use `tet.coordinates()` to get actual tetrahedron vertices for centroid calculation
    - **Octree uses cubes**: center = origin + cellSize/2 in each dimension
    - **Tetree uses tetrahedra**: centroid = average of the 4 vertices from tet.coordinates()
    - **Fixed instances**: estimateNodeDistance() and getParentIndex() in Tetree.java (June 2025)
- **TET SFC LEVEL ENCODING - Critical Understanding:**
    - **The Tet SFC index DOES encode the level** through the number of 3-bit chunks used
    - **Level 0**: index = 0 (no bits)
    - **Level 1**: index uses 3 bits (values 0-7)
    - **Level 2**: index uses 6 bits (values 0-63)
    - **Level 3**: index uses 9 bits (values 0-511)
    - **The `index()` method**: Builds the SFC index by encoding the path from root, adding 3 bits per level
    - **The `tetLevelFromIndex()` method**: Recovers the level by finding the highest set bit and dividing by 3
    - **Each level adds exactly 3 bits** to encode which of the 8 children is selected (line 568: `exponent += 3`)
    - **This is NOT like Morton codes with level offsets** - the level is implicit in the bit pattern itself
- **CRITICAL T8CODE PARENT-CHILD CYCLE FIX (June 2025):**
    - **Problem**: TetreeFamily.isFamily() was failing because children computed different parent types
    - **Root Cause**: Tet.child() method was using wrong algorithm to determine child types
    - **WRONG**: `childType = getChildType(parentType, childIndex)` (direct lookup)
    - **CORRECT**: `beyId = getBeyChildId(parentType, childIndex); childType = getChildType(parentType, beyId)` (t8code algorithm)
    - **t8code Algorithm**: Morton index → Bey ID → Child type (two-step lookup using connectivity tables)
    - **Fix Location**: Tet.java line 277-281, swapped order to use Bey ID intermediary step
    - **Tables Used**: `t8_dtet_index_to_bey_number[parent_type][child_index]` → `t8_dtet_type_of_child[parent_type][bey_id]`
    - **Result**: All 8 children now correctly compute same parent type, parent-child round trip works perfectly
    - **Validation**: TetreeValidatorTest#testFamilyValidation now passes, maintaining t8code parity
- **CRITICAL CACHE KEY COLLISION FIX (June 2025):**
    - **Problem**: TetreeLevelCache index caching was causing test failures due to cache key collisions
    - **Root Cause**: Incorrect bit packing - z coordinate (32-bit) wasn't shifted, overlapping with level and type fields
    - **WRONG**: `key = ((long) x << 32) | ((long) y << 16) | ((long) z) | ((long) level << 8) | type`
    - **CORRECT**: Uses hash function with prime multipliers for proper distribution
    - **Hash Function**: `key = x * 0x9E3779B97F4A7C15L + y * 0xBF58476D1CE4E5B9L + z * 0x94D049BB133111EBL + level * 0x2545F4914F6CDD1DL + type`
    - **Fix Location**: TetreeLevelCache.java cacheIndex() and getCachedIndex() methods
    - **Result**: 0% collision rate (was 74%), >95% slot utilization, all SFC round-trip tests passing
    - **Validation**: TetreeLevelCacheKeyCollisionTest shows fix eliminates collisions
- **PERFORMANCE OPTIMIZATIONS (June 2025):**
    - **AbstractSpatialIndex**: Now uses `SpatialIndexSet` instead of `TreeSet` for O(1) operations
    - **TetreeLevelCache**: Provides O(1) level extraction, parent chain caching, and type transitions
    - **Tet.tetLevelFromIndex()**: Uses cached lookup instead of O(log n) numberOfLeadingZeros
    - **Tet.index()**: Checks cache first (line 510) and caches results (line 541)
    - **Tet.computeType()**: Uses cached type transitions (line 324) for O(1) lookups
    - **Tetree.ensureAncestorNodes()**: Uses cached parent chains for O(1) ancestor creation
    - **Memory overhead**: ~120KB for all caches combined (negligible for practical use)
    - **Result**: Tetree now matches Octree performance for all common operations
- read TETREE_CUBE_ID_GAP_ANALYSIS_CORRECTED.md as this establishes that the Tet.cubeId method is correct beyond doubt

## 🎯 CURRENT STATUS (June 2025)

**All Spatial Index Enhancements Complete** - The lucien module is feature-complete with all planned enhancements successfully implemented. For details on completed roadmaps and milestones, see [COMPLETED_ROADMAPS_JUNE_2025.md](lucien/archived/COMPLETED_ROADMAPS_JUNE_2025.md)

### Key Achievements:
- ✅ All 6 major spatial index components implemented (Ray Intersection, Collision Detection, Tree Traversal, Tree Balancing, Plane Intersection, Frustum Culling)
- ✅ Comprehensive API documentation for all features
- ✅ 200+ tests with full coverage
- ✅ Performance optimizations achieving 10x improvements for bulk operations
- ✅ ~90% t8code parity for tetrahedral operations

## 🚀 Performance (June 2025)

**Tetree vs Octree Benchmark Results:**

| Operation | Octree | Tetree | Improvement |
|-----------|--------|--------|-------------|
| Bulk insert 100K | 346 ms | 34 ms | **10x faster** |
| k-NN queries | 2.40 ms | 1.15 ms | 2x faster |
| Throughput | 300K/sec | 2-5M/sec | 7-16x higher |

**Key Optimizations:**
- **O(1) Operations**: `SpatialIndexSet` and `TetreeLevelCache` replace O(log n) operations
- **Bulk Operations**: Batch insertion, deferred subdivision, pre-allocation strategies
- **Adaptive Storage**: Array-based nodes for cache locality, automatic switching
- **Memory**: Only ~120KB overhead for all caches

## 📊 Performance Testing

**Active Plan**: `/lucien/doc/SPATIAL_INDEX_PERFORMANCE_TESTING_PLAN_2025.md`

**Test Control**: Set `RUN_SPATIAL_INDEX_PERF_TESTS=true` to enable performance tests

**Performance Targets:**
- Bulk insertion: 500K entities/sec
- k-NN queries: <50μs for k=10  
- Memory: <350 bytes per entity