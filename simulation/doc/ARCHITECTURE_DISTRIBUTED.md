# Distributed Architecture - Luciferase Simulation

**Status**: Current
**Scope**: Multi-bubble distributed simulation with VON coordination and Byzantine consensus

---

## Overview

Luciferase simulation module implements massively distributed 3D animation using mobile bubbles with VON-based neighbor discovery. Built on PrimeMover discrete event simulation with Tetree spatial indexing.

**Key Architectural Principles**:
- Mobile bubbles track position and neighbor relationships
- VON MOVE protocol coordinates bubble position updates
- Byzantine consensus for entity migration decisions
- Causal consistency within bubbles
- Eventual consistency across bubbles via ghost layer
- 2PC entity migration protocol with exactly-once semantics

---

## Table of Contents

1. [High-Level Architecture](#high-level-architecture)
2. [Mobile Bubble Architecture](#mobile-bubble-architecture)
3. [VON Coordination](#von-coordination)
4. [Entity Migration Protocol](#entity-migration-protocol)
5. [Consensus Layer](#consensus-layer)
6. [Ghost Layer](#ghost-layer)
7. [Network Architecture](#network-architecture)
8. [Time Management](#time-management)
9. [Performance Characteristics](#performance-characteristics)

---

## High-Level Architecture

### Component Stack

```
┌─────────────────────────────────────────────────────┐
│         Application Layer                           │
│  (VolumeAnimator, Entity factories, animation loop) │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Mobile Bubble Layer                         │
│  (Bubble, Manager, VON neighbor discovery)         │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         VON Coordination Layer                      │
│  (MoveProtocol, GhostSyncVONIntegration, AOI)      │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Consensus Layer (BFT)                       │
│  (ViewCommitteeConsensus, CommitteeVotingProtocol) │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Migration & Coordination Layer              │
│  (CrossProcessMigration, 2PC state machine)        │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Network & Communication Layer               │
│  (P2P Transport, LocalServerTransport, gRPC)       │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Ghost Layer                                 │
│  (GhostStateManager, dead reckoning, ghost sync)   │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Spatial Index Layer                         │
│  (Tetree, Forest, k-NN neighbor discovery)         │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Fireflies Membership Layer                  │
│  (Virtual Synchrony, View Tracking, BFT)           │
└─────────────────────────────────────────────────────┘
```

### Key Components

| Component | Responsibility | Location |
|-----------|----------------|----------|
| **Bubble** | VON-enabled mobile bubble with P2P transport | `von/Bubble.java` |
| **Manager** | Bubble lifecycle coordination (JOIN/MOVE/LEAVE) | `von/Manager.java` |
| **MoveProtocol** | AOI-based neighbor management with k-NN discovery | `von/MoveProtocol.java` |
| **GhostSyncVONIntegration** | VON-Ghost coordination and neighbor discovery | `ghost/GhostSyncVONIntegration.java` |
| **ViewCommitteeConsensus** | Byzantine consensus for entity migrations | `consensus/committee/` |
| **CrossProcessMigration** | 2PC entity migration with PrimeMover @Entity | `distributed/migration/` |
| **GhostStateManager** | Ghost lifecycle with dead reckoning | `ghost/GhostStateManager.java` |
| **BubbleGhostCoordinator** | Ghost channel + state manager integration | `ghost/BubbleGhostCoordinator.java` |
| **FirefliesViewMonitor** | Membership view tracking for BFT | `causality/` |

---

## Mobile Bubble Architecture

### Concept

A **bubble** is a mobile spatial region that tracks its position, bounds, and VON neighbor relationships. Bubbles move to optimize entity distribution and load balance.

**Location**: `von/Bubble.java` (713 lines)

**Core Properties**:
- Position tracking (Point3D)
- Bounds tracking (BubbleBounds min/max)
- VON neighbor relationships (Set<UUID>)
- P2P transport for direct communication
- Event-driven message handling

### Bubble Responsibilities

```java
// Bubble.java implements Node interface for VON integration
public class Bubble extends EnhancedBubble implements Node {
    private final Transport transport;  // P2P communication
    private final Map<UUID, NeighborState> neighborStates;  // Neighbor metadata
    private final Set<UUID> introducedTo;  // Introduction tracking
    private volatile ClockContext clockContext;  // Deterministic time

    record NeighborState(UUID nodeId, Point3D position, BubbleBounds bounds, long lastUpdateMs)
}
```

**Key Operations**:
- `initiateJoin(UUID acceptorId)`: Contact entry point, receive neighbor list
- `broadcastMove()`: Notify all neighbors of position update
- `broadcastLeave()`: Graceful shutdown notification
- `handleMessage(Message msg)`: Dispatch JOIN/MOVE/LEAVE/GHOST_SYNC messages
- `addNeighbor(UUID id)` / `removeNeighbor(UUID id)`: Maintain neighbor set

### Neighbor Discovery

**Primary Mechanism**: Ghost-based discovery
```java
// When bubble receives ghost from unknown sender, add as neighbor
public void onGhostBatchReceived(UUID fromBubbleId) {
    if (!neighbors.contains(fromBubbleId)) {
        addNeighbor(fromBubbleId);  // Discover via ghost arrival
        ghostManager.onVONNeighborAdded(fromBubbleId);  // Enable bidirectional sync
    }
}
```

**Secondary Mechanism**: Spatial k-NN queries (k=10)
```java
// MoveProtocol discovers neighbors via spatial index
List<Node> candidates = spatialIndex.findKNearest(position, 10);
for (Node candidate : candidates) {
    if (isInAOI(position, candidate.position())) {
        addNeighbor(candidate.id());
    }
}
```

**No Voronoi**: System uses k-NN spatial index, not Voronoi cell calculations.

### JOIN Protocol

**Entry point selection**: First available bubble or designated acceptor

**Flow**:
1. Bubble sends `JoinRequest(id, position, bounds)` to acceptor
2. Acceptor responds with `JoinResponse(neighborList)`
3. Bubble contacts each neighbor via `JoinRequest`
4. Neighbors add bubble to their neighbor sets
5. Ghost synchronization establishes boundary coordination

**Retry Policy**: Exponential backoff (50ms, 100ms, 200ms, 400ms, 800ms) with compensation tracking on failure.

### MOVE Protocol

**Location**: `von/MoveProtocol.java` (175 lines)

**When triggered**: External caller (Manager or application) invokes `move()` when bubble position changes.

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
- **AOI Radius**: Configurable (default based on bubble bounds)
- **Buffer Zone**: 10.0f units beyond AOI for hysteresis
- **Neighbor Pruning**: Drop neighbors when distance > AOI + buffer

### LEAVE Protocol

**Graceful Shutdown**:
1. Broadcast `LeaveNotification` to all neighbors
2. Neighbors remove bubble from their neighbor sets
3. Ghost layer cleans up ghost entities
4. Transport registry unregisters bubble
5. LifecycleCoordinator stops bubble processing

**Idempotency**: `broadcastLeave()` protected by AtomicBoolean to prevent duplicate notifications.

### Neighbor Consistency (NC)

**Metric**: Measures how well bubble knows its actual neighbors

```java
// Manager.java calculates NC
public float calculateNC(Bubble bubble) {
    int knownNeighbors = bubble.neighbors().size();
    int actualNeighbors = countBubblesWithinAOI(bubble);
    return (float) knownNeighbors / actualNeighbors;  // Range: 0.0 to 1.0
}
```

**Target**: NC ≥ 0.8 indicates good neighbor awareness.

---

## VON Coordination

### Manager

**Location**: `von/Manager.java` (502 lines)

**Responsibilities**:
- Create and register bubbles with P2P transport
- Coordinate JOIN (entry point selection)
- Coordinate MOVE (position broadcast)
- Coordinate LEAVE (graceful shutdown via LifecycleCoordinator)
- Calculate NC (neighbor consistency) metrics
- Forward bubble events to application

**Lifecycle Coordination**:
```java
public Bubble createBubble() {
    var transport = transportRegistry.register(id);  // P2P transport
    var bubble = new Bubble(id, spatialLevel, targetFrameMs, transport);
    bubble.setClock(clock);  // Propagate deterministic clock
    bubble.addEventListener(this::dispatchEvent);  // Event forwarding
    coordinator.registerAndStart(adapter);  // Lifecycle management
    bubbles.put(id, bubble);
    return bubble;
}
```

**Clock Propagation**: Manager sets clock on all bubbles for deterministic testing.

### Spatial Index Integration

**k-NN Discovery**: Uses Tetree spatial index for neighbor queries
```java
// Find k=10 nearest bubbles to a position
List<Node> nearestBubbles = spatialIndex.findKNearest(position, 10);
```

**Boundary Detection**: Uses spatial queries to identify bubbles within AOI.

**No Global Registry**: Bubbles discover neighbors via ghost arrivals and spatial queries (fully distributed).

**Naming Note**: "VON" (Voronoi Overlay Network) is a naming legacy. The implementation uses **k-NN spatial index** (k=10), NOT Voronoi diagrams. This avoids expensive Voronoi cell recomputation on every bubble movement.

---

## Entity Migration Protocol

### Overview

**Location**: `distributed/migration/CrossProcessMigration.java` (810 lines)

CrossProcessMigration implements Two-Phase Commit (2PC) using PrimeMover @Entity state machine for non-blocking event-driven execution.

### Architecture

```java
@Entity  // PrimeMover transforms this into non-blocking state machine
public static class CrossProcessMigrationEntity {
    private enum State {
        ACQUIRING_LOCK, PREPARE, COMMIT, ABORT
    }

    private volatile State currentState = State.ACQUIRING_LOCK;
    private final ReentrantLock entityLock;  // C1: Per-entity migration lock
    private final EntitySnapshot snapshot;  // Captured entity state
}
```

### 2PC State Machine

**State Transitions**:
```
ACQUIRING_LOCK → PREPARE → COMMIT → SUCCESS
                         ↓ (on failure)
                       ABORT → ROLLBACK_COMPLETE
```

### Migration Flow

**Entry Point**:
```java
public CompletableFuture<MigrationResult> migrate(
    String entityId,
    BubbleReference source,
    BubbleReference dest
) {
    var lock = getLockForEntity(entityId);  // C1: Per-entity lock
    var entity = new CrossProcessMigrationEntity(...);
    activeEntities.put(entityId, entity);  // Strong reference tracking
    entity.startMigration();  // Start PrimeMover state machine
    return entity.getFuture();
}
```

**Phase 1: PREPARE** (Remove from source)
```java
private void prepare() {
    snapshot = createEntitySnapshot(entityId, source, timestamp);
    boolean removed = source.removeEntity(entityId);
    if (!removed) {
        failAndUnlock("PREPARE_FAILED");
        return;
    }
    currentState = State.COMMIT;
    commit();  // Transition to COMMIT
}
```

**Phase 2: COMMIT** (Add to destination)
```java
private void commit() {
    boolean added = dest.addEntity(snapshot);
    if (!added) {
        abortReason = "COMMIT_FAILED";
        currentState = State.ABORT;
        abort();  // Rollback to source
        return;
    }
    succeedAndUnlock(totalLatency);  // Success
}
```

**Phase 3: ABORT** (Rollback on failure)
```java
private void abort() {
    boolean restored = source.addEntity(snapshot);
    if (!restored) {
        log.error("ABORT/Rollback FAILED - CRITICAL: Manual intervention required");
        recordRollbackFailure.run();
        recordOrphanedEntity.accept(entityId);  // Track for recovery
    }
    failAndUnlock(abortReason);
}
```

### Key Guarantees

**C1: Per-Entity Migration Locks**
- `Map<String, ReentrantLock>` prevents concurrent migrations of same entity
- Lock timeout: 50ms (fast fail on contention)
- WeakReference-based lock cache prevents memory leaks

**C2: Idempotency Tokens**
- UUID-based tokens for exactly-once semantics
- `IdempotencyStore` tracks processed tokens (30s expiration)
- Duplicate requests return cached result

**C3: Rollback Failure Handling**
- If ABORT fails (source unreachable), log error
- Track orphaned entities in metrics
- Manual intervention required for recovery

**D6B.8: Remove-Then-Commit Ordering**
- Always remove from source BEFORE adding to destination
- Eliminates entity duplication
- Failure recovery restores to source (no duplicates)

### Cleanup Safety

**Strong Reference Tracking**:
```java
// activeEntities map keeps strong references during migration
private final Map<String, CrossProcessMigrationEntity> activeEntities;

future.whenComplete((result, exception) -> {
    activeEntities.remove(entityId);  // Cleanup after completion
});
```

**Periodic Orphan Cleanup**: Migrations >5min timeout are logged and cleaned up.

### Timeout Handling

**Per-Phase Timeout**: 100ms (configurable via `MigrationConfig`)
- PREPARE: 100ms to remove from source
- COMMIT: 100ms to add to destination
- ABORT: 100ms to restore to source

**Total Timeout**: 300ms (3 phases × 100ms)

**Timeout Detection**:
```java
private boolean isTimedOut(long startTime) {
    var elapsed = clock.currentTimeMillis() - startTime;
    return elapsed > totalTimeoutMs;
}
```

### Idempotency Store

**Location**: `distributed/migration/IdempotencyStore.java`

```java
public class IdempotencyStore {
    private volatile Clock clock = Clock.system();
    private static final long TOKEN_EXPIRATION_MS = 30_000;

    private final ConcurrentHashMap<UUID, TokenEntry> tokens;

    public boolean isProcessed(UUID token) {
        var entry = tokens.get(token);
        if (entry == null) return false;

        var age = clock.currentTimeMillis() - entry.timestamp();
        return age < TOKEN_EXPIRATION_MS;
    }
}
```

**Token Expiration**: 30 seconds (balances memory vs duplicate detection window)

### Migration Metrics

**Location**: `distributed/migration/MigrationMetrics.java`

**Tracked Metrics**:
- Total migrations attempted
- Successful migrations
- Failed migrations (by phase: PREPARE/COMMIT)
- Rollbacks executed
- Rollback failures (C3 violations)
- Orphaned entities (requiring manual recovery)
- Average migration duration
- P50/P95/P99 latencies

---

## Consensus Layer

### Overview

**Location**: `consensus/committee/ViewCommitteeConsensus.java`

Byzantine fault-tolerant (BFT) agreement on entity migration decisions before 2PC execution. Prevents conflicting migrations that could violate entity conservation.

### Why Byzantine Tolerance?

**Problem Without Consensus**:
```
t1: Node A decides: Entity E migrates A → B
t2: Node C decides: Entity E migrates A → C  ← CONFLICT!
t3: 2PC executes both migrations
Result: E duplicated in B and C → CORRUPTION
```

**Byzantine Threats**:
1. Clock skew: Incorrect clocks → bad migration decisions
2. Network partition: Split-brain → conflicting ownership claims
3. Buggy nodes: Software bugs → incorrect proposals
4. Race conditions: Concurrent migrations of same entity

**Solution**: Committee-based BFT consensus before 2PC execution.

### Architecture

```java
public class ViewCommitteeConsensus {
    private FirefliesViewMonitor viewMonitor;  // Current view tracking
    private ViewCommitteeSelector committeeSelector;  // Deterministic committee selection
    private CommitteeVotingProtocol votingProtocol;  // Vote aggregation

    private final ConcurrentHashMap<UUID, ProposalTracking> pendingProposals;
}
```

### Migration Flow with Consensus

```
1. Spatial boundary detection → Entity crosses bubble boundary
2. Create MigrationProposal with current Fireflies view ID
3. Byzantine consensus → Committee votes ACCEPT/REJECT
4. If quorum approved → Execute 2PC migration
5. If rejected or view changed → Abort migration
```

### Committee Selection

**Deterministic from Fireflies View**:
```java
public Set<Member> selectCommittee(Digest viewId) {
    var context = firefliesView.getContext();
    var committee = context.bftSubset(viewId);  // O(log n) size
    return committee;
}
```

**Committee Size by Cluster**:
| Cluster Size | Byzantine Tolerance (t) | Quorum | Committee Size |
|--------------|-------------------------|--------|----------------|
| 2-3 nodes    | 0                       | 1      | 5              |
| 4-7 nodes    | 1                       | 2      | 7              |
| 8+ nodes     | 2                       | 3      | 9-11           |

### Voting Protocol

**MigrationProposal**:
```java
public record MigrationProposal(
    UUID proposalId,
    String entityId,
    UUID sourceBubbleId,
    UUID targetBubbleId,
    Digest viewId,  // CRITICAL: View synchrony check
    long timestamp
) {}
```

**Consensus Flow**:
```java
public CompletableFuture<Boolean> requestConsensus(MigrationProposal proposal) {
    var currentViewId = getCurrentViewId();

    // CRITICAL: Abort if proposal from old view
    if (!proposal.viewId().equals(currentViewId)) {
        return CompletableFuture.completedFuture(false);
    }

    return votingProtocol.requestVote(proposal)
        .thenApply(approved -> {
            // Final view check before 2PC
            if (!getCurrentViewId().equals(proposal.viewId())) {
                return false;  // View changed during voting
            }
            return approved;
        });
}
```

**Vote Decision Criteria**:
- Entity not already migrating (idempotency check)
- Destination has capacity (resource check)
- Source confirms ownership (state check)
- Proposal viewId matches current view (synchrony check)

### View Change Handling

**Automatic Proposal Rollback**:
```java
public void onViewChange(Digest newViewId) {
    pendingProposals.values().forEach(tracking -> {
        tracking.future().completeExceptionally(
            new IllegalStateException("View changed during consensus")
        );
    });
    pendingProposals.clear();  // All pending proposals aborted
}
```

**Virtual Synchrony Guarantee** (from Fireflies):
- All messages sent within stable view delivered to all live members
- View changes are atomic (no partial updates)
- Safe to abort pending proposals on view change

### Performance

**Consensus Latency**:
| Operation | Target | Typical |
|-----------|--------|---------|
| Committee selection | < 10ms | ~5ms |
| Vote broadcast | < 20ms | ~10ms |
| Quorum detection | < 50ms | ~25ms |
| Total consensus | < 100ms | ~50ms |

**Byzantine Tolerance**:
- t=0 (2-3 nodes): Crash tolerance only
- t=1 (4-7 nodes): Tolerates 1 Byzantine failure
- t=2 (8+ nodes): Tolerates 2 Byzantine failures

**Scalability**:
- Committee size: O(log n)
- Message complexity: O(log n)
- View changes: Automatic proposal rollback

---

## Ghost Layer

### Overview

**Location**: `ghost/GhostStateManager.java` (499 lines)

Ghost layer provides boundary synchronization between bubbles. Entities near bubble boundaries exist in multiple bubbles as "ghost entities" for smooth handoff.

### Architecture

```java
public class GhostStateManager {
    private volatile Clock clock = Clock.system();

    private final Map<StringEntityID, GhostState> ghostStates;  // Position + velocity
    private final DeadReckoningEstimator deadReckoning;  // Extrapolation
    private final GhostLifecycleStateMachine lifecycle;  // State transitions

    record GhostState(GhostEntity entity, Vector3f velocity)
}
```

### Ghost Lifecycle

**State Machine**: CREATE → UPDATE → STALE → EXPIRED

**Operations**:
```java
// Receive ghost update from network
public void updateGhost(UUID sourceBubbleId, EntityUpdateEvent event) {
    var existingState = ghostStates.get(entityId);
    if (existingState == null) {
        lifecycle.onCreate(entityId, sourceBubbleId, timestamp);
    }
    lifecycle.onUpdate(entityId, timestamp);

    var newState = new GhostState(ghostEntity, velocity);
    ghostStates.put(entityId, newState);

    deadReckoning.onAuthoritativeUpdate(adapter, position);
}

// Extrapolate position with dead reckoning
public Point3f getGhostPosition(StringEntityID entityId, long currentTime) {
    var predictedPosition = deadReckoning.predict(adapter, currentTime);
    return clampToBounds(predictedPosition);  // Clamp to bubble bounds
}

// Cull stale ghosts (TTL=500ms default)
public void tick(long currentTime) {
    var expiredCount = lifecycle.expireStaleGhosts(currentTime);
    for (var entityId : ghostStates.keySet()) {
        if (lifecycle.getState(entityId) == null) {
            removeGhost(entityId);  // Cleanup expired ghosts
        }
    }
}
```

### Dead Reckoning

**Purpose**: Extrapolate ghost position between network updates using velocity.

**Mechanism**:
1. Store ghost velocity with each update
2. Predict position: `position + velocity × deltaTime`
3. Clamp predicted position to bubble bounds (prevent extrapolation outside)

**Boundary Clamping**:
```java
private Point3f clampToBounds(Point3f position) {
    if (bounds.contains(position)) {
        return position;
    }
    // Convert to RDGCS, clamp to bounds, convert back to Cartesian
    var rdgPos = bounds.toRDG(position);
    var clampedRdg = new Point3i(
        Math.max(rdgMin.x, Math.min(rdgMax.x, rdgPos.x)),
        Math.max(rdgMin.y, Math.min(rdgMax.y, rdgPos.y)),
        Math.max(rdgMin.z, Math.min(rdgMax.z, rdgPos.z))
    );
    return bounds.toCartesian(clampedRdg);
}
```

### Ghost Synchronization

**Frequency**: Sub-frame rate (10ms interval for 60+ FPS animation)

**Protocol**:
1. Source bubble sends ghost updates to all VON neighbors
2. Receiving bubble updates ghost state via `GhostStateManager.updateGhost()`
3. Dead reckoning extrapolates position between updates
4. Stale ghosts (>500ms) automatically culled

### VON Integration

**Location**: `ghost/GhostSyncVONIntegration.java`

**Ghost-Based Neighbor Discovery**:
```java
public void onGhostBatchReceived(UUID fromBubbleId) {
    if (!vonNode.neighbors().contains(fromBubbleId)) {
        vonNode.addNeighbor(fromBubbleId);  // Discover via ghost arrival
        ghostManager.onVONNeighborAdded(fromBubbleId);  // Enable sync
    }
}
```

**Core Thesis**: "Ghost layer + VON protocols = fully distributed animation with no global state"

**Discovery Pattern**:
1. Bubble receives ghost from unknown sender
2. Add sender as new VON neighbor
3. Register with ghost manager for bidirectional sync

---

## Network Architecture

### P2P Transport

**Location**: `von/LocalServerTransport.java`

**Registry-Based Architecture**:
```java
public class LocalServerTransport {
    public static class Registry {
        private final Map<UUID, LocalServerTransport> transports;

        public LocalServerTransport register(UUID bubbleId) {
            var transport = new LocalServerTransport(bubbleId, this);
            transports.put(bubbleId, transport);
            return transport;
        }
    }
}
```

**Message Delivery**:
```java
public void sendToNeighbor(UUID neighborId, Message message) {
    var neighborTransport = registry.lookup(neighborId);
    if (neighborTransport != null) {
        neighborTransport.deliver(message);  // Direct local delivery
    }
}
```

**Properties**:
- In-process message passing (for local multi-bubble testing)
- Direct bubble-to-bubble communication
- Registry lookup for neighbor resolution
- Extensible to network transport (gRPC)

### LocalServerTransport.Registry Detailed Role

**Purpose**: Central registry for in-process P2P transport between bubbles.

**Core Responsibilities**:
1. **Bubble Registration**: Maps UUID → LocalServerTransport for each active bubble
2. **Transport Lifecycle**: Creates transport instances with shared executor service
3. **Message Routing**: Enables neighbor lookup for direct message delivery
4. **Resource Management**: Coordinates shutdown of all transports on cleanup

**Architecture Pattern**: Service Locator with dependency injection.

**Lifecycle**:
```java
// 1. Create shared registry
var registry = LocalServerTransport.Registry.create();

// 2. Register bubbles (called by Manager)
var transport1 = registry.register(bubbleId1);
var transport2 = registry.register(bubbleId2);

// 3. Bubbles communicate via registry lookup
transport1.sendToNeighbor(bubbleId2, message);  // Registry resolves bubbleId2 → transport2

// 4. Unregister on shutdown
registry.unregister(bubbleId1);
```

**Thread Safety**:
- Uses `ConcurrentHashMap<UUID, LocalServerTransport>` for concurrent registration/lookup
- Thread-safe operations: `register()`, `unregister()`, `lookup()`
- No external synchronization required

**Testing Benefits**:
- Zero network overhead (in-memory message passing)
- Deterministic delivery order with per-destination executors
- Failure injection support (partition, delay, exception)
- Supports multi-bubble integration tests (TestClusterBuilder pattern)

**Production Replacement**:
- Registry pattern unchanged in distributed deployment
- LocalServerTransport → gRPC-based SocketTransport
- UUID lookup → Fireflies membership + gRPC connection pool
- Same Transport interface, different implementation

### Message Protocol

**Message Types** (Java records):
- `JoinRequest(bubbleId, position, bounds, timestamp)`
- `JoinResponse(neighbors, timestamp)`
- `MoveNotification(bubbleId, position, bounds, timestamp)`
- `LeaveNotification(bubbleId, timestamp)`
- `GhostSyncBatch(ghosts, timestamp)`

**Timestamp Injection**:
- All messages use `MessageFactory.currentTimeMillis()` for timestamps
- Enables deterministic testing with TestClock
- Maintains protocol ordering semantics

### gRPC Integration

**Future**: Network transport layer for distributed deployment
- Protobuf message serialization
- Bidirectional streaming
- Connection pooling
- Retry with exponential backoff

---

## Time Management

### Clock Interface

**Location**: `distributed/integration/Clock.java`

```java
public interface Clock {
    long currentTimeMillis();  // Milliseconds since epoch

    default long nanoTime() {  // High-resolution time
        return System.nanoTime();
    }

    static Clock system() {  // Production: System.* wrapper
        return System::currentTimeMillis;
    }

    static Clock fixed(long fixedTime) {  // Testing: Fixed time
        return () -> fixedTime;
    }
}
```

### TestClock (Deterministic Testing)

**Location**: `distributed/integration/TestClock.java`

**Features**:
- Absolute mode: Returns exact set time (default)
- Relative mode: Adds offset to System.*
- Time advancement: `advance(long)`, `advanceNanos(long)`
- Thread-safe: AtomicLong-based state
- Dual time tracking: Millis + nanos with 1:1,000,000 ratio

**Usage**:
```java
var testClock = new TestClock();
testClock.setMillis(1000L);  // T=1000ms

// Inject into components
migration.setClock(testClock);
ghostManager.setClock(testClock);
bubble.setClock(testClock);

// Control time progression
testClock.advance(100);  // T=1100ms
```

### MessageFactory (Record Time Injection)

**Location**: `distributed/integration/MessageFactory.java`

**Pattern** (for Java records):
```java
public record MoveNotification(UUID bubbleId, Point3D position, BubbleBounds bounds, long timestamp) {
    public MoveNotification {
        timestamp = MessageFactory.currentTimeMillis();  // Injected from factory
    }
}

// In tests
MessageFactory.setClock(testClock);
var msg = new MoveNotification(id, pos, bounds, 0);  // timestamp from testClock
```

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

## Configuration

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `PHASE_TIMEOUT_MS` | 100 | Per-phase migration timeout |
| `TOTAL_TIMEOUT_MS` | 300 | Total migration timeout |
| `TOKEN_EXPIRATION_MS` | 30000 | Idempotency token lifetime |
| `GHOST_SYNC_INTERVAL_MS` | 10 | Ghost synchronization frequency |
| `GHOST_TTL_MS` | 500 | Ghost staleness threshold |
| `AOI_BUFFER` | 10.0f | AOI hysteresis buffer (units) |
| `K_NN` | 10 | k-nearest neighbors for discovery |
| `JOIN_RETRY_MAX` | 5 | Max JOIN retry attempts |

### Troubleshooting Guide: Common Migration Failures

**Problem**: Migration timeout (exceeds 300ms total)

**Symptoms**:
- `MigrationResult.TIMEOUT` returned
- Logs show "Migration exceeded total timeout" warnings
- Entity stuck in limbo (neither source nor destination)

**Diagnosis**:
```java
// Check migration metrics
var metrics = migration.getMetrics();
log.info("Phase breakdown: PREPARE={}ms, COMMIT={}ms, ABORT={}ms",
         metrics.avgPrepareLatency(), metrics.avgCommitLatency(), metrics.avgAbortLatency());
```

**Causes**:
1. Network latency spike (check P99 transport latency)
2. Lock contention (concurrent migrations of nearby entities)
3. Source/destination bubble overloaded (check CPU and entity count)
4. Byzantine consensus slow (check Fireflies view stability)

**Solutions**:
- Increase `TOTAL_TIMEOUT_MS` from 300ms to 500ms
- Reduce concurrent migration parallelism (lower thread pool)
- Increase per-phase timeout (`PHASE_TIMEOUT_MS` from 100ms to 150ms)
- Profile hotspots with JFR (Java Flight Recorder)

---

**Problem**: Migration PREPARE phase failure

**Symptoms**:
- Entity not removed from source bubble
- Migration aborts with "PREPARE_FAILED" reason
- Source bubble still owns entity after migration attempt

**Diagnosis**:
```java
// Check entity existence in source
if (!source.containsEntity(entityId)) {
    log.error("Entity {} not found in source bubble", entityId);
}
```

**Causes**:
1. Entity already migrated (idempotency failure)
2. Concurrent migration of same entity (lock timeout)
3. Entity removed by application before migration
4. Source bubble crashed during PREPARE

**Solutions**:
- Check idempotency store (30s expiration, may need tuning)
- Increase per-entity lock timeout (50ms default)
- Add application-level migration coordination
- Enable comprehensive logging (`log.debug` → `log.trace`)

---

**Problem**: Migration COMMIT phase failure (entity duplication risk)

**Symptoms**:
- Entity removed from source but not added to destination
- Migration enters ABORT phase for rollback
- Logs show "COMMIT_FAILED" with rollback attempt

**Diagnosis**:
```java
// Check destination capacity
if (dest.getEntityCount() >= dest.getCapacity()) {
    log.error("Destination bubble {} at capacity", dest.id());
}
```

**Causes**:
1. Destination bubble at capacity (entity limit reached)
2. Destination crashed between PREPARE and COMMIT
3. Network partition (destination unreachable)
4. Byzantine validation failure (invalid entity state)

**Solutions**:
- Increase destination capacity or spawn new bubble
- Retry migration to different destination
- Check network health (partition detection)
- Validate entity state before migration proposal

---

**Problem**: ABORT rollback failure (orphaned entity)

**Symptoms**:
- Entity not in source OR destination
- Log shows "ABORT/Rollback FAILED - CRITICAL"
- `MigrationMetrics.orphanedEntities` count increases

**Diagnosis**:
```java
// Check orphaned entity tracking
var orphans = metrics.getOrphanedEntities();
log.critical("Orphaned entities requiring manual recovery: {}", orphans);
```

**Causes**:
1. Source bubble crashed during ABORT
2. Network partition prevents rollback
3. Source storage corruption
4. Concurrent entity deletion

**Solutions**:
- **Immediate**: Log orphaned entity ID for manual recovery
- **Short-term**: Spawn entity in fallback bubble with logged state
- **Long-term**: Implement automated orphan reconciliation
- **Prevention**: Increase ABORT timeout, enable transaction logging

---

**Problem**: Byzantine consensus rejection (migration never executes)

**Symptoms**:
- Consensus returns `false` (not approved)
- Migration never reaches 2PC phases
- Logs show "Consensus rejected proposal"

**Diagnosis**:
```java
// Check view ID mismatch
if (!proposal.viewId().equals(currentViewId)) {
    log.warn("Proposal stale: proposalView={}, currentView={}",
             proposal.viewId(), currentViewId);
}
```

**Causes**:
1. Fireflies view changed during proposal (most common)
2. Insufficient quorum (Byzantine failures in committee)
3. Invalid proposal (validation checks failed)
4. Concurrent conflicting migration proposals

**Solutions**:
- Retry with new view ID (automatic in most cases)
- Wait for view stability before retrying (monitor `viewMonitor`)
- Check Fireflies health (gossip frequency, failure detector)
- Add application-level conflict resolution

---

**Problem**: High migration latency (>500ms P95)

**Symptoms**:
- Migrations succeed but take excessive time
- Animation stuttering or frame drops
- Entity positions stale across bubbles

**Diagnosis**:
```java
// Profile migration phases
var p95 = metrics.getP95Latency();
if (p95 > 500) {
    log.warn("Migration P95 latency {}ms exceeds target 300ms", p95);
}
```

**Causes**:
1. Consensus overhead (committee size too large)
2. Network round-trip latency (geographic distribution)
3. Lock contention (too many concurrent migrations)
4. GC pauses during migration

**Solutions**:
- Reduce Byzantine tolerance (smaller committees, lower t)
- Use regional clusters (reduce network latency)
- Implement migration backpressure (rate limiting)
- Tune JVM GC (reduce pause times)

---

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

## Deployment Scenario Example

### 3-Bubble Distributed Cluster

**Scenario**: Distribute 10,000 entities across 3 bubbles with spatial locality.

**Initial Setup**:
```
Bubble A: Position (0, 0, 0), Bounds [(-50, -50, -50), (50, 50, 50)]
Bubble B: Position (100, 0, 0), Bounds [(50, -50, -50), (150, 50, 50)]
Bubble C: Position (0, 100, 0), Bounds [(-50, 50, -50), (50, 150, 50)]
```

**Deployment Steps**:

**1. Bootstrap Phase** (Bubble A is entry point)
```
t=0ms:   Bubble A starts, initializes Fireflies view
t=50ms:  Bubble A enters ACTIVE state (no neighbors yet)
t=100ms: Bubble B starts, sends JoinRequest to Bubble A
t=150ms: Bubble A responds with JoinResponse(neighbors=[])
t=200ms: Bubble B enters ACTIVE, adds Bubble A as neighbor
t=250ms: Bubble A adds Bubble B as neighbor (bidirectional)
t=300ms: Ghost synchronization establishes between A-B boundary
```

**2. Third Bubble Joins**
```
t=400ms: Bubble C starts, sends JoinRequest to Bubble A (entry point)
t=450ms: Bubble A responds with JoinResponse(neighbors=[B])
t=500ms: Bubble C sends JoinRequest to Bubble B
t=550ms: Bubble B accepts Bubble C as neighbor
t=600ms: k-NN discovery identifies A-C and B-C as neighbors
t=650ms: Ghost zones established for all 3 bubble boundaries
```

**3. Entity Distribution**
```
Initial: 10,000 entities randomly spawned across 3 bubbles
  - Bubble A: 3,400 entities
  - Bubble B: 3,300 entities
  - Bubble C: 3,300 entities

t=1000ms: Entity migration begins based on spatial proximity
  - 120 entities near A-B boundary migrate (consensus approval required)
  - 95 entities near A-C boundary migrate
  - 80 entities near B-C boundary migrate

t=1500ms: Stable distribution achieved
  - Bubble A: 3,200 entities (net loss of 200)
  - Bubble B: 3,410 entities (net gain of 110)
  - Bubble C: 3,390 entities (net gain of 90)
```

**4. Migration Flow Example** (Entity E123 crosses A→B boundary)
```
t=2000ms: Entity E123 position update crosses boundary threshold
t=2001ms: Bubble A detects boundary crossing via ghost zone monitoring
t=2002ms: Create MigrationProposal(E123, sourceA, destB, viewId=V1)
t=2003ms: ViewCommitteeConsensus selects BFT committee (3 nodes, t=0)
t=2020ms: Committee votes ACCEPT (quorum reached)
t=2021ms: 2PC Phase 1 (PREPARE): Remove E123 from Bubble A
t=2025ms: 2PC Phase 2 (COMMIT): Add E123 to Bubble B
t=2030ms: Migration SUCCESS, metrics updated (30ms latency)
```

**5. Ghost Synchronization** (Continuous background)
```
Every 10ms:
  - Bubble A broadcasts ghost updates for entities in A-B and A-C ghost zones
  - Bubble B receives ghost updates from A, applies dead reckoning
  - Bubble C receives ghost updates from A, applies dead reckoning

Ghost zone size: 10% of bubble bounds = ~10 units
Entities in ghost zones: ~5-8% of total entities per bubble (~170 entities)
Network traffic: 170 entities × 3 bubbles × 100 Hz = 51,000 updates/sec
```

**6. Load Balancing Trigger** (Bubble B overloaded)
```
t=5000ms: Bubble B processes 4,500 entities (entity influx from migration)
t=5100ms: Frame processing latency exceeds 16ms target → 22ms (38% over)
t=5200ms: Manager detects load imbalance, calculates new position
t=5250ms: Bubble B broadcasts MOVE(newPosition=(120, 0, 0), newBounds)
t=5300ms: Neighbors A and C update AOI, discover B's new position
t=5400ms: Entity redistribution begins (200 entities migrate B→A, 150 entities B→C)
t=5900ms: Rebalancing complete, frame latency returns to 14ms
```

**Network Topology**:
```
       Bubble A (3,200 entities)
         /  \
        /    \
   Ghost    Ghost
    Zone     Zone
      /        \
Bubble B     Bubble C
(3,410)      (3,390)
     \        /
      \      /
      Ghost Zone
```

**Scaling to 10 Bubbles**:
- Each bubble maintains 10-15 VON neighbors (k-NN with k=10)
- Total migrations: 100-200 per second per bubble
- Ghost synchronization: 50,000-80,000 updates/sec cluster-wide
- Byzantine consensus: O(log n) committee size and message complexity

**Failure Scenario** (Bubble B crashes):
```
t=10000ms: Bubble B process crashes (ungraceful shutdown)
t=10300ms: Fireflies detects B's heartbeat failure (view change triggered)
t=10350ms: View changes from V1 → V2, all pending proposals aborted
t=10400ms: Bubbles A and C remove B from neighbor sets
t=10450ms: Ghost zones cleanup (B's ghost entities expired)
t=10500ms: Entities owned by B become orphaned (logged for recovery)
t=10600ms: Manual intervention or automated recovery spawns Bubble B'
t=11000ms: Bubble B' re-joins via Bubble A, recovers orphaned entities
```

---

## Related Documentation

### Implementation Guides
- [H3_DETERMINISM_EPIC.md](H3_DETERMINISM_EPIC.md) - Deterministic testing with Clock interface
- [TESTING_PATTERNS.md](TESTING_PATTERNS.md) - Testing best practices
- [VON_OVERLAY_ASSESSMENT.md](VON_OVERLAY_ASSESSMENT.md) - VON architecture and MOVE protocol

### ADRs
- [ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md](ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md) - Migration and Consensus design

---

**Document Version**: 3.0 (Mobile bubble architecture)
**Last Updated**: 2026-02-13
**Status**: Current
