# Deprecated Method Removal Plan

## Overview
This document outlines the plan for removing deprecated methods from the Luciferase codebase, specifically focusing on methods that use incorrect index types or have been superseded by better implementations.

## Deprecated Methods Identified

### 1. `Tet.tetrahedron(long index)`
- **Location**: `/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java`
- **Marked**: `@Deprecated(since = "0.0.1", forRemoval = true)`
- **Issue**: Attempts to derive level from index, which is fundamentally flawed
- **Replacement**: `Tet.tetrahedron(long index, byte level)` or `Tet.tetrahedron(TetreeKey key)`

### 2. `Tet.spatialRangeQuery(VolumeBounds bounds, boolean includeIntersecting)`
- **Location**: `/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java`
- **Marked**: `@Deprecated` (added recently)
- **Issue**: Uses `consecutiveIndex()` which is only unique within a level, not globally unique
- **Replacement**: `Tet.spatialRangeQueryKeys(VolumeBounds bounds, boolean includeIntersecting)`

## Current Usage Analysis

### Usage of `tetrahedron(long index)`:
1. **Internal to Tet.java**:
   - Line 1136: Inside commented-out code
   - Line 2122: Inside `spatialRangeQuery` method (which is also deprecated)

2. **External files**:
   - `Simplex.java`: Constructor uses it
   - `TetreeSFCRayTraversal.java`: Used in ray traversal logic
   - `TetreeValidator.java`: Used in multiple validation methods

### Usage of `spatialRangeQuery`:
1. **AbstractSpatialIndex.java**: The base class has its own `spatialRangeQuery` method that is NOT deprecated
2. **Tet.java**: Only used internally within deprecated code

## Removal Plan

### Phase 1: Update Direct Callers (Immediate)
1. **Simplex.java**:
   - Current: `var tet = Tet.tetrahedron(index);`
   - Change to: Store level information in Simplex or use TetreeKey instead of long index

2. **TetreeSFCRayTraversal.java**:
   - Current: `Tet tet = Tet.tetrahedron(index);`
   - Change to: Pass level information through the traversal or use TetreeKey

3. **TetreeValidator.java**:
   - Current: Multiple uses of `Tet.tetrahedron(index)`
   - Change to: Validator should work with TetreeKey objects instead of raw longs

### Phase 2: Refactor Supporting Infrastructure (1-2 weeks)
1. **SFCRange Record**:
   - Current: Uses `long start, long end`
   - Change to: Create `TetreeKeyRange` that uses TetreeKey objects
   - Update all methods that use SFCRange

2. **Spatial Range Query System**:
   - Complete the migration from `long` indices to `TetreeKey` objects
   - Update all the helper methods in Tet.java that support spatial range queries

### Phase 3: Remove Deprecated Methods (2-3 weeks)
1. Remove `Tet.tetrahedron(long index)` method
2. Remove `Tet.spatialRangeQuery` and all its supporting infrastructure
3. Clean up commented-out code that references deprecated methods

## Technical Considerations

### 1. Simplex Class Refactoring
The Simplex class currently stores a `long index`. Options:
- Option A: Change to store `TetreeKey` instead
- Option B: Add level information to Simplex
- **Recommendation**: Option A - Use TetreeKey for consistency

### 2. Ray Traversal Updates
TetreeSFCRayTraversal needs to maintain level information during traversal:
- Current: Works with consecutive indices
- Proposed: Work with TetreeKey objects throughout
- This ensures global uniqueness and proper level tracking

### 3. Validator Modifications
TetreeValidator should be updated to:
- Accept NavigableSet<TetreeKey> instead of NavigableSet<Long>
- Use TetreeKey throughout validation logic
- This aligns with the rest of the codebase using TetreeKey

## Migration Strategy

### Step 1: Add New Methods (Week 1)
- Create TetreeKey-based versions of affected methods
- Add appropriate unit tests
- Mark old methods with stronger deprecation warnings

### Step 2: Update Callers (Week 1-2)
- Update each caller to use new methods
- Run full test suite after each update
- Document any behavior changes

### Step 3: Validation Period (Week 2)
- Run performance tests to ensure no regression
- Verify spatial query accuracy
- Check memory usage patterns

### Step 4: Removal (Week 3)
- Remove deprecated methods
- Clean up any dead code
- Update documentation

## Risk Assessment

### Low Risk:
- Removing commented-out code
- Updating internal Tet.java usage

### Medium Risk:
- Updating TetreeValidator - extensive test coverage exists
- Updating Simplex - may affect other modules

### High Risk:
- TetreeSFCRayTraversal changes - complex algorithm that needs careful testing
- Spatial range query system - performance critical

## Testing Requirements

1. **Unit Tests**:
   - All existing tests must pass
   - Add tests for new TetreeKey-based methods
   - Add migration tests that verify old and new methods produce equivalent results

2. **Integration Tests**:
   - Test ray traversal accuracy
   - Test spatial range query performance
   - Test validator correctness

3. **Performance Tests**:
   - Ensure no performance regression
   - Compare memory usage before and after

## Success Criteria

1. All deprecated methods removed
2. All tests passing
3. No performance regression (within 5% of current performance)
4. Code coverage maintained or improved
5. Documentation updated to reflect changes

## Timeline Estimate

- Week 1: Implement new methods and update simple callers
- Week 2: Update complex callers and validate
- Week 3: Remove deprecated code and finalize

Total estimated time: 3 weeks

## Next Steps

1. Review and approve this plan
2. Create tracking issues for each component
3. Begin Phase 1 implementation
4. Regular progress reviews

---

**Note**: This plan prioritizes correctness over speed. The use of TetreeKey throughout ensures global uniqueness and proper spatial indexing, which is critical for the accuracy of the spatial data structure.