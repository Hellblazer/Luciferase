# Phase 7D Implementation Plan: Ghost Physics & Timeout Handling

**Last Updated**: 2026-01-09 (Updated after plan-auditor review)
**Status**: READY FOR IMPLEMENTATION (Conditional GO)
**Epic Bead**: Luciferase-qq0i (Inc7 Phase 0)
**Estimated Duration**: 8 days (adjusted from 8-10 after Phase 7D.2 rescoping)
**Depends On**: Phase 7C (COMPLETE), R1+R3 blockers addressed
**Blocks**: Inc7 Phase 7D completion

---

## Executive Summary

Phase 7D completes the autonomous distributed simulation framework by implementing:
1. **Timeout-based Rollback**: Automatic rollback when migrations exceed time limits (2 days)
2. **Ghost Physics Integration**: FSM integration with existing dead reckoning (1.5 days rescoped)
3. **Advanced View Stability**: Dynamic stability thresholds and recovery strategies (1.5 days)
4. **Comprehensive E2E Testing**: 50+ tests across 7 scenarios with fault injection (3 days)

**Key Design Principle**: All operations remain eventually consistent through Lamport timestamps, with timeout-driven recovery ensuring no entities are permanently lost.

---

## Architecture Overview

### Timeout-Based Migration Recovery

```
Migration Lifecycle with Timeout:
┌─────────────────────────────────────────────────────┐
│                MIGRATING_OUT State                   │
│  startTime = now()                                  │
│  timeout = now() + MIGRATION_TIMEOUT_MS             │
│                                                     │
│  Option 1: Successful Transfer (View Stable)        │
│  ├─ Receive COMMIT_ACK from target                  │
│  ├─ View has been stable for 3+ ticks              │
│  └─> DEPARTED state (success)                       │
│                                                     │
│  Option 2: View Change (Any Time)                   │
│  ├─ Cluster membership changes                      │
│  └─> ROLLBACK_OWNED (automatic via onViewChange)   │
│                                                     │
│  Option 3: Timeout Expiry (8 seconds default)       │
│  ├─ Now > timeout AND still MIGRATING_OUT           │
│  ├─ Triggered on next transition attempt            │
│  └─> ROLLBACK_OWNED (automatic timeout)             │
└─────────────────────────────────────────────────────┘
```

### Ghost Physics During Transfer

```
Ghost Entity Lifecycle:
┌─────────┐  DEPARTED   ┌─────────┐  boundary   ┌────────────┐
│  OWNED  │─────────────│  GHOST  │─────────────│ MIGRATING_IN│
└─────────┘  (source)   └─────────┘  transfer   └────────────┘
                            │
                            │ Dead Reckoning:
                            │ - Velocity tracking
                            │ - Position extrapolation
                            │ - State synchronization via events
                            │
                            v
                    ┌─────────────────┐
                    │ Ghost Behavior: │
                    ├─────────────────┤
                    │ ✓ Process events│
                    │ ✓ Update physics│
                    │ ✓ Extrapolate   │
                    │ ✗ Local control │
                    │ ✗ Collisions    │
                    └─────────────────┘
```

### Configuration System

```java
// New EntityMigrationStateMachine.Configuration enhancements:
public static class Configuration {
    public final boolean requireViewStability;      // Current
    public final int rollbackTimeoutTicks;          // Current (stub)
    public final long migrationTimeoutMs;           // NEW: 8000ms default
    public final int minStabilityTicks;             // NEW: 3 ticks default
    public final boolean enableTimeoutRollback;     // NEW: enabled by default
    public final int maxRetries;                    // NEW: 3 retries

    // Built-in presets:
    public static Configuration aggressive()        // Fast timeout (2s)
    public static Configuration conservative()      // Slow timeout (15s)
    public static Configuration adaptive()          // Dynamic timeout based on network
}
```

---

## Sub-Phase Breakdown

### Phase 7D.1: Timeout Infrastructure (2 days)

**Objective**: Implement time-based migration tracking and expiry detection.

**Key Changes**:
- Extend `MigrationContext` with `startTimeMs` and `timeoutMs` fields
- Pass simulation time to `transition()` method
- Implement `checkTimeouts()` in EntityMigrationStateMachine
- Add `Configuration` parameter support

**Deliverables**:
| Component | File | Changes | Tests |
|-----------|------|---------|-------|
| MigrationContext | EntityMigrationStateMachine.java | Add time tracking | Unit |
| Timeout Detection | EntityMigrationStateMachine.java | checkTimeouts() method | Unit |
| Configuration | EntityMigrationStateMachine.java | New Configuration fields | Unit |
| Integration | RealTimeController.java | Pass simTime to FSM | Integration |

**Acceptance Criteria**:
- [ ] MigrationContext tracks start and timeout timestamps
- [ ] checkTimeouts() identifies expired migrations
- [ ] RealTimeController passes simulation time to FSM
- [ ] Configuration system fully functional
- [ ] All unit tests passing (target: 35+ tests)

### Phase 7D.2: Ghost Physics Integration (1.5 days)

**Objective**: Integrate existing dead reckoning with EntityMigrationStateMachine GHOST state lifecycle.

**Context**: GhostStateManager.java already implements dead reckoning with velocity tracking and DeadReckoningEstimator.java provides position prediction. This phase focuses on FSM integration and collision prevention.

**Key Changes**:
- Integrate GhostStateManager with EntityMigrationStateMachine state transitions
- Ensure ghost velocity tracking works during GHOST→MIGRATING_IN→OWNED transitions
- Implement collision double-counting prevention (source vs target physics)
- Validate extrapolation accuracy during ownership transfer

**Deliverables**:
| Component | File | Changes | Tests |
|-----------|------|---------|-------|
| FSM Integration | EntityMigrationStateMachine.java | Hook GHOST state to GhostStateManager | Unit |
| Collision Prevention | GhostStateManager.java | Track source/target zones separately | Unit |
| State Validation | Both files | Verify velocity consistency through transfer | Integration |

**Acceptance Criteria**:
- [ ] Ghost entities maintain velocity from source to target
- [ ] Position extrapolation accurate within 5% during transfer (validation, not new implementation)
- [ ] No collisions counted twice between source and target
- [ ] State consistency verified across GHOST→MIGRATING_IN→OWNED cycle
- [ ] All tests passing (target: 12+ new integration tests)

### Phase 7D.3: Advanced View Stability (1.5 days)

**Objective**: Implement dynamic stability thresholds and adaptive recovery.

**Key Changes**:
- Dynamic stability threshold based on network latency
- View change recovery with backoff strategy
- Stability metric tracking and reporting
- Adaptive timeout based on observed transfer times

**Deliverables**:
| Component | File | Changes | Tests |
|-----------|------|---------|-------|
| Stability Metrics | FirefliesViewMonitor.java | Track latency stats | Unit |
| Adaptive Thresholds | FirefliesViewMonitor.java | Dynamic config | Unit |
| Recovery Strategy | EntityMigrationStateMachine.java | Backoff logic | Unit |

**Acceptance Criteria**:
- [ ] Stability threshold adapts to network conditions
- [ ] Backoff prevents thrashing during unstable periods
- [ ] Metrics track success/failure rates
- [ ] Fallback logic handles persistent instability
- [ ] All tests passing (target: 15+ tests)

### Phase 7D.4: Comprehensive E2E Testing (2.5 days)

**Objective**: Validate full system behavior with complex scenarios.

**Test Scenarios**:
1. **Successful Migration**: 4 bubbles, 100 entities, stable network
2. **Timeout Rollback**: 4 bubbles, migration times out, entities return to source
3. **View Changes During Transfer**: Entity in flight when partition occurs
4. **Ghost Extrapolation**: Entity location matches expected dead reckoning
5. **Cascading Transfers**: Chain of 3+ migrations with ghost physics
6. **Network Jitter**: Rapid view changes with recovery
7. **Performance**: 1000 entities with 4-bubble cluster, ≥ 94 TPS (Phase 7C baseline, no regression)

**Deliverables**:
| Test Suite | File | Scenarios | Target |
|-----------|------|-----------|--------|
| Timeout Scenarios | TimeoutMigrationTest.java | 3 scenarios | 10+ tests |
| Ghost Physics | GhostPhysicsTest.java | 2 scenarios | 8+ tests |
| Cascading | CascadingMigrationTest.java | 2 scenarios | 10+ tests |
| Performance | E2EPerformanceTest.java | 1 scenario | 3+ tests |
| Stability Integration | AdvancedViewStabilityTest.java | 3 scenarios | 8+ tests |

**Test Count Reconciliation**:
- **Unit Tests (7D.1-7D.3)**: 50+ new tests (timeout infrastructure, dead reckoning integration, stability metrics)
- **Integration Tests (7D.2, 7D.3)**: 35+ tests (FSM integration, cascading transfers, stability recovery)
- **E2E Tests (7D.4)**: 31+ tests across 7 scenarios (4 test suites as shown above)
- **Total Phase 7D**: 50+ unit + 35+ integration + 31+ E2E = 116+ comprehensive tests
- **Regression Tests**: All Phase 7C tests (32 tests) continue to pass without regression

**Acceptance Criteria**:
- [ ] All 116+ Phase 7D tests passing (50+ unit + 35+ integration + 31+ E2E)
- [ ] All 32 Phase 7C regression tests passing
- [ ] No entity loss or duplication in any scenario
- [ ] Ghost position within 5% of expected (validation, not implementation)
- [ ] Performance ≥ 94 TPS sustained (Phase 7C baseline maintained)
- [ ] View changes handled gracefully with backoff strategy
- [ ] Timeout recovery always succeeds within configured limits
- [ ] Full 4-bubble scenarios complete in < 5 seconds

---

## Phase 7D Blockers & Dependencies (From Substantive-Critic Review)

**Status**: Must be addressed before Phase 7D.1 can begin
**Timeline**: 3 days (R1: 2 days, R3: 1 day, R4: config update)

### R1: Implement MigrationCoordinator Bridge (2 days, CRITICAL)

**Problem**: EntityMigrationStateMachine is disconnected from CrossProcessMigration 2PC protocol.
- FSM manages local state transitions
- 2PC manages distributed commit protocol
- No bridge exists between them → risk of state divergence
- **Impact**: 30% probability of entity duplication/loss during migrations

**Solution**: Create `MigrationCoordinator.java` to bridge FSM and 2PC:
- Listen to FSM state transitions (OWNED → MIGRATING_OUT → DEPARTED)
- Coordinate with 2PC PREPARE/COMMIT phases
- Ensure consistency between local FSM state and global 2PC state
- Handle ROLLBACK_OWNED recovery in 2PC context

**Implementation Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/causality/MigrationCoordinator.java`

### R3: Define EventReprocessor Gap Handling (1 day, CRITICAL)

**Problem**: Queue overflow causes event drops, but gap recovery mechanism is undefined.
- Current: Events dropped silently on overflow
- Risk: Permanent event loss when packet loss > 500ms
- **Impact**: Inconsistent state under packet loss scenarios

**Solution**: Define 30-second timeout threshold + explicit gap acceptance:
- On queue overflow, start 30-second timeout
- Accept gap explicitly rather than silently dropping
- Trigger view change if timeout expires (forces rollback/recovery)
- Log gap occurrences for monitoring and debugging

**Implementation Location**: Enhance `EventReprocessor.java` with timeout + gap acceptance mechanism

### R4: Update View Stability Threshold (config update, IMPORTANT)

**Problem**: Current N=3 ticks (100ms) insufficient for realistic network jitter.
- Recommended for production: N=30 ticks (300ms)
- **Impact**: Reduces false-positive view instability under normal network conditions

**Solution**: Update `FirefliesViewMonitor.java` configuration:
```java
// Current:
public FirefliesViewMonitor(FirefliesView<?> view) {
    this(view, 3);  // 3 ticks = 100ms at 30 ticks/sec
}

// Updated:
public FirefliesViewMonitor(FirefliesView<?> view) {
    this(view, 30);  // 30 ticks = 1000ms at 30 ticks/sec (more realistic)
}
```

**Notes**:
- Can be configured per-bubble for testing (N=3) vs production (N=30)
- Phase 7D accepts either threshold; production deployment will use N=30

---

## Technical Decisions

### Decision 1: Timeout Strategy
**Problem**: How to detect and recover from stalled migrations?
**Options**:
1. **Passive**: Check timeouts only on next transition attempt (chosen)
2. **Active**: Background thread polling timeouts (complexity, threading)
3. **Event-Driven**: Timeout events from RealTimeController (adds coupling)

**Rationale**: Passive approach maintains FSM simplicity while being sufficient for simulation workloads where operations happen frequently.

### Decision 2: Ghost Physics
**Problem**: How to maintain physical consistency while entity is in flight?
**Options**:
1. **Dead Reckoning**: Project position based on last velocity (chosen)
2. **Freeze**: Ghost doesn't move until transfer completes (unrealistic)
3. **Full Simulation**: Ghost runs physics normally (can diverge from source)

**Rationale**: Dead reckoning balances realism with consistency, preventing divergence while avoiding double-simulation.

### Decision 3: Adaptive Stability
**Problem**: How to handle varying network latencies?
**Options**:
1. **Fixed Threshold**: All bubbles use same stability threshold (current)
2. **Adaptive**: Threshold adjusts per-bubble based on network stats (chosen)
3. **Per-Entity**: Different timeout per entity based on transfer history (complex)

**Rationale**: Adaptive approach improves responsiveness without per-entity complexity.

---

## Risk Analysis

### HIGH RISK
| Risk | Impact | Mitigation |
|------|--------|-----------|
| Timeout too aggressive | Entity loss | Configurable timeout, extensive testing |
| Dead reckoning divergence | Position error | 5% accuracy validation, continuous synchronization |
| View changes spam rollbacks | Infinite loops | Rate limiting, progressive backoff |

### MEDIUM RISK
| Risk | Impact | Mitigation |
|------|--------|-----------|
| Timeout detection overhead | Performance regression | Batch checking, metrics tracking |
| Ghost state corruption | Consistency violation | Invariant checking, comprehensive tests |

### LOW RISK
| Risk | Impact | Mitigation |
|------|--------|-----------|
| Configuration complexity | User error | Presets (aggressive/conservative/adaptive) |
| Integration breaks 7C | Regression | Full regression test suite |

---

## Code Organization

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/
├── causality/
│   ├── EntityMigrationStateMachine.java          (Enhanced with timeout)
│   ├── EntityMigrationState.java                 (No changes)
│   ├── MigrationContext.java (extracted)         (Add time fields)
│   ├── FirefliesViewMonitor.java                 (Enhanced stability)
│   └── TimeoutRollbackStrategy.java              (NEW)
│
└── delos/
    ├── GhostStateManager.java                    (Enhanced dead reckoning)
    └── DeadReckoningStrategy.java                (NEW)

simulation/src/test/java/com/hellblazer/luciferase/simulation/
├── causality/
│   ├── TimeoutMigrationTest.java                 (NEW, 10+ tests)
│   └── TimeoutRollbackTest.java                  (NEW, 8+ tests)
│
└── integration/
    ├── GhostPhysicsTest.java                     (NEW, 8+ tests)
    ├── CascadingMigrationTest.java               (NEW, 10+ tests)
    ├── E2EPerformanceTest.java                   (NEW, 3+ tests)
    └── AdvancedViewStabilityTest.java            (NEW, 8+ tests)
```

---

## Validation Strategy

### Unit Testing
- **Target**: 50+ new unit tests
- **Coverage**: All timeout logic, configuration, dead reckoning
- **Execution**: `mvn test -pl simulation -Dtest="*Timeout*,*DeadReckoning*"`

### Integration Testing
- **Target**: 35+ new integration tests
- **Coverage**: Multi-bubble scenarios, cascading transfers, ghost physics
- **Execution**: `mvn test -pl simulation -Dtest="*Integration*"`

### E2E Testing
- **Target**: 7 comprehensive scenarios
- **Coverage**: Performance, reliability, consistency under faults
- **Acceptance**: 500+ TPS sustained, zero entity loss

### Performance Baseline
- **Current (Phase 7C)**: 94+ TPS with 100 entities, 4 bubbles
- **Target (Phase 7D)**: 500+ TPS with 1000 entities, 4 bubbles
- **Acceptable Regression**: None - must maintain or improve

---

## Implementation Order

1. **Phase 7D.1** (Days 1-2): Timeout infrastructure
2. **Phase 7D.2** (Days 2-4): Dead reckoning
3. **Phase 7D.3** (Days 4-5): View stability
4. **Phase 7D.4** (Days 6-8): E2E testing & validation

**Rollback Milestones**:
- Day 2: Timeout detection working
- Day 4: Dead reckoning integrated
- Day 5: Stability metrics validated
- Day 8: All tests passing, performance target met

---

## Success Criteria

**Phase 7D is COMPLETE when**:
1. ✓ 50+ unit tests pass with > 90% code coverage (Phase 7D.1-7D.3 new code)
2. ✓ 35+ integration tests pass (Phase 7D.2 FSM integration + 7D.4 E2E)
3. ✓ 7 E2E scenarios all pass:
   - Successful migration (4 bubbles, 100 entities, stable network)
   - Timeout rollback (entity returned to source after 8s timeout)
   - View changes during transfer (entity recovers correctly)
   - Ghost extrapolation validation (position within 5%)
   - Cascading transfers (3+ migration chain)
   - Network jitter recovery (rapid view changes handled)
   - Performance baseline (≥ Phase 7C performance: 94+ TPS)

4. ✓ Zero entity loss or duplication verified in all tests

5. ✓ **Performance**: Simulation throughput ≥ 94 TPS (Phase 7C baseline)
   - Measured as: Simulation ticks processed per wall-clock second
   - Test scenario: 1000 entities, 4 bubbles, 100+ simulation ticks
   - Success: No performance regression from Phase 7C

6. ✓ **Timeout Rollback**: Automatic rollback verified for:
   - Migrations exceeding 8-second timeout
   - View changes during MIGRATING_OUT state
   - Recovery without entity loss

7. ✓ **Ghost Physics Accuracy**: Validation (not implementation):
   - Definition: `error = |extrapolated_position - actual_position| / max(1.0, distance_traveled)`
   - Success: `error ≤ 0.05` (5% of movement distance)
   - Test: 10 migrations with velocity changes, measure extrapolation error

8. ✓ Code review passed for all Phase 7D changes (code-review-expert agent)

9. ✓ No regressions in Phase 7C tests (all 32 tests still passing)

10. ✓ Plan audit validation (plan-auditor confirmation of completion)

---

## References

- Phase 7C Implementation: PHASE_7C_IMPLEMENTATION_PLAN.md
- Phase 7C Plan Audit: PHASE_7C_PLAN_AUDIT.md
- Entity Migration State Machine: EntityMigrationStateMachine.java
- FirefliesViewMonitor: FirefliesViewMonitor.java
- GhostStateManager: GhostStateManager.java

---

**Status**: READY FOR ARCHITECTURE REVIEW
**Next Action**: Substantive-critic review → Plan-auditor audit → Implementation
