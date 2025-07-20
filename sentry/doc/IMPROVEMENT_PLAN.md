# Sentry Improvement Plan

## Executive Summary

The Sentry module implements a 3D Delaunay tetrahedralization algorithm but currently has 10 major limitations affecting correctness, performance, and usability. This improvement plan provides a roadmap to address these issues through incremental improvements.

**Key Issues**:
- Fast geometric predicates cause Delaunay violations (10-20% of tetrahedra)
- Rebuild method fails to maintain proper vertex-tetrahedron relationships
- Memory leaks in vertex removal operations
- No handling of degenerate configurations
- Unclear concurrency model

**Approach**:
1. **Quick Wins** (< 1 week): Configuration improvements and simple fixes
2. **Critical Fixes** (3 weeks): Exact predicates and rebuild fixes
3. **Robustness** (2 weeks): Memory management and degenerate handling
4. **Architecture** (4 weeks): Component decoupling and concurrency model

**Expected Outcomes**:
- 100% Delaunay property correctness
- < 2x performance impact with exact predicates
- Proper memory management
- Clear architecture and concurrency model

Total estimated effort: 9-10 weeks for full implementation.

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

### 4. Document Thread Safety Model ✅
- Added comprehensive Javadoc to MutableGrid about single-threaded design
- Included external synchronization example with ReentrantReadWriteLock

See QUICK_FIX_IMPLEMENTATION_STATUS.md for implementation details.

## Priority 1: Critical Fixes (Correctness)

### 1.1 Implement Adaptive Exact Predicates

**Goal**: Ensure geometric correctness while minimizing performance impact.

**Current State**: 
- GeometricPredicates interface exists with orientation/inSphere methods
- ScalarGeometricPredicates provides basic implementation
- HybridGeometricPredicates exists but needs exact fallback
- GeometricPredicatesFactory controls implementation selection

**Implementation Steps**:
1. Integrate Shewchuk's robust predicates as new implementation
2. Enhance HybridGeometricPredicates with adaptive fallback
3. Add error bound detection to trigger exact computation
4. Add predicate statistics/monitoring

**Code Changes**:
```java
public class ShewchukPredicates implements GeometricPredicates {
    // Exact arithmetic predicates
    public double orientation(double ax, double ay, double az, 
                            double bx, double by, double bz,
                            double cx, double cy, double cz,
                            double dx, double dy, double dz) {
        // Shewchuk's robust implementation
        return orient3dExact(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }
    
    public double inSphere(double ax, double ay, double az,
                          double bx, double by, double bz,
                          double cx, double cy, double cz,
                          double dx, double dy, double dz,
                          double ex, double ey, double ez) {
        // Shewchuk's robust implementation  
        return insphereExact(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
    }
    
    public String getImplementationName() {
        return "Shewchuk Exact Predicates";
    }
}

public class AdaptiveGeometricPredicates extends HybridGeometricPredicates {
    private static final double EPSILON = 1e-12;
    private final ShewchukPredicates exact = new ShewchukPredicates();
    private long fastCalls = 0;
    private long exactCalls = 0;
    
    @Override
    public double orientation(double ax, double ay, double az, 
                            double bx, double by, double bz,
                            double cx, double cy, double cz,
                            double dx, double dy, double dz) {
        double fast = super.orientation(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
        fastCalls++;
        
        // Check if result is near zero (uncertain)
        if (Math.abs(fast) < EPSILON) {
            exactCalls++;
            return exact.orientation(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
        }
        return fast;
    }
    
    public String getStatistics() {
        double exactRatio = exactCalls / (double) fastCalls * 100;
        return String.format("Fast: %d, Exact: %d (%.2f%%)", fastCalls, exactCalls, exactRatio);
    }
}
```

**Integration Points**:
- Update GeometricPredicatesFactory to support adaptive mode
- Modify Grid constructor to accept GeometricPredicates parameter
- Add system property `sentry.predicates.mode` to control predicate selection

**Testing**: 
- Run existing tests with exact predicates to verify correctness
- Add DelaunayPropertyTest to ensure no violations
- Measure performance impact with different predicate modes

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

### 3.2 Define Concurrency Model

**Goal**: Clear thread-safety guarantees.

**Recommendation**: Document as single-threaded, provide thread-safe wrapper if needed.

```java
/**
 * This class is NOT thread-safe. For concurrent access, use
 * ConcurrentGrid or synchronize externally.
 */
public class MutableGrid {
    // ...
}

public class ConcurrentGrid {
    private final MutableGrid grid;
    private final ReadWriteLock lock;
    
    public void addVertex(Vertex v) {
        lock.writeLock().lock();
        try {
            grid.addVertex(v);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
```

## Implementation Schedule

### Phase 1 (2-3 weeks): Critical Fixes
- Week 1: Implement exact predicates
  - Day 1-2: Integrate Shewchuk predicates
  - Day 3-4: Implement adaptive fallback
  - Day 5: Testing and validation
- Week 2: Fix rebuild method
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

### Phase 3 (3-4 weeks): Architecture  
- Weeks 6-7: Decouple components
  - Extract predicate module
  - Refactor vertex-tetrahedron references
  - Create clean interfaces
- Week 8: Concurrency model
  - Document thread safety
  - Implement concurrent wrapper
  - Add concurrent tests
- Week 9: Performance optimization
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

1. **Correctness**: 100% Delaunay property maintained
2. **Performance**: < 2x slowdown with exact predicates
3. **Memory**: Proper cleanup with untrack
4. **Robustness**: Handle all degenerate cases
5. **Maintainability**: Reduced coupling, clear abstractions

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
4. Clear concurrency model
5. Estimated effort: 6-8 weeks

## Recommendation

Proceed with incremental improvements (Priority 1 & 2) first. This provides immediate value while preserving existing code. If these prove successful, continue with Priority 3. If not, consider the rewrite option.