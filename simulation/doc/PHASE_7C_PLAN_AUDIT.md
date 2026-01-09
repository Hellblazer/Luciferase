# Phase 7C Implementation Plan Audit

**Auditor**: plan-auditor (Claude Opus 4.5)
**Date**: 2026-01-09
**Plan**: PHASE_7C_IMPLEMENTATION_PLAN.md
**Epic**: Luciferase-qq0i

---

## Verdict: CONDITIONAL GO

The Phase 7C implementation plan is technically sound and well-structured. However, there is **one BLOCKER** that must be addressed before implementation can begin.

---

## Critical Finding: Phase 7B Test Failure (BLOCKER)

### Issue
`TwoBubbleCommunicationTest.testGhostPositionMatchesOwned` is **FAILING**:
```
expected: <100.0> but was: <124.45079>
```

### Analysis
The test creates an entity at position (100.0, 200.0, 50.0) and expects the ghost position to match. The test explicitly requests the position at creation time to avoid dead reckoning extrapolation:

```java
// Get ghost position from Bubble B at the CREATION TIME (no extrapolation)
var ghostId = new StringEntityID(entityId);
var creationTime = controllerA.getSimulationTime();
var ghostPos = bubbleB.getGhostStateManager()
    .getGhostPosition(ghostId, creationTime);
```

However, the position returned is 124.45079 instead of 100.0, indicating:
1. The timestamp is not being correctly used to avoid extrapolation, OR
2. There's a timestamp mismatch between controllerA and controllerB simulation times

### Impact on Phase 7C
Phase 7C depends on Phase 7B ghost state management working correctly. The LamportClockGenerator (7C.1) and CausalityPreserver (7C.3) rely on proper timestamp handling between bubbles.

### Required Action
**Fix the Phase 7B test failure before starting Phase 7C implementation.**

Likely fix: Ensure `GhostStateManager.getGhostPosition(entityId, timestamp)` correctly uses the provided timestamp to compute position at that exact time, not extrapolate beyond it.

---

## Dependency Verification

### Files Confirmed to Exist

| File | Status | LOC | Notes |
|------|--------|-----|-------|
| RealTimeController.java | EXISTS | 295 | Has AtomicLong lamportClock, updateLamportClock() method |
| CausalRollback.java | EXISTS | 257 | Bounded checkpoint pattern (MAX_ROLLBACK_BUCKETS=2) |
| GhostStateManager.java | EXISTS | 400 | Dead reckoning, staleness detection |
| EntityUpdateEvent.java | EXISTS | 99 | Record with lamportClock field |
| TetrahedralMigration.java | EXISTS | 302 | Two-phase commit, cooldown, hysteresis |
| CrossProcessMigration.java | EXISTS | 400 | 2PC with idempotency, locks, metrics |
| FirefliesMembershipView.java | EXISTS | 106 | Wraps Delos Fireflies View |

### API Verification

| API | Plan Uses | Codebase Has | Status |
|-----|-----------|--------------|--------|
| DynamicContext.active() | Yes | Yes | CONFIRMED |
| context.allMembers() | - | Yes | Alternative available |
| View.register() | Yes | Yes | CONFIRMED |

---

## Sub-Phase Analysis

### Phase 7C.1: LamportClockGenerator (1.5 days) - APPROVED

**Design Assessment**: Sound
- Replaces `AtomicLong lamportClock` in RealTimeController
- Vector timestamp support via `ConcurrentHashMap<UUID, Long>`
- Standard Lamport rule: `max(local, remote) + 1`

**Codebase Alignment**: RealTimeController already has:
- `lamportClock.incrementAndGet()` on tick
- `updateLamportClock(remoteClock)` for remote events
- Transition to LamportClockGenerator is clean

**Tests**: 15 tests - comprehensive

### Phase 7C.2: EventReprocessor (2 days) - APPROVED

**Design Assessment**: Sound
- Bounded queue (1000 events max) - reasonable
- Lookahead window (100-500ms) - configurable
- PriorityQueue ordered by Lamport clock

**Codebase Alignment**: Similar to CausalRollback pattern:
- CausalRollback uses `ConcurrentLinkedDeque` for checkpoints
- EventReprocessor uses `PriorityQueue` for events
- Both enforce bounded windows

**Tests**: 20 tests - comprehensive

### Phase 7C.3: CausalityPreserver (2 days) - APPROVED

**Design Assessment**: Sound
- `processedClocks` map tracks highest processed clock per source
- `canProcess(event)` validates sequential processing
- Simple, effective causality enforcement

**Tests**: 15 tests - comprehensive

### Phase 7C.4: FirefliesViewMonitor (1.5 days) - APPROVED

**Design Assessment**: Sound
- Uses `context.active()` - API CONFIRMED to exist in Delos
- Stability threshold (N=3 ticks) - configurable, reasonable default
- ViewChangeEvent for listener notification

**Codebase Alignment**: FirefliesMembershipView shows pattern:
```java
view.register(listenerKey, this::handleDelosViewChange);
```

**Tests**: 15 tests - comprehensive

### Phase 7C.5: EntityMigrationStateMachine (2 days) - APPROVED WITH NOTES

**Design Assessment**: Sound
- 6-state machine is comprehensive
- View stability guards on commit transitions
- ROLLBACK_OWNED prevents entity loss

**Integration Concern**:
- TetrahedralMigration has existing two-phase commit logic
- CrossProcessMigration has per-entity locks and 2PC
- State machine must integrate cleanly without duplicating logic

**Recommendation**: Consider extracting common state tracking to EntityMigrationStateMachine and having TetrahedralMigration/CrossProcessMigration delegate state transitions.

**Tests**: 25 tests - comprehensive

### Phase 7C.6: Integration Testing (1 day) - APPROVED

**Design Assessment**: Ambitious but achievable
- 4-bubble scenario validates full stack
- 1000-tick run tests stability
- Determinism test validates reproducibility

**Tests**: 10 tests - appropriate for integration

---

## Timeline Assessment

### Critical Path Analysis

```
7C.1 (1.5d) -> 7C.2 (2d) -> 7C.3 (2d) -> 7C.5 (2d) -> 7C.6 (1d) = 8.5 days
                    |
7C.4 (1.5d) -------+  (parallel)
```

**Total**: 10 days allocated, 8.5 days critical path = 1.5 days buffer

**Assessment**: Timeline is realistic with appropriate buffer.

---

## Risk Assessment

| Risk | Severity | Probability | Mitigation |
|------|----------|-------------|------------|
| Phase 7B test failure | BLOCKER | CERTAIN | Fix before starting |
| View never stabilizes | HIGH | MEDIUM | Configurable threshold, timeout fallback |
| Event reprocessing window overflow | HIGH | LOW | Metrics, alerts, configurable limits |
| State machine edge cases | MEDIUM | MEDIUM | 25 tests, invariant checks |
| TPS regression | MEDIUM | LOW | Profile hot paths, batch processing |
| 64-bit Lamport overflow | LOW | NEGLIGIBLE | 9 quintillion ticks capacity |

---

## Recommendations

### Mandatory Before Starting

1. **Fix Phase 7B Test**: `TwoBubbleCommunicationTest.testGhostPositionMatchesOwned`
   - Investigate timestamp handling in `GhostStateManager.getGhostPosition()`
   - Ensure creation timestamp prevents extrapolation
   - All 6 Phase 7B tests must pass

### Implementation Guidance

2. **LamportClockGenerator Integration**: When modifying RealTimeController:
   - Keep existing `getLamportClock()` for backward compatibility
   - Add `getVectorTimestamp()` for new causality features
   - Update tests incrementally

3. **EntityMigrationStateMachine Integration**:
   - Consider TetrahedralMigration and CrossProcessMigration as clients of the state machine
   - Extract common state tracking logic
   - Avoid duplicating two-phase commit logic

4. **FirefliesViewMonitor**: Use `context.active()` as planned (API confirmed)
   - Consider logging view changes for debugging
   - Document stability threshold tuning guidance

### Testing Guidance

5. **Integration Test Setup**: FourBubbleCausalityTest will need:
   - Mock or real Fireflies context
   - InMemoryGhostChannel or actual Delos transport
   - Controlled tick injection for determinism

---

## Success Criteria Validation

| Criterion | Phase | Validation Method | Feasibility |
|-----------|-------|-------------------|-------------|
| Out-of-order events reprocessed | 7C.2, 7C.6 | EventReprocessorTest, FourBubbleCausalityTest | FEASIBLE |
| No causality violations | 7C.3, 7C.6 | CausalityPreserverTest, FourBubbleCausalityTest | FEASIBLE |
| View changes detected | 7C.4, 7C.6 | FirefliesViewMonitorTest, ViewChangeHandlingTest | FEASIBLE |
| All 1523+ tests passing | 7C.6 | Full regression suite | FEASIBLE |
| Entity retention: 100% | 7C.5, 7C.6 | EntityMigrationStateMachineTest | FEASIBLE |
| TPS >= 94 | 7C.6 | Performance test | FEASIBLE |
| Determinism: 3 runs | 7C.6 | FourBubbleCausalityTest | FEASIBLE |

---

## Conclusion

**CONDITIONAL GO**: The Phase 7C implementation plan is approved for execution after fixing the Phase 7B test blocker.

### Required Actions Before Implementation:
1. Fix `TwoBubbleCommunicationTest.testGhostPositionMatchesOwned`
2. Verify all 6 Phase 7B tests pass
3. Create sub-beads as documented in plan

### Plan Quality Score: 85/100
- Structure: 95/100 (excellent sub-phase breakdown)
- Technical Design: 90/100 (sound architecture)
- Risk Analysis: 85/100 (good coverage)
- Integration Points: 80/100 (needs state machine integration guidance)
- Dependency Validation: 75/100 (Phase 7B blocker missed in original plan)

---

**Auditor Signature**: plan-auditor (Claude Opus 4.5)
**Audit Date**: 2026-01-09
**Next Action**: Fix Phase 7B test, then create Phase 7C sub-beads
