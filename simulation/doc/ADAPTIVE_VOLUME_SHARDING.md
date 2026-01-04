# Adaptive Volume Sharding Architecture

**Document Version**: 1.0
**Date**: 2026-01-03
**Bead Reference**: Luciferase-1oo
**Status**: Design Complete, Ready for Implementation

## Executive Summary

Adaptive Volume Sharding introduces SpatialTumbler and SpatialSpan components to enable dynamic tetrahedral volume management with split/join capabilities for load balancing in the simulation module. This design replaces the conceptual dependency on MutableGrid (sentry module) with a spatial index approach that leverages the existing Tetree infrastructure.

## Context

### Current State
- VolumeAnimator uses Tetree for entity tracking (migration from MutableGrid complete)
- Simple API: `track(Point3f) -> Cursor`, `start()`
- Single Tetree instance per VolumeAnimator
- No dynamic volume partitioning capability

### Target State
- SpatialTumbler manages dynamic regions within a Tetree
- SpatialSpan tracks boundary entities between regions
- Automatic split when region entity count exceeds threshold
- Automatic join when adjacent regions fall below threshold
- Integration with BubbleDynamicsManager for simulation bubbles

### Related Components
- **Tetree**: Tetrahedral spatial index with TetreeKey hierarchy
- **AdaptiveForest**: Multi-tree management with split/merge (reference architecture)
- **ForestLoadBalancer**: Load metric tracking and migration planning
- **BubbleDynamicsManager**: Entity grouping and affinity tracking
- **GhostLayer**: Boundary entity synchronization for distributed support

## Design Decisions

### D1: Region-Based vs Tree-Based Architecture

**Decision**: Region-based architecture operating on TetreeKey hierarchies within a single Tetree.

**Rationale**:
- Tetree's hierarchical TetreeKey structure naturally supports regions
- More memory-efficient than multiple Tetree instances
- Leverages existing S0-S5 tetrahedral subdivision
- O(log n) operations maintained through single spatial index

**Alternatives Rejected**:
- **Multiple Tetree instances**: Higher memory overhead, complex cross-tree queries
- **Wrap AdaptiveForest directly**: Overhead of forest management for single simulation

### D2: SpatialSpan Boundary Model

**Decision**: Distance-based boundary zones with configurable span width.

**Rationale**:
- Entities within span distance of region boundary visible to both regions
- Enables efficient cross-region neighbor queries
- Lazy evaluation prevents unnecessary computation

**Parameters**:
- `spanWidthRatio`: Span width as fraction of region size (default: 0.1)
- `minSpanDistance`: Absolute minimum span in world units (default: 1.0)

### D3: Split/Join Triggers

**Decision**: Dual-threshold system with hysteresis.

**Parameters**:
- `splitThreshold`: Entity count triggering split (default: 5000)
- `joinThreshold`: Combined count below which adjacent regions join (default: 500)
- `minRegionLevel`: Minimum TetreeKey level for regions (default: 4)
- `maxRegionLevel`: Maximum TetreeKey level (deepest split) (default: 12)

### D4: VolumeAnimator Integration

**Decision**: Composition - VolumeAnimator delegates to SpatialTumbler.

**Rationale**:
- Maintains backward compatibility with existing track() API
- SpatialTumbler manages regions transparently
- Easy to enable/disable volume sharding per VolumeAnimator

---

## Architecture

### Class Diagram

```
+------------------+       +-------------------+       +------------------+
|  VolumeAnimator  |------>|  SpatialTumbler   |------>|      Tetree      |
+------------------+       +-------------------+       +------------------+
        |                          |                          ^
        |                          |                          |
        v                          v                          |
+------------------+       +-------------------+              |
|  AnimationFrame  |       |  TumblerRegion    |--------------+
+------------------+       +-------------------+
                                   |
                                   v
                           +-------------------+
                           |   SpatialSpan     |
                           +-------------------+
                                   |
                                   v
                           +-------------------+
                           |   BoundaryZone    |
                           +-------------------+
```

### Package Structure

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/
  tumbler/
    SpatialTumbler.java           # Main interface
    SpatialTumblerImpl.java       # Implementation with split/join
    TumblerRegion.java            # Region state record
    TumblerConfig.java            # Configuration parameters
    RegionSplitStrategy.java      # Strategy for subdivision
    package-info.java
  span/
    SpatialSpan.java              # Boundary management interface
    SpatialSpanImpl.java          # Implementation
    BoundaryZone.java             # Zone between regions
    SpanConfig.java               # Span configuration
    package-info.java
```

---

## API Design

### SpatialTumbler Interface

```java
/**
 * Manages dynamic tetrahedral volume regions with adaptive split/join.
 *
 * @param <ID> Entity identifier type
 * @param <Content> Entity content type
 */
public interface SpatialTumbler<ID extends EntityID, Content> {

    /**
     * Track an entity at the given position.
     * Returns the region key where the entity was placed.
     */
    TetreeKey<?> track(ID entityId, Point3f position, Content content);

    /**
     * Update entity position, potentially moving between regions.
     */
    TetreeKey<?> update(ID entityId, Point3f newPosition);

    /**
     * Remove entity from tracking.
     */
    boolean remove(ID entityId);

    /**
     * Get the region containing a position.
     */
    TumblerRegion<ID> getRegion(Point3f position);

    /**
     * Get the region for a specific key.
     */
    TumblerRegion<ID> getRegion(TetreeKey<?> regionKey);

    /**
     * Get all regions.
     */
    Collection<TumblerRegion<ID>> getAllRegions();

    /**
     * Force split check on all regions.
     * Returns number of splits performed.
     */
    int checkAndSplit();

    /**
     * Force join check on all regions.
     * Returns number of joins performed.
     */
    int checkAndJoin();

    /**
     * Get the boundary span manager.
     */
    SpatialSpan<ID> getSpan();

    /**
     * Get current configuration.
     */
    TumblerConfig getConfig();

    /**
     * Get statistics.
     */
    TumblerStatistics getStatistics();
}
```

### TumblerRegion Record

```java
/**
 * Represents a region within SpatialTumbler.
 * Regions are defined by TetreeKey hierarchies.
 */
public record TumblerRegion<ID extends EntityID>(
    TetreeKey<?> key,              // Region identifier (ancestor key)
    int entityCount,               // Current entity count
    float density,                 // Entities per unit volume
    Set<ID> entities,              // Entity IDs in this region
    TetreeKey<?> parentKey,        // Parent region (if split)
    List<TetreeKey<?>> childKeys,  // Child regions (if has children)
    long lastUpdateTime,           // Last modification timestamp
    RegionState state              // ACTIVE, SPLITTING, JOINING, FROZEN
) {
    public enum RegionState {
        ACTIVE,      // Normal operation
        SPLITTING,   // In process of splitting
        JOINING,     // In process of joining
        FROZEN       // Temporarily immutable
    }

    public boolean isLeaf() {
        return childKeys == null || childKeys.isEmpty();
    }

    public boolean needsSplit(TumblerConfig config) {
        return entityCount > config.splitThreshold()
            && key.level() < config.maxRegionLevel();
    }

    public boolean canJoin(TumblerConfig config) {
        return entityCount < config.joinThreshold()
            && key.level() > config.minRegionLevel();
    }
}
```

### SpatialSpan Interface

```java
/**
 * Manages boundary zones between regions for cross-region queries.
 */
public interface SpatialSpan<ID extends EntityID> {

    /**
     * Get entities in the boundary zone between two regions.
     */
    Set<ID> getBoundaryEntities(TetreeKey<?> region1, TetreeKey<?> region2);

    /**
     * Get all entities in boundary zones for a region.
     */
    Set<ID> getAllBoundaryEntities(TetreeKey<?> regionKey);

    /**
     * Check if entity is in a boundary zone.
     */
    boolean isInBoundary(ID entityId);

    /**
     * Get boundary zones for a region.
     */
    Collection<BoundaryZone<ID>> getBoundaryZones(TetreeKey<?> regionKey);

    /**
     * Update boundary zones after entity movement.
     */
    void updateBoundary(ID entityId, Point3f position);

    /**
     * Recalculate all boundary zones (after split/join).
     */
    void recalculateBoundaries();

    /**
     * Get configuration.
     */
    SpanConfig getConfig();
}
```

### TumblerConfig Record

```java
/**
 * Configuration for SpatialTumbler behavior.
 */
public record TumblerConfig(
    int splitThreshold,        // Entity count triggering split (default: 5000)
    int joinThreshold,         // Combined count for join (default: 500)
    byte minRegionLevel,       // Minimum region depth (default: 4)
    byte maxRegionLevel,       // Maximum region depth (default: 12)
    float spanWidthRatio,      // Span as fraction of region (default: 0.1)
    float minSpanDistance,     // Minimum span width (default: 1.0)
    boolean autoAdapt,         // Enable automatic split/join (default: true)
    int adaptCheckInterval,    // Operations between adapt checks (default: 100)
    RegionSplitStrategy splitStrategy  // How to split regions
) {
    public static TumblerConfig defaults() {
        return new TumblerConfig(
            5000, 500, (byte) 4, (byte) 12,
            0.1f, 1.0f, true, 100,
            RegionSplitStrategy.OCTANT
        );
    }

    public TumblerConfig withSplitThreshold(int threshold) {
        return new TumblerConfig(threshold, joinThreshold, minRegionLevel,
            maxRegionLevel, spanWidthRatio, minSpanDistance, autoAdapt,
            adaptCheckInterval, splitStrategy);
    }
    // ... other with* methods
}
```

---

## Implementation Plan

### Phase 1: Foundation - SpatialTumbler Core
**Duration**: 3-4 days
**Dependencies**: None

#### Tasks

| Task | Description | Effort |
|------|-------------|--------|
| 1.1 | Create TumblerConfig record with defaults and builders | 2h |
| 1.2 | Create TumblerRegion record with state management | 3h |
| 1.3 | Create SpatialTumbler interface | 2h |
| 1.4 | Create SpatialTumblerImpl with Tetree delegation | 6h |
| 1.5 | Implement region registration and tracking | 4h |
| 1.6 | Implement entity count and density calculation | 3h |
| 1.7 | Write unit tests for region management | 4h |

#### Test Strategy
- `TumblerRegionTest`: State transitions, count tracking
- `SpatialTumblerBasicTest`: Entity insertion, region lookup
- `TumblerConfigTest`: Builder pattern, defaults validation

#### Success Criteria
- [ ] TumblerRegion correctly tracks entity count and density
- [ ] SpatialTumbler can create regions and track entities
- [ ] Entity lookup returns correct region
- [ ] All unit tests pass

#### Deliverables
- `tumbler/TumblerConfig.java`
- `tumbler/TumblerRegion.java`
- `tumbler/SpatialTumbler.java`
- `tumbler/SpatialTumblerImpl.java` (partial)
- Unit tests

---

### Phase 2: Split/Join Logic
**Duration**: 4-5 days
**Dependencies**: Phase 1

#### Tasks

| Task | Description | Effort |
|------|-------------|--------|
| 2.1 | Create RegionSplitStrategy enum/interface | 2h |
| 2.2 | Implement OCTANT split (8-way tetrahedral) | 6h |
| 2.3 | Implement entity redistribution on split | 5h |
| 2.4 | Implement region join detection | 4h |
| 2.5 | Implement entity consolidation on join | 4h |
| 2.6 | Add split/join locks for thread safety | 3h |
| 2.7 | Implement checkAndSplit/checkAndJoin methods | 4h |
| 2.8 | Write unit tests for split/join | 6h |

#### Test Strategy
- `RegionSplitTest`: Verify correct subdivision geometry
- `RegionJoinTest`: Verify correct consolidation
- `SplitJoinConcurrencyTest`: Thread-safe operations
- `SplitThresholdTest`: Trigger conditions

#### Success Criteria
- [ ] Split correctly creates 8 child regions
- [ ] Entities redistribute to correct child regions
- [ ] Join correctly consolidates child entities to parent
- [ ] No entity loss during split/join operations
- [ ] Concurrent operations do not corrupt state
- [ ] Performance: split < 100ms for 10K entities

#### Deliverables
- `tumbler/RegionSplitStrategy.java`
- Enhanced `tumbler/SpatialTumblerImpl.java`
- Split/join unit tests

---

### Phase 3: SpatialSpan Boundary Management
**Duration**: 3-4 days
**Dependencies**: Phase 1

#### Tasks

| Task | Description | Effort |
|------|-------------|--------|
| 3.1 | Create SpanConfig record | 1h |
| 3.2 | Create BoundaryZone record | 2h |
| 3.3 | Create SpatialSpan interface | 2h |
| 3.4 | Implement boundary zone detection | 5h |
| 3.5 | Implement entity boundary tracking | 4h |
| 3.6 | Implement cross-region queries | 4h |
| 3.7 | Integrate with SpatialTumbler | 3h |
| 3.8 | Write unit tests for boundary management | 4h |

#### Test Strategy
- `BoundaryZoneTest`: Zone creation and geometry
- `SpanEntityTrackingTest`: Entity in/out of boundary
- `CrossRegionQueryTest`: Entities visible to adjacent regions

#### Success Criteria
- [ ] Boundary zones correctly calculated for adjacent regions
- [ ] Entities near boundaries tracked in both regions
- [ ] Cross-region queries return expected entities
- [ ] Boundary updates efficient (< 1ms per entity)

#### Deliverables
- `span/SpanConfig.java`
- `span/BoundaryZone.java`
- `span/SpatialSpan.java`
- `span/SpatialSpanImpl.java`
- Boundary unit tests

---

### Phase 4: VolumeAnimator Integration
**Duration**: 2-3 days
**Dependencies**: Phases 1-3

#### Tasks

| Task | Description | Effort |
|------|-------------|--------|
| 4.1 | Add SpatialTumbler field to VolumeAnimator | 1h |
| 4.2 | Refactor track() to use SpatialTumbler | 3h |
| 4.3 | Add configuration for tumbler enable/disable | 2h |
| 4.4 | Maintain backward compatibility | 2h |
| 4.5 | Write integration tests | 4h |
| 4.6 | Performance benchmarking | 3h |

#### Test Strategy
- `VolumeAnimatorIntegrationTest`: End-to-end tracking
- `VolumeAnimatorBackwardCompatTest`: API unchanged
- `VolumeAnimatorPerformanceTest`: Comparison with previous impl

#### Success Criteria
- [ ] VolumeAnimator.track() works with SpatialTumbler
- [ ] Existing tests continue to pass
- [ ] No performance regression (< 10% overhead)
- [ ] Tumbler can be enabled/disabled via config

#### Deliverables
- Updated `VolumeAnimator.java`
- Integration tests

---

### Phase 5: Load Balancing Integration
**Duration**: 2-3 days
**Dependencies**: Phase 4

#### Tasks

| Task | Description | Effort |
|------|-------------|--------|
| 5.1 | Connect TumblerStatistics to BubbleDynamicsManager | 3h |
| 5.2 | Implement affinity-based region assignment | 4h |
| 5.3 | Add migration hooks for bubble transfers | 3h |
| 5.4 | Implement load metrics collection | 3h |
| 5.5 | Write integration tests | 4h |

#### Test Strategy
- `LoadBalancingIntegrationTest`: Metrics flow correctly
- `AffinityRegionTest`: Entities assigned to optimal regions
- `MigrationHookTest`: Transfers trigger correctly

#### Success Criteria
- [ ] Load metrics flow from SpatialTumbler to BubbleDynamicsManager
- [ ] Entity affinity influences region assignment
- [ ] Bubble migration triggers region rebalancing

#### Deliverables
- Updated `BubbleDynamicsManager.java`
- Integration with `TumblerStatistics`
- Integration tests

---

### Phase 6: Ghost Layer Integration
**Duration**: 2-3 days
**Dependencies**: Phase 3

#### Tasks

| Task | Description | Effort |
|------|-------------|--------|
| 6.1 | Connect SpatialSpan to GhostZoneManager | 3h |
| 6.2 | Implement ghost entity sync for span entities | 4h |
| 6.3 | Handle network partition scenarios | 3h |
| 6.4 | Write integration tests | 4h |

#### Test Strategy
- `GhostSpanIntegrationTest`: Span entities sync as ghosts
- `PartitionRecoveryTest`: Span state recovers after partition

#### Success Criteria
- [ ] Span boundary entities available as ghosts
- [ ] Ghost sync latency < 10ms
- [ ] Partition recovery maintains entity consistency

#### Deliverables
- Updated `SpatialSpanImpl.java` with ghost integration
- Ghost layer integration tests

---

## Performance Targets

| Operation | Target | Method |
|-----------|--------|--------|
| Entity track | O(log n) | Tetree insertion |
| Region lookup | O(log n) | TetreeKey hierarchy |
| Split operation | < 100ms for 10K entities | Batch redistribution |
| Join operation | < 50ms for 5K entities | Batch consolidation |
| Boundary query | O(k) where k = span entities | Direct span access |
| Adapt check | < 1ms | Periodic sampling |

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Split cascade (repeated splits) | Medium | Medium | Hysteresis threshold gap |
| Entity loss during split/join | Low | High | Transaction logging, validation |
| Performance regression | Medium | Medium | Benchmarking, profiling |
| Concurrency issues | Medium | High | ReadWriteLock, immutable regions |

## Dependencies

### External
- Tetree (lucien module) - stable
- BubbleDynamicsManager (simulation) - stable
- GhostZoneManager (lucien/forest) - stable

### Internal
- Phase dependencies as documented above

## Testing Requirements

### Unit Tests (Per Phase)
- Each class has corresponding test class
- Minimum 80% code coverage
- All edge cases documented

### Integration Tests
- VolumeAnimator end-to-end
- BubbleDynamicsManager integration
- Ghost layer integration

### Performance Tests
- Split/join benchmarks
- Memory profiling
- Concurrent operation stress tests

## Documentation Deliverables

| Document | Location | Status |
|----------|----------|--------|
| Architecture (this document) | simulation/doc/ADAPTIVE_VOLUME_SHARDING.md | Complete |
| API JavaDoc | Source files | Per phase |
| Integration Guide | simulation/doc/TUMBLER_INTEGRATION_GUIDE.md | Phase 4 |
| Performance Report | simulation/doc/TUMBLER_PERFORMANCE.md | Phase 4 |

---

## Appendix A: TetreeKey Hierarchy for Regions

Regions are defined by TetreeKey ancestors. A region at level L contains all entities with TetreeKeys that have the same ancestor at level L.

```
Level 0: Root (1 region covering entire space)
Level 4: 6^4 = 1,296 possible regions
Level 8: 6^8 = 1,679,616 possible regions
Level 12: 6^12 = 2.18 billion possible regions
```

Split creates 6 child regions (one per tetrahedron type at next level).
Join consolidates 6 child regions into parent.

## Appendix B: Boundary Zone Geometry

For adjacent regions at level L:
- Region size: ~1/6^L of unit cube
- Span width: spanWidthRatio * region_size
- Boundary entities: within span distance of shared face

Tetrahedral face adjacency determined by TetreeNeighborFinder.

---

**Document Maintainer**: Claude Code
**Last Review**: 2026-01-03
**Next Review**: Upon Phase 2 completion
