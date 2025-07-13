# Performance Documentation Index

## Master Performance Reference

**⚠️ IMPORTANT**: For all current performance metrics and benchmarks, see:
- **[PERFORMANCE_METRICS_MASTER.md](PERFORMANCE_METRICS_MASTER.md)** - Single source of truth for all performance numbers

## Overview

This index guides you to performance-related documentation for the Luciferase spatial indexing library.

**Last Updated**: July 12, 2025

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

**Insertion-Heavy**: Use Tetree (2.1x to 6.2x faster), avoid Prism (1.54x slower than Octree)
**Query-Heavy**: Use Tetree for k-NN at low entity counts, Octree at scale (>10K), Prism for anisotropic data
**Memory-Constrained**: Use Tetree (65-73% of Octree's memory usage), Prism uses 22-29% more than Octree
**Batch Loading**: Use Tetree with bulk operations (35-38% faster)
**Update-Heavy**: Mixed results - profile your specific use case
**Anisotropic Data**: Use Prism for directional data patterns, avoids Octree's cubic subdivision overhead

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
```

### [PRISM_PROGRESS_TRACKER.md](PRISM_PROGRESS_TRACKER.md)

**Prism spatial index implementation progress**

- Development phases and milestones
- Performance optimization tracking
- Anisotropic subdivision benefits
- Implementation status updates

## Archived Documents

