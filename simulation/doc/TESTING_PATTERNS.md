# Simulation Module Testing Patterns

**Last Updated**: 2026-02-08
**Status**: Current
**Scope**: Simulation module-specific patterns and best practices

This document catalogs **simulation-specific** testing patterns and best practices. For general test framework guidance, performance test thresholds, and flaky test handling, see the authoritative reference: **TEST_FRAMEWORK_GUIDE.md** in the repository root.

---

## Table of Contents

1. [Deterministic Testing with Clock Interface](#deterministic-testing-with-clock-interface) *(General pattern - reference TEST_FRAMEWORK_GUIDE.md)*
2. [Flaky Test Handling](#flaky-test-handling) *(General pattern - reference TEST_FRAMEWORK_GUIDE.md)*
3. [Network Simulation Testing](#network-simulation-testing) ⭐ *Simulation-specific*
4. [Migration Testing Patterns](#migration-testing-patterns) ⭐ *Simulation-specific*
5. [Ghost Layer Testing](#ghost-layer-testing) ⭐ *Simulation-specific*
6. [CI-Specific Testing Considerations](#ci-specific-testing-considerations) *(General guidance - reference TEST_FRAMEWORK_GUIDE.md)*
7. [Concurrency Testing Patterns](#concurrency-testing-patterns) ⭐ *Simulation-specific*

---

## Quick Reference

For information on these topics, consult the authoritative **TEST_FRAMEWORK_GUIDE.md**:
- [Test Execution commands](../../TEST_FRAMEWORK_GUIDE.md#test-execution)
- [Performance Test Thresholds](../../TEST_FRAMEWORK_GUIDE.md#performance-test-thresholds)
- [General Flaky Test Handling](../../TEST_FRAMEWORK_GUIDE.md#flaky-test-handling)
- [Clock Interface Patterns](../../TEST_FRAMEWORK_GUIDE.md#deterministic-testing-patterns)
- [Concurrent Test Constraints](../../TEST_FRAMEWORK_GUIDE.md#concurrent-test-constraints)
- [CI/CD Test Distribution](../../TEST_FRAMEWORK_GUIDE.md#cicd-test-distribution)

---

## Deterministic Testing with Clock Interface

**See**: [Clock Interface Patterns in TEST_FRAMEWORK_GUIDE.md](../../TEST_FRAMEWORK_GUIDE.md#deterministic-testing-patterns)

The Clock interface enables deterministic control of time in tests. This is a **general pattern used project-wide**, not simulation-specific.

**Location**: `simulation/src/main/java/.../distributed/integration/Clock.java`

**Quick Reference**:
```java
// Inject TestClock in tests
@BeforeEach void setUp() {
    testClock = new TestClock();
    testClock.setTime(1000L);  // Absolute time mode
    service.setClock(testClock);
}

@Test void testTimeBasedBehavior() {
    var result1 = service.doWork();  // At T=1000ms
    testClock.advance(500);           // Move to T=1500ms
    var result2 = service.doWork();
    assertEquals(500, result2.elapsedSince(result1));
}
```

**Key Capabilities**:
- `setTime(long)` - Set absolute milliseconds
- `advance(long)` - Advance milliseconds by delta
- `setSkew(long)` - Set time offset (hybrid mode)
- Thread-safe via AtomicLong

For complete patterns (timeout testing, time-windowed operations, TestClock modes), see **TEST_FRAMEWORK_GUIDE.md § Deterministic Testing Patterns**.

---

## Flaky Test Handling

**See**: [Flaky Test Handling in TEST_FRAMEWORK_GUIDE.md](../../TEST_FRAMEWORK_GUIDE.md#flaky-test-handling)

The @DisabledIfEnvironmentVariable pattern is a **general pattern used project-wide**, not simulation-specific.

### Quick Reference

```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: [specific reason]"
)
@Test
void testProbabilisticBehavior() {
    // Runs locally for development, skips in CI
}
```

**When to apply**:
- Probabilistic tests (random failure injection, packet loss)
- Timing-sensitive tests (race conditions, timeouts)
- Tests that fail under CI load
- Tests where determinism would defeat purpose

### Simulation-Specific Examples

**FailureRecoveryTest** (probability + network):
```java
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky: probabilistic test with 30% packet loss")
@Test
void testFailureRecovery() {
    fakeNetwork.setPacketLoss(0.3);
    var result = service.executeWithRetry();
    assertTrue(result.isSuccess());
}
```

**TwoNodeDistributedMigrationTest** (timing + distribution):
```java
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky: timing-sensitive distributed migration simulation")
@Test
void testTwoNodeMigration() {
    // Network simulation with timing dependencies
    // Race conditions between nodes
}
```

For complete guidance (decision criteria, diagnostic procedure, best practices), see **TEST_FRAMEWORK_GUIDE.md § Flaky Test Handling**.

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
    testClock.setTime(0L);  // Start at T=0

    var fakeNetwork = new FakeNetworkChannel("node1", "node2");
    fakeNetwork.setClock(testClock);
    fakeNetwork.setLatency(100);  // 100ms latency

    // Send at T=0
    fakeNetwork.send(message);

    // Advance to T=50ms (message in flight)
    testClock.advance(50);
    assertNull(fakeNetwork.receive());  // Not delivered yet

    // Advance to T=100ms (message delivered)
    testClock.advance(50);  // Total 100ms
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
    var testClock = new TestClock(1000L);  // Start at T=1000ms

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
    var testClock = new TestClock(1000L);  // Start at T=1000ms

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
    testClock.setTime(1000L);
    ghostManager.updatePosition(entityId, insideGhostZone);

    // Verify ghost state activated
    assertTrue(ghostManager.isGhost(entityId));
}
```

---

## CI-Specific Testing Considerations

**See**: [CI/CD Test Distribution in TEST_FRAMEWORK_GUIDE.md](../../TEST_FRAMEWORK_GUIDE.md#cicd-test-distribution)

Tests automatically detect CI environment via `CI=true` environment variable set in GitHub Actions.

### CI vs Local Behavior

| Aspect | Local | CI |
|--------|-------|-----|
| Flaky tests | Run (development value) | Skip (@DisabledIfEnvironmentVariable) |
| Probabilistic tests | Run (verify behavior) | Skip |
| Timing-sensitive tests | Run (may pass) | Skip (contention) |
| Full test suite | Always runs | Always runs (except @Disabled) |

### Running Skipped Tests Locally

```bash
# Run with local behavior (tests marked @DisabledIfEnvironmentVariable run)
mvn test -pl simulation

# Simulate CI behavior locally
CI=true mvn test -pl simulation

# Force run specific skipped test
CI=false mvn test -Dtest=FlakyTest -pl simulation
```

### Simulation-Specific CI Stability Tips

1. **Use TestClock**: Don't rely on System time (general rule - see TEST_FRAMEWORK_GUIDE.md)
2. **FakeNetworkChannel timing**: Account for latency simulation in assertions
3. **GhostStateManager sync**: Allow time for ghost boundary updates to propagate
4. **Migration 2PC timing**: Use TestClock for deterministic timeout testing

For complete CI/CD architecture and test distribution, see **TEST_FRAMEWORK_GUIDE.md § CI/CD Test Distribution**.

---

## Concurrency Testing Patterns

### Overview

Concurrency tests validate thread-safe behavior under multi-threaded access. These tests are particularly susceptible to race conditions and timing issues that manifest differently in CI vs local environments.

**Key Challenge**: Ensuring test assertions see consistent state after concurrent operations complete.

### Pattern: State Stabilization After CountDownLatch

**Problem**: `CountDownLatch.await()` only waits for threads to call `countDown()`, not for internal state updates to propagate through concurrent data structures.

**Anti-Pattern** (AVOID):
```java
@Test
void testConcurrentOperations() throws InterruptedException {
    var latch = new CountDownLatch(10);
    var executor = Executors.newFixedThreadPool(10);

    // Spawn 10 threads that modify shared state
    for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
            try {
                sharedObject.modify();  // Updates ConcurrentHashMap
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    // RACE CONDITION: May read stale values!
    assertEquals(10, sharedObject.getCount());
}
```

**Correct Pattern**:
```java
@Test
void testConcurrentOperations() throws InterruptedException {
    var latch = new CountDownLatch(10);
    var executor = Executors.newFixedThreadPool(10);

    for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
            try {
                sharedObject.modify();
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    // Wait for state stabilization (cache coherency propagation)
    Thread.sleep(50);

    // Now safe to check assertions
    assertEquals(10, sharedObject.getCount());
}
```

**Why This Works**:
1. `latch.await()` ensures threads have finished executing
2. `Thread.sleep(50)` allows ConcurrentHashMap updates to propagate across CPU caches
3. Main thread reads consistent state after propagation completes

**When to Use**:
- Testing concurrent state machines
- Verifying outcomes after multi-threaded operations
- Assertions check shared mutable state (ConcurrentHashMap, AtomicInteger, etc.)

**Timing Guidance**:
- **50ms**: Standard wait for most concurrency tests
- **100ms**: For complex state machines with deep update chains
- **10ms**: Minimum for simple atomic operations

**Why Different in CI**:
- **CI Environment**: High CPU contention, multi-socket CPUs, slower cache coherency
- **Local Environment**: Lower contention, single-socket CPU, faster propagation
- **Result**: Race window wider in CI, more likely to catch issues

### Pattern: Polling for Expected State (Alternative)

For tests where exact timing matters, poll for expected state instead of fixed wait:

```java
@Test
void testEventualConsistency() throws InterruptedException {
    // Trigger concurrent operations
    triggerOperations();

    // Poll up to 1 second for expected state
    long deadline = System.currentTimeMillis() + 1000;
    while (sharedObject.getCount() != 10 && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);  // Check every 10ms
    }

    // Verify reached expected state
    assertEquals(10, sharedObject.getCount());
}
```

**When to Use**:
- Testing eventually consistent systems
- Operations with variable completion time
- When fixed wait is too long for fast cases

**Trade-offs**:
- Pro: More robust, waits only as long as needed
- Con: More complex, changes test semantics to "eventual consistency"
- Con: Can mask underlying synchronization bugs

### Pattern: Verify No Deadlocks

Concurrency tests should verify that operations don't deadlock:

```java
@Test
void testNoDeadlocks() throws InterruptedException {
    int threads = 20;
    var executor = Executors.newFixedThreadPool(threads);
    var latch = new CountDownLatch(threads);

    // Many threads performing overlapping operations
    for (int i = 0; i < threads; i++) {
        executor.submit(() -> {
            try {
                // Operations that might deadlock
                sharedObject.complexOperation();
            } finally {
                latch.countDown();
            }
        });
    }

    // If this times out, potential deadlock
    assertTrue(latch.await(10, TimeUnit.SECONDS),
               "Operations should complete without deadlock");
    executor.shutdown();
}
```

### Pattern: Verify Thread Safety Invariants

```java
@Test
void testThreadSafetyInvariants() throws InterruptedException {
    var queryResults = Collections.synchronizedList(new ArrayList<Integer>());
    var latch = new CountDownLatch(10);
    var executor = Executors.newFixedThreadPool(10);

    // Query threads read state while modification threads write
    for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
            try {
                for (int j = 0; j < 100; j++) {
                    var count = sharedObject.getCount();
                    queryResults.add(count);
                }
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(10, TimeUnit.SECONDS);
    executor.shutdown();
    Thread.sleep(50);  // State stabilization

    // Verify invariants held during concurrent access
    for (var count : queryResults) {
        assertTrue(count >= 0, "Count should never be negative");
        assertTrue(count <= 100, "Count should not exceed max");
    }
}
```

### Common Pitfalls

**Pitfall 1: Assuming Immediate Visibility**
```java
// WRONG: Assumes updates visible immediately
latch.await(...);
assertEquals(expected, actual);  // May fail
```

**Pitfall 2: Using Thread.sleep() Before Latch**
```java
// WRONG: Sleep before latch doesn't help
Thread.sleep(100);
latch.await(...);  // Threads may still be running!
assertEquals(expected, actual);
```

**Pitfall 3: Not Shutting Down Executor**
```java
// WRONG: Executor threads keep running
latch.await(...);
// No executor.shutdown() - threads still active!
assertEquals(expected, actual);  // Interference from running threads
```

### Real-World Example: EntityMigrationStateMachine

**File**: `simulation/.../causality/EntityMigrationStateMachineConcurrencyTest.java`

This test suite validates thread-safe state machine behavior under high concurrency. It applies the state stabilization pattern:

```java
@Test
void testConcurrentInitializeAndTransition() throws InterruptedException {
    // Setup entities and transitions
    var latch = new CountDownLatch(6);
    var executor = Executors.newFixedThreadPool(6);

    // Initialize and transition entities concurrently
    executor.submit(() -> {
        try {
            fsm.initializeOwned(entity2);
            fsm.initializeOwned(entity3);
        } finally {
            latch.countDown();
        }
    });

    // More threads transitioning entities...

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    executor.shutdown();

    // CRITICAL: Wait for FSM state to stabilize
    Thread.sleep(50);

    // Verify consistent state after concurrent operations
    assertEquals(3, fsm.getEntityCount());
    assertEquals(2, fsm.getEntitiesInMigration());
}
```

**History**: This test failed intermittently in CI (66% failure rate) before adding the 50ms stabilization wait. After fix: 100% pass rate (verified 10/10 local runs).

### Best Practices for Concurrency Tests

1. **Always wait for state stabilization** after latch/executor shutdown
2. **Use 50ms as default wait** (adjust if needed for complex state)
3. **Run locally 10x** before CI submission to catch flakiness
4. **Document timing assumptions** in test comments
5. **Shut down executors** before assertions
6. **Use meaningful timeouts** (5-10 seconds for latch, not infinite)

### When NOT to Use Stabilization Wait

- Testing atomic operations only (no state machine updates)
- Testing lock-free algorithms with immediate visibility guarantees
- Testing where timing IS the behavior being tested (use TestClock instead)

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
- ✅ Add 50ms stabilization wait after CountDownLatch in concurrency tests
- ✅ Run concurrency tests 10x locally before CI submission
- ✅ Shut down executors before checking assertions

### Don'ts

- ❌ Use System.currentTimeMillis() or System.nanoTime() in new code
- ❌ Use Thread.sleep() in tests (use TestClock.advance() instead)
- ❌ Allow non-deterministic tests in CI
- ❌ Hide flaky tests without documentation
- ❌ Use fixed ports in tests (use dynamic allocation)
- ❌ Assume CI environment matches local environment
- ❌ Check assertions immediately after CountDownLatch (add stabilization wait)
- ❌ Forget to shut down executors in concurrency tests

---

## Related Documentation

- [H3_DETERMINISM_EPIC.md](H3_DETERMINISM_EPIC.md) - Complete H3 determinism work
- [H3.7_PHASE1_COMPLETION.md](H3.7_PHASE1_COMPLETION.md) - Phase 1 execution report
- [DISTRIBUTED_ANIMATION_ARCHITECTURE.md](DISTRIBUTED_ANIMATION_ARCHITECTURE.md) - Distributed simulation architecture
- [TECHNICAL_DECISION_CONCURRENCY_TEST_FIX.md](TECHNICAL_DECISION_CONCURRENCY_TEST_FIX.md) - Concurrency test flakiness resolution
- [SPRINT_A_RESTART_STATUS.md](SPRINT_A_RESTART_STATUS.md) - Sprint A restart after test fix

---

---

## Document Organization

**This document** (TESTING_PATTERNS.md in simulation/doc/):
- ✅ Simulation-specific patterns (network, migration, ghost, concurrency)
- ✅ Domain-specific code examples and considerations
- ✅ References to general patterns in authoritative guide

**Related documents**:
- **TEST_FRAMEWORK_GUIDE.md** (root) - Authoritative reference for all test framework guidance
- **DISABLED_TESTS_POLICY.md** (this directory) - Inventory of disabled tests and policy
- **H3_DETERMINISM_EPIC.md** - Detailed Clock interface implementation plan

---

**Document Version**: 2.1 (Refactored for clarity, duplication removed)
**Last Updated**: 2026-02-08
**Maintainer**: Simulation Module Team
**Status**: ✅ Focused on simulation-specific patterns, general patterns reference TEST_FRAMEWORK_GUIDE.md
