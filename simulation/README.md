# Simulation Module

**Last Updated**: 2026-01-12
**Status**: Implementation Complete + Deterministic Testing (31.9% H3 Complete)

Distributed animation framework for 3D spatial systems with causal consistency. Built on PrimeMover discrete event simulation with Tetree spatial indexing.

**New**: Deterministic testing with Clock interface - 36 of 113 System.* calls converted (H3.7 Phase 1 complete).

## Features

### Simulation Bubbles

Massively distributed animation using emergent consistency boundaries:

- **BucketScheduler**: 100ms time bucket coordination across nodes
- **CausalRollback**: Bounded rollback (GGPO-style) for divergence correction
- **BubbleDynamicsManager**: Entity clustering and bubble management
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

### Deterministic Testing (H3.7 - In Progress)

**Status**: Phase 1 Complete (31.9% of 113 System.* calls converted)

Clock interface enables deterministic control of time in tests:

```java
// Setup deterministic clock
var testClock = new TestClock();
testClock.setMillis(1000L);  // Start at T=1000ms

// Inject into components
var migration = new CrossProcessMigration(dedup, metrics);
migration.setClock(testClock);

// Control time progression
testClock.advance(500);  // Advance to T=1500ms
```

**Benefits**:
- Eliminates timing-dependent test flakiness
- Enables reproducible debugging
- Supports deterministic simulation replay
- Zero behavioral change in production (Clock.system() ≡ System.currentTimeMillis())

**See**: [H3_DETERMINISM_EPIC.md](doc/H3_DETERMINISM_EPIC.md) for complete documentation.

## Architecture

**See Complete Documentation**:

- [doc/ARCHITECTURE_DISTRIBUTED.md](doc/ARCHITECTURE_DISTRIBUTED.md) - Complete distributed architecture
- [doc/SIMULATION_BUBBLES.md](doc/SIMULATION_BUBBLES.md) - Distributed simulation design
- [doc/H3_DETERMINISM_EPIC.md](doc/H3_DETERMINISM_EPIC.md) - Deterministic testing architecture
- [doc/TESTING_PATTERNS.md](doc/TESTING_PATTERNS.md) - Testing best practices

**Key Concepts**:

- **Simulation Bubble**: Emergent connected components of interacting entities
- **Causal Consistency**: Bounded rollback within interaction range (100-200ms window)
- **Ghost Layer**: Boundary entity synchronization using Lucien's GhostZoneManager
- **Bucket Scheduler**: Time-based coordination for distributed animation
- **Clock Interface**: Deterministic time control for testing (H3.7)

## Implementation Status

**Completed** (PR #63, merged 2026-01-04):

- ✅ Simulation Bubbles (6 phases, 18 main classes, 11+ test classes)
- ✅ Integration with Lucien ghost layer
- ✅ 275+ tests passing
- ✅ Complete architecture documentation

**Components**:

- Bubbles: BucketScheduler, CausalRollback, Bubble, AffinityTracker
- Dynamics: BubbleDynamicsManager, GhostBoundarySync, GhostLayerHealth
- Core: VolumeAnimator, Cursor, AnimationFrame

## Dependencies

- **PrimeMover 1.0.6**: Discrete event simulation (`RealTimeController`, `Kronos`, `@Entity`) with improved virtual time, clock drift fixes, and enhanced bytecode transformation
- **Lucien**: `Tetree` spatial indexing, `GhostZoneManager`, `Forest` multi-tree support

## Usage

```bash
# Run all simulation tests
mvn test -pl simulation

# Run bubble tests only
mvn test -pl simulation -Dtest=*Bucket*,*Bubble*
```

## Performance Targets

- **Bucket Processing**: < 100ms per time bucket
- **Rollback Window**: 100-200ms (2 buckets)
- **Ghost Sync**: Sub-frame boundary synchronization

## License

AGPL v3.0
