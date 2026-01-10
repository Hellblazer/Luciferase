# Phase 7F Day 4: Three-Node Consensus Mechanism Plan

**Date**: 2026-01-10
**Status**: PLANNED
**Phase**: 7F - Distributed Testing of Entity Migration
**Day**: 4 of 4

---

## Executive Summary

Day 4 implements a distributed three-node consensus mechanism for entity migration decisions. The protocol uses majority voting (2+ out of 3 nodes must agree) to authorize entity ownership transfers, handling conflicting requests, network latency, and timeout scenarios.

---

## Context from Days 1-3

### Existing Infrastructure
| Component | Description | Location |
|-----------|-------------|----------|
| DistributedBubbleNode | Wraps EnhancedBubble with network communication | `distributed/network/` |
| BubbleNetworkChannel | Interface for inter-node messaging | `distributed/network/` |
| FakeNetworkChannel | Simulates latency/packet loss for testing | `distributed/network/` |
| EntityMigrationStateMachine | 6-state FSM for entity ownership | `causality/` |
| TwoNodeDistributedMigrationTest | Day 3 two-node migration tests | `distributed/network/` |

### Existing Message Types
- `EntityDepartureEvent` - Entity leaving source bubble
- `ViewSynchronyAck` - Target acknowledges receipt
- `EntityRollbackEvent` - Rollback due to view change

---

## Design Decisions

### Question 1: Centralized vs Distributed Voting?
**Decision**: **Distributed Voting**

**Rationale**:
- Matches existing peer-to-peer BubbleNetworkChannel pattern
- No single point of failure
- No leader election complexity
- Each node has local ConsensusCoordinator that can propose and vote

### Question 2: Reuse or Create New Components?
**Decision**: **Create new consensus package**

**Rationale**:
- Clean separation of concerns
- Consensus logic is distinct from migration mechanics
- New message types needed for voting
- Extend FakeNetworkChannel for vote message delivery

### Question 3: Vote Timeout Handling?
**Decision**: **500ms timeout with partial vote decision**

**Rationale**:
- Fast enough for interactive scenarios
- Slow enough to handle 100ms network latency
- If < 2 APPROVE votes when timeout: reject migration
- Configurable via ConsensusConfiguration

### Question 4: Persistence/Logging?
**Decision**: **Log all decisions, in-memory state only**

**Rationale**:
- SLF4J logging for audit trail
- ConcurrentHashMap for pending votes
- No persistent storage needed for testing phase
- Clear state after decision or timeout

---

## Architecture

### New Package Structure
```
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/consensus/
├── ConsensusCoordinator.java         # Core voting logic
├── ConsensusConfiguration.java       # Timeout/threshold configuration
├── VoteType.java                     # APPROVE/REJECT/ABSTAIN enum
├── VoteContext.java                  # Pending vote state tracking
├── VoteDecision.java                 # Final decision result
├── MigrationVoteRequest.java         # Vote request message
├── MigrationVoteResponse.java        # Vote response message
└── MigrationVoteDecision.java        # Decision broadcast message
```

### Component Diagram
```
┌─────────────────────────────────────────────────────────────────────┐
│                     Three-Node Consensus Flow                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   Node A (Proposer)         Node B               Node C              │
│   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐       │
│   │ Consensus     │    │ Consensus     │    │ Consensus     │       │
│   │ Coordinator   │    │ Coordinator   │    │ Coordinator   │       │
│   └───────┬───────┘    └───────┬───────┘    └───────┬───────┘       │
│           │                     │                     │              │
│           │  VoteRequest        │  VoteRequest        │              │
│           ├────────────────────>│                     │              │
│           ├─────────────────────────────────────────>│              │
│           │                     │                     │              │
│           │  VoteResponse       │  VoteResponse       │              │
│           │<────────────────────┤                     │              │
│           │<─────────────────────────────────────────┤              │
│           │                     │                     │              │
│           │  (Tally: 3/3 APPROVE)                     │              │
│           │                     │                     │              │
│           │  VoteDecision (COMMIT)                    │              │
│           ├────────────────────>│                     │              │
│           ├─────────────────────────────────────────>│              │
│           │                     │                     │              │
│           │  EntityDepartureEvent (existing flow)     │              │
│           └────────────────────>│                     │              │
│                                 │                     │              │
└─────────────────────────────────────────────────────────────────────┘
```

### Voting Protocol
```
1. PROPOSE: Source node sends MigrationVoteRequest to ALL nodes (including self)
2. VOTE: Each node evaluates and responds with VoteResponse
   - APPROVE: Entity exists, source claims ownership correctly
   - REJECT: Entity unknown, source not owner, or migration invalid
   - ABSTAIN: Node cannot determine (e.g., network partition recovery)
3. TALLY: Proposer collects votes within timeout
   - COMMIT: 2+ APPROVE votes
   - REJECT: < 2 APPROVE votes OR timeout with insufficient votes
4. DECIDE: Proposer broadcasts MigrationVoteDecision
5. EXECUTE: If COMMIT, proceed with existing EntityDepartureEvent flow
```

---

## Component Specifications

### 1. VoteType Enum
```java
public enum VoteType {
    APPROVE,  // Node agrees with migration
    REJECT,   // Node disagrees (conflict, invalid state)
    ABSTAIN   // Node cannot determine (partition recovery)
}
```

### 2. VoteContext Record
```java
public record VoteContext(
    UUID entityId,
    UUID sourceBubble,
    UUID targetBubble,
    long proposalTimeMs,
    Map<UUID, VoteType> votes,
    int requiredVotes,
    long timeoutMs
) {
    public boolean hasReachedQuorum() { ... }
    public boolean isTimedOut(long currentTimeMs) { ... }
    public VoteDecision makeDecision() { ... }
}
```

### 3. ConsensusCoordinator
```java
public class ConsensusCoordinator {
    private final UUID nodeId;
    private final Set<UUID> knownNodes;
    private final ConsensusConfiguration config;
    private final ConcurrentHashMap<UUID, VoteContext> pendingVotes;

    // Propose migration (called by entity owner)
    public VoteContext proposeEntityMigration(UUID entityId, UUID targetBubble);

    // Record vote from remote node
    public void recordVote(UUID entityId, UUID voterId, VoteType vote);

    // Check if vote is complete (quorum or timeout)
    public Optional<VoteDecision> checkVoteComplete(UUID entityId);

    // Handle vote timeout
    public VoteDecision handleVoteTimeout(UUID entityId);

    // Evaluate vote request (called when receiving VoteRequest)
    public VoteType evaluateVoteRequest(MigrationVoteRequest request);
}
```

### 4. ConsensusConfiguration
```java
public record ConsensusConfiguration(
    int nodeCount,           // 3 for this scenario
    int requiredVotes,       // 2 (majority)
    long voteTimeoutMs,      // 500ms default
    int maxRetries,          // 3 retries on network failure
    boolean allowAbstain     // true to count ABSTAIN as non-blocking
) {
    public static ConsensusConfiguration defaultThreeNode() {
        return new ConsensusConfiguration(3, 2, 500, 3, true);
    }
}
```

### 5. Message Types

**MigrationVoteRequest**:
```java
public record MigrationVoteRequest(
    UUID requestId,
    UUID entityId,
    UUID sourceBubble,
    UUID targetBubble,
    long proposalTimeMs,
    int sequenceNumber    // For ordering conflicting requests
) {}
```

**MigrationVoteResponse**:
```java
public record MigrationVoteResponse(
    UUID requestId,
    UUID entityId,
    UUID voterId,
    VoteType vote,
    String reason         // Optional rejection reason
) {}
```

**MigrationVoteDecision**:
```java
public record MigrationVoteDecision(
    UUID requestId,
    UUID entityId,
    boolean approved,
    int approveCount,
    int rejectCount,
    int abstainCount
) {}
```

---

## Test Scenarios

### ThreeNodeConsensusTest.java

| Test | Scenario | Expected Outcome |
|------|----------|------------------|
| 1 | **Unanimous Agreement** | All 3 vote APPROVE -> migration proceeds |
| 2 | **Split Vote (2-1 Majority)** | 2 APPROVE, 1 REJECT -> migration proceeds |
| 3 | **Split Vote (Rejection)** | 1 APPROVE, 2 REJECT -> migration rejected |
| 4 | **Conflicting Simultaneous Proposals** | Two proposals for same entity -> earlier timestamp wins |
| 5 | **Vote Timeout** | Node C fails to respond -> decision with 2 votes |
| 6 | **Network Latency (100ms)** | Votes still collected within timeout |
| 7 | **Packet Loss (30%)** | Retry mechanism ensures delivery |
| 8 | **Cascading Migrations** | A->B then B->C, each with consensus |

### Test Implementation Details

**Test 1: Unanimous Agreement**
```java
@Test
@DisplayName("Unanimous agreement: all 3 nodes vote APPROVE")
void testUnanimousAgreement() {
    // Setup: 3 nodes, entity owned by Node A
    // Action: Node A proposes migration to Node B
    // Assert: All 3 vote APPROVE, decision is COMMIT
    // Assert: EntityDepartureEvent sent to Node B
    // Assert: Consensus time < 50ms
}
```

**Test 4: Conflicting Proposals**
```java
@Test
@DisplayName("Conflicting proposals: earlier timestamp wins")
void testConflictingProposals() {
    // Setup: Entity owned by Node A
    // Action: Node A proposes A->B at T=100ms
    //         Node C proposes A->C at T=105ms (conflicting)
    // Assert: Node A's proposal wins (earlier timestamp)
    // Assert: Node C's proposal rejected with reason "conflict"
}
```

**Test 5: Vote Timeout**
```java
@Test
@DisplayName("Vote timeout: decision with partial votes")
void testVoteTimeout() {
    // Setup: 3 nodes, Node C is unresponsive
    // Action: Node A proposes, only A and B respond
    // Assert: After 500ms timeout, decision made with 2 votes
    // Assert: If 2 APPROVE -> proceed; if 1 APPROVE -> reject
}
```

---

## Implementation Phases

### Phase A: Consensus Data Structures (Morning, ~2 hours)

**Deliverables**:
- VoteType.java (10 LOC)
- VoteContext.java (80 LOC)
- VoteDecision.java (40 LOC)
- MigrationVoteRequest.java (50 LOC)
- MigrationVoteResponse.java (50 LOC)
- MigrationVoteDecision.java (40 LOC)
- ConsensusConfiguration.java (60 LOC)

**Tests** (~120 LOC):
- VoteContextTest.java: quorum detection, timeout, decision making
- VoteTypeTest.java: enum values

**Validation**: `mvn test -pl simulation -Dtest=VoteContextTest`

### Phase B: ConsensusCoordinator Core (Mid-morning, ~2 hours)

**Deliverables**:
- ConsensusCoordinator.java (250 LOC)
  - proposeEntityMigration()
  - recordVote()
  - checkVoteComplete()
  - handleVoteTimeout()
  - evaluateVoteRequest()

**Tests** (~200 LOC):
- ConsensusCoordinatorTest.java: all core methods

**Validation**: `mvn test -pl simulation -Dtest=ConsensusCoordinatorTest`

### Phase C: Network Integration (Early afternoon, ~2 hours)

**Deliverables**:
- Extend BubbleNetworkChannel interface with vote methods
- Extend FakeNetworkChannel with vote message handling
- Wire ConsensusCoordinator into DistributedBubbleNode
- ThreeNodeDistributedBubbleNode helper class (optional)

**Tests** (~150 LOC):
- FakeNetworkChannelVoteTest.java: vote message delivery

**Validation**: `mvn test -pl simulation -Dtest=*NetworkChannel*`

### Phase D: Integration Tests (Late afternoon, ~3 hours)

**Deliverables**:
- ThreeNodeConsensusTest.java (400 LOC)
  - All 8 test scenarios
  - Performance validation
  - Network condition testing

**Validation**:
```bash
mvn test -pl simulation -Dtest=ThreeNodeConsensusTest
```

---

## Success Criteria

### Functional Requirements
- [ ] All 8 test scenarios pass
- [ ] Unanimous agreement: < 50ms decision time
- [ ] Split vote: Correct majority handling (2/3 required)
- [ ] Timeout: Proper rejection after 500ms
- [ ] Conflicting proposals: Deterministic winner selection (earlier timestamp)
- [ ] No entity duplication during consensus
- [ ] No entity loss during consensus

### Performance Requirements
- [ ] Consensus without latency: < 50ms
- [ ] Consensus with 100ms latency: < 400ms (2x RTT + processing)
- [ ] 30% packet loss: Consensus completes within 3 retries

### Code Quality
- [ ] All new code compiles without warnings
- [ ] Unit test coverage > 80%
- [ ] Integration tests pass on CI

---

## Build and Test Commands

```bash
# Compile all simulation code
mvn compile -pl simulation

# Run Phase A tests (data structures)
mvn test -pl simulation -Dtest=VoteContextTest,VoteTypeTest

# Run Phase B tests (coordinator)
mvn test -pl simulation -Dtest=ConsensusCoordinatorTest

# Run Phase C tests (network integration)
mvn test -pl simulation -Dtest="*NetworkChannel*Vote*"

# Run Phase D tests (integration)
mvn test -pl simulation -Dtest=ThreeNodeConsensusTest

# Run all Day 4 tests
mvn test -pl simulation -Dtest="*Consensus*"

# Full regression (ensure no breakage)
mvn test -pl simulation
```

---

## Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Complex conflict resolution | Medium | Medium | Simplify to "earlier timestamp wins" rule |
| Network integration slow | Low | Medium | Mock channel for initial testing |
| Vote timeout too aggressive | Medium | Low | Start with 500ms, adjust based on tests |
| Deadlock on conflicting proposals | Low | High | Add proposal sequence number for ordering |
| Memory leak from pending votes | Low | Low | Clear votes after decision or timeout |

---

## LOC Estimates

| Component | Implementation | Tests | Total |
|-----------|----------------|-------|-------|
| Phase A: Data Structures | 330 | 120 | 450 |
| Phase B: ConsensusCoordinator | 250 | 200 | 450 |
| Phase C: Network Integration | 200 | 150 | 350 |
| Phase D: Integration Tests | 50 | 400 | 450 |
| **TOTAL** | **830** | **870** | **~1,700** |

---

## Dependencies

### Blocking Dependencies (Must exist)
- DistributedBubbleNode (Day 1) - COMPLETE
- FakeNetworkChannel (Day 1) - COMPLETE
- TwoNodeDistributedMigrationTest (Day 3) - COMPLETE

### Non-Blocking Dependencies
- EntityMigrationStateMachine (Phase 7C) - COMPLETE
- OptimisticMigrator (Phase 7E) - COMPLETE

---

## References

### Existing Files
- `/simulation/src/main/java/.../distributed/network/DistributedBubbleNode.java`
- `/simulation/src/main/java/.../distributed/network/FakeNetworkChannel.java`
- `/simulation/src/main/java/.../distributed/network/BubbleNetworkChannel.java`
- `/simulation/src/main/java/.../causality/EntityMigrationStateMachine.java`

### Related Documentation
- `PHASE_7E_VALIDATION_REPORT.md` - Entity migration validation
- `PHASE_7D2_FINAL_STATUS_COMPLETE.md` - Ghost physics integration
- `PHASE_6E_INTEGRATION_VALIDATION.md` - Distributed testing patterns

### ChromaDB References
- `decision::migration::phase-6b4-2pc-protocol` - Two-phase commit patterns
- `plan::strategic-planner::inc6-distributed-multi-bubble-2026-01-08` - Distributed architecture

---

## Conclusion

This plan delivers a three-node consensus mechanism that:
1. Enables majority voting (2/3) for entity migration authorization
2. Handles network latency and packet loss gracefully
3. Resolves conflicting migration proposals deterministically
4. Integrates cleanly with existing DistributedBubbleNode infrastructure
5. Provides comprehensive test coverage for all scenarios

**Estimated Implementation Time**: 8-9 hours (single developer)
**Estimated Test Count**: ~20 new tests (8 integration + 12 unit)
**Estimated Total LOC**: ~1,700

---

**Plan Status**: Ready for Implementation
**Next Action**: Begin Phase A (Consensus Data Structures)
