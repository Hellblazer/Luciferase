# VoN AoI Baseline Benchmarks

**Last Updated**: 2026-05-23
**Status**: Current

This directory archives JMH baseline results for RDR-003 Phase 0. Each file is the
JMH native JSON output captured at a known code state, intended to be compared
against by later phases.

## simulation-von-aoi-baseline-2026-05.json

**Scope**: RDR-003 Phase 0 Steps 1 + 1b (beads `Luciferase-d8a` + `Luciferase-jp7`).

**What's measured**: `SpatialNeighborIndex.findWithinRadius(center, radius)` and
`findKNearest(center, k=10)` against the current linear-scan
`ConcurrentHashMap` implementation. Two query operations × the parameter matrix
below.

**Parameter matrix**:

| Param | Values | Rationale |
|---|---|---|
| `entityCount` | 1,000 / 10,000 / 100,000 | Spans the realistic VoN scale band (per RDR-003 §Validation §Performance Expectations). |
| `spatialLevel` | 10 / 18 | 10 = original / degenerate single-cell; 18 = corrected per Step 0. |
| `queryRadius` | 10 / 20 / 50 / 100 | Spans the observed VoN AoI radius distribution (research-003 — 10 covers RandomWalk/GridBoundary, 20–50 covers Flocking/Prey/Predator/Pack, 100 covers ClusterIntegrationTest). |
| `k` (findKNearest) | 10 (fixed) | Typical neighbor-count target in VoN protocols. |

24 combinations × 2 benchmarks = 48 measurements.

## Run environment

- **Hardware**: Apple M4 Max
- **OS**: Darwin 25.4.0 (macOS)
- **JVM**: Oracle GraalVM 25.0.1+8 (Java HotSpot 64-Bit Server VM, JVMCI mixed mode, sharing)
- **JMH**: 1.37
- **Mode**: `AverageTime`, units μs/op
- **Warmup**: 5 iterations × 1s
- **Measurement**: 10 iterations × 1s
- **Fork**: in-process (`@Fork(0)`) — see note below

### Why `@Fork(0)`

`mvn exec:java` does not propagate the test classpath to a forked JMH JVM
(`ClassNotFoundException: org.openjdk.jmh.runner.ForkedMain`). The benchmark runs
in-process. This is acceptable for relative comparisons across configurations on
the same JVM — which is the entire point of this baseline — but absolute numbers
are not directly comparable to forked-JVM benchmarks elsewhere.

## How to reproduce

```bash
cd /path/to/Luciferase
mvn -pl simulation test-compile  # generates META-INF/BenchmarkList
mvn -pl simulation exec:java \
  -Dexec.mainClass='com.hellblazer.luciferase.simulation.von.SpatialNeighborIndexBaselineBenchmark' \
  -Dexec.classpathScope=test \
  -Djmh.result=$(pwd)/simulation/doc/baselines/simulation-von-aoi-baseline-2026-05.json
```

Optional smoke (single config, short iterations):

```bash
mvn -pl simulation exec:java \
  -Dexec.mainClass='com.hellblazer.luciferase.simulation.von.SpatialNeighborIndexBaselineBenchmark' \
  -Dexec.classpathScope=test \
  -Dexec.args='-wi 1 -i 1 -p entityCount=1000 -p spatialLevel=18 -p queryRadius=50' \
  -Djmh.result=$(pwd)/simulation/target/jmh-smoke.json
```

## Empirical observation — Step 1b's "isolate the level contribution"

The RDR §Phase 0 Step 1b frames the corrected-level run as isolating the
"spatial-level contribution" from the "data-structure contribution" in Step 3's
differential analysis. The current `SpatialNeighborIndex` implementation
**does not consume the spatial level** in its query path — `findWithinRadius`
and `findKNearest` iterate a flat `ConcurrentHashMap` computing Euclidean
distance between `Point3D` positions. The spatial level only appears in the
`BubbleBounds` construction during setup, which JMH amortizes out.

### Headline findings (μs/op, AverageTime)

| Op | N | r | level=10 | level=18 | Δ |
|---|---|---|---|---|---|
| findKNearest | 1K | 50 | 103.7 ± 1.1 | 103.3 ± 1.2 | -0.4% |
| findKNearest | 10K | 50 | 1544 ± 32 | 1575 ± 24 | +2.0% |
| findKNearest | 100K | 50 | 20778 ± 349 | 21809 ± 446 | +5.0% |
| findWithinRadius | 1K | 50 | 7.71 ± 0.13 | 7.69 ± 0.12 | -0.2% |
| findWithinRadius | 10K | 50 | 94.4 ± 1.4 | 94.3 ± 1.9 | -0.1% |
| findWithinRadius | 100K | 50 | 1597 ± 51 | 1574 ± 57 | -1.4% |

Across all 24 `(N, r)` pairs in the matrix:

- `findKNearest` is dominated by the O(N) iteration + O(N log N) sort; radius and
  level do not enter the cost. Timings scale ~13× per 10× N (matches N log N).
- `findWithinRadius` is dominated by the O(N) iteration, with mild result-list
  cost as radius grows. Timings scale ~10× per 10× N (matches O(N)).
- **Level effect**: at fixed `(N, r)`, the level=10 vs level=18 gap is within
  ±5% — JMH `@Fork(0)` in-process noise band. Two outliers (`findWithinRadius`
  at N=100K with r=20: 1493 vs 1871, +25%; and at N=10K with r=10:
  69 vs 80, +15%) live in iterations with already-large error bars (±189
  and ±8 respectively) and are attributable to GC / cache-residency variation
  across iterations in the shared JVM, not to the spatial level. The level is
  empirically irrelevant to the query path, as expected from inspection.

### Why this matters for Step 3

The Step 3 differential `(Tetree-backed at level 18) / (linear-scan at level 18)`
isolates the data-structure contribution with no level-fix confound — both
halves of the ratio are at level 18. The level-10 rows in this baseline confirm
empirically that no level-fix confound *could* arise from the linear-scan side
of the ratio: changing the level alone in the current implementation produces
no measurable query-time delta.

## How to use this baseline for Step 3 (Tetree-backed) comparison

When Step 2 lands (`SpatialNeighborIndex` internals replaced with Tetree), re-run
this benchmark unchanged. The new JSON (named e.g. `simulation-von-aoi-tetree-2026-05.json`)
should be a drop-in comparison: same benchmark methods, same `@Param` matrix.
Per-row ratio against this file gives the Tetree's intrinsic speedup; the Step 4
go/no-go criterion (p99 latency / aggregate throughput vs simulation AoI budget)
reads off this comparison directly.
