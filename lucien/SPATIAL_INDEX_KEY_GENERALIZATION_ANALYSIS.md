# Spatial Index Key Generalization Analysis

## Executive Summary

This document analyzes the feasibility of generalizing the spatial index key from `long` to an opaque type while maintaining Comparable semantics. The primary driver is to fix the Tetree's non-unique index problem by encoding level information in the key.

## Current State Analysis

### Problem Statement

The Tetree implementation currently has a critical flaw: **SFC indices are not unique across levels**. The same index value can represent different tetrahedra at different levels, leading to potential data loss when entities at different levels map to the same key.

Example:
- Level 1, index 7: A large tetrahedron
- Level 2, index 7: A smaller tetrahedron at a different location
- Current system: Both map to key `7L`, causing collisions

### Current Architecture

1. **Key Type**: All spatial indices use `long` as the key type
   - `Map<Long, NodeType> spatialIndex` in AbstractSpatialIndex
   - `NavigableSet<Long> sortedSpatialIndices` for sorted access
   - Method signatures like `hasNode(long mortonIndex)`

2. **Key Usage Patterns**:
   - **Comparison**: Keys are compared using natural ordering for range queries
   - **No Arithmetic**: Keys are treated as opaque identifiers (no addition/subtraction)
   - **Level Extraction**: Some code extracts level from keys (e.g., `Constants.toLevel()`)
   - **Spatial Locality**: Sorting preserves spatial locality for efficient queries

3. **Implementation Differences**:
   - **Octree**: Morton codes inherently encode level information
   - **Tetree**: SFC indices do NOT encode level, causing uniqueness problems

## Feasibility Analysis

### Is Comparable Invariant Maintainable?

**YES**, the Comparable invariant can be maintained between Octree and Tetree with an opaque key type, but with important considerations:

1. **Octree Ordering**: 
   - Current: Morton codes naturally order by spatial locality
   - With opaque key: Can wrap the long Morton code, preserving natural ordering

2. **Tetree Ordering**:
   - Current: Broken due to level ambiguity
   - With opaque key: Can encode `(level, sfcIndex)` with lexicographic ordering
   - This maintains spatial locality within each level

3. **Cross-Structure Comparison**:
   - Keys from different structures should NOT be comparable
   - Type system should prevent mixing Octree and Tetree keys

### Key Design Requirements

An opaque key type must satisfy:

1. **Uniqueness**: Each spatial location must map to exactly one key
2. **Comparable**: Support natural ordering for range queries
3. **Spatial Locality**: Close keys should represent close spatial regions
4. **Performance**: Minimize object allocation and comparison overhead
5. **Type Safety**: Prevent mixing incompatible key types
6. **Component Access**: Support efficient extraction of level, index, etc.

## Proposed Solution

### Key Interface Hierarchy

```java
// Base interface for all spatial keys
public interface SpatialKey<K extends SpatialKey<K>> extends Comparable<K> {
    /**
     * Get the level of this key in the spatial hierarchy.
     * Required for optimizations like SpatialIndexSet.
     */
    byte getLevel();
    
    /**
     * Convert to a long representation for legacy compatibility.
     * May throw UnsupportedOperationException if not applicable.
     */
    default long toLong() {
        throw new UnsupportedOperationException();
    }
}

// Octree-specific key
public final class MortonKey implements SpatialKey<MortonKey> {
    private final long mortonCode;
    
    public MortonKey(long mortonCode) {
        this.mortonCode = mortonCode;
    }
    
    @Override
    public byte getLevel() {
        return Constants.toLevel(mortonCode);
    }
    
    @Override
    public long toLong() {
        return mortonCode;
    }
    
    @Override
    public int compareTo(MortonKey other) {
        return Long.compare(this.mortonCode, other.mortonCode);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MortonKey)) return false;
        return mortonCode == ((MortonKey) o).mortonCode;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(mortonCode);
    }
}

// Tetree-specific key
public final class TetreeKey implements SpatialKey<TetreeKey> {
    private final byte level;
    private final long sfcIndex;
    
    public TetreeKey(byte level, long sfcIndex) {
        this.level = level;
        this.sfcIndex = sfcIndex;
    }
    
    @Override
    public byte getLevel() {
        return level;
    }
    
    public long getSfcIndex() {
        return sfcIndex;
    }
    
    @Override
    public int compareTo(TetreeKey other) {
        // Lexicographic ordering: first by level, then by SFC index
        int levelCmp = Byte.compare(this.level, other.level);
        if (levelCmp != 0) return levelCmp;
        return Long.compare(this.sfcIndex, other.sfcIndex);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TetreeKey)) return false;
        TetreeKey that = (TetreeKey) o;
        return level == that.level && sfcIndex == that.sfcIndex;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(level, sfcIndex);
    }
}
```

### Updated Interface Signatures

```java
public interface SpatialIndex<ID, Content, K extends SpatialKey<K>> {
    boolean hasNode(K spatialKey);
    void removeNode(K spatialKey);
    Content getNode(K spatialKey);
    // ... other methods updated similarly
}

public abstract class AbstractSpatialIndex<ID, Content, K extends SpatialKey<K>, 
                                          NodeType extends Node<ID, Content>> 
    implements SpatialIndex<ID, Content, K> {
    
    protected final Map<K, NodeType> spatialIndex;
    protected final NavigableSet<K> sortedSpatialIndices;
    // ... implementation updated for generic keys
}
```

## Implementation Plan

### Phase 1: Key Infrastructure (Week 1)
1. Create `SpatialKey` interface hierarchy
2. Implement `MortonKey` and `TetreeKey` classes
3. Add comprehensive unit tests for key ordering and equality
4. Verify spatial locality preservation

### Phase 2: Core Refactoring (Week 2)
1. Update `SpatialIndex` interface to use generic key type
2. Refactor `AbstractSpatialIndex` to use `K extends SpatialKey<K>`
3. Update `SpatialIndexSet` to work with generic keys
4. Ensure all shared functionality remains generic

### Phase 3: Octree Migration (Week 3)
1. Update `Octree` to use `MortonKey`
2. Modify `calculateSpatialIndex` to return `MortonKey`
3. Update all Octree-specific methods
4. Run full test suite to ensure no regression

### Phase 4: Tetree Migration (Week 3-4)
1. Update `Tetree` to use `TetreeKey`
2. Modify `calculateSpatialIndex` to return `TetreeKey(level, sfcIndex)`
3. Fix level encoding to ensure uniqueness
4. Verify collision issues are resolved

### Phase 5: Performance Optimization (Week 4)
1. Profile key allocation overhead
2. Consider key pooling for frequently used keys
3. Optimize comparison operations
4. Benchmark against current implementation

### Phase 6: Documentation & Cleanup (Week 5)
1. Update all documentation
2. Remove deprecated long-based methods
3. Update CLAUDE.md with new architecture
4. Create migration guide for any external users

## Benefits

1. **Correctness**: Fixes Tetree's non-unique index problem
2. **Type Safety**: Prevents mixing incompatible key types
3. **Extensibility**: Easy to add new spatial structures
4. **Maintainability**: Clear separation of concerns
5. **Future-Proof**: Supports arbitrary key encodings

## Risks and Mitigations

1. **Performance Overhead**
   - Risk: Object allocation for each key
   - Mitigation: Key pooling, primitive specialization

2. **API Breaking Changes**
   - Risk: Existing code depends on long keys
   - Mitigation: Deprecation period, compatibility layer

3. **Complexity Increase**
   - Risk: Generic types make code harder to understand
   - Mitigation: Clear documentation, consistent naming

## Conclusion

Generalizing the spatial index key from `long` to an opaque type is both **feasible and necessary** to fix the Tetree's correctness issues. The Comparable invariant can be maintained, and the design provides better type safety and extensibility. The implementation plan minimizes risk through phased migration and comprehensive testing.

### Recommendation

**Proceed with implementation** starting with Phase 1 immediately. The Tetree's current non-unique index problem represents a critical bug that could cause data loss in production use. The proposed solution addresses this while maintaining performance and adding valuable type safety.