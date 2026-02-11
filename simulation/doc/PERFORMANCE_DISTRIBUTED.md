# Distributed Performance Benchmarks

**Last Updated**: 2026-02-10
**Status**: In Progress
**Bead**: Luciferase-dugk

---

## Overview

This document describes distributed performance benchmarks for validating claims in [ARCHITECTURE_DISTRIBUTED.md](ARCHITECTURE_DISTRIBUTED.md).

**Benchmarking Framework**: JMH (Java Microbenchmark Harness)
**Network**: Real GrpcBubbleNetworkChannel (not FakeNetworkChannel mocks)
**Deployment**: ProcessBuilder spawns separate JVM processes

---

## Claims Being Validated

### Throughput Targets

| Metric | Claimed | Measured | Status |
|--------|---------|----------|--------|
| Migrations/sec per node | 100+ (typ. 200) | TBD | ⏳ Pending |
| Entities per bubble | 10,000+ (typ. 5000) | TBD | ⏳ Pending |
| Ghost sync updates/sec | 1000+ (typ. 2000) | TBD | ⏳ Pending |
| Concurrent migrations | 50+ (typ. 100) | TBD | ⏳ Pending |

### Latency Targets

| Metric | Claimed | Measured | Status |
|--------|---------|----------|--------|
| Bucket processing | < 100ms (typ. 50ms) | TBD | ⏳ Pending |
| Entity migration (2PC) | < 300ms (typ. 150ms) | TBD | ⏳ Pending |
| Ghost synchronization | < 10ms (typ. 5ms) | TBD | ⏳ Pending |

---

## Benchmark Suite

### 1. Migration Throughput Benchmark

**File**: `DistributedMigrationBenchmark.java`

**Purpose**: Validate "100+ migrations/sec per node (typical ~200)"

**Methodology**:
- Spawn 2 JVM processes with GrpcBubbleNetworkChannel
- Node1 spawns entities, sends to Node2
- Measure migrations per second over 10 second window
- Test with 50, 100, 200 entities
- Report total throughput

**Running**:
```bash
# Run migration throughput benchmark
mvn test -Dtest=DistributedMigrationBenchmark

# Run specific entity count
mvn test -Dtest=DistributedMigrationBenchmark#migrationThroughput100Entities
```

**Expected Results**:
- 50 entities: > 100 migrations/sec
- 100 entities: > 150 migrations/sec
- 200 entities: > 200 migrations/sec

---

### 2. Entity Capacity Benchmark

**File**: `DistributedCapacityBenchmark.java`

**Purpose**: Validate "10,000+ entities per bubble (typical ~5000)"

**Methodology**:
- Spawn 2 JVM processes (one bubble per process)
- Test with 1000, 5000, 10000 entities per bubble
- Measure P99 tick latency and heap usage
- Verify latency stays under threshold

**Running**:
```bash
# Run capacity benchmark
mvn test -Dtest=DistributedCapacityBenchmark

# Run specific entity count
mvn test -Dtest=DistributedCapacityBenchmark#capacity5000Entities
```

**Expected Results**:
- 1000 entities: P99 < 25ms, heap < 200MB
- 5000 entities: P99 < 50ms, heap < 500MB
- 10000 entities: P99 < 100ms, heap < 1GB

**Acceptance Criteria**:
- ✅ Linear memory scaling (not exponential)
- ✅ P99 latency under 100ms at 10K entities
- ✅ No GC pauses > 100ms

---

## Network Configurations

### Localhost (Baseline)

**Latency**: ~0.1ms RTT
**Configuration**: Default
**Purpose**: Minimal network overhead, isolate compute performance

**Usage**: All benchmarks use localhost by default

### LAN Simulation (1ms)

**Latency**: ~1ms RTT
**Configuration**: Docker network with `tc` (traffic control)

**Setup**:
```bash
# TODO: Add Docker-based LAN simulation
# Use tc (traffic control) to add 1ms latency
```

### WAN Simulation (50ms)

**Latency**: ~50ms RTT
**Configuration**: Docker network with `tc`

**Setup**:
```bash
# TODO: Add Docker-based WAN simulation
# Use tc to add 50ms latency (cross-region simulation)
```

---

## Resource Measurement

### CPU Usage

**Methodology**: Measure per-node CPU usage at various entity counts

**Tool**: `jcmd` or JMX MBeans

**Test Matrix**:
| Entities | Expected CPU % |
|----------|----------------|
| 100 | < 10% |
| 1000 | < 30% |
| 10000 | < 80% |

### Memory Usage

**Methodology**: Heap usage graphed vs entity count

**Expected**: Linear scaling (~100KB per entity)

**Measurement**:
```java
var memoryBean = ManagementFactory.getMemoryMXBean();
var heapUsage = memoryBean.getHeapMemoryUsage();
long heapMB = heapUsage.getUsed() / (1024 * 1024);
```

### Network Bandwidth

**Methodology**: Monitor gRPC message traffic

**Expected**:
- Localhost: > 100 MB/s
- LAN (1ms): > 50 MB/s
- WAN (50ms): > 10 MB/s

---

## Benchmark Implementation Details

### JMH Configuration

```java
@BenchmarkMode(Mode.Throughput)  // Or Mode.AverageTime for latency
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
```

**Rationale**:
- **Warmup**: 2 iterations × 5s = 10s warmup (JIT compilation stabilization)
- **Measurement**: 5 iterations × 10s = 50s measurement (statistical significance)
- **Fork**: 1 (single JVM fork to reduce test time)

### Process Spawning Pattern

```java
var pb = new ProcessBuilder(
    javaHome + "/bin/java",
    "-cp", classPath,
    "com.hellblazer.luciferase.simulation.benchmarks.distributed.MigrationBenchmarkNode",
    nodeName,
    String.valueOf(serverPort),
    String.valueOf(peerPort),
    String.valueOf(entityCount)
);

pb.redirectErrorStream(true);
pb.redirectOutput(logFile.toFile());
var process = pb.start();
```

**Markers**: Nodes emit markers for synchronization:
- `READY` - Node initialized and listening
- `MIGRATION_COUNT: N` - Total migrations sent/received
- `P99_LATENCY_MS: X.XX` - P99 tick latency
- `HEAP_MB: N` - Heap memory usage

---

## Reproducibility

### System Requirements

- **OS**: macOS, Linux (Windows untested)
- **Java**: 24+
- **Memory**: 4GB+ available
- **Network**: localhost loopback

### Benchmark Variance

**Expected Variance**:
- Throughput: ±5% between runs
- Latency: ±10% (system contention)
- Memory: ±2% (GC timing)

**Reducing Variance**:
1. Close background applications
2. Disable CPU frequency scaling
3. Run on dedicated hardware (not VMs)
4. Increase JMH iterations for more samples

---

## TODO

**Remaining Benchmarks**:
- [ ] Ghost synchronization overhead benchmark
- [ ] Bucket processing latency benchmark
- [ ] Concurrent migration benchmark (50+ simultaneous)
- [ ] Network latency comparison (localhost/LAN/WAN)

**Network Configurations**:
- [ ] Docker-based LAN simulation (1ms)
- [ ] Docker-based WAN simulation (50ms)

**Documentation**:
- [ ] Add results table with measured values
- [ ] Compare distributed vs local performance
- [ ] Graph memory scaling vs entity count
- [ ] Graph latency P50/P95/P99 distribution

---

## Results

**Status**: Benchmarks created, not yet run

**Next Steps**:
1. Compile benchmarks: `mvn test-compile`
2. Run migration benchmark: `mvn test -Dtest=DistributedMigrationBenchmark`
3. Run capacity benchmark: `mvn test -Dtest=DistributedCapacityBenchmark`
4. Analyze results and update this document
5. Update ARCHITECTURE_DISTRIBUTED.md with validated claims

---

**Document Version**: 0.1 (Draft)
**Last Updated**: 2026-02-10
**Author**: Claude (Sonnet 4.5)
**Status**: In Progress
