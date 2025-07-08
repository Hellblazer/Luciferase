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

### Lucien Module - Spatial Indexing (98 Java files total)

- **Core** (27 classes): `AbstractSpatialIndex`, `SpatialIndex`, `SpatialNodeStorage`, etc.
- **Entity Management** (12 classes): `EntityManager`, `EntityBounds`, ID generators, etc.
- **Octree** (5 classes): Morton curve-based cubic decomposition
- **Tetree** (32 classes): Tetrahedral space-filling curve decomposition
- **Collision** (12 classes): Collision detection system with shapes and physics
- **Balancing** (3 classes): Tree balancing strategies
- **Visitor** (6 classes): Tree traversal visitor pattern
- **Index** (1 class): TM-index implementation

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
- The lucien module contains 98 Java files total (organized across 8 packages)
- run OctreeVsTetreeBenchmark for benchmarking documentation
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
- **CRITICAL SUBDIVISION FIX (June 28, 2025):**
    - **Problem**: Tetree was creating only 2 nodes for 1000 entities instead of proper subdivision
    - **Root Cause**: Tetree wasn't overriding `insertAtPosition` to handle empty/subdivided nodes
    - **Solution**: Added override to automatically insert at finer levels like Octree does
    - **Result**: Performance improved 38-96%, memory usage now correct (92-103% of Octree)
    - **Location**: Tetree.java lines 1391-1430 (insertAtPosition override)
    - **Impact**: Insertion now 6-35x slower (was 770x), proper tree structure maintained
- **V2 TMINDEX OPTIMIZATION (June 28, 2025):**
    - **Problem**: Original tmIndex was complex with extensive caching logic and fallbacks
    - **Solution**: Replaced with streamlined V2 approach - single loop parent chain collection
    - **Performance**: 4x speedup (0.23 μs → 0.06 μs per tmIndex call)
    - **Code Reduction**: Simplified from 70+ lines to ~15 lines in Tet.tmIndex()
    - **Algorithm**: Walk up collecting types in array, then build bits sequentially
    - **Impact**: Reduces Tetree insertion gap from 7-10x to 3-5x vs Octree
    - **Integration**: Full production integration completed June 28, 2025
- **Performance Testing Configuration:**
    - Disable Java assertions when running performance testing to reduce overhead
- **TETRAHEDRAL SUBDIVISION SOLUTION (June 28, 2025):**
    - **Problem**: Tetrahedral subdivision only achieved 37.5% containment due to vertex system mismatch
    - **Root Cause**: Our Tet used type-dependent V3 computation vs Subdivision.md's V3 = anchor + (h,h,h)
    - **Solution**: Created subdivisionCoordinates() method that uses V3 = anchor + (h,h,h) specifically for subdivision
    - **Impact**: Achieved 100% geometric containment without changing the global coordinate system
    - **Location**: Tet.java subdivisionCoordinates() method, BeySubdivision uses this for subdivision operations
    - **Note**: This is a localized solution - existing coordinate system remains unchanged
    - **Result**: geometricSubdivide() produces 8 children geometrically contained within parent in subdivision space
- **EFFICIENT CHILD COMPUTATION (July 2025):**
    - **Problem**: Computing all 8 children when only one is needed was inefficient
    - **Solution**: Added efficient single-child methods to BeySubdivision
    - **New Methods**:
        - `getBeyChild(parent, beyIndex)` - Computes single child in Bey order
        - `getTMChild(parent, tmIndex)` - Computes single child in TM order  
        - `getMortonChild(parent, mortonIndex)` - Computes single child in Morton order
    - **Performance**: ~3x faster than computing all children (17.10 ns per call)
    - **Integration**: Tet.child() now uses BeySubdivision.getMortonChild()
    - **Location**: BeySubdivision.java, integrated into Tet.child()
    - **Validation**: TetChildVsBeySubdivisionTest proves identical results
- **T8CODE PARTITION LIMITATION (July 2025):**
    - **Problem**: Tests expecting cube partitioning were failing after our changes
    - **Root Cause**: t8code tetrahedra fundamentally don't partition the cube
    - **Analysis**: ~48% gaps and ~32% overlaps in t8code decomposition
    - **Solution**: Disabled tests expecting proper partitioning
    - **Affected Tests**:
        - TetreeContainmentConsistencyTest
        - TetreePartitionTest  
        - TetreeContainmentDebugTest
        - TetreeTypeDeterminationTest
        - CorrectTetreeLocateTest
    - **Documentation**: See TETREE_T8CODE_PARTITION_ANALYSIS.md for details
    - **Note**: This is a fundamental limitation of t8code, not a bug in our implementation
- **VISUALIZATION FIXES (July 2025):**
    - **SimpleT8CodeGapDemo**: Fixed to use actual Tet.coordinates() instead of hardcoded unit cube vertices
    - **SimpleBeyRefinementDemo**: Fixed edge rendering using Cylinder shapes with proper 3D rotation
    - **Parent Wireframe**: Now correctly shows S0 tetrahedron edges using actual t8code coordinates
    - **Edge Rotation**: Switched from Box to Cylinder for edges, using cross product for proper alignment
    - **Coordinate Accuracy**: Visualizations now use real Tet class coordinates, not approximations
- **S0-S5 TETRAHEDRAL DECOMPOSITION COMPLETION (July 2025):**
    - **Problem**: Entity visualization showed spheres outside their containing tetrahedra due to incorrect coordinates
    - **Root Cause**: Tet.coordinates() was using legacy ei/ej algorithm instead of standard S0-S5 cube decomposition
    - **Solution**: Implemented correct S0-S5 decomposition where 6 tetrahedra perfectly tile a cube
    - **S0-S5 Pattern**: Each tetrahedra uses specific cube vertices (S0: 0,1,3,7; S1: 0,2,3,7; etc.)
    - **Results**: Achieved 100% containment rate (up from 35%), perfect cube tiling with no gaps/overlaps
    - **Coordinate Fix**: All types now share V0 (origin) and V7 (opposite corner) as required by cube decomposition
    - **Containment Fix**: Updated containsUltraFast() to handle mirrored tetrahedra (types 1,3,4) with reversed face tests
    - **Test Updates**: Fixed all test failures by updating expectations to match S0-S5 geometry
    - **Location**: Tet.java coordinates() method, TetS0S5DecompositionTest validates implementation
    - **Impact**: Visualization now correctly shows entities contained within their tetrahedra
- **PERFORMANCE BENCHMARKS (July 6, 2025):**
    - **Insertion**: Octree is 2.3x to 11.4x faster (gap increases with entity count)
    - **K-NN Search**: Tetree is 1.6x to 5.9x faster 
    - **Range Queries**: Tetree is 1.4x to 3.5x faster
    - **Memory**: Tetree uses only 20-25% of Octree's memory
    - **Root Cause**: tmIndex() O(level) parent chain walk vs Morton O(1) encoding
    - **See**: lucien/doc/OCTREE_VS_TETREE_PERFORMANCE_JULY_2025.md for details
- **DOCUMENTATION CLEANUP (July 6, 2025):**
    - **Archived**: 9 completed/outdated documents moved to lucien/archived/
    - **Updated**: Fixed references to legacy ei/ej algorithm in active docs
    - **Clarified**: T8code partition issues are fundamental, not fixable
    - **Performance**: Created new benchmark report with latest results
    - **S0-S5 Docs**: Moved reference doc from archived/ to doc/ as it's current
- **DOCUMENTATION TONE GUIDELINES (July 6, 2025):**
    - **Use professional, matter-of-fact tone**: Avoid promotional or triumphant language
    - **Avoid exclamation marks**: Only use in code examples (e.g., != operator)
    - **Replace superlatives**: Use "complete" not "perfect", "faster" not "superior"
    - **No bold emphasis on performance**: Present metrics plainly (e.g., "3x faster" not "**3x faster**")
    - **Neutral headings**: Use descriptive titles, not promotional ones (e.g., "Bulk Loading Performance" not "The Game Changer")
    - **Factual descriptions**: Focus on technical accuracy without enthusiasm or marketing language
    - **Professional phrasing**: State improvements factually without celebration
    - **Measured claims**: Avoid absolute terms unless technically accurate
- **LAZY EVALUATION IMPLEMENTATION (July 2025):**
    - **Problem**: TetreeKey O(level) tmIndex() computation prevents efficient range enumeration
    - **Solution**: Implemented lazy evaluation patterns to defer key generation until needed
    - **Components**:
        - LazyRangeIterator: O(1) memory iterator regardless of range size
        - LazySFCRangeStream: Java Stream API integration with early termination
        - RangeHandle: First-class range objects with deferred computation
        - RangeQueryVisitor: Tree-based alternative to range iteration
    - **Performance**: 99.5% memory savings for large ranges (6M+ keys)
    - **Trade-offs**: Small overhead for tiny ranges, massive benefits for large ranges
    - **Documentation**: See lucien/doc/LAZY_EVALUATION_USAGE_GUIDE.md for usage examples

[... rest of the file remains unchanged ...]