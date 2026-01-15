# Phase 4 Architectural Analysis: Distributed Infrastructure Review

**Date**: 2026-01-15
**Bead**: Luciferase-rap1 (Phase 4: Distributed Multi-Process Coordination)
**Goal**: Identify what needs Prime-Mover @Entity conversion vs what duplicates Fireflies functionality

---

## Executive Summary

**CRITICAL FINDING**: ProcessCoordinator implements custom failure detection via heartbeat monitoring, duplicating Fireflies functionality that already exists in the codebase.

**User Feedback**: "Event driven heartbeat monitoring is irrelevant as we are based on Fireflies, which does its own failure detection that we inherit."

**Architectural Debt**: ProcessCoordinator was built before Fireflies integration was complete. It now has two separate failure detection systems running in parallel:
1. **Custom heartbeat** (ProcessCoordinator + ProcessRegistry) - REDUNDANT
2. **Fireflies view changes** (ViewCommitteeConsensus + FirefliesViewMonitor) - CORRECT

---

## Infrastructure Inventory

### 1. ProcessCoordinator.java (18KB) - NEEDS MAJOR REFACTORING

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessCoordinator.java`

**Current Architecture**:
```java
public class ProcessCoordinator {
    private final ScheduledExecutorService heartbeatScheduler;  // ⚠️ REDUNDANT
    private final ProcessRegistry registry;                      // ⚠️ Partially redundant
    private final CoordinatorElectionProtocol election;          // ⚠️ Questionable
    private final WallClockBucketScheduler bucketScheduler;      // ✅ Legitimate
    private final MessageOrderValidator messageValidator;        // ✅ Legitimate

    public void start() {
        // Start heartbeat monitoring ← DUPLICATES FIREFLIES
        heartbeatScheduler.scheduleAtFixedRate(
            this::monitorHeartbeats,
            ProcessRegistry.HEARTBEAT_INTERVAL_MS,
            ProcessRegistry.HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }
}
```

**Problems**:
- **Lines 74, 93-97, 149-154**: ScheduledExecutorService for heartbeat monitoring duplicates Fireflies
- Uses blocking calls, not Prime-Mover @Entity pattern
- CoordinatorElectionProtocol may be unnecessary (Fireflies provides view membership)

**Responsibilities** (which are legitimate vs redundant):

| Responsibility | Status | Rationale |
|----------------|--------|-----------|
| Heartbeat monitoring | ❌ DELETE | Fireflies provides failure detection via view changes |
| Process registration | ✅ KEEP | Fireflies tracks members, not which bubbles they host |
| Coordinator election | ⚠️ EVALUATE | Could derive coordinator from Fireflies view instead |
| Topology broadcasting | ✅ CONVERT | Legitimate, needs Prime-Mover @Entity conversion |
| Clock synchronization | ✅ KEEP | WallClockBucketScheduler is correct as-is |
| Message ordering | ✅ KEEP | MessageOrderValidator is legitimate |
| WAL crash recovery | ✅ KEEP | MigrationLogPersistence is correct |

---

### 2. ProcessRegistry.java (5.7KB) - PARTIALLY REDUNDANT

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/ProcessRegistry.java`

**Current Architecture**:
```java
public class ProcessRegistry {
    public static final long HEARTBEAT_INTERVAL_MS = 1000;     // ⚠️ REDUNDANT
    public static final long HEARTBEAT_TIMEOUT_MS = 3000;      // ⚠️ REDUNDANT

    private final Map<UUID, ProcessMetadata> processes;

    public boolean updateHeartbeat(UUID processId) { /* ... */ }  // ⚠️ REDUNDANT
    public boolean isAlive(UUID processId) { /* ... */ }          // ⚠️ REDUNDANT

    public UUID findProcess(UUID bubbleId) { /* ... */ }          // ✅ LEGITIMATE
    public List<UUID> getAllBubbles() { /* ... */ }               // ✅ LEGITIMATE
}
```

**Analysis**:
- **DELETE**: Heartbeat tracking (lines 51-52, 124-155) - Fireflies provides this
- **KEEP**: Process-to-bubble mapping (lines 100-170) - Fireflies doesn't track bubble assignments
- **KEEP**: Bubble lookup by process (lines 158-170) - Useful for migration coordination

**Refactored Scope**:
- Remove heartbeat-related methods
- Keep process/bubble mapping
- Integrate with Fireflies view via MembershipView instead of custom heartbeat

---

### 3. CoordinatorElectionProtocol.java (5.8KB) - QUESTIONABLE

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/CoordinatorElectionProtocol.java`

**Current Architecture**:
```java
public class CoordinatorElectionProtocol {
    public void startElection(List<UUID> candidateProcessIds) {
        // Deterministic winner: lowest UUID
        var winner = candidateProcessIds.stream()
                                        .min(UUID::compareTo)
                                        .orElseThrow();
        this.currentCoordinator = winner;
    }
}
```

**Analysis**:
- Uses "lowest UUID wins" algorithm (deterministic, no actual voting)
- Ballot tracking is mostly ceremonial
- **QUESTION**: Is this necessary or could we just pick coordinator from Fireflies view?

**Fireflies Alternative**:
```java
// Could replace election with:
var members = membershipView.getMembers().toList();
var coordinator = members.stream().min(UUID::compareTo).orElseThrow();
```

**Decision Needed**: Does coordinator role need to be separate from Fireflies membership, or can we derive it from the view?

---

### 4. WallClockBucketScheduler.java (5.7KB) - LEGITIMATE

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/WallClockBucketScheduler.java`

**Current Architecture**:
```java
public class WallClockBucketScheduler {
    private volatile Clock clock = Clock.system();
    private final AtomicLong currentBucket;

    public long bucketForTimestamp(long timestamp) {
        return timestamp / BUCKET_DURATION_MS;
    }

    public long getClockSkew(long timestamp) { /* ... */ }
}
```

**Analysis**:
- ✅ No ScheduledExecutorService - just calculations
- ✅ Clock skew detection is legitimate
- ✅ Bucket-to-wall-clock mapping is needed for distributed coordination
- **ACTION**: Keep as-is, or convert to Prime-Mover @Entity if it needs event-driven behavior

---

### 5. CrossProcessMigration.java (17KB) - KEEP

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/migration/CrossProcessMigration.java`

**Current Architecture**: 2PC implementation with remove-then-commit ordering

**Analysis**:
- ✅ Correct 2PC implementation (Phase 7D Day 1)
- ✅ IdempotencyStore for exactly-once semantics
- ✅ MigrationLogPersistence for crash recovery
- **ACTION**: Keep, but may need Prime-Mover @Entity conversion if it uses blocking calls

---

### 6. GrpcBubbleNetworkChannel.java (24KB) - KEEP

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/network/GrpcBubbleNetworkChannel.java`

**Analysis**:
- ✅ gRPC integration for cross-process communication
- ✅ Correct network abstraction
- **ACTION**: Keep as-is (network I/O layer, not coordination logic)

---

## Correct Fireflies Integration Pattern

### ViewCommitteeConsensus.java - REFERENCE IMPLEMENTATION

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/consensus/committee/ViewCommitteeConsensus.java`

**Pattern**:
```java
public class ViewCommitteeConsensus {
    private final FirefliesViewMonitor viewMonitor;

    public ViewCommitteeConsensus(MembershipView<?> membershipView) {
        this.viewMonitor = new FirefliesViewMonitor(membershipView);

        // Subscribe to view changes
        viewMonitor.onViewChange((newView) -> {
            log.info("View changed, rolling back pending proposals");
            rollbackPendingProposals();
        });
    }
}
```

**Key Insight**: Uses MembershipView abstraction to receive Fireflies notifications. No custom heartbeat monitoring.

### FirefliesViewMonitor.java - VIEW CHANGE LISTENER

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/causality/FirefliesViewMonitor.java`

**Pattern**:
```java
public class FirefliesViewMonitor {
    private final MembershipView<?> membershipView;

    public FirefliesViewMonitor(MembershipView<?> membershipView) {
        this.membershipView = membershipView;

        // Listen to Fireflies view changes
        membershipView.addListener(this::handleViewChange);
    }

    private void handleViewChange(ViewChange<?> change) {
        // View changed: members joined/left
        // This is automatic failure detection
    }
}
```

**Key Insight**: Fireflies provides failure detection via view changes. No polling, no heartbeat monitoring needed.

---

## Architecture Decision

### What Fireflies Already Provides

From ADR_001:
> **Message Propagation**: Uses existing Fireflies gossip broadcast
> - No separate P2P RPC infrastructure needed
> - 10-100x message reduction vs all-nodes-participate
> - **Leverages existing view membership and failure detection**

**Fireflies Capabilities**:
1. ✅ Cluster membership tracking (which processes are alive)
2. ✅ Failure detection (view changes when processes fail)
3. ✅ Virtual Synchrony (atomic view changes)
4. ✅ Byzantine Fault Tolerance (via committee selection)
5. ✅ Gossip broadcast (message propagation)

### What ProcessCoordinator Adds (Incorrectly)

**Duplicate Functionality**:
1. ❌ Heartbeat monitoring (lines 149-154) - Fireflies provides this
2. ❌ Failure detection (ProcessRegistry.isAlive) - Fireflies provides this
3. ❌ Custom liveness tracking - Fireflies provides this

### What ProcessCoordinator Should Provide (Legitimately)

**Non-Duplicate Functionality**:
1. ✅ Process-to-bubble mapping (Fireflies tracks members, not bubbles)
2. ✅ Topology updates (which bubbles exist in the system)
3. ⚠️ Coordinator election (QUESTION: needed or can derive from Fireflies view?)
4. ✅ Clock synchronization (WallClockBucketScheduler)
5. ✅ Message ordering validation (MessageOrderValidator)

---

## Phase 4 Scope Revision

### Option A (Original): Full Prime-Mover Integration

**PROBLEM**: Assumes all ProcessCoordinator functionality is legitimate and just needs Prime-Mover conversion.

**Reality**: Much of ProcessCoordinator is **architectural debt** that duplicates Fireflies.

### Option A (Corrected): Eliminate Redundancy + Prime-Mover Integration

**Step 1: Delete Redundant Code** (1-2 days)
- Delete heartbeat monitoring from ProcessCoordinator (lines 74, 93-97, 149-154)
- Delete heartbeat tracking from ProcessRegistry (lines 51-52, 124-155)
- Delete ProcessRegistry.isAlive() (Fireflies provides this via view changes)

**Step 2: Fireflies Integration** (2-3 days)
- Refactor ProcessCoordinator to use MembershipView (like ViewCommitteeConsensus)
- Subscribe to Fireflies view changes for failure detection
- Replace custom heartbeat with Fireflies view change listener

**Step 3: Evaluate Coordinator Election** (1 day)
- **Decision Required**: Is CoordinatorElectionProtocol needed?
- **Alternative**: Derive coordinator from Fireflies view (lowest UUID)
- If election is kept, integrate with Fireflies view (not custom heartbeat)

**Step 4: Prime-Mover @Entity Conversion** (3-5 days)
- Convert remaining ProcessCoordinator coordination logic to Prime-Mover @Entity
- Follow BucketScheduler.BucketSchedulerEntity pattern (event-driven polling)
- Replace ScheduledExecutorService with Kronos.sleep() + recursive events

**Step 5: Integration Testing** (2-3 days)
- Multi-process distributed tests
- Fireflies view change simulation (process join/leave)
- Verify no duplicate failure detection
- Performance validation (no heartbeat overhead)

**Total Effort**: 9-14 days (vs 14-21 days original estimate)

---

## Critical Questions for User

### 1. Coordinator Election
**Question**: Does the coordinator role need a separate election protocol, or can we just pick the coordinator from the Fireflies view (e.g., lowest UUID in current view)?

**Option A (Keep Election)**:
- Separate coordinator election with voting
- Allows coordinator to be different from "lowest UUID"
- More complex, requires election protocol integration

**Option B (Derive from View)**:
- Coordinator = member with lowest UUID in current Fireflies view
- Simpler, eliminates CoordinatorElectionProtocol entirely
- Deterministic, no voting needed

**Recommendation**: Option B unless there's a specific reason the coordinator must be elected separately.

### 2. Process-to-Bubble Mapping
**Question**: Should ProcessRegistry remain as a separate mapping, or should this be integrated into Fireflies metadata?

**Current**: ProcessRegistry tracks which bubbles each process hosts
**Fireflies**: Tracks members, but doesn't know which bubbles they host

**Recommendation**: Keep ProcessRegistry for process-to-bubble mapping, but remove heartbeat tracking.

### 3. Timing
**Question**: Should we do this refactoring as part of Phase 4, or defer it?

**Option A (Refactor Now)**:
- Eliminate architectural debt before Prime-Mover integration
- Cleaner codebase for Prime-Mover conversion
- Avoids converting redundant code to @Entity pattern

**Option B (Defer)**:
- Focus Phase 4 purely on Prime-Mover conversion
- Accept heartbeat monitoring as "temporary" technical debt
- Risk: Converting redundant code to @Entity pattern

**Recommendation**: Refactor now (Option A) - don't want to convert redundant code to Prime-Mover.

---

## Proposed Phase 4 Plan (Revised)

### Phase 4.1: Eliminate Fireflies Redundancy (3-5 days)
1. Delete heartbeat monitoring from ProcessCoordinator
2. Refactor ProcessRegistry to remove heartbeat tracking
3. Integrate ProcessCoordinator with MembershipView (Fireflies)
4. Decide on coordinator election (keep vs derive from view)
5. Tests pass with Fireflies-based failure detection

**Exit Criteria**:
- No ScheduledExecutorService for heartbeat monitoring
- ProcessCoordinator uses Fireflies view changes for failure detection
- Tests pass with new architecture

### Phase 4.2: Prime-Mover @Entity Conversion (5-7 days)
1. Convert ProcessCoordinator to inner @Entity pattern
2. Replace remaining blocking calls with event-driven polling
3. Follow BucketScheduler.BucketSchedulerEntity template
4. Integration testing with Prime-Mover event loop

**Exit Criteria**:
- ProcessCoordinator uses Prime-Mover @Entity pattern
- No blocking calls in coordination logic
- Performance >= baseline (no heartbeat overhead)

### Phase 4.3: Integration & Validation (2-3 days)
1. Multi-process distributed tests
2. Fireflies view change scenarios (join/leave/fail)
3. Performance profiling
4. Documentation updates

**Total Effort**: 10-15 days (reduced from original 14-21 days due to code deletion)

---

## Files Requiring Changes

### DELETE (Heartbeat Monitoring)
- ProcessCoordinator.java lines 74, 93-97, 149-154 (heartbeatScheduler)
- ProcessRegistry.java lines 51-52 (HEARTBEAT_INTERVAL_MS, HEARTBEAT_TIMEOUT_MS)
- ProcessRegistry.java lines 124-155 (updateHeartbeat, isAlive methods)

### REFACTOR (Fireflies Integration)
- ProcessCoordinator.java (add MembershipView, subscribe to view changes)
- ProcessRegistry.java (keep process/bubble mapping, remove heartbeat)
- CoordinatorElectionProtocol.java (evaluate: keep vs eliminate)

### CONVERT (Prime-Mover @Entity)
- ProcessCoordinator.java (inner @Entity class for coordination logic)
- Topology broadcasting logic (event-driven)

### KEEP AS-IS
- WallClockBucketScheduler.java (clock synchronization)
- MessageOrderValidator.java (message ordering)
- CrossProcessMigration.java (2PC implementation)
- GrpcBubbleNetworkChannel.java (network layer)
- MigrationLogPersistence.java (WAL crash recovery)

---

## References

- **ADR_001**: `simulation/doc/ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md`
  - Lines 78-108: Fireflies consensus architecture
  - "Leverages existing view membership and failure detection"

- **Correct Pattern**: `ViewCommitteeConsensus.java`
  - Uses FirefliesViewMonitor for view change notifications
  - No custom heartbeat monitoring

- **Fireflies Abstraction**: `MembershipView.java`
  - Interface for cluster membership
  - ViewChange notifications for join/leave/fail events

- **Phase 3 Template**: `BucketScheduler.java`
  - Prime-Mover @Entity pattern with event-driven polling
  - Kronos.sleep() + recursive advanceBucket() events

---

## Next Steps

1. **User Decision**: Approve revised Phase 4 plan (eliminate redundancy + Prime-Mover)
2. **Answer Critical Questions**: Coordinator election (keep vs derive), timing (refactor now vs defer)
3. **Create Detailed Implementation Plan**: Break down into beads with dependencies
4. **Spawn Agents**: plan-auditor for validation, java-architect-planner for detailed design

---

**Status**: Awaiting user approval for revised Phase 4 approach
**Bead**: Luciferase-rap1
**Last Updated**: 2026-01-15
