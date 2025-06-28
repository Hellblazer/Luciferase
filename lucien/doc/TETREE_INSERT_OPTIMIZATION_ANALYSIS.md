# Tetree Insert Optimization Analysis

## Performance Comparison: Octree vs Tetree Insertion

### Current Performance Gap

- **Octree**: 1.5 μs/entity insertion
- **Tetree**: 1690 μs/entity insertion (1125x slower)

## Root Cause Analysis

### Octree Insert Flow

1. **calculateSpatialIndex()**: Calls `calculateMortonCode()`
    - Simple bit interleaving operation - **O(1)**
    - No parent chain traversal needed
    - Direct computation from coordinates

```java
// Octree.calculateMortonCode()
private MortonKey calculateMortonCode(Point3f position, byte level) {
    return new MortonKey(Constants.calculateMortonIndex(position, level), level);
}

// Constants.calculateMortonIndex() - Simple O(1) operation
public static long calculateMortonIndex(Point3f point, byte level) {
    var length = lengthAtLevel(level);
    int quantizedX = (int) (Math.floor(point.x / length) * length);
    int quantizedY = (int) (Math.floor(point.y / length) * length);
    int quantizedZ = (int) (Math.floor(point.z / length) * length);
    return MortonCurve.encode(quantizedX, quantizedY, quantizedZ);
}
```

### Tetree Insert Flow

1. **calculateSpatialIndex()**: Calls `locate()` then `tmIndex()`
    - `locate()`: O(1) - Fast Freudenthal triangulation
    - `tmIndex()`: **O(level)** - Parent chain walk required

```java
// Tetree.calculateSpatialIndex()
protected TetreeKey calculateSpatialIndex(Point3f position, byte level) {
    var tet = locate(position, level);  // O(1)
    return tet.tmIndex();              // O(level) - THE BOTTLENECK
}

// Tet.tmIndex() - Walks parent chain
public TetreeKey tmIndex() {
    // Check cache first
    var cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
    if (cached != null) {
        return cached;
    }

    // Walk parent chain to collect ancestor types
    Tet current = this;
    while (current.l() > 1) {
        current = current.parent();  // O(1) per level
        ancestorTypes.addFirst(current.type());
    }

    // Build TM-index by interleaving bits
    // ... complex bit manipulation ...
}
```

## Key Performance Differences

### 1. Index Calculation Complexity

| Operation               | Octree  | Tetree      | Notes                             |
|-------------------------|---------|-------------|-----------------------------------|
| Coordinate Quantization | O(1)    | O(1)        | Both fast                         |
| Index Computation       | O(1)    | O(level)    | Tetree walks parent chain         |
| Caching Benefit         | Minimal | Significant | Tetree benefits more from caching |

### 2. Parent Chain Traversal

- **Octree**: Morton encoding is self-contained - no parent information needed
- **Tetree**: TM-index requires complete ancestor type hierarchy for global uniqueness

### 3. Cache Effectiveness

- **Octree**: Minimal benefit - operation already O(1)
- **Tetree**: Critical for performance, but:
    - Cache size limited (65536 entries)
    - Cache thrashing on diverse datasets
    - Still pays O(level) cost on cache miss

## Optimization Opportunities

### 1. Thread-Local Caching (Already Implemented)

```java
if(useThreadLocalCache){
return ThreadLocalTetreeCache.

getTetreeKey(tet);
}
```

- Reduces cache contention in multi-threaded scenarios
- Still limited by fundamental O(level) algorithm

### 2. Batch Insertion Optimization

```java
// Pre-compute and cache TM indices for batch operations
public void insertBatch(List<EntityData<ID, Content>> entities) {
    // Pre-calculate all TM indices
    Map<Tet, TetreeKey> tmIndexCache = new HashMap<>();
    for (EntityData data : entities) {
        Tet tet = locate(data.position(), data.level());
        tmIndexCache.computeIfAbsent(tet, Tet::tmIndex);
    }

    // Use cached indices for insertion
    for (EntityData data : entities) {
        Tet tet = locate(data.position(), data.level());
        TetreeKey key = tmIndexCache.get(tet);
        // ... insert using cached key
    }
}
```

### 3. Hierarchical Caching Strategy

```java
// Cache parent chains more aggressively
private final Map<Tet, Tet[]> parentChainCache = new ConcurrentHashMap<>();

public TetreeKey tmIndexOptimized() {
    // Check full parent chain cache
    Tet[] chain = parentChainCache.get(this);
    if (chain != null) {
        return buildTMIndexFromChain(chain);
    }

    // Build and cache parent chain
    chain = buildParentChain();
    parentChainCache.put(this, chain);
    return buildTMIndexFromChain(chain);
}
```

### 4. Lazy TM-Index Computation

```java
// Store Tet objects internally, compute TM-index only when needed
protected void insertAtPositionOptimized(ID entityId, Point3f position, byte level) {
    Tet tet = locate(position, level);

    // Use Tet as internal key, avoiding tmIndex() call
    TetreeNodeImpl<ID> node = tetIndex.computeIfAbsent(tet, k -> {
        // Only compute TM-index when absolutely necessary
        TetreeKey tmKey = k.tmIndex();
        sortedSpatialIndices.add(tmKey);
        return nodePool.acquire();
    });

    node.addEntity(entityId);
}
```

### 5. Pre-computed Index Tables

For common patterns (e.g., regular grids), pre-compute TM indices:

```java
// Pre-compute TM indices for regular grid patterns
private static final Map<GridCell, TetreeKey> GRID_TM_INDEX_CACHE = 
    precomputeGridIndices();

private TetreeKey fastGridLookup(Point3f position, byte level) {
    GridCell cell = quantizeToGrid(position, level);
    return GRID_TM_INDEX_CACHE.get(cell);
}
```

## Fundamental Limitation

The core issue is that **TM-index requires global uniqueness through ancestor type encoding**, which fundamentally
requires O(level) work. Unlike Morton codes which achieve uniqueness through simple bit interleaving, the tetrahedral
SFC must encode the complete traversal path.

## Recommendations

1. **Short-term**: Implement batch processing optimizations and enhanced caching
2. **Medium-term**: Consider lazy TM-index computation for internal operations
3. **Long-term**: Investigate alternative tetrahedral indexing schemes that don't require parent chain traversal

## Conclusion

The 1000x performance gap is primarily due to the O(level) vs O(1) algorithmic difference in spatial index calculation.
While caching helps, it cannot overcome the fundamental algorithmic complexity difference between Morton encoding and
TM-index computation.
