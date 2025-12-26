# Bulk Operations API Documentation

**Last Updated**: 2025-12-08
**Status**: Current

The Bulk Operations API provides high-performance methods for inserting, updating, and managing large numbers of
entities in spatial indices. These operations can be 5-10x faster than individual insertions.

## Overview

Bulk operations optimize performance through:

- Batch processing to reduce overhead
- Deferred node subdivision
- Parallel processing for large datasets
- Morton code pre-sorting for spatial locality
- Stack-based tree construction
- Memory pre-allocation

## API Methods

### Basic Batch Insertion

```java
List<ID> insertBatch(
    List<Point3f> positions, 
    List<Content> contents, 
    byte level)

```

Insert multiple entities in a single operation.

**Parameters:**

- `positions` - List of 3D positions
- `contents` - List of content objects (same length as positions)
- `level` - Spatial refinement level

**Returns:**

- List of generated entity IDs in the same order as inputs

**Example:**

```java
// Prepare data
List<Point3f> positions = new ArrayList<>();
List<String> contents = new ArrayList<>();

for(
int i = 0;
i< 100000;i++){
positions.

add(new Point3f(
random.nextFloat() *1000,
random.

nextFloat() *1000,
random.

nextFloat() *1000
));
contents.

add("Entity "+i);
}

// Bulk insert
List<ID> entityIds = spatialIndex.insertBatch(positions, contents, (byte) 10);

```

### Batch Insertion with Spanning

```java
List<ID> insertBatchWithSpanning(List<EntityBounds> bounds, List<Content> contents, byte level)

```

Insert entities with bounds that may span multiple spatial nodes.

**Parameters:**

- `bounds` - List of entity bounding boxes
- `contents` - List of content objects
- `level` - Spatial refinement level

**Example:**

```java
List<EntityBounds> bounds = new ArrayList<>();
List<MeshData> contents = new ArrayList<>();

for(
Mesh mesh :meshes){
bounds.

add(mesh.getBounds());
contents.

add(mesh.getData());
}

// Insert with automatic spanning
List<ID> meshIds = spatialIndex.insertBatchWithSpanning(bounds, contents, (byte) 8);

```

### Parallel Batch Operations

```java
ParallelOperationResult<ID> insertBatchParallel(List<Point3f> positions, List<Content> contents, byte level)
throws InterruptedException

```

Use multiple threads for very large datasets (100K+ entities).

**Example:**

```java
// For 1 million entities
ParallelOperationResult<ID> result = spatialIndex.insertBatchParallel(millionPositions, millionContents, (byte) 10);

System.out.

println("Inserted "+result.getSuccessCount() +" entities");
System.out.

println("Time: "+result.getElapsedMillis() +" ms");
System.out.

println("Throughput: "+result.getThroughput() +" entities/sec");

```

## Configuration

### BulkOperationConfig

```java
void configureBulkOperations(BulkOperationConfig config)

```

Configure bulk operation behavior for optimal performance.

**Configuration Options:**

```java
BulkOperationConfig config = BulkOperationConfig.highPerformance().withBatchSize(
                                                10000)              // Entities per batch
                                                .withDeferredSubdivision(true)     // Delay node splits
                                                .withPreSortByMorton(true)         // Sort by spatial locality
                                                .withParallel(true)                // Use multiple threads
                                                .withParallelThreshold(100000)     // Min size for parallel
                                                .withStackBasedBuilder(true)       // Iterative construction
                                                .withDynamicLevelSelection(true)   // Auto-select optimal level
                                                .withAdaptiveSubdivision(true);    // Prevent over-subdivision

spatialIndex.

configureBulkOperations(config);

```

### Bulk Loading Mode

```java
void enableBulkLoading()

void finalizeBulkLoading()

```

Enable special mode for maximum insertion performance.

**Example:**

```java
// Enable bulk loading mode
spatialIndex.enableBulkLoading();

// Insert millions of entities
for(
DataChunk chunk :dataChunks){
spatialIndex.

insertBatch(chunk.positions, chunk.contents, level);
}

// Finalize and trigger deferred operations
spatialIndex.

finalizeBulkLoading();

```

## Configuration Presets

### High Performance

```java
// Maximum speed for large datasets
BulkOperationConfig config = BulkOperationConfig.highPerformance();

```

Optimizes for:

- Large batch sizes (10K)
- Parallel processing
- Stack-based construction
- Morton pre-sorting

### Memory Efficient

```java
// Minimize memory usage
BulkOperationConfig config = BulkOperationConfig.memoryEfficient();

```

Optimizes for:

- Smaller batch sizes (1K)
- Node pooling
- Incremental processing

### Balanced

```java
// Good general-purpose settings
BulkOperationConfig config = BulkOperationConfig.balanced();

```

Balanced trade-off between speed and memory.

## Performance Optimization Strategies

### 1. Dynamic Level Selection

```java
// Automatically choose optimal starting level
config.withDynamicLevelSelection(true);

// The system analyzes data distribution and selects level
byte optimalLevel = LevelSelector.selectOptimalLevel(positions, maxEntitiesPerNode);

```

### 2. Pre-allocation

```java
// Pre-allocate memory for known data size
spatialIndex.preAllocateNodes(expectedEntityCount, NodeEstimator.SpatialDistribution.UNIFORM);

```

### 3. Morton Code Sorting

```java
// Enable spatial sorting for better cache locality
config.withPreSortByMorton(true);

// Sorted insertions create more balanced trees

```

### 4. Deferred Subdivision

```java
// Delay node splitting until all data is inserted
config.withDeferredSubdivision(true);

// Subdivisions happen during finalizeBulkLoading()

```

## Use Cases

### 1. Point Cloud Loading

```java
public void loadPointCloud(PointCloudFile file) {
    // Configure for dense point data
    BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                    .withBatchSize(50000)
                                                    .withStackBasedBuilder(true)
                                                    .withDynamicLevelSelection(true);

    spatialIndex.configureBulkOperations(config);
    spatialIndex.enableBulkLoading();

    // Read and insert in chunks
    while (file.hasMorePoints()) {
        PointBatch batch = file.readBatch(50000);
        spatialIndex.insertBatch(batch.positions, batch.colors, batch.suggestedLevel);
    }

    spatialIndex.finalizeBulkLoading();
}

```

### 2. Mesh Scene Loading

```java
public void loadScene(SceneFile scene) {
    List<EntityBounds> allBounds = new ArrayList<>();
    List<MeshContent> allMeshes = new ArrayList<>();

    // Collect all meshes
    for (SceneNode node : scene.getNodes()) {
        if (node.hasMesh()) {
            allBounds.add(node.getMeshBounds());
            allMeshes.add(node.getMeshContent());
        }
    }

    // Configure for large bounded entities
    BulkOperationConfig config = BulkOperationConfig.balanced().withDeferredSubdivision(
                                                    false)  // Immediate for spanning
                                                    .withBatchSize(1000);

    spatialIndex.configureBulkOperations(config);

    // Insert with spanning support
    List<ID> meshIds = spatialIndex.insertBatchWithSpanning(allBounds, allMeshes, (byte) 8);
}

```

### 3. Streaming Data

```java
public void processStreamingData(DataStream stream) {
    // Configure for continuous batching
    BulkOperationConfig config = BulkOperationConfig.balanced().withBatchSize(5000).withDeferredSubdivision(true);

    spatialIndex.configureBulkOperations(config);

    List<Point3f> buffer = new ArrayList<>(5000);
    List<Content> contents = new ArrayList<>(5000);

    stream.onData((position, content) -> {
        buffer.add(position);
        contents.add(content);

        // Flush when buffer is full
        if (buffer.size() >= 5000) {
            spatialIndex.insertBatch(buffer, contents, (byte) 10);
            buffer.clear();
            contents.clear();
        }
    });

    // Final flush
    if (!buffer.isEmpty()) {
        spatialIndex.insertBatch(buffer, contents, (byte) 10);
    }
}

```

### 4. Large Dataset Import

```java
public void importLargeDataset(String[] files) {
    // Configure for high performance bulk loading
    BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                    .withBatchSize(10000)
                                                    .withDeferredSubdivision(true)
                                                    .withStackBasedBuilder(true);
    
    spatialIndex.configureBulkOperations(config);
    spatialIndex.enableBulkLoading();
    
    // Process each file with bulk operations
    for (String file : files) {
        DataFile data = DataFile.load(file);
        List<ID> ids = spatialIndex.insertBatch(data.positions, data.contents, (byte) 10);
        System.out.println("Processed " + file + ": " + ids.size() + " entities");
    }
    
    // Finalize bulk loading
    spatialIndex.finalizeBulkLoading();
}

```

## Performance Benchmarks

Based on testing with uniform random distribution (June 2025):

| Operation     | Single Insert | Basic Batch | Optimized Batch |
| --------------- | --------------- | ------------- | ----------------- |
| 10K entities  | 145ms         | 89ms        | 32ms            |
| 100K entities | 1,450ms       | 670ms       | 157ms           |
| 1M entities   | 15,200ms      | 6,100ms     | 1,250ms         |

## Best Practices

1. **Batch Size**: Use 5K-10K for optimal performance
2. **Pre-sorting**: Enable for clustered data
3. **Parallel Threshold**: Use parallel for >100K entities
4. **Memory Pre-allocation**: Estimate node count for large datasets
5. **Bulk Loading Mode**: Use for one-time large imports
6. **Level Selection**: Let system choose optimal level for unknown data

## Error Handling

```java
try{
List<ID> ids = spatialIndex.insertBatch(positions, contents, level);

// Check for partial failures
    if(ids.

contains(null)){
int failures = Collections.frequency(ids, null);
        log.

warn("Failed to insert {} entities",failures);
    }
    }catch(
IllegalArgumentException e){
// Input validation failed
log.

error("Invalid input: {}",e.getMessage());
}catch(
OutOfMemoryError e){
// Dataset too large
log.

error("Out of memory - reduce batch size");
}

```

## Memory Considerations

Estimate memory usage:

```java
long estimatedMemory = 
    entityCount * 350 +              // ~350 bytes per entity
    estimatedNodes * 200 +           // ~200 bytes per node
    batchSize * 100;                 // Temporary buffers

if (estimatedMemory > Runtime.getRuntime().maxMemory() * 0.8) {
    // Use smaller batches or enable streaming mode
}

```

## Conclusion

The Bulk Operations API provides dramatic performance improvements for loading large datasets. Choose the appropriate
configuration based on your data characteristics and performance requirements. For datasets over 10K entities, bulk
operations should always be preferred over individual insertions.
