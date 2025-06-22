# Tet.tetrahedron Migration Guide

**Date**: 2025-06-21  
**Status**: DEPRECATED METHOD - Will be removed in next major version

## Summary

The single-parameter `Tet.tetrahedron(long index)` method has been deprecated because it cannot correctly determine the level from the SFC index alone. This is a fundamental limitation of the tetrahedral space-filling curve.

## The Problem

```java
// WRONG - Same index exists at multiple levels!
Tet tet = Tet.tetrahedron(42);  // Which level? 🤷
```

The tetrahedral SFC has a many-to-one mapping where the same index value can represent different tetrahedra at different levels. Without the level, we cannot uniquely identify a tetrahedron.

## Migration Path

### Before (Deprecated):
```java
Tet tet = Tet.tetrahedron(index);
```

### After (Correct):
```java
// Option 1: If you know the level
Tet tet = Tet.tetrahedron(index, level);

// Option 2: If you have a Tet instance
Tet parent = someTetrahedron;
Tet child = parent.child(3);  // Child knows its level

// Option 3: From spatial operations
byte level = 10;  // Your target level
Tet tet = Tet.locate(point, level);
```

## Common Patterns

### Pattern 1: Traversing the Tree
```java
// Instead of storing just indices
Set<Long> tetIndices = new HashSet<>();

// Store Tet objects which include level information
Set<Tet> tets = new HashSet<>();
// OR store both index and level
Map<Long, Byte> tetIndexToLevel = new HashMap<>();
```

### Pattern 2: Neighbor Finding
```java
// Old way (flawed)
long[] neighbors = findNeighbors(tetIndex);

// New way (correct)
long[] neighbors = findNeighbors(tetIndex, level);
// OR
Tet[] neighbors = findNeighbors(tet);
```

### Pattern 3: Spatial Queries
```java
// When querying the spatial index
// The index should track level information
Map<Long, TetreeNode> spatialIndex;  // Node stores level

// Or create a compound key that includes level
record TetKey(long index, byte level) {}
Map<TetKey, TetreeNode> spatialIndex;
```

## Why This Change?

The tetrahedral SFC doesn't encode level information in the index itself (unlike Morton codes with level offsets). This is consistent with the t8code reference implementation, which always requires both index and level to identify a tetrahedron.

## Temporary Workaround

While migrating, the deprecated method uses `tetLevelFromIndex()` which makes a best-effort guess at the level. This is NOT reliable and should only be used during migration.

## Timeline

- **Current Release**: Method deprecated, warnings issued
- **Next Minor Release**: New level-aware APIs added where needed
- **Next Major Release**: Deprecated method will be removed

## Questions?

If you're unsure how to migrate your code, look for these patterns:
1. Are you storing tetrahedral indices? Consider storing level too
2. Are you passing indices between methods? Pass Tet objects instead
3. Are you using indices as map keys? Create a compound key with both index and level