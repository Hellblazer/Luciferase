# Distributed Animation Architecture v4.0

**Last Updated**: 2026-01-05
**Date**: 2026-01-04
**Status**: VON-Centric Design with Tetrahedral Bounding
**Epic**: Luciferase-8oe (replaces Luciferase-3xw)
**Critical Change**: VON overlay IS the distributed spatial index

---

## Executive Summary

This architecture simplifies the v3.0 design by recognizing that **VON (Voronoi Overlay Network) IS the distributed spatial index**. Bubbles are VON nodes, not separate entities requiring discovery via a Replicated Forest. The spatial index (Tetree) obviates Voronoi calculation - we use nearest neighbor relationships and spatial grid coordinates instead.

**Key Insight**: The natural bounding volume for Tetree is the **asymmetric tetrahedral cell** (S0-S5 characteristic tetrahedra), NOT axis-aligned bounding boxes (AABB).

---

## Critical Architectural Changes from v3.0

### 1. VON-Centric Model

**OLD (v3.0)**: Replicated Forest + separate VON discovery
```
Layer 2: Replicated Forest (full replica per server)
  └─ Delos gossip for replication (1-2s propagation)

Layer 3: VON Discovery
  └─ JOIN/MOVE/LEAVE protocols
  └─ Delos gossip for announcements
```

**NEW (v4.0)**: VON IS the distributed spatial index
```
VON Overlay Network:
  - Bubbles ARE VON nodes
  - Spatial index provides NN relationships
  - No separate forest replication
  - Point-to-point after join (no broadcast)
```

**Eliminated**:
- Replicated Forest layer
- Delos gossip broadcast
- Separate discovery mechanism
- Eventually consistent forest sync

### 2. Tetrahedral Bounding Volumes

**AABB Trap** (what we want to avoid):
```java
class BubbleBounds {
    float minX, maxX;  // ❌ Axis-aligned
    float minY, maxY;
    float minZ, maxZ;
}
```

**Tetrahedral Model** (natural for Tetree):
```java
class BubbleBounds {
    TetreeKey rootKey;      // S0-S5 characteristic tetrahedron
    Tuple3i rdgMin, rdgMax; // Tetrahedral coordinates (RDGCS)
    int level;              // Subdivision level

    // Converts to/from Cartesian via Tetrahedral coordinate system
    Point3D toCartesian(Tuple3i rdg);
    Tuple3i toRDG(Point3f cartesian);
}
```

**Rationale**: S0-S5 tetrahedra perfectly tile a cube and align with Tetree's natural subdivision. Using AABB loses geometric accuracy and forces extra coordinate conversions.

---

## Core Architecture Layers

### Layer 1: VON Overlay Network

**Purpose**: Distributed spatial index via proximity-based neighbor relationships

**Components**:
- **Bubble** (VON node): Single-threaded animator with tetrahedral bounds
- **VON JOIN**: New bubble contacts Fireflies member, routes to responsible region
- **VON MOVE**: Bubble centroid shifts → notify VON neighbors
- **VON LEAVE**: Bubble shutdown → notify neighbors
- **Fireflies**: Server-level cluster membership (BFT subset voting)

**Key Properties**:
1. **Point-to-point communication**: After join, all communication is P2P between VON neighbors
2. **No broadcast**: Fireflies only used for initial contact (member list)
3. **Spatial routing**: TetreeKey provides consistent hashing for "route to region"
4. **Boundary neighbors**: VON "watchmen" pattern for discovery

**Discovery Flow**:
```
1. New Bubble B spawns
2. B contacts any Fireflies member F
3. F routes to closest bubble C (by TetreeKey)
4. C is "acceptor" - sends neighbor list to B
5. B establishes ghost sync with neighbors
6. Neighbors update their VON views (add B)
```

**Movement Flow**:
```
1. Bubble A's entities move
2. A's centroid shifts (adaptive bounding)
3. A sends VON MOVE to neighbors
4. Neighbors check if A crossed boundaries
5. New neighbors discovered via spatial proximity
6. Ghost sync established with new neighbors
```

### Layer 2: Bubble Lifecycle with Tetrahedral Bounds

**Purpose**: Single-threaded animators with internal spatial indexing

**Bubble Structure**:
```java
class Bubble {
    UUID bubbleId;
    TetreeKey centerKey;              // Centroid in tetrahedral coords
    BubbleBounds bounds;              // Adaptive tetrahedral volume
    Tetree<EntityID, Content> index;  // Internal entity index
    Set<UUID> vonNeighbors;           // VON boundary neighbors
    VolumeAnimator animator;          // Single-threaded (1:1 mapping)
}
```

**Adaptive Tetrahedral Bounding**:
```java
class BubbleBounds {
    // Max Tet encompassing all entities
    TetreeKey rootKey;   // S0-S5 type + position
    int level;           // Subdivision level

    // Grows/shrinks as entity distribution evolves
    void recalculate(Collection<Entity> entities) {
        // Find bounding Tet in RDGCS space
        Tuple3i rdgMin = entities.stream()
            .map(e -> tetrahedral.toRDG(e.position))
            .reduce(Tuple3i::min);
        Tuple3i rdgMax = entities.stream()
            .map(e -> tetrahedral.toRDG(e.position))
            .reduce(Tuple3i::max);

        // Determine enclosing characteristic tetrahedron
        rootKey = findEnclosingTet(rdgMin, rdgMax);
        level = calculateLevel(rdgMin, rdgMax);
    }
}
```

**Split Trigger**: `frameTimeMs > TARGET_FRAME_TIME * 1.2` (10ms budget)

**Split Strategy**: Cluster detection → create bubbles around entity clusters → assign to servers via power of 2 choices

**Capacity Limit**: If can't subdivide enough to meet frame budget → reject new entities (density too high)

### Layer 3: Ghost Layer (Cross-Server Sync)

**Purpose**: Synchronize entity state between VON neighbors on different servers

**Ghost Trigger**:
```java
boolean needsGhost = (bubble1.serverId != bubble2.serverId)
                  && overlaps(bubble1.bounds, bubble2.bounds);
```

**Same-Server Optimization**: No ghosts when bubbles on same server (shared memory)

**Batched Sync**:
- Batch ghosts at bucket boundaries (100ms intervals)
- TTL: 500ms (5 buckets)
- Memory limit: 1000 ghosts per neighbor

**GhostEntity Structure**:
```java
record GhostEntity<ID>(
    ID entityId,
    Tuple3f position,      // Cartesian position
    Tuple3i rdgPosition,   // Tetrahedral coordinates
    BubbleBounds bounds,   // Tetrahedral bounds
    Content content,
    UUID sourceBubbleId
);
```

**Discovery via Ghosts**: When ghost arrives, learn about source bubble → VON "watchmen" pattern

### Layer 4: Load Balancing (Optional)

**Purpose**: Migrate bubbles for capacity balancing

**Algorithm**: Power of 2 Random Choices + Spatial Locality
```java
// Spatial Tumbler for locality (symbolic, not ownership)
TumblerRegion region = spatialTumbler.getRegion(bubble.position());
Set<Server> localServers = region.getServers();

// Power of 2 choices
Server s1 = randomChoice(localServers);
Server s2 = randomChoice(localServers);

return s1.utilizationPercent() < s2.utilizationPercent() ? s1 : s2;
```

**Migration Protocol**:
1. Bubble B selected for migration from Server1 to Server2
2. Server2 creates new Bubble B' (same bubbleId)
3. B' sends VON MOVE to neighbors (position unchanged!)
4. Neighbors update routing: bubbleId → Server2
5. Server1 deactivates B after confirmation
6. Ghost sync re-established with new server

**Note**: Migration does NOT change bubble position - only server assignment. VON neighbors notified via overlay maintenance protocol.

---

## Tetrahedral Coordinate System (RDGCS)

### Coordinate Transformations

From `portal/src/main/java/com/hellblazer/luciferase/portal/Tetrahedral.java`:

```java
// Cartesian → Tetrahedral
Point3i toRDG(Tuple3f cartesian) {
    return new Point3i(
        (int) ((-cartesian.x + cartesian.y + cartesian.z) * MULTIPLICATIVE_ROOT_2),
        (int) ((cartesian.x - cartesian.y + cartesian.z) * MULTIPLICATIVE_ROOT_2),
        (int) ((cartesian.x + cartesian.y - cartesian.z) * MULTIPLICATIVE_ROOT_2)
    );
}

// Tetrahedral → Cartesian
Point3D toCartesian(Tuple3i rdg) {
    return new Point3D(
        (rdg.y + rdg.z) * DIVIDE_ROOT_2,
        (rdg.z + rdg.x) * DIVIDE_ROOT_2,
        (rdg.x + rdg.y) * DIVIDE_ROOT_2
    );
}
```

**Constants**:
- `DIVIDE_ROOT_2 = 1.0 / Math.sqrt(2.0)` = 0.7071...
- `MULTIPLICATIVE_ROOT_2 = Math.sqrt(2.0)` = 1.4142...

### S0-S5 Characteristic Tetrahedra

From Tetree implementation (`lucien/tetree/Tet.java`):

```java
// 6 tetrahedra perfectly tile a cube
S0: vertices (0,1,3,7) - all share V0 (origin) and V7 (opposite corner)
S1: vertices (0,2,3,7)
S2: vertices (0,4,5,7)
S3: vertices (0,4,6,7)
S4: vertices (0,1,5,7)
S5: vertices (0,2,6,7)
```

**Properties**:
- 100% cube coverage (no gaps or overlaps)
- Each type has unique orientation
- All 6 share two vertices (V0, V7)
- Perfect containment via barycentric coordinates

### Why Tetrahedral Bounding?

1. **Geometric Accuracy**: Tetree subdivides space tetrahedrally - using AABB forces coordinate conversion overhead
2. **Natural Containment**: Entity containment already uses tetrahedral barycentric coordinates
3. **Perfect Tiling**: S0-S5 guarantee no gaps/overlaps when bubbles subdivide
4. **Adaptive Subdivision**: Bubble split creates child tetrahedra, not child cubes
5. **Collision Detection**: Ray-tetrahedron intersection using existing Plücker coordinates

---

## Implementation Phases (6-7 weeks)

### Phase 0: VON + Fireflies Integration (1 week)

**Bead**: Luciferase-9l6

**Deliverables**:
1. **Copy Thoth VON**: `~/git/Thoth` → `luciferase/von/`
   - VON JOIN/MOVE/LEAVE protocols
   - 2D → 3D adaptation using Tetree spatial index
   - Remove Voronoi calculation (use NN from Tetree)

2. **Copy Delos Fireflies**: `~/git/Delos/fireflies` → `luciferase/fireflies/`
   - BFT cluster membership
   - View change notifications
   - MTLS client pool

3. **Validate Integration**:
   - Fireflies `context.active()` returns member list
   - VON routing via TetreeKey hashing
   - MTLS `connect(member)` establishes secure connection

4. **TetreeKeyRouter**: Fallback wrapper for SFC routing
   ```java
   class TetreeKeyRouter {
       Member routeToKey(TetreeKey key) {
           Digest keyDigest = digestAlgo.digest(key.consecutiveIndex());
           return context.successor(ringIndex, keyDigest);
       }
   }
   ```

**Success Criteria**:
- Fireflies view changes < 5 seconds (HA requirement)
- VON routing finds correct region
- MTLS connections secured
- No external dependencies (Thoth/Delos copied locally)

### Phase 1: Bubble with Tetrahedral Bounds (2 weeks)

**Bead**: Luciferase-xkm

**Deliverables**:
1. **Bubble Class**:
   - Internal Tetree spatial index
   - Adaptive tetrahedral bounds
   - VolumeAnimator integration (single-threaded)
   - VON neighbor tracking

2. **BubbleBounds**:
   - TetreeKey rootKey (S0-S5 + level)
   - RDGCS coordinate conversions
   - Adaptive recalculation

3. **Adaptive Split**:
   - Cluster detection in RDGCS space
   - Child bubble creation (tetrahedral subdivision)
   - Server assignment via power of 2 + locality

4. **Bubble Join** (affinity-based merge):
   - Combine entities from nearby bubbles
   - Recalculate tetrahedral bounds
   - Update VON neighbors

**Files**:
```
simulation/src/main/java/.../simulation/
├── Bubble.java
├── BubbleBounds.java
├── BubbleLifecycle.java
├── AdaptiveSplitPolicy.java
└── test/
    ├── BubbleTest.java
    ├── BubbleBoundsTest.java
    └── AdaptiveSplitTest.java
```

**Success Criteria**:
- Bubble tracks 100-5000 entities
- Frame time < 10ms (single-threaded)
- Split triggers at 10ms budget breach
- Tetrahedral bounds accurate (100% containment)
- Join reduces bubble count when load permits

### Phase 2: VON Discovery Protocol (2 weeks)

**Bead**: Luciferase-htv

**Deliverables**:
1. **VON JOIN**: New bubble contacts Fireflies, routes to acceptor
2. **VON MOVE**: Position update → neighbor discovery
3. **VON LEAVE**: Graceful shutdown notification
4. **Neighbor Discovery**: Via Tetree NN queries (no Voronoi)

**Integration**:
- Bubble lifecycle events trigger VON protocols
- Fireflies provides initial contact
- TetreeKeyRouter handles routing
- VON neighbors stored in bubble

**Tests**:
- 10-bubble cluster formation
- Bubble movement triggers neighbor updates
- Graceful shutdown propagates to neighbors
- Network partition recovery

**Success Criteria**:
- JOIN latency < 100ms
- MOVE notification < 50ms
- Neighbor discovery via NN (no Voronoi calculation)
- NC (Neighbor Consistency) > 0.9

### Phase 3: Ghost Layer Cross-Server Sync (1.5 weeks)

**Bead**: Luciferase-bgt (reuse from v3.0)

**Deliverables**:
1. **GhostChannel**: Batched sync at bucket boundaries
2. **Same-Server Optimization**: Skip ghosts for co-located bubbles
3. **Ghost TTL**: 500ms expiration
4. **Memory Limits**: 1000 ghosts per neighbor

**Integration**:
- VON neighbors → ghost sync targets
- Different servers → ghosts needed
- Same server → shared memory (no ghosts)

**Success Criteria** (reuse from Phase 3 v3.0):
- Ghost appears at neighbor within 1 bucket (100ms)
- NC > 0.9 in normal operation
- Memory bounded (1000 ghost limit)
- TTL enforced (500ms expiration)

### Phase 4: Spatial Tumbler Load Balancing (1.5 weeks)

**Bead**: Luciferase-4fm (adapted from v3.0)

**Deliverables**:
1. **TumblerRegion**: Symbolic spatial organization for locality
2. **Power of 2 Choices**: Classic load balancing
3. **Migration Protocol**: Bubble reassignment
4. **Cooldown**: Prevent oscillation

**Key Difference from v3.0**: Tumbler is NOT ownership - just spatial locality hint for server selection

**Success Criteria**:
- Load imbalance < 20% after stabilization
- Migration latency < 1 second
- No oscillation (cooldown prevents ping-pong)
- Utilization-based selection

### Phase 5: Observability & Tuning (0.5 weeks)

**Bead**: Luciferase-y4t (reuse from v3.0)

**Deliverables**:
- Animator utilization metrics (CPU %)
- VON neighbor count tracking
- Ghost sync latency
- Tetrahedral bounds visualization
- Configuration externalization

---

## Capacity Model

**Server**: 8 CPU cores = 8 animator threads = 8 bubbles max
**Bubble**: Variable entities (100-5000), capped by single-thread frame budget (10ms)
**Cluster**: 10 servers × 8 bubbles = 80 bubbles = 160k-400k entities total

**Bubble Position**: Independent of server assignment (no spatial ownership)
**Migration**: Only for load balancing, NOT spatial movement

---

## Key Architectural Advantages

### 1. No Replicated Forest

**v3.0**: Each server maintains full forest replica (1-2s propagation delay via gossip)
**v4.0**: VON overlay provides distributed spatial index (< 100ms neighbor discovery)

**Savings**:
- No gossip broadcast overhead
- No eventually consistent synchronization
- No forest replication storage
- Faster discovery (P2P vs broadcast)

### 2. Tetrahedral Bounding

**AABB Approach**:
- Cartesian bounds require conversion to/from RDGCS
- Loses geometric accuracy (tetrahedral → cubic approximation)
- Extra containment checks

**Tetrahedral Approach**:
- Native Tetree coordinate system
- Perfect containment (barycentric coordinates)
- Adaptive subdivision aligns with Tetree structure
- No coordinate conversion overhead

### 3. Simplified Architecture

**v3.0**: 5 layers (Ghost, Forest, VON, Bubble, Load Balancing)
**v4.0**: 3 layers (VON, Bubble, Ghost) + optional Load Balancing

**Complexity Reduction**:
- 8.5 weeks → 6-7 weeks (-1.5 to -2.5 weeks)
- Fewer components to test/maintain
- Clearer responsibility boundaries

---

## Success Criteria

| Criterion | Measurement | Target | Phase |
|-----------|-------------|--------|-------|
| VON JOIN | Latency | < 100ms | Phase 2 |
| Neighbor Discovery | NC metric | > 0.9 | Phase 2 |
| Ghost Sync | Latency | < 100ms | Phase 3 |
| Tetrahedral Containment | Accuracy | 100% | Phase 1 |
| Frame Budget | Per-bubble | < 10ms | Phase 1 |
| Load Imbalance | Cluster-wide | < 20% | Phase 4 |
| Migration | End-to-end | < 1s | Phase 4 |
| Tests | Coverage | 325 tests | Phase 5 |

---

## Related Documents

### ChromaDB References
- `research::von::core-architecture`: VON boundary neighbors
- `research::von::protocols`: JOIN/MOVE/LEAVE
- `research::von::neighbor-discovery`: Watchmen pattern
- `design::esvt::efficient-sparse-tetrahedral-volumes-architecture`: Tetrahedral geometry
- `tetrahedral_sfc_core_innovation`: TM-index and S0-S5 subdivision
- `critical-geometry-cube-vs-tet`: Cube vs tetrahedron centroid calculations

### Memory Bank
- `Luciferase_active/distributed-animation-v4.md`: Implementation tracking

### Architecture Documents (Superseded)
- ❌ `simulation/doc/LOAD_BALANCING_ARCHITECTURE.md`: v2.0, spatial ownership (wrong)
- ❌ `simulation/doc/DISTRIBUTED_ANIMATION_ARCHITECTURE.md`: v3.0, replicated forest (unnecessary)

### File Locations
- **VON**: `luciferase/von/` (copied from ~/git/Thoth)
- **Fireflies**: `luciferase/fireflies/` (copied from ~/git/Delos/fireflies)
- **Tetrahedral Coords**: `portal/src/main/java/.../portal/Tetrahedral.java`
- **Bubble**: `simulation/src/main/java/.../simulation/Bubble.java`

---

## Critical Warnings

### 1. AABB Trap

**DO NOT** use axis-aligned bounding boxes for Tetree bubbles:
```java
// ❌ WRONG - forces coordinate conversion
class BubbleBounds {
    float minX, maxX, minY, maxY, minZ, maxZ;
}
```

**USE** tetrahedral coordinates:
```java
// ✅ CORRECT - native Tetree representation
class BubbleBounds {
    TetreeKey rootKey;      // S0-S5 + level
    Tuple3i rdgMin, rdgMax; // RDGCS coordinates
}
```

**Rationale**: "The NATURAL model for the Tetree spatial index is the asymmetric tetrahedral cell" (user quote)

### 2. Centroid Calculation

**Cube**: `origin + cellSize / 2`
**Tetrahedron**: `(v0 + v1 + v2 + v3) / 4`

**NEVER** use cube center formula for tetrahedron bounds. Always use `tet.coordinates()` to get vertices, then average.

### 3. VON vs Forest

**VON IS the distributed spatial index** - do not create separate Replicated Forest. The overlay network provides spatial awareness through VON neighbors.

---

## Implementation Timeline

```
Phase 0: VON + Fireflies (1 week)         → Week 1
Phase 1: Bubble + Tetrahedral (2 weeks)   → Week 2-3
Phase 2: VON Discovery (2 weeks)          → Week 4-5
Phase 3: Ghost Layer (1.5 weeks)          → Week 5.5-7
Phase 4: Load Balancing (1.5 weeks)       → Week 7-8.5
Phase 5: Observability (0.5 weeks)        → Week 9

Total: 6-7 weeks (optimistic) vs 8.5 weeks (v3.0)
```

**Savings**: 1.5-2.5 weeks due to elimination of Replicated Forest layer

---

**Status**: Architecture v4.0 design complete, ready for implementation planning
**Epic**: Luciferase-8oe (new epic, replaces Luciferase-3xw)
**Timeline**: 6-7 weeks (down from 8.5 weeks in v3.0)
