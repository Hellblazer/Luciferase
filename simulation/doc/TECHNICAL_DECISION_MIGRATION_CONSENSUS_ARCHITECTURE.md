# Technical Decision: Migration/Consensus Architecture Layer Boundaries

**Last Updated**: 2026-01-15
**Date**: 2026-01-13
**Status**: Proposed
**Related Beads**: Luciferase-flod (M1: Create ADR for migration/consensus architecture)

## Executive Summary

This ADR addresses architectural overlap and unclear boundaries across 6+ coordinator classes managing entity migration, consensus, and process coordination. We propose a 4-layer architecture with clear responsibility boundaries to eliminate dual routing logic, dual retry mechanisms, and unclear delegation paths.

**Impact**: Affects 17 classes (2,421 LOC) across causality, distributed coordination, and consensus layers.

## Context

### Problem Statement

The distributed simulation subsystem has evolved organically, resulting in overlapping responsibilities across multiple coordinator classes:

**Coordinator Classes (6)**:
- `ProcessCoordinator` (171 LOC) - Process lifecycle management
- `CoordinatorElectionProtocol` (129 LOC) - Leader election
- `MigrationCoordinator` (257 LOC) - Migration state machine orchestration
- `CrossBubbleMigrationManager` (224 LOC) - Cross-bubble migration routing
- `GhostLayerManager` (183 LOC) - Ghost entity synchronization
- `ViewCommitteeConsensus` (187 LOC) - View-based voting

**Migration Classes (8)**:
- `CrossBubbleMigrationManager` (224 LOC) - Cross-bubble entity routing
- `MigrationCoordinator` (257 LOC) - State machine coordination
- `EntityMigrationStateMachine` (401 LOC) - State transitions (IDLE → PREPARE → COMMIT)
- `ConsensusAwareMigrator` (156 LOC) - Demo/prototype (not production)
- `MigrationMessage` (111 LOC) - Protocol messages
- `GhostLayerManager` (183 LOC) - Ghost synchronization
- `GhostLayerMessage` (62 LOC) - Ghost protocol messages
- `TwoBubbleSimulation` coordination (507 LOC) - Local simulation orchestration

**Consensus Classes (3)**:
- `ViewCommitteeConsensus` (187 LOC) - View-based voting protocol
- `CommitteeConsensusMessage` (94 LOC) - Consensus protocol messages
- `CoordinatorElectionProtocol` (129 LOC) - Leader election algorithm

**Total**: 17 classes, 2,421 LOC

### Identified Issues

#### Issue 1: Dual Zone Routing Logic

**Location**: Both `CrossBubbleMigrationManager` and `MigrationCoordinator` implement zone-based entity routing.

**CrossBubbleMigrationManager.java**:
```java
// Lines 117-123
private boolean shouldMigrate(EnhancedBubble.EntityRecord entity,
                               MigrationDirection direction, long currentTick) {
    float x = entity.position().x;
    if (direction == MigrationDirection.TO_BUBBLE_2) {
        return x >= (boundaryX + hysteresisDistance);  // Zone check
    } else {
        return x < (boundaryX - hysteresisDistance);   // Zone check
    }
}
```

**MigrationCoordinator** (inferred from class inventory):
```java
// Similar zone-based routing logic for distributed case
private UUID getZoneForEntity(String entityId, Point3f position) {
    // Zone determination logic
}
```

**Problem**: Two classes independently determine which bubble/zone owns an entity, risking inconsistent routing decisions.

#### Issue 2: Dual Retry Mechanisms

Both managers implement independent retry logic:

**CrossBubbleMigrationManager**:
```java
// Implicit retry via migration cooldown
private final ConcurrentHashMap<String, Long> migrationCooldowns;
private final int cooldownTicks;  // Prevents immediate retry after failure
```

**MigrationCoordinator**:
```java
// Explicit retry queue (inferred from pattern)
private static final int MAX_RETRIES = 3;
private final Map<String, Integer> retryAttempts;
```

**Problem**: No single source of truth for retry policy. An entity could be in cooldown in one manager while being retried by another.

#### Issue 3: Unclear Layer Boundaries

**Question**: When should `CrossBubbleMigrationManager` delegate to `MigrationCoordinator`?

**Current behavior**:
- `CrossBubbleMigrationManager` handles local two-bubble migrations directly
- `MigrationCoordinator` handles distributed multi-bubble migrations with state machines
- **Overlap**: Both handle entity routing, retry logic, and commit protocols

**Missing clarity**:
- At what scale does local become distributed?
- Should `CrossBubbleMigrationManager` always wrap `MigrationCoordinator` for consistency?
- How does ghost layer synchronization integrate with either manager?

#### Issue 4: Ghost Layer Independence

`GhostLayerManager` operates as an independent synchronization loop:

**GhostLayerManager.java**:
```java
public class GhostLayerManager {
    public void syncGhosts(long currentTick) {
        // Periodic ghost synchronization
        // No integration with EntityMigrationStateMachine states
    }

    public void expireGhosts(long currentTick) {
        // Independent TTL-based expiration
        // Not tied to migration commit/rollback
    }
}
```

**Problem**: Ghost entities are not integrated into the migration lifecycle. A migration could fail, but the ghost remains until TTL expiration rather than being immediately cleaned up.

**Expected Integration**:
```java
// Ideal: Ghost lifecycle tied to migration states
EntityMigrationStateMachine:
  PREPARE → Create ghost in target
  COMMIT  → Remove ghost (entity now real)
  ROLLBACK → Remove ghost (migration failed)
```

#### Issue 5: Consensus Demo Only

`ConsensusAwareMigrator` exists in demo package with no production integration:

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/demo/ConsensusAwareMigrator.java`

**Status**: Prototype demonstrating consensus-based migration but not used in production code paths.

**Problem**: Production migrations occur without consensus validation:
- `ViewCommitteeConsensus` exists but isn't called during migrations
- No mechanism to require majority approval before entity ownership transfer
- Risk of split-brain scenarios in multi-bubble distributed simulations

## Decision

We propose a **4-layer architecture** with clear responsibility boundaries and integration contracts:

### Layer 1: CAUSALITY (State Transitions)

**Owner**: `EntityMigrationStateMachine`
**Responsibility**: State transition logic (IDLE → PREPARE → COMMIT/ROLLBACK)

**Interface**:
```java
public enum MigrationState {
    IDLE,       // No migration in progress
    PREPARE,    // Validation phase (hysteresis, cooldown, ghost creation)
    COMMIT,     // Ownership transfer phase
    ROLLBACK    // Failure recovery phase
}

public interface MigrationLifecycleCallbacks {
    void onPrepareStart(String entityId);
    void onPrepareSuccess(String entityId, GhostEntry ghost);
    void onPrepareFailed(String entityId, String reason);

    void onCommitStart(String entityId);
    void onCommitSuccess(String entityId);
    void onCommitFailed(String entityId, String reason);

    void onRollbackStart(String entityId);
    void onRollbackComplete(String entityId);
}
```

**Integration**: Provides callback hooks for upper layers to implement routing, consensus, and ghost management.

### Layer 2: CROSS-BUBBLE INTEGRATION (Local vs Distributed Routing)

**Owner**: `CrossBubbleMigrationManager`
**Responsibility**: Entity-to-bubble routing decisions, hysteresis enforcement

**Consolidated Ownership**:
- **Zone routing logic**: Sole owner of `shouldMigrate()` and boundary calculations
- **Hysteresis enforcement**: Prevents boundary oscillation with configurable threshold
- **Local optimization**: Direct two-bubble migrations bypass distributed coordination

**Interface**:
```java
public interface MigrationRouter {
    /**
     * Determine target bubble for entity based on spatial position.
     * Single source of truth for zone-based routing.
     */
    Optional<UUID> determineTargetBubble(String entityId, Point3f position);

    /**
     * Check if entity has crossed hysteresis threshold.
     * Prevents migration until entity is hysteresisDistance past boundary.
     */
    boolean shouldMigrate(String entityId, Point3f position, UUID currentBubble);
}
```

**Delegation Rule**:
```java
// CrossBubbleMigrationManager decides: local or distributed?
public MigrationIntent checkMigration(String entityId, Point3f position) {
    var targetBubble = determineTargetBubble(entityId, position);

    if (isLocalMigration(targetBubble)) {
        // Execute directly (2-phase commit)
        return executeLocalMigration(entityId, targetBubble);
    } else {
        // Delegate to MigrationCoordinator (distributed coordination)
        return migrationCoordinator.initiateMigration(entityId, targetBubble);
    }
}
```

### Layer 3: DISTRIBUTED COORDINATION (Multi-Bubble Orchestration)

**Owner**: `MigrationCoordinator`
**Responsibility**: Distributed migration orchestration, retry logic, process coordination

**Consolidated Ownership**:
- **Retry mechanism**: Sole owner of retry policy (MAX_RETRIES = 3)
- **State machine management**: Coordinates `EntityMigrationStateMachine` instances
- **Process coordination**: Integrates with `ProcessCoordinator` for lifecycle events

**Interface**:
```java
public interface DistributedMigrationCoordinator {
    /**
     * Initiate distributed migration with retry logic.
     * Single source of truth for retry policy.
     */
    MigrationIntent initiateMigration(String entityId, UUID targetBubble);

    /**
     * Execute migration with automatic retry on transient failures.
     * Implements exponential backoff and MAX_RETRIES limit.
     */
    CompletableFuture<MigrationResult> executeMigration(MigrationIntent intent);

    /**
     * Query migration state for debugging/monitoring.
     */
    MigrationState getMigrationState(String entityId);
}
```

**Integration with Ghost Layer**:
```java
// MigrationCoordinator implements MigrationLifecycleCallbacks
@Override
public void onPrepareSuccess(String entityId, GhostEntry ghost) {
    // Callback: EntityMigrationStateMachine entered PREPARE state
    ghostLayerManager.createGhost(ghost);
}

@Override
public void onCommitSuccess(String entityId) {
    // Callback: EntityMigrationStateMachine entered COMMIT state
    ghostLayerManager.removeGhost(entityId);  // Entity now real in target
}

@Override
public void onRollbackComplete(String entityId) {
    // Callback: EntityMigrationStateMachine entered ROLLBACK state
    ghostLayerManager.removeGhost(entityId);  // Clean up failed migration
}
```

**Ghost Layer Manager** (integrated):
```java
public class GhostLayerManager {
    // No longer runs independent sync loop
    // Responds to lifecycle callbacks from MigrationCoordinator

    public void createGhost(GhostEntry ghost) {
        // Called during PREPARE phase
    }

    public void removeGhost(String entityId) {
        // Called during COMMIT or ROLLBACK phase
    }

    // TTL-based expiration remains as safety net for orphaned ghosts
    public void expireGhosts(long currentTick) {
        // Cleanup orphaned ghosts from crashes/network failures
    }
}
```

### Layer 4: CONSENSUS (Distributed Agreement)

**Owner**: `ConsensusMigrationCoordinator` (new wrapper class)
**Responsibility**: Consensus validation before migration commit

**Interface**:
```java
public class ConsensusMigrationCoordinator implements DistributedMigrationCoordinator {
    private final MigrationCoordinator delegate;
    private final ViewCommitteeConsensus consensus;

    @Override
    public CompletableFuture<MigrationResult> executeMigration(MigrationIntent intent) {
        // 1. Prepare phase (delegate to MigrationCoordinator)
        var prepareResult = delegate.prepareInternal(intent);
        if (!prepareResult.success()) {
            return CompletableFuture.completedFuture(prepareResult);
        }

        // 2. Consensus phase (NEW: require majority approval)
        var proposal = new MigrationProposal(intent.entityId(), intent.targetBubble());
        var approved = consensus.proposeAndVote(proposal).get();  // Blocking

        if (!approved) {
            // Rollback: consensus rejected migration
            delegate.rollbackInternal(intent);
            return CompletableFuture.completedFuture(
                MigrationResult.failure(intent.entityId(), "Consensus rejected")
            );
        }

        // 3. Commit phase (delegate to MigrationCoordinator)
        return delegate.commitInternal(intent);
    }
}
```

**Production Path**:
```java
// Replace direct MigrationCoordinator usage with consensus wrapper
var migrationCoordinator = new ConsensusMigrationCoordinator(
    new MigrationCoordinator(...),
    new ViewCommitteeConsensus(...)
);
```

**Integration**: Wraps `MigrationCoordinator`, adding consensus validation between PREPARE and COMMIT phases.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 4: CONSENSUS (Distributed Agreement)                      │
│                                                                  │
│  ConsensusMigrationCoordinator (NEW)                            │
│    ├─ Wraps: MigrationCoordinator                               │
│    ├─ Adds: Consensus validation (ViewCommitteeConsensus)       │
│    └─ Ensures: Majority approval before COMMIT                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓ delegates to
┌─────────────────────────────────────────────────────────────────┐
│ Layer 3: DISTRIBUTED (Multi-Bubble Orchestration)               │
│                                                                  │
│  MigrationCoordinator                                            │
│    ├─ Owns: Retry logic (MAX_RETRIES = 3)                       │
│    ├─ Coordinates: EntityMigrationStateMachine instances         │
│    └─ Integrates: GhostLayerManager via callbacks               │
│                                                                  │
│  GhostLayerManager (integrated)                                  │
│    ├─ Responds to: Migration lifecycle callbacks                │
│    ├─ Creates ghosts: During PREPARE phase                      │
│    └─ Removes ghosts: During COMMIT or ROLLBACK                 │
│                                                                  │
│  ProcessCoordinator                                              │
│    └─ Manages: Process lifecycle (start/stop/crash handling)    │
└─────────────────────────────────────────────────────────────────┘
                              ↓ delegates to
┌─────────────────────────────────────────────────────────────────┐
│ Layer 2: CROSS-BUBBLE INTEGRATION (Local vs Distributed)        │
│                                                                  │
│  CrossBubbleMigrationManager                                     │
│    ├─ Owns: Zone routing logic (shouldMigrate)                  │
│    ├─ Enforces: Hysteresis threshold                            │
│    ├─ Optimizes: Local 2-bubble migrations (direct)             │
│    └─ Delegates: Distributed migrations to Layer 3              │
└─────────────────────────────────────────────────────────────────┘
                              ↓ uses
┌─────────────────────────────────────────────────────────────────┐
│ Layer 1: CAUSALITY (State Transitions)                          │
│                                                                  │
│  EntityMigrationStateMachine                                     │
│    ├─ States: IDLE → PREPARE → COMMIT/ROLLBACK                  │
│    ├─ Provides: Lifecycle callbacks                             │
│    └─ Guarantees: Atomic state transitions                      │
└─────────────────────────────────────────────────────────────────┘
```

## Consequences

### Positive

1. **Single Source of Truth**:
   - Zone routing: `CrossBubbleMigrationManager.shouldMigrate()`
   - Retry policy: `MigrationCoordinator.executeMigration()`
   - State transitions: `EntityMigrationStateMachine`

2. **Clear Delegation Path**:
   - Local migrations: `CrossBubbleMigrationManager` → direct execution
   - Distributed migrations: `CrossBubbleMigrationManager` → `MigrationCoordinator` → `EntityMigrationStateMachine`
   - Consensus migrations: `ConsensusMigrationCoordinator` wrapper → adds voting phase

3. **Integrated Ghost Lifecycle**:
   - Ghosts created during PREPARE phase (via callback)
   - Ghosts removed during COMMIT phase (via callback)
   - Ghosts removed during ROLLBACK phase (via callback)
   - TTL-based expiration as safety net for orphaned ghosts

4. **Production Consensus Path**:
   - `ConsensusMigrationCoordinator` wrapper provides drop-in consensus
   - No code changes to `MigrationCoordinator` required
   - Gradual rollout: start with non-consensus, add wrapper when ready

5. **Testability**:
   - Each layer independently testable via interfaces
   - Mock callbacks for state machine testing
   - Deterministic Clock injection for time-based logic

### Negative

1. **Refactoring Required**:
   - Extract duplicate zone routing logic from `MigrationCoordinator` to `CrossBubbleMigrationManager`
   - Convert `GhostLayerManager` from independent loop to callback-driven
   - Create `ConsensusMigrationCoordinator` wrapper class
   - Update delegation logic in `CrossBubbleMigrationManager`

2. **Performance Overhead**:
   - Callback indirection adds method call overhead (negligible)
   - Consensus voting adds network round-trip (acceptable for correctness)

3. **Migration Risk**:
   - Existing tests assume independent ghost synchronization loop
   - Need to verify callback ordering during state transitions
   - Consensus wrapper adds complexity to production deployment

### Mitigation Strategies

1. **Incremental Migration**:
   - Phase 1: Extract zone routing to `CrossBubbleMigrationManager` (no behavior change)
   - Phase 2: Add lifecycle callbacks to `EntityMigrationStateMachine` (no callers yet)
   - Phase 3: Integrate `GhostLayerManager` with callbacks (preserve TTL safety net)
   - Phase 4: Create `ConsensusMigrationCoordinator` wrapper (opt-in)

2. **Test Coverage**:
   - Add integration test: `MigrationCoordinatorCallbackTest` (verify ghost lifecycle)
   - Add integration test: `ConsensusMigrationCoordinatorTest` (verify voting before commit)
   - Preserve existing `GhostLayerManagerTest` (verify TTL cleanup still works)

3. **Feature Flags**:
   - `enable-consensus-migration`: Toggle consensus wrapper in production
   - `ghost-layer-ttl-seconds`: Configure safety net TTL (default: 60s)

## Alternatives Considered

### Alternative 1: Merge All Coordinators into Single Class

**Approach**: Create `UnifiedMigrationCoordinator` with 1000+ LOC handling all layers.

**Rejected because**:
- Violates Single Responsibility Principle
- Hard to test (mocking all dependencies)
- Difficult to understand and maintain
- No clear upgrade path for consensus

### Alternative 2: Keep Current Architecture, Document Only

**Approach**: Add comments explaining when to use each coordinator.

**Rejected because**:
- Doesn't eliminate duplicate routing logic
- Doesn't integrate ghost lifecycle with migrations
- Doesn't provide production consensus path
- Documentation rot inevitable as code evolves

### Alternative 3: Event-Driven Architecture

**Approach**: Replace direct method calls with event bus (`MigrationEvent`, `GhostEvent`, etc.).

**Rejected because**:
- Adds complexity (event ordering, replay, debugging)
- Harder to reason about causality (which event triggered what?)
- Performance overhead of event serialization/dispatch
- Overkill for deterministic state machine transitions

## Implementation Plan

### Phase 1: Extract Zone Routing (Week 1)

**Goal**: Consolidate zone routing logic in `CrossBubbleMigrationManager`.

**Tasks**:
1. Move `shouldMigrate()` logic from `MigrationCoordinator` to `CrossBubbleMigrationManager`
2. Make `MigrationCoordinator` delegate to `CrossBubbleMigrationManager.shouldMigrate()`
3. Add integration test verifying identical behavior
4. Bead: Create `Luciferase-????` for zone routing extraction

**Success Criteria**:
- Zero duplicate zone logic
- All existing tests pass
- No behavior change

### Phase 2: Add Lifecycle Callbacks (Week 1-2)

**Goal**: Add callback interface to `EntityMigrationStateMachine`.

**Tasks**:
1. Define `MigrationLifecycleCallbacks` interface
2. Add callback invocations to `EntityMigrationStateMachine` state transitions
3. Create no-op default implementation
4. Add unit test: `MigrationLifecycleCallbackTest`
5. Bead: Create `Luciferase-????` for lifecycle callbacks

**Success Criteria**:
- Callbacks invoked at correct state transitions
- No callers yet (no behavior change)
- All existing tests pass

### Phase 3: Integrate Ghost Layer (Week 2)

**Goal**: Convert `GhostLayerManager` from independent loop to callback-driven.

**Tasks**:
1. Implement `MigrationLifecycleCallbacks` in `MigrationCoordinator`
2. Call `ghostLayerManager.createGhost()` from `onPrepareSuccess()`
3. Call `ghostLayerManager.removeGhost()` from `onCommitSuccess()` and `onRollbackComplete()`
4. Preserve TTL-based `expireGhosts()` as safety net
5. Update `GhostLayerManagerTest` to verify callback integration
6. Bead: Create `Luciferase-????` for ghost layer integration

**Success Criteria**:
- Ghosts created/removed synchronously with state transitions
- TTL cleanup still works for orphaned ghosts
- All existing tests pass

### Phase 4: Create Consensus Wrapper (Week 2-3)

**Goal**: Enable opt-in consensus validation for migrations.

**Tasks**:
1. Create `ConsensusMigrationCoordinator` class
2. Implement consensus voting between PREPARE and COMMIT
3. Add feature flag: `enable-consensus-migration`
4. Add integration test: `ConsensusMigrationCoordinatorTest`
5. Update documentation with consensus usage examples
6. Bead: Create `Luciferase-????` for consensus wrapper

**Success Criteria**:
- Consensus wrapper functional and tested
- Feature flag toggles wrapper on/off
- No impact when feature flag disabled

## References

### Related Beads
- **Luciferase-flod**: M1 - Create ADR for migration/consensus architecture (this document)
- **Luciferase-me4d**: M2 - Consolidate Ghost Layer implementations (depends on this ADR)

### Related Documents
- `simulation/doc/ARCHITECTURE_DISTRIBUTED.md` - Distributed simulation overview
- `simulation/doc/V3_GHOST_MANAGER_DECISION.md` - Ghost layer design history
- `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/CrossBubbleMigrationManager.java` - Layer 2 implementation
- `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/EntityMigrationStateMachine.java` - Layer 1 implementation

### External References
- Two-Phase Commit Protocol: [Wikipedia](https://en.wikipedia.org/wiki/Two-phase_commit_protocol)
- Consensus Algorithms: Raft, Paxos
- Distributed Systems Patterns: [martinfowler.com](https://martinfowler.com/articles/patterns-of-distributed-systems/)

---

**Approval**: Pending review and discussion
**Next Steps**: Phase 1 implementation (zone routing extraction)
