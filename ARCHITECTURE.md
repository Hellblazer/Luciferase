# Luciferase Architecture Overview

**Last Updated**: 2026-01-12
**Status**: Current

This document provides a high-level architecture overview of the Luciferase spatial data structure and visualization library.

---

## Table of Contents

- [System Overview](#system-overview)
- [Module Architecture](#module-architecture)
- [Key Design Principles](#key-design-principles)
- [Spatial Indexing](#spatial-indexing)
- [Distributed Simulation](#distributed-simulation)
- [Rendering and Visualization](#rendering-and-visualization)
- [Cross-Cutting Concerns](#cross-cutting-concerns)

---

## System Overview

Luciferase is a comprehensive 3D spatial data structure library providing:

- **Spatial Indexing**: Multiple hierarchical spatial data structures (Octree, Tetree, Prism, SFCArrayIndex)
- **Distributed Simulation**: Multi-bubble distributed animation with entity migration
- **Collision Detection**: Physics-aware collision detection with material properties
- **Rendering**: ESVO (Efficient Sparse Voxel Octrees) GPU-accelerated rendering
- **Visualization**: JavaFX 3D visualization and mesh generation

### Target Use Cases

- Large-scale 3D spatial simulations
- Distributed virtual environments
- Real-time collision detection systems
- GPU-accelerated voxel rendering
- Kinetic point tracking and Delaunay tetrahedralization

---

## Module Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         APPLICATIONS                            │
├─────────────────────────────────────────────────────────────────┤
│  portal (JavaFX)  │  render (LWJGL)  │  simulation (PrimeMover) │
├─────────────────────────────────────────────────────────────────┤
│                         CORE LIBRARIES                          │
├─────────────────────────────────────────────────────────────────┤
│     lucien        │      sentry      │        von              │
│ (Spatial Indexing)│(Tetrahedralization)│(Voronoi Perception)    │
├─────────────────────────────────────────────────────────────────┤
│                      SHARED UTILITIES                           │
├─────────────────────────────────────────────────────────────────┤
│  common (Collections) │ grpc (Protobuf) │ dyada-java (Math)    │
└─────────────────────────────────────────────────────────────────┘
```

### Module Dependencies

| Module | Dependencies | Purpose |
|--------|-------------|---------|
| **lucien** | common, dyada-java | Spatial indexing, collision detection, ghost layer |
| **simulation** | lucien, grpc, von | Distributed animation, entity migration, bubbles |
| **render** | lucien, common | ESVO rendering, GPU ray tracing |
| **portal** | lucien, common | JavaFX visualization, mesh generation |
| **sentry** | common | Delaunay tetrahedralization, kinetic tracking |
| **von** | lucien | Voronoi-based area-of-interest perception |
| **grpc** | - | Protocol buffer definitions for serialization |
| **common** | - | Optimized collections, geometry utilities |

---

## Key Design Principles

### 1. Generic Spatial Index Design

All spatial indexes share a common generic architecture:

```java
AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>
```

**Benefits**:
- ~95% code reuse across Octree, Tetree, Prism
- Type-safe spatial key operations
- Unified API for all spatial index types
- Consistent behavior and testing patterns

### 2. Thread-Safe Concurrent Operations

- **ConcurrentSkipListMap**: Lock-free ordered concurrent access
- **CopyOnWriteArrayList**: Thread-safe entity storage with iteration
- **volatile + setter**: Visibility guarantees without locking
- **ObjectPool**: Lock-free resource management for hot paths

### 3. Memory Efficiency

- **FFM API**: Direct memory management via Foreign Function & Memory API
- **Optimized collections**: `FloatArrayList`, `OaHashSet` for reduced overhead
- **Object pooling**: Reduce GC pressure in k-NN, collision, ray intersection
- **LITMAX/BIGMIN**: Efficient SFC range query algorithm

### 4. Deterministic Testing

- **Clock interface**: Abstraction for time queries (currentTimeMillis, nanoTime)
- **TestClock**: Deterministic implementation for reproducible tests
- **VonMessageFactory**: Time injection for record classes
- **Benefits**: Eliminate timing-dependent flakiness, enable time-travel debugging

### 5. Distributed by Design

- **Ghost layer**: Boundary entity synchronization across spatial partitions
- **2PC migration**: Two-phase commit protocol for reliable entity migration
- **Bubble-based**: Emergent consistency boundaries for massively distributed simulation
- **Causal consistency**: Bounded rollback (GGPO-style) within interaction range

---

## Spatial Indexing

### Lucien Module Architecture

**190+ Java files, 18 packages**

#### Core Spatial Index Types

| Type | Space-Filling Curve | Geometry | Use Case |
|------|---------------------|----------|----------|
| **Octree** | Morton Z-curve | Cubic subdivision | General-purpose 3D indexing |
| **Tetree** | TM-index | Tetrahedral subdivision | Anisotropic data, mesh-aligned |
| **Prism** | Custom | Triangular/linear | Terrain, stratified data |
| **SFCArrayIndex** | Morton (flat) | Cubic (flat array) | Static datasets, fastest inserts |

#### Key Components

**Entity Management** (13 classes):
- Entity lifecycle (registration, updates, removal)
- Multi-entity support per spatial location
- ID generation (sequential, UUID-based)

**Collision Detection** (29 classes):
- Physics shapes (AABB, sphere, capsule, mesh)
- Continuous collision detection (CCD)
- Material properties and response
- BVH (Bounding Volume Hierarchy) optimization

**Forest Management** (27 classes):
- Multi-tree coordination
- Ghost layer synchronization
- Distributed spatial partitioning
- Cross-tree entity migration

**Dynamic Scene Occlusion Culling** (9+ classes):
- Adaptive Z-buffer (128x128 to 2048x2048)
- Lazy allocation (3+ occluder threshold)
- Auto-disable on >20% overhead
- 2.0x speedup when properly configured

#### Performance Characteristics

**Octree vs Tetree** (10,000 entities):
- Tetree: 1.9x-6.2x faster insertions
- Octree: Better query performance
- Both: O(log n) operations, 21 levels (2 billion nodes)

**SFCArrayIndex**:
- Fastest insertions (flat array)
- Static datasets only (no dynamic updates)
- LITMAX/BIGMIN for efficient range queries

**See**: [lucien/doc/PERFORMANCE_METRICS_MASTER.md](lucien/doc/PERFORMANCE_METRICS_MASTER.md) for detailed benchmarks.

---

## Distributed Simulation

### Simulation Module Architecture

**Built on PrimeMover discrete event simulation framework**

#### Key Concepts

**Simulation Bubbles**:
- Emergent connected components of interacting entities
- Autonomous ticking with RealTimeController
- Causal consistency within interaction range
- Eventual consistency across bubbles

**Entity Migration**:
- 2PC (Two-Phase Commit) protocol for reliability
- Source-initiated with target approval
- Timeout-based recovery (30s default)
- Ghost state synchronization during migration

**Bucket Scheduler**:
- 100ms time bucket coordination
- Wall-clock-based synchronization across nodes
- Bounded rollback for divergence correction (GGPO-style)

#### Deterministic Testing Infrastructure

**Clock Interface** (H3 Determinism Epic):
- Abstraction for time queries (`currentTimeMillis`, `nanoTime`)
- System clock for production: `Clock.system()`
- TestClock for deterministic testing
- VonMessageFactory for record class time injection

**Progress**: Phase 1 complete (36/113 calls converted, 31.9%)

**Pattern** (Standard Clock Injection):
```java
public class MyService {
    private volatile Clock clock = Clock.system();

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void doWork() {
        long now = clock.currentTimeMillis();  // NOT System.currentTimeMillis()
    }
}
```

**Benefits**:
- Reproducible distributed scenarios
- Time-travel debugging
- Elimination of timing-dependent flakiness
- Consistent CI/CD results

**See**: [simulation/doc/H3_DETERMINISM_EPIC.md](simulation/doc/H3_DETERMINISM_EPIC.md) for complete architecture.

#### Network Simulation

**FakeNetworkChannel**:
- Configurable packet loss (0-100%)
- Latency simulation (min/max delay)
- Message ordering control
- Deterministic testing support (Clock-based timestamps)

**GhostStateManager**:
- Boundary entity synchronization
- Ghost layer health monitoring
- Cross-bubble visibility

#### Migration Coordination

**State Machine** (EntityMigrationStateMachine):
- 6 states: IDLE → PREPARING → MIGRATING → COMMITTING → COMMITTED → ROLLEDBACK
- FSM notification pattern via MigrationStateListener
- Timeout detection and rollback
- Event reprocessing for gap recovery

**MigrationCoordinator**:
- FSM ↔ 2PC bridge
- Transaction coordination
- Conflict resolution
- Metrics collection

**EventReprocessor**:
- Gap detection (30s timeout)
- Event ordering validation
- Retry with exponential backoff

---

## Rendering and Visualization

### ESVO (Efficient Sparse Voxel Octrees)

**Based on Laine & Karras 2010 paper**

#### Core Algorithms (Complete)

- **8-byte unified nodes**: Child descriptor (64 bits) + contour descriptor (32 bits)
- **Stack-based ray traversal**: GPU-optimized DDA-style descent
- **Contour pointers**: Surface refinement for high-quality rendering
- **Mipmapping**: Level-of-detail support

#### Rendering Pipeline

**CPU Path**:
- ESVTCPURayTraverser: Reference implementation
- ResultBufferProtocol: Standardized hit recording
- CrossValidation: CPU ↔ GPU parity validation

**GPU Path** (LWJGL + OpenCL):
- OpenCL kernel compilation and caching
- Memory buffer management via FFM
- Multi-frame rendering support
- Performance profiling integration

**Status**: Core algorithms complete, GPU integration validated

**See**: [render/doc/ESVO_COMPLETION_SUMMARY.md](render/doc/ESVO_COMPLETION_SUMMARY.md) for implementation details.

### JavaFX Visualization (Portal)

**Capabilities**:
- 3D mesh generation and rendering
- Collision visualization
- Spatial index visualization
- Interactive camera controls

**Components**:
- CollisionVisualizationTest: Debug visualization
- CollisionDebugSystem: Real-time collision display
- Mesh utilities: Procedural mesh generation

---

## Cross-Cutting Concerns

### Concurrency Model

**No `synchronized` keyword allowed**:
- ConcurrentSkipListMap for ordered concurrent access
- CopyOnWriteArrayList for thread-safe iteration
- volatile fields for visibility guarantees
- Optimistic concurrency control (lock-free entity updates)

**ObjectPool Pattern**:
- Reduce GC pressure in hot paths
- Used for: k-NN, collision, ray intersection, frustum culling
- Thread-local pools for cache-line optimization

### Error Handling

**Logging** (SLF4J + Logback):
- `private static final Logger log = LoggerFactory.getLogger(ClassName.class);`
- Debug logging for spatial operations, performance metrics
- Test classes use `logback-test.xml` configuration
- **Important**: Use `{}` placeholders, NOT Python-style `{:.2f}`

**Validation**:
- Precondition checks via IllegalArgumentException
- Null safety via @NotNull annotations
- Invariant checks in critical paths

### Performance Profiling

**JMH Benchmarks**:
- Standardized benchmark process
- Automated metric extraction
- Documentation auto-update via `performance-full` profile

**DSOC (Dynamic Scene Occlusion Culling)**:
- Auto-disable on >20% overhead
- Adaptive Z-buffer sizing
- Lazy allocation (3+ occluder threshold)

### Testing Strategy

**Unit Tests**: 2200+ tests across all modules
**Integration Tests**: Distributed scenarios, multi-bubble simulations
**Performance Tests**: JMH benchmarks with `-Pperformance` profile
**Deterministic Tests**: Clock-based time control for reproducibility

**Flaky Test Handling**:
```java
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Flaky: probabilistic test")
```

---

## Architecture Decision Records

### Key Decisions

**Spatial Indexing**:
- Generic `AbstractSpatialIndex<Key, ID, Content>` design for code reuse
- ConcurrentSkipListMap over dual HashMap/TreeSet (54-61% memory savings)
- S0-S5 tetrahedral subdivision (100% cube tiling, no gaps/overlaps)

**Distributed Simulation**:
- Clock interface for deterministic testing (H3 Epic)
- 2PC protocol for reliable entity migration
- Bucket-based time synchronization (100ms buckets)
- Ghost layer for boundary synchronization

**Rendering**:
- ESVO 8-byte unified nodes (Laine & Karras 2010)
- Stack-based ray traversal (GPU-optimized)
- CPU reference implementation for validation

**Performance**:
- FFM API for native memory management
- Object pooling for GC reduction
- Lock-free concurrency control
- SIMD operations where applicable

---

## Future Architecture Directions

**H3 Determinism Epic**:
- Phases 2-4: Convert remaining 77 System.* calls (68.1%)
- Complete deterministic testing coverage

**Phase 7D Entity Migration**:
- Ghost physics integration
- View stability optimization
- End-to-end migration testing

**ESVO Rendering**:
- GPU warp coherence optimization (H6)
- SIMD vectorization for 4-wide ray bundles (H5)
- Contour integration for surface refinement (H7)

---

## Related Documentation

### Core Architecture
- [lucien/doc/LUCIEN_ARCHITECTURE.md](lucien/doc/LUCIEN_ARCHITECTURE.md) - Spatial indexing details
- [simulation/doc/CONSOLIDATION_MASTER_OVERVIEW.md](simulation/doc/CONSOLIDATION_MASTER_OVERVIEW.md) - Simulation architecture
- [render/doc/ESVO_COMPLETION_SUMMARY.md](render/doc/ESVO_COMPLETION_SUMMARY.md) - ESVO implementation

### Design Documentation
- [simulation/doc/H3_DETERMINISM_EPIC.md](simulation/doc/H3_DETERMINISM_EPIC.md) - Clock interface architecture
- [simulation/doc/SIMULATION_BUBBLES.md](simulation/doc/SIMULATION_BUBBLES.md) - Bubble-based simulation
- [lucien/doc/PERFORMANCE_METRICS_MASTER.md](lucien/doc/PERFORMANCE_METRICS_MASTER.md) - Performance characteristics

### Developer Documentation
- [CONTRIBUTING.md](CONTRIBUTING.md) - Contribution guidelines
- [CLAUDE.md](CLAUDE.md) - Claude Code development guide
- [README.md](README.md) - Project overview and quick start

---

**Questions or Feedback?**

Contact: hal.hildebrand@gmail.com
GitHub: [@Hellblazer](https://github.com/Hellblazer)

---

**License**: AGPL v3.0
