# Sentry Benchmark Suite

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

## Running Benchmarks

### Using the script (recommended):
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

## Next Steps

After running baseline benchmarks:
1. Save results for comparison
2. Implement Phase 1.1 optimizations (ArrayList conversion)
3. Re-run benchmarks to measure improvement
4. Document results in OPTIMIZATION_TRACKER.md