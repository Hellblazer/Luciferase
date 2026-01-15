# Distributed Architecture - Luciferase Simulation

**Last Updated**: 2026-01-12
**Status**: Current
**Scope**: Multi-bubble distributed simulation with causal consistency

---

## Overview

Luciferase simulation module implements massively distributed 3D animation using emergent consistency boundaries (simulation bubbles) with causal consistency guarantees. Built on PrimeMover discrete event simulation with Tetree spatial indexing.

**Key Architectural Principles**:
- Emergent consistency boundaries (bubbles)
- Causal consistency within interaction range
- Eventual consistency across bubbles
- 2PC entity migration protocol
- Ghost layer boundary synchronization

---

## Table of Contents

1. [High-Level Architecture](#high-level-architecture)
2. [Simulation Bubbles](#simulation-bubbles)
3. [Entity Migration Protocol](#entity-migration-protocol)
4. [Network Architecture](#network-architecture)
5. [Ghost State Management](#ghost-state-management)
6. [Process Coordination](#process-coordination)
7. [Time Management](#time-management)

---

## High-Level Architecture

### Component Stack

```
┌─────────────────────────────────────────────────────┐
│         Application Layer                           │
│  (Multi-bubble coordination, entity factories)      │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Simulation Bubble Layer                     │
│  (BucketScheduler, CausalRollback, Bubble)         │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Migration & Coordination Layer              │
│  (CrossProcessMigration, MigrationCoordinator)     │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Network & Communication Layer               │
│  (GrpcBubbleNetworkChannel, FakeNetworkChannel)    │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Ghost Layer                                 │
│  (GhostStateManager, GhostZoneManager)             │
└─────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────┐
│         Spatial Index Layer                         │
│  (Tetree, Forest, multi-tree support)              │
└─────────────────────────────────────────────────────┘
```

### Key Components

| Component | Responsibility | Location |
|-----------|----------------|----------|
| **VolumeAnimator** | Frame-rate controlled animation loop | `animation/` |
| **BucketScheduler** | 100ms time bucket coordination | `bubble/` |
| **CausalRollback** | Bounded rollback (GGPO-style) | `bubble/` |
| **CrossProcessMigration** | 2PC entity migration orchestrator | `distributed/migration/` |
| **GhostStateManager** | Ghost lifecycle management | `ghost/` |
| **RemoteBubbleProxy** | Remote bubble communication | `distributed/` |
| **VONDiscoveryProtocol** | Voronoi-based area-of-interest | `distributed/` |

---

## Simulation Bubbles

### Concept

A **simulation bubble** is an emergent connected component of interacting entities. Bubbles form dynamically based on entity proximity and interaction range.

**Properties**:
- Causal consistency within bubble (bounded rollback window)
- Eventual consistency across bubbles
- Dynamic formation and dissolution
- 100ms time bucket coordination

### Bucket Scheduler

**Location**: `bubble/BucketSynchronizedController.java`

**Purpose**: Coordinate time bucket execution across distributed nodes.

**Architecture**:
```java
public class BucketSynchronizedController {
    private volatile Clock clock = Clock.system();
    private final long bucketDurationMs = 100;  // 100ms buckets

    // Coordinate bucket execution
    public void executeBucket(long bucketId) {
        var startTime = clock.currentTimeMillis();
        // Execute all events in bucket
        // ...
        var elapsed = clock.currentTimeMillis() - startTime;
        metrics.recordBucketDuration(elapsed);
    }
}
```

**Time Buckets**:
- Duration: 100ms
- Alignment: All nodes synchronize on bucket boundaries
- Execution: Events within bucket execute in causal order
- Rollback: Up to 2 buckets (100-200ms window)

### Causal Rollback

**Purpose**: Handle divergence between nodes within interaction range.

**Algorithm** (GGPO-style):
1. Detect divergence (state mismatch)
2. Roll back to last common state (within 2-bucket window)
3. Re-execute events with corrected inputs
4. Converge to consistent state

**Bounded Window**: 100-200ms (2 buckets)
- Short enough for low latency
- Long enough for network jitter
- Prevents cascading rollbacks

---

## Entity Migration Protocol

### Overview

CrossProcessMigration implements Two-Phase Commit (2PC) protocol for entity migration between bubbles with exactly-once semantics.

**Location**: `distributed/migration/CrossProcessMigration.java`

### Architecture

```java
public class CrossProcessMigration {
    private volatile Clock clock = Clock.system();
    private static final long PHASE_TIMEOUT_MS = 100;  // Per-phase timeout
    private static final long TOTAL_TIMEOUT_MS = 300;  // Total migration timeout

    // C1: Per-entity migration locks (prevent concurrent migrations)
    private final Map<String, ReentrantLock> entityMigrationLocks;

    // Idempotency store (exactly-once semantics)
    private final IdempotencyStore dedup;

    // Metrics tracking
    private final MigrationMetrics metrics;
}
```

### 2PC Protocol Flow

```
┌─────────────────────────────────────────────────────┐
│  Phase 1: PREPARE (Remove from source)             │
├─────────────────────────────────────────────────────┤
│  1. Acquire entity migration lock (C1)              │
│  2. Check idempotency token (duplicate detection)   │
│  3. Remove entity from source bubble                │
│  4. Timeout: 100ms                                  │
│  5. On failure: Release lock, fail fast             │
└─────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│  Phase 2: COMMIT (Add to destination)              │
├─────────────────────────────────────────────────────┤
│  1. Add entity to destination bubble                │
│  2. Timeout: 100ms                                  │
│  3. On success: Release lock, record metrics        │
│  4. On failure: ABORT → Rollback to source (C3)    │
└─────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│  Phase 3: ABORT (Rollback on failure) - C3         │
├─────────────────────────────────────────────────────┤
│  1. Restore entity to source bubble                 │
│  2. Log rollback failure (if restore fails)         │
│  3. Record metrics (rollback count, duration)       │
│  4. Release lock                                    │
└─────────────────────────────────────────────────────┘
```

### Key Guarantees

**C1: Entity Migration Locks**
- Per-entity ReentrantLock prevents concurrent migrations of same entity
- Prevents race conditions (entity migrating to multiple destinations)
- Lock timeout: 50ms (fast fail if contention detected)

**C2: Idempotency Tokens**
- UUID-based tokens for exactly-once semantics
- IdempotencyStore tracks processed tokens (30s expiration)
- Duplicate requests return cached result (no re-execution)

**C3: Rollback Failure Handling**
- If ABORT phase fails (source bubble unreachable), log failure
- Metrics track rollback failures separately
- Manual intervention required for orphaned entities

### Timeout Handling

**Per-Phase Timeout**: 100ms
- PREPARE phase: 100ms to remove from source
- COMMIT phase: 100ms to add to destination

**Total Timeout**: 300ms (includes ABORT if needed)

**Timeout Detection**:
```java
private boolean isTimedOut(MigrationTransaction tx) {
    var elapsed = clock.currentTimeMillis() - tx.startTime();
    return elapsed > TOTAL_TIMEOUT_MS;
}
```

### Idempotency Store

**Location**: `distributed/migration/IdempotencyStore.java`

**Purpose**: Track processed operations to prevent duplicate execution.

**Architecture**:
```java
public class IdempotencyStore {
    private volatile Clock clock = Clock.system();
    private static final long TOKEN_EXPIRATION_MS = 30_000;  // 30s

    // Token cache with expiration
    private final ConcurrentHashMap<UUID, TokenEntry> tokens;

    public boolean isProcessed(UUID token) {
        var entry = tokens.get(token);
        if (entry == null) return false;

        // Check expiration
        var age = clock.currentTimeMillis() - entry.timestamp();
        return age < TOKEN_EXPIRATION_MS;
    }
}
```

**Token Expiration**: 30 seconds
- Balances memory usage vs duplicate detection window
- Sufficient for network retry scenarios
- Automatic cleanup of expired tokens

### Migration Metrics

**Location**: `distributed/migration/MigrationMetrics.java`

**Tracked Metrics**:
- Total migrations attempted
- Successful migrations
- Failed migrations (by phase)
- Rollbacks executed
- Rollback failures (C3 violations)
- Average migration duration
- P50/P95/P99 latencies

---

## Network Architecture

### Communication Channels

#### GrpcBubbleNetworkChannel

**Location**: `distributed/network/GrpcBubbleNetworkChannel.java`

**Purpose**: Production network communication between bubbles using gRPC.

**Features**:
- Bidirectional streaming
- Protobuf message serialization
- Connection pooling
- Retry with exponential backoff
- Load balancing

**Configuration**:
```java
var channel = new GrpcBubbleNetworkChannel(
    sourceNodeId,
    destinationNodeId,
    grpcHost,
    grpcPort
);
```

#### FakeNetworkChannel

**Location**: `distributed/network/FakeNetworkChannel.java`

**Purpose**: Controlled network simulation for testing.

**Features**:
- Configurable packet loss (0.0 - 1.0)
- Configurable latency (milliseconds)
- Deterministic time control (Clock injection)
- In-memory message queue
- No actual network I/O

**Testing Usage**:
```java
var testClock = new TestClock();
var fakeNetwork = new FakeNetworkChannel("node1", "node2");
fakeNetwork.setClock(testClock);

// Simulate 30% packet loss, 50ms latency
fakeNetwork.setPacketLoss(0.3);
fakeNetwork.setLatency(50);
```

### Message Protocol

**Location**: `distributed/migration/MigrationProtocolMessages.java`

**Message Types** (Java records):
- `PrepareRequest(entityId, sourceId, destinationId, timestamp)`
- `PrepareResponse(success, entityData, timestamp)`
- `CommitRequest(entityId, entityData, timestamp)`
- `CommitResponse(success, timestamp)`
- `AbortRequest(entityId, timestamp)`
- `AbortResponse(success, timestamp)`

**Timestamp Injection**:
- All records use `VonMessageFactory.currentTimeMillis()` for timestamp generation
- Enables deterministic testing with TestClock
- Maintains 2PC protocol ordering semantics

---

## Ghost State Management

### Overview

Ghost layer provides boundary synchronization between bubbles. Entities near bubble boundaries exist in multiple bubbles as "ghost entities" for smooth handoff.

**Location**: `ghost/GhostStateManager.java`

### Architecture

```java
public class GhostStateManager {
    private volatile Clock clock = Clock.system();

    // Ghost entity lifecycle tracking
    private final Map<String, GhostEntry> ghostEntities;

    // Ghost entry with timestamps
    record GhostEntry(String entityId, long becameGhostAt, long lastSync) {}

    public void markAsGhost(String entityId, long timestamp) {
        ghostEntities.put(entityId, new GhostEntry(entityId, timestamp, timestamp));
    }

    public long getGhostDuration(String entityId) {
        var entry = ghostEntities.get(entityId);
        return clock.currentTimeMillis() - entry.becameGhostAt();
    }
}
```

### Ghost Zone

**Definition**: Spatial region near bubble boundaries where entities are replicated across bubbles.

**Properties**:
- Zone width: Configurable (typically 10-20 units)
- Entry detection: Spatial query against bubble boundary
- Exit detection: Entity leaves zone or migrates

### Ghost Synchronization

**Protocol**:
1. Entity enters ghost zone (spatial detection)
2. Source bubble notifies neighboring bubbles (ghost creation)
3. Ghost entity synchronized at sub-frame rate (60+ FPS)
4. Entity leaves zone → ghost cleanup
5. Entity migrates → ghost becomes authoritative

**Synchronization Frequency**: Sub-frame (100 FPS animation → 10ms sync interval)

### Ghost Lifecycle

```
┌─────────────┐
│   LOCAL     │ Entity fully owned by bubble
│   ENTITY    │
└──────┬──────┘
       │ Enters ghost zone
       ▼
┌─────────────┐
│   GHOST     │ Entity replicated to neighbor bubbles
│  ENTITY     │ (synchronized at sub-frame rate)
└──────┬──────┘
       │ Migration or zone exit
       ▼
┌─────────────┐
│  MIGRATED / │ Entity removed or becomes local again
│   REMOVED   │
└─────────────┘
```

---

## Process Coordination

### ProcessRegistry

**Location**: `distributed/ProcessRegistry.java`

**Purpose**: Track active simulation processes (nodes) in distributed deployment.

**Architecture**:
```java
public class ProcessRegistry {
    private volatile Clock clock = Clock.system();

    // Registered processes
    private final Map<String, ProcessMetadata> processes;

    record ProcessMetadata(
        String processId,
        String host,
        int port,
        long registeredAt,
        long lastHeartbeat
    ) {}

    public void registerProcess(ProcessMetadata metadata) {
        processes.put(metadata.processId(), metadata);
    }

    public void heartbeat(String processId) {
        var metadata = processes.get(processId);
        processes.put(processId, metadata.withLastHeartbeat(clock.currentTimeMillis()));
    }
}
```

### ProcessCoordinator

**Location**: `distributed/ProcessCoordinator.java`

**Purpose**: Coordinate distributed simulation across multiple processes.

**Responsibilities**:
- Process registration and discovery
- Heartbeat monitoring (detect failures)
- Topology updates (process join/leave)
- Load balancing decisions

**Heartbeat Protocol**:
- Interval: 5 seconds
- Timeout: 15 seconds (3 missed heartbeats)
- Action on timeout: Mark process as failed, redistribute entities

---

## Time Management

### Clock Interface

**Location**: `distributed/integration/Clock.java`

**Purpose**: Unified abstraction for time queries, enabling deterministic testing.

**Interface**:
```java
public interface Clock {
    long currentTimeMillis();  // Milliseconds since epoch

    default long nanoTime() {  // High-resolution time
        return System.nanoTime();
    }

    static Clock system() {  // Production: System.* wrapper
        return System::currentTimeMillis;
    }

    static Clock fixed(long fixedTime) {  // Testing: Fixed time
        return () -> fixedTime;
    }
}
```

### TestClock (Deterministic Testing)

**Location**: `distributed/integration/TestClock.java`

**Purpose**: Controllable time source for deterministic testing.

**Features**:
- Absolute mode: Returns exact set time (default)
- Relative mode: Adds offset to System.*
- Time advancement: `advance(long)`, `advanceNanos(long)`
- Thread-safe: AtomicLong-based state
- Dual time tracking: Millis + nanos with 1:1,000,000 ratio

**Usage**:
```java
var testClock = new TestClock();
testClock.setMillis(1000L);  // T=1000ms

// Inject into components
migration.setClock(testClock);
ghostManager.setClock(testClock);
fakeNetwork.setClock(testClock);

// Control time progression
testClock.advance(100);  // T=1100ms
```

### VonMessageFactory (Record Class Time Injection)

**Location**: `distributed/integration/VonMessageFactory.java`

**Purpose**: Inject time into Java record classes (which cannot have mutable fields).

**Pattern**:
```java
public record MigrationMessage(String entityId, long timestamp, Point3D position) {
    public MigrationMessage {
        // Compact constructor uses factory-injected time
        timestamp = VonMessageFactory.currentTimeMillis();
    }
}

// In tests
VonMessageFactory.setClock(testClock);
var msg = new MigrationMessage("entity1", 0, position);  // timestamp from testClock
```

---

## Performance Characteristics

### Latency Targets

| Operation | Target | Typical |
|-----------|--------|---------|
| Bucket processing | < 100ms | ~50ms |
| Entity migration (2PC) | < 300ms | ~150ms |
| Ghost synchronization | < 10ms | ~5ms |
| Network message RTT (local) | < 5ms | ~2ms |
| Rollback window | 100-200ms | 150ms |

### Throughput Targets

| Metric | Target | Typical |
|--------|--------|---------|
| Migrations per second (per node) | 100+ | ~200 |
| Ghost sync updates per second | 1000+ | ~2000 |
| Entities per bubble | 10,000+ | ~5000 |
| Concurrent migrations | 50+ | ~100 |

### Scalability

**Horizontal Scaling**:
- Add more nodes → more bubbles
- Entity distribution via spatial hashing
- Ghost layer provides boundary coherence
- Linear scalability up to network bandwidth limits

**Vertical Scaling**:
- More entities per bubble (up to spatial index limits)
- Higher animation frame rate (up to CPU limits)
- Larger ghost zones (more overlap, smoother handoff)

---

## Consistency Model

### Causal Consistency Within Bubble

**Guarantee**: All events within a bubble respect causality.

**Mechanism**:
- Vector clocks track causality
- Bucket scheduler enforces causal order
- Rollback restores causality on divergence

### Eventual Consistency Across Bubbles

**Guarantee**: Bubbles eventually converge on shared state (ghost entities).

**Mechanism**:
- Ghost layer synchronization (sub-frame rate)
- Migration protocol transfers ownership
- No global synchronization required

### Bounded Inconsistency

**Guarantee**: Inconsistency bounded by rollback window (100-200ms).

**Mechanism**:
- 2-bucket rollback limit prevents cascading rollbacks
- Fast divergence detection (bucket boundary checks)
- Causal rollback restores consistency

---

## Fault Tolerance

### Network Failures

**Detection**: Timeout on network operations (100ms per phase)

**Recovery**:
- Migration 2PC rolls back on commit failure
- Idempotency tokens prevent duplicate execution
- Metrics track failure rates

### Process Failures

**Detection**: Heartbeat timeout (15 seconds = 3 missed heartbeats)

**Recovery**:
- ProcessCoordinator marks process as failed
- Entity redistribution to surviving processes
- Ghost layer handles boundary updates

### Rollback Failures (C3)

**Detection**: ABORT phase fails (source unreachable)

**Logging**:
```java
log.error("Rollback failed for entity {}: source bubble unreachable", entityId);
metrics.recordRollbackFailure(entityId);
```

**Recovery**: Manual intervention required
- Review metrics to identify orphaned entities
- Use administrative tools to resolve state
- Consider entity recreation or reconciliation

---

## Configuration

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `BUCKET_DURATION_MS` | 100 | Time bucket duration |
| `ROLLBACK_WINDOW_BUCKETS` | 2 | Max rollback distance (200ms) |
| `PHASE_TIMEOUT_MS` | 100 | Per-phase migration timeout |
| `TOTAL_TIMEOUT_MS` | 300 | Total migration timeout |
| `TOKEN_EXPIRATION_MS` | 30000 | Idempotency token lifetime |
| `HEARTBEAT_INTERVAL_MS` | 5000 | Process heartbeat frequency |
| `HEARTBEAT_TIMEOUT_MS` | 15000 | Process failure detection |
| `GHOST_ZONE_WIDTH` | 15.0 | Ghost zone boundary width |

### Tuning Guidelines

**Low Latency** (< 100ms target):
- Reduce BUCKET_DURATION_MS to 50ms
- Reduce PHASE_TIMEOUT_MS to 50ms
- Increase network bandwidth

**High Throughput** (> 1000 migrations/sec):
- Increase BUCKET_DURATION_MS to 200ms
- Increase migration thread pool size
- Reduce ghost zone width (less overlap)

**High Reliability** (fault tolerance):
- Increase ROLLBACK_WINDOW_BUCKETS to 3
- Reduce HEARTBEAT_TIMEOUT_MS to 10s
- Enable aggressive metrics tracking

---

## Related Documentation

- [H3_DETERMINISM_EPIC.md](H3_DETERMINISM_EPIC.md) - Deterministic testing with Clock interface
- [SIMULATION_BUBBLES.md](SIMULATION_BUBBLES.md) - Detailed bubble architecture
- [DISTRIBUTED_ANIMATION_ARCHITECTURE.md](DISTRIBUTED_ANIMATION_ARCHITECTURE.md) - Complete animation framework
- [TESTING_PATTERNS.md](TESTING_PATTERNS.md) - Testing best practices

---

**Document Version**: 1.0
**Last Updated**: 2026-01-12
**Author**: knowledge-tidier (Haiku)
**Status**: Current
