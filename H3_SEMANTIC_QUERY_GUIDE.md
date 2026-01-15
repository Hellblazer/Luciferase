# H3 Deterministic Testing: Semantic Query Guide

**Version**: 1.0
**Last Updated**: 2026-01-12
**Scope**: H3.1-H3.7 Phase 1 Clock injection and deterministic testing infrastructure

---

## Quick Reference

This guide provides validated semantic queries for discovering H3-related code using `mgrep search`.

**Store**: `mgrep` (default)
**Recommended Results**: `-m 15` (medium complexity)
**Sync Flag**: Use `-s` if recent changes not appearing

---

## Clock Infrastructure

### 1. Clock Interface Location

**Query**:
```bash
mgrep search "where is Clock interface used for deterministic testing" --store mgrep -a -m 20
```

**Expected Results**:
- Clock.java interface definition
- All files with `setClock()` methods (12+ files)
- TestClock implementations

**Key Concepts**: Pluggable clock, deterministic testing, System.currentTimeMillis() abstraction

---

### 2. Clock Injection Pattern

**Query**:
```bash
mgrep search "how to inject Clock for deterministic testing" --store mgrep -a -m 15
```

**Expected Results**:
- setClock() method implementations
- Clock field declarations (volatile Clock clock = Clock.system())
- Test setup examples

**Key Pattern**:
```java
private volatile Clock clock = Clock.system();

public void setClock(Clock clock) {
    this.clock = clock;
}
```

---

### 3. nanoTime vs currentTimeMillis

**Query**:
```bash
mgrep search "nanoTime vs currentTimeMillis difference" --store mgrep -a -m 10
```

**Expected Results**:
- Clock.java interface javadoc
- nanoTime() usage for elapsed time measurements
- currentTimeMillis() usage for absolute timestamps

**Key Distinction**:
- **nanoTime()**: Relative, high-resolution, for measuring intervals
- **currentTimeMillis()**: Absolute, epoch-based, for timestamps

---

### 4. TestClock Usage

**Query**:
```bash
mgrep search "TestClock usage examples" --store mgrep -a -m 10
```

**Expected Results**:
- TestClock.withSkew() examples
- Deterministic time control in tests
- Clock injection in test setup

**Common Pattern**:
```java
TestClock clock = new TestClock();
component.setClock(clock);
clock.advanceBy(100, TimeUnit.MILLISECONDS);
```

---

## Migration Protocol

### 5. Two-Phase Commit (2PC) Implementation

**Query**:
```bash
mgrep search "entity migration 2PC protocol implementation" --store mgrep -a -m 15
```

**Expected Results**:
- CrossProcessMigration.java (coordinator)
- MigrationProtocolMessages.java (protocol messages)
- EntityMigrationStateMachine.java (state machine)

**Protocol Flow**:
1. PREPARE: Remove entity from source
2. COMMIT: Add entity to destination
3. ABORT (if failure): Rollback to source

---

### 6. Timeout Handling

**Query**:
```bash
mgrep search "how does CrossProcessMigration handle timeouts" --store mgrep -a -m 15
```

**Expected Results**:
- PHASE_TIMEOUT_MS = 100ms
- TOTAL_TIMEOUT_MS = 300ms
- Timeout enforcement in prepare/commit/abort phases

**Key Constants**:
```java
private static final long PHASE_TIMEOUT_MS = 100;
private static final long TOTAL_TIMEOUT_MS = 300;
private static final long LOCK_TIMEOUT_MS = 50;
```

---

### 7. Idempotency Tokens

**Query**:
```bash
mgrep search "idempotency token usage in migration" --store mgrep -a -m 10
```

**Expected Results**:
- IdempotencyStore interface
- Duplicate detection logic
- Exactly-once semantics enforcement

**Purpose**: Prevent duplicate migrations if retry logic executes multiple times

---

### 8. Migration Rollback

**Query**:
```bash
mgrep search "migration rollback procedure" --store mgrep -a -m 10
```

**Expected Results**:
- ABORT phase implementation
- Restore entity to source bubble
- Critical error logging for rollback failures (C3)

**Architecture Decision**: D6B.8 - Remove-then-commit ordering eliminates duplicates

---

### 9. Entity Migration Locks

**Query**:
```bash
mgrep search "entity migration lock implementation" --store mgrep -a -m 10
```

**Expected Results**:
- entityMigrationLocks ConcurrentHashMap
- ReentrantLock per entity
- Prevents concurrent migrations of same entity (C1 condition)

**Critical Condition C1**: Prevents race conditions when multiple bubbles try to migrate same entity

---

## Network Simulation

### 10. Packet Loss Injection

**Query**:
```bash
mgrep search "FakeNetworkChannel packet loss simulation" --store mgrep -a -m 10
```

**Expected Results**:
- FakeNetworkChannel.java implementation
- dropRate parameter
- Random packet drop logic

**Use Case**: Testing migration protocol resilience to network failures

---

### 11. Remote Bubble Proxy Caching

**Query**:
```bash
mgrep search "remote bubble proxy caching implementation" --store mgrep -a -m 10
```

**Expected Results**:
- RemoteBubbleProxy.java caching logic
- Cache invalidation strategy
- Clock injection for cache TTL

---

### 12. VON Discovery Protocol

**Query**:
```bash
mgrep search "VON discovery protocol implementation" --store mgrep -a -m 15
```

**Expected Results**:
- VONDiscoveryProtocol.java
- Neighbor discovery algorithm
- Voronoi-based spatial partitioning

---

## Test Patterns

### 13. Flaky Test Handling

**Query**:
```bash
mgrep search "flaky test handling with DisabledIfEnvironmentVariable" --store mgrep -a -m 10
```

**Expected Results**:
- @DisabledIfEnvironmentVariable("CI", matches = "true") annotation usage
- FailureRecoveryTest.java
- TwoNodeDistributedMigrationTest.java
- Probabilistic test examples

**Rationale**: Tests with random behavior or timing sensitivity are excluded from CI

---

### 14. CI Test Exclusion Pattern

**Query**:
```bash
mgrep search "CI-specific test exclusion patterns" --store mgrep -a -m 10
```

**Expected Results**:
- 20+ test files with @DisabledIfEnvironmentVariable
- CI environment variable detection
- Test stability considerations

**Common Pattern**:
```java
@Test
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky due to random packet loss")
void testNetworkResilience() { ... }
```

---

### 15. Probabilistic Test Examples

**Query**:
```bash
mgrep search "probabilistic test examples with random failures" --store mgrep -a -m 10
```

**Expected Results**:
- SingleBubbleWithEntitiesTest.java
- Tests with random packet loss
- Tests with random timing variations

**Challenge**: Balance test coverage with CI stability

---

## Ghost Layer

### 16. Ghost State Management

**Query**:
```bash
mgrep search "ghost state management implementation" --store mgrep -a -m 15
```

**Expected Results**:
- GhostStateManager.java
- Ghost entity tracking
- Shadow state coordination

**Purpose**: Manage ghost entities during cross-process migration

---

## Advanced Queries

### 17. All Files with setClock()

**Query**:
```bash
mgrep search "files with setClock method for test injection" --store mgrep -a -m 20
```

**Expected Results**: 12+ files
- CrossProcessMigration.java
- BubbleMigrator.java
- VolumeAnimator.java
- WallClockBucketScheduler.java
- FakeNetworkChannel.java
- BucketSynchronizedController.java
- VONDiscoveryProtocol.java
- GhostStateManager.java
- EntityMigrationStateMachine.java
- MigrationProtocolMessages.java
- RemoteBubbleProxy.java
- (Others from H3.2-H3.4)

---

### 18. nanoTime() Usages

**Query**:
```bash
mgrep search "where is nanoTime used for timing measurements" --store mgrep -a -m 15
```

**Expected Results**:
- Clock.java interface
- BucketSynchronizedController.java (bucket boundary detection)
- Performance profiling code
- Elapsed time measurements

**Use Case**: High-resolution timing for performance-critical code

---

### 19. Timeout Configuration

**Query**:
```bash
mgrep search "timeout configuration for distributed operations" --store mgrep -a -m 15
```

**Expected Results**:
- CrossProcessMigration.java timeouts
- Migration protocol timeout constants
- Network timeout handling

---

### 20. Deterministic Test Setup

**Query**:
```bash
mgrep search "how to set up deterministic tests with Clock injection" --store mgrep -a -m 15
```

**Expected Results**:
- Test setup examples
- TestClock instantiation
- Clock injection patterns
- Deterministic time control

**Pattern**:
```java
@Test
void testWithDeterministicTime() {
    TestClock clock = new TestClock();
    CrossProcessMigration migration = new CrossProcessMigration(...);
    migration.setClock(clock);

    // Control time progression
    clock.advanceBy(100, TimeUnit.MILLISECONDS);

    // Verify time-dependent behavior
    assertThat(migration.getElapsedTime()).isEqualTo(100);
}
```

---

## Query Optimization

### Result Count Guidelines

| Concept Complexity | Recommended -m | Use Case |
|-------------------|----------------|----------|
| Specific class/method | 10 | "FakeNetworkChannel packet loss" |
| Medium concept | 15 | "Clock injection pattern" |
| Cross-cutting concern | 20-30 | "All files with setClock" |

### Natural Language Tips

**Effective Queries** (conversational):
- "How does CrossProcessMigration handle timeouts"
- "Where is Clock interface used for deterministic testing"
- "Files with setClock method for test injection"

**Ineffective Queries** (keyword-style):
- "CrossProcessMigration timeout" (too vague)
- "clock test" (ambiguous)
- "setClock files" (not semantic)

### Sync Strategy

**When to use `-s` flag**:
- Recent commits (last 24 hours) not appearing
- After major refactoring
- When validation queries return <80% expected results

**Caution**: `-s` forces re-indexing, expensive operation. Use sparingly.

---

## Validation Checklist

Use this checklist to verify store coverage:

- [ ] Clock interface queries return Clock.java with nanoTime() docs
- [ ] All 12+ files with setClock() are discoverable
- [ ] Migration protocol queries return 2PC implementation
- [ ] Timeout constants (100ms, 300ms) are in results
- [ ] Test pattern queries find @DisabledIfEnvironmentVariable examples
- [ ] Network simulation queries return FakeNetworkChannel
- [ ] Ghost layer queries return GhostStateManager

**Target Coverage**: 80%+ of expected results appear in top 15 results

---

## Common Issues

### Issue 1: Recent Commits Not Indexed

**Symptom**: Queries don't return files from H3.7 Phase 1 (commits 61ad158-df1e695)

**Solution**:
```bash
mgrep search "H3.7 Phase 1 Clock injection" --store mgrep -a -m 20 -s
```

---

### Issue 2: BucketSynchronizedController Not in Logging Queries

**Symptom**: Queries about "logging in controllers" miss BucketSynchronizedController.java

**Explanation**: This file doesn't use SLF4J logger (may not need logging)

**Workaround**: Use file-specific query: "BucketSynchronizedController implementation"

---

### Issue 3: Test Files Not Returning for Pattern Queries

**Symptom**: Queries about @DisabledIfEnvironmentVariable don't return test files

**Solution**: Ensure test files are indexed. Check `.mgreprc.yaml` exclude patterns:
```yaml
exclude_patterns:
  - "target/"
  - "*.class"
  # Should NOT exclude test files:
  # - "**/*Test.java"  # REMOVE THIS if present
```

---

## Integration with Other Knowledge Systems

### ChromaDB Storage

Store this guide and validation results:
```
Document ID: guide::h3::semantic-queries
Content: This guide
Metadata: {type: "query-guide", scope: "h3-deterministic-testing", version: "1.0"}
```

### Memory Bank Caching

Cache frequent queries in session state:
```markdown
# Luciferase_active/h3_query_cache.md

## Frequent H3 Queries

### Clock Infrastructure
**Query**: `mgrep search "Clock interface usage" --store mgrep -a -m 15`
**Last run**: 2026-01-12 10:30
**Results**: Clock.java, 12+ setClock() files
```

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-01-12 | Initial guide covering H3.1-H3.7 Phase 1 |

---

## Appendix: File Locations

### Quick Reference Paths

```
Clock.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/integration/Clock.java

CrossProcessMigration.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/migration/CrossProcessMigration.java

FakeNetworkChannel.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/network/FakeNetworkChannel.java

BucketSynchronizedController.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/bubble/BucketSynchronizedController.java

VONDiscoveryProtocol.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/VONDiscoveryProtocol.java

GhostStateManager.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/ghost/GhostStateManager.java

EntityMigrationStateMachine.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/causality/EntityMigrationStateMachine.java

MigrationProtocolMessages.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/migration/MigrationProtocolMessages.java

RemoteBubbleProxy.java:
simulation/src/main/java/com/hellblazer/luciferase/simulation/distributed/RemoteBubbleProxy.java
```

---

**End of Guide**
