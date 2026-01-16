# Changelog

All notable changes to the Luciferase project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [Phase 9] - 2026-01-15

### Added - Dynamic Topology Adaptation

**Phase 9A: Detection & Metrics (14 tests)**
- `DensityMonitor` with hysteresis state machine (10% tolerance prevents oscillation)
- `ClusteringDetector` using K-means for entity distribution analysis
- `BoundaryStressAnalyzer` with 60-second sliding window for migration pressure
- `TopologyMetricsCollector` aggregating metrics with <10ms overhead

**Phase 9B: Consensus Coordination (13 tests)**
- `TopologyProposal` sealed interface (SplitProposal, MergeProposal, MoveProposal)
- `TopologyConsensusCoordinator` wrapping ViewCommitteeConsensus with 30s cooldown
- `TopologyValidator` with Byzantine-resistant pre-validation
- View ID verification preventing cross-view double-commit races

**Phase 9C: Execution & Reorganization (78 tests)**
- `BubbleSplitter` with atomic entity redistribution (<1s for 5000 entities)
- `BubbleMerger` with duplicate detection (<500ms)
- `BubbleMover` for boundary relocation (<500ms)
- `TopologyExecutor` with snapshot/rollback orchestration
- `TopologyMetrics` with Prometheus-compatible operational monitoring

**Phase 9D: Integration & Validation**
- `DynamicTopologyDemo` showing 4→8+ bubble evolution
- `TopologyEvolutionTest` with natural split/merge/move scenarios
- `ByzantineTopologyTest` validating Byzantine proposal rejection
- `TopologyConsistencyValidator` for cluster-wide agreement validation

### Technical Details

- **Split Threshold**: >5000 entities/bubble (120% frame utilization)
- **Merge Threshold**: <500 entities/bubble (60% affinity)
- **Consensus Latency**: <200ms for BFT voting
- **Safety**: 100% entity retention across all topology operations
- **Test Coverage**: 105+ tests passing
- **Memory**: <2 MB for 100 bubbles (monitoring + edge counters)

### Architecture Patterns

- Sealed interface for type-safe proposal handling
- Adapter pattern for consensus integration (reuses ViewCommitteeConsensus)
- Snapshot/rollback for atomic execution with automatic recovery
- Hysteresis state machine for stable threshold detection

### Dependencies

- Phase 8: ViewCommitteeConsensus, EntityAccountant, TetreeBubbleGrid
- No new external dependencies

## [Phase 8] - 2026-01-10

### Added - Consensus-Coordinated Migration

- `ViewCommitteeConsensus` for Byzantine-fault-tolerant consensus
- `EntityAccountant` ensuring 100% entity retention
- `CrossProcessMigrationValidator` for distributed migration validation
- Committee-based voting protocol with view ID safety

### Technical Details

- **Consensus Model**: BFT quorum with Byzantine minority tolerance
- **Entity Conservation**: Atomic operations with pre/post validation
- **View Safety**: View ID verification prevents race conditions

## [Prior Work]

### Phase 7 and Earlier

Extensive spatial indexing, rendering, and simulation infrastructure:

- **Lucien Module**: 190+ files across 18 packages
  - Octree, Tetree, Prism spatial indexing
  - SFCArrayIndex with Morton curve optimization
  - Collision detection with CCD
  - Forest coordination with ghost layers
  - DSOC (Dynamic Spatial Occlusion Culling)

- **Render Module**: ESVO implementation
  - LWJGL OpenGL rendering
  - FFM integration for native interop
  - Shader-based visualization

- **Simulation Module**: Distributed simulation framework
  - Multi-process coordination
  - Deterministic time handling (Clock interface)
  - Entity lifecycle management

- **Von Module**: Spatial perception framework
  - Distributed perception protocols
  - Network communication layer

- **Portal Module**: JavaFX 3D visualization
- **Sentry Module**: Delaunay tetrahedralization
- **Common Module**: Optimized collections and geometry utilities

### Performance Optimizations

- Tetree 1.9x-6.2x faster than Octree for insertions
- Concurrent architecture with ConcurrentSkipListMap
- Lock-free entity updates
- ObjectPool for GC pressure reduction

### Testing Infrastructure

- 1400+ tests across all modules
- Parallel CI with 6 test batches (9-12 min total runtime)
- Dynamic port allocation preventing conflicts
- Deterministic time handling for reproducible tests

## [Stabilization Sprint] - 2026-01-11 to Present

### Phase: Test Stabilization (H3 Determinism Epic)

- ✅ Converted 136 wall-clock instances to Clock interface
- ✅ Eliminated 96 System.currentTimeMillis()/nanoTime() calls
- ✅ Fixed TOCTTOU race in SingleBubbleAutonomyTest
- ✅ Resolved GitHub Actions cache conflicts
- ✅ Achieved 2/5 consecutive clean CI runs

### Rules Established

1. **NO NEW FEATURES** - Phase 9+ beads blocked until health > 7/10
2. **NO NEW ABSTRACTIONS** - Use existing Clock interface
3. **DELETE BEFORE ADD** - Every new file requires deleting 2 old files
4. **FIX TESTS, DON'T DISABLE** - Pre-push hook rejects @Disabled
5. **MEASURE FIRST** - No optimization without benchmark

### Next Phase: Complexity Reduction (Sprint B)

- MultiBubbleSimulation refactoring (558 → 150 LOC facade)
- File deletion (target: 100 files in Month 2)
- Decomposition pattern documentation

---

## Version History Summary

- **Phase 9**: Dynamic topology with split/merge/move + Byzantine consensus
- **Phase 8**: Consensus-coordinated migration with 100% entity retention
- **Phase 7-**: Spatial indexing, rendering, distributed simulation foundation
- **Stabilization Sprint**: Test stability + complexity reduction (ongoing)

## References

- [Phase 9 Plan](https://github.com/Hellblazer/Luciferase/blob/main/.pm-archives/sorted-wibbling-hammock.md)
- [Stabilization Plan](https://github.com/Hellblazer/Luciferase/blob/main/.pm/STABILIZATION_PLAN.md)
- [Performance Metrics](https://github.com/Hellblazer/Luciferase/blob/main/lucien/doc/PERFORMANCE_METRICS_MASTER.md)
