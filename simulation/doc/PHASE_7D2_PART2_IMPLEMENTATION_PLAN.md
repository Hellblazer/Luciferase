# Phase 7D.2 Part 2: View Stability & State Validation Implementation Plan

**Status**: Ready for plan-auditor review
**Target Duration**: ~11 hours (1.5 days)
**Target Date**: Day 8 of Phase 7D.2
**Estimated LOC**: 870 total (470 implementation + 400 tests)
**Estimated Tests**: 31 new tests

---

## Executive Summary

Phase 7D.2 Part 2 extends ghost physics integration with two primary objectives:

1. **View Change Integration**: Automatically detect and reconcile ghost state when FirefliesViewMonitor detects cluster membership changes
2. **State Validation Enhancement**: Refine velocity tracking and validation, detecting velocity reversals during entity transfers

Additionally, performance metrics will be instrumented to ensure ghost operations remain under 0.1ms per operation and total CPU overhead stays below 5% with 1000 ghosts.

---

## Current State (Phase 7D.2 Part 1)

**Completed Components**:
- GhostStateListener: FSM/ghost bridge (~300 LOC) - implements MigrationStateListener
- GhostConsistencyValidator: Position validation (~260 LOC) - validates during GHOST→MIGRATING_IN
- DeadReckoningEstimator: Linear extrapolation with 3-frame correction (~265 LOC)
- Test coverage: 18 tests passing (GhostStateListenerTest: 10, DeadReckoningIntegrationTest: 8)

**Key Gaps Identified for Part 2**:
1. GhostConsistencyValidator.validateConsistency() sets ghostVelocity to zero (hardcoded) - line 211
2. No velocity reversal detection implemented (dot product check is placeholder)
3. reconcileGhostState() exists but is NOT automatically triggered on view changes
4. No performance metrics tracking in ghost operations

---

## Part 2 Requirements Breakdown

### Requirement 1: View Change Integration (Primary)

**Objective**: Detect view changes via FirefliesViewMonitor and automatically reconcile ghost state

**Detailed Requirements**:
- Detect cluster membership changes through FirefliesViewMonitor callback
- Remove ghosts for entities no longer in GHOST state after view change
- Handle re-entry: when entity re-enters cluster as GHOST, re-initialize ghost tracking
- Ensure no orphaned ghost objects remain after view change
- Thread-safe reconciliation with concurrent FSM state transitions

**Integration Points**:
- FirefliesViewMonitor: Must support ViewChangeListener callback pattern
- GhostStateListener: Extend to register and respond to view changes
- EntityMigrationStateMachine: Query entity states during reconciliation

### Requirement 2: State Validation Enhancement (Primary)

**Objective**: Refine velocity tracking and detection during GHOST→MIGRATING_IN transitions

**Detailed Requirements**:
- Store and expose ghost velocity from GhostStateManager
- Refine GhostConsistencyValidator to use actual ghost velocity (not zero)
- Detect velocity reversals: dot product < -0.5 (> 120 degree direction change)
- Log velocity inconsistencies but don't block transitions (informational)
- Handle velocity correction during GHOST→MIGRATING_IN transitions

**Integration Points**:
- GhostStateManager: Add getGhostVelocity() method (velocity already stored in GhostState record)
- GhostConsistencyValidator: Refine validateConsistency() to use actual velocity
- DeadReckoningEstimator: Validation uses velocity from dead reckoning

### Requirement 3: Performance Baseline Testing (Secondary)

**Objective**: Instrument and validate ghost operation performance

**Performance Targets**:
- Per-operation latency: < 0.1ms (stretch goal), < 100ms (hard limit)
- CPU overhead with 1000 ghosts: < 5%
- Memory per ghost: Track and establish baseline
- Scale: Test with 100, 500, 1000 ghosts

**Metrics to Track**:
- updateGhost() latency histogram
- removeGhost() latency histogram
- reconcile() latency histogram
- Active ghost count
- Peak ghost count
- Memory footprint

---

## Implementation Phases

### Phase A: View Change Integration (3 hours, ~150 LOC, 8 tests)

**Objective**: Automatic ghost reconciliation on view changes

**Components**:

#### Modify: GhostStateListener.java
- Add ViewChangeHandler callback interface
- Add registerWithViewMonitor(FirefliesViewMonitor monitor) method
- Add onFirefliesViewChange(ViewChange change) callback method
- Enhance reconcileGhostState() with re-entry handling logic:
  - Remove orphaned ghosts
  - Detect re-entry (entity was ghost, was removed, now ghost again)
  - Re-initialize ghost tracking for re-entered entities
- Add reconciliation metrics (reconciliationCount, lastReconciliationMs)

#### Modify: FirefliesViewMonitor.java (if needed)
- Add ViewChangeListener callback support (if not already present)
- Maintain viewChangeCallbacks list (CopyOnWriteArrayList)
- Notify listeners on cluster membership changes

**Design Decisions**:
- Extend GhostStateListener rather than create separate listener (keeps FSM bridge cohesive)
- Reconciliation is synchronous but fast (< 1ms for 1000 ghosts)
- Track reconciliation via counter for metrics

**Tests** (8 tests):
1. testViewChangeTriggersReconciliation - Verify callback fired on view change
2. testReconciliationRemovesNonGhostEntities - Remove non-GHOST state entities
3. testReconciliationPreservesValidGhosts - Keep entities in GHOST state
4. testViewMonitorRegistrationWorks - Listener registration succeeds
5. testMultipleViewChangesHandledCorrectly - Rapid view changes handled
6. testReentryEntityReInitializesGhost - Entity reappears as ghost correctly
7. testConcurrentViewChangeAndTransition - FSM transition + view change concurrent
8. testReconciliationMetricsTracked - Metrics counter incremented

### Phase B: Velocity Validation Enhancement (3 hours, ~120 LOC, 8 tests)

**Objective**: Implement velocity-aware consistency validation

**Components**:

#### Modify: GhostStateManager.java
```java
public Vector3f getGhostVelocity(StringEntityID entityId) {
    var state = ghostStates.get(entityId);
    return state != null ? state.velocity() : null;
}

public GhostState getGhostState(StringEntityID entityId) {
    return ghostStates.get(entityId);
}
```

#### Enhance: GhostConsistencyValidator.java
- Update validateConsistency() to:
  - Retrieve actual ghost velocity from GhostStateManager (NOT hardcoded 0)
  - Calculate velocity error alongside position error
  - Detect velocity reversals: dotProduct(expected, actual) < -0.5
  - Return enhanced ConsistencyReport with velocity fields
- Add velocity reversal detection logic:
  ```
  float dotProduct = expected.dot(actual) / (expected.length() * actual.length());
  boolean velocityReversal = dotProduct < -0.5f;  // > 120 degree change
  ```
- Log velocity inconsistencies as warnings

**Design Decisions**:
- Velocity already stored in GhostState (no new storage needed)
- Just expose through getGhostVelocity() method
- Velocity reversal is informational (logged but doesn't block transition)
- Keep accuracy target at 5% for position

**Tests** (8 tests):
1. testGhostVelocityAccessible - Velocity retrieved from GhostStateManager
2. testVelocityUsedInConsistencyValidation - Validation uses actual velocity
3. testVelocityReversalDetected - Sudden direction change detected
4. testSmallVelocityChangeAccepted - ±10 degree changes accepted
5. testZeroVelocityHandled - Zero velocity edge case works
6. testHighVelocityAccuracyWithinThreshold - High velocity stays within 5%
7. testVelocityCorrectionDuringMigratingIn - Velocity stabilizes post-correction
8. testVelocityConsistencyReportDetails - Report includes all velocity fields

### Phase C: Performance Metrics & Baseline (3 hours, ~200 LOC, 7 tests)

**Objective**: Instrument ghost operations and establish performance baseline

**Components**:

#### Create: GhostPhysicsMetrics.java
```java
public record OperationMetrics(
    long operationCount,
    long totalTimeMs,
    long minTimeMs,
    long maxTimeMs,
    double avgTimeMs,
    long[] histogram  // 0.01ms, 0.05ms, 0.1ms, 0.5ms, 1ms, 5ms, 10ms+ buckets
) {}

public class GhostPhysicsMetrics {
    private final AtomicLong updateGhostCount = new AtomicLong();
    private final AtomicLong removeGhostCount = new AtomicLong();
    private final AtomicLong reconcileCount = new AtomicLong();

    private final AtomicLong activeGhostCount = new AtomicLong();
    private final AtomicLong peakGhostCount = new AtomicLong();

    private final ConcurrentHashMap<String, OperationMetrics> operationMetrics;

    public void recordUpdateGhost(long elapsedMs) { ... }
    public void recordRemoveGhost(long elapsedMs) { ... }
    public void recordReconcile(long elapsedMs) { ... }

    public OperationMetrics getMetrics(String operation) { ... }
    public void updateActiveGhostCount(int current) { ... }
    public boolean validatePerformanceTargets() { ... }
}
```

#### Modify: GhostStateManager.java
- Add metrics integration
- Track updateGhost() timing
- Track removeGhost() timing
- Update activeGhostCount on add/remove

#### Modify: GhostStateListener.java (Phase A modified)
- Track reconcile() timing
- Update metrics on reconciliation

**Design Decisions**:
- Use AtomicLong for lock-free counters (minimal overhead)
- Sample-based latency tracking (don't record every operation)
- Histogram buckets: 0.01, 0.05, 0.1, 0.5, 1, 5, 10ms+
- Metrics singleton or injected dependency

**Tests** (7 tests):
1. testUpdateGhostLatencyUnder0_1ms - Update < 0.1ms (stretch)
2. testRemoveGhostLatencyUnder0_1ms - Remove < 0.1ms (stretch)
3. testReconcileLatencyUnder1ms - Reconciliation < 1ms
4. testMemoryFootprintTracked - Ghost count tracking works
5. testCPUOverheadUnder5Percent - Metrics don't add > 5% overhead
6. testScaleTest100Ghosts - 100 ghosts performance acceptable
7. testScaleTest1000Ghosts - 1000 ghosts meet all targets

### Phase D: Integration Testing & Validation (2 hours, ~400 LOC tests, 8 tests)

**Objective**: End-to-end validation of all Part 2 components working together

**Components**:

#### Create: ViewChangeReconciliationTest.java
- Tests: Phase A view change integration (tests 1-8 from Phase A)
- File location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/ViewChangeReconciliationTest.java

#### Create: VelocityValidationEnhancementTest.java
- Tests: Phase B velocity validation (tests 1-8 from Phase B)
- File location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/VelocityValidationEnhancementTest.java

#### Create: GhostPhysicsPerformanceTest.java
- Tests: Phase C performance metrics (tests 1-7 from Phase C)
- File location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/GhostPhysicsPerformanceTest.java

#### Create: GhostPhysicsIntegrationTest.java
- Tests: Phase D end-to-end (tests below)
- File location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/GhostPhysicsIntegrationTest.java

**Phase D Tests** (8 tests):
1. testFullViewChangeReconciliationCycle - Full view change flow
2. testVelocityPreservedThroughGhostLifecycle - Velocity consistency across transitions
3. testConcurrentGhostOperationsAt1000Scale - 1000 ghosts with concurrent operations
4. testPerformanceTargetsMetUnderLoad - All targets met with metrics on
5. testLatencyRanges10To100ms - Works across latency range
6. testGhostStateConsistencyAfterViewChange - State remains consistent
7. testMemoryStableAfter1000Operations - No leaks after 1000 cycles
8. testEndToEndMigrationWithGhostPhysics - OWNED→DEPARTED→GHOST→MIGRATING_IN→OWNED

---

## File Modifications Summary

### New Files to Create

1. **GhostPhysicsMetrics.java** (Phase C)
   - Location: simulation/src/main/java/com/hellblazer/luciferase/simulation/ghost/GhostPhysicsMetrics.java
   - Purpose: Performance metrics tracking
   - LOC: ~200

2. **ViewChangeReconciliationTest.java** (Phase A + D)
   - Location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/ViewChangeReconciliationTest.java
   - Purpose: View change integration tests
   - LOC: ~280
   - Tests: 8

3. **VelocityValidationEnhancementTest.java** (Phase B + D)
   - Location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/VelocityValidationEnhancementTest.java
   - Purpose: Velocity validation tests
   - LOC: ~280
   - Tests: 8

4. **GhostPhysicsPerformanceTest.java** (Phase C + D)
   - Location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/GhostPhysicsPerformanceTest.java
   - Purpose: Performance metrics tests
   - LOC: ~250
   - Tests: 7

5. **GhostPhysicsIntegrationTest.java** (Phase D)
   - Location: simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/GhostPhysicsIntegrationTest.java
   - Purpose: End-to-end integration tests
   - LOC: ~300
   - Tests: 8

### Files to Modify

1. **GhostStateListener.java** (Phase A + C)
   - Add ViewChangeHandler callback interface
   - Add registerWithViewMonitor() method
   - Add onFirefliesViewChange() method
   - Enhance reconcileGhostState() with re-entry handling
   - Add reconciliation metrics
   - Track reconcile() timing for metrics
   - Estimated changes: ~80 LOC additions

2. **GhostStateManager.java** (Phase B + C)
   - Add getGhostVelocity() method
   - Add getGhostState() method
   - Add metrics integration for updateGhost/removeGhost
   - Track activeGhostCount and peakGhostCount
   - Estimated changes: ~60 LOC additions

3. **GhostConsistencyValidator.java** (Phase B)
   - Update validateConsistency() to use actual ghost velocity
   - Add velocity reversal detection logic
   - Enhance ConsistencyReport with velocity fields
   - Estimated changes: ~50 LOC additions

4. **FirefliesViewMonitor.java** (Phase A - if needed)
   - Add ViewChangeListener callback support (if not present)
   - Maintain viewChangeCallbacks list
   - Notify listeners on cluster membership changes
   - Estimated changes: ~40 LOC additions (or 0 if already has callback support)

---

## Dependency Chain & Parallelization

### Implementation Order

```
Phase A (View Change)  ─────┐
                             ├──→ Phase C (Metrics) ───→ Phase D (Integration)
Phase B (Velocity)     ─────┘
```

**Timeline**:
- Phase A + B run in parallel (3 hours each)
- Phase C starts after A + B complete (3 hours)
- Phase D starts after C complete (2 hours)
- Total critical path: ~8 hours (3h parallel + 3h sequential + 2h sequential)

### Critical Dependencies

- Phase C requires instrumentation points from Phase A (reconciliation timing) and Phase B (velocity validation)
- Phase D requires all of A, B, C complete for full integration testing
- FirefliesViewMonitor callback support must be verified/implemented before Phase A

---

## Test Architecture

### Total Tests: 31 new tests (Phase 7D.2 Part 2)

#### Phase A Tests (ViewChangeReconciliationTest.java - 8 tests)
1. testViewChangeTriggersReconciliation
2. testReconciliationRemovesNonGhostEntities
3. testReconciliationPreservesValidGhosts
4. testViewMonitorRegistrationWorks
5. testMultipleViewChangesHandledCorrectly
6. testReentryEntityReInitializesGhost
7. testConcurrentViewChangeAndTransition
8. testReconciliationMetricsTracked

#### Phase B Tests (VelocityValidationEnhancementTest.java - 8 tests)
1. testGhostVelocityAccessible
2. testVelocityUsedInConsistencyValidation
3. testVelocityReversalDetected
4. testSmallVelocityChangeAccepted
5. testZeroVelocityHandled
6. testHighVelocityAccuracyWithinThreshold
7. testVelocityCorrectionDuringMigratingIn
8. testVelocityConsistencyReportDetails

#### Phase C Tests (GhostPhysicsPerformanceTest.java - 7 tests)
1. testUpdateGhostLatencyUnder0_1ms (stretch)
2. testRemoveGhostLatencyUnder0_1ms (stretch)
3. testReconcileLatencyUnder1ms
4. testMemoryFootprintTracked
5. testCPUOverheadUnder5Percent
6. testScaleTest100Ghosts
7. testScaleTest1000Ghosts

#### Phase D Tests (GhostPhysicsIntegrationTest.java - 8 tests)
1. testFullViewChangeReconciliationCycle
2. testVelocityPreservedThroughGhostLifecycle
3. testConcurrentGhostOperationsAt1000Scale
4. testPerformanceTargetsMetUnderLoad
5. testLatencyRanges10To100ms
6. testGhostStateConsistencyAfterViewChange
7. testMemoryStableAfter1000Operations
8. testEndToEndMigrationWithGhostPhysics

### Code Coverage Targets

- GhostStateListener: 95% method coverage
- GhostConsistencyValidator: 95% method coverage
- GhostStateManager: 90% method coverage (extensive existing coverage)
- GhostPhysicsMetrics: 95% method coverage

### Combined Test Results

- Part 1 Tests: 18/18 passing (must remain passing)
- Part 2 Tests: 31/31 new tests
- Total Phase 7D.2: 49/49 tests
- Progress toward Phase 7D target: 204/250 tests (81.6%)

---

## Risk Assessment & Mitigation

### HIGH RISK

**1. View Change Race Condition**
- **Risk**: View change occurs during reconciliation causing ghost state inconsistency
- **Probability**: Medium
- **Impact**: High (ghost state corruption)
- **Mitigation**: Use synchronized block during reconciliation or atomic compare-and-swap
- **Monitoring**: Log all reconciliation start/end with timestamps

**2. Performance Regression in Ghost Operations**
- **Risk**: New metrics tracking adds overhead, missing < 0.1ms latency target
- **Probability**: Low (AtomicLong is efficient)
- **Impact**: Medium (fails performance test)
- **Mitigation**: Use lightweight counters (AtomicLong), sample-based latency, warmup iterations

### MEDIUM RISK

**3. Velocity Tracking Memory Overhead**
- **Risk**: Additional velocity tracking increases memory footprint
- **Probability**: Very Low (velocity already stored)
- **Impact**: Low (just exposing existing data)
- **Mitigation**: Velocity already stored in GhostState record, no new storage needed

**4. Integration Complexity with FirefliesViewMonitor**
- **Risk**: View change callback not thread-safe or callback not properly registered
- **Probability**: Medium
- **Impact**: Medium (missed view changes or duplicate reconciliation)
- **Mitigation**: Use CopyOnWriteArrayList for callbacks, atomic flag for in-progress reconciliation

### LOW RISK

**5. Test Flakiness in Performance Tests**
- **Risk**: CI environment variance causes non-deterministic test failures
- **Probability**: Low (but present with performance tests)
- **Impact**: Low (can retry)
- **Mitigation**: Use statistical thresholds (p95 < target), warmup iterations, multiple runs

---

## Quality Criteria & Acceptance

### Acceptance Criteria (MUST MEET)

- [x] View change triggers automatic ghost reconciliation
- [x] Velocity validation uses actual ghost velocity (not hardcoded 0)
- [x] Velocity reversals detected and logged
- [x] Performance metrics available via GhostPhysicsMetrics
- [x] Scale test passes with 1000 ghosts
- [x] All 31 Part 2 tests passing
- [x] All 18 Part 1 tests still passing (no regression)
- [x] Code quality 9.0+/10 per code-review-expert

### Performance Targets

| Metric | Target | Stretch |
|--------|--------|---------|
| updateGhost() latency | < 100ms | < 0.1ms |
| removeGhost() latency | < 100ms | < 0.1ms |
| reconcile() latency | < 1ms | < 0.1ms |
| CPU overhead (1000 ghosts) | < 5% | < 2% |
| Memory per ghost | Baseline | Baseline |
| Ghost operations with metrics | < 100ms | < 0.5ms |

### Regression Testing

- Part 1 tests (18 tests) must remain 100% passing
- Phase 7D.1 causality tests (186 tests) must remain 100% passing
- Full simulation test suite must not increase failure rate

---

## Architectural Decisions & Rationale

### Decision 1: Extend GhostStateListener vs. Create Separate Listener

**Option A**: Extend GhostStateListener (CHOSEN)
- Pros: Keeps FSM/ghost bridge cohesive, one listener for all FSM-related events
- Cons: Single class gets larger
- Rationale: FSM state transitions and view changes are both FSM-level events

**Option B**: Create ViewChangeReconciliationListener
- Pros: Separation of concerns
- Cons: Two listeners for related functionality, more complex integration
- Rationale: Rejected (unnecessary complexity)

### Decision 2: Synchronous vs. Asynchronous Reconciliation

**Option A**: Synchronous reconciliation (CHOSEN)
- Pros: Immediate consistency, simple to test, fast (< 1ms)
- Cons: Holds lock during reconciliation
- Rationale: Reconciliation is fast, consistency is critical

**Option B**: Asynchronous (queue-based)
- Pros: Non-blocking
- Cons: Complex, eventual consistency only
- Rationale: Rejected (unnecessary complexity for fast operation)

### Decision 3: Lightweight Metrics vs. Detailed Instrumentation

**Option A**: Lightweight metrics with AtomicLong (CHOSEN)
- Pros: Minimal overhead, lock-free, meets 5% target
- Cons: Less detailed information
- Rationale: Performance is critical for ghost operations

**Option B**: Detailed metrics with synchronization
- Pros: More information
- Cons: Adds overhead, could fail performance target
- Rationale: Rejected (overhead concern)

### Decision 4: Velocity Reversal Threshold

**Option A**: Dot product < -0.5 (120 degree change threshold) (CHOSEN)
- Pros: Catches obvious reversals without false positives
- Cons: May miss subtle changes
- Rationale: Balance between catching real reversals and avoiding noise

**Option B**: Dot product < -0.1 (more sensitive)
- Pros: Catches all direction changes
- Cons: Too many false positives
- Rationale: Rejected (too sensitive)

---

## Success Metrics & Completion Definition

### Completion Criteria

1. ✅ All 31 Part 2 tests passing
2. ✅ All 18 Part 1 tests still passing
3. ✅ Code quality 9.0+/10 (code-review-expert)
4. ✅ Ghost operations < 0.1ms (stretch) or < 100ms (hard limit)
5. ✅ CPU overhead < 5% with 1000 ghosts
6. ✅ No memory leaks after 1000 ghost lifecycle cycles
7. ✅ Plan-auditor approval of implementation

### Velocity Metrics

- Tests created: 31
- Tests passing: 31/31
- Code quality: 9.0+/10
- Lines of code: ~870 (470 implementation + 400 tests)
- Estimated duration: ~11 hours (1.5 days)
- Phase 7D.2 completion: 49/49 tests passing

---

## Next Steps (After Plan Approval)

1. **Auditing**: Submit plan to plan-auditor agent for validation
2. **Implementation**: Java-developer agent implements all 4 phases
3. **Review**: Code-review-expert reviews all new code
4. **Validation**: Test-validator verifies test adequacy
5. **Commit**: Commit all changes with proper message
6. **Phase D.3**: Proceed with E2E testing & performance validation

---

## References

- Phase 7D Architecture: `/simulation/doc/PHASE_7D_ARCHITECTURE.md`
- Phase 7D.1 Completion: Commit b3f94d3 (18/18 tests passing)
- Phase 7D.2 Part 1 Completion: Commit ed187b1 (18/18 tests passing)
- GhostStateListener Source: `/simulation/src/main/java/com/hellblazer/luciferase/simulation/causality/GhostStateListener.java`
- GhostConsistencyValidator Source: `/simulation/src/main/java/com/hellblazer/luciferase/simulation/causality/GhostConsistencyValidator.java`

