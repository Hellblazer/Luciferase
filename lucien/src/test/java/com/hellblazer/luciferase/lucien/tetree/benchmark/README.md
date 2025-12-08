# Tetree Performance Optimization Benchmarks

**Last Updated**: 2025-12-08
**Status**: Current

This directory contains JMH (Java Microbenchmark Harness) benchmarks that measure the performance improvements achieved
through the optimization work on the Tetree spatial index implementation.

## Overview

The benchmarks demonstrate the conversion of several critical operations from O(log n) or O(level) complexity to O(1)
constant time through caching and precomputation strategies.

## Benchmark Files

### 1. TetreeLevelCacheBenchmark.java

Measures the performance improvement in level extraction from SFC indices.

- **Original**: O(log n) using `Long.numberOfLeadingZeros()`
- **Optimized**: O(1) using lookup tables and De Bruijn multiplication
- **Expected improvement**: 5-10x faster for large indices

### 2. TetreeParentChainBenchmark.java

Measures the performance improvement in parent chain traversal and type computation.

- **Original**: O(level) iterative parent computation
- **Optimized**: O(1) for cached entries, with intelligent cache management
- **Expected improvement**: 10-20x faster for deep trees (level 15+)

### 3. SpatialIndexSetBenchmark.java

Measures the performance improvement in spatial index set operations.

- **Original**: TreeSet with O(log n) operations
- **Optimized**: Hash-based SpatialIndexSet with O(1) operations
- **Expected improvement**: 3-5x faster for add/remove/contains operations

## Prerequisites

To run these benchmarks, you need to add JMH dependencies to the `lucien/pom.xml`:

```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>test</scope>
</dependency>
```

## Running the Benchmarks

### Run all benchmarks:

```bash
mvn clean test -Dtest=TetreeLevelCacheBenchmark,TetreeParentChainBenchmark,SpatialIndexSetBenchmark
```

### Run individual benchmarks:

```bash
# Level extraction benchmark
mvn test -Dtest=TetreeLevelCacheBenchmark

# Parent chain benchmark
mvn test -Dtest=TetreeParentChainBenchmark

# Spatial index set benchmark
mvn test -Dtest=SpatialIndexSetBenchmark
```

### Run with custom JMH options:

```bash
java -cp target/test-classes:target/classes:target/dependency/* \
     org.openjdk.jmh.Main ".*TetreeLevelCache.*" \
     -f 1 -wi 3 -i 5 -t 1
```

## Interpreting Results

### TetreeLevelCacheBenchmark Results

- **Throughput benchmarks**: Higher ops/ms is better
- **Average time benchmarks**: Lower ns/op is better
- Compare `original*` vs `cached*` methods
- Pay attention to scaling with data size (small/medium/large)

### TetreeParentChainBenchmark Results

- Deep trees show the most dramatic improvement
- Cache warmup affects initial measurements
- Parent chain operations scale with tree depth

### SpatialIndexSetBenchmark Results

- Basic operations (add/remove/contains) show constant time
- Level-based queries demonstrate O(1) vs O(n) difference
- Mixed operations show real-world performance gains

## Key Performance Metrics

### Expected Improvements:

1. **Level extraction**: ~5-10x faster
2. **Parent chain traversal**: ~10-20x faster for deep trees
3. **Set operations**: ~3-5x faster
4. **Level queries**: ~100x faster (O(n) → O(1))

### Memory Trade-offs:

- TetreeLevelCache: ~16KB for lookup tables
- Parent chain cache: ~8KB for 1024 entries
- SpatialIndexSet: ~2x memory vs TreeSet (hash table overhead)

## Optimization Techniques Demonstrated

1. **Lookup Tables**: Pre-computed results for small indices
2. **De Bruijn Multiplication**: O(1) highest bit detection
3. **Level-based Bucketing**: Efficient range queries
4. **LRU Caching**: Parent chain and type transition caching
5. **Hybrid Data Structures**: Hash tables with lazy sorting

## Benchmark Best Practices

1. Always run with warmup iterations
2. Use fixed seeds for reproducible results
3. Test multiple data sizes to verify scaling
4. Monitor GC impact with `-verbose:gc`
5. Run on isolated system for consistent results

## Future Optimizations

Potential areas for further improvement:

- SIMD operations for batch processing
- Memory-mapped caches for persistence
- Lock-free concurrent data structures
- CPU cache-line optimization
