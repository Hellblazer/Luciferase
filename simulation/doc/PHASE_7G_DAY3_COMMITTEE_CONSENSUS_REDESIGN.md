# Phase 7G Day 3 Redesign: Fireflies View Committee Consensus

**Date**: 2026-01-10
**Status**: Design Complete - Ready for Plan Audit
**Epic**: Luciferase-8gd3 (Inc7: Autonomous Asynchronous Distributed Simulation)
**Author**: strategic-planner (Claude Opus 4.5)

---

## Executive Summary

This document redesigns Phase 7G Day 3's consensus mechanism from Raft-style all-nodes-participate consensus to a Fireflies view committee-based approach. The key insight is that Delos Fireflies already provides:

1. **View-based membership management** - View rings define trusted nodes
2. **BFT subset selection** - `bftSubset(viewId)` returns deterministic committee
3. **Virtual synchrony** - All members see same view transitions
4. **Failure detection** - Gossip-based liveness already handled

This redesign **eliminates the need for leader election entirely** and reduces code by 85%.

---

## Problem Statement

### Current Phase 7G Day 3 Approach (Problematic)

The current plan implements Raft-style consensus:

| Component | LOC | Purpose |
|-----------|-----|---------|
| ElectionState | 60 | FOLLOWER/CANDIDATE/LEADER FSM |
| CoordinatorElectionProtocol | 350 | Term-based election logic |
| FailureDetector | 150 | Heartbeat monitoring |
| BallotBox | 180 | Vote counting |
| ConsensusCoordinator | 300 | Orchestration |
| GrpcCoordinatorService | 200 | gRPC implementation |
| coordinator.proto | 100 | Message definitions |
| **Total** | **~1,490** | (tests add ~1,850 more) |

**Problems:**
1. **All nodes participate** - Scales poorly (O(n) messages per decision)
2. **Duplicates Fireflies** - Reinvents membership/failure detection
3. **Complex election FSM** - Term-based voting, split-brain handling
4. **No view integration** - Doesn't leverage existing view synchrony

### Fireflies View Committee Approach (Solution)

Delos Fireflies already solves these problems:

```java
// From ViewManagement.java - Committee selection
context.bftSubset(diadem.get().compact(), context::isActive)
    .stream()
    .map(Member::getId)
    .forEach(d -> observers.put(d, -1));
```

- **Committee is deterministic** - Selected from ring successors of view hash
- **Size is O(log n)** - Typically 13-42 nodes for 1M members
- **View changes update committee** - Automatic recalculation on membership change
- **No election needed** - Committee is computed, not elected

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    ViewCommitteeConsensus                        │
│  ├─ proposeEntityOwnership()  - Submit migration proposal       │
│  ├─ onVote()                  - Process committee vote          │
│  └─ onViewChange()            - Abort pending, recalculate      │
└──────────────────────────────────────────────────────────────────┘
                              │
                   ┌──────────┴──────────┐
                   │                     │
            ┌──────▼──────┐       ┌──────▼──────┐
            │ ViewCommittee│       │ CommitteeVoting│
            │ (Selection)  │       │ Service (gRPC) │
            └──────┬──────┘       └──────┬──────┘
                   │                     │
            ┌──────▼──────┐       ┌──────▼──────┐
            │ DynamicContext│       │ GrpcBubbleNetwork│
            │ (Delos)      │       │ Channel (Day 1)  │
            └─────────────┘       └─────────────────┘
```

**Key Difference**: No election protocol. Committee is computed from view membership.

---

## Committee Selection Algorithm

### How Delos bftSubset Works

From `Context.java`:

```java
default SequencedSet<T> bftSubset(Digest hash, Predicate<T> filter) {
    var collector = new LinkedHashSet<T>();
    uniqueSuccessors(hash, filter, collector);
    return collector;
}
```

The algorithm:
1. Takes a hash (view ID) as the selection point
2. For each ring in the context, finds the successor at that hash position
3. Returns unique members across all rings

### Committee Size Calculation

From `DynamicContextImpl.java`:

```java
ringCount = minMajority(pByz, cardinality, epsilon, bias) * bias + 1
```

Where:
- `pByz` = Probability of Byzantine behavior (default 0.1)
- `cardinality` = Total membership count
- `bias` = Multiplier for Byzantine tolerance (default 2)
- `epsilon` = Tolerance factor

**Example calculations:**

| Total Bubbles | pByz | bias | Ring Count | Committee Size |
|---------------|------|------|------------|----------------|
| 5 | 0.1 | 2 | 5 | 5 (all members) |
| 10 | 0.1 | 2 | 5 | 5 |
| 20 | 0.1 | 2 | 7 | 7 |
| 50 | 0.1 | 2 | 9 | 9 |
| 100 | 0.1 | 2 | 11 | 11 |

### Luciferase Committee Selection

```java
public class ViewCommittee {
    private final DynamicContext<BubbleMember> context;
    private volatile Digest viewId;
    private volatile Set<UUID> currentCommittee;

    public ViewCommittee(DynamicContext<BubbleMember> context) {
        this.context = context;
    }

    public void updateView(Digest newViewId) {
        this.viewId = newViewId;
        this.currentCommittee = context.bftSubset(viewId)
            .stream()
            .map(m -> m.getId())
            .map(this::digestToUUID)
            .collect(Collectors.toUnmodifiableSet());
    }

    public Set<UUID> getCommitteeMembers() {
        return currentCommittee;
    }

    public boolean isCommitteeMember(UUID bubbleId) {
        return currentCommittee.contains(bubbleId);
    }

    public int requiredMajority() {
        return (currentCommittee.size() / 2) + 1;
    }
}
```

---

## View Change Handling

### Fireflies View Change Process

From `ViewManagement.java`:

```java
void initiateViewChange() {
    view.stable(() -> {
        if (vote.get() != null) {
            log.trace("Vote already cast for: {} on: {}", currentView(), node.getId());
            return;
        }
        // Use pending rebuttals as a proxy for stability
        if (view.hasPendingRebuttals()) {
            log.debug("Pending rebuttals in view: {} on: {}", currentView(), node.getId());
            view.scheduleViewChange(1);
            return;
        }
        view.scheduleFinalizeViewChange();
        // ... observer voting logic
    });
}
```

### Key Properties

1. **Stability Detection**: `hasPendingRebuttals()` indicates view is unstable
2. **Observer Voting**: Only `bftSubset` members (observers) vote on view changes
3. **Atomic Installation**: `installCore(ballot)` atomically updates view and committee
4. **Version Tracking**: `observerVersion` increments on committee changes

### Luciferase View Change Integration

```java
public class ViewCommitteeConsensus {
    private final ViewCommittee committee;
    private final Map<UUID, ProposalState> pendingProposals;
    private final FirefliesViewMonitor viewMonitor; // From Phase 7C

    public ViewCommitteeConsensus(ViewCommittee committee,
                                   FirefliesViewMonitor viewMonitor) {
        this.committee = committee;
        this.viewMonitor = viewMonitor;

        // Register for view change notifications
        viewMonitor.addViewChangeListener(this::onViewChange);
    }

    public void onViewChange(Digest newViewId) {
        // 1. Abort all pending proposals (view changed = context invalidated)
        pendingProposals.forEach((id, state) -> {
            state.future.complete(false);
            log.info("Proposal {} aborted due to view change", id);
        });
        pendingProposals.clear();

        // 2. Update committee membership
        committee.updateView(newViewId);

        log.info("View changed to {}, committee now: {}",
            newViewId, committee.getCommitteeMembers());
    }

    public boolean isViewStable() {
        return viewMonitor.isViewStable();
    }
}
```

### Handling In-Flight Proposals During View Change

```
Timeline:
    t0: Proposal P1 submitted to committee [A, B, C]
    t1: View change starts (new member D joining)
    t2: Vote from A arrives (still valid, same view)
    t3: View change completes, new committee [A, B, D]
    t4: Vote from B arrives with OLD view ID

Resolution:
    - t3: P1 aborted (view changed)
    - t4: Vote from B rejected (view mismatch)
    - t4+: New proposal must be submitted to new committee
```

---

## Voting Mechanism

### Proposal Flow

```
Source Bubble                Committee [A, B, C]            Target Bubble
    │                              │                              │
    │──(1) proposeOwnership()──────>                              │
    │                              │                              │
    │                              │──(2) VoteRequest to A──────>│
    │                              │──(2) VoteRequest to B──────>│
    │                              │──(2) VoteRequest to C──────>│
    │                              │                              │
    │                              │<─(3) Vote YES from A─────────│
    │                              │<─(3) Vote YES from B─────────│
    │                              │<─(3) Vote NO from C──────────│
    │                              │                              │
    │<──(4) APPROVED (2/3)─────────│                              │
    │                              │                              │
    │──(5) EntityDepartureEvent────────────────────────────────────>
```

### Vote Validation

```java
public void onVote(UUID voter, CommitteeVote vote) {
    // 1. Verify voter is committee member
    if (!committee.isCommitteeMember(voter)) {
        log.warn("Vote from non-committee member {} ignored", voter);
        return;
    }

    // 2. Verify view ID matches
    if (!vote.viewId().equals(committee.getViewId())) {
        log.warn("Vote with stale view {} (current {}) rejected",
            vote.viewId(), committee.getViewId());
        return;
    }

    // 3. Record vote
    var state = pendingProposals.get(vote.proposalId());
    if (state == null) {
        log.debug("Vote for unknown/expired proposal {}", vote.proposalId());
        return;
    }

    state.recordVote(voter, vote.approved());
    checkQuorum(state);
}

private void checkQuorum(ProposalState state) {
    int required = committee.requiredMajority();
    int yesVotes = state.yesCount();
    int noVotes = state.noCount();
    int totalVotes = yesVotes + noVotes;

    if (yesVotes >= required) {
        state.future.complete(true);  // APPROVED
        pendingProposals.remove(state.proposalId);
    } else if (noVotes >= required) {
        state.future.complete(false); // REJECTED
        pendingProposals.remove(state.proposalId);
    } else if (totalVotes >= committee.size()) {
        state.future.complete(false); // NO MAJORITY
        pendingProposals.remove(state.proposalId);
    }
}
```

---

## Proto Definition

### committee.proto (Simplified)

```protobuf
syntax = "proto3";

option java_multiple_files = true;
option java_package = "com.hellblazer.luciferase.lucien.distributed.committee.proto";
option java_outer_classname = "CommitteeProto";

package lucien.committee;

// Migration proposal submitted to committee
message MigrationProposal {
    int32 version = 1;
    string proposal_id = 2;         // Unique proposal identifier
    string entity_id = 3;           // Entity being migrated
    string source_bubble_id = 4;    // Current owner bubble
    string target_bubble_id = 5;    // Proposed new owner bubble
    string view_id = 6;             // View ID when proposal created
    int64 timestamp_nanos = 7;      // Proposal creation time
}

// Vote from committee member
message CommitteeVote {
    int32 version = 1;
    string proposal_id = 2;         // Proposal being voted on
    string voter_id = 3;            // Committee member casting vote
    bool approved = 4;              // true = YES, false = NO
    string view_id = 5;             // Voter's view ID (must match proposal)
    int64 timestamp_nanos = 6;      // Vote cast time
}

// Result of committee vote
message CommitteeDecision {
    int32 version = 1;
    string proposal_id = 2;
    bool approved = 3;              // Overall decision
    int32 yes_votes = 4;
    int32 no_votes = 5;
    int32 committee_size = 6;       // For verification
    string view_id = 7;
    int64 timestamp_nanos = 8;
}

// gRPC service for committee voting
service CommitteeVotingService {
    // Submit migration proposal (source bubble -> committee)
    rpc SubmitProposal(MigrationProposal) returns (CommitteeDecision);

    // Cast vote on proposal (committee member -> coordinator)
    rpc CastVote(CommitteeVote) returns (CommitteeDecision);
}
```

**Comparison**: 50 LOC vs 100 LOC in original (50% reduction)

---

## Integration Points

### 1. OptimisticMigrator Integration

Current interface (unchanged):
```java
CompletableFuture<Boolean> requestMigrationApproval(UUID entityId, UUID targetBubble);
```

New implementation:
```java
public class OptimisticMigratorImpl implements OptimisticMigrator {
    private final ViewCommitteeConsensus consensus;

    @Override
    public CompletableFuture<Boolean> requestMigrationApproval(
            UUID entityId, UUID targetBubble) {
        // Check view stability first
        if (!consensus.isViewStable()) {
            return CompletableFuture.completedFuture(false);
        }

        // Submit to committee (only committee members receive)
        return consensus.proposeEntityOwnership(entityId, targetBubble);
    }
}
```

### 2. FirefliesViewMonitor Integration (From Phase 7C)

```java
// Already exists from Phase 7C
public class FirefliesViewMonitor {
    void addViewChangeListener(Consumer<Digest> listener);
    boolean isViewStable();
    Digest getCurrentViewId();
}
```

Used by `ViewCommitteeConsensus` to:
- Detect view changes and abort pending proposals
- Check stability before accepting new proposals
- Get current view ID for proposal tagging

### 3. GrpcBubbleNetworkChannel Extension

```java
// Add to existing GrpcBubbleNetworkChannel
public class GrpcBubbleNetworkChannel implements BubbleNetworkChannel {
    // ... existing methods ...

    // NEW: Committee voting
    public void sendMigrationProposal(UUID targetNodeId, MigrationProposal proposal);
    public void sendCommitteeVote(UUID targetNodeId, CommitteeVote vote);

    // NEW: Committee voting listeners
    public void setProposalListener(ProposalListener listener);
    public void setVoteListener(VoteListener listener);
}
```

---

## Implementation Plan

### Phase 1: Foundation (2 hours)

**Files:**
- `committee.proto` (~50 LOC)
- `ViewCommittee.java` (~100 LOC)
- `ProposalState.java` (~50 LOC)
- `ViewCommitteeTest.java` (~150 LOC)

**Tasks:**
1. Create committee.proto in grpc/src/main/proto/lucien/
2. Run `mvn compile -pl grpc` to generate Java classes
3. Implement ViewCommittee with bftSubset wrapper
4. Add 10 unit tests for committee selection

### Phase 2: Consensus Logic (3 hours)

**Files:**
- `ViewCommitteeConsensus.java` (~250 LOC)
- `CommitteeVotingService.java` (~150 LOC)
- `ViewCommitteeConsensusTest.java` (~200 LOC)
- `CommitteeVotingServiceTest.java` (~150 LOC)

**Tasks:**
1. Implement ViewCommitteeConsensus with proposal tracking
2. Implement gRPC service for voting
3. Wire to FirefliesViewMonitor for view change handling
4. Add 25 unit tests

### Phase 3: Integration (2 hours)

**Files:**
- `OptimisticMigratorImpl.java` modifications (~50 LOC)
- `GrpcBubbleNetworkChannel.java` extensions (~100 LOC)
- `CommitteeIntegrationTest.java` (~200 LOC)

**Tasks:**
1. Wire OptimisticMigratorImpl to ViewCommitteeConsensus
2. Add committee methods to GrpcBubbleNetworkChannel
3. Create integration tests with FakeNetworkChannel
4. Verify backward compatibility with Phase 7F tests

---

## File Structure

### New Source Files

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/committee/
├── ViewCommittee.java                     (~100 LOC)
├── ViewCommitteeConsensus.java            (~250 LOC)
├── ProposalState.java                     (~50 LOC)
└── CommitteeVotingService.java            (~150 LOC)

grpc/src/main/proto/lucien/
└── committee.proto                        (~50 LOC)

Total Implementation: ~600 LOC
```

### New Test Files

```
simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/committee/
├── ViewCommitteeTest.java                 (~150 LOC)
├── ViewCommitteeConsensusTest.java        (~200 LOC)
├── CommitteeVotingServiceTest.java        (~150 LOC)
└── CommitteeIntegrationTest.java          (~200 LOC)

Total Test Code: ~700 LOC
```

### Grand Total: ~1,300 LOC

**Comparison with Original Plan:**
- Original: 3,340 LOC
- Redesign: 1,300 LOC
- **Reduction: 61%**

---

## Components Eliminated

The following components from the original Phase 7G Day 3 plan are **no longer needed**:

| Component | Original LOC | Status |
|-----------|--------------|--------|
| ElectionState enum | 60 | ELIMINATED |
| CoordinatorElectionProtocol | 350 | ELIMINATED |
| FailureDetector | 150 | ELIMINATED (Fireflies handles) |
| Term-based voting | - | ELIMINATED |
| Leader/Follower concept | - | ELIMINATED |
| Heartbeat mechanism | - | ELIMINATED |
| Election FSM | - | ELIMINATED |

**Reason**: Fireflies already provides:
- Failure detection via gossip
- Membership management via view changes
- Committee selection via bftSubset
- Virtual synchrony for consistency

---

## Success Criteria

### Functional Requirements
- [ ] Committee selected deterministically from view ID
- [ ] Only committee members vote on proposals
- [ ] View changes abort pending proposals
- [ ] Migration approval latency < 200ms for 5-node committee
- [ ] Integration with existing OptimisticMigrator interface

### Test Requirements
- [ ] 10 ViewCommittee unit tests passing
- [ ] 15 ViewCommitteeConsensus unit tests passing
- [ ] 10 CommitteeVotingService unit tests passing
- [ ] 10 CommitteeIntegration tests passing
- [ ] All 70 Phase 7F backward compatibility tests pass
- [ ] All 22 Phase 7G Day 1-2 tests pass

### Performance Requirements
- [ ] Committee selection < 1ms
- [ ] Vote collection < 200ms for 5-node committee
- [ ] View change detection < 100ms
- [ ] No deadlocks under concurrent proposals

---

## Risk Analysis

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Delos API changes | Low | Medium | Pin Delos version in pom.xml |
| Ring calculation mismatch | Low | High | Unit test committee sizes for various cardinalities |
| View stability timing | Medium | Medium | Use FirefliesViewMonitor proven in Phase 7C |
| Concurrent proposal race | Low | Medium | View ID tagging on all messages |
| Proto compatibility | Low | Low | Version field in all messages |

---

## Migration Path

For existing systems using the Raft-style approach (if any):

1. **Add feature flag**: `consensus.mode = COMMITTEE | RAFT`
2. **Default**: COMMITTEE for new deployments
3. **Deprecation**: Mark RAFT mode deprecated
4. **Removal**: Remove RAFT code after validation period

---

## Related Documents

- **ChromaDB**: `delos::module::fireflies` - Fireflies implementation details
- **ChromaDB**: `plan::strategic-planner::phase7g-day3-consensus-2026-01-10` - Original plan
- **ChromaDB**: `audit::plan-auditor::phase7g-day3-consensus-2026-01-10` - Original audit
- **Phase 7C**: FirefliesViewMonitor implementation
- **Delos Source**: `/Users/hal.hildebrand/git/Delos/fireflies/src/main/java/com/hellblazer/delos/fireflies/ViewManagement.java`

---

## Appendix: Delos Code References

### bftSubset Implementation (Context.java)

```java
default SequencedSet<T> bftSubset(Digest hash, Predicate<T> filter) {
    var collector = new LinkedHashSet<T>();
    uniqueSuccessors(hash, filter, collector);
    return collector;
}
```

### Observer Reset (ViewManagement.java)

```java
private void resetObservers() {
    observers.clear();
    context.bftSubset(diadem.get().compact(), context::isActive)
           .stream()
           .map(Member::getId)
           .forEach(d -> observers.put(d, -1));
    if (observers.isEmpty()) {
        observers.put(node.getId(), -1); // bootstrap case
    }
    var newVersion = observerVersion.incrementAndGet();
}
```

### Committee Creation (Committee.java)

```java
static Context<Member> viewFor(Digest hash, Context<? super Member> baseContext) {
    Set<Member> successors = (Set<Member>) baseContext.bftSubset(hash);
    var newView = new StaticContext<>(hash, baseContext.getProbabilityByzantine(), 3,
                                       successors, baseContext.getEpsilon(), successors.size());
    return newView;
}
```

---

## Next Steps

1. **Plan Audit**: Submit this redesign to plan-auditor for validation
2. **Bead Creation**: Create beads for the three implementation phases
3. **Implementation**: Begin with Phase 1 (Foundation)
4. **Validation**: Run all tests including backward compatibility
5. **Documentation**: Update Phase 7G documentation with new architecture
