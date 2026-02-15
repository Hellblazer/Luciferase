# Sentry Benchmark Suite

**Last Updated**: 2025-12-08
**Status**: Current

This directory contains JMH benchmarks for measuring Sentry module performance.

## Benchmarks

### FlipOperationBenchmark

- Measures baseline performance of OrientedFace.flip() operations
- Tests with various ear counts (10, 50, 100, 200)
- Includes LinkedList iteration patterns

### DataStructureBenchmark

- Compares LinkedList vs ArrayList performance
- Tests random access, iteration, and removal patterns
- Demonstrates expected improvement from Phase 1.1 optimization

### GeometricPredicateBenchmark

- Measures geometric predicate calculation performance
- Tests leftOfPlane and inSphere operations
- Includes both fast and exact versions

### ObjectPoolBenchmark

- Measures TetrahedronPool performance and memory efficiency
- Tests insertion performance with object pooling
- Monitors pool reuse rate and memory allocation pressure

### Manual Benchmarks

- **ManualBenchmarkRunner**: Basic performance tests without JMH
- **OptimizedBenchmarkRunner**: Tests ArrayList optimizations for flip operations
- **CachedAdjacentVertexBenchmark**: Tests caching of adjacent vertex lookups
- **LandmarkIndexBenchmark**: Tests spatial indexing performance

## Running Benchmarks

### Using the script (recommended)

```bash

cd /path/to/Luciferase
./sentry/run-baseline-benchmark.sh

```

### Manual execution:

```bash

mvn -f sentry/pom.xml clean test-compile
java -cp [classpath] org.openjdk.jmh.Main FlipOperationBenchmark

```

### Using BenchmarkRunner:

```bash

mvn -f sentry/pom.xml clean test-compile
java -cp [classpath] com.hellblazer.sentry.benchmark.BenchmarkRunner

```

## Results

Benchmark results are saved in:

- JSON format: `sentry/target/benchmarks/baseline-[timestamp].json`
- Text format: `sentry/target/benchmarks/baseline-[timestamp].txt`

### Current Performance Metrics (July 2025)

#### Manual Benchmarks

- **LinkedList vs ArrayList Random Access**: ArrayList is 4.32x faster
- **Flip Operation Performance**: 0.05 µs per operation
- **getAdjacentVertex Performance**: 9.45 ns per call

#### Object Pool Performance

- **Average insertion time**: 25.51 µs
- **Pool reuse rate**: 2.16% (initial operations)
- **Memory usage**: 13.80 MB for 10 grids with 100 vertices each

#### Optimized Flip Operations (ArrayList)

- **Average insertion time**: 28.03 µs
- **Ears list access improvements**:
  - Size 10: 1.21x faster
  - Size 50: 1.29x faster
  - Size 100: 4.42x faster
  - Size 200: 11.66x faster

### Running Manual Benchmarks

For quick performance testing without JMH:

```bash

# Object Pool Benchmark

java -cp "sentry/target/test-classes:sentry/target/classes:$(mvn -f sentry/pom.xml dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" com.hellblazer.sentry.benchmark.ObjectPoolBenchmark

# Manual Benchmark Runner

java -cp "sentry/target/test-classes:sentry/target/classes:$(mvn -f sentry/pom.xml dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" com.hellblazer.sentry.benchmark.ManualBenchmarkRunner

# Optimized Benchmark Runner

java -cp "sentry/target/test-classes:sentry/target/classes:$(mvn -f sentry/pom.xml dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" com.hellblazer.sentry.benchmark.OptimizedBenchmarkRunner

```

## Next Steps

After running baseline benchmarks:

1. Save results for comparison
2. Continue optimizations (e.g., improve pool reuse rate)
3. Re-run benchmarks to measure improvement
4. Document results in performance tracking documents
