# DAG Compression API Reference

**Last Updated**: 2026-01-20
**Package**: `com.hellblazer.luciferase.esvo.dag`
**Status**: Stable API (F2.2 Complete)

## Core API

### DAGBuilder

**Package**: `com.hellblazer.luciferase.esvo.dag`

Fluent builder for creating DAG-compressed octrees from SVO sources.

#### Factory Method

```java
public static DAGBuilder from(ESVOOctreeData source)
```

Create a new builder from source octree.

**Parameters**:
- `source`: Source SVO octree (must not be null)

**Returns**: New DAGBuilder instance

**Throws**: `DAGBuildException.InvalidInputException` if source is null

**Example**:
```java
var builder = DAGBuilder.from(myOctree);
```

#### Configuration Methods

```java
public DAGBuilder withHashAlgorithm(HashAlgorithm algorithm)
```

Set hash algorithm for deduplication (default: SHA256).

**Parameters**:
- `algorithm`: Hash algorithm enum value

**Returns**: This builder for chaining

---

```java
public DAGBuilder withCompressionStrategy(CompressionStrategy strategy)
```

Set compression strategy (default: BALANCED).

**Parameters**:
- `strategy`: Compression strategy enum value

**Returns**: This builder for chaining

---

```java
public DAGBuilder withProgressCallback(Consumer<BuildProgress> callback)
```

Set optional progress callback for UI updates.

**Parameters**:
- `callback`: Progress callback (null to disable)

**Returns**: This builder for chaining

---

```java
public DAGBuilder withValidation(boolean validate)
```

Enable or disable structural validation (default: enabled).

**Parameters**:
- `validate`: true to enable validation

**Returns**: This builder for chaining

#### Build Method

```java
public DAGOctreeData build()
```

Execute compression and build DAG.

**Returns**: Compressed DAG octree data

**Throws**:
- `DAGBuildException.InvalidInputException` if source is empty
- `DAGBuildException.ValidationFailedException` if validation fails

**Example**:
```java
var dag = DAGBuilder.from(svo)
    .withHashAlgorithm(HashAlgorithm.XXHASH64)
    .withValidation(true)
    .build();
```

### DAGOctreeData

**Package**: `com.hellblazer.luciferase.esvo.dag`

Result interface for compressed DAG octrees.

#### Core Methods

```java
ESVONodeUnified[] nodes()
```

Get the compacted node array (absolute addressing).

**Returns**: Array of DAG nodes (canonical nodes only)

---

```java
DAGMetadata getMetadata()
```

Get compression metadata and statistics.

**Returns**: Metadata record with compression info

---

```java
float getCompressionRatio()
```

Get compression ratio (sourceNodes / compressedNodes).

**Returns**: Compression ratio (e.g., 5.0 for 5x compression)

---

```java
int resolveChildIndex(int parentIdx, ESVONodeUnified node, int octant)
```

Resolve child node index using DAG's child pointer indirection.

**Parameters**:
- `parentIdx`: Parent node index (currently unused in DAG, may be -1)
- `node`: Parent node
- `octant`: Child octant (0-7)

**Returns**: Child node index in compacted array

**Throws**: `IndexOutOfBoundsException` if octant invalid or child pointer out of bounds

### DAGMetadata

**Package**: `com.hellblazer.luciferase.esvo.dag`

Compression statistics and configuration metadata.

#### Fields

```java
int uniqueNodeCount()       // Nodes in compressed DAG
int sourceNodeCount()       // Nodes in source SVO
int maxDepth()              // Maximum tree depth
int sharedSubtreeCount()    // Number of shared subtrees
Duration buildTime()        // Time to build DAG
HashAlgorithm hashAlgorithm() // Hash algorithm used
CompressionStrategy strategy() // Compression strategy used
long sourceHash()           // Source data hash (for validation)
```

#### Methods

```java
float compressionRatio()
```

Calculate compression ratio: `sourceNodeCount / uniqueNodeCount`.

**Returns**: Compression ratio (higher = better)

---

```java
long memorySavedBytes()
```

Estimate memory saved: `(sourceNodeCount - uniqueNodeCount) * 8`.

**Returns**: Bytes saved (assumes 8 bytes per node reference)

## Metrics API

### CompressionMetrics

**Package**: `com.hellblazer.luciferase.esvo.dag.metrics`

Immutable compression performance metrics record.

#### Constructor

```java
public CompressionMetrics(
    int compressedNodeCount,
    int sourceNodeCount,
    int uniqueInternalNodes,
    int uniqueLeafNodes,
    Duration buildTime
)
```

#### Methods

```java
float compressionRatio()     // sourceNodeCount / compressedNodeCount
float compressionPercent()   // (1 - compressed/source) * 100
long memorySavedBytes()      // (source - compressed) * 8
float memorySavedPercent()   // (source - compressed) / source * 100
String strategy()            // "HASH_BASED"
long timestamp_value()       // Metrics creation timestamp
```

### CompressionMetricsCollector

**Package**: `com.hellblazer.luciferase.esvo.dag.metrics`

Thread-safe collector for compression metrics.

#### Methods

```java
void recordCompression(SparseVoxelData source, DAGOctreeData result)
```

Record a compression operation (thread-safe).

**Parameters**:
- `source`: Source octree
- `result`: Compressed DAG

**Throws**: `NullPointerException` if source or result is null

---

```java
CompressionSummary getSummary()
```

Get aggregated statistics for all recorded compressions.

**Returns**: Summary with total/average/min/max timing

---

```java
List<CompressionMetrics> getAllMetrics()
```

Get immutable snapshot of all recorded metrics.

**Returns**: List of metrics (defensive copy)

---

```java
void reset()
```

Clear all recorded metrics (thread-safe).

### CacheMetrics

**Package**: `com.hellblazer.luciferase.esvo.dag.metrics`

Immutable cache performance metrics.

#### Constructor

```java
public CacheMetrics(
    long hitCount,
    long missCount,
    long evictionCount
)
```

#### Methods

```java
float hitRate()  // hitCount / (hitCount + missCount)
```

### CacheMetricsCollector

**Package**: `com.hellblazer.luciferase.esvo.dag.metrics`

Thread-safe cache metrics collector using atomic operations.

#### Methods

```java
void recordHit()       // Increment hit count (thread-safe)
void recordMiss()      // Increment miss count (thread-safe)
void recordEviction()  // Increment eviction count (thread-safe)
CacheMetrics getMetrics()  // Get current snapshot (thread-safe)
void reset()           // Reset all counters (thread-safe)
```

### FileMetricsExporter

**Package**: `com.hellblazer.luciferase.esvo.dag.metrics`

File-based metrics exporter (CSV/JSON formats).

#### Constructor

```java
public FileMetricsExporter(Path outputPath, Format format)
```

**Parameters**:
- `outputPath`: Output file path
- `format`: `Format.CSV` or `Format.JSON`

#### Methods (MetricsExporter Interface)

```java
void exportCompression(CompressionMetrics metrics)
void exportCache(CacheMetrics metrics)
void close()  // Finalizes JSON array, closes resources
```

**Usage Pattern**:
```java
try (var exporter = new FileMetricsExporter(path, Format.CSV)) {
    exporter.exportCompression(metrics);
}  // Auto-closes
```

## Enumerations

### HashAlgorithm

```java
public enum HashAlgorithm {
    SHA256,    // Cryptographically secure, moderate speed
    XXHASH64   // Very fast, non-cryptographic
}
```

### CompressionStrategy

```java
public enum CompressionStrategy {
    CONSERVATIVE,  // Favor build speed
    BALANCED,      // Default balance
    AGGRESSIVE     // Favor memory savings
}
```

Note: Currently all strategies use the same algorithm. This is a future optimization point.

### BuildPhase

```java
public enum BuildPhase {
    HASHING,        // Computing subtree hashes (0-33%)
    DEDUPLICATION,  // Identifying duplicates (33-66%)
    COMPACTION,     // Building compacted DAG (66-90%)
    VALIDATION,     // Optional validation (90-100%)
    COMPLETE        // Build complete (100%)
}
```

## Exception Hierarchy

### DAGBuildException

**Package**: `com.hellblazer.luciferase.esvo.dag`

Base exception for DAG construction failures.

#### Subclasses

**InvalidInputException**
- Thrown when: Source is null or empty
- Usage: Validate input before building

**ValidationFailedException**
- Thrown when: DAG validation detects structural errors
- Usage: Enable validation during development to catch bugs early

**HashCollisionException** (future)
- Thrown when: Hash collision detected during deduplication
- Usage: Extremely rare, indicates need for different hash algorithm

### MetricsExportException

**Package**: `com.hellblazer.luciferase.esvo.dag.metrics`

Thrown when metric export fails (I/O errors, format errors).

**Note**: FileMetricsExporter logs warnings instead of throwing this exception by default.

## Type Hierarchy

```
com.hellblazer.luciferase.esvo.dag
├── DAGBuilder (fluent builder)
├── DAGOctreeData (interface)
│   └── DAGOctreeDataImpl (internal implementation)
├── DAGMetadata (record)
├── HashAlgorithm (enum)
├── CompressionStrategy (enum)
├── BuildPhase (enum)
├── BuildProgress (record)
└── DAGBuildException
    ├── InvalidInputException
    └── ValidationFailedException

com.hellblazer.luciferase.esvo.dag.metrics
├── CompressionMetrics (record)
├── CompressionMetricsCollector
├── CacheMetrics (record)
├── CacheMetricsCollector
├── MetricsExporter (interface)
│   └── FileMetricsExporter
└── MetricsExportException
```

## Thread Safety Summary

| Class | Thread Safety | Mechanism |
|-------|---------------|-----------|
| DAGBuilder | Not thread-safe | Single-threaded build |
| DAGOctreeData | Read-only immutable | Immutable after construction |
| DAGMetadata | Immutable record | Immutable by design |
| CompressionMetrics | Immutable record | Immutable by design |
| CompressionMetricsCollector | Thread-safe | ReentrantLock + synchronized list |
| CacheMetrics | Immutable record | Immutable by design |
| CacheMetricsCollector | Thread-safe | AtomicLong operations |
| FileMetricsExporter | Thread-safe | ReentrantLock for file writes |

## Performance Characteristics

| Operation | Time Complexity | Space Complexity |
|-----------|----------------|------------------|
| DAGBuilder.build() | O(N) | O(N) peak, O(U) result |
| resolveChildIndex() | O(1) | O(1) |
| compressionRatio() | O(1) | O(1) |
| CompressionMetricsCollector.recordCompression() | O(1) | O(M) for M recordings |
| CompressionMetricsCollector.getSummary() | O(M) | O(1) |
| CacheMetricsCollector.recordHit/Miss/Eviction() | O(1) | O(1) |

Where:
- N = source node count
- U = unique node count (compressed)
- M = number of recorded metrics

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-01-20 | Initial API release (F2.2 complete) |

For implementation details, see [DAG_INTEGRATION_GUIDE.md](DAG_INTEGRATION_GUIDE.md).
