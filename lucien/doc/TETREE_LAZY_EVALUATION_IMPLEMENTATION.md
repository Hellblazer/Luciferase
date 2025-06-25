# Tetree Lazy Evaluation Implementation Plan

## Problem Analysis

The current insertion flow shows that every insert MUST compute tmIndex() immediately:

```java
// In AbstractSpatialIndex.insertAtPosition()
Key spatialIndex = calculateSpatialIndex(position, level);  // EXPENSIVE for Tetree
NodeType node = getSpatialIndex().computeIfAbsent(spatialIndex, k -> {...});
```

This is because:
1. The spatial index Map requires the key immediately
2. The sorted set needs the key for ordering
3. Entity tracking needs the key

## Proposed Solution: Lazy TetreeKey

### 1. Create LazyTetreeKey Class

```java
public class LazyTetreeKey extends TetreeKey {
    private final Tet tet;
    private final int lazyHashCode;  // Pre-computed for HashMap
    private volatile TetreeKey resolved;
    
    public LazyTetreeKey(Tet tet) {
        super((byte) -1, null);  // Placeholder values
        this.tet = tet;
        // Pre-compute hash based on Tet coordinates for HashMap efficiency
        this.lazyHashCode = computeLazyHash(tet);
    }
    
    private static int computeLazyHash(Tet tet) {
        // Hash based on coordinates only - sufficient for HashMap distribution
        int hash = 31 * tet.x();
        hash = 31 * hash + tet.y();
        hash = 31 * hash + tet.z();
        hash = 31 * hash + tet.l();
        hash = 31 * hash + tet.type();
        return hash;
    }
    
    @Override
    public BigInteger getTmIndex() {
        ensureResolved();
        return resolved.getTmIndex();
    }
    
    @Override
    public byte getLevel() {
        return tet.l();  // Can return immediately
    }
    
    @Override
    public int hashCode() {
        return lazyHashCode;  // Use pre-computed hash
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TetreeKey)) return false;
        
        if (obj instanceof LazyTetreeKey other) {
            // Compare Tet directly if both are lazy
            return tet.equals(other.tet);
        } else {
            // Must resolve to compare with regular TetreeKey
            ensureResolved();
            return resolved.equals(obj);
        }
    }
    
    @Override
    public int compareTo(TetreeKey other) {
        // Only resolve when actual comparison needed
        ensureResolved();
        return resolved.compareTo(other);
    }
    
    private void ensureResolved() {
        if (resolved == null) {
            synchronized (this) {
                if (resolved == null) {
                    resolved = tet.tmIndex();
                }
            }
        }
    }
    
    public boolean isResolved() {
        return resolved != null;
    }
}
```

### 2. Modify Tetree to Use Lazy Keys

```java
// In Tetree.java
@Override
protected TetreeKey calculateSpatialIndex(Point3f position, byte level) {
    var tet = locate(position, level);
    
    // Return lazy key for deferred computation
    if (bulkLoadingMode || deferTmIndexComputation) {
        return new LazyTetreeKey(tet);
    }
    
    // Use existing caching for immediate computation
    if (useThreadLocalCache) {
        return ThreadLocalTetreeCache.getTetreeKey(tet);
    }
    
    return tet.tmIndex();
}

// Add configuration option
private boolean deferTmIndexComputation = true;

public void setDeferTmIndexComputation(boolean defer) {
    this.deferTmIndexComputation = defer;
}
```

### 3. Optimize Sorted Set Operations

The NavigableSet requires comparison, which forces resolution. We can defer this:

```java
// In AbstractSpatialIndex
public class LazyNavigableSet<K extends SpatialKey<K>> implements NavigableSet<K> {
    private final Set<K> unorderedSet = new HashSet<>();
    private volatile NavigableSet<K> orderedSet = null;
    
    @Override
    public boolean add(K key) {
        if (orderedSet != null) {
            return orderedSet.add(key);
        }
        return unorderedSet.add(key);
    }
    
    // Force ordering only when needed
    private void ensureOrdered() {
        if (orderedSet == null) {
            synchronized (this) {
                if (orderedSet == null) {
                    orderedSet = new TreeSet<>(unorderedSet);
                    unorderedSet.clear();
                }
            }
        }
    }
    
    @Override
    public K first() {
        ensureOrdered();
        return orderedSet.first();
    }
    
    // ... other NavigableSet methods trigger ensureOrdered()
}
```

### 4. Batch Resolution Strategy

For bulk operations, resolve keys in batches:

```java
public void resolveLazyKeys() {
    List<LazyTetreeKey> lazyKeys = spatialIndex.keySet().stream()
        .filter(k -> k instanceof LazyTetreeKey)
        .map(k -> (LazyTetreeKey) k)
        .filter(k -> !k.isResolved())
        .collect(toList());
    
    if (!lazyKeys.isEmpty()) {
        log.debug("Resolving {} lazy keys", lazyKeys.size());
        
        // Resolve in parallel for better performance
        lazyKeys.parallelStream().forEach(LazyTetreeKey::ensureResolved);
        
        // Rebuild sorted indices if needed
        if (sortedSpatialIndices instanceof LazyNavigableSet) {
            ((LazyNavigableSet<Key>) sortedSpatialIndices).ensureOrdered();
        }
    }
}
```

## Expected Performance Impact

### Single Insertions
- Current: 105μs (with tmIndex computation)
- With lazy evaluation: ~15-20μs (deferred computation)
- **5-7x improvement** for insertion

### Bulk Insertions
- Current: 43μs per entity
- With lazy evaluation: ~10-15μs per entity
- **3-4x improvement** 

### When Keys Are Resolved
- During range queries (need ordering)
- During k-NN searches (need comparison)
- During tree balancing
- On explicit request

## Implementation Steps

1. **Phase 1**: Implement LazyTetreeKey class
2. **Phase 2**: Modify Tetree to return lazy keys
3. **Phase 3**: Implement LazyNavigableSet for deferred ordering
4. **Phase 4**: Add batch resolution strategies
5. **Phase 5**: Performance testing and tuning

## Risks and Mitigations

### Risk 1: Memory Overhead
- Each LazyTetreeKey holds a Tet reference
- Mitigation: Tet objects are small (20 bytes)

### Risk 2: Comparison Performance
- First comparison forces resolution
- Mitigation: Batch operations can pre-resolve

### Risk 3: Concurrent Resolution
- Multiple threads might resolve same key
- Mitigation: Synchronized resolution with volatile field

## Conclusion

Lazy evaluation of TetreeKey can provide significant performance improvements by deferring the expensive tmIndex() computation until absolutely necessary. This is particularly effective for:
- High-volume insertions
- Bulk loading scenarios  
- Cases where many inserted entities are never queried

Combined with the existing optimizations, this could reduce the Tetree insertion performance gap from 70x to approximately 15-20x compared to Octree.