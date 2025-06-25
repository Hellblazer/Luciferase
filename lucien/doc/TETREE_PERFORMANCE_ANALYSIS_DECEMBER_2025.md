# Tetree Performance Analysis - December 2025

## Executive Summary

After refactoring to use `tmIndex()` for correctness, Tetree performance has degraded catastrophically:
- At level 20, `tmIndex()` is **430x slower** than `consecutiveIndex()`
- Insertion performance dropped from competitive to 1125x slower than Octree
- The root cause is the O(level) parent chain traversal in `tmIndex()`

However, this performance can be largely recovered through aggressive caching.

## Current Performance Bottleneck Analysis

### 1. tmIndex() Method Breakdown

The tmIndex() method (Tet.java lines 1201-1260) performs these expensive operations:

```java
// O(level) parent chain walk
List<Byte> ancestorTypes = new ArrayList<>();
Tet current = this;
while (current.l() > 1) {
    current = current.parent();  // Creates new Tet object
    if (current != null) {
        ancestorTypes.addFirst(current.type());
    }
}
```

**Cost Analysis per tmIndex() call:**
- Level 1: 0 parent() calls → 63 ns
- Level 5: 4 parent() calls → 321 ns  
- Level 10: 9 parent() calls → 410 ns
- Level 20: 19 parent() calls → 1311 ns

Each parent() call involves:
- Bit manipulation for coordinate calculation
- Table lookup for parent type determination
- New Tet object creation
- Validation checks

### 2. Hot Path Analysis

Based on code analysis, tmIndex() is called in these critical paths:

| Operation | tmIndex() Calls | Impact |
|-----------|----------------|---------|
| Insert single entity | 2-5 | Direct insertion cost |
| k-NN search (k=10) | 50-200 | Node exploration |
| Range query | 100-1000 | Spatial traversal |
| Collision detection | 20-100 per entity | Proximity checks |
| Tree subdivision | 8 per split | Node management |

### 3. Memory Access Patterns

Current implementation has poor cache locality:
- Parent chain walk jumps through memory
- Each Tet object is 25 bytes (3 ints + 2 bytes)
- ArrayList allocations for ancestor types
- BigInteger allocations for TM-index

## Caching Opportunities

### 1. TetreeKey Cache (Highest Priority)

**What to cache:** Complete TetreeKey objects
```java
// Key: (x, y, z, level, type) → TetreeKey
private static final int TETREE_KEY_CACHE_SIZE = 65536; // 16x larger
private static final long[] KEY_CACHE_KEYS = new long[TETREE_KEY_CACHE_SIZE];
private static final TetreeKey[] KEY_CACHE_VALUES = new TetreeKey[TETREE_KEY_CACHE_SIZE];
```

**Expected Impact:**
- Converts O(level) to O(1) for cached entries
- Hit rate should be >90% for spatial operations
- Memory cost: ~2MB for 64K entries

### 2. Ancestor Type Cache

**What to cache:** Pre-computed ancestor type arrays
```java
// Key: (x, y, z, level, type) → byte[] ancestorTypes
private static final byte[][] ANCESTOR_TYPE_CACHE = new byte[8192][];
```

**Benefits:**
- Eliminates parent chain walk for type computation
- Reusable for sibling tetrahedra
- Small memory footprint (max 21 bytes per entry)

### 3. Parent Tet Cache

**What to cache:** Parent Tet objects
```java
// Key: child Tet → parent Tet
private static final Tet[] PARENT_CACHE = new Tet[16384];
```

**Benefits:**
- Speeds up tree traversal
- Reduces object allocation
- Enables fast ancestor queries

### 4. Thread-Local Caches

For heavily contested caches, use thread-local storage:
```java
private static final ThreadLocal<TetreeKeyCache> THREAD_LOCAL_CACHE = 
    ThreadLocal.withInitial(TetreeKeyCache::new);
```

## Implementation Strategy

### Phase 1: Extend TetreeLevelCache (Immediate)

Add these methods to TetreeLevelCache:

```java
// Cache complete TetreeKey objects
public static TetreeKey getCachedTetreeKey(int x, int y, int z, byte level, byte type) {
    long key = generateCacheKey(x, y, z, level, type);
    int slot = (int)(key & (TETREE_KEY_CACHE_SIZE - 1));
    
    if (KEY_CACHE_KEYS[slot] == key) {
        return KEY_CACHE_VALUES[slot];
    }
    return null;
}

public static void cacheTetreeKey(int x, int y, int z, byte level, byte type, TetreeKey tetreeKey) {
    long key = generateCacheKey(x, y, z, level, type);
    int slot = (int)(key & (TETREE_KEY_CACHE_SIZE - 1));
    KEY_CACHE_KEYS[slot] = key;
    KEY_CACHE_VALUES[slot] = tetreeKey;
}
```

### Phase 2: Optimize Tet.tmIndex() (Quick Win)

Modify tmIndex() to check cache first:

```java
public TetreeKey tmIndex() {
    // Check cache first
    TetreeKey cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
    if (cached != null) {
        return cached;
    }
    
    // ... existing computation ...
    
    // Cache result before returning
    TetreeKey result = new TetreeKey(l, index);
    TetreeLevelCache.cacheTetreeKey(x, y, z, l, type, result);
    return result;
}
```

### Phase 3: Bulk Operation Optimization

For bulk operations, pre-compute and cache entire regions:

```java
public void precomputeRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, byte level) {
    // Pre-compute tmIndex for all tetrahedra in region
    // Enables O(1) lookups during bulk operations
}
```

## Expected Performance Improvements

### With Full Caching Implementation

| Operation | Current | With Caching | Improvement |
|-----------|---------|--------------|-------------|
| tmIndex() @ L20 | 1311 ns | 15 ns | 87x faster |
| Single Insert | 1690 μs | 20 μs | 84x faster |
| Bulk Insert 50K | 84,483 ms | 1,000 ms | 84x faster |
| k-NN Search | 5.9 μs | 5.0 μs | 1.2x faster |

### Memory Cost

- TetreeKey cache (64K entries): ~2 MB
- Ancestor type cache (8K entries): ~200 KB  
- Parent cache (16K entries): ~400 KB
- Total overhead: **~2.6 MB** (acceptable for performance gain)

## Testing and Validation

### 1. Microbenchmarks

Create focused benchmarks for:
- Cache hit rate measurement
- tmIndex() performance with/without caching
- Thread contention testing
- Memory overhead validation

### 2. Integration Tests

Verify correctness with:
- Round-trip tests (Tet → tmIndex → Tet)
- Parent-child relationship validation
- Spatial query result verification

### 3. Performance Regression Tests

Establish baselines and monitor:
- Insertion throughput
- Query latency percentiles
- Memory usage growth
- Cache effectiveness metrics

## Risk Mitigation

### 1. Cache Invalidation

The caches are safe because:
- Tet objects are immutable
- TetreeKey objects are immutable
- Spatial coordinates uniquely determine tmIndex

### 2. Memory Growth

Implement cache size limits:
- LRU eviction for less common entries
- Periodic cache clearing for long-running processes
- Memory pressure monitoring

### 3. Thread Safety

Current design is thread-safe:
- Cache writes are idempotent
- Race conditions only affect performance, not correctness
- Thread-local caches eliminate contention

## Conclusion

The performance degradation from tmIndex() is severe but fixable:
1. The root cause is well understood (parent chain walk)
2. Caching can eliminate most of the overhead
3. Implementation is straightforward with existing infrastructure
4. Memory cost is acceptable (~2.6 MB)

With proper caching, Tetree can recover most of its performance while maintaining the correctness gained from using globally unique indices.