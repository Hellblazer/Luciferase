# Tetree Performance Improvement Plan

## Overview

This plan addresses the 430x performance degradation in tmIndex() operations that has made Tetree insertions 1125x
slower than Octree. The goal is to recover performance through strategic caching while maintaining correctness.

## Goals

1. **Primary:** Reduce tmIndex() cost from O(level) to O(1) for cached entries
2. **Target:** Achieve >90% cache hit rate for typical spatial operations
3. **Memory Budget:** Keep additional memory under 5MB
4. **Timeline:** Implement in 3 phases over 1-2 weeks

## Phase 1: Core Caching Infrastructure (Days 1-3)

### 1.1 Extend TetreeLevelCache

**File:** `TetreeLevelCache.java`

```java
// Add these fields
private static final int TETREE_KEY_CACHE_SIZE = 65536;
private static final long[] TETREE_KEY_CACHE_KEYS = new long[TETREE_KEY_CACHE_SIZE];
private static final TetreeKey[] TETREE_KEY_CACHE_VALUES = new TetreeKey[TETREE_KEY_CACHE_SIZE];

// Add these methods
public static TetreeKey getCachedTetreeKey(int x, int y, int z, byte level, byte type) {
    long key = generateCacheKey(x, y, z, level, type);
    int slot = (int)(key & (TETREE_KEY_CACHE_SIZE - 1));
    
    if (TETREE_KEY_CACHE_KEYS[slot] == key) {
        return TETREE_KEY_CACHE_VALUES[slot];
    }
    return null;
}

public static void cacheTetreeKey(int x, int y, int z, byte level, byte type, TetreeKey tetreeKey) {
    long key = generateCacheKey(x, y, z, level, type);
    int slot = (int)(key & (TETREE_KEY_CACHE_SIZE - 1));
    TETREE_KEY_CACHE_KEYS[slot] = key;
    TETREE_KEY_CACHE_VALUES[slot] = tetreeKey;
}

// Cache statistics for monitoring
private static long cacheHits = 0;
private static long cacheMisses = 0;

public static double getCacheHitRate() {
    long total = cacheHits + cacheMisses;
    return total > 0 ? (double)cacheHits / total : 0.0;
}
```

### 1.2 Optimize Tet.tmIndex()

**File:** `Tet.java`

```java
public TetreeKey tmIndex() {
    // PERFORMANCE: Check cache first
    TetreeKey cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
    if (cached != null) {
        return cached;
    }

    if (l == 0) {
        return ROOT_TET;
    }

    // Existing implementation...
    // [Keep current code]

    // PERFORMANCE: Cache result before returning
    TetreeKey result = new TetreeKey(l, index);
    TetreeLevelCache.cacheTetreeKey(x, y, z, l, type, result);
    return result;
}
```

### 1.3 Add Ancestor Type Caching

```java
// In TetreeLevelCache
private static final int ANCESTOR_CACHE_SIZE = 8192;
private static final long[] ANCESTOR_CACHE_KEYS = new long[ANCESTOR_CACHE_SIZE];
private static final byte[][] ANCESTOR_CACHE_VALUES = new byte[ANCESTOR_CACHE_SIZE][];

public static byte[] getCachedAncestorTypes(int x, int y, int z, byte level, byte type) {
    long key = generateCacheKey(x, y, z, level, type);
    int slot = (int)(key & (ANCESTOR_CACHE_SIZE - 1));
    
    if (ANCESTOR_CACHE_KEYS[slot] == key) {
        return ANCESTOR_CACHE_VALUES[slot];
    }
    return null;
}
```

## Phase 2: Bulk Operation Optimization (Days 4-5)

### 2.1 Region Pre-computation

**New class:** `TetreeRegionCache.java`

```java
public class TetreeRegionCache {
    private final Map<Long, TetreeKey> regionCache = new ConcurrentHashMap<>();

    public void precomputeRegion(VolumeBounds bounds, byte level) {
        int cellSize = Constants.lengthAtLevel(level);
        int minX = (int) (bounds.minX() / cellSize) * cellSize;
        int maxX = (int) (bounds.maxX() / cellSize) * cellSize;
        // ... similar for Y and Z

        // Pre-compute all tetrahedra in region
        for (int x = minX; x <= maxX; x += cellSize) {
            for (int y = minY; y <= maxY; y += cellSize) {
                for (int z = minZ; z <= maxZ; z += cellSize) {
                    for (byte type = 0; type < 6; type++) {
                        Tet tet = new Tet(x, y, z, level, type);
                        TetreeKey key = tet.tmIndex();
                        // Already cached by tmIndex() call
                    }
                }
            }
        }
    }
}
```

### 2.2 Bulk Insert Optimization

**Modify:** `Tetree.insertBatch()`

```java
public List<ID> insertBatch(List<Point3f> positions, List<Content> contents, byte level) {
    // Pre-compute bounding box
    VolumeBounds bounds = calculateBounds(positions);

    // PERFORMANCE: Pre-cache the region
    TetreeRegionCache regionCache = new TetreeRegionCache();
    regionCache.precomputeRegion(bounds, level);

    // Now perform insertions with cached tmIndex values
    return super.insertBatch(positions, contents, level);
}
```

## Phase 3: Advanced Optimizations (Days 6-7)

### 3.1 Thread-Local Caching

For heavily concurrent workloads:

```java
public class ThreadLocalTetreeCache {
    private static final ThreadLocal<TetreeKeyCache> tlCache = ThreadLocal.withInitial(() -> new TetreeKeyCache(4096));

    public static TetreeKey getTetreeKey(Tet tet) {
        return tlCache.get().get(tet);
    }
}
```

### 3.2 Parent Chain Optimization

Cache entire parent chains:

```java
// In TetreeLevelCache
private static final int PARENT_CHAIN_CACHE_SIZE = 4096;
private static final long[] PARENT_CHAIN_KEYS = new long[PARENT_CHAIN_CACHE_SIZE];
private static final Tet[][] PARENT_CHAIN_VALUES = new Tet[PARENT_CHAIN_CACHE_SIZE][];

public static Tet[] getCachedParentChain(Tet tet) {
    long key = generateCacheKey(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
    int slot = (int)(key & (PARENT_CHAIN_CACHE_SIZE - 1));
    
    if (PARENT_CHAIN_KEYS[slot] == key) {
        return PARENT_CHAIN_VALUES[slot];
    }
    return null;
}
```

### 3.3 Spatial Locality Optimization

Exploit spatial locality in traversals:

```java
public class SpatialLocalityCache {
    private final int LOCALITY_RADIUS = 2; // Cache 2 cells in each direction

    public void preCacheNeighborhood(Tet center) {
        int cellSize = center.length();

        for (int dx = -LOCALITY_RADIUS; dx <= LOCALITY_RADIUS; dx++) {
            for (int dy = -LOCALITY_RADIUS; dy <= LOCALITY_RADIUS; dy++) {
                for (int dz = -LOCALITY_RADIUS; dz <= LOCALITY_RADIUS; dz++) {
                    int x = center.x() + dx * cellSize;
                    int y = center.y() + dy * cellSize;
                    int z = center.z() + dz * cellSize;

                    if (x >= 0 && y >= 0 && z >= 0) {
                        for (byte type = 0; type < 6; type++) {
                            new Tet(x, y, z, center.l(), type).tmIndex();
                        }
                    }
                }
            }
        }
    }
}
```

## Testing Plan

### 1. Unit Tests

Create tests for:

- Cache correctness (cached value == computed value)
- Cache hit rate monitoring
- Thread safety validation
- Memory usage tracking

### 2. Performance Tests

```java

@Test
public void testTmIndexCachePerformance() {
    // Warm up cache
    for (int i = 0; i < 1000; i++) {
        Tet tet = new Tet(i * 100, i * 100, i * 100, (byte) 10, (byte) 0);
        tet.tmIndex();
    }

    // Measure with cache
    long start = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
        Tet tet = new Tet(i % 1000 * 100, i % 1000 * 100, i % 1000 * 100, (byte) 10, (byte) 0);
        tet.tmIndex();
    }
    long cached = System.nanoTime() - start;

    // Should be >90% cache hits
    assertTrue(TetreeLevelCache.getCacheHitRate() > 0.9);
}
```

### 3. Integration Tests

Run full benchmark suite:

- OctreeVsTetreeBenchmark
- TetreeInsertionBenchmark
- SpatialIndexQueryPerformanceTest

## Monitoring and Metrics

### 1. Cache Statistics

Add JMX beans for monitoring:

```java
@MXBean
public interface TetreeCacheStatsMXBean {
    long getCacheHits();
    long getCacheMisses();
    double getCacheHitRate();
    long getCacheMemoryUsage();
}
```

### 2. Performance Metrics

Track:

- tmIndex() call frequency
- Average tmIndex() latency
- Cache hit rates by operation type
- Memory growth over time

## Rollout Strategy

1. **Phase 1 First**: Implement basic caching, measure impact
2. **Gradual Rollout**: Enable caching via feature flag
3. **Monitor Metrics**: Track cache effectiveness
4. **Tune Cache Sizes**: Adjust based on real workload
5. **Phase 2-3**: Implement advanced features if needed

## Success Criteria

- [ ] tmIndex() performance improved by >50x for cached entries
- [ ] Cache hit rate >90% for typical workloads
- [ ] Tetree insertion within 10x of Octree (vs current 1125x)
- [ ] Memory overhead <5MB
- [ ] No correctness regressions

## Risk Mitigation

1. **Cache Coherency**: Not an issue - Tet objects are immutable
2. **Memory Leaks**: Use fixed-size arrays, not growing maps
3. **Thread Contention**: Use thread-local caches if needed
4. **Cache Pollution**: Use quality hash function (already implemented)

## Long-term Optimization

If caching proves insufficient:

1. Consider lazy tmIndex computation (compute only when needed)
2. Investigate alternative index schemes that don't require parent walk
3. Explore SIMD optimization for batch operations
4. Consider GPU acceleration for massive bulk operations

## Conclusion

This plan provides a systematic approach to recovering Tetree performance:

1. Start with simple, high-impact caching
2. Measure and validate improvements
3. Add advanced optimizations as needed
4. Monitor long-term performance

With proper implementation, we expect to reduce the performance gap from 1125x to under 10x while maintaining the
correctness benefits of globally unique indices.
