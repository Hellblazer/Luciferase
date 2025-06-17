# Tetree t8code Gap Analysis and Implementation Plan

**Date:** June 17, 2025  
**Status:** ACTIVE - Progress on supporting algorithms  
**Last Updated:** June 17, 2025

## Executive Summary

The current Java Tetree implementation has fundamental discrepancies with the t8code reference implementation, leading
to incorrect child generation, broken family validation, and space-filling curve inconsistencies. This analysis
identifies the root causes and provides a comprehensive remediation plan.

## Critical Issues Identified

### 1. **INCORRECT CHILD COORDINATE CALCULATION**

**Severity:** CRITICAL  
**Impact:** Breaks all tetrahedral operations

**Current Java Implementation (WRONG):**

```java
// From Tet.child() method
byte cubeId = PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[type][childIndex];
int childX = x;
int childY = y;
int childZ = z;
if((cubeId &1)!=0)childX +=h /2;  // bit 0: X offset
if((cubeId &2)!=0)childY +=h /2;  // bit 1: Y offset  
if((cubeId &4)!=0)childZ +=h /2;  // bit 2: Z offset
```

**Correct t8code Implementation:**

```c
// From t8_dtri_child() function in t8code
Bey_cid = t8_dtri_index_to_bey_number[t->type][childid];
if (Bey_cid == 0) {
    c->x = t->x;
    c->y = t->y;
    c->z = t->z;
} else {
    vertex = t8_dtri_beyid_to_vertex[Bey_cid];
    t8_dtri_compute_coords(t, vertex, t_coordinates);
    c->x = (t->x + t_coordinates[0]) >> 1;
    c->y = (t->y + t_coordinates[1]) >> 1;
    c->z = (t->z + t_coordinates[2]) >> 1;
}
```

**Root Cause:** Java implementation treats tetrahedra as cubes, using cube-based coordinate offsets instead of
tetrahedral vertex-based calculations.

### 2. **INCONSISTENT LOOKUP TABLES**

**Severity:** HIGH  
**Impact:** Wrong types and parent-child relationships

**Issue:** Multiple lookup tables in Constants.java are inconsistent with each other and with TetreeConnectivity.java:

- `PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID` has duplicate cube IDs (children 1,2,3 all get cube ID 1)
- `PARENT_TYPE_LOCAL_INDEX_TO_TYPE` doesn't match `TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE`

**Evidence from validation tests:**

```
Child 6 parent: Tet[x=0, y=0, z=0, l=0, type=4] (expected: Tet[x=0, y=0, z=0, l=0, type=0], matches: false)
```

### 3. **MISSING T8CODE ALGORITHM IMPLEMENTATIONS**

**Severity:** HIGH  
**Impact:** Incomplete tetrahedral operations

**Missing Functions:**

- `t8_dtri_compute_coords()` - Calculate vertex coordinates of tetrahedron
- `t8_dtri_index_to_bey_number[]` - Mapping from Morton index to Bey child ID
- `t8_dtri_beyid_to_vertex[]` - Mapping from Bey child ID to vertex number
- Proper vertex-based coordinate calculation

### 4. **SPACE-FILLING CURVE ROUND-TRIP FAILURES**

**Severity:** HIGH  
**Impact:** Index↔Tetrahedron conversion broken

**Evidence:**

```
ROUND-TRIP FAILURE:
  Original index: 2
  Tetrahedron: Tet[x=0, y=1048576, z=0, l=1, type=0]
  Reconstructed index: 1
```

**85 round-trip failures** in SFCRoundTripTest indicate the SFC indexing is fundamentally broken.

## Recent Progress (June 2025)

### ✅ Completed Supporting Algorithm Implementations

**TetreeBits.java** - Efficient bitwise operations for tetrahedral indices:
- ✅ `extractLevel()` - Fast level extraction from SFC indices
- ✅ `extractType()` - Type extraction using t8code algorithm 
- ✅ `computeCubeLevel()` - **NEW (June 17, 2025)** - Compute cube level from tetrahedral coordinates
- ✅ `localityHash()` - Spatial locality-preserving hash codes
- ✅ `lowestCommonAncestorLevel()` - Based on t8code's NCA algorithm
- ✅ `parentCoordinate()` - Parent coordinate calculation using bitwise operations
- ✅ `packTet()` / `unpackTet()` - Efficient tetrahedron storage
- ✅ All supporting bitwise arithmetic operations

**TetreeConnectivity.java** - Complete connectivity tables:
- ✅ All parent-child type mappings
- ✅ Sibling relationship calculations
- ✅ Child validation and family checking

**TetreeIterator.java** - Multiple traversal patterns:
- ✅ Depth-first, breadth-first, level-order, Morton-order traversal
- ✅ Comprehensive test coverage

**TetreeNeighborFinder.java** - Neighbor relationship algorithms:
- ✅ Face, edge, vertex neighbor finding
- ✅ All 6 tetrahedron types supported

**TetreeFamily.java** - Family validation:
- ✅ Complete sibling validation algorithms
- ✅ Family completeness checking

**TetreeValidator.java** - Comprehensive validation suite:
- ✅ Structure validation, coordinate checking, type validation

**TetreeSFCRayTraversal.java** - Ray traversal optimization:
- ✅ Specialized ray traversal using SFC properties

### 🚧 Core Algorithm Issues Remain

Despite the supporting algorithm progress, the **core child/parent calculation algorithms in Tet.java still require the critical fixes** outlined in this analysis. The supporting algorithms provide the infrastructure, but the fundamental t8code parity issues persist.

## Detailed t8code Reference Analysis

### T8code Tables and Constants

From `/t8code/src/t8_schemes/t8_default/t8_dtet_connectivity.c`:

1. **Child Type Mapping (CORRECT - already matches our TetreeConnectivity):**

```c
const int t8_dtet_type_of_child[6][8] = {
  {0, 0, 0, 0, 4, 5, 2, 1},  // Parent type 0
  {1, 1, 1, 1, 3, 2, 5, 0},  // Parent type 1
  // ... etc
};
```

2. **Index to Bey Number Mapping (MISSING from Java):**

```c
const int t8_dtet_index_to_bey_number[6][8] = {
  {0, 1, 4, 5, 2, 7, 6, 3},  // Parent type 0
  {0, 1, 5, 4, 7, 2, 6, 3},  // Parent type 1
  // ... etc
};
```

3. **Bey ID to Vertex Mapping (MISSING from Java):**

```c
const int t8_dtet_beyid_to_vertex[8] = { 0, 1, 2, 3, 1, 1, 2, 2 };
```

4. **Vertex Coordinate Calculation (MISSING from Java):**
   The t8code `t8_dtri_compute_coords()` function calculates the actual 3D coordinates of tetrahedron vertices based on
   type and level.

## Comprehensive Implementation Plan

### Phase 1: Fix Child Coordinate Calculation (Days 1-3)

**Priority:** CRITICAL

1. **Add Missing t8code Tables to TetreeConnectivity.java:**

```java
/** Index to Bey number mapping - from t8code t8_dtet_index_to_bey_number */
public static final byte[][] INDEX_TO_BEY_NUMBER = { { 0, 1, 4, 5, 2, 7, 6, 3 },  // Parent type 0
                                                     { 0, 1, 5, 4, 7, 2, 6, 3 },  // Parent type 1
                                                     { 0, 4, 5, 1, 2, 7, 6, 3 },  // Parent type 2
                                                     { 0, 1, 5, 4, 6, 7, 2, 3 },  // Parent type 3
                                                     { 0, 4, 5, 1, 6, 2, 7, 3 },  // Parent type 4
                                                     { 0, 5, 4, 1, 6, 7, 2, 3 }   // Parent type 5
};

/** Bey ID to vertex mapping - from t8code t8_dtet_beyid_to_vertex */
public static final byte[] BEY_ID_TO_VERTEX = { 0, 1, 2, 3, 1, 1, 2, 2 };
```

2. **Implement computeVertexCoordinates() method in Tet.java:**

```java
/**
 * Compute coordinates of a specific vertex of this tetrahedron.
 * Based on t8code's t8_dtri_compute_coords algorithm.
 */
private Point3i computeVertexCoordinates(int vertex) {
    // Implementation based on t8code tetrahedral vertex calculation
    // Must handle all 6 tetrahedron types and their vertex arrangements
}
```

3. **Rewrite Tet.child() method using t8code algorithm:**

```java
public Tet child(int childIndex) {
    if (l == getMaxRefinementLevel()) {
        throw new IllegalArgumentException("No children at maximum refinement level");
    }

    // Get child type from connectivity table  
    byte childType = TetreeConnectivity.getChildType(type, childIndex);
    byte childLevel = (byte) (l + 1);

    // Get Bey child ID from index mapping
    byte beyId = TetreeConnectivity.INDEX_TO_BEY_NUMBER[type][childIndex];

    if (beyId == 0) {
        // Child 0 inherits parent coordinates
        return new Tet(x, y, z, childLevel, childType);
    } else {
        // Calculate child coordinates using vertex-based approach
        byte vertex = TetreeConnectivity.BEY_ID_TO_VERTEX[beyId];
        Point3i vertexCoords = computeVertexCoordinates(vertex);

        // Child coordinates are midpoint between parent anchor and vertex
        int childX = (x + vertexCoords.x) >> 1;  // Divide by 2 (bit shift)
        int childY = (y + vertexCoords.y) >> 1;
        int childZ = (z + vertexCoords.z) >> 1;

        return new Tet(childX, childY, childZ, childLevel, childType);
    }
}
```

### Phase 2: Fix Parent Calculation (Days 4-5)

**Priority:** CRITICAL

1. **Implement correct parent() method:**

```java
public Tet parent() {
    if (l == 0) {
        throw new IllegalStateException("Root tetrahedron has no parent");
    }
    
    // Use t8code's parent coordinate calculation: parent->x = t->x & ~h;
    int h = Constants.lengthAtLevel(l);
    int parentX = x & ~h;
    int parentY = y & ~h; 
    int parentZ = z & ~h;
    
    byte parentLevel = (byte) (l - 1);
    
    // Determine parent type using connectivity tables and reverse lookup
    byte parentType = computeParentType(parentX, parentY, parentZ, parentLevel);
    
    return new Tet(parentX, parentY, parentZ, parentLevel, parentType);
}
```

2. **Implement computeParentType() helper method** using inverse lookup tables.

### Phase 3: Fix Space-Filling Curve Implementation (Days 6-8)

**Priority:** HIGH

1. **Audit and fix Tet.index() method** to ensure it produces correct SFC indices.
2. **Audit and fix Tet.tetrahedron(long index) method** to ensure correct reconstruction.
3. **Implement proper level inference** from SFC indices.
4. **Add comprehensive SFC validation tests** based on t8code patterns.

### Phase 4: Validate Against t8code Reference (Days 9-10)

**Priority:** HIGH

1. **Create t8code validation harness:**
    - Compare child generation results with t8code for various parent types/levels
    - Validate parent-child relationships
    - Verify family validation logic
    - Test SFC round-trip conversions

2. **Fix any remaining discrepancies** found during validation.

### Phase 5: Update Documentation and Tests (Days 11-12)

**Priority:** MEDIUM

1. **Update TETREE_PARITY_IMPLEMENTATION_PLAN.md** with final status
2. **Add comprehensive test coverage** for all fixed functionality
3. **Document the t8code compliance** in code comments

## Implementation Validation Strategy

### Test-Driven Implementation

1. **Before each change:** Run current failing tests to establish baseline
2. **After each fix:** Verify fix doesn't break existing functionality
3. **Progressive validation:** Fix one issue at a time, test comprehensively

### Validation Test Suite

1. **TetreeValidatorTest** - Family validation, parent-child relationships
2. **SFCRoundTripTest** - Space-filling curve consistency
3. **TetreeTest** - Core tetree operations
4. **Performance tests** - Ensure fixes don't degrade performance

### Reference Compliance Tests

Create tests that validate against known t8code results:

```java

@Test
void testT8codeChildCompliance() {
    // Test specific cases from t8code reference
    Tet parent = new Tet(0, 0, 0, (byte) 1, (byte) 0);
    Tet[] children = new Tet[8];
    for (int i = 0; i < 8; i++) {
        children[i] = parent.child(i);
    }

    // Validate against known t8code results
    assertTrue(TetreeFamily.isFamily(children));
    // Additional specific coordinate/type validations
}
```

## Risk Assessment

### High Risk Items

1. **Breaking existing functionality** - Requires careful regression testing
2. **Performance impact** - New vertex coordinate calculations may be slower
3. **Integration complexity** - Changes affect multiple subsystems

### Mitigation Strategies

1. **Incremental implementation** - Fix one component at a time
2. **Comprehensive testing** - Validate each change thoroughly
3. **Rollback capability** - Keep ability to revert changes if needed

## Success Criteria

### Phase 1 Success (Child Generation)

- [ ] All children have distinct coordinates
- [ ] Child types match t8code expectations
- [ ] TetreeValidatorTest.testFamilyValidation passes
- [ ] No "Children do not form a valid subdivision family" errors

### Phase 2 Success (Parent Calculation)

- [ ] All children report correct parent
- [ ] TetreeValidatorTest.testParentChildValidation passes
- [ ] Parent-child round-trip works correctly

### Phase 3 Success (SFC Implementation)

- [ ] SFCRoundTripTest passes completely
- [ ] Zero round-trip failures
- [ ] Index ↔ Tetrahedron conversion is bijective

### Overall Success

- [ ] All tetree tests pass
- [ ] No regression in existing functionality
- [ ] Performance acceptable (within 20% of current)
- [ ] 100% t8code algorithm compliance

## Timeline

**Total Estimated Duration:** 12 working days

- **Days 1-3:** Fix child coordinate calculation (CRITICAL)
- **Days 4-5:** Fix parent calculation (CRITICAL)
- **Days 6-8:** Fix SFC implementation (HIGH)
- **Days 9-10:** Validate against t8code (HIGH)
- **Days 11-12:** Documentation and final testing (MEDIUM)

## Conclusion

The current Tetree implementation has fundamental algorithmic discrepancies with t8code that require systematic fixes.
The child coordinate calculation is the most critical issue, as it affects all other tetrahedral operations. By
following this implementation plan and using t8code as the definitive reference, we can achieve full parity and reliable
tetrahedral spatial indexing.

**Next Action:** Begin Phase 1 - Fix child coordinate calculation using t8code's vertex-based approach.
