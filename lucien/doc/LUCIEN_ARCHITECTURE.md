# Lucien Module Architecture

## Overview

The lucien module provides spatial indexing capabilities through a unified architecture that supports octree (cubic),
tetree (tetrahedral), and prism (anisotropic) subdivision strategies. The module uses inheritance to maximize code
reuse while maintaining the unique characteristics of each spatial indexing approach.

The Luciferase codebase underwent architectural simplification in 2025, focusing on core spatial indexing
functionality with entity management as the primary abstraction. The system has been refocused to eliminate complex
abstractions while maintaining full spatial indexing capabilities.

The module consists of 172 Java files organized across 14 packages, providing a comprehensive spatial indexing system
with advanced features including collision detection, tree balancing, visitor patterns, forest management, and distributed
ghost support. All core features are complete, including the S0-S5 tetrahedral subdivision, anisotropic prism subdivision,
and full ghost layer implementation with gRPC communication.

## Package Structure

```
com.hellblazer.luciferase.lucien/
├── Root package (28 classes + 2 images)
│   ├── Core abstractions: SpatialIndex, AbstractSpatialIndex, 
│   │                      SpatialNodeStorage, SpatialNodeImpl, SpatialKey
│   ├── Spatial types: Spatial, VolumeBounds, SpatialIndexSet
│   ├── Geometry: Frustum3D, Plane3D, Ray3D, Simplex, FrustumIntersection, PlaneIntersection
│   ├── Performance: BulkOperationConfig, BulkOperationProcessor, DeferredSubdivisionManager,
│   │                ParallelBulkOperations, SpatialNodePool, FineGrainedLockingStrategy
│   ├── Utilities: Constants, VisibilitySearch, LevelSelector, NodeEstimator, 
│   │              BatchInsertionResult, StackBasedTreeBuilder, SubdivisionStrategy
│   └── Resources: reference-cube.png, reference-simplexes.png
├── entity/ (12 classes)
│   ├── Core: Entity, EntityBounds, EntityData, EntityDistance
│   ├── Identity: EntityID, EntityIDGenerator
│   ├── Management: EntityManager, EntitySpanningPolicy
│   └── Implementations: LongEntityID, UUIDEntityID,
│                       SequentialLongIDGenerator, UUIDGenerator
├── octree/ (6 classes)
│   ├── Octree - Main implementation
│   ├── MortonKey - Space-filling curve key
│   ├── OctreeBalancer - Tree balancing implementation
│   ├── OctreeSubdivisionStrategy - Subdivision policy
│   ├── Octant - Octant enumeration
│   └── internal/NodeDistance - Node distance utilities
├── tetree/ (34 classes)
│   ├── Core: Tetree, Tet, TetrahedralGeometry, TetrahedralSearchBase
│   ├── Keys: TetreeKey, CompactTetreeKey, LazyTetreeKey
│   ├── Indexing: TmIndex, SimpleTMIndex
│   ├── Caching: TetreeLevelCache, TetreeRegionCache, SpatialLocalityCache, ThreadLocalTetreeCache
│   ├── Utilities: TetreeBits, TetreeConnectivity, TetreeFamily, TetreeHelper, 
│   │              TetreeIterator, TetreeLUT, TetreeNeighborFinder
│   ├── Subdivision: BeySubdivision, TetreeSubdivisionStrategy
│   ├── Advanced: TetreeSFCRayTraversal, TetreeValidator, TetreeValidationUtils, 
│   │             PluckerCoordinate, TetOptimized
│   └── internal/TetDistance - Distance utilities
├── prism/ (8 classes)
│   ├── Core: Prism, PrismKey, PrismGeometry
│   ├── Primitives: Line, Triangle
│   ├── Collision: PrismCollisionDetector
│   ├── Intersection: PrismRayIntersector
│   └── Navigation: PrismNeighborFinder
├── balancing/ (4 classes)
│   ├── TreeBalancer - Main balancing interface
│   ├── TreeBalancingStrategy - Strategy pattern
│   ├── DefaultBalancingStrategy - Default implementation
│   └── TetreeBalancer - Tetree-specific balancing
├── collision/ (29 classes)
│   ├── Main: CollisionSystem, CollisionShape, CollisionResponse, etc. (18 classes)
│   ├── ccd/ - Continuous collision detection (4 classes)
│   ├── physics/ - Physics integration (4 classes)
│   └── physics/constraints/ - Constraint system (3 classes)
├── visitor/ (6 classes)
│   ├── TreeVisitor - Visitor interface
│   ├── AbstractTreeVisitor - Base implementation
│   ├── Concrete: EntityCollectorVisitor, NodeCountVisitor
│   └── Support: TraversalContext, TraversalStrategy
├── forest/ (16 classes + ghost subpackage)
│   ├── Core: Forest, TreeNode, TreeMetadata, TreeLocation
│   ├── Configuration: ForestConfig
│   ├── Management: DynamicForestManager, ForestEntityManager, ForestLoadBalancer
│   ├── Specialized: GridForest, AdaptiveForest, HierarchicalForest
│   ├── Spatial Queries: ForestQuery, ForestSpatialQueries
│   ├── Connectivity: TreeConnectivityManager
│   └── ghost/ (11 classes)
│       ├── Core: GhostElement, GhostType, GhostLayer, GhostZoneManager
│       ├── Management: ElementGhostManager, DistributedGhostManager
│       ├── Communication: GhostExchangeServiceImpl, GhostServiceClient, 
│       │                  GhostCommunicationManager
│       ├── Serialization: ProtobufConverters, ContentSerializer, 
│       │                  ContentSerializerRegistry
│       └── Discovery: SimpleServiceDiscovery
├── neighbor/ (3 classes)
│   ├── NeighborDetector - Interface for topological neighbor detection
│   ├── MortonNeighborDetector - Octree neighbor detection
│   └── TetreeNeighborDetector - Tetree neighbor detection
├── lockfree/ (3 classes)
│   ├── LockFreeEntityMover - Atomic movement protocol (264K movements/sec)
│   ├── AtomicSpatialNode - Lock-free spatial node using atomic collections
│   └── VersionedEntityState - Immutable versioned state for optimistic concurrency
├── internal/ (4 classes)
│   ├── EntityCache - Entity caching system
│   ├── IndexedEntity - Indexed entity utilities
│   ├── ObjectPools - Memory pool management
│   └── UnorderedPair - Utility for unordered pairs
├── geometry/ (1 class)
│   └── AABBIntersector - AABB intersection utilities
└── index/ (0 classes)
    └── [Empty directory]
```

## Class Hierarchy

### Core Inheritance Structure

```
SpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content> (interface)
    └── AbstractSpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content>
            ├── Octree<ID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content>
            ├── Tetree<ID, Content> extends AbstractSpatialIndex<TetreeKey, ID, Content>
            └── Prism<ID, Content> extends AbstractSpatialIndex<PrismKey, ID, Content>
```

### Node Storage (Phase 6.2 Update)

As of July 10, 2025, the node storage hierarchy has been simplified:

```
SpatialNodeStorage<ID> (interface)
    └── SpatialNodeImpl<ID> (unified implementation used by both Octree and Tetree)
```

The previous `OctreeNode` and `TetreeNodeImpl` classes have been eliminated in favor of a single unified node
implementation.

## AbstractSpatialIndex - The Foundation

The `AbstractSpatialIndex` class contains the majority of spatial indexing functionality:

### Common State (Updated July 2025)

- `spatialIndex: ConcurrentNavigableMap<Key, SpatialNodeImpl<ID>>` - **Thread-safe spatial storage** using
  ConcurrentSkipListMap (replaces dual HashMap/TreeSet structure)
- `entityManager: EntityManager<ID, Content>` - Centralized entity lifecycle management
- `maxEntitiesPerNode: int` - Threshold for node subdivision
- `maxDepth: byte` - Maximum tree depth
- `spanningPolicy: EntitySpanningPolicy` - Controls entity spanning behavior

**Key Architectural Change (July 2025):** Eliminated separate `sortedSpatialIndices` NavigableSet in favor of single
ConcurrentSkipListMap providing both O(log n) access and sorted iteration with thread safety.

### Common Operations

- **Entity Management**: insert(), remove(), update(), lookup()
- **Spatial Queries**: boundedBy(), bounding(), enclosing()
- **k-NN Search**: Complete k-nearest neighbor implementation
- **Range Queries**: Optimized spatial range query with customizable index calculation
- **Node Lifecycle**: insertAtPosition(), onNodeRemoved(), handleNodeSubdivision()

### Abstract Methods (Implementation-Specific)

The actual AbstractSpatialIndex defines 17 abstract methods for implementation-specific behavior:

- `addNeighboringNodes(Key, Queue<Key>, Set<Key>)` - Neighbor traversal for searches
- `calculateSpatialIndex(Point3f, byte)` - Convert position to spatial index
- `createDefaultSubdivisionStrategy()` - Create default subdivision strategy
- `doesFrustumIntersectNode(Key, Frustum3D)` - Frustum intersection testing
- `doesNodeIntersectVolume(Key, Spatial)` - Volume intersection testing
- `doesPlaneIntersectNode(Key, Plane3D)` - Plane intersection testing
- `doesRayIntersectNode(Key, Ray3D)` - Ray intersection testing
- `estimateNodeDistance(Key, Point3f)` - Distance estimation for optimization
- `findNodesIntersectingBounds(VolumeBounds)` - Find nodes intersecting bounds
- `getCellSizeAtLevel(byte)` - Get cell size at specific level
- `getFrustumTraversalOrder(Frustum3D, Point3f)` - Frustum traversal optimization
- `getNodeBounds(Key)` - Get spatial bounds of a node (takes Key not long)
- `getPlaneTraversalOrder(Plane3D)` - Plane traversal optimization
- `getRayNodeIntersectionDistance(Key, Ray3D)` - Ray-node intersection distance
- `getRayTraversalOrder(Ray3D)` - Ray traversal optimization
- `isNodeContainedInVolume(Key, Spatial)` - Volume containment testing
- `shouldContinueKNNSearch(Key, Point3f, ...)` - k-NN search continuation logic

## Octree Implementation

The `Octree` class provides Morton curve-based cubic spatial subdivision:

### Unique Characteristics

- Uses Morton encoding for spatial indices
- No coordinate constraints (supports negative coordinates)
- 8 children per node (cubic subdivision)
- Efficient range queries using Morton curve properties

### Key Methods

- `calculateSpatialIndex()` - Computes Morton code from position
- `getMortonCodeRange()` - Optimized range calculation using Morton properties
- `addNeighboringNodes()` - 26-neighbor cubic traversal

## Tetree Implementation

The `Tetree` class provides tetrahedral spatial subdivision:

### Unique Characteristics

- Uses tetrahedral space-filling curve
- Requires positive coordinates only
- 6 tetrahedra per grid cell (S0-S5 subdivision)
- Complex neighbor relationships

### S0-S5 Tetrahedral Subdivision (July 2025)

The Tetree uses the standard S0-S5 subdivision where 6 tetrahedra completely tile a cube:

- **S0**: Vertices {0, 1, 3, 7} - X-dominant, upper diagonal
- **S1**: Vertices {0, 2, 3, 7} - Y-dominant, upper diagonal
- **S2**: Vertices {0, 4, 5, 7} - Z-dominant, upper diagonal
- **S3**: Vertices {0, 4, 6, 7} - Z-dominant, lower diagonal
- **S4**: Vertices {0, 1, 5, 7} - X-dominant, lower diagonal
- **S5**: Vertices {0, 2, 6, 7} - Y-dominant, lower diagonal

This replaced the legacy ei/ej algorithm and provides:

- 100% cube coverage with no gaps or overlaps
- Correct geometric containment for all entities
- Standard subdivision matching academic literature

### Key Methods

- `calculateSpatialIndex()` - Uses Tet.locate() for tetrahedral index
- `computeSFCRanges()` - Tetrahedral SFC range calculation
- `addNeighboringNodes()` - Tetrahedral neighbor traversal
- `validatePositiveCoordinates()` - Enforces positive coordinate constraint
- `Tet.coordinates()` - Returns actual S0-S5 vertices (not AABB approximation)

## Prism Implementation

The `Prism` class provides anisotropic spatial subdivision using composite triangular and linear indices:

### Unique Characteristics

- Uses anisotropic subdivision (2D triangular + 1D linear)
- Composite spatial key combining Line and Triangle indices
- Triangular constraint: x + y < worldSize for valid coordinates
- Fine horizontal granularity, coarse vertical granularity
- Optimized for terrain data, urban planning, and height-stratified content

### Anisotropic Subdivision Pattern

Unlike uniform cubic (Octree) or tetrahedral (Tetree) subdivision, Prism uses distinct subdivision strategies for
different dimensions:

- **Horizontal Plane (X, Y)**: Triangular subdivision using right triangles for fine spatial resolution
- **Vertical Axis (Z)**: Linear subdivision using simple bins for coarse height stratification
- **Composite Indexing**: Combines Triangle index (horizontal) with Line index (vertical) into PrismKey

### Triangular Coordinate System

The horizontal triangular subdivision imposes geometric constraints:

- **Valid Region**: Points must satisfy x + y < worldSize
- **Right Triangle Grid**: Space divided into right triangles with configurable granularity
- **Triangle Identification**: Each point maps to a specific triangle based on (x, y) coordinates
- **Spatial Locality**: Adjacent triangles share edges, preserving spatial relationships

### Key Methods

- `calculateSpatialIndex()` - Computes composite PrismKey from 3D position
- `getTriangleIndex()` - Maps (x, y) coordinates to triangular grid
- `getLineIndex()` - Maps z coordinate to vertical bin
- `addNeighboringNodes()` - Triangular and linear neighbor traversal
- `validateTriangularConstraints()` - Enforces x + y < worldSize constraint

### Use Cases

The Prism spatial index is optimized for specific domain applications:

**Terrain and Geographic Data:**

- Height maps and elevation data
- Geographic information systems (GIS)
- Topographic analysis and visualization

**Urban Planning:**

- Building height regulations
- Zoning with height restrictions
- City skyline analysis

**Atmospheric and Oceanographic Data:**

- Layered atmospheric measurements
- Ocean depth stratification
- Environmental monitoring with altitude components

**Game Development:**

- 2.5D game environments
- Platformer games with height layers
- Terrain-based gameplay mechanics

**Scientific Visualization:**

- Data with natural height stratification
- Cross-sectional analysis
- Layer-based scientific datasets

## Entity Management

The `EntityManager` class provides centralized entity lifecycle:

### Core Functionality

- Entity storage with O(1) access by ID
- Position and bounds tracking
- Multi-location support (entity spanning)
- ID generation through pluggable generators

### Entity Types

- `Entity<Content>` - Core entity with content, position, and bounds
- `EntityID` - Abstract identifier (implementations: LongEntityID, UUIDEntityID)
- `EntityBounds` - Bounding box for spatial entities
- `EntityDistance` - Entity-distance pair for k-NN results

## Key Classes by Package

### Root Package Classes (26)

**Core Abstractions:**

- **SpatialIndex** - Main interface defining spatial operations
- **AbstractSpatialIndex** - Base implementation with common functionality
- **SpatialNodeStorage** - Interface for node storage strategies
- **SpatialNodeImpl** - Unified node implementation

**Spatial Types:**

- **Spatial** - Sealed interface for spatial volumes (Cube, Sphere, etc.)
- **VolumeBounds** - AABB representation for any spatial volume

**Geometry Utilities:**

- **Frustum3D** - View frustum representation
- **Plane3D** - 3D plane with distance calculations
- **Ray3D** - Ray casting support
- **Simplex** - Simplex aggregation strategies

**Core Utilities:**

- **Constants** - System-wide constants and calculations
- **VisibilitySearch** - Line-of-sight and occlusion queries

### Entity Package (12)

- **EntityManager** - Centralized entity management
- **Entity** - Core entity class with content and metadata
- **EntityBounds** - Bounding volume for spanning entities
- **EntityData** - Bulk operation container
- **EntityDistance** - Distance calculation results
- **EntityID** - Abstract entity identifier
- **EntityIDGenerator** - ID generation interface
- **EntitySpanningPolicy** - Policy for multi-node entities
- **LongEntityID**, **UUIDEntityID** - Concrete ID types
- **SequentialLongIDGenerator**, **UUIDGenerator** - ID generators

### Lock-Free Package (3) - July 2025

High-performance concurrent operations using atomic protocols:

- **LockFreeEntityMover** - Four-phase atomic movement protocol achieving 264K movements/sec with 4 threads
- **AtomicSpatialNode** - Lock-free spatial node using CopyOnWriteArraySet and atomic operations
- **VersionedEntityState** - Immutable versioned state for optimistic concurrency control with ABA prevention

### Octree Package (6)

- **Octree** - Morton curve-based spatial index
- **MortonKey** - Space-filling curve key using Morton encoding
- **OctreeBalancer** - Tree balancing implementation
- **OctreeSubdivisionStrategy** - Subdivision policy for octree nodes
- **Octant** - Octant enumeration and utilities
- **internal/NodeDistance** - Node distance utilities

### Prism Package (8)

Core classes:

- **Prism** - Anisotropic spatial index with triangular/linear subdivision
- **PrismKey** - Composite spatial key combining Triangle and Line indices
- **PrismGeometry** - Geometric operations for triangular coordinate systems

Primitive types:

- **Line** - Linear subdivision primitive for vertical axis
- **Triangle** - Right triangle primitive for horizontal plane subdivision

Specialized operations:

- **PrismCollisionDetector** - Collision detection optimized for anisotropic geometry
- **PrismRayIntersector** - Ray intersection with triangular/linear space partitioning
- **PrismNeighborFinder** - Neighbor traversal across triangular and linear boundaries

### Tetree Package (34)

Core classes:

- **Tetree** - Tetrahedral tree with positive coordinate constraints
- **Tet** - Tetrahedron representation with SFC indexing
- **TetrahedralGeometry** - Geometric operations on tetrahedra
- **TetrahedralSearchBase** - Base class for tetrahedral queries

Key implementations (4):

- **TetreeKey** - Abstract base class for tetrahedral space-filling curve keys
- **CompactTetreeKey** - Single-long optimization for levels 0-10 (64-bit storage)
- **ExtendedTetreeKey** - Full implementation supporting levels 0-21 (128-bit storage with level 21 bit packing)
- **LazyTetreeKey** - Lazy evaluation variant for deferred computation

Performance optimizations (7):

- **TetreeLevelCache** - O(1) level extraction and parent caching
- **TetreeRegionCache** - Regional caching for queries
- **SpatialLocalityCache**, **ThreadLocalTetreeCache** - Thread-local optimizations
- **TetreeMetrics** - Performance monitoring
- **TetOptimized** - Optimized tetrahedron operations

Subdivision classes (3):

- **TetrahedralSubdivision**, **BeySubdivision** - Tetrahedral subdivision algorithms
- **TetreeSubdivisionStrategy** - Subdivision policy

Additional utilities (14):

- **TetreeBits**, **TetreeConnectivity**, **TetreeFamily** - Bit operations and connectivity
- **TetreeHelper**, **TetreeIterator**, **TetreeLUT** - Helper utilities
- **TetreeNeighborFinder**, **TetreeSFCRayTraversal** - Advanced search
- **TetreeValidator**, **TetreeValidationUtils** - Validation utilities
- **TmIndex**, **SimpleTMIndex**, **TMIndex128Clean** - Index implementations
- **PluckerCoordinate** - Ray intersection optimization

Lazy evaluation support (4):

- **LazyRangeIterator** - O(1) memory iterator for TetreeKey ranges
- **LazySFCRangeStream** - Stream API integration with lazy semantics
- **RangeHandle** - Deferred computation for spatial queries
- **RangeQueryVisitor** - Tree-based traversal with early termination

### Forest Package (16) - Complete Multi-Tree Architecture

The Forest package provides a comprehensive multi-tree spatial indexing solution designed for large-scale applications
requiring distributed spatial data management. Supports both simple grid partitioning and sophisticated
adaptive/hierarchical forests.

**Core Forest Management (4 classes):**

- **Forest** - Main forest coordinator with thread-safe collections (CopyOnWriteArrayList, ConcurrentHashMap)
- **TreeNode** - Wrapper providing forest-specific metadata, neighbor tracking, and global bounds management
- **TreeMetadata** - Immutable metadata with builder pattern (name, type, creation time, custom properties)
- **TreeLocation** - Spatial positioning and bounds tracking for trees within forest coordinate system

**Entity and Load Management (3 classes):**

- **ForestEntityManager** - Cross-tree entity lifecycle with automatic tree assignment strategies (RoundRobin,
  SpatialBounds, LoadBalanced)
- **ForestLoadBalancer** - Real-time load metrics and automatic rebalancing (entity count, memory usage, query load
  thresholds)
- **DynamicForestManager** - Runtime tree operations with background processing (splitting, merging, entity migration)

**Specialized Forest Types (3 classes):**

- **GridForest** - Uniform spatial partitioning with factory methods for both Octree and Tetree grids
- **AdaptiveForest** - Dynamic density-based adaptation with multiple subdivision strategies (Octant, Binary, K-means,
  Adaptive)
- **HierarchicalForest** - Level-of-detail management with distance-based entity promotion/demotion and configurable LOD
  distances

**Query and Connectivity (4 classes):**

- **ForestQuery** - Unified query interface for single-tree targeting and forest-wide operations
- **ForestSpatialQueries** - Parallel spatial queries (k-NN, range, ray intersection, frustum culling) with configurable
  thread counts
- **TreeConnectivityManager** - Spatial relationship management with adjacency detection and shared boundary analysis
- **GhostZoneManager** - Boundary entity synchronization with configurable ghost zone widths and bulk update operations

**Configuration (2 classes):**

- **ForestConfig** - Builder-pattern configuration (overlapping policies, ghost zones, partition strategies, background
  management)
- **AdaptiveForestEntityManager** - Enhanced entity manager for adaptive forests with integrated density tracking

**Forest Features:**

- **Multi-Tree Coordination**: Unified operations across collections of spatial index trees
- **Dynamic Management**: Automatic tree splitting/merging based on configurable strategies
- **Grid Partitioning**: Uniform spatial partitioning for large-scale applications
- **Ghost Zones**: Boundary handling for seamless cross-tree queries
- **Load Balancing**: Distribution strategies for optimal performance
- **Entity Migration**: Transparent entity movement between trees during reorganization
- **Neighbor Tracking**: Spatial relationship management between adjacent trees
- **Statistics and Monitoring**: Comprehensive statistics gathering and performance tracking
- **Thread Safety**: Concurrent access support for all forest operations

## Performance Characteristics

### Time Complexity

- Insert: O(1) average (HashMap access)
- Remove: O(1) average
- Lookup: O(1) average
- k-NN: O(k log k + visited nodes)
- Range Query: O(nodes in range)

### Space Complexity

- O(n) for n entities
- Additional O(m) for m non-empty nodes
- Sorted indices add O(m) space

### Implementation-Specific Performance

**Octree:**

- O(1) average node access (HashMap-based)
- Morton curve preserves spatial locality
- Efficient range queries via sorted Morton codes
- Supports dynamic node subdivision

**Tetree:**

- O(1) average node access (HashMap-based as of January 2025)
- Tetrahedral SFC for positive coordinates only
- More complex geometry calculations
- Subdivision support through tetrahedral subdivision

**Prism:**

- O(1) average node access (HashMap-based)
- Anisotropic subdivision for specialized use cases
- Triangular constraint limits coordinate space
- Optimized for terrain and height-stratified data

## Design Principles

### 1. Generic Type System with SpatialKey

All spatial classes use consistent generics with type-safe keys:

```java
public class SpatialClass<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    // Key: Type-safe spatial key (MortonKey or ExtendedTetreeKey)
    // ID: Entity identifier type
    // Content: User-defined content type
}
```

**SpatialKey Architecture**:

- `MortonKey`: Wraps long Morton code for Octree
- `TetreeKey`: Encodes (level, sfcIndex) tuple for Tetree with support for 21 levels matching Octree capacity
- `PrismKey`: Composite key combining Triangle and Line indices for anisotropic subdivision
- Type safety prevents mixing incompatible keys
- Maintains spatial locality and comparable semantics

### 2. Template Method Pattern

AbstractSpatialIndex defines the algorithm structure:

- Common operations in base class
- Spatial-specific logic in abstract methods
- Concrete implementations provide specialization

### 3. Multi-Entity Support

Built into the core architecture:

- Multiple entities per spatial location
- Entities can span multiple nodes
- Efficient entity-to-node mapping

### 4. Separation of Concerns

- Spatial indexing logic separate from entity management
- Node storage abstracted from spatial operations
- Geometry utilities independent of data structures

## Design Benefits

1. **Code Reuse**: ~90% of functionality shared through inheritance
2. **Consistency**: Both implementations follow identical patterns
3. **Maintainability**: Changes to core algorithms only needed in one place
4. **Type Safety**: Generic types ensure compile-time correctness
5. **Performance**: HashMap provides O(1) node access
6. **Flexibility**: Easy to add new spatial subdivision strategies

## TetreeKey Encoding Architecture

The TetreeKey system provides efficient spatial key encoding for tetrahedral subdivision with full 21-level support matching Octree capacity:

### Dual Implementation Strategy

**CompactTetreeKey (Levels 0-10)**:
- Single 64-bit long storage for optimal performance
- Handles 95%+ of typical use cases efficiently
- 6 bits per level encoding (3 coordinate + 3 type bits)
- Maximum 60 bits used (10 levels × 6 bits)

**ExtendedTetreeKey (Levels 0-21)**:
- Dual 64-bit long storage (128-bit total)
- Standard encoding for levels 0-20
- Special bit packing for level 21 using leftover bits

### Level 21 Bit Packing Implementation

Level 21 uses innovative split encoding to achieve full 21-level support:

```
Level 21 Encoding (6 bits total):
- 4 bits stored in low long positions 60-63
- 2 bits stored in high long positions 60-61
- Preserves space-filling curve ordering
- Enables efficient parent/child computation
```

**Key Features**:
- Maintains SFC ordering properties despite split encoding
- No performance penalty for levels 0-20
- Seamless factory method selection based on level
- Complete compatibility with existing tetrahedral operations

**Constants and Masks**:
```java
protected static final int  LEVEL_21_LOW_BITS_SHIFT = 60;  // Position in low long
protected static final int  LEVEL_21_HIGH_BITS_SHIFT = 60; // Position in high long  
protected static final long LEVEL_21_LOW_MASK = 0xFL;      // 4 bits: 0b1111
protected static final long LEVEL_21_HIGH_MASK = 0x3L;     // 2 bits: 0b11
```

This design achieves full Octree-equivalent refinement levels while maintaining the memory efficiency and performance characteristics of the tetrahedral space-filling curve.

## Architectural Simplification

The codebase underwent dramatic simplification in 2025, focusing on core spatial indexing:

### Key Removals

- All specialized search implementations (previously planned 11+ for each structure)
- All optimization layers and optimizer classes
- Spatial engine abstraction layer
- Parallel processing infrastructure
- Complex adapter pattern implementations

### Key Addition (June 2025)

- **SpatialKey Architecture**: Type-safe spatial keys to prevent index collisions
    - Resolves Tetree's non-unique SFC index issue
    - Provides type safety between Octree and Tetree operations
    - Maintains performance with minimal object allocation overhead

### What This Architecture Does NOT Include

- **No specialized search classes** - Search algorithms integrated into core classes
- **No optimizer classes** - Optimization is left to implementations
- **No spatial engine layer** - Direct use of spatial indices
- **No parallel processing classes** - Thread-safe through ReadWriteLock
- **No adapter patterns** - Clean inheritance instead

### Current Design Philosophy

The simplified architecture focuses on:

1. **Core Functionality**: Basic spatial indexing without specialized searches
2. **Entity-Centric**: Entities as first-class citizens with IDs and bounds
3. **Direct API**: No adapter layers or complex abstractions
4. **Minimal Implementation**: Only essential classes retained
5. **Multi-Tree Support**: Forest architecture for distributed and large-scale spatial indexing

## Usage Example

```java
// Create an octree
Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator(), 10,  // max entities per node
                                                   (byte) 20  // max depth
);

// Create a tetree (positive coordinates only)
Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, (byte) 20);

// Create a prism (triangular constraint: x + y < worldSize)
Prism<LongEntityID, String> prism = new Prism<>(new SequentialLongIDGenerator(), 10, (byte) 20, 1000.0f);

// Insert entities
LongEntityID id = new LongEntityID(1);
octree.

insert(id, new Point3f(100, 200,300), (byte)10,"Entity data");
tetree.

insert(id, new Point3f(100, 200,300), (byte)10,"Entity data");  // positive coordinates
prism.

insert(id, new Point3f(100, 200,300), (byte)10,"Entity data");   // x + y < worldSize

// k-NN search (same API across all implementations)
List<LongEntityID> nearest = octree.kNearestNeighbors(new Point3f(110, 210, 310), 5,  // find 5 nearest
                                                      1000.0f  // max distance
                                                     );

// Range query
Stream<SpatialNode<LongEntityID>> nodes = octree.boundedBy(new Spatial.Cube(0, 0, 0, 500));

// Forest usage with different spatial index types
Forest<MortonKey, LongEntityID, String> octreeForest = new Forest<>();
Forest<TetreeKey, LongEntityID, String> tetreeForest = new Forest<>();
Forest<PrismKey, LongEntityID, String> prismForest = new Forest<>();

// Add trees to forest
Octree<LongEntityID, String> tree1 = new Octree<>(new SequentialLongIDGenerator(), 10, (byte) 20);
Tetree<LongEntityID, String> tree2 = new Tetree<>(new SequentialLongIDGenerator(), 10, (byte) 20);
Prism<LongEntityID, String> tree3 = new Prism<>(new SequentialLongIDGenerator(), 10, (byte) 20, 1000.0f);

TreeMetadata metadata1 = TreeMetadata.builder().name("region_1").treeType(TreeMetadata.TreeType.OCTREE).property(
"region", "northeast").build();

String treeId1 = octreeForest.addTree(tree1, metadata1);
String treeId2 = tetreeForest.addTree(tree2);
String treeId3 = prismForest.addTree(tree3);

// Forest-wide k-NN search (type-specific)
List<LongEntityID> nearestAcrossOctrees = octreeForest.findKNearestNeighbors(new Point3f(100, 200, 300), 10);
List<LongEntityID> nearestAcrossPrisms = prismForest.findKNearestNeighbors(new Point3f(100, 200, 300),
                                                                           10);  // x + y < worldSize constraint applies

// Grid forest for uniform partitioning
GridForest<MortonKey, LongEntityID, String> gridForest = GridForest.createOctreeGrid(new Point3f(0, 0, 0),     // origin
                                                                                     new Vector3f(1000, 1000, 1000),
                                                                                     // total size
                                                                                     4, 4, 4
                                                                                     // 4x4x4 grid of trees
                                                                                    );

// Dynamic forest management
DynamicForestManager<MortonKey, LongEntityID, String> manager = new DynamicForestManager<>(forest, entityManager,
                                                                                           () -> new Octree<>(
                                                                                           new SequentialLongIDGenerator(),
                                                                                           10, (byte) 20));

// Enable automatic tree splitting/merging
manager.

enableAutoManagement(60000); // Check every minute
```

## Forest Architecture

The Forest package provides a complete multi-tree spatial indexing solution designed for large-scale applications that
require distributed spatial data management. The forest architecture enables:

### Use Cases

- **Distributed Spatial Indexing**: Partition large spatial datasets across multiple trees for better performance
- **Level-of-Detail Management**: Different trees for different detail levels or data types
- **Spatial Partitioning**: Divide space into regions handled by separate trees
- **Load Balancing**: Distribute entities across trees based on utilization
- **Dynamic Scaling**: Add/remove trees based on data distribution changes

### Key Architectural Features

1. **Forest Management**: Central coordination of multiple spatial index trees with unified query interfaces
2. **Tree Metadata**: Rich metadata support for tree identification, classification, and custom properties
3. **Neighbor Relationships**: Explicit neighbor tracking between spatially adjacent trees
4. **Ghost Zones**: Boundary region management for seamless cross-tree operations
5. **Dynamic Operations**: Runtime tree splitting, merging, and entity migration
6. **Grid Specialization**: Optimized uniform grid partitioning for regular spatial divisions
7. **Load Balancing**: Configurable strategies for optimal entity distribution
8. **Statistics and Monitoring**: Comprehensive performance tracking and forest health monitoring

### Thread Safety and Performance

The Forest architecture is designed for high-performance concurrent operations:

- **CopyOnWriteArrayList**: Thread-safe tree collection iteration
- **ConcurrentHashMap**: Fast tree lookup by ID
- **Atomic Counters**: Lock-free statistics tracking
- **Fine-Grained Locking**: Minimal synchronization overhead
- **Query Routing**: Efficient query distribution based on spatial bounds

## Recent Architecture Updates

### Concurrent Optimization Refactoring (July 11, 2025)

**Major architectural shift eliminating separate HashMap/TreeSet in favor of single ConcurrentSkipListMap:**

- **ConcurrentSkipListMap Replacement**: Single thread-safe data structure replaces spatialIndex HashMap +
  sortedSpatialIndices TreeSet
- **Memory Efficiency**: 54-61% reduction in memory usage through architectural consolidation
- **Thread Safety**: Eliminated ConcurrentModificationException during iteration
- **CopyOnWriteArrayList**: SpatialNodeImpl now uses thread-safe entity storage preventing iteration conflicts
- **Lock-Free Entity Updates**: Added atomic movement protocol with four-phase commit (PREPARE → INSERT → UPDATE →
  REMOVE)
- **ObjectPool Integration**: Extended pooling to all query operations (k-NN, collision, ray intersection, frustum
  culling)

### Phase 6.2 Cleanup (July 10, 2025)

- **Node Class Consolidation**: Eliminated `TetreeNodeImpl` and `OctreeNode`, created unified `SpatialNodeImpl`
- **Generic Parameter Reduction**: Reduced from 4 to 3 type parameters throughout
- **K-NN Multi-Level Fix**: Improved search radius expansion for complete results
- **Spanning Entity Fix**: Large entities now properly found by small query regions

### Performance Optimizations (July 2025)

- **Lazy Evaluation**: 99.5% memory reduction for large range queries
- **Parent Caching**: 17.3x speedup for parent() operations
- **V2 tmIndex**: 4x speedup over original implementation
- **Bulk Operations**: 15.5x speedup for batch insertion

### Core Features Complete

- **Ray Intersection**: Complete implementation with spatial optimization
- **Collision Detection**: Broad/narrow phase with physics integration
- **Tree Traversal API**: Visitor pattern with multiple strategies
- **Dynamic Tree Balancing**: Multiple strategies with monitoring
- **Entity Spanning**: Advanced policies for large entities
- **Plane Intersection**: Arbitrary 3D plane queries
- **Frustum Culling**: View frustum visibility for graphics
- **Dynamic Level Selection**: Automatic optimization
- **Forest Management**: Multi-tree coordination with dynamic operations
- **Grid Forests**: Specialized uniform spatial partitioning

### Phase 4: Comprehensive Documentation ✅

- **10 API Documentation Files**: Complete coverage of all features
- **Performance Testing Framework**: Automated benchmarking
- **Architecture Documentation**: Updated to reflect current state

## Choosing the Right Spatial Index

The unified architecture allows easy switching between spatial index types based on application requirements:

### Octree - General Purpose Spatial Indexing

**Use Octree when:**

- Working with general 3D spatial data without geometric constraints
- Need support for negative coordinates
- Uniform data distribution across all dimensions
- Range queries are the primary operation
- Working with volumetric data or 3D objects

**Advantages:**

- No coordinate constraints
- Fastest range queries (1.4-6x faster than Tetree)
- Mature Morton curve optimizations
- Uniform cubic subdivision

**Trade-offs:**

- Higher memory usage (27-35% more than Tetree)
- Slower insertion performance (2-6x slower than Tetree)

### Tetree - Memory-Efficient Advanced Indexing

**Use Tetree when:**

- Memory efficiency is critical
- k-NN searches are primary operations
- Working with positive coordinate space only
- Need advanced geometric operations
- Complex neighbor relationships required

**Advantages:**

- Superior memory efficiency (20-25% of Octree memory usage)
- Faster k-NN searches (1.6-5.9x faster)
- Advanced tetrahedral geometric operations
- Better insertion performance (2-6x faster)

**Trade-offs:**

- Positive coordinates only constraint
- More complex geometric calculations
- Slower range queries

### Prism - Specialized Anisotropic Indexing

**Use Prism when:**

- Working with terrain or height-stratified data
- Fine horizontal resolution with coarse vertical binning needed
- Data naturally fits triangular coordinate constraints (x + y < worldSize)
- 2.5D applications or layered data visualization
- Urban planning or geographic information systems

**Advantages:**

- Optimized for anisotropic data patterns
- Efficient triangular coordinate system
- Specialized collision detection for terrain
- Natural height stratification

**Trade-offs:**

- Limited to triangular coordinate constraint
- Specialized use case optimization
- Less general-purpose than Octree/Tetree

### Decision Matrix

| Criterion                  | Octree | Tetree        | Prism             |
|----------------------------|--------|---------------|-------------------|
| **Coordinate Constraints** | None   | Positive only | x + y < worldSize |
| **Memory Usage**           | High   | Low           | Medium            |
| **Insertion Speed**        | Slow   | Fast          | Medium            |
| **k-NN Performance**       | Medium | Fast          | Medium            |
| **Range Queries**          | Fast   | Slow          | Medium            |
| **Use Case Generality**    | High   | High          | Specialized       |
| **Geometric Complexity**   | Low    | High          | Medium            |

## Performance Characteristics (July 11, 2025)

**Major Performance Reversal**: Concurrent optimizations have completely reversed performance characteristics. Tetree is
now superior for most operations.

### Individual Operations (Current Metrics)

| Operation                 | Octree          | Tetree          | Prism           | Winner                                        |
|---------------------------|-----------------|-----------------|-----------------|-----------------------------------------------|
| **Insertion**             | 1.5-2.0 μs/op   | 0.24-0.95 μs/op | 0.8-1.3 μs/op   | **Tetree 2-6x faster**                        |
| **k-NN Query**            | 15.8-18.2 μs/op | 7.8-19.0 μs/op  | 10.5-16.2 μs/op | **Mixed, Tetree better for smaller datasets** |
| **Range Query**           | 2.1-14.2 μs/op  | 13.0-19.9 μs/op | 5.8-12.1 μs/op  | **Octree fastest, Prism second**              |
| **Memory Usage**          | 100%            | 65-73%          | 78-85%          | **Tetree 27-35% less memory**                 |
| **Triangular Queries**    | N/A             | N/A             | 3.2-7.8 μs/op   | **Prism specialized optimization**            |
| **Height Stratification** | Standard        | Standard        | Optimized       | **Prism for layered data**                    |

### Concurrent Architecture Benefits (July 2025)

- **ConcurrentSkipListMap**: 54-61% memory reduction vs dual HashMap/TreeSet
- **Lock-Free Operations**: Zero blocking for read operations, optimistic updates
- **ObjectPool Integration**: Reduced GC pressure across all query operations
- **Atomic Movement Protocol**: Four-phase atomic entity movement with conflict resolution

Key optimizations implemented:

- V2 tmIndex: 4x speedup in tmIndex computation (June 28)
- Parent cache: 17-67x speedup for parent operations (June 28)
- Bulk operations: Deferred subdivision provides massive benefits (June 28)
- Efficient child computation: 3x speedup for single child lookups (July 5)
- Lazy evaluation: 99.5% memory reduction for large ranges (July 8)

## Testing

The module includes comprehensive test coverage:

- 109 test files (many containing multiple test methods)
- Unit tests for all major operations
- Integration tests for spatial queries
- Performance benchmarks (controlled by environment flag)
- Thread-safety tests for concurrent operations
- API-specific test suites for all features

## Current State

As of July 2025, the lucien module represents a complete spatial indexing solution with:

- Three distinct spatial indexing strategies (Octree, Tetree, Prism)
- Unified API enabling easy switching between implementations
- All planned enhancements implemented
- Comprehensive API documentation
- Proven performance characteristics across all three approaches
- Robust test coverage
- Specialized optimization for different data patterns and use cases

The architecture successfully balances simplicity with advanced features, providing both ease of use and high
performance for diverse 3D spatial indexing needs. The addition of Prism extends the system to handle anisotropic data
patterns, completing the coverage of major spatial subdivision strategies.
