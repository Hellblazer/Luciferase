# Ghost Layer: Boundary Synchronization for Mobile Bubbles

**Status**: Current
**Architecture**: Ghost synchronization with dead reckoning for mobile bubble boundaries

---

## Summary

Ghost layer provides boundary synchronization between mobile bubbles. Entities near bubble boundaries are replicated as "ghost entities" for smooth handoff and sub-frame coherence.

---

## Architecture

### Ghost State Management

**Location**: `ghost/GhostStateManager.java` (499 lines)

```java
public class GhostStateManager {
    private volatile Clock clock = Clock.system();

    private final Map<StringEntityID, GhostState> ghostStates;  // Position + velocity
    private final DeadReckoningEstimator deadReckoning;  // Extrapolation
    private final GhostLifecycleStateMachine lifecycle;  // State transitions

    record GhostState(GhostEntity entity, Vector3f velocity)
}
```

**Responsibilities**:
- Track ghost entity position and velocity
- Extrapolate position via dead reckoning between updates
- Detect and cull stale ghosts (TTL=500ms)
- Manage lifecycle state transitions (CREATE → UPDATE → STALE → EXPIRED)
- Clamp extrapolated positions to bubble bounds

---

## Ghost Lifecycle

### State Machine

```
CREATE → UPDATE → STALE → EXPIRED
```

**State Transitions**:
- **CREATE**: Ghost arrives from network, add to ghostStates map
- **UPDATE**: Receive position/velocity update, reset staleness timer
- **STALE**: No update for >500ms, mark for cleanup
- **EXPIRED**: Removed from ghostStates map

### Operations

**Update Ghost** (receive from network):
```java
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
```

**Get Ghost Position** (with dead reckoning):
```java
public Point3f getGhostPosition(StringEntityID entityId, long currentTime) {
    var predictedPosition = deadReckoning.predict(adapter, currentTime);
    return clampToBounds(predictedPosition);  // Clamp to bubble bounds
}
```

**Cull Stale Ghosts** (periodic cleanup):
```java
public void tick(long currentTime) {
    var expiredCount = lifecycle.expireStaleGhosts(currentTime);
    for (var entityId : ghostStates.keySet()) {
        if (lifecycle.getState(entityId) == null) {
            removeGhost(entityId);  // Cleanup expired
        }
    }
}
```

---

## Dead Reckoning

### Purpose

Extrapolate ghost position between network updates (10ms sync interval) using stored velocity.

### Mechanism

**Prediction Formula**:
```
predictedPosition = lastKnownPosition + velocity × deltaTime
```

**Implementation**:
```java
// DeadReckoningEstimator extrapolates position
public Point3f predict(DeadReckoningAdapter adapter, long currentTime) {
    var lastUpdate = adapter.getLastUpdateTime();
    var deltaTime = currentTime - lastUpdate;

    var position = adapter.getPosition();
    var velocity = adapter.getVelocity();

    return position.add(velocity.scale(deltaTime / 1000.0f));  // Convert ms to seconds
}
```

**Benefits**:
- Smooth animation between network updates
- Reduced perceived latency for remote ghosts
- Sub-frame coherence (60+ FPS animation with 10ms sync)

### Boundary Clamping

**Problem**: Dead reckoning can extrapolate position outside bubble bounds.

**Solution**: Clamp predicted position to bubble bounds.

**Implementation**:
```java
private Point3f clampToBounds(Point3f position) {
    if (bounds.contains(position)) {
        return position;  // Already within bounds
    }

    // Convert to RDGCS (recursive diamond grid coordinate system)
    var rdgPos = bounds.toRDG(position);

    // Clamp to min/max bounds
    var clampedRdg = new Point3i(
        Math.max(rdgMin.x, Math.min(rdgMax.x, rdgPos.x)),
        Math.max(rdgMin.y, Math.min(rdgMax.y, rdgPos.y)),
        Math.max(rdgMin.z, Math.min(rdgMax.z, rdgPos.z))
    );

    // Convert back to Cartesian
    return bounds.toCartesian(clampedRdg);
}
```

**Properties**:
- Prevents ghosts from appearing outside bubble visual region
- Uses RDGCS coordinate system (tetrahedral subdivision)
- Conservative clamping (keeps ghost visible at boundary)

---

## Ghost Synchronization

### Protocol

**Frequency**: 10ms interval (sub-frame for 60+ FPS animation)

**Flow**:
```
Every 10ms:
  1. For each entity in ghost zone:
     2. Get current state (position, velocity, content)
     3. Send ghost update to neighbor bubbles (via P2P transport)
     4. Update lastSync timestamp
```

**Message Format**:
```java
public record GhostUpdate(
    String entityId,
    Point3f position,
    Vector3f velocity,
    Content content,
    long timestamp
) {}
```

**Network Protocol**: Direct P2P messages via bubble transport.

---

## VON Integration

### Ghost-Based Neighbor Discovery

**Location**: `ghost/GhostSyncVONIntegration.java`

**Discovery Pattern**:
```java
public void onGhostBatchReceived(UUID fromBubbleId) {
    if (!vonNode.neighbors().contains(fromBubbleId)) {
        vonNode.addNeighbor(fromBubbleId);  // Discover via ghost arrival
        ghostManager.onVONNeighborAdded(fromBubbleId);  // Enable bidirectional sync
    }
}
```

**Core Thesis**: "Ghost layer + VON protocols = fully distributed animation with no global state"

**Integration Points**:
1. **VON JOIN**: Initialize ghost relationships with all neighbors
2. **VON MOVE**: Discover new boundary neighbors via ghost arrivals
3. **Ghost Batch**: Primary neighbor discovery mechanism

### MOVE Event Handling

**When bubble moves**:
```java
public void onVONMove(Point3D newPosition) {
    var nearbyNodes = neighborIndex.findKNearest(newPosition, 10);
    for (var nearbyNode : nearbyNodes) {
        if (neighborIndex.isBoundaryNeighbor(vonNode, nearbyNode)) {
            vonNode.addNeighbor(nearbyId);
            ghostManager.onVONNeighborAdded(nearbyId);  // Register for ghost sync
        }
    }
}
```

**Properties**:
- k-NN spatial query (k=10) finds nearby bubbles
- Boundary neighbor detection enables ghost sync
- Automatic registration with ghost manager

---

## Integration with Migration

### Ghost Promotion

When entity migrates across boundary:

```
Phase 1 (in source bubble):
  └─ Entity is local, ghost exists in destination
       ↓
Phase 2 (migration decision):
  └─ Byzantine consensus + 2PC protocol begins
       ↓
Phase 3 (in destination bubble):
  └─ Ghost promoted to local entity
  └─ Source removes local entity
       ↓
Phase 4 (cleanup):
  └─ Other ghost copies removed (no longer in ghost zone)
```

**Key Insight**: Ghost already exists in destination before migration, enabling smooth handoff.

### Ghost vs Migration

| Aspect | Ghost Sync | Entity Migration |
|--------|-----------|------------------|
| **Frequency** | 10ms (continuous) | As needed (rare) |
| **Latency** | ~5ms (direct P2P) | ~150ms (2PC + consensus) |
| **Purpose** | Sub-frame coherence | Ownership transfer |
| **Reversible** | Yes (entity can leave zone) | No (atomic transfer) |

---

## Performance Characteristics

### Ghost Synchronization Overhead

| Metric | Target | Typical |
|--------|--------|---------| |
| Sync frequency | 100 Hz (10ms) | 100 Hz |
| Network overhead per ghost | ~100 bytes/update | ~80 bytes |
| Sync latency | < 10ms | ~5ms |
| Ghosts per boundary | 10-50 | ~20 |

**Total Overhead** (per boundary):
- 20 ghosts × 100 Hz × 80 bytes = 160 KB/sec
- Negligible for modern networks (< 1% of 1 Gbps link)

### Dead Reckoning Accuracy

| Metric | Target | Typical |
|--------|--------|---------|
| Prediction error | < 5 units | ~2 units |
| Staleness threshold | 500ms | 500ms |
| Extrapolation interval | 10ms | 10ms |

**Trade-off**:
- Shorter sync interval: More network overhead, better accuracy
- Longer sync interval: Less overhead, more prediction error

---

## Configuration

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `GHOST_SYNC_INTERVAL_MS` | 10 | Synchronization frequency |
| `GHOST_TTL_MS` | 500 | Staleness threshold for ghost expiration |
| `GHOST_ENABLED` | true | Enable ghost layer (disable for testing) |

### Tuning Guidelines

**Smooth Animation** (60+ FPS):
- Keep `GHOST_SYNC_INTERVAL_MS` at 10ms (sub-frame)
- Reduce `GHOST_TTL_MS` to 300ms (faster staleness detection)

**Low Network Usage**:
- Increase `GHOST_SYNC_INTERVAL_MS` to 20ms (lower frequency)
- Trade-off: May see small "jumps" at boundaries

**Testing**:
- Set `GHOST_ENABLED` to false (disable ghost layer)
- Validate migration works without ghost synchronization
- Measure migration latency baseline

---

## Testing

### Ghost Layer Tests

**Coverage**:
- Ghost lifecycle state transitions (CREATE → UPDATE → STALE → EXPIRED)
- Dead reckoning extrapolation accuracy
- Boundary clamping (prevents ghost escape)
- Staleness detection and cleanup
- VON integration (ghost-based neighbor discovery)

**Test Files**:
- `GhostStateManagerTest.java`: Lifecycle and dead reckoning
- `GhostSyncVONIntegrationTest.java`: VON neighbor discovery via ghosts
- `TetrahedralForestE2ETest.java`: End-to-end ghost synchronization

**Validated**:
- ✅ Ghost updates received from neighbors
- ✅ Dead reckoning prediction accuracy (< 5 units error)
- ✅ Staleness detection (>500ms expiration)
- ✅ Boundary clamping (ghosts don't escape bounds)
- ✅ VON neighbor discovery via ghost arrivals

---

## Related Documentation

- [SIMULATION_BUBBLES.md](SIMULATION_BUBBLES.md) - Mobile bubble architecture
- [VON_OVERLAY_ASSESSMENT.md](VON_OVERLAY_ASSESSMENT.md) - VON architecture and MOVE protocol
- [ARCHITECTURE_DISTRIBUTED.md](ARCHITECTURE_DISTRIBUTED.md) - Complete distributed architecture

---

**Document Version**: 2.0 (Mobile bubble architecture)
**Last Updated**: 2026-02-13
**Status**: Current
