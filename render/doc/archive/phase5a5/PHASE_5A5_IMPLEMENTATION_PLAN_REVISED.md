# Phase 5a.5: Integration Testing & 30% Node Reduction Validation (REVISED)

**Bead**: Luciferase-4i5z (P0)
**Status**: Ready for implementation (Audit conditions addressed)
**Predecessor**: Luciferase-vmjc (Phase 5a.4) - COMPLETE
**Estimated Duration**: 3 days
**Working Directory**: `/Users/hal.hildebrand/git/Luciferase`
**Audit Status**: GO - CONDITIONS ADDRESSED (See Appendix A)

---

## Revision Summary

This plan revision addresses the plan-auditor findings from the 2026-01-23 audit:

| Audit Condition | Status | Changes Made |
|-----------------|--------|--------------|
| REQUIRED #1: CoherenceAnalyzer type mismatch | ADDRESSED | Added SimpleRayCoherenceAnalyzer.java (~80 LOC) to Phase 1 |
| REQUIRED #2: LargeFrameScene memory config | ADDRESSED | Added @Tag("memory-intensive"), JVM flags, Build & Test section |
| Recommended #3: Virtual node counting docs | ADDRESSED | Added clarifying comments in NodeReductionComparator |
| Recommended #4: Spearman correlation backup | ADDRESSED | Added to CoherenceCorrelationAnalyzer spec |
| Recommended #5: Review existing patterns | ADDRESSED | Reference to ESVOPerformanceBenchmark.java added |

---

## Executive Summary

Phase 5a.5 validates that tile-based adaptive execution delivers the promised 30% node reduction compared to global BeamTree processing. This phase creates comprehensive benchmark infrastructure, implements 5 test scenarios, provides statistical validation of coherence-speed correlation, and documents findings.

---

## Design Decisions

### DD-1: Spatial-Only Coherence Analysis for Phase 5a.5

**Decision**: Implement `SimpleRayCoherenceAnalyzer` using spatial-only analysis (direction similarity) rather than using the existing DAG-aware `RayCoherenceAnalyzer`.

**Rationale**:
1. **Type Compatibility**: `TileBasedDispatcher.CoherenceAnalyzer` expects `Ray[]` (beam package), but `RayCoherenceAnalyzer` uses `ESVORay[]` - incompatible types
2. **Performance**: Spatial-only analysis avoids DAG traversal overhead, which is unnecessary for tile-level coherence measurement
3. **Simplicity**: Direction similarity alone is sufficient for tile partitioning decisions
4. **Precedent**: BeamTreeBuilder.computeCoherence() uses the same spatial-only approach with proven effectiveness

**Algorithm** (adapted from BeamTreeBuilder):
```java
// Direction-based coherence: average dot product with reference ray
public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
    if (rays.length <= 1) return 1.0;
    var refDir = rays[0].direction();
    double totalSim = 0.0;
    for (int i = 1; i < rays.length; i++) {
        totalSim += Math.abs(refDir.dot(rays[i].direction()));
    }
    return totalSim / (rays.length - 1);
}
```

### DD-2: Virtual Node Counting for Low-Coherence Tiles

**Decision**: Count low-coherence tiles as 1 "virtual node" each in node reduction calculations.

**Rationale**: Low-coherence tiles bypass BeamTree construction entirely and execute via single-ray kernel. The "1 virtual node" represents the minimum dispatch overhead for processing these tiles. This provides a conservative baseline for comparison against global tree nodes.

**Formula**:
```
tiled_nodes = SUM(high_coherence_tile_beamtree_nodes) + COUNT(low_coherence_tiles) * 1
reduction = 1 - (tiled_nodes / global_nodes)
```

---

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
  - For low-coherence tiles: Count 1 "virtual node" per tile (single-ray kernel, no tree)
    - **Rationale**: Virtual nodes represent single-ray kernel dispatch overhead; this is a conservative count since no actual tree is built
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

    /**
     * Comparison result between global and tiled approaches.
     * NOTE: tiledNodes includes 1 "virtual node" per low-coherence tile,
     * representing single-ray kernel overhead (no actual tree built).
     */
    public record ComparisonResult(
        int globalNodes,           // Nodes in single global BeamTree
        int tiledNodes,            // Sum of nodes in per-tile BeamTrees + virtual nodes
        double reductionRatio,     // 1 - (tiledNodes / globalNodes)
        int highCoherenceTiles,    // Tiles routed to batch kernel (tree built)
        int lowCoherenceTiles      // Tiles routed to single-ray kernel (virtual node)
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

**Memory Requirements**:
- Ray array: ~400MB (8.3M rays x 48 bytes)
- BeamTree overhead: Up to 2-3x additional for nodes
- **Minimum JVM heap: 1GB (`-Xmx1g`)**

**Expected Behavior**:
- Dispatch overhead: < 5% of total frame time
- Memory footprint: Stable (no OOM)
- Node reduction: Similar to MixedScene (~30%)

**Validation Criteria**:
- Dispatch time < 5ms for 32K tiles
- Total overhead < 5% of frame time
- All tiles processed without exception

**Test Annotations**:
```java
@Tag("memory-intensive")  // Requires -Xmx1g JVM heap
@Test
void testLargeFrameDispatchOverhead() { ... }
```

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
   - **Backup**: If Pearson p-value > 0.05, compute Spearman rank correlation (robust to non-normal distributions)

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
        double pearsonPValue,      // Statistical significance for Pearson
        double spearmanRho,        // Spearman rank correlation (backup)
        double spearmanPValue,     // Statistical significance for Spearman
        double avgTimeHigh,        // Mean time for high-coherence tiles
        double avgTimeMedium,      // Mean time for medium-coherence
        double avgTimeLow,         // Mean time for low-coherence
        int sampleCount            // Total data points
    ) {
        /**
         * Returns the recommended correlation based on Pearson p-value.
         * If Pearson is not significant (p > 0.05), falls back to Spearman.
         */
        public double recommendedCorrelation() {
            return pearsonPValue <= 0.05 ? pearsonR : spearmanRho;
        }
    }

    public CorrelationResult analyze(List<TileExecutionRecord> records);
}
```

---

## Implementation Phases

### Phase 1: Node Reduction Infrastructure (Day 1, Morning)

**Files to Create**:

| File | Package | LOC | Description |
|------|---------|-----|-------------|
| `SimpleRayCoherenceAnalyzer.java` | `render.benchmark` | ~80 | **NEW**: Spatial-only coherence for Ray[] type |
| `NodeReductionComparator.java` | `render.benchmark` | ~150 | Core comparison logic with virtual node docs |
| `SceneGenerator.java` | `render.benchmark` | ~100 | Abstract base for scene generation |
| `TestSceneFactory.java` | `render.benchmark` | ~50 | Factory for creating standard scenes |
| `NodeReductionComparatorTest.java` | `render.benchmark` (test) | ~100 | Unit tests for comparator |

**Package Structure**:
```
render/src/main/java/com/hellblazer/luciferase/render/benchmark/
  SimpleRayCoherenceAnalyzer.java    <- NEW (addresses audit REQUIRED #1)
  NodeReductionComparator.java
  SceneGenerator.java
  TestSceneFactory.java

render/src/test/java/com/hellblazer/luciferase/render/benchmark/
  NodeReductionComparatorTest.java
```

**SimpleRayCoherenceAnalyzer Specification**:
```java
/**
 * Spatial-only coherence analyzer for Ray[] arrays.
 * Implements TileBasedDispatcher.CoherenceAnalyzer interface.
 *
 * <p>Design Decision (DD-1): Uses direction similarity only, without DAG traversal.
 * This approach matches BeamTreeBuilder.computeCoherence() and avoids the type
 * incompatibility with RayCoherenceAnalyzer (which uses ESVORay[]).
 *
 * <p>Algorithm: Average dot product between reference ray direction and all other rays.
 * Returns value in [0.0, 1.0] where 1.0 = perfectly coherent (parallel rays).
 */
public class SimpleRayCoherenceAnalyzer implements TileBasedDispatcher.CoherenceAnalyzer {

    @Override
    public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
        // dag parameter ignored - spatial-only analysis
        if (rays == null || rays.length <= 1) {
            return 1.0;
        }
        var refDir = rays[0].direction();
        double totalSimilarity = 0.0;
        for (int i = 1; i < rays.length; i++) {
            totalSimilarity += Math.abs(refDir.dot(rays[i].direction()));
        }
        return totalSimilarity / (rays.length - 1);
    }
}
```

**Acceptance Criteria**:
- [ ] SimpleRayCoherenceAnalyzer implements CoherenceAnalyzer interface correctly
- [ ] NodeReductionComparator compiles and passes unit tests
- [ ] Virtual node counting documented in Javadoc comments
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
| `LargeFrameScene.java` | `render.benchmark.scenes` | ~70 | 4K stress test with memory docs |
| `SceneGeneratorTest.java` | `render.benchmark.scenes` (test) | ~150 | Validate scene properties |

**Package Structure**:
```
render/src/main/java/com/hellblazer/luciferase/render/benchmark/scenes/
  SkyScene.java
  GeometryScene.java
  MixedScene.java
  CameraMovementScene.java
  LargeFrameScene.java          <- With memory documentation

render/src/test/java/com/hellblazer/luciferase/render/benchmark/scenes/
  SceneGeneratorTest.java
```

**LargeFrameScene Memory Documentation**:
```java
/**
 * 4K resolution stress test scene (3840x2160 = 8,294,400 rays).
 *
 * <p><b>MEMORY REQUIREMENTS</b>: This scene allocates approximately:
 * <ul>
 *   <li>Ray array: ~400MB (8.3M rays * 48 bytes per Ray)</li>
 *   <li>BeamTree nodes: Additional 200-600MB depending on coherence</li>
 *   <li>Total: ~600MB-1GB peak memory usage</li>
 * </ul>
 *
 * <p><b>JVM CONFIGURATION</b>: Run tests using this scene with minimum 1GB heap:
 * <pre>
 *   mvn test -pl render -Dtest=Phase5a5BenchmarkTest -DargLine='-Xmx1g'
 * </pre>
 *
 * @see Phase5a5BenchmarkTest#testLargeFrameDispatchOverhead()
 */
public class LargeFrameScene extends SceneGenerator {
    // ...
}
```

**Acceptance Criteria**:
- [ ] Each scene generates rays with expected coherence patterns
- [ ] SkyScene: all tiles coherence >= 0.9
- [ ] GeometryScene: all tiles coherence <= 0.4
- [ ] MixedScene: configurable ratio works correctly
- [ ] LargeFrameScene includes memory documentation
- [ ] Scenes produce valid Ray[] arrays

### Phase 3: Benchmarking Framework (Day 2, Afternoon)

**Files to Create**:

| File | Package | LOC | Description |
|------|---------|-----|-------------|
| `Phase5a5BenchmarkRunner.java` | `render.benchmark` | ~200 | Orchestrates benchmark runs |
| `BenchmarkResult.java` | `render.benchmark` | ~60 | Result data structure |
| `BenchmarkConfig.java` | `render.benchmark` | ~40 | Configuration for runs |
| `Phase5a5BenchmarkTest.java` | `render.benchmark` (test) | ~350 | Main integration test suite |

**Note**: Follow conventions from `esvo/performance/ESVOPerformanceBenchmark.java`:
- Use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` for shared setup
- Use SLF4J logging for benchmark output
- Define constants for ray counts, iterations, etc.

**Package Structure**:
```
render/src/main/java/com/hellblazer/luciferase/render/benchmark/
  Phase5a5BenchmarkRunner.java
  BenchmarkResult.java
  BenchmarkConfig.java

render/src/test/java/com/hellblazer/luciferase/render/benchmark/
  Phase5a5BenchmarkTest.java
```

**Test Suite (16 tests)**:
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
11. `testLargeFrameDispatchOverhead()` - <5% overhead **[@Tag("memory-intensive")]**
12. `testLargeFrameMemoryStability()` - No OOM **[@Tag("memory-intensive")]**
13. `testCoherenceThresholdSensitivity()` - Threshold tuning
14. `testNodeCountConsistency()` - Reproducible results
15. `testEdgeCaseSingleTile()` - Single tile frame
16. `testEdgeCaseOddDimensions()` - Non-divisible frame size

**Memory-Intensive Test Annotations**:
```java
@Tag("memory-intensive")
@Test
void testLargeFrameDispatchOverhead() {
    // NOTE: Requires -Xmx1g JVM heap
    // See LargeFrameScene Javadoc for memory details
    var scene = new LargeFrameScene();
    // ...
}

@Tag("memory-intensive")
@Test
void testLargeFrameMemoryStability() {
    // NOTE: Requires -Xmx1g JVM heap
    // Verifies no OOM during 4K scene processing
    // ...
}
```

**Acceptance Criteria**:
- [ ] All 16 tests pass (with appropriate JVM flags for 4K tests)
- [ ] MixedScene achieves >= 30% node reduction (or documents why not)
- [ ] Benchmark results are reproducible
- [ ] Memory-intensive tests properly tagged

### Phase 4: Statistical Validation (Day 3, Morning)

**Files to Create**:

| File | Package | LOC | Description |
|------|---------|-----|-------------|
| `CoherenceCorrelationAnalyzer.java` | `render.benchmark` | ~150 | Statistical analysis with Spearman backup |
| `CorrelationResult.java` | `render.benchmark` | ~50 | Analysis result record (includes Spearman) |
| `TileExecutionRecord.java` | `render.benchmark` | ~30 | Per-tile data point |
| `CoherenceCorrelationTest.java` | `render.benchmark` (test) | ~100 | Validate statistics |

**Spearman Correlation Implementation**:
```java
/**
 * Compute Spearman rank correlation as backup for non-normal distributions.
 *
 * <p>Spearman correlation is computed when Pearson p-value > 0.05, indicating
 * the data may not satisfy normality assumptions required for Pearson.
 *
 * @param coherenceScores coherence values [0,1]
 * @param executionTimes execution times in nanoseconds
 * @return Spearman rho correlation coefficient [-1, 1]
 */
private double computeSpearmanCorrelation(double[] coherenceScores, double[] executionTimes) {
    // Convert values to ranks, then compute Pearson on ranks
    var coherenceRanks = toRanks(coherenceScores);
    var timeRanks = toRanks(executionTimes);
    return computePearson(coherenceRanks, timeRanks);
}
```

**Acceptance Criteria**:
- [ ] Pearson correlation coefficient computed correctly
- [ ] Spearman correlation computed as backup (when Pearson p > 0.05)
- [ ] r >= 0.5 correlation between coherence and speed (Pearson or Spearman)
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
   - Node Reduction Measurement (with virtual node explanation)
   - Statistical Analysis (Pearson + Spearman)
3. Results by Scenario
4. Statistical Analysis
5. 30% Target Assessment
6. Recommendations
7. Appendix: Raw Data
8. Appendix: Build Configuration

---

## Build & Test Configuration

### Standard Development Testing

```bash
# Run all Phase 5a.5 tests (excludes memory-intensive)
mvn test -pl render -Dtest=**/benchmark/**Test

# Run specific test class
mvn test -pl render -Dtest=Phase5a5BenchmarkTest

# Run with verbose output
mvn test -pl render -Dtest=Phase5a5BenchmarkTest -DVERBOSE_TESTS=true
```

### Memory-Intensive Testing (4K Scenes)

```bash
# Run 4K tests with adequate heap (REQUIRED for LargeFrameScene)
mvn test -pl render -Dtest=Phase5a5BenchmarkTest -DargLine='-Xmx1g'

# Run only memory-intensive tests
mvn test -pl render -Dgroups=memory-intensive -DargLine='-Xmx1g'

# Skip memory-intensive tests in resource-constrained environments
mvn test -pl render -DexcludedGroups=memory-intensive
```

### CI/CD Configuration

**GitHub Actions**: Memory-intensive tests should be tagged and handled appropriately:

```yaml
# In test batch configuration (if not already present)
- name: Run render module tests
  run: |
    mvn test -pl render -DexcludedGroups=memory-intensive

- name: Run memory-intensive tests (separate job with more resources)
  run: |
    mvn test -pl render -Dgroups=memory-intensive -DargLine='-Xmx1g'
```

**Alternative**: If CI runners have sufficient memory (2GB+), run all tests together:
```bash
mvn test -pl render -DargLine='-Xmx1g'
```

### Memory Requirements Summary

| Scene | Rays | Memory (Ray[]) | Memory (Total) | JVM Flag |
|-------|------|----------------|----------------|----------|
| SkyScene (256x256) | 65,536 | ~3MB | ~10MB | Default |
| GeometryScene (256x256) | 65,536 | ~3MB | ~10MB | Default |
| MixedScene (256x256) | 65,536 | ~3MB | ~10MB | Default |
| CameraMovementScene | 65,536 x 10 | ~30MB | ~100MB | Default |
| **LargeFrameScene (4K)** | **8,294,400** | **~400MB** | **~800MB** | **-Xmx1g** |

---

## Success Criteria

### Must Have (P0)
- [ ] SimpleRayCoherenceAnalyzer implements CoherenceAnalyzer interface
- [ ] All 16 integration tests passing (with appropriate JVM flags)
- [ ] 30% node reduction validated for MixedScene (or documented why not achievable)
- [ ] Coherence-speed correlation demonstrated (r >= 0.5, Pearson or Spearman)
- [ ] Tile dispatch overhead < 5% of frame time
- [ ] Live metrics confirm dispatch routing matches expected behavior

### Should Have (P1)
- [ ] Camera movement validation (coherence cache working)
- [ ] 4K stress test passes without OOM (with -Xmx1g)
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
| TileBasedDispatcher.CoherenceAnalyzer | `render/tile/TileBasedDispatcher.java:29` | VERIFIED |
| TileConfiguration | `render/tile/TileConfiguration.java` | VERIFIED |
| DispatchMetrics | `render/tile/DispatchMetrics.java` | VERIFIED |
| BeamTree | `esvo/gpu/beam/BeamTree.java` | VERIFIED |
| BeamTreeBuilder | `esvo/gpu/beam/BeamTreeBuilder.java` | VERIFIED |
| Ray (beam) | `esvo/gpu/beam/Ray.java` | VERIFIED |
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
| Node counting methodology unclear | LOW | LOW | Validate against BeamTree.getStatistics().totalBeams(), document virtual nodes |
| Statistical noise obscures correlation | MEDIUM | LOW | Use large sample size (25,600 data points), aggregate by bands, Spearman backup |
| Camera invalidation timing off | LOW | LOW | Unit tests for TileCoherenceMap.invalidate() |
| Performance regression from metrics | LOW | MEDIUM | Already validated <1ms overhead in Phase 5a.4 |
| 4K test OOM in CI | MEDIUM | MEDIUM | @Tag("memory-intensive"), -Xmx1g flag, CI configuration |

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
2. Use Spearman correlation (already implemented as backup)
3. Report confidence intervals instead of point estimates
4. Document correlation direction even if r < 0.5

---

## Day-by-Day Timeline

### Day 1

**Morning (4 hours)**:
- Create `render/benchmark/` package structure
- Implement `SimpleRayCoherenceAnalyzer.java` (NEW - addresses audit #1)
- Implement `NodeReductionComparator.java` (with virtual node docs)
- Implement `SceneGenerator.java` (abstract base)
- Implement `TestSceneFactory.java`
- Write `NodeReductionComparatorTest.java`
- **Checkpoint**: SimpleRayCoherenceAnalyzer and NodeReductionComparator tests passing

**Afternoon (4 hours)**:
- Implement `SkyScene.java`
- Implement `GeometryScene.java`
- Implement `MixedScene.java`
- Write initial `SceneGeneratorTest.java`
- **Checkpoint**: Scene generators produce valid ray arrays

### Day 2

**Morning (4 hours)**:
- Implement `CameraMovementScene.java`
- Implement `LargeFrameScene.java` (with memory docs - addresses audit #2)
- Complete `SceneGeneratorTest.java`
- Run initial node reduction measurements
- **Checkpoint**: All 5 scenes generating correct rays

**Afternoon (4 hours)**:
- Implement `Phase5a5BenchmarkRunner.java`
- Implement `BenchmarkResult.java` and `BenchmarkConfig.java`
- Start `Phase5a5BenchmarkTest.java` (first 8 tests)
- Add @Tag("memory-intensive") to 4K tests
- **Checkpoint**: Basic benchmark infrastructure working

### Day 3

**Morning (4 hours)**:
- Complete `Phase5a5BenchmarkTest.java` (tests 9-16)
- Implement `CoherenceCorrelationAnalyzer.java` (with Spearman backup)
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

### Production Code (~880 LOC)

| File | LOC | Purpose |
|------|-----|---------|
| `SimpleRayCoherenceAnalyzer.java` | 80 | **NEW**: Spatial-only coherence analyzer |
| `NodeReductionComparator.java` | 150 | Core comparison logic |
| `SceneGenerator.java` | 100 | Abstract scene base |
| `TestSceneFactory.java` | 50 | Scene factory |
| `SkyScene.java` | 60 | High coherence scene |
| `GeometryScene.java` | 80 | Low coherence scene |
| `MixedScene.java` | 100 | Target validation scene |
| `CameraMovementScene.java` | 80 | Cache validation scene |
| `LargeFrameScene.java` | 70 | 4K stress test (with memory docs) |
| `Phase5a5BenchmarkRunner.java` | 200 | Benchmark orchestration |
| `BenchmarkResult.java` | 60 | Result data |
| `BenchmarkConfig.java` | 40 | Configuration |
| `CoherenceCorrelationAnalyzer.java` | 150 | Statistics (with Spearman) |
| `CorrelationResult.java` | 50 | Analysis result record |
| `TileExecutionRecord.java` | 30 | Per-tile data point |

### Test Code (~700 LOC)

| File | LOC | Tests |
|------|-----|-------|
| `NodeReductionComparatorTest.java` | 100 | 5 tests |
| `SceneGeneratorTest.java` | 150 | 10 tests |
| `Phase5a5BenchmarkTest.java` | 350 | 16 tests |
| `CoherenceCorrelationTest.java` | 100 | 5 tests |

**Total Tests**: 36 tests across 4 test classes

### Documentation

| File | Lines |
|------|-------|
| `PHASE_5A5_RESULTS.md` | ~300 |

---

## Appendix A: Audit Condition Resolution

### REQUIRED #1: CoherenceAnalyzer Type Mismatch

**Resolution**: Added `SimpleRayCoherenceAnalyzer.java` (~80 LOC) to Phase 1.

**Details**:
- Implements `TileBasedDispatcher.CoherenceAnalyzer` interface
- Takes `Ray[]` array as input (not ESVORay[])
- Uses direction-only similarity (dot product)
- No DAG traversal required
- Algorithm adapted from `BeamTreeBuilder.computeCoherence()`

**Design Decision**: Documented as DD-1 at top of plan.

### REQUIRED #2: LargeFrameScene Memory Configuration

**Resolution**: Added comprehensive memory documentation and test annotations.

**Details**:
1. Added `@Tag("memory-intensive")` to `testLargeFrameDispatchOverhead()` and `testLargeFrameMemoryStability()`
2. Added memory documentation to LargeFrameScene Javadoc (~400MB ray array, ~800MB total)
3. Added new "Build & Test Configuration" section with:
   - Standard Maven commands
   - Memory-intensive test commands with `-Xmx1g`
   - CI/CD configuration recommendations
4. Added memory requirements table

### RECOMMENDED #3: Virtual Node Counting Documentation

**Resolution**: Added explicit documentation in NodeReductionComparator and throughout plan.

**Details**:
- ComparisonResult record includes Javadoc explaining virtual nodes
- Node reduction formula explicitly shows `+ COUNT(low_coherence_tiles) * 1`
- Rationale documented in DD-2 design decision

### RECOMMENDED #4: Spearman Correlation Backup

**Resolution**: Added Spearman rank correlation to CoherenceCorrelationAnalyzer.

**Details**:
- CorrelationResult record includes `spearmanRho` and `spearmanPValue` fields
- `recommendedCorrelation()` method returns Spearman if Pearson p > 0.05
- Implementation sketch included in Phase 4 specification

### RECOMMENDED #5: Review Existing Benchmark Patterns

**Resolution**: Added reference to ESVOPerformanceBenchmark.java conventions.

**Details**:
- Phase 3 includes note to follow conventions from `esvo/performance/ESVOPerformanceBenchmark.java`
- Specifically: `@TestInstance(Lifecycle.PER_CLASS)`, SLF4J logging, constants for parameters

---

## Handoff to plan-auditor

This revised plan addresses all audit conditions. Key validation points:

1. **REQUIRED #1**: SimpleRayCoherenceAnalyzer added to Phase 1 (~80 LOC)
2. **REQUIRED #2**: @Tag("memory-intensive"), -Xmx1g flag, Build & Test section added
3. **RECOMMENDED #3**: Virtual node counting documented in DD-2 and throughout
4. **RECOMMENDED #4**: Spearman correlation added to CoherenceCorrelationAnalyzer
5. **RECOMMENDED #5**: ESVOPerformanceBenchmark.java reference added

Please verify all conditions are satisfactorily addressed for final approval.

---

## References

- **Phase 5a.3 Implementation**: TileBasedDispatcher, TileConfiguration, DispatchMetrics
- **Phase 5a.4 Implementation**: GPUMetricsCollector, MetricsSnapshot, BeamMetricsOverlay
- **BeamTree Architecture**: BeamTree, BeamTreeBuilder, BeamNode
- **Existing Tests**: TileBasedDispatcherTest, BeamTreeBuilderTest patterns
- **Existing Benchmarks**: ESVOPerformanceBenchmark.java (conventions reference)
- **Audit Report**: `/Users/hal.hildebrand/git/Luciferase/render/doc/PHASE_5A5_PLAN_AUDIT_REPORT.md`
