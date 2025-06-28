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

- **Generic SpatialKey Design**: `AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>` with type-safe spatial
  keys
- **Dual Key Types**: `MortonKey` for Octree, `TetreeKey` for Tetree - both extend `SpatialKey<Key>`
- **Unified API**: 95% shared functionality across spatial index types via generics
- **O(1) Performance**: HashMap storage with spatial key indexing, optimized data structures
- **Thread-Safe**: ReadWriteLock for concurrent access, fine-grained locking strategies
- **Multi-Entity Support**: Multiple entities per spatial location with efficient storage
- **Feature Complete**: Ray intersection, collision detection, frustum culling, spatial range queries

### Critical Context Files

When working with Octree implementation use the C++ code in the [Octree directory](./Octree) as the reference
implementation.

The Java implementation uses a unified generic architecture:

- **Generic Base**: `AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>`
- **Type-Safe Keys**: `MortonKey` (Octree) and `TetreeKey` (Tetree) provide spatial indexing
- **Shared Operations**: HashMap storage, k-NN search, range queries, collision detection
- **Multi-Entity Support**: Built-in support for multiple entities per spatial location
- **Update Operations**: Efficient entity movement and spatial reorganization
- **Code Reuse**: ~95% of functionality shared through generic inheritance
  The architecture prioritizes type safety, performance, and code reuse.

For accurate architecture documentation, see:

- `lucien/doc/LUCIEN_ARCHITECTURE_2025.md` - Complete architecture overview (June 2025)
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
- All documentation has been cleaned up to reflect current state (June 2025)
- The lucien module contains 34 classes total (not 60+ as originally planned)
- AbstractSpatialIndex provides common functionality through generics:
    - Contains the spatialIndex Map<Key, NodeType> field with type-safe spatial keys
    - Contains the sortedSpatialIndices NavigableSet<Key> field for range operations
    - Implements k-NN search algorithm with generic key types
    - Implements spatial range query logic using SpatialKey interface
    - Both Octree (MortonKey) and Tetree (TetreeKey) use HashMap for O(1) storage
    - Thread-safe implementation using ReadWriteLock with fine-grained locking (June 2025)
- use the test MortonValidationTest to validate morton SFC constraints and behavior before you start doubting the
  implementation of these functions
- **CRITICAL GEOMETRIC DISTINCTION - Cube vs Tetrahedron Center Calculations:**
    - **CUBE CENTER**: `origin.x + cellSize / 2.0f` (simple offset from origin)
    - **TETRAHEDRON CENTROID**: `(v0 + v1 + v2 + v3) / 4.0f` (average of 4 vertices)
    - **NEVER** use cube center formula for tetrahedron calculations
    - **ALWAYS** use `tet.coordinates()` to get actual tetrahedron vertices for centroid calculation
    - **Octree uses cubes**: center = origin + cellSize/2 in each dimension
    - **Tetree uses tetrahedra**: centroid = average of the 4 vertices from tet.coordinates()
    - **Fixed instances**: estimateNodeDistance() and getParentIndex() in Tetree.java (June 2025)
- **TET SFC LEVEL ENCODING - Critical Understanding:**
    - **The `consecutiveIndex()` method (formerly `index()`)**: Builds the SFC index by encoding the path from root,
      adding 3 bits per level
- **CRITICAL T8CODE PARENT-CHILD CYCLE FIX (June 2025):**
    - **Problem**: TetreeFamily.isFamily() was failing because children computed different parent types
    - **Root Cause**: Tet.child() method was using wrong algorithm to determine child types
    - **WRONG**: `childType = getChildType(parentType, childIndex)` (direct lookup)
    - **CORRECT**: `beyId = getBeyChildId(parentType, childIndex); childType = getChildType(parentType, beyId)` (t8code
      algorithm)
    - **t8code Algorithm**: Morton index → Bey ID → Child type (two-step lookup using connectivity tables)
    - **Fix Location**: Tet.java line 277-281, swapped order to use Bey ID intermediary step
    - **Tables Used**: `t8_dtet_index_to_bey_number[parent_type][child_index]` →
      `t8_dtet_type_of_child[parent_type][bey_id]`
    - **Result**: All 8 children now correctly compute same parent type, parent-child round trip works perfectly
    - **Validation**: TetreeValidatorTest#testFamilyValidation now passes, maintaining t8code parity
- **CRITICAL CACHE KEY COLLISION FIX (June 2025):**
    - **Problem**: TetreeLevelCache index caching was causing test failures due to cache key collisions
    - **Root Cause**: Incorrect bit packing - z coordinate (32-bit) wasn't shifted, overlapping with level and type
      fields
    - **WRONG**: `key = ((long) x << 32) | ((long) y << 16) | ((long) z) | ((long) level << 8) | type`
    - **CORRECT**: Uses hash function with prime multipliers for proper distribution
    - **Hash Function**:
      `key = x * 0x9E3779B97F4A7C15L + y * 0xBF58476D1CE4E5B9L + z * 0x94D049BB133111EBL + level * 0x2545F4914F6CDD1DL + type`
    - **Fix Location**: TetreeLevelCache.java cacheIndex() and getCachedIndex() methods
    - **Result**: 0% collision rate (was 74%), >95% slot utilization, all SFC round-trip tests passing
    - **Validation**: TetreeLevelCacheKeyCollisionTest shows fix eliminates collisions
- **PERFORMANCE OPTIMIZATIONS (June 2025):**
    - **AbstractSpatialIndex**: Now uses `SpatialIndexSet` instead of `TreeSet` for O(1) operations
    - **TetreeLevelCache**: Provides O(1) level extraction, parent chain caching, and type transitions
    - **Tet.tetLevelFromIndex()**: Uses cached lookup instead of O(log n) numberOfLeadingZeros
    - **Tet.consecutiveIndex()**: Checks cache first and caches results
    - **Tet.computeType()**: Uses cached type transitions (line 324) for O(1) lookups
    - **Tetree.ensureAncestorNodes()**: Uses cached parent chains for O(1) ancestor creation
    - **Memory overhead**: ~120KB for all caches combined (negligible for practical use)
    - **Result**: These optimizations help but cannot overcome the O(level) cost of tmIndex()
- **FINAL BUG FIXES (June 24, 2025):**
    - **Collision Detection Fix**: Changed `return` to `continue` in entity iteration loops within forEach lambdas
    - **Neighbor Finding Fix**: Rewrote `findNeighborsWithinDistance` to use entity positions instead of tetrahedron
      centroids
    - **Location**: Tetree.java collision detection (lines 982-988) and neighbor finding (lines 336-440)
    - **Result**: All collision detection and neighbor finding tests now pass, completing spatial index functionality
- read TETREE_CUBE_ID_GAP_ANALYSIS_CORRECTED.md as this establishes that the Tet.cubeId method is correct beyond doubt
- **CRITICAL INDEX METHOD DISTINCTION (June 2025):**
    - **Tet.consecutiveIndex()** (formerly index()): Returns long, O(1) with caching, unique only within a level
    - **Tet.tmIndex()**: Returns TetreeKey, O(level) due to parent chain walk, globally unique across all levels
    - **Performance Impact**: tmIndex() is 3.4x slower at level 1, 140x slower at level 20
    - **Tetree uses tmIndex()** for all spatial operations, causing massive performance degradation
    - **Octree uses Morton encoding**: Simple bit interleaving, always O(1)
    - **Cannot be fixed**: The parent chain walk in tmIndex() is required for global uniqueness across levels
- use tetrahedral geometry rather than incorrect AABB approximations for Tet and Tetree

## 🎯 CURRENT STATUS (June 2025)

**All Spatial Index Enhancements Complete** - The lucien module is feature-complete with all planned enhancements
successfully implemented, including the final collision detection and neighbor finding fixes completed on June 24, 2025.
For details on completed roadmaps and milestones,
see [COMPLETED_ROADMAPS_JUNE_2025.md](lucien/archived/COMPLETED_ROADMAPS_JUNE_2025.md)

### Key Achievements:

- ✅ All 6 major spatial index components implemented (Ray Intersection, Collision Detection, Tree Traversal, Tree
  Balancing, Plane Intersection, Frustum Culling)
- ✅ Generic SpatialKey architecture with type-safe spatial indexing
- ✅ Comprehensive API documentation for all features
- ✅ 200+ tests with full coverage
- ✅ Performance optimizations implemented (though Octree remains faster for insertions)
- ✅ ~90% t8code parity for tetrahedral operations
- ✅ **Final Bug Fixes (June 24, 2025)**: Collision detection and neighbor finding fully working

## 🚀 Performance (June 2025 - Post DeferredSortedSet Removal)

**IMPORTANT**: Previous performance claims were based on using the non-unique `consecutiveIndex()` method. After
refactoring to use the globally unique `tmIndex()`, the performance characteristics have changed significantly.

**Current Tetree vs Octree Performance Reality (Updated June 2025):**

| Operation   | Octree        | Tetree         | Winner                    | Notes                        |
|-------------|---------------|----------------|---------------------------|------------------------------|
| Insertion   | 1.3 μs/entity | 483 μs/entity  | **Octree (372x faster)**  | tmIndex() walks parent chain |
| k-NN Search | 206 μs        | 64 μs          | **Tetree (3.2x faster)**  | Better spatial locality      |
| Range Query | 203 μs        | 62 μs          | **Tetree (3.3x faster)**  | Efficient traversal          |
| Update      | 0.012 μs      | 1.16 μs        | **Octree (97x faster)**   | Morton code efficiency       |
| Memory      | 100%          | 20-220%        | **Mixed**                 | Varies with data size        |

**Root Cause of Performance Difference:**

- **Octree**: Uses Morton encoding - simple bit interleaving, O(1) operation
- **Tetree**: Uses `tmIndex()` which walks parent chain - O(level) operation
- At level 20: `tmIndex()` is ~140x slower than `consecutiveIndex()`

**Key Findings:**

- The `consecutiveIndex()` method (formerly `index()`) is NOT equivalent to `tmIndex()`
- `consecutiveIndex()` is fast but unique only within a level
- `tmIndex()` provides global uniqueness across all levels but at significant performance cost
- Previous optimizations (TetreeLevelCache) help but cannot overcome fundamental algorithmic differences

## 📊 Performance Testing

**Active Plan**: `/lucien/doc/SPATIAL_INDEX_PERFORMANCE_TESTING_PLAN_2025.md`

**Test Control**: Set `RUN_SPATIAL_INDEX_PERF_TESTS=true` to enable performance tests

**Realistic Performance Expectations (June 2025 - Post DeferredSortedSet Removal):**

- **Octree**: ~770K entities/sec insertion, 5-30μs k-NN queries  
- **Tetree**: ~2K entities/sec insertion, 15-60μs k-NN queries
- **Memory**: Mixed results - Tetree uses 20% memory for small datasets, 220% for large datasets
- **Note**: Tetree is 372x slower for insertions but 3.2x faster for queries
- **Lazy Evaluation**: Still provides 3.8x speedup for Tetree insertions by deferring tmIndex() computation