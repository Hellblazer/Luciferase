# Sentry Improvement Plan

## Executive Summary

The Sentry module implements a 3D Delaunay tetrahedralization algorithm but currently has 10 major limitations affecting correctness, performance, and usability. This improvement plan provides a roadmap to address these issues through incremental improvements.

**Current Build Status (2025-01-20)**: ✅ ALL TESTS PASSING (64/64)
- Quick Wins: 100% Complete
- Exact Predicates: Implemented and working
- Delaunay violations: Fixed (was 10-20%, now 0%)
- Rebuild method: Still needs fixing (only ~10% vertex preservation)
- Memory management: Partially addressed

**Remaining Key Issues**:
- Rebuild method fails to maintain proper vertex-tetrahedron relationships
- Memory leaks in vertex removal operations (partially fixed)
- Limited handling of degenerate configurations
- No comprehensive validation framework

**Approach**:
1. **Quick Wins** ✅ COMPLETED (2025-01-19): Configuration improvements and simple fixes
2. **Critical Fixes** (IN PROGRESS): Exact predicates ✅, rebuild fixes pending
3. **Robustness** (2 weeks): Memory management and degenerate handling
4. **Architecture** (3 weeks): Component decoupling and validation framework

**Completed Outcomes**:
- ✅ 100% Delaunay property correctness with exact predicates
- ✅ Adaptive predicates with < 2% performance impact
- ✅ Basic degenerate detection implemented
- ✅ Thread safety documentation added

**Expected Remaining Outcomes**:
- 100% vertex preservation in rebuild operations
- Complete memory leak fixes
- Comprehensive validation framework
- Improved architecture with decoupled components

Total estimated effort: 4-5 weeks remaining (was 7-8 weeks total).

## Immediate Quick Wins (< 1 week) ✅ COMPLETED 2025-01-19

These improvements have been implemented:

### 1. Add Predicate Mode Configuration ✅
- Added system property `sentry.predicates.mode` to GeometricPredicatesFactory
- Added PredicateMode enum with SCALAR, SIMD, HYBRID, ADAPTIVE options
- Maintained backward compatibility with legacy property

### 2. Fix Size Tracking in Untrack ✅
- Fixed: decremented size counter in untrack() method
- Fixed: Vertex.detach() infinite loop bug
- Added: proper head/tail handling in untrack()

### 3. Add Degenerate Detection Flag ✅
- Added `isDegenerate` and `isNearDegenerate` flags to Tetrahedron
- Added volume thresholds (1e-10f and 1e-6f)
- Added updateDegeneracy() method with null checks

### 4. Document Single-Threaded Design ✅
- Added comprehensive Javadoc to MutableGrid about single-threaded design
- Clarified that external synchronization is caller's responsibility if needed

See QUICK_FIX_IMPLEMENTATION_STATUS.md for implementation details.

## Priority 1: Critical Fixes (Correctness)

### 1.1 Implement Adaptive Exact Predicates ✅ COMPLETED 2025-01-20

**Goal**: Ensure geometric correctness while minimizing performance impact.

**Completed Implementation**: 
- ✅ ExactGeometricPredicates using Geometry class for exact arithmetic
- ✅ AdaptiveGeometricPredicates with intelligent fallback to exact computation
- ✅ GeometricPredicatesFactory updated with EXACT and ADAPTIVE modes
- ✅ Performance impact < 2% with adaptive mode
- ✅ DelaunayPropertyTest passing with 0% violations

**Key Achievements**:
1. Integrated exact arithmetic using existing Geometry class
2. Adaptive fallback triggers only when needed (~1.8% of operations)  
3. Full backward compatibility maintained
4. Comprehensive testing with challenging point sets

See EXACT_PREDICATES_IMPLEMENTATION.md for implementation details.

### 1.2 Fix Rebuild Method

**Goal**: Ensure vertex-tetrahedron relationships are properly maintained.

**Current Issues**:
- Vertex.adjacent references become stale after rebuild
- Not all vertices get proper tetrahedron references
- No validation of bidirectional consistency

**Implementation Steps**:
1. Track all vertex-tetrahedron relationships during rebuild
2. Update vertex.adjacent references after creating new tetrahedra  
3. Validate bidirectional consistency
4. Clear old tetrahedron pool entries

**Code Changes**:
```java
public void rebuild(Random entropy) {
    // Save current vertices
    List<Vertex> vertexList = new ArrayList<>();
    for (var v : head) {
        vertexList.add(v);
    }
    
    // Clear existing structure
    clear();
    
    // Rebuild from scratch
    last = TetrahedronPool.getInstance().acquire(fourCorners);
    
    // Re-insert all vertices
    for (Vertex v : vertexList) {
        var containedIn = locate(v, last, entropy);
        if (containedIn != null) {
            insert(v, containedIn);
            // Ensure vertex has valid adjacent reference
            if (v.adjacent == null || !isValid(v.adjacent)) {
                v.adjacent = findValidTetrahedronContaining(v);
            }
        }
    }
    
    // Validate all vertex references
    validateVertexReferences();
}

private void validateVertexReferences() {
    for (var v : head) {
        if (v.adjacent == null) {
            // Find any tetrahedron containing this vertex
            v.adjacent = findTetrahedronContaining(v);
        }
    }
}
```

## Priority 2: Performance & Memory

### 2.1 Fix Untrack Memory Leak

**Goal**: Properly remove vertices and free memory.

**Current Issues**:
- Vertices remain in linked list after untrack
- Size counter not decremented
- No cleanup of tetrahedron references
- LandmarkIndex not updated

**Implementation Steps**:
1. Remove vertex from linked list properly
2. Update size tracking
3. Clean up tetrahedron references
4. Update spatial indices

**Code Changes**:
```java
public void untrack(Vertex v) {
    if (v == null || !vertices.contains(v)) {
        return;
    }
    
    // Remove from linked list
    if (v.previous != null) {
        v.previous.next = v.next;
    } else {
        head = v.next;
    }
    
    if (v.next != null) {
        v.next.previous = v.previous;
    } else {
        tail = v.previous;
    }
    
    // Clear vertex references
    v.next = null;
    v.previous = null;
    v.adjacent = null;
    
    // Update tracking
    vertices.remove(v);
    size--;
    
    // Update landmark index if present
    if (landmarkIndex != null) {
        landmarkIndex.removeVertex(v);
    }
    
    // Note: Full rebuild may be needed to maintain Delaunay property
}
```

### 2.2 Degenerate Tetrahedra Handling

**Goal**: Prevent or handle degenerate configurations gracefully.

**Current Issues**:
- No detection of near-zero volume tetrahedra
- Degenerate tetrahedra can cause numerical instability
- Flip operations may fail on degenerate configurations

**Options**:
1. **Prevention**: Use symbolic perturbation (SoS)
2. **Detection**: Flag and handle specially
3. **Removal**: Post-process to eliminate

**Recommended**: Multi-strategy approach
```java
public class Tetrahedron {
    private static final double DEGENERATE_THRESHOLD = 1e-10;
    private static final double NEAR_DEGENERATE_THRESHOLD = 1e-6;
    private boolean isDegenerate = false;
    private boolean isNearDegenerate = false;
    
    public void updateDegeneracy() {
        double vol = Math.abs(volume());
        isDegenerate = vol < DEGENERATE_THRESHOLD;
        isNearDegenerate = vol < NEAR_DEGENERATE_THRESHOLD;
        
        if (isDegenerate && log.isWarnEnabled()) {
            log.warn("Degenerate tetrahedron detected: volume={}", vol);
        }
    }
    
    public boolean shouldFlip() {
        if (isDegenerate) return false; // Never flip degenerate
        if (isNearDegenerate && !wouldImproveQuality()) {
            return false; // Only flip if it improves quality
        }
        // ... normal flip logic
    }
    
    private boolean wouldImproveQuality() {
        // Check if flip would create better-shaped tetrahedra
        // Compare aspect ratios, minimum angles, etc.
        return true; // placeholder
    }
}
```

**Additional Strategies**:
- Add quality metrics (aspect ratio, radius ratio)
- Implement vertex smoothing for near-degenerate cases
- Consider adaptive precision for borderline cases

## Priority 3: Architecture Improvements

### 3.1 Decouple Components

**Goal**: Reduce coupling for easier maintenance and testing.

**Current Issues**:
- Tight coupling between Vertex, Tetrahedron, and Grid
- Direct object references make serialization difficult
- Hard to test components in isolation

**Proposed Changes**:

1. **Extract Geometric Predicates Module**
   ```java
   // New module: sentry-predicates
   package com.hellblazer.sentry.predicates;
   
   public interface PredicateEngine {
       double orientation3D(...);
       double inSphere(...);
       PredicateStats getStatistics();
   }
   ```

2. **Replace Direct References with Indices**
   ```java
   public class IndexedTetrahedron {
       private int[] vertexIndices = new int[4];  // Instead of Vertex[]
       private int[] neighborIndices = new int[4]; // Instead of Tetrahedron[]
   }
   ```

3. **Separate Topology from Geometry**
   ```java
   public interface TopologyManager {
       void addVertex(int vertexId);
       void removeVertex(int vertexId);
       int[] getNeighbors(int tetrahedronId);
   }
   
   public interface GeometryManager {
       Point3f getVertexPosition(int vertexId);
       void updateVertexPosition(int vertexId, Point3f position);
   }
   ```

### 3.2 Add Validation Framework

**Goal**: Ensure invariants are maintained throughout operations.

**Recommendation**: Add comprehensive validation methods that can be enabled during testing/debugging.

```java
public class MutableGrid {
    private boolean validationEnabled = false;
    
    public void enableValidation(boolean enable) {
        this.validationEnabled = enable;
    }
    
    private void validateInvariants() {
        if (!validationEnabled) return;
        
        // Check Delaunay property
        for (Tetrahedron t : tetrahedra()) {
            assert satisfiesDelaunayProperty(t) : "Delaunay violation detected";
        }
        
        // Check vertex-tetrahedron bidirectional consistency
        for (Vertex v : vertices) {
            assert v.adjacent != null : "Vertex missing adjacent tetrahedron";
            assert v.adjacent.contains(v) : "Inconsistent vertex-tetrahedron reference";
        }
        
        // Check tetrahedron neighbor consistency
        for (Tetrahedron t : tetrahedra()) {
            for (int i = 0; i < 4; i++) {
                Tetrahedron neighbor = t.neighbor(i);
                if (neighbor != null) {
                    assert neighbor.neighborIndex(t) >= 0 : "Inconsistent neighbor relationship";
                }
            }
        }
    }
}
```

## Implementation Schedule

### Phase 1 (2-3 weeks): Critical Fixes
- Week 1: ✅ COMPLETED - Implement exact predicates (2025-01-20)
  - ✅ Integrated exact arithmetic using Geometry class
  - ✅ Implemented adaptive fallback with < 2% overhead
  - ✅ Testing and validation complete
- Week 2: Fix rebuild method (Starting 2025-01-27)
  - Day 1-2: Fix vertex reference updates
  - Day 3-4: Add validation logic
  - Day 5: Testing with various datasets
- Week 3: Fix memory management
  - Day 1-2: Implement proper untrack
  - Day 3-4: Add landmark index cleanup
  - Day 5: Memory leak testing

### Phase 2 (2 weeks): Robustness
- Week 4: Degenerate handling
  - Day 1-2: Add degenerate detection
  - Day 3-4: Implement handling strategies
  - Day 5: Test edge cases
- Week 5: Comprehensive testing
  - Day 1-2: Stress testing suite
  - Day 3-4: Performance benchmarks
  - Day 5: Documentation updates

### Phase 3 (2-3 weeks): Architecture  
- Week 6: Decouple components
  - Extract predicate module
  - Refactor vertex-tetrahedron references
  - Create clean interfaces
- Week 7: Validation framework
  - Implement comprehensive invariant checks
  - Add debug/test mode validation
  - Create validation test suite
- Week 8: Performance optimization
  - Profile with exact predicates
  - Optimize hot paths
  - Final benchmarks

## Testing Strategy

### 1. Regression Testing
- Run all existing tests with each change
- Add tests for each fixed limitation
- Ensure no new failures introduced

### 2. Specific Test Cases

**Exact Predicates Testing**:
```java
@Test
public void testExactPredicatesDelaunayProperty() {
    MutableGrid grid = new MutableGrid();
    grid.setPredicates(new ShewchukPredicates());
    
    // Add challenging point set
    addNearlyCoplanarPoints(grid);
    
    // Verify Delaunay property
    for (Tetrahedron tet : grid.tetrahedra()) {
        assertTrue("Delaunay violation", satisfiesDelaunayProperty(tet));
    }
}
```

**Rebuild Testing**:
```java
@Test 
public void testRebuildMaintainsReferences() {
    // Create grid with vertices
    // Rebuild
    // Verify all vertices have valid adjacent references
    // Verify all tetrahedra contain their vertices
}
```

**Memory Leak Testing**:
```java
@Test
public void testUntrackFreesMemory() {
    long initialMemory = getUsedMemory();
    // Add many vertices
    // Untrack them all
    System.gc();
    long finalMemory = getUsedMemory();
    assertTrue("Memory not freed", finalMemory <= initialMemory * 1.1);
}
```

### 3. Stress Testing
- Large point sets (1M+ points)
- Degenerate configurations:
  - Nearly coplanar points
  - Points on a sphere
  - Grid-aligned points
- Random point distributions with various densities

### 4. Comparison Testing  
- Compare against reference implementation (CGAL)
- Validate geometric properties:
  - Delaunay criterion
  - Convex hull correctness
  - Voronoi dual consistency
- Benchmark performance across predicate implementations

## Success Metrics

1. **Correctness**: ✅ 100% Delaunay property maintained (achieved with exact predicates)
2. **Performance**: ✅ < 2% slowdown with adaptive predicates (target was < 2x)
3. **Memory**: ⚠️ Partial - untrack fixed but rebuild still leaks
4. **Robustness**: ⚠️ Partial - degenerate detection added, handling incomplete
5. **Maintainability**: ❌ Not started - components still tightly coupled

## Risk Mitigation

1. **Performance Regression**: Profile continuously, maintain fast path
2. **Breaking Changes**: Version API changes, provide migration guide
3. **Complexity**: Incremental changes, comprehensive tests
4. **Compatibility**: Maintain backward compatibility where possible

## Alternative: Complete Rewrite

If incremental fixes prove too difficult, consider:
1. New architecture with lessons learned
2. Start with exact predicates from day 1
3. Design for vertex removal/updates
4. Built-in validation framework
5. Estimated effort: 5-6 weeks

## Recommendation

Proceed with incremental improvements (Priority 1 & 2) first. This provides immediate value while preserving existing code. If these prove successful, continue with Priority 3. If not, consider the rewrite option.
