# Distributed Coordination Patterns

**Last Updated**: 2026-01-15
**Phase**: Phase 4 Complete
**Status**: Production

## Overview

This document describes the distributed coordination patterns used in Luciferase's
multi-process simulation architecture, following Phase 4 refactoring.

Phase 4 replaced custom heartbeat monitoring with Fireflies membership service and
converted all coordination logic to event-driven Prime-Mover @Entity pattern.

## Architecture Principles

### 1. Event-Driven Coordination

All distributed coordination uses Prime-Mover's @Entity pattern for deterministic,
non-blocking execution.

**Pattern**: Orchestrator + @Entity

```java
public class ProcessCoordinator {
    private final ProcessCoordinatorEntity entity;
    private final RealTimeController controller;

    public ProcessCoordinator(VonTransport transport, MembershipView<?> membershipView) {
        this.controller = new RealTimeController("ProcessCoordinator");
        this.entity = new ProcessCoordinatorEntity(/* suppliers */);
        Kairos.setController(controller);
    }

    public void start() {
        entity.coordinationTick();  // Start event loop
        controller.start();
    }
}
```

**@Entity Inner Class Pattern**:

```java
@Entity
public static class ProcessCoordinatorEntity {
    private static final long POLL_INTERVAL_NS = 10_000_000; // 10ms

    public void coordinationTick() {
        // 1. Check state
        // 2. Perform coordination tasks
        // 3. Schedule next tick
        Kronos.sleep(POLL_INTERVAL_NS);
        this.coordinationTick(); // Recursive scheduling
    }
}
```

**Key Points**:
- @Entity on inner class (avoids Prime-Mover generic issues)
- @NonEvent on all getters
- Kronos.sleep() for polling (not Thread.sleep())
- Recursive event scheduling (this.coordinationTick())
- Suppliers for access to outer class state

### 2. Fireflies Integration

Failure detection and membership management uses Fireflies MembershipView instead
of custom heartbeat monitoring.

**Pattern**: MembershipView + FirefliesViewMonitor

```java
public class ProcessCoordinator {
    private final MembershipView<UUID> membershipView;
    private FirefliesViewMonitor viewMonitor;

    public void start() throws Exception {
        // Initialize Fireflies view monitor
        viewMonitor = new FirefliesViewMonitor(membershipView);
        viewMonitor.addViewChangeListener(change -> handleViewChange(change));
    }

    private void handleViewChange(ViewChange<?> change) {
        // Members left = process failures (automatic failure detection)
        for (var member : change.left()) {
            UUID processId = extractProcessId(member);
            unregisterProcess(processId);
        }

        // Members joined = new processes
        for (var member : change.joined()) {
            UUID processId = extractProcessId(member);
            // Process will call registerProcess() separately
        }
    }
}
```

**Benefits**:
- No periodic heartbeat polling
- Instant failure detection
- No heartbeat network traffic
- No ScheduledExecutorService overhead

### 3. Ring Ordering Coordinator Selection

Coordinator selection uses deterministic ring ordering based on sorted UUID order.

**Pattern**: Ring Ordering

```java
public UUID getCoordinator() {
    var members = viewMonitor.getCurrentMembers();
    return members.stream()
                  .filter(m -> m instanceof UUID)
                  .map(m -> (UUID) m)
                  .sorted(Comparator.comparing(UUID::toString))
                  .findFirst()
                  .orElse(null);
}

public boolean isCoordinator() {
    var coordinator = getCoordinator();
    return transport.getLocalId().equals(coordinator);
}
```

**Characteristics**:
- **Deterministic**: Same view always produces same coordinator
- **Zero network overhead**: Purely local computation
- **Random distribution**: UUID sorting provides even distribution
- **Instant convergence**: No election rounds needed

**Compared to Election Protocols**:
- No multi-round consensus required
- No network coordination needed
- No election state machines
- Instant determination on view changes

### 4. Rate-Limited Topology Broadcasting

Topology updates are broadcast with rate-limiting to prevent network storms.

**Pattern**: Rate-Limited Broadcasting

```java
@Entity
public static class ProcessCoordinatorEntity {
    private static final long BROADCAST_COOLDOWN_MS = 1000; // 1 second

    private long lastBroadcastTimeMs = 0;

    public void coordinationTick() {
        var currentTopology = topologySupplier.get();

        if (topologyChanged(currentTopology)) {
            var currentTime = clockSupplier.get();
            var timeSinceLastBroadcast = currentTime - lastBroadcastTimeMs;

            if (timeSinceLastBroadcast >= BROADCAST_COOLDOWN_MS) {
                // Broadcast topology update
                broadcastCallback.accept(currentTopology);
                lastBroadcastTimeMs = currentTime;
                lastBroadcastTopology = List.copyOf(currentTopology);
            }
        }

        Kronos.sleep(POLL_INTERVAL_NS);
        this.coordinationTick();
    }
}
```

**Characteristics**:
- 10ms polling detects all topology changes
- 1 second cooldown limits broadcast rate
- Prevents broadcast storms during cluster churn
- Maintains responsiveness (10ms latency)

### 5. Deterministic Time Handling

All time-dependent operations use injectable Clock interface for deterministic testing.

**Pattern**: Clock Injection

```java
public class ProcessCoordinator {
    private volatile Clock clock = Clock.system();

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    // Entity receives clock supplier
    this.entity = new ProcessCoordinatorEntity(
        /* ... */,
        () -> clock.currentTimeMillis()
    );
}
```

**Testing Pattern**:

```java
@Test
void testRateLimiting() {
    var testClock = new TestClock();
    coordinator.setClock(testClock);

    // Advance time deterministically
    testClock.setMillis(1000);
    // ... test logic ...
}
```

**Benefits**:
- Reproducible time-dependent tests
- No timing flakiness
- Time-travel debugging
- Consistent CI results

## Common Patterns

### Pattern: Create ProcessCoordinator

```java
// Create with transport and membership view
var transport = /* VonTransport */;
var membershipView = /* MembershipView<UUID> */;

var coordinator = new ProcessCoordinator(transport, membershipView);

// Start coordinator (begins event-driven coordination)
coordinator.start();

// Register processes with bubbles
coordinator.registerProcess(processId, bubbleIds);

// Stop gracefully
coordinator.stop();
```

### Pattern: Mock Fireflies View for Testing

```java
// Create mock view
var mockView = new MockMembershipView<UUID>();

// Set members (auto-generates ViewChange events)
mockView.setMembers(Set.of(process1, process2, process3));

// Simulate failures
mockView.setMembers(Set.of(process1, process3)); // process2 failed
```

### Pattern: Test Coordinator Selection

```java
@Test
void testCoordinatorSelection() {
    // Create 3-process cluster
    var uuid1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var uuid2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    var uuid3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    // Set view with all members
    mockView.setMembers(Set.of(uuid1, uuid2, uuid3));

    // Verify coordinator (first UUID in sorted order)
    assertEquals(uuid1, coordinator.getCoordinator());
    assertTrue(coordinator.isCoordinator()); // for uuid1
}
```

### Pattern: Test View Change Handling

```java
@Test
void testProcessFailure() {
    // Initial view with all processes
    mockView.setMembers(Set.of(process1, process2, process3));

    // Register processes
    coordinator.registerProcess(process1, bubbles1);
    coordinator.registerProcess(process2, bubbles2);
    coordinator.registerProcess(process3, bubbles3);

    // Simulate process2 failure
    mockView.setMembers(Set.of(process1, process3));

    // Wait for view change processing
    Thread.sleep(200);

    // Verify process2 unregistered
    assertNull(coordinator.getRegistry().getProcess(process2));
}
```

## Migration from Phase 3 to Phase 4

If you have code using old ProcessCoordinator patterns:

### Change 1: Constructor Signature

**Before**:
```java
var coordinator = new ProcessCoordinator(transport);
```

**After**:
```java
var coordinator = new ProcessCoordinator(transport, membershipView);
```

### Change 2: No Heartbeat Methods

**Before**:
```java
coordinator.updateHeartbeat(processId);
boolean alive = coordinator.isAlive(processId);
```

**After**:
```java
// Heartbeat methods removed - use Fireflies view changes
// Failure detection automatic via handleViewChange()
```

### Change 3: No Election Protocol

**Before**:
```java
coordinator.conductElection();
UUID coordinator = coordinator.getCurrentCoordinator();
```

**After**:
```java
// Election removed - use ring ordering
UUID coordinator = coordinator.getCoordinator();
boolean isCoord = coordinator.isCoordinator();
```

### Change 4: Event-Driven Access

**Before**:
```java
// Blocking methods
coordinator.broadcastTopologyUpdate(bubbles);
```

**After**:
```java
// Non-blocking, event-driven
// Topology updates automatic on registry changes
// Broadcasts rate-limited (1/second)
coordinator.registerProcess(processId, bubbles); // Triggers update
```

## Testing Guidelines

### Unit Tests

Use MockMembershipView for controlled view manipulation:

```java
@Test
void testMyFeature() {
    var mockView = new MockMembershipView<UUID>();
    var coordinator = new ProcessCoordinator(transport, mockView);
    coordinator.start();

    // Set initial view
    mockView.setMembers(Set.of(process1, process2));

    // Test logic
    // ...

    coordinator.stop();
}
```

### Integration Tests

Use TestProcessCluster for multi-process scenarios:

```java
@Test
void testMultiProcess() throws Exception {
    var cluster = new TestProcessCluster(3, 2); // 3 processes, 2 bubbles each
    cluster.start();

    // Test multi-process coordination
    // ...

    cluster.stop();
}
```

### Deterministic Time Testing

Inject TestClock for reproducible time-dependent tests:

```java
@Test
void testRateLimiting() {
    var testClock = new TestClock();
    coordinator.setClock(testClock);

    // First update (should broadcast)
    coordinator.registerProcess(process1, bubbles1);
    testClock.setMillis(0);

    // Second update (within cooldown, skip)
    coordinator.registerProcess(process2, bubbles2);
    testClock.setMillis(500); // < 1000ms cooldown

    // Third update (after cooldown, broadcast)
    coordinator.registerProcess(process3, bubbles3);
    testClock.setMillis(1100); // > 1000ms cooldown
}
```

## Performance Characteristics

### Coordination Overhead

- **Polling**: 10ms interval (0.01% CPU)
- **Failure Detection**: Instant (Fireflies notification)
- **Coordinator Selection**: O(N log N) on view changes only
- **Topology Broadcasting**: Rate-limited (max 1/second)

### Memory Footprint

- **Per Coordinator**: ~500 bytes (entity state)
- **No Thread Pools**: Eliminated ScheduledExecutorService
- **No Heartbeat Storage**: Eliminated timestamp maps

### Network Traffic

- **No Heartbeats**: Zero periodic traffic
- **Topology Updates**: Only on changes (rate-limited)
- **View Changes**: Handled by Fireflies (external)

## Best Practices

1. **Always inject MembershipView** for Fireflies integration
2. **Use @Entity pattern** for event-driven coordination
3. **Inject Clock** for deterministic testing
4. **Use ring ordering** for coordinator selection
5. **Rate-limit broadcasts** to prevent storms
6. **Test with MockMembershipView** for unit tests
7. **Test with TestProcessCluster** for integration tests
8. **Never use Thread.sleep()** in @Entity methods (use Kronos.sleep())
9. **Mark all getters @NonEvent** in @Entity classes
10. **Use recursive scheduling** for continuous coordination

## References

### Phase 4 Beads

- Luciferase-rap1: Phase 4 epic
- Luciferase-3qdd: Delete CoordinatorElectionProtocol
- Luciferase-k5z4: Delete heartbeat monitoring
- Luciferase-6s7v: Refactor ProcessRegistry
- Luciferase-wdc7: Integrate MembershipView
- Luciferase-32sn: Update tests for Fireflies
- Luciferase-id8f: Event-driven topology broadcasting
- Luciferase-5hlx: Update all call sites
- Luciferase-ulab: Multi-process coordination tests
- Luciferase-jdy2: Performance validation
- Luciferase-gu8f: Documentation updates (this document)

### Related Documentation

- `PHASE_4_IMPLEMENTATION_PLAN.md`: Original planning document
- `PHASE_4_PERFORMANCE_VALIDATION.md`: Performance metrics
- `ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md`: Architectural decisions
- `CLAUDE.md`: Development patterns and guidelines

### Code References

- `ProcessCoordinator.java`: Main coordination class
- `ProcessCoordinatorEntity`: Event-driven entity
- `FirefliesViewMonitor.java`: Fireflies integration
- `MockMembershipView.java`: Testing mock
- `TestProcessCluster.java`: Multi-process test infrastructure
- `MultiProcessCoordinationTest.java`: Phase 4 integration tests

## History

- **2026-01-15**: Phase 4.3.3 - Documentation complete
- **2026-01-15**: Phase 4.3.2 - Performance validation
- **2026-01-15**: Phase 4.3.1 - Multi-process tests
- **2026-01-15**: Phase 4.2.4 - All call sites updated
- **2026-01-15**: Phase 4.2.3 - Rate-limited broadcasting
- **2026-01-15**: Phase 4.2.2 - CrossProcessMigration event-driven
- **2026-01-15**: Phase 4.2.1 - ProcessCoordinatorEntity created
- **2026-01-15**: Phase 4.1.5 - Fireflies integration tests
- **2026-01-15**: Phase 4.1.4 - MembershipView integration
- **2026-01-15**: Phase 4.1.3 - ProcessRegistry refactored
- **2026-01-15**: Phase 4.1.2 - Heartbeat monitoring deleted
- **2026-01-15**: Phase 4.1.1 - CoordinatorElectionProtocol deleted
