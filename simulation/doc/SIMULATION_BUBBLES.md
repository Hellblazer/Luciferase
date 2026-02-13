# Simulation Bubbles: Mobile VON Architecture

**Status**: Current
**Architecture**: Mobile bubbles with VON-based neighbor discovery

---

## Overview

Simulation bubbles are **mobile spatial regions** that track position, bounds, and VON neighbor relationships. Bubbles move to optimize entity distribution and coordinate via MOVE protocol.

**Bubble = mobile spatial region with P2P neighbor coordination.**

### Responsibilities

Each bubble knows:
- **Local entities**: Entities spatially contained within its bounds
- **Ghost entities**: Entities in neighboring bubbles near shared boundaries
- **VON neighbors**: Adjacent bubbles for coordination and ghost synchronization
- **Position and bounds**: Current spatial region it manages

---

## Architecture

```text
Layer 4: BUBBLE COORDINATION
  Bubble, Manager, VON neighbor discovery
         | uses
Layer 3: GHOST LAYER
  GhostStateManager, dead reckoning, boundary synchronization
         | uses
Layer 2: VON PROTOCOL
  MoveProtocol, VONDiscoveryProtocol, AOI management
         | uses
Layer 1: SPATIAL INDEX
  Tetree, k-NN queries, spatial containment
         | uses
Layer 0: MEMBERSHIP
  Fireflies view tracking, Byzantine consensus
```

---

## Mobile Bubble Model

### Core Properties

**Location**: `von/Bubble.java` (713 lines)

```java
public class Bubble extends EnhancedBubble implements Node {
    private final Transport transport;  // P2P communication
    private final Map<UUID, NeighborState> neighborStates;  // Neighbor metadata
    private final Set<UUID> introducedTo;  // Introduction tracking
    private volatile ClockContext clockContext;  // Deterministic time

    record NeighborState(UUID nodeId, Point3D position, BubbleBounds bounds, long lastUpdateMs)
}
```

**Key Attributes**:
- **Position**: Point3D tracking bubble center
- **Bounds**: BubbleBounds (min/max) defining spatial region
- **Neighbors**: Set<UUID> of VON-adjacent bubbles
- **Transport**: P2P channel for direct bubble-to-bubble communication
- **Clock**: Injected Clock interface for deterministic testing

### Bubble Lifecycle

```
1. CREATE
   └─ Register with Manager, initialize transport
        ↓
2. JOIN
   └─ Contact entry point, receive neighbor list
   └─ Send JOIN requests to all neighbors
   └─ Establish ghost synchronization
        ↓
3. ACTIVE
   └─ Process entities, handle messages
   └─ Broadcast MOVE when position changes
   └─ Maintain VON neighbor relationships
        ↓
4. LEAVE
   └─ Broadcast LEAVE to all neighbors
   └─ Stop ghost synchronization
   └─ Unregister transport
```

---

## VON Coordination

### Neighbor Discovery

**Primary Mechanism**: Ghost-based discovery

```java
// Bubble discovers neighbors via ghost arrivals
public void onGhostBatchReceived(UUID fromBubbleId) {
    if (!neighbors.contains(fromBubbleId)) {
        addNeighbor(fromBubbleId);  // Discover via ghost
        ghostManager.onVONNeighborAdded(fromBubbleId);  // Enable sync
    }
}
```

**Secondary Mechanism**: Spatial k-NN queries

```java
// MoveProtocol discovers neighbors via spatial index (k=10)
List<Node> candidates = spatialIndex.findKNearest(position, 10);
for (Node candidate : candidates) {
    if (isInAOI(position, candidate.position())) {
        addNeighbor(candidate.id());
    }
}
```

**Naming Note**: "VON" (Voronoi Overlay Network) is a naming legacy. The implementation uses **k-NN spatial index** (k=10), NOT Voronoi diagrams. This avoids expensive Voronoi cell recomputation on every bubble movement.

### JOIN Protocol

**Entry Point Selection**: First available bubble or designated acceptor.

**Flow**:
```
1. Bubble sends JoinRequest(id, position, bounds) to acceptor
2. Acceptor responds with JoinResponse(neighborList)
3. Bubble contacts each neighbor via JoinRequest
4. Neighbors add bubble to their neighbor sets
5. Ghost synchronization establishes boundary coordination
```

**Retry Policy**: Exponential backoff (50ms, 100ms, 200ms, 400ms, 800ms) with compensation tracking.

**Idempotency**: Introduction tracking prevents duplicate JOIN processing.

### MOVE Protocol

**Location**: `von/MoveProtocol.java` (175 lines)

**6-Step Flow**:
```
1. Update position in spatial index
2. Notify all current neighbors (broadcast MOVE message)
3. Discover new neighbors via k-NN (k=10)
4. Add new neighbors within AOI radius
5. Drop neighbors outside AOI + buffer (maxDistance = aoiRadius + 10.0f)
6. Emit MOVE event for observers
```

**AOI Management**:
- **AOI Radius**: Configurable based on bubble bounds
- **Buffer Zone**: 10.0f units beyond AOI for hysteresis
- **Neighbor Pruning**: Drop neighbors when distance > AOI + buffer

**When Triggered**: External caller (Manager or application) invokes `move()` when bubble position changes significantly.

### LEAVE Protocol

**Graceful Shutdown**:
```
1. Broadcast LeaveNotification to all neighbors
2. Neighbors remove bubble from their neighbor sets
3. Ghost layer cleans up ghost entities
4. Transport registry unregisters bubble
5. LifecycleCoordinator stops bubble processing
```

**Idempotency**: Protected by AtomicBoolean to prevent duplicate notifications.

---

## Manager Coordination

**Location**: `von/Manager.java` (502 lines)

### Responsibilities

- Create and register bubbles with P2P transport
- Coordinate JOIN (entry point selection)
- Coordinate MOVE (position broadcast)
- Coordinate LEAVE (graceful shutdown)
- Calculate NC (neighbor consistency) metrics
- Forward bubble events to application

### Lifecycle Management

```java
public Bubble createBubble() {
    var transport = transportRegistry.register(id);  // P2P transport
    var bubble = new Bubble(id, spatialLevel, targetFrameMs, transport);
    bubble.setClock(clock);  // Propagate deterministic clock
    bubble.addEventListener(this::dispatchEvent);  // Event forwarding
    coordinator.registerAndStart(adapter);  // Lifecycle coordination
    bubbles.put(id, bubble);
    return bubble;
}
```

**Clock Propagation**: Manager sets clock on all bubbles for deterministic testing.

### Neighbor Consistency (NC)

**Metric**: Measures how well bubble knows its actual neighbors.

```java
public float calculateNC(Bubble bubble) {
    int knownNeighbors = bubble.neighbors().size();
    int actualNeighbors = countBubblesWithinAOI(bubble);
    return (float) knownNeighbors / actualNeighbors;  // Range: 0.0 to 1.0
}
```

**Target**: NC ≥ 0.8 indicates good neighbor awareness.

---

## Entity Migration

### Two-Phase Commit (2PC)

**Location**: `distributed/migration/CrossProcessMigration.java` (810 lines)

Entity migration uses PrimeMover @Entity state machine for non-blocking 2PC execution.

**State Transitions**:
```
ACQUIRING_LOCK → PREPARE → COMMIT → SUCCESS
                         ↓ (on failure)
                       ABORT → ROLLBACK_COMPLETE
```

**Phase Flow**:
```
Phase 1: PREPARE
  └─ Remove entity from source bubble (100ms timeout)
       ↓ success
Phase 2: COMMIT
  └─ Add entity to destination bubble (100ms timeout)
       ↓ failure
Phase 3: ABORT
  └─ Restore entity to source (100ms timeout)
```

**Guarantees**:
- **C1**: Per-entity migration locks prevent concurrent migrations
- **C2**: Idempotency tokens provide exactly-once semantics
- **C3**: Rollback failures logged for manual recovery
- **D6B.8**: Remove-then-commit ordering eliminates duplicates

**Performance**:
- Latency: ~150ms per migration (3 phases × 100ms timeouts)
- Throughput: 100-200 migrations/sec per bubble (concurrent execution)
- Concurrent: Lock-free for different entities

### Byzantine Consensus

**Location**: `consensus/committee/ViewCommitteeConsensus.java`

Migration decisions require committee approval before 2PC execution.

**Flow**:
```
1. Create MigrationProposal with current Fireflies view ID
2. ViewCommitteeSelector deterministically selects BFT committee
3. Committee votes ACCEPT/REJECT based on validation
4. Quorum (t+1 votes) required for approval
5. View ID verified before execution (prevents cross-view races)
```

**Byzantine Tolerance**:
- t=0 (2-3 nodes): Crash tolerance only
- t=1 (4-7 nodes): Tolerates 1 Byzantine failure
- t=2 (8+ nodes): Tolerates 2 Byzantine failures

---

## Ghost Layer

**Location**: `ghost/GhostStateManager.java` (499 lines)

### Ghost Zones

Entities near bubble boundaries are replicated to neighboring bubbles as "ghost entities" for smooth handoff.

**Purpose**: Sub-frame boundary coherence during entity movement.

**Synchronization**:
- Frequency: 10ms (sub-frame for 60+ FPS animation)
- Protocol: Direct P2P updates to neighbor bubbles
- Lifecycle: Entity enters zone → ghost created, exits → ghost removed

### Ghost Entity States

```
LOCAL ENTITY → [enters ghost zone] → GHOST ENTITY
                                            ↓
                                      [migrates or exits]
                                            ↓
                                   MIGRATED / REMOVED
```

**State Machine**: CREATE → UPDATE → STALE → EXPIRED

### Dead Reckoning

**Purpose**: Extrapolate ghost position between network updates using velocity.

**Mechanism**:
```java
public Point3f getGhostPosition(StringEntityID entityId, long currentTime) {
    var predictedPosition = deadReckoning.predict(adapter, currentTime);
    return clampToBounds(predictedPosition);  // Clamp to bubble bounds
}
```

**Properties**:
- Store velocity with each ghost update
- Predict position: `position + velocity × deltaTime`
- Clamp to bubble bounds (prevent extrapolation outside)
- Staleness threshold: 500ms (ghosts older than this are culled)

### VON Integration

**Location**: `ghost/GhostSyncVONIntegration.java`

**Ghost-Based Neighbor Discovery**:
```java
public void onGhostBatchReceived(UUID fromBubbleId) {
    if (!vonNode.neighbors().contains(fromBubbleId)) {
        vonNode.addNeighbor(fromBubbleId);  // Discover via ghost arrival
        ghostManager.onVONNeighborAdded(fromBubbleId);  // Enable bidirectional sync
    }
}
```

**Core Thesis**: "Ghost layer + VON protocols = fully distributed animation with no global state"

**Discovery Pattern**:
1. Bubble receives ghost from unknown sender
2. Add sender as new VON neighbor
3. Register with ghost manager for bidirectional sync

---

## Performance Characteristics

### Latency Targets

| Operation | Target | Typical |
|-----------|--------|---------|
| Ghost synchronization | < 10ms | ~5ms |
| Entity migration (2PC) | < 300ms | ~150ms |
| Consensus approval | < 100ms | ~50ms |
| MOVE broadcast | < 20ms | ~10ms |
| k-NN neighbor discovery | < 10ms | ~5ms |

### Throughput Targets

| Metric | Target | Typical |
|--------|--------|---------|
| Migrations/sec per bubble | 100+ | ~150 |
| Ghost updates/sec | 1000+ | ~2000 |
| Entities per bubble | 5K-10K | ~5000 |
| VON neighbors per bubble | 10-20 | ~15 |

### Scalability

**Horizontal Scaling**:
- Add bubbles → more spatial coverage
- Ghost-based discovery → no global registry
- VON neighbor management → O(log n) overhead
- Linear scalability to network bandwidth limits

**Vertical Scaling**:
- More entities per bubble (spatial index limits)
- Higher animation frame rate (CPU limits)
- Larger AOI radius (more neighbors, more ghost sync)

---

## Implementation Status

### Completed

- [x] Mobile bubble architecture (Bubble.java)
- [x] VON neighbor discovery (MoveProtocol, VONDiscoveryProtocol)
- [x] P2P transport (LocalServerTransport.Registry)
- [x] JOIN/MOVE/LEAVE protocols
- [x] 2PC entity migration (CrossProcessMigration)
- [x] Byzantine consensus (ViewCommitteeConsensus)
- [x] Ghost layer with dead reckoning (GhostStateManager)
- [x] Ghost-based neighbor discovery (GhostSyncVONIntegration)
- [x] Neighbor consistency metrics (Manager.calculateNC)
- [x] Deterministic testing (Clock interface, TestClock)

### Testing

**22 test files** validate VON functionality:
- MOVE protocol message handling
- Bubble position updates
- Neighbor relationship management
- Load balancing scenarios
- Entity migration during moves
- Ghost synchronization
- Byzantine consensus integration

---

## Configuration

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `PHASE_TIMEOUT_MS` | 100 | Per-phase migration timeout |
| `TOTAL_TIMEOUT_MS` | 300 | Total migration timeout |
| `GHOST_SYNC_INTERVAL_MS` | 10 | Ghost synchronization frequency |
| `GHOST_TTL_MS` | 500 | Ghost staleness threshold |
| `AOI_BUFFER` | 10.0f | AOI hysteresis buffer (units) |
| `K_NN` | 10 | k-nearest neighbors for discovery |
| `JOIN_RETRY_MAX` | 5 | Max JOIN retry attempts |

### Tuning Guidelines

**Low Latency** (< 100ms target):
- Reduce PHASE_TIMEOUT_MS to 50ms
- Reduce GHOST_SYNC_INTERVAL_MS to 5ms
- Increase network bandwidth

**High Throughput** (> 500 migrations/sec):
- Increase migration thread pool
- Reduce AOI radius (fewer neighbors)
- Increase K_NN to 20 (better discovery)

**High Reliability** (fault tolerance):
- Increase Byzantine tolerance (larger committees)
- Reduce GHOST_TTL_MS to 300ms (faster staleness detection)
- Enable comprehensive metrics tracking

---

## Related Documentation

### Architecture
- [ARCHITECTURE_DISTRIBUTED.md](ARCHITECTURE_DISTRIBUTED.md) - Complete distributed architecture
- [VON_OVERLAY_ASSESSMENT.md](VON_OVERLAY_ASSESSMENT.md) - VON architecture and MOVE protocol
- [GHOST_LAYER_CONSOLIDATION.md](GHOST_LAYER_CONSOLIDATION.md) - Ghost layer implementation

### Implementation
- [H3_DETERMINISM_EPIC.md](H3_DETERMINISM_EPIC.md) - Deterministic testing with Clock interface
- [TESTING_PATTERNS.md](TESTING_PATTERNS.md) - Testing best practices

### ADRs
- [ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md](ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md) - Migration and Consensus design

---

**Document Version**: 3.0 (Mobile bubble architecture)
**Last Updated**: 2026-02-13
**Status**: Current
