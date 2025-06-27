# Transformation Plan: Tet.tmIndex() to TMIndex128Clean

## Executive Summary

Transform the current `Tet.tmIndex()` implementation from using BigInteger to a 128-bit two-long implementation based on TMIndex128Clean. This will provide significant performance improvements while maintaining the same functionality.

## Performance Analysis

### Current Performance (Based on CLAUDE.md)
- **Tetree Insertion**: 483 μs/entity (largely due to tmIndex overhead)
- **BigInteger Operations**: ~10-50x slower than native operations

### Expected Performance (Based on TMIndex128Clean)
- **TMIndex128Clean encode**: 44.7 ns/op
- **TMIndex128Clean decode**: 38.5 ns/op
- **Expected speedup**: 10-50x for tmIndex operations

## Key Differences Between Implementations

### Current Tet.tmIndex()
```java
// Uses BigInteger with parent chain walking
BigInteger index = BigInteger.ZERO;
BigInteger sixty_four = BigInteger.valueOf(64);
// Complex bit manipulation with BigInteger operations
index = index.multiply(sixty_four).add(BigInteger.valueOf(sixBits));
```

### TMIndex128Clean
```java
// Uses two longs with direct bit operations
long low = 0L, high = 0L;
// Simple bit shifting and OR operations
low |= ((long) sixBits) << (6 * level);
high |= ((long) sixBits) << (6 * highLevel);
```

## Transformation Steps

### Step 1: Add TMIndex128Bit Support to TetreeKey
```java
public class TetreeKey implements SpatialKey<TetreeKey> {
    private final byte level;
    private final BigInteger index;  // Keep for compatibility
    private final long lowBits;      // Add for 128-bit support
    private final long highBits;     // Add for 128-bit support
    
    // Add constructor for 128-bit values
    public TetreeKey(byte level, long low, long high) {
        this.level = level;
        this.lowBits = low;
        this.highBits = high;
        // Convert to BigInteger for compatibility
        this.index = convertToBigInteger(low, high);
    }
}
```

### Step 2: Create Optimized tmIndex128() Method
```java
public TetreeKey tmIndex128() {
    // Check cache first
    var cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
    if (cached != null) {
        return cached;
    }
    
    if (l == 0) {
        return ROOT_TET_128;
    }
    
    // Build type sequence using lookup tables
    int[] typeSequence = computeTypeSequence();
    
    // Direct bit manipulation with two longs
    long low = 0L, high = 0L;
    
    for (int i = 0; i < l; i++) {
        int xBit = (x >> i) & 1;
        int yBit = (y >> i) & 1;
        int zBit = (z >> i) & 1;
        
        int coordBits = (zBit << 2) | (yBit << 1) | xBit;
        int typeBits = typeSequence[i] & 0x7;
        int sixBits = (coordBits << 3) | typeBits;
        
        if (i < 10) {
            low |= ((long) sixBits) << (6 * i);
        } else {
            high |= ((long) sixBits) << (6 * (i - 10));
        }
    }
    
    var result = new TetreeKey(l, low, high);
    TetreeLevelCache.cacheTetreeKey(x, y, z, l, type, result);
    return result;
}
```

### Step 3: Add Lookup Tables for Type Computation
```java
// Add to Tet class
private static final int[][] CHILD_TYPES = {
    { 0, 0, 0, 0, 4, 5, 2, 1 },
    { 1, 1, 1, 1, 3, 2, 5, 0 },
    { 2, 2, 2, 2, 0, 1, 4, 3 },
    { 3, 3, 3, 3, 5, 4, 1, 2 },
    { 4, 4, 4, 4, 2, 3, 0, 5 },
    { 5, 5, 5, 5, 1, 0, 3, 4 }
};

private int[] computeTypeSequence() {
    int[] types = new int[l];
    if (l == 0) return types;
    
    types[0] = 0; // Root always type 0
    int currentType = 0;
    
    for (int level = 1; level < l; level++) {
        int bitPos = level - 1;
        int childIdx = ((z >> bitPos) & 1) << 2 | ((y >> bitPos) & 1) << 1 | ((x >> bitPos) & 1);
        currentType = CHILD_TYPES[currentType][childIdx];
        types[level] = currentType;
    }
    
    return types;
}
```

### Step 4: Create Inverse Function (tetrahedron from TetreeKey)
```java
public static Tet tetrahedron128(TetreeKey key) {
    if (key.level() == 0) {
        return new Tet(0, 0, 0, (byte)0, (byte)0);
    }
    
    int x = 0, y = 0, z = 0;
    long low = key.getLowBits();
    long high = key.getHighBits();
    
    // Extract coordinates from packed bits
    for (int i = 0; i < key.level(); i++) {
        int sixBits;
        if (i < 10) {
            sixBits = (int) ((low >> (6 * i)) & 0x3F);
        } else {
            sixBits = (int) ((high >> (6 * (i - 10))) & 0x3F);
        }
        
        int coordBits = (sixBits >> 3) & 0x7;
        x |= (coordBits & 1) << i;
        y |= ((coordBits >> 1) & 1) << i;
        z |= ((coordBits >> 2) & 1) << i;
    }
    
    // Compute final type by following the path
    int finalType = 0;
    for (int i = 0; i < key.level(); i++) {
        int childIdx = ((z >> i) & 1) << 2 | ((y >> i) & 1) << 1 | ((x >> i) & 1);
        finalType = CHILD_TYPES[finalType][childIdx];
    }
    
    return new Tet(x, y, z, (byte)finalType, key.level());
}
```

### Step 5: Migration Strategy

1. **Phase 1**: Add new methods alongside existing ones
   - Implement tmIndex128() and tetrahedron128()
   - Keep existing tmIndex() for compatibility
   - Add feature flag to switch between implementations

2. **Phase 2**: Update callers gradually
   - Identify all tmIndex() call sites
   - Update performance-critical paths first (insertions)
   - Run parallel testing to ensure correctness

3. **Phase 3**: Complete migration
   - Switch all callers to new implementation
   - Deprecate old BigInteger-based methods
   - Remove old implementation after validation period

## Testing Strategy

1. **Correctness Tests**
   - Round-trip encoding/decoding for all levels (0-21)
   - Verify same results as current implementation
   - Edge cases: level 0, level 21, boundary coordinates

2. **Performance Tests**
   - Benchmark encoding/decoding at each level
   - Compare with current implementation
   - Verify expected 10-50x speedup

3. **Integration Tests**
   - Test with existing Tetree operations
   - Verify spatial queries still work correctly
   - Check cache integration

## Risk Mitigation

1. **Compatibility Risk**: Keep both implementations during transition
2. **Correctness Risk**: Extensive testing with known values
3. **Performance Risk**: Benchmark at each step to ensure improvements

## Expected Outcomes

- **10-50x faster tmIndex operations** (from ~500-1000 ns to ~45 ns)
- **Reduced memory usage** (32 bytes vs 68 bytes per TetreeKey)
- **Better cache efficiency** due to smaller objects
- **Overall Tetree insertion improvement** from 483 μs to potentially <100 μs per entity

## Timeline

1. Week 1: Implement core transformation (Steps 1-4)
2. Week 2: Testing and validation
3. Week 3-4: Phased migration of callers
4. Week 5: Performance validation and optimization
5. Week 6: Cleanup and documentation