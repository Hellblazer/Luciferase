# DSOC Testing Guide

This guide provides an overview of testing strategies for the Dynamic Scene Occlusion Culling (DSOC) system.

## Testing Documentation

### 1. Performance Testing

- **[DSOC_PERFORMANCE_TESTING_GUIDE.md](DSOC_PERFORMANCE_TESTING_GUIDE.md)** - Java performance tests and benchmarks
  - JUnit performance tests (disabled by default)
  - JMH benchmarks for detailed performance analysis
  - Instructions for running performance tests

### 2. Comprehensive Testing

- **[DSOC_TESTING_COMPREHENSIVE.md](DSOC_TESTING_COMPREHENSIVE.md)** - Complete testing strategies
  - Unit testing examples (C++)
  - Integration testing patterns
  - Edge case validation
  - Validation strategies

## Quick Test Commands

### Run DSOC Performance Tests

```bash
# Enable performance tests temporarily

export RUN_DSOC_PERF_TESTS=true

# Run basic performance tests

mvn test -Dtest=DSOCPerformanceTest

# Run JMH benchmarks (takes longer)

mvn test -Dtest=DSOCBenchmarkRunner

```

### Run Integration Tests

```bash
# Run DSOC integration tests

mvn test -Dtest=*DSOCIntegrationTest

# Run with specific configuration

mvn test -Dtest=*DSOCIntegrationTest -DdsocEnabled=true

```

## Test Categories

### 1. Unit Tests

- TBV (Temporal Bounding Volume) creation and validation
- Z-buffer operations
- Occlusion query logic
- Configuration validation

### 2. Integration Tests

- DSOC with spatial indices (Octree, Tetree)
- Auto-disable mechanism
- Performance monitoring
- Memory management

### 3. Performance Tests

- Throughput benchmarks
- Memory usage analysis
- Scalability testing
- Overhead measurement

### 4. Edge Cases

- Empty scenes
- Extreme entity counts
- Rapid movement scenarios
- Configuration edge cases

## Key Test Scenarios

### Scenario 1: Basic Occlusion

```java
// Test that DSOC correctly culls occluded entities
spatialIndex.enableDSOC(config, 512, 512);
// Add occluders and test entities
// Verify culling results

```

### Scenario 2: Performance Protection

```java
// Test auto-disable kicks in when performance degrades
// Create scenario with poor occlusion
// Verify DSOC auto-disables

```

### Scenario 3: Dynamic Scenes

```java
// Test with moving entities
// Verify TBV updates correctly
// Check performance metrics

```

## Best Practices

1. **Always test with DSOC disabled first** - Establish baseline performance
2. **Test multiple entity counts** - 1K, 10K, 50K, 100K entities
3. **Vary occlusion ratios** - 10%, 50%, 90% occlusion
4. **Include dynamic entities** - 0%, 20%, 50% moving
5. **Monitor memory usage** - Check for leaks and excessive allocation
6. **Verify auto-disable** - Ensure protection mechanisms work

## Common Issues to Test

1. **Memory leaks** in Z-buffer allocation
2. **Performance degradation** without occlusion
3. **Incorrect culling** results
4. **TBV expiration** handling
5. **Thread safety** in concurrent scenarios

## Test Data Requirements

- **Occluder geometry**: Large static objects (walls, buildings)
- **Test entities**: Various sizes and positions
- **Camera paths**: Predictable movement patterns
- **Performance baselines**: Non-DSOC reference times
