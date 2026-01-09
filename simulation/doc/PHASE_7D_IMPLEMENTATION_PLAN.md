# Phase 7D Implementation Plan: Ghost Physics & Timeout Handling

**Last Updated**: 2026-01-09
**Status**: PLANNING (Awaiting Architecture Review)
**Epic Bead**: Luciferase-qq0i (Inc7 Phase 0)
**Estimated Duration**: 8-10 days
**Depends On**: Phase 7C (COMPLETE)
**Blocks**: Inc7 Phase 7D completion

---

## Executive Summary

Phase 7D completes the autonomous distributed simulation framework by implementing:
1. **Timeout-based Rollback**: Automatic rollback when migrations exceed time limits
2. **Ghost Physics**: Dead reckoning and ghost entity behavior during ownership transfer
3. **Advanced View Stability**: Dynamic stability thresholds and recovery strategies
4. **Comprehensive E2E Testing**: Full 4-8 bubble scenarios with fault injection

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

### Phase 7D.2: Dead Reckoning for Ghosts (2.5 days)

**Objective**: Implement ghost entity physics and state extrapolation.

**Key Changes**:
- Enhance `GhostStateManager` with velocity tracking
- Implement position extrapolation during ownership transfer
- Add predictive state updates based on last known velocity
- Integrate with physics simulation during ghost period

**Deliverables**:
| Component | File | Changes | Tests |
|-----------|------|---------|-------|
| Ghost Velocity | GhostStateManager.java | Track velocities | Unit |
| Extrapolation | GhostStateManager.java | Position prediction | Unit |
| Physics Integration | GhostStateManager.java | Apply to entities | Integration |

**Acceptance Criteria**:
- [ ] Ghost entities maintain velocity during transfer
- [ ] Position extrapolation accurate within 5% of final position
- [ ] Physics simulation continues during GHOST state
- [ ] No collision double-counting between source/target
- [ ] All tests passing (target: 20+ tests)

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
7. **Performance**: 1000 entities with 4-bubble cluster, > 500 TPS

**Deliverables**:
| Test Suite | File | Scenarios | Target |
|-----------|------|-----------|--------|
| Timeout Scenarios | TimeoutMigrationTest.java | 3 scenarios | 10+ tests |
| Ghost Physics | GhostPhysicsTest.java | 2 scenarios | 8+ tests |
| Cascading | CascadingMigrationTest.java | 2 scenarios | 10+ tests |
| Performance | E2EPerformanceTest.java | 1 scenario | 3+ tests |

**Acceptance Criteria**:
- [ ] All 31+ E2E tests passing
- [ ] No entity loss or duplication in any scenario
- [ ] Ghost position within 5% of expected
- [ ] Performance > 500 TPS sustained
- [ ] View changes handled gracefully
- [ ] Timeout recovery always succeeds
- [ ] Full 4-bubble scenarios complete in < 5 seconds

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
1. ✓ 50+ unit tests pass with > 95% coverage
2. ✓ 35+ integration tests pass
3. ✓ 7 E2E scenarios pass including performance
4. ✓ Zero entity loss/duplication in any test
5. ✓ 500+ TPS sustained (or beats Phase 7C baseline)
6. ✓ Timeout rollback tested and verified
7. ✓ Ghost physics within 5% accuracy
8. ✓ Code review passed for all changes
9. ✓ No regressions in Phase 7C tests
10. ✓ Plan audit passed

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
