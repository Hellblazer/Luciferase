# Multi-Process Integration Tests

**Last Updated**: 2026-02-10

## Overview

These integration tests validate distributed simulation behavior with **real multi-process execution** using ProcessBuilder and actual gRPC network communication.

Unlike unit tests that use `FakeNetworkChannel` for in-memory mocking, these tests:
- ✅ Spawn separate JVM processes
- ✅ Use `GrpcBubbleNetworkChannel` for real TCP communication
- ✅ Validate entity migration across process boundaries
- ✅ Test failure scenarios (network partitions, process crashes)
- ✅ Verify conservation laws and causal consistency

## Test Structure

### TwoProcessMigrationTest
- **Purpose**: Validate entity migration between 2 processes
- **Scenarios**:
  - Entity conservation (no duplication or loss)
  - Bidirectional migration
  - Causal consistency (lamport clocks)
- **Duration**: ~60 seconds
- **Entities**: 20-40 entities total

### ThreeProcessTopologyTest
- **Purpose**: Validate 3-node cluster operations
- **Scenarios**:
  - Cluster formation with 3 nodes
  - Split operation (node disconnect)
  - Merge operation (node reconnect)
- **Duration**: ~90-120 seconds
- **Entities**: 30 entities total

## Running Tests

### Run All Integration Tests
```bash
mvn verify
```

### Run Specific Test
```bash
mvn verify -Dit.test=TwoProcessMigrationTest
mvn verify -Dit.test=ThreeProcessTopologyTest
```

### Run Normal Unit Tests (excludes integration tests)
```bash
mvn test
```

## Architecture

### Node Process Pattern
Each test spawns `MigrationTestNode` instances as separate JVM processes:

```
Test Process (JUnit)
├─ startNodeProcess("Node1", port1, port2, entityCount)
│  └─ java MigrationTestNode Node1 <port1> <port2> <entityCount>
│     ├─ GrpcBubbleNetworkChannel (real gRPC)
│     ├─ RealTimeController (100 Hz tick)
│     └─ Octree spatial index
└─ startNodeProcess("Node2", port2, port1, 0)
   └─ java MigrationTestNode Node2 <port2> <port1> 0
```

### Synchronization via Log Markers
Tests monitor node logs for coordination markers:
- `READY` - Node initialized and ready
- `ENTITIES_SPAWNED` - Initial entities created
- `ENTITY_ARRIVED` - Entity migrated from peer
- `ENTITY_COUNT: N` - Current entity count (for conservation)
- `LAMPORT_CLOCK: N` - Current lamport clock (for causality)

### Dynamic Port Allocation
All tests use `findAvailablePort()` to avoid port conflicts:
```java
var node1Port = findAvailablePort(); // e.g., 54321
var node2Port = findAvailablePort(); // e.g., 54322
```

## CI/CD Integration

### Surefire (Unit Tests)
- Runs during `mvn test` phase
- **Excludes** integration tests: `**/integration/multiprocess/**/*Test.java`
- Fast execution (~5-10 minutes for all unit tests)

### Failsafe (Integration Tests)
- Runs during `mvn verify` phase
- **Includes** only: `**/integration/multiprocess/**/*Test.java`
- Slower execution (~10-15 minutes for all integration tests)

### GitHub Actions
Integration tests run in CI after all unit tests pass:
```yaml
- name: Run Integration Tests
  run: mvn verify -DskipTests=false
```

## Troubleshooting

### Test Hangs
- Check log files in `/tmp/` (printed in test output)
- Look for gRPC initialization failures
- Verify no port conflicts on CI runners

### Test Flakiness
- Integration tests may be disabled in CI with `@DisabledIfEnvironmentVariable(named = "CI", matches = "true")`
- Network timing variability can cause occasional failures
- Increase timeout durations if needed

### Port Conflicts
- Always use `findAvailablePort()` for dynamic allocation
- Never hardcode ports in tests

## Future Enhancements

Planned additional tests (see bead Luciferase-pofk):
- **NetworkPartitionTest**: Simulate network partition and recovery
- **ProcessCrashTest**: Validate crash detection and recovery
- **ByzantineNodeTest**: Test malformed message quarantine

## References

- ProcessBuilder spike: `simulation/src/test/java/.../spike/ProcessBuilderSpikeTest.java`
- TwoNodeExample: `simulation/src/test/java/.../examples/TwoNodeExampleTest.java`
- GrpcBubbleNetworkChannel: `simulation/src/main/java/.../network/GrpcBubbleNetworkChannel.java`
