# Spatial Index Performance Tuning Guide (Updated June 28, 2025)

This guide provides detailed instructions for optimizing the performance of Octree and Tetree spatial indices based on
the implemented optimizations and real-world benchmarks.

## Performance Update (June 28, 2025)

After V2 tmIndex optimization and parent cache implementation, Tetree now performs better than Octree in bulk loading scenarios at large scales.

### Individual Operation Performance
| Operation | Entity Count | Octree | Tetree | Faster | Performance Ratio |
|-----------|-------------|---------|---------|---------|-------------------|
| Insertion | 100 | 5.58 μs/entity | 28.42 μs/entity | Octree | 5.1x faster |
| | 1,000 | 2.47 μs/entity | 7.66 μs/entity | Octree | 3.1x faster |
| | 10,000 | 1.03 μs/entity | 5.27 μs/entity | Octree | 5.1x faster |
| k-NN Search | 10,000 | 36.26 μs | 12.63 μs | Tetree | 2.9x faster |
| Range Query | 10,000 | 21.12 μs | 162.70 μs | Octree | 7.7x faster |

### Bulk Loading Performance
| Entity Count | Octree Bulk | Tetree Bulk | Faster | Performance |
|-------------|-------------|-------------|---------|-------------|
| 50,000 | 82 ms | 53 ms | Tetree | 35% faster |
| 100,000 | 162 ms | 101 ms | Tetree | 38% faster |

**Recommendation**: Use Tetree for bulk loading scenarios (50K+ entities) and k-NN query intensive applications. Use Octree for real-time individual insertions and range queries.

## Quick Start

### Best Configuration for Common Use Cases

#### Dense Point Clouds (millions of points)

```java
BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                .withBatchSize(10000)
                                                .withStackBasedBuilder(true)
                                                .withEnableParallel(true)
                                                .withParallelThreshold(100000);

octree.

configureBulkOperations(config);
octree.

preAllocateNodes(pointCount, NodeEstimator.SpatialDistribution.uniform());
```

#### Large Entities with Spanning

```java
SubdivisionStrategy strategy = OctreeSubdivisionStrategy.forLargeEntities();
octree.

setSubdivisionStrategy(strategy);

BulkOperationConfig config = BulkOperationConfig.defaultConfig().withDeferSubdivision(
                                                false)  // Immediate subdivision for large entities
                                                .withBatchSize(100);
```

#### Real-time Updates

```java
// Use memory pooling for frequent insertions/deletions
SpatialNodePool<OctreeNode> pool = new SpatialNodePool<>(10000);
pool.preallocate(5000, OctreeNode::new);
octree.configureSpatialNodePool(pool);
```

## Optimization Techniques

### 1. Bulk Operations

Bulk operations provide 5-10x performance improvement over iterative insertion.

#### Configuration Options

| Parameter              | Default | Recommended           | Description                              |
|------------------------|---------|-----------------------|------------------------------------------|
| `batchSize`            | 1000    | 1000-10000            | Entities processed per batch             |
| `deferSubdivision`     | false   | true                  | Delay node splitting until bulk complete |
| `preSortByMorton`      | false   | true                  | Sort entities by Morton code first       |
| `enableParallel`       | false   | true (large datasets) | Use parallel processing                  |
| `useStackBasedBuilder` | false   | true (>10K entities)  | Use iterative tree building              |

#### Example: Optimized Bulk Loading

```java
// For 1 million uniformly distributed points
BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                .withBatchSize(10000)
                                                .withDeferSubdivision(true)
                                                .withPreSortByMorton(true)
                                                .withStackBasedBuilder(true)
                                                .withStackBuilderThreshold(10000);

octree.

configureBulkOperations(config);

// Pre-allocate nodes for better memory efficiency
octree.

preAllocateNodes(1_000_000,NodeEstimator.SpatialDistribution.uniform());

// Perform bulk insertion
List<ID> ids = octree.insertBatch(positions, contents, level);
```

### 2. Memory Optimization

#### Node Pre-allocation

Pre-allocating nodes reduces memory fragmentation and allocation overhead.

```java
// For uniform distribution
int estimatedNodes = NodeEstimator.estimateNodeCount(entityCount:100000,
maxEntitiesPerNode:32,
maxDepth:20,
distribution:NodeEstimator.SpatialDistribution.

uniform()
);

octree.

preAllocateNodes(100000,NodeEstimator.SpatialDistribution.uniform());
```

#### Node Pooling

For scenarios with frequent insertions and deletions:

```java
// Create a pool with 10,000 pre-allocated nodes
SpatialNodePool<OctreeNode> pool = new SpatialNodePool<>(10000);
pool.preallocate(10000, OctreeNode::new);

octree.configureSpatialNodePool(pool);

// Monitor pool efficiency
double hitRate = pool.getHitRate();
if (hitRate < 0.8) {
    // Pool too small, increase size
    pool.preallocate(5000, OctreeNode::new);
}
```

#### Memory Usage Patterns

| Distribution | Memory Factor | Recommended Pre-allocation |
|--------------|---------------|----------------------------|
| Uniform      | 1.0x          | `entityCount / 20` nodes   |
| Clustered    | 0.7x          | `entityCount / 30` nodes   |
| Surface      | 0.5x          | `entityCount / 40` nodes   |

### 3. Parallel Processing

#### Thread Configuration

```java
ParallelBulkOperations.ParallelConfig parallelConfig = ParallelBulkOperations.ParallelConfig.highPerformanceConfig()
                                                                                            .withThreadCount(
                                                                                            Runtime.getRuntime()
                                                                                                   .availableProcessors())
                                                                                            .withBatchSize(1000)
                                                                                            .withWorkStealing(true)
                                                                                            .withTaskThreshold(100);

ParallelBulkOperations<ID, Content, NodeType> parallelOps = new ParallelBulkOperations<>(spatialIndex, bulkProcessor,
                                                                                         parallelConfig);

ParallelOperationResult<ID> result = parallelOps.insertBatchParallel(positions, contents, level);
```

#### Scaling Guidelines

- **< 10K entities**: Single-threaded is usually faster
- **10K - 100K entities**: 2-4 threads optimal
- **100K - 1M entities**: Use all available cores
- **> 1M entities**: Consider NUMA optimization

### 4. Subdivision Strategies

#### Choosing the Right Strategy

```java
// For dense point clouds
SubdivisionStrategy strategy = OctreeSubdivisionStrategy.forDensePointClouds()
                                                        .withMinEntitiesForSplit(8)
                                                        .withLoadFactor(0.9);

// For large bounding boxes
SubdivisionStrategy strategy = OctreeSubdivisionStrategy.forLargeEntities()
                                                        .withMinEntitiesForSplit(2)
                                                        .withSpanningThreshold(0.7);

// For mixed workloads
SubdivisionStrategy strategy = OctreeSubdivisionStrategy.balanced();

octree.

setSubdivisionStrategy(strategy);
```

#### Strategy Comparison

| Strategy       | Best For     | Split Threshold | Load Factor |
|----------------|--------------|-----------------|-------------|
| Dense Points   | Point clouds | 8 entities      | 0.9         |
| Large Entities | CAD models   | 2 entities      | 0.5         |
| Balanced       | General use  | 4 entities      | 0.75        |

### 5. Query Optimization

#### k-NN Search Optimization

```java
// For repeated k-NN queries in the same region
octree.enableQueryCache(1000); // Cache last 1000 queries

// For large k values
KNearestNeighborConfig knnConfig = new KNearestNeighborConfig()
    .withInitialRadius(100.0f)
    .withRadiusMultiplier(1.5f)
    .withMaxIterations(10);
```

#### Range Query Optimization

```java
// Pre-compute frequently queried regions
octree.precomputeRegion(minBound, maxBound);

// Use spatial hints for better performance
List<ID> results = octree.entitiesInRegion(min, max, SpatialHint.MOSTLY_CONTAINED);
```

## Performance Benchmarks

Based on our testing with OctreeVsTetreeBenchmark (June 28, 2025):

### Insertion Performance (Individual Operations)

| Entity Count | Octree (μs/entity) | Tetree (μs/entity) | Winner |
|--------------|--------------------|--------------------|--------|
| 100          | 5.58               | 28.42              | Octree 5.1x faster |
| 1,000        | 2.47               | 7.66               | Octree 3.1x faster |
| 10,000       | 1.03               | 5.27               | Octree 5.1x faster |

### Memory Usage Comparison

| Entity Count | Octree (MB) | Tetree (MB) | Tetree Savings |
|--------------|-------------|-------------|----------------|
| 100          | 0.15        | 0.04        | **74% less**   |
| 1,000        | 1.39        | 0.33        | **76% less**   |
| 10,000       | 12.89       | 3.31        | **74% less**   |

### Query Performance (10K entities)

| Query Type      | Octree (μs) | Tetree (μs) | Winner |
|-----------------|-------------|-------------|--------|
| k-NN (k=10)     | 36.26       | 12.63       | Tetree 2.9x faster |
| Range (10x10x10)| 21.12       | 162.70      | Octree 7.7x faster |

## Profiling and Monitoring

### Key Metrics to Monitor

```java
// Get performance statistics
Map<String, Object> stats = octree.getPerformanceStatistics();

// Important metrics:
// - nodeCount: Total nodes in tree
// - maxDepth: Maximum tree depth
// - averageNodeOccupancy: Entities per node
// - subdivisionCount: Number of node splits
// - memoryUsage: Estimated memory consumption
```

### JVM Tuning

For optimal performance with large datasets:

```bash
java -Xmx8g -Xms8g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+UseNUMA \
     -XX:+AggressiveOpts \
     YourApplication
```

## Troubleshooting

### High Memory Usage

1. Check node occupancy:

```java
double avgOccupancy = octree.getAverageNodeOccupancy();
if (avgOccupancy < 0.5) {
    // Tree is too sparse, adjust subdivision strategy
}
```

2. Enable node pooling for dynamic workloads
3. Use adaptive pre-allocation based on actual distribution

### Poor Query Performance

1. Check tree balance:

```java
int maxDepth = octree.getMaxDepth();
if (maxDepth > 15) {
    // Tree too deep, consider rebalancing
}
```

2. Enable query caching for repeated queries
3. Use appropriate spatial hints

### Slow Bulk Loading

1. Ensure pre-sorting is enabled
2. Use stack-based builder for large datasets
3. Enable parallel processing for >100K entities
4. Check lock contention in parallel mode

## Best Practices

1. **Always benchmark** with your specific data distribution
2. **Profile memory usage** before and after optimization
3. **Start with default configurations** and tune based on results
4. **Monitor performance metrics** in production
5. **Use appropriate data structures** (Octree for uniform, Tetree for irregular)

## Configuration Templates

### High-Throughput Configuration

```java
// For maximum insertion speed
BulkOperationConfig config = BulkOperationConfig.highPerformance()
    .withBatchSize(10000)
    .withDeferSubdivision(true)
    .withPreSortByMorton(true)
    .withEnableParallel(true)
    .withUseStackBasedBuilder(true);
```

### Memory-Efficient Configuration

```java
// For minimum memory usage
BulkOperationConfig config = BulkOperationConfig.memoryEfficient()
                                                .withBatchSize(1000)
                                                .withNodePoolSize(5000)
                                                .withAdaptivePreAllocation(true);
```

### Balanced Configuration

```java
// For general-purpose use
BulkOperationConfig config = BulkOperationConfig.balanced()
    .withBatchSize(5000)
    .withDeferSubdivision(true)
    .withPreSortByMorton(true);
```

## Recent Performance Discoveries (June 28, 2025)

### Key Optimizations Implemented

1. **V2 tmIndex Optimization**: Streamlined parent chain collection
    - Before: Complex caching with fallbacks
    - After: Single loop parent collection
    - Result: 4x speedup (0.23 μs → 0.06 μs per call)

2. **Parent Cache**: Cached parent operations for Tetree
    - Cache hit rate: 98-100% for repeated access
    - Result: 17-67x speedup for deep tree operations

3. **Bulk Loading Optimization**: Deferred subdivision for batch operations
    - Tetree benefits massively from bulk operations
    - Result: Tetree 35-38% faster than Octree at 50K+ entities

### Performance Trade-offs

**Octree Advantages:**
- O(1) Morton encoding for individual insertions
- Consistent low latency for real-time operations
- Superior range query performance (7.7x faster)

**Tetree Advantages:**
- Better bulk loading at scale (35-38% faster at 50K+)
- Better k-NN search performance (2.9x faster)
- 74-76% memory savings across all scales
- Good cache locality for repeated operations

### Performance Comparison by Use Case

| Use Case | Recommended | Reason |
|----------|-------------|--------|
| Real-time game entities | Octree | Consistent low-latency insertions |
| Bulk point cloud loading | Tetree | 35-38% faster at large scales |
| k-NN intensive apps | Tetree | 2.9x faster searches |
| Range query heavy | Octree | 7.7x faster range queries |
| Memory constrained | Tetree | 74-76% less memory usage |

## Conclusion

The key to optimal performance is understanding your data distribution and operation patterns:

1. **Choose based on your workload**:
   - Octree: Real-time insertions, range queries, consistent latency
   - Tetree: Bulk loading (50K+), k-NN searches, memory efficiency

2. **Bulk operations are critical**: Both implementations benefit massively
   - Octree: Up to 10x improvement with bulk operations
   - Tetree: 35-38% faster than Octree for bulk loads at scale

3. **Memory matters**: Tetree uses 74-76% less memory consistently

4. **Recent optimizations make a difference**:
   - V2 tmIndex: 4x speedup for Tetree operations
   - Parent cache: Up to 67x improvement for deep trees
   - Bulk loading: Tetree now performs better than Octree at large scales
   - Geometric subdivision: 5.5x faster than grid-based child() operations

5. **Geometric Subdivision Performance** (June 28, 2025):
   - Operation time: ~0.04 μs per subdivision
   - Throughput: ~25 million subdivisions/second
   - Comparison: 5.5x faster than 8 individual child() calls
   - Use case: When you need all 8 children with geometric containment guarantees

Start with your dominant operation pattern (individual vs bulk, insertion vs query) to choose the right spatial index.
