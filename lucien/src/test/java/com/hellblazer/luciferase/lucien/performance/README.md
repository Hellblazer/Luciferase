# Spatial Index Performance Testing Framework

This directory contains a comprehensive performance testing framework for evaluating the performance characteristics of
spatial index implementations (Octree and Tetree).

## Overview

The performance testing framework is designed to:

- Measure and compare performance between different spatial index implementations
- Test various operations: creation, queries, updates, collision detection, and memory usage
- Generate detailed performance metrics and CSV reports
- Support multiple spatial distributions for realistic testing scenarios

## Test Categories

### 1. Creation Performance Tests

- Tests bulk loading and incremental insertion
- Evaluates performance with different spatial distributions
- Measures scalability with varying data sizes

### 2. Query Performance Tests

- Range searches with varying query box sizes
- k-nearest neighbor searches
- Ray intersection tests
- Frustum culling performance

### 3. Memory Performance Tests

- Memory usage scaling with entity count
- Memory fragmentation after many operations
- Memory efficiency with different spatial distributions
- Empty node overhead measurement

### 4. Update Performance Tests (Phase 2)

- Entity movement and position updates
- Dynamic tree balancing performance
- Batch update operations

### 5. Collision Detection Tests (Phase 2)

- Broad phase collision detection
- Narrow phase collision tests
- Collision detection with different entity densities

## Running Performance Tests

Performance tests are disabled by default to avoid running during regular test cycles.

### Enable Performance Tests

```bash
export RUN_SPATIAL_INDEX_PERF_TESTS=true
mvn test -pl lucien
```

### Run Specific Performance Tests

```bash
# Run only Octree creation performance tests
mvn test -pl lucien -Dtest=OctreeCreationPerformanceTest

# Run all query performance tests
mvn test -pl lucien -Dtest=*QueryPerformanceTest
```

## Test Configuration

### Environment Variables

- `RUN_SPATIAL_INDEX_PERF_TESTS`: Set to `true` to enable performance tests

### Test Parameters (in AbstractSpatialIndexPerformanceTest)

- `WARMUP_ITERATIONS`: Number of warmup iterations before measurement (default: 3)
- `TEST_ITERATIONS`: Number of test iterations for averaging (default: 5)
- `TEST_SIZES`: Array of entity counts for scalability tests
- `SMOKE_TEST_SIZES`: Smaller sizes for quick validation

## Spatial Distributions

The framework supports multiple spatial distributions to simulate real-world scenarios:

1. **UNIFORM_RANDOM**: Entities uniformly distributed throughout the space
2. **CLUSTERED**: Entities grouped in clusters
3. **DIAGONAL**: Entities distributed along the main diagonal
4. **SURFACE_ALIGNED**: Entities concentrated on the surfaces of the bounding volume
5. **WORST_CASE**: All entities at the same position (stress test)

## Performance Metrics

Each test generates `PerformanceMetrics` objects containing:

- Operation name and entity count
- Elapsed time (nanoseconds, milliseconds, seconds)
- Memory usage (bytes, MB)
- Operations per second
- Additional custom metrics

Results are automatically exported to CSV files in `target/performance-results/` (created automatically when tests run).

## Extending the Framework

To add new performance tests:

1. Create a new abstract test class extending `AbstractSpatialIndexPerformanceTest`
2. Implement concrete test classes for Octree and Tetree
3. Use the `measure()` or `measureAverage()` methods for timing
4. Add results to `performanceResults` for CSV export

Example:

```java
public abstract class MyNewPerformanceTest<ID extends EntityID, Content>
extends AbstractSpatialIndexPerformanceTest<ID, Content> {

    @Test
    void testMyOperation() {
        PerformanceMetrics metrics = measure("my_operation", entityCount, () -> {
            // Your test code here
        });
        performanceResults.add(metrics);
    }
}
```

## Interpreting Results

Performance results are saved as CSV files with columns:

- `operation`: Name of the operation tested
- `entityCount`: Number of entities in the test
- `elapsedNanos`: Time in nanoseconds
- `memoryUsed`: Memory usage in bytes
- Additional custom metrics as key-value pairs

Use the CSV files to:

- Compare performance between Octree and Tetree
- Track performance regressions
- Identify optimization opportunities
- Generate performance graphs and reports

## Current Status

### Phase 1: Core Performance Tests ✅ COMPLETED

- Creation performance tests
- Query performance tests
- Memory performance tests

### Phase 2: Advanced Performance Tests (TODO)

- Update performance tests
- Collision detection performance tests
- Comparative analysis tools

### Phase 3: Reporting and Analysis (TODO)

- Automated performance report generation
- Performance regression detection
- Visualization tools
