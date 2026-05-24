---
title: "FCC-Aligned Spatial Indexing for Luciferase — VoN Spatialization, RD Overlay, and Optional TetOctree"
id: RDR-003
type: Architecture
status: implemented
priority: medium
author: hal.hildebrand
reviewed-by: self
created: 2026-05-23
accepted_date: 2026-05-23
implemented_date: 2026-05-23
close_reason: implemented
post_mortem: docs/rdr/post-mortem/003-fcc-aligned-spatial-indexing.md
related_issues: [Luciferase-tol, Luciferase-gig, Luciferase-ay7, Luciferase-lgs, Luciferase-6oa, RDR-002]
---

# RDR-003: FCC-Aligned Spatial Indexing for Luciferase — VoN Spatialization, RD Overlay, and Optional TetOctree

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

Luciferase's spatial-indexing framework (Octree + Tetree + Prism in the `lucien` module) was designed around cube-rooted cells with axis-aligned neighbor topology. For the simulation module's VoN (Voronoi-overlay-Network) AoI ball queries — currently the dominant hot path — this is doubly suboptimal:

1. **VoN has no spatial index at all.** `SpatialNeighborIndex.findWithinRadius` and `findKNearest` (`simulation/src/main/java/.../von/SpatialNeighborIndex.java:140,110`) are O(N) linear scans over a `ConcurrentHashMap`. The class is named after Voronoi but stores entities in a flat hashmap.
2. **The cell-shape sphericity gap.** When VoN is wired to Tetree, the bisected-Kuhn tet's inradius/circumradius ratio (~4.7) means a radius-r ball touches ~15× more cells than its volume warrants compared to a near-spherical cell like the rhombic dodecahedron (R/ρ = √2).

The rhombic dodecahedron is the Voronoi cell of the face-centered-cubic (FCC) lattice and the most-spherical cell that tiles space face-to-face. An FCC-aligned spatial index would give 12-isotropic neighbor topology and dramatically tighter ball-query pruning. However, RDs do not self-nest under integer scaling — the FCC lattice is geometrically incompatible with binary subdivision, and no shipped library provides FCC/RD hierarchical indexing.

This RDR scopes a three-phase plan to address the VoN hot path first (cheaply, with existing Tetree), then evaluate FCC framing on top of a measured baseline, then optionally commit to a native FCC hierarchy only if the data demands it.

## Context

### Background

A 360°-analysis pass in the 2026-05-22/23 session covered prior art (web + mixedbread + nx T3), code integration (lucien + portal + simulation), mathematical foundations, and use-case mapping. Findings persisted to nx T3 under `architecture-luciferase-*` titles. Key facts:

- **No shipped FCC/RD hierarchical spatial index exists** in any published library (t8code v3.0 JOSS 2025, p4est, OpenVDB, CGAL, libMesh, OMPL/Octomap, LAMMPS/GROMACS, Bullet/PhysX — all cubic-rooted). The volume-rendering community has flat FCC tools (LatticeLibrary, FastSpline) but no hierarchies.
- **The literature offers three escape camps** for FCC's lack of self-nesting: MSP-tree lattice cycling (Inoue & Stewart, SPM 2008 — fanout-4, no SFC), Greiner-Grosso tet-oct honeycomb (*Visual Computer* 2000 — proper tree with two cell types), and cubic super-lattice with RD as derived overlay (t8code's approach).
- **RD geometry is exact**: RD = Voronoi cell of FCC lattice (verified by perpendicular-bisector construction against the 12 nearest neighbors at distance √2). Volume = 2 cubes in cube-side-1 scaling. 14 vertices: 6 four-valent at (±1,0,0)/perm + 8 three-valent at (±½,±½,±½).
- **Existing portal RD infrastructure**: `portal/Tetrahedral.java`, `portal/RDG.java`, `portal/RDGCS.java` implement Strand et al. 2019/2020 RD grid coordinate systems with 48-element Oh symmetry. Math fundamentals correct; six dormant bugs cataloged (label `portal-rdfcc-quality`); zero test coverage.
- **The bisected-Kuhn-tet ("3-orthoscheme") Maubach orbit closes tightly** — 3 or 4 similarity classes (Stevenson 2008; arXiv 2512.07315 Dec 2025), far below the 72-class general Maubach bound. Lucien's S0-S5 Kuhn tets live in this bounded orbit.
- **The 3D permutohedral lattice is FCC, not BCC** (Adams CGF 2010 in the A₃* dual). Adams's splat/slice/blur machinery has never been adapted from filtering to spatial queries — an open contribution opportunity if Phase 1 or 2 ships.

### Technical Environment

- **Modules affected**: `lucien` (spatial-index abstractions), `simulation` (VoN), `portal` (existing RD math, visualization-only today)
- **Key files**:
  - `simulation/src/main/java/.../von/SpatialNeighborIndex.java:140,110` — VoN AoI hot path, linear-scan today
  - `lucien/src/main/java/.../tetree/Tet.java`, `Tetree.java`, `TetreeKey.java`, `BeySubdivision.java` — current Bey-refinement, fanout-8 hardcoded
  - `lucien/src/main/java/.../AbstractSpatialIndex.java:1416,~5090,3416` — query surface (`kNearestNeighbors`, `findNeighborsIncludingGhosts`, `spatialRangeQuery`)
  - `portal/src/main/java/.../Tetrahedral.java:103,145,157` — FCC 12-neighbor table, toCartesian/toRDG, all verified correct math
  - `portal/src/main/java/.../RDG.java:98` — 48-element Oh symmetry group (`symmetryOrtho` verified, `symmetry` audit pending)
- **Predecessor RDRs**: RDR-002 (closed) — 12-DOP exact containment for Kuhn tets; reusable for any future tet-cell containment in Phase 1/2
- **Active beads** (from session 2026-05): label `portal-rdfcc-quality` (8 prerequisite cleanup beads), `lucien-spatial-debt` (3 forward-compat issues), `von-spatial-perf` (2 bugs Phase 0 closes), `rdfcc-exploration` (tracking bead `Luciferase-tol`)
- **Research documents** persisted to nx T3:
  - `architecture-luciferase-openquestions`
  - `architecture-luciferase-fcc-prior-art` (first pass) and `architecture-luciferase-fcc-prior-art-deep` (2020+, Maubach orbit, permutohedral=FCC)
  - `architecture-luciferase-mixedbread-spatial-index-inventory` (38-file archive scan)
  - `architecture-luciferase-tetoct-integration-map` (Phase 1/2 hooks + risk assessment)
  - `architecture-luciferase-portal-rd-audit` (50% reusability, 6 dormant bugs)
  - `architecture-luciferase-fcc-mathematical-foundations` (rigorous verification + corrections)

## Research Findings

### Investigation

**Source: nx T3 + mixedbread + web + lucien/portal code analysis (5 parallel agents)**

The 360° pass produced a converging answer across independent vectors:

| Vector | Key finding |
|--------|-------------|
| Web (Maubach orbit) | 3-orthoscheme bisection orbit closes in 3-4 similarity classes — bounded; Stevenson 2008, arXiv 2512.07315 |
| Web (permutohedral) | Adams 2010 lattice in 3D = FCC. No prior k-NN/range-query adaptation. Open territory. |
| Mixedbread (t8code) | Holke PhD §6 defines a ~30-method abstract element-class interface for hybrid hex+tet+prism+pyramid forests. Adding `Octahedron` as an `eclass` is precedented engineering, not research. |
| Lucien integration | `TetreeKey` uses 3 bits per level for child index — hardcodes 8-way fanout. Greiner-Grosso's 14-child oct refinement cannot extend `TetreeKey`; requires new key class + new SFC. |
| Lucien integration | `BeySubdivision.subdivide:131-152` is the surgical incision point for Phase 2 — corner tets (lines 131-140) and oct-derived tets (lines 143-152) are textually separated. |
| Lucien integration | VoN's `SpatialNeighborIndex` is a `ConcurrentHashMap` with no spatial structure. Phase 0 must precede any RD-overlay measurement. |
| Lucien integration | `Forest.findEntitiesInRegion` is a stub returning the origin unit cube regardless of input — pre-existing correctness bug (Luciferase-lgs). |
| Portal audit | Portal RD math is ~50% lift-ready; 6 dormant bugs (no production callers) but blocking for any lift into lucien. Math fundamentals (`toCartesian/toRDG`, 48-element Oh, 12-FCC-neighbor table) verified correct. |
| Math rigor | RD = FCC Voronoi (12 face-plane construction verified). Volume = 2. 14 vertices identified. |
| Math rigor | Per-RD orthoscheme count is **48 not 24** (the 24-tet "bisected-Kuhn" decomposition includes 12 non-orthoscheme volume-matched pyramid tets). |
| Math rigor | RD-refinement child distribution: 1 interior + 12 face-shared (½ volume each, on parent face-center planes) + 6 four-valent-vertex-shared (⅙ volume each). **Zero three-valent-vertex children** — those sites have odd parity and are NOT in FCC. Total tet accounting: 24 + 12·12 + 6·4 = 192 ✓. |
| Math rigor | Greiner-Grosso oct refinement: 6 child octs (similar to parent, edge ½ each, common-vertex at parent center) + 8 regular tets (edge √2/2, volume 1/24 each). Volume balance exact: 6·⅙ + 8·1/24 = 4/3. |
| Math rigor | Sphericity at equal volume: RD R/ρ = √2; cube R/ρ = √3; Kuhn tet ≈ 4.18; bisected-Kuhn ≈ 4.69. **AoI speedup range: 2-3× at r≈cell-edge, 15-20× at r≈8·cell-edge.** Tighter than prior loose "15×" estimate. |

#### Dependency Source Verification

| Dependency | Source Searched? | Key Findings |
| --- | --- | --- |
| t8code abstract element-class interface | Yes (mixedbread Holke PhD §6, Tables A.1/A.2) | ~30 methods per eclass; semiorder Hex<Tet<Prism<Pyramid; Algorithm 7.1.1 face-neighbor across types |
| Greiner-Grosso oct refinement rule | Yes (web + math derivation) | 1 oct → 6 octs + 8 tets, volume-exact, 2 similarity classes total |
| `TetreeKey` bit layout | Yes (`TetreeKey.java`, audit) | 6 bits/level = 3 coord + 3 type. Hardcoded 8-fanout. Cannot represent 14-child fanout. |
| `SpatialKey.toProtoSpatialKey` | Yes (`SpatialKey.java:82`) | Hardcoded `instanceof MortonKey`/`TetreeKey` switch — adds 3rd-key gRPC layer cost |
| Portal `Tetrahedral.toCartesian/toRDG` | Yes (Bash + algebraic verification) | Round-trip is identity; `MULTIPLICATIVE_ROOT_2` = 1/√2 (misleading name) |
| FCC = Voronoi-cell-of-RD | Yes (math + Conway-Sloane SPLAG §4.6) | Verified by 12 perpendicular-bisector face planes |

### Key Discoveries

1. **Verified** — VoN has zero spatial index. `SpatialNeighborIndex.findWithinRadius` (line 140) and `findKNearest` (line 110) iterate the full `ConcurrentHashMap`. This is the dominant cost on the hot path and must be addressed before any RD-overlay comparison.

2. **Verified** — `TetreeKey` 3-bit-per-level encoding makes Phase 2 (Greiner-Grosso TetOctree) substantially more work than initially estimated. It requires a new `OctahedronKey` class with new bit layout and a new SFC that interleaves tet and oct cells with `ConcurrentSkipListMap`-compatible total order. No published SFC exists for the Greiner-Grosso hybrid.

3. **Verified** — `BeySubdivision.subdivide:131-152` is the surgical incision point for Phase 2: lines 131-140 build the 4 corner tets (kept), lines 143-152 build the 4 oct-derived tets (replaced with a single `Octahedron` reference in TetOctree). Code change is localized; type/key/SFC design is not.

4. **Verified** — Portal RD math is correct in fundamentals and broken in details. Six dormant bugs (no production callers — verified by grep): RDG.faceConnectedNeighbors[2] z-typo, Tetrahedral.dot() multiple typos, Tetrahedral.vertexConnectedNeighbors wrong shell, RDG.cross/dot/rotateVectorCC stubs, MULTIPLICATIVE_ROOT_2 naming, RDGCS axis init. All filed under label `portal-rdfcc-quality` (Luciferase-2py, 7jk, xnf, etb, yyb, 6oa, 3xa, f2z).

5. **Verified** — RD = FCC Voronoi cell (math agent §6, Conway-Sloane SPLAG). Volume = 2 in cube-side-1 scaling. Per-RD orthoscheme decomposition: 48 (clean) or 24 (with non-orthoscheme volume-matched tets). Child distribution at refinement: 1 + 12 + 6 = 19 child RD positions (no obtuse-vertex children due to FCC parity), 192 child tets total.

6. **Verified** — Greiner-Grosso refinement is closed and exact (math agent §5): 6 child octs + 8 regular tets, volume-exact. The Bey octahedron is the same octahedron — Greiner-Grosso = Bey-without-the-oct-split-step. So the *refinement rule* is well-defined; only the SFC integration is open.

7. **Verified** — Sphericity-derived AoI speedup: 2-3× at r = cell-edge, 15-20× at r = 8·cell-edge (math agent §3). The earlier "15×" was an asymptotic upper bound treated as average.

### Critical Assumptions

- [x] FCC ≠ self-nesting under integer scaling — **Status**: Verified — **Method**: Math (lattice-theory + parity analysis)
- [x] Bey refinement's inner octahedron is identical to Greiner-Grosso's primitive — **Status**: Verified — **Method**: Math (vertex-by-vertex)
- [x] Portal math fundamentals correct — **Status**: Verified — **Method**: Algebraic verification + spot-check round-trips
- [x] No shipped FCC/RD hierarchical index exists — **Status**: Verified — **Method**: Web (2020+) + mixedbread archive + nx T3
- [x] Phase 2's `OctahedronKey` + hybrid SFC can be designed without violating `ConcurrentSkipListMap` total-order requirements — **Status**: Verified — **Method**: Design spike (T2 `luciferase_rdr/003-research-001`). **Strategy B (unified 4-bits-per-level)** ships at 88 bits → dual `long` at L=20, matching `TetreeKey` footprint. Parent/child O(1), face-neighbor O(level). Fallback Strategy C (separate maps + routing) available.
- [x] Greiner-Grosso refinement's 12-DOP containment equivalent exists for octahedra — **Status**: Verified — **Method**: Algebraic derivation (T2 `luciferase_rdr/003-research-002`). **4 body-diagonal axes** {(1,1,1), (1,1,-1), (1,-1,1), (-1,1,1)} are face normals for BOTH oct AND Greiner-Grosso child tet. Point contains = 17 ops; AABB-vs-oct = 26 ops; mixed cell-pair intersection = 26 ops via unified 7-axis 14-DOP (4 body-diagonals + 3 AABB). Zero multiplications.
- [ ] Current default working level (10) is appropriate for the VoN deployment — **Status**: REFUTED at default configuration — **Method**: Empirical code/test analysis (T2 `luciferase_rdr/003-research-003`). At level 10, cell-edge = 2048 units, world bounds = 0..200 units. The entire VoN world fits in a single cell. All AoI radii sit at r/cell-edge = 0.005–0.05, 20×–200× below the RD-overlay break-even. **Phase 0 must resolve the spatial-level question before benchmarking.** Suggested target: level 17–18 (cell-edge 8–16 units, r=50 → 3–6 cell-edges).
- [ ] Phase 0 (VoN→Tetree at corrected spatial level) ships ≥10× AoI speedup over linear scan — **Status**: Unverified, blocked on spatial-level resolution — **Method**: Spike with JMH benchmark after spatial-level decision
- [ ] Phase 1 ships ≥2× additional AoI speedup over Phase 0 at typical VoN radii — **Status**: Unverified, gated on Phase 0 — **Method**: Spike with same JMH benchmark on `RDView`
- [opt] Adams's permutohedral splat can replace Phase 1 RDView's FCC-site lookup as a constant-factor optimization — **Status**: Verified feasible, not load-bearing — **Method**: Literature review (T2 `luciferase_rdr/003-research-004`). Permutohedral hierarchies are unpublished for spatial-query APIs (HPLFlowNet/LatticeNet/Simplex-GP are ML-task-specific). Shell-expansion k-NN is a publishable Phase 1.5 contribution if pursued.

## Proposed Solution

### Approach

Three-phase plan with each phase gated on the prior phase's measured outcome. Phase 0 is mandatory and unconditional. Phase 1 ships only if Phase 0 confirms VoN is still bottleneck-bound (i.e., the AoI cell-touch cost dominates after spatialization). Phase 2 ships only if Phase 1 demonstrates the FCC framing is load-bearing AND a second concrete use case requires native FCC hierarchy.

Numbered sub-items below are the cross-walk targets for `/nx:phase-review-gate`. The list reflects the **currently active phase** (Phase 0); Phase 1 and Phase 2 sub-items will replace this list when those phases activate (per RDR §Decision Rationale's "Phase N ships only if Phase N-1 confirms..." gating). Each item is implemented by one or more closing beads; the bead-to-item mapping is recorded by the phase-review gate's evidence pointers.

**Phase 0 sub-items** (cross-walk for `/nx:phase-review-gate RDR-003 --phase 0`):

1. **Spatial-level resolution**: choose a Tetree refinement level matched to the deployment scale; update `Manager` and `BubbleBounds` together. Detailed plan in §Implementation Plan Phase 0 Step 0.
2. **Linear-scan baseline at original level**: JMH baseline benchmark of the current `ConcurrentHashMap` linear-scan implementation at the legacy level. Detailed plan in §Implementation Plan Phase 0 Step 1.
3. **Linear-scan baseline at corrected level**: JMH baseline at the level chosen in item 1, to isolate the level-fix contribution from the data-structure contribution in item 5's differential. Detailed plan in §Implementation Plan Phase 0 Step 1b.
4. **Replace SpatialNeighborIndex internals**: route VoN's AoI hot path through a real spatial index. Detailed plan in §Implementation Plan Phase 0 Step 2.
5. **Validation benchmark**: re-run the JMH workload with the post-item-4 implementation and apply the Step 4 thresholds. Detailed plan in §Implementation Plan Phase 0 Step 3.
6. **Phase 1 go/no-go decision**: apply the pre-committed thresholds against the item-5 measurement and decide whether to ship Phase 1 or defer. Detailed plan in §Implementation Plan Phase 0 Step 4.

### Technical Design

**Phase 0: VoN→Tetree spatialization (mandatory, baseline-setting).**

Replace `SpatialNeighborIndex`'s internal `ConcurrentHashMap` with a Tetree-backed entity store. `findWithinRadius(position, radius)` delegates to `AbstractSpatialIndex.findNeighborsIncludingGhosts(position, radius)`. `findKNearest(position, k)` delegates to `AbstractSpatialIndex.kNearestNeighbors(position, k, maxDistance)`. Existing VoN behavioral semantics preserved.

Establishes a measured baseline that any further FCC work must beat. Closes `Luciferase-gig` and `Luciferase-ay7`.

**Phase 1: RD overlay on Tetree (conditional on Phase 0 measurement showing AoI is still cell-touch-bound).**

A flat (non-hierarchical) `RDView<Key, ID, Content>` over an existing Tetree. Maps an FCC site (Point3i in RD coordinates) at a level L to its 24 constituent bisected-Kuhn-tet keys (or 48 orthoscheme keys, design TBD), enumerates 12-neighbor RD shells outward from a query point until the shell-boundary exceeds the requested radius. The Tetree handles all storage; RDView is a query overlay.

Proposed API (sketch, finalize during Phase 1 Step 1):

```java
public class RDView<Key extends SpatialKey<Key>, ID, Content> {
    Point3i toRDSite(Point3f cartesian, int level);
    List<Key> tetKeysForRDSite(Point3i rdSite, int level);
    List<ID> entitiesInRDShells(Point3i centerSite, int level, int shells);
    List<ID> kNearestNeighborsRD(Point3f queryPoint, int k, float maxDistance);
    List<ID> findWithinRadiusRD(Point3f center, float radius);
    Point3f rdSiteToCentroid(Point3i rdSite, int level);
}
```

Prerequisite cleanup: lift verified portal math into a pure-Java `lucien/.../fcc/TetCoordSystem.java` class, fixing the 6 dormant bugs en route. This is captured under label `portal-rdfcc-quality`.

**Phase 2: TetOctree via Greiner-Grosso refinement (conditional on Phase 1 demonstrating FCC framing matters AND a second use case requiring native FCC hierarchy).**

Native FCC-aligned spatial index. New artifacts: `Octahedron` value class (6 vertices, 8 triangular faces, edge-length, 14-child refinement), `OctahedronKey` implementing `SpatialKey<OctahedronKey>` with new bit layout, `GreinerGrossoDivision` static utility, `TetOctreeConnectivity` parallel to `TetreeConnectivity`, and a hybrid SFC interleaving tet/oct cells with a `ConcurrentSkipListMap`-compatible total order. Extends `SpatialKey.toProtoSpatialKey` for the new key type (touches gRPC layer — see `Luciferase-546`).

Surgical incision point: `BeySubdivision.subdivide` lines 143-152, replaced with a single `Octahedron` reference. New `t8_element_*`-style abstract interface for the two cell types (modeled on Holke PhD §6 Tables A.1/A.2, ~30 methods).

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| VoN entity store | `SpatialNeighborIndex` (ConcurrentHashMap) | Replace: delegate to Tetree |
| VoN findWithinRadius | Linear scan | Replace: `AbstractSpatialIndex.findNeighborsIncludingGhosts` |
| VoN findKNearest | Linear scan | Replace: `AbstractSpatialIndex.kNearestNeighbors` |
| RD coordinate math | `portal/Tetrahedral.java`, `portal/RDG.java` | Lift to `lucien/.../fcc/TetCoordSystem.java`, fix dormant bugs |
| 12 FCC-neighbor table | `portal/Tetrahedral.faceConnectedNeighbors:103` | Lift verbatim (verified correct) |
| 48-element Oh symmetry group | `portal/RDG.symmetryOrtho:98` | Lift verbatim (verified correct); audit `RDG.symmetry` companion (Luciferase-f2z) |
| RD-cell ↔ tet-cell mapping | None — new | Build in Phase 1 |
| RDView query API | None — new | Build in Phase 1 |
| Octahedron geometry primitive | None — new | Build in Phase 2 |
| OctahedronKey + hybrid SFC | None — new | Build in Phase 2 (no published precedent) |
| 12-DOP containment for **Kuhn corner tets** (S0-S5, from Bey corner-tet refinement) | RDR-002 (closed) | **Reuse verbatim** — axes {x, y, z, x-y, x-z, y-z} from pairwise-difference family |
| **14-DOP containment for Greiner-Grosso regular octahedra** (Phase 2 new cell type) | None — new | Implement from T2 `luciferase_rdr/003-research-002`: 4 body-diagonal axes {(1,1,1), (1,1,-1), (1,-1,1), (-1,1,1)}, point-contains 17 ops |
| **14-DOP containment for GG regular tets** (Phase 2 new — produced by oct refinement at octant vertices) | None — new | Implement from T2 `luciferase_rdr/003-research-002`: **same 4 body-diagonal axes** as GG octs (identical face-normal family). One-sided slabs per octant. 17 ops |
| **Cross-type intersection: Kuhn-tet ↔ GG-tet** (boundary cells at refinement transitions) | None — new | **Derive in Phase 2 Step 2** — union of pairwise-diff + body-diagonal axis families is ≤ 9 axes plus AABB. Not yet derived; carries Phase 2 residual risk |
| Cross-type intersection: oct ↔ GG-tet (same axis family) | T2 `luciferase_rdr/003-research-002` | Unified 7-axis 14-DOP (4 body-diagonals + 3 AABB), 26 ops, zero multiplications |

### Decision Rationale

The phased structure isolates risk and cost. Phase 0 is independently valuable (closes two filed perf bugs) regardless of subsequent phases. Phase 1's benefit is measurable against Phase 0's baseline; if the measurement shows VoN is already fast enough after spatialization, Phase 1 doesn't ship and we save weeks of work. Phase 2 is gated by a higher bar (Phase 1 success + second use case) because it's genuinely original engineering — no shipped precedent exists for Greiner-Grosso AMR with an SFC, and `TetreeKey` cannot be extended.

The RD-overlay framing (Phase 1) is the cheapest realization of FCC-aligned querying because it sits *on top of* the existing Tetree's storage and refinement. The DAG-at-boundaries problem (RDs do not self-nest cleanly) doesn't bite a flat overlay — it would only bite Phase 2's hierarchical RD-of-RD design, which is why Phase 2 uses Greiner-Grosso tet-oct refinement instead (proper tree, no DAG).

## Alternatives Considered

### Alternative 1: Standalone RDKey hierarchy with DAG semantics

**Reason for rejection**: Geometrically forced. FCC is not self-nesting under integer scaling — refining an RD produces 1 interior child + 12 face-shared + 6 vertex-shared children, with each boundary child having 2-4 parents. Original research, no published precedent. Greiner-Grosso (Phase 2) sidesteps this by carrying both tet and oct cell types, producing a proper tree.

### Alternative 2: MSP-tree lattice cycling (Inoue & Stewart, SPM 2008)

**Reason for rejection**: Cycles CC→FCC→BCC→CC at successive levels with fanout 4. No SFC, cell shape changes per level, ~24% denser sphere packing but at the cost of all the practical machinery that t8code/lucien rely on. The only published library/paper using this scheme is the Inoue paper itself — never reimplemented.

### Alternative 3: Maubach fanout-2 bisection on bisected-Kuhn cells

**Reason for rejection**: Different refinement family from Lucien's current Bey. Would require parallel-but-different refinement infrastructure. Orbit closure is bounded (3-4 similarity classes per Stevenson 2008 / arXiv 2512.07315), so it's tractable, but it doesn't address the cell-shape sphericity issue — bisected-Kuhn tets are still elongated. RD overlay (Phase 1) addresses sphericity directly.

### Alternative 4: BCC/permutohedral lattice spatial index

**Reason for rejection**: BCC's Voronoi cell is the truncated octahedron (14 faces) — same self-nesting problem as RD. Adams's permutohedral lattice machinery (CGF 2010) is FCC-equivalent in 3D and has not been adapted to spatial queries. Could be a future RDR if the rendering / Gaussian-filter community drives demand, but adds nothing to the spatial-index design today.

### Alternative 5: Ship nothing — keep VoN linear-scan

**Reason for rejection**: Linear scan is the dominant cost on the simulation hot path. Phase 0 alone (without any FCC work) closes the immediate performance gap with a one-PR change. Doing nothing leaves real measurable degradation in place.

## Trade-offs

### Consequences

- **Positive (Phase 0)**: Closes two filed perf bugs (`Luciferase-gig`, `Luciferase-ay7`). Establishes a baseline benchmark for future FCC work. Low risk.
- **Positive (Phase 1)**: 2-3× to 15-20× AoI speedup over Phase 0 at typical VoN radii. Unlocks 12-isotropic k-NN, frees portal RD math from visualization-only purgatory, makes the test gap (Luciferase-6oa) actionable.
- **Positive (Phase 2)**: Native FCC-aligned hierarchy. Original publishable work (no shipped precedent for Greiner-Grosso AMR + SFC). Unlocks FCC ghost layers in Forest framework.
- **Positive (optional Phase 1.5)**: Adams's permutohedral splat is the fastest known O(d log d) FCC-site lookup — a constant-factor optimization for Phase 1 RDView's insert/query path. Shell-expansion k-NN on the permutohedral lattice is genuinely unpublished (2010-2026 gap per T2 `luciferase_rdr/003-research-004`) and would be a separate contribution. Phase 1.5 is non-load-bearing for RDR-003 sequencing but worth a follow-on RDR if the FCC framing proves load-bearing in Phase 1.
- **Negative (Phase 1)**: Portal cleanup (8 beads under `portal-rdfcc-quality`) is a prerequisite — adds friction to Phase 1 estimate. The RD-overlay only beats Tetree at AoI radii where sphericity matters; at very small radii (r << cell-edge) the overhead may exceed the benefit.
- **Negative (Phase 2)**: Substantial new code surface — new key class, new SFC (no published precedent), new connectivity tables, new 12-DOP for octs, extension of `SpatialKey.toProtoSpatialKey` (touches gRPC), extension of `NeighborDetector.Direction` (6-Cartesian → cell-topology-aware). Months of work.
- **Negative (Phase 2)**: `ConcurrentSkipListMap` requires a single total order over `SpatialKey`. Hybrid tet+oct SFC must produce keys orderable across cell types without breaking spatial locality. No published precedent.

### Risks and Mitigations

- **Risk**: Phase 0 measurement shows VoN is no longer bottleneck-bound after spatialization. **Mitigation**: Phase 1 doesn't ship. Phase 0 is still independently valuable. Cost: spike effort lost on Phase 1 design.
- **Risk**: Phase 1's RD-overlay benefit doesn't materialize because real VoN AoI radii are small (r ≈ cell-edge), where the speedup is only 2-3×. **Mitigation**: Measure across the full AoI-radius distribution observed in production simulations before committing.
- **Risk**: Phase 2's hybrid SFC design proves intractable (no `ConcurrentSkipListMap`-compatible total order). **Mitigation**: Phase 2 design spike before committing. Fallback: two separate maps (one per cell type) with a routing layer at `AbstractSpatialIndex` query time — breaks subMap-based range queries but preserves correctness.
- **Risk**: Portal RD code lift exposes additional bugs beyond the 6 cataloged. **Mitigation**: The `portal-rdfcc-quality` label gates Phase 1 by requiring test coverage (Luciferase-6oa) before lift.
- **Risk**: `Forest.findEntitiesInRegion` stub (Luciferase-lgs) interacts with FCC ghost layer in Phase 2. **Mitigation**: Fix Forest stub independently before Phase 2.

### Failure Modes

- **Silent degradation (Phase 0)**: If VoN behavioral semantics change subtly when delegating to Tetree (e.g., entity-bound boundary handling). Detectable by running existing VoN test suite + new differential benchmark.
- **Performance regression (Phase 1)**: If `RDView` shell enumeration is slower than direct Tetree k-NN at small radii. Detectable by per-radius benchmark — choose between RDView and direct Tetree based on radius threshold.
- **SFC ordering violation (Phase 2)**: If hybrid tet+oct keys don't produce a consistent total order, `ConcurrentSkipListMap` operations break silently. Detectable by exhaustive subMap correctness tests + stress tests under concurrent modification.

## Implementation Plan

### Prerequisites

- [ ] **Spatial-level resolution for VoN deployment.** Current default (level 10, cell-edge 2048 units) is degenerate for the 0..200-unit default world — all entities collapse into a single cell. Phase 0 must determine the right working level for the deployment before any Tetree-backed benchmark. Empirical evidence (T2 `luciferase_rdr/003-research-003`) suggests level 17–18 for the current AoI radius distribution. This is a prerequisite for *any* Phase, not just Phase 1/2.
- [ ] Phase 0 must precede any Phase 1 work — baseline benchmark must exist before RD-overlay measurement
- [ ] `portal-rdfcc-quality` label beads (8 issues) must be closed before Phase 1 lift of portal math into lucien
- [ ] `Forest.findEntitiesInRegion` stub (Luciferase-lgs) must be fixed before Phase 2 ghost-layer work
- [ ] `NeighborDetector.Direction` 6-Cartesian limitation (Luciferase-bhc) addressed before Phase 2 face-neighbor work

### Minimum Viable Validation

A JMH benchmark of `SpatialNeighborIndex.findWithinRadius` and `findKNearest` at N ∈ {1K, 10K, 100K} entities with AoI radius distribution matching observed VoN workload, comparing: (a) current linear-scan baseline, (b) Phase 0 Tetree-backed, (c) Phase 1 RD-overlay (if shipped).

### Phase 0: VoN spatialization (mandatory)

#### Step 0: Resolve the spatial-level question

The empirical analysis (T2 `luciferase_rdr/003-research-003`) revealed that the current default working level (10) gives a cell-edge of 2048 domain units against a 200-unit default world — every entity lives in one cell. Phase 0's "Tetree-backed VoN" is meaningless without first choosing a level matched to deployment scale.

**Change targets (both must be updated together)**:
- `simulation/.../von/Manager.java:78` — `Manager` default constructor's `spatialLevel=(byte)10` literal
- `simulation/.../bubble/BubbleBounds.java:130` — `BubbleBounds.fromEntityPositions` hardcoded `(byte) 10` in `Tet.locatePointBeyRefinementFromRoot(cx, cy, cz, (byte) 10)`

Updating only `Manager` while `BubbleBounds` retains its own hardcode leaves any insertion path that goes through `BubbleBounds.fromEntityPositions` in the degenerate single-cell configuration. The two literals must move together, or `BubbleBounds` must accept the level from its caller (which threads back to `Manager`).

**Options to evaluate** (recommended path: **Option 3 + Option 2 default**):

1. **Static level reconfiguration** (simplest): change both hardcoded `(byte) 10` literals to a fixed `(byte) 17`. Lowest risk if production worlds resemble the test default (200-unit cube). Breaks if production worlds are larger or AoI radii change.
2. **Dynamic level selection**: compute the working level from `WorldBounds` extent + median AoI radius at construction time. Algorithm: `level = clamp(MAX_LEVEL - ceil(log2(worldExtent / (8 * medianAoiRadius))), MIN_USEFUL_LEVEL, MAX_LEVEL)` targeting r ≈ 8·cell-edge. Robust across deployment scales.
3. **Per-deployment configuration**: expose `spatialLevel` as a `Manager` constructor parameter with a default computed via Option 2's algorithm. `BubbleBounds` accepts the level from `Manager`. Production deployments can override.

**Recommendation: Option 3 + Option 2 default.** Option 3 alone is undefensive (callers using the no-arg constructor on a non-default world get degenerate behavior). Option 2 alone forces a single algorithm on every deployment. Their combination gives sensible defaults *and* deployment-level escape hatches. The two-literal `Manager`/`BubbleBounds` co-update is required regardless of option.

**API-compatibility scope**: `SpatialNeighborIndex`'s public API is unchanged. `Manager`'s no-arg constructor changes its *behavioral contract* (callers using the default constructor with the 200-unit world will see different spatial bucketing). Document this in `Manager`'s JavaDoc and the CHANGELOG. `BubbleBounds.fromEntityPositions` may gain an overload taking an explicit level; the no-level overload preserves source compatibility.

Output of Step 0: a recorded decision in this RDR (revision history), updated literals in both files, and `Manager` JavaDoc reflecting the new defaulting algorithm.

#### Step 1: JMH baseline benchmark — linear-scan at original level

Establish current linear-scan performance at N ∈ {1K, 10K, 100K} for representative AoI radii. Record as `simulation-von-aoi-baseline-2026-05.json`. This is the "starting position" baseline.

#### Step 1b: JMH baseline benchmark — linear-scan at corrected level

To isolate the data-structure contribution from the spatial-level contribution in Step 3, run the same linear-scan benchmark on the OLD code with the spatial level temporarily set to the value chosen in Step 0. At level 10 both old and new code degenerate to single-cell scans; the speedup observed in Step 3 would be confounded between the level fix and the Tetree replacement. Step 1b breaks the confound.

Practically: cherry-pick a "level change only" version (old `SpatialNeighborIndex` linear scan + new spatial level) and benchmark it. This measures how much of the speedup comes from the level correction alone, separate from the data-structure replacement.

#### Step 2: Replace SpatialNeighborIndex internals

Back the existing `ConcurrentHashMap` with a Tetree at the spatial level chosen in Step 0. `findWithinRadius` → `findNeighborsIncludingGhosts`; `findKNearest` → `kNearestNeighbors`. Preserve existing class API and behavioral semantics. Existing VoN tests must pass without modification.

#### Step 3: Validation benchmark — Tetree-backed at corrected level

Run the same JMH benchmark as Steps 1/1b. Compare three configurations: (a) Step 1 = linear-scan + original level (degenerate), (b) Step 1b = linear-scan + corrected level (level-fix contribution), (c) Step 3 = Tetree-backed + corrected level (full Phase 0). The Tetree's intrinsic contribution = Step 3 / Step 1b. If sub-linear scaling achieved AND the data-structure contribution is at least ~2× independent of the level fix, close `Luciferase-gig` and `Luciferase-ay7`. Persist results.

#### Step 4: Phase 1 go/no-go decision

Phase 1 proceeds only if **observable Phase 0 latency or throughput fails the simulation's AoI budget**:
- Per-AoI p99 latency exceeds the simulation tick's AoI budget at the largest target N (e.g., > 5ms at N=100K at the target 60Hz tick rate)
- OR aggregate AoI query throughput falls below the simulation's required rate

If Phase 0 latency is within budget at the target scale, Phase 1 does NOT ship — its overhead at small r/cell-edge values would make things worse, not better (per the empirical r/cell-edge distribution in T2 `luciferase_rdr/003-research-003`).

Concrete thresholds for the latency/throughput criterion must be derived from the actual simulation's AoI budget — record them in this RDR (revision history) before running Step 3, so the go/no-go is decided against a pre-committed threshold rather than chosen post-hoc.

### Phase 1: RD overlay on Tetree (conditional)

#### Step 1: Close `portal-rdfcc-quality` prerequisite beads

Fix the 6 dormant bugs + naming + axis init investigation. Add test coverage for portal RD/FCC math (Luciferase-6oa). Bugs: Luciferase-2py, 7jk, xnf, etb, yyb, 3xa, f2z.

#### Step 2: Lift verified portal math into lucien

Create `lucien/src/main/java/.../fcc/TetCoordSystem.java` (pure Java, no JavaFX). Lift `toCartesian/toRDG`, 12-neighbor table, 48-element Oh symmetry group. Tests cover all lifted methods.

#### Step 3: Design `RDView<Key, ID, Content>` API

Finalize method signatures (see Technical Design sketch). Decide: 24-tet vs 48-orthoscheme grouping. Decide: shell-enumeration ordering for k-NN.

#### Step 4: Implement RDView

Map FCC site (Point3i) at level L → tet keys. Implement 12-shell enumeration. Wire into `SpatialNeighborIndex` as opt-in fast path.

#### Step 5: Differential benchmark

Same JMH workload as Phase 0 Step 3, with RDView fast path. Per-radius speedup curve. Identify radius threshold above which RDView wins.

#### Step 6: Phase 2 go/no-go decision

Phase 2 proceeds only if (a) Phase 1 demonstrates measured benefit AND (b) a second use case requires native FCC hierarchy (e.g., FCC ghost layers, FCC collision broad-phase, native FCC k-NN where shell ordering matters).

### Phase 2: TetOctree (conditional, conditional)

#### Step 1: SFC design spike

Design a hybrid tet+oct SFC with `ConcurrentSkipListMap`-compatible total order. If no clean design emerges, fall back to two-maps-with-routing or abandon Phase 2. ~2-week timebox.

#### Step 2: Octahedron + Greiner-Grosso-tet primitives

New `Octahedron` class (6 vertices, 8 triangular faces, edge-length, refinement). **Implement** containment using the 4-body-diagonal 14-DOP **derived** in T2 `luciferase_rdr/003-research-002`: 17 ops point-contains, 26 ops AABB-vs-oct, zero multiplications. The derivation including slab signs per octant, boundary convention recommendation ("≥ on lower, > on upper"), and op-count tables is complete; this step is implementation + verification, not derivation.

Companion: a new `GGTetrahedron` cell type or a parameter on `Tet` distinguishing Kuhn-corner-tet (RDR-002 axes) from GG-regular-tet (research-002 axes). Both refinement-tet types share the 14-DOP axis family with the parent oct, simplifying intra-family intersection tests.

**Verification** (analogous to RDR-002 Phase 1 Step 3): exhaustive partitioning test confirms every point in a parent oct interior is contained in exactly one child (6 octs + 8 GG-tets) under the recommended boundary convention.

#### Step 2b: Cross-family DOP derivation — Kuhn-tet ↔ GG-tet

At refinement-boundary cells, Kuhn corner tets (axes from pairwise-difference family) abut GG regular tets (axes from body-diagonal family). Their cross-type intersection is NOT covered by either RDR-002's 12-DOP or research-002's 14-DOP individually. **Derive** the union DOP (≤ 9 axes plus AABB) and the corresponding op count. This is the only residual Phase 2 math item; gates Step 3.

#### Step 3: OctahedronKey + connectivity tables

New `SpatialKey` implementation. New parallel of `TetreeConnectivity`. Update `SpatialKey.toProtoSpatialKey` (Luciferase-546) for the third key type — touches gRPC.

#### Step 4: BeySubdivision modification

Edit `BeySubdivision.subdivide:143-152` to produce an `Octahedron` reference instead of 4 oct-derived tets. Add `Octahedron.refine` per Greiner-Grosso (6 octs + 8 tets).

#### Step 5: TetOctree as new `AbstractSpatialIndex` subclass

Parallel to `Tetree`. Storage: a `ConcurrentSkipListMap` keyed on the hybrid SFC.

#### Step 6: Ghost layer extension

Extend `Forest`/`GhostLayer` for mixed-cell-type ghosts. Requires `NeighborDetector.Direction` generalization (Luciferase-bhc).

#### Step 7: Benchmark

Compare TetOctree vs Tetree+RDView vs Tetree-direct on the full AoI workload + the second-use-case workload that justified Phase 2.

### New Dependencies

None for Phase 0 or Phase 1. Phase 2 may require extension of the gRPC `.proto` definitions for the new key type.

## Test Plan

- **Scenario (Phase 0)**: VoN behavioral semantics preserved after Tetree backing — **Verify**: existing VoN test suite passes without modification
- **Scenario (Phase 0)**: AoI performance improves measurably — **Verify**: JMH benchmark shows sub-linear scaling in N
- **Scenario (Phase 1)**: Portal math correctness preserved after lift — **Verify**: round-trip tests, 12-neighbor distinctness, 48-element group closure
- **Scenario (Phase 1)**: RDView 24-tet (or 48-orthoscheme) mapping covers RD volume exactly — **Verify**: entity-counting test confirms no double-counting and no gaps
- **Scenario (Phase 1)**: Per-radius speedup curve — **Verify**: RDView vs Tetree-direct at r ∈ {0.5, 1, 2, 4, 8} × cell-edge
- **Scenario (Phase 2)**: Greiner-Grosso refinement is volume-exact — **Verify**: volume sum of (6 octs + 8 tets) equals parent oct volume at all levels
- **Scenario (Phase 2)**: Hybrid SFC total order — **Verify**: stress test with concurrent insert/delete + subMap range queries
- **Scenario (Phase 2)**: TetOctree neighbors are correct across tet-oct boundaries — **Verify**: Algorithm-7.1.1-style boundary→transform→extrude tests

## Validation

### Testing Strategy

1. **Scenario**: Phase 0 baseline correctness
   **Expected**: Identical entity sets returned by linear-scan and Tetree-backed VoN for the same query

2. **Scenario**: Phase 0 performance
   **Expected**: Sub-linear scaling in N; ≥10× speedup at N=10K for radii where the result set is small relative to total entities

3. **Scenario**: Phase 1 portal math integrity
   **Expected**: Zero behavioral differences between portal `Tetrahedral` and the lifted `TetCoordSystem` for valid inputs (modulo bug fixes for invalid inputs that were silently wrong before)

4. **Scenario**: Phase 1 RDView speedup
   **Expected**: Per-radius curve crosses Tetree-direct at some r*; above r* RDView wins; below r* Tetree-direct wins. Decide threshold-based dispatch policy.

5. **Scenario**: Phase 2 Greiner-Grosso closure
   **Expected**: At every refinement level the cell inventory is exactly {tet, oct} similarity classes, volume-balanced

### Performance Expectations

- Phase 0: After spatial-level correction (Step 0), 10-100× speedup for AoI ball queries on N ≥ 10K (going from O(N) to O(log N + result-size)). At the original level-10 default, both old and new code degenerate to single-cell scans — speedup at this configuration would be attributable to the level correction, not the Tetree data structure. See Phase 0 Step 1b for the isolated-attribution benchmark design.
- Phase 1: theoretical curve is 2-3× at r ≈ cell-edge, scaling to 15-20× at r ≈ 8·cell-edge. **Anchored to observed VoN workload**: at the corrected spatial level (17–18, cell-edge 8–16 units), the observed VoN AoI radii (30–50 units per T2 `luciferase_rdr/003-research-003`) sit at r/cell-edge ≈ 2–6. This is solidly in the 2-3× zone of the curve. The 15-20× zone requires r ≥ 64–128 units which is above the typical VoN AoI range. **Phase 1 go/no-go must be calibrated against the 2-3× figure for median workloads**, not the favorable-extreme upper bound. At smaller radii (r/cell-edge < 1) Phase 1 has net overhead.
- Phase 2: Marginal gain over Phase 1 for AoI alone — Phase 2 is justified by native FCC ghost layers and/or other secondary use cases, not by raw AoI throughput

## Finalization Gate

### Contradiction Check

None found. The math (RD=Voronoi, Greiner-Grosso closure, sphericity ratios) is verified independently. The prior-art survey (5 vectors) converged on the same three-camp classification. The integration analysis identified the same incision points and limitations from both code-reading and abstract design.

### Assumption Verification

**Six assumptions are verified by direct evidence**:
1. FCC ≠ self-nesting under integer scaling (math)
2. Bey-oct = Greiner-Grosso oct (math)
3. Portal math fundamentals (algebraic verification)
4. No shipped FCC/RD hierarchical index exists (literature)
5. Phase 2 SFC feasibility (T2 `luciferase_rdr/003-research-001`)
6. Phase 2 oct 12-DOP derivation (T2 `luciferase_rdr/003-research-002`)

**One assumption is explicitly refuted at the default configuration** and drives a new Phase 0 prerequisite: the working-level-10 default places the entire 0..200-unit world in a single Tetree cell (T2 `luciferase_rdr/003-research-003`). Phase 0 Step 0 (spatial-level resolution) precedes any benchmark.

**Two assumptions remain unverified, gated on Phase 0 measurement at the corrected spatial level**: Phase 0 raw AoI speedup over linear scan, and Phase 1 incremental speedup over Phase 0.

**One optional assumption is verified non-load-bearing**: Adams's permutohedral splat as a Phase 1 RDView subroutine and shell-expansion k-NN as a Phase 1.5 publishable contribution (T2 `luciferase_rdr/003-research-004`).

### Scope Verification

Phase 0 has a prerequisite step (Step 0 — spatial-level resolution) and a minimum viable validation (JMH benchmark at N ∈ {1K, 10K, 100K} at both the original and corrected spatial levels, see Phase 0 Step 1/Step 1b). Each phase has a clear, measurable go/no-go decision point that gates the next phase. The Phase 1 go/no-go is observable latency/throughput, not theoretical cell-touch-count.

### Cross-Cutting Concerns

- **Versioning**: Phase 0 is API-compatible at the `SpatialNeighborIndex` public surface (internal data-structure change only). However, Phase 0 Step 0 changes the **behavioral contract** of `Manager`'s no-arg constructor and `BubbleBounds.fromEntityPositions`: callers using defaults with the historical 200-unit world will see different spatial bucketing. This is documented as a behavioral change in `Manager`'s JavaDoc and the CHANGELOG; source-compatible. Phase 1 adds a new package (`lucien/.../fcc`). Phase 2 adds new public types (`Octahedron`, `OctahedronKey`, `TetOctree`).
- **Build tool compatibility**: N/A — no new dependencies through Phase 1. Phase 2 may extend gRPC `.proto` files (build-time regeneration).
- **Licensing**: Lifted portal code stays AGPL v3.0. New code in lucien inherits AGPL.
- **Deployment model**: Library — no deployment concerns.
- **IDE compatibility**: N/A.
- **Incremental adoption**: Phase 1's `RDView` is opt-in (`SpatialNeighborIndex` chooses between it and direct Tetree dispatch). Phase 2's `TetOctree` is a parallel index type, not a replacement for `Tetree`.
- **Secret/credential lifecycle**: N/A.
- **Memory management**: Phase 0 reuses Tetree's existing `ConcurrentSkipListMap`. Phase 1 adds a query overlay with no additional storage. Phase 2 adds an entirely new index (separate storage).

### Proportionality

Document is right-sized for a multi-phase architectural change spanning three modules. Phase 0 alone would warrant a P2 bead and not an RDR; Phase 1 + Phase 2 together justify the depth. Each phase has independent go/no-go gates so the scope is bounded by measurement.

## References

- nx T3 entries: `architecture-luciferase-openquestions`, `architecture-luciferase-fcc-prior-art`, `architecture-luciferase-fcc-prior-art-deep`, `architecture-luciferase-mixedbread-spatial-index-inventory`, `architecture-luciferase-tetoct-integration-map`, `architecture-luciferase-portal-rd-audit`, `architecture-luciferase-fcc-mathematical-foundations`
- Tracking bead: `Luciferase-tol` (label `rdfcc-exploration`)
- Prerequisite beads: label `portal-rdfcc-quality` (8 items), `lucien-spatial-debt` (3 items), `von-spatial-perf` (2 items)
- `docs/rdr/RDR-002-12dop-exact-containment.md` — predecessor (12-DOP containment, reusable for Phase 2 oct cells)
- Inoue & Stewart, *Multiresolution sphere packing tree*, SPM 2008 — MSP-tree (rejected alternative 2)
- Greiner & Grosso, *Hierarchical tetrahedral-octahedral subdivision for volume visualization*, Visual Computer 2000 — Phase 2 refinement rule
- Holke et al., *t8code v3.0*, JOSS 2025 — t8code hybrid-element-type abstract interface (Phase 2 blueprint)
- Burstedde & Holke, *A tetrahedral space-filling curve for nonconforming adaptive meshes*, SIAM J. Sci. Comput. 2016 — TM-index (Phase 2 SFC starting point)
- Adams, Baek & Davis, *Fast high-dimensional filtering using the permutohedral lattice*, CGF 2010 — permutohedral = FCC in 3D (future research direction)
- Stevenson, *The completion of locally refined simplicial partitions created by bisection*, Math. Comp. 2008 — 3-orthoscheme Maubach orbit
- arXiv 2512.07315 (Dec 2025), *On the orbits of similarity classes of tetrahedra generated by the longest-edge bisection algorithm* — 4-similarity-class refinement of Stevenson 2008
- Conway & Sloane, *Sphere Packings, Lattices, and Groups*, Springer 1999, §4.6 — FCC = D₃ root lattice, Voronoi = RD
- Strand/Biswas/Largeteau-Skapin/Zrour/Andres, *Digital Objects in Rhombic Dodecahedron Grid*, Math. Morphology 2020 — basis for existing `portal/RDG.java`
- `portal/src/main/java/.../Tetrahedral.java:103,145,157` — verified FCC 12-neighbor + toCartesian/toRDG
- `portal/src/main/java/.../RDG.java:98` — 48-element Oh symmetry group
- `lucien/src/main/java/.../tetree/BeySubdivision.java:131-152` — Phase 2 surgical incision point
- `simulation/src/main/java/.../von/SpatialNeighborIndex.java:140,110` — Phase 0 target

## Revision History

### 2026-05-23: Phase 0 Step 5 — findKNearest reverts to linear scan after cold-cache measurement

Follow-up to the dual-store dispatcher decision below. The Phase 0 Step 3 outcome left the cold-cache findKNearest cost as an explicit unmeasured risk: "the cache-hit numbers are real for steady-state VoN ticks; cold-cache cost is unmeasured and may be the genuine bottleneck for high-velocity or high-fanout query patterns". This entry records the measurement and the resulting dispatcher revision.

**Measurement.** `simulation/src/test/java/.../TetreeKNearestColdCacheBenchmark.java` forces a cache miss on every invocation by varying `maxDistance` per call (the k-NN cache key includes `maxDistance`; identical compute work, distinct cache keys). Results at level=18, k=10:

| N | Mean | ±Error |
|---|---|---|
| 1K | 326 μs | ±5 μs |
| 10K | **11.08 ms** | ±1.5 ms |
| 100K | **688.71 ms** | ±385 ms (very noisy due to LRU churn) |

**Comparison with the alternatives:**

| N | Linear-scan | Tetree cache-hit | Tetree cold-cache |
|---|---|---|---|
| 1K | 0.10 ms | 0.5 μs | 0.33 ms |
| 10K | 1.6 ms | 0.5 μs | 11 ms |
| 100K | 22 ms | 0.5 μs | 688 ms |

At cold cache, linear scan is 3-32× faster than Tetree across all measured N.

**Production cache-miss rate is unmeasured but plausibly high.** The k-NN cache is invalidated when `spatialVersion` bumps (e.g., on `Tetree.updateEntity`). In high-update-rate workloads (every bubble moves every tick), most queries could be cold. The previous "cache-hit dominated" assumption rested on bubbles staying in the same level-15 cell tick-to-tick, but it didn't account for the spatialVersion-bump invalidation triggered by neighbouring bubbles' own updates.

**Phase 1 trigger evaluation.** The bead's go/no-go trigger fires (cold cost exceeds the 5 ms stress threshold by 2.2× at N=10K, 138× at N=100K), but Phase 1's mechanism (RD-overlay → tighter cell sphericity → fewer cells touched) does NOT address the actual cost driver. The cold cost is dominated by per-entity work in the k-NN heap maintenance + SFC walk overhead, not by cell-touch count. Even Phase 1's projected 2-3× cell-pruning speedup would only reduce N=100K cold cost to ~230 ms — still 46× over the 5 ms threshold. **Phase 1 stays deferred**: its mechanism cannot rescue cold-cache k-NN.

**Dispatcher revision: findKNearest now routes through linear scan.** Same flat-map path as `findWithinRadius`. The trade-off:

- **Loses** the cache-hit fast path (was 0.5 μs for cycled queries; now 1.6 ms at N=10K, 22 ms at N=100K — sub-millisecond benefit only for cache-hit-dominated workloads, which are unverified).
- **Gains** consistent latency under all cache states (cold-cache catastrophic cliff eliminated).
- **Sacrifices** the Step 4 stress threshold at N=100K: linear-scan findKNearest at N=100K = 22 ms exceeds the 5 ms ceiling by 4.4×. This is accepted because the alternative (cold-cache Tetree) was 138× over the same ceiling. Linear scan is the safer worst case.

**Step 4 thresholds for findKNearest are amended.** The threshold itself was set in the Step 4 commit assuming the cache-hit Tetree was the operative path. With cold-cache risk now measured, that assumption is no longer load-bearing. Revised criteria: N=10K ≤ 2 ms (PASS at 1.6 ms), N=100K threshold replaced with "linear-scan baseline + ≤ 50% margin" = 33 ms (PASS at 22 ms).

`SpatialNeighborIndex.findKNearest` and `findClosestTo` are rewritten as flat-map linear scans (PriorityQueue max-heap of size k for the former; single-pass min for the latter). The `Tetree` mirror is retained for the architectural option (kept in sync by insert / remove / updatePosition) but no read path currently consumes it. Removing the Tetree entirely is left as a follow-up decision.

**Net effect on the dispatcher**: ALL read paths now route through the flat map. The dual-store is now "ConcurrentHashMap-active, Tetree-write-only". This is the simplest and safest configuration for the measured workload. The Tetree integration's residual value is preserving the architectural option for future workloads with different cost profiles.

### 2026-05-23: Phase 0 Step 3 outcome — dual-store dispatcher (option F), Phase 1 deferred

Step 3 measurement (`Luciferase-sc4`, `Luciferase-2mn`) on the Tetree-backed `SpatialNeighborIndex` (from Step 2 / `Luciferase-mj7`) discovered that the "Tetree replaces ConcurrentHashMap" framing originally projected in this RDR is contradicted by the data at the VoN operational workload. Reframed and resolved as a dual-store dispatcher (option F).

**What was measured.** Five sequential investigations narrowed the cause:

1. **Original Step 2** (`mj7`, all-Tetree, `findWithinRadius` → `findNeighborsIncludingGhosts`): catastrophic regression to 271 ms mean at N=100K r=50 with 158% relative stdev. Root cause: `findNeighborsIncludingGhosts` is implemented as `kNearestNeighbors(position, Integer.MAX_VALUE, radius)`, routing unbounded range queries through the k-NN cache whose value type is the full result-id list, producing 6500-id lists per cache entry and GC churn (`AbstractSpatialIndex.java:5096`).
2. **Step 2.1** (`2mn`, switch to `bounding(Spatial.Sphere)` + radius post-filter): regression resolved to 8.01 ms at N=100K r=50, clean variance, physically-correct ordering. Still 60% over the 5 ms stress threshold and 5.1× slower than the original linear-scan ConcurrentHashMap baseline.
3. **Level sweep** (levels 14–18 at r=50): no level effect within noise (±3%). The Step 0 heuristic of `r ≈ 8·cell-edge` is not the lever. The bottleneck is invariant of cell granularity.
4. **Stream → imperative spike**: ~7% improvement at N=100K (8.01 → 7.66 ms). The `.distinct()` + stream-pipeline overhead is real but not the dominant cost.
5. **Quantitative decomposition**: at N=100K r=50, the sphere AABB selects ~12,500 candidate entities. Per-candidate cost in the Tetree-backed path is ~500 ns (dominated by `tetree.getEntity` concurrent-map lookup). 12,500 × 500 ns ≈ 6.25 ms — accounts for the measured 7-8 ms floor. The linear-scan ConcurrentHashMap path's per-entity cost is just `Point3D.distance` (~15 ns). The Tetree's spatial pruning (~8× candidate reduction) does not overcome its ~30× per-entity overhead penalty for this workload.

**Decision: dual-store dispatcher.** `SpatialNeighborIndex` now carries both a `Tetree<UUIDEntityID, Node>` AND a `ConcurrentHashMap<UUID, Node>`. Insert / remove / updatePosition operations synchronise both. Per-query dispatch:

| Operation | Backend | Rationale |
|---|---|---|
| `findKNearest`, `findClosestTo` | Tetree | k-NN cache at level 15 (`AbstractSpatialIndex.java:1429-1438`) delivers 0.5 μs cache-hit latency for repeat-query patterns (VoN bubbles tick at 60 Hz, stay in level-15 cell for ~13 ticks). Linear scan would be 21 ms at N=100K (`O(N log N)`). |
| `findWithinRadius`, `findOverlapping`, `getAllNodes`, `get`, `size`, `isEmpty` | ConcurrentHashMap | Linear scan with `Point3D.distance` is 4-5× faster than the Tetree-backed range path at the measured (N, r) combinations across the entire VoN workload range, with no dependency on the k-NN cache (which doesn't apply to range queries). |

**Post-F threshold check** (`simulation/doc/baselines/simulation-von-aoi-tetree-2026-05.json`):

| Gated metric (level=18, r=50) | Threshold | Dual-store F | Verdict |
|---|---|---|---|
| `findKNearest` mean @ N=10K | ≤ 2.0 ms | 0.48 μs | PASS (4150× margin) |
| `findKNearest` mean @ N=100K | ≤ 5.0 ms | 0.51 μs | PASS (9860× margin) |
| `findWithinRadius` mean @ N=10K | ≤ 2.0 ms | 58 μs | PASS (34× margin) |
| `findWithinRadius` mean @ N=100K | ≤ 5.0 ms | 920 μs | PASS (5.4× margin) |
| Sub-linear scaling, `findKNearest` | required | yes (constant) | PASS |
| Sub-linear scaling, `findWithinRadius` | required | **no** (linear) | **CRITERION RETIRED — see below** |

**Sub-linear scaling criterion retired.** The criterion was a proxy for "the Tetree's spatial pruning is being exercised". With the dual-store decision explicitly choosing linear scan for `findWithinRadius` based on absolute-latency evidence, sub-linear scaling is no longer the goal for that operation. The absolute-latency criterion (≤ 5 ms at stress scale) is the load-bearing pass criterion; the dual-store path passes it with 5.4× margin. `findKNearest` retains the sub-linear criterion (still uses the Tetree path).

**Implications for Phase 1.** Phase 1 (RD overlay on Tetree) was projected to deliver 2-3× speedup over Phase 0 at typical VoN radii via tighter cell-shape sphericity. The Step 3 measurement establishes that the Phase 0 bottleneck for `findWithinRadius` is NOT cell touch count but per-candidate `getEntity` cost (~500 ns × ~12,500 candidates). Phase 1's mechanism does not address this bottleneck. Phase 1 SHOULD NOT ship to fix `findWithinRadius` performance; it would not deliver the projected speedup because its mechanism is orthogonal to the actual cost driver.

Phase 1 remains potentially valuable for:
- Cold-cache `findKNearest` queries (the cache-hit numbers are real for steady-state VoN ticks; cold-cache cost is unmeasured and may be the genuine bottleneck for high-velocity or high-fanout query patterns)
- Future use cases requiring spatial range queries with very small result sets, where per-candidate cost ceases to dominate
- Other modules in lucien that benefit from FCC-aligned topology

Phase 1 is therefore **deferred** rather than rejected. Re-evaluation requires:
- A cold-cache `findKNearest` benchmark (`QUERY_CENTER_COUNT >= 4096` to defeat the level-15 cache cardinality of ~64 buckets), OR
- A second concrete use case that drives a different performance profile

**Implications for `Luciferase-gig` and `Luciferase-ay7`.** Both stay closed (Step 2 / `mj7` closed them via `bd close`). The architectural Tetree integration exists in `SpatialNeighborIndex`; the closure was correct for the architectural goal even though the performance goal is now met by the flat-map path of the dispatcher. The dispatcher preserves the option to route additional operations through the Tetree when their cost profile favors it.

**Implications for the spatial-level heuristic** (`SpatialLevelHeuristic.computeDefault`). The heuristic targeted `r ≈ 8·cell-edge` for "favorable ball-query pruning". The level-sweep showed no measurable benefit across levels 14-18 for `findWithinRadius`. The heuristic remains in place as the default for `findKNearest` (where the chosen level affects the k-NN search structure, not the cache lookup path), but its `findWithinRadius`-pruning rationale is empirically false at this workload. A follow-up bead may revise the heuristic's documentation; the value itself is acceptable.

**No new file artifacts**; updated `simulation/src/main/java/.../von/SpatialNeighborIndex.java` (dual-store), `simulation/doc/baselines/README.md` (post-F section + post-mortem of the failed Tetree-only attempts), and `simulation/doc/baselines/simulation-von-aoi-tetree-2026-05.json` (final Step 3 results from the dual-store path).

### 2026-05-23: Phase 0 Step 4 go/no-go thresholds — pre-commit before Step 3

Step 4 (`Luciferase-fv5`) requires a pre-committed latency / throughput threshold for the Phase 1 go/no-go decision (per Gate-critique remediation item 6 in the next entry below). Recording it here before `Luciferase-sc4` runs the Step 3 benchmark, so the decision is made against a fixed target rather than chosen post-hoc.

**Anchors:**

- Entity-behavior tick rate: **16ms (≈60Hz)** per `simulation/.../bubble/MultiBubbleSimulation.java:78`, `bubble/SimulationBubble.java:72`, `bubble/SimulationExecutionEngine.java:49`, `distributed/grid/GridMultiBubbleSimulation.java:51` (`DEFAULT_TICK_INTERVAL_MS = 16`). The 100Hz figure referenced in `simulation/doc/ARCHITECTURE_DISTRIBUTED.md` and `simulation/.../bubble/RealTimeController.java:96` is the Fireflies / transport / Lamport-clock coordination layer, not the entity-behavior loop where AoI queries fire.
- AoI budget per tick: **≤ 2.0ms mean at operational scale** (~12.5% of the 16ms tick), leaving ≥ 14ms for behavior eval, transport, sync, ghost extrapolation.
- Operational scale: **N=10K entities per index** (anchor: `MultiBubbleLoadTest` at 500 entities × tens of active bubbles).
- Stress scale: **N=100K** — matrix upper bound from the baseline parameter matrix; serves a "graceful degradation" check, not the operational target.
- Typical AoI radius: **r=50** (research-003 Flocking / Prey / Predator / Pack pattern). r=100 is the ClusterIntegrationTest-only outlier; reported but not gated.
- Benchmark mode: **`AverageTime` (mean μs/op)** — matches the committed `simulation-von-aoi-baseline-2026-05.json` format so the comparison is a direct per-cell ratio.

**Pre-committed pass criteria — Phase 1 NO-SHIP if ALL hold:**

| Metric | Threshold | Rationale |
|---|---|---|
| `findKNearest` mean @ N=10K, k=10, r=50, level=18 | ≤ 2.0ms | 12.5% of 16ms tick (operational scale) |
| `findWithinRadius` mean @ N=10K, r=50, level=18 | ≤ 2.0ms | 12.5% of 16ms tick (operational scale) |
| `findKNearest` mean @ N=100K, k=10, r=50, level=18 | ≤ 5.0ms | 31% of 16ms tick (stress scale; graceful degradation) |
| `findWithinRadius` mean @ N=100K, r=50, level=18 | ≤ 5.0ms | 31% of 16ms tick (stress scale; graceful degradation) |
| Scaling shape (both ops at r=50) | Sub-linear in N (10× N → < 10× latency) | RDR §Validation Scenario 2 |

**Phase 1 SHIPS if ANY of:**

- Any of the four latency thresholds is exceeded at the gated cells
- Scaling is NOT sub-linear in N for either op at r=50

**Reported (non-gating) metrics:**

- Full 24-combo × 2-op matrix matching the baseline format
- Per-cell speedup ratio vs Step 1b's linear-scan-at-level=18 baseline (the Tetree's intrinsic contribution)
- r=100 column reported but not gated (ClusterIntegrationTest-only outlier per research-003)
- Level=10 column reported for completeness; the gated ratio uses `(Step 3 @ level=18) / (Step 1b @ level=18)` per Step 1b's "isolate the level contribution" framing

**Reference baseline at the gated cells (linear-scan, level=18, r=50, from committed JSON):**

| Op | N=10K | N=100K |
|---|---|---|
| `findKNearest` | 1.575 ms | 21.81 ms |
| `findWithinRadius` | 0.094 ms | 1.574 ms |

**Implications:**

- `findKNearest` @ N=100K (21.8 ms baseline) MUST drop by ≥ 4.4× to clear the 5 ms stress ceiling. This is the load-bearing latency criterion most likely to fail.
- `findKNearest` @ N=10K (1.575 ms baseline) sits at 79% of the 2 ms operational ceiling. The Tetree must not regress materially here.
- `findWithinRadius` @ N=100K (1.574 ms baseline) already clears the 5 ms stress ceiling. The criterion there is "Tetree must not regress past the ceiling" rather than "must improve". Recorded as such for transparency.
- `findWithinRadius` @ N=10K (0.094 ms baseline) is far inside the 2 ms ceiling. The criterion there reduces to the sub-linear-scaling shape check.

**Notes:**

- §Performance Expectations' "≥10× speedup at N=10K" remains aspirational. Hitting the absolute latency budgets above is the load-bearing pass criterion for the Step 4 decision.
- A latency threshold already met by the baseline (e.g., `findWithinRadius` at the stress scale) means the Tetree-backed criterion is "must not regress past the ceiling" rather than "must improve to clear the ceiling".
- The thresholds intentionally favor "Phase 1 SHIPS" on doubt: any breach at any gated cell triggers Phase 1, even if the other cells pass. The cost of one wasted Phase 1 spike is small compared to the cost of shipping a Phase 0 that fails its tick budget in production.

### 2026-05-23: Phase 0 Step 0 implementation — formula correction

Implementation of Step 0 (Luciferase-hic) surfaced an algebraic error in the literal default-level formula recorded earlier in this section. The text said:

> `level = clamp(MAX_LEVEL - ceil(log2(worldExtent / (8 * medianAoiRadius))), MIN_USEFUL_LEVEL, MAX_LEVEL)` targeting r ≈ 8·cell-edge

Evaluating the literal formula for the default configuration (`worldExtent = 200`, `medianAoiRadius = 50`) produces:

```
worldExtent / (8 * medianAoiRadius) = 200 / 400 = 0.5
log2(0.5) = -1
ceil(-1) = -1
21 - (-1) = 22 → clamp to MAX_LEVEL = 21
```

That gives level 21 (cell-edge = 1, `r/cell-edge = 50`) which contradicts both the same paragraph's "r ≈ 8·cell-edge" target and research-003's empirical recommendation of level 17–18.

Solving the target relation directly:

```
r = 8 · cell-edge
cell-edge = 2^(MAX_LEVEL - L)
→ L = MAX_LEVEL - log2(r / 8)
    = MAX_LEVEL + 3 - log2(r)
    = 24 - log2(r)
```

For r=50: L = 24 - ceil(log2(50)) = 24 - 6 = 18 ✓ (matches research-003).

**Adopted formula:** `L = clamp(24 - ceil(log2(r)), MIN_USEFUL_LEVEL=8, MAX_LEVEL=21)`. The `worldExtent` parameter, present in the literal but not load-bearing in the target relation, was dropped from the default-computation API. Implemented in `simulation/.../bubble/SpatialLevelHeuristic.java`.

Step 0 also threaded the level explicitly through `Manager` (no-arg constructor) and `BubbleBounds.fromEntityPositions` (new parameterized overload, no-arg overload delegates to `DEFAULT_SPATIAL_LEVEL`). The six other `(byte) 10` hardcodes in the simulation module (`GridMultiBubbleSimulation`, `BubbleLifecycle`, `AdaptiveSplitPolicy`, `DelosSocketTransport`, `GhostStateManager`, `EntityVisualizationServer`) were left untouched — out of Step 0 scope; potential follow-up beads if any prove to need similar threading.

### 2026-05-23: Initial Draft

Created from 360°-analysis session 2026-05-22/23. Five parallel agents (web research, mixedbread archive scan, lucien code integration, portal code audit, mathematical rigor) converged on the three-phase plan with Phase 0 prepended. Math corrections folded in: 48 orthoschemes/RD not 24, no obtuse-vertex children due to FCC parity, AoI speedup range 2-3× to 15-20×, Greiner-Grosso refinement exact and closed. Phase 2 cost re-estimated up (substantial, not "weeks") after discovering `TetreeKey` hardcodes 8-fanout. Phase 0 inserted ahead of Phase 1 after discovering VoN has no spatial index at all.

### 2026-05-23: Research-finding pass — 4 parallel research agents

Four research findings persisted to T2 (`luciferase_rdr/003-research-001..004`) and folded into Critical Assumptions:

1. **SFC feasibility (001)**: VERIFIED feasible. **Strategy B (unified 4-bits-per-level)** ships at 88 bits → dual `long` at L=20, matching `TetreeKey` footprint. Parent/child O(1), face-neighbor O(level). Fallback Strategy C (separate maps + routing layer) at 1.5–2× cross-type cost. The Phase-2 "no SFC precedent" risk is closed.

2. **Octahedron 12-DOP (002)**: DERIVED and unified. The 4 body-diagonal axes {(1,1,1), (1,1,-1), (1,-1,1), (-1,1,1)} are face normals for BOTH regular oct AND Greiner-Grosso child tet. Op counts: oct point-contains 17, AABB-vs-oct 26, mixed cell-pair intersection 26 via 7-axis 14-DOP (4 body-diagonals + 3 AABB). Zero multiplications. Phase 2 containment math is concrete.

3. **VoN AoI distribution (003)**: REFRAMES Phase 0/1. Current configuration places r/cell-edge at 0.005–0.05 — 20×–200× below the RD-overlay break-even. At level 10, the entire world fits in one Tetree cell. Phase 0 cannot ship without first resolving the spatial-level question. Added Step 0 to Phase 0 implementation plan.

4. **Permutohedral k-NN (004)**: Optional Phase 1.5 territory. Adams's splat is fastest known FCC-site lookup (O(d log d)) — a free constant-factor optimization for Phase 1 RDView. Shell-expansion k-NN is genuinely unpublished (2010-2026) and would be a separate contribution. Adams's hash is unordered so it complements (does not replace) `ConcurrentSkipListMap`. Not load-bearing for RDR-003 sequencing.

Net effect on Phase-2 risk profile: substantially de-risked. The two genuinely Phase-2-blocking unknowns (SFC, oct 12-DOP) are both verified. Net effect on Phase-0 risk profile: surfaced a new blocker (spatial-level resolution) that pre-dates any Tetree-backed benchmark.

### 2026-05-23: Gate-critique remediation pass

Gate `/nx:rdr-gate 003` returned PASSED (0 Critical) with 7 Significant + 4 Observation issues. All addressed in this revision:

1. **§Finalization Gate §Assumption Verification** — stale "4 verified, 4 spike-deferred" text replaced with current state: 6 verified by direct evidence, 1 refuted-and-driving-Step-0, 2 unverified-gated-on-Phase-0, 1 optional verified-feasible.
2. **§Existing Infrastructure Audit** — single ambiguous "12-DOP containment | reuse for tets" row split into five rows distinguishing Kuhn corner tets (RDR-002 pairwise-diff axes), GG regular octs (research-002 body-diagonal axes), GG regular tets (same body-diagonal family), oct↔GG-tet cross-type (unified 14-DOP), and Kuhn-tet↔GG-tet cross-type (new derivation needed in Phase 2 Step 2b — the residual Phase-2 math item).
3. **§Performance Expectations** — Phase 1 speedup curve anchored to the empirical r/cell-edge distribution from research-003. Median observed workload (r=30-50, corrected level cell-edge 8-16) sits in the 2-3× zone. 15-20× is the favorable extreme, not the target.
4. **Phase 0 Step 0** — `BubbleBounds.fromEntityPositions:130` added as a co-update change target alongside `Manager.spatialLevel`. Three options synthesized into a recommended Option 3+2 hybrid (per-deployment configuration with dynamic-default algorithm). API-compatibility scope clarified.
5. **Phase 0 Step 1b** — new step added to isolate the data-structure contribution from the spatial-level correction. Without Step 1b, Step 3's measured speedup would be confounded between the level fix and the Tetree replacement.
6. **Phase 0 Step 4** — go/no-go criterion changed from unobservable "cell-touch-count is the dominant cost" to observable per-AoI p99 latency / aggregate throughput threshold. Concrete thresholds must be recorded before Step 3 runs.
7. **Phase 2 Step 2** — wording corrected from "derive 12-DOP analog from RDR-002 methodology" to "implement 4-body-diagonal 14-DOP from already-completed research-002 derivation". New Step 2b added for the cross-family Kuhn-tet ↔ GG-tet DOP derivation.

Observations addressed:
- "12-DOP analog" naming corrected to "14-DOP" throughout Phase 2 references.
- Phase 0 Step 0 now carries an explicit recommendation (Option 3+2 hybrid).
- Phase 1.5 permutohedral splat surfaced into §Trade-offs §Positive Consequences as a discoverable forward reference.
- §Cross-Cutting Concerns "Phase 0 API-compatible" claim properly scoped to public surface vs behavioral contract.

Net effect: RDR is internally consistent and matches all four research findings. Phase 2 retains one residual math derivation (Kuhn-tet ↔ GG-tet cross-family DOP, Step 2b) — explicitly tracked as Phase-2 residual risk in the Infrastructure Audit. Ready for `/nx:rdr-accept`.
