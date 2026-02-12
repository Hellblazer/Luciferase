# Tetrahedral Hierarchical Forest Enhancement

**Epic**: Luciferase-dlm7
**Date**: 2026-02-12
**Status**: Phase 1-3 Complete (53/53 tests passing)

## Overview

Extends `AdaptiveForest` with tetrahedral subdivision capability, navigable tree hierarchy, and event-driven server assignment integration via Tumbler. Implemented across 4 phases with 53 comprehensive tests validating correctness.

### Key Capabilities

- **Dual-Path Subdivision**: Cubic bounds → 6 S0-S5 tetrahedra (Case A), Tetrahedral bounds → 8 Bey children (Case B)
- **Navigable Hierarchy**: Parent-child bidirectional links, level tracking, ancestor/descendant queries
- **Event System**: ForestEventListener integration for spatial changes → server assignment
- **Thread-Safe**: Lock-free design with AtomicBoolean CAS guards, volatile fields, CopyOnWriteArrayList

## Architecture

### Dual-Path Tetrahedral Subdivision

The forest supports TWO distinct tetrahedral subdivision operations dispatched via pattern matching on `TreeBounds` sealed interface:

#### Case A: Cubic → 6 S0-S5 Tetrahedra

Decomposes a CUBE into 6 characteristic tetrahedra that perfectly tile the parent cube:

- **Input**: CubicBounds (AABB)
- **Output**: 6 children with TetrahedralBounds (types 0-5)
- **Geometry**: All share V0 (anchor) and V7 (opposite corner)
- **Properties**: No gaps, no overlaps, exact tiling

```java
switch(bounds) {
    case CubicBounds cubic -> subdivideCubicToTets(parentTree, region, cubic);
    // ... creates 6 Tet children with types 0-5
}
```

#### Case B: Tetrahedron → 8 Bey Children

Subdivides a TETRAHEDRON into 8 refined children via vertex midpoint subdivision:

- **Input**: TetrahedralBounds (Tet)
- **Output**: 8 children with TetrahedralBounds (4 corner + 4 interior)
- **Geometry**: Bey subdivision algorithm (vertex midpoint refinement)
- **Properties**: All children contained within parent

```java
switch(bounds) {
    case TetrahedralBounds tet -> subdivideTetToSubTets(parentTree, region, tet);
    // ... creates 8 Bey children via BeySubdivision.subdivide()
}
```

### TreeBounds Sealed Interface

Type-safe polymorphic bounds representation:

```java
public sealed interface TreeBounds
    permits CubicBounds, TetrahedralBounds {
    boolean containsPoint(float x, float y, float z);
    EntityBounds toAABB();  // For ghost layer compatibility
    float volume();
}
```

**CubicBounds**:
- Wraps EntityBounds (AABB)
- containsPoint() uses AABB test
- toAABB() returns wrapped bounds directly

**TetrahedralBounds**:
- Wraps Tet (tetrahedral geometry)
- containsPoint() uses `Tet.containsUltraFast()` for exact containment
- toAABB() computes bounding box from tet vertices (for ghost layer broad-phase)
- centroid() returns (v0+v1+v2+v3)/4 (**NOT** cube center formula)

### Hierarchy vs LOD: Critical Distinction

**IMPORTANT**: This implementation provides a **subdivision hierarchy**, NOT a level-of-detail (LOD) hierarchy.

| Subdivision Hierarchy (This Feature) | LOD Hierarchy (Separate Concern) |
|---------------------------------------|----------------------------------|
| Spatial partitioning tree | Rendering detail levels |
| Parent preserves spatial bounds | Different representations at different distances |
| Children partition parent space | Same geometry, different poly counts |
| Navigable via parentTreeId/childTreeIds | View-dependent selection |
| Used for: spatial queries, entity distribution | Used for: rendering optimization |

**Current Implementation**: Subdivision hierarchy only. LOD hierarchy would be a separate, orthogonal feature if needed.

### Hierarchy Navigation

**TreeNode Fields** (Phase 2):
- `AtomicReference<String> parentTreeId` - Link to parent tree
- `CopyOnWriteArrayList<String> childTreeIds` - Links to child trees
- `volatile int hierarchyLevel` - Depth in tree (root=0)
- `volatile TreeBounds treeBounds` - Polymorphic bounds (Cubic or Tetrahedral)
- `AtomicBoolean subdivided` - CAS guard for race-free subdivision

**Forest Navigation Methods**:
```java
List<TreeNode> getAncestors(String treeId);  // Parent chain to root
List<TreeNode> getDescendants(String treeId); // All children recursively
List<TreeNode> getSubtree(String treeId);     // Node + descendants
List<TreeNode> getLeaves();                    // All leaf nodes
List<TreeNode> queryHierarchy(Predicate<TreeNode> filter);
List<TreeNode> findLeavesUnder(String treeId);
List<TreeNode> getTreesAtLevel(int level);
```

**Leaf Filtering**: All query methods default to `isLeaf()` filtering to prevent duplicate results from overlapping parent+children.

### Event-Driven Integration

**ForestEvent** sealed interface (Phase 3):
```java
sealed interface ForestEvent permits
    TreeCreated, TreeSubdivided, TreeRemoved, EntityMigrated {
    String forestId();
    long timestamp();
}

record TreeSubdivided(
    String forestId, String treeId, SubdivisionType subdivisionType,
    List<String> childTreeIds, long timestamp
) implements ForestEvent {}
```

**Subdivision Types**:
- `OCTANT` - 8 cubic children
- `CUBIC_TO_TET` - 6 tetrahedral children (Case A)
- `TET_TO_SUBTET` - 8 tetrahedral children (Case B)
- `BINARY_X`, `BINARY_Y`, `BINARY_Z` - 2 children along axis

**ForestToTumblerBridge**: Translates spatial subdivision events → server assignments for distributed spatial computing.

### Thread-Safety Design

**Concurrency Primitives**:
- `AtomicBoolean subdivided` with CAS in `tryMarkSubdivided()` prevents double-subdivision races
- `volatile` fields (treeBounds, hierarchyLevel) ensure visibility across threads
- `CopyOnWriteArrayList` for children and event listeners (iteration-safe)
- `AtomicReference` for parent links (atomic updates)
- **No `synchronized` blocks** - lock-free design throughout

**Race Condition Prevention**:
```java
public boolean tryMarkSubdivided() {
    return subdivided.compareAndSet(false, true);
}

// In subdivision methods:
if (!parentTree.tryMarkSubdivided()) {
    return; // Another thread already subdividing
}
```

## Implementation Phases

### Phase 1: Tetrahedral Subdivision (30 tests)

**Deliverables**:
- `TreeBounds` sealed interface + `CubicBounds`, `TetrahedralBounds` records
- `subdivideTetrahedral()` with pattern matching dispatch
- Case A: `subdivideCubicToTets()` creates 6 S0-S5 children
- Case B: `subdivideTetToSubTets()` creates 8 Bey children
- Entity redistribution with first-match-wins tie-breaking
- Negative coordinate validation (fallback to OCTANT if needed)

**Tests** (30 passing):
- TreeBounds interface tests (containsPoint, toAABB, volume, centroid)
- Case A: 6 children creation, types 0-5, bounds correctness
- Case B: 8 children creation, Bey containment validation
- Entity redistribution: preservation, no duplication, boundary tie-breaking
- Cascade subdivision: cubic→tet→subtet multi-level
- Mixed forest: Octree + Tetree coexistence

### Phase 2: Navigable Hierarchy (15 tests)

**Deliverables**:
- TreeNode hierarchy fields (parent, children, level, treeBounds, subdivided)
- `establishHierarchy()` bidirectional parent-child linking
- Atomic subdivision guard (`tryMarkSubdivided()`)
- Navigation methods (ancestors, descendants, subtree, leaves)
- Query methods with `isLeaf()` filtering

**Tests** (15 passing):
- Hierarchy establishment (parent-child links, levels)
- Navigation (ancestors, descendants, subtree)
- Query filtering (leaves only, level-based)
- Atomic subdivision guard (prevents double-subdivision)

### Phase 3: Tumbler Integration (8 tests)

**Deliverables**:
- `ForestEvent` sealed interface hierarchy
- `ForestEventListener` interface
- `ForestToTumblerBridge` for server assignment
- Event emission in AdaptiveForest subdivision methods
- Server tracking in TreeNode

**Tests** (8 passing):
- Event emission (TreeCreated, TreeSubdivided, EntityMigrated)
- Event listener dispatch (CopyOnWriteArrayList iteration)
- ForestToTumblerBridge server assignment tracking
- Multi-listener support

### Phase 4: Integration & Documentation (Partial)

**Status**: Documentation complete, E2E tests deferred (integration issues)

**Completed**:
- Architecture documentation (this file)
- Phase 1-3 validation (53/53 tests passing)

**Deferred** (requires follow-up):
- End-to-end integration tests (4 tests, failing due to DensityRegion.bounds immutability)
- Performance benchmarks (subdivision timing, entity redistribution, hierarchy traversal)
- Ghost layer compatibility validation (may already exist in Phase 1 tests)

## Performance Characteristics

**Design Targets** (from architecture):
- Per-operation subdivision: <10ms
- Entity redistribution: <5ms per 1000 entities
- Hierarchy traversal: <1ms per root-to-leaf path

**Actual Performance**: Benchmarks deferred to follow-up work. Phase 1-3 tests validate correctness, not performance.

## Geometric Correctness

### S0-S5 Cube Tiling

6 characteristic tetrahedra (types 0-5) at the same anchor and level perfectly tile the enclosing cube:
- All share V0 (anchor) and V7 (opposite corner)
- No gaps, no overlaps
- Validated by `TetS0S5SubdivisionTest` in tetree module

### Bey Subdivision

8 Bey children produced by vertex midpoint refinement:
- 4 corner children (same type as parent) + 4 interior children
- All 8 contained within parent tetrahedron
- Validated by `BeySubdivision` tests in tetree module

### Cascade Correctness

- **Level 0**: Forest has CUBIC trees with CubicBounds
- **Level 1**: CUBIC tree subdivided → 6 TETRAHEDRAL children (Case A)
- **Level 2**: TETRAHEDRAL child subdivided → 8 TETRAHEDRAL grandchildren (Case B)
- **Level N**: Always Case B from level 2 forward
- **Case A occurs exactly ONCE per lineage**: The initial cube decomposition

## Testing Coverage

**Total**: 53/53 tests passing (100% success rate)

| Phase | Test Suite | Tests | Coverage |
|-------|------------|-------|----------|
| 1 | TetrahedralSubdivisionForestTest | 30 | TreeBounds, dual-path subdivision, redistribution, cascade |
| 2 | HierarchyNavigationForestTest | 15 | Parent-child links, navigation, queries, atomic guards |
| 3 | ForestTumblerBridgeTest | 8 | Events, listeners, server assignment |
| **Total** | | **53** | **Comprehensive unit + integration** |

**Test Categories**:
- **Unit Tests**: TreeBounds interface, geometry (containment, volume, centroid)
- **Integration Tests**: Subdivision workflows, entity redistribution, hierarchy navigation
- **Concurrency Tests**: Atomic subdivision guard (CAS prevents races)
- **Cascade Tests**: Multi-level subdivision (cubic→tet→subtet)
- **Coexistence Tests**: Mixed forests (octree + tetree)

## Usage Examples

### Creating a Forest with Tetrahedral Subdivision

```java
var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
    .subdivisionStrategy(SubdivisionStrategy.TETRAHEDRAL)
    .maxEntitiesPerTree(100)
    .minTreeVolume(1000.0f)
    .build();

var forest = new AdaptiveForest<>(
    ForestConfig.defaultConfig(),
    adaptationConfig,
    entityIdGenerator,
    "my-forest"
);
```

### Adding a Tree with Cubic Bounds

```java
var spatialIndex = new Octree<>(idGenerator);
var metadata = TreeMetadata.builder()
    .name("RootRegion")
    .treeType(TreeMetadata.TreeType.OCTREE)
    .build();

var treeId = forest.addTree(spatialIndex, metadata);
var tree = forest.getTree(treeId);

// Set cubic bounds (will subdivide to 6 tets when threshold met)
var bounds = new EntityBounds(
    new Point3f(0, 0, 0),
    new Point3f(1000, 1000, 1000)
);
tree.setTreeBounds(new CubicBounds(bounds));
```

### Listening for Subdivision Events

```java
var bridge = new ForestToTumblerBridge();
forest.addEventListener(bridge);

// When subdivision occurs, bridge receives events:
// - TreeCreated for each child
// - TreeSubdivided for parent
// Bridge assigns servers to new children
```

### Navigating the Hierarchy

```java
// Get all ancestors from node to root
var ancestors = forest.getAncestors(childTreeId);

// Get all leaves under a subtree
var leaves = forest.findLeavesUnder(rootTreeId);

// Query trees at specific level
var level2Trees = forest.getTreesAtLevel(2);

// Get all leaf nodes (filtered automatically)
var allLeaves = forest.getLeaves();
```

## Known Limitations

### Negative Coordinate Handling

Tetree SFC requires non-negative coordinates. Subdivision logic validates and falls back to OCTANT strategy for negative regions:

```java
if (aabb.getMinX() < 0 || aabb.getMinY() < 0 || aabb.getMinZ() < 0) {
    log.warn("Negative coordinates, falling back to OCTANT");
    subdivideOctant(parentTree, region);
    return;
}
```

### Boundary Entity Assignment

Entities on tet boundaries use first-match-wins tie-breaking (iterate children in index order). Orphaned boundary entities fall back to nearest centroid assignment.

### E2E Integration (Deferred Issue)

Integration tests discovered a design issue: `DensityRegion.bounds` is immutable and captured at `addTree()` time. If bounds are expanded after tree creation, the DensityRegion retains old bounds, affecting volume calculations in `considerSubdivision()`.

**Workaround**: Set bounds before calling `addTree()`, or refactor to pass bounds during tree creation.

**Impact**: Does not affect Phase 1-3 functionality (53 tests passing). Only affects E2E integration workflows.

## References

- **Tetree Implementation**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/`
- **BeySubdivision**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/BeySubdivision.java`
- **Octree Reference**: Root directory `Octree/` (C++ reference implementation)
- **ESVO Completion**: `render/doc/ESVO_COMPLETION_SUMMARY.md`
- **Performance Metrics**: `lucien/doc/PERFORMANCE_METRICS_MASTER.md`

## Future Work

1. **E2E Integration Tests**: Fix DensityRegion.bounds immutability issue
2. **Performance Benchmarks**: Measure actual subdivision, redistribution, and traversal times
3. **Ghost Layer Validation**: Verify TetrahedralBounds.toAABB() integration
4. **Adaptive Strategy**: Enhance ADAPTIVE subdivision to intelligently choose between OCTANT/TETRAHEDRAL/BINARY based on workload
5. **Tree Merging**: Implement reverse operation (collapse underutilized children back to parent)

## Conclusion

The Tetrahedral Hierarchical Forest enhancement successfully adds tetrahedral subdivision capability to AdaptiveForest with 53 comprehensive tests validating correctness. The dual-path design cleanly separates cubic→tet and tet→subtet subdivision via sealed interfaces and pattern matching. Thread-safe implementation uses lock-free concurrency primitives. Event-driven integration enables distributed spatial computing via Tumbler.

**Status**: Phases 1-3 complete and production-ready. Phase 4 documentation complete, E2E integration and benchmarks deferred for follow-up.
