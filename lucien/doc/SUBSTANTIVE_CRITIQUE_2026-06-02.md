# Lucien Module — Substantive Critique & Remediation Reference (2026-06-02)

**Status**: Active remediation backlog
**Tracking epic**: `Luciferase-yzrg0`
**Filter label**: `critique-2026-06-02`
**Branch at time of critique**: `feature/1ugmx-prism-pyramid-demo`
**Method**: 8 parallel `conexus:substantive-critic` agents, each scoped to one subsystem, each briefed with the locked specs (CLAUDE.md Critical Architecture Notes, RDR-010/012, active beads) and required to verify every claim at `file:line` via Serena. Per-subsystem critiques persisted in T2 (`critique-*-2026-06-02.md`).

Totals: **20 Critical, 38 Significant** findings. Pyramid subsystem returned **0 Critical** (its findings were already tracked under bead `a6tz`).

---

## 1. Cross-cutting themes (read this first)

These systemic patterns matter more than any single bug. Each recurs across multiple subsystems.

1. **Clock-injection mandate violated wholesale (~70+ sites).** CLAUDE.md forbids direct `System.currentTimeMillis()` / `System.nanoTime()` in production; mandates injecting `com.hellblazer.luciferase.common.time.Clock`. Violations cluster in core, the entire forest subsystem, the entire balancing/fault package (50+ sites — the most timeout-sensitive code in the module), DSOC, and KNNCache. Consequence: timeout/perf behaviour is non-deterministically testable. → **`Luciferase-mt7hi`** (sweep) + facet beads.

2. **Hollow stubs shipped as production behind elaborate scaffolding.** `balancing/fault/`'s three primary `PartitionRecovery` implementations are `Thread.sleep` simulations that unconditionally call `markHealthy()` and return success; 40 production + 72 test classes validate the scaffolding, not recovery. Verdict: speculative over-engineering vs CLAUDE.md "extract only when repetition is proven." → **`Luciferase-yogvu`**.

3. **Vacuous / spec-contradicting tests give false green.** `Triangle.setBounds` is a no-op making triangular-query tests assert only `assertNotNull`; `DSOCAutoDisableTest` never asserts `isDSOCEnabled()==false` (the safety valve has zero true-branch coverage); `TetreeLevelCacheKeyCollisionTest` tests a deleted formula; SIMD tests run only the scalar fallback. → `8sufr`, `cvtaa`, `egjwk`, `lsy13`.

4. **RDR-004 D3-class silent data loss on the distributed ghost path** — the highest-severity cluster. → `c1ka5`, `7pias`.

---

## 2. Bead index (full mapping)

Legend: **C** Critical · **S** Significant · **M** Minor. Priority is the filed `bd` priority.

### Forest / Ghost / Distributed
| Bead | Sev | Pri | Finding | Primary location |
|------|-----|-----|---------|------------------|
| `c1ka5` | C | P1 | GhostCoordinator dual GhostLayer split → every ghost query returns zero ghosts | `GhostCoordinator.java:80,99,170,222,258`; `GhostBoundaryDetector.java:100` |
| `7pias` | C | P1 | requestGhostElements ignores entityIdClass + bypasses contentSerializer → silent drop (RDR-004 class) | `GhostServiceClient.java:225,227` (lucien-distributed) |
| `8es5p` | C | P1 | GridForest.createTreeAt throws unconditionally → GridForest non-functional | `GridForest.java:184` |
| `cfg4o` | S | P2 | Distributed ghost update outside coordinator lock → inconsistent boundary set | `GhostCoordinator.java:283-288`; `GhostBoundaryDetector.java:127` |
| `963vw` | S | P2 | syncCallback registered but never invoked | `DistributedGhostManager.java:78,402` |
| `smaik` | S | P2 | setGhostType/setNeighborDetector ordering gap | `GhostCoordinator.java:111,202-207` |

### Collision / CCD / Physics
| Bead | Sev | Pri | Finding | Primary location |
|------|-----|-----|---------|------------------|
| `v2na8` | C | P1 | conservativeCCD mutates live shapes; early-return skips restore; race | `ContinuousCollisionDetector.java:176-205` |
| `gojpy` | C | P1 | rayVsMovingSphereCCD is 10-sample discrete sampling → tunnels | `ContinuousCollisionDetector.java:228-266` |
| `i1mlg` | C | P1 | Hardcoded penetrationDepth=0.1f across 6+ pairs; OBB strips orientation | `CollisionDetector.java:197,619,799-803,916,936,1072,1285` |
| `wv1yk` | C | P1 | DistanceConstraint ignores body orientation → drift/explode | `DistanceConstraint.java:41-51,154-162` |
| `fyb22` | S | P2 | applyFriction double-scale on impulse2 (mass1≠mass2) | `CollisionResolver.java:215-223` |
| `fglgp` | S | P2 | conservativeCCD binary search misses through-collision; false-neg on exhaustion | `ContinuousCollisionDetector.java:162-207` |
| `j8iqw` | S | P2 | sweptSphereVsLineSegment reduces edge to midpoint sphere | `SweptSphere.java:441-446` |
| `ig4yi` | S | P2 | findBoundedEntityCollisions O(n²) ignores spatial index | `CollisionEngine.java:591-610` |
| `s62fr` | S | P3 | CCD path ignores registered CollisionShape; arbitrary radius buffer | `CollisionSystem.java:419-438` |

### Prism / SFC
| Bead | Sev | Pri | Finding | Primary location |
|------|-----|-----|---------|------------------|
| `qrxy4` | C | P1 | Prism runs DefaultTreeBalancer (dead NoOp field) → possible entity corruption | `Prism.java:59,97,323`; `AbstractSpatialIndex.java:181,1896` |
| `8sufr` | C | P1 | Triangle.setBounds no-op → triangular-query tests vacuous | `Triangle.java:667-670`; `PrismSpatialQueriesSimpleTest.java:54,88,101` |
| `lield` | S | P2 | LitmaxBigmin above-query degrades to +1 linear scan | `LitmaxBigmin.java:226-250` |
| `6hqr4` | S | P2 | PrismKey.getVolume 2× too large | `PrismKey.java:284-289` |
| `h9r0z` | S | P2 | Prism.enclosing null + getPlaneTraversalOrder empty (untracked) | `Prism.java:507-509,540-544` |
| `5oruk` | S | P2 | SFCArrayIndex.createDefaultSubdivisionStrategy returns null → latent NPE | `SFCArrayIndex.java:310-312` |
| `mfe6y` | S | P3 | Triangle.neighbors() coordinate-shift, inconsistent with faceNeighbor() | `Triangle.java:428-487` |
| `ef43s` | S | P3 | PrismNeighborFinder.getCorrespondingEdgeFace stub | `PrismNeighborFinder.java:306-310` |

### Core abstractions / entity management
| Bead | Sev | Pri | Finding | Primary location |
|------|-----|-----|---------|------------------|
| `aqx6x` | C | P1 | updateBatchParallel/removeBatchParallel TOCTOU → concurrent readers miss entities | `ParallelBulkOperations.java:189-197,217-234` |
| `3vwqb` | S | P2 | configureBulk/ParallelOperations mutate non-volatile fields without lock | `AbstractSpatialIndex.java:116,122,331-360` |
| `xiv5u` | S | P2 | size()/processEntitiesInSFCOrder/processNodesInSFCOrder lock-free | `AbstractSpatialIndex.java:1264,2243-2267` |
| `1q51y` | S | P2 | getEntityLocations bypasses read lock → transient empty during move | `AbstractSpatialIndex.java:763-765` |
| `vb8dt` | S | P2 | Dead deferredSubdivisionNodes field (wrong type, never written) | `AbstractSpatialIndex.java:106` |
| `rk8hv` | S | P3 | Extract SpatialIndexGeometry interface (5 inner classes mirror signatures, no compile check) | `AbstractSpatialIndex.java:1476-1479` |

### Tetree / t8code
| Bead | Sev | Pri | Finding | Primary location |
|------|-----|-----|---------|------------------|
| `rzn79` | C | P2 | computeType() called unguarded on possibly-pyramid-rooted Tet (latent throw) | `TetreeBits.java:52-58`; `ESVTBuilder.java:558` (render) |
| `dcath` | C | P2 | Stale/spec-contradicting javadocs (locatePointS0Tree documents DELETED algorithm) | `Tet.java:355-382`; `Tetree.locate()`; intersectsTet12DOP comment |
| `w6f0w` | S | P3 | Dead methods: getRootTetrahedronType, classifyPointInS0S5Cube (stale geometry), determineTetrahedronType | `Tet.java`, `Tetree.java` |
| `egjwk` | S | P3 | TetreeLevelCacheKeyCollisionTest tests a deleted formula → cache untested | `TetreeLevelCacheKeyCollisionTest.java` |

### DSOC / occlusion / util
| Bead | Sev | Pri | Finding | Primary location |
|------|-----|-----|---------|------------------|
| `z3gvs` | C | P1 | DsocController auto-disable counters non-atomic volatile-long ++ | `DsocController.java:88-91,381-385` |
| `cvtaa` | C | P1 | DSOC auto-disable never tested to fire + uninjectable nanoTime | `DSOCAutoDisableTest.java`; `DsocController.java:375,379` |
| `vdv4p` | C | P2 | DSOC overhead comparison not apples-to-apples (biased baseline) | `DsocController.java:253-254,412-420` |
| `up7uz` | S | P2 | ObjectPools PriorityQueue pooling no-op on hot k-NN path; dead ConcurrentPool fields | `ObjectPools.java:36-37,213-217`; `KnnSearcher.java:172` |
| `2qpd2` | S | P2 | SpatialIndexConverter reflective getLevel (silent level-0 trap) + non-atomic failedEntities | `SpatialIndexConverter.java:210-258,289-296` |
| `eu4dc` | S | P3 | SpatialIndexProfiler.generateReport iterates synchronizedList unsafely (CME) | `SpatialIndexProfiler.java:251,311-313` |
| `1436o` | S | P3 | KNNCache stores unused nanoTime timestamp (dead state + Clock violation) | `KNNCache.java:149` |
| `lsy13` | M | P3 | EntityCache not LRU; TraversalStrategy.IN_ORDER dead; SIMD tests scalar-only | `EntityCache.java`; `TraversalStrategy`; `SIMDMortonEncoderTest` |

### Balancing / fault recovery
| Bead | Sev | Pri | Finding | Primary location |
|------|-----|-----|---------|------------------|
| `yogvu` | C | P1 | PartitionRecovery implementations are hollow stubs shipped as production | `DefaultPartitionRecovery.java:152-163`; `BarrierRecoveryImpl.java:244-251`; `CascadingRecoveryImpl.java:320-406` |
| `ln6wu` | C | P2 | CrossPartitionBalancePhase reflection dispatch = runtime-only failure mode | `CrossPartitionBalancePhase.java:454-479` |
| `lere9` | S | P2 | InFlightOperationTracker.pauseAndWait TOCTOU → 5s stall | `InFlightOperationTracker.java:116-151` |
| `h08sd` | S | P2 | CascadingRecoveryImpl unbounded newCachedThreadPool | `CascadingRecoveryImpl.java:82-85` |
| `1dcx7` | S | P2 | ClockAwareScheduler.tick() data race → double execution | `ClockAwareScheduler.java:97-109` |
| `d2nxe` | S | P3 | Converge fault/ to one Clock pattern (3 incompatible today) | `DefaultFailureDetector.java:49`; `DefaultFaultHandler.java:143,154` |

### Cross-cutting
| Bead | Sev | Pri | Finding |
|------|-----|-----|---------|
| `mt7hi` | C(cross) | P1 | Clock-injection sweep across lucien — 70+ sites |
| `06ujn` | S/SEC | P2 | Ghost+Balance gRPC servers: server-side auth + per-request size bounds (DoS) |
| `8cpq6` | DOC | P3 | Fix CLAUDE.md inaccuracies (grpc module location, 12-DOP op count) |

### Pre-existing related beads (NOT created by this critique)
- `a6tz` (P2, in_progress) — Pyramid doc/test gaps: conservative-query scope, cross-level SFCRange hazard, tet-tet involution. **All Pyramid critique findings map here** (plus one new low-risk `typeBits` inconsistency at `PyramidIndex.java:293`, folded into a6tz scope). Pyramid's `PyramidBoundaryPinningTest` was verified non-vacuous; the "infrastructure-only" deep-tet framing is honest.
- `irh` (P2, in_progress) — von SocketServer deserialization hardening (RDR-004 D3).
- `va5` (P2, in_progress) — gRPC TLS + auth model for ghost/balancing (RDR-005 D4). `06ujn` is the interim server-side bounds/validation increment; relates to va5.

---

## 3. Recommended triage order

1. **Ghost silent-data-loss trio** — `c1ka5`, `7pias` (single-file fixes, largest correctness win, unblocks the distributed path). `8es5p` GridForest alongside.
2. **gRPC server auth + bounds** — `06ujn` (security-exposed; interim before full `va5` mTLS).
3. **Collision physics** — `i1mlg`, `v2na8`, `gojpy`, `wv1yk` (wrong dynamics / corruption / tunneling).
4. **Prism balancer** — `qrxy4` (silent entity corruption on auto-balance).
5. **Clock-injection sweep** — `mt7hi` (mechanical; unblocks deterministic testing everywhere; do fault/ as one pass; closes facets `d2nxe`, `1436o`, and the time-injection part of `cvtaa`).
6. **fault/ stub decision** — `yogvu` (implement real recovery or collapse to NoOp + feature-flag; don't keep 40 classes around `markHealthy()`).
7. **Vacuous tests** — `8sufr`, `cvtaa`, `egjwk` (these hide the bugs above).
8. **Tetree guards + docs** — `rzn79`, `dcath`; remaining significants and the doc fix `8cpq6` as cleanup.

---

## 4. Verification conventions

- Per-module test: `mvn test -pl lucien -Dtest=ClassName` (distributed beads: `-pl lucien-distributed`; ESVT/render: `-pl render`).
- IDE problem check: `mcp jetbrains.findProjectProblems` (no compile needed when IDE is up).
- Two-pass review gate per CLAUDE.md before closing any behavioural bead: `code-review-expert` then `substantive-critic`.
- Clock-discipline regression guard: `grep -rn "System.currentTimeMillis()\|System.nanoTime()" lucien/src/main lucien-distributed/src/main` should trend to zero as `mt7hi` lands.

---

## 5. Provenance

Per-subsystem T2 critique entries (project `Luciferase`): `critique-lucien-core-abstraction-2026-06-02`, `critique-tetree-subsystem-2026-06-02`, `critique-prism-sfc-2026-06-02`, `critique-collision-*-2026-06-02`, `critique-forest-ghost-distributed-2026-06-02`, `critique-balancing-fault-2026-06-02`, `critique-dsoc-*-2026-06-02`, and the pyramid critique entry. T1 scratch tags: `critique-core`, `critique-tetree`, `critique-pyramid`, `critique-prism-sfc`, `critique-collision`, `critique-forest-ghost`, `critique-balancing`, `critique-dsoc-util`.
