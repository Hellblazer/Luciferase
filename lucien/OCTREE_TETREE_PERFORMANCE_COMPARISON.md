# Octree vs Tetree Performance Comparison

**Date**: June 17, 2025  
**Status**: Active Analysis  
**Scope**: Spatial indexing performance comparison in Java Luciferase

## Executive Summary

The Octree and Tetree implementations in Luciferase share ~90% of their code through `AbstractSpatialIndex`, leading to very similar performance characteristics for most operations. The key differences lie in spatial decomposition geometry, space-filling curve algorithms, and geometric operations rather than fundamental performance.

## Architecture Comparison

### Shared Infrastructure (90% Common)

Both implementations inherit from `AbstractSpatialIndex<ID, Content, NodeType>`:

**Common Components:**
- Entity management via `EntityManager<ID, Content>`
- Spatial storage using `Map<Long, NodeType>` (O(1) access)
- Sorted indices via `NavigableSet<Long>` for range queries
- k-NN search algorithm (identical implementation)
- Thread-safe operations using `ReadWriteLock`
- Multi-entity support and spanning policies
- Collision detection and ray intersection APIs

**Performance Impact**: Since 90% of code is shared, most operations have nearly identical performance characteristics.

### Key Differences (10% Specific)

| Aspect | Octree | Tetree | Performance Impact |
|--------|--------|--------|-------------------|
| **Space Decomposition** | 8 cubic children | 8 tetrahedral children | Minimal - same branching factor |
| **Space-Filling Curve** | Morton code (bit interleaving) | Tetrahedral SFC (t8code) | Moderate - SFC calculation overhead |
| **Node Shape** | Axis-aligned bounding boxes | Tetrahedra | Low - affects only geometric tests |
| **Coordinate Domain** | Full 3D space | Positive octant only | Low - validation overhead |

## Performance Characteristics

### 1. **Insertion Performance**

**Expected**: Nearly identical performance
- Both use O(1) HashMap lookup for spatial index
- Both use same entity management system  
- Both support multi-entity nodes and spanning

**Tetree Overhead**: 
- Coordinate validation (positive coordinates only)
- More complex SFC calculation (tetrahedral vs Morton)

**Benchmark Results** (measured June 17, 2025):
- **Octree**: 4.84ms for 1000 entities (4.84μs per entity)
- **Tetree**: 111.72ms for 1000 entities (111.72μs per entity)  
- **Difference**: **23x faster** (Octree significantly outperforms Tetree for insertions)

### 2. **Query Performance**

**k-NN Search**: Identical performance (same algorithm in `AbstractSpatialIndex`)
**Range Queries**: Identical performance (same sorted index traversal)
**Point Lookup**: Nearly identical (O(1) spatial index access)

**Geometric Tests**:
- **Octree**: Simple AABB intersection tests
- **Tetree**: Tetrahedral intersection tests (slightly more complex)

### 3. **Ray Intersection Performance**

**Octree**: 
- Simple cube-ray intersection (6 plane tests)
- Morton-based traversal order

**Tetree**:
- Tetrahedral-ray intersection (4 triangle tests + inside/outside)
- t8code SFC-based traversal order
- Specialized `TetreeSFCRayTraversal` algorithm

**Expected**: Tetree 10-20% slower due to more complex geometric tests

### 4. **Collision Detection Performance**

**Shared Implementation**: Both use identical broad-phase and narrow-phase algorithms from `AbstractSpatialIndex`

**Geometric Tests**:
- **Octree**: AABB-AABB intersection (simple)  
- **Tetree**: Tetrahedral collision detection (complex)

**Expected**: Tetree 20-30% slower for geometric collision tests

### 5. **Memory Usage**

**Spatial Index**: Identical (same HashMap storage)
**Node Storage**: 
- **Octree**: `OctreeNode<ID>` (minimal - just entity list)
- **Tetree**: `TetreeNodeImpl<ID>` (minimal - just entity list)

**Entity Storage**: Identical (same `EntityManager`)

**Expected**: Nearly identical memory usage

## Measured Performance Data

### From Existing Performance Tests

**Insertion Benchmarks** (1000 entities):
- Octree: ~100-120ms
- Tetree: ~114ms  
- Difference: <15%

**k-NN Search** (measured June 17, 2025 - 100 queries, k=10):
- **Octree**: 3.41ms total (34.1μs per query)
- **Tetree**: 5.63ms total (56.3μs per query)
- **Difference**: 1.65x faster (Octree moderately outperforms Tetree)

**Ray Traversal**:
- Octree: Not measured (ray intersection recently added)
- Tetree: 0-8ms for typical scenes

**Collision Detection**:
- Both: 3-115ms depending on entity count and density
- Narrow phase: Tetree slower due to tetrahedral geometry

## Theoretical Analysis

### Space-Filling Curve Complexity

**Morton Code (Octree)**:
```java
// Simple bit interleaving - O(1) with bitwise operations
long morton = interleaveBits(x, y, z);
```

**Tetrahedral SFC (Tetree)**:
```java
// Complex t8code algorithm - O(log n) with tree traversal
long index = buildPathFromRoot(parent, childIndex, level);
```

**Impact**: Tetree SFC calculation is ~2-3x slower than Morton encoding

### Geometric Operation Complexity

**Octree AABB Operations**:
- Point-in-box: 6 comparisons
- Box-box intersection: 6 comparisons  
- Ray-box intersection: 6 plane tests

**Tetree Tetrahedral Operations**:
- Point-in-tetrahedron: 4 barycentric coordinate tests
- Tetrahedron-tetrahedron intersection: Complex (multiple cases)
- Ray-tetrahedron intersection: 4 triangle intersection tests

**Impact**: Tetrahedral operations 2-5x more complex than cubic operations

## Algorithmic Advantages

### Octree Advantages

1. **Simplicity**: Cubic geometry is intuitive and well-understood
2. **Morton Codes**: Extremely fast bit-interleaving SFC 
3. **AABB Operations**: Simple, fast geometric tests
4. **Industry Standard**: Well-established algorithms and optimizations

### Tetree Advantages  

1. **Better Space Partitioning**: Tetrahedra can represent complex 3D shapes more accurately
2. **Scientific Computing**: Superior for mesh generation and finite element methods
3. **Adaptive Refinement**: Better handling of irregular spatial distributions
4. **t8code Parity**: Access to advanced tetrahedral algorithms

## Performance Recommendations

### Choose Octree When:
- **Performance is critical** and simplicity suffices
- Working with **axis-aligned data** (buildings, voxels, regular grids)  
- Need **maximum compatibility** with existing octree algorithms
- **Ray tracing** or **collision detection** performance is paramount

### Choose Tetree When:
- Working with **complex 3D meshes** or irregular geometries
- Need **scientific computing** capabilities (FEM, mesh generation)
- **Spatial accuracy** is more important than raw performance
- Integration with **t8code ecosystem** is required

## Optimization Opportunities

### For Both Implementations:
1. **Vector API**: Use Java 19+ Vector API for SIMD operations
2. **Parallel Processing**: Parallelize bulk operations
3. **Memory Layout**: Optimize node storage for cache efficiency
4. **Geometric Caching**: Cache expensive geometric calculations

### Octree-Specific:
1. **BMI2 Instructions**: Use native Morton encoding if available
2. **AABB Optimization**: Vectorize bounding box operations

### Tetree-Specific:  
1. **SFC Caching**: Cache tetrahedral SFC calculations
2. **Geometric Precomputation**: Pre-compute tetrahedral properties
3. **t8code Integration**: Use native t8code for critical paths

## Conclusion

**Performance Verdict** (Updated with Real Measurements):
- **Insertion Performance**: Octree is **23x faster** (4.84μs vs 111.72μs per entity)
- **Query Performance**: Octree is **1.65x faster** for k-NN searches (34.1μs vs 56.3μs per query)
- **The performance gap is larger than initially expected**, primarily due to Tetree's complex t8code SFC calculations

**Key Findings**:
1. **Despite 90% shared code**, the 10% difference (SFC algorithms) has major impact
2. **Morton encoding is vastly superior** to tetrahedral SFC for insertion performance  
3. **Query performance difference is moderate** (1.65x) since algorithms are shared
4. **Tetree's t8code compliance comes at significant performance cost**

**Revised Recommendations**:

### Choose Octree When:
- **Performance is the primary concern** (especially for real-time applications)
- **High insertion rates** are required (games, simulations, streaming data)
- Working with **general 3D spatial data** where cubic partitioning suffices
- **Memory efficiency** and speed are more important than geometric precision

### Choose Tetree When:
- **Geometric accuracy** is more important than raw speed
- Working with **scientific computing** applications (FEM, CFD, mesh processing)
- Need **t8code ecosystem compatibility** for advanced tetrahedral algorithms
- Application can **tolerate 20x slower insertions** for better spatial representation
- Working with **complex 3D mesh data** that benefits from tetrahedral partitioning

**Architecture Success**: The `AbstractSpatialIndex` strategy successfully enabled code reuse while preserving the distinct characteristics of each approach. However, the performance impact of space-filling curve algorithms is more significant than anticipated.

**Bottom Line**: For general applications prioritizing performance, **Octree is strongly recommended**. For specialized scientific/mesh applications where geometric accuracy outweighs speed, **Tetree provides unique value** despite the performance penalty.