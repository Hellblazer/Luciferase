# ChromaDB Knowledge Index: TM-SFC Non-Conforming Meshes

This document describes how the TM-SFC non-conforming meshes knowledge is structured for ChromaDB storage and retrieval.

## Storage Schema

### Document 1: Hanging Nodes Core Concepts
**ID**: `tm_sfc_001_hanging_nodes`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "high",
  "topic": "hanging_nodes",
  "tags": ["adaptive_meshes", "non-conforming", "topology"],
  "lucien_modules": ["forest/ghost", "tetree"],
  "source_file": "TM_SFC_NONCONFORMING_MESH_EXTRACTION.md#1"
}
```

**Content Summary**:
- Definition: Elements created when parent subdivided, neighbor stays unrefined
- Geometric consequences: Discontinuities at element boundaries
- Luciferase handling: Bey vertex-based refinement prevents element-level hanging nodes
- Ghost layer approach: Non-local copies maintain neighbor topology
- Key metric: Hanging node depth constraint (MAX = 3 levels)

---

### Document 2: Bey Refinement Algorithm
**ID**: `tm_sfc_002_bey_refinement`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "critical",
  "topic": "tetrahedral_subdivision",
  "tags": ["bey_refinement", "8_children", "vertex_midpoints"],
  "lucien_modules": ["tetree", "Tet.java"],
  "performance_impact": "O(1) single child computation",
  "source_file": "BEY_TETRAHEDRAL_SUBDIVISION.md"
}
```

**Content Summary**:
- Edge midpoints: 6 vertices at tetrahedron edge midpoints
- Octahedron formation: 6 midpoints create internal octahedron
- 8 children: 4 corner tetrahedra + 4 octahedral tetrahedra
- Type mapping: TetreeConnectivity ensures continuity across children
- Optimization: Compute single child without computing all 8 (3x speedup)

**Implementation Details**:
```
Child generation pattern:
  T0-T3: Corner tetrahedra (parent anchor + edge midpoints)
  T4-T7: Interior tetrahedra (split octahedron)
  Result: 100% cube tiling, no gaps/overlaps
```

---

### Document 3: TM-SFC Ordering and Ghost Detection
**ID**: `tm_sfc_003_tm_sfc_ordering`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "critical",
  "topic": "space_filling_curve",
  "tags": ["sfc", "tree_monotonic", "neighbor_locality"],
  "lucien_modules": ["tetree/TetreeConnectivity", "forest/ghost"],
  "computational_benefit": "efficient_neighbor_detection",
  "source_file": "TM_SFC_NONCONFORMING_MESH_EXTRACTION.md#3.1"
}
```

**Content Summary**:
- Tree-monotonic property: Children numbered to preserve spatial locality
- SFC index encoding: Bey index mapped to TM index per parent type
- Locality preservation: Spatially close children have close SFC indices
- Ghost detection efficiency: Neighbor finding via SFC properties
- Communication optimization: Minimal ghost data transfers in distributed mode

**Mapping Table**:
```
Parent Type 0: TM Order = [T0, T1, T4, T7, T2, T3, T6, T5]
  - Maps to TetreeConnectivity lookup tables
  - Preserves neighbor relationships for ghost creation
```

---

### Document 4: Ghost Layer Architecture for Non-Conformity
**ID**: `tm_sfc_004_ghost_layer`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "high",
  "topic": "distributed_spatial_index",
  "tags": ["ghost_elements", "non_local_data", "neighbor_info"],
  "lucien_modules": ["forest/ghost/GhostLayer.java", "forest/ghost/ElementGhostManager.java"],
  "algorithms": ["MINIMAL", "CONSERVATIVE", "AGGRESSIVE", "ADAPTIVE"],
  "source_file": "GHOST_API.md"
}
```

**Content Summary**:
- Purpose: Enable local computation without explicit communication
- Dual ghost approach: Distance-based and topology-based
- Local domain: Elements in this process
- Ghost domain: Non-local elements (neighbor copies)
- Synchronization: Deferred to ghost sync phase (not computation phase)

**Algorithm Selection**:
```
MINIMAL:      Direct neighbors only (lowest memory)
CONSERVATIVE: Direct + second-level neighbors (balanced)
AGGRESSIVE:   Multiple levels for maximum performance
ADAPTIVE:     Learns from usage patterns
```

**Key Classes**:
- `GhostLayer<Key, ID, Content>`: Stores ghost elements
- `ElementGhostManager<Key, ID, Content>`: Detects boundaries, creates ghosts
- `NeighborDetector<Key>`: Finds multi-level neighbors
- `GhostAlgorithm`: Selects ghost creation strategy

---

### Document 5: Balancing Strategies and Regularity
**ID**: `tm_sfc_005_balancing_strategies`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "high",
  "topic": "mesh_regularity",
  "tags": ["tree_balancing", "node_splitting", "adaptive_thresholds"],
  "lucien_modules": ["tree/TreeBalancer.java", "tree/TreeBalancingStrategy.java"],
  "strategies": ["DefaultBalancing", "AggressiveBalancing", "ConservativeBalancing"],
  "source_file": "TREE_BALANCING_API.md"
}
```

**Content Summary**:
- Problem: Unconstrained refinement creates excessive hanging nodes
- Solution: Tree balancing maintains regularity constraints
- Node-level: Split/merge decisions based on entity count
- Tree-level: Rebalancing when variance exceeds thresholds
- Adaptive: Adjust thresholds based on performance metrics

**Strategy Thresholds**:
```
Default:
  - Split at 80% capacity
  - Merge at <20% capacity
  - Rebalance if variance > 50

Aggressive:
  - Split at 60% capacity
  - Merge at <25% capacity
  - Rebalance if variance > 25

Conservative:
  - Split at 95% capacity (only when necessary)
  - Merge rarely
  - Only rebalance in extreme cases
```

**Conformity Enforcement**:
```
1-Irregular (1-Conforming):   Max depth diff = 1
2-Irregular (2-Conforming):   Max depth diff = 2
3+ Irregular:                  Unrestricted (requires ghosts)
```

---

### Document 6: Adaptive Refinement Integration
**ID**: `tm_sfc_006_adaptive_refinement`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "high",
  "topic": "adaptive_mesh_refinement",
  "tags": ["refinement_criteria", "hanging_node_control", "amr"],
  "lucien_modules": ["dyada-java/AdaptiveRefinementStrategy.java"],
  "decisions": ["REFINE", "COARSEN", "MAINTAIN"],
  "source_file": "AdaptiveRefinementStrategy.java"
}
```

**Content Summary**:
- Strategy interface: Analyze cells and make refinement decisions
- Decision types: REFINE (8 children), COARSEN (merge with siblings), MAINTAIN
- Hanging node prevention: Validate decisions before execution
- Context information: Cell center, size, level, field values
- Validation: Prevents local refinement from being too aggressive

**Refinement Decision Context**:
```java
record RefinementContext(
    LevelIndex cellIndex,
    Coordinate cellCenter,
    double cellSize,
    int currentLevel,
    Object cellData
)
```

**Depth Control**:
```
MAX_HANGING_NODE_DEPTH = 3
- If depth > 3: Return COARSEN
- Prevents exponential ghost overhead
- Balances mesh quality and efficiency
```

---

### Document 7: Conforming/Non-Conforming Integration
**ID**: `tm_sfc_007_hybrid_approaches`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "high",
  "topic": "mesh_strategy_selection",
  "tags": ["octree_tetree_migration", "hybrid_meshes", "spatial_partitioning"],
  "lucien_modules": ["forest/AdaptiveForest.java", "octree", "tetree"],
  "use_cases": ["interior_conforming", "boundary_nonconforming"],
  "source_file": "FOREST_MANAGEMENT_API.md"
}
```

**Content Summary**:
- Hybrid approach: Conforming interior, non-conforming boundaries
- Forest strategy: Multiple trees with different properties
- Migration: Tetree ↔ Octree conversions based on efficiency
- Trade-offs: Elements vs. refinement decisions vs. ghost overhead
- Implementation: AdaptiveForest with density-based subdivision

**Integration Pattern**:
```
Interior regions (simple):       Boundary regions (adaptive):
  - Octree (conforming)           - Tetree (non-conforming)
  - Regular structure             - Local refinement
  - Predictable queries           - Feature capture
  - No ghost overhead             - Ghost infrastructure
```

---

### Document 8: Red and Green Refinement in Tetrees
**ID**: `tm_sfc_008_red_green_refinement`
**Metadata**:
```json
{
  "paper": "tm_sfc_nonconforming",
  "relevance_to_lucien": "medium",
  "topic": "refinement_patterns",
  "tags": ["red_refinement", "green_refinement", "isotropic_bisection"],
  "lucien_modules": ["tetree", "AdaptiveRefinementStrategy"],
  "refinement_types": ["bey_red", "edge_bisection_green"],
  "source_file": "TM_SFC_NONCONFORMING_MESH_EXTRACTION.md#2"
}
```

**Content Summary**:
- Red refinement: Isotropic subdivision into 8 congruent children (Bey)
- Green refinement: Bisection along longest edge (2 children)
- Combined use: Red for fine-grain control, green for economy
- Tetree property: Bey refinement IS red refinement (always 8 children)
- Balancing role: Prevents excessive red refinement depth

**Comparison Table**:
```
Aspect          Red (Bey)              Green (Bisection)
Children        8 (always)             2 (always)
Isotropy        Yes                    No (anisotropic)
Deterministic   Yes                    Yes
Element quality Good (similar sizes)   Fair (can degrade)
Hanging nodes   At boundaries only     Minimal
Economy         Moderate               Higher
```

---

## ChromaDB Query Examples

### Query 1: Find hanging node handling approaches
```
Query: "How are hanging nodes handled in non-conforming meshes?"
Expected Results: Documents 1, 2, 4, 5, 6

Relevant sections:
- How hanging nodes form (Doc 1)
- Bey refinement prevents element-level hanging nodes (Doc 2)
- Ghost infrastructure manages mixed-level neighbors (Doc 4)
- Balancing controls hanging node depth (Doc 5)
- Adaptive strategies validate decisions (Doc 6)
```

### Query 2: Implement distributed spatial index with ghosts
```
Query: "What's the architecture for ghost elements in distributed tetrees?"
Expected Results: Documents 3, 4, 7

Relevant sections:
- TM-SFC enables efficient neighbor detection (Doc 3)
- Ghost layer stores non-local copies (Doc 4)
- GhostAlgorithm selection (Doc 4)
- Integration with forest management (Doc 7)
```

### Query 3: Balance non-conforming refinement
```
Query: "How to prevent excessive hanging nodes while maintaining adaptivity?"
Expected Results: Documents 5, 6, 8

Relevant sections:
- Balancing strategies enforce regularity (Doc 5)
- Depth control in refinement decisions (Doc 6)
- Hanging node depth limits (Doc 5)
- Red/green refinement trade-offs (Doc 8)
```

### Query 4: Optimize tetrahedral vs. cubic meshes
```
Query: "When should I use Tetree vs. Octree for adaptive meshes?"
Expected Results: Documents 2, 7, 8

Relevant sections:
- Bey refinement efficiency (Doc 2)
- Hybrid conforming/non-conforming strategy (Doc 7)
- Red refinement properties (Doc 8)
```

---

## Indexing Strategy for ChromaDB

### Vector Embeddings (Implicit)
Each document is embedded for semantic search on:
1. **Technical concepts**: hanging nodes, space-filling curves, ghost elements
2. **Algorithms**: Bey refinement, TM-SFC ordering, balancing strategies
3. **Implementation details**: Code patterns, class relationships, API usage
4. **Performance implications**: Ghost overhead, balancing costs, neighbor detection efficiency

### Metadata Fields Used in Filtering
- `paper`: Always "tm_sfc_nonconforming" for this knowledge base
- `relevance_to_lucien`: "critical", "high", "medium" (affects search ranking)
- `topic`: Category for document retrieval (hanging_nodes, tetrahedral_subdivision, etc.)
- `tags`: Searchable keywords
- `lucien_modules`: Which modules implement this knowledge
- `source_file`: Link back to documentation or code

### Query Workflow
```
User Query (natural language)
    ↓
ChromaDB semantic search (finds relevant documents by embedding)
    ↓
Metadata filtering (optional: by topic, module, relevance)
    ↓
Results ranked by:
    1. Semantic relevance to query
    2. Relevance to Lucien (critical > high > medium)
    3. Recency of documentation
    4. Cross-references (related documents)
```

---

## Integration with Tetree Implementation

### Immediate Applications

**1. Ghost Layer Enhancement**
- Use Document 4 to understand existing ghost infrastructure
- Document 3 (TM-SFC) explains why neighbor detection works efficiently
- Document 1 explains the hanging nodes being managed by ghosts

**2. Balancing Algorithm Tuning**
- Document 5 provides pre-configured strategies
- Document 6 shows depth control implementation
- Combine with Document 8 for refinement pattern analysis

**3. Adaptive Refinement Integration**
- Document 6 is core for adding AMR capabilities
- Document 5 controls regularity during refinement
- Document 1 and 2 explain what's being created

### Long-term Research
- Conforming vs. non-conforming trade-off analysis (Document 7)
- Distributed Tetree performance optimization (Document 3, 4)
- Hanging node depth optimization for specific applications (Document 5, 6)

---

## Summary: Document Connectivity Graph

```
Hanging Nodes (1)
    ├─→ Bey Refinement (2)
    │   └─→ TM-SFC Ordering (3)
    │       └─→ Ghost Layer (4)
    │           └─→ Balancing (5)
    │               └─→ Adaptive Refinement (6)
    │                   ├─→ Hybrid Approaches (7)
    │                   └─→ Red/Green Refinement (8)
    │
    └─→ Ghost Layer (4)
        └─→ [same path as above]

Read Order for Learning:
1. Start: Hanging Nodes (1) - understand the problem
2. Solution: Bey Refinement (2) - understand the core algorithm
3. Property: TM-SFC Ordering (3) - understand why it works
4. Implementation: Ghost Layer (4) - distributed support
5. Control: Balancing (5) - manage hanging node depth
6. Application: Adaptive Refinement (6) - practical usage
7. Strategy: Hybrid Approaches (7) - when to use Tetree vs Octree
8. Detail: Red/Green Refinement (8) - refinement options
```

---

**Total Knowledge Captured**: 8 major documents, ~150 key concepts
**Estimated ChromaDB Size**: ~50-100KB (highly compressed, vector embeddings separate)
**Update Frequency**: As Luciferase Tetree implementation evolves
**Maintenance**: Cross-reference updates when code changes
