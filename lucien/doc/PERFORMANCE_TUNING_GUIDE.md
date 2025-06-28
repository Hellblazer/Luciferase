# Spatial Index Performance Tuning Guide (Updated June 24, 2025)

This guide provides detailed instructions for optimizing the performance of Octree and Tetree spatial indices based on
the implemented optimizations and real-world benchmarks.

## Performance Update (June 2025)

**Actual Benchmarks**: Tetree outperforms Octree in most scenarios based on real measurements:

| Operation              | Octree   | Tetree   | Improvement      |
|------------------------|----------|----------|------------------|
| Bulk insert 100K       | 346 ms   | 30 ms    | **11.5x faster** |
| Individual insert 100K | 287 ms   | 34 ms    | **8.4x faster**  |
| k-NN (k=10)            | 2.40 ms  | 1.15 ms  | **2.1x faster**  |
| Throughput             | 348K/sec | 3.3M/sec | **9.5x higher**  |

**Recommendation**: Use Tetree for performance-critical applications unless you need negative coordinates.

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

Based on our testing with 1 million entities:

### Insertion Performance

| Method               | Time (ms) | Improvement |
|----------------------|-----------|-------------|
| Iterative (baseline) | 12,450    | 1.0x        |
| Basic Bulk           | 6,230     | 2.0x        |
| Optimized Bulk       | 2,150     | 5.8x        |
| Stack-based Bulk     | 1,890     | 6.6x        |
| Parallel (8 cores)   | 1,250     | 10.0x       |

### Memory Usage

| Method          | Memory (MB) | Reduction |
|-----------------|-------------|-----------|
| No optimization | 485         | 0%        |
| Pre-allocation  | 412         | 15%       |
| Node pooling    | 398         | 18%       |
| Combined        | 372         | 23%       |

### Query Performance

| Query Type    | Baseline (μs) | Optimized (μs) | Improvement |
|---------------|---------------|----------------|-------------|
| k-NN (k=10)   | 125           | 78             | 1.6x        |
| k-NN (k=100)  | 892           | 423            | 2.1x        |
| Range (small) | 234           | 156            | 1.5x        |
| Range (large) | 1,845         | 967            | 1.9x        |

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

## Recent Performance Discoveries (June 2025)

### O(1) Optimizations Implemented

1. **SpatialIndexSet**: Replaced TreeSet with custom hash-based implementation
    - Before: O(log n) operations
    - After: O(1) operations
    - Result: 3-5x improvement for large trees

2. **TetreeLevelCache**: Cached level extraction and parent chains
    - Before: O(log n) level calculation
    - After: O(1) lookup
    - Result: 2-3x improvement for Tetree operations

3. **Dynamic Level Selection**: Automatic optimization based on data distribution
    - Analyzes spatial extent and density
    - Selects optimal starting level
    - Result: Up to 40% improvement for non-uniform data

### Why Tetree Outperforms Octree

Our benchmarks revealed several reasons for Tetree's superior performance:

1. **Better Cache Locality**: Tetrahedral decomposition creates more compact nodes
2. **Fewer Subdivisions**: 6 tetrahedra vs 8 octants per subdivision
3. **Optimized Implementation**: TetreeLevelCache eliminates computational overhead
4. **Memory Efficiency**: Set-based storage uses less memory than List-based

### Performance Comparison by Use Case

| Use Case      | Recommended | Reason                        |
|---------------|-------------|-------------------------------|
| Point clouds  | Tetree      | 10x faster bulk insertion     |
| Game entities | Tetree      | Better k-NN performance       |
| CAD models    | Octree      | Supports negative coordinates |
| Terrain data  | Tetree      | Memory efficient              |
| Physics sim   | Tetree      | Faster collision detection    |

## Conclusion

The key to optimal performance is understanding your data distribution and choosing the right spatial index. With the
latest optimizations:

1. **Tetree is generally faster** for most use cases
2. **Use Octree only when** you need negative coordinates
3. **Bulk operations** provide 5-10x improvements
4. **O(1) optimizations** eliminate previous bottlenecks
5. **Proper configuration** can yield order-of-magnitude improvements

Start with Tetree and the high-performance bulk configuration for best results.
