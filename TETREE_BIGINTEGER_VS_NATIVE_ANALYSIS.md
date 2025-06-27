# Why Java Uses BigInteger vs Native Integers for tmIndex

## Understanding the Two Different Indices

There are two distinct indices in the tetrahedral spatial decomposition:

1. **Linear Index** (`consecutiveIndex()`): A simple consecutive numbering within a level
2. **TM-Index** (`tmIndex()`): The tetrahedral Morton index - a space-filling curve that interleaves spatial and type information

This analysis focuses on the **TM-Index**, which is what requires BigInteger in Java.

## The Bit Requirements Problem

The Java TM-index implementation uses BigInteger because the index requires more than 64 bits:

### TM-Index Bit Requirements

For the TM-index encoding, each level requires 6 bits:
- 3 bits for spatial coordinates (x, y, z) 
- 3 bits for tetrahedral type information

| Level | Total Bits | Fits in 64-bit long? |
|-------|------------|---------------------|
| 0-10  | 0-60       | ✓ Yes               |
| 11    | 66         | ✗ No                |
| 14    | 84         | ✗ No                |
| 21    | 126        | ✗ No                |

**Key Finding**: The TM-index requires at least 84 bits for practical use cases (level 14+), and 126 bits for the full 21 levels.

## Why t8code Uses Native Integers Differently

t8code's `t8_dtri_linear_id` is NOT equivalent to the Java TM-index:

### t8code's Linear ID
- Encodes only child indices (0-7) at each level
- 3 bits per level (for 8 children)
- Maximum 21 levels × 3 bits = 63 bits
- **Does NOT include type information in the index**
- Type is stored separately in the `t8_dtri_t` structure

### Java's TM-Index
- Interleaves coordinate bits AND type information
- 6 bits per level (3 spatial + 3 type)
- Creates a true space-filling curve with type encoding
- Maximum 21 levels × 6 bits = 126 bits
- **Includes complete type hierarchy in the index**

## Why Java Cannot Use Native Integers for TM-Index

### 1. Fundamental Algorithm Difference
```java
// Java TM-index: Interleaves coordinates AND types
int sixBits = (coordBits << 3) | typeArray[i];  // 6 bits per level
```

```c
// t8code: Only encodes child position
id |= ((t8_linearidx_t) Iloc) << exponent;  // 3 bits per level
```

### 2. Complete Type Encoding
- Java's TM-index encodes the complete ancestor type hierarchy
- This is essential for the space-filling curve properties
- Each tetrahedron's type affects its children's positions

### 3. Minimum 84+ Bits Required
- Even at moderate levels (14+), the TM-index exceeds 64 bits
- No amount of optimization can compress 84-126 bits into 64 bits without losing information

## Could Java Use Native Integers for TM-Index?

No, not without fundamentally changing what the TM-index represents:

### Option 1: Use Two Longs (128 bits)
- Store the 126-bit TM-index across two long values
- Implement custom 128-bit arithmetic operations
- Would eliminate BigInteger overhead while preserving the algorithm
- Most viable option for performance improvement

### Option 2: Reduce to t8code's 3-bit Linear ID
- Would lose type information from the index
- No longer a true tetrahedral Morton index
- Would break space-filling curve properties
- Not recommended as it changes the fundamental data structure

### Option 3: Hybrid Approach for Small Levels
```java
public TetreeKey tmIndex() {
    if (level <= 10) {
        // Levels 0-10 need only 60 bits, fits in long
        return new TetreeKey(level, computeNativeTmIndex());
    } else {
        // Levels 11-21 need 66-126 bits, use BigInteger
        return computeBigIntegerTmIndex();
    }
}
```

## Performance Impact

The BigInteger requirement is inherent to the TM-index design:
- The 6 bits per level (3 spatial + 3 type) is fundamental
- Cannot be compressed without losing information
- BigInteger overhead contributes to the 372x slower performance

## Conclusion

**The Java implementation requires BigInteger (or equivalent 128-bit arithmetic) because the TM-index fundamentally needs 84-126 bits to encode both spatial position and type hierarchy. This is not a design flaw but a consequence of creating a true tetrahedral space-filling curve that includes complete type information.**

The only way to use native 64-bit integers would be to abandon the TM-index in favor of a simpler 3-bit-per-level encoding like t8code's linear ID, but this would sacrifice the type encoding that makes the TM-index a complete space-filling curve.