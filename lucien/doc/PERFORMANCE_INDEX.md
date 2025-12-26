# Performance Documentation Index

## Master Performance Reference

**⚠️ IMPORTANT**: For all current performance metrics and benchmarks, see:

- **[PERFORMANCE_METRICS_MASTER.md](PERFORMANCE_METRICS_MASTER.md)** - Single source of truth for all performance numbers

## Overview

This index guides you to performance-related documentation for the Luciferase spatial indexing library.

**Last Updated**: 2025-12-08  
**Status**: Current

## Main Performance Documents

### [PERFORMANCE_TRACKING.md](./PERFORMANCE_TRACKING.md)

**Current performance baseline and optimization history**

- Octree vs Tetree vs Prism comparison
- Optimization timeline
- Performance guidelines
- Benchmark instructions

### [SPATIAL_INDEX_PERFORMANCE_COMPARISON.md](SPATIAL_INDEX_PERFORMANCE_COMPARISON.md)

**Three-way spatial index comparison (Octree vs Tetree vs Prism)**

- Insertion, query, and update benchmarks
- Memory usage comparison
- Collision and ray performance
- Anisotropic vs isotropic performance
- Use case recommendations

## Specialized Performance Reports

All specialized performance metrics have been consolidated into [PERFORMANCE_METRICS_MASTER.md](PERFORMANCE_METRICS_MASTER.md) for consistency.

- Bulk operation strategies

### [SPATIAL_INDEX_PERFORMANCE_GUIDE.md](./SPATIAL_INDEX_PERFORMANCE_GUIDE.md)

**Performance tuning guide**

- Configuration options
- Optimization strategies
- Best practices

### [DSOC_PERFORMANCE_TESTING_GUIDE.md](./DSOC_PERFORMANCE_TESTING_GUIDE.md)

**DSOC performance testing**

- Performance test categories
- JMH benchmarks
- Gated test execution

## Quick Reference

### For Different Workloads

**Insertion-Heavy**: Use Tetree (1.8x to 5.7x faster than Octree)
**Range Query-Heavy**: Use Octree (3.2x to 8.3x faster than Tetree)
**k-NN Search**: Use Tetree for small/medium datasets, Octree for >10K entities
**Memory-Constrained**: Memory usage nearly converged (Tetree uses 80-99% of Octree's memory)
**Update-Heavy**: Use Tetree (1.7x to 3.0x faster than Octree)
**Batch Loading**: Use Tetree with bulk operations
**Anisotropic Data**: Use Prism for directional data patterns
**High Occlusion Scenes**: Enable DSOC for up to 2.0x speedup

### Running Benchmarks

```bash
# Three-way comparison (Octree vs Tetree vs Prism)

mvn test -Dtest=OctreeVsTetreeBenchmark

# Prism-specific benchmarks

mvn test -Dtest=*PrismPerformanceTest

# Anisotropic workload testing

mvn test -Dtest=*AnisotropicTest

# Quick validation

mvn test -Dtest=QuickPerformanceTest

# Full suite

mvn test -Dtest=*PerformanceTest

# DSOC performance tests (gated)

export RUN_DSOC_PERF_TESTS=true
mvn test -Dtest=DSOCPerformanceTest

```
