# Phase 9: Dynamic Topology Adaptation - Strategic Plan

**Date**: 2026-01-10
**Status**: Ready for Plan Audit
**Epic**: Luciferase-8oe (Distributed Animation)
**Dependency**: Phase 8 (Static MVP) - HARD BLOCKER
**Duration**: 4-5 days
**LOC Estimate**: ~1,700 new LOC + ~600 test LOC
**Test Estimate**: 30-35 new tests

---

## Executive Summary

Phase 9 transforms Phase 8's static 4-bubble topology into a dynamic topology that adapts to entity distribution. Bubbles split when overcrowded and merge when underpopulated, with all topology changes coordinated through committee consensus.

**Critical Insight**: Most split/merge algorithms already exist in the codebase. Phase 9 is primarily CONSENSUS ORCHESTRATION around existing operations.

---

## Research Findings: Existing Infrastructure

### Code Reuse Inventory

| Component | File | LOC | Status | Phase 9 Usage |
|-----------|------|-----|--------|---------------|
| AdaptiveSplitPolicy | `bubble/AdaptiveSplitPolicy.java` | 390 | Complete | Wrap - split detection |
| BubbleLifecycle | `bubble/BubbleLifecycle.java` | 133 | Complete | Wrap - merge detection |
| BubbleDynamicsManager | `bubble/BubbleDynamicsManager.java` | 503 | Complete | Extend - orchestration |
| ViewCommitteeConsensus | `consensus/committee/ViewCommitteeConsensus.java` | 258 | Complete | Extend - topology voting |
| CommitteeVotingProtocol | `consensus/committee/CommitteeVotingProtocol.java` | 207 | Complete | Reuse - voting FSM |
| MigrationProposal | `consensus/committee/MigrationProposal.java` | 49 | Complete | Template for topology proposals |
| BubbleGrid | `distributed/grid/BubbleGrid.java` | 161 | Complete | Extend - dynamic sizing |
| FlockingBehavior | `behavior/FlockingBehavior.java` | 419 | Complete | Reuse - entity movement |

**Total Existing Code**: ~2,120 LOC available for reuse

### Key Existing Algorithms

**Split Detection** (AdaptiveSplitPolicy):
```java
public boolean shouldSplit(EnhancedBubble bubble) {
    return bubble.frameUtilization() > splitThreshold; // Default 1.2 = 120%
}

public SplitResult analyzeSplit(EnhancedBubble bubble) {
    // K-means clustering for entity distribution
    var clusters = detectClusters(bubble, minEntitiesPerBubble, maxDistance);
    // Returns feasible=true with cluster assignments
}
```

**Merge Detection** (BubbleLifecycle):
```java
public boolean shouldJoin(EnhancedBubble b1, EnhancedBubble b2, float affinity) {
    return affinity > MERGE_THRESHOLD; // Default 0.6 = 60%
}

public EnhancedBubble performJoin(EnhancedBubble b1, EnhancedBubble b2) {
    // Creates merged bubble, transfers entities, updates VON neighbors
}
```

**Entity Distribution** (BubbleDynamicsManager):
```java
public List<UUID> splitBubble(UUID source, List<Set<ID>> componentSets, long bucket) {
    // Source becomes first component
    // New bubbles created for remaining components
    // Entities reassigned
    // Split event emitted
}

public void mergeBubbles(UUID bubble1, UUID bubble2, long bucket) {
    // Smaller absorbed into larger
    // Entities transferred
    // Smaller removed
    // Merge event emitted
}
```

---

## Phase 9 Architecture

### Component Overview

```
Phase 8 Components (Reused):
├── ConsensusBubbleGrid (4 static bubbles)
├── ViewCommitteeConsensus (migration voting)
├── CommitteeVotingProtocol (voting FSM)
├── FlockingBehavior (entity movement)
└── Phase 7G Tests (100+ tests)

Phase 9 Additions:
├── TopologyDetection/
│   ├── DensityMonitor           (wraps AdaptiveSplitPolicy)
│   ├── MergeDetector            (wraps BubbleLifecycle)
│   └── TopologyDetectionLoop    (periodic detection)
├── TopologyProposals/
│   ├── TopologySplitProposal    (record)
│   ├── TopologyMergeProposal    (record)
│   └── TopologyProposalFactory  (creates proposals)
├── TopologyConsensus/
│   └── TopologyConsensusCoordinator  (extends ViewCommitteeConsensus)
├── TopologyExecution/
│   ├── TopologyReorganizer      (executes topology changes)
│   ├── EntityRedistributor      (moves entities on split/merge)
│   └── TopologyValidator        (consistency verification)
└── DynamicBubbleGrid            (extends ConsensusBubbleGrid)
```

### New Classes Specification

#### 1. TopologySplitProposal (Record)
```java
public record TopologySplitProposal(
    UUID proposalId,           // Unique proposal ID
    UUID bubbleId,             // Bubble to split
    int targetClusterCount,    // How many bubbles to create
    Digest viewId,             // View context
    long timestamp,            // Proposal time
    float densityRatio,        // Current density ratio (for audit)
    List<Point3f> clusterCentroids  // Proposed cluster centers
) {}
```

#### 2. TopologyMergeProposal (Record)
```java
public record TopologyMergeProposal(
    UUID proposalId,           // Unique proposal ID
    UUID bubble1Id,            // First bubble
    UUID bubble2Id,            // Second bubble
    UUID targetBubbleId,       // Which bubble survives
    Digest viewId,             // View context
    long timestamp,            // Proposal time
    float affinity,            // Cross-bubble affinity
    int combinedEntityCount    // Total entities after merge
) {}
```

#### 3. TopologyConsensusCoordinator
```java
public class TopologyConsensusCoordinator {
    private final ViewCommitteeConsensus delegateConsensus;
    private final CommitteeVotingProtocol votingProtocol;
    private final TopologyReorganizer reorganizer;
    private final TopologyValidator validator;
    private final Duration cooldownPeriod = Duration.ofSeconds(30);
    private volatile Instant lastTopologyChange = Instant.EPOCH;

    // Request consensus for split
    public CompletableFuture<Boolean> requestSplitConsensus(TopologySplitProposal proposal);

    // Request consensus for merge
    public CompletableFuture<Boolean> requestMergeConsensus(TopologyMergeProposal proposal);

    // Check if topology change is allowed (cooldown)
    public boolean canProposeTopologyChange();

    // Handle view change (abort pending topology proposals)
    public void onViewChange(Digest newViewId);
}
```

#### 4. DensityMonitor
```java
public class DensityMonitor {
    private final AdaptiveSplitPolicy splitPolicy;
    private final float densityThreshold;

    // Check if any bubble needs splitting
    public Optional<TopologySplitProposal> detectSplitCandidate(
        Collection<EnhancedBubble> bubbles, Digest viewId);

    // Get density ratio for a bubble
    public float getDensityRatio(EnhancedBubble bubble);
}
```

#### 5. MergeDetector
```java
public class MergeDetector {
    private final BubbleLifecycle lifecycle;
    private final float affinityThreshold;
    private final int minEntityThreshold;  // Don't merge if combined > threshold

    // Check if any bubble pair should merge
    public Optional<TopologyMergeProposal> detectMergeCandidate(
        Collection<EnhancedBubble> bubbles, Digest viewId);

    // Get affinity between two bubbles
    public float calculateAffinity(EnhancedBubble b1, EnhancedBubble b2);
}
```

#### 6. TopologyReorganizer
```java
public class TopologyReorganizer {
    private final BubbleDynamicsManager dynamicsManager;
    private final EntityRedistributor redistributor;

    // Execute approved split
    public List<EnhancedBubble> executeSplit(TopologySplitProposal proposal,
                                               EnhancedBubble sourceBubble);

    // Execute approved merge
    public EnhancedBubble executeMerge(TopologyMergeProposal proposal,
                                        EnhancedBubble b1, EnhancedBubble b2);

    // Rollback failed topology change
    public void rollback(UUID proposalId);
}
```

#### 7. DynamicBubbleGrid
```java
public class DynamicBubbleGrid extends ConsensusBubbleGrid {
    private final TopologyConsensusCoordinator topologyCoordinator;
    private final DensityMonitor densityMonitor;
    private final MergeDetector mergeDetector;

    // Check topology and propose changes if needed
    public void evaluateTopology();

    // Handle approved split
    public void onSplitApproved(TopologySplitProposal proposal);

    // Handle approved merge
    public void onMergeApproved(TopologyMergeProposal proposal);

    // Get current bubble count
    public int getBubbleCount();

    // Get topology change history
    public List<TopologyChangeEvent> getTopologyHistory();
}
```

#### 8. TopologyValidator
```java
public class TopologyValidator {
    // Validate all nodes agree on topology
    public boolean validateTopologyConsistency(Collection<BubbleNode> nodes);

    // Validate entity counts after topology change
    public boolean validateEntityConservation(
        int expectedCount, Collection<EnhancedBubble> bubbles);

    // Validate no orphaned entities
    public boolean validateNoOrphans(Collection<EnhancedBubble> bubbles);
}
```

---

## Phase Breakdown

### Phase 9A: Topology Detection (1 day)

**Goal**: Wrap existing split/merge detection in monitoring components

**Deliverables**:
- `DensityMonitor.java` (~100 LOC) - Wraps AdaptiveSplitPolicy
- `MergeDetector.java` (~100 LOC) - Wraps BubbleLifecycle
- `TopologyDetectionLoop.java` (~80 LOC) - Periodic detection

**Tests** (8 tests):
- DensityMonitor detects overcrowded bubble
- DensityMonitor respects threshold
- MergeDetector detects high-affinity pair
- MergeDetector respects minimum entity count
- DetectionLoop runs at correct interval
- DetectionLoop respects cooldown
- No false positives on balanced topology
- Detection with ghost entities included

**Success Criteria**:
- Split detection matches AdaptiveSplitPolicy.shouldSplit()
- Merge detection matches BubbleLifecycle.shouldJoin()
- Cooldown prevents rapid topology changes

**Dependencies**: None (can start after Phase 8)

### Phase 9B: Consensus Coordination (1.5 days)

**Goal**: Add committee consensus for topology changes

**Deliverables**:
- `TopologySplitProposal.java` (~50 LOC) - Split proposal record
- `TopologyMergeProposal.java` (~50 LOC) - Merge proposal record
- `TopologyProposalFactory.java` (~60 LOC) - Creates proposals
- `TopologyConsensusCoordinator.java` (~200 LOC) - Orchestrates voting

**Tests** (10 tests):
- TopologySplitProposal immutability
- TopologyMergeProposal immutability
- Coordinator submits to voting protocol
- Coordinator respects cooldown
- Coordinator handles view change
- Quorum approval triggers execution
- Quorum rejection prevents execution
- Byzantine vote ignored
- Timeout causes rejection
- Concurrent proposals handled

**Success Criteria**:
- Topology proposals follow MigrationProposal pattern
- Voting reuses CommitteeVotingProtocol
- View changes abort pending proposals
- Cooldown prevents oscillation

**Dependencies**: Phase 9A

### Phase 9C: Topology Execution (1.5 days)

**Goal**: Execute approved topology changes atomically

**Deliverables**:
- `TopologyReorganizer.java` (~200 LOC) - Executes changes
- `EntityRedistributor.java` (~150 LOC) - Moves entities
- `TopologyValidator.java` (~150 LOC) - Consistency checks
- `DynamicBubbleGrid.java` (~250 LOC) - Extends ConsensusBubbleGrid

**Tests** (10 tests):
- Split creates correct number of bubbles
- Split redistributes entities evenly
- Split maintains entity count (no loss)
- Merge combines entities correctly
- Merge removes smaller bubble
- Merge maintains entity count
- Validator detects entity loss
- Validator detects orphaned entities
- DynamicBubbleGrid grows on split
- DynamicBubbleGrid shrinks on merge

**Success Criteria**:
- 100% entity retention on split/merge
- No duplicate entities
- Grid size adjusts correctly
- Rollback restores previous state on failure

**Dependencies**: Phase 9B

### Phase 9D: Integration & Validation (1 day)

**Goal**: Validate full dynamic topology in realistic scenarios

**Deliverables**:
- `DynamicTopologyDemo.java` (~200 LOC) - Extended demo
- `DynamicDemoConfiguration.java` (~50 LOC) - Config
- `TopologyAdaptationMetrics.java` (~80 LOC) - Metrics collection

**Tests** (7 tests):
- 4 bubbles split to 6 under load
- 6 bubbles merge to 4 when underpopulated
- Topology stable with balanced load
- Byzantine node cannot force bad split
- Entity retention 100% through topology changes
- Ghost consistency after topology change
- Topology change latency < 2 seconds

**Success Criteria**:
- Demo shows topology adapting to entity clustering
- All Phase 8 tests still pass
- Combined 110+ tests passing
- Performance targets met

**Dependencies**: Phase 9C

---

## Integration with Phase 8

### Phase 8 Components Extended

| Phase 8 Component | Phase 9 Extension |
|-------------------|-------------------|
| ConsensusBubbleGrid | DynamicBubbleGrid extends it |
| ViewCommitteeConsensus | TopologyConsensusCoordinator wraps it |
| MigrationProposal | TopologySplitProposal/TopologyMergeProposal follow pattern |
| CommitteeVotingProtocol | Reused for topology voting |
| ConsensusMigrationDemo | Extended with topology changes |

### Backward Compatibility

- All Phase 8 tests MUST continue to pass
- ConsensusBubbleGrid API unchanged
- ViewCommitteeConsensus API unchanged
- Entity migration works during topology changes
- Static 4-bubble mode available via configuration

---

## Threshold Configuration

### Split Thresholds (from AdaptiveSplitPolicy)
```java
// Trigger split when frame utilization > 120%
float SPLIT_THRESHOLD = 1.2f;

// Minimum entities per bubble after split
int MIN_ENTITIES_PER_BUBBLE = 10;

// Maximum distance for cluster detection
float MAX_CLUSTER_DISTANCE = 50.0f;
```

### Merge Thresholds (from BubbleLifecycle)
```java
// Trigger merge when cross-bubble affinity > 60%
float MERGE_THRESHOLD = 0.6f;

// Maximum combined entity count after merge
int MAX_MERGED_ENTITY_COUNT = 200;
```

### Topology Control
```java
// Cooldown after topology change
Duration TOPOLOGY_COOLDOWN = Duration.ofSeconds(30);

// Maximum bubbles allowed
int MAX_BUBBLE_COUNT = 8;

// Minimum bubbles required
int MIN_BUBBLE_COUNT = 2;

// Detection interval
Duration DETECTION_INTERVAL = Duration.ofSeconds(5);
```

---

## Risk Analysis

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Entity loss during split | Medium | Critical | Atomic split protocol, validation tests, rollback |
| Topology oscillation (split/merge/split) | High | Medium | Hysteresis thresholds, 30s cooldown, require consensus |
| Byzantine node proposes bad split | Medium | High | Consensus voting prevents unilateral changes |
| View change during topology change | Medium | Medium | Abort + retry with new view, rollback protocol |
| Performance degradation | Low | Medium | Topology changes async, don't block migrations |
| Split during active migration | Medium | Medium | Migration lock during topology change |

### Oscillation Prevention

1. **Cooldown Timer**: 30 seconds between topology changes
2. **Hysteresis**: Split at 120%, only merge at 60%
3. **Consensus Required**: Byzantine minority cannot force changes
4. **Entity Count Guards**: Min/max entity thresholds

---

## File Inventory

### New Files (~20 files)

**Phase 9A** (4 files, ~280 LOC):
```
simulation/src/main/java/.../topology/
├── DensityMonitor.java                    (~100 LOC)
├── MergeDetector.java                     (~100 LOC)
└── TopologyDetectionLoop.java             (~80 LOC)

simulation/src/test/java/.../topology/
└── TopologyDetectionTest.java             (~200 LOC, 8 tests)
```

**Phase 9B** (5 files, ~560 LOC):
```
simulation/src/main/java/.../topology/
├── TopologySplitProposal.java             (~50 LOC)
├── TopologyMergeProposal.java             (~50 LOC)
├── TopologyProposalFactory.java           (~60 LOC)
└── TopologyConsensusCoordinator.java      (~200 LOC)

simulation/src/test/java/.../topology/
└── TopologyConsensusTest.java             (~300 LOC, 10 tests)
```

**Phase 9C** (5 files, ~950 LOC):
```
simulation/src/main/java/.../topology/
├── TopologyReorganizer.java               (~200 LOC)
├── EntityRedistributor.java               (~150 LOC)
├── TopologyValidator.java                 (~150 LOC)
└── DynamicBubbleGrid.java                 (~250 LOC)

simulation/src/test/java/.../topology/
└── TopologyExecutionTest.java             (~300 LOC, 10 tests)
```

**Phase 9D** (5 files, ~530 LOC):
```
simulation/src/main/java/.../topology/
├── DynamicTopologyDemo.java               (~200 LOC)
├── DynamicDemoConfiguration.java          (~50 LOC)
└── TopologyAdaptationMetrics.java         (~80 LOC)

simulation/src/test/java/.../topology/
├── DynamicTopologyIntegrationTest.java    (~250 LOC, 5 tests)
└── TopologyByzantineResilienceTest.java   (~100 LOC, 2 tests)
```

### Package Structure
```
com.hellblazer.luciferase.simulation.topology/
├── detection/
│   ├── DensityMonitor.java
│   ├── MergeDetector.java
│   └── TopologyDetectionLoop.java
├── proposal/
│   ├── TopologySplitProposal.java
│   ├── TopologyMergeProposal.java
│   └── TopologyProposalFactory.java
├── consensus/
│   └── TopologyConsensusCoordinator.java
├── execution/
│   ├── TopologyReorganizer.java
│   ├── EntityRedistributor.java
│   └── TopologyValidator.java
├── grid/
│   └── DynamicBubbleGrid.java
└── demo/
    ├── DynamicTopologyDemo.java
    ├── DynamicDemoConfiguration.java
    └── TopologyAdaptationMetrics.java
```

---

## Success Criteria

### Functional
- [ ] Bubbles split when entity density exceeds 120% frame utilization
- [ ] Bubbles merge when population drops below threshold
- [ ] All nodes reach consensus on topology changes
- [ ] Entity redistribution is atomic (all or nothing)
- [ ] Byzantine nodes cannot force bad splits/merges
- [ ] Topology changes respect cooldown period

### Performance
- [ ] Topology change latency < 2 seconds
- [ ] No entity loss during split/merge
- [ ] Entity retention 100% after topology change
- [ ] Ghost consistency maintained across topology change
- [ ] No performance regression for migrations during topology change

### Testing
- [ ] 30-35 new tests
- [ ] All Phase 8 tests pass (backward compatibility)
- [ ] Combined Phase 8 + Phase 9 = 100-110 total tests
- [ ] Coverage >= 80% for new code

---

## Timeline

| Phase | Days | Cumulative |
|-------|------|------------|
| Phase 9A: Detection | 1.0 | 1.0 |
| Phase 9B: Consensus | 1.5 | 2.5 |
| Phase 9C: Execution | 1.5 | 4.0 |
| Phase 9D: Integration | 1.0 | 5.0 |

**Total**: 5 days (with buffer)
**Minimum**: 4 days (aggressive)

---

## Dependency Graph

```
Phase 8 (Static MVP)     ← HARD BLOCKER
         │
         ▼
    Phase 9A (Detection)
         │
         ▼
    Phase 9B (Consensus)
         │
         ▼
    Phase 9C (Execution)
         │
         ▼
    Phase 9D (Integration)
```

---

## Open Questions Resolved

| Question | Resolution | Rationale |
|----------|------------|-----------|
| Density threshold | 1.2 (120% frame utilization) | AdaptiveSplitPolicy default |
| Split strategy | K-means clustering | Already implemented in AdaptiveSplitPolicy |
| Merge threshold | 0.6 (60% affinity) | BubbleLifecycle default |
| Max bubbles | 8 | 2 splits from initial 4 |
| Cooldown | 30 seconds | Prevents oscillation |

---

## Demo Scenario

### Starting State
- 4 bubbles (Phase 8 static grid)
- 100 entities distributed evenly (25 per bubble)
- Flocking behavior causes clustering

### Split Trigger
1. Entities cluster into 2 groups in bubble A1
2. Frame utilization exceeds 120%
3. DensityMonitor detects split candidate
4. TopologySplitProposal created
5. Committee votes YES
6. A1 splits into A1a and A1b
7. Grid now has 5 bubbles

### Merge Trigger
1. Entities migrate away from bubble B2
2. B2 population drops below threshold
3. MergeDetector detects merge candidate with B1
4. TopologyMergeProposal created
5. Committee votes YES
6. B1 and B2 merge into B1
7. Grid returns to 4 bubbles

---

## Related Documents

### ChromaDB References
- `audit::plan-auditor::phase8-implementation-plan-2026-01-10`
- `plan::strategic-planner::inc5-multi-bubble-scalability`
- `implementation::simulation::phase4-bubble-dynamics`

### Memory Bank
- `Luciferase_active/PHASE_8_STATUS.md` (when created)

### Architecture Documents
- `simulation/doc/DISTRIBUTED_ANIMATION_ARCHITECTURE_v4.0.md`
- `simulation/doc/PHASE_7G_DAY3_COMMITTEE_CONSENSUS_REDESIGN.md`

### Key Source Files
- `simulation/src/main/java/.../bubble/AdaptiveSplitPolicy.java`
- `simulation/src/main/java/.../bubble/BubbleLifecycle.java`
- `simulation/src/main/java/.../bubble/BubbleDynamicsManager.java`
- `simulation/src/main/java/.../consensus/committee/ViewCommitteeConsensus.java`

---

## Quality Gates

Before Phase 9 can be marked complete:
- [ ] Plan audited by plan-auditor
- [ ] Substantive-critic review complete
- [ ] All 30-35 new tests pass
- [ ] All Phase 8 tests pass (100%)
- [ ] Demo shows topology adaptation
- [ ] No entity loss in any scenario
- [ ] Byzantine resilience validated
- [ ] Documentation complete

---

**Status**: Ready for Plan Audit
**Next Action**: Route to plan-auditor for validation
**Blocked On**: Phase 8 completion
