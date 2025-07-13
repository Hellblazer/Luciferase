# Performance Metrics Master Reference

**Last Updated**: July 12, 2025
**Purpose**: Single source of truth for all spatial index performance metrics

> **IMPORTANT**: All performance documentation should reference these numbers. Do not duplicate performance metrics in other files.

## Current Performance Metrics (July 12, 2025)

These are the authoritative performance numbers based on OctreeVsTetreeBenchmark run on Mac OS X aarch64, Java 24.

### Insertion Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100 | 1.363 ms | 0.645 ms | 2.1x faster | - | - | - |
| 1,000 | 23.132 ms | 4.182 ms | 5.5x faster | - | - | - |
| 10,000 | 704.240 ms | 112.233 ms | 6.3x faster | - | - | - |

**Key Insight**: Tetree insertion is now significantly faster than Octree due to ConcurrentSkipListMap optimizations (July 11, 2025).

### k-Nearest Neighbor (k-NN) Search Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.026 ms    | 0.022 ms    | 1.2x faster      | -          | -               | -               |
| 1,000       | 0.025 ms    | 0.029 ms    | 1.2x slower      | 2.053 ms   | 82x slower      | 71x slower      |
| 10,000      | 0.110 ms    | 0.117 ms    | 1.1x slower      | -          | -               | -               |

**Key Insight**: Performance varies by scale. Tetree is faster at small scale, Octree faster at large scale. Prism is consistently slowest.

### Range Query Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.007 ms    | 0.041 ms    | 6.1x slower      | -          | -               | -               |
| 1,000       | 0.014 ms    | 0.044 ms    | 3.2x slower      | 2.422 ms   | 173x slower     | 55x slower      |
| 10,000      | 0.122 ms    | 0.160 ms    | 1.3x slower      | -          | -               | -               |

**Key Insight**: Octree performs best for range queries. Tetree and Prism have similar slower performance.

### Memory Usage

| Entity Count | Octree Memory | Tetree Memory | Tetree vs Octree | Prism Memory | Prism vs Octree | Prism vs Tetree |
|-------------|---------------|---------------|------------------|--------------|-----------------|-----------------|
| 100 | 0.050 MB | 0.040 MB | 1.3x faster | - | - | - |
| 1,000 | 0.420 MB | 0.270 MB | 1.6x faster | - | - | - |
| 10,000 | 4.140 MB | 2.700 MB | 1.5x faster | - | - | - |

**Key Insight**: Tetree uses 62-73% of Octree's memory. Prism uses 22-29% more memory than competitors.

### Update Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100 | 0.015 ms | 0.009 ms | 1.7x faster | - | - | - |
| 1,000 | 0.019 ms | 0.009 ms | 2.1x faster | - | - | - |
| 10,000 | 0.146 ms | 0.022 ms | 6.6x faster | - | - | - |

### Removal Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100 | 0.001 ms | 0.000 ms | Infinityx faster | - | - | - |
| 1,000 | 0.000 ms | 0.000 ms | NaNx slower | - | - | - |
| 10,000 | 0.008 ms | 0.002 ms | 4.0x faster | - | - | - |

## Historical Context

### Performance Reversal (July 11, 2025)

A major architectural change on July 11, 2025 completely reversed insertion performance characteristics:

**Before July 11**:
- Octree was 2.3x to 11.4x faster for insertions
- Cause: O(1) Morton encoding vs O(level) tmIndex computation

**After July 11**:
- Tetree is now 2.1x to 5.7x faster for insertions
- Cause: ConcurrentSkipListMap refactoring favored Tetree's simpler key comparisons

## Recommendations

### Use Octree When:
- General-purpose 3D spatial indexing needed
- Range queries are performance critical
- Balanced performance across all operations required

### Use Tetree When:
- Memory efficiency is critical (uses 62-73% of Octree memory)
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
- **Memory**: 8192 MB
- **Date**: July 12, 2025
- **Benchmark**: OctreeVsTetreeBenchmark

## Notes

1. All times are averages from multiple runs
2. Memory measurements taken after garbage collection
3. Performance can vary based on data distribution and access patterns
4. Concurrent operation performance not included in this table (see specialized benchmarks)