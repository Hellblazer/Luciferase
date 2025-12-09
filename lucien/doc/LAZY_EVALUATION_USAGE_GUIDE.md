# Lazy Evaluation Usage Guide

## Overview

This guide demonstrates how to use the lazy evaluation features in Tetree to efficiently handle large spatial ranges without memory exhaustion.

## Key Components

### 1. LazyRangeIterator

The `LazyRangeIterator` provides O(1) memory iteration over TetreeKey ranges.

```java

// Create a large range
var startTet = new Tet(0, 0, 0, level, (byte)0);
var endTet = new Tet(1000000, 1000000, 1000000, level, (byte)5);

// Create lazy iterator - uses constant memory regardless of range size
var iterator = new LazyRangeIterator(startTet, endTet);

// Iterate without materializing all keys
while (iterator.hasNext()) {
    TetreeKey<?> key = iterator.next();
    // Process key
    if (shouldStop(key)) {
        break; // Early termination supported
    }
}

```text

### 2. SFCRange with Lazy Methods

The enhanced `SFCRange` provides streaming capabilities:

```java

// Create a spatial range
var range = new Tet.SFCRange(startKey, endKey);

// Stream processing - lazy by default
range.stream()
    .filter(key -> meetsCondition(key))
    .limit(100)  // Only generates 100 keys
    .forEach(this::processKey);

// Get iterator for manual control
Iterator<TetreeKey<?>> iter = range.iterator();

// Check if single element
if (range.isSingle()) {
    // Handle single key case efficiently
}

// Estimate size without enumeration
long estimatedSize = range.estimateSize();

```text

### 3. RangeHandle for Deferred Computation

`RangeHandle` represents spatial queries without immediate execution:

```java

// Create range handle - no computation yet
var handle = new RangeHandle(rootTet, bounds, includeIntersecting, level);

// Computation happens only when needed
long count = handle.stream().count();

// Multiple operations on same handle
var filteredKeys = handle.stream()
    .filter(key -> isValid(key))
    .toList();

// Combine multiple handles efficiently
List<RangeHandle> handles = createMultipleRanges();
var allKeys = handles.stream()
    .flatMap(RangeHandle::stream)
    .distinct()
    .toList();

```text

### 4. RangeQueryVisitor for Tree Traversal

Alternative to range-based iteration using tree structure:

```java

// Create visitor for spatial bounds query
var visitor = new RangeQueryVisitor<ID, Content>(bounds, includeIntersecting);

// Traverse tree with early termination
tetree.nodes().forEach(node -> {
    if (!visitor.visitNode(node, level, parentKey)) {
        // Subtree pruned
    }
});

// Get results and statistics
var matchingNodes = visitor.getResults();
System.out.println(visitor.getStatistics());

```text

## Usage Patterns

### Pattern 1: Large Range Queries

When querying large spatial ranges:

```java

// BAD: Materializes all keys
List<TetreeKey<?>> allKeys = computeAllKeysInRange(bounds);
for (var key : allKeys) {
    processKey(key);
}

// GOOD: Lazy evaluation
Tet.spatialRangeQueryKeys(bounds, level)
    .stream()
    .forEach(this::processKey);

```text

### Pattern 2: Early Termination

Finding first N matching elements:

```java

// Find first 100 entities in range
var firstHundred = tetree.boundedBy(bounds)
    .flatMap(node -> node.entityIds().stream())
    .limit(100)
    .toList();

```text

### Pattern 3: Memory-Conscious Processing

Processing very large ranges in batches:

```java

var range = new Tet.SFCRange(startKey, endKey);
var iterator = range.iterator();

// Process in batches to control memory
List<TetreeKey<?>> batch = new ArrayList<>(1000);
while (iterator.hasNext()) {
    batch.add(iterator.next());
    
    if (batch.size() >= 1000) {
        processBatch(batch);
        batch.clear();
    }
}
if (!batch.isEmpty()) {
    processBatch(batch);
}

```text

### Pattern 4: Combining Multiple Ranges

Efficiently combine results from multiple spatial queries:

```java

// Define multiple query regions
List<VolumeBounds> regions = getRegionsOfInterest();

// Create handles for deferred execution
var handles = regions.stream()
    .map(bounds -> new RangeHandle(rootTet, bounds, true, level))
    .toList();

// Process all regions lazily
handles.stream()
    .flatMap(RangeHandle::stream)
    .distinct()  // Remove duplicates across regions
    .forEach(this::processKey);

```text

## Performance Considerations

### Memory Usage

- **LazyRangeIterator**: O(1) memory regardless of range size
- **Eager Collection**: O(n) memory where n is number of keys
- **Memory Savings**: Up to 99.5% for large ranges (millions of keys)

### Performance Trade-offs

1. **Small Ranges** (<1000 keys): Slight overhead from lazy machinery
2. **Medium Ranges** (1000-100K keys): Comparable performance
3. **Large Ranges** (>100K keys): Significant memory and performance benefits

### Best Practices

1. **Use Lazy Evaluation For**:
   - Large spatial queries
   - Memory-constrained environments
   - Early termination scenarios
   - Streaming/pipeline processing

2. **Use Eager Collection For**:
   - Small, known-size ranges
   - When random access is needed
   - When processing the same range multiple times

3. **Avoid**:
   - Collecting large ranges to lists unnecessarily
   - Multiple passes over lazy iterators
   - Nested lazy operations without intermediate collection

## Examples

### Example 1: Spatial Search with Distance Filter

```java

Point3f queryPoint = new Point3f(100, 100, 100);
float maxDistance = 50.0f;

var results = Tet.spatialRangeQueryKeys(
        new VolumeBounds(
            queryPoint.x - maxDistance,
            queryPoint.y - maxDistance,
            queryPoint.z - maxDistance,
            queryPoint.x + maxDistance,
            queryPoint.y + maxDistance,
            queryPoint.z + maxDistance
        ), level)
    .stream()
    .map(range -> range.stream())
    .flatMap(s -> s)
    .filter(key -> {
        var tet = Tet.tetrahedron(key);
        var center = tet.centroid();
        return center.distance(queryPoint) <= maxDistance;
    })
    .toList();

```text

### Example 2: Progressive Loading

```java

public class ProgressiveLoader {
    private final Iterator<TetreeKey<?>> iterator;
    private final int batchSize = 100;
    
    public ProgressiveLoader(Tet.SFCRange range) {
        this.iterator = range.iterator();
    }
    
    public List<TetreeKey<?>> loadNextBatch() {
        List<TetreeKey<?>> batch = new ArrayList<>(batchSize);
        
        for (int i = 0; i < batchSize && iterator.hasNext(); i++) {
            batch.add(iterator.next());
        }
        
        return batch;
    }
    
    public boolean hasMore() {
        return iterator.hasNext();
    }
}

```text

### Example 3: Memory-Aware Range Processing

```java

public void processLargeRange(VolumeBounds bounds, byte level) {
    var ranges = Tet.spatialRangeQueryKeys(bounds, level);
    
    System.out.println("Processing " + ranges.size() + " ranges");
    
    for (var range : ranges) {
        // Estimate memory requirement
        long estimatedKeys = range.estimateSize();
        
        if (estimatedKeys > 10000) {
            // Use lazy processing for large ranges
            System.out.println("Large range detected, using lazy processing");
            range.stream()
                .forEach(this::processKeyLazily);
        } else {
            // Small range - collect for batch processing
            var keys = range.stream().toList();
            processBatch(keys);
        }
    }
}

```text

## Conclusion

Lazy evaluation in Tetree provides powerful tools for handling large spatial datasets efficiently. By understanding when and how to use these features, you can build applications that scale to massive spatial indices without memory constraints.
