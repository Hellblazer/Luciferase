# Luciferase

**Last Updated**: 2026-03-22

![Build Status](https://github.com/hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)

3D spatial indexing, collision detection, and visualization for Java 25.

## What It Does

Luciferase provides four spatial index types that partition 3D space for fast entity lookup, collision detection, ray casting, and nearest-neighbor search:

| Index | Geometry | Best For |
|-------|----------|----------|
| **Octree** | Cubic (Morton SFC) | Range queries, static scenes |
| **Tetree** | Tetrahedral (TM-index SFC) | High-density insertion, exact containment |
| **SFCArrayIndex** | Flat Morton array | Small static datasets, fastest inserts |
| **Prism** | Triangular/linear | Terrain, stratified data |

All indices support 21 refinement levels, multi-entity locations, thread-safe concurrent access, k-NN search, ray intersection, and frustum culling.

### 12-DOP Exact Containment

The Tetree uses a 12-DOP (Discrete Oriented Polytope) derived from the permutohedron structure of Kuhn/Freudenthal tetrahedra. The 6 axes {x, y, z, x-y, x-z, y-z} provide **mathematically exact** containment — zero false positives, no multiplications, no extra storage.

| Operation | Method | Cost |
|-----------|--------|------|
| Point containment | `contains12DOP` | 11 ops |
| AABB-vs-tet intersection | `intersects12DOP` | 18 ops |
| Tet-vs-tet intersection | `intersectsTet12DOP` | 18 ops (27.5x faster than SAT) |

See [12-DOP theory](lucien/doc/AABT_12DOP_EXACT_CONTAINMENT.md) and [slab ranges](lucien/doc/12DOP_SLAB_RANGES.md).

## Modules

| Module | Description |
|--------|-------------|
| **common** | Optimized collections (`FloatArrayList`, `OaHashSet`), geometry utilities |
| **lucien** | Core spatial indexing — 263 Java files across 19 packages |
| **render** | ESVO/ESVT implementation with LWJGL, FFM integration |
| **sentry** | Delaunay tetrahedralization for kinetic point tracking |
| **portal** | JavaFX 3D visualization and mesh handling |
| **simulation** | Distributed simulation bubbles, entity migration, VON perception, deterministic testing |
| **grpc** | Protobuf definitions for ghost layer synchronization |
| **dyada-java** | Mathematical utilities and data structures |

External: [gpu-support](https://github.com/Hellblazer/gpu-support) provides `resource` (shaders, config) and `gpu-test-framework`.

## Requirements

- Java 25 (stable FFM API)
- Maven 3.9.1+
- JavaFX 25 (visualization)
- LWJGL 3 (OpenGL rendering)

## Build

```bash
git clone https://github.com/Hellblazer/Luciferase.git
cd Luciferase
mvn clean install
mvn test                          # run tests
mvn test -Pperformance            # run benchmarks
```

## Quick Start

```java
// Octree
var octree = new Octree<LongEntityID, String>(
    new SequentialLongIDGenerator(), 10, (byte) 10);

octree.insert(new Point3f(10, 20, 30), (byte) 5, "entity");
var neighbors = octree.kNearestNeighbors(new Point3f(10, 20, 30), 5, Float.MAX_VALUE);
var hits = octree.rayIntersectAll(new Ray3D(new Point3f(0,0,0), new Vector3f(1,0,0)));

// Tetree — exact tetrahedral containment
var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
boolean inside = tet.contains12DOP(px, py, pz);           // 11 ops, exact
boolean intersects = tet.intersects12DOP(minX, minY, minZ, maxX, maxY, maxZ);  // 18 ops
```

## Performance

| Benchmark | Result |
|-----------|--------|
| Tetree insertions vs Octree | 1.9x-6.2x faster |
| Octree range queries vs Tetree | 3.2x-8.3x faster |
| k-NN cache hit | 50-102x speedup |
| 12-DOP tet-vs-tet vs SAT | 27.5x faster (4.3 ns vs 135 ns) |
| 12-DOP point containment | 4.2 ns (11 ops, exact) |
| CI pipeline | 6 parallel batches, 9-12 minutes |

See [performance metrics](lucien/doc/PERFORMANCE_METRICS_MASTER.md) and [benchmarking guide](lucien/doc/PERFORMANCE_CONSOLIDATION_REPORT.md).

## Simulation

The simulation module provides distributed 3D simulation with:

- **Simulation bubbles** — partitioned spatial regions with entity migration
- **Ghost layers** — cross-bubble visibility via gRPC synchronization
- **Dynamic topology** — automatic split/merge when density exceeds thresholds
- **Byzantine consensus** — fault-tolerant coordination for topology changes
- **Deterministic testing** — injectable `Clock` interface eliminates timing flakiness
- **PrimeMover 1.0.6** — bytecode-transformed discrete event simulation

## Key Architecture

- **Generic spatial index**: `AbstractSpatialIndex<Key extends SpatialKey<Key>, ID, Content>` — 95% code sharing across index types
- **Dual key types**: `MortonKey` (Octree/SFC) and `TetreeKey` (Tetree)
- **Thread-safe**: `ConcurrentSkipListMap` storage with lock-free entity updates
- **S0-S5 Kuhn decomposition**: 6 tetrahedra tile each cube cell; the coordinate ordering IS the containment test
- **Bey refinement**: 8-child tetrahedral subdivision with `coordinates()` vertex convention

## Documentation

| Category | Documents |
|----------|-----------|
| Architecture | [Lucien Architecture](lucien/doc/LUCIEN_ARCHITECTURE.md), [Architecture Summary](lucien/doc/ARCHITECTURE_SUMMARY.md), [CLAUDE.md](CLAUDE.md) |
| Spatial Indexing | [12-DOP Containment](lucien/doc/AABT_12DOP_EXACT_CONTAINMENT.md), [Slab Ranges](lucien/doc/12DOP_SLAB_RANGES.md), [S0-S5 Subdivision](lucien/doc/S0_S5_TETRAHEDRAL_SUBDIVISION.md) |
| Performance | [Metrics Master](lucien/doc/PERFORMANCE_METRICS_MASTER.md), [Consolidation Report](lucien/doc/PERFORMANCE_CONSOLIDATION_REPORT.md) |
| Testing | [Test Framework Guide](TEST_FRAMEWORK_GUIDE.md), [H3 Determinism](simulation/doc/H3_DETERMINISM_EPIC.md) |
| CI/CD | [Parallel CI](docs/MAVEN_PARALLEL_CI_OPTIMIZATION.md), [Dependencies](docs/DEPENDENCY_VERSIONS_CONSOLIDATED.md) |
| Design Records | [RDR-001](docs/rdr/RDR-001-axis-aligned-bounding-tetrahedra.md), [RDR-002](docs/rdr/RDR-002-12dop-exact-containment.md) |

## License

AGPL-3.0. See [LICENSE](LICENSE).

## Acknowledgments

- ESVO: Laine & Karras 2010 "Efficient Sparse Voxel Octrees"
- Tetrahedral indexing inspired by [t8code](https://github.com/DLR-AMR/t8code)
- 12-DOP containment: Kuhn/Freudenthal simplicial decomposition via the A₂ root system
