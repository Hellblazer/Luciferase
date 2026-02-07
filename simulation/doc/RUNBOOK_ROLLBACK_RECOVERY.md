# Rollback Recovery Runbook

**Version**: 1.0
**Date**: 2026-02-06
**Status**: Production
**Owner**: Operations Team

## Overview

This runbook provides operational procedures for recovering from entity migration rollback failures in the Luciferase distributed simulation system. When a migration fails and the rollback (ABORT phase) also fails, the entity becomes "orphaned" - not present in either source or destination bubble.

**Critical Severity**: Orphaned entities represent data loss and require immediate manual intervention.

---

## 1. DETECTION

### 1.1 Automated Alerts

**Primary Alert**: Rollback failure count > 0
```
Alert: Migration Rollback Failure
Severity: CRITICAL
Metric: migration.rollback.failures > 0
Action: Follow this runbook immediately
```

**Secondary Alert**: Orphaned entity detection
```
Alert: Orphaned Entities Detected
Severity: CRITICAL
Metric: migration.orphaned.entities.count > 0
Action: Follow Section 3 (Manual Recovery)
```

### 1.2 Log Patterns

**Search for ERROR-level messages**:
```bash
# Search application logs for critical rollback failures
grep "ABORT/Rollback FAILED" /var/log/luciferase/simulation.log

# Expected pattern:
ERROR c.h.l.s.d.m.CrossProcessMigration - ABORT/Rollback FAILED for entity {entityId} - CRITICAL: Manual intervention required (txn={txnId}, source={sourceId}, dest={destId}, snapshot=[epoch={epoch}, position={x,y,z}], reason={reason})
```

**Log Fields**:
- `entityId`: Unique entity identifier (orphaned entity)
- `txnId`: Transaction UUID (for audit trail correlation)
- `source`: Source bubble UUID
- `dest`: Destination bubble UUID
- `snapshot`: Last known state (`epoch`, `position`)
- `reason`: Failure reason (COMMIT_FAILED, PREPARE_TIMEOUT, COMMIT_TIMEOUT, etc.)

### 1.3 Recovery State API Query

**Use getRecoveryState() for admin tooling**:

```java
// Query recovery state programmatically
RecoveryState state = crossProcessMigration.getRecoveryState();

System.out.println("Orphaned Entities: " + state.orphanedEntities().size());
System.out.println("Active Transactions: " + state.activeTransactions());
System.out.println("Rollback Failures: " + state.rollbackFailures());
System.out.println("Concurrent Migrations: " + state.concurrentMigrations());

// List orphaned entity IDs
state.orphanedEntities().forEach(entityId -> {
    System.out.println("ORPHANED: " + entityId);
});
```

**RecoveryState Fields**:
- `orphanedEntities`: Set of entity IDs that failed rollback
- `activeTransactions`: Count of currently in-flight migrations
- `rollbackFailures`: Total rollback failure count since system start
- `concurrentMigrations`: Current concurrent migration attempts

**Note**: Snapshot is not atomic. If migrations complete during the query, values may be slightly inconsistent across fields. This is acceptable for admin monitoring.

---

## 2. DIAGNOSIS

### 2.1 Determine Entity Last Known State

**From Log Entry**:
```
snapshot=[epoch=42, position=Point3D [x=10.5, y=20.3, z=15.7]]
```

**Key Information**:
- `epoch`: Causality epoch (for event ordering)
- `position`: Last known 3D spatial coordinates

### 2.2 Identify Source and Destination Bubbles

**From Log Entry**:
```
source={sourceId}, dest={destId}
```

**Verification Steps**:
1. **Check if bubbles are still active**:
   ```bash
   # Query bubble health status
   curl http://bubble-{sourceId}:8080/health
   curl http://bubble-{destId}:8080/health
   ```

2. **Verify entity NOT present in either bubble**:
   ```java
   // Query source bubble
   boolean inSource = sourceBubble.entities().contains(entityId);

   // Query destination bubble
   boolean inDest = destBubble.entities().contains(entityId);

   // Both should be false for orphaned entity
   assert !inSource && !inDest : "Entity not orphaned - found in bubble";
   ```

### 2.3 Check Migration Log

**Query MigrationLog for audit trail**:
```java
List<MigrationEvent> events = migrationLog.getEventsForEntity(entityId);

for (MigrationEvent event : events) {
    System.out.printf("Time: %d, Phase: %s, Status: %s, TxnId: %s%n",
        event.timestamp(), event.phase(), event.status(), event.txnId());
}
```

**Expected Pattern for Rollback Failure**:
```
Time: 1000, Phase: PREPARE, Status: SUCCESS, TxnId: abc-123
Time: 1050, Phase: COMMIT, Status: FAILED, TxnId: abc-123
Time: 1100, Phase: ABORT, Status: FAILED, TxnId: abc-123  ← Rollback failed
```

---

## 3. MANUAL RECOVERY

### 3.1 Entity Restoration Procedure

**Prerequisites**:
- Entity snapshot from log entry (epoch, position)
- Source bubble ID
- Source bubble is healthy and reachable

**Steps**:

**A. Prepare Entity Reconstruction**:
```java
// From log entry
UUID sourceId = UUID.fromString("{sourceId}");
long epoch = {epoch};
Point3D position = new Point3D({x}, {y}, {z});
String entityId = "{entityId}";
```

**B. Recreate Entity at Source**:
```java
// Access source bubble
Bubble sourceBubble = getBubbleById(sourceId);

// Reconstruct entity from snapshot
Entity entity = new Entity(entityId);
entity.setPosition(position);
entity.setEpoch(epoch);

// Re-add to source bubble
sourceBubble.addEntity(entity);

// Verify addition
assert sourceBubble.entities().contains(entityId);
```

**C. Verify Entity State**:
```java
// Query entity from source
Entity recovered = sourceBubble.getEntity(entityId);

// Validate position and epoch match snapshot
assert recovered.getPosition().equals(position);
assert recovered.getEpoch() == epoch;
```

### 3.2 State Reconciliation

**Scenario**: Entity may have pending events or interactions that occurred during migration.

**Steps**:

**A. Check Event Log for Pending Events**:
```java
// Query events for entity during migration window
long migrationStart = /* from txnId timestamp */;
long migrationEnd = migrationStart + 300; // 300ms migration window

List<Event> pendingEvents = eventLog.getEventsDuring(entityId, migrationStart, migrationEnd);
```

**B. Replay Events if Necessary**:
```java
// Replay events in causal order (by epoch)
pendingEvents.stream()
    .sorted(Comparator.comparingLong(Event::getEpoch))
    .forEach(event -> entity.applyEvent(event));
```

**C. Notify Neighbor Bubbles**:
```java
// If entity is visible to neighbors (ghost layer)
List<Bubble> neighbors = vonTopology.getNeighbors(sourceId);

for (Bubble neighbor : neighbors) {
    // Send ghost update
    ghostChannel.queueGhost(neighbor.getId(), entity.toGhost());
}
ghostChannel.sendBatch();
```

### 3.3 IdempotencyStore Cleanup

**Problem**: Failed migration may leave stale idempotency token.

**Steps**:

**A. Check for Stale Token**:
```java
UUID txnId = UUID.fromString("{txnId}"); // from log entry
boolean tokenExists = idempotencyStore.contains(txnId);
```

**B. Remove Stale Token (if migration will retry)**:
```java
// Allow retry by removing token
idempotencyStore.remove(txnId);

// Verify removal
assert !idempotencyStore.contains(txnId);
```

**Note**: Tokens auto-expire after 5 minutes. Manual removal only needed for immediate retry.

### 3.4 MigrationMetrics Reset

**After manual recovery completes**:

```java
// OPTIONAL: Reset orphaned entity tracking
// (Do NOT reset if you want to track historical failures)

// Clear orphaned entity from tracking
migrationMetrics.clearOrphanedEntity(entityId);

// Verify removal
RecoveryState state = crossProcessMigration.getRecoveryState();
assert !state.orphanedEntities().contains(entityId);
```

**Warning**: Resetting metrics loses historical failure tracking. Only reset after confirming recovery is complete and documented.

---

## 4. MONITORING

### 4.1 Alert Configurations

**Critical Alerts** (PagerDuty / Opsgenie):

```yaml
# Rollback failure detection
- name: migration_rollback_failure
  metric: migration.rollback.failures
  condition: value > 0
  severity: CRITICAL
  notify: ops-team
  runbook: https://wiki/Runbook-Rollback-Recovery

# Orphaned entity detection
- name: orphaned_entities_detected
  metric: migration.orphaned.entities.count
  condition: value > 0
  severity: CRITICAL
  notify: ops-team
  action: Check logs, run getRecoveryState()
```

**Warning Alerts** (Slack / Email):

```yaml
# Migration success rate degradation
- name: migration_success_rate_low
  metric: migration.success.rate
  condition: value < 0.95  # <95% success
  severity: WARNING
  notify: dev-team
  action: Investigate failure reasons

# High concurrent migration load
- name: high_concurrent_migrations
  metric: migration.concurrent.count
  condition: value > 100
  severity: WARNING
  notify: dev-team
  action: Review capacity planning
```

### 4.2 Dashboard Metrics

**Migration Health Dashboard**:

```
Migration Success Rate:       98.5% (last 1h)
Total Migrations:             1,234
Rollback Failures:            2 ⚠️
Orphaned Entities:            2 🚨
Active Transactions:          15
Concurrent Migrations (P99):  42

Recent Failures:
  - Entity: player-42, Reason: COMMIT_FAILED, Time: 10:23:45
  - Entity: npc-789, Reason: COMMIT_TIMEOUT, Time: 10:24:12
```

**Key Metrics**:
- `migration.success.rate`: Percentage of successful migrations (target: >99%)
- `migration.rollback.failures`: Count of rollback failures (target: 0)
- `migration.orphaned.entities.count`: Current orphaned entity count (target: 0)
- `migration.latency.p99`: 99th percentile migration latency (target: <300ms)
- `migration.concurrent.count`: Active concurrent migrations

### 4.3 Log Aggregation Queries

**Splunk / ELK Query Examples**:

```spl
# Find all rollback failures in last 24h
index=luciferase "ABORT/Rollback FAILED"
| timechart count by reason

# List orphaned entities
index=luciferase "ABORT/Rollback FAILED"
| rex field=_raw "entity (?<entityId>\S+)"
| stats count by entityId

# Migration failure reasons breakdown
index=luciferase level=ERROR migration
| stats count by reason
| sort -count
```

---

## 5. PREVENTION

### 5.1 Configuration Recommendations

**Increase Phase Timeouts** (for high-latency networks):

```java
// Default: 100ms per phase
private static final long PHASE_TIMEOUT_MS = 100;

// Recommendation for WAN: 500ms
private static final long PHASE_TIMEOUT_MS = 500;
```

**Trade-off**: Higher latency per migration (300ms → 1500ms) vs. fewer timeout failures.

**Adjust for Network Conditions**:
- LAN clusters: 100ms (default)
- Multi-datacenter (same region): 250ms
- Cross-region WAN: 500ms
- High packet loss: 1000ms

### 5.2 Capacity Planning

**Monitor Concurrent Migration Load**:

```java
// Set limit on concurrent migrations to prevent resource exhaustion
private static final int MAX_CONCURRENT_MIGRATIONS = 100;

// In CrossProcessMigration
if (activeTransactions.size() >= MAX_CONCURRENT_MIGRATIONS) {
    throw new MigrationException("Migration capacity exceeded");
}
```

**Recommendation**: Set limit to 2x normal peak load to handle bursts.

### 5.3 Bubble Health Checks

**Pre-Migration Validation**:

```java
// Verify destination bubble is healthy before migration
public void migrate(Entity entity, Bubble dest) {
    // Health check
    if (!dest.isHealthy()) {
        throw new MigrationException("Destination bubble unhealthy");
    }

    // Capacity check
    if (dest.entityCount() >= dest.maxCapacity()) {
        throw new MigrationException("Destination at capacity");
    }

    // Proceed with 2PC...
}
```

### 5.4 Network Reliability

**Recommendations**:
- Use TCP keepalive (detect broken connections faster)
- Enable gRPC retry policies for transient failures
- Configure reasonable gRPC deadlines (align with phase timeouts)
- Monitor network latency between bubbles

**gRPC Configuration Example**:

```java
ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
    .keepAliveTime(10, TimeUnit.SECONDS)
    .keepAliveTimeout(5, TimeUnit.SECONDS)
    .retryAttempts(3)
    .defaultServiceConfig(/* retry policy */)
    .build();
```

### 5.5 Testing Rollback Failures

**Chaos Engineering**:

```java
// Inject rollback failures in test environment
@Test
void testRollbackFailureRecovery() {
    // Force rollback to fail
    sourceBubble.setFailAddEntity(true);

    // Attempt migration (will fail COMMIT, then fail ABORT)
    assertThrows(MigrationException.class, () ->
        migration.migrate(entity, dest));

    // Verify orphaned entity detected
    RecoveryState state = migration.getRecoveryState();
    assertTrue(state.orphanedEntities().contains(entity.getId()));

    // Perform manual recovery
    manualRecovery(entity, sourceBubble, snapshot);

    // Verify recovery complete
    assertTrue(sourceBubble.entities().contains(entity.getId()));
}
```

**Recommendation**: Run chaos tests quarterly to verify recovery procedures.

---

## 6. ESCALATION

### 6.1 When to Escalate

**Escalate to Engineering Team if**:
- Manual recovery fails (entity cannot be re-added to source)
- Multiple orphaned entities (>5) detected simultaneously
- Rollback failure rate exceeds 1% of migrations
- Systematic failures (same failure reason repeatedly)

### 6.2 Information to Provide

When escalating, include:
1. Full log entry (ERROR message with txnId, source, dest, snapshot)
2. RecoveryState output
3. MigrationLog events for affected entity
4. Network latency between source/dest bubbles
5. Source/dest bubble health status
6. Any manual recovery steps already attempted

### 6.3 Contact Information

```
L1 On-Call: ops-team@example.com (PagerDuty)
L2 Engineering: dev-team@example.com (Slack #luciferase-alerts)
L3 Architecture: architects@example.com (Email only)
```

---

## 7. APPENDIX

### 7.1 Related Documentation

- **ADR 001**: Migration and Consensus Architecture (`simulation/doc/ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md`)
- **ADR 002**: Clock.fixed() nanoTime Semantics (`simulation/doc/ADR_002_CLOCK_FIXED_NANOTIME.md`)
- **Architecture**: Distributed Simulation (`simulation/doc/ARCHITECTURE_DISTRIBUTED.md`)
- **Implementation**: CrossProcessMigration.java (`simulation/src/main/java/.../migration/CrossProcessMigration.java`)

### 7.2 Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-02-06 | Phase 2C | Initial runbook creation based on RecoveryState API implementation |

### 7.3 Testing Checklist

Before deploying to production, verify:
- [ ] Rollback failure alerts configured and tested
- [ ] Orphaned entity alerts configured and tested
- [ ] getRecoveryState() API accessible to operations team
- [ ] Manual recovery procedure tested in staging
- [ ] Log aggregation queries validated
- [ ] Escalation contacts confirmed
- [ ] Team trained on runbook procedures

---

**End of Runbook**
