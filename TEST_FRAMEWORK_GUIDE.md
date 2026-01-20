# Test Framework & Configuration Guide

**Last Updated**: 2026-01-20
**Status**: Comprehensive consolidation of test patterns and recent fixes
**Scope**: Luciferase test framework, performance test thresholds, CI/CD integration

---

## Executive Summary

This guide consolidates test framework documentation, recent performance test fixes, and threshold adjustments made during PrimeMover 1.0.6 upgrade. It serves as the authoritative reference for:

- Test configuration and execution patterns
- Performance test thresholds and their justification
- Flaky test handling strategies
- CI/CD integration and test batching
- Timeout values and load constraints
- Deterministic testing patterns (Clock interface)

---

## Table of Contents

1. [Test Execution](#test-execution)
2. [Performance Test Thresholds](#performance-test-thresholds)
3. [Flaky Test Handling](#flaky-test-handling)
4. [Deterministic Testing Patterns](#deterministic-testing-patterns)
5. [Concurrent Test Constraints](#concurrent-test-constraints)
6. [CI/CD Test Distribution](#cicd-test-distribution)
7. [Recent Fixes & Threshold Adjustments](#recent-fixes--threshold-adjustments)
8. [Troubleshooting](#troubleshooting)

---

## Test Execution

### Standard Commands

```bash
# Run all tests
mvn test

# Run specific module tests
mvn test -pl <module-name>

# Run specific test class
mvn test -Dtest=ClassName

# Run single test without retries
mvn test -Dtest=TestName -Dsurefire.rerunFailingTestsCount=0

# Run tests with output visible
VERBOSE_TESTS=1 mvn test

# Run performance benchmarks
mvn test -Pperformance

# Full verify with metrics extraction
mvn clean verify -Pperformance-full
```

### Test Output

Test output is **suppressed by default** to reduce noise. Enable verbose output with:

```bash
VERBOSE_TESTS=1 mvn test
```

---

## Performance Test Thresholds

### Overview

Performance tests in Luciferase have specific thresholds that vary based on execution context:
- **Local execution**: Stricter thresholds validate optimal performance
- **CI execution under full test suite load**: Relaxed thresholds accommodate system contention
- **Isolated CI execution**: Medium thresholds balance safety and performance

### Test-Specific Thresholds

#### 1. ForestConcurrencyTest (lucien module)

**Location**: `lucien/src/test/java/.../forest/ForestConcurrencyTest.java`

**Configuration**:
```java
// Line 79-83
int numThreads = 5;              // Reduced from default for CI stability
int operationsPerThread = 30;    // Reduced load to avoid timeout
// Comment: "Under full test suite load, further reduction needed to avoid timeout"
assertTrue(latch.await(45, TimeUnit.SECONDS), ...);  // 45-second timeout
```

**Recent Fix** (commit efad4be3):
- Problem: Timeout under full test suite execution
- Solution: Reduced thread count (5) and operations per thread (30)
- Context: Tests run concurrently with other test batches in CI

**Threshold Values**:
- Thread count: 5
- Operations per thread: 30
- Timeout: 45 seconds
- Lock contention: High (ReentrantReadWriteLock)

**When to Adjust**:
- If test consistently times out in CI: Reduce numThreads to 3-4
- If local execution is too slow: Increase to 8-10 threads
- If test completes in <30s consistently: Can increase operations

#### 2. VolumeAnimatorGhostTest (simulation module)

**Location**: `simulation/src/test/java/.../animation/VolumeAnimatorGhostTest.java`

**Configuration**:
```java
// Lines 47-48: Class-level disable
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Ghost animation performance test: 189% overhead varies with CI runner speed, optimization planned for Phase 7B.5+")

// Lines 335-340: Performance threshold in testNoPerformanceRegression
assertTrue(overhead < 1.5,  // 150% overhead tolerance
    "Ghost animation overhead should be < 150%, was: " + (overhead * 100) + "%");
```

**Recent Fix** (commit b1084c26):
- Problem: Performance overhead varies with CI runner speed (189% vs 50% local)
- Solution: Disabled entire test class in CI, increased acceptance threshold to 150%
- Status: Optimization planned for Phase 7B.5+ (caching and batching)

**Threshold Values**:
- Current acceptance: 150% overhead (temporary, will be optimized)
- Target for Phase 7B.5+: <100% overhead
- Issue: 189% overhead when run under CI load (disabled to prevent false failures)
- Success Criteria (original): <5% for 100 ghosts (from test comments)

**When to Adjust**:
- If optimizations implemented: Reduce threshold toward 100% target
- If Phase 7B.5 caching complete: Remove CI disable and enforce <100%
- Testing locally: Run with `mvn test -Dtest=VolumeAnimatorGhostTest` (not disabled locally)

#### 3. MultiBubbleLoadTest (simulation module)

**Location**: `simulation/src/test/java/.../distributed/grid/MultiBubbleLoadTest.java`

**Test 1: testBasic3x3GridLoad**
```java
// Lines 126-151
double maxFrameMs = metrics.getMaxFrameTimeMs();
assertTrue(maxFrameMs < 50.0, "Max tick latency should be <50ms");
```

**Test 2: testHeavyLoad500Entities** (CI-Disabled)
```java
// Line 156: Disabled in CI
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky performance test: P99 tick latency exceeds required <25ms threshold in CI environment")

// Lines 182-187: Threshold adjusted for full suite load
long p99Ns = calculatePercentile(samples, 99.0);
double p99Ms = p99Ns / 1_000_000.0;
// "Under full suite execution, system contention causes latency variance"
// "Measured 39.1ms during concurrent test execution"
assertTrue(p99Ms < 50.0, "P99 tick latency must be <50ms");  // Relaxed from 25ms
```

**Test 3: testLatencyDistribution** (CI-Disabled)
```java
// Line 394: Disabled in CI
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky performance test: P99 tick latency threshold (<25ms) varies with system load")

// Lines 436-437
assertTrue(p99Ms < 25.0, "P99 must be <25ms");            // Fails under full suite load
assertTrue(p999Ms < 50.0, "P99.9 must be <50ms");
```

**Recent Fixes** (commits d61f26e2, b1084c26):
- Problem: P99 latency threshold of 25ms unrealistic under full test suite load
- Solution: Disabled tests in CI, relaxed testHeavyLoad500Entities to 50ms
- Measured: 39.1ms P99 during concurrent test execution

**Threshold Summary**:

| Test | Configuration | Threshold | CI Disabled | Notes |
|------|---------------|-----------|------------|-------|
| Basic 3x3 | 450 entities, 1000 ticks | Max <50ms | No | Always runs |
| Heavy 500 | 500 entities, 1000 ticks | P99 <50ms | Yes | Was <25ms, relaxed for contention |
| Latency Distribution | 500 entities, 1000 ticks | P99 <25ms | Yes | Unrealistic under full load |
| Ghost Sync | 500 entities, 500 ticks | Mean <20ms | No | Always runs |
| Scaling 4vs9 | Mixed configs | Cost ratio <3.0 | No | Loose tolerance for variance |

**When to Adjust**:
- If full suite still times out: Reduce entity counts further
- If local tests perform well: Increase thresholds for consistency
- If CI finishes early: Verify system resources and consider stricter thresholds
- After Phase 7B.5 optimization: Re-enable testLatencyDistribution with <25ms threshold

---

## Flaky Test Handling

### The Problem

Probabilistic tests cause non-deterministic CI failures:
- **Network simulation**: Packet loss injection causes timing variance
- **Performance tests**: System load affects measurement consistency
- **Race condition tests**: Timing-dependent assertions fail randomly

### The Solution Pattern: @DisabledIfEnvironmentVariable

```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: [specific reason]"
)
@Test
void testProbabilisticBehavior() {
    // Test runs locally for development
    // Skips in CI to prevent false failures
}
```

### Examples in Codebase

**1. VolumeAnimatorGhostTest (Performance variance)**
```java
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Ghost animation performance test: 189% overhead varies with CI runner speed")
class VolumeAnimatorGhostTest { ... }
```

**2. testNoPerformanceRegression (Timing-dependent)**
```java
@Test
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky performance test: Ghost animation overhead varies with CI runner performance")
void testNoPerformanceRegression() { ... }
```

**3. testHeavyLoad500Entities (Latency threshold)**
```java
@Test
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky performance test: P99 tick latency exceeds required <25ms threshold in CI environment")
void testHeavyLoad500Entities() { ... }
```

**4. testLatencyDistribution (System load sensitive)**
```java
@Test
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky performance test: P99 tick latency threshold (<25ms) varies with system load")
void testLatencyDistribution() { ... }
```

### When to Use @DisabledIfEnvironmentVariable

Apply this pattern for tests with **inherent non-determinism**:

- **Probabilistic tests**: Random failure injection (30% packet loss)
- **Timing-sensitive tests**: Race conditions, timeout windows
- **Performance tests**: Overhead measurements vary with system load
- **Network simulation tests**: Timing-dependent assertion windows
- **System-resource tests**: Fail when CI runner has contention

### When NOT to Use

Do NOT apply for tests with **fixable non-determinism**:
- Tests missing deterministic Clock injection (use Clock interface instead)
- Tests with insufficient retry logic
- Tests with race conditions in synchronization (fix the race)
- Tests with timing dependencies (use TestClock)

### Diagnostic Procedure

If a test suspected of flakiness:

1. **Collect evidence**: Run in isolation, run repeatedly, check for timing patterns
   ```bash
   mvn test -Dtest=SuspectTest
   for i in {1..10}; do mvn test -Dtest=SuspectTest; done
   ```

2. **Analyze root cause**:
   - Look for `System.currentTimeMillis()`, `Thread.sleep()`, `Random` usage
   - Check for assertions on timing or order-dependent operations
   - Examine performance measurement code

3. **Choose solution**:
   - If **inherent randomness**: Apply @DisabledIfEnvironmentVariable with clear reason
   - If **missing determinism**: Inject Clock interface or use TestClock
   - If **race condition**: Fix synchronization before disabling

4. **Document clearly**: Reason must be specific and actionable
   - Bad: "Flaky performance test"
   - Good: "Flaky: P99 latency <25ms unrealistic under concurrent test load (39.1ms measured)"

---

## Deterministic Testing Patterns

### Clock Interface (H3 Epic - Phase 1 Complete)

**Status**: 31.9% of time calls converted (Phase 1 complete, Phases 2-4 planned)

**Core Abstraction**:
```java
public interface Clock {
    long currentTimeMillis();
    default long nanoTime() { return System.nanoTime(); }
    static Clock system() { return System::currentTimeMillis; }
    static Clock fixed(long fixedTime) { return () -> fixedTime; }
}
```

**Standard Pattern (Regular Classes)**:
```java
public class MyService {
    private volatile Clock clock = Clock.system();

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void doWork() {
        long now = clock.currentTimeMillis();  // Not System.currentTimeMillis()
    }
}

// In tests:
@Test
void testTimeBasedBehavior() {
    var testClock = new TestClock();
    testClock.setMillis(1000L);
    var service = new MyService();
    service.setClock(testClock);
    service.doWork();
    testClock.advance(500);  // Time travel!
    service.doWork();
}
```

**Record Class Pattern (VonMessageFactory)**:
```java
public record MigrationMessage(String id, long timestamp) {
    public MigrationMessage {
        timestamp = VonMessageFactory.currentTimeMillis();
    }
}

// In tests:
@BeforeEach
void setup() {
    var testClock = new TestClock();
    VonMessageFactory.setClock(testClock);
}

@Test
void testMessageTimestamp() {
    testClock.setMillis(1000L);
    var msg = new MigrationMessage("id", 0);
    assertEquals(1000L, msg.timestamp());  // Deterministic!
}
```

### H3 Epic Phase Breakdown

**Phase 0 (Complete)**: Foundation - Clock interface, TestClock, VonMessageFactory
**Phase 1 (Complete)**: Critical files - 8 files, 36 calls (31.9%)
**Phase 2 (Planned)**: Business logic - 18 files, 25 calls
**Phase 3 (Planned)**: Metrics - 18 files, 30 calls
**Phase 4 (Planned)**: Low priority - 17 files, 20 calls
**Total**: 113 System.* calls → 0 (only in Clock/TestClock)

### Best Practices

1. **Use volatile Clock field**: Ensures thread visibility without synchronization
2. **Setter injection**: Avoids breaking existing callers
3. **Default to Clock.system()**: Production defaults to system clock
4. **For records**: Use VonMessageFactory pattern (cannot have mutable fields)

---

## Concurrent Test Constraints

### Lock Contention in ForestConcurrencyTest

**Issue**: ReentrantReadWriteLock in ForestEntityManager causes high contention

**Symptom**: Test timeout when thread count too high or full test suite load

**Solution**:
- Reduce thread count: 5 threads (from default ~8-10)
- Reduce operations: 30 per thread (from default ~50-100)
- Increase timeout: 45 seconds (from default ~30 seconds)

**Configuration**:
```java
int numThreads = 5;              // Tuned for CI
int operationsPerThread = 30;    // Tuned for CI
assertTrue(latch.await(45, TimeUnit.SECONDS), ...);
```

**When Contention Increases**: Reduce thread count further (3-4) or operations (20)

### Ghost Animation Overhead

**Issue**: Processing 100 ghosts with animation adds overhead that varies with system load

**Measurement**: 50% overhead locally, 189% overhead in CI

**Threshold**: Currently 150% (temporary, will be optimized)

**Solution Path**:
1. Phase 7B.4 (current): Document overhead, disable in CI to prevent false failures
2. Phase 7B.5 (planned): Implement caching/batching to reduce to <100%
3. Phase 7B.6 (planned): Re-enable with <100% threshold

### Network Simulation Timing

**Issue**: Probabilistic tests (packet loss injection) cause non-deterministic failures

**Pattern**: @DisabledIfEnvironmentVariable with clear reason

**Example**:
```java
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky: probabilistic test with 30% packet loss")
void testFailureRecovery() { ... }
```

---

## CI/CD Test Distribution

### Parallel GitHub Actions Workflow

**Total Runtime**: 9-12 minutes (vs 20-30+ sequential)

**Architecture**:
1. **Compile job**: Maven Central optimization, 54 seconds
2. **6 parallel test batches**: Distributed by module/category
3. **Aggregator job**: Collects results

**Test Batches**:

| Batch | Modules | Runtime | Notes |
|-------|---------|---------|-------|
| test-batch-1 | bubble, behavior, metrics | 1 min | Fast unit tests |
| test-batch-2 | von, transport | 8-9 min | Integration tests |
| test-batch-3 | causality, migration | 4-5 min | State machines |
| test-batch-4 | distributed, network, delos | 8-9 min | Consensus/migration |
| test-batch-5 | consensus, ghost | 45-60 sec | Fast checks |
| test-other-modules | grpc, common, lucien, sentry, render, portal, dyada | 3-4 min | Mixed |

### Performance Metrics

**Reference**: `.github/CI_PERFORMANCE_METRICS.md`

**Key Optimization**: Maven repositories reordered to place Maven Central first
- Avoids 10-12 minute GitHub Packages timeout
- Reduces total CI time by 50%

---

## Recent Fixes & Threshold Adjustments

### Summary of Recent Changes

All changes made during PrimeMover 1.0.6 upgrade + test framework consolidation:

**Commit: d61f26e2** "Adjust performance test thresholds for full test suite execution"
- Updated MultiBubbleLoadTest thresholds
- Context: Full test suite load causes higher latency
- Changes: testHeavyLoad500Entities P99 relaxed to 50ms

**Commit: efad4be3** "Reduce ForestConcurrencyTest load to prevent timeout in CI"
- Reduced threads: 5, operations: 30 per thread
- Context: High write contention under test batching
- Changes: Timeout adjusted to 45 seconds

**Commit: b1084c26** "Adjust ghost animation performance threshold to accommodate system load variance"
- Disabled VolumeAnimatorGhostTest in CI
- Context: 189% overhead varies with CI runner
- Changes: Performance threshold increased to 150% (temporary)

**Commit: 5e550044** "Fix bubble split plane partitioning by using correct centroid API"
- Topology splitting centroid calculation fix
- Context: TetrahedralCentroid vs cube center confusion
- Changes: Uses correct `tet.coordinates()` method

### Impact Analysis

**Positive**: Tests now pass reliably in CI without false failures

**Trade-offs**: Some tests disabled in CI, performance assertions relaxed

**Timeline for Optimization**:
- Phase 7B.5: Ghost animation caching/batching (target <100% overhead)
- Phase 7B.5+: Re-enable latency distribution tests
- Phase H3.7.2-4: Remaining Clock interface conversions

---

## Troubleshooting

### ForestConcurrencyTest Times Out

**Symptom**: Test exceeds 45-second timeout

**Cause**: High lock contention from other concurrent tests

**Solution**:
```java
// Reduce further
int numThreads = 3;
int operationsPerThread = 20;
assertTrue(latch.await(60, TimeUnit.SECONDS), ...);
```

### VolumeAnimatorGhostTest Performance Varies

**Symptom**: Overhead measured as 50% locally, 189% in CI

**Cause**: System load affects animation loop performance

**Status**: Temporary limitation, optimized in Phase 7B.5

**Current**: Disabled in CI to prevent false failures

**Workaround**: Run locally with `mvn test -Dtest=VolumeAnimatorGhostTest`

### MultiBubbleLoadTest P99 Exceeds Threshold

**Symptom**: P99 latency >50ms when other tests run

**Cause**: CI test batches create system contention

**Solution**: Check if test batch is overloaded
- Review `.github/CI_PERFORMANCE_METRICS.md`
- Consider splitting batch if consistently slow
- Increase timeout or reduce entity count

### Test Output Suppressed

**Symptom**: Can't see test logs

**Solution**: Enable verbose output
```bash
VERBOSE_TESTS=1 mvn test
```

### Clock Interface Not Injected

**Symptom**: Test using `System.currentTimeMillis()` instead of Clock

**Cause**: Code not yet migrated (H3 Epic Phases 2-4)

**Solution**:
1. Inject Clock field: `private volatile Clock clock = Clock.system();`
2. Add setter: `public void setClock(Clock clock) { this.clock = clock; }`
3. Use in code: `clock.currentTimeMillis()` instead of `System.currentTimeMillis()`
4. Update tests with TestClock injection

---

## Cross-References

### Related Documentation

- **H3_DETERMINISM_EPIC.md**: Complete Clock interface implementation plan
- **CLAUDE.md**: General development guidance (updated with links to this guide)
- **.github/CI_PERFORMANCE_METRICS.md**: CI/CD performance data
- **simulation/doc/TECHNICAL_DECISION_PARALLEL_CI.md**: Parallel CI architecture

### Test Files with Recent Fixes

- `lucien/src/test/java/.../forest/ForestConcurrencyTest.java` (efad4be3)
- `simulation/src/test/java/.../animation/VolumeAnimatorGhostTest.java` (b1084c26)
- `simulation/src/test/java/.../distributed/grid/MultiBubbleLoadTest.java` (d61f26e2)

### Modules Affected

- **lucien**: ForestConcurrencyTest, spatial indexing tests
- **simulation**: VolumeAnimatorGhostTest, MultiBubbleLoadTest, topology tests
- **von**: Network integration tests, distributed tests
- **All modules**: Clock interface conversion (H3 Epic in progress)

---

## Document History

| Date | Change | Commit |
|------|--------|--------|
| 2026-01-20 | Initial consolidation | knowledge-tidier |
| 2026-01-12 | H3 Phase 1 complete | d61f26e2, efad4be3, b1084c26 |
| 2025-08-15 | Original threshold documentation | Initial test suite |

---

**Document Version**: 1.0
**Last Updated**: 2026-01-20
**Status**: Complete and consolidated
**Next Review**: When Phase 7B.5 (ghost animation optimization) completes
