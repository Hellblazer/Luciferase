# Recovery Cycle Budget Documentation

**Created**: 2026-01-24
**Phase**: A.1 - P4.1 API Enhancements
**Modification**: #5 - Success Criteria Documentation

## Recovery Cycle Budget: 500ms

This budget covers **local coordination only** during partition recovery operations.

### What's Included (500ms total):

1. **Failure Detection (Barrier Timeout)**: 50ms
   - Detection of partition failure via barrier timeout
   - Quorum detection of failed partition

2. **Recovery Phase Transitions**: 200ms
   - IDLE → DETECTING: Detection phase execution
   - DETECTING → REDISTRIBUTING: Entity redistribution planning
   - REDISTRIBUTING → REBALANCING: Tree rebalancing coordination
   - REBALANCING → VALIDATING: Ghost layer validation preparation
   - VALIDATING → COMPLETE: Final validation

3. **Quorum Check and Coordination**: 10ms
   - Active partition quorum verification
   - Rank coordination for recovery

4. **Ghost Validation (Local Only)**: 50ms
   - Local ghost layer consistency check
   - Boundary element verification
   - No network communication

5. **Reserve Margin**: 190ms
   - Buffer for system variability
   - Handles unexpected delays in local operations

### What's NOT Included:

The following operations have **separate budgets** and do NOT count toward the 500ms cycle:

- **Network latency for ghost synchronization**: Depends on network conditions
- **Cross-partition barrier round-trips**: Network-bound, variable latency
- **gRPC communication delays**: External communication overhead
- **Entity data transfer**: Data size dependent, network-bound

### Design Rationale:

1. **Local Focus**: The 500ms budget applies only to operations that can be completed locally without waiting for network responses.

2. **Deterministic Testing**: Clock injection (Modification #4) enables precise timing control for budget validation in tests.

3. **Scalability**: Budget does not grow with:
   - Number of partitions (P)
   - Number of entities
   - Network topology

4. **Fault Tolerance**: 190ms reserve margin (38% of budget) provides robustness against:
   - JVM garbage collection pauses
   - System scheduling delays
   - Transient CPU contention

### Benchmark Test:

The recovery cycle budget will be validated in Phase E integration tests:

```java
@Test
void testRecoveryCycleDuration() {
    // Inject test clock for deterministic timing
    var testClock = new TestClock();
    recovery.setClock(testClock);

    // Measure local recovery cycle duration
    var startTime = testClock.currentTimeMillis();
    var result = recovery.recover(partitionId, faultHandler).get();
    var duration = testClock.currentTimeMillis() - startTime;

    // Verify: Local coordination completes within 500ms budget
    assertTrue(duration <= 500,
        String.format("Recovery cycle exceeded 500ms budget: %dms", duration));
}
```

### Success Criteria:

- [ ] Recovery phase transitions complete within 200ms
- [ ] Ghost validation (local) completes within 50ms
- [ ] Total local coordination within 500ms
- [ ] Budget validated by benchmark test in Phase E
- [ ] Network operations excluded from budget measurement

### References:

- **DefaultPartitionRecovery**: Implements recovery state machine
- **Clock Interface**: Enables deterministic time measurement
- **RecoveryPhase**: Defines recovery state transitions
- **GhostLayerValidator**: Performs local ghost validation

---

**Note**: This budget is for **local coordination only**. Total recovery time including network communication will be higher and depends on network conditions, partition count, and data size.
