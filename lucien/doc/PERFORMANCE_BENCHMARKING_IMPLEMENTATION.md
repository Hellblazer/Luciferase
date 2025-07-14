# Performance Benchmarking Implementation Guide

## Overview

This guide provides concrete implementation details for setting up a comprehensive performance benchmarking suite for Lucien using Java Microbenchmark Harness (JMH) and custom performance tracking infrastructure.

## JMH Setup

### 1. Maven Configuration

Add JMH dependencies to `pom.xml`:

```xml
<dependencies>
    <!-- JMH Core -->
    <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-core</artifactId>
        <version>1.37</version>
        <scope>test</scope>
    </dependency>
    
    <!-- JMH Annotation Processor -->
    <dependency>
        <groupId>org.openjdk.jmh</groupId>
        <artifactId>jmh-generator-annprocess</artifactId>
        <version>1.37</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<!-- JMH Plugin for running benchmarks -->
<plugins>
    <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <executions>
            <execution>
                <id>run-benchmarks</id>
                <phase>integration-test</phase>
                <goals>
                    <goal>exec</goal>
                </goals>
                <configuration>
                    <classpathScope>test</classpathScope>
                    <executable>java</executable>
                    <arguments>
                        <argument>-classpath</argument>
                        <classpath/>
                        <argument>org.openjdk.jmh.Main</argument>
                        <argument>-rf</argument>
                        <argument>json</argument>
                        <argument>-rff</argument>
                        <argument>target/jmh-results.json</argument>
                    </arguments>
                </configuration>
            </execution>
        </executions>
    </plugin>
</plugins>
```

### 2. Benchmark Structure

Create benchmark classes in `src/test/java/com/hellblazer/luciferase/lucien/benchmarks/`:

```java
package com.hellblazer.luciferase.lucien.benchmarks;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"-Xms4G", "-Xmx4G", "-XX:+UseG1GC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class CoreOperationsBenchmark {
    
    @Param({"1000", "10000", "100000"})
    private int entityCount;
    
    @Param({"OCTREE", "TETREE", "PRISM"})
    private String indexType;
    
    @Param({"5", "10", "20"})
    private int maxDepth;
    
    private SpatialIndex<?, LongEntityID, TestEntity> spatialIndex;
    private List<TestEntity> entities;
    private List<Point3f> queryPoints;
    private EntityIDGenerator<LongEntityID> idGenerator;
    
    @Setup(Level.Trial)
    public void setupTrial() {
        idGenerator = new SequentialLongIDGenerator();
        entities = TestDataGenerator.generateEntities(entityCount);
        queryPoints = TestDataGenerator.generateQueryPoints(100);
    }
    
    @Setup(Level.Iteration)
    public void setupIteration() {
        spatialIndex = createSpatialIndex(indexType, maxDepth);
        // Pre-populate for update/remove benchmarks
        if (needsPrePopulation()) {
            populateIndex();
        }
    }
    
    @Benchmark
    public void insertSingle(Blackhole blackhole) {
        int idx = ThreadLocalRandom.current().nextInt(entities.size());
        TestEntity entity = entities.get(idx);
        LongEntityID id = idGenerator.generateId();
        
        spatialIndex.insert(id, entity.getPosition(), (byte)10, entity);
        blackhole.consume(id);
    }
    
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void bulkInsert() {
        for (TestEntity entity : entities) {
            spatialIndex.insert(
                idGenerator.generateId(),
                entity.getPosition(),
                (byte)10,
                entity
            );
        }
    }
    
    @Benchmark
    public List<LongEntityID> kNearestNeighbors() {
        Point3f queryPoint = queryPoints.get(
            ThreadLocalRandom.current().nextInt(queryPoints.size())
        );
        return spatialIndex.kNearestNeighbors(queryPoint, 10, 1000.0f);
    }
    
    @Benchmark
    public List<LongEntityID> rangeQuery() {
        Point3f center = queryPoints.get(
            ThreadLocalRandom.current().nextInt(queryPoints.size())
        );
        Spatial.Cube searchCube = new Spatial.Cube(
            center.x - 50, center.y - 50, center.z - 50, 100
        );
        return spatialIndex.findEntitiesInVolume(searchCube);
    }
}
```

## Custom Performance Framework

### 1. Performance Metrics Collection

```java
package com.hellblazer.luciferase.lucien.performance;

public class PerformanceMetrics {
    private final String operationName;
    private final String indexType;
    private final Map<String, Object> parameters;
    private final DescriptiveStatistics timingStats;
    private final DescriptiveStatistics memoryStats;
    private final AtomicLong operationCount;
    private final long startTime;
    
    public PerformanceMetrics(String operationName, String indexType) {
        this.operationName = operationName;
        this.indexType = indexType;
        this.parameters = new HashMap<>();
        this.timingStats = new DescriptiveStatistics();
        this.memoryStats = new DescriptiveStatistics();
        this.operationCount = new AtomicLong();
        this.startTime = System.nanoTime();
    }
    
    public void recordOperation(long durationNanos, long memoryUsed) {
        timingStats.addValue(durationNanos);
        memoryStats.addValue(memoryUsed);
        operationCount.incrementAndGet();
    }
    
    public PerformanceReport generateReport() {
        long totalTime = System.nanoTime() - startTime;
        
        return PerformanceReport.builder()
            .operationName(operationName)
            .indexType(indexType)
            .parameters(parameters)
            .totalOperations(operationCount.get())
            .throughput(operationCount.get() * 1e9 / totalTime)
            .meanLatencyNanos(timingStats.getMean())
            .medianLatencyNanos(timingStats.getPercentile(50))
            .p95LatencyNanos(timingStats.getPercentile(95))
            .p99LatencyNanos(timingStats.getPercentile(99))
            .maxLatencyNanos(timingStats.getMax())
            .meanMemoryBytes(memoryStats.getMean())
            .maxMemoryBytes(memoryStats.getMax())
            .build();
    }
}
```

### 2. Thread-Safe Performance Recorder

```java
public class ThreadSafePerformanceRecorder {
    private final ConcurrentHashMap<String, PerformanceMetrics> metricsMap;
    private final ScheduledExecutorService reporter;
    
    public ThreadSafePerformanceRecorder() {
        this.metricsMap = new ConcurrentHashMap<>();
        this.reporter = Executors.newScheduledThreadPool(1);
        
        // Report metrics every 10 seconds
        reporter.scheduleAtFixedRate(this::reportMetrics, 10, 10, TimeUnit.SECONDS);
    }
    
    public AutoCloseable recordOperation(String operation, String indexType) {
        long startTime = System.nanoTime();
        long startMemory = getCurrentMemoryUsage();
        
        return () -> {
            long duration = System.nanoTime() - startTime;
            long memoryDelta = getCurrentMemoryUsage() - startMemory;
            
            PerformanceMetrics metrics = metricsMap.computeIfAbsent(
                operation + ":" + indexType,
                k -> new PerformanceMetrics(operation, indexType)
            );
            
            metrics.recordOperation(duration, memoryDelta);
        };
    }
    
    private long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    private void reportMetrics() {
        List<PerformanceReport> reports = metricsMap.values().stream()
            .map(PerformanceMetrics::generateReport)
            .collect(Collectors.toList());
        
        // Send to monitoring system
        MetricsPublisher.publish(reports);
    }
}
```

### 3. Comparative Benchmark Suite

```java
@State(Scope.Benchmark)
public class ComparativeSpatialIndexBenchmark {
    
    private static final int WARMUP_OPERATIONS = 10000;
    
    @Param({"OCTREE", "TETREE", "PRISM"})
    private String indexType;
    
    @Param({"UNIFORM", "CLUSTERED", "SPARSE", "BOUNDARY_HEAVY"})
    private String distribution;
    
    @Param({"SMALL", "MEDIUM", "LARGE"})
    private String datasetSize;
    
    private SpatialIndexBenchmarkHarness harness;
    
    @Setup(Level.Trial)
    public void setup() {
        TestConfiguration config = TestConfiguration.builder()
            .indexType(IndexType.valueOf(indexType))
            .distribution(DataDistribution.valueOf(distribution))
            .datasetSize(getEntityCount(datasetSize))
            .build();
            
        harness = new SpatialIndexBenchmarkHarness(config);
        harness.initialize();
        
        // Warmup
        harness.runWarmup(WARMUP_OPERATIONS);
    }
    
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void mixedOperations(Blackhole blackhole) {
        // Simulate realistic workload
        OperationMix mix = OperationMix.TYPICAL; // 70% read, 20% insert, 10% update
        Object result = harness.executeRandomOperation(mix);
        blackhole.consume(result);
    }
    
    @Benchmark
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    public void concurrentOperations(Blackhole blackhole) {
        // Test with multiple threads
        int threadCount = Runtime.getRuntime().availableProcessors();
        CompletableFuture<?>[] futures = new CompletableFuture[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < 100; j++) {
                    Object result = harness.executeRandomOperation(OperationMix.TYPICAL);
                    blackhole.consume(result);
                }
            });
        }
        
        CompletableFuture.allOf(futures).join();
    }
    
    private int getEntityCount(String size) {
        switch (size) {
            case "SMALL": return 10_000;
            case "MEDIUM": return 100_000;
            case "LARGE": return 1_000_000;
            default: throw new IllegalArgumentException("Unknown size: " + size);
        }
    }
}
```

## Memory Profiling

### 1. Memory Benchmark

```java
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {
    "-Xms2G", "-Xmx2G",
    "-XX:+UseG1GC",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+PrintInlining",
    "-XX:+TraceClassLoading",
    "-XX:+LogCompilation"
})
public class MemoryEfficiencyBenchmark {
    
    @Param({"1000", "10000", "100000"})
    private int entityCount;
    
    @Param({"OCTREE", "TETREE", "PRISM"})
    private String indexType;
    
    @Benchmark
    public MemoryProfile measureMemoryUsage() {
        // Force GC before measurement
        System.gc();
        Thread.sleep(100);
        
        long beforeMemory = getUsedMemory();
        
        // Create and populate index
        SpatialIndex<?, LongEntityID, String> index = createIndex(indexType);
        populateIndex(index, entityCount);
        
        // Force GC to get accurate measurement
        System.gc();
        Thread.sleep(100);
        
        long afterMemory = getUsedMemory();
        long memoryUsed = afterMemory - beforeMemory;
        
        return MemoryProfile.builder()
            .totalMemoryBytes(memoryUsed)
            .bytesPerEntity(memoryUsed / entityCount)
            .nodeCount(index.getNodeCount())
            .bytesPerNode(memoryUsed / index.getNodeCount())
            .build();
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
```

### 2. GC Impact Analysis

```java
public class GCImpactBenchmark {
    
    @Benchmark
    @Fork(value = 1, jvmArgs = {
        "-Xms4G", "-Xmx4G",
        "-XX:+UseG1GC",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:G1NewSizePercent=20",
        "-XX:G1MaxNewSizePercent=30",
        "-verbose:gc",
        "-XX:+PrintGCDetails",
        "-XX:+PrintGCDateStamps",
        "-Xloggc:target/gc-benchmark.log"
    })
    public void measureGCImpact() {
        SpatialIndex<MortonKey, LongEntityID, String> index = 
            new Octree<>(new SequentialLongIDGenerator(), 100, (byte)20);
        
        // Continuous operations to trigger GC
        for (int i = 0; i < 1_000_000; i++) {
            LongEntityID id = new LongEntityID(i);
            Point3f position = new Point3f(
                (float)(Math.random() * 1000),
                (float)(Math.random() * 1000),
                (float)(Math.random() * 1000)
            );
            
            index.insert(id, position, (byte)10, "data" + i);
            
            // Periodic queries to create garbage
            if (i % 100 == 0) {
                index.kNearestNeighbors(position, 10, 100.0f);
            }
            
            // Periodic removals
            if (i % 1000 == 0 && i > 1000) {
                index.remove(new LongEntityID(i - 1000));
            }
        }
    }
}
```

## Scalability Testing

### 1. Thread Scalability Benchmark

```java
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class ThreadScalabilityBenchmark {
    
    @Param({"1", "2", "4", "8", "16", "32"})
    private int threadCount;
    
    @Param({"OCTREE", "TETREE"})
    private String indexType;
    
    private SpatialIndex<?, LongEntityID, String> index;
    private List<Operation> operations;
    
    @Setup(Level.Trial)
    public void setup() {
        index = createConcurrentIndex(indexType);
        operations = generateMixedOperations(100000);
        
        // Pre-populate
        populateIndex(index, 50000);
    }
    
    @Benchmark
    @Threads(1) // JMH will scale based on threadCount param
    public void concurrentThroughput() {
        int opsPerThread = operations.size() / threadCount;
        int threadId = (int) Thread.currentThread().getId() % threadCount;
        int startIdx = threadId * opsPerThread;
        int endIdx = Math.min(startIdx + opsPerThread, operations.size());
        
        for (int i = startIdx; i < endIdx; i++) {
            operations.get(i).execute(index);
        }
    }
    
    @TearDown(Level.Iteration)
    public void tearDown() {
        // Calculate and report scalability metrics
        // Perfect scaling = throughput increases linearly with thread count
    }
}
```

### 2. Data Scalability Benchmark

```java
@State(Scope.Benchmark)
public class DataScalabilityBenchmark {
    
    @Param({"1000", "10000", "100000", "1000000", "10000000"})
    private int entityCount;
    
    @Param({"OCTREE", "TETREE", "PRISM"})
    private String indexType;
    
    private SpatialIndex<?, LongEntityID, String> index;
    private List<Point3f> queryPoints;
    
    @Setup(Level.Trial)
    public void setup() {
        index = createIndex(indexType);
        queryPoints = generateQueryPoints(1000);
        
        // Measure population time
        long startTime = System.nanoTime();
        populateIndex(index, entityCount);
        long populationTime = System.nanoTime() - startTime;
        
        System.out.println(String.format(
            "Population time for %d entities: %.2f seconds",
            entityCount,
            populationTime / 1e9
        ));
    }
    
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public List<LongEntityID> queryScalability() {
        Point3f query = queryPoints.get(
            ThreadLocalRandom.current().nextInt(queryPoints.size())
        );
        return index.kNearestNeighbors(query, 10, Float.MAX_VALUE);
    }
    
    @TearDown(Level.Trial)
    public void analyzeScalability() {
        // Log complexity analysis
        double logN = Math.log(entityCount) / Math.log(2);
        System.out.println(String.format(
            "Entity count: %d, log2(N): %.2f, Tree depth: %d",
            entityCount,
            logN,
            index.getMaxDepth()
        ));
    }
}
```

## Performance Regression Detection

### 1. Baseline Management

```java
public class PerformanceBaseline {
    private final Map<String, BaselineMetrics> baselines;
    
    public void saveBaseline(String testName, PerformanceReport report) {
        BaselineMetrics baseline = BaselineMetrics.builder()
            .testName(testName)
            .commitHash(GitUtils.getCurrentCommit())
            .timestamp(Instant.now())
            .meanLatency(report.getMeanLatencyNanos())
            .p99Latency(report.getP99LatencyNanos())
            .throughput(report.getThroughput())
            .build();
            
        baselines.put(testName, baseline);
        persistToFile();
    }
    
    public RegressionResult checkRegression(String testName, PerformanceReport current) {
        BaselineMetrics baseline = baselines.get(testName);
        if (baseline == null) {
            return RegressionResult.noBaseline();
        }
        
        double latencyIncrease = (current.getMeanLatencyNanos() - baseline.getMeanLatency()) 
                               / baseline.getMeanLatency();
        double throughputDecrease = (baseline.getThroughput() - current.getThroughput()) 
                                  / baseline.getThroughput();
        
        boolean isRegression = latencyIncrease > 0.10 || throughputDecrease > 0.10;
        
        return RegressionResult.builder()
            .hasRegression(isRegression)
            .latencyIncrease(latencyIncrease)
            .throughputDecrease(throughputDecrease)
            .baseline(baseline)
            .current(current)
            .build();
    }
}
```

### 2. Automated Regression Testing

```java
public class RegressionTestRunner {
    private final PerformanceBaseline baseline;
    private final List<String> criticalBenchmarks = Arrays.asList(
        "CoreOperations.insert",
        "CoreOperations.kNearestNeighbors",
        "CoreOperations.rangeQuery",
        "BulkOperations.bulkInsert"
    );
    
    public void runRegressionTests() throws RegressionException {
        List<RegressionResult> regressions = new ArrayList<>();
        
        for (String benchmark : criticalBenchmarks) {
            PerformanceReport current = runBenchmark(benchmark);
            RegressionResult result = baseline.checkRegression(benchmark, current);
            
            if (result.hasRegression()) {
                regressions.add(result);
            }
        }
        
        if (!regressions.isEmpty()) {
            generateRegressionReport(regressions);
            throw new RegressionException(
                "Performance regression detected in " + regressions.size() + " benchmarks"
            );
        }
    }
    
    private void generateRegressionReport(List<RegressionResult> regressions) {
        StringBuilder report = new StringBuilder();
        report.append("PERFORMANCE REGRESSION REPORT\n");
        report.append("=============================\n\n");
        
        for (RegressionResult regression : regressions) {
            report.append(String.format(
                "Benchmark: %s\n" +
                "Latency increase: %.1f%%\n" +
                "Throughput decrease: %.1f%%\n" +
                "Baseline: %.2f µs (mean), %.2f ops/sec\n" +
                "Current: %.2f µs (mean), %.2f ops/sec\n\n",
                regression.getTestName(),
                regression.getLatencyIncrease() * 100,
                regression.getThroughputDecrease() * 100,
                regression.getBaseline().getMeanLatency() / 1000.0,
                regression.getBaseline().getThroughput(),
                regression.getCurrent().getMeanLatencyNanos() / 1000.0,
                regression.getCurrent().getThroughput()
            ));
        }
        
        // Save report and notify
        Files.write(Paths.get("target/regression-report.txt"), report.toString().getBytes());
    }
}
```

## Running Benchmarks

### 1. Command Line Execution

```bash
# Run all benchmarks
mvn clean test -P benchmark

# Run specific benchmark
mvn test -Dtest=CoreOperationsBenchmark

# Run with specific JMH options
java -jar target/benchmarks.jar CoreOperationsBenchmark -f 1 -wi 3 -i 5 -p entityCount=10000

# Run with profiler
java -jar target/benchmarks.jar -prof gc -prof stack
```

### 2. CI Integration

```yaml
# .github/workflows/performance.yml
name: Performance Benchmarks

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  benchmark:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    
    - name: Cache Maven dependencies
      uses: actions/cache@v3
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
    
    - name: Run benchmarks
      run: |
        mvn clean package
        java -jar target/benchmarks.jar -rf json -rff results.json
    
    - name: Upload results
      uses: actions/upload-artifact@v3
      with:
        name: benchmark-results
        path: results.json
    
    - name: Check for regression
      run: |
        mvn test -Dtest=RegressionTestRunner
```

## Monitoring and Visualization

### 1. Metrics Export

```java
public class MetricsExporter {
    public void exportToPrometheus(List<PerformanceReport> reports) {
        for (PerformanceReport report : reports) {
            // Export as Prometheus metrics
            String metric = String.format(
                "lucien_%s_%s_latency_microseconds{index=\"%s\",percentile=\"0.99\"} %.2f",
                report.getOperationName().toLowerCase(),
                report.getIndexType().toLowerCase(),
                report.getIndexType(),
                report.getP99LatencyNanos() / 1000.0
            );
            
            PrometheusClient.push(metric);
        }
    }
    
    public void exportToInfluxDB(List<PerformanceReport> reports) {
        // Similar for InfluxDB
    }
}
```

### 2. Dashboard Setup

Create Grafana dashboards to visualize:
- Operation latencies over time
- Throughput trends
- Memory usage patterns
- GC impact
- Scalability curves

## Best Practices

1. **Isolation**: Run benchmarks on dedicated hardware
2. **Warmup**: Always include sufficient warmup iterations
3. **Variance**: Run multiple forks to account for JVM variance
4. **Profiling**: Use JMH profilers to understand performance
5. **Regression**: Automate regression detection in CI
6. **Documentation**: Document benchmark scenarios and parameters

## Conclusion

This implementation guide provides a comprehensive framework for performance testing Lucien. By combining JMH microbenchmarks with custom performance tracking, we can ensure consistent performance across releases and quickly identify regressions.