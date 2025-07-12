# Performance Documentation Index

## Overview

This index guides you to performance-related documentation for the Luciferase spatial indexing library.

**Last Updated**: July 11, 2025

## Main Performance Documents

### [PERFORMANCE_TRACKING.md](./PERFORMANCE_TRACKING.md)

**Current performance baseline and optimization history**

- Octree vs Tetree comparison
- Optimization timeline
- Performance guidelines
- Benchmark instructions

### [OCTREE_VS_TETREE_PERFORMANCE.md](OCTREE_VS_TETREE_PERFORMANCE.md)

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

### [BATCH_PERFORMANCE.md](BATCH_PERFORMANCE.md)

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

**Insertion-Heavy**: Use Tetree (2.1x to 6.2x faster after concurrent optimizations)
**Query-Heavy**: Use Tetree for k-NN at low entity counts, Octree at scale (>10K)
**Memory-Constrained**: Use Tetree (65-73% of Octree's memory usage)
**Batch Loading**: Use Tetree with bulk operations (35-38% faster)
**Update-Heavy**: Mixed results - profile your specific use case

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

