# Duplicate Methods Analysis: Octree vs Tetree

This document identifies methods with identical or very similar implementations between Octree.java and Tetree.java that could potentially be moved to AbstractSpatialIndex to reduce code duplication.

## Methods with Identical Implementations

### 1. **getNodeCount()**
- **Octree**: Not shown in the snippets but likely similar
- **Tetree**: Lines 577-584
- **Implementation**: Simple lock-read and return `spatialIndex.size()`
- **Recommendation**: Move to AbstractSpatialIndex

### 2. **size()**
- **Octree**: Not shown but likely similar
- **Tetree**: Lines 1153-1155
- **Implementation**: Count non-empty nodes using stream filter
- **Recommendation**: Move to AbstractSpatialIndex

### 3. **hasNode(Key spatialIndex)**
- **Octree**: Likely similar
- **Tetree**: Lines 641-648
- **Implementation**: Lock-read and check `spatialIndex.containsKey(spatialIndex)`
- **Recommendation**: Already exists in AbstractSpatialIndex (lines 1183-1190)

### 4. **getSpatialIndex()**
- **Octree**: Lines 547-549
- **Tetree**: Lines 1576-1578
- **Implementation**: Simple getter returning `spatialIndex`
- **Recommendation**: Move to AbstractSpatialIndex as protected method

### 5. **getSortedSpatialIndices()**
- **Octree**: Lines 552-554
- **Tetree**: Lines 603-610
- **Implementation**: Lock-read and return copy of `sortedSpatialIndices`
- **Recommendation**: Move to AbstractSpatialIndex

### 6. **Iterator Methods (Non-empty, Parent-Child, Sibling)**
- Both implementations have similar iterator patterns
- **Recommendation**: Create abstract iterator base class in AbstractSpatialIndex

## Methods with Similar Logic but Type-Specific Differences

### 1. **findCollisions(ID entityId)**
- Both use spatial range queries but with different neighbor search strategies
- **Octree**: Uses structural neighbors
- **Tetree**: Uses spatial range due to SFC properties
- **Recommendation**: Extract common pattern to AbstractSpatialIndex with abstract neighbor strategy

### 2. **insertBatch() Methods**
- Both have similar bulk insertion logic with optimizations
- **Octree**: Lines not shown
- **Tetree**: Lines 660-711
- **Common Pattern**: Pre-computation, caching, bulk mode handling
- **Recommendation**: Most of the logic is already in AbstractSpatialIndex (lines 1281-1409)

### 3. **findAllCollisions()**
- Core logic is similar but Tetree has additional logic for checking same-grid-cell tetrahedra
- **Recommendation**: Base implementation already in AbstractSpatialIndex (lines 435-543), Tetree correctly overrides for its specific needs

### 4. **handleNodeSubdivision()**
- Similar subdivision pattern but different child generation
- **Octree**: Lines 385-463
- **Tetree**: Lines 1629-1746
- **Common Pattern**: Entity redistribution, child node creation, parent cleanup
- **Recommendation**: Extract common redistribution logic to base class

### 5. **insertWithSpanning()**
- Similar pattern but different intersection finding
- **Octree**: Lines 477-494
- **Tetree**: Lines 1762-1783
- **Common Pattern**: Find intersecting nodes, add entity to each
- **Recommendation**: Extract common pattern with abstract intersection method

## Methods Already in AbstractSpatialIndex

1. **containsEntity()** - Lines 295-302
2. **getEntity()** - Lines 1007-1014
3. **entityCount()** - Lines 386-393
4. **getEntityBounds()** - Lines 1019-1026
5. **getEntityPosition()** - Lines 1031-1038
6. **lookup()** - Lines 1051-1075
7. **kNearestNeighbors()** - Lines 1513-1667
8. **insertBatch()** - Lines 1281-1409
9. **rayIntersectAll()** - Lines 1923-1982
10. **planeIntersectAll()** - Lines 1701-1743

## Methods that Should Remain Implementation-Specific

1. **calculateSpatialIndex()** - Fundamentally different (Morton vs TM-index)
2. **locate() / locateTetrahedron()** - Geometry-specific
3. **getChildNodes()** - Different child generation (8 cubic vs 8 tetrahedral)
4. **doesNodeIntersectVolume()** - Different geometry tests
5. **getNodeBounds()** - Returns different shapes (cube vs tetrahedron bounds)
6. **addNeighboringNodes()** - Different neighbor finding strategies

## Recommendations for Refactoring

### High Priority (Easy Wins)
1. Move `getSpatialIndex()` and `getSortedSpatialIndices()` to AbstractSpatialIndex
2. Move `getNodeCount()` and similar simple getters
3. Consolidate iterator creation patterns

### Medium Priority (More Complex)
1. Extract common subdivision logic while keeping geometry-specific parts abstract
2. Create abstract neighbor finding strategy
3. Unify collision detection patterns

### Low Priority (Requires Careful Design)
1. Abstract spanning insertion patterns
2. Generalize bulk operation optimizations
3. Create pluggable caching strategies

## Code Duplication Metrics

- **Estimated duplicate lines**: ~200-300 lines
- **Percentage of duplication**: ~10-15% of total code
- **Complexity of refactoring**: Medium (due to type system and performance considerations)

## Performance Considerations

When moving methods to AbstractSpatialIndex:
1. Ensure virtual method calls don't impact hot paths
2. Consider using final methods where inheritance isn't needed
3. Keep lock acquisition patterns consistent
4. Maintain cache locality for performance-critical sections