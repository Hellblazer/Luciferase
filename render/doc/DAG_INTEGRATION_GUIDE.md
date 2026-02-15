# DAG Compression Integration Guide

**Last Updated**: 2026-01-20
**Status**: Production Ready (F2.2 Complete)
**Module**: render

## Overview

This guide explains how to integrate Sparse Voxel DAG (Directed Acyclic Graph) compression into your Luciferase application. DAG compression achieves **4.56x to 15x** memory reduction and **13x traversal speedup** through hash-based subtree deduplication.

### What is DAG Compression?

DAG compression converts a traditional Sparse Voxel Octree (SVO) with relative child pointers into a DAG where duplicate subtrees are identified via hashing and merged. Instead of storing N copies of identical subtrees, the DAG stores one canonical copy and rewrites all references to point to it.

### Key Benefits

- **Memory Savings**: 4.56x - 15x compression ratio (77.8% - 93.3% reduction)
- **Performance**: 13x faster traversal due to improved cache locality
- **Lossless**: Perfect reconstruction of original octree structure
- **Automatic**: No manual tuning required for basic usage

## Quick Start

### Basic Usage (5 Minutes)

```java
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;

// 1. Load or create your source octree
ESVOOctreeData svo = loadYourOctree();

// 2. Build DAG with default settings
var dag = DAGBuilder.from(svo).build();

// 3. Check compression results
System.out.printf("Compression: %.2fx%n", dag.getCompressionRatio());
System.out.printf("Memory saved: %d bytes%n",
    dag.getMetadata().memorySavedBytes());
```text

That's it! The default configuration uses SHA-256 hashing, balanced compression strategy, and automatic validation.

### Configuration Options

For advanced control, use the fluent builder API:

```java
var dag = DAGBuilder.from(svo)
    .withHashAlgorithm(HashAlgorithm.XXHASH64)  // Faster hashing
    .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)  // Max compression
    .withProgressCallback(progress -> updateUI(progress))  // UI updates
    .withValidation(true)  // Structural validation (default: enabled)
    .build();
```text

## Configuration Reference

### Hash Algorithms

**SHA256** (default)
- Collision probability: Cryptographically secure (2^-256)
- Speed: Moderate (~200 MB/s)
- Use when: Correctness is critical, performance is acceptable

**XXHASH64** (fast)
- Collision probability: Very low (2^-64)
- Speed: Very fast (~10 GB/s)
- Use when: Performance is critical, working with <10M unique nodes

```java
// Fast hashing for performance
.withHashAlgorithm(HashAlgorithm.XXHASH64)
```text

### Compression Strategies

**BALANCED** (default)
- Memory vs speed tradeoff: Moderate
- Use when: General-purpose compression

**AGGRESSIVE**
- Memory vs speed tradeoff: Favor memory savings
- Use when: Memory is severely constrained
- Note: Currently same as BALANCED (future optimization point)

**CONSERVATIVE**
- Memory vs speed tradeoff: Favor build speed
- Use when: Fast compression is priority
- Note: Currently same as BALANCED (future optimization point)

```java
// Aggressive compression
.withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
```text

### Progress Callbacks

Monitor compression progress for UI updates or logging:

```java
.withProgressCallback(progress -> {
    System.out.printf("[%s] %.1f%%%n",
        progress.phase(),
        progress.percentComplete());

    // Phase values:
    // HASHING (0-33%)
    // DEDUPLICATION (33-66%)
    // COMPACTION (66-90%)
    // VALIDATION (90-100%)
    // COMPLETE (100%)
})
```text

### Validation

Structural validation verifies:
- Non-empty node pool
- All nodes valid
- Root node exists
- No null nodes

```java
// Enable validation (default)
.withValidation(true)

// Disable for maximum performance
.withValidation(false)
```text

## Metrics Collection

### Compression Metrics

Track compression performance across multiple operations:

```java
import com.hellblazer.luciferase.esvo.dag.metrics.*;

var collector = new CompressionMetricsCollector();

// Record compressions
for (var svo : octrees) {
    var dag = DAGBuilder.from(svo).build();
    collector.recordCompression(svo, dag);
}

// Analyze performance
var summary = collector.getSummary();
System.out.printf("Total: %d compressions%n", summary.totalCompressions());
System.out.printf("Average time: %.2fms%n", summary.averageTimeMs());
System.out.printf("Time range: [%dms, %dms]%n",
    summary.minTimeMs(), summary.maxTimeMs());
```text

### Cache Metrics

If using a caching compression implementation:

```java
var cacheCollector = new CacheMetricsCollector();

// Record cache operations
cacheCollector.recordHit();   // Found in cache
cacheCollector.recordMiss();  // Not in cache
cacheCollector.recordEviction(); // Cache entry removed

// Check effectiveness
var cacheMetrics = cacheCollector.getMetrics();
System.out.printf("Hit rate: %.1f%%%n", cacheMetrics.hitRate() * 100);
```text

### Export to Files

Export metrics to CSV or JSON:

```java
// CSV format
try (var exporter = new FileMetricsExporter(
    Path.of("metrics.csv"),
    FileMetricsExporter.Format.CSV)) {

    // Export compression metrics
    exporter.exportCompression(compressionMetrics);

    // Export cache metrics
    exporter.exportCache(cacheMetrics);
}

// JSON format
try (var exporter = new FileMetricsExporter(
    Path.of("metrics.json"),
    FileMetricsExporter.Format.JSON)) {

    exporter.exportCompression(compressionMetrics);
}
```text

**CSV Format**:
```csv
timestamp,sourceNodeCount,compressedNodeCount,uniqueInternalNodes,uniqueLeafNodes,compressionRatio,compressionPercent,memorySavedBytes,buildTimeMs
1705776000000,10240,2048,1536,512,5.00,80.00,65536,250
```text

**JSON Format**:
```json
[{
  "timestamp": 1705776000000,
  "sourceNodeCount": 10240,
  "compressedNodeCount": 2048,
  "uniqueInternalNodes": 1536,
  "uniqueLeafNodes": 512,
  "compressionRatio": 5.00,
  "compressionPercent": 80.00,
  "memorySavedBytes": 65536,
  "buildTimeMs": 250
}]
```text

## Serialization

### Save DAG to Disk

```java
import com.hellblazer.luciferase.esvo.dag.DAGSerializer;

var dag = DAGBuilder.from(svo).build();

// Serialize to file
DAGSerializer.serialize(dag, Path.of("scene.dag"));
```text

### Load DAG from Disk

```java
// Deserialize from file
var dag = DAGSerializer.deserialize(Path.of("scene.dag"));

// Use immediately
System.out.printf("Loaded DAG: %d nodes, %.2fx compression%n",
    dag.nodeCount(),
    dag.getCompressionRatio());
```text

### Configuration Preservation

Serialization preserves:
- All node data (descriptors, attributes)
- Child pointer indirection
- Compression metadata (ratio, build time, hash algorithm)
- Source hash (for round-trip validation)

## Performance Tuning

### Memory vs Compression Trade-offs

**High Compression** (minimize memory)
```java
DAGBuilder.from(svo)
    .withHashAlgorithm(HashAlgorithm.SHA256)  // Better collision resistance
    .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
    .build();
```text

**Fast Compression** (minimize build time)
```java
DAGBuilder.from(svo)
    .withHashAlgorithm(HashAlgorithm.XXHASH64)  // 50x faster hashing
    .withValidation(false)  // Skip validation
    .build();
```text

### Batch Processing

For processing multiple octrees:

```java
// Sequential processing
var dags = octrees.stream()
    .map(svo -> DAGBuilder.from(svo).build())
    .toList();

// Parallel processing (8 cores)
var dags = octrees.parallelStream()
    .map(svo -> DAGBuilder.from(svo)
        .withValidation(false)  // Reduce overhead
        .build())
    .toList();
```text

### Memory Budget

DAGBuilder peak memory usage during build:
```text
peakMemory ≈ 2.5 * sourceNodeCount * 16 bytes

Example:
  1M nodes → ~40 MB peak
  10M nodes → ~400 MB peak
  100M nodes → ~4 GB peak
```text

To avoid OOM with large octrees:
1. Increase JVM heap: `-Xmx8g`
2. Process in chunks (spatial subdivision)
3. Use disk-based intermediate storage

## Troubleshooting

### Build Failures

**InvalidInputException: Source octree is null**
- Ensure source octree is loaded before calling `DAGBuilder.from()`

**InvalidInputException: Source octree is empty**
- Verify octree has nodes: `svo.nodeCount() > 0`

**ValidationFailedException: DAG contains null nodes**
- Source octree may be corrupted
- Disable validation and inspect result manually

### Poor Compression

**Compression ratio < 2.0x**
- Scene has low spatial redundancy (few duplicate subtrees)
- This is expected for random/noise data
- Consider geometric compression techniques instead

**Build time > 1 second for 10K nodes**
- Switch to XXHASH64 for faster hashing
- Disable validation: `.withValidation(false)`
- Check for disk I/O bottlenecks (if source is memory-mapped)

### Memory Issues

**OutOfMemoryError during build**
- Increase JVM heap: `-Xmx` flag
- Process octree in spatial chunks
- Use streaming deduplication (future feature)

**High memory after build**
- Check for multiple references to source SVO (prevent GC)
- Verify DAG is smaller: `dag.nodeCount() < svo.nodeCount()`

## Integration Examples

### JavaFX Visualization

```java
// Build DAG for rendering
var dag = DAGBuilder.from(svo)
    .withProgressCallback(progress ->
        Platform.runLater(() -> progressBar.setProgress(progress.percentComplete() / 100.0)))
    .build();

// Render DAG (same API as SVO)
renderOctree(dag);
```text

### LWJGL Rendering

```java
// Upload DAG to GPU
var nodeBuffer = dag.nodesToByteBuffer();
glBufferData(GL_SHADER_STORAGE_BUFFER, nodeBuffer, GL_STATIC_DRAW);

// DAG uses absolute addressing - simpler shaders
// No far pointer handling needed
```text

### Network Transmission

```java
// Compress for transmission
var dag = DAGBuilder.from(svo).build();

// Serialize to bytes
var bytes = DAGSerializer.toBytes(dag);

// Send over network (4-15x smaller than SVO)
socket.write(bytes);
```text

## Best Practices

1. **Always measure**: Use `CompressionMetricsCollector` to track actual compression ratios
2. **Validate once**: Enable validation during development, disable in production
3. **Cache DAGs**: Serialize compressed DAGs to disk, reuse across runs
4. **Profile first**: Start with defaults, optimize based on metrics
5. **Test with real data**: Compression ratios vary dramatically by scene complexity

## Summary

DAG compression provides significant memory and performance improvements with minimal integration effort. The default configuration works well for most use cases, while advanced options enable fine-tuning for specific requirements.

**Next Steps**:
- Read [DAG_API_REFERENCE.md](DAG_API_REFERENCE.md) for detailed API documentation
- See [ESVO_DAG_COMPRESSION.md](ESVO_DAG_COMPRESSION.md) for architecture and implementation details
- Check [PERFORMANCE_METRICS_MASTER.md](../lucien/doc/PERFORMANCE_METRICS_MASTER.md) for benchmark data

For questions or issues, file a GitHub issue with the `dag-compression` label.
