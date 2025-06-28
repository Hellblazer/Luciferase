# Tetree Coordinate System Analysis

## The Problem

The Tetree implementation supports two coordinate systems:

1. **Grid Coordinates**: Small integers in range [0, 2^level-1] as returned by `locateStandardRefinement()`
2. **Absolute Coordinates**: Large integers representing absolute positions, typically multiples of
   `Constants.lengthAtLevel()`

The `tmIndex()` method needs to encode both types of coordinates into the same TM-index format, which expects bits to be
aligned with a hierarchical structure.

## Current Implementation Issues

### Encoding (tmIndex)

The current implementation detects coordinate type and shifts grid coordinates:

```java
int maxGridCoord = (1 << l) - 1;
boolean isGridCoordinates = x <= maxGridCoord && y <= maxGridCoord && z <= maxGridCoord;

if (isGridCoordinates) {
    int shiftAmount = Constants.getMaxRefinementLevel() - l;
    shiftedX = x << shiftAmount;
    shiftedY = y << shiftAmount;
    shiftedZ = z << shiftAmount;
} else {
    // Already absolute coordinates
    shiftedX = x;
    shiftedY = y;
    shiftedZ = z;
}
```

### Decoding (tetrahedron)

The decoding attempts to detect if coordinates need to be shifted back:

```java
int testX = x >> shiftAmount;
if (testX <= maxGridCoord) {
    // These were grid coordinates that were shifted
    x >>= shiftAmount;
    y >>= shiftAmount;
    z >>= shiftAmount;
}
```

### The Flaw

This detection is unreliable because:

1. **Ambiguity**: A shifted grid coordinate can have the same value as an unshifted absolute coordinate
2. **Example**: At level 1:
    - Grid coordinate 1 shifted by 20 bits = 1048576
    - Absolute coordinate 1048576 (= Constants.lengthAtLevel(1)) = 1048576
    - Both encode to the same value, but decode differently

## Solutions

### Option 1: Standardize on One Coordinate System

The cleanest solution is to standardize on absolute coordinates throughout:

1. **Always use absolute coordinates** when creating Tet objects
2. Convert grid coordinates to absolute at the API boundary
3. Remove coordinate detection logic entirely

**Pros**:

- Simple and unambiguous
- No round-trip issues
- Consistent with how Morton codes work in Octree

**Cons**:

- Breaking change for code using grid coordinates
- May require updates throughout the codebase

### Option 2: Encode Coordinate Type in TM-Index

Reserve a bit or use a different encoding scheme to indicate coordinate type.

**Pros**:

- Preserves both coordinate systems
- Unambiguous decoding

**Cons**:

- Reduces available coordinate space
- More complex encoding/decoding
- Different from reference implementation

### Option 3: Document the Limitation

Accept that round-trip conversion only works reliably for one coordinate system and document this clearly.

**Pros**:

- No code changes needed
- Maintains compatibility

**Cons**:

- Surprise failures for users
- Limits usability

## Recommendation

**Standardize on absolute coordinates (Option 1)** because:

1. It aligns with how spatial indices typically work
2. It matches the Octree implementation pattern
3. It provides reliable round-trip conversion
4. The performance impact is negligible
5. Grid coordinates can always be converted to absolute: `absoluteCoord = gridCoord * Constants.lengthAtLevel(level)`

## Implementation Plan

1. Update `Tet` constructor documentation to clarify it expects absolute coordinates
2. Remove coordinate detection logic from `tmIndex()` and `tetrahedron()`
3. Update all test cases to use absolute coordinates
4. Add utility methods for grid-to-absolute conversion if needed
5. Update any code that creates Tet objects with grid coordinates
