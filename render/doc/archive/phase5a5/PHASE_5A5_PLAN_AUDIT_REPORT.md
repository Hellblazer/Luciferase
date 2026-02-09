# Phase 5a.5 Implementation Plan - Audit Report

**Auditor**: plan-auditor
**Plan Document**: `/Users/hal.hildebrand/git/Luciferase/render/doc/PHASE_5A5_IMPLEMENTATION_PLAN.md`
**Bead**: Luciferase-4i5z (P0)
**Audit Date**: 2026-01-23

---

## Executive Summary

**RECOMMENDATION: GO - WITH CONDITIONS**

The Phase 5a.5 implementation plan is well-structured, comprehensive, and technically sound. All hard dependencies are verified to exist in the codebase. The methodology for measuring node reduction is appropriate, and the statistical validation approach is rigorous. However, several clarifications and one design decision are required before implementation.

---

## Audit Checklist

### 1. Dependency Verification

| Dependency | Expected Location | Status | Notes |
|------------|-------------------|--------|-------|
| TileBasedDispatcher | `render/tile/TileBasedDispatcher.java` | VERIFIED | 284 lines, complete |
| TileConfiguration | `render/tile/TileConfiguration.java` | VERIFIED | 78 lines, record type |
| DispatchMetrics (tile) | `render/tile/DispatchMetrics.java` | VERIFIED | Has dispatchTimeNs |
| DispatchMetrics (metrics) | `esvo/gpu/beam/metrics/DispatchMetrics.java` | VERIFIED | Different API |
| BeamTree | `esvo/gpu/beam/BeamTree.java` | VERIFIED | Has getStatistics() |
| BeamTreeBuilder | `esvo/gpu/beam/BeamTreeBuilder.java` | VERIFIED | 397 lines |
| BeamNode | `esvo/gpu/beam/BeamNode.java` | VERIFIED | Has getCoherenceScore() |
| GPUMetricsCollector | `esvo/gpu/beam/metrics/GPUMetricsCollector.java` | VERIFIED | 129 lines |
| MetricsSnapshot | `esvo/gpu/beam/metrics/MetricsSnapshot.java` | VERIFIED | Record type |
| MetricsAggregator | `esvo/gpu/beam/metrics/MetricsAggregator.java` | VERIFIED | Thread-safe |
| CoherenceSnapshot | `esvo/gpu/beam/metrics/CoherenceSnapshot.java` | VERIFIED | Has totalBeams |
| KernelExecutor | `render/tile/KernelExecutor.java` | VERIFIED | Interface with 3 methods |
| TileCoherenceMap | `render/tile/TileCoherenceMap.java` | VERIFIED | 187 lines |
| Tile | `render/tile/Tile.java` | VERIFIED | Record type |
| CoherenceStatistics | `render/tile/CoherenceStatistics.java` | VERIFIED | Used by TileCoherenceMap |
| Ray (beam) | `esvo/gpu/beam/Ray.java` | VERIFIED | Record type |
| RayCoherenceAnalyzer | `esvo/gpu/beam/RayCoherenceAnalyzer.java` | VERIFIED | Uses ESVORay[] |

**Result**: All 17 dependencies verified.

---

### 2. Methodology Assessment

#### Node Reduction Measurement

**Assessment**: SOUND

The methodology correctly identifies that:
1. Global BeamTree built from all rays provides baseline node count
2. Per-tile BeamTrees (for high-coherence tiles) + virtual nodes (for low-coherence tiles) provides comparison
3. Reduction = 1 - (tiled_nodes / global_nodes)

**Verified API**:
- `BeamTree.getStatistics().totalBeams()` - Returns integer count of beam nodes
- `BeamTreeBuilder.from(rays).build()` - Creates tree from Ray[] array
- `TileBasedDispatcher.partitionIntoTiles()` - Package-private but accessible in tests

**Potential Issue**: The plan counts "1 virtual node" for low-coherence tiles. This is logically correct but should be documented clearly - the reduction formula assumes low-coherence tiles contribute 1 node each (representing single-ray kernel overhead).

#### Statistical Validation

**Assessment**: APPROPRIATE

- Pearson correlation coefficient is appropriate for continuous variables
- Sample size (25,600 data points) is sufficient for statistical power
- Grouping by coherence bands (LOW/MEDIUM/HIGH) is valid for trend analysis
- Target r >= 0.5 is reasonable for moderate correlation

**Suggestion**: Add Spearman rank correlation as a backup if data is non-normal.

---

### 3. Technical Gaps Identified

#### GAP 1: CoherenceAnalyzer Type Mismatch (CRITICAL)

**Issue**: TileBasedDispatcher uses `CoherenceAnalyzer` interface with signature:
```java
double analyzeCoherence(Ray[] rays, DAGOctreeData dag);
```

But the existing `RayCoherenceAnalyzer` uses:
```java
double analyzeCoherence(ESVORay[] rays, DAGOctreeData dag);
```

`Ray` (from beam package) and `ESVORay` are incompatible types.

**Resolution Options**:
1. **Create adapter**: Implement CoherenceAnalyzer that converts Ray[] to ESVORay[]
2. **Use spatial-only analysis**: BeamTreeBuilder's computeCoherence() works with Ray[] directly, using spatial/directional similarity without DAG traversal
3. **Create simplified analyzer**: New class that analyzes Ray[] coherence without DAG

**Recommendation**: Use option 2 or 3 for Phase 5a.5. The DAG-aware analysis in RayCoherenceAnalyzer is heavyweight and not required for tile-level coherence measurement. Document this design decision.

**Impact**: LOW - Tests currently use mock analyzers; benchmark infrastructure can use spatial-only analysis.

#### GAP 2: Package Structure Decision

**Issue**: Plan proposes new package `render.benchmark` but existing benchmarks are scattered:
- `render/src/test/java/.../esvo/performance/` - JMH benchmarks
- `render/src/main/java/.../esvo/validation/` - Validation benchmarks
- `render/src/main/java/.../esvt/validation/` - ESVT benchmarks

**Recommendation**: Place Phase 5a.5 benchmark code in `render.benchmark` as planned, but ensure it follows existing patterns (JUnit-based tests in test tree, not JMH).

**Impact**: LOW - Organizational only.

#### GAP 3: 4K Memory Estimation Missing

**Issue**: LargeFrameScene (4K) creates 8,294,400 rays. Memory estimate needed.

**Calculation**:
- Each Ray: ~48 bytes (Point3f + Vector3f + object overhead)
- 8.3M rays * 48 bytes = ~400 MB for ray array alone
- BeamTree overhead: Variable, but could be 2-3x for nodes

**Recommendation**: Add memory constraint check or `-Xmx` requirement to test configuration.

**Impact**: MEDIUM - Could cause OOM in CI without proper configuration.

---

### 4. Risk Assessment Validation

| Risk | Plan Assessment | Auditor Assessment | Notes |
|------|-----------------|-------------------|-------|
| 30% target not achievable | MEDIUM/MEDIUM | **AGREE** | Empirical validation required |
| Node counting unclear | LOW/LOW | **AGREE** | API verified |
| Statistical noise | MEDIUM/LOW | **AGREE** | Sample size adequate |
| Camera invalidation | LOW/LOW | **AGREE** | TileCoherenceMap.invalidate() exists |
| Metrics overhead | LOW/MEDIUM | **AGREE** | Already validated |

**Additional Risk Identified**:

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Ray type mismatch delays implementation | MEDIUM | LOW | Clarify in pre-implementation; use spatial-only analysis |
| 4K test OOM in CI | MEDIUM | MEDIUM | Add memory configuration or skip in low-memory CI |

---

### 5. Timeline Assessment

**Plan**: 3 days (24 hours of work)
**Auditor Assessment**: REALISTIC but TIGHT

**Breakdown Analysis**:

| Phase | Planned | Auditor Estimate | Risk |
|-------|---------|------------------|------|
| Phase 1: Node Reduction | 4h | 4h | LOW |
| Phase 2: Scenes (5 classes) | 8h | 6-8h | LOW |
| Phase 3: Benchmark Framework | 4h | 5-6h | MEDIUM |
| Phase 4: Statistical Validation | 4h | 4-5h | LOW |
| Phase 5: Documentation | 4h | 3h | LOW |

**Total**: 24h planned vs 22-26h estimated

**Recommendation**: Plan is achievable in 3 days. Build in 4h buffer for debugging/iteration. If 30% target not met on first measurement, budget additional analysis time.

---

### 6. Test Coverage Assessment

**Planned Tests**: 36 tests across 4 test classes

| Test Class | Test Count | Coverage |
|------------|------------|----------|
| NodeReductionComparatorTest | 5 | Core comparison logic |
| SceneGeneratorTest | 10 | All 5 scene types |
| Phase5a5BenchmarkTest | 16 | Integration scenarios |
| CoherenceCorrelationTest | 5 | Statistical validation |

**Assessment**: ADEQUATE

**Suggestions**:
1. Add negative test for invalid configuration (tile size > frame size)
2. Add test for reproducibility (same rays = same node count)
3. Consider parameterized tests for different tile sizes

---

### 7. Build/Test Command Validation

**Standard Maven Commands**:
- `mvn test -pl render` - Run render module tests
- `mvn test -pl render -Dtest=Phase5a5BenchmarkTest` - Run specific test class

**Verified**: Commands follow project patterns in CLAUDE.md.

**Note**: No special JVM flags required for tests (unless 4K test needs `-Xmx1g` or higher).

---

## Conditions for GO

Before implementation proceeds, address the following:

### REQUIRED (Must Do)

1. **Clarify CoherenceAnalyzer implementation** (GAP 1)
   - Document whether to use spatial-only analysis or create Ray-to-ESVORay adapter
   - Recommendation: Spatial-only analysis (simpler, sufficient for tiles)

2. **Add memory configuration for LargeFrameScene** (GAP 3)
   - Add `@Tag("memory-intensive")` and skip in low-memory CI
   - OR document minimum `-Xmx` requirement

### RECOMMENDED (Should Do)

3. **Document virtual node counting**
   - Add clarifying comment in NodeReductionComparator that low-coherence tiles count as 1 node

4. **Add Spearman correlation as backup**
   - If Pearson correlation p-value > 0.05, report Spearman instead

5. **Consider existing benchmark patterns**
   - Review `esvo/performance/ESVOPerformanceBenchmark.java` for conventions

---

## Final Verdict

| Criterion | Status |
|-----------|--------|
| Dependencies verified | PASS |
| Methodology sound | PASS |
| Technical gaps acceptable | PASS (with conditions) |
| Risks identified and manageable | PASS |
| Timeline realistic | PASS |
| Test coverage adequate | PASS |

**DECISION: GO - WITH CONDITIONS**

Implement Phase 5a.5 after addressing the two REQUIRED conditions above. The plan is comprehensive, well-structured, and the 30% node reduction validation approach is technically sound.

---

## Appendix: Verified Code Snippets

### BeamTree Statistics API
```java
// From BeamTree.java
public TreeStatistics getStatistics() {
    return new TreeStatistics(totalBeams, averageCoherence, maxDepth);
}

public record TreeStatistics(int totalBeams, double averageCoherence, int maxDepth) { ... }
```

### TileCoherenceMap Invalidation
```java
// From TileCoherenceMap.java
public void invalidate() {
    for (int x = 0; x < config.tilesX(); x++) {
        for (int y = 0; y < config.tilesY(); y++) {
            coherenceScores[x][y] = defaultCoherence;
            sampleCounts[x][y] = 0;
        }
    }
}
```

### Ray Type (beam package)
```java
// From Ray.java - compatible with BeamTreeBuilder
public record Ray(Point3f origin, Vector3f direction) {
    public float directionDifference(Ray other) {
        var dot = this.direction.dot(other.direction);
        return Math.max(0f, 1f - dot);
    }
}
```

---

**Audit Complete**
