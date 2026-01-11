# Deleted Code History

**Last Updated**: 2026-01-10
**Reason**: Phase 7G Day 5 - Complete deletion of Raft-style consensus code

## Summary

The old Raft-style consensus implementation was completely removed in favor of the Fireflies View Committee-based consensus approach. This document archives the deleted code for historical reference.

**User Decision**: "Don't want to deprecate. Just want it gone :("

## Replacement Architecture

The old Raft-style leader election has been replaced by:
- **ViewCommitteeSelector**: Deterministic BFT committee selection from Fireflies view
- **ViewCommitteeConsensus**: Committee-based voting without leader election
- **CommitteeBallotBox**: Vote aggregation with KerlDHT quorum formula
- **CommitteeVotingProtocol**: Proposal lifecycle management

**Key Benefits**:
1. No leader election required (committee is computed, not elected)
2. 85% code reduction
3. Inherits BFT tolerance from Delos Fireflies
4. View-based membership management (no separate failure detection)

---

## Deleted Main Source Files

### 1. ElectionState.java (79 LOC)
**Path**: simulation/src/main/java/.../consensus/ElectionState.java
**Purpose**: Three-state FSM enum for Raft leader election

```java
package com.hellblazer.luciferase.simulation.consensus;

/**
 * Three-state model for coordinator election protocol.
 *
 * State transitions:
 * - FOLLOWER -> CANDIDATE: Election timeout triggered
 * - CANDIDATE -> LEADER: Won majority votes
 * - CANDIDATE -> FOLLOWER: Received AppendHeartbeat from valid leader
 * - LEADER -> FOLLOWER: Received AppendHeartbeat from higher term
 */
public enum ElectionState {
    FOLLOWER("follower"),
    CANDIDATE("candidate"),
    LEADER("leader");

    // ... (full implementation archived)
}
```

### 2. ConsensusElectionProtocol.java (395 LOC)
**Path**: simulation/src/main/java/.../consensus/ConsensusElectionProtocol.java
**Purpose**: Term-based Raft election protocol with vote requests and heartbeats

**Key Components**:
- PeerCommunicator interface for broadcasting
- Term-based voting logic
- Election timeout handling
- Leader heartbeat broadcasting
- Step-down logic for higher terms

### 3. BallotBox.java (262 LOC)
**Path**: simulation/src/main/java/.../consensus/BallotBox.java
**Purpose**: Vote tracking for consensus decisions

**Key Components**:
- Proposal registration
- Yes/No vote recording
- Quorum-based decision making
- DecisionState enum (PENDING, APPROVED, REJECTED)
- VoteCounts record class

### 4. FailureDetector.java (257 LOC)
**Path**: simulation/src/main/java/.../consensus/FailureDetector.java
**Purpose**: Heartbeat-based failure detection

**Key Components**:
- Heartbeat recording and monitoring
- NodeStatus enum (ALIVE, SUSPECTED, DEAD)
- Consecutive failure tracking
- Background monitoring thread

### 5. ConsensusCoordinator.java (315 LOC)
**Path**: simulation/src/main/java/.../consensus/ConsensusCoordinator.java
**Purpose**: Orchestrator for election protocol and gRPC service

**Key Components**:
- Lifecycle management (start/stop)
- Entity ownership proposals
- Leader status checking
- gRPC server management
- Metrics (election count, proposal count)

### 6. GrpcCoordinatorService.java (242 LOC)
**Path**: simulation/src/main/java/.../consensus/GrpcCoordinatorService.java
**Purpose**: gRPC service implementation for consensus

**Key Components**:
- requestVote() RPC handler
- appendHeartbeat() RPC handler
- proposeBallot() ballot voting
- getLeader() status query

---

## Deleted Test Files

### Unit Tests
- ElectionStateTest.java (105 LOC) - Enum parsing tests
- ConsensusElectionProtocolTest.java (450 LOC) - FSM transition tests
- BallotBoxTest.java (340 LOC) - Vote tracking tests
- FailureDetectorTest.java (285 LOC) - Heartbeat tests
- ConsensusCoordinatorTest.java (320 LOC) - Orchestrator tests
- CoordinatorElectionServiceTest.java (350 LOC) - gRPC tests
- GrpcCoordinatorServiceTest.java (520 LOC) - Service tests

### Integration Tests
- TwoNodeConsensusTest.java (180 LOC) - 2-node cluster tests
- ThreeNodeConsensusTest.java (215 LOC) - 3-node cluster tests
- QuorumValidationTest.java (195 LOC) - Quorum calculation tests
- FailureRecoveryTest.java (260 LOC) - Failure handling tests
- BackwardCompatibilityVerificationTest.java (235 LOC) - Compatibility tests

### Support Files
- TestPeerCommunicator.java (105 LOC) - Test peer communication

---

## Total Code Deleted

| Category | Files | LOC |
|----------|-------|-----|
| Main Source | 6 | ~1,550 |
| Unit Tests | 7 | ~2,370 |
| Integration Tests | 5 | ~1,085 |
| Support Files | 1 | ~105 |
| **Total** | **19** | **~5,110** |

---

## Proto File Status

The `grpc/src/main/proto/lucien/coordinator.proto` file was retained as generated classes may still be referenced. The proto defines:
- VoteRequest/VoteResponse
- HeartbeatRequest/HeartbeatResponse
- BallotProposal/BallotVote
- GetLeaderRequest/GetLeaderResponse
- LogEntry
- CoordinatorElectionService gRPC service

If no remaining references exist, this proto file can also be deleted.

---

## Migration Notes

For systems that previously used the Raft-style consensus:

1. Replace `ConsensusCoordinator` with `ViewCommitteeConsensus`
2. Replace `ElectionState` checks with view-based committee membership
3. Remove all heartbeat-based failure detection (Fireflies handles this)
4. Use `ViewCommitteeSelector.selectCommittee(viewId)` for committee selection
5. Use `MigrationProposal` records instead of ballot proposals

---

## References

- Phase 7G Day 3 Redesign: `simulation/doc/PHASE_7G_DAY3_COMMITTEE_CONSENSUS_REDESIGN.md`
- New Committee Implementation: `simulation/src/main/java/.../consensus/committee/`
- Integration Tests: `simulation/src/test/java/.../consensus/committee/integration/`
