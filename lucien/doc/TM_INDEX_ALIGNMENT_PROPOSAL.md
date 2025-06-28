# TM-Index Implementation Alignment Proposal

## Summary of Findings

The comparison test reveals fundamental differences between the implementations:

1. **Bit Extraction Order**: TMIndex128Clean uses LSB→MSB, Tet uses MSB→LSB
2. **Encoded Values**: Different bit patterns produce incompatible indices
3. **Type Computation**: TMIndex128Clean uses forward computation, Tet uses parent chain traversal

## Proposed Changes to Align Tet with TMIndex128Clean

### 1. Change Bit Extraction Order in `tmIndex()`

**Current (MSB to LSB):**

```java
for (int i = 0; i < maxBits; i++) {
    int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
    int xBit = (x >> bitPos) & 1;
    int yBit = (y >> bitPos) & 1;
    int zBit = (z >> bitPos) & 1;
}
```

**Proposed (LSB to MSB):**

```java
for (int i = 0; i < maxBits; i++) {
    int xBit = (x >> i) & 1;
    int yBit = (y >> i) & 1;
    int zBit = (z >> i) & 1;
}
```

### 2. Update Type Computation to Use Child Type Table

**Add to Tet class:**

```java
private static final int[][] CHILD_TYPES = {
    { 0, 0, 0, 0, 4, 5, 2, 1 },
    { 1, 1, 1, 1, 3, 2, 5, 0 },
    { 2, 2, 2, 2, 0, 1, 4, 3 },
    { 3, 3, 3, 3, 5, 4, 1, 2 },
    { 4, 4, 4, 4, 2, 3, 0, 5 },
    { 5, 5, 5, 5, 1, 0, 3, 4 }
};
```

**Replace type array building with:**

```java
int[] typeArray = new int[maxBits];
typeArray[0] = 0; // Root always type 0

int currentType = 0;
for (int level = 1; level < maxBits; level++) {
    int bitPos = level - 1;
    int childIdx = ((z >> bitPos) & 1) << 2 | 
                   ((y >> bitPos) & 1) << 1 | 
                   ((x >> bitPos) & 1);
    currentType = CHILD_TYPES[currentType][childIdx];
    typeArray[level] = currentType;
}
```

### 3. Update Decode Method

**Change bit reconstruction in `tetrahedron(BigInteger, byte)`:**

```java
// Build coordinates by placing bits at their actual positions
for (int i = 0; i < maxBits; i++) {
    x |= (coordXBits[i] << i);
    y |= (coordYBits[i] << i);
    z |= (coordZBits[i] << i);
}
```

### 4. Handle Coordinate Scaling

Since Tet uses absolute coordinates (scaled to 2^21) while TMIndex128Clean uses grid coordinates:

**Add conversion methods:**

```java
public static Tet fromGridCoordinates(int gridX, int gridY, int gridZ, byte level) {
    int scale = 1 << (Constants.getMaxRefinementLevel() - level);
    return new Tet(gridX * scale, gridY * scale, gridZ * scale, level, 
                   computeTypeFromPath(gridX, gridY, gridZ, level));
}

public Point3i toGridCoordinates() {
    int scale = 1 << (Constants.getMaxRefinementLevel() - l);
    return new Point3i(x / scale, y / scale, z / scale);
}
```

### 5. Update Child Methods

The `childStandard()` method should compute child types using the CHILD_TYPES table:

```java
public Tet childStandard(int childIndex) {
    if (childIndex < 0 || childIndex >= 8) {
        throw new IllegalArgumentException("Child index must be 0-7: " + childIndex);
    }

    byte childLevel = (byte) (l + 1);
    int cellSize = Constants.lengthAtLevel(childLevel);

    // Calculate child coordinates based on which octant
    int childX = x + ((childIndex & 1) != 0 ? cellSize : 0);
    int childY = y + ((childIndex & 2) != 0 ? cellSize : 0);
    int childZ = z + ((childIndex & 4) != 0 ? cellSize : 0);

    // Use CHILD_TYPES table for type
    byte childType = (byte) CHILD_TYPES[type][childIndex];

    return new Tet(childX, childY, childZ, childLevel, childType);
}
```

## Migration Strategy

### Phase 1: Add Compatibility Layer

1. Create `TetTMIndexCompat` class with both old and new implementations
2. Add feature flag to switch between implementations
3. Add conversion utilities between the two formats

### Phase 2: Parallel Testing

1. Run both implementations in parallel for validation
2. Compare performance characteristics
3. Ensure spatial queries work correctly with new encoding

### Phase 3: Migration

1. Update all code to use new implementation
2. Provide migration tool for existing data
3. Remove old implementation

## Benefits of Alignment

1. **Interoperability**: Compatible with TMIndex128Clean and potentially other implementations
2. **Performance**: Simpler type computation without parent chain traversal
3. **Clarity**: Standard bit ordering (LSB to MSB) is more intuitive
4. **Correctness**: Ensures consistent type assignment through refinement

## Risks and Mitigations

### Risk 1: Breaking Existing Data

- **Mitigation**: Provide migration tools and maintain backward compatibility during transition

### Risk 2: Performance Regression

- **Mitigation**: Benchmark both implementations thoroughly before switching

### Risk 3: Integration Issues

- **Mitigation**: Extensive testing with all spatial index operations

## Recommendation

Proceed with alignment to TMIndex128Clean's approach because:

1. It's algorithmically cleaner and more efficient
2. LSB-to-MSB bit ordering is more standard
3. Type computation via lookup table is O(1) vs O(level) for parent traversal
4. Better potential for interoperability with other tetrahedral indexing systems
