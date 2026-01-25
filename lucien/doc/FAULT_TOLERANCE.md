# Fault Tolerance Framework

**Version**: 1.0
**Status**: Production Ready
**Last Updated**: 2026-01-25

---

## Overview

The Luciferase Fault Tolerance Framework provides production-grade fault detection, recovery coordination, and consistency validation for distributed spatial forests. The framework detects partition failures in real-time and orchestrates multi-phase recovery while maintaining ghost layer consistency and 2:1 spatial balance constraints.

### Key Components

- **FaultHandler**: Detection and coordination of partition health monitoring
- **PartitionRecovery**: Multi-phase recovery state machine (DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE)
- **GhostLayerValidator**: Consistency checks across partition boundaries
- **TwoOneBalanceChecker**: 2:1 balance violation detection at ghost layer boundaries
- **CrossPartitionBalancePhase**: O(log P) butterfly pattern communication for distributed balance restoration

### Architecture Diagram

```
┌─────────────────┐
│  FaultHandler   │  Detection & Status Transitions
│  (Monitoring)   │  HEALTHY → SUSPECTED → FAILED
└────────┬────────┘
         │ triggers
         ↓
┌─────────────────┐
│ PartitionRecov. │  5-Phase State Machine
│  (Coordination) │  DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE
└────────┬────────┘
         │ validates with
         ↓
┌─────────────────┐
│ GhostLayerValid.│  Consistency Checks
│ + TwoOneBalance │  Ghost boundaries & spatial balance
└─────────────────┘
```

### Performance Characteristics

- **Detection Latency**: < 100ms (heartbeat), < 500ms (barrier timeout)
- **Recovery Time**: < 1.7 seconds (single partition, all 5 phases)
- **Cascading Failures**: < 8s for 4 sequential partitions
- **Concurrent Failures**: < 5s for 3 simultaneous partitions
- **CPU Overhead**: < 5% during normal operation

---

## Components

### 1. FaultHandler Interface

**Purpose**: Detects partition failures via heartbeats, barriers, and ghost layer synchronization. Coordinates recovery and manages partition status transitions.

**Implementations**:
- `DefaultFaultHandler`: Full-featured handler with clock injection for deterministic testing
- `SimpleFaultHandler`: Lightweight handler for basic fault detection

**Status Transition Logic**:

```
HEALTHY
  ↓ (2 consecutive barrier timeouts)
SUSPECTED
  ↓ (failureConfirmationMs elapsed)
FAILED
  ↓ (recovery successful)
HEALTHY
```

**Key Methods**:

```java
// Status queries
PartitionStatus checkHealth(UUID partitionId);

// Failure reporting
void reportBarrierTimeout(UUID partitionId);
void reportSyncFailure(UUID partitionId);
void reportHeartbeatFailure(UUID partitionId, UUID nodeId);

// Recovery coordination
void registerRecovery(UUID partitionId, PartitionRecovery recovery);
CompletableFuture<Boolean> initiateRecovery(UUID partitionId);
void notifyRecoveryComplete(UUID partitionId, boolean success);

// Event subscriptions
Subscription subscribeToChanges(Consumer<PartitionChangeEvent> listener);

// Lifecycle
void start();
void stop();
boolean isRunning();
```

**Thread Safety**: Uses `ConcurrentHashMap` and `CopyOnWriteArrayList` for concurrent access.

**Example Usage**:

```java
var config = FaultConfiguration.defaultConfig();
var topology = new InMemoryPartitionTopology();
var handler = new DefaultFaultHandler(config, topology);

// Subscribe to status changes
handler.subscribeToChanges(event -> {
    log.info("Partition {} transitioned from {} to {}",
        event.partitionId(), event.oldStatus(), event.newStatus());
});

// Start monitoring
handler.start();

// Report failures
handler.reportBarrierTimeout(partitionId);

// Check health
var status = handler.checkHealth(partitionId);
```

### 2. PartitionRecovery Interface

**Purpose**: Orchestrates recovery through a 5-phase state machine. Coordinates data redistribution, rebalancing, and validation.

**Default Implementation**: `DefaultPartitionRecovery`

**Recovery Phases**:

```
IDLE
  ↓
DETECTING (identify failures, < 100ms)
  ↓
REDISTRIBUTING (migrate data from failed partition, < 500ms)
  ↓
REBALANCING (restore 2:1 spatial balance, < 1000ms)
  ↓
VALIDATING (ghost layer consistency check, < 100ms)
  ↓
COMPLETE (recovery successful)
```

Any phase can transition to `FAILED` on error.

**Key Methods**:

```java
// Recovery orchestration
CompletableFuture<RecoveryResult> recover(UUID failedPartitionId, FaultHandler handler);

// Capability check
boolean canRecover(UUID partitionId, FaultHandler handler);

// State tracking
RecoveryPhase getCurrentPhase();
void subscribe(Consumer<RecoveryPhase> listener);

// Configuration
String getStrategyName();
FaultConfiguration getConfiguration();
```

**Example Usage**:

```java
var recovery = new DefaultPartitionRecovery(partitionId, topology, config);
recovery.setClock(Clock.system()); // Or TestClock for testing

// Subscribe to phase transitions
recovery.subscribe(phase -> {
    log.info("Recovery phase: {}", phase);
});

// Initiate recovery
var result = recovery.recover(partitionId, handler).get();
if (result.success()) {
    log.info("Recovery completed in {}ms", result.durationMs());
} else {
    log.error("Recovery failed: {}", result.errorMessage());
}
```

### 3. GhostLayerValidator

**Purpose**: Validates ghost layer consistency during the VALIDATING phase. Ensures all ghost elements have corresponding local elements and no orphaned ghosts exist.

**Location**: `com.hellblazer.luciferase.lucien.balancing.fault.GhostLayerValidator`

**Validation Checks**:
- All ghost elements have corresponding local elements in source partitions
- No orphaned ghosts (ghosts without local counterparts)
- Boundary integrity (ghosts properly replicated across partition boundaries)

**Example Usage**:

```java
var validator = new GhostLayerValidator();
var activeRanks = topology.activeRanks();
var failedRank = topology.rankFor(failedPartitionId).orElse(-1);
var ghostLayer = ghostManager.getGhostLayer();

var result = validator.validate(ghostLayer, activeRanks, failedRank);
if (!result.valid()) {
    log.error("Ghost layer validation failed: {}", result.errors());
}
```

### 4. TwoOneBalanceChecker

**Purpose**: Detects 2:1 balance violations at partition boundaries. Returns violations where level difference exceeds 1.

**Location**: `com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker`

**Violation Record**:

```java
public record BalanceViolation<K extends SpatialKey<K>>(
    K localKey,
    K ghostKey,
    int localLevel,
    int ghostLevel,
    int levelDifference,
    int sourceRank
)
```

**Example Usage**:

```java
var checker = new TwoOneBalanceChecker<MortonKey, LongEntityID, Content>();
var violations = checker.findViolations(ghostLayer, forest);

for (var violation : violations) {
    log.warn("Balance violation: local {} (level {}) vs ghost {} (level {}) from rank {}",
        violation.localKey(), violation.localLevel(),
        violation.ghostKey(), violation.ghostLevel(),
        violation.sourceRank());
}
```

### 5. CrossPartitionBalancePhase

**Purpose**: Coordinates cross-partition refinement requests using O(log P) butterfly pattern communication during the REBALANCING phase.

**Location**: `com.hellblazer.luciferase.lucien.balancing.CrossPartitionBalancePhase`

**Key Operations**:
- `identifyRefinementNeeds()`: Identifies violations, groups by rank, sends async requests
- `applyRefinementResponses()`: Deserializes responses, adds refined elements to ghost layer

**Example Usage**:

```java
var phase = new CrossPartitionBalancePhase<>(forest, ghostLayer, coordinator);

// Identify refinement needs
var requests = phase.identifyRefinementNeeds(violations);

// Apply responses (after async communication)
phase.applyRefinementResponses(requests, responses);
```

---

## Failure Scenarios

The framework handles these failure scenarios (validated by 18 E2E tests):

### 1. Single Partition Failures

**Heartbeat Failure** (`testSinglePartitionHeartbeatFailure`):
- Detection via 2 consecutive missed heartbeats
- Transition to SUSPECTED within 100ms
- Full recovery in < 1.7s

**Barrier Timeout** (`testSinglePartitionBarrierTimeout`):
- Detection via barrier synchronization timeout
- Transition to SUSPECTED within 500ms
- Other partitions unaffected

**Ghost Sync Failure** (`testSinglePartitionGhostSyncFailure`):
- Detection via ghost layer synchronization failure
- Immediate recovery initiation
- Validation ensures ghost layer consistency

**Crash and Recover** (`testSinglePartitionCrashAndRecover`):
- All 5 recovery phases complete successfully
- Full phase transitions: DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE
- Total recovery time < 1.7s

### 2. Cascading Failures (Sequential)

**Two Partition Sequential (500ms delay)** (`testTwoPartitionSequentialFailure_500msDelay`):
- Second partition fails during first recovery
- Overlapping recovery coordination
- Total recovery < 5s

**Three Partition Cascade (100ms interval)** (`testThreePartitionCascade_100msInterval`):
- 3 partitions fail sequentially
- Recovery not interrupted
- Total recovery < 8s
- Ghost layer remains consistent

**Cascading with Network Latency** (`testCascadingFailureDuringRecovery_NetworkLatency`):
- Secondary failure during REDISTRIBUTING phase
- 50ms network latency injected
- Recovery resilient to latency
- Total recovery < 6s

**Four Partition Cascade** (`testFourPartitionCascade_FullRecovery`):
- 4 sequential failures with 50ms spacing
- System remains available
- Total recovery < 8s

### 3. Concurrent Failures (Simultaneous)

**Two Partition Concurrent** (`testTwoPartitionConcurrentFailure`):
- Both partitions fail simultaneously
- Parallel recovery faster than sequential
- Total recovery < 3s

**Three Partition with Network Split** (`testThreePartitionConcurrentFailure_NetworkPartition`):
- Network partition simulated
- 3 partitions fail on one side
- Split-brain avoided
- Ghost layer consistency maintained

**Four Partition Stress Test** (`testFourPartitionConcurrentFailure_StressTest`):
- 4 partitions fail simultaneously
- No deadlocks
- Total recovery < 5s

### 4. Clock Faults

**Clock Skew (1 second)** (`testClockSkewDetection_SystemClock_1second`):
- Clock jumps forward 1 second
- No spurious failures
- Detection not affected by skew

**Clock Drift (1000ppm)** (`testClockDrift_SlowClock_1000ppm`):
- 1ms per second drift
- Detection works correctly
- Latency within acceptable bounds

**Clock Skew with Cascading Failure** (`testClockSkew_CascadingFailureWithTimestamps`):
- 500ms skew applied
- Timestamp ordering preserved
- Recovery succeeds

### 5. Network Faults

**Network Latency (100ms RTT)** (`testNetworkLatency_100msRoundTrip_Impact`):
- 100ms round-trip latency injected
- Detection increases by ~100ms
- Total recovery < 3s

**Packet Loss (5%)** (`testPacketLoss_5Percent_RecoveryResilience`):
- 5% packet loss injected
- Recovery resilient with retries
- Total recovery < 5s

---

## Configuration

### FaultConfiguration Record

```java
public record FaultConfiguration(
    long suspectTimeoutMs,      // Time before HEALTHY → SUSPECTED
    long failureConfirmationMs, // Time before SUSPECTED → FAILED
    int maxRecoveryRetries,     // Recovery retry limit
    long recoveryTimeoutMs,     // Recovery phase timeout
    boolean autoRecoveryEnabled,// Auto-recovery on failure detection
    int maxConcurrentRecoveries // Max concurrent partition recoveries
)
```

### Default Values

```java
var config = FaultConfiguration.defaultConfig();
// suspectTimeoutMs: 3000 (3 seconds)
// failureConfirmationMs: 5000 (5 seconds)
// maxRecoveryRetries: 3
// recoveryTimeoutMs: 30000 (30 seconds)
// autoRecoveryEnabled: true
// maxConcurrentRecoveries: 3
```

**Total Detection Latency**: `suspectTimeoutMs + failureConfirmationMs = 8 seconds`

### Tuning Guidelines

**For Faster Detection** (trade-off: higher false positive risk):

```java
var fastConfig = FaultConfiguration.defaultConfig()
    .withSuspectTimeout(500)           // 500ms to SUSPECTED
    .withFailureConfirmation(1000);    // 1s to FAILED
// Total detection: 1.5 seconds
```

**For Production (balanced)**:

```java
var prodConfig = FaultConfiguration.defaultConfig()
    .withSuspectTimeout(3000)          // 3s to SUSPECTED
    .withFailureConfirmation(5000)     // 5s to FAILED
    .withMaxRetries(3)                 // 3 retry attempts
    .withRecoveryTimeout(30000);       // 30s per recovery attempt
// Total detection: 8 seconds
```

**For Testing (deterministic)**:

```java
var testConfig = new FaultConfiguration(
    500,   // suspectTimeoutMs - fast for tests
    1000,  // failureConfirmationMs - fast confirmation
    3,     // maxRecoveryRetries
    30000, // recoveryTimeoutMs
    true,  // autoRecoveryEnabled
    3      // maxConcurrentRecoveries
);
```

### Clock Injection Pattern

**For Deterministic Testing**:

```java
var testClock = new TestClock(0); // Start at epoch
var handler = new DefaultFaultHandler(config, topology);
handler.setClock(testClock);

var recovery = new DefaultPartitionRecovery(partitionId, topology, config);
recovery.setClock(testClock);

// Advance time deterministically
testClock.advance(500); // Advance 500ms
handler.checkTimeouts(); // Trigger timeout checks

// Validate status transitions
assertThat(handler.checkHealth(partitionId))
    .isEqualTo(PartitionStatus.SUSPECTED);
```

**For Production**:

```java
var handler = new DefaultFaultHandler(config, topology);
// Uses System::currentTimeMillis by default

var recovery = new DefaultPartitionRecovery(partitionId, topology, config);
// Uses Clock.system() by default
```

---

## Best Practices

### 1. Always Use Clock Injection for Testing

**Why**: Enables deterministic time control, reproducible test failures, and time-travel debugging.

```java
// ✅ CORRECT: Deterministic testing
var testClock = new TestClock(0);
handler.setClock(testClock);
recovery.setClock(testClock);

testClock.advance(500);
handler.checkTimeouts();

// ❌ WRONG: Non-deterministic timing
Thread.sleep(500); // Flaky!
handler.checkTimeouts();
```

### 2. Scenario-Based Test Construction

Use `FaultScenarioBuilder` for complex multi-event tests:

```java
var scenario = new FaultScenarioBuilder(injector, clock)
    .named("cascading-failure")
    .setup(5)
    .atTime(0, () -> handler.reportBarrierTimeout(0))
    .atTime(50, () -> handler.reportBarrierTimeout(0))
    .atTime(500, () -> handler.reportBarrierTimeout(1))
    .atTime(550, () -> handler.reportBarrierTimeout(1))
    .atTime(1000, () -> {
        assertThat(handler.checkHealth(partition0)).isEqualTo(PartitionStatus.SUSPECTED);
        assertThat(handler.checkHealth(partition1)).isEqualTo(PartitionStatus.SUSPECTED);
    })
    .build();

scenario.execute();
```

### 3. Metrics Collection and Validation

Track key metrics during tests:

```java
var detectionTime = new AtomicLong(0);
var recoveryStartTime = new AtomicLong(0);
var recoveryCompleteTime = new AtomicLong(0);

handler.subscribe(event -> {
    if (event.newStatus() == PartitionStatus.SUSPECTED && detectionTime.get() == 0) {
        detectionTime.set(clock.currentTimeMillis());
    }
});

recovery.subscribe(phase -> {
    if (phase == RecoveryPhase.DETECTING) {
        recoveryStartTime.set(clock.currentTimeMillis());
    }
    if (phase == RecoveryPhase.COMPLETE) {
        recoveryCompleteTime.set(clock.currentTimeMillis());
    }
});

// Assert metrics
assertThat(detectionTime.get()).isLessThan(100);
assertThat(recoveryCompleteTime.get() - recoveryStartTime.get()).isLessThan(1700);
```

### 4. Performance Monitoring

Monitor CPU overhead in production:

```java
var metrics = handler.getAggregateMetrics();
log.info("Detection overhead: {}%", metrics.cpuOverhead());

if (metrics.cpuOverhead() > 5.0) {
    log.warn("High fault detection overhead detected");
}
```

### 5. Subscribe to Phase Transitions

Always subscribe to recovery phase transitions for observability:

```java
recovery.subscribe(phase -> {
    log.info("Recovery phase: {}", phase);
    switch (phase) {
        case DETECTING -> log.debug("Identifying failed partitions");
        case REDISTRIBUTING -> log.debug("Migrating data from failed partition");
        case REBALANCING -> log.debug("Restoring 2:1 spatial balance");
        case VALIDATING -> log.debug("Validating ghost layer consistency");
        case COMPLETE -> log.info("Recovery successful");
        case FAILED -> log.error("Recovery failed");
    }
});
```

---

## Troubleshooting

### Issue: Detection Timeout Exceeded

**Symptom**: Partition stuck in HEALTHY despite failures.

**Cause**: `suspectTimeoutMs` too large or `checkTimeouts()` not called frequently enough.

**Solution**:
1. Reduce `suspectTimeoutMs` for faster detection
2. Ensure periodic `checkTimeouts()` calls (or use automatic monitoring)

```java
var fastConfig = config.withSuspectTimeout(500); // Reduce from 3000ms
handler.startMonitoring(); // Enables automatic timeout checks
```

### Issue: Recovery Phase Timeout

**Symptom**: Recovery stuck in REDISTRIBUTING or REBALANCING phase.

**Cause**: `recoveryTimeoutMs` too short or network issues.

**Solution**:
1. Increase `recoveryTimeoutMs` for larger datasets
2. Check network latency and adjust timeouts

```java
var relaxedConfig = config.withRecoveryTimeout(60000); // Increase to 60s
```

### Issue: Ghost Layer Inconsistency

**Symptom**: `GhostLayerValidator` reports orphaned ghosts or missing local elements.

**Cause**: Recovery validation phase failed or ghost layer not updated during recovery.

**Solution**:
1. Enable detailed validation logging
2. Check ghost manager integration
3. Verify VALIDATING phase completes

```java
recovery.subscribe(phase -> {
    if (phase == RecoveryPhase.VALIDATING) {
        var activeRanks = topology.activeRanks();
        var failedRank = topology.rankFor(partitionId).orElse(-1);
        var result = validator.validate(ghostLayer, activeRanks, failedRank);
        if (!result.valid()) {
            log.error("Validation errors: {}", result.errors());
        }
    }
});
```

### Issue: Message Delivery Failures

**Symptom**: `TimeoutException` or `RuntimeException` during refinement coordination.

**Cause**: Network partition, high latency, or partition unreachable.

**Solution**:
1. Retry with exponential backoff
2. Check network connectivity
3. Increase `recoveryTimeoutMs`

```java
// CrossPartitionBalancePhase handles TimeoutException automatically
// Check logs for propagated exceptions:
log.warn("Timeout waiting for refinement response from partition {}", rank);
```

### Issue: False Positive Detections

**Symptom**: Partitions marked SUSPECTED during normal operation.

**Cause**: `suspectTimeoutMs` too low or network latency exceeds threshold.

**Solution**:
1. Increase `suspectTimeoutMs`
2. Tune for network latency (add RTT buffer)

```java
var conservativeConfig = config
    .withSuspectTimeout(5000)          // Increase from 3000ms
    .withFailureConfirmation(10000);   // Increase from 5000ms
```

---

## See Also

- **FAULT_TOLERANCE_BENCHMARKS.md**: Performance targets and benchmark results
- **PHASE_5_FAULT_TOLERANCE_SUMMARY.md**: Complete architecture overview
- **Javadoc**: `FaultHandler.java`, `PartitionRecovery.java`, `DefaultFaultHandler.java`, `DefaultPartitionRecovery.java`
- **Tests**: `Phase45E2EValidationTest.java` (18 E2E tests), `FaultDetectionBenchmark.java` (5 JMH benchmarks)
