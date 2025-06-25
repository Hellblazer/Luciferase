# Tetree Insertion Optimization Strategy - June 2025

## Executive Summary

After analyzing the insertion implementations, the performance gap between Octree (1.5μs) and Tetree (105μs) comes down to one critical difference in `calculateSpatialIndex()`:

```java
// Octree - O(1) operation
public MortonKey calculateSpatialIndex(Point3f position, byte level) {
    return new MortonKey(Constants.calculateMortonIndex(position, level), level);
}

// Tetree - O(level) operation  
protected TetreeKey calculateSpatialIndex(Point3f position, byte level) {
    var tet = locate(position, level);  // Fast O(1)
    return tet.tmIndex();              // SLOW O(level) - walks parent chain
}
```

## The Core Problem

The `tmIndex()` method MUST walk the parent chain because:
1. The tmIndex IS unique across all levels (forms a globally unique identifier)
2. However, the level cannot be derived from the index alone (unlike Morton codes)
3. The parent chain walk builds the complete hierarchical path to ensure global uniqueness
4. This is fundamental to the algorithm and cannot be changed

## Current Optimizations Already in Place

1. **TetreeKey Caching** (Phase 1) - 64K entry cache
2. **Bulk Operations** (Phase 2) - Region pre-computation
3. **Thread-Local Caching** (Phase 3) - Per-thread caches
4. **Parent Chain Caching** (Phase 3) - 4K entry cache

These reduced the gap from 1125x to 70x, but we've hit diminishing returns.

## New Optimization Opportunities

### 1. **Lazy TetreeKey Computation** ⭐ HIGH IMPACT

Instead of computing the full TetreeKey immediately, defer computation until needed:

```java
public class LazyTetreeKey extends TetreeKey {
    private final Tet tet;
    private volatile TetreeKey computed;
    
    public LazyTetreeKey(Tet tet) {
        this.tet = tet;
    }
    
    @Override
    public BigInteger getTmIndex() {
        if (computed == null) {
            computed = tet.tmIndex();
        }
        return computed.getTmIndex();
    }
}
```

**Benefits**:
- Insertion just stores the Tet (O(1))
- tmIndex only computed when key is actually used
- Many operations (like bulk insert) may never need the full key

### 2. **Tetree-Specific Node Storage** ⭐ MEDIUM IMPACT

Since Tetree nodes are accessed by TetreeKey, we could store additional metadata:

```java
public class TetreeNodeImpl<ID> extends AbstractSpatialNode<TetreeKey, ID> {
    private final Tet tet;  // Store the Tet directly
    private final byte[] parentTypes;  // Pre-computed parent chain
    
    // Avoid recomputing tmIndex for operations on this node
}
```

### 3. **Batch Parent Chain Resolution** ⭐ MEDIUM IMPACT

When inserting multiple entities, collect all Tets first, then resolve parent chains in batch:

```java
protected void insertBatch(List<EntityData> entities) {
    // Step 1: Locate all Tets (parallel, O(1) each)
    List<Tet> tets = entities.parallelStream()
        .map(e -> locate(e.position, e.level))
        .collect(toList());
    
    // Step 2: Build parent chains efficiently
    Map<Long, Tet[]> parentChains = buildParentChainsInBatch(tets);
    
    // Step 3: Insert with pre-computed chains
    for (int i = 0; i < entities.size(); i++) {
        insertWithCachedChain(entities.get(i), tets.get(i), parentChains);
    }
}
```

### 4. **Optimize Entity Manager Overhead** ⭐ HIGH IMPACT

The profiling showed 82.8% of time is in the insertion logic AFTER tmIndex:

```java
// Current flow in insertAtPosition():
1. calculateSpatialIndex() - 6.8% (after caching)
2. computeIfAbsent() - Creates node if needed
3. node.addEntity() - Adds to entity list
4. entityManager.addEntityLocation() - EXPENSIVE tracking
5. subdivision checks - Can trigger cascading work
```

Optimizations:
- Pool EntityLocation objects
- Use primitive collections for entity tracking
- Batch entity manager updates

### 5. **Spatial Locality Insertion Order** ⭐ LOW IMPACT

Reorder insertions to maximize cache hits:

```java
public List<ID> insertBatch(List<Point3f> positions, List<Content> contents, byte level) {
    // Sort by spatial locality (Morton order works for both!)
    List<Integer> indices = IntStream.range(0, positions.size())
        .boxed()
        .sorted((i, j) -> {
            long m1 = Constants.calculateMortonIndex(positions.get(i), level);
            long m2 = Constants.calculateMortonIndex(positions.get(j), level);
            return Long.compare(m1, m2);
        })
        .collect(toList());
    
    // Insert in spatial order for better cache performance
    return indices.stream()
        .map(i -> insert(positions.get(i), level, contents.get(i)))
        .collect(toList());
}
```

## Implementation Priority

1. **Lazy TetreeKey Computation** - Biggest potential win
2. **Entity Manager Optimization** - Addresses the 82.8% bottleneck
3. **Batch Parent Chain Resolution** - Good for bulk operations
4. **Node Storage Optimization** - Reduces repeated computations
5. **Spatial Locality Ordering** - Easy win for batch operations

## Expected Impact

With all optimizations:
- Single insert: Could improve from 105μs to ~30-40μs (3x improvement)
- Bulk insert: Could improve from 43μs to ~15-20μs (2-3x improvement)
- Final gap: From 70x to ~20-25x slower than Octree

## Conclusion

While we cannot eliminate the O(level) complexity of tmIndex(), we can:
1. Defer its computation (lazy evaluation)
2. Optimize the surrounding insertion logic (82.8% of time)
3. Exploit spatial locality and batching

These optimizations would make Tetree much more practical for real-world use cases, especially when combined with its superior query performance.