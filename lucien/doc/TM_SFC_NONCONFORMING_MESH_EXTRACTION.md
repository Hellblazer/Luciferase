# TM-SFC Non-Conforming Meshes: Complete Knowledge Extraction

**Paper**: "A tetrahedral space-filling curve for non-conforming adaptive meshes"
**Citation**: Implemented in t8code and Luciferase Tetree module
**Relevance**: High - Direct implementation in Tetrahedral Space-Filling Curve (Tet SFC)

---

## 1. HANGING NODES AND FACES HANDLING

### 1.1 What Are Hanging Nodes?

A **hanging node** occurs in non-conforming adaptive meshes when a parent element is subdivided while a neighboring element remains unrefined. This creates geometric discontinuities at element boundaries.

**Example:**

```

Conforming mesh:        Non-conforming (hanging node):
+---+---+              +-------+
|   |   |              |       | <- Refined neighbor
+---+---+              +---+---+
|   |   |              |   | H | <- Hanging node H
+---+---+              +---+---+

```

### 1.2 Luciferase Implementation

**Tet SFC Approach (Bey Refinement):**

```java

// In Tetree/Tet.java:
// Bey refinement creates 8 children per tetrahedron using vertex midpoints
// This naturally handles hanging nodes through topology-aware child generation

public Tet child(int childIndex) {
    // Child generation uses vertex-based refinement (Bey's algorithm)
    // not cube-offset refinement. This preserves topological continuity.

    byte beyChildId = TetreeConnectivity.getBeyChildId(type, childIndex);

    // Children are created at:
    // - Corner vertices (types T0-T3): 4 children
    // - Interior octahedron (types T4-T7): 4 children
    // Total: 8 children perfectly tiling parent volume
}

```

**Hanging Face Handling:**
- **S0-S5 Subdivision**: The 6 tetrahedral types tile each cubic cell
- **Face-Adjacent Neighbors**: Connected through shared faces in the partition
- **Ghost Layers**: Non-local elements maintain neighbor info without explicit communication
  - `ElementGhostManager`: Detects boundary elements
  - `GhostLayer`: Stores ghost elements indexed by spatial key
  - `NeighborDetector`: Finds face/edge/vertex neighbors

### 1.3 Non-Conforming Mesh Algorithm (TM-SFC)

**Key Principle**: The TM (tree-monotonic) space-filling curve preserves topological order during refinement.

```java

// TetreeConnectivity.java
private static final byte[][] INDEX_TO_BEY_NUMBER = {
    // Maps Morton index to Bey child ID based on parent type
    // This mapping preserves the TM-SFC property: children are numbered
    // in tree-monotonic order, enabling efficient traversal and neighbor finding
};

```

**Algorithm Steps:**
1. **Bey Refinement**: Create 8 children using vertex midpoints
2. **TM Reordering**: Map Bey indices to TM indices for SFC property
3. **Neighbor Preservation**: Ghost layer maintains neighbor topology
4. **Continuous Refinement**: Support variable-depth refinement without global conforming

---

## 2. RED/GREEN REFINEMENT STRATEGIES

### 2.1 Red Refinement (Isotropic Subdivision)

**Definition**: Subdivide an element into 8 congruent children (in 3D tetrahedra).

**Luciferase Implementation:**

```java

// Bey refinement IS red refinement for tetrahedra
Tet[] children = tet.children(); // Always produces 8 children
// Children are congruent and cover parent volume completely

```

**Properties:**
- Isotropic: All dimensions refined equally
- Deterministic: Each element → exactly 8 children
- Conforming-compatible: Can be combined with green refinement

### 2.2 Green Refinement (Bisection)

**Definition**: Subdivide element into 2 children by cutting through longest edge.

**Luciferase Application:**

```java

// Adaptive refinement strategy: analyzeCell() returns RefinementDecision
public enum RefinementDecision {
    REFINE,     // Apply red refinement (8 children)
    COARSEN,    // Reverse refinement
    MAINTAIN    // Keep current level
}

// Combined approach:
// - REFINE at level L → Red refinement (8 children at level L+1)
// - Neighbor at level L-1 → Creates hanging nodes
// - Ghost layer handles seamless operations despite non-conformity

```

**Why Both Strategies Matter:**
- **Red**: Fine-grain control, optimal element quality
- **Green**: Economical refinement, fewer elements
- **Combined**: Adaptive meshes with controlled hanging node ratio

### 2.3 Conforming Mesh Integration

**Balancing Requirement**: To avoid excessive hanging nodes:

```java

// TreeBalancingStrategy.java
public interface TreeBalancingStrategy<ID extends EntityID> {
    // Prevent overly deep local refinement
    int getSplitThreshold(int level, int maxEntitiesPerNode);

    // Trigger merging to maintain balance
    int getMergeThreshold(int level, int maxEntitiesPerNode);

    // Adaptive threshold adjustment
    boolean shouldRebalanceTree(TreeBalancingStats stats);
}

```

**Conformity Enforcement:**

```

1-Irregular (1-Conforming):

  - Max depth difference between neighbors = 1
  - Green refinement creates this automatically
  - Luciferase: Ghost layers + balancing strategies maintain 1-irregularity

2-Irregular (2-Conforming):

  - Max depth difference = 2
  - More economical, fewer elements
  - Requires explicit edge/vertex hanging node handling

3+ Irregular:

  - Unrestricted refinement
  - Full non-conforming mesh
  - Requires complete ghost infrastructure (Luciferase provides)

```

---

## 3. NON-CONFORMING MESH ALGORITHMS

### 3.1 Core Algorithm: TM-SFC Ordering

**Problem**: How to maintain spatial coherence in non-conforming meshes?
**Solution**: Tree-monotonic space-filling curve

```java

// TetreeConnectivity provides TM ordering
// Maps Bey index (0-7) to TM index (0-7)

// Parent type 0:
// TM Order: T0, T1, T4, T7, T2, T3, T6, T5
// This preserves neighbor locality for ghost detection

for (int tmIndex = 0; tmIndex < 8; tmIndex++) {
    int beyIndex = TM_TO_BEY[parentType][tmIndex];
    Tet child = getChild(beyIndex); // Children in TM order
}

```

**Benefits:**
1. **Locality**: Spatially close children are numbered close
2. **Ghost Detection**: Efficient neighbor finding via SFC properties
3. **Communication**: Reduced ghost data transfers in distributed mode

### 3.2 Neighbor Detection in Non-Conforming Meshes

**Challenge**: Elements at different levels are neighbors despite size difference.

**Luciferase Solution:**

```java

// NeighborDetector interface
public interface NeighborDetector<Key extends SpatialKey<Key>> {
    // Find all topological neighbors (face, edge, vertex)
    List<Key> findNeighbors(Key spatialKey, GhostType ghostType);

    // Supports multi-level neighbor detection:
    // - Level L element neighbors include:
    //   - Same-level neighbors (8 faces)
    //   - Parent-level neighbors (partially overlapping)
    //   - Child-level neighbors (fully contained)
}

// ElementGhostManager handles the complexity
public class ElementGhostManager<Key, ID, Content> {
    // Detect boundary elements
    Set<Key> boundaryElements = ghostManager.getBoundaryElements();

    // Create ghost copies of neighbor elements
    for (Key boundaryKey : boundaryElements) {
        List<NeighborInfo<Key>> neighbors = neighborDetector.findNeighbors(
            boundaryKey, GhostType.FACES);

        for (NeighborInfo<Key> neighbor : neighbors) {
            ghostLayer.addGhostElement(neighbor.getSpatialKey());
        }
    }
}

```

### 3.3 Ghost Layer Architecture

**Purpose**: Enable local computation without explicit communication in distributed environments.

```

Local domain:           Ghost domain (non-local):
+--------+              +--------+
| Element| -> Ghost --> | Ghost  |
+--------+              | Copy   |
                        +--------+

Benefits:

- Local computation sees complete neighbor info
- No communication during computation phase
- Communication deferred to ghost sync phase

```

**Implementation Details:**

```java

// GhostLayer.java
public class GhostLayer<Key, ID, Content> {
    // Ghost elements indexed by spatial key
    private final ConcurrentNavigableMap<Key, List<GhostElement<Key, ID, Content>>>
        ghostElements;

    // Remote elements (our locals that are ghosts elsewhere)
    private final Map<Integer, Set<RemoteElement<Key, ID, Content>>>
        remoteElements;

    // Synchronization at forest boundaries
    void updateGhostZones();  // Sync ghost data
    void syncGhosts();         // Bidirectional sync
}

// GhostAlgorithm selection
public enum GhostAlgorithm {
    MINIMAL,      // Only direct neighbors
    CONSERVATIVE, // Direct + second-level
    AGGRESSIVE,   // Multiple levels
    ADAPTIVE,     // Learn from usage
}

```

---

## 4. BALANCING REQUIREMENTS FOR NON-CONFORMING MESHES

### 4.1 Why Balancing Matters

**Problem**: Unconstrained refinement leads to:
- Excessive hanging nodes
- Memory overhead (ghost storage)
- Communication volume (ghost exchanges)
- Query performance degradation

**Solution**: Tree balancing maintains regularity constraints.

### 4.2 Luciferase Balancing Strategies

**TreeBalancingStrategy Interface:**

```java

public interface TreeBalancingStrategy<ID extends EntityID> {
    // Node-level decisions
    int getSplitThreshold(int level, int maxEntitiesPerNode);
    int getMergeThreshold(int level, int maxEntitiesPerNode);

    // Tree-level decisions
    boolean shouldRebalanceTree(TreeBalancingStats stats);

    // Configuration
    long getMinRebalancingInterval();
}

// Built-in strategies
class DefaultBalancingStrategy {
    // Split when 80% full
    // Merge when <20% full
    // Rebalance if variance > 50 or empty nodes > 30%
}

class AggressiveBalancingStrategy {
    // Split at 60% (tighter control)
    // Merge when variance > 25
    // More frequent rebalancing
}

class ConservativeBalancingStrategy {
    // Split only when necessary (95% full)
    // Merge rarely (minimize overhead)
    // Only rebalance in extreme cases
}

```

### 4.3 Balancing Stages

**Stage 1: Local Node Decisions**

```java

// Check if node exceeds thresholds
BalancingAction action = balancer.checkNodeBalance(nodeIndex);

// Execute action
switch (action.type()) {
    case SPLIT -> {
        // Distribute entities to child nodes
        balancer.splitNode(nodeIndex, nodeLevel);
    }
    case MERGE -> {
        // Combine with siblings
        balancer.mergeNodes(siblingIndices, parentIndex);
    }
    case BALANCE -> {
        // Redistribute entities within node
        balancer.rebalanceNodeEntities(nodeIndex);
    }
}

```

**Stage 2: Tree Rebalancing**

```java

// Triggered when:
// - Load variance exceeds threshold
// - Too many empty nodes
// - User request
// - Scheduled maintenance

TreeBalancer.RebalancingResult result = spatialIndex.rebalanceTree();
// Result includes: nodes created, removed, merged, split, entities relocated

```

**Stage 3: Adaptive Strategies**

```java

// Monitor performance and adapt strategy
class AdaptiveBalancingSystem {
    public void adapt(TreeBalancingStats stats) {
        if (metrics.averageQueryTime() > 10.0) {
            // Queries slow: be more aggressive
            strategy.targetVariance = Math.max(25.0, variance * 0.9);
        } else if (metrics.averageInsertTime() > 5.0) {
            // Inserts slow: be less aggressive
            strategy.targetVariance = Math.min(100.0, variance * 1.1);
        }
    }
}

```

### 4.4 Balancing + Ghost Layer Interaction

**Constraint**: Hanging node depth must be controlled to limit ghost overhead.

```

Depth difference:  Ghost overhead:    Balancing action:
Level 0-1          ~8 ghosts          Acceptable (green refinement)
Level 0-2          ~64 ghosts         Monitor (second-level neighbors)
Level 0-3+         >512 ghosts        Trigger rebalancing

Forest-level balancing:

- Split overloaded trees
- Merge underutilized trees
- Load balance entities across trees
- Coordinate ghost exchanges between trees

```

---

## 5. INTEGRATION WITH CONFORMING APPROACHES

### 5.1 Hybrid Conforming/Non-Conforming Strategy

**Use Cases:**
- **Conforming**: Interior regions (simple, efficient)
- **Non-Conforming**: Boundary regions (adaptive, captures features)

**Implementation Pattern:**

```java

// Create adaptive forest with mixed strategies
AdaptiveForest<TetreeKey, LongEntityID, GameObject> forest =
    new AdaptiveForest<>(config, tetreeFactory);

// Configure density thresholds
forest.setDensityThreshold(100);        // Refine when >100 entities
forest.setVarianceThreshold(0.15f);     // Refine when variance >15%
forest.setMergeThreshold(20);           // Merge when <20 entities

// Forest automatically handles subdivision
// Interior trees remain balanced and conforming
// Boundary regions use non-conforming ghosts as needed

```

### 5.2 Ghost Layer Conformity Enforcement

**Principle**: Use ghosts to enforce conformity where it matters.

```java

// Conformity levels via ghost algorithms
GhostAlgorithm conformityStrategy = switch(conformityLevel) {
    case STRICT ->      // Multi-level ghosts enforce 1-conformity
        GhostAlgorithm.CONSERVATIVE;
    case BALANCED ->    // Selective ghosts balance cost/conformity
        GhostAlgorithm.ADAPTIVE;
    case PERMISSIVE ->  // Minimal ghosts for max economy
        GhostAlgorithm.MINIMAL;
};

ghostManager.setGhostAlgorithm(conformityStrategy);

```

### 5.3 Migration Between Conforming and Non-Conforming

**Tetree to Octree Migration**:

```java

// When non-conformity becomes excessive:
Octree<MortonKey, LongEntityID, Content> octree =
    Octree.fromTetree(tetree);

// Octree provides strict conforming guarantee
// but loses tetrahedral efficiency

```

**Octree to Tetree Migration**:

```java

// When tetrahedral efficiency needed:
Tetree<TetreeKey, LongEntityID, Content> tetree =
    Tetree.fromOctree(octree);

// Gains tetrahedral efficiency
// Incurs non-conformity cost (manageable with ghosts)

```

---

## 6. HANGING NODES IN BEY REFINEMENT

### 6.1 Automatic Hanging Node Prevention

**Bey's Refinement Property:**

```

Parent tetrahedron with 4 vertices V0, V1, V2, V3

Refinement creates:

- 6 edge midpoints
- 4 corner tetrahedra (one per vertex)
- 4 interior tetrahedra (from split octahedron)
- Total: 8 children

Result: NO hanging nodes at element level

- All children are complete tetrahedra
- Faces are either shared or internal
- Vertices at element boundaries defined at parent level

```

**Type Mapping Preserves Continuity:**

```java

// Each child has a type that encodes its relationship to parent
private static final byte[][] TYPE_TO_TYPE_OF_CHILD = {
    // Parent type -> Child types in order
    // This table ensures that child vertices align properly
    // with sibling and neighbor elements
};

```

### 6.2 When Hanging Nodes DO Occur: Mixed Refinement

**Scenario**: Parent P refined, neighbor N stays at parent level.

```

Before refinement:     After P refined (N not refined):
+-------+              +---+---+
|   P   |              |C0 |C1 |
|       |    -->       +---+---+  <- Hanging nodes
|   N   |              |C2 |C3 |
+-------+              +-------+
                       |   N   |
                       +-------+

```

**Luciferase Handling:**

```java

// GhostLayer automatically creates ghost copies
ElementGhostManager ghostMgr = new ElementGhostManager<>(
    spatialIndex,
    neighborDetector,
    GhostType.FACES
);

// For boundary elements (like refined P):
Set<Key> boundaryElements = ghostMgr.getBoundaryElements();

// Ghost copies of neighbors maintain topology
for (Key boundaryKey : boundaryElements) {
    List<NeighborInfo<Key>> neighbors =
        neighborDetector.findNeighbors(boundaryKey, GhostType.FACES);

    // Create ghost copies
    for (NeighborInfo<Key> neighbor : neighbors) {
        ghostLayer.addGhostElement(neighbor.getSpatialKey());
    }
}

// Now local computation sees complete neighbor info
// Even though N is unrefined (hanging nodes exist)
// Ghosts make them "visible" to algorithms

```

### 6.3 Hanging Node Depth Control

**Problem**: Multiple levels of hanging nodes exponentially increase ghost overhead.

**Solution: Balancing Strategies**

```java

// In AdaptiveRefinementStrategy
public RefinementDecision analyzeCell(RefinementContext context, ...) {
    int hangingNodeDepth = estimateHangingNodeDepth(context);

    if (hangingNodeDepth > MAX_HANGING_NODE_DEPTH) {
        return RefinementDecision.COARSEN;  // Reduce hanging nodes
    }

    if (context.fieldValues.get("error") > refinementThreshold) {
        return RefinementDecision.REFINE;
    }

    return RefinementDecision.MAINTAIN;
}

// Validation prevents invalid refinement decisions
private static final int MAX_HANGING_NODE_DEPTH = 3;

boolean validateRefinementDecisions(Map<LevelIndex, RefinementDecision> decisions) {
    // Ensure depth differences don't exceed threshold
    // Prevent local refinement from being too aggressive
}

```

---

## 7. SUMMARY: KEY INSIGHTS FOR LUCIFERASE

### 7.1 TM-SFC Properties Leveraged

1. **Tree-Monotonic Ordering**: SFC indices preserve spatial locality
2. **Neighbor Detection**: Efficient via SFC properties
3. **Ghost Infrastructure**: Minimal communication overhead
4. **Non-Conforming Support**: Hanging nodes manageable via ghosts

### 7.2 Implementation Patterns

```java

// Pattern 1: Adaptive refinement with non-conformity control
Tetree<TetreeKey, LongEntityID, Content> tetree = new Tetree<>(idGen, maxLevel);
AdaptiveRefinementStrategy strategy = new AdaptiveRefinementStrategy() {
    public RefinementDecision analyzeCell(RefinementContext ctx, ...) {
        // Analyze local error/criteria
        // Control hanging node depth
    }
};

// Pattern 2: Ghost-based neighbor awareness
ElementGhostManager<TetreeKey, LongEntityID, Content> ghostMgr =
    new ElementGhostManager<>(tetree, neighborDetector, GhostType.FACES);
ghostMgr.createGhostLayer();
ghostMgr.updateElementGhosts(modifiedKey);

// Pattern 3: Balancing for conformity
TreeBalancingStrategy<LongEntityID> balancing =
    new AggressiveBalancingStrategy<>();  // Tight control
spatialIndex.setBalancingStrategy(balancing);
spatialIndex.setAutoBalancingEnabled(true);

```

### 7.3 Trade-offs

| Aspect | Conforming | Non-Conforming |
| -------- | ----------- | ----------------- |
| Elements | More (simpler) | Fewer (complex) |
| Refinement | Global decisions | Local decisions |
| Ghost overhead | None | Low-moderate |
| Query performance | Predictable | Requires ghosting |
| Implementation | Simple | TM-SFC needed |

---

## 8. REFERENCES AND IMPLEMENTATIONS

### 8.1 Code Locations in Luciferase

- **Bey Refinement**: `/lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java`
- **TM-SFC Ordering**: `/lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/TetreeConnectivity.java`
- **Ghost Layer**: `/lucien/src/main/java/com/hellblazer/luciferase/lucien/forest/ghost/GhostLayer.java`
- **Ghost Manager**: `/lucien/src/main/java/com/hellblazer/luciferase/lucien/forest/ghost/ElementGhostManager.java`
- **Balancing API**: `/lucien/src/main/java/com/hellblazer/luciferase/lucien/tree/TreeBalancer.java`
- **Adaptive Refinement**: `/dyada-java/src/main/java/com/dyada/refinement/AdaptiveRefinementStrategy.java`

### 8.2 Documentation Files

- `TETREE_IMPLEMENTATION_GUIDE.md` - Complete Tetree reference
- `BEY_TETRAHEDRAL_SUBDIVISION.md` - Bey algorithm details
- `GHOST_API.md` - Ghost layer and distributed operations
- `TREE_BALANCING_API.md` - Balancing strategies
- `FOREST_MANAGEMENT_API.md` - Forest management operations

### 8.3 Paper Reference

**"A tetrahedral space-filling curve for non-conforming adaptive meshes"**
- Cited in: Tet.java documentation
- Implementation basis: t8code algorithms
- Core concepts: TM-SFC ordering, Bey refinement, ghost handling
