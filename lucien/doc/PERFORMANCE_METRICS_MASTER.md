# Performance Metrics Master Reference

**Last Updated**: August 3, 2025
**Purpose**: Single source of truth for all spatial index performance metrics

> **IMPORTANT**: All performance documentation should reference these numbers. Do not duplicate performance metrics in other files.

> **NOTE**: 10,000 entity tests for Prism marked as *pending* - these tests take significantly longer to complete.

## Current Performance Metrics (August 3, 2025)

These are the authoritative performance numbers based on OctreeVsTetreeVsPrismBenchmark run on Mac OS X aarch64, Java HotSpot(TM) 64-Bit Server VM 24, 16 processors, 512 MB memory.

### Insertion Performance

| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 1.429 ms    | 0.777 ms    | 1.8x faster      | 0.024 ms   | 60x faster      | 32x faster      |
| 1,000       | 23.899 ms   | 4.168 ms    | 5.7x faster      | 0.336 ms   | 71x faster      | 12x faster      |
| 10,000      | 764.464 ms  | 142.980 ms  | 5.3x faster      | *pending*  | *pending*       | *pending*       |
**Key Insight**: Prism demonstrates exceptional insertion performance, outperforming both Octree (60-71x faster) and Tetree (12-32x faster) at small to medium scales. This is due to its simplified vertical subdivision approach.
### k-Nearest Neighbor (k-NN) Search Performance
| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.030 ms    | 0.021 ms    | 1.4x faster      | 0.010 ms   | 3.0x faster     | 2.1x faster     |
| 1,000       | 0.027 ms    | 0.024 ms    | 1.1x faster      | 0.055 ms   | 2.0x slower     | 2.3x slower     |
| 10,000      | 0.103 ms    | 0.121 ms    | 1.2x slower      | *pending*  | *pending*       | *pending*       |
**Key Insight**: Tetree performs well for k-NN searches, typically faster at small to medium scale, with Octree taking a slight lead only at very large scale (10K+ entities).
### Range Query Performance
| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.004 ms    | 0.033 ms    | 8.3x slower      | 0.007 ms   | 1.8x slower     | 4.7x faster     |
| 1,000       | 0.009 ms    | 0.033 ms    | 3.7x slower      | 0.054 ms   | 6.0x slower     | 1.6x slower     |
| 10,000      | 0.038 ms    | 0.122 ms    | 3.2x slower      | *pending*  | *pending*       | *pending*       |
**Key Insight**: Octree consistently outperforms Tetree for range queries, with the performance gap being significant across all scales (3.2x-8.3x faster).
### Memory Usage
| Entity Count | Octree Memory | Tetree Memory | Tetree vs Octree | Prism Memory | Prism vs Octree | Prism vs Tetree |
|-------------|---------------|---------------|------------------|--------------|-----------------|-----------------|
| 100         | 0.050 MB      | 0.040 MB      | 20% less         | 0.052 MB   | 4% more         | 30% more        |
| 1,000       | 0.420 MB      | 0.380 MB      | 10% less         | 0.487 MB   | 16% more        | 28% more        |
| 10,000      | 3.800 MB      | 3.750 MB      | 1% less          | *pending*   | *pending*       | *pending*       |
**Key Insight**: Tetree uses slightly less memory than Octree, with savings decreasing at larger scales (20% less at 100 entities, only 1% less at 10K entities).
### Update Performance
| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.003 ms    | 0.001 ms    | 3.0x faster      | 0.117 ms  | 39x slower      | 117x slower     |
| 1,000       | 0.005 ms    | 0.003 ms    | 1.7x faster      | 0.030 ms  | 6.0x slower     | 10x slower      |
| 10,000      | 0.094 ms    | 0.035 ms    | 2.7x faster      | *pending* | *pending*       | *pending*       |
**Key Insight**: Tetree consistently outperforms Octree for entity updates, maintaining a 1.7x to 3.0x performance advantage across all scales.
### Removal Performance
| Entity Count | Octree Time | Tetree Time | Tetree vs Octree | Prism Time | Prism vs Octree | Prism vs Tetree |
|-------------|-------------|-------------|------------------|------------|-----------------|-----------------|
| 100         | 0.001 ms    | 0.000 ms    | 2.0x faster      | 0.003 ms  | 3.0x slower     | 6.0x slower     |
| 1,000       | 0.001 ms    | 0.001 ms    | 1.0x faster      | 0.001 ms  | 1.0x faster     | 1.0x faster     |
| 10,000      | 0.005 ms    | 0.003 ms    | 1.7x faster      | *pending* | *pending*       | *pending*       |
**Key Insight**: Tetree shows modest performance advantages for entity removal operations, with performance benefits ranging from parity to 2x faster.
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
- Range queries are performance critical (3.2x to 8.3x faster)
- Balanced performance across all operations required
- Traditional cubic subdivision is preferred
### Use Tetree When:
- Insert performance is critical (1.8x to 5.7x faster)
- Update performance matters (1.7x to 3.0x faster)
- Working with large datasets (10K+ entities)
- Tetrahedral space partitioning is beneficial for your domain

### Use Prism When:
- Insert performance is paramount (60-71x faster than Octree)
- Data has anisotropic distribution (fine horizontal, coarse vertical)
- Memory usage slightly higher is acceptable (4-28% more than alternatives)
- Working with small to medium datasets (< 10K entities)
- Working with layered/stratified data (terrain, atmosphere, buildings)
- Custom subdivision requirements
- Insertion performance vs Tetree is acceptable tradeoff

## Benchmark Environment

- **Platform**: Mac OS X aarch64
- **JVM**: Java HotSpot(TM) 64-Bit Server VM 24
- **Processors**: 16
- **Memory**: 512 MB
- **Date**: August 3, 2025
- **Benchmark**: OctreeVsTetreeVsPrismBenchmark

## Notes

1. All times are averages from multiple runs
2. Memory measurements taken after garbage collection
3. Performance can vary based on data distribution and access patterns
4. Concurrent operation performance not included in this table (see specialized benchmarks)
