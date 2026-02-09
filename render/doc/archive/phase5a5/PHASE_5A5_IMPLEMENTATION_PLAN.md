# Phase 5a.5: Integration Testing & 30% Node Reduction Validation

**Bead**: Luciferase-4i5z (P0)
**Status**: Ready for implementation
**Predecessor**: Luciferase-vmjc (Phase 5a.4) - COMPLETE
**Estimated Duration**: 3 days
**Working Directory**: `/Users/hal.hildebrand/git/Luciferase`

## Executive Summary

Phase 5a.5 validates that tile-based adaptive execution delivers the promised 30% node reduction compared to global BeamTree processing. This phase creates comprehensive benchmark infrastructure, implements 5 test scenarios, provides statistical validation of coherence-speed correlation, and documents findings.

## Objectives

1. **30% Node Reduction Validation**: Measure and prove node count reduction when using tile-based dispatch vs global BeamTree
2. **Performance Benchmarking**: Quantify actual speedup vs baseline using the live metrics system from Phase 5a.4
3. **Coherence Correlation**: Statistically validate that high-coherence tiles execute faster with batch kernel
4. **Integration Testing**: Comprehensive scenarios covering sky-only, geometry-only, mixed scenes, camera movement
5. **Metrics Validation**: Confirm tile coherence scores correlate with actual execution time

---

## Node Reduction Measurement Methodology

### Definition of "Node Reduction"

The 30% node reduction metric measures the total number of BeamTree nodes required:

- **Baseline (Global)**: Build ONE BeamTree for ALL rays in the frame
  - Measure: `BeamTreeBuilder.from(allRays).build().getStatistics().totalBeams()`

- **Tiled (Adaptive)**: Build N smaller BeamTrees, one per high-coherence tile
  - For high-coherence tiles: Build BeamTree, count totalBeams
  - For low-coherence tiles: Count 1 "virtual node" (single-ray kernel, no tree)
  - Sum all nodes across tiles

- **Reduction Calculation**:
  ```
  reduction = 1 - (tiled_nodes / global_nodes)
  target: reduction >= 0.30 for mixed scenes
  ```

### Why Tiling Reduces Nodes

When rays are pre-partitioned into coherent tiles:
1. Each tile's BeamTree contains spatially coherent rays
2. Coherent rays share early tree nodes, requiring fewer subdivisions
3. Low-coherence tiles bypass tree building entirely (single-ray kernel)
4. A global tree must subdivide more to achieve the same spatial organization

### Measurement Infrastructure

```java
public class NodeReductionComparator {

    public record ComparisonResult(
        int globalNodes,           // Nodes in single global BeamTree
        int tiledNodes,            // Sum of nodes in per-tile BeamTrees
        double reductionRatio,     // 1 - (tiledNodes / globalNodes)
        int highCoherenceTiles,    // Tiles routed to batch kernel
        int lowCoherenceTiles      // Tiles routed to single-ray kernel
    ) {}

    public ComparisonResult compare(Ray[] rays, TileConfiguration config, double threshold);
}
```

---

## Test Scenarios

### Scenario 1: SkyScene (High Coherence)

**Description**: All rays parallel, pointing in same direction (simulating sky-only rendering)

**Ray Configuration**:
- Frame: 256x256 (65,536 rays)
- Tiles: 16x16 pixels (256 tiles)
- All ray directions: (0, 0, 1) - parallel
- All ray origins: Grid pattern on z=0 plane

**Expected Behavior**:
- Coherence score: >= 0.9 for all tiles
- Batch ratio: ~100%
- Node reduction: Maximum achievable (40-50%)

**Validation Criteria**:
- All tiles routed to batch kernel
- BeamTree depth <= 3 (shallow tree due to high coherence)
- Execution time <= baseline global tree

### Scenario 2: GeometryScene (Low Coherence)

**Description**: Rays diverging in all directions from scene center (simulating complex geometry reflections)

**Ray Configuration**:
- Frame: 256x256 (65,536 rays)
- Tiles: 16x16 pixels (256 tiles)
- Ray directions: Radially outward from center
- Ray origins: All at (0.5, 0.5, 0.5)

**Expected Behavior**:
- Coherence score: <= 0.4 for all tiles
- Batch ratio: ~0%
- Node reduction: Minimal (0-10%)

**Validation Criteria**:
- All tiles routed to single-ray kernel
- No BeamTree construction for individual tiles
- Total "nodes" = number of tiles (one virtual node per tile)

### Scenario 3: MixedScene (Target: 30% Reduction)

**Description**: Top 60% of frame shows sky (parallel rays), bottom 40% shows complex geometry (divergent rays)

**Ray Configuration**:
- Frame: 256x256 (65,536 rays)
- Tiles: 16x16 pixels (256 tiles)
- Top 60%: Parallel rays (0, 0, 1) - 154 tiles
- Bottom 40%: Divergent rays - 102 tiles

**Expected Behavior**:
- Average coherence: ~0.6
- Batch ratio: ~60%
- Node reduction: >= 30% (PRIMARY TARGET)

**Validation Criteria**:
- 60% tiles high-coherence, 40% low-coherence
- Combined node count <= 70% of global tree
- Performance improvement visible in frame time

### Scenario 4: CameraMovementScene

**Description**: Same scene rendered 10 times to validate coherence caching

**Ray Configuration**:
- Frame: 256x256
- Tiles: 16x16 pixels
- Static scene geometry
- Simulate camera movement between frames

**Expected Behavior**:
- Frame 1: Full coherence analysis
- Frames 2-10: Cache hits for unchanged tiles
- TileCoherenceMap retains values between frames

**Validation Criteria**:
- Frame 2+ dispatch time < Frame 1 dispatch time
- Coherence cache hit rate > 90% for static tiles
- Invalidation works correctly when camera moves

### Scenario 5: LargeFrameScene (4K Stress Test)

**Description**: Full 4K resolution to validate dispatch overhead at scale

**Ray Configuration**:
- Frame: 3840x2160 (8,294,400 rays)
- Tiles: 16x16 pixels (32,400 tiles)
- Mixed coherence distribution (60/40 split)

**Expected Behavior**:
- Dispatch overhead: < 5% of total frame time
- Memory footprint: Stable (no OOM)
- Node reduction: Similar to MixedScene (~30%)

**Validation Criteria**:
- Dispatch time < 5ms for 32K tiles
- Total overhead < 5% of frame time
- All tiles processed without exception

---

## Statistical Validation: Coherence-Speed Correlation

### Hypothesis

High-coherence tiles (>= 0.7) execute faster with batch kernel than low-coherence tiles using single-ray kernel, because:
1. Batch kernel amortizes memory access across coherent rays
2. SIMD utilization higher with similar ray paths
3. Shared BeamTree nodes reduce traversal work

### Methodology

1. **Data Collection**:
   - Run MixedScene for 100 frames
   - Record per-tile: coherence score, execution time, kernel type
   - Collect 25,600 data points (256 tiles x 100 frames)

2. **Grouping by Coherence Bands**:
   - Band LOW: [0.0, 0.3) - Single-ray kernel
   - Band MEDIUM: [0.3, 0.7) - Borderline tiles
   - Band HIGH: [0.7, 1.0] - Batch kernel

3. **Statistical Analysis**:
   - Compute mean execution time per band
   - Calculate Pearson correlation coefficient between coherence and speed
   - Target: r >= 0.5 (moderate positive correlation)

4. **Expected Results**:
   ```
   Band HIGH:   avg_time = T_base
   Band MEDIUM: avg_time = T_base * 1.2-1.5
   Band LOW:    avg_time = T_base * 1.5-2.0
   ```

### Implementation

```java
public class CoherenceCorrelationAnalyzer {

    public record CorrelationResult(
        double pearsonR,           // Correlation coefficient [-1, 1]
        double pValue,             // Statistical significance
        double avgTimeHigh,        // Mean time for high-coherence tiles
        double avgTimeMedium,      // Mean time for medium-coherence
        double avgTimeLow,         // Mean time for low-coherence
        int sampleCount            // Total data points
    ) {}

    public CorrelationResult analyze(List<TileExecutionRecord> records);
}
```

---

## Implementation Phases

### Phase 1: Node Reduction Infrastructure (Day 1, Morning)

**Files to Create**:

| File | Package | LOC | Description |
|------|---------|-----|-------------|
| `NodeReductionComparator.java` | `render.benchmark` | ~150 | Core comparison logic |
| `SceneGenerator.java` | `render.benchmark` | ~100 | Abstract base for scene generation |
| `TestSceneFactory.java` | `render.benchmark` | ~50 | Factory for creating standard scenes |
| `NodeReductionComparatorTest.java` | `render.benchmark` (test) | ~100 | Unit tests for comparator |

**Package Structure**:
```
render/src/main/java/com/hellblazer/luciferase/render/benchmark/
  NodeReductionComparator.java
  SceneGenerator.java
  TestSceneFactory.java

render/src/test/java/com/hellblazer/luciferase/render/benchmark/
  NodeReductionComparatorTest.java
```

**Acceptance Criteria**:
- [ ] NodeReductionComparator compiles and passes unit tests
- [ ] Can measure global BeamTree node count
- [ ] Can measure sum of per-tile BeamTree nodes
- [ ] Reduction ratio calculated correctly

### Phase 2: Test Scene Implementations (Day 1, Afternoon + Day 2, Morning)

**Files to Create**:

| File | Package | LOC | Description |
|------|---------|-----|-------------|
| `SkyScene.java` | `render.benchmark.scenes` | ~60 | High coherence parallel rays |
| `GeometryScene.java` | `render.benchmark.scenes` | ~80 | Low coherence divergent rays |
| `MixedScene.java` | `render.benchmark.scenes` | ~100 | Configurable sky/geometry mix |
| `CameraMovementScene.java` | `render.benchmark.scenes` | ~80 | Multi-frame rendering test |
| `LargeFrameScene.java` | `render.benchmark.scenes` | ~60 | 4K stress test |
| `SceneGeneratorTest.java` | `render.benchmark.scenes` (test) | ~150 | Validate scene properties |

**Package Structure**:
```
render/src/main/java/com/hellblazer/luciferase/render/benchmark/scenes/
  SkyScene.java
  GeometryScene.java
  MixedScene.java
  CameraMovementScene.java
  LargeFrameScene.java

render/src/test/java/com/hellblazer/luciferase/render/benchmark/scenes/
  SceneGeneratorTest.java
```

**Acceptance Criteria**:
- [ ] Each scene generates rays with expected coherence patterns
- [ ] SkyScene: all tiles coherence >= 0.9
- [ ] GeometryScene: all tiles coherence <= 0.4
- [ ] MixedScene: configurable ratio works correctly
- [ ] Scenes produce valid Ray[] arrays

### Phase 3: Benchmarking Framework (Day 2, Afternoon)

**Files to Create**:

| File | Package | LOC | Description |
|------|---------|-----|-------------|
| `Phase5a5BenchmarkRunner.java` | `render.benchmark` | ~200 | Orchestrates benchmark runs |
| `BenchmarkResult.java` | `render.benchmark` | ~60 | Result data structure |
| `BenchmarkConfig.java` | `render.benchmark` | ~40 | Configuration for runs |
| `Phase5a5BenchmarkTest.java` | `render.benchmark` (test) | ~300 | Main integration test suite |

**Package Structure**:
```
render/src/main/java/com/hellblazer/luciferase/render/benchmark/
  Phase5a5BenchmarkRunner.java
  BenchmarkResult.java
  BenchmarkConfig.java

render/src/test/java/com/hellblazer/luciferase/render/benchmark/
  Phase5a5BenchmarkTest.java
```

**Test Suite (15+ tests)**:
1. `testSkySceneHighCoherence()` - All tiles high coherence
2. `testSkySceneBatchRatio()` - 100% batch routing
3. `testSkySceneNodeReduction()` - Maximum reduction achieved
4. `testGeometrySceneLowCoherence()` - All tiles low coherence
5. `testGeometrySceneSingleRayRouting()` - 0% batch routing
6. `testMixedSceneNodeReduction()` - **30% reduction target**
7. `testMixedSceneBatchRatio()` - ~60% batch routing
8. `testMixedSceneCoherenceDistribution()` - Correct split
9. `testCameraMovementCacheHits()` - Cache effectiveness
10. `testCameraMovementInvalidation()` - Correct cache clearing
11. `testLargeFrameDispatchOverhead()` - <5% overhead
12. `testLargeFrameMemoryStability()` - No OOM
13. `testCoherenceThresholdSensitivity()` - Threshold tuning
14. `testNodeCountConsistency()` - Reproducible results
15. `testEdgeCaseSingleTile()` - Single tile frame
16. `testEdgeCaseOddDimensions()` - Non-divisible frame size

**Acceptance Criteria**:
- [ ] All 15+ tests pass
- [ ] MixedScene achieves >= 30% node reduction (or documents why not)
- [ ] Benchmark results are reproducible

### Phase 4: Statistical Validation (Day 3, Morning)

**Files to Create**:

| File | Package | LOC | Description |
|------|---------|-----|-------------|
| `CoherenceCorrelationAnalyzer.java` | `render.benchmark` | ~120 | Statistical analysis |
| `CorrelationResult.java` | `render.benchmark` | ~40 | Analysis result record |
| `TileExecutionRecord.java` | `render.benchmark` | ~30 | Per-tile data point |
| `CoherenceCorrelationTest.java` | `render.benchmark` (test) | ~100 | Validate statistics |

**Acceptance Criteria**:
- [ ] Pearson correlation coefficient computed correctly
- [ ] r >= 0.5 correlation between coherence and speed
- [ ] P-value < 0.05 (statistically significant)
- [ ] Clear trend: HIGH band faster than MEDIUM faster than LOW

### Phase 5: Documentation (Day 3, Afternoon)

**Files to Create**:

| File | LOC | Description |
|------|-----|-------------|
| `PHASE_5A5_RESULTS.md` | ~300 | Detailed findings document |

**Document Structure**:
1. Executive Summary
2. Methodology
3. Results by Scenario
4. Statistical Analysis
5. 30% Target Assessment
6. Recommendations
7. Appendix: Raw Data

---

## Success Criteria

### Must Have (P0)
- [ ] All 15+ integration tests passing
- [ ] 30% node reduction validated for MixedScene (or documented why not achievable)
- [ ] Coherence-speed correlation demonstrated (r >= 0.5)
- [ ] Tile dispatch overhead < 5% of frame time
- [ ] Live metrics confirm dispatch routing matches expected behavior

### Should Have (P1)
- [ ] Camera movement validation (coherence cache working)
- [ ] 4K stress test passes without OOM
- [ ] Statistical significance p < 0.05
- [ ] Results documented in PHASE_5A5_RESULTS.md

### Nice to Have (P2)
- [ ] Coherence threshold optimization recommendations
- [ ] Performance comparison across tile sizes (8x8, 16x16, 32x32)
- [ ] Visualization of coherence heatmap during benchmark

---

## Dependencies

### Hard Dependencies (Must Exist)

| Dependency | File | Status |
|------------|------|--------|
| TileBasedDispatcher | `render/tile/TileBasedDispatcher.java` | VERIFIED |
| TileConfiguration | `render/tile/TileConfiguration.java` | VERIFIED |
| DispatchMetrics | `render/tile/DispatchMetrics.java` | VERIFIED |
| BeamTree | `esvo/gpu/beam/BeamTree.java` | VERIFIED |
| BeamTreeBuilder | `esvo/gpu/beam/BeamTreeBuilder.java` | VERIFIED |
| GPUMetricsCollector | `esvo/gpu/beam/metrics/GPUMetricsCollector.java` | VERIFIED |
| CoherenceSnapshot | `esvo/gpu/beam/metrics/CoherenceSnapshot.java` | VERIFIED |
| MetricsSnapshot | `esvo/gpu/beam/metrics/MetricsSnapshot.java` | VERIFIED |
| KernelExecutor | `render/tile/KernelExecutor.java` | VERIFIED |
| TileCoherenceMap | `render/tile/TileCoherenceMap.java` | VERIFIED |
| Tile | `render/tile/Tile.java` | VERIFIED |

### Soft Dependencies (Nice to Have)
- Real GPU profiling counters (can simulate with timing)
- High-fidelity test DAGs (can use synthetic simple DAGs)
- Real-time metrics visualization (already have from Phase 5a.4)

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| 30% target not achievable | MEDIUM | MEDIUM | Document actual achieved reduction, identify threshold/config optimizations |
| Node counting methodology unclear | LOW | LOW | Validate against BeamTree.getStatistics().totalBeams() |
| Statistical noise obscures correlation | MEDIUM | LOW | Use large sample size (25,600 data points), aggregate by bands |
| Camera invalidation timing off | LOW | LOW | Unit tests for TileCoherenceMap.invalidate() |
| Performance regression from metrics | LOW | MEDIUM | Already validated <1ms overhead in Phase 5a.4 |

---

## Rollback/Scope Adjustment

### If 30% Target Not Achievable

1. Document actual achieved reduction (e.g., 15-20%)
2. Analyze why:
   - Tile size suboptimal?
   - Coherence threshold needs tuning?
   - Scene characteristics matter?
3. Identify configurations where target IS achievable
4. Adjust expectations based on empirical data

### If Statistical Validation Weak

1. Increase sample size (200+ frames)
2. Use non-parametric tests if distribution non-normal
3. Report confidence intervals instead of point estimates
4. Document correlation direction even if r < 0.5

---

## Day-by-Day Timeline

### Day 1

**Morning (4 hours)**:
- Create `render/benchmark/` package structure
- Implement `NodeReductionComparator.java`
- Implement `SceneGenerator.java` (abstract base)
- Implement `TestSceneFactory.java`
- Write `NodeReductionComparatorTest.java`
- **Checkpoint**: NodeReductionComparator tests passing

**Afternoon (4 hours)**:
- Implement `SkyScene.java`
- Implement `GeometryScene.java`
- Implement `MixedScene.java`
- Write initial `SceneGeneratorTest.java`
- **Checkpoint**: Scene generators produce valid ray arrays

### Day 2

**Morning (4 hours)**:
- Implement `CameraMovementScene.java`
- Implement `LargeFrameScene.java`
- Complete `SceneGeneratorTest.java`
- Run initial node reduction measurements
- **Checkpoint**: All 5 scenes generating correct rays

**Afternoon (4 hours)**:
- Implement `Phase5a5BenchmarkRunner.java`
- Implement `BenchmarkResult.java` and `BenchmarkConfig.java`
- Start `Phase5a5BenchmarkTest.java` (first 8 tests)
- **Checkpoint**: Basic benchmark infrastructure working

### Day 3

**Morning (4 hours)**:
- Complete `Phase5a5BenchmarkTest.java` (tests 9-16)
- Implement `CoherenceCorrelationAnalyzer.java`
- Implement `CorrelationResult.java`
- Write `CoherenceCorrelationTest.java`
- Run full benchmark suite, collect data
- **Checkpoint**: All tests passing (or failures documented)

**Afternoon (4 hours)**:
- Analyze benchmark results
- Write `PHASE_5A5_RESULTS.md`
- Update bead Luciferase-4i5z with results
- Prepare for plan-auditor review
- **Checkpoint**: Phase 5a.5 complete

---

## Critical Files Summary

### Production Code (~800 LOC)

| File | LOC | Purpose |
|------|-----|---------|
| `NodeReductionComparator.java` | 150 | Core comparison logic |
| `SceneGenerator.java` | 100 | Abstract scene base |
| `TestSceneFactory.java` | 50 | Scene factory |
| `SkyScene.java` | 60 | High coherence scene |
| `GeometryScene.java` | 80 | Low coherence scene |
| `MixedScene.java` | 100 | Target validation scene |
| `CameraMovementScene.java` | 80 | Cache validation scene |
| `LargeFrameScene.java` | 60 | 4K stress test |
| `Phase5a5BenchmarkRunner.java` | 200 | Benchmark orchestration |
| `BenchmarkResult.java` | 60 | Result data |
| `BenchmarkConfig.java` | 40 | Configuration |
| `CoherenceCorrelationAnalyzer.java` | 120 | Statistics |
| `CorrelationResult.java` | 40 | Analysis result |
| `TileExecutionRecord.java` | 30 | Per-tile data point |

### Test Code (~650 LOC)

| File | LOC | Tests |
|------|-----|-------|
| `NodeReductionComparatorTest.java` | 100 | 5 tests |
| `SceneGeneratorTest.java` | 150 | 10 tests |
| `Phase5a5BenchmarkTest.java` | 300 | 16 tests |
| `CoherenceCorrelationTest.java` | 100 | 5 tests |

### Documentation

| File | Lines |
|------|-------|
| `PHASE_5A5_RESULTS.md` | ~300 |

---

## Handoff to plan-auditor

This plan is ready for review. Key validation points:

1. **Completeness**: Does the plan cover all aspects of 30% node reduction validation?
2. **Methodology**: Is the node reduction measurement approach sound?
3. **Statistical Rigor**: Is the coherence-speed correlation analysis valid?
4. **Timeline**: Is 3 days realistic for the scope?
5. **Dependencies**: Are all required Phase 5a.3/5a.4 components available?
6. **Success Criteria**: Are the targets achievable and measurable?

---

## References

- **Phase 5a.3 Implementation**: TileBasedDispatcher, TileConfiguration, DispatchMetrics
- **Phase 5a.4 Implementation**: GPUMetricsCollector, MetricsSnapshot, BeamMetricsOverlay
- **BeamTree Architecture**: BeamTree, BeamTreeBuilder, BeamNode
- **Existing Tests**: TileBasedDispatcherTest, BeamTreeBuilderTest patterns
