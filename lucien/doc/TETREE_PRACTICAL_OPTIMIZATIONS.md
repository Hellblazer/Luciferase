# Practical Tetree Performance Optimizations

## Executive Summary
While we cannot eliminate the O(level) complexity of tmIndex(), we can implement several practical optimizations to reduce the constant factor and improve real-world performance.

## Immediate Optimizations (Low Effort, High Impact)

### 1. Increase Cache Sizes
Current cache sizes are too small for production workloads:
```java
// Current: 65536 entries (~2MB memory)
private static final int TETREE_KEY_CACHE_SIZE = 65536;

// Recommended: 1M entries (~32MB memory) 
private static final int TETREE_KEY_CACHE_SIZE = 1048576;

// Also increase parent chain cache
private static final int PARENT_CHAIN_CACHE_SIZE = 65536; // was 4096
```

### 2. Batch Insert with Pre-computation
```java
// Add to Tetree.java
public void insertBatch(List<EntityData<ID, Content>> entities) {
    // Pre-compute all Tet objects and TetreeKeys
    List<TetEntry> entries = new ArrayList<>(entities.size());
    
    for (EntityData<ID, Content> data : entities) {
        Tet tet = locate(data.position(), data.level());
        TetreeKey key = tet.tmIndex(); // Compute once
        entries.add(new TetEntry(data, tet, key));
    }
    
    // Sort by TetreeKey for better cache locality
    entries.sort(Comparator.comparing(e -> e.key));
    
    // Insert using pre-computed keys
    for (TetEntry entry : entries) {
        TetreeNodeImpl<ID> node = spatialIndex.computeIfAbsent(entry.key, k -> {
            sortedSpatialIndices.add(k);
            return nodePool.acquire();
        });
        
        boolean shouldSplit = node.addEntity(entry.data.id());
        entityManager.addEntityLocation(entry.data.id(), entry.key);
        
        if (shouldSplit && entry.data.level() < maxDepth) {
            handleNodeSubdivision(entry.key, entry.data.level(), node);
        }
    }
}

private record TetEntry(EntityData<ID, Content> data, Tet tet, TetreeKey key) {}
```

### 3. Aggressive Parent Chain Caching
```java
// In Tet.java - modify tmIndex() to better utilize parent chain cache
public TetreeKey tmIndex() {
    // Check TetreeKey cache first
    var cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
    if (cached != null) {
        return cached;
    }
    
    if (l == 0) {
        return ROOT_TET;
    }
    
    // NEW: Check if any ancestor is cached
    Tet current = this;
    List<Tet> pathToRoot = new ArrayList<>();
    pathToRoot.add(current);
    
    while (current.l() > 0) {
        current = current.parent();
        pathToRoot.add(current);
        
        // Check if this ancestor has a cached TetreeKey
        var ancestorKey = TetreeLevelCache.getCachedTetreeKey(
            current.x, current.y, current.z, current.l, current.type);
        
        if (ancestorKey != null) {
            // Build from cached ancestor instead of going to root
            return buildTMIndexFromAncestor(pathToRoot, ancestorKey);
        }
    }
    
    // No cached ancestors found, compute normally
    return computeTMIndexFromScratch();
}
```

### 4. Locality-Aware Insertion
```java
// Group nearby insertions together
public void insertLocalityAware(List<EntityData<ID, Content>> entities) {
    // Group entities by spatial proximity
    Map<SpatialBucket, List<EntityData<ID, Content>>> buckets = 
        groupBySpatialProximity(entities);
    
    // Process each bucket - entities in same bucket likely share parent chains
    for (var bucket : buckets.entrySet()) {
        // Warm up caches with first entity in bucket
        if (!bucket.getValue().isEmpty()) {
            var first = bucket.getValue().get(0);
            Tet tet = locate(first.position(), first.level());
            tet.tmIndex(); // Populate caches
        }
        
        // Process rest of bucket - cache hits likely
        for (var entity : bucket.getValue()) {
            insert(entity.id(), entity.position(), entity.level(), entity.content());
        }
    }
}

private record SpatialBucket(int x, int y, int z) {}

private Map<SpatialBucket, List<EntityData<ID, Content>>> groupBySpatialProximity(
        List<EntityData<ID, Content>> entities) {
    Map<SpatialBucket, List<EntityData<ID, Content>>> buckets = new HashMap<>();
    int bucketSize = 1000; // Tune based on data distribution
    
    for (var entity : entities) {
        int bx = (int)(entity.position().x / bucketSize);
        int by = (int)(entity.position().y / bucketSize);
        int bz = (int)(entity.position().z / bucketSize);
        var bucket = new SpatialBucket(bx, by, bz);
        buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(entity);
    }
    
    return buckets;
}
```

### 5. Level-Specific Optimizations
```java
// For shallow levels (0-5), use direct computation tables
private static final Map<Integer, TetreeKey> SHALLOW_LEVEL_CACHE = 
    precomputeShallowLevels();

private static Map<Integer, TetreeKey> precomputeShallowLevels() {
    Map<Integer, TetreeKey> cache = new HashMap<>();
    
    // Pre-compute all possible tetrahedra for levels 0-5
    for (byte level = 0; level <= 5; level++) {
        int cellSize = Constants.lengthAtLevel(level);
        int maxCoord = Constants.MAX_COORD / cellSize;
        
        for (int x = 0; x <= maxCoord; x++) {
            for (int y = 0; y <= maxCoord; y++) {
                for (int z = 0; z <= maxCoord; z++) {
                    for (byte type = 0; type <= 5; type++) {
                        Tet tet = new Tet(x * cellSize, y * cellSize, z * cellSize, level, type);
                        int key = packShallowKey(x, y, z, level, type);
                        cache.put(key, tet.tmIndex());
                    }
                }
            }
        }
    }
    
    return cache;
}

// In calculateSpatialIndex()
protected TetreeKey calculateSpatialIndex(Point3f position, byte level) {
    var tet = locate(position, level);
    
    // Fast path for shallow levels
    if (level <= 5) {
        int key = packShallowKey(
            tet.x() / Constants.lengthAtLevel(level),
            tet.y() / Constants.lengthAtLevel(level),
            tet.z() / Constants.lengthAtLevel(level),
            level, tet.type());
        
        TetreeKey cached = SHALLOW_LEVEL_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
    }
    
    // Use thread-local cache if enabled
    if (useThreadLocalCache) {
        return ThreadLocalTetreeCache.getTetreeKey(tet);
    }
    
    return tet.tmIndex();
}
```

## Medium-Term Optimizations

### 6. Parallel Parent Chain Computation
For batch operations, compute parent chains in parallel:
```java
public void insertBatchParallel(List<EntityData<ID, Content>> entities) {
    // Parallel pre-computation of TetreeKeys
    var entries = entities.parallelStream()
        .map(data -> {
            Tet tet = locate(data.position(), data.level());
            TetreeKey key = tet.tmIndex();
            return new TetEntry(data, tet, key);
        })
        .collect(Collectors.toList());
    
    // Sequential insertion (due to shared state)
    for (TetEntry entry : entries) {
        // ... insert logic
    }
}
```

### 7. Speculative Cache Pre-warming
```java
// When inserting at a location, pre-warm caches for likely neighbors
private void preWarmNeighborCaches(Tet tet) {
    // Pre-compute TetreeKeys for immediate neighbors
    executor.submit(() -> {
        for (int face = 0; face < 4; face++) {
            Tet neighbor = neighborFinder.findFaceNeighbor(tet, face);
            if (neighbor != null) {
                neighbor.tmIndex(); // Populate cache
            }
        }
    });
}
```

## Performance Impact Estimates

| Optimization | Implementation Effort | Expected Improvement |
|--------------|---------------------|---------------------|
| Increase Cache Sizes | Trivial | 10-20% |
| Batch Insert | Low | 30-50% for bulk ops |
| Parent Chain Caching | Medium | 20-30% |
| Locality-Aware Insert | Medium | 25-40% |
| Shallow Level Cache | Low | 15-25% |
| Parallel Pre-compute | Medium | 40-60% for bulk ops |

## Measurement Strategy

```java
// Add to Tetree.java
private final AtomicLong tmIndexCalls = new AtomicLong();
private final AtomicLong tmIndexTime = new AtomicLong();

// In calculateSpatialIndex()
long start = System.nanoTime();
TetreeKey result = tet.tmIndex();
long elapsed = System.nanoTime() - start;
tmIndexCalls.incrementAndGet();
tmIndexTime.addAndGet(elapsed);

// Reporting method
public String getPerformanceReport() {
    long calls = tmIndexCalls.get();
    long totalTime = tmIndexTime.get();
    double avgTime = calls > 0 ? (double)totalTime / calls : 0;
    
    return String.format(
        "TM-Index Performance: %d calls, %.2f ns average, %.2f ms total",
        calls, avgTime, totalTime / 1_000_000.0);
}
```

## Conclusion

While we cannot eliminate the fundamental O(level) complexity, these optimizations can significantly reduce the constant factor and improve real-world performance. The key is to:
1. Maximize cache effectiveness
2. Amortize tmIndex() costs across multiple operations
3. Exploit spatial locality in the data
4. Pre-compute where possible

With all optimizations applied, we could potentially reduce the performance gap from 1125x to approximately 200-300x for typical workloads.