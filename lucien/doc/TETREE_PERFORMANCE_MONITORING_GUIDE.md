# Tetree Performance Monitoring Guide

## Overview

The Tetree implementation includes built-in performance monitoring capabilities to help analyze and optimize spatial
operations. This guide explains how to use these features effectively.

## Enabling Performance Monitoring

Performance monitoring is disabled by default to avoid any overhead in production use. When disabled, there is zero
performance impact.

### Basic Usage

```java
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeMetrics;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;

// Create a Tetree instance
Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());

// Enable performance monitoring
tetree.

setPerformanceMonitoring(true);

// Perform operations...
// Insert entities, perform queries, etc.

// Retrieve and display metrics
TetreeMetrics metrics = tetree.getMetrics();
System.out.

println(metrics.getSummary());
```

### Resetting Counters

For benchmarking specific operations:

```java
// Reset all counters to zero
tetree.resetPerformanceCounters();

// Perform the operations you want to measure
for(
int i = 0;
i< 1000;i++){
tetree.

findAllFaceNeighbors(someIndex);
}

// Get metrics for just these operations
TetreeMetrics metrics = tetree.getMetrics();
System.out.

println("Average neighbor query time: "+
        metrics.getAverageNeighborQueryTimeMicros() +" µs");
```

## What Is Monitored

### 1. Neighbor Query Performance

- **Metric**: `neighborQueryCount`, `totalNeighborQueryTime`
- **Tracked Operations**:
    - `findAllFaceNeighbors()`
    - Face neighbor queries
- **Purpose**: Identify bottlenecks in neighbor-finding algorithms

### 2. Cache Performance

- **Metric**: `cacheHitRate`
- **Tracked**: TetreeLevelCache hit/miss ratio
- **Purpose**: Evaluate effectiveness of caching strategies

### 3. Tree Structure Statistics

- **Metrics**: Node count, depth, balance factor
- **Tracked**: Via `TetreeValidator.TreeStats`
- **Purpose**: Understand tree structure and identify imbalance

### 4. Tree Traversal Performance (Future)

- **Metric**: `traversalCount`, `totalTraversalTime`
- **Note**: Currently tracked but not actively used
- **Purpose**: Will monitor iterator and visitor performance

## Understanding the Metrics

### TetreeMetrics Record

```java
public record TetreeMetrics(TreeStats treeStatistics,           // Structural info
                            float cacheHitRate,                 // 0.0 to 1.0
                            float averageNeighborQueryTime,     // nanoseconds
                            float averageTraversalTime,         // nanoseconds
                            long neighborQueryCount,            // total queries
                            long traversalCount,                // total traversals
                            boolean monitoringEnabled           // current state
)
```

### Interpreting Results

#### Good Performance Indicators:

- Cache hit rate > 90%
- Average neighbor query time < 1000 ns (1 µs)
- Balanced tree (balance factor close to 1.0)

#### Warning Signs:

- Cache hit rate < 50%
- Neighbor query times > 10 µs
- Highly imbalanced tree (balance factor > 2.0)

## Performance Testing Example

```java
public class TetreePerformanceTest {

    public void benchmarkNeighborQueries() {
        Tetree<LongEntityID, String> tetree = createPopulatedTetree();

        // Enable monitoring
        tetree.setPerformanceMonitoring(true);

        // Warm up the cache
        for (int i = 0; i < 100; i++) {
            tetree.findAllFaceNeighbors(randomIndex());
        }

        // Reset counters after warmup
        tetree.resetPerformanceCounters();

        // Actual benchmark
        long startTime = System.nanoTime();
        int numQueries = 10000;

        for (int i = 0; i < numQueries; i++) {
            tetree.findAllFaceNeighbors(randomIndex());
        }

        long totalTime = System.nanoTime() - startTime;

        // Get detailed metrics
        TetreeMetrics metrics = tetree.getMetrics();

        System.out.println("Benchmark Results:");
        System.out.println("Total time: " + (totalTime / 1_000_000) + " ms");
        System.out.println("Queries per second: " + (numQueries * 1_000_000_000L / totalTime));
        System.out.println(metrics.getSummary());
    }
}
```

## Advanced Usage

### Custom Performance Analysis

```java
// Track specific operation patterns
tetree.setPerformanceMonitoring(true);

// Measure face neighbor performance
long faceNeighborStart = tetree.getMetrics().neighborQueryCount();

performFaceNeighborOperations();

long faceNeighborEnd = tetree.getMetrics().neighborQueryCount();

// Measure cache effectiveness during bulk operations
float initialHitRate = tetree.getMetrics().cacheHitRate();

performBulkInsertions();

float finalHitRate = tetree.getMetrics().cacheHitRate();

System.out.

println("Cache hit rate degradation: "+
        (initialHitRate -finalHitRate));
```

### Integration with Profiling Tools

```java
public void profileWithMetrics() {
    tetree.setPerformanceMonitoring(true);

    // Your profiler start

    // Operations to profile
    performSpatialOperations();

    // Your profiler end

    // Correlate with Tetree metrics
    TetreeMetrics metrics = tetree.getMetrics();
    exportMetricsToCSV(metrics);
}
```

## Performance Impact

When performance monitoring is enabled:

- **Time overhead**: ~10-20 nanoseconds per monitored operation
- **Memory overhead**: Negligible (a few long counters)
- **No allocations**: All tracking uses primitive counters

When disabled:

- **Zero overhead**: Monitoring checks are optimized away by JIT
- **No memory usage**: Counters remain at zero

## Best Practices

1. **Development**: Enable monitoring during development to understand performance characteristics
2. **Testing**: Use in unit tests to verify performance expectations
3. **Production**: Keep disabled unless diagnosing specific issues
4. **Benchmarking**: Always reset counters between benchmark runs
5. **Warmup**: Allow JIT compilation and cache warming before measurements

## Common Use Cases

### 1. Identifying Hotspots

```java
tetree.setPerformanceMonitoring(true);

runApplication();

TetreeMetrics metrics = tetree.getMetrics();
if(metrics.

neighborQueryCount() >1_000_000){
System.out.

println("High neighbor query load detected");
}
```

### 2. Comparing Algorithms

```java
// Algorithm A
tetree.resetPerformanceCounters();

runAlgorithmA();

TetreeMetrics metricsA = tetree.getMetrics();

// Algorithm B
tetree.

resetPerformanceCounters();

runAlgorithmB();

TetreeMetrics metricsB = tetree.getMetrics();

// Compare
System.out.

println("Algorithm A avg time: "+
        metricsA.getAverageNeighborQueryTimeMicros());
System.out.

println("Algorithm B avg time: "+
        metricsB.getAverageNeighborQueryTimeMicros());
```

### 3. Regression Testing

```java

@Test
public void testPerformanceRegression() {
    tetree.setPerformanceMonitoring(true);

    // Run standard workload
    runStandardWorkload();

    TetreeMetrics metrics = tetree.getMetrics();

    // Assert performance thresholds
    assertTrue("Neighbor query regression", metrics.getAverageNeighborQueryTimeMicros() < 2.0);
    assertTrue("Cache hit rate regression", metrics.getCacheHitPercentage() > 85.0);
}
```

## Future Enhancements

The performance monitoring infrastructure is designed to be extensible. Future metrics may include:

- Ray traversal performance
- Memory allocation tracking
- Subdivision/merge operation counts
- Entity spanning statistics
- Concurrent access patterns

## Troubleshooting

### High Neighbor Query Times

- Check tree balance - imbalanced trees require more traversal
- Verify cache hit rate - low rates indicate cache thrashing
- Consider data distribution - clustered data may cause hotspots

### Low Cache Hit Rate

- May indicate random access patterns
- Could suggest working set larger than cache
- Check if operations span multiple levels frequently

### Monitoring Not Working

- Ensure `setPerformanceMonitoring(true)` is called
- Verify operations are actually occurring
- Check that you're calling monitored methods (e.g., `findAllFaceNeighbors`)
