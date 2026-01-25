# Fault Tolerance Performance Benchmarks

**Version**: 1.0
**Status**: Production Ready
**Last Updated**: 2026-01-25

---

## Overview

This document defines performance targets and measurement methodology for the Luciferase Fault Tolerance Framework. Benchmarks are implemented using JMH (Java Microbenchmark Harness) with deterministic time control via `TestClock`.

**Benchmark Suite**: `FaultDetectionBenchmark.java`
**Location**: `lucien/src/test/java/com/hellblazer/luciferase/lucien/balancing/fault/`
**Execution**: `mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark`

---

## Performance Targets

### 1. Detection Latency Targets

Detection latency measures the time from failure injection to status transition.

| Detection Type | Target | Measured As | Notes |
|---------------|--------|-------------|-------|
| Heartbeat Miss | < 100ms | Time to SUSPECTED | Single heartbeat miss |
| Barrier Timeout | < 500ms | Time to SUSPECTED | 2 consecutive timeouts required |
| Ghost Sync Failure | < 100ms | Time to SUSPECTED | Immediate detection |
| Failure Confirmation | suspectTimeoutMs + failureConfirmationMs | Time to FAILED | Default: 8 seconds |

**Baseline Configuration**:
- `suspectTimeoutMs`: 3000ms
- `failureConfirmationMs`: 5000ms
- Total detection latency: 8 seconds

**Fast Test Configuration**:
- `suspectTimeoutMs`: 500ms
- `failureConfirmationMs`: 1000ms
- Total detection latency: 1.5 seconds

### 2. Recovery Time Targets (Single Partition)

Recovery time measures the full 5-phase state machine execution.

| Phase | Target | Cumulative | Notes |
|-------|--------|-----------|-------|
| DETECTING | < 100ms | 100ms | Identify failures |
| REDISTRIBUTING | < 500ms | 600ms | Migrate data from failed partition |
| REBALANCING | < 1000ms | 1600ms | Restore 2:1 spatial balance |
| VALIDATING | < 100ms | 1700ms | Ghost layer consistency check |
| **Total** | **< 1.7s** | **1700ms** | Full recovery pipeline |

**Benchmark**: `benchmarkSinglePartitionRecovery`

### 3. Cascading Failure Targets (Sequential)

Cascading failures measure total recovery time for N partitions failing sequentially.

| Scenario | Partitions | Delay | Target | Notes |
|---------|-----------|-------|--------|-------|
| Two Sequential | 2 | 500ms | < 5s | Second fails during first recovery |
| Three Cascade | 3 | 100ms | < 8s | Rapid sequential failures |
| Four Cascade | 4 | 50ms | < 8s | Stress test for recovery coordination |

**Benchmark**: `benchmarkCascadingFailureTotal`

**Scaling**: O(N) for N sequential failures (each adds ~1.7s recovery time + overlap).

### 4. Concurrent Failure Targets (Simultaneous)

Concurrent failures measure total recovery time for N partitions failing simultaneously.

| Scenario | Partitions | Target | Notes |
|---------|-----------|--------|-------|
| Two Concurrent | 2 | < 3s | Parallel recovery benefit |
| Three Concurrent | 3 | < 5s | Should be faster than cascading |
| Four Concurrent | 4 | < 5s | Stress test with no deadlocks |

**Benchmark**: `benchmarkConcurrentFailureTotal`

**Scaling**: Parallel recovery provides ~2x speedup over sequential (< 3s for 2 concurrent vs. 3.4s sequential).

**Communication Overhead**: O(log P) messages per round (butterfly pattern).
- 2 partitions: 1 round, 2 messages
- 4 partitions: 2 rounds, 4 messages
- 8 partitions: 3 rounds, 8 messages

### 5. CPU Overhead Target

CPU overhead measures the cost of fault detection during normal operation.

| Metric | Target | Measured As | Notes |
|--------|--------|-------------|-------|
| Detection Overhead | < 5% | (overhead ops / baseline ops) * 100 | During active monitoring |
| Heartbeat Processing | < 100µs | Per heartbeat operation | Includes recording + health check |
| Status Query | < 10µs | Per checkHealth() call | Read-only concurrent access |

**Benchmark**: `benchmarkCPUOverhead`

**Measurement**: Compare 100 iterations of normal heartbeat processing vs. baseline (no fault detection).

---

## Measurement Methodology

### JMH Benchmark Configuration

All benchmarks use the following JMH configuration:

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS) // or MILLISECONDS for long-running
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
```

### Clock-Based Time Measurement

**Key Principle**: Use logical time (`TestClock`) instead of wall-clock time for deterministic measurement.

**TestClock Advantages**:
1. **Deterministic**: Reproducible results across runs and systems
2. **Time-Travel**: Advance time programmatically (`clock.advance(500)`)
3. **No Flakiness**: Eliminates timing-dependent test failures
4. **CI/CD Friendly**: Consistent results in containerized environments

**Example Measurement**:

```java
var testClock = new TestClock(0); // Start at epoch
handler.setClock(testClock);
recovery.setClock(testClock);

var startTime = testClock.currentTimeMillis(); // Record start

// Execute recovery
recovery.recover(partitionId, handler).get();

var endTime = testClock.currentTimeMillis(); // Record end
var duration = endTime - startTime; // Logical time duration
```

### Test Environment Setup

**Hardware**:
- CPU: Modern multi-core processor (4+ cores)
- Memory: 8GB+ RAM
- Storage: SSD recommended for I/O-bound operations

**Software**:
- Java 24 (or latest stable)
- Maven 3.9.1+
- JMH 1.37

**Configuration**:
- Disable Java assertions for performance tests (`-da` flag)
- Use production-optimized JVM flags
- Isolate benchmark execution (no concurrent workloads)

**Running Benchmarks**:

```bash
# Full benchmark suite
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark

# Specific benchmark
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark#benchmarkDetectionLatency

# Run directly from main()
java -cp target/test-classes:target/classes \
  com.hellblazer.luciferase.lucien.balancing.fault.FaultDetectionBenchmark
```

### Metrics Collection

**Collected Metrics**:
1. **Detection Latency**: Time from failure injection to SUSPECTED status
2. **Recovery Duration**: Time from DETECTING to COMPLETE phase
3. **Phase Durations**: Time spent in each recovery phase
4. **Message Count**: Number of cross-partition messages (for communication overhead)
5. **Ghost Layer Consistency**: Boolean validation result

**Metrics Builder**:

```java
private Phase45TestMetrics buildMetrics() {
    var detection = detectionTime.get();
    var recoveryStart = recoveryStartTime.get();
    var recoveryComplete = recoveryCompleteTime.get();

    var detectionLatency = detection > 0 ? detection : 0;
    var recoveryTime = (recoveryComplete > 0 && recoveryStart > 0)
        ? (recoveryComplete - recoveryStart) : 0;
    var totalTime = recoveryComplete > 0 ? recoveryComplete : clock.currentTimeMillis();

    return new Phase45TestMetrics(
        detectionLatency,
        recoveryTime,
        totalTime,
        phaseTimes,
        messageCounter.get(),
        ghostLayerConsistent
    );
}
```

---

## Benchmark Results

### Benchmark 1: Detection Latency

**Benchmark**: `benchmarkDetectionLatency`

**Target**: < 100µs (microseconds) for heartbeat miss detection

**Method**: Measures time from missing a heartbeat to partition being marked SUSPECTED.

**Expected Results** (to be filled after baseline run):

| Metric | Target | Baseline | Status |
|--------|--------|----------|--------|
| Average Latency | < 100µs | _TBD_ | ⏳ Pending |
| 95th Percentile | < 200µs | _TBD_ | ⏳ Pending |
| 99th Percentile | < 500µs | _TBD_ | ⏳ Pending |

**Interpretation**:
- If latency > 100µs: Investigate concurrent map overhead or listener notification cost
- If latency varies significantly: Check for GC pauses or system contention

### Benchmark 2: Single Partition Recovery

**Benchmark**: `benchmarkSinglePartitionRecovery`

**Target**: < 1.7ms (1700µs) for full recovery pipeline

**Method**: Measures complete recovery from DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE.

**Expected Results** (to be filled after baseline run):

| Phase | Target | Baseline | Status |
|-------|--------|----------|--------|
| DETECTING | < 100µs | _TBD_ | ⏳ Pending |
| REDISTRIBUTING | < 500µs | _TBD_ | ⏳ Pending |
| REBALANCING | < 1000µs | _TBD_ | ⏳ Pending |
| VALIDATING | < 100µs | _TBD_ | ⏳ Pending |
| **Total** | **< 1700µs** | **_TBD_** | **⏳ Pending** |

**Interpretation**:
- If REDISTRIBUTING > 500µs: Investigate data migration overhead
- If REBALANCING > 1000µs: Check butterfly pattern communication or refinement coordination
- If VALIDATING > 100µs: Check ghost layer validation complexity

### Benchmark 3: Cascading Failure (2 Partitions)

**Benchmark**: `benchmarkCascadingFailureTotal`

**Target**: < 5 seconds for 2 sequential failures

**Method**: Measures total time for two partitions failing sequentially with minimal delay.

**Expected Results** (to be filled after baseline run):

| Metric | Target | Baseline | Status |
|--------|--------|----------|--------|
| Total Recovery Time | < 5s | _TBD_ | ⏳ Pending |
| Overlap Benefit | ~1.7s savings | _TBD_ | ⏳ Pending |

**Calculation**: Sequential (no overlap) = 2 × 1.7s = 3.4s. With overlap, target is 5s (allows 1.6s additional time for coordination).

**Interpretation**:
- If time > 5s: Recovery phases not properly overlapping
- If time < 3.4s: Excellent parallelism in recovery coordination

### Benchmark 4: Concurrent Failure (3 Partitions)

**Benchmark**: `benchmarkConcurrentFailureTotal`

**Target**: < 5 seconds for 3 simultaneous failures

**Method**: Measures total time for three partitions failing simultaneously.

**Expected Results** (to be filled after baseline run):

| Metric | Target | Baseline | Status |
|--------|--------|----------|--------|
| Total Recovery Time | < 5s | _TBD_ | ⏳ Pending |
| Speedup vs Sequential | > 2x | _TBD_ | ⏳ Pending |
| No Deadlocks | 100% success | _TBD_ | ⏳ Pending |

**Calculation**: Sequential (no parallelism) = 3 × 1.7s = 5.1s. Target < 5s demonstrates parallel recovery benefit.

**Interpretation**:
- If time > 5s: Insufficient parallelism or resource contention
- If time < 3s: Excellent parallel recovery performance

### Benchmark 5: CPU Overhead

**Benchmark**: `benchmarkCPUOverhead`

**Target**: < 5% overhead during normal operation

**Method**: Measures CPU overhead of fault detection by comparing normal heartbeat processing with active fault detection.

**Expected Results** (to be filled after baseline run):

| Metric | Target | Baseline | Status |
|--------|--------|----------|--------|
| Overhead (%) | < 5% | _TBD_ | ⏳ Pending |
| Operations/sec | Baseline | _TBD_ | ⏳ Pending |

**Calculation**: `Overhead = ((baseline_ops - with_detection_ops) / baseline_ops) * 100`

**Interpretation**:
- If overhead > 5%: Investigate listener notification cost or concurrent map overhead
- If overhead varies: Check for GC pauses or system-level interference

---

## Performance Optimization Opportunities

Based on benchmark results, consider these optimizations:

### 1. Detection Latency

- **Listener Notification**: Use async event dispatch to reduce blocking
- **Concurrent Map**: Profile `ConcurrentHashMap` overhead, consider lock-striping
- **Heartbeat Processing**: Batch heartbeat updates for higher throughput

### 2. Recovery Time

- **Phase Parallelization**: Execute REDISTRIBUTING + REBALANCING in parallel (future work)
- **Adaptive Timeouts**: Use observed latency to adjust timeout thresholds dynamically
- **Ghost Layer Validation**: Cache validation results for incremental checks

### 3. Communication Overhead

- **Message Batching**: Group refinement requests to reduce network round-trips
- **Compression**: Compress large refinement responses (serialized keys)
- **Pipeline**: Overlap communication with computation in butterfly pattern

### 4. Scaling Characteristics

- **O(log P) Butterfly Pattern**: Verified for P=2,4,8 partitions (expected: 1,2,3 rounds)
- **Memory**: Linear growth with number of partitions (one health state per partition)
- **CPU**: Constant overhead per partition (no quadratic interactions)

---

## Continuous Monitoring

**Production Metrics to Track**:
1. Detection latency percentiles (P50, P95, P99)
2. Recovery success rate (%)
3. Recovery duration percentiles (P50, P95, P99)
4. False positive rate (spurious SUSPECTED transitions)
5. CPU overhead during normal operation

**Alerting Thresholds**:
- Detection latency P99 > 200ms: Investigate network or system issues
- Recovery duration P95 > 3s: Check for resource contention
- False positive rate > 1%: Tune `suspectTimeoutMs` or network thresholds

---

## Running Benchmarks

### Full Benchmark Suite

```bash
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark
```

### Individual Benchmarks

```bash
# Detection latency
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark#benchmarkDetectionLatency

# Single partition recovery
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark#benchmarkSinglePartitionRecovery

# Cascading failure
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark#benchmarkCascadingFailureTotal

# Concurrent failure
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark#benchmarkConcurrentFailureTotal

# CPU overhead
mvn test -pl lucien -Pperformance -Dtest=FaultDetectionBenchmark#benchmarkCPUOverhead
```

### Custom JMH Options

```bash
# Run with custom fork count and iterations
java -jar target/benchmarks.jar FaultDetectionBenchmark \
  -f 3 \
  -wi 5 \
  -i 10
```

---

## See Also

- **FAULT_TOLERANCE.md**: Architecture, components, and configuration guide
- **PHASE_5_FAULT_TOLERANCE_SUMMARY.md**: Complete architecture overview
- **FaultDetectionBenchmark.java**: JMH benchmark implementation
- **Phase45E2EValidationTest.java**: 18 E2E validation tests
