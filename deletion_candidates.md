# Luciferase Code Reduction: 424 Deletion Candidates

**Bead**: Luciferase-xo6p (D3: Identify deletion candidates)
**Date**: 2026-01-13
**Status**: Analysis Complete - Ready for Execution

---

## Executive Summary

**Current State**: 1,468 Java files
**Target**: ~1,044 files (30% reduction)
**Gap**: 424 files
**Approach**: Three-tranche execution (Easy → Medium → Hard)

## Summary by Module

| Module | Current | Reduction Target | Files to Delete | Priority |
|--------|---------|------------------|-----------------|----------|
| simulation | 475 | 60 (Month 2) | 415 | CRITICAL |
| lucien | 402 | ~280 | 122 | HIGH |
| render | 204 | opportunistic | ~50 | MEDIUM |
| portal | 149 | opportunistic | ~30 | MEDIUM |
| Other modules | 238 | minimal | ~7 | LOW |
| **Total** | **1,468** | **~1,044** | **~424** | - |

**Key Insight**: Simulation module requires 87% reduction (475 → 60 files). This is the primary focus area.

---

## EASY TRANCHE (100 files) - Month 2 Target

**Timeline**: Weeks 5-8 (Month 2)
**Risk Level**: LOW
**Prerequisites**: None - can start immediately after analysis approval

### Category 1: Duplicate Classes (12 files)

Files with identical names that have newer/better implementations elsewhere.

| # | File Path | Reason | Risk | Blocker |
|---|-----------|--------|------|---------|
| 1 | `simulation/src/main/java/.../consensus/demo/FlockingBehavior.java` | Duplicate of behavior/FlockingBehavior.java | LOW | Migrate demo usages |
| 2 | `simulation/src/main/java/.../distributed/GridBubbleFactory.java` | Duplicate of distributed/grid/GridBubbleFactory.java | LOW | Update imports |
| 3 | `simulation/src/main/java/.../distributed/InitialDistribution.java` | Duplicate of distributed/grid/InitialDistribution.java | LOW | Update imports |
| 4 | `simulation/src/main/java/.../distributed/MultiBubbleSimulation.java` | Duplicate of distributed/grid/MultiBubbleSimulation.java | LOW | Update imports |
| 5 | `simulation/src/main/java/.../causality/MigrationCoordinator.java` | Duplicate of distributed/migration/MigrationCoordinator.java | LOW | Verify which is active |
| 6 | `simulation/src/main/java/.../distributed/grid/MigrationMetrics.java` | Duplicate of distributed/migration/MigrationMetrics.java | LOW | Consolidate metrics |
| 7 | `simulation/src/test/java/.../distributed/GridBubbleFactoryTest.java` | Duplicate test | LOW | None |
| 8 | `simulation/src/test/java/.../distributed/InitialDistributionTest.java` | Duplicate test | LOW | None |
| 9 | `simulation/src/test/java/.../distributed/MultiBubbleSimulationTest.java` | Duplicate test | LOW | None |
| 10 | `simulation/src/test/java/.../causality/MigrationCoordinatorTest.java` | Duplicate test | LOW | None |
| 11 | `dyada-java/src/main/java/com/dyada/visualization/data/Bounds.java` | Duplicate of core/coordinates/Bounds.java | LOW | Update visualization usages |
| 12 | `render/src/main/java/.../sparse/core/CoordinateSpace.java` | Duplicate of esvo/core/CoordinateSpace.java | LOW | Update sparse usages |

**Execution Plan**:
1. For each file, run IDE "Find Usages" to identify all references
2. Create migration script to update imports
3. Delete duplicate file
4. Run full test suite to verify no breakage

### Category 2: Demo/Example Code (29 files)

Demo code that can be removed from production build.

| # | File Path | Reason | Risk | Blocker |
|---|-----------|--------|------|---------|
| 1 | `simulation/src/main/java/.../consensus/demo/ConsensusBubbleGrid.java` | Demo code | LOW | None |
| 2 | `simulation/src/main/java/.../consensus/demo/ConsensusBubbleNode.java` | Demo code | LOW | None |
| 3 | `simulation/src/main/java/.../consensus/demo/ConsensusBubbleGridFactory.java` | Demo code | LOW | None |
| 4 | `simulation/src/main/java/.../consensus/demo/ConsensusAwareMigrator.java` | Demo code | LOW | None |
| 5 | `simulation/src/main/java/.../consensus/demo/ConsensusMigrationIntegration.java` | Demo code | LOW | None |
| 6 | `simulation/src/main/java/.../consensus/demo/DemoConfiguration.java` | Demo code | LOW | None |
| 7 | `simulation/src/main/java/.../consensus/demo/DemoMetricsCollector.java` | Demo code | LOW | None |
| 8 | `simulation/src/main/java/.../consensus/demo/DemoValidationReport.java` | Demo code | LOW | None |
| 9 | `simulation/src/main/java/.../consensus/demo/EntitySpawner.java` | Demo code | LOW | None |
| 10 | `simulation/src/main/java/.../consensus/demo/FailureInjector.java` | Demo code (already deleted) | LOW | None |
| 11 | `simulation/src/main/java/.../consensus/demo/FailureScenario.java` | Demo code (already deleted) | LOW | None |
| 12 | `simulation/src/main/java/.../consensus/demo/FlockingEntity.java` | Demo code | LOW | None |
| 13 | `simulation/src/main/java/.../consensus/demo/FlockingEntityFactory.java` | Demo code | LOW | None |
| 14 | `simulation/src/main/java/.../consensus/demo/ByzantineNode.java` | Demo code | LOW | None |
| 15 | `simulation/src/main/java/.../consensus/demo/SimulationRunner.java` | Demo code | LOW | None |
| 16 | `simulation/src/main/java/.../consensus/demo/SpatialDemo.java` | Demo code | LOW | None |
| 17-29 | `simulation/src/test/java/.../consensus/demo/*Test.java` (13 files) | Demo tests | LOW | None |

**Note**: The entire `simulation/src/main/java/.../consensus/demo/` package (16 files) and corresponding tests (13 files) can be deleted as a single unit.

**Execution Plan**:
1. Verify demo package is not referenced by production code
2. Delete entire `consensus/demo/` package directory
3. Run full test suite to verify no breakage

### Category 3: @Deprecated Code (10 files)

Files with @Deprecated annotations indicating planned removal.

| # | File Path | Reason | Risk | Blocker |
|---|-----------|--------|------|---------|
| 1 | `simulation/src/main/java/.../causality/EntityMigrationStateMachine.java` | @Deprecated | LOW | Verify no callers |
| 2 | `simulation/src/main/java/.../animation/VolumeAnimator.java` | @Deprecated | LOW | Verify no callers |
| 3 | `sentry/src/main/java/com/hellblazer/sentry/TetrahedronPoolContext.java` | @Deprecated | LOW | Verify no callers |
| 4 | `sentry/src/main/java/com/hellblazer/sentry/MutableGrid.java` | @Deprecated | LOW | Verify no callers |
| 5 | `render/src/main/java/.../esvt/optimization/ESVTOptimizationPipeline.java` | @Deprecated | LOW | Verify no callers |
| 6 | `render/src/main/java/.../esvt/core/ESVTNodeUnified.java` | @Deprecated | LOW | Verify no callers |
| 7 | `render/src/main/java/.../esvo/optimization/ESVOOptimizationPipeline.java` | @Deprecated | LOW | Verify no callers |
| 8 | `render/src/main/java/.../esvo/core/ESVONode.java` | @Deprecated | LOW | Verify no callers |
| 9 | `render/src/main/java/.../esvo/builder/ESVOCPUBuilder.java` | @Deprecated | LOW | Verify no callers |
| 10 | `lucien/src/main/java/.../tetree/Tet.java` | @Deprecated methods (partial) | MEDIUM | Selective removal |

**Execution Plan**:
1. For each file, run IDE "Find Usages" to verify no active callers
2. If callers exist, create migration bead to update them first
3. Delete deprecated file
4. Run full test suite to verify no breakage

**Note**: `lucien/src/main/java/.../tetree/Tet.java` has partial deprecation - only specific methods are deprecated, not the entire file. Requires selective removal.

### Category 4: Debugging/Test Utilities (49 files)

Test infrastructure and debugging utilities that duplicate functionality or are no longer needed.

#### Render Module Debug Tests (17 files)

| # | File Path | Reason |
|---|-----------|--------|
| 1 | `render/src/test/java/.../esvo/test/BufferIdentityTest.java` | Debug test |
| 2 | `render/src/test/java/.../esvo/test/BufferPositionTest.java` | Debug test |
| 3 | `render/src/test/java/.../esvo/test/BufferTrackingDeepDive.java` | Debug investigation |
| 4 | `render/src/test/java/.../esvo/test/DirectIdentityMapTest.java` | Debug test |
| 5 | `render/src/test/java/.../esvo/test/IdentityHashMapFixTest.java` | Debug test |
| 6 | `render/src/test/java/.../esvo/test/IsolatedResourceLeakTest.java` | Debug test |
| 7 | `render/src/test/java/.../esvo/test/IsolatedTrackingTest.java` | Debug test |
| 8 | `render/src/test/java/.../esvo/test/ManagerInstanceTest.java` | Debug test |
| 9 | `render/src/test/java/.../esvo/test/MimicOctreeBuilderTest.java` | Debug test |
| 10 | `render/src/test/java/.../esvo/test/OctreeBuilderLeakTest.java` | Debug test |
| 11 | `render/src/test/java/.../esvo/test/OctreeBuilderVoxelTest.java` | Debug test |
| 12 | `render/src/test/java/.../esvo/test/SimpleBufferTest.java` | Debug test |
| 13 | `render/src/test/java/.../esvo/test/SimpleResourceTest.java` | Debug test |
| 14 | `render/src/test/java/.../esvo/test/SynchronizationBugTest.java` | Debug test |
| 15 | `render/src/test/java/.../esvo/test/SynchronizedIdentityMapTest.java` | Debug test |
| 16 | `render/src/test/java/.../esvo/test/WorkaroundVerificationTest.java` | Debug test |
| 17 | `render/src/test/java/.../esvo/test/ESVOResourceIntegrationTest.java` | Debug test |

**Note**: The entire `render/src/test/java/.../esvo/test/` package can be deleted as a single unit.

#### Simulation Module Test Utilities (13 files)

| # | File Path | Reason |
|---|-----------|--------|
| 1 | `simulation/src/test/java/.../MovableActor.java` | Test utility |
| 2 | `simulation/src/test/java/.../SmokeTest.java` | Basic smoke test |
| 3 | `simulation/src/test/java/.../integration/IntegrationTestBase.java` | Test base (check inheritance) |
| 4 | `simulation/src/test/java/.../integration/TestClusterBuilder.java` | Test utility |
| 5 | `simulation/src/test/java/.../delos/fireflies/Graph.java` | Test utility |
| 6 | `simulation/src/test/java/.../validation/CausalRollbackPrototypeTest.java` | Prototype test |
| 7 | `simulation/src/test/java/.../validation/CheckpointBandwidthTest.java` | Validation test |
| 8 | `simulation/src/test/java/.../distributed/integration/Entity3DDistributor.java` | Test utility |
| 9 | `simulation/src/test/java/.../distributed/integration/Entity3DTracker.java` | Test utility |
| 10 | `simulation/src/test/java/.../distributed/integration/PerformanceStabilityTestValidation.java` | Validation test |
| 11 | `portal/src/test/java/.../JavaFXTestBaseExample.java` | Example code |
| 12 | `portal/src/test/java/.../TestPortal.java` | Test utility |
| 13 | `portal/src/test/java/.../Viewer.java` | Test utility |

#### Portal Module Test Utilities (7 files)

| # | File Path | Reason |
|---|-----------|--------|
| 1 | `portal/src/test/java/.../mesh/explorer/JavaFXTestCondition.java` | Test utility |
| 2 | `portal/src/test/java/.../mesh/explorer/RequiresJavaFX.java` | Test annotation |
| 3 | `portal/src/test/java/.../mesh/explorer/SimpleVisualTest.java` | Visual test |
| 4 | `portal/src/test/java/.../JavaFXTestBase.java` | Test base |
| 5 | `portal/src/test/java/.../JavaFXTestBaseExample.java` | Example code |
| 6 | `portal/src/test/java/.../TestPortal.java` | Test utility |
| 7 | `portal/src/test/java/.../Viewer.java` | Test utility |

#### Other Test Utilities (12 files)

| # | File Path | Reason |
|---|-----------|--------|
| 1-2 | `render/src/test/java/.../esvo/test/CIEnvironmentCheck.java`<br>`lucien/src/test/java/.../benchmark/CIEnvironmentCheck.java` | Duplicate CI utilities |
| 3-12 | Various simulation/portal/lucien test utilities | Consolidate or remove |

**Execution Plan**:
1. Delete render debug test package (`render/src/test/java/.../esvo/test/`)
2. For each test utility, verify no active usage
3. Delete unused test utilities
4. Run full test suite to verify no breakage

---

## MEDIUM TRANCHE (200 files)

**Timeline**: Months 3-4 (Weeks 9-16)
**Risk Level**: MEDIUM
**Prerequisites**: Easy tranche complete, design review for architectural changes

### Category 5: Redundant Simulation Grid Infrastructure (72 files)

**Problem**: The `distributed/grid` package duplicates `bubble` package functionality.

**Strategy**: Consolidate into `bubble` package, migrate usages, delete redundant grid infrastructure.

#### Main Files (10 files)

| # | File Path | Reason | Risk | Blocker |
|---|-----------|--------|------|---------|
| 1 | `simulation/src/main/java/.../distributed/grid/BubbleGrid.java` | Redundant with bubble/ | MEDIUM | Migration plan |
| 2 | `simulation/src/main/java/.../distributed/grid/GridBubbleFactory.java` | Duplicate | MEDIUM | Migration plan |
| 3 | `simulation/src/main/java/.../distributed/grid/InitialDistribution.java` | Duplicate | MEDIUM | Migration plan |
| 4 | `simulation/src/main/java/.../distributed/grid/GridBoundaryDetector.java` | Redundant | MEDIUM | Migration plan |
| 5 | `simulation/src/main/java/.../distributed/grid/GridGhostSyncAdapter.java` | Redundant | MEDIUM | Migration plan |
| 6 | `simulation/src/main/java/.../distributed/grid/MigrationDirection.java` | Redundant | MEDIUM | Migration plan |
| 7 | `simulation/src/main/java/.../distributed/grid/MigrationMetrics.java` | Duplicate | MEDIUM | Migration plan |
| 8 | `simulation/src/main/java/.../distributed/grid/MultiDirectionalMigration.java` | Redundant | MEDIUM | Migration plan |
| 9 | `simulation/src/main/java/.../distributed/grid/MigrationRouter.java` | Redundant | MEDIUM | Migration plan |
| 10 | `simulation/src/main/java/.../distributed/grid/MultiBubbleSimulation.java` | Duplicate | MEDIUM | Migration plan |

**Keep** (Core Types):
- `GridConfiguration.java` - Core config
- `BubbleCoordinate.java` - Core type

#### Test Files (62 files)

| # | File Path | Reason | Risk |
|---|-----------|--------|------|
| 1-8 | `simulation/src/test/java/.../distributed/grid/*Test.java` | Redundant tests | MEDIUM |
| 9-62 | Additional grid-related test files | Redundant tests | MEDIUM |

**Execution Plan**:
1. Create design review document for grid → bubble consolidation
2. Identify all usages of grid package
3. Create migration beads for updating references
4. Test thoroughly with integration tests
5. Delete grid package after migration complete

### Category 6: Consensus Committee (28 files)

**Problem**: Over-engineered consensus committee infrastructure can be consolidated with simpler causality mechanisms.

#### Main Files (11 files)

| # | File Path | Reason | Risk | Blocker |
|---|-----------|--------|------|---------|
| 1 | `simulation/src/main/java/.../consensus/committee/MigrationProposal.java` | Over-engineered | MEDIUM | Refactor |
| 2 | `simulation/src/main/java/.../consensus/committee/Vote.java` | Over-engineered | MEDIUM | Refactor |
| 3 | `simulation/src/main/java/.../consensus/committee/CommitteeConfig.java` | Over-engineered | MEDIUM | Refactor |
| 4 | `simulation/src/main/java/.../consensus/committee/ViewCommitteeSelector.java` | Over-engineered | MEDIUM | Refactor |
| 5 | `simulation/src/main/java/.../consensus/committee/CommitteeBallotBox.java` | Over-engineered | MEDIUM | Refactor |
| 6 | `simulation/src/main/java/.../consensus/committee/PropagationStrategy.java` | Over-engineered | MEDIUM | Refactor |
| 7 | `simulation/src/main/java/.../consensus/committee/ViewCommitteeConsensus.java` | Over-engineered | MEDIUM | Refactor |
| 8 | `simulation/src/main/java/.../consensus/committee/CommitteeVotingProtocol.java` | Over-engineered | MEDIUM | Refactor |
| 9 | `simulation/src/main/java/.../consensus/committee/CommitteeProtoConverter.java` | Over-engineered | MEDIUM | Refactor |
| 10 | `simulation/src/main/java/.../consensus/committee/CommitteeServiceImpl.java` | Over-engineered | MEDIUM | Refactor |
| 11 | `simulation/src/main/java/.../consensus/committee/OptimisticMigratorIntegration.java` | Over-engineered | MEDIUM | Refactor |

#### Test Files (17 files)

Corresponding tests for above files.

**Execution Plan**:
1. Create design review for consensus simplification
2. Identify minimal consensus requirements
3. Refactor to simpler causality-based approach
4. Delete committee infrastructure after refactor

### Category 7: Render Module ESVO Phased Tests (13 files)

**Problem**: Phased development tests are no longer needed after feature completion.

| # | File Path | Reason | Risk | Blocker |
|---|-----------|--------|------|---------|
| 1 | `render/src/test/java/.../esvo/ESVOPhase0Tests.java` | Phased development complete | MEDIUM | None |
| 2 | `render/src/test/java/.../esvo/ESVOPhase1Tests.java` | Phased development complete | MEDIUM | None |
| 3 | `render/src/test/java/.../esvo/ESVOPhase2Tests.java` | Phased development complete | MEDIUM | None |
| 4 | `render/src/test/java/.../esvo/ESVOPhase3Tests.java` | Phased development complete | MEDIUM | None |
| 5 | `render/src/test/java/.../esvo/ESVOPhase4Tests.java` | Phased development complete | MEDIUM | None |
| 6 | `render/src/test/java/.../esvo/ESVOPhase5Tests.java` | Phased development complete | MEDIUM | None |
| 7 | `render/src/test/java/.../esvo/ESVOPhase6Tests.java` | Phased development complete | MEDIUM | None |
| 8 | `render/src/test/java/.../esvo/ESVOPhase7Tests.java` | Phased development complete | MEDIUM | None |
| 9 | `render/src/test/java/.../esvo/ESVOPhase8Tests.java` | Phased development complete | MEDIUM | None |
| 10 | `render/src/test/java/.../esvo/ESVOCppComplianceTests.java` | Compliance verified | MEDIUM | None |
| 11 | `render/src/test/java/.../esvo/ESVOEnhancedRayTests.java` | Feature complete | MEDIUM | None |
| 12 | `render/src/test/java/.../esvo/ESVOTraversalValidationTest.java` | Validation complete | MEDIUM | None |
| 13 | `render/src/test/java/.../esvo/ESVOGPUIntegrationTest.java` | Consolidate | MEDIUM | None |

**Execution Plan**:
1. Verify phased tests are superseded by comprehensive tests
2. Delete phased test files
3. Run full test suite to verify coverage maintained

### Category 8: Lucien Benchmark Infrastructure (15 files)

**Problem**: Multiple overlapping benchmark files that can be consolidated.

| # | File Path | Keep/Delete | Reason |
|---|-----------|-------------|--------|
| 1 | `lucien/src/test/java/.../benchmark/BaselinePerformanceBenchmark.java` | DELETE | Consolidate |
| 2 | `lucien/src/test/java/.../benchmark/DSOCBenchmarkRunner.java` | DELETE | Consolidate |
| 3 | `lucien/src/test/java/.../benchmark/DSOCPerformanceBenchmark.java` | DELETE | Consolidate |
| 4 | `lucien/src/test/java/.../benchmark/FourWaySpatialIndexBenchmark.java` | **KEEP** | Primary benchmark |
| 5 | `lucien/src/test/java/.../benchmark/GeometricSubdivisionBenchmark.java` | DELETE | Consolidate |
| 6 | `lucien/src/test/java/.../benchmark/OctreeVsTetreeBenchmark.java` | **KEEP** | Primary benchmark |
| 7 | `lucien/src/test/java/.../benchmark/PerformanceDataExtractor.java` | DELETE | Consolidate |
| 8 | `lucien/src/test/java/.../benchmark/PerformanceDocumentationUpdater.java` | DELETE | Consolidate |
| 9 | `lucien/src/test/java/.../benchmark/SpatialIndexStressTest.java` | DELETE | Consolidate |
| 10-15 | Various baseline/SIMD benchmark files | DELETE | Consolidate |

**Execution Plan**:
1. Identify essential benchmarks to keep (2 primary)
2. Consolidate others into primary benchmarks
3. Delete redundant benchmark files

### Category 9: Simulation Integration Tests (72 files)

**Problem**: Heavy integration test infrastructure that can be reduced while maintaining coverage.

**Strategy**: Keep essential integration tests, consolidate or delete heavy/redundant tests.

| Package | File Count | Keep Essential | Delete Redundant |
|---------|------------|----------------|------------------|
| `distributed/integration/` | 26 | ~10 | ~16 |
| `distributed/network/` | 9 | ~4 | ~5 |
| `consensus/committee/integration/` | 7 | ~3 | ~4 |
| `causality/` | 18 | ~8 | ~10 |
| `ghost/` | 12 | ~5 | ~7 |

**Execution Plan**:
1. Audit each integration test package
2. Identify essential tests covering critical paths
3. Consolidate redundant tests
4. Delete consolidated tests

---

## HARD TRANCHE (124 files)

**Timeline**: Months 4-6 (Weeks 17-24)
**Risk Level**: HIGH
**Prerequisites**: Medium tranche complete, architectural review, design documents

### Category 10: Ghost/Von Infrastructure Consolidation (45 files)

**Problem**: The ghost and von packages have overlapping responsibilities for boundary synchronization.

**Current State**:
- `simulation/.../ghost/` - 21 files - Ghost entity management
- `simulation/.../von/` - 17 files - VON (Voronoi Overlay Network) boundary detection
- `simulation/.../von/` tests - 7 files

**Strategy**: Create unified boundary management abstraction that consolidates both approaches.

**Risk**: HIGH - Core architectural change affecting distributed simulation

**Execution Plan**:
1. Create ADR for ghost/von consolidation
2. Design unified boundary management interface
3. Get plan-auditor approval
4. Incremental migration with comprehensive testing
5. Delete redundant infrastructure after migration

### Category 11: Portal Visualization Consolidation (35 files)

**Problem**: Multiple overlapping visualization approaches across portal module.

**Packages to Consolidate**:
- `collision/` - 8 files - Debug visualization
- `esvo/` - 10 files - Can consolidate with esvt
- `mesh/explorer/` - 12 files - Complex explorer can be simplified
- `inspector/` - 5 files - Consolidate inspection tools

**Strategy**: Consolidate into unified visualization pipeline.

**Risk**: HIGH - Affects development tools and debugging capabilities

**Execution Plan**:
1. Create design review for visualization consolidation
2. Identify essential visualization features
3. Design unified visualization interface
4. Migrate to consolidated approach
5. Delete redundant visualization code

### Category 12: Lucien Forest/Collision Deep Refactor (44 files)

**Problem**: Complex subsystems that need architectural review and simplification.

**Packages to Refactor**:
- `forest/` - 17 files - Over-engineered multi-tree coordination
- `forest/ghost/` - 10 files - Overlaps with simulation ghost
- `collision/physics/` - 5 files - May not be needed
- `collision/physics/constraints/` - 3 files - May not be needed
- `forest/` tests - 9 files

**Strategy**: Simplify forest coordination, remove unused physics, consolidate ghost with simulation.

**Risk**: HIGH - Core lucien functionality, affects spatial indexing

**Execution Plan**:
1. Create ADR for forest simplification
2. Analyze actual usage of physics subsystem
3. Design simplified forest coordination
4. Get plan-auditor approval
5. Incremental refactor with comprehensive testing
6. Delete simplified infrastructure

---

## Risk Mitigation Strategy

### Pre-Deletion Checklist

For **every file** before deletion:

1. ✅ **Find Usages**: Use IDE "Find Usages" to verify no active callers
2. ✅ **Run Tests**: Run full test suite and verify all tests pass
3. ✅ **Check Coverage**: Verify test coverage maintained or improved
4. ✅ **CI Verification**: Verify CI passes before and after deletion
5. ✅ **Git Safety**: Create feature branch for deletion batch
6. ✅ **Rollback Plan**: Document how to revert if issues discovered

### Per-Tranche Safety Measures

**Easy Tranche**:
- Delete in small batches (10-15 files per batch)
- Run full test suite after each batch
- Commit frequently with descriptive messages

**Medium Tranche**:
- Create design review document for architectural changes
- Get plan-auditor approval before starting
- Use feature branches for complex migrations
- Incremental rollout with testing between stages

**Hard Tranche**:
- Create comprehensive ADR for each major refactor
- Get plan-auditor + technical lead approval
- Phased rollout with milestone reviews
- Maintain rollback capability until stabilized

### Rollback Procedures

If issues discovered after deletion:

1. **Immediate**: `git revert <commit>` to restore deleted files
2. **Investigation**: Identify root cause of issue
3. **Fix Forward**: Either fix the issue or restore file permanently
4. **Document**: Update deletion_candidates.md with lessons learned

---

## Files to Keep (Critical Infrastructure)

**DO NOT DELETE** - These are core abstractions:

### Simulation Module
- `Clock.java` - Core time abstraction for deterministic testing
- `TestClock.java` - Test infrastructure
- Core bubble package files (after grid consolidation)
- Core migration package files (after consolidation)

### Lucien Module
- `SpatialIndex.java` - Core interface
- `AbstractSpatialIndex.java` - Core implementation
- All octree core files (`lucien/src/main/java/.../octree/`)
- All tetree core files (`lucien/src/main/java/.../tetree/`)
- Core SFC array index files

### Render Module
- Core ESVO implementation files
- Core ESVT implementation files
- Resource management abstractions

### Common Module
- All utility collections (`FloatArrayList`, `OaHashSet`, etc.)
- Geometry utilities

---

## Execution Roadmap

### Month 2 (Weeks 5-8) - Easy Tranche

**Week 5-6**: Duplicates + Demo Code (41 files)
- Days 1-2: Delete 12 duplicate classes
- Days 3-5: Delete 29 demo/example files
- Run full test suite, verify CI

**Week 7-8**: Deprecated + Debug Utilities (59 files)
- Days 1-2: Delete 10 @Deprecated files
- Days 3-10: Delete 49 debug/test utilities
- Run full test suite, verify CI

**Gate**: 100 files deleted, all tests passing, CI green

### Month 3 (Weeks 9-12) - Medium Tranche (Part 1)

**Week 9-10**: Grid Consolidation (72 files)
- Create design review
- Migrate grid → bubble
- Delete grid package
- Run full test suite

**Week 11-12**: Consensus + ESVO Tests (41 files)
- Simplify consensus committee (28 files)
- Delete ESVO phased tests (13 files)
- Run full test suite

**Gate**: 113 files deleted (cumulative 213), tests passing, CI green

### Month 4 (Weeks 13-16) - Medium Tranche (Part 2)

**Week 13-14**: Lucien Benchmarks + Integration Tests (87 files)
- Consolidate benchmark infrastructure (15 files)
- Trim integration tests (72 files)
- Run performance benchmarks to verify

**Week 15-16**: Buffer week for Medium tranche completion

**Gate**: 200 files deleted (cumulative 300), tests passing, benchmarks stable

### Months 5-6 (Weeks 17-24) - Hard Tranche

**Week 17-18**: Ghost/Von Consolidation (45 files)
- Create ADR
- Design unified boundary management
- Incremental migration
- Delete redundant infrastructure

**Week 19-20**: Portal Visualization (35 files)
- Design unified visualization
- Migrate to consolidated approach
- Delete redundant visualization code

**Week 21-22**: Lucien Forest/Collision (44 files)
- Create ADR for forest simplification
- Refactor forest coordination
- Delete simplified infrastructure

**Week 23-24**: Buffer week for Hard tranche completion

**Gate**: 124 files deleted (cumulative 424), tests passing, all modules stable

---

## Success Metrics

### Quantitative Metrics

- [ ] **File Count**: 1,468 → 1,044 (424 files deleted)
- [ ] **Simulation Module**: 475 → 60 (415 files deleted)
- [ ] **Lucien Module**: 402 → 280 (122 files deleted)
- [ ] **Test Pass Rate**: Maintained at 100%
- [ ] **Code Coverage**: Maintained at >70%
- [ ] **CI Build Time**: ≤ current (no regression)

### Qualitative Metrics

- [ ] **Architectural Clarity**: Clear module boundaries
- [ ] **Code Health**: Reduced technical debt
- [ ] **Developer Experience**: Easier to navigate codebase
- [ ] **Maintenance**: Reduced surface area for bugs
- [ ] **Documentation**: Updated architecture docs

---

## Next Steps

1. **Get Approval**: Present this analysis for review and approval
2. **Create Beads**: Create deletion beads for each tranche
3. **Start Easy Tranche**: Begin with Week 5 (duplicates + demo code)
4. **Track Progress**: Update daily_progress.md with deletion metrics
5. **Monthly Reviews**: Review progress at end of each month

---

## References

- Bead: Luciferase-xo6p (D3: Identify deletion candidates)
- METHODOLOGY.md: Primary metrics and gate criteria
- Analysis Date: 2026-01-13
- Analyst: codebase-deep-analyzer agent (agent a57e875)

---

**Document Status**: COMPLETE - Ready for approval and execution
**Last Updated**: 2026-01-13
**Version**: 1.0
