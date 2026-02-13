# ADR 002: Fixed-Volume Spatial Partitioning

**Status**: Accepted
**Date**: 2026-02-13
**Context**: Luciferase Distributed Simulation Architecture

---

## Decision

Simulation bubbles are implemented as **fixed-volume spatial partitions** with deterministic entity assignment, not emergent mobile volumes.

---

## Architecture

### Spatial Assignment

**TetreeKeyRouter** provides deterministic mapping:
```java
public int routeTo(TetreeKey<?> key) {
    var hash = key.getLowBits() ^ key.getHighBits();
    return (int) (Math.abs(hash) % context.size());
}
```

**Properties**:
- Deterministic: Same TetreeKey → same member ID (within a view)
- Load balanced: XOR hash provides uniform distribution
- View-aware: Routes to current cluster membership

### Bubble Bounds

Bubbles have **fixed spatial boundaries** defined by:
- Minimum point (x, y, z)
- Maximum point (x, y, z)
- Bounds set at bubble creation, do not move

Entities assigned to bubbles via spatial hashing of their TetreeKey position.

### Migration Protocol

**Two-Phase Commit (2PC)** without rollback:

```
PREPARE: Remove entity from source bubble (100ms timeout)
    ↓
COMMIT: Add entity to destination bubble (100ms timeout)
    ↓ (on failure)
ABORT: Restore entity to source (100ms timeout)
```

**Characteristics**:
- Latency: ~150ms per migration (3 phases)
- Throughput: 2K-5K migrations/sec per node
- Exactly-once: Idempotency tokens prevent duplication
- No rollback: Entities never "go back in time"

### Consistency Model

**Within bubble** (causal consistency):
- Lamport clocks track causality
- Bucket scheduler (100ms buckets) enforces causal order
- No rollback - divergence prevented via consensus

**Across bubbles** (eventual consistency):
- Ghost layer synchronizes boundary entities (10ms interval)
- Migration protocol transfers ownership atomically
- No global synchronization required

---

## Rationale

### Determinism
Fixed volumes with deterministic assignment enable:
- Reproducible test scenarios
- Predictable entity distribution
- Debuggable migration paths

### Testability
Deterministic routing allows:
- TestClock injection for time control
- FakeNetworkChannel for network simulation
- Reproducible multi-bubble scenarios

### Resource Allocation
Fixed bounds provide:
- Predictable memory usage per node
- Capacity planning based on spatial volume
- Load balancing via uniform hash distribution

### Simplicity
No rollback eliminates:
- State snapshot/restore complexity
- Cascading rollback scenarios
- Time-travel causality violations

---

## Trade-offs Accepted

### No Rollback
- **Accepted**: Migration failures abort cleanly, no state rewind
- **Impact**: Entities may briefly pause during migration retry
- **Mitigation**: 2PC ensures exactly-once semantics, idempotency prevents duplication

### Fixed Throughput
- **Accepted**: 2K-5K migrations/sec limited by 2PC latency
- **Impact**: Not suitable for >5K migrations/sec workloads
- **Mitigation**: Sufficient for spatial simulation use cases (entities move slowly relative to migration capacity)

### Static Bounds
- **Accepted**: Bubbles don't move to follow entity clusters
- **Impact**: Entity distribution may become unbalanced over time
- **Mitigation**: Spatial hashing provides initial uniform distribution, ghost layer handles boundary transitions

---

## Implementation Evidence

### Deterministic Routing
- `TetreeKeyRouter.java` (lines 60-77): Spatial hash to member ID
- `FirefliesMembershipView.java`: Cluster membership tracking
- `ViewCommitteeSelector.java`: Deterministic committee selection

### Fixed Bounds
- `BubbleBounds` record: Immutable min/max points
- `Tetree.contains()`: Spatial containment checks
- No bubble movement methods in codebase

### 2PC Migration
- `CrossProcessMigration.java`: PREPARE/COMMIT/ABORT protocol
- `IdempotencyStore.java`: Exactly-once semantics
- `MigrationMetrics.java`: Success/failure/rollback tracking

### No Rollback
- `CausalRollback` class exists but contains no rollback logic
- Lamport clocks track causality, no time-travel
- Bucket scheduler enforces causal order prospectively

---

## Alternatives Considered

### Emergent Mobile Bubbles
- **Description**: Bubbles form dynamically, move to follow entity clusters
- **Rejected**: Non-deterministic, hard to test, unpredictable resource usage
- **Documentation artifacts**: SIMULATION_BUBBLES.md claimed this approach (being corrected)

### GGPO-Style Rollback
- **Description**: Bounded rollback window (100-200ms) for divergence correction
- **Rejected**: Complexity, causality violations, not needed for spatial simulation
- **Documentation artifacts**: ARCHITECTURE_DISTRIBUTED.md referenced GGPO (being removed)

---

## Consequences

### Documentation Updates Required
1. SIMULATION_BUBBLES.md: Rewrite to describe fixed-volume architecture
2. ARCHITECTURE_DISTRIBUTED.md: Remove "emergent" and GGPO references
3. FIREFLIES_ARCHITECTURE_ASSESSMENT.md: Correct 300K→2K-5K migration throughput

### No Code Changes Needed
Implementation already follows fixed-volume architecture. Documentation alignment only.

### Performance Expectations
- Migration latency: ~150ms (validated in tests)
- Migration throughput: 2K-5K/sec per node (2PC latency bound)
- Spatial routing: O(1) via hash lookup

---

## References

- CrossProcessMigration.java: 2PC implementation
- TetreeKeyRouter.java: Deterministic spatial routing
- FirefliesMembershipView.java: Cluster membership integration
- TEST_FRAMEWORK_GUIDE.md: Deterministic testing patterns

---

**Document Version**: 1.0
**Author**: Documentation alignment (Luciferase-9mri)
**Next Review**: After production deployment validation
