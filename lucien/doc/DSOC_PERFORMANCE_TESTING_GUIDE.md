# DSOC Performance Testing Guide

## Overview

The DSOC performance tests are gated to prevent them from running during normal builds, CI, or automated testing. These tests are resource-intensive and designed for manual execution when evaluating DSOC performance characteristics.

## Test Categories

### 1. Basic Performance Tests

**Class**: `DSOCPerformanceTest`
**Location**: `src/test/java/com/hellblazer/luciferase/lucien/occlusion/DSOCPerformanceTest.java`
**Tags**: `@Tag("performance")`, `@Disabled`

#### Tests Included:
- `testDSOCPerformanceComparison()` - Compares DSOC vs non-DSOC performance across entity counts and occlusion ratios
- `testDynamicScenePerformance()` - Tests dynamic scene with moving entities

#### Test Scenarios:
- Entity counts: 1,000, 10,000, 50,000
- Occlusion ratios: 10%, 50%, 90%
- Dynamic entity ratio: 20%
- Both Octree and Tetree implementations

### 2. JMH Benchmarks

**Class**: `DSOCPerformanceBenchmark`
**Location**: `src/test/java/com/hellblazer/luciferase/lucien/benchmark/DSOCPerformanceBenchmark.java`
**Tags**: `@Tag("benchmark")`, `@Disabled`

#### Benchmark Methods:
- `benchmarkOctreeWithDSOC()` - JMH benchmark for Octree with DSOC
- `benchmarkOctreeWithoutDSOC()` - JMH benchmark for Octree without DSOC
- `benchmarkTetreeWithDSOC()` - JMH benchmark for Tetree with DSOC
- `benchmarkTetreeWithoutDSOC()` - JMH benchmark for Tetree without DSOC

#### Parameterized Testing:
- Entity counts: 1,000, 10,000, 100,000
- Occlusion ratios: 10%, 30%, 50%, 70%, 90%
- Dynamic entity ratios: 0%, 20%, 50%
- Z-buffer resolutions: 512, 1024, 2048

### 3. JMH Runner

**Class**: `DSOCBenchmarkRunner`
**Location**: `src/test/java/com/hellblazer/luciferase/lucien/benchmark/DSOCBenchmarkRunner.java`
**Tags**: `@Tag("benchmark")`, `@Disabled`

Provides a JUnit wrapper for running JMH benchmarks.

## Running Performance Tests

### Method 1: Run Specific Test Class

```bash
# Run basic performance tests
mvn test -Dtest=DSOCPerformanceTest

# Run JMH benchmark runner
mvn test -Dtest=DSOCBenchmarkRunner
```

### Method 2: Enable in IDE

1. Remove or comment out the `@Disabled` annotation
2. Run tests normally in your IDE
3. **Remember to re-enable `@Disabled` before committing**

### Method 3: Run by Tag (if tags are configured)

```bash
# Run all performance tests
mvn test -Dgroups=performance

# Run all benchmarks
mvn test -Dgroups=benchmark
```

### Method 4: Profile-based Execution

If you want to add Maven profiles for performance testing:

```xml
<profile>
    <id>performance</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <groups>performance,benchmark</groups>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

Then run with:
```bash
mvn test -Pperformance
```

## Expected Results

### Basic Performance Test Output
```
=== DSOC Performance Comparison ===

Entities: 1000, Occlusion Ratio: 0.1
----------------------------------------
Octree Results:
  Without DSOC: 0.15 ms/frame
  With DSOC:    3.27 ms/frame
  Speedup:      0.05x
  Occlusion Rate: 31.2%
  Active TBVs: 0
  TBV Hit Rate: NaN%
```

### JMH Benchmark Output
```
Benchmark                                          Mode  Cnt     Score    Error  Units
DSOCPerformanceBenchmark.benchmarkOctreeWithDSOC  avgt   10  3274.123 ± 45.678  us/op
```

## Performance Characteristics

### Current Results Summary
- **Small scenes (1K entities)**: 3ms DSOC overhead
- **Medium scenes (10K entities)**: Overhead still significant
- **Large scenes (50K entities)**: DSOC becomes competitive (58-61% of original time)
- **Occlusion rate**: 30-40% in test scenarios
- **TBV activation**: Currently 0 (static test scenes)

### When DSOC Provides Benefits
1. **Large entity counts** (10,000+ entities)
2. **High occlusion scenarios** (dense urban, indoor scenes)
3. **Scenes with significant depth complexity**
4. **Applications where 3ms overhead is acceptable for rendering quality**

### When DSOC May Not Help
1. **Small entity counts** (<5,000 entities)
2. **Open environments** with minimal occlusion
3. **Highly dynamic scenes** with rapid camera movement
4. **Performance-critical applications** where 3ms overhead is unacceptable

## Test Configuration

### Memory Requirements
- Tests allocate large entity datasets (up to 50K entities)
- Z-buffers consume 4-16MB depending on resolution
- Recommend 4GB+ heap space for full test suite

### Execution Time
- Basic performance tests: ~90 seconds
- JMH benchmarks: 5-30 minutes depending on parameters
- Full parameterized runs: 1+ hours

### System Requirements
- Multi-core system recommended for JMH benchmarks
- Sufficient RAM for large entity datasets
- Minimal background processes for accurate timing

## Interpreting Results

### Key Metrics
- **Frame time**: Average milliseconds per frustum cull operation
- **Speedup**: Ratio of non-DSOC to DSOC time (>1.0 means DSOC is faster)
- **Occlusion rate**: Percentage of entities culled by occlusion
- **Active TBVs**: Number of temporal bounding volumes in use
- **TBV hit rate**: Accuracy of TBV predictions

### Performance Analysis
1. **Baseline establishment**: Run without DSOC first
2. **Overhead measurement**: Compare DSOC vs non-DSOC times
3. **Scaling analysis**: Test across multiple entity counts
4. **Break-even calculation**: Find where DSOC becomes beneficial

## Troubleshooting

### Common Issues

1. **OutOfMemoryError**
   - Increase heap size: `-Xmx4g`
   - Reduce entity counts in test parameters

2. **Coordinate errors**
   - Tests require positive coordinates
   - Entities are bounded to 50-950 range

3. **Long execution times**
   - Reduce iteration counts for faster feedback
   - Use smaller entity count ranges

4. **Inconsistent results**
   - Ensure minimal background processes
   - Use fixed random seeds for reproducibility
   - Consider multiple test runs for averaging

## Integration with CI

These tests are **intentionally excluded** from CI and normal builds to prevent:
- Extended build times
- Resource consumption on build servers
- Flaky test failures due to timing sensitivity

To run in CI environments:
1. Create separate performance testing jobs
2. Use dedicated high-performance agents
3. Run on schedule rather than per-commit
4. Store results for trend analysis

## Documentation Updates

When modifying performance tests:
1. Update this guide with new test scenarios
2. Document expected performance characteristics
3. Update baseline measurements after significant changes
4. Maintain compatibility with existing test infrastructure