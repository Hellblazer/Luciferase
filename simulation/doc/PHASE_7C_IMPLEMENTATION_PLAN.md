# Phase 7C Implementation Plan: Causality & Fireflies Integration

**Last Updated**: 2026-01-09
**Status**: CONDITIONAL GO (Audit Complete)
**Audit**: PHASE_7C_PLAN_AUDIT.md - BLOCKER: Fix Phase 7B test first
**Epic Bead**: Luciferase-qq0i
**Duration**: 10 days (2026-01-16 to 2026-01-29)
**Blocked By**: Phase 7A (COMPLETE), Phase 7B (COMPLETE)

---

## Executive Summary

Phase 7C is the most complex phase in Inc7, implementing causality preservation and view-based migration coordination. It adds Lamport clock-based event ordering, bounded-lookahead event reprocessing, Fireflies view monitoring, and a comprehensive entity migration state machine.

**Key Insight**: Each bubble is fully autonomous with its own RealTimeController. Coordination is about eventual consistency, not atomic guarantees. We leverage Fireflies for view stability detection rather than building custom consensus.

---

## Architecture Overview

### Data Flow with Causality

```
Sending Bubble:
  1. RealTimeController.onTick() -> LamportClockGenerator.tick()
  2. EntityUpdateEvent created with Lamport timestamp
  3. CausalityPreserver.markProcessed(event)
  4. DelosSocketTransport.send(event)

Receiving Bubble:
  1. DelosSocketTransport.receive(event)
  2. LamportClockGenerator.onRemoteEvent(event.lamportClock, event.sourceBubble)
  3. EventReprocessor.queueEvent(event)
  4. EventReprocessor.processReady() -> for each ready event:
     a. CausalityPreserver.canProcess(event) -> true
     b. GhostStateManager.updateGhost(event)
     c. CausalityPreserver.markProcessed(event)
  5. If canProcess() -> false: event stays queued
```

### Entity Migration with View Stability

```
                         ┌─────────────────────────────────┐
                         │                                 │
                         v                                 │
    ┌─────────┐  boundary  ┌───────────────┐  commit    ┌─────────┐
    │  OWNED  │──crossing──>│ MIGRATING_OUT │──────────>│ DEPARTED │
    └─────────┘            └───────────────┘            └────┬────┘
         ^                        │                          │
         │                        │ timeout/                 │ receive
         │                        │ view_change              │ ghost
    ┌────┴──────────┐             v                          v
    │ ROLLBACK_OWNED│<────────────────────────────     ┌─────────┐
    └───────────────┘                                  │  GHOST  │
                                                       └────┬────┘
                                                            │ boundary
                                                            │ crossing
                                                            v
    ┌─────────┐  transfer   ┌───────────────┐         ┌────────────┐
    │  OWNED  │<────────────│ MIGRATING_IN  │<────────│   (claim)  │
    └─────────┘             └───────────────┘         └────────────┘
         ^                        │
         │                        │ timeout/view_change
         │                        v
         └────────────────── GHOST (stay)
```

### Fireflies View Integration

```
Fireflies View:
  - Active members = bubbles in cluster
  - View change = member join/leave
  - View ID = hash of membership set

FirefliesViewMonitor:
  - Tracks DynamicContext.active()
  - Counts ticks since last view change
  - isViewStable() = true when stable for N ticks (N=3 default)

Migration Coordination:
  - MIGRATING_OUT -> DEPARTED: requires view stable
  - MIGRATING_IN -> OWNED: requires view stable
  - View change triggers ROLLBACK
```

---

## Sub-Phase Breakdown

### Phase 7C.1: LamportClockGenerator Enhancement (1.5 days)

**Objective**: Enhance RealTimeController's Lamport clock with vector timestamp support.

**Deliverables**:

| File | Type | LOC | Description |
|------|------|-----|-------------|
| `simulation/src/main/java/.../causality/LamportClockGenerator.java` | NEW | ~200 | Per-bubble logical clock with vector timestamps |
| `simulation/src/test/java/.../causality/LamportClockGeneratorTest.java` | NEW | ~300 | 15 comprehensive tests |
| `RealTimeController.java` | MODIFY | ~50 | Replace AtomicLong with LamportClockGenerator |

**Key Design**:

```java
public class LamportClockGenerator {
    private final UUID bubbleId;
    private final AtomicLong localClock;
    private final ConcurrentHashMap<UUID, Long> vectorTimestamp;

    // Increment on local event
    public long tick() {
        return localClock.incrementAndGet();
    }

    // Update on remote event: max(local, remote) + 1
    public long onRemoteEvent(long remoteClock, UUID sourceBubble) {
        vectorTimestamp.merge(sourceBubble, remoteClock, Math::max);
        return localClock.updateAndGet(current -> Math.max(current, remoteClock) + 1);
    }

    // Get current vector timestamp for causal ordering
    public Map<UUID, Long> getVectorTimestamp() {
        var result = new HashMap<>(vectorTimestamp);
        result.put(bubbleId, localClock.get());
        return Collections.unmodifiableMap(result);
    }
}
```

**Tests (15)**:
- testInitialClockAtZero
- testTickIncrementsLocalClock
- testOnLocalEventIncrementsAndReturns
- testOnRemoteEventMaxPlusOne
- testRemoteHigherThanLocal
- testRemoteLowerThanLocal
- testVectorTimestampTracking
- testMultipleBubbleVectorClocks
- testVectorTimestampMerge
- testConcurrentClockUpdates
- testClockMonotonicity
- testIntegrationWithRealTimeController
- testSerializationOfVectorTimestamp
- testOverflowHandling
- testDeterminismWithSameSeed

**Success Criteria**:
- Lamport clock increments correctly on local and remote events
- Vector timestamps accurately track all known bubbles
- Backward compatible with existing RealTimeController tests

**Dependencies**: None (foundation component)

---

### Phase 7C.2: EventReprocessor with Bounded Lookahead (2 days)

**Objective**: Queue out-of-order events and reprocess when dependencies satisfied.

**Deliverables**:

| File | Type | LOC | Description |
|------|------|-----|-------------|
| `simulation/src/main/java/.../causality/EventReprocessor.java` | NEW | ~350 | Bounded reprocessing queue |
| `simulation/src/main/java/.../causality/EventDependency.java` | NEW | ~80 | Event dependency record |
| `simulation/src/test/java/.../causality/EventReprocessorTest.java` | NEW | ~400 | 20 comprehensive tests |

**Key Design**:

```java
public class EventReprocessor<E extends CausalEvent> {
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final Duration DEFAULT_LOOKAHEAD = Duration.ofMillis(300);

    private final PriorityQueue<E> pendingEvents; // Ordered by Lamport clock
    private final Map<EventDependency, Set<E>> waitingForDependency;
    private final Duration lookaheadWindow;
    private final Consumer<E> eventHandler;

    // Queue event for potential reprocessing
    public void queueEvent(E event) {
        if (pendingEvents.size() >= MAX_QUEUE_SIZE) {
            // Drop oldest event beyond lookahead
            evictOldest();
        }
        pendingEvents.add(event);
    }

    // Process all events with satisfied dependencies
    public int processReady(long currentTime) {
        int processed = 0;
        while (!pendingEvents.isEmpty()) {
            var event = pendingEvents.peek();
            if (!isDependencySatisfied(event)) break;
            if (isExpired(event, currentTime)) {
                pendingEvents.poll(); // Drop expired
                continue;
            }
            pendingEvents.poll();
            eventHandler.accept(event);
            processed++;
        }
        return processed;
    }
}

public record EventDependency(
    long requiredLamportClock,
    UUID requiredSourceBubble
) {}
```

**Tests (20)**:
- testEmptyQueueReturnsNothing
- testSingleEventProcessedImmediately
- testOutOfOrderEventQueued
- testDependencySatisfiedTriggerProcess
- testLookaheadWindowBoundsQueue
- testQueueMaxSizeEnforced
- testEventsOrderedByLamportClock
- testMultipleDependenciesAllRequired
- testReprocessingCallsHandler
- testEventExpiredBeyondWindow
- testConcurrentQueueAccess
- testEventWithNoDependencies
- testCircularDependencyDetection
- testPerformanceWith1000Events
- testIntegrationWithRealTimeController
- testWindowAdjustment
- testQueuePersistenceAcrossTicks
- testDuplicateEventRejection
- testEventCancellation
- testMetricsTracking

**Success Criteria**:
- Out-of-order events queued correctly
- Events processed in Lamport clock order when dependencies met
- Queue bounded to 1000 events max
- No memory leaks in long-running tests

**Dependencies**: Phase 7C.1 (LamportClockGenerator)

---

### Phase 7C.3: CausalityPreserver (2 days)

**Objective**: Enforce partial order of events within lookahead window, prevent state corruption.

**Deliverables**:

| File | Type | LOC | Description |
|------|------|-----|-------------|
| `simulation/src/main/java/.../causality/CausalityPreserver.java` | NEW | ~250 | Causality enforcement |
| `simulation/src/test/java/.../causality/CausalityPreserverTest.java` | NEW | ~350 | 15 comprehensive tests |

**Key Design**:

```java
public class CausalityPreserver {
    // Track highest processed Lamport clock per source bubble
    private final ConcurrentHashMap<UUID, Long> processedClocks;
    // Track events we've seen but not yet processed
    private final Set<Long> pendingClocks;

    // Check if event can be processed without violating causality
    public boolean canProcess(CausalEvent event) {
        // Event is ready if we've processed all events with lower Lamport clocks
        // from the same source, OR the event has no dependencies
        long eventClock = event.lamportClock();
        UUID source = event.sourceBubble();
        Long processed = processedClocks.get(source);
        return processed == null || eventClock == processed + 1 || eventClock <= processed;
    }

    // Mark event as processed, update causal state
    public void markProcessed(CausalEvent event) {
        processedClocks.merge(event.sourceBubble(), event.lamportClock(), Math::max);
        pendingClocks.remove(event.lamportClock());
    }

    // Detect causality violation
    public Optional<CausalityViolation> detectViolation(CausalEvent event) {
        if (!canProcess(event)) {
            Long expected = processedClocks.getOrDefault(event.sourceBubble(), 0L) + 1;
            return Optional.of(new CausalityViolation(
                event.lamportClock(),
                expected,
                event.sourceBubble()
            ));
        }
        return Optional.empty();
    }
}

public record CausalityViolation(
    long receivedClock,
    long expectedClock,
    UUID sourceBubble
) {}
```

**Tests (15)**:
- testCanProcessCausallyFirst
- testCannotProcessBeforeDependency
- testMarkProcessedUpdatesState
- testViolationDetected
- testConcurrentEventsAllowed
- testCausalChainEnforced
- testPartialOrderPreserved
- testMultipleBubbleCausality
- testViolationRecovery
- testIntegrationWithEventReprocessor
- testStateResetOnRollback
- testPerformanceWithManyEvents
- testMemoryBoundedHistory
- testCausalityAcrossViewChange
- testDeterministicOrdering

**Success Criteria**:
- Events processed in causal order
- Violations detected and reported
- No state corruption from out-of-order processing
- Memory bounded (no unbounded history retention)

**Dependencies**: Phase 7C.2 (EventReprocessor)

---

### Phase 7C.4: FirefliesViewMonitor (1.5 days)

**Objective**: Monitor Fireflies view for active bubbles and view stability.

**Deliverables**:

| File | Type | LOC | Description |
|------|------|-----|-------------|
| `simulation/src/main/java/.../fireflies/FirefliesViewMonitor.java` | NEW | ~300 | View stability monitoring |
| `simulation/src/main/java/.../fireflies/ViewChangeEvent.java` | NEW | ~50 | View change event record |
| `simulation/src/test/java/.../fireflies/FirefliesViewMonitorTest.java` | NEW | ~350 | 15 tests with mock Fireflies |

**Key Design**:

```java
public class FirefliesViewMonitor {
    private static final int DEFAULT_STABILITY_THRESHOLD = 3; // ticks

    private final DynamicContext<?> context; // Fireflies context
    private final int stabilityThreshold;
    private final List<ViewChangeListener> listeners;

    private volatile Set<UUID> currentActiveBubbles;
    private volatile Digest currentViewId;
    private volatile int ticksSinceLastChange;

    // Called each simulation tick
    public void tick() {
        var newActive = context.active()
            .map(Member::getId)
            .map(Digest::toUUID)
            .collect(Collectors.toSet());

        if (!newActive.equals(currentActiveBubbles)) {
            var added = new HashSet<>(newActive);
            added.removeAll(currentActiveBubbles);
            var removed = new HashSet<>(currentActiveBubbles);
            removed.removeAll(newActive);

            var event = new ViewChangeEvent(added, removed, context.getId(), System.currentTimeMillis());
            notifyListeners(event);

            currentActiveBubbles = newActive;
            ticksSinceLastChange = 0;
        } else {
            ticksSinceLastChange++;
        }
    }

    // Check if view has been stable for threshold ticks
    public boolean isViewStable() {
        return ticksSinceLastChange >= stabilityThreshold;
    }

    // Get currently active bubbles
    public Set<UUID> getActiveBubbles() {
        return Collections.unmodifiableSet(currentActiveBubbles);
    }
}

public record ViewChangeEvent(
    Set<UUID> addedBubbles,
    Set<UUID> removedBubbles,
    Digest newViewId,
    long timestamp
) {}
```

**Tests (15)**:
- testInitialViewEmpty
- testBubbleJoinDetected
- testBubbleLeaveDetected
- testViewStableAfterNTicks
- testViewUnstableOnChange
- testMultipleChangesResetStability
- testListenerNotification
- testActiveBubblesAccurate
- testViewIdChangesOnMembership
- testIntegrationWithMockFireflies
- testConcurrentViewUpdates
- testStabilityCounterReset
- testRapidChangeHandling
- testPartitionDetection
- testRecoveryFromPartition

**Success Criteria**:
- View changes detected within 1 tick
- Stability threshold configurable
- Listeners notified of all view changes
- Works with mock and real Fireflies

**Dependencies**: None (can run parallel with 7C.1-7C.2)

---

### Phase 7C.5: Entity Migration State Machine (2 days)

**Objective**: Implement comprehensive entity ownership tracking with view-stability-based migration.

**Deliverables**:

| File | Type | LOC | Description |
|------|------|-----|-------------|
| `simulation/src/main/java/.../migration/EntityMigrationState.java` | NEW | ~100 | State enum with transitions |
| `simulation/src/main/java/.../migration/EntityMigrationStateMachine.java` | NEW | ~400 | Per-entity state tracking |
| `simulation/src/main/java/.../migration/MigrationCoordinatorEnhanced.java` | NEW | ~250 | View-aware coordinator |
| `simulation/src/test/java/.../migration/EntityMigrationStateMachineTest.java` | NEW | ~500 | 25 comprehensive tests |
| `TetrahedralMigration.java` | MODIFY | ~50 | Integrate state machine |
| `CrossProcessMigration.java` | MODIFY | ~50 | Add view stability checks |

**Key Design**:

```java
public enum EntityMigrationState {
    OWNED,          // This bubble is authoritative
    MIGRATING_OUT,  // Entity leaving, pending acceptance elsewhere
    DEPARTED,       // Entity left, no longer tracked
    GHOST,          // Remote entity, local copy for rendering
    MIGRATING_IN,   // Entity arriving, pending acceptance
    ROLLBACK_OWNED  // Migration failed, restored to owned
}

public class EntityMigrationStateMachine {
    private final ConcurrentHashMap<EntityID, EntityMigrationState> entityStates;
    private final ConcurrentHashMap<EntityID, MigrationContext> migrationContexts;
    private final FirefliesViewMonitor viewMonitor;

    // Attempt state transition
    public TransitionResult transition(EntityID entity, EntityMigrationState newState) {
        var current = entityStates.get(entity);
        if (!isValidTransition(current, newState)) {
            return TransitionResult.invalid(current, newState);
        }

        // Check view stability for commit transitions
        if (requiresViewStability(newState) && !viewMonitor.isViewStable()) {
            return TransitionResult.blocked("View not stable");
        }

        entityStates.put(entity, newState);
        logTransition(entity, current, newState);
        return TransitionResult.success();
    }

    // Called when view changes - may trigger rollbacks
    public void onViewChange(ViewChangeEvent event) {
        // Rollback any MIGRATING_OUT to ROLLBACK_OWNED
        entityStates.entrySet().stream()
            .filter(e -> e.getValue() == EntityMigrationState.MIGRATING_OUT)
            .forEach(e -> {
                entityStates.put(e.getKey(), EntityMigrationState.ROLLBACK_OWNED);
                log.info("View change: rolling back {} to ROLLBACK_OWNED", e.getKey());
            });

        // MIGRATING_IN stays as GHOST
        entityStates.entrySet().stream()
            .filter(e -> e.getValue() == EntityMigrationState.MIGRATING_IN)
            .forEach(e -> {
                entityStates.put(e.getKey(), EntityMigrationState.GHOST);
                log.info("View change: {} remains as GHOST", e.getKey());
            });
    }

    // Invariant check: exactly one OWNED per entity globally
    public boolean verifyInvariant(EntityID entity, Set<UUID> allBubbles) {
        // Implementation would coordinate across bubbles
        // For local check: at most one of {OWNED, MIGRATING_IN}
        var state = entityStates.get(entity);
        return state != EntityMigrationState.OWNED ||
               state != EntityMigrationState.MIGRATING_IN;
    }
}
```

**State Transitions**:

| From | To | Trigger | Guard |
|------|-----|---------|-------|
| OWNED | MIGRATING_OUT | Boundary crossing | View stable, target identified |
| MIGRATING_OUT | DEPARTED | COMMIT_ACK received | View stable |
| MIGRATING_OUT | ROLLBACK_OWNED | Timeout/view_change/NACK | None |
| DEPARTED | GHOST | Ghost update received | None |
| GHOST | MIGRATING_IN | Boundary crossing (back) | View stable |
| MIGRATING_IN | OWNED | TRANSFER received | View stable |
| MIGRATING_IN | GHOST | Timeout/view_change/NACK | None |
| ROLLBACK_OWNED | OWNED | Automatic | None |

**Tests (25)**:
- testInitialStateOwned
- testOwnedToMigratingOut
- testMigratingOutToDeparted
- testMigratingOutToRollback
- testDepartedToGhost
- testGhostToMigratingIn
- testMigratingInToOwned
- testMigratingInToGhost
- testRollbackToOwned
- testInvalidTransitionRejected
- testInvariantExactlyOneOwned
- testViewChangeTriggersRollback
- testViewStableAllowsCommit
- testTimeoutTriggersRollback
- testConcurrentMigrations
- testStatePersistence
- testRecoveryAfterCrash
- testIntegrationWithTetrahedralMigration
- testIntegrationWithCrossProcessMigration
- testMetricsTracking
- testAuditLog
- testMultiEntityMigration
- testMigrationDuringPartition
- testMigrationAfterRecovery
- testEndToEndMigrationFlow

**Success Criteria**:
- All state transitions validated
- Invariant enforced (exactly one OWNED per entity)
- View changes trigger appropriate rollbacks
- No lost or duplicated entities

**Dependencies**: Phase 7C.3 (CausalityPreserver), Phase 7C.4 (FirefliesViewMonitor)

---

### Phase 7C.6: Integration Testing (1 day)

**Objective**: Validate complete Phase 7C functionality with multi-bubble scenario.

**Deliverables**:

| File | Type | LOC | Description |
|------|------|-----|-------------|
| `simulation/src/test/java/.../integration/FourBubbleCausalityTest.java` | NEW | ~600 | 4-bubble E2E test |
| `simulation/src/test/java/.../integration/ViewChangeHandlingTest.java` | NEW | ~400 | View change scenarios |

**Test Scenarios**:

**FourBubbleCausalityTest (6 tests)**:
1. **testFourBubbleSetup**: 4 bubbles initialize correctly with RealTimeController and all Phase 7C components
2. **testOutOfOrderEventsReprocessed**: Send events out of Lamport clock order, verify reprocessing
3. **testCausalityMaintained1000Ticks**: Run 1000 ticks, verify no causality violations
4. **testEntityRetention100Percent**: Start with 100 entities, verify all retained after 1000 ticks
5. **testPerformanceTPS94**: Verify throughput >= 94 TPS
6. **testDeterminism3Runs**: Same seed produces identical results across 3 runs

**ViewChangeHandlingTest (4 tests)**:
1. **testViewChangeDuringMigration**: Bubble leaves during MIGRATING_OUT, verify rollback
2. **testMigrationAfterViewStable**: Migration completes after view stabilizes
3. **testMultipleViewChanges**: Rapid join/leave, verify no lost entities
4. **testPartitionAndRecovery**: Simulate partition, verify recovery

**Test Configuration**:
```java
@BeforeEach
void setup() {
    // Create 4 bubbles with Phase 7C components
    for (int i = 0; i < 4; i++) {
        var bubbleId = UUID.randomUUID();
        var clockGen = new LamportClockGenerator(bubbleId);
        var controller = new RealTimeController(bubbleId, "bubble-" + i, 100);
        var reprocessor = new EventReprocessor<>(event -> processEvent(event));
        var causality = new CausalityPreserver();
        var viewMonitor = new FirefliesViewMonitor(mockContext, 3);
        var stateMachine = new EntityMigrationStateMachine(viewMonitor);

        bubbles.add(new TestBubble(bubbleId, clockGen, controller, reprocessor,
                                   causality, viewMonitor, stateMachine));
    }
}
```

**Success Criteria**:
- All 4 bubbles communicate correctly
- Out-of-order events reprocessed without violations
- 1000-tick run completes successfully
- 100% entity retention
- TPS >= 94
- 3 deterministic runs match

**Dependencies**: All previous sub-phases

---

## Dependency Graph

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│  ┌───────────────┐               ┌────────────────────────┐     │
│  │ Phase 7C.1    │               │ Phase 7C.4             │     │
│  │ LamportClock  │               │ FirefliesViewMonitor   │     │
│  │ (1.5 days)    │               │ (1.5 days)             │     │
│  └───────┬───────┘               └───────────┬────────────┘     │
│          │                                   │                   │
│          v                                   │                   │
│  ┌───────────────┐                           │                   │
│  │ Phase 7C.2    │                           │                   │
│  │ EventReproc.  │                           │                   │
│  │ (2 days)      │                           │                   │
│  └───────┬───────┘                           │                   │
│          │                                   │                   │
│          v                                   │                   │
│  ┌───────────────┐                           │                   │
│  │ Phase 7C.3    │                           │                   │
│  │ CausalityPres │                           │                   │
│  │ (2 days)      │                           │                   │
│  └───────┬───────┘                           │                   │
│          │                                   │                   │
│          └───────────────┬───────────────────┘                   │
│                          │                                       │
│                          v                                       │
│                  ┌───────────────┐                               │
│                  │ Phase 7C.5    │                               │
│                  │ Migration SM  │                               │
│                  │ (2 days)      │                               │
│                  └───────┬───────┘                               │
│                          │                                       │
│                          v                                       │
│                  ┌───────────────┐                               │
│                  │ Phase 7C.6    │                               │
│                  │ Integration   │                               │
│                  │ (1 day)       │                               │
│                  └───────────────┘                               │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**Critical Path**: 7C.1 -> 7C.2 -> 7C.3 -> 7C.5 -> 7C.6 (8.5 days)
**Parallel Path**: 7C.4 runs alongside 7C.1-7C.2 (1.5 days, no slack needed)

---

## Integration Points with Phase 7A/7B

### Phase 7A Integration (RealTimeController)

**Modifications to RealTimeController.java**:
1. Replace `AtomicLong lamportClock` with `LamportClockGenerator clockGenerator`
2. Update `tickLoop()` to use `clockGenerator.tick()`
3. Update `updateLamportClock()` to delegate to `clockGenerator.onRemoteEvent()`
4. Add `getVectorTimestamp()` method

**Backward Compatibility**:
- Existing tests continue to work
- Legacy `getLamportClock()` method retained
- No breaking API changes

### Phase 7B Integration (Event Delivery)

**Modifications to Event Flow**:
1. EntityUpdateEvent already has `lamportClock` field (no change needed)
2. DelosSocketTransport.receive() -> EventReprocessor.queueEvent() (new)
3. EventReprocessor.processReady() -> GhostStateManager.updateGhost() (new)
4. CausalityPreserver validates before processing (new)

**Backward Compatibility**:
- Direct processing path still available for testing
- EventReprocessor optional (configurable)

### Phase 7D Integration (Ghost Physics)

**Prepares for 7D**:
- GhostStateManager already supports dead reckoning
- Migration state machine handles GHOST state lifecycle
- CausalityPreserver ensures ghost updates ordered correctly

---

## Risk Analysis

### HIGH RISK

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Event reprocessing window too small | Event loss | Medium | Configurable window (100-500ms), metrics, alerts |
| View never stabilizes | Migrations blocked | Medium | Configurable threshold, timeout fallback |
| State machine edge cases | Entity loss/duplication | Medium | Exhaustive tests, invariant checks, audit logs |

### MEDIUM RISK

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Performance regression | TPS < 94 | Medium | Profile hot paths, batch processing, optimize |
| Lamport clock overflow | Incorrect ordering | Low | 64-bit long (9 quintillion), detection logic |
| Integration breaks 7A/7B | Regression | Low | Full regression suite, incremental integration |

### LOW RISK

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Mock Fireflies inaccuracy | Incorrect tests | Low | Base on actual Fireflies patterns |
| Network partition during migration | Orphaned entities | Low | Rollback on view change, stock neighbors |

---

## Test Summary

| Phase | Unit Tests | Integration Tests | Total |
|-------|------------|-------------------|-------|
| 7C.1 | 15 | - | 15 |
| 7C.2 | 20 | - | 20 |
| 7C.3 | 15 | - | 15 |
| 7C.4 | 15 | - | 15 |
| 7C.5 | 25 | - | 25 |
| 7C.6 | - | 10 | 10 |
| **Total** | **90** | **10** | **100** |

---

## Success Criteria (from Bead Luciferase-qq0i)

| Criterion | Phase | Validation |
|-----------|-------|------------|
| Out-of-order events reprocessed correctly | 7C.2, 7C.6 | EventReprocessorTest, FourBubbleCausalityTest |
| No state corruption from causality violations | 7C.3, 7C.6 | CausalityPreserverTest, FourBubbleCausalityTest |
| View changes detected reliably | 7C.4, 7C.6 | FirefliesViewMonitorTest, ViewChangeHandlingTest |
| All 1523+ tests passing | 7C.6 | Full regression suite |
| Entity retention: 100% | 7C.5, 7C.6 | EntityMigrationStateMachineTest, FourBubbleCausalityTest |
| TPS >= 94 | 7C.6 | FourBubbleCausalityTest.testPerformanceTPS94 |
| Determinism: 3 identical runs | 7C.6 | FourBubbleCausalityTest.testDeterminism3Runs |

---

## Files Summary

### New Files (15)

| File | Phase | LOC |
|------|-------|-----|
| causality/LamportClockGenerator.java | 7C.1 | ~200 |
| causality/LamportClockGeneratorTest.java | 7C.1 | ~300 |
| causality/EventReprocessor.java | 7C.2 | ~350 |
| causality/EventDependency.java | 7C.2 | ~80 |
| causality/EventReprocessorTest.java | 7C.2 | ~400 |
| causality/CausalityPreserver.java | 7C.3 | ~250 |
| causality/CausalityPreserverTest.java | 7C.3 | ~350 |
| fireflies/FirefliesViewMonitor.java | 7C.4 | ~300 |
| fireflies/ViewChangeEvent.java | 7C.4 | ~50 |
| fireflies/FirefliesViewMonitorTest.java | 7C.4 | ~350 |
| migration/EntityMigrationState.java | 7C.5 | ~100 |
| migration/EntityMigrationStateMachine.java | 7C.5 | ~400 |
| migration/MigrationCoordinatorEnhanced.java | 7C.5 | ~250 |
| migration/EntityMigrationStateMachineTest.java | 7C.5 | ~500 |
| integration/FourBubbleCausalityTest.java | 7C.6 | ~600 |
| integration/ViewChangeHandlingTest.java | 7C.6 | ~400 |

**Total New LOC**: ~4,880

### Modified Files (3)

| File | Phase | Changes |
|------|-------|---------|
| RealTimeController.java | 7C.1 | Replace lamportClock with LamportClockGenerator |
| TetrahedralMigration.java | 7C.5 | Integrate state machine |
| CrossProcessMigration.java | 7C.5 | Add view stability checks |

**Total Modified LOC**: ~150

---

## Bead Structure

**Parent Bead**: Luciferase-qq0i (existing - Inc7-Phase7C)

**Sub-Beads** (to be created):
- Luciferase-7c1-clock: Phase 7C.1 - LamportClockGenerator
- Luciferase-7c2-reprocess: Phase 7C.2 - EventReprocessor
- Luciferase-7c3-causal: Phase 7C.3 - CausalityPreserver
- Luciferase-7c4-view: Phase 7C.4 - FirefliesViewMonitor
- Luciferase-7c5-migrate: Phase 7C.5 - Entity Migration SM
- Luciferase-7c6-test: Phase 7C.6 - Integration Testing

---

## Next Steps

1. **Plan Audit**: Route this plan to plan-auditor for validation
2. **Bead Creation**: Create 6 sub-beads with dependencies
3. **Implementation Start**: Begin Phase 7C.1 and 7C.4 in parallel
4. **Progress Tracking**: Update Memory Bank with daily progress

---

## References

### ChromaDB Documents
- `research::distributed-simulation::inc7-mechanisms` - TimeWarp, Fireflies, Gossip research
- `research::distributed-consistency::causal-consistency-games` - Lamport clocks, vector clocks
- `audit::plan-auditor::inc7-corrected-2026-01-09` - Inc7 plan audit with Phase 7C recommendations
- `paper::fireflies` - Fireflies membership and gossip protocol

### Codebase Files
- `/Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/.../bubble/RealTimeController.java`
- `/Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/.../scheduling/CausalRollback.java`
- `/Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/.../events/EntityUpdateEvent.java`
- `/Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/.../ghost/GhostStateManager.java`
- `/Users/hal.hildebrand/git/Luciferase/simulation/doc/PHASE_7B_COMPLETION_SUMMARY.md`
- `/Users/hal.hildebrand/git/Luciferase/simulation/doc/PHASE_7A_SUMMARY.md`

---

**Plan Status**: READY FOR AUDIT
**Next Action**: Route to plan-auditor for validation
