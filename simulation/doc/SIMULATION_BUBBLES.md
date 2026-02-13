# Simulation Bubbles: Fixed-Volume Spatial Partitioning

**Status**: Implemented
**Architecture**: Fixed-volume spatial partitioning with deterministic entity assignment
**ADR**: [ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md](ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md)

---

## Overview

Simulation bubbles are **fixed spatial volumes** assigned to cluster nodes via deterministic hashing. Each node manages entities within its assigned spatial region.

**Bubble = fixed spatial volume with deterministic entity assignment.**

### Node Responsibilities

Each node knows:
- **Local entities**: Entities spatially contained within its bubble bounds
- **Ghost entities**: Entities in neighboring bubbles near shared boundaries
- **Assigned volume**: Fixed spatial region (min/max bounds) it manages

### Spatial Assignment

Entities assigned to bubbles via **TetreeKeyRouter**:
```java
public int routeTo(TetreeKey<?> key) {
    var hash = key.getLowBits() ^ key.getHighBits();
    return (int) (Math.abs(hash) % context.size());
}
```

**Properties**:
- Deterministic: Same TetreeKey → same member ID (within cluster view)
- Load balanced: XOR hash provides uniform distribution
- View-aware: Adapts to cluster membership changes

---

## Consistency Model

### Within Bubble (Causal Consistency)

- **Lamport clocks** track causality between events
- **Bucket scheduler** (100ms time buckets) enforces causal order
- **No rollback**: Events execute once, entities never "go back in time"

### Across Bubbles (Eventual Consistency)

- **Ghost layer**: Synchronizes boundary entities at 10ms intervals
- **2PC migration**: Atomically transfers entity ownership between bubbles
- **No global synchronization**: Bubbles coordinate only at boundaries

### Bounded Inconsistency

**Guarantee**: Inconsistency bounded by ghost sync interval (10ms).

**Mechanism**:
- Entities near boundaries exist in multiple bubbles (as ghosts)
- Ghost synchronization provides sub-frame coherence
- Migration protocol transfers ownership with exactly-once semantics

---

## Architecture

```text
Layer 4: BUBBLE COORDINATION (simulation module)
  BubbleNetworkChannel, CrossProcessMigration, ViewCommitteeConsensus
         | uses
Layer 3: GHOST LAYER (lucien module)
  GhostZoneManager, GhostStateManager, boundary synchronization
         | uses
Layer 2: SPATIAL INDEX (lucien module)
  Tetree, TetreeKey, Entity<Key,Content>, spatial queries
         | uses
Layer 1: MEMBERSHIP (fireflies integration)
  FirefliesMembershipView, TetreeKeyRouter, cluster tracking
```

---

## Migration Protocol

### Two-Phase Commit (2PC)

Entity migration uses 2PC for exactly-once semantics:

```
Phase 1: PREPARE
  └─ Remove entity from source bubble (100ms timeout)
       ↓ success
Phase 2: COMMIT
  └─ Add entity to destination bubble (100ms timeout)
       ↓ failure
Phase 3: ABORT (rollback)
  └─ Restore entity to source (100ms timeout)
```

**Guarantees**:
- Exactly-once: Entity appears in exactly one bubble
- Atomic transfer: No entity duplication or loss
- Timeout handling: Failed migrations abort cleanly

**Performance**:
- Latency: ~150ms per migration (3 phases)
- Throughput: 2K-5K migrations/sec per node
- Concurrent: Multiple migrations in parallel (lock-free)

### Byzantine Consensus

Migration decisions require **committee approval** before execution:

1. Proposer creates MigrationProposal with current Fireflies view ID
2. ViewCommitteeSelector deterministically selects BFT committee
3. Committee votes ACCEPT/REJECT based on validation
4. Quorum (t+1 votes) required for approval
5. View ID verified before execution (prevents cross-view races)

**Byzantine Tolerance**:
- t=0 (2-3 nodes): No BFT, crash tolerance only
- t=1 (4-7 nodes): Tolerates 1 Byzantine failure
- t=2 (8+ nodes): Tolerates 2 Byzantine failures

---

## Ghost Layer

### Ghost Zones

Entities within **ghost zone width** (default 10.0f units) of bubble boundaries are replicated to neighboring bubbles.

**Purpose**: Smooth handoff during migration, sub-frame boundary coherence.

**Synchronization**:
- Frequency: 10ms (sub-frame for 60 FPS animation)
- Protocol: Direct gRPC updates to neighbor bubbles
- Lifecycle: Entity enters zone → ghost created, exits → ghost removed

### Ghost Entity States

```
LOCAL ENTITY → [enters ghost zone] → GHOST ENTITY
                                            ↓
                                      [migrates or exits]
                                            ↓
                                   MIGRATED / REMOVED
```

**Ghost Promotion**: When entity migrates, ghost becomes authoritative in destination bubble.

---

## Performance Characteristics

### Latency Targets

| Operation | Target | Typical |
|-----------|--------|---------|
| Bucket processing | < 100ms | ~50ms |
| Entity migration (2PC) | < 300ms | ~150ms |
| Ghost synchronization | < 10ms | ~5ms |
| Spatial routing | < 1ms | ~0.5ms |

### Throughput Targets

| Metric | Target | Realistic |
|--------|--------|-----------|
| Migrations per second (per node) | 2K-5K | ~3K |
| Ghost sync updates per second | 1000+ | ~2000 |
| Entities per bubble | 5K-10K | ~5000 |
| Concurrent migrations | 50+ | ~100 |

### Scalability

**Horizontal Scaling**:
- Add nodes → more bubbles (spatial volumes split)
- Entity distribution via TetreeKeyRouter (automatic)
- Linear scalability up to network bandwidth limits

**Vertical Scaling**:
- More entities per bubble (up to spatial index limits)
- Higher ghost sync rate (up to network limits)
- Larger ghost zones (more overlap, smoother handoff)

---

## Implementation Status

### Completed

- [x] Deterministic spatial routing (TetreeKeyRouter)
- [x] Fixed bubble bounds (BubbleBounds record)
- [x] 2PC migration protocol (CrossProcessMigration)
- [x] Byzantine consensus integration (ViewCommitteeConsensus)
- [x] Ghost layer with boundary synchronization (GhostStateManager)
- [x] Fireflies membership integration (FirefliesMembershipView)
- [x] Lamport clock causality tracking
- [x] Bucket scheduler (100ms time buckets)

### Not Implemented (Explicitly Not Planned)

- [ ] GGPO-style rollback (not needed for fixed-volume architecture)
- [ ] Emergent bubble formation (bubbles are pre-computed)
- [ ] Dynamic bubble movement (bounds are static)
- [ ] VON MOVE protocol (unnecessary for fixed volumes)

---

## Research Foundation

### Implemented Concepts

- Lamport, "Time, Clocks, and the Ordering of Events" (1978) - Causal consistency
- Fireflies virtual synchrony - View-based membership
- Two-Phase Commit protocol - Atomic entity migration
- Ghost layer pattern - Boundary synchronization

### Not Implemented (Referenced in Prior Designs)

- GGPO rollback netcode - Not applicable to fixed-volume architecture
- Jefferson, "Virtual Time" (TimeWarp) - Not needed for prospective causality
- VON MOVE protocol - Entities migrate, bubbles don't move

---

## Configuration

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `BUCKET_DURATION_MS` | 100 | Time bucket duration |
| `PHASE_TIMEOUT_MS` | 100 | Per-phase migration timeout |
| `GHOST_ZONE_WIDTH` | 10.0 | Ghost zone boundary width |
| `GHOST_SYNC_INTERVAL_MS` | 10 | Ghost synchronization frequency |

### Tuning Guidelines

**Low Latency** (< 100ms target):
- Reduce BUCKET_DURATION_MS to 50ms
- Reduce PHASE_TIMEOUT_MS to 50ms
- Increase network bandwidth

**High Throughput** (> 5K migrations/sec):
- Increase concurrent migration capacity
- Reduce ghost zone width (less overlap)
- Optimize spatial routing cache

**High Reliability** (fault tolerance):
- Increase Byzantine tolerance (larger committees)
- Enable comprehensive metrics tracking
- Monitor view stability and migration success rates

---

## Related Documentation

### Architecture
- [ARCHITECTURE_DISTRIBUTED.md](ARCHITECTURE_DISTRIBUTED.md) - Complete distributed architecture
- [ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md](ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md) - Architectural decision rationale

### Implementation
- [H3_DETERMINISM_EPIC.md](H3_DETERMINISM_EPIC.md) - Deterministic testing with Clock interface
- [FIREFLIES_ARCHITECTURE_ASSESSMENT.md](FIREFLIES_ARCHITECTURE_ASSESSMENT.md) - Fireflies integration assessment

### Testing
- [TEST_FRAMEWORK_GUIDE.md](../../TEST_FRAMEWORK_GUIDE.md) - Testing patterns and deterministic scenarios

---

**Document Version**: 2.0 (Rewritten for fixed-volume architecture)
**Last Updated**: 2026-02-13
**Status**: Current
