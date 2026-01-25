# Phase 4.4: DistributedForest & VON Integration - Usage Guide

**Date**: 2026-01-24
**Audience**: Developers implementing fault-tolerant distributed spatial forests

## Overview

This guide demonstrates how to use the Phase 4.4 fault tolerance integration to build production-ready distributed spatial forests with automatic failure detection and recovery.

## Quick Start

### 1. Basic Setup

```java
import com.hellblazer.luciferase.lucien.balancing.*;
import com.hellblazer.luciferase.lucien.balancing.fault.*;
import com.hellblazer.luciferase.lucien.forest.*;

// Create local forest
var localForest = new Forest<>(forestConfig);

// Create ghost manager for inter-partition communication
var ghostManager = new DistributedGhostManager<>(localForest, partitionId);

// Create partition registry for distributed coordination
var partitionRegistry = new InMemoryPartitionRegistry(partitionCount);

// Create distributed forest
var distributedForest = new DistributedForestImpl<>(
    localForest,
    ghostManager,
    partitionRegistry
);

// Create parallel balancer
var balancer = new DefaultParallelBalancer<>(
    distributedForest,
    BalancerConfig.defaultConfig()
);

// Wrap forest with fault tolerance (shares tracker with balancer)
var faultTolerantForest = FaultTolerantDistributedForest.wrap(
    distributedForest,
    balancer
);
```

### 2. Fault Detection Setup

```java
// Create fault configuration
var faultConfig = FaultConfiguration.defaultConfig()
    .withHeartbeatInterval(1000)           // 1 second heartbeat
    .withFailureConfirmation(5000)         // 5 second confirmation window
    .withMaxRetries(3);                    // 3 retry attempts

// Create fault handler
var faultHandler = new SimpleFaultHandler(faultConfig);

// Wrap partition registry with timeout detection
var faultAwareRegistry = new FaultAwarePartitionRegistry(
    partitionRegistry,
    faultHandler,
    5000 // barrier timeout ms
);

// Start fault monitoring
faultHandler.start();
```

### 3. Recovery Registration

```java
// Create recovery strategy for each partition
for (var partitionId : partitionIds) {
    var recovery = new DefaultPartitionRecovery(partitionId, topology);
    faultHandler.registerRecovery(partitionId, recovery);
}

// Subscribe to partition status changes
faultHandler.subscribeToChanges(event -> {
    System.out.printf("Partition %s: %s -> %s (%s)%n",
        event.partitionId(),
        event.oldStatus(),
        event.newStatus(),
        event.reason());

    // Automatic recovery on FAILED status
    if (event.newStatus() == PartitionStatus.FAILED) {
        faultHandler.initiateRecovery(event.partitionId())
            .thenAccept(success -> {
                if (success) {
                    System.out.printf("Recovery succeeded for %s%n", event.partitionId());
                } else {
                    System.err.printf("Recovery failed for %s%n", event.partitionId());
                }
            });
    }
});
```

### 4. Deterministic Testing (Optional)

```java
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

// Create TestClock for deterministic testing
var testClock = new TestClock(0);

// Inject clock into fault-aware registry
faultAwareRegistry.setClock(testClock);

// Inject clock into fault handler
faultHandler.setClock(testClock);

// Now you can control time in tests
testClock.advance(1000); // Advance by 1 second
testClock.setTime(5000); // Set absolute time
```

## Advanced Usage

### Custom Recovery Strategy

Implement `PartitionRecovery` for custom recovery logic:

```java
public class CustomPartitionRecovery implements PartitionRecovery {

    private final UUID partitionId;
    private final PartitionTopology topology;
    private final MyRecoveryService recoveryService;

    @Override
    public CompletableFuture<RecoveryResult> recover(
        UUID partitionId,
        FaultHandler faultHandler
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Custom recovery logic
                recoveryService.redistributeEntities(partitionId);
                recoveryService.rebalanceGhostLayers(partitionId);
                recoveryService.validatePartitionState(partitionId);

                return RecoveryResult.success(partitionId, 1, System.currentTimeMillis());
            } catch (Exception e) {
                return RecoveryResult.failure(partitionId, e.getMessage());
            }
        });
    }

    @Override
    public void subscribe(Consumer<RecoveryPhase> listener) {
        // Notify listener of phase transitions
    }

    @Override
    public RecoveryPhase getCurrentPhase() {
        return RecoveryPhase.COMPLETE;
    }

    @Override
    public void retryRecovery() {
        // Reset state for retry
    }

    @Override
    public int getRetryCount() {
        return 0;
    }

    @Override
    public CompletableFuture<Boolean> initiateRecovery(UUID partitionId) {
        return recover(partitionId, null)
            .thenApply(RecoveryResult::success);
    }
}
```

### Monitoring Recovery Progress

```java
var recovery = new DefaultPartitionRecovery(partitionId, topology);

// Subscribe to recovery phase transitions
recovery.subscribe(phase -> {
    System.out.printf("Partition %s: Recovery phase %s%n", partitionId, phase);

    switch (phase) {
        case DETECTING:
            System.out.println("  Detecting failure extent...");
            break;
        case REDISTRIBUTING:
            System.out.println("  Redistributing entities...");
            break;
        case REBALANCING:
            System.out.println("  Rebalancing spatial trees...");
            break;
        case VALIDATING:
            System.out.println("  Validating partition state...");
            break;
        case COMPLETE:
            System.out.println("  Recovery complete!");
            break;
    }
});

// Initiate recovery
recovery.recover(partitionId, faultHandler)
    .thenAccept(result -> {
        System.out.printf("Recovery result: %s (attempts: %d, duration: %dms)%n",
            result.success() ? "SUCCESS" : "FAILURE",
            result.attemptsNeeded(),
            result.recoveryDurationMs());
    });
```

### Coordinating Multiple Partitions

```java
// Use RecoveryCoordinatorLock to prevent concurrent recovery of same partition
var coordinatorLock = new RecoveryCoordinatorLock(3); // Max 3 concurrent recoveries

// Acquire lock before recovery
if (coordinatorLock.acquireRecoveryLock(partitionId, topology, 5, TimeUnit.SECONDS)) {
    try {
        var result = recovery.recover(partitionId, faultHandler).get();
        System.out.printf("Partition %s recovered: %s%n", partitionId, result.success());
    } finally {
        coordinatorLock.releaseRecoveryLock(partitionId);
    }
} else {
    System.err.printf("Failed to acquire recovery lock for partition %s%n", partitionId);
}
```

### Pause/Resume Operations During Recovery

```java
var tracker = balancer.getOperationTracker();

// Pause operations before recovery
if (tracker.pauseAndWait(5, TimeUnit.SECONDS)) {
    System.out.println("All in-flight operations completed, starting recovery...");

    try {
        // Perform recovery
        var result = recovery.recover(partitionId, faultHandler).get();

        if (result.success()) {
            System.out.println("Recovery succeeded, resuming operations...");
        } else {
            System.err.println("Recovery failed, investigating...");
        }
    } finally {
        // Always resume operations after recovery
        tracker.resume();
    }
} else {
    System.err.println("Timeout waiting for in-flight operations to complete");
}
```

## Configuration Options

### FaultConfiguration

```java
var config = FaultConfiguration.defaultConfig()
    .withHeartbeatInterval(1000)           // Heartbeat every 1s
    .withFailureConfirmation(5000)         // Confirm failure after 5s
    .withMaxRetries(3)                     // Max 3 recovery retries
    .withRecoveryTimeout(30000)            // Recovery timeout 30s
    .withBarrierTimeout(5000);             // Barrier timeout 5s
```

### BalancerConfig

```java
var config = BalancerConfig.defaultConfig()
    .withMaxIterations(10)                 // Max 10 balance iterations
    .withConvergenceThreshold(0.01f)       // 1% load imbalance tolerance
    .withParallelism(4);                   // 4 parallel balance threads
```

## Testing Patterns

### Unit Testing with Deterministic Clock

```java
@Test
void testRecoveryWithDeterministicClock() throws Exception {
    // Given: TestClock at t=0
    var testClock = new TestClock(0);
    var faultHandler = new DefaultFaultHandler(config, topology);
    faultHandler.setClock(testClock);

    // When: Inject barrier timeouts
    faultHandler.reportBarrierTimeout(partitionId);
    faultHandler.reportBarrierTimeout(partitionId);

    // Then: Partition should be SUSPECTED (not yet FAILED)
    assertEquals(PartitionStatus.SUSPECTED, faultHandler.checkHealth(partitionId));

    // When: Advance clock past confirmation window
    testClock.advance(1000);
    faultHandler.checkTimeouts();

    // Then: Partition should be FAILED
    assertEquals(PartitionStatus.FAILED, faultHandler.checkHealth(partitionId));
}
```

### Integration Testing with Multiple Partitions

```java
@Test
void testMultiPartitionRecovery() throws Exception {
    // Given: 3 partitions with fault tolerance
    var partitions = List.of(partition0, partition1, partition2);
    var recoveries = new HashMap<UUID, PartitionRecovery>();

    for (var partition : partitions) {
        var recovery = new DefaultPartitionRecovery(partition, topology);
        recoveries.put(partition, recovery);
        faultHandler.registerRecovery(partition, recovery);
    }

    // When: All partitions fail simultaneously
    var futures = new ArrayList<CompletableFuture<RecoveryResult>>();
    for (var partition : partitions) {
        faultHandler.reportBarrierTimeout(partition);
        faultHandler.reportBarrierTimeout(partition);

        var recovery = recoveries.get(partition);
        futures.add(recovery.recover(partition, faultHandler));
    }

    // Then: All should complete successfully
    var results = futures.stream()
        .map(f -> f.join())
        .collect(Collectors.toList());

    for (var result : results) {
        assertTrue(result.success(), "All recoveries should succeed");
    }
}
```

## Error Handling

### Common Errors and Solutions

1. **IllegalStateException: "Operations are paused for recovery"**
   - **Cause**: Attempted to start operation while recovery is in progress
   - **Solution**: Use `tryBeginOperation()` instead of `beginOperation()` to handle gracefully:
     ```java
     var token = tracker.tryBeginOperation();
     if (token.isPresent()) {
         try (var t = token.get()) {
             // Perform operation
         }
     } else {
         // Recovery in progress, skip operation or retry later
     }
     ```

2. **TimeoutException: "Barrier timeout"**
   - **Cause**: Partition did not respond to barrier within timeout
   - **Solution**: Increase barrier timeout or investigate partition health:
     ```java
     var faultAwareRegistry = new FaultAwarePartitionRegistry(
         partitionRegistry,
         faultHandler,
         10000 // Increase to 10 seconds
     );
     ```

3. **NoClassDefFoundError in tests**
   - **Cause**: Test compilation issue with anonymous inner classes
   - **Solution**: Run `mvn clean test-compile` before `mvn test`

## Performance Considerations

### Barrier Timeout Tuning

- **Default**: 5000ms
- **Low-latency networks**: 1000-2000ms
- **High-latency networks**: 10000-15000ms
- **Recommendation**: Set to 2x typical barrier completion time

### Recovery Timeout Tuning

- **Default**: 30000ms
- **Small partitions (<1000 entities)**: 10000-15000ms
- **Large partitions (>10000 entities)**: 60000-120000ms
- **Recommendation**: Measure recovery duration in tests, add 50% buffer

### Heartbeat Interval Tuning

- **Default**: 1000ms
- **Production**: 500-2000ms based on network latency
- **Testing**: 100-200ms for faster test execution
- **Trade-off**: Lower interval = faster failure detection, higher network overhead

## Troubleshooting

### Recovery Stuck in Phase

**Symptom**: Recovery transitions to DETECTING but never progresses

**Diagnosis**:
```java
recovery.subscribe(phase -> {
    System.out.printf("[%d] Phase transition: %s%n", System.currentTimeMillis(), phase);
});

// Add timeout to recovery future
recovery.recover(partitionId, faultHandler)
    .orTimeout(30, TimeUnit.SECONDS)
    .exceptionally(e -> {
        System.err.printf("Recovery timeout: %s%n", e.getMessage());
        return RecoveryResult.failure(partitionId, "Timeout");
    });
```

**Solutions**:
- Check partition topology is up-to-date
- Verify no circular dependencies in recovery chain
- Ensure in-flight operations completed before recovery

### Concurrent Recovery Failures

**Symptom**: Multiple partitions fail to recover when failing simultaneously

**Diagnosis**:
```java
var lock = new RecoveryCoordinatorLock(3); // Max 3 concurrent

System.out.printf("Active recoveries: %d%n", lock.getActiveRecoveryCount());
System.out.printf("Max concurrent: %d%n", lock.getMaxConcurrentRecoveries());
```

**Solutions**:
- Increase `maxConcurrentRecoveries` if deadlock suspected
- Use rank-based coordination to avoid contention
- Stagger recovery initiation times

### TestClock Not Working

**Symptom**: Test hangs or timeouts occur despite advancing clock

**Diagnosis**:
```java
System.out.printf("Clock time: %d%n", testClock.currentTimeMillis());
System.out.printf("Fault handler using TestClock: %s%n",
    faultHandler.getClock() instanceof TestClock);
```

**Solutions**:
- Ensure clock is injected BEFORE calling `start()`:
  ```java
  faultHandler.setClock(testClock);
  faultHandler.start(); // After clock injection
  ```
- Call `checkTimeouts()` explicitly after advancing clock:
  ```java
  testClock.advance(1000);
  faultHandler.checkTimeouts(); // Required for deterministic behavior
  ```

## Best Practices

1. **Always use try-with-resources for OperationToken**:
   ```java
   try (var token = tracker.beginOperation()) {
       // Operation code
   } // Automatically calls endOperation()
   ```

2. **Subscribe to status changes before starting monitoring**:
   ```java
   faultHandler.subscribeToChanges(listener);
   faultHandler.start(); // After subscription
   ```

3. **Test recovery under load**:
   ```java
   // Generate continuous load while triggering recovery
   var loadGenerator = Executors.newFixedThreadPool(4);
   var latch = new CountDownLatch(1);

   for (int i = 0; i < 4; i++) {
       loadGenerator.submit(() -> {
           while (!Thread.interrupted()) {
               // Generate operations
           }
       });
   }

   // Trigger recovery
   faultHandler.initiateRecovery(partitionId).get();

   loadGenerator.shutdownNow();
   ```

4. **Monitor recovery metrics**:
   ```java
   var metrics = faultHandler.getMetrics(partitionId);
   System.out.printf("Failures: %d, Recoveries: %d/%d, Latency: %dms%n",
       metrics.failureCount(),
       metrics.successfulRecoveries(),
       metrics.recoveryAttempts(),
       metrics.recoveryLatencyMs());
   ```

## References

- **Architecture**: `lucien/doc/PHASE_44_INTEGRATION_VERIFICATION.md`
- **Test Examples**: `lucien/src/test/java/com/hellblazer/luciferase/lucien/balancing/fault/Phase44SystemIntegrationTest.java`
- **API Documentation**: See Javadoc in source files

---
**Last Updated**: 2026-01-24
**Phase**: 4.4 - Integration Complete
