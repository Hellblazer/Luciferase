# Lucien Module Architecture (June 2025 - Final Update)

## Overview

The lucien module provides spatial indexing capabilities through a unified architecture that supports both octree (
cubic) and tetree (tetrahedral) decomposition strategies. Following a major consolidation in January 2025, the module
now uses inheritance to maximize code reuse while maintaining the unique characteristics of each spatial indexing
approach.

The Luciferase codebase underwent dramatic architectural simplification in 2025, focusing on core spatial indexing
functionality with entity management as the primary abstraction. The system has been refocused to eliminate complex
abstractions while maintaining full spatial indexing capabilities.

The module consists of 34 core Java classes plus additional support classes for advanced features, organized in a clean
package hierarchy, prioritizing simplicity and correctness
over advanced features. As of June 2025, all planned enhancements have been successfully implemented.

## Package Structure

```
com.hellblazer.luciferase.lucien/
├── Root package (13 classes + 2 images)
│   ├── Core abstractions: SpatialIndex, AbstractSpatialIndex, 
│   │                      SpatialNodeStorage, AbstractSpatialNode
│   ├── Spatial types: Spatial, VolumeBounds
│   ├── Geometry: Frustum3D, Plane3D, Ray3D, Simplex
│   ├── Utilities: Constants, VisibilitySearch
│   └── Resources: reference-cube.png, reference-simplexes.png
├── entity/ (12 classes)
│   ├── Core: Entity, EntityBounds, EntityData, EntityDistance
│   ├── Identity: EntityID, EntityIDGenerator
│   ├── Management: EntityManager, EntitySpanningPolicy
│   └── Implementations: LongEntityID, UUIDEntityID,
│                       SequentialLongIDGenerator, UUIDGenerator
├── octree/ (3 classes)
│   ├── Octree - Main implementation
│   ├── OctreeNode - Node storage
│   └── Octant - Octant enumeration
└── tetree/ (6 classes)
    ├── Tetree - Main implementation
    ├── TetreeNodeImpl - Node storage
    ├── Tet - Tetrahedron representation
    ├── TetrahedralGeometry - Geometric operations
    ├── TetrahedralSearchBase - Search operations
    └── TetreeHelper - Utility functions
```

## Class Hierarchy

### Core Inheritance Structure

```
SpatialIndex<ID extends EntityID, Content> (interface)
    └── AbstractSpatialIndex<ID, Content, NodeType extends SpatialNodeStorage<ID>>
            ├── Octree<ID, Content> extends AbstractSpatialIndex<ID, Content, OctreeNode<ID>>
            └── Tetree<ID, Content> extends AbstractSpatialIndex<ID, Content, TetreeNodeImpl<ID>>
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

- `spatialIndex: Map<Long, NodeType>` - Main spatial storage mapping indices to nodes
- `sortedSpatialIndices: NavigableSet<Long>` - Sorted indices for efficient range queries
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

### Octree Package (3)

- **Octree** - Morton curve-based spatial index
- **OctreeNode** - List-based entity storage per node
- **Octant** - Octant enumeration and utilities

### Tetree Package (6)

- **Tetree** - Tetrahedral tree with positive coordinate constraints
- **TetreeNodeImpl** - Set-based entity storage per node
- **Tet** - Tetrahedron representation with SFC indexing
- **TetreeHelper** - Utility functions
- **TetrahedralGeometry** - Geometric operations on tetrahedra
- **TetrahedralSearchBase** - Base class for tetrahedral queries

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

## Performance Characteristics (Post-Subdivision Fix - June 28, 2025)

**Key Findings**: After fixing Tetree's subdivision bug, performance characteristics are now correct

Source: OctreeVsTetreeBenchmark.java (after subdivision fix)

- **Insertion Performance**: Octree is 6x to 35x faster (was 770x due to bug)
- **Query Performance**: Tetree is 3-4x faster for k-NN and range queries
- **Memory Usage**: Now comparable (92-103%) - was 20% due to improper subdivision
- **Subdivision Fix**: Tetree was creating only 2 nodes for 1000 entities instead of proper tree structure
- **Remaining Gap**: Due to fundamental O(1) vs O(level) algorithmic difference

## Testing

The module includes comprehensive test coverage:

- 200+ total tests with complete feature coverage
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
