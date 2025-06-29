# TM-Index Mismatch Analysis

## Executive Summary

The `Tet.childTM()` method doesn't produce geometrically contained children because it's using the wrong table. The `TYPE_TO_TYPE_OF_CHILD_MORTON` table contains child **types** in TM order, not the **Bey indices** needed to access children in TM order.

## The Root Cause

### What TetrahedralSubdivision Does (CORRECT)
```java
// TetrahedralSubdivision.java - CORRECT implementation
Tetrahedron getTMChild(int tmIndex) {
    // Get all children in Bey's order
    Tetrahedron[] beyChildren = subdivide();
    
    // Convert TM index to Bey index using the permutation table
    int beyIndex = SubdivisionTables.TM_TO_BEY_PERMUTATION[this.type][tmIndex];
    
    return beyChildren[beyIndex];
}
```

The `TM_TO_BEY_PERMUTATION` table correctly maps TM indices to Bey indices:
```java
// For parent type 0:
TM_TO_BEY_PERMUTATION[0] = { 0, 1, 4, 7, 2, 3, 6, 5 }
// This means: TM child 0 is Bey child 0, TM child 2 is Bey child 4, etc.
```

### What Tet.childTM() Does (WRONG)
```java
// Tet.java - WRONG implementation
public Tet childTM(byte i) {
    return child(TYPE_TO_TYPE_OF_CHILD_MORTON[type][i]);
}
```

The problem is that `TYPE_TO_TYPE_OF_CHILD_MORTON` contains child **types**, not Bey indices:
```java
// For parent type 0:
TYPE_TO_TYPE_OF_CHILD_MORTON[0] = { 0, 0, 4, 5, 0, 1, 2, 0 }
// These are TYPES (0-5), not child indices (0-7)!
```

## The Confusion

The name `TYPE_TO_TYPE_OF_CHILD_MORTON` is misleading. It actually contains:
- **What it is**: The types of children when accessed in TM order
- **What childTM needs**: The Bey indices to access children in TM order

## Example of the Bug

For parent type 0, TM child 3:
- **Correct**: TM child 3 → Bey child 7 (from TM_TO_BEY_PERMUTATION)
- **Wrong**: childTM(3) calls child(5) because TYPE_TO_TYPE_OF_CHILD_MORTON[0][3] = 5
- Since 5 is a valid child index (0-7), it returns Bey child 5 instead of child 7
- This gives the wrong geometric child!

## The Fix

Tet needs a proper TM-to-Bey permutation table like TetrahedralSubdivision has:

```java
// Add to Constants.java or Tet.java
public static final byte[][] TM_TO_BEY_PERMUTATION = {
    { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
    { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
    { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
    { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
    { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
    { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
};

// Fix childTM:
public Tet childTM(byte i) {
    byte beyIndex = TM_TO_BEY_PERMUTATION[type][i];
    return child(beyIndex);
}
```

## Verification

The test output shows that for many TM indices, childTM() is returning type 0 children when it should return other types. This happens because it's using type values (0-5) as child indices, and when the type is 0, 1, or 2, it accidentally accesses valid children but the wrong ones.

## Impact

This bug causes:
1. TM-ordered children are not geometrically contained in their parent
2. Space-filling curve traversal using TM indices doesn't work correctly
3. Any algorithm relying on TM-ordered child access gets wrong results