# Simulation Module

**Last Updated**: 2026-01-04
**Status**: Production Ready

Distributed animation framework for 3D spatial systems with adaptive volume sharding and causal consistency. Built on PrimeMover discrete event simulation with Tetree spatial indexing.

## Features

### Adaptive Volume Sharding

Dynamic tetrahedral volume management with automatic split/join based on entity density:

- **SpatialTumbler**: Manages dynamic regions using TetreeKey hierarchies
- **TumblerRegion**: Tracks entity density and triggers split/join operations
- **SpatialSpan**: Boundary zone management for cross-region queries
- **Performance**: Sub-100ms split operations, efficient join detection

### Simulation Bubbles

Massively distributed animation using emergent consistency boundaries:

- **BucketScheduler**: 100ms time bucket coordination across nodes
- **CausalRollback**: Bounded rollback (GGPO-style) for divergence correction
- **BubbleDynamicsManager**: Entity clustering and bubble management
- **VON Integration**: Voronoi Overlay Network for distributed bubble discovery
- **Consistency Model**: Causal consistency within interaction range, eventual across bubbles

### Core Animation Framework

Frame-rate controlled animation loop using PrimeMover discrete event simulation:

```java
var animator = new VolumeAnimator("scene");
animator.start(); // Begins 100 FPS frame loop

// Track entities with optional sharding
Cursor cursor = animator.track(new Point3f(x, y, z));
cursor.moveTo(newPosition);
```

**Features**:

- Tetree spatial indexing for O(log n) operations
- Multi-entity support per spatial location
- Precise timing control via Kronos
- Frame statistics tracking

### Entity Interfaces

- **Locatable**: `position()` - query entity location
- **Moveable**: `moveBy(delta)`, `moveTo(position)` - entity movement
- **Cursor**: Movement tracking with spatial index integration

## Architecture

**See Complete Documentation**:

- [doc/CONSOLIDATION_MASTER_OVERVIEW.md](doc/CONSOLIDATION_MASTER_OVERVIEW.md) - Complete architecture overview
- [doc/ADAPTIVE_VOLUME_SHARDING.md](doc/ADAPTIVE_VOLUME_SHARDING.md) - Volume sharding implementation
- [doc/SIMULATION_BUBBLES.md](doc/SIMULATION_BUBBLES.md) - Distributed simulation design

**Key Concepts**:

- **Adaptive Volume Sharding**: Dynamic spatial partitioning based on entity density
- **Simulation Bubble**: Emergent connected components of interacting entities
- **Causal Consistency**: Bounded rollback within interaction range (100-200ms window)
- **Ghost Layer**: Boundary entity synchronization using Lucien's GhostZoneManager
- **Bucket Scheduler**: Time-based coordination for distributed animation

## Implementation Status

**Completed** (PR #63, merged 2026-01-04):

- ✅ Adaptive Volume Sharding (6 phases, 9 main classes, 7 test classes)
- ✅ Simulation Bubbles (6 phases, 18 main classes, 11+ test classes)
- ✅ Integration with Lucien ghost layer
- ✅ 390+ tests passing
- ✅ Complete architecture documentation

**Components** (30 main classes):

- Sharding: SpatialTumbler, TumblerRegion, SpatialSpan, BoundaryZone
- Bubbles: BucketScheduler, CausalRollback, Bubble, AffinityTracker, VONCoordinator
- Shared: BubbleDynamicsManager, GhostBoundarySync, GhostLayerHealth
- Core: VolumeAnimator, Cursor, AnimationFrame

## Dependencies

- **PrimeMover 1.0.5**: Discrete event simulation (`RealTimeController`, `Kronos`, `@Entity`)
- **Lucien**: `Tetree` spatial indexing, `GhostZoneManager`, `Forest` multi-tree support
- **VON**: Distributed bubble discovery and coordination

## Usage

```bash
# Run all simulation tests
mvn test -pl simulation

# Run sharding tests only
mvn test -pl simulation -Dtest=*Tumbler*

# Run bubble tests only
mvn test -pl simulation -Dtest=*Bucket*,*Bubble*
```

## Performance Targets

- **Split Operation**: < 100ms for 5000+ entity regions
- **Bucket Processing**: < 100ms per time bucket
- **Rollback Window**: 100-200ms (2 buckets)
- **Ghost Sync**: Sub-frame boundary synchronization

## License

AGPL v3.0
