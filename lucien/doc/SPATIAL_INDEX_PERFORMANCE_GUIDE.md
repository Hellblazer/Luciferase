# Spatial Index Performance Guide

This comprehensive guide covers performance optimization for both Octree and Tetree spatial indices in the Luciferase system, including general strategies, implementation-specific optimizations, monitoring, and best practices.

## Overview

The Luciferase system provides two spatial index implementations:

- **Octree**: Uses Morton encoding (simple bit interleaving) for O(1) operations
- **Tetree**: Uses tetrahedral decomposition with TM-index for memory efficiency

## Performance Characteristics (July 2025)

### Operation Performance Comparison

| Operation | Entity Count | Octree | Tetree | Winner | Ratio |
|-----------|-------------|---------|---------|---------|-------|
| **Individual Insertion** | 100 | 5.58 μs/entity | 28.42 μs/entity | Octree | 5.1x faster |
| | 1,000 | 2.47 μs/entity | 7.66 μs/entity | Octree | 3.1x faster |
| | 10,000 | 1.03 μs/entity | 5.27 μs/entity | Octree | 5.1x faster |
| **Bulk Loading** | 50,000 | 82 ms | 53 ms | Tetree | 35% faster |
| | 100,000 | 162 ms | 101 ms | Tetree | 38% faster |
| **k-NN Search** | 10,000 | 36.26 μs | 12.63 μs | Tetree | 2.9x faster |
| **Range Query** | 10,000 | 21.12 μs | 162.70 μs | Octree | 7.7x faster |
| **Update** | - | ~0.002 μs | ~0.005 μs | Octree | 2.5x faster |

### Memory Usage Comparison

| Entity Count | Octree (MB) | Tetree (MB) | Tetree Efficiency |
|--------------|-------------|-------------|-------------------|
| 100          | 0.15        | 0.04        | 74% less memory   |
| 1,000        | 1.39        | 0.33        | 76% less memory   |
| 10,000       | 12.89       | 3.31        | 74% less memory   |

## Choosing the Right Index

### Use Octree When:
- **Real-time insertions** are critical (game engines, simulations)
- **Range queries** dominate your workload
- **Frequent updates** to entity positions
- **Consistent low latency** is required
- Memory usage is not a primary concern

### Use Tetree When:
- **Bulk loading** large datasets (50K+ entities)
- **k-NN queries** are primary operation
- **Memory efficiency** is critical (embedded systems)
- **Read-heavy workloads** with infrequent updates
- Better **spatial locality** for cache performance

## General Optimization Strategies

### 1. Bulk Operations Configuration

Bulk operations provide 5-10x performance improvement over iterative insertion:

```java
// High-performance bulk loading
BulkOperationConfig config = BulkOperationConfig.highPerformance()
    .withBatchSize(10000)
    .withDeferSubdivision(true)
    .withPreSortByMorton(true)
    .withEnableParallel(true)
    .withStackBasedBuilder(true);

spatialIndex.configureBulkOperations(config);
```

#### Configuration Parameters

| Parameter | Default | Recommended | Description |
|-----------|---------|-------------|-------------|
| `batchSize` | 1000 | 1000-10000 | Entities processed per batch |
| `deferSubdivision` | false | true | Delay node splitting until bulk complete |
| `preSortByMorton` | false | true | Sort entities by spatial code first |
| `enableParallel` | false | true (>100K) | Use parallel processing |
| `useStackBasedBuilder` | false | true (>10K) | Use iterative tree building |

### 2. Memory Optimization

#### Node Pre-allocation

```java
// Estimate and pre-allocate nodes
int estimatedNodes = NodeEstimator.estimateNodeCount(
    entityCount: 100000,
    maxEntitiesPerNode: 32,
    maxDepth: 20,
    distribution: NodeEstimator.SpatialDistribution.uniform()
);

spatialIndex.preAllocateNodes(100000, NodeEstimator.SpatialDistribution.uniform());
```

#### Memory Usage Patterns

| Distribution | Memory Factor | Recommended Pre-allocation |
|--------------|---------------|----------------------------|
| Uniform      | 1.0x          | `entityCount / 20` nodes   |
| Clustered    | 0.7x          | `entityCount / 30` nodes   |
| Surface      | 0.5x          | `entityCount / 40` nodes   |

#### Node Pooling for Dynamic Workloads

```java
// Create a pool for frequent insertions/deletions
SpatialNodePool<NodeType> pool = new SpatialNodePool<>(10000);
pool.preallocate(10000, NodeType::new);
spatialIndex.configureSpatialNodePool(pool);

// Monitor pool efficiency
if (pool.getHitRate() < 0.8) {
    pool.preallocate(5000, NodeType::new);
}
```

### 3. Parallel Processing

```java
// Configure parallel operations
ParallelBulkOperations.ParallelConfig parallelConfig = 
    ParallelBulkOperations.ParallelConfig.highPerformanceConfig()
        .withThreadCount(Runtime.getRuntime().availableProcessors())
        .withBatchSize(1000)
        .withWorkStealing(true)
        .withTaskThreshold(100);

ParallelBulkOperations<ID, Content, NodeType> parallelOps = 
    new ParallelBulkOperations<>(spatialIndex, bulkProcessor, parallelConfig);
```

#### Scaling Guidelines

- **< 10K entities**: Single-threaded is usually faster
- **10K - 100K entities**: 2-4 threads optimal
- **100K - 1M entities**: Use all available cores
- **> 1M entities**: Consider NUMA optimization

### 4. Subdivision Strategies

```java
// For dense point clouds
SubdivisionStrategy strategy = OctreeSubdivisionStrategy.forDensePointClouds()
    .withMinEntitiesForSplit(8)
    .withLoadFactor(0.9);

// For large bounding boxes
SubdivisionStrategy strategy = OctreeSubdivisionStrategy.forLargeEntities()
    .withMinEntitiesForSplit(2)
    .withSpanningThreshold(0.7);

spatialIndex.setSubdivisionStrategy(strategy);
```

## Octree-Specific Optimizations

### Morton Code Optimization
- O(1) encoding/decoding operations
- Pre-sort data by Morton code for better cache locality
- Use bit manipulation optimizations

### Range Query Optimization
```java
// Pre-compute frequently queried regions
octree.precomputeRegion(minBound, maxBound);

// Use spatial hints
List<ID> results = octree.entitiesInRegion(min, max, SpatialHint.MOSTLY_CONTAINED);
```

## Tetree-Specific Optimizations

### Recent Performance Improvements (June-July 2025)

1. **V2 tmIndex Optimization**: 4x speedup (0.23 μs → 0.06 μs per call)
2. **Parent Cache**: 17-67x speedup for deep tree operations
3. **Efficient Child Computation**: 3x faster child lookups
4. **Subdivision Fix**: Proper tree structure, 38-96% performance improvement

### Performance Monitoring

Enable built-in performance monitoring for Tetree:

```java
// Enable monitoring
tetree.setPerformanceMonitoring(true);

// Perform operations...

// Get metrics
TetreeMetrics metrics = tetree.getMetrics();
System.out.println(metrics.getSummary());

// Key metrics to monitor:
// - Cache hit rate (should be > 90%)
// - Average neighbor query time (< 1 μs is good)
// - Tree balance factor (close to 1.0 is ideal)
```

### Tetree-Specific Best Practices

1. **Leverage Bulk Loading**: Tetree excels at bulk operations
2. **Monitor Cache Performance**: Ensure high cache hit rates
3. **Use for k-NN Queries**: 2.9x faster than Octree
4. **Consider Memory Constraints**: 74-76% less memory than Octree

## Query Optimization

### k-NN Search Optimization

```java
// Cache repeated queries
spatialIndex.enableQueryCache(1000);

// Configure for large k values
KNearestNeighborConfig knnConfig = new KNearestNeighborConfig()
    .withInitialRadius(100.0f)
    .withRadiusMultiplier(1.5f)
    .withMaxIterations(10);
```

### Performance by Query Type

| Query Type | Best Index | Optimization Strategy |
|------------|------------|----------------------|
| k-NN | Tetree | Use query caching, optimize initial radius |
| Range | Octree | Pre-compute regions, use spatial hints |
| Ray | Either | Enable frustum culling, use early termination |

## Performance Benchmarking

### Benchmark Template

```java
public void benchmarkSpatialIndex() {
    // Disable assertions for accurate timing
    // Run with: -ea:none or -da
    
    SpatialIndex index = createIndex();
    
    // Enable monitoring (Tetree only)
    if (index instanceof Tetree) {
        ((Tetree) index).setPerformanceMonitoring(true);
    }
    
    // Warm up (important for JIT)
    for (int i = 0; i < 1000; i++) {
        index.insert(randomPosition(), content, level);
    }
    
    // Reset counters
    if (index instanceof Tetree) {
        ((Tetree) index).resetPerformanceCounters();
    }
    
    // Actual benchmark
    long start = System.nanoTime();
    // ... perform operations ...
    long elapsed = System.nanoTime() - start;
    
    // Analyze results
    System.out.printf("Operations/second: %.2f%n", 
        numOperations * 1_000_000_000.0 / elapsed);
}
```

## JVM Tuning

For optimal performance with large datasets:

```bash
java -Xmx8g -Xms8g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+UseNUMA \
     -XX:+AggressiveOpts \
     -da \  # Disable assertions for performance
     YourApplication
```

## Configuration Templates

### High-Throughput Configuration
```java
BulkOperationConfig config = BulkOperationConfig.highPerformance()
    .withBatchSize(10000)
    .withDeferSubdivision(true)
    .withPreSortByMorton(true)
    .withEnableParallel(true)
    .withStackBasedBuilder(true);
```

### Memory-Efficient Configuration
```java
BulkOperationConfig config = BulkOperationConfig.memoryEfficient()
    .withBatchSize(1000)
    .withNodePoolSize(5000)
    .withAdaptivePreAllocation(true);
```

### Balanced Configuration
```java
BulkOperationConfig config = BulkOperationConfig.balanced()
    .withBatchSize(5000)
    .withDeferSubdivision(true)
    .withPreSortByMorton(true);
```

## Troubleshooting Performance Issues

### High Memory Usage
1. Check node occupancy: `avgOccupancy < 0.5` indicates sparse tree
2. Enable node pooling for dynamic workloads
3. Use adaptive pre-allocation
4. Consider Tetree for 74-76% memory reduction

### Poor Query Performance
1. Check tree balance: `maxDepth > 15` indicates imbalance
2. Enable query caching for repeated queries
3. Use appropriate spatial hints
4. Consider index type (Tetree for k-NN, Octree for range)

### Slow Bulk Loading
1. Ensure pre-sorting is enabled
2. Use stack-based builder for large datasets
3. Enable parallel processing for >100K entities
4. Check lock contention in parallel mode
5. Consider Tetree for 35-38% faster bulk loading at scale

### Tetree-Specific Issues

#### Low Cache Hit Rate
- Indicates random access patterns
- Working set larger than cache
- Operations spanning multiple levels frequently

#### High Neighbor Query Times
- Check tree balance
- Verify cache hit rate
- Consider data distribution

## Best Practices Summary

1. **Profile First**: Measure your specific use case before optimizing
2. **Choose Wisely**: Octree for writes/ranges, Tetree for reads/memory
3. **Batch Everything**: Always batch operations when possible
4. **Pre-sort Data**: Use spatial sorting for better locality
5. **Monitor Production**: Track performance metrics in real deployments
6. **Disable Assertions**: Use `-da` flag for performance testing
7. **Warm Up JIT**: Allow proper warmup before benchmarking

## Performance Trade-offs Summary

| Use Case | Recommended | Reason |
|----------|-------------|--------|
| Real-time game entities | Octree | Consistent low-latency insertions |
| Bulk point cloud loading | Tetree | 35-38% faster at large scales |
| k-NN intensive apps | Tetree | 2.9x faster searches |
| Range query heavy | Octree | 7.7x faster range queries |
| Memory constrained | Tetree | 74-76% less memory usage |
| Mixed workload | Profile first | Depends on read/write ratio |

## Conclusion

The key to optimal spatial index performance is understanding your workload:

1. **Insertion-heavy**: Choose Octree for O(1) Morton encoding
2. **Query-heavy**: Choose Tetree for better k-NN performance
3. **Memory-limited**: Choose Tetree for 74-76% reduction
4. **Bulk loading**: Choose Tetree for 35-38% faster performance
5. **Always**: Use bulk operations, pre-allocation, and appropriate configuration

Start with your dominant operation pattern to choose the right spatial index, then apply the optimization strategies outlined in this guide.