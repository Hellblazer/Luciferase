# Tetree Coordinate Fix Implementation Plan

## Executive Summary

The Tetree implementation has a critical bug: the `Tet.coordinates()` method does not produce the standard S0-S5 tetrahedral decomposition of a cube. This causes only ~35% of points to be contained by their "enclosing" tetrahedra, when it should be 100%.

## Problem Statement

### Current Situation
- The 6 tetrahedra (types 0-5) do NOT properly tile a cube
- Points have gaps (not contained by any tetrahedron) and overlaps (contained by multiple)
- Visualization shows entities floating outside their assigned tetrahedra
- `locate()` returns tetrahedra that don't contain the requested points

### Root Cause
The `Tet.coordinates()` method produces incorrect vertices. For example:
- **Expected Type 0 (S0)**: vertices (0,0,0), (h,0,0), (h,h,0), (h,h,h)
- **Actual Type 0**: vertices (0,0,0), (h,0,0), (h,0,h), (0,h,h)

### Impact
- Spatial queries fail (65% of the time)
- Visualization is incorrect
- Any algorithm depending on containment is broken

## Implementation Plan

### Phase 1: Fix Tetrahedral Coordinates ✅ CURRENT PHASE

#### 1.1 Document Current State (COMPLETED ✅)
- [x] Created `CubeTilingVerificationTest.java` - demonstrates the problem
- [x] Created `TetVertexAnalysisTest.java` - shows expected vs actual vertices
- [x] Created `TETREE_SFC_CONTAINMENT_FIX.md` - comprehensive analysis
- [x] Identified that subdivision coordinates are closer to correct

#### 1.2 Implement Correct S0-S5 Decomposition (IN PROGRESS 🔄)
- [ ] Create backup of current `Tet.coordinates()` method as `coordinatesLegacy()`
- [ ] Implement correct S0-S5 vertex calculation in `coordinates()`
- [ ] Add unit test `TetS0S5DecompositionTest.java` to verify:
  - All 6 tetrahedra have correct vertices
  - No gaps: every point in cube is in exactly one tetrahedron
  - No overlaps: no point is in multiple tetrahedra
  - Volume preservation: sum of 6 tetrahedra volumes = cube volume

#### 1.3 Verify Child-Parent Relationships (TODO 📋)
- [ ] Test that `Tet.parent()` still works correctly
- [ ] Test that `Tet.child(i)` produces valid children
- [ ] Verify refinement maintains containment: if point in parent, it's in exactly one child
- [ ] Check tm-index encoding/decoding still works

#### 1.4 Update Dependent Algorithms (TODO 📋)
- [ ] Review and update `Tetree.locate()` if needed
- [ ] Check `TetreeNeighborFinder` for vertex dependencies
- [ ] Verify `TetreeSFCRayTraversal` still works
- [ ] Update any other code that assumes old vertex positions

### Phase 2: Verification and Testing

#### 2.1 Comprehensive Testing (TODO 📋)
- [ ] Run all existing Tetree tests
- [ ] Create `TetreeContainmentStressTest.java`:
  - Test 10,000 random points
  - Verify 100% are contained by their located tetrahedron
  - Test at multiple levels (0-10)
- [ ] Update `SubdivisionContainmentTest.java` to show improvement

#### 2.2 Performance Verification (TODO 📋)
- [ ] Run benchmarks to ensure no performance regression
- [ ] Compare containment check speed
- [ ] Verify memory usage unchanged

#### 2.3 Visualization Testing (TODO 📋)
- [ ] Run `TetreeVisualizationDemo`
- [ ] Verify all entities appear inside their tetrahedra
- [ ] Remove subdivision coordinate workaround from visualization
- [ ] Update visualization documentation

### Phase 3: Cleanup and Documentation

#### 3.1 Code Cleanup (TODO 📋)
- [ ] Remove `subdivisionCoordinates()` if no longer needed
- [ ] Remove subdivision-specific visualization methods
- [ ] Clean up any workarounds for the coordinate bug
- [ ] Update inline documentation

#### 3.2 Documentation Updates (TODO 📋)
- [ ] Update LUCIEN_ARCHITECTURE_2025.md
- [ ] Update CLAUDE.md with fixed understanding
- [ ] Create migration guide if API changes
- [ ] Document the S0-S5 decomposition clearly

#### 3.3 Final Validation (TODO 📋)
- [ ] Full test suite passes
- [ ] Benchmarks show acceptable performance
- [ ] Code review completed
- [ ] Documentation reviewed

## Progress Tracking

### Current Status: Phase 1.2 - Implementing Correct Coordinates

**Last Action**: Identified the bug and documented it in TETREE_SFC_CONTAINMENT_FIX.md

**Next Action**: Create backup of current coordinates() method and implement S0-S5

### Key Files Modified
1. `/lucien/doc/TETREE_SFC_CONTAINMENT_FIX.md` - Problem analysis
2. `/lucien/src/test/java/com/hellblazer/luciferase/lucien/tetree/CubeTilingVerificationTest.java` - Bug demonstration
3. `/lucien/src/test/java/com/hellblazer/luciferase/lucien/tetree/TetVertexAnalysisTest.java` - Vertex analysis
4. `/portal/src/main/java/com/hellblazer/luciferase/portal/mesh/explorer/TetreeVisualization.java` - Temp fix using subdivision coords

### Key Insights Discovered
1. The 6 tetrahedra SHOULD perfectly tile a cube (no gaps/overlaps)
2. Current implementation produces wrong vertices
3. Subdivision coordinates are closer to correct (that's why they work better)
4. This is a BUG, not a fundamental algorithm limitation

### Test Results to Remember
- Current containment rate: 35%
- With subdivision coordinates: 51% 
- Expected after fix: 100%

## Risk Mitigation

### Potential Risks
1. **Breaking existing code**: Other algorithms may depend on current (wrong) vertices
   - Mitigation: Comprehensive testing, keep legacy method available
   
2. **Performance impact**: New coordinates might be slower to compute
   - Mitigation: Benchmark before/after, optimize if needed
   
3. **tm-index compatibility**: Parent/child relationships might break
   - Mitigation: Extensive parent/child testing

### Rollback Plan
1. Keep `coordinatesLegacy()` method
2. Git commit before major changes
3. Feature flag to switch between implementations if needed

## Success Criteria

1. **100% Containment**: Every point is in exactly one tetrahedron
2. **No Visual Artifacts**: Entities appear inside their tetrahedra
3. **Test Suite Passes**: All existing tests still work
4. **Performance Maintained**: No significant slowdown
5. **Clean Implementation**: No special cases or workarounds needed

## Notes for Future Sessions

When returning to this work:
1. Check "Current Status" section above
2. Look at "Next Action" 
3. Review "Key Insights" to remember context
4. Run `CubeTilingVerificationTest` to see the problem
5. The fix goes in `Tet.coordinates()` method

## References

- Original t8code paper: https://arxiv.org/abs/1509.04627
- Standard cube decomposition: Bey's red refinement
- S0-S5 tetrahedra share vertices v0=(0,0,0) and v7=(h,h,h)