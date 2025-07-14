# Lucien Testing and Performance Plan

## Executive Summary

This document outlines a comprehensive testing and performance measurement strategy for Lucien, incorporating lessons learned from t8code's testing infrastructure while leveraging modern Java testing frameworks. The plan focuses on correctness verification, performance benchmarking, and scalability testing across all spatial index implementations (Octree, Tetree, Prism).

## Testing Strategy

### 1. Test Organization

#### Current State
- 109+ test files with JUnit 5
- Package-based organization mirroring source structure
- Good coverage of basic operations

#### Enhanced Structure
```
lucien/src/test/java/
├── unit/                    # Fast, isolated unit tests
│   ├── octree/
│   ├── tetree/
│   ├── prism/
│   └── entity/
├── integration/             # System-wide integration tests
│   ├── forest/
│   ├── ghost/
│   └── collision/
├── performance/             # JMH benchmarks
│   ├── microbenchmarks/
│   ├── macrobenchmarks/
│   └── scalability/
├── stress/                  # Long-running stress tests
│   ├── concurrent/
│   ├── memory/
│   └── endurance/
└── testutil/               # Shared test utilities
    ├── generators/
    ├── validators/
    └── statistics/
```

### 2. Test Categories

#### Unit Tests (Target: < 1 second per test)
- **Purpose**: Verify individual component correctness
- **Framework**: JUnit 5 with AssertJ assertions
- **Coverage Target**: 90% line coverage, 85% branch coverage
- **Key Areas**:
  - Spatial key operations (Morton, Tetree, Prism)
  - Entity management lifecycle
  - Geometric calculations
  - Tree node operations

#### Integration Tests (Target: < 10 seconds per test)
- **Purpose**: Verify component interactions
- **Framework**: JUnit 5 with TestContainers for distributed tests
- **Key Scenarios**:
  - Multi-tree forest operations
  - Ghost layer synchronization
  - Collision system integration
  - Cross-index type operations

#### Performance Tests (Using JMH)
- **Purpose**: Measure and track performance metrics
- **Framework**: Java Microbenchmark Harness (JMH)
- **Metrics**: Throughput, latency, memory allocation
- **Key Benchmarks**:
  - Individual operation performance
  - Bulk operation throughput
  - Query performance under load
  - Memory efficiency

#### Stress Tests
- **Purpose**: Verify behavior under extreme conditions
- **Duration**: 1-24 hours
- **Scenarios**:
  - Maximum entity capacity
  - Concurrent modification storms
  - Memory pressure situations
  - Network partition simulation (ghost tests)

### 3. Test Data Management

#### Test Data Generators
```java
public class SpatialTestDataGenerator {
    // Inspired by t8code's test case generation
    public static Octree<LongEntityID, String> createStandardOctree(TestScenario scenario) {
        switch (scenario) {
            case UNIFORM_DISTRIBUTION:
                return createUniformOctree();
            case CLUSTERED:
                return createClusteredOctree();
            case SPARSE:
                return createSparseOctree();
            // ... more scenarios
        }
    }
    
    public enum TestScenario {
        UNIFORM_DISTRIBUTION,    // Evenly distributed entities
        CLUSTERED,              // Dense clusters with sparse regions
        SPARSE,                 // Few entities, widely separated
        HIERARCHICAL,           // Mixed levels of subdivision
        BOUNDARY_HEAVY,         // Entities concentrated at boundaries
        MOVING_ENTITIES,        // Entities with movement patterns
        LARGE_ENTITIES,         // Spanning multiple nodes
        MIXED_SIZES            // Various entity sizes
    }
}
```

#### Reproducible Test Data
```java
public class DeterministicTestData {
    private static final long SEED = 42L;
    private final Random random = new Random(SEED);
    
    // Fixed test positions for regression testing
    public static final Point3f[] STANDARD_POSITIONS = {
        new Point3f(100, 200, 300),
        new Point3f(500, 500, 500),
        // ... more positions
    };
    
    // Generate deterministic random data
    public List<Point3f> generatePositions(int count, Bounds3D bounds) {
        return IntStream.range(0, count)
            .mapToObj(i -> randomPointInBounds(bounds))
            .collect(Collectors.toList());
    }
}
```

### 4. Performance Measurement Framework

#### Statistics Collection (Inspired by t8code)
```java
public class PerformanceStatistics {
    private final DescriptiveStatistics stats = new DescriptiveStatistics();
    private final String operationName;
    
    public void recordTiming(long nanos) {
        stats.addValue(nanos);
    }
    
    public PerformanceReport generateReport() {
        return PerformanceReport.builder()
            .operation(operationName)
            .mean(stats.getMean())
            .median(stats.getPercentile(50))
            .p95(stats.getPercentile(95))
            .p99(stats.getPercentile(99))
            .standardDeviation(stats.getStandardDeviation())
            .sampleCount(stats.getN())
            .build();
    }
    
    // Aggregate across threads (similar to MPI aggregation)
    public static PerformanceReport aggregateReports(List<PerformanceReport> threadReports) {
        // Combine statistics from multiple threads
    }
}
```

#### JMH Benchmark Template
```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"-Xms4G", "-Xmx4G"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class SpatialIndexBenchmark {
    
    @Param({"1000", "10000", "100000"})
    private int entityCount;
    
    @Param({"OCTREE", "TETREE", "PRISM"})
    private IndexType indexType;
    
    private SpatialIndex<?, LongEntityID, String> index;
    private List<Point3f> testPositions;
    
    @Setup(Level.Trial)
    public void setup() {
        index = createIndex(indexType);
        testPositions = generateTestPositions(entityCount);
    }
    
    @Benchmark
    public void insertBenchmark() {
        for (int i = 0; i < entityCount; i++) {
            index.insert(new LongEntityID(i), testPositions.get(i), (byte)10, "data");
        }
    }
    
    @Benchmark
    public List<LongEntityID> knnBenchmark() {
        return index.kNearestNeighbors(testPositions.get(0), 10, Float.MAX_VALUE);
    }
}
```

### 5. Concurrent Testing

#### Thread Safety Verification
```java
@Test
public void testConcurrentOperations() throws InterruptedException {
    final int threadCount = 10;
    final int operationsPerThread = 1000;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch endLatch = new CountDownLatch(threadCount);
    
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    AtomicInteger errors = new AtomicInteger(0);
    
    for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
            try {
                startLatch.await(); // Ensure all threads start together
                performConcurrentOperations(threadId, operationsPerThread);
            } catch (Exception e) {
                errors.incrementAndGet();
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });
    }
    
    startLatch.countDown(); // Start all threads
    endLatch.await(30, TimeUnit.SECONDS);
    
    assertEquals(0, errors.get(), "Concurrent operations should not produce errors");
    verifyIndexConsistency();
}
```

### 6. Memory Testing

#### Memory Leak Detection
```java
public class MemoryLeakTest {
    @Test
    public void testNoMemoryLeakDuringBulkOperations() {
        WeakReference<Octree<LongEntityID, String>> weakRef = null;
        
        // Create and populate index
        {
            Octree<LongEntityID, String> index = new Octree<>(idGen, 10, (byte)20);
            populateWithEntities(index, 100000);
            weakRef = new WeakReference<>(index);
        }
        
        // Force garbage collection
        System.gc();
        Thread.sleep(100);
        System.gc();
        
        assertNull(weakRef.get(), "Index should be garbage collected");
    }
    
    @Test
    public void testMemoryUsageScaling() {
        long[] memorySamples = new long[5];
        
        for (int i = 0; i < 5; i++) {
            int entityCount = (i + 1) * 10000;
            
            Runtime runtime = Runtime.getRuntime();
            runtime.gc();
            long memBefore = runtime.totalMemory() - runtime.freeMemory();
            
            Octree<LongEntityID, String> index = createAndPopulate(entityCount);
            
            runtime.gc();
            long memAfter = runtime.totalMemory() - runtime.freeMemory();
            
            memorySamples[i] = memAfter - memBefore;
        }
        
        // Verify linear scaling
        verifyLinearMemoryScaling(memorySamples);
    }
}
```

### 7. Correctness Verification

#### Property-Based Testing
```java
public class SpatialIndexProperties {
    @Property
    public void insertedEntitiesAreRetrievable(@ForAll @Size(min=1, max=1000) List<@From("positions") Point3f> positions) {
        SpatialIndex<MortonKey, LongEntityID, String> index = new Octree<>(idGen, 10, (byte)20);
        
        // Insert all positions
        for (int i = 0; i < positions.size(); i++) {
            LongEntityID id = new LongEntityID(i);
            index.insert(id, positions.get(i), (byte)10, "data");
        }
        
        // Verify all can be found
        for (int i = 0; i < positions.size(); i++) {
            LongEntityID id = new LongEntityID(i);
            assertTrue(index.lookup(id).isPresent());
        }
    }
    
    @Property
    public void knnReturnsClosestEntities(@ForAll Point3f queryPoint, 
                                          @ForAll @IntRange(min=1, max=50) int k) {
        // Property: k-NN returns exactly k nearest neighbors (or all if fewer exist)
        List<LongEntityID> results = index.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        assertTrue(results.size() <= k);
        
        // Verify ordering by distance
        List<Float> distances = results.stream()
            .map(id -> index.lookup(id).get().getPosition().distance(queryPoint))
            .collect(Collectors.toList());
        
        assertEquals(distances, distances.stream().sorted().collect(Collectors.toList()));
    }
}
```

### 8. Ghost Layer Testing

#### Distributed Correctness
```java
public class GhostLayerTest {
    @Test
    public void testGhostConsistencyAcrossNodes() {
        // Create multiple spatial indices representing different nodes
        List<Octree<LongEntityID, String>> nodes = createDistributedNodes(4);
        
        // Setup ghost communication
        GhostCommunicationManager[] managers = setupGhostManagers(nodes);
        
        // Insert entities near boundaries
        insertBoundaryEntities(nodes);
        
        // Synchronize ghosts
        CompletableFuture<?>[] syncs = new CompletableFuture[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            syncs[i] = managers[i].syncGhosts(getNeighborIds(i), GhostType.FACES);
        }
        
        CompletableFuture.allOf(syncs).join();
        
        // Verify ghost consistency
        for (int i = 0; i < nodes.size(); i++) {
            verifyGhostCorrectness(nodes.get(i), managers[i]);
        }
    }
}
```

## Performance Testing Plan

### 1. Baseline Performance Metrics

#### Operation Performance Matrix
| Operation | Metric | Target | Measurement Method |
|-----------|--------|--------|-------------------|
| Insert | Throughput | >100K ops/sec | JMH benchmark |
| Remove | Throughput | >100K ops/sec | JMH benchmark |
| Update | Throughput | >50K ops/sec | JMH benchmark |
| k-NN (k=10) | Latency p99 | <100 μs | JMH benchmark |
| Range Query | Latency p99 | <1 ms | JMH benchmark |
| Bulk Insert | Throughput | >500K entities/sec | Custom benchmark |

### 2. Scalability Testing

#### Entity Count Scaling
- Test with: 1K, 10K, 100K, 1M, 10M entities
- Measure: Operation latency, memory usage, tree depth
- Expected: O(log n) query performance, O(n) memory

#### Thread Scaling
- Test with: 1, 2, 4, 8, 16, 32, 64 threads
- Measure: Aggregate throughput, contention metrics
- Expected: Near-linear scaling up to core count

#### Node Scaling (Forest)
- Test with: 1, 4, 16, 64, 256 trees
- Measure: Query routing overhead, memory overhead
- Expected: Constant overhead per tree

### 3. Comparative Analysis

#### Octree vs Tetree vs Prism
```java
@State(Scope.Benchmark)
public class ComparativeBenchmark {
    @Param({"OCTREE", "TETREE", "PRISM"})
    private IndexType indexType;
    
    @Param({"UNIFORM", "CLUSTERED", "SPARSE"})
    private DataDistribution distribution;
    
    @Param({"1000", "10000", "100000"})
    private int entityCount;
    
    // Benchmarks for each operation across all combinations
}
```

### 4. Memory Profiling

#### Memory Usage Analysis
- Heap allocation per entity
- Memory overhead of tree structure
- Ghost layer memory cost
- Memory under different distributions

#### GC Impact Testing
- GC pause frequency and duration
- Allocation rate during operations
- Memory churn from updates

### 5. Stress Testing Scenarios

#### Long-Running Stability
- 24-hour continuous operation test
- Random mix of all operations
- Monitor for memory leaks, performance degradation

#### Extreme Load Testing
- Maximum entities (until OOM)
- Maximum concurrent threads
- Maximum update frequency

#### Failure Recovery Testing
- Node failures during ghost sync
- Partial operation failures
- Recovery time measurement

## Test Automation

### 1. Continuous Integration

#### Test Execution Strategy
```yaml
# GitHub Actions workflow
name: Lucien Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Run Unit Tests
        run: mvn test -Dtest="unit/**"
  
  integration-tests:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - name: Run Integration Tests
        run: mvn test -Dtest="integration/**"
  
  performance-tests:
    runs-on: ubuntu-latest
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    steps:
      - name: Run Performance Tests
        run: mvn test -P performance
      - name: Upload Results
        uses: actions/upload-artifact@v2
        with:
          name: performance-results
          path: target/performance-reports/
```

### 2. Performance Tracking

#### Historical Performance Database
```sql
CREATE TABLE performance_results (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,
    commit_hash VARCHAR(40) NOT NULL,
    test_name VARCHAR(255) NOT NULL,
    index_type VARCHAR(20) NOT NULL,
    operation VARCHAR(50) NOT NULL,
    entity_count INTEGER NOT NULL,
    mean_time_ns BIGINT NOT NULL,
    p95_time_ns BIGINT NOT NULL,
    p99_time_ns BIGINT NOT NULL,
    throughput_ops_sec DOUBLE PRECISION,
    memory_bytes BIGINT
);

CREATE INDEX idx_performance_test_commit ON performance_results(test_name, commit_hash);
CREATE INDEX idx_performance_trends ON performance_results(test_name, timestamp);
```

### 3. Performance Regression Detection

```java
public class PerformanceRegressionDetector {
    private static final double REGRESSION_THRESHOLD = 0.10; // 10% regression
    
    public boolean detectRegression(PerformanceResult current, PerformanceResult baseline) {
        double degradation = (current.getMeanTime() - baseline.getMeanTime()) 
                           / baseline.getMeanTime();
        
        if (degradation > REGRESSION_THRESHOLD) {
            generateRegressionReport(current, baseline, degradation);
            return true;
        }
        return false;
    }
}
```

## Testing Best Practices

### 1. Test Naming Convention
```java
@Test
public void methodName_StateUnderTest_ExpectedBehavior() {
    // Example: insert_WithValidEntity_ReturnsTrue
    // Example: kNearestNeighbors_WithEmptyIndex_ReturnsEmptyList
}
```

### 2. Test Data Isolation
- Each test creates its own spatial index
- No shared mutable state between tests
- Deterministic test data generation

### 3. Assertion Guidelines
```java
// Use AssertJ for readable assertions
assertThat(result)
    .isNotNull()
    .hasSize(10)
    .extracting(Entity::getId)
    .containsExactlyInAnyOrder(expectedIds);

// Custom assertions for spatial data
assertThat(entity)
    .isContainedIn(bounds)
    .isCloserThan(100.0f).to(targetPoint);
```

### 4. Performance Test Guidelines
- Run performance tests in isolated environment
- Use JMH for microbenchmarks
- Warm up JVM before measurements
- Report statistics, not single runs
- Test with realistic data distributions

## Implementation Timeline

### Phase 1: Test Infrastructure (Week 1-2)
- [ ] Create test utility classes
- [ ] Implement test data generators
- [ ] Setup performance statistics collection
- [ ] Create custom assertions

### Phase 2: Unit Test Enhancement (Week 3-4)
- [ ] Increase unit test coverage to 90%
- [ ] Add property-based tests
- [ ] Implement parameterized tests for variations
- [ ] Add edge case testing

### Phase 3: Performance Framework (Week 5-6)
- [ ] Setup JMH benchmarks
- [ ] Create performance test suite
- [ ] Implement regression detection
- [ ] Setup performance database

### Phase 4: Integration Testing (Week 7-8)
- [ ] Ghost layer integration tests
- [ ] Forest coordination tests
- [ ] Concurrent operation tests
- [ ] Failure scenario tests

### Phase 5: Automation (Week 9-10)
- [ ] CI/CD pipeline integration
- [ ] Automated performance tracking
- [ ] Test report generation
- [ ] Dashboard creation

## Success Metrics

### Coverage Goals
- Unit test coverage: 90% lines, 85% branches
- Integration test scenarios: 100% of documented use cases
- Performance benchmarks: All major operations

### Performance Goals
- No performance regression > 5% between releases
- Meet or exceed target metrics for all operations
- Memory usage scaling remains linear

### Quality Goals
- Zero flaky tests
- All tests run in < 30 minutes
- Performance tests reproducible within 2% variance

## Conclusion

This testing plan combines the systematic approach observed in t8code with modern Java testing practices. By implementing comprehensive unit, integration, performance, and stress testing, we can ensure Lucien maintains high quality, performance, and reliability across all spatial index implementations.