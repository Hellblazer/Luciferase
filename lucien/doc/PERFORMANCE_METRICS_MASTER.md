# Performance Metrics Master Reference

**Last Updated**: July 12, 2025  
**Purpose**: Single source of truth for all spatial index performance metrics

> **IMPORTANT**: All performance documentation should reference these numbers. Do not duplicate performance metrics in other files.

## Current Performance Metrics (July 12, 2025)

These are the authoritative performance numbers based on the latest benchmarks run on Mac OS X aarch64, Java 24.

### Insertion Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 1.378 ms    | 0.731 ms    | 1.9x faster      | -          | -               | -               |
| 1,000       | 24.220 ms   | 4.226 ms    | 5.7x faster      | 7.17 ms    | 1.42x slower    | 0.59x faster    |
| 10,000      | 755.526 ms  | 121.099 ms  | 6.2x faster      | -          | -               | -               |

**Key Insight**: Tetree insertion is now significantly faster than Octree due to ConcurrentSkipListMap optimizations (July 11, 2025).

### k-Nearest Neighbor (k-NN) Search Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.029 ms    | 0.018 ms    | 1.6x faster      | -          | -               | -               |
| 1,000       | 0.021 ms    | 0.021 ms    | Same             | 2.053 ms   | 2.78x slower    | 2.58x slower    |
| 10,000      | 0.097 ms    | 0.115 ms    | 1.2x slower      | -          | -               | -               |

**Key Insight**: Performance varies by scale. Tetree is faster at small scale, Octree faster at large scale. Prism is consistently slowest.

### Range Query Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.006 ms    | 0.041 ms    | 6.3x slower      | -          | -               | -               |
| 1,000       | 0.014 ms    | 0.029 ms    | 2.1x slower      | 2.422 ms   | 1.38x slower    | 1.29x slower    |
| 10,000      | 0.150 ms    | 0.151 ms    | Same             | -          | -               | -               |

**Key Insight**: Octree performs best for range queries. Tetree and Prism have similar slower performance.

### Memory Usage

| Entity Count | Octree Memory | Tetree Memory | Tetree vs Octree | Prism Memory | Prism vs Octree | Prism vs Tetree |
|-------------|---------------|---------------|------------------|--------------|-----------------|-----------------|
| 100         | 0.05 MB       | 0.04 MB       | 73%              | -            | -               | -               |
| 1,000       | 0.42 MB       | 0.27 MB       | 65%              | -            | -               | -               |
| 2,000       | -             | -             | -                | 0.774 MB     | 122%            | 129%            |
| 10,000      | 4.15 MB       | 2.67 MB       | 64%              | -            | -               | -               |

**Key Insight**: Tetree uses 64-73% of Octree's memory. Prism uses 22-29% more memory than competitors.

### Update Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree |
|-------------|-------------|-------------|------------------|
| 100         | 0.014 ms    | 0.009 ms    | 1.5x faster      |
| 1,000       | 0.018 ms    | 0.008 ms    | 2.3x faster      |
| 10,000      | 0.153 ms    | 0.024 ms    | 6.3x faster      |

### Removal Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree |
|-------------|-------------|-------------|------------------|
| 100         | 0.001 ms    | 0.000 ms    | 1.6x faster      |
| 1,000       | 0.001 ms    | 0.000 ms    | 1.3x faster      |
| 10,000      | 0.009 ms    | 0.002 ms    | 4.9x faster      |

## Historical Context

### Performance Reversal (July 11, 2025)

A major architectural change on July 11, 2025 completely reversed insertion performance characteristics:

**Before July 11**:
- Octree was 2.3x to 11.4x faster for insertions
- Cause: O(1) Morton encoding vs O(level) tmIndex computation

**After July 11**:
- Tetree is now 1.9x to 6.2x faster for insertions
- Cause: ConcurrentSkipListMap refactoring favored Tetree's simpler key comparisons

## Recommendations

### Use Octree When:
- General-purpose 3D spatial indexing needed
- Range queries are performance critical
- Balanced performance across all operations required

### Use Tetree When:
- Memory efficiency is critical (uses 64-73% of Octree memory)
- Insert/update/remove performance is priority
- Working with large datasets (10K+ entities)

### Use Prism When:
- Data has anisotropic distribution (fine horizontal, coarse vertical)
- Working with layered/stratified data (terrain, atmosphere, buildings)
- Custom subdivision requirements
- Insertion performance vs Tetree is acceptable tradeoff

## Benchmark Environment

- **Platform**: Mac OS X aarch64
- **JVM**: Java HotSpot(TM) 64-Bit Server VM 24
- **Processors**: 16
- **Memory**: 512 MB
- **Date**: July 12, 2025

## Notes

1. All times are averages from multiple runs
2. Memory measurements taken after garbage collection
3. Performance can vary based on data distribution and access patterns
4. Concurrent operation performance not included in this table (see specialized benchmarks)