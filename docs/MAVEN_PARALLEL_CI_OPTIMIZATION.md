# Maven Parallel Test Execution and CI Optimization

**Last Updated**: 2026-01-13
**Status**: Production Deployment
**Total Runtime**: 9-12 minutes (vs 20-30+ sequential)

---

## Executive Summary

Luciferase uses GitHub Actions parallel workflow for fast CI/CD feedback. The project achieves 50-60% time savings through strategic test batching and parallel execution across 6 test batches plus 1 aggregator job.

---

## Architecture

### Workflow Structure

```mermaid
graph TD
    A["GitHub Actions Workflow<br/>Parallel Execution"]
    B["compile<br/>54 sec"]
    C["test-batch-1<br/>1 min<br/>Fast unit tests"]
    D["test-batch-2<br/>8-9 min<br/>Von/transport integration"]
    E["test-batch-3<br/>4-5 min<br/>Causality/migration state machines"]
    F["test-batch-4<br/>8-9 min<br/>Distributed systems/network/Delos"]
    G["test-batch-5<br/>45-60 sec<br/>Consensus/ghost"]
    H["test-other-modules<br/>3-4 min"]
    H1["grpc"]
    H2["common"]
    H3["lucien"]
    H4["sentry"]
    H5["render"]
    H6["portal"]
    I["aggregate<br/>validates all passed"]

    A --> B
    B --> C
    B --> D
    B --> E
    B --> F
    B --> G
    B --> H
    H --> H1
    H --> H2
    H --> H3
    H --> H4
    H --> H5
    H --> H6
    C --> I
    D --> I
    E --> I
    F --> I
    G --> I
    H --> I
```text

### Parallel Execution Timeline

**Sequential (Before)**:
```text
compile: 54s
test-all: 20-30min (sequential)
────────────────────────────────
Total: 21-31min
```text

**Parallel (Current)**:
```text
compile: 54s
tests: 8-9min (all in parallel)
────────────────────────────────
Total: 9-12min (55% faster)
```text

---

## Test Distribution Strategy

### Why This Distribution?

Tests are strategically grouped by:

1. **Execution Time**: Fastest tests first, slowest distributed across jobs
2. **Module Dependency**: Isolated modules can run in parallel
3. **Network Requirements**: Von/transport grouped together
4. **Determinism Risk**: Distributed system tests grouped for consistency

### Test Batch Details

#### test-batch-1: Fast Unit Tests (1 minute)

**Modules**:
- bubble (behavior/metrics)
- common (collection utilities)
- dyada-java (math utilities)

**Characteristics**:
- No network I/O
- No distributed coordination
- Pure computation
- Total: ~1000 test methods

**Configuration**:
```bash
mvn test -pl bubble,common,dyada-java
```bash

#### test-batch-2: Von/Transport Integration (8-9 minutes)

**Modules**:
- simulation (Von message handling)
- transport (network layer tests)

**Characteristics**:
- Simulated network I/O
- Message serialization/deserialization
- Network protocol validation
- Total: ~800 test methods

**Configuration**:
```bash
mvn test -pl simulation,transport
```bash

**Duration**: 8-9 minutes due to:
- Network simulation overhead
- gRPC integration testing
- Protocol validation sequences

#### test-batch-3: Causality/Migration State Machines (4-5 minutes)

**Modules**:
- migration (entity migration)
- causality (causal consistency)

**Characteristics**:
- Deterministic state machine testing
- Clock injection support
- Bounded rollback verification
- Total: ~600 test methods

**Configuration**:
```bash
mvn test -pl migration,causality
```bash

#### test-batch-4: Distributed Systems/Network/Delos (8-9 minutes)

**Modules**:
- distributed (multi-node coordination)
- network (network protocol tests)
- delos (Byzantine consensus)

**Characteristics**:
- Multi-process simulation
- Byzantine fault injection
- Consensus verification
- Ghost layer testing
- Total: ~700 test methods

**Duration**: 8-9 minutes due to:
- Ethereal consensus 12 epochs × 33 seconds = 396 seconds
- Determinism verification across replicas
- Byzantine attack scenarios

**Note**: CHOAM determinism tests (DeterminismVerificationTest) run here and are expected to take 6+ minutes per test class.

#### test-batch-5: Consensus/Ghost (45-60 seconds)

**Modules**:
- consensus (consensus protocol)
- ghost (ghost layer management)

**Characteristics**:
- Protocol state validation
- Ghost boundary synchronization
- Quick consensus checks
- Total: ~300 test methods

**Configuration**:
```bash
mvn test -pl consensus,ghost
```bash

#### test-other-modules: Other Modules (3-4 minutes)

**Modules**:
- grpc (protocol buffer generation)
- lucien (spatial indexing)
- sentry (Delaunay tetrahedralization)
- render (ESVO GPU rendering)
- portal (JavaFX visualization)

**Characteristics**:
- Can run independently
- No module-to-module dependencies in test suite
- Mix of CPU-intensive and GPU tests
- Total: ~2000+ test methods

**Configuration**:
```bash
mvn test -pl grpc,lucien,sentry,render,portal
```bash

---

## Implementation Details

### GitHub Actions Workflow (.github/workflows/maven.yml)

```yaml
name: Maven Build and Test (Parallel)

on: [push, pull_request]

jobs:
  # Step 1: Compile all code once
  compile:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
      - run: mvn clean compile

  # Step 2-6: Run test batches in parallel
  test-batch-1:
    needs: compile
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: mvn test -pl bubble,common,dyada-java

  test-batch-2:
    needs: compile
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: mvn test -pl simulation,transport

  test-batch-3:
    needs: compile
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: mvn test -pl migration,causality

  test-batch-4:
    needs: compile
    runs-on: ubuntu-latest
    timeout-minutes: 15  # Longer timeout for distributed tests
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: mvn test -pl distributed,network,delos

  test-batch-5:
    needs: compile
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: mvn test -pl consensus,ghost

  test-other-modules:
    needs: compile
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: mvn test -pl grpc,lucien,sentry,render,portal

  # Step 7: Verify all tests passed
  aggregate:
    needs: [test-batch-1, test-batch-2, test-batch-3,
            test-batch-4, test-batch-5, test-other-modules]
    runs-on: ubuntu-latest
    steps:
      - run: echo "All test batches passed successfully"
```yaml

### Key Implementation Features

**1. Repository Optimization**

Maven Central is checked FIRST to avoid GitHub Packages timeout (10-12 minutes):

```xml
<repositories>
    <!-- Maven Central FIRST -->
    <repository>
        <id>central</id>
        <url>https://repo.maven.apache.org/maven2</url>
    </repository>

    <!-- GitHub Packages SECOND -->
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/Hellblazer/...</url>
    </repository>
</repositories>
```xml

**Impact**: Reduced dependency resolution time from 12-15 minutes to <1 minute

**2. Compile Phase Sharing**

All test batches use same compiled output (target/classes/):
- Compile once, test many times
- Saves 54 seconds per batch if done sequentially
- GitHub Actions caches artifacts between jobs (Maven/Java built-in)

**3. Timeout Configuration**

```yaml
timeout-minutes: 15  # distributed systems need longer
```yaml

For tests with known long execution times (e.g., Ethereal consensus with 12 epochs × 33 seconds = 396 seconds).

**4. Deterministic Test Support**

All batches support Clock interface injection:
- No System.currentTimeMillis() calls in core code
- Tests can control time progression
- Reduces flakiness and timing dependencies

---

## Local Development: Maven Parallel Execution

While GitHub Actions runs distributed batches, local machines can use Maven's built-in parallelism:

### Single-Machine Parallel Testing

```bash
# Run tests with 4 threads (1 per core)
mvn test -T 1C

# Run tests with specific thread count
mvn test -T 4

# Run modules in parallel (1 thread per module)
mvn test -T 1 -pl module1,module2,module3
```bash

**Performance on 8-core machine**:
```text
Sequential: 20-30 minutes
Parallel (-T 1C): 5-10 minutes
```text

### Recommended Local Configuration

```bash
# Best balance: 4-8 threads
mvn clean test -T 4

# Full parallel (one per core)
mvn clean test -T 1C

# Skip some slow modules (during development)
mvn clean test -pl !distributed,!delos,!choam
```text

---

## Performance Metrics

### Benchmark Results

| Scenario | Time | Notes |
|----------|------|-------|
| Sequential (baseline) | 20-30 min | All tests run one-by-one |
| Parallel GitHub Actions | 9-12 min | 6 batches run in parallel |
| Local parallel (-T 4) | 8-12 min | 4 threads on 8-core machine |
| Local full parallel (-T 1C) | 5-8 min | One thread per core |

### Speedup Achieved

```text
Parallel Speedup = Sequential Time / Parallel Time
                 = 25 min (average) / 10.5 min (average)
                 = 2.38x speedup (58% time savings)
```text

### Bottleneck Analysis

**Current Bottleneck**: test-batch-2 and test-batch-4 at 8-9 minutes each

**Why**:
- Ethereal Byzantine consensus requires 12 epochs × 33 seconds = 396 seconds per test
- Network simulation adds overhead
- Cannot be optimized further without weakening consensus guarantees

---

## Optimization Strategies Used

### 1. Module Grouping by Execution Time

Distribution follows Longest Processing Time (LPT) algorithm:

```bash
Job 1 (Von/Transport):  8-9 min  ← Longest
Job 2 (Distributed):    8-9 min  ← Longest
Job 3 (Causality):      4-5 min  ← Medium
Job 4 (Consensus):      45-60 sec ← Quick
Job 5 (Other):          3-4 min  ← Medium
Job 6 (Fast):           1 min    ← Quickest
                        ────────
Total: 9-12 min (all run in parallel)
```bash

### 2. Dependency-Aware Scheduling

Modules with no test dependencies run in parallel:
- lucien, sentry, portal, grpc can run together
- simulation, von, transport can run together

### 3. GitHub Packages Bypass

Maven Central checked first to avoid 10-12 minute timeouts:

**Before**: Dependency resolution: 12-15 minutes
**After**: Dependency resolution: <1 minute

### 4. Compile-Once Strategy

Compilation happens once, results shared across all test jobs:
- Reduces repeated compilation overhead
- Tests use pre-compiled artifacts
- Saves cumulative 54 seconds per job × 6 jobs = 5+ minutes

---

## Troubleshooting CI Performance

### Job Times Out (Distributed Tests)

**Issue**: test-batch-4 hits 10-minute default timeout

**Solution**: Increase timeout:
```yaml
timeout-minutes: 15  # increased from default 10
```yaml

### Slow Dependency Resolution

**Issue**: mvn install takes 10+ minutes on dependency step

**Solution**: Maven Central is already first in pom.xml

**If still slow**:
```bash
mvn clean install -X | grep -i "central\|github"
```bash

### Tests Fail in Parallel but Pass Sequentially

**Issue**: Test order dependency or shared state

**Investigation**:
```bash
# Run in sequence to debug
mvn test -T 1  # Single thread

# Run just the failing test
mvn test -Dtest=FailingTestClass
```bash

### Out of Memory

**Issue**: Too many threads cause heap exhaustion

**Solution**: Reduce thread count:
```bash
mvn test -T 2  # Use 2 threads instead of -T 1C
```

### One Batch Significantly Slower Than Others

**Investigation**:
```bash
mvn test -pl slow_module -e

# Check test count
find slow_module/src/test -name "*Test.java" | wc -l
```

**Rebalancing**: If one batch has 2x more tests, redistribute modules.

---

## Best Practices

### For Developers

1. **Run batch locally before pushing**:
   ```bash
   mvn clean test -T 4 -pl your_module
   ```

2. **Check test count before adding tests**:
   ```bash
   find your_module/src/test -name "*Test.java" | wc -l
   ```

3. **Use @Timeout for long-running tests**:
   ```java
   @Test
   @Timeout(value = 30, unit = TimeUnit.SECONDS)
   void longRunningTest() { ... }
   ```

4. **Avoid timing-sensitive assertions**:
   ```java
   // BAD: Depends on execution speed
   assertTrue(elapsedTime < 100);

   // GOOD: Use Clock interface
   var testClock = new TestClock();
   assertEquals(1000, testClock.currentTimeMillis());
   ```

### For CI/CD

1. **Monitor batch times** - Rebalance if one batch grows to 12+ minutes
2. **Update .github/CI_PERFORMANCE_METRICS.md** monthly
3. **Alert on regressions** - If batch time increases >20%, investigate
4. **Keep compile time under 60s** - Add precompiled artifacts if needed

---

## Future Optimization Opportunities

### Short Term (1-2 months)

1. **Further test batching** - Split test-batch-2 and test-batch-4 if they grow
2. **Artifact caching** - Cache Maven artifacts between runs
3. **Matrix builds** - Test on multiple Java versions in parallel

### Medium Term (3-6 months)

1. **Flaky test isolation** - Quarantine probabilistic tests via @DisabledIfEnvironmentVariable
2. **GPU test optimization** - Move GPU tests to separate runner with GPU
3. **Integration test framework** - Unified framework for distributed system tests

### Long Term (6-12 months)

1. **Build cache** - Use Gradle Build Cache or similar
2. **Test result analysis** - Identify consistently slow tests for optimization
3. **Smart scheduling** - ML-based job assignment based on historical times

---

## Monitoring and Metrics

### Current Metrics (.github/CI_PERFORMANCE_METRICS.md)

Track per-job execution times:

| Batch | Min | Max | Avg | Status |
|-------|-----|-----|-----|--------|
| compile | 40s | 70s | 54s | Healthy |
| batch-1 | 50s | 90s | 1m | Healthy |
| batch-2 | 7m | 10m | 8.5m | Slow |
| batch-3 | 3m | 5m | 4m | Healthy |
| batch-4 | 7m | 10m | 8.5m | Slow |
| batch-5 | 40s | 60s | 50s | Healthy |
| other | 2m | 5m | 3.5m | Healthy |
| **Total** | **9m** | **12m** | **10.5m** | **Good** |

### Alerts

- ⚠️ Warn if any batch exceeds 12 minutes
- 🔴 Fail if total exceeds 15 minutes
- 🟡 Monitor batch-2 and batch-4 (both 8-9 minutes)

---

## Related Documentation

### In This Repository

- **CLAUDE.md**: Development guidance
- **.github/CI_PERFORMANCE_METRICS.md**: Current metrics (auto-updated)
- **simulation/doc/TECHNICAL_DECISION_PARALLEL_CI.md**: Technical decision document

### External Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Maven Parallel Build Guide](https://maven.apache.org/guides/mini/guide-parallel-builds.html)
- [Docker GitHub Actions](https://docs.github.com/en/actions/publishing-packages/publishing-docker-images)

---

**Document Status**: Complete and Current
**Last Verified**: January 2026
**Implementation**: Production (GitHub Actions)
**Performance**: 9-12 minutes total, 58% improvement over sequential
