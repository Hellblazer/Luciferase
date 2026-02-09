# F3.1.3: Stream C - Beam Optimization Plan

**Bead**: Luciferase-pbhp
**Status**: CONDITIONAL (triggers only if Streams A+B miss targets)
**Target**: 30% node reduction through beam filtering
**Estimated Effort**: 8 developer-days (if triggered)
**Last Updated**: 2026-01-21

---

## Executive Summary

Stream C implements **beam optimization** - a ray coherence exploitation technique that groups adjacent screen rays into "beams" sharing traversal work. This optimization is **conditional**: it activates only if the baseline kernel (Stream A) plus memory optimization (Stream B) fail to meet performance targets.

### Key Metrics

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| Node Count Reduction | >=30% | Profiler instrumentation |
| Correctness Preservation | >95% | GPU/CPU parity tests |
| Register Overhead | <10% increase | OpenCL compiler stats |
| Coherence Threshold | 0.6+ | Automatic decision gate |

---

## I. Ray Coherence Analysis

### 1.1 Coherence Patterns

**Primary Ray Coherence** (viewing rays from camera):
- Adjacent pixels have nearly identical origins (camera position)
- Directions differ by small angles: `tan(FOV / resolution)`
- Traversal paths share 80-95% of visited nodes at upper tree levels
- **Key insight**: First 3-5 octree levels are IDENTICAL for adjacent rays

**Coherence Decay by Level**:
```
Level 0 (root):     100% coherent (all rays visit)
Level 1-3:          95%+ coherent  (beam optimization effective)
Level 4-6:          75-85% coherent (moderate benefit)
Level 7-10:         50-65% coherent (diminishing returns)
Level 10+:          <50% coherent (individual ray processing better)
```

### 1.2 Existing Infrastructure

The codebase already contains coherence analysis infrastructure:

**ESVTBeamOptimization.java** (ESVT module):
- Two-pass rendering: coarse (1/4 resolution) + fine pass
- Conservative t-min calculation from coarse buffer
- 30-50% traversal reduction reported

**ESVOTraversalOptimizer.java** (ESVO module):
- `RayCoherence` class: spatial + directional coherence metrics
- `RayGroup` class: grouped rays with centroid/average direction
- `MAX_RAYS_PER_GROUP = 32`
- Coherence threshold: 0.1 (spatial), 0.8 (directional)

**ESVTTraversalOptimizer.java** (ESVT module):
- Enhanced with tetrahedron face coherence
- Tet-type-aware grouping
- 6 tetrahedron types (S0-S5) considered

---

## II. Beam Filtering Strategy

### 2.1 Beam Width Selection

| Beam Width | Layout | Coherence | Best For |
|------------|--------|-----------|----------|
| 8 rays | 2x4 / 4x2 | 85%+ | Divergent scenes, thin objects |
| **16 rays** | **4x4** | **75-85%** | **General purpose (RECOMMENDED)** |
| 32 rays | 8x4 / 4x8 | 65-75% | Simple geometry, open scenes |

**Recommendation**: Default to **16 rays per beam** (4x4 screen tiles)
- Half-warp on NVIDIA (good occupancy)
- Good coherence for typical scenes
- Balanced complexity vs benefit

### 2.2 Ray Filtering Criteria

```java
record BeamFilterCriteria(
    float directionSimilarityThreshold,  // Default: 0.95 (dot product)
    float traversalDepthCorrelation,     // Default: 0.8
    float hitMissPredictionConfidence,   // Default: 0.7
    int maxDepthBeforeIndividual         // Default: 8 levels
) {}
```

**Direction Similarity Threshold**:
- Rays with `dot(dir1, dir2) > 0.95` share traversal
- Below threshold: split to individual processing

**Traversal Depth Correlation**:
- Track shared octants visited
- Below 0.8 correlation: divergence imminent

**Hit/Miss Prediction**:
- If beam frustum misses node AABB: all rays miss
- Early termination for entire beam (huge savings)

### 2.3 Divergent Ray Fallback

When rays diverge beyond threshold:
1. Beam transitions to individual ray processing
2. Each ray gets private stack (spills from shared if needed)
3. Remaining traversal uses baseline kernel logic
4. Results merged at end

---

## III. Implementation Strategy

### 3.1 GPU Kernel Design

```opencl
/**
 * Beam-optimized DAG traversal kernel
 *
 * Traverses coherent ray groups with shared node access,
 * falling back to individual ray processing on divergence.
 */
__kernel void rayTraverseDAGBeam(
    __global const DAGNode* nodePool,
    __global const uint* childPointers,
    const uint nodeCount,
    __global const Ray* rays,
    const uint rayCount,
    const uint beamSize,              // 8, 16, or 32
    __global IntersectionResult* results
) {
    uint beamId = get_group_id(0);
    uint rayInBeam = get_local_id(0);
    uint rayIdx = beamId * beamSize + rayInBeam;

    if (rayIdx >= rayCount) return;

    // Shared memory for beam traversal
    __local uint sharedStack[MAX_DEPTH];
    __local uint sharedStackPtr;
    __local uint beamStatus;  // Bitmap: active rays in beam

    // Phase 1: Beam-level shared traversal
    if (rayInBeam == 0) {
        sharedStack[0] = 0;  // Root node
        sharedStackPtr = 1;
        beamStatus = (1u << beamSize) - 1;  // All rays active
    }
    barrier(CLK_LOCAL_MEM_FENCE);

    Ray ray = rays[rayIdx];
    uint myOctant = 0;
    bool stillInBeam = true;

    while (sharedStackPtr > 0 && stillInBeam) {
        uint nodeIdx = sharedStack[sharedStackPtr - 1];
        DAGNode node = nodePool[nodeIdx];  // Single load for all rays

        uint childMask = getChildMask(node.childDescriptor);

        // Each ray votes for its preferred octant
        myOctant = computeOctantForRay(ray, nodeIdx);

        // Ballot: do all active rays agree?
        uint votes = work_group_reduce_and(myOctant);  // Simplified voting
        bool unanimous = (popcount(votes ^ myOctant) == 0);

        if (unanimous && hasChild(childMask, myOctant)) {
            // All rays agree: advance beam
            if (rayInBeam == 0) {
                uint childIdx = getChildPtr(node.childDescriptor) + myOctant;
                sharedStack[sharedStackPtr++] = childIdx;
            }
            barrier(CLK_LOCAL_MEM_FENCE);
        } else {
            // Divergence: exit beam traversal
            stillInBeam = false;
        }
    }

    // Phase 2: Individual ray traversal (after divergence)
    uint privateStack[MAX_DEPTH];
    uint privateStackPtr = 0;

    // Copy remaining shared stack to private
    for (uint i = 0; i < sharedStackPtr; i++) {
        privateStack[i] = sharedStack[i];
    }
    privateStackPtr = sharedStackPtr;

    // Continue with standard traversal...
    IntersectionResult result = continueIndividualTraversal(
        nodePool, childPointers, nodeCount, ray,
        privateStack, privateStackPtr
    );

    results[rayIdx] = result;
}
```

### 3.2 Java-Side Beam Formation

```java
public class BeamFormation {

    private static final int DEFAULT_BEAM_SIZE = 16;

    /**
     * Reorganize rays into beam-coherent order.
     *
     * @param rays Original ray array (screen order)
     * @param width Screen width
     * @param height Screen height
     * @return Reordered rays grouped by beam
     */
    public static Ray[] formBeams(Ray[] rays, int width, int height) {
        int beamWidth = (int) Math.sqrt(DEFAULT_BEAM_SIZE);  // 4 for 16-ray beams
        int beamsX = (width + beamWidth - 1) / beamWidth;
        int beamsY = (height + beamWidth - 1) / beamWidth;

        var reordered = new Ray[rays.length];
        int outIdx = 0;

        for (int by = 0; by < beamsY; by++) {
            for (int bx = 0; bx < beamsX; bx++) {
                // Gather rays in this beam's tile
                for (int ly = 0; ly < beamWidth; ly++) {
                    for (int lx = 0; lx < beamWidth; lx++) {
                        int px = bx * beamWidth + lx;
                        int py = by * beamWidth + ly;

                        if (px < width && py < height) {
                            int srcIdx = py * width + px;
                            reordered[outIdx++] = rays[srcIdx];
                        }
                    }
                }
            }
        }

        return reordered;
    }
}
```

---

## IV. Conditional Implementation

### 4.1 Decision Gate

```java
public class BeamDecisionGate {

    private static final float TARGET_100K_MS = 5.0f;
    private static final float BEAM_TRIGGER_THRESHOLD = 1.2f;  // 20% over target
    private static final float MIN_COHERENCE = 0.6f;

    /**
     * Determine if beam optimization should be enabled.
     *
     * @param baseline100K Baseline kernel time for 100K rays (ms)
     * @param memoryUtilization Memory bandwidth utilization (0-1)
     * @param coherenceScore Ray coherence score (0-1)
     * @return true if beam optimization should activate
     */
    public static boolean shouldEnableBeamOptimization(
            float baseline100K,
            float memoryUtilization,
            float coherenceScore) {

        // Gate 1: Performance target met?
        if (baseline100K <= TARGET_100K_MS) {
            return false;  // Not needed
        }

        // Gate 2: Within close range of target?
        if (baseline100K <= TARGET_100K_MS * BEAM_TRIGGER_THRESHOLD) {
            // Close to target - check if worth the complexity
            return coherenceScore >= MIN_COHERENCE + 0.1f;
        }

        // Gate 3: Significantly over target - beam opt needed
        if (coherenceScore < MIN_COHERENCE) {
            return false;  // Low coherence = beam won't help
        }

        // Gate 4: Memory bottleneck check
        if (memoryUtilization > 0.85f) {
            return true;  // Memory bound - beam reduces loads
        }

        return baseline100K > TARGET_100K_MS * 1.4f;
    }
}
```

### 4.2 Decision Matrix

| Baseline 100K | Memory BW | Coherence | Decision |
|---------------|-----------|-----------|----------|
| <5ms | Any | Any | **SKIP** (target met) |
| 5-6ms | <80% | >0.7 | **ENABLE** (moderate benefit) |
| 5-6ms | >80% | Any | **SKIP** (BW not bottleneck) |
| 6-7ms | Any | >0.6 | **ENABLE** (clear benefit) |
| >7ms | Any | >0.5 | **ENABLE** (needed) |
| Any | Any | <0.5 | **SKIP** (low coherence) |

---

## V. Implementation Phases

### Phase C1: Ray Coherence Analysis Infrastructure (2 days)

**Objective**: Build Java-side ray sorting and coherence measurement

**Files to Create**:
| File | Purpose | Lines (est) |
|------|---------|-------------|
| `BeamFormation.java` | Ray grouping by screen position | 80 |
| `RayCoherenceAnalyzer.java` | Coherence metric computation | 120 |
| `BeamFormationTest.java` | TDD tests | 150 |

**Acceptance Criteria**:
- [ ] Rays correctly grouped into 4x4 tiles
- [ ] Coherence score computed (spatial + directional)
- [ ] 100% test coverage on beam formation
- [ ] Performance: <1ms for 1M rays grouping

### Phase C2: Beam Grouping Algorithm (2 days)

**Objective**: Adaptive beam width selection and frustum computation

**Files to Create**:
| File | Purpose | Lines (est) |
|------|---------|-------------|
| `AdaptiveBeamGrouper.java` | Dynamic beam width selection | 150 |
| `BeamFrustum.java` | Convex hull for beam extent | 100 |
| `BeamGroupingTest.java` | TDD tests | 180 |

**Acceptance Criteria**:
- [ ] Adaptive selection between 8/16/32 beam widths
- [ ] Frustum correctly bounds all rays in beam
- [ ] Boundary ray handling (overlap strategy)
- [ ] Auto-tuning based on scene coherence

### Phase C3: Shared Node Cache Optimization (3 days)

**Objective**: GPU kernel with beam traversal

**Files to Create**:
| File | Purpose | Lines (est) |
|------|---------|-------------|
| `dag_beam_traversal.cl` | Beam-optimized kernel | 200 |
| `DAGBeamOpenCLRenderer.java` | Renderer with beam support | 150 |
| `BeamKernelTest.java` | GPU/CPU parity tests | 200 |

**Acceptance Criteria**:
- [ ] Beam kernel compiles on NVIDIA/AMD/Intel
- [ ] >95% GPU/CPU parity
- [ ] Shared stack works correctly
- [ ] Fallback to individual rays on divergence
- [ ] >=30% node reduction for coherent scenes

### Phase C4: Profiling and Decision Logic (1 day)

**Objective**: Auto-detection and conditional enablement

**Files to Create**:
| File | Purpose | Lines (est) |
|------|---------|-------------|
| `BeamOptimizationProfiler.java` | Performance comparison | 100 |
| `BeamDecisionGate.java` | Conditional enablement | 80 |
| `BeamDecisionGateTest.java` | Decision tests | 100 |

**Acceptance Criteria**:
- [ ] Decision gate correctly activates/skips
- [ ] Profiler measures node reduction accurately
- [ ] Auto-tuning selects optimal beam width
- [ ] Documentation complete

---

## VI. Success Criteria

### 6.1 Functional Requirements

| Requirement | Metric | Target |
|-------------|--------|--------|
| Node Count Reduction | Profiler measurement | >=30% |
| Correctness | GPU/CPU parity | >95% |
| Coherence Detection | Analyzer accuracy | >90% |
| Decision Gate | Correct enable/disable | >95% |

### 6.2 Performance Requirements

| Metric | Target | Measurement |
|--------|--------|-------------|
| Register Overhead | <10% increase | OpenCL compiler |
| Beam Formation | <1ms for 1M rays | Java profiler |
| Memory Reduction | 25-40% | GPU profiler |
| Speedup vs Baseline | 1.3-1.5x | Benchmark suite |

### 6.3 Quality Gates

**Phase C1 Gate**:
- [ ] Coherence analyzer passes all TDD tests
- [ ] Beam formation handles edge cases

**Phase C2 Gate**:
- [ ] Adaptive width selection validated
- [ ] Frustum computation correct

**Phase C3 Gate**:
- [ ] Kernel compiles on all vendors
- [ ] >95% parity with baseline
- [ ] 30% node reduction achieved

**Phase C4 Gate**:
- [ ] Decision gate correctly activates
- [ ] End-to-end integration working

---

## VII. Risk Analysis

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Ballot intrinsics not portable | MEDIUM | HIGH | Local memory voting fallback |
| Beam overhead exceeds benefit | MEDIUM | MEDIUM | Decision gate auto-disables |
| Register pressure from dual stacks | LOW | HIGH | Lazy allocation, global spill |
| Divergence handling complex | MEDIUM | MEDIUM | Early fallback to individual |
| macOS OpenCL quirks | MEDIUM | LOW | Documented workarounds |

---

## VIII. Dependencies

### Upstream Dependencies

| Dependency | Bead | Status | Required For |
|------------|------|--------|--------------|
| Stream A: Baseline Kernel | Luciferase-jybo | In Progress | Performance baseline |
| Stream B: Memory Optimization | Luciferase-40r8 | Pending | Coalescence metrics |
| Phase 2 DAG | Complete | Done | DAG data structure |

### Decision Gate Trigger

Stream C activates **ONLY** when:
1. Stream A baseline kernel is functional
2. Stream B memory optimization complete
3. Combined A+B performance still misses targets
4. Measured ray coherence >= 0.6

---

## IX. Files to Create Summary

### Production Code

| File | Package | Lines | Phase |
|------|---------|-------|-------|
| `BeamFormation.java` | `esvo.beam` | 80 | C1 |
| `RayCoherenceAnalyzer.java` | `esvo.beam` | 120 | C1 |
| `AdaptiveBeamGrouper.java` | `esvo.beam` | 150 | C2 |
| `BeamFrustum.java` | `esvo.beam` | 100 | C2 |
| `dag_beam_traversal.cl` | `kernels` | 200 | C3 |
| `DAGBeamOpenCLRenderer.java` | `esvo.gpu` | 150 | C3 |
| `BeamOptimizationProfiler.java` | `esvo.beam` | 100 | C4 |
| `BeamDecisionGate.java` | `esvo.beam` | 80 | C4 |

**Total Production**: ~980 lines

### Test Code

| File | Package | Lines | Phase |
|------|---------|-------|-------|
| `BeamFormationTest.java` | `esvo.beam` | 150 | C1 |
| `BeamGroupingTest.java` | `esvo.beam` | 180 | C2 |
| `BeamKernelTest.java` | `esvo.gpu` | 200 | C3 |
| `BeamDecisionGateTest.java` | `esvo.beam` | 100 | C4 |

**Total Tests**: ~630 lines

---

## X. Integration with Existing Codebase

### Reuse Opportunities

1. **ESVTBeamOptimization.java**: Two-pass rendering pattern
   - Adapt `executeCoarsePass()` / `executeFinePass()` logic
   - Reuse `BeamStats` record for metrics

2. **ESVOTraversalOptimizer.java**: Coherence calculation
   - Reuse `RayCoherence` class
   - Reuse `RayGroup` class
   - Adapt `optimizeRayGrouping()` algorithm

3. **dag_ray_traversal.cl**: Baseline kernel structure
   - Extend for beam traversal
   - Reuse `traverseDAG()` for individual ray fallback
   - Reuse data structures (DAGNode, Ray, IntersectionResult)

### API Surface

```java
// New public API
public class DAGBeamOpenCLRenderer extends AbstractOpenCLRenderer {

    public void setBeamOptimizationEnabled(boolean enabled);
    public void setBeamSize(int size);  // 8, 16, or 32
    public BeamStats getBeamStats();

    // Auto-detection mode (uses decision gate)
    public void setAutoBeamOptimization(boolean auto);
}
```

---

## XI. Documentation References

- **KERNEL_ARCHITECTURE_ANALYSIS.md**: Baseline kernel patterns
- **PHASE_2_COMPLETION_SUMMARY.md**: DAG implementation context
- **ESVTBeamOptimization.java**: Existing beam implementation
- **ESVOTraversalOptimizer.java**: Coherence analysis reference

---

**Document Status**: PLANNING COMPLETE
**Author**: Strategic Planner
**Reviewers**: GPU Team, Architecture Committee
**Approval Required**: After Streams A+B performance assessment
