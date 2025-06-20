# SpatialIndex Performance Testing Plan

Based on analysis of the C++ Octree performance testing implementation, this document outlines a comprehensive performance testing strategy for the Java SpatialIndex implementations (Octree and Tetree).

## C++ Octree Performance Testing Analysis

### Test Categories Found

1. **Unit Performance Tests** (`performance.tests.cpp`)
   - Point insertion performance (2D, 3D, 4D, 16D, 63D)
   - Box insertion performance (2D, 3D, 4D, 63D)
   - Range search performance
   - Both sequential and parallel execution modes
   - Structured (diagonal) and random distributions
   - Various tree depths (3, 4)
   - Scale: 1M to 10M entities

2. **Manual Benchmarks** (`benchmarks.cpp`)
   - Tree creation (sequential and parallel)
   - Range search operations
   - Collision detection
   - Self-conflict detection
   - Comparison with brute force approaches
   - Different spatial distributions:
     - Diagonal placement
     - Random placement
     - Cylindrical semi-random placement
   - Scale: 50 to 100M entities
   - CSV output for analysis

3. **Automated Benchmarks** (`main.cpp`)
   - Google Benchmark framework integration
   - Basic operations (GetNodeID, GetDepthID, GetNodeEntities)
   - Complexity analysis

### Key Performance Metrics

1. **Creation Performance**
   - Time to build tree from entities
   - Sequential vs parallel construction
   - Memory usage

2. **Query Performance**
   - Range search time
   - k-NN search time (not implemented in C++, but relevant for Java)
   - Ray intersection time

3. **Update Performance**
   - Entity insertion time
   - Entity removal time
   - Entity update/movement time

4. **Collision Detection**
   - Broad phase performance
   - Comparison with brute force O(n²)

## Java SpatialIndex Performance Testing Plan

### Environment Control

All performance tests will be controlled by the environment variable:
```java
boolean runPerformanceTests = Boolean.parseBoolean(
    System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false")
);
```

### Test Structure

```java
package com.hellblazer.luciferase.lucien.performance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractSpatialIndexPerformanceTest<ID extends EntityID, Content> {
    
    protected static final boolean RUN_PERF_TESTS = Boolean.parseBoolean(
        System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false")
    );
    
    @BeforeEach
    public void checkPerformanceTestsEnabled() {
        Assumptions.assumeTrue(RUN_PERF_TESTS, 
            "Performance tests disabled. Set RUN_SPATIAL_INDEX_PERF_TESTS=true to enable.");
    }
    
    // Abstract methods for concrete implementations
    protected abstract SpatialIndex<ID, Content> createSpatialIndex(
        VolumeBounds bounds, int maxDepth);
}
```

### Core Performance Test Categories

#### 1. Creation Performance Tests

```java
public class SpatialIndexCreationPerformanceTest {
    // Test data sizes (matching C++ scales)
    static final int[] SIZES = {50, 100, 500, 1000, 5000, 10000, 50000, 
                                100000, 500000, 1000000, 5000000, 10000000};
    
    @ParameterizedTest
    @MethodSource("spatialDistributions")
    void testCreationPerformance(SpatialDistribution distribution, int size) {
        // Measure creation time for different distributions:
        // - Uniform random
        // - Clustered (Gaussian)
        // - Diagonal (structured)
        // - Surface-aligned (cylindrical)
    }
    
    @Test
    void testBulkInsertionVsIncremental() {
        // Compare bulk loading vs one-by-one insertion
    }
    
    @Test
    void testParallelCreation() {
        // Test parallel construction performance
    }
}
```

#### 2. Query Performance Tests

```java
public class SpatialIndexQueryPerformanceTest {
    
    @ParameterizedTest
    @ValueSource(ints = {10, 100, 1000, 10000, 100000, 1000000})
    void testRangeSearchPerformance(int treeSize) {
        // Measure range search with varying:
        // - Tree sizes
        // - Query box sizes (1%, 5%, 10%, 25% of space)
        // - Result set sizes
    }
    
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 50, 100})
    void testKNNSearchPerformance(int k) {
        // k-NN search performance with varying k values
    }
    
    @Test
    void testRayIntersectionPerformance() {
        // Ray traversal performance
    }
    
    @Test
    void testFrustumCullingPerformance() {
        // View frustum culling performance
    }
}
```

#### 3. Update Performance Tests

```java
public class SpatialIndexUpdatePerformanceTest {
    
    @Test
    void testEntityMovementPerformance() {
        // Measure update performance for moving entities
        // - Small movements (within node)
        // - Large movements (across nodes)
        // - Batch updates
    }
    
    @Test
    void testDynamicInsertionPerformance() {
        // Insertion into existing tree
    }
    
    @Test
    void testDynamicRemovalPerformance() {
        // Removal from existing tree
    }
    
    @Test
    void testTreeRebalancingPerformance() {
        // Measure rebalancing overhead
    }
}
```

#### 4. Collision Detection Performance Tests

```java
public class SpatialIndexCollisionPerformanceTest {
    
    @ParameterizedTest
    @ValueSource(ints = {50, 100, 500, 1000, 5000, 10000})
    void testBroadPhaseCollisionPerformance(int entityCount) {
        // Compare spatial index vs brute force
        // Measure speedup factor
    }
    
    @Test
    void testSelfCollisionDetection() {
        // All pairs within single set
    }
    
    @Test
    void testCrossSetCollisionDetection() {
        // Collision between two entity sets
    }
}
```

#### 5. Memory Performance Tests

```java
public class SpatialIndexMemoryPerformanceTest {
    
    @Test
    void testMemoryUsageScaling() {
        // Measure memory per entity
        // Node overhead
        // Cache efficiency
    }
    
    @Test
    void testMemoryFragmentation() {
        // After many insertions/deletions
    }
}
```

### Spatial Distributions

```java
public enum SpatialDistribution {
    UNIFORM_RANDOM {
        @Override
        public List<Point3D> generate(int count, VolumeBounds bounds) {
            // Uniform random distribution
        }
    },
    CLUSTERED {
        @Override
        public List<Point3D> generate(int count, VolumeBounds bounds) {
            // Gaussian clusters
        }
    },
    DIAGONAL {
        @Override
        public List<Point3D> generate(int count, VolumeBounds bounds) {
            // Points along diagonal (structured)
        }
    },
    SURFACE_ALIGNED {
        @Override
        public List<Point3D> generate(int count, VolumeBounds bounds) {
            // Cylindrical or surface distribution
        }
    },
    WORST_CASE {
        @Override
        public List<Point3D> generate(int count, VolumeBounds bounds) {
            // Pathological case for tree structure
        }
    };
    
    public abstract List<Point3D> generate(int count, VolumeBounds bounds);
}
```

### Performance Metrics Collection

```java
public class PerformanceMetrics {
    private final String operation;
    private final long entityCount;
    private final long elapsedNanos;
    private final long memoryUsedBytes;
    private final Map<String, Object> additionalMetrics;
    
    // Methods for calculating:
    // - Operations per second
    // - Nanoseconds per operation
    // - Memory per entity
    // - Speedup vs baseline
    
    public void exportToCSV(Path outputPath) {
        // Export results in CSV format like C++ implementation
    }
}
```

### Benchmark Harness

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SpatialIndexBenchmarkSuite {
    
    private static final Path RESULTS_DIR = Paths.get("performance-results");
    
    @BeforeAll
    void setupBenchmarks() {
        Files.createDirectories(RESULTS_DIR);
    }
    
    @Test
    void runComprehensiveBenchmark() {
        // Run all benchmarks and generate report
        // Similar to C++ manual benchmark suite
    }
    
    private void warmup(SpatialIndex<?, ?> index) {
        // JVM warmup iterations
    }
    
    private PerformanceMetrics measure(String operation, Runnable task) {
        // Accurate timing with System.nanoTime()
        // Memory measurement with MemoryMXBean
    }
}
```

### Octree vs Tetree Comparison Tests

```java
public class OctreeVsTetreePerformanceTest {
    
    @ParameterizedTest
    @MethodSource("testScenarios")
    void comparePerformance(TestScenario scenario) {
        // Direct comparison of Octree vs Tetree
        // Same data, same operations
        // Measure relative performance
    }
}
```

### Performance Test Utilities

```java
public class PerformanceTestUtils {
    
    public static void assertPerformanceRegression(
            PerformanceMetrics baseline, 
            PerformanceMetrics current, 
            double maxRegressionPercent) {
        // Detect performance regressions
    }
    
    public static void generatePerformanceReport(
            List<PerformanceMetrics> results,
            Path outputPath) {
        // Generate HTML/Markdown performance report
    }
    
    public static void plotPerformanceGraphs(
            List<PerformanceMetrics> results,
            Path outputDir) {
        // Generate performance graphs (using JFreeChart or similar)
    }
}
```

## Features Not Included (Not Implemented by Both Trees)

The following performance tests require features not yet implemented by both Octree and Tetree:

1. **Parallel Operations**
   - Parallel tree construction
   - Concurrent queries
   - Thread-safe updates

2. **Advanced Spatial Queries**
   - Plane intersection queries (requires AbstractSpatialIndex enhancement)
   - Sphere queries
   - Polygon intersection

3. **Specialized Tree Operations**
   - Tree serialization/deserialization
   - Tree merging/splitting
   - Adaptive refinement

## Implementation Priority

1. **Phase 1: Core Performance Tests** (Week 1)
   - Creation performance
   - Basic query performance (range, k-NN)
   - Memory usage

2. **Phase 2: Advanced Performance Tests** (Week 2)
   - Update performance
   - Collision detection
   - Comparative analysis (Octree vs Tetree)

3. **Phase 3: Reporting and Analysis** (Week 3)
   - Performance regression detection
   - Automated reporting
   - Performance optimization recommendations

## Running Performance Tests

```bash
# Run all performance tests
RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test -Dtest="*PerformanceTest"

# Run specific performance test category
RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test -Dtest="SpatialIndexCreationPerformanceTest"

# Generate performance report
RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test -Dtest="SpatialIndexBenchmarkSuite"
```

## Expected Outcomes

1. **Performance Baselines**: Establish performance characteristics for both Octree and Tetree
2. **Scaling Analysis**: Understand how performance scales with data size
3. **Bottleneck Identification**: Identify performance bottlenecks for optimization
4. **Regression Prevention**: Automated tests to prevent performance regressions
5. **Documentation**: Comprehensive performance documentation for users

## Notes

- All tests use JUnit 5 with parameterized tests for comprehensive coverage
- Performance tests are disabled by default to not slow down regular test runs
- Results are exported in CSV format for analysis (matching C++ approach)
- Memory measurements use JVM instrumentation for accuracy
- Warmup iterations ensure JIT compilation doesn't skew results