# Lucien Module Architecture (June 2025 - Final Update)

## Overview

The lucien module provides spatial indexing capabilities through a unified architecture that supports both octree (
cubic) and tetree (tetrahedral) decomposition strategies. Following a major consolidation in January 2025, the module
now uses inheritance to maximize code reuse while maintaining the unique characteristics of each spatial indexing
approach.

The Luciferase codebase underwent dramatic architectural simplification in 2025, focusing on core spatial indexing
functionality with entity management as the primary abstraction. The system has been refocused to eliminate complex
abstractions while maintaining full spatial indexing capabilities.

The module consists of 96 Java files organized across 8 packages, providing a comprehensive spatial indexing system with advanced features including collision detection, tree balancing, and visitor patterns. As of June 2025, all planned enhancements have been successfully implemented.

## Package Structure

```
com.hellblazer.luciferase.lucien/
├── Root package (27 classes + 2 images)
│   ├── Core abstractions: SpatialIndex, AbstractSpatialIndex, 
│   │                      SpatialNodeStorage, AbstractSpatialNode, SpatialKey
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
├── octree/ (5 classes)
│   ├── Octree - Main implementation
│   ├── OctreeNode - Node storage
│   ├── MortonKey - Space-filling curve key
│   ├── Octant - Octant enumeration
│   └── OctreeSubdivisionStrategy - Subdivision policy
├── tetree/ (30 classes)
│   ├── Core: Tetree, TetreeNodeImpl, Tet, TetrahedralGeometry, TetrahedralSearchBase
│   ├── Keys: TetreeKey, BaseTetreeKey, CompactTetreeKey, LazyTetreeKey
│   ├── Indexing: TmIndex, SimpleTMIndex, TMIndex128Clean
│   ├── Caching: TetreeLevelCache, TetreeRegionCache, SpatialLocalityCache, ThreadLocalTetreeCache
│   ├── Utilities: TetreeBits, TetreeConnectivity, TetreeFamily, TetreeHelper, 
│   │              TetreeIterator, TetreeLUT, TetreeMetrics, TetreeNeighborFinder
│   └── Advanced: TetreeSFCRayTraversal, TetreeSubdivisionStrategy, TetreeValidator,
│                 TetreeValidationUtils, PluckerCoordinate, TetOptimized
├── balancing/ (3 classes)
│   ├── TreeBalancer - Main balancing interface
│   ├── TreeBalancingStrategy - Strategy pattern
│   └── DefaultBalancingStrategy - Default implementation
├── collision/ (12 classes)
│   ├── Core: CollisionSystem, CollisionShape, CollisionResponse
│   ├── Shapes: BoxShape, SphereShape, CapsuleShape, OrientedBoxShape
│   └── Support: CollisionFilter, CollisionListener, CollisionResolver,
│                CollisionShapeFactory, PhysicsProperties
├── visitor/ (6 classes)
│   ├── TreeVisitor - Visitor interface
│   ├── AbstractTreeVisitor - Base implementation
│   ├── Concrete: EntityCollectorVisitor, NodeCountVisitor
│   └── Support: TraversalContext, TraversalStrategy
└── index/ (1 class)
    └── TMIndexSimple - Simplified TM-index implementation
```

## Class Hierarchy

### Core Inheritance Structure

```
SpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content> (interface)
    └── AbstractSpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content, 
                            NodeType extends SpatialNodeStorage<ID>>
            ├── Octree<ID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content, OctreeNode<ID>>
            └── Tetree<ID, Content> extends AbstractSpatialIndex<TetreeKey, ID, Content, TetreeNodeImpl<ID>>
```

### Node Storage Hierarchy

```
SpatialNodeStorage<ID> (interface)
    └── AbstractSpatialNode<ID>
            ├── OctreeNode<ID> (List-based storage)
            └── TetreeNodeImpl<ID> (Set-based storage)
```

## AbstractSpatialIndex - The Foundation

The `AbstractSpatialIndex` class contains the majority of spatial indexing functionality:

### Common State

- `spatialIndex: Map<Key, NodeType>` - Main spatial storage mapping spatial keys to nodes
- `sortedSpatialIndices: NavigableSet<Key>` - Sorted keys for efficient range queries (uses SpatialIndexSet)
- `entityManager: EntityManager<ID, Content>` - Centralized entity lifecycle management
- `maxEntitiesPerNode: int` - Threshold for node subdivision
- `maxDepth: byte` - Maximum tree depth
- `spanningPolicy: EntitySpanningPolicy` - Controls entity spanning behavior

### Common Operations

- **Entity Management**: insert(), remove(), update(), lookup()
- **Spatial Queries**: boundedBy(), bounding(), enclosing()
- **k-NN Search**: Complete k-nearest neighbor implementation
- **Range Queries**: Optimized spatial range query with customizable index calculation
- **Node Lifecycle**: insertAtPosition(), onNodeRemoved(), handleNodeSubdivision()

### Abstract Methods (Implementation-Specific)

- `calculateSpatialIndex(Point3f, byte level)` - Convert position to spatial index
- `createNode()` - Create implementation-specific node type
- `getLevelFromIndex(long)` - Extract level from spatial index
- `getNodeBounds(long)` - Get spatial bounds of a node
- `validateSpatialConstraints()` - Validate coordinate constraints
- `shouldContinueKNNSearch()` - k-NN search continuation logic
- `addNeighboringNodes()` - Neighbor traversal for searches

## Octree Implementation

The `Octree` class provides Morton curve-based cubic spatial decomposition:

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

The `Tetree` class provides tetrahedral spatial decomposition:

### Unique Characteristics

- Uses tetrahedral space-filling curve
- Requires positive coordinates only
- 6 tetrahedra per grid cell
- Complex neighbor relationships

### Key Methods

- `calculateSpatialIndex()` - Uses Tet.locate() for tetrahedral index
- `computeSFCRanges()` - Tetrahedral SFC range calculation
- `addNeighboringNodes()` - Tetrahedral neighbor traversal
- `validatePositiveCoordinates()` - Enforces positive coordinate constraint

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

### Root Package Classes (13)

**Core Abstractions:**

- **SpatialIndex** - Main interface defining spatial operations
- **AbstractSpatialIndex** - Base implementation with common functionality
- **SpatialNodeStorage** - Interface for node storage strategies
- **AbstractSpatialNode** - Base node implementation

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

### Octree Package (5)

- **Octree** - Morton curve-based spatial index
- **OctreeNode** - List-based entity storage per node
- **MortonKey** - Space-filling curve key using Morton encoding
- **Octant** - Octant enumeration and utilities
- **OctreeSubdivisionStrategy** - Subdivision policy for octree nodes

### Tetree Package (30)

Core classes:
- **Tetree** - Tetrahedral tree with positive coordinate constraints
- **TetreeNodeImpl** - Set-based entity storage per node
- **Tet** - Tetrahedron representation with SFC indexing
- **TetrahedralGeometry** - Geometric operations on tetrahedra
- **TetrahedralSearchBase** - Base class for tetrahedral queries

Key implementations (4):
- **TetreeKey** - Main space-filling curve key
- **BaseTetreeKey**, **CompactTetreeKey**, **LazyTetreeKey** - Optimized key variants

Performance optimizations (7):
- **TetreeLevelCache** - O(1) level extraction and parent caching
- **TetreeRegionCache** - Regional caching for queries
- **SpatialLocalityCache**, **ThreadLocalTetreeCache** - Thread-local optimizations
- **TetreeMetrics** - Performance monitoring
- **TetOptimized** - Optimized tetrahedron operations

Additional utilities (14):
- **TetreeBits**, **TetreeConnectivity**, **TetreeFamily** - Bit operations and connectivity
- **TetreeHelper**, **TetreeIterator**, **TetreeLUT** - Helper utilities
- **TetreeNeighborFinder**, **TetreeSFCRayTraversal** - Advanced search
- **TetreeSubdivisionStrategy**, **TetreeValidator**, **TetreeValidationUtils**
- **TmIndex**, **SimpleTMIndex**, **TMIndex128Clean** - Index implementations
- **PluckerCoordinate** - Ray intersection optimization

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
- Subdivision support through tetrahedral decomposition

## Design Principles

### 1. Generic Type System with SpatialKey

All spatial classes use consistent generics with type-safe keys:

```java
public class SpatialClass<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    // Key: Type-safe spatial key (MortonKey or TetreeKey)
    // ID: Entity identifier type
    // Content: User-defined content type
}
```

**SpatialKey Architecture**:

- `MortonKey`: Wraps long Morton code for Octree
- `TetreeKey`: Encodes (level, sfcIndex) tuple for Tetree
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
6. **Flexibility**: Easy to add new spatial decomposition strategies

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

Unlike earlier documentation that described 60+ classes, the actual implementation contains 34 classes:

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

## Usage Example

```java
// Create an octree
Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator(), 10,  // max entities per node
                                                   (byte) 20  // max depth
);

// Insert entities
LongEntityID id = new LongEntityID(1);
octree.insert(id, new Point3f(100, 200, 300), (byte)10, "Entity data");

// k-NN search
List<LongEntityID> nearest = octree.kNearestNeighbors(new Point3f(110, 210, 310), 5,  // find 5 nearest
                                                      1000.0f  // max distance
                                                     );

// Range query
Stream<SpatialNode<LongEntityID>> nodes = octree.boundedBy(new Spatial.Cube(0, 0, 0, 500));
```

## Completed Enhancements (June 2025)

### Phase 1: Essential Search Algorithms ✅

- **Ray Intersection**: Complete implementation with spatial optimization
- **Collision Detection**: Broad/narrow phase with physics integration
- **Tree Traversal API**: Visitor pattern with multiple strategies

### Phase 2: Performance Optimizations ✅

- **Dynamic Tree Balancing**: Multiple strategies with monitoring
- **Entity Spanning**: Advanced policies for large entities
- **O(1) Operations**: SpatialIndexSet replaces TreeSet
- **TetreeLevelCache**: Eliminates O(log n) calculations

### Phase 3: Additional Features ✅

- **Plane Intersection**: Arbitrary 3D plane queries
- **Frustum Culling**: View frustum visibility for graphics
- **Bulk Operations**: 5-10x performance improvement
- **Dynamic Level Selection**: Automatic optimization

### Phase 4: Comprehensive Documentation ✅

- **10 API Documentation Files**: Complete coverage of all features
- **Performance Testing Framework**: Automated benchmarking
- **Architecture Documentation**: Updated to reflect current state

## Performance Characteristics (June 28, 2025)

**BREAKTHROUGH**: With V2 tmIndex optimization and parent cache, Tetree now outperforms Octree for bulk loading at large scales!

### Individual Operations
- **Insertion**: Octree 3-5x faster due to O(1) Morton encoding
- **k-NN Search**: Tetree 2.9x faster due to better spatial locality
- **Range Query**: Octree 7.7x faster
- **Memory**: Tetree uses 74-76% less memory

### Bulk Loading (The Game Changer)
- **50K entities**: Tetree 35% faster than Octree
- **100K entities**: Tetree 38% faster than Octree

Key optimizations implemented:
- V2 tmIndex: 4x speedup in tmIndex computation
- Parent cache: 17-67x speedup for parent operations
- Bulk operations: Deferred subdivision provides massive benefits

## Testing

The module includes comprehensive test coverage:

- 109 test files (many containing multiple test methods)
- Unit tests for all major operations
- Integration tests for spatial queries
- Performance benchmarks (controlled by environment flag)
- Thread-safety tests for concurrent operations
- API-specific test suites for all features

## Current State

As of June 2025, the lucien module represents a complete spatial indexing solution with:

- All planned enhancements implemented
- Comprehensive API documentation
- Proven performance characteristics
- Robust test coverage

The architecture successfully balances simplicity with advanced features, providing both ease of use and high
performance for 3D spatial indexing needs.
