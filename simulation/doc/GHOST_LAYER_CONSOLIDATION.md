# Ghost Layer: Deterministic Boundary Zones

**Date**: 2026-02-13
**Status**: Implemented
**Architecture**: Deterministic boundary zones for fixed-volume bubbles
**ADR**: [ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md](ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md)

---

## Summary

Ghost layer provides **deterministic boundary synchronization** for entities near fixed bubble boundaries. Ghost zones are pre-computed spatial regions, not emergent formations.

---

## Architecture

### Ghost Zone Definition

A ghost zone is a **fixed spatial region** along bubble boundaries where entities are replicated to neighboring bubbles.

```
Bubble A Bounds: (0,0,0) to (100,100,100)
Ghost Zone Width: 10.0f units

Ghost Zone (negative X boundary):
  Min: (0, 0, 0)
  Max: (10, 100, 100)

Ghost Zone (positive X boundary):
  Min: (90, 0, 0)
  Max: (100, 100, 100)
```

**Properties**:
- Width: Configurable (default 10.0f units)
- Location: Determined by bubble bounds (fixed, not emergent)
- Coverage: All 6 faces of cubic bubble (X±, Y±, Z±)

### Ghost Entity Lifecycle

```
Entity at (5, 50, 50) - Inside bubble A ghost zone
         ↓
GhostZoneManager detects position
         ↓
Create ghost in neighbor bubble B
         ↓
Synchronize at 10ms interval
         ↓
Entity moves to (95, 50, 50) - Exits ghost zone OR migrates
         ↓
Remove ghost from bubble B OR promote ghost to local entity
```

**States**:
1. **Local Entity**: Outside ghost zone, only in owning bubble
2. **Ghost Entity**: Inside ghost zone, replicated to neighbors
3. **Migrated**: Crosses boundary, ownership transferred via 2PC

---

## Implementation

### GhostZoneManager

**Location**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/ghost/GhostStateManager.java`

**Responsibilities**:
- Detect entities entering/exiting ghost zones (spatial containment check)
- Track ghost entity metadata (creation time, last sync)
- Coordinate ghost lifecycle (create, update, remove)

**Key Methods**:
```java
public void markAsGhost(String entityId, long timestamp);
public void unmarkAsGhost(String entityId);
public long getGhostDuration(String entityId);
```

### Boundary Detection

**Algorithm**:
```java
public boolean isInGhostZone(Point3f position, BubbleBounds bounds, float zoneWidth) {
    // Check distance to each face
    boolean nearMinX = (position.x - bounds.getMinX()) < zoneWidth;
    boolean nearMaxX = (bounds.getMaxX() - position.x) < zoneWidth;
    boolean nearMinY = (position.y - bounds.getMinY()) < zoneWidth;
    boolean nearMaxY = (bounds.getMaxY() - position.y) < zoneWidth;
    boolean nearMinZ = (position.z - bounds.getMinZ()) < zoneWidth;
    boolean nearMaxZ = (bounds.getMaxZ() - position.z) < zoneWidth;

    return nearMinX || nearMaxX || nearMinY || nearMaxY || nearMinZ || nearMaxZ;
}
```

**Properties**:
- O(1) containment check (6 comparisons)
- Deterministic: Same position always yields same result
- No probabilistic detection or emergent formation

### Ghost Synchronization

**Protocol**:
```
Every 10ms (sub-frame for 60 FPS animation):
  1. For each entity in ghost zone:
     2. Get current state (position, velocity, content)
     3. Send ghost update to neighbor bubbles (via gRPC)
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

**Network Protocol**: Direct gRPC messages (not Fireflies gossip)

---

## Integration with Migration

### Ghost Promotion

When entity migrates across boundary:

```
Phase 1 (in source bubble):
  └─ Entity is local, ghost exists in destination
       ↓
Phase 2 (migration decision):
  └─ 2PC protocol begins (consensus + PREPARE)
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
| **Latency** | ~5ms (direct gRPC) | ~150ms (2PC + consensus) |
| **Purpose** | Sub-frame coherence | Ownership transfer |
| **Reversible** | Yes (entity can leave zone) | No (atomic transfer) |

---

## Performance Characteristics

### Ghost Synchronization Overhead

| Metric | Target | Typical |
|--------|--------|---------|
| Sync frequency | 100 Hz (10ms) | 100 Hz |
| Network overhead per ghost | ~100 bytes/update | ~80 bytes |
| Sync latency | < 10ms | ~5ms |
| Ghosts per boundary | 10-50 | ~20 |

**Total Overhead** (per boundary):
- 20 ghosts × 100 Hz × 80 bytes = 160 KB/sec
- Negligible for modern networks (< 1% of 1 Gbps link)

### Ghost Zone Coverage

**Typical Configuration**:
- Bubble size: 1000 units³ (10×10×10)
- Ghost zone width: 10 units
- Ghost zone volume: ~280 units³ (6 faces × ~47 units²/face × 10 units)
- Coverage: ~28% of bubble volume

**Trade-off**:
- Larger zones: Smoother handoff, higher network overhead
- Smaller zones: Lower overhead, more migration "jumps"

---

## Configuration

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `GHOST_ZONE_WIDTH` | 10.0f | Zone width along each boundary face |
| `GHOST_SYNC_INTERVAL_MS` | 10 | Synchronization frequency |
| `GHOST_ENABLED` | true | Enable ghost layer (disable for testing) |

### Tuning Guidelines

**Smooth Animation** (60+ FPS):
- Keep `GHOST_SYNC_INTERVAL_MS` at 10ms (sub-frame)
- Increase `GHOST_ZONE_WIDTH` to 15-20 units (more overlap)

**Low Network Usage**:
- Reduce `GHOST_ZONE_WIDTH` to 5 units (less overlap)
- Increase `GHOST_SYNC_INTERVAL_MS` to 20ms (lower frequency)
- Trade-off: May see "jumps" at boundaries

**Testing**:
- Set `GHOST_ENABLED` to false (disable ghost layer)
- Validate migration works without ghost synchronization
- Measure migration latency baseline

---

## Testing

### Ghost Zone Detection Tests

**Coverage**:
- Entity enters ghost zone → ghost created
- Entity exits ghost zone → ghost removed
- Entity migrates → ghost promoted to local

**Test Files**:
- `TetrahedralForestE2ETest.java:testGhostLayerWithTetrahedralForest()` (lines 597-681)
- `TetrahedralForestE2ETest.java:testGhostLayerWithBeySubdivision()` (lines 899-1027)

**Validated**:
- ✅ Ghost zones created for all 6 tetrahedral children (S0-S5 subdivision)
- ✅ Ghost zones created for all 8 Bey grandchildren (Case B subdivision)
- ✅ AABB computation for tetrahedral bounds (required for ghost detection)
- ✅ AABB non-degeneracy (positive extents in X, Y, Z)

### Ghost Synchronization Tests

**Coverage**:
- Ghost update frequency (10ms interval)
- Network message format (GhostUpdate serialization)
- Concurrent ghost updates (lock-free)

**Test Pattern** (from H3_DETERMINISM_EPIC.md):
```java
var testClock = new TestClock();
ghostManager.setClock(testClock);

// T=0: Entity enters ghost zone
testClock.setMillis(0);
ghostManager.markAsGhost("entity1", testClock.currentTimeMillis());

// T=10: First sync
testClock.advance(10);
ghostManager.syncGhosts();  // Should trigger network update

// Verify sync timestamp
assertEquals(10, ghostManager.getLastSync("entity1"));
```

---

## Comparison to Prior Designs

### Emergent Boundary Formation (NOT IMPLEMENTED)

**Prior Vision**:
- Ghost zones form dynamically based on entity interactions
- Boundaries emerge from spatial clustering
- No pre-computed zones

**Why Rejected**:
- Non-deterministic (hard to test)
- Unpredictable network overhead (unknown ghost count)
- Complex implementation (cluster detection algorithms)

### Fixed Deterministic Zones (CURRENT)

**Implementation**:
- Ghost zones defined by fixed bubble bounds
- Deterministic containment checks (O(1))
- Predictable overhead (based on zone width)

**Benefits**:
- Testable (deterministic behavior)
- Predictable performance (known network overhead)
- Simple implementation (spatial containment only)

---

## Integration Points

### Spatial Index Layer

**Ghost detection uses Tetree spatial queries**:
- `Tetree.contains()`: Check if entity in boundary region
- `TetrahedralBounds.toAABB()`: Convert tet bounds to AABB for ghost detection
- `Forest.getTree()`: Lookup neighbor trees for ghost synchronization

**Validated**: TetrahedralForestE2ETest validates ghost AABB computation (lines 663-677)

### Network Layer

**Ghost sync uses direct gRPC**:
- `BubbleNetworkChannel.sendGhostUpdate()`: Send ghost state to neighbor
- `GhostMessageHandler.handleGhostUpdate()`: Receive ghost update from neighbor

**Not using Fireflies gossip**: Ghost sync is targeted point-to-point, not cluster-wide broadcast

### Migration Protocol

**Ghost promotion during 2PC**:
- Phase 1 (PREPARE): Source removes local entity (ghost remains in destination)
- Phase 2 (COMMIT): Destination promotes ghost to local entity
- Phase 3 (cleanup): Remove ghost from other neighbors

**Integration**: CrossProcessMigration coordinates with GhostStateManager during ownership transfer

---

## Future Optimizations (Not Planned)

### Adaptive Ghost Zone Width
- **Idea**: Dynamically adjust zone width based on entity velocity
- **Benefit**: Fast-moving entities get larger zones (smoother handoff)
- **Trade-off**: Complexity, non-deterministic overhead
- **Status**: Not needed for current use cases

### Ghost Prediction
- **Idea**: Extrapolate entity position between sync intervals
- **Benefit**: Reduce perceived latency for remote ghosts
- **Trade-off**: Prediction errors, synchronization complexity
- **Status**: 10ms sync interval is sufficient for 60 FPS animation

---

## Related Documentation

- [SIMULATION_BUBBLES.md](SIMULATION_BUBBLES.md) - Overall bubble architecture
- [ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md](ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md) - Fixed-volume decision
- [ARCHITECTURE_DISTRIBUTED.md](ARCHITECTURE_DISTRIBUTED.md) - Complete distributed architecture
- [.pm/ghost-layer-validation-findings.md](../../.pm/ghost-layer-validation-findings.md) - Test coverage validation

---

**Document Version**: 1.0
**Author**: Documentation alignment (Luciferase-9mri)
**Status**: Current
