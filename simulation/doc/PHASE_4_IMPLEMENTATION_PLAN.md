# Phase 4 Implementation Plan: Distributed Multi-Process Coordination

**Bead**: Luciferase-rap1
**Date**: 2026-01-15
**Status**: Ready to Execute
**Approach**: Clean sweep - eliminate redundancy FIRST, then Prime-Mover conversion

---

## User Decisions (Confirmed)

1. **Coordinator Election**: Derive from Fireflies view using **ring ordering** (better distribution than lowest UUID)
2. **Timing**: Refactor NOW - "take the hit and sweep the place clean"
3. **CoordinatorElectionProtocol**: DELETE entirely (replaced by ring ordering from view)

---

## Phase 4.1: Eliminate Fireflies Redundancy (3-5 days)

**Goal**: Remove all custom failure detection and heartbeat monitoring. Integrate with Fireflies MembershipView.

### Bead 4.1.1: Delete CoordinatorElectionProtocol

**Effort**: 1 day
**Priority**: P0

**Actions**:
1. Delete `CoordinatorElectionProtocol.java` entirely (165 lines)
2. Remove election field from ProcessCoordinator (line 73)
3. Remove election-related methods from ProcessCoordinator:
   - `conductElection()` (lines 249-272)
   - Election initialization in `start()` (if any)
   - `unregisterProcess()` election check (lines 204-209)
4. Update tests to remove CoordinatorElectionProtocol references

**Verification**:
- [ ] CoordinatorElectionProtocol.java deleted
- [ ] All references removed from ProcessCoordinator
- [ ] Tests compile and pass

**Files**:
- DELETE: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/CoordinatorElectionProtocol.java`
- MODIFY: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessCoordinator.java`

---

### Bead 4.1.2: Delete Heartbeat Monitoring from ProcessCoordinator

**Effort**: 1 day
**Priority**: P0
**Depends On**: 4.1.1

**Actions**:
1. Delete ScheduledExecutorService field (line 74)
2. Delete heartbeat scheduler initialization (lines 93-97)
3. Delete `monitorHeartbeats()` method (lines 149-154 area, need full read)
4. Delete heartbeat scheduling in `start()` (lines 149-154)
5. Update `stop()` to remove scheduler shutdown (lines 168-176)
6. Remove `processHeartbeatAck()` method (lines 219-225)

**Verification**:
- [ ] No ScheduledExecutorService in ProcessCoordinator
- [ ] No heartbeat monitoring methods
- [ ] Tests compile and pass

**Files**:
- MODIFY: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessCoordinator.java`

---

### Bead 4.1.3: Refactor ProcessRegistry (Remove Heartbeat Tracking)

**Effort**: 1 day
**Priority**: P0
**Depends On**: 4.1.2

**Actions**:
1. Delete constants (lines 51-52):
   - `HEARTBEAT_INTERVAL_MS`
   - `HEARTBEAT_TIMEOUT_MS`
2. Delete methods:
   - `updateHeartbeat()` (lines 128-136)
   - `isAlive()` (lines 147-155)
3. Update `ProcessMetadata` record to remove `lastHeartbeat` field
4. Keep legitimate methods:
   - `register()`, `unregister()`, `getProcess()`
   - `getAllBubbles()`, `getAllProcesses()`
   - `findProcess()` (process-to-bubble mapping)

**Verification**:
- [ ] No heartbeat constants or methods
- [ ] Process-to-bubble mapping intact
- [ ] Tests compile and pass

**Files**:
- MODIFY: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessRegistry.java`

---

### Bead 4.1.4: Integrate ProcessCoordinator with MembershipView

**Effort**: 2 days
**Priority**: P0
**Depends On**: 4.1.1, 4.1.2, 4.1.3

**Actions**:
1. Add MembershipView field to ProcessCoordinator:
   ```java
   private final MembershipView<?> membershipView;
   ```

2. Add FirefliesViewMonitor field:
   ```java
   private final FirefliesViewMonitor viewMonitor;
   ```

3. Update constructor to accept MembershipView:
   ```java
   public ProcessCoordinator(VonTransport transport, MembershipView<?> membershipView) {
       this.membershipView = membershipView;
       this.viewMonitor = new FirefliesViewMonitor(membershipView);
   }
   ```

4. Subscribe to view changes in `start()`:
   ```java
   viewMonitor.onViewChange((viewChange) -> {
       handleViewChange(viewChange);
   });
   ```

5. Implement `handleViewChange()`:
   ```java
   private void handleViewChange(ViewChange<?> change) {
       // Members left = process failures (automatic failure detection)
       for (var member : change.left()) {
           UUID processId = extractProcessId(member);
           log.info("Fireflies detected process failure: {}", processId);
           unregisterProcess(processId);
       }

       // Members joined = new processes
       for (var member : change.joined()) {
           UUID processId = extractProcessId(member);
           log.info("Fireflies detected process join: {}", processId);
           // Process will call registerProcess() separately with bubble list
       }

       // Recompute coordinator via ring ordering
       updateCoordinatorFromView();
   }
   ```

6. Implement coordinator selection via ring ordering:
   ```java
   private void updateCoordinatorFromView() {
       var members = membershipView.getMembers().toList();
       if (members.isEmpty()) {
           currentCoordinator = null;
           return;
       }

       // Ring ordering: deterministic, randomly distributed via hash
       // Use Fireflies ring ordering utilities (need to identify exact API)
       var coordinator = selectCoordinatorViaRingOrdering(members);

       if (!Objects.equals(currentCoordinator, coordinator)) {
           log.info("Coordinator changed: {} -> {}", currentCoordinator, coordinator);
           currentCoordinator = coordinator;
           broadcastTopologyUpdate(registry.getAllBubbles());
       }
   }
   ```

7. Research Fireflies ring ordering API:
   - Check Delos Fireflies documentation
   - Look for ring ordering utilities in existing codebase
   - Likely in `com.salesforce.apollo.fireflies` package

**Verification**:
- [ ] ProcessCoordinator uses MembershipView
- [ ] View changes trigger failure detection
- [ ] Coordinator derived via ring ordering
- [ ] Tests pass with Fireflies-based coordination

**Files**:
- MODIFY: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessCoordinator.java`
- REFERENCE: `simulation/src/main/java/com/hellblazer/luciferase/simulation/consensus/committee/ViewCommitteeConsensus.java`
- REFERENCE: `simulation/src/main/java/com/hellblazer/luciferase/simulation/causality/FirefliesViewMonitor.java`

---

### Bead 4.1.5: Update Tests for Fireflies Integration

**Effort**: 1 day
**Priority**: P0
**Depends On**: 4.1.4

**Actions**:
1. Find all ProcessCoordinator tests
2. Replace heartbeat test assertions with view change tests
3. Add MockFirefliesView or use existing mock
4. Verify coordinator selection via ring ordering
5. Test failure detection via view changes (not heartbeat timeout)

**Verification**:
- [ ] All ProcessCoordinator tests pass
- [ ] No tests reference heartbeat monitoring
- [ ] View change tests validate failure detection
- [ ] Coordinator selection tests validate ring ordering

**Files**:
- MODIFY: `simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/*Test.java`

---

## Phase 4.2: Prime-Mover @Entity Conversion (5-7 days)

**Goal**: Convert remaining coordination logic to Prime-Mover @Entity pattern.

### Bead 4.2.1: Create ProcessCoordinatorEntity Inner Class

**Effort**: 2 days
**Priority**: P1
**Depends On**: Phase 4.1 complete

**Actions**:
1. Create inner `@Entity` class following BucketScheduler pattern:
   ```java
   @Entity
   public class ProcessCoordinatorEntity {
       private static final long POLL_INTERVAL_NS = 10_000_000; // 10ms polling

       @NonEvent
       public UUID getCurrentCoordinator() { return currentCoordinator; }

       public void coordinationTick() {
           // 1. Check for topology changes
           // 2. Broadcast updates if needed
           // 3. Process pending coordination tasks

           // Schedule next tick
           Kronos.sleep(POLL_INTERVAL_NS);
           this.coordinationTick();
       }
   }
   ```

2. Refactor ProcessCoordinator to orchestrator + @Entity pattern:
   ```java
   public class ProcessCoordinator {
       private final ProcessCoordinatorEntity entity;
       private final RealTimeController controller;

       public ProcessCoordinator(VonTransport transport, MembershipView<?> membershipView) {
           // Initialize dependencies
           this.controller = new RealTimeController("ProcessCoordinator");
           this.entity = new ProcessCoordinatorEntity();
           Kairos.setController(controller);
       }

       public void start() {
           entity.coordinationTick();  // Start event loop
           controller.start();
       }
   }
   ```

3. Move coordination logic from blocking methods to event-driven tick

**Verification**:
- [ ] ProcessCoordinatorEntity follows Prime-Mover pattern
- [ ] All getters marked @NonEvent
- [ ] Uses Kronos.sleep() + recursive events
- [ ] Tests compile

**Files**:
- MODIFY: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessCoordinator.java`
- REFERENCE: `simulation/src/main/java/com/hellblazer/luciferase/simulation/scheduling/BucketScheduler.java` (lines 1-100)

---

### Bead 4.2.2: Convert CrossProcessMigration to Event-Driven

**Effort**: 2 days
**Priority**: P1
**Depends On**: 4.2.1

**Actions**:
1. Audit CrossProcessMigration for blocking calls
2. Convert blocking 2PC phases to event-driven pattern
3. Use Kronos.sleep() for phase timeouts instead of Thread.sleep()
4. Integrate with ProcessCoordinatorEntity

**Verification**:
- [ ] No blocking calls in migration flow
- [ ] 2PC phases use event-driven timing
- [ ] Tests pass with Prime-Mover timing

**Files**:
- MODIFY: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/migration/CrossProcessMigration.java`

---

### Bead 4.2.3: Event-Driven Topology Broadcasting

**Effort**: 1 day
**Priority**: P1
**Depends On**: 4.2.1

**Actions**:
1. Move topology broadcasting to ProcessCoordinatorEntity.coordinationTick()
2. Detect topology changes (new bubbles, removed bubbles)
3. Broadcast updates via events (not blocking calls)
4. Rate-limit broadcasts (max 1 per second)

**Verification**:
- [ ] Topology updates event-driven
- [ ] No blocking broadcasts
- [ ] Tests pass

**Files**:
- MODIFY: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessCoordinator.java`

---

### Bead 4.2.4: Update All Call Sites

**Effort**: 1-2 days
**Priority**: P1
**Depends On**: 4.2.1, 4.2.2, 4.2.3

**Actions**:
1. Find all ProcessCoordinator instantiations
2. Update to provide MembershipView parameter
3. Update tests to use Prime-Mover RealTimeController
4. Verify no blocking calls remain

**Verification**:
- [ ] All call sites updated
- [ ] Tests pass with new constructor signature
- [ ] Integration tests pass

**Files**:
- SEARCH: All ProcessCoordinator usages

---

## Phase 4.3: Integration & Validation (2-3 days)

**Goal**: Comprehensive testing and performance validation.

### Bead 4.3.1: Multi-Process Distributed Tests

**Effort**: 1 day
**Priority**: P1
**Depends On**: Phase 4.2 complete

**Actions**:
1. Create 3-process distributed test scenario
2. Test process join (view change)
3. Test process failure (view change)
4. Test coordinator selection (ring ordering)
5. Test migration coordination across processes

**Verification**:
- [ ] 3-process test passes
- [ ] Coordinator selected via ring ordering
- [ ] Failure detection via Fireflies (no heartbeat)
- [ ] Migration coordination works

**Files**:
- CREATE: `simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/MultiProcessCoordinationTest.java`

---

### Bead 4.3.2: Performance Validation

**Effort**: 1 day
**Priority**: P1
**Depends On**: 4.3.1

**Actions**:
1. Measure coordination overhead (should be LOWER without heartbeat)
2. Measure migration latency (should be same or better)
3. Measure memory usage (should be LOWER without heartbeat scheduler)
4. Document performance improvements

**Verification**:
- [ ] Coordination overhead < baseline (no heartbeat cost)
- [ ] Migration latency <= baseline
- [ ] Memory usage < baseline
- [ ] Documentation updated

**Files**:
- UPDATE: `simulation/doc/PERFORMANCE_METRICS_MASTER.md`

---

### Bead 4.3.3: Documentation Updates

**Effort**: 1 day
**Priority**: P1
**Depends On**: 4.3.1, 4.3.2

**Actions**:
1. Update ADR_001 to document Phase 4 changes
2. Update CLAUDE.md with Prime-Mover coordination patterns
3. Create migration guide for future work
4. Document ring ordering coordinator selection

**Verification**:
- [ ] ADR_001 updated
- [ ] CLAUDE.md updated
- [ ] Migration guide complete
- [ ] All documentation accurate

**Files**:
- UPDATE: `simulation/doc/ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md`
- UPDATE: `CLAUDE.md`
- CREATE: `simulation/doc/DISTRIBUTED_COORDINATION_PATTERNS.md`

---

## Bead Summary

| Bead ID | Title | Effort | Priority | Dependencies |
|---------|-------|--------|----------|--------------|
| 4.1.1 | Delete CoordinatorElectionProtocol | 1 day | P0 | None |
| 4.1.2 | Delete heartbeat from ProcessCoordinator | 1 day | P0 | 4.1.1 |
| 4.1.3 | Refactor ProcessRegistry | 1 day | P0 | 4.1.2 |
| 4.1.4 | Integrate MembershipView | 2 days | P0 | 4.1.1-4.1.3 |
| 4.1.5 | Update tests for Fireflies | 1 day | P0 | 4.1.4 |
| 4.2.1 | Create ProcessCoordinatorEntity | 2 days | P1 | Phase 4.1 |
| 4.2.2 | Convert CrossProcessMigration | 2 days | P1 | 4.2.1 |
| 4.2.3 | Event-driven topology broadcasting | 1 day | P1 | 4.2.1 |
| 4.2.4 | Update all call sites | 1-2 days | P1 | 4.2.1-4.2.3 |
| 4.3.1 | Multi-process tests | 1 day | P1 | Phase 4.2 |
| 4.3.2 | Performance validation | 1 day | P1 | 4.3.1 |
| 4.3.3 | Documentation updates | 1 day | P1 | 4.3.1-4.3.2 |

**Total Effort**: 14-16 days (front-loaded with cleanup)

---

## Critical Research Item: Fireflies Ring Ordering API

**Need to identify**:
- How to derive coordinator from Fireflies view using ring ordering
- Fireflies API for ring-based member selection
- Hash mixing with view ID and member ID

**Likely locations**:
- Delos Fireflies package: `com.salesforce.apollo.fireflies`
- Ring utilities or consistent hashing implementation
- ViewCommitteeConsensus may have examples

**Action**: Search codebase for ring ordering utilities before starting 4.1.4

---

## Exit Criteria (Phase 4 Complete)

- [ ] No ScheduledExecutorService in distributed coordination
- [ ] No custom heartbeat monitoring (Fireflies provides failure detection)
- [ ] Coordinator derived from Fireflies view via ring ordering
- [ ] ProcessCoordinator uses Prime-Mover @Entity pattern
- [ ] All blocking calls eliminated from coordination logic
- [ ] Tests pass (unit, integration, multi-process)
- [ ] Performance >= baseline (should be better without heartbeat overhead)
- [ ] Documentation complete and accurate

---

## Risk Mitigation

### Risk 1: Fireflies Ring Ordering API Unknown
**Mitigation**: Research before starting 4.1.4, consult Delos documentation, check existing usage

### Risk 2: Breaking Existing Tests
**Mitigation**: Update tests incrementally in 4.1.5, keep old tests until new ones pass

### Risk 3: Performance Regression
**Mitigation**: Measure baseline before changes, profile after each phase, revert if needed

### Risk 4: Integration Issues
**Mitigation**: Multi-process tests in 4.3.1, gradual rollout, fallback plan

---

**Status**: Ready to execute
**Next Action**: Create beads for Phase 4.1 and start cleanup
**Last Updated**: 2026-01-15
