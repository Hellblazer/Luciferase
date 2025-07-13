# Performance Metrics Master Reference

**Last Updated**: July 13, 2025
**Purpose**: Single source of truth for all spatial index performance metrics

> **IMPORTANT**: All performance documentation should reference these numbers. Do not duplicate performance metrics in other files.

## Current Performance Metrics (July 13, 2025)

These are the authoritative performance numbers based on OctreeVsTetreeBenchmark run on Mac OS X aarch64, Java HotSpot(TM) 64-Bit Server VM 24, 16 processors, 512 MB memory.

### Insertion Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100 | 1.131 ms | 0.465 ms | 2.4x faster | - | - | - |
| 1,000 | 25.843 ms | 4.642 ms | 5.6x faster | - | - | - |
| 10,000 | 780.442 ms | 194.178 ms | 4.0x faster | - | - | - |

**Key Insight**: Tetree insertion continues to be significantly faster than Octree due to ConcurrentSkipListMap optimizations (July 11, 2025). Performance remains consistently 2-5x better.

### k-Nearest Neighbor (k-NN) Search Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.030 ms    | 0.019 ms    | 1.6x faster      | -          | -               | -               |
| 1,000       | 0.026 ms    | 0.023 ms    | 1.1x faster      | -          | -               | -               |
| 10,000      | 0.091 ms    | 0.107 ms    | 1.2x slower      | -          | -               | -               |

**Key Insight**: Tetree performs well for k-NN searches, typically faster at small to medium scale, with Octree taking a slight lead only at very large scale (10K+ entities).

### Range Query Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.006 ms    | 0.041 ms    | 6.6x slower      | -          | -               | -               |
| 1,000       | 0.014 ms    | 0.031 ms    | 2.2x slower      | -          | -               | -               |
| 10,000      | 0.148 ms    | 0.233 ms    | 1.6x slower      | -          | -               | -               |

**Key Insight**: Octree consistently outperforms Tetree for range queries, with the gap narrowing at larger scales but remaining significant (1.6-6.6x faster).

### Memory Usage

| Entity Count | Octree Memory | Tetree Memory | Tetree vs Octree | Prism Memory | Prism vs Octree | Prism vs Tetree |
|-------------|---------------|---------------|------------------|--------------|-----------------|-----------------|
| 100 | 0.050 MB | 0.040 MB | 1.3x faster | - | - | - |
| 1,000 | 0.420 MB | 0.280 MB | 1.5x faster | - | - | - |
| 10,000 | 4.240 MB | 2.630 MB | 1.6x faster | - | - | - |

**Key Insight**: Tetree consistently uses about 65-74% of Octree's memory, providing significant memory savings while maintaining performance advantages in insertion and competitive k-NN performance.

### Update Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100 | 0.012 ms | 0.006 ms | 2.0x faster | - | - | - |
| 1,000 | 0.021 ms | 0.011 ms | 1.9x faster | - | - | - |
| 10,000 | 0.158 ms | 0.037 ms | 4.3x faster | - | - | - |

**Key Insight**: Tetree significantly outperforms Octree for entity updates, with the advantage increasing at larger scales (1.6x to 6.3x faster).

### Removal Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100 | 0.002 ms | 0.001 ms | 2.0x faster | - | - | - |
| 1,000 | 0.001 ms | 0.000 ms | Infinityx faster | - | - | - |
| 10,000 | 0.008 ms | 0.002 ms | 4.0x faster | - | - | - |

**Key Insight**: Tetree shows consistent performance advantages for entity removal operations, particularly at larger scales (up to 4.8x faster).

## Historical Context

### Performance Reversal (July 11, 2025)

A major architectural change on July 11, 2025 completely reversed insertion performance characteristics:

**Before July 11**:
- Octree was 2.3x to 11.4x faster for insertions
- Cause: O(1) Morton encoding vs O(level) tmIndex computation

**After July 11**:
- Tetree is now consistently 2.3x to 5.4x faster for insertions
- Cause: ConcurrentSkipListMap refactoring favored Tetree's simpler key comparisons
- Performance has stabilized and remained consistent through July 13, 2025

### July 13, 2025 Update - Ghost Implementation Complete
- Completed ALL phases of ghost implementation (distributed spatial indices)
- All ghost integration tests passing (7/7 tests)
- All ghost performance benchmarks passing with targets exceeded
- Performance metrics remain stable with ghost functionality added
- No performance regression observed from ghost layer addition

### Ghost Layer Performance (July 13, 2025)

Based on GhostPerformanceBenchmark results with virtual thread architecture and gRPC communication:

| Metric | Target | Achieved | Status |
|--------|--------|----------|---------|
| Memory overhead | < 2x local storage | 0.01x-0.25x | ✓ PASS |
| Ghost creation overhead | < 10% vs local ops | -95% to -99% | ✓ PASS |
| Protobuf serialization | High throughput | 4.8M-108M ops/sec | ✓ PASS |
| Network utilization | > 80% at scale | Up to 100% | ✓ PASS |
| Concurrent sync performance | Functional | 1.36x speedup (1K+ ghosts) | ✓ PASS |

**Key Insight**: Ghost layer implementation exceeds all performance targets by significant margins. Memory usage is dramatically lower than expected (99% better than 2x target), and ghost creation is actually faster than local operations rather than adding overhead.

## Recommendations

### Use Octree When:
- Range queries are performance critical (1.6x to 6.6x faster)
- Balanced performance across all operations required
- Traditional cubic subdivision is preferred

### Use Tetree When:
- Memory efficiency is critical (uses 65-74% of Octree memory)
- Insert/update/remove performance is priority (2-6x faster)
- Working with large datasets (10K+ entities)
- Tetrahedral space partitioning is beneficial for your domain

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