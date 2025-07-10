# Performance Documentation Index

## Overview

This index guides you to performance-related documentation for the Luciferase spatial indexing library.

**Last Updated**: July 10, 2025

## Main Performance Documents

### [PERFORMANCE_TRACKING.md](./PERFORMANCE_TRACKING.md)

**Current performance baseline and optimization history**

- Octree vs Tetree comparison
- Optimization timeline
- Performance guidelines
- Benchmark instructions

### [OCTREE_VS_TETREE_PERFORMANCE_.md](OCTREE_VS_TETREE_PERFORMANCE.md)

**Primary spatial index comparison**

- Insertion, query, and update benchmarks
- Memory usage comparison
- Collision and ray performance
- Use case recommendations

## Specialized Performance Reports

### [COLLISION_SYSTEM_PERFORMANCE_REPORT.md](COLLISION_SYSTEM_PERFORMANCE_REPORT.md)

**Collision detection performance**

- Discrete collision: 27-93 ns per check
- Continuous collision detection
- Spatial index integration

### [BATCH_PERFORMANCE_.md](BATCH_PERFORMANCE.md)

**Batch loading analysis**

- Tetree shows 74-296x speedup in batch mode
- Bulk operation strategies

### [SPATIAL_INDEX_PERFORMANCE_GUIDE.md](./SPATIAL_INDEX_PERFORMANCE_GUIDE.md)

**Performance tuning guide**

- Configuration options
- Optimization strategies
- Best practices

## Quick Reference

### For Different Workloads

**Insertion-Heavy**: Use Octree (up to 15x faster)
**Query-Heavy**: Use Tetree (2-4x faster queries)
**Memory-Constrained**: Use Tetree (75-80% less memory)
**Batch Loading**: Use Tetree with bulk operations

### Running Benchmarks

```bash
# Primary comparison
mvn test -Dtest=OctreeVsTetreeBenchmark

# Quick validation
mvn test -Dtest=QuickPerformanceTest

# Full suite
mvn test -Dtest=*PerformanceTest
```

## Archived Documents

