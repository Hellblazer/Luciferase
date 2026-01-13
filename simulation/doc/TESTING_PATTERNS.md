# Testing Patterns for Luciferase Simulation

**Last Updated**: 2026-01-12
**Status**: Current

This document catalogs testing patterns and best practices for the Luciferase simulation module, with emphasis on deterministic testing and flaky test handling.

---

## Table of Contents

1. [Deterministic Testing with Clock Interface](#deterministic-testing-with-clock-interface)
2. [Flaky Test Handling](#flaky-test-handling)
3. [Network Simulation Testing](#network-simulation-testing)
4. [Migration Testing Patterns](#migration-testing-patterns)
5. [Ghost Layer Testing](#ghost-layer-testing)
6. [CI-Specific Testing Considerations](#ci-specific-testing-considerations)

---

## Deterministic Testing with Clock Interface

### Overview

The Clock interface enables deterministic control of time in tests, eliminating timing-dependent test flakiness.

**Location**: `simulation/src/main/java/.../distributed/integration/Clock.java`

### Pattern: Inject TestClock in Tests

```java
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MyServiceTest {
    private TestClock testClock;
    private MyService service;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        testClock.setMillis(1000L);  // Start at T=1000ms

        service = new MyService();
        service.setClock(testClock);  // Inject deterministic clock
    }

    @Test
    void testTimeBasedBehavior() {
        // Execute at T=1000ms
        var result1 = service.doWork();

        // Advance time by 500ms
        testClock.advance(500);

        // Execute at T=1500ms
        var result2 = service.doWork();

        // Verify time-dependent behavior
        assertEquals(500, result2.elapsedSince(result1));
    }
}
```

### Pattern: Test Timeout Detection

```java
@Test
void testTimeoutDetection() {
    testClock.setMillis(1000L);
    service.setClock(testClock);

    var operation = service.startOperation();

    // Simulate 200ms passing (exceeds 100ms timeout)
    testClock.advance(200);

    // Verify timeout detected
    assertTrue(operation.isTimedOut());
}
```

### Pattern: Test Time-Windowed Operations

```java
@Test
void testBucketScheduling() {
    testClock.setMillis(0L);
    scheduler.setClock(testClock);

    // Schedule for 100ms bucket
    scheduler.schedule(event, 100);

    // Advance to bucket boundary
    testClock.setMillis(100);

    // Verify event executed
    assertTrue(event.wasExecuted());
}
```

### TestClock Capabilities

**Time Control**:
- `setMillis(long)` - Set absolute milliseconds since epoch
- `setNanos(long)` - Set absolute nanoseconds
- `advance(long)` - Advance milliseconds by delta
- `advanceNanos(long)` - Advance nanoseconds by delta

**Modes**:
- **Absolute mode** (default): Returns exact set time
- **Relative mode**: Adds offset to System.* (hybrid scenarios)

**Thread Safety**: All operations use AtomicLong for thread-safe state

**Time Ratio**: Maintains 1:1,000,000 ratio between millis and nanos

---

## Flaky Test Handling

### Overview

Flaky tests are tests that fail non-deterministically due to timing, randomness, or resource contention. The @DisabledIfEnvironmentVariable pattern allows tests to run locally (valuable) but skip in CI (stable).

### Pattern: @DisabledIfEnvironmentVariable

```java
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: probabilistic test with 30% packet loss"
)
@Test
void testFailureRecovery() {
    // Configure probabilistic failure
    fakeNetwork.setPacketLoss(0.3);  // 30% packet loss

    // Execute test that verifies recovery behavior
    var result = service.executeWithRetry();

    // Verify recovery worked
    assertTrue(result.isSuccess());
}
```

### When to Apply

Apply @DisabledIfEnvironmentVariable when:

1. **Probabilistic Behavior**:
   - Random failure injection
   - Packet loss simulation
   - Stochastic algorithms

2. **Timing-Sensitive Tests**:
   - Race conditions
   - Timeout windows
   - Thread synchronization

3. **Resource-Constrained Tests**:
   - Tests that fail under CI load
   - Heavy parallelization
   - Memory pressure scenarios

4. **CI vs Local Divergence**:
   - Passes locally, fails in CI
   - Non-deterministic CI failures

### Decision Criteria

Ask these questions:

1. **Is test valuable locally?** → YES
2. **Does test fail non-deterministically in CI?** → YES
3. **Would making deterministic defeat test purpose?** → YES
4. **Then apply @DisabledIfEnvironmentVariable** ✅

### Diagnostic Procedure

When a test is suspected of being flaky:

```bash
# 1. Run in isolation
mvn test -Dtest=SuspectTest -pl simulation

# 2. Run repeatedly (20 times)
for i in {1..20}; do
    mvn test -Dtest=SuspectTest -pl simulation || echo "FAIL $i"
done

# 3. Check for probabilistic logic
grep -rn "Random\|packet.*loss\|failure.*inject" src/test/java

# 4. Check for timing dependencies
grep -rn "Thread.sleep\|timeout\|race" src/test/java

# 5. Run under load
mvn test -pl simulation -DforkCount=4 -Dparallel=all
```

If failures are non-deterministic, apply the pattern.

### Examples from H3.7 Phase 1

**FailureRecoveryTest** (commit c14c217):
```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: probabilistic test with 30% packet loss"
)
@Test
void testFailureRecovery() {
    // Uses FakeNetworkChannel with 30% packet loss
    // Verifies recovery after network failures
    // Intentionally non-deterministic
}
```

**TwoNodeDistributedMigrationTest**:
```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: timing-sensitive distributed migration simulation"
)
@Test
void testTwoNodeMigration() {
    // Network simulation with timing dependencies
    // Race conditions between nodes
    // Fails under CI resource contention
}
```

---

## Network Simulation Testing

### Overview

FakeNetworkChannel enables controlled network simulation for testing distributed systems.

**Location**: `simulation/src/main/java/.../distributed/network/FakeNetworkChannel.java`

### Pattern: Deterministic Network Testing

```java
@Test
void testReliableNetworkBehavior() {
    var testClock = new TestClock();
    var fakeNetwork = new FakeNetworkChannel("node1", "node2");
    fakeNetwork.setClock(testClock);

    // Configure reliable network (0% packet loss, 0ms latency)
    fakeNetwork.setPacketLoss(0.0);
    fakeNetwork.setLatency(0);

    // Send message
    fakeNetwork.send(message);

    // Verify delivery (deterministic)
    var received = fakeNetwork.receive();
    assertEquals(message, received);
}
```

### Pattern: Network Failure Simulation (Local Development Only)

```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: probabilistic test with 30% packet loss"
)
@Test
void testNetworkFailureRecovery() {
    var fakeNetwork = new FakeNetworkChannel("node1", "node2");

    // Simulate 30% packet loss
    fakeNetwork.setPacketLoss(0.3);

    // Verify retry logic works
    var result = service.sendWithRetry(message, maxRetries = 5);
    assertTrue(result.isSuccess());
}
```

### Pattern: Network Latency Testing

```java
@Test
void testLatencyHandling() {
    var testClock = new TestClock();
    testClock.setMillis(0L);

    var fakeNetwork = new FakeNetworkChannel("node1", "node2");
    fakeNetwork.setClock(testClock);
    fakeNetwork.setLatency(100);  // 100ms latency

    // Send at T=0
    fakeNetwork.send(message);

    // Advance to T=50ms (message in flight)
    testClock.setMillis(50);
    assertNull(fakeNetwork.receive());  // Not delivered yet

    // Advance to T=100ms (message delivered)
    testClock.setMillis(100);
    assertNotNull(fakeNetwork.receive());  // Now delivered
}
```

---

## Migration Testing Patterns

### Overview

Migration tests verify entity migration between bubbles using 2PC protocol.

### Pattern: Deterministic Migration Timeout Testing

```java
@Test
void testMigrationTimeout() {
    var testClock = new TestClock();
    testClock.setMillis(1000L);

    var migration = new CrossProcessMigration(dedup, metrics);
    migration.setClock(testClock);

    // Start migration
    var tx = migration.startMigration(entityId, source, destination);

    // Simulate timeout (PHASE_TIMEOUT_MS = 100ms)
    testClock.advance(150);

    // Verify timeout detection
    assertTrue(migration.checkTimeout(tx));
}
```

### Pattern: Idempotency Testing

```java
@Test
void testIdempotentMigration() {
    var migration = new CrossProcessMigration(dedup, metrics);

    var token = UUID.randomUUID();

    // Execute migration twice with same token
    var result1 = migration.migrate(entityId, source, destination, token);
    var result2 = migration.migrate(entityId, source, destination, token);

    // Verify second call was idempotent (no duplicate)
    assertTrue(result1.isSuccess());
    assertTrue(result2.isIdempotent());
}
```

### Pattern: Rollback Testing

```java
@Test
void testMigrationRollback() {
    var migration = new CrossProcessMigration(dedup, metrics);

    // Configure destination to fail
    destination.setFailOnAdd(true);

    // Execute migration (will fail at commit phase)
    var result = migration.migrate(entityId, source, destination, token);

    // Verify rollback executed
    assertTrue(result.isRolledBack());
    assertTrue(source.contains(entityId));  // Entity restored
    assertFalse(destination.contains(entityId));  // Not in destination
}
```

---

## Ghost Layer Testing

### Overview

Ghost layer synchronization requires careful time control for boundary updates.

### Pattern: Ghost State Lifecycle Testing

```java
@Test
void testGhostStateLifecycle() {
    var testClock = new TestClock();
    testClock.setMillis(1000L);

    var ghostManager = new GhostStateManager();
    ghostManager.setClock(testClock);

    // Entity enters ghost zone at T=1000ms
    ghostManager.markAsGhost(entityId, timestamp = 1000L);

    // Advance time by 50ms
    testClock.advance(50);

    // Entity leaves ghost zone at T=1050ms
    ghostManager.markAsLocal(entityId, timestamp = 1050L);

    // Verify ghost duration
    assertEquals(50L, ghostManager.getGhostDuration(entityId));
}
```

### Pattern: Ghost Boundary Synchronization

```java
@Test
void testGhostBoundarySynchronization() {
    var testClock = new TestClock();
    var ghostManager = new GhostStateManager();
    ghostManager.setClock(testClock);

    // Configure ghost zone boundary
    ghostManager.setGhostBoundary(boundary);

    // Entity at T=1000ms moves into ghost zone
    testClock.setMillis(1000L);
    ghostManager.updatePosition(entityId, insideGhostZone);

    // Verify ghost state activated
    assertTrue(ghostManager.isGhost(entityId));
}
```

---

## CI-Specific Testing Considerations

### CI Environment Detection

Tests automatically detect CI environment via `CI=true` environment variable:

```java
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", ...)
```

### CI vs Local Behavior

| Aspect | Local Development | CI Environment |
|--------|-------------------|----------------|
| Flaky tests | Run (valuable for development) | Skip (stability) |
| Probabilistic tests | Run (verify behavior) | Skip (non-deterministic) |
| Timing-sensitive tests | Run (may pass) | Skip (resource contention) |
| Full test suite | Always runs | Always runs (except @Disabled) |

### Running Skipped Tests Locally

```bash
# Tests marked @DisabledIfEnvironmentVariable still run locally
mvn test -pl simulation

# To simulate CI behavior locally, set CI=true
CI=true mvn test -pl simulation

# To force run a specific skipped test
CI=false mvn test -Dtest=FlakyTest -pl simulation
```

### CI Stability Recommendations

1. **Avoid System.* calls**: Use Clock interface for time
2. **Avoid Thread.sleep()**: Use TestClock advancement
3. **Avoid Random without seed**: Use seeded Random or disable in CI
4. **Avoid resource contention**: Use dynamic ports, unique directories
5. **Mark probabilistic tests**: Apply @DisabledIfEnvironmentVariable

---

## Best Practices Summary

### Do's

- ✅ Use Clock interface for all time queries
- ✅ Inject TestClock in tests for deterministic time
- ✅ Apply @DisabledIfEnvironmentVariable to probabilistic tests
- ✅ Document flaky test rationale clearly
- ✅ Test timeouts and time windows with TestClock
- ✅ Use FakeNetworkChannel for network simulation
- ✅ Verify idempotency with duplicate operations

### Don'ts

- ❌ Use System.currentTimeMillis() or System.nanoTime() in new code
- ❌ Use Thread.sleep() in tests (use TestClock.advance() instead)
- ❌ Allow non-deterministic tests in CI
- ❌ Hide flaky tests without documentation
- ❌ Use fixed ports in tests (use dynamic allocation)
- ❌ Assume CI environment matches local environment

---

## Related Documentation

- [H3_DETERMINISM_EPIC.md](H3_DETERMINISM_EPIC.md) - Complete H3 determinism work
- [H3.7_PHASE1_COMPLETION.md](H3.7_PHASE1_COMPLETION.md) - Phase 1 execution report
- [DISTRIBUTED_ANIMATION_ARCHITECTURE.md](DISTRIBUTED_ANIMATION_ARCHITECTURE.md) - Distributed simulation architecture

---

**Document Version**: 1.0
**Last Updated**: 2026-01-12
**Maintainer**: Simulation Module Team
