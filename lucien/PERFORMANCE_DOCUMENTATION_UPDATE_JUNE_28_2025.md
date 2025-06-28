# Performance Documentation Update - June 28, 2025

## Summary

Updated all performance documentation to reflect actual benchmark results from `OctreeVsTetreeBenchmark.java` run on June 28, 2025.

## Key Changes

### 1. Updated Performance Metrics

**Previous Claims** (various sources):
- Octree: 372x faster for insertions
- Tetree: 3.2x faster for queries
- Memory: Tetree uses 193-407x more memory

**Actual Results** (OctreeVsTetreeBenchmark - June 28, 2025):
- Octree: 9.7x to 770x faster for insertions (scales with dataset size)
- Tetree: 3.1x to 4.0x faster for queries
- Memory: Tetree uses only 20% of Octree memory (contradicts other tests)

### 2. Files Updated

1. **PERFORMANCE_TEST_RESULTS_JUNE_28_2025.md**
   - Added note that all data is from OctreeVsTetreeBenchmark
   - Updated insertion performance table with actual benchmark times
   - Updated query performance with exact microsecond measurements
   - Added note about memory usage contradiction

2. **lucien/doc/PERFORMANCE_REALITY_DECEMBER_2025.md**
   - Renamed references from December to June 2025
   - Updated all performance tables with OctreeVsTetreeBenchmark results
   - Added data source attribution
   - Noted memory measurement methodology differences

3. **CLAUDE.md**
   - Updated performance section with June 28, 2025 benchmark results
   - Changed from generic claims to specific per-dataset measurements
   - Added data source attribution to OctreeVsTetreeBenchmark
   - Updated realistic performance expectations

### 3. Key Findings

1. **Performance Gap Widens with Scale**
   - 100 entities: Octree 9.7x faster
   - 1K entities: Octree 57.6x faster
   - 10K entities: Octree 770x faster

2. **Consistent Query Advantage**
   - Tetree maintains 3-4x advantage for both k-NN and range queries
   - Performance advantage is consistent across dataset sizes

3. **Memory Usage Discrepancy**
   - OctreeVsTetreeBenchmark shows Tetree using LESS memory (20% of Octree)
   - This contradicts other tests showing Tetree using 193-407x MORE memory
   - Likely due to different measurement methodologies or workload patterns

### 4. Documentation Best Practices Applied

- All performance claims now cite their source benchmark
- Actual measurement data replaces theoretical estimates
- Conflicting results are noted rather than hidden
- Date accuracy maintained (June 28, 2025)

## Next Steps

1. Investigate memory usage discrepancy between benchmarks
2. Run additional performance tests if different metrics are needed
3. Consider consolidating performance testing methodology