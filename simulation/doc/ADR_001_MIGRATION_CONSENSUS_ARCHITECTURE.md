# ADR 001: Migration and Consensus Architecture

## Status

**Accepted** - Current Implementation (as of 2026-01-15)

## Context

### Problem Statement

Luciferase implements a distributed entity simulation where entities must migrate between spatial bubbles as they move through 3D space. This migration requires:

1. **Atomicity**: Entity migration must complete fully or not at all
2. **No Duplicates**: Entity must exist in exactly one bubble at any time
3. **No Loss**: Entity must never be lost, even on failure
4. **Idempotency**: Same migration request must be applied exactly once
5. **Coordination**: Multiple bubbles must agree on entity ownership

### Historical Evolution

The migration/consensus architecture has evolved through multiple phases:

**Phase 7 (Early 2026)**: Initial exploration of various migration approaches
- Direct transfer (no coordination)
- Optimistic migration with rollback
- 2PC with add-first ordering

**Phase 7G Day 3 (Jan 10, 2026)**: Raft consensus experiment
- Built complete Raft-style consensus implementation
- ~1,490 LOC implementation + ~1,850 LOC tests
- Components: ElectionState FSM, CoordinatorElectionProtocol, FailureDetector, BallotBox
- **Deleted after 1 day** (commit c05d5748, Jan 10, 2026)

**Phase 7G Day 3 Redesign (Jan 10, 2026)**: Switch to Fireflies committee-based consensus
- Leveraged existing Delos Fireflies infrastructure
- Eliminated leader election entirely
- 85% code reduction vs Raft approach
- Committee-based voting with BFT (Byzantine Fault Tolerance)

**Phase 6B4 (Jan 8-12, 2026)**: Two-Phase Commit migration protocol
- Implemented distributed 2PC with remove-then-commit ordering
- Clock interface integration for deterministic testing (H3.7)
- CrossProcessMigration implementation (simulation/.../migration/)

## Decision

### Migration Protocol: Two-Phase Commit with Remove-Then-Commit Ordering

**Implementation**: `CrossProcessMigration.java`

**Protocol Flow**:
```
PREPARE Phase (100ms timeout):
1. Generate idempotency token (UUID)
2. Check IdempotencyStore (reject if duplicate)
3. Snapshot entity state for rollback
4. REMOVE entity from source bubble
5. Entity is now "in flight"

COMMIT Phase (100ms timeout):
1. ADD entity to destination bubble
2. Persist idempotency token with 5-minute TTL
3. Success: Entity now at destination

ABORT Phase (on failure, 100ms timeout):
1. RE-ADD entity to source from snapshot
2. Remove idempotency token (allow retry)
3. Entity restored to original location
```

**Key Insight**: Remove-then-commit prevents duplicates. If COMMIT fails, entity can be restored from snapshot. Traditional add-first approach risks duplicates if remove fails after add succeeds.

**Trade-offs Accepted**:
- Entity temporarily "homeless" during migration (~100ms window)
- Additional complexity for rollback logic
- Requires entity snapshot storage

### Consensus Protocol: Fireflies View Committee with BFT

**Implementation**: `ViewCommitteeConsensus.java`, `CommitteeVotingProtocol.java`

**Committee Selection**:
- Committee computed from `context.bftSubset(viewId)` (Delos Fireflies API)
- Deterministic based on view hash
- Size: O(log n) - typically 5-11 nodes for small clusters, 13-42 for large
- Auto-recalculates on view changes

**Voting Protocol**:
```
1. Proposer: Submit EntityOwnershipProposal to committee
2. Committee members: Vote ACCEPT/REJECT based on:
   - Entity not already migrating
   - Destination bubble has capacity
   - Source bubble confirms ownership
3. Quorum: (t + 1) votes required where t = Byzantine tolerance
4. Commit: Execute 2PC migration on quorum
5. View Change: Pending proposals automatically aborted
```

**Byzantine Tolerance**:
- **t=0**: 2-3 node clusters, quorum=1 (no BFT)
- **t=1**: 4-7 node clusters, quorum=2 (tolerates 1 Byzantine)
- **t=2**: 8+ node clusters, quorum=3 (tolerates 2 Byzantine)

**Message Propagation**: Uses existing Fireflies gossip broadcast
- No separate P2P RPC infrastructure needed
- 10-100x message reduction vs all-nodes-participate
- Leverages existing view membership and failure detection

## Alternatives Considered

### 1. Raft Consensus (REJECTED after 1 day)

**Approach**:
- All nodes participate in consensus
- Leader election with FOLLOWER/CANDIDATE/LEADER FSM
- Term-based voting with split-brain handling
- Heartbeat-based failure detection

**Why Rejected**:
- **Duplicates Fireflies**: Reinvents membership and failure detection already in Delos
- **Scales poorly**: O(n) messages per decision (all nodes participate)
- **Complexity**: ~1,490 LOC for consensus alone, ~1,850 LOC tests
- **No view integration**: Doesn't leverage existing virtual synchrony
- **Over-engineered**: Simulation doesn't need log-based consensus

**Evidence**: Commit c05d5748 deleted `coordinator.proto` containing all Raft code after Phase 7G Day 3 redesign

### 2. Add-First 2PC (REJECTED - Jan 8, 2026)

**Approach**:
```
PREPARE: Add entity to destination
COMMIT: Remove from source
ABORT: Remove from destination
```

**Why Rejected**:
- **Duplicates risk**: If remove fails after add succeeds, entity exists in both bubbles
- **Harder rollback**: Remove is harder to undo than add
- **Safety concern**: Duplicate entities violate system invariants

**Decision**: Phase 6B4 audit (document `decision::migration::phase-6b4-2pc-protocol`) confirmed remove-then-commit is superior

### 3. Saga Pattern (REJECTED - complexity)

**Why Rejected**:
- Overkill for single entity migration
- Requires compensation logic for every step
- No clear advantage over 2PC for this use case

### 4. Distributed Locking (REJECTED - deadlock risk)

**Why Rejected**:
- Deadlock risk at scale (bubbles locking each other)
- Hard to implement correctly in distributed system
- Doesn't provide atomicity for migration

### 5. Optimistic Migration (REJECTED - requires complex reconciliation)

**Why Rejected**:
- Duplicate detection is complex
- Reconciliation after conflicts is hard
- Doesn't guarantee consistency

## Consequences

### Positive

**Migration Protocol**:
1. ✅ Zero duplicates in normal operation
2. ✅ Exactly-once semantics via idempotency tokens
3. ✅ <300ms total migration latency (3 phases × 100ms)
4. ✅ Deterministic testing via Clock interface (H3.7)
5. ✅ Graceful failure handling with rollback
6. ✅ MigrationLog provides audit trail
7. ✅ DuplicateEntityDetector remains as safety net

**Consensus Protocol**:
1. ✅ No leader election complexity
2. ✅ Byzantine fault tolerance built-in
3. ✅ Scales to O(log n) message complexity
4. ✅ Automatic view synchrony handling
5. ✅ 85% less code than Raft approach
6. ✅ Reuses existing Fireflies infrastructure
7. ✅ Virtual synchrony: pending proposals auto-abort on view changes

### Negative

**Migration Protocol**:
1. ⚠️ Entity unavailable for ~100ms during migration
2. ⚠️ Rollback failure is critical condition (requires manual intervention)
3. ⚠️ Additional memory for entity snapshots during migration
4. ⚠️ Idempotency store cleanup required (5-min TTL)

**Consensus Protocol**:
1. ⚠️ Requires Delos Fireflies dependency
2. ⚠️ Committee size grows with cluster size (O(log n))
3. ⚠️ View changes abort pending proposals (retry required)
4. ⚠️ Byzantine tolerance requires quorum calculation

### Monitoring & Safety

**Migration Metrics** (`MigrationMetrics`):
- Success rate and latency percentiles
- Rollback failures (critical alerts)
- Duplicate rejections (idempotency working)
- Concurrent migration attempts

**Consensus Metrics**:
- Committee size per view
- Vote latency and quorum time
- Proposal rejection reasons
- Byzantine detection events

**Invariant Checkers**:
- `DuplicateEntityDetector`: Safety net for duplicates
- `EntityCountInvariant`: Total entity count preservation (recommended)
- `MigrationLog`: Audit trail for reconciliation

## Implementation Status

### Completed (as of 2026-01-15)

**Migration (Phase 6B4)**:
- ✅ CrossProcessMigration.java - 2PC coordinator
- ✅ IdempotencyStore.java - Duplicate detection
- ✅ MigrationTransaction.java - State tracking
- ✅ H3.7 Clock integration (8 System.* calls converted)
- ✅ 42+ tests passing
- ✅ Stress tested: 100+ concurrent migrations

**Consensus (Phase 7G)**:
- ✅ ViewCommitteeConsensus.java - Committee-based voting
- ✅ CommitteeVotingProtocol.java - Vote handling
- ✅ GrpcCoordinatorService.java - gRPC integration
- ✅ 91 committee consensus tests passing
- ✅ 21 integration tests passing
- ✅ BFT validation: 2-node, 3-node, 5-node clusters

**Integration**:
- ✅ EntityMigrationStateMachine.java - FSM integration
- ✅ Consensus + Migration working together
- ✅ View change proposal rollback verified
- ✅ Byzantine robustness validated

### Key Files

**Migration**:
- `simulation/src/main/java/.../migration/CrossProcessMigration.java`
- `simulation/src/main/java/.../migration/IdempotencyStore.java`
- `simulation/src/main/java/.../migration/MigrationTransaction.java`
- `simulation/src/main/java/.../ghost/MigrationLog.java`

**Consensus**:
- `simulation/src/main/java/.../coordination/ViewCommitteeConsensus.java`
- `simulation/src/main/java/.../coordination/CommitteeVotingProtocol.java`
- `simulation/src/main/java/.../coordination/GrpcCoordinatorService.java`
- `grpc/src/main/proto/lucien/committee.proto` (gRPC definitions)

**Ghost Layer** (related):
- `simulation/src/main/java/.../ghost/GhostBoundarySync.java`
- `simulation/src/main/java/.../ghost/DuplicateEntityDetector.java`
- `simulation/src/main/java/.../ghost/TetreeGhostSyncAdapter.java`

## Ghost Channel Implementations (M2 Decision)

**Decision Date**: 2026-01-15 (M2 Ghost Layer Consolidation)

### Current Implementations

#### 1. GhostChannel Interface
- **Location**: `simulation/src/main/java/.../ghost/GhostChannel.java`
- **Purpose**: Abstract interface for batched ghost transmission between bubbles
- **Key Methods**: `queueGhost()`, `sendBatch()`, `flush()`, `onReceive()`

#### 2. InMemoryGhostChannel (KEEP)
- **Location**: `simulation/src/main/java/.../ghost/InMemoryGhostChannel.java`
- **Status**: ✅ PRODUCTION (testing/single-server deployments)
- **Features**:
  - Thread-safe batching with ConcurrentHashMap
  - Optional simulated latency (0-N ms)
  - Multiple handlers via CopyOnWriteArrayList
  - Always connected (no network failures)
- **Use Cases**:
  - Unit/integration testing
  - Single-server multi-bubble simulations
  - Performance benchmarking with controlled latency

#### 3. P2PGhostChannel (PRODUCTION)
- **Location**: `simulation/src/main/java/.../ghost/P2PGhostChannel.java`
- **Status**: ✅ PRODUCTION (distributed multi-bubble)
- **Features**:
  - P2P transmission via VonTransport abstraction
  - Batched transmission at bucket boundaries (100ms)
  - Automatic SimulationGhostEntity ↔ TransportGhost conversion
  - Event-based receive handling from VonBubble
  - Same-server optimization bypass
- **Use Cases**:
  - Distributed multi-bubble simulations
  - VON-based P2P neighbor synchronization
  - Cross-process ghost delivery

#### 4. DelosSocketTransport (DEPRECATED)
- **Location**: `simulation/src/main/java/.../ghost/DelosSocketTransport.java`
- **Status**: ⚠️ DEPRECATED (incomplete prototype)
- **Issues**:
  - 7+ "TODO Phase 7B.2" comments throughout implementation
  - Uses simulated network via `connectTo()` method (not real Delos)
  - EntityUpdateEvent serialization incomplete
  - Superseded by P2PGhostChannel's VonTransport integration
- **Deprecation Plan**:
  - Mark `@Deprecated(forRemoval = true)` immediately
  - Remove in Month 2 (after verification P2PGhostChannel covers all use cases)
  - Tests remain for reference but skip via `@Disabled`

### Decision Rationale

**Why Keep InMemoryGhostChannel**:
- Essential for deterministic testing (no network variability)
- Lightweight for single-server scenarios
- Simulated latency feature valuable for testing edge cases
- Clear separation: testing vs production implementations

**Why P2PGhostChannel is Production**:
- Leverages VON transport abstraction (already implements network communication)
- Integrated with VonBubble P2P neighbor discovery
- Handles same-server optimization automatically
- Production-ready with full test coverage

**Why Deprecate DelosSocketTransport**:
- Incomplete implementation (7+ TODOs for "actual Delos integration")
- Simulated network contradicts "production" intent
- P2PGhostChannel achieves the same goal using existing VON infrastructure
- Maintaining both creates confusion and duplication
- No production usage found in codebase audit

### Implementation Pattern

**Testing**:
```java
// Unit tests: use InMemoryGhostChannel
var channel = new InMemoryGhostChannel<StringEntityID, Object>();
channel.onReceive((from, ghosts) -> processGhosts(ghosts));
```

**Production**:
```java
// Distributed simulation: use P2PGhostChannel
var vonBubble = new VonBubble(bubbleId, level, frameMs, transport);
var channel = new P2PGhostChannel<StringEntityID, Object>(vonBubble);
channel.onReceive((from, ghosts) -> processGhosts(ghosts));
```

### Related M2 Consolidation

This decision aligns with the broader M2 Ghost Layer Consolidation effort:
- Eliminates incomplete/duplicate implementations
- Clarifies testing vs production boundaries
- Reduces LOC from 3 implementations (701 LOC) to 2 (InMemory + P2P)
- Enables future consolidation of P2PGhostChannel with BubbleGhostManager

See `simulation/doc/GHOST_LAYER_CONSOLIDATION_ANALYSIS.md` for full consolidation plan.

## Future Work

### Inc 7+ (Multi-Host Distribution)

**Migration Enhancements**:
- Increase phase timeouts for WAN latency (100ms → 500ms)
- Network partition detection and healing
- Geographic-aware destination selection
- Cross-datacenter migration optimization

**Consensus Enhancements**:
- Real Fireflies cluster (not mock)
- STUN/NAT traversal for committee communication
- Dynamic committee resizing based on load
- Consensus latency optimization for WAN

**Monitoring**:
- Distributed tracing for migration lifecycle
- Consensus decision audit log
- Byzantine behavior detection dashboards
- SLA monitoring (migration latency, consensus quorum time)

## Related Documents

### ChromaDB References
- `architecture::distributed::migration` - Migration protocol details
- `decision::migration::phase-6b4-2pc-protocol` - 2PC decision rationale
- `audit::plan-auditor::phase-6b4-cross-process-migration-2026-01-08` - Phase 6B4 audit
- `plan::strategic-planner::inc6-distributed-multi-bubble-2026-01-08` - Inc 6 plan

### Implementation Plans
- `simulation/doc/PHASE_7G_DAY3_COMMITTEE_CONSENSUS_REDESIGN.md` - Consensus redesign
- `.pm/plans/H1_ENHANCED_BUBBLE_REFACTOR.md` - EnhancedBubble decomposition

### Git Commits
- `c05d5748` - Raft code deletion (Jan 10, 2026)
- `a16de8b6` - Committee consensus setup (Jan 10, 2026)
- `df1e695` - H3.7 Phase 1 Clock integration (Jan 12, 2026)

## Date

**Created**: 2026-01-15
**Last Updated**: 2026-01-15 (M2: Ghost Channel consolidation decision added)
**Reviewed By**: [Pending human review]

---

## Appendix: Timeouts and Configuration

### Migration Timeouts
```java
private static final long PHASE_TIMEOUT_MS = 100;   // Per-phase (PREPARE/COMMIT/ABORT)
private static final long TOTAL_TIMEOUT_MS = 300;   // Total migration
private static final long LOCK_TIMEOUT_MS = 50;     // Lock acquisition
```

### Idempotency Configuration
```java
private static final long TTL_MINUTES = 5;  // Idempotency token TTL
```

### Consensus Configuration
```java
// Committee selection parameters (from DynamicContextImpl)
double pByz = 0.1;     // Probability of Byzantine behavior
int bias = 2;          // Byzantine tolerance multiplier
double epsilon = 0.1;  // Tolerance factor

// Quorum calculation
int quorum = t + 1;    // where t = Byzantine tolerance
```

### Byzantine Tolerance Levels
| Cluster Size | t | Quorum | Committee Size |
|--------------|---|--------|----------------|
| 2-3 nodes | 0 | 1 | 5 |
| 4-7 nodes | 1 | 2 | 7 |
| 8+ nodes | 2 | 3 | 9-11 |

---

**End of ADR 001**
