# CI Performance Metrics

**Last Updated**: 2026-01-13
**Purpose**: Track GitHub Actions workflow performance over time

---

## Current Performance (Parallel Workflow)

### Overall Metrics

| Metric | Value | Baseline | Improvement |
|--------|-------|----------|-------------|
| **Total Runtime** | 9-12 minutes | 20-30+ minutes | **3-4x faster** |
| **Compile Time** | 54 seconds | 10-12 minutes | **12-13x faster** |
| **Longest Test Batch** | 8-9 minutes | N/A (sequential) | Parallelized |
| **Feedback Loop** | ~12 minutes | ~25 minutes | **52% reduction** |

### Workflow Architecture

**Job Structure**:

- 1 compile job (caches to SHA-specific key)
- 6 parallel test jobs (restore from compile cache)
- 1 aggregator job (collects results)

**Total Jobs**: 8 (7 execution + 1 status)

---

## Recent CI Runs (Commits)

### Run 1/5: commit 94e31da (2026-01-13)

**Title**: Fix CI: Remove non-existent modules from test-other-modules job
**Result**: ✅ PASS
**Total Runtime**: ~12 minutes

```text
✓ compile:             54s
✓ test-batch-1:        1m7s   (bubble/behavior/metrics)
✓ test-batch-2:        8m42s  (von/transport) ← longest pole
✓ test-batch-3:        4m31s  (causality/migration)
✓ test-batch-4:        8m28s  (distributed)
✓ test-batch-5:        48s    (consensus/ghost)
✓ test-other-modules:  3m15s  (grpc/common/lucien/sentry/render/portal/dyada-java)
✓ build-status:        2s
```text

**Notes**: First clean run after fixing non-existent module references (von, e2e-test, gpu-test-framework)

---

### Run 2/5: commit 8908d03 (2026-01-13)

**Title**: Document parallel CI workflow implementation in TDR
**Result**: ✅ PASS
**Total Runtime**: ~12 minutes

```text
✓ compile:             54s
✓ test-batch-1:        46s    (bubble/behavior/metrics)
✓ test-batch-2:        8m41s  (von/transport) ← longest pole
✓ test-batch-3:        4m28s  (causality/migration)
✓ test-batch-4:        8m22s  (distributed)
✓ test-batch-5:        55s    (consensus/ghost)
✓ test-other-modules:  3m29s  (grpc/common/lucien/sentry/render/portal/dyada-java)
✓ build-status:        3s
```text

**Notes**: Consistent performance with Run 1/5

---

## Performance Analysis

### Compile Job Optimization

**Key Improvement**: Reordered Maven repositories to place Maven Central first (commit 20f6664)

**Impact**:

- **Before**: 10-12 minutes (GitHub Packages dependency resolution timeouts)
- **After**: 54 seconds
- **Speedup**: 12-13x

**Root Cause**: Maven checked GitHub Packages repositories for every dependency before falling back to Maven Central, causing 5-10+ minute timeouts for commons, Jakarta EE, and standard Java dependencies.

**Solution**: Placed Maven Central first in `pom.xml` repository list:

```xml
<repositories>
  <repository>
    <id>central</id>
    <url>https://repo.maven.apache.org/maven2</url>
  </repository>
  <!-- GitHub Packages repositories after -->
</repositories>
```text

### Test Batch Distribution

**Longest Poles** (determine overall runtime):

1. **test-batch-2**: 8m41s (von/transport integration tests)
2. **test-batch-4**: 8m22s (distributed systems/network/Delos)

**Fast Batches**:

1. **test-batch-1**: 46s-1m7s (unit tests)
2. **test-batch-5**: 48s-55s (consensus/ghost)

**Optimization Opportunity**: Split batches 2 and 4 to reduce longest pole to 5-6 minutes, targeting 8-9 minute total runtime.

### Cache Performance

**Strategy**: SHA-specific cache keys with multi-level fallback

```yaml
key: luciferase-maven-${{ github.sha }}
restore-keys: |
  luciferase-maven-${{ github.ref }}-
  luciferase-maven-refs/heads/main-
  luciferase-maven-
```text

**Cache Hit Rate**: ~100% (test jobs restore from compile job)
**Cache Contents**:

- `~/.m2/repository` (Maven dependencies)
- `**/target/` (compiled classes and test classes)

**Benefits**:

- Test jobs skip compilation entirely
- Deterministic builds per commit
- Graceful fallback if cache misses

---

## Historical Performance (Pre-Parallel)

### Sequential Workflow (Before commit 3d143a7)

**Total Runtime**: 20-30+ minutes

- Compile: 10-12 minutes (GitHub Packages timeouts)
- Tests (sequential): 10-15 minutes
- No parallelization

**Pain Points**:

- Slow feedback loop (25+ minutes)
- Context switching during wait
- Sprint A required 2-2.5 hours for 5 consecutive runs

---

## Trends and Observations

### Consistency

**Compile Time**: Highly consistent at 54s (±5s variance)

- Indicates stable dependency resolution
- Maven Central reordering eliminated timeout variability

**Test Batch Variance**:

- **Fast batches** (1, 5): ±10s variance (acceptable)
- **Long batches** (2, 4): ±20s variance (within expected range)
- **Medium batches** (3, other-modules): ±15s variance

**Total Runtime**: 11-13 minutes typical range

### Bottlenecks

1. **Longest Test Batches**: Batches 2 and 4 at 8-9 minutes determine total runtime
2. **No Current Bottlenecks**: Compile cache strategy working well
3. **Optimization Headroom**: Can achieve 8-9 min total by splitting long batches

---

## Benchmarking Methodology

### Tracking New Runs

When adding new CI run data:

1. **Record commit SHA and title**
2. **Capture all 7 job runtimes** from `gh run view <run-id>`
3. **Note longest pole** (determines total runtime)
4. **Document any anomalies** (cache misses, flaky tests, timeouts)
5. **Update trends section** if patterns change

### Performance Regression Detection

**Red Flags**:

- Compile time > 2 minutes (investigate dependency resolution)
- Any batch > 10 minutes (investigate test slowdown or flakiness)
- Total runtime > 15 minutes (investigate parallelization or cache issues)

**Investigation Steps**:

1. Check for new dependencies causing compile slowdown
2. Review test logs for hung tests or infinite loops
3. Verify cache hit rate (test jobs should restore from compile)
4. Check GitHub Actions runner capacity (timeouts may indicate resource contention)

---

## Future Optimizations

### Short-Term (1-2 weeks)

**Target**: 8-9 minute total runtime

**Actions**:

1. Split test-batch-2 into:
   - von tests (~4 min)
   - transport/integration tests (~4 min)

2. Split test-batch-4 into:
   - distributed/network tests (~4 min)
   - Delos integration tests (~4 min)

**Expected Result**:

- 8 test batches (was 6)
- Longest pole: 5-6 minutes
- Total runtime: 8-9 minutes

### Medium-Term (1-2 months)

**Test Categorization**:

- Tag tests: `@Fast`, `@Integration`, `@Slow`
- Enable selective execution for quick feedback
- Full suite for main branch, fast tests for PRs

**Performance Tracking**:

- Instrument workflow to log runtimes to database/file
- Automated alerts on regressions
- Trend visualization dashboard

### Long-Term (3-6 months)

**Intelligent Test Selection**:

- Run affected tests first based on changed files
- Full suite nightly or on-demand
- Further reduce feedback loop for isolated changes

**Resource Optimization**:

- Right-size GitHub Actions runners (small for fast batches, standard for long)
- Artifact caching (JAR files separate from compiled classes)
- Parallel compile (multi-module compilation)

---

## Sprint A Context

### Goal

Achieve 5 consecutive clean CI runs to validate test stability.

### Progress

- **Run 1/5**: commit 94e31da ✅ PASS (2026-01-13)
- **Run 2/5**: commit 8908d03 ✅ PASS (2026-01-13)
- **Run 3/5**: Not documented
- **Run 4/5**: Not documented
- **Run 5/5**: Not documented

### Status

⚠️ **Sprint A Tracking Incomplete**: Runs 3-5 were not documented. As of 2026-02-08, it's unclear if these runs were completed or if the sprint goal was superseded by other work. This goal should either be completed and documented, or formally closed.

### Historical Attempts

**Original Sprint A Sequence** (before parallel workflow):

- Run 1/5: ✅ PASS (TOCTTOU fix)
- Run 2/5: ✅ PASS (Cache fix)
- Run 3/5: ❌ FAIL (Flaky concurrency test)
- Run 4/5: ❌ FAIL (Same flaky test)
- Run 5/5: ✅ PASS (intermittent)

**Outcome**: Only 1 consecutive clean run, restarted Sprint A

**Restart Sequence** (with parallel workflow):

- Run 1/5: ✅ PASS (Module fix)
- Run 2/5: ✅ PASS (TDR documentation)
- In progress...

---

## References

- **Parallel Workflow TDR**: `simulation/doc/TECHNICAL_DECISION_PARALLEL_CI.md`
- **Delos Pattern**: `~/.../Delos/.github/workflows/maven.yml`
- **Workflow File**: `.github/workflows/maven.yml`
- **Sprint A Epic**: Luciferase-k91e

---

## Change Log

| Date | Event | Impact |
|------|-------|--------|
| 2026-01-13 | Maven Central first (commit 20f6664) | Compile: 10-12 min → 54s |
| 2026-01-13 | Parallel workflow (commit 3d143a7) | Total: 25 min → 12 min |
| 2026-01-13 | Module list fix (commit 94e31da) | First clean parallel run |
| 2026-01-13 | TDR documentation (commit 8908d03) | Second clean parallel run |

---

**End of CI Performance Metrics**
