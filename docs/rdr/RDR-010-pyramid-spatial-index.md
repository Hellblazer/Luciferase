---
title: "Pyramid Spatial Index — Close the Hybrid Hex↔Tet Partition Gap"
id: RDR-010
type: Architecture
status: draft
priority: medium
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-28
related_issues: [Luciferase-pi1, RDR-001, RDR-002, RDR-008, RDR-009]
---

# RDR-010: Pyramid Spatial Index — Close the Hybrid Hex↔Tet Partition Gap

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

Luciferase's three spatial indices — `Octree` (cubic), `Tetree` (tetrahedral S0-S5), `Prism` (triangular × linear) — leave two interlocking gaps that no current code closes:

1. **The documented t8code partition gap.** `CLAUDE.md` and `lucien/doc/TETREE_T8CODE_PARTITION_ANALYSIS.md` record that t8code-style tetrahedra fundamentally **do not tile the cube**: ~48% volume gaps and ~32% overlaps remain after the S0-S5 union. The 12-DOP exact containment work (RDR-002) is a containment-test optimization on the *tet primitives* — it does not fix the partition. `TetreeKey`'s SFC is internally consistent, but it indexes a subset that doesn't cover the cube.

2. **No hex↔tet hybrid transition primitive.** Luciferase has cubes (Octree) and tets (Tetree). It does not have an element whose role is "bridge a hex face to a tet face." Practical hybrid meshes — the toy-airplane CFD geometry the Knapp 2026 paper demonstrates at scale, and any CAD-to-FEM workflow that wants graded refinement — need such a transition element. Without one, hybrid scenes that need both shapes are forced to either (a) use only one shape (giving up the other's advantages) or (b) accept the partition gap as a domain-wide hazard.

A third, softer concern compounds these: Luciferase's three indices each have their own SFC encoding (`MortonKey`, `TetreeKey`, `PrismKey`) with no unifying theory beyond "all extend `SpatialKey`." The 2026 paper proposes the unification — a Morton-type SFC across all standard element shapes — but Luciferase is still on the per-index-bespoke side of that line.

A published, peer-reviewed, scale-validated fix exists: **Knapp/Holke/Spenke/Burstedde/Dreyer 2026 — "A Morton-Type Space-Filling Curve for Pyramid Subdivision and Hybrid Adaptive Mesh Refinement"** (arXiv:2602.20887v3). The paper introduces pyramid types 6 and 7 alongside the existing tet types 0-5, giving an **exact tiling of the cube** (Fig 3.1c: 2 pyramids per cube + 2 tet gaps, then each pyramid refines into 6 pyramid + 4 tet children). The construction is implemented in t8code and validated to ~131k MPI processes / 40·10⁹ elements on DLR's CARO cluster. **This RDR proposes pulling that construction into Luciferase as a `PyramidIndex`.**

## Context

### Background

- **Knapp 2026 paper** (catalog tumbler `1.12.7`, 162 chunks in `knowledge__dt-papers__voyage-context-3__v1`) is the bridge. The paper introduces the pyramid SFC (§3), element-level algorithms (§4), forest-level partitioning + ghost (§5), and demonstrates near-ideal scaling for all shapes including pyramids (§6) on a hybrid hex+tet+prism+pyramid mesh (§7).

- **Burstedde+Holke 2016** (catalog `1.12.8`, 195 chunks) — "A tetrahedral SFC for nonconforming adaptive meshes" SISC — is **Tetree's actual algorithmic basis** (not Bey [4] directly). Knapp 2026's pyramid construction is an extension of this paper, **not** a rewrite. Tet types 0-5 are reused verbatim.

- **Holke 2018 PhD thesis** (catalog `1.12.9`, 216 chunks) — "Scalable Algorithms for Parallel Tree-based AMR with General Element Types" — provides the SFC existence/uniqueness theorem (retrieved chunks number it **Theorem 16 / Proposition 4.17 in Chapter 4**; the handoff's "Theorem 3.5" label likely refers to the Chapter 3 cube-Morton background and should be verified on the PDF). Substantive content: the TM-index is in bijection with the cube Morton index, inheriting existence + uniqueness + total order from the cube. Knapp 2026 lifts this proof strategy from 3D tets to 6D pyramids.

- **p4est SISC paper** (catalog `1.12.10`, 201 chunks) — Burstedde+Wilcox+Ghattas 2011 — defines the forest-of-octrees Partition/Balance/Ghost contract that t8code (and Knapp 2026's §5) extends to multi-shape forests. Relevant to Luciferase's `forest`, `forest.balancing.fault`, and `forest.ghost` packages.

- **t8code v1.0 paper** (catalog `1.12.11`, 19 chunks) — the published reference implementation Luciferase is comparing against. The chunk count is sparse; cite Knapp 2026 for benchmark/algorithmic detail rather than this paper directly.

- **Holke+Knapp+Burstedde 2019** (catalog `1.14.6`, 202 chunks, arXiv:1910.10641) — "An Optimized, Parallel Computation of the Ghost Layer for Adaptive Hybrid Forest Meshes." The published scalable ghost algorithm for hybrid forest meshes — and reference [21] in Knapp 2026's bibliography. Direct dependency for Direction B distributed pyramid scope.

### Cross-citation map (post-synthesis)

The 28 foundation papers form a citation graph with **Knapp 2026 (1.12.7) as the hub**. Bey 1992 (Computing 55, BibTeX [4] in Knapp, NOT separately indexed in this collection) → Burstedde+Holke 2016 (`1.12.8`) → Holke 2018 PhD (`1.12.9`) → Knapp 2026 (`1.12.7`) → Holke+Knapp+Burstedde 2019 (`1.14.6`) is the **load-bearing algorithmic lineage**. The 28-paper synthesis (T3 `research-pyramid-sfc-foundation-cross-link-2026-05-28` + `-part2`) created 46 catalog cross-links grounded in actual paper text. Six topic clusters were identified.

#### Topic clusters (with anchor claims)

**Cluster A — Tetrahedral SFC Theory** (5 papers, hub `1.12.8`):

| Tumbler | Anchor claim |
|---|---|
| 1.12.7 Knapp 2026 | 6D Morton embedding for pyramids; O(1) parent/child/face-neighbor with `min_tet_level` |
| 1.12.8 Burstedde+Holke 2016 | TM-index: bitwise interleave (z,y,x,b); types 0-5; uniqueness proven |
| 1.12.9 Holke 2018 PhD | Theorem 16 (TM-index bijection), Prop 4.17 (uniqueness) |
| 1.12.18 Tet Morton ARM | Independent tet Morton index validation |
| 1.12.19 Tet SFC bitwise interleaving | Same primitive, alternative derivation |

**Cluster B — Tetrahedral Refinement Primitives** (9 papers, hub `1.12.12` Bey 1995): `1.12.12` Bey 1995, `1.12.29` Hebert 1994, `1.12.14`, `1.12.15` Liu-Joe, `1.12.16` Korotov, `1.12.30` Freudenthal, `1.12.31` bisection, `1.12.32` red congruent, `1.12.33` cubic tet refinement.

**Cluster C — Octree / Forest-of-Octrees Parallel Algorithms** (5 papers, hub `1.12.10` p4est): `1.12.10` p4est, `1.12.11` t8code v1.0, `1.12.34` Recursive Forests, `1.12.35` bottom-up 2:1 balance, `1.12.36` low-cost 2:1 balance.

**Cluster D — Ghost Layer & Distributed AMR** (2 papers):

| Tumbler | Anchor claim |
|---|---|
| 1.14.6 Holke+Knapp+Burstedde 2019 | First ghost algorithm for hybrid forests (tet+hex+pyramid); supersedes 1.12.34 for hybrid case |
| 1.12.26 Coarse mesh partitioning | Pre-step to ghost; cites p4est |

**Cluster E — SFC Breadth Context** (5 papers, hub `1.12.17`):

| Tumbler | Anchor claim |
|---|---|
| 1.12.17 | Constant-time neighbor finding hierarchical tet |
| 1.12.23 | Pointerless hierarchical simplicial meshes — informs KnnSearcher |
| 1.12.24 | Bounds on Morton-type SFC discontinuities |
| 1.12.25 | Sixteen SFCs for d-dim cubes and simplices |
| 1.12.28 | Parallel tet mesh generation at scale |

**Cluster F — Adjacent / Specialized** (3 papers): `1.12.20` AMR textbook, `1.12.21` Omnitrees (anisotropic), `1.12.22` pentahedra SFC.

#### Algorithmic lineage (detailed)

- **Bey 1992/1995** (Knapp ref [4], NOT indexed) — 1:8 red tet refinement (4 corner tets + 4 from central octahedron). The geometric substrate. `1.12.12` is the later parallel multilevel paper, not the original Computing 55.
- **Burstedde+Holke 2016 (`1.12.8`)** — TM-index: anchor coord + 4-bit type, bitwise-interleave `(z, y, x, b)` per level. O(1) parent, child, face-neighbor for tet types 0-5. Integrates with p4est forest.
- **Holke 2018 PhD (`1.12.9`)** — Full theoretical framework: Theorem 16 (TM-index injectivity), Prop 4.17 (within-level uniqueness), total order = SFC.
- **Knapp 2026 (`1.12.7`)** — Extends Holke 2018 framework to pyramids: (a) geometric decomposition (types 6+7, 10 children = 6 pyramid + 4 tet); (b) 6D Morton embedding `Θ: P→Q` with axes `(B², B¹, B⁰, x, y, z)`, inheriting SFC properties from 6D cube Morton; (c) O(1) algorithms via `min_tet_level` cached field; (d) forest partition via `N(ℓ) = 2·8^ℓ − 6^ℓ` per-shape weight.
- **Holke+Knapp+Burstedde 2019 (`1.14.6`)** — Hybrid ghost algorithm extending `1.12.8` to multi-shape forests. Face extrusion in d-1 dimensions. Nearly perfect parallel efficiency.

#### Citation adjacency (condensed)

```
1.12.7  Knapp  --cites+extends--> 1.12.8         (ref [8])
1.12.7  Knapp  --cites--> 1.12.9                 (ref [19]: "Analogous to the tetrahedral Morton index [19]")
1.12.7  Knapp  --cites--> 1.12.10                (ref [11])
1.12.7  Knapp  --cites--> 1.12.11                (ref [20])
1.12.7  Knapp  --cites--> 1.14.6                 (ref [21]: "Ghost algorithm [21]")
1.12.7  Knapp  --cites--> 1.12.12                (Bey refinement basis)
1.14.6  Ghost  --cites+extends--> 1.12.8         (ref [7] in ghost paper)
1.14.6  Ghost  --cites--> 1.12.9                 (ref [1] in ghost paper)
1.14.6  Ghost  --cites--> 1.12.10
1.14.6  Ghost  --supersedes--> 1.12.34           (for hybrid-forest ghost use case)
1.12.9  PhD    --extends--> 1.12.8
1.12.9  PhD    --cites--> 1.12.10
1.12.11 t8code --cites--> 1.12.8, 1.12.9, 1.12.10
1.12.8  TetSFC --cites--> 1.12.10, 1.12.12, 1.12.30

p4est (1.12.10) fan-in: cited by 1.12.7, 1.12.8, 1.12.9, 1.12.11, 1.14.6
```

Knapp 2026 has no `cites` edge to Hebert 1994 (`1.12.29`) — no `"Hebert"` or `"Symbolic Local Refinement"` in retrieved Knapp chunks.

### Technical Environment

Architecture survey output (full brief on file:line cites in T2 memory; key facts here):

- **`SpatialKey` contract** (`lucien/src/main/java/com/hellblazer/luciferase/lucien/SpatialKey.java:31-80`) is minimal: `getLevel()`, `isValid()`, `parent()`, `root()`, `toString()`, and registry-based serde. **A new `PyramidKey` requires no interface change** — register a `PyramidKeySerde` with `SpatialKeySerdeRegistry`.

- **Bit-budget map**:

| Key | bits/level | storage | encoding |
|---|---|---|---|
| `MortonKey` | 3 (xyz) | 64-bit `long` | Octree |
| `TetreeKey` (compact) | 6 (3 coord + 3 type) | 64-bit `long`, ≤level 10 | Tetree |
| `TetreeKey` (extended) | 6 | 128-bit split, ≤level 21 | Tetree |
| **`PyramidKey`** (proposed) | **6 (3 coord + 3 type)** | **128-bit at level 21 (6·21 = 126 bits)** | **Pyramid** |

The paper's pyramid encoding (6 bits/level) **matches Tetree's existing extended key budget exactly** — but the 6 bits decompose as 3 coord ‖ 3 *6D-Morton-embedding-type* bits, not 3 coord ‖ 3 *tet-type* bits. The math underneath is different (Eq 3.5–3.9: 6D Morton embedding), so PyramidKey is a sibling-of-TetreeKey, not a subclass.

- **`Tet` primitive** (`lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java`):
  - `coordinates()` @ line 1292 — S0-S5 vertex tables. **S0-S5 ↔ paper types 0-5 confirmed** by code comments (lines 1299-1329).
  - `contains12DOP(float,float,float)` @ line 1080 — 11-op Kuhn-simplex containment. **Tet-only, not directly applicable to pyramids.** Open question (below).
  - `consecutiveIndex()` @ line 1010 — 3 bits/level, O(1) via cached `LOCAL_INDICES`. The pyramid analog adds 3 type-tuple bits/level.
  - `tmIndex()` @ line 1785 — the O(level) parent-walk SFC encoding. **Paper's `min_tet_level` field is the published fix** to keep this O(1) for tets descending from pyramidal roots — would need a PyramidKey analog (see Approach §1).

- **`AbstractSpatialIndex`** (`lucien/src/main/java/com/hellblazer/luciferase/lucien/AbstractSpatialIndex.java:86`) is **concrete after RDR-008 P4**. ~157 public methods, no abstract methods to implement. A `PyramidIndex` subclasses it and initializes the standard collaborators (`entityManager`, `spatialIndex`, `ghost`, `knn`, `culler`, `core`) — same pattern as `Octree`/`Tetree`. The RDR-008 entity-lifecycle phase (P6, currently in flight) will further narrow the residual surface; **PyramidIndex implementation should sequence after RDR-008 P6 close**.

- **`Forest` is already heterogeneous** (`lucien/src/main/java/com/hellblazer/luciferase/lucien/forest/Forest.java`): `addTree(AbstractSpatialIndex<Key, ID, Content>)` accepts any concrete spatial index — Octree, Tetree, Prism, or (proposed) Pyramid. This is a significant departure from the handoff's assumption of "Forest is homogeneous today." **The missing piece** for Algorithm 5.1 (§5.1 of Knapp 2026) is a per-shape weight provider — Forest currently uses `entityCount` only (`TreeNode.java:76`), with no `N_shape(ℓ)` hook. Adding pyramid does not require homogenization → heterogenization; it requires adding the weight-pluggability.

- **`NeighborDetector`** (`lucien/src/main/java/com/hellblazer/luciferase/lucien/neighbor/NeighborDetector.java:37-141`) is shape-generic at the interface level (keyed on `SpatialKey<Key>`), but concrete implementations (`TetreeNeighborFinder`, `TetreeNeighborDetector`) are tet-specific. Pyramid integration requires a `PyramidNeighborDetector`. `GhostCoordinator.setNeighborDetector()` is the wire-in point.

- **Sequencing dependency RESOLVED 2026-05-28**: RDR-008 (god-class decomposition, epic `Luciferase-x5i`) closed today. All six phases (P0 `SpatialIndexCore` → P1 `DsocController` → P2 `GhostCoordinator` → P3 `KnnSearcher` → P4 `Culler` → P5 `CollisionEngine` → P6 `EntityLifecycleManager`) shipped. Facade shrunk 5851 → 2916 LOC (50% reduction). Residual ~159 methods + 42 protected template hooks (the post-mortem `docs/rdr/post-mortem/008-decompose-abstractspatialindex.md` documents the realized-vs-predicted delta). **PyramidIndex implementation arc can now proceed** without further wait on RDR-008.

### What this RDR does and does not do

- **In scope**: propose the architectural shape of a `PyramidIndex` (key, primitive, index, forest integration, ghost integration). Decide which candidate direction to lock at gate. NOT implement.
- **Out of scope**: pyramid 12-DOP containment derivation (open question, may be its own bead — see Approach §3), prism `N_shape(ℓ)` formula derivation (separate research item; not blocking unless prism is added to a hybrid forest scenario), Bey 1992 Computing 55 ground-truth verification of `Tet.java` vertex conventions (gap flagged in synthesis; resolve in implementation phase).

## Approach

> Candidate directions below; to be resolved by research (see [Research Findings](#research-findings)) into a locked design at gate. These numbered items are the scope contract for phase-review at implementation time.

1. **Element-level: define `PyramidKey` + `Pyramid` primitive.** New key class implementing `SpatialKey<PyramidKey>` with 128-bit storage (two `long`s — symmetric with `TetreeKey`'s extended form) encoding the 6D Morton embedding (Knapp Eq 3.5–3.7). New `Pyramid` element class analogous to `Tet`, with `coordinates()` for the pyramid vertex set (Table 3.1/3.2 anchors and child types) and a **mandatory `min_tet_level` field** (Knapp §4, Algorithm 4.1). The `min_tet_level` field is what keeps `parent()` / `child(i)` / `face_neighbor()` O(1) for tet elements descending from pyramidal roots — without it those operations are O(level). Storage cost: ~1 byte per element for `min_tet_level` (max value = MAX_LEVEL = 21).

2. **Reuse existing tet machinery.** **Tet types 1, 2, 4, 5 are unchanged from Burstedde+Holke 2016** (Knapp 2026 explicitly reuses `t8_tet_parent` / `t8_tet_child` / tet `face_neighbor` for these types). Only tet types 0 and 3 get pyramid-aware branches (Knapp §4.4, Tables 4.2/4.8). Luciferase's `Tet.coordinates()`, `contains12DOP()`, and S0-S5 subdivision **all work as-is for tets inside pyramidal trees** — no Tetree changes required. This is the "extension, not rewrite" point.

3. **Pyramid containment primitive — three sub-options to evaluate at gate.** Pyramids are NOT Kuhn simplices, so the 12-DOP exact containment (RDR-002) does not apply directly. Options:
   - **3a — Derive a pyramid analog.** Fresh DOP derivation: identify the axis-set that exactly contains a pyramid of types 6/7 (likely larger than 12-DOP — possibly 20-DOP — given the larger vertex count). Highest correctness, highest derivation cost.
   - **3b — Decompose-and-reuse.** Each pyramid = 6 sub-pyramids + 4 sub-tets at the next level. Recurse one level, then apply tet 12-DOP at the leaves. Reuses proven code; correctness inherited. ~10× containment cost per pyramid at any given level vs. 3a.
   - **3c — AABB + tet-decomposition lazy.** Fast AABB test for cheap-rejection; on hit, decompose to tets and run 12-DOP for confirmation. Best amortized cost when most queries miss. Adds branching.
   Decide at gate. Default to 3b (decompose-and-reuse) absent specific perf requirements.

4. **Forest integration: add `ShapeWeightProvider`.** Forest is already heterogeneous — the missing piece for Algorithm 5.1 is per-shape `N_shape(ℓ)`. Three concrete steps:
   - 4a. Define `interface ShapeWeightProvider { long elementCount(int level); }` (or equivalent) on `TreeMetadata` or `AbstractSpatialIndex`.
   - 4b. Provide implementations: `N_hex(ℓ) = 8^ℓ` (Octree), `N_tet(ℓ) = 8^ℓ` (Tetree), `N_pyramid(ℓ) = 2·8^ℓ − 6^ℓ` (Pyramid, Knapp Eq 5.1). `N_prism(ℓ)` is NOT in the retrieved chunks of Knapp 2026 — derive separately or defer until prism enters a hybrid forest.
   - 4c. Wire `Forest.routeQuery()` and `forest.balancing.*` to consult the per-shape weight for cumulative-offset partition (Knapp Algorithm 5.1) rather than treating every tree as 1:8.
   This change has no PyramidIndex prerequisite — it can land independently if there's appetite to fix the hex/tet weight asymmetry first.

5. **Ghost-layer integration: `PyramidNeighborDetector`.** Implement `NeighborDetector` for pyramid topology (4-face quadrilateral base + 4 triangular side faces). Cross-shape neighbor finding (pyramid↔tet at the 6/7-boundary, hex↔pyramid at the cubic-tile boundary) follows Knapp §4.3-4.4 "Construct pyramid from face" + Table 4.2. Register with `GhostCoordinator.setNeighborDetector()`. **Holke+Knapp+Burstedde 2019** (catalog `1.14.6`, 202 chunks) is the proven distributed ghost algorithm and is now searchable in T3 — Direction B can cite it directly. The prior-art at `1.12.34` (Recursive Distributed Forests of Octrees) is the pre-hybrid recursive ghost paper that Holke 2019 supersedes for hybrid meshes.

6. **Three candidate directions (the gate question).** Pick one (or hybrid) at accept:
   - **Direction A — Full element-level integration only.** Items 1, 2, 3, 5 above. Add PyramidIndex as a peer of Octree/Tetree. No Forest changes. Pyramid trees can be added to a Forest but use the default 1:8 weight (slightly inaccurate for `Forest.balancing.*`, but workable). **Smallest change**, ~2-3 RDR-008-phase-sized arc.
   - **Direction B — Element-level + Forest weight pluggability.** Direction A plus item 4. Closes Algorithm 5.1's "hybrid forest partition" loop. **Confirmed at HIGH confidence by 28-paper synthesis (2026-05-28).** ~4-5 RDR-008-phase-sized arc.
   - **Direction C — Defer-and-document.** Update `CLAUDE.md` and `TETREE_T8CODE_PARTITION_ANALYSIS.md` to cite Knapp 2026 as the documented published fix; do not implement. Revisit when a concrete use case demands hex↔tet hybrid meshes. **No longer warranted post-synthesis** — the fix is well-understood and the load-bearing references (including Holke 2019 ghost paper at 1.14.6) are now indexed.

   Recommendation post-synthesis: **Direction B**, sequenced after RDR-008 close. Direction A remains a valid fallback if `Forest.balancing.fault` audit reveals weight-pluggability is entangled. Direction C is no longer recommended given the corpus completeness.

## Research Findings

> Investigation 2026-05-28. Mined sources: 28 foundation papers in T3 collection `knowledge__dt-papers__voyage-context-3__v1` (catalog tumblers `1.12.7`-`1.12.11` Tier 1, `1.12.12`+`1.12.14`-`1.12.19`+`1.12.29` Tier 2, `1.12.20`-`1.12.36` Tier 3, plus `1.14.6` Holke 2019 ghost paper). Architecture survey via Serena symbol navigation. Full dossier in T1 scratch `b7c8bed9`. Post-synthesis cross-link report in T3 `research-pyramid-sfc-foundation-cross-link-2026-05-28` + `-part2`. Catalog map in T2 `Luciferase/dt-foundation-papers-indexed-2026-05-28`.

1. **The pyramid SFC is a 6D Morton embedding, not a flat 6-bit-per-level concat.** Knapp Eq 3.5–3.7: the pyramid index `m_P(P)` is defined to equal the cube Morton index `m_Q(Θ(P))` of an embedding `Θ: P → Q` into a 6D cube whose axes are `(B², B¹, B⁰, x, y, z)` — three "type-representing tuple" axes + three spatial axes. Per-level layout at bit position `L−(l+1)`: 3 type bits ‖ 3 coord bits. Consequence: PyramidKey ≠ "TetreeKey + 1 extra bit." The encoding math is fundamentally different (6D bijection-with-cube-Morton vs 3D), even though the per-level bit budget happens to be the same.

2. **`min_tet_level` is mandatory for O(1), not optional.** Algorithm 4.1 (parent) and the face_neighbor algorithm (§4.3-4.4) both branch on whether the current element's ancestor chain crosses a pyramid-to-tet boundary. The cached `min_tet_level` field (= "smallest level at which an ancestor is a tetrahedron"; sentinel −1 for pyramids and for tets that descend from pure-tet roots) makes the boundary-crossing decision O(1). Without it, the type-recovery walk is O(level) — bounded by 21 in Luciferase, but called on every parent/child/neighbor query. **Implication: PyramidKey storage = 128-bit Morton index + ~1-byte `min_tet_level` field per element.** The field is per-element, not per-key, so it lives on the `Pyramid` primitive (analogous to `Tet`), not the `PyramidKey`.

3. **Tet types 1, 2, 4, 5 reuse the 2016 algorithms unchanged.** Knapp 2026 explicitly states that `t8_tet_parent`, `t8_tet_child`, and tet `face_neighbor` for tet types 1, 2, 4, 5 are reused from Burstedde+Holke 2016 verbatim. Only types 0 and 3 get pyramid-aware branches. **Implication: `Tet.java`'s S1/S2/S4/S5 handling is correct as-is and need not change.** S0/S3 (Luciferase naming for tet types 0/3) get new code paths *when called from within a pyramidal tree*; Tet code called from a pure Tetree continues to work unchanged.

4. **Luciferase's `Forest` is already heterogeneous in API.** Contrary to the handoff's framing of "Forest is homogeneous today," `Forest.addTree(AbstractSpatialIndex<Key, ID, Content>)` already accepts heterogeneous trees. The missing piece for Algorithm 5.1 is purely the `N_shape(ℓ)` weight hook — `TreeNode.java:76` tracks only `entityCount`, not shape-aware element-count. **This is a smaller fix than expected** and is a strong tailwind for Direction B (Element + Forest).

5. **`AbstractSpatialIndex` is stable post-RDR-008 close (2026-05-28).** Façade with ~159 public/protected methods + ~42 protected template hooks (the realized-vs-predicted delta from RDR-008's ~70-method estimate is documented in the post-mortem). Subclass-and-initialize-collaborators is the extension pattern: PyramidIndex initializes `SpatialIndexCore` + `DsocController` + `GhostCoordinator` + `KnnSearcher` + `Culler` + `CollisionEngine` + `EntityLifecycleManager`, then overrides the ~42 protected template hooks (which break down by cluster sub-interface: `occlusion.FrustumGeometry`, `cache.KnnProvider`, `cache.KnnGeometry`, `cull.CullGeometry`, `cull.FrustumCullProvider`, `collision.CollisionGeometry`, `entity.EntityLifecycleGeometry`, `entity.EntityLifecycleHost`). **Sequencing dependency on RDR-008 is satisfied** — PyramidIndex can start any time.

6. **`N(ℓ) = 2·8^ℓ − 6^ℓ` for pyramid root** (Knapp Eq 5.1). Counts pyramids + tets descended from one root pyramid after ℓ uniform refinement levels. The `−6^ℓ` term corrects for the fact that pyramidal children refine to 10 (= 6 pyramid + 4 tet) while tet children refine to 8 — non-uniform mixing accumulates the correction. For `N_hex` and `N_tet` (both `= 8^ℓ`) the formula is trivial; `N_prism` is not in the retrieved chunks (the paper's hybrid mesh in §7 uses prism but the supplementary material SM1 deriving N(ℓ) for prism was not captured).

7. **Storage and comparison overhead at 128-bit keys.** PyramidKey at MAX_LEVEL=21 needs 126 bits → 128-bit (two `long`s) storage. Larger than MortonKey (64-bit) and compact TetreeKey (64-bit at ≤level 10). `ConcurrentSkipListMap<PyramidKey, ...>` comparisons are now 2-long comparisons + branching, vs single-long for Octree. At 40·10⁹ elements (Knapp's §7 demo scale) this matters; at Luciferase's typical workload (≤10⁵ elements) it does not. Worth a microbenchmark before locking representation. Alternative: `byte[]`-backed key (less locality, more GC) or `BigInteger` (heap allocation per key — likely too costly).

8. **`forest.ghost` integration path — RESOLVED.** Holke+Knapp+Burstedde 2019 (catalog `1.14.6`, 202 chunks, arXiv:1910.10641) is now indexed. The paper's `cites+extends 1.12.8` and `cites 1.14.6` edges in Knapp 2026's bibliography are confirmed by the post-synthesis catalog cross-links. Direction B distributed-pyramid scope can cite the algorithm directly.

9. **`nx_answer` routing rough edge — FILED.** Compare/extract plans selecting retrieval skeletons that don't hydrate content. Filed as `nexus-ncqhv` (P3). Workaround was direct `search(structured=true) → store_get_many(structured=true)`.

10. **28-paper foundation corpus complete (post-synthesis 2026-05-28).** Full Tier 1+2+3 + Holke 2019 ghost indexed. **3,929 chunks** across `knowledge__dt-papers__voyage-context-3__v1`. **22 citation links auto-created by bib enrichment** (OpenAlex/Semantic Scholar match-and-link), **46 additional cross-links** created by the deep-research-synthesizer agent grounded in actual paper text (no fabricated citations — hard constraint honored). Topic clusters with named hubs in synthesis report.

11. **Algorithmic lineage confirmed.** Bey 1992 (Computing 55, BibTeX [4] in Knapp 2026, NOT separately indexed) → Burstedde+Holke 2016 (`1.12.8`, 195 chunks) → Holke 2018 PhD (`1.12.9`, 216 chunks) → Knapp 2026 (`1.12.7`, 162 chunks) → Holke+Knapp+Burstedde 2019 (`1.14.6`, 202 chunks). Each step adds a strict capability: Bey introduces 1:8 tet refinement; Burstedde+Holke 2016 introduces the TM-index and proves uniqueness for tet types 0-5; Holke 2018 PhD generalizes the existence/uniqueness machinery (Theorem 16/Prop 4.17 in Chapter 4); Knapp 2026 introduces pyramid types 6+7 and the 6D Morton embedding; Holke 2019 introduces the parallel hybrid ghost algorithm.

12. **Direction B confirmed at HIGH confidence by synthesis.** Two uncertainties at RDR drafting are now resolved: (a) Holke 2019 ghost paper IS indexed (no longer a future-work caveat); (b) Forest weight pluggability is bounded to a single `N_shape(ℓ)` callback per shape — not a structural Forest change. Direction A remains a valid fallback only if `Forest.balancing.fault` audit reveals weight-pluggability is entangled. Direction C (defer-and-document) is **no longer warranted**.

13. **Pyramid containment derivation (Approach §3) is the ONLY remaining open question** the foundation-paper corpus cannot close. Synthesis explicitly identified this as a load-bearing gap with no paper in corpus deriving it. Implementer must make this call at gate time (default 3b decompose-and-reuse, escalate to 3a derive-from-scratch if DSOC/Culler profiling shows bottleneck).

14. **`Tet.java` call-site inventory for pyramid-aware branching (2026-05-28, Serena audit).** Finding #3 named *which tet types* get new branches (0 and 3); this finding names *which exact call sites*. Four entry points in `lucien/src/main/java/com/hellblazer/luciferase/lucien/tetree/Tet.java` need pyramid awareness when called on a tet that descends from a pyramidal root (i.e., when the tet's `min_tet_level != -1`):
    - **`child(int)`** at `:918` — currently delegates to `BeySubdivision.getMortonChild(this, childIndex)`. No type-0/3 branching today. **Knapp Algorithm 4.2 addition**: `BeySubdivision.getMortonChild` would need a guard checking `min_tet_level` on the parent and selecting either the existing Bey 1:8 path (`min_tet_level == -1`) or the pyramid-aware §4 path (`min_tet_level != -1`).
    - **`faceNeighbor(int)`** at `:1442` — currently the t8code 3D `dtri_bits.c` algorithm with branches on `face`, not on `type`. **Knapp §4.4 addition**: for types 0 and 3 *only*, case split on whether the lowest-level tetrahedral ancestor's parent is a pyramid (Theorem 4.2 guarantees this walk is O(1) with `min_tet_level` cached). For types 1, 2, 4, 5 the existing code path is correct (Finding #3).
    - **`parent()`** at `:1691` — currently walks pure-tet S0 Bey tree. **Knapp Algorithm 4.1 addition**: when `min_tet_level == E.level`, the parent is the pyramid that birthed this tet (uses `from(E.coord)` + `cut(E.coord)`, sets `min_tet_level = -1` on the parent pyramid). When `min_tet_level != E.level`, delegate to existing `tet_parent(E)`, propagating `min_tet_level`.
    - **`computeType(byte level)`** at `:952` — currently assumes root type 0 (`return 0; // Root is always type 0 in S0 Bey tree`, line 964). **Pyramid-rooted modification**: must check whether the level being queried is above or below the `min_tet_level` boundary; above (closer to root) types are pyramid types 6/7, below are tet types 0-5. The current "trace from root (type 0)" assumption holds only for pure-Tetree contexts.

    **Plus one connectivity table addition**: `TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE_TABLE` (commented "Parent type 0 ... Parent type 5" at `:62-72`) needs entries for parent types 6 and 7 (Knapp Table 3.2 — pyramid → 6 pyramid + 4 tet children with type-swap-at-center).

    **Architectural implication**: `Tet` needs a `min_tet_level` field (default −1) and all four entry points check it. With sentinel default, pure-Tetree code paths are unchanged. This confirms Finding #3's "Tet code in pure Tetree continues to work unchanged" — the guard is `if (min_tet_level == -1) <existing-code> else <Knapp §4-branch>` at each entry. Storage cost: ~1 byte/Tet (boundary `max_level = 21`).

## Reuse-vs-Rewrite Analysis (from synthesis Part 2)

### Code paths reused unchanged

| Component | Location in Luciferase | Basis |
|---|---|---|
| S0-S5 tet types (types 0-5) | `Tet.java` `Tet.coordinates()` | Knapp 2026 §4: tet types 1, 2, 4, 5 use existing Burstedde+Holke 2016 algorithms unchanged |
| 12-DOP exact containment | `contains12DOP()`, `intersects12DOP()` | Knapp 2026: tet children of pyramid roots reuse existing tet containment |
| Bey 1:8 tet refinement | `Tetree` refinement paths | Knapp 2026 Algorithm 4.2: tet child computation delegates to Burstedde+Holke 2016 unchanged |
| TM-index for tet children | `TetreeKey.consecutiveIndex()` | Knapp 2026: tet-descendent SFC unchanged |
| `AbstractSpatialIndex` core | Post-RDR-008 close (concrete façade) | No abstract methods; subclass-and-initialize-collaborators pattern (same as Octree/Tetree) |
| `Forest.addTree()` heterogeneous API | `forest` package | Already accepts heterogeneous trees — surprise finding from prior research |

### Code paths requiring extension

| Component | What changes | Basis |
|---|---|---|
| `PyramidKey` | New 128-bit key (6 bits/level × 21 levels = 126 bits; two `long`s) | Knapp 2026 §3, 6D Morton embedding |
| Pyramid child tables | Table 3.1 (anchor shifts), Table 3.2 (child-type lookup) | Knapp 2026 §3 |
| `min_tet_level` field | ~1 byte per element; sentinel `-1` for pyramids and pure-Tetree elements | Knapp 2026 Algorithm 4.1 — required for O(1) parent/face_neighbor |
| Face-neighbor for tet types 0, 3 | Add pyramid-aware branches (see Research Finding #14 for call-site inventory) | Knapp 2026 §4.3-4.4 |
| `Forest` partition weight | `N_shape(ℓ)` hook per shape | Knapp 2026 §5 Algorithm 5.1; pyramid: `N(ℓ) = 2·8^ℓ − 6^ℓ` |
| `PyramidNeighborDetector` | New class; face types pyramid↔tet, Table 4.2 | Knapp 2026 §4.4 |
| `TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE_TABLE` | Append parent types 6 and 7 entries | Knapp 2026 Table 3.2 |

### Gaps in the foundation-paper corpus (follow-up research)

1. **Bey 1992/1995 "Computing 55"** (Knapp ref [4]) — **NOT indexed**. J. Bey, *Tetrahedral grid refinement*, Computing 55 (1995), 355-378. The original Bey red-refinement paper defining the 1:8 subdivision geometry. The indexed paper at `1.12.12` is the later *parallel multilevel* paper. Required for vertex-labeling and orientation-convention verification against `Tet.java`. Action: locate and index from DEVONthink or publisher.
2. **Sundar+Sampath+Biros 2008 "Dendro"** — **NOT indexed**. *Bottom-Up Construction and 2:1 Balance Refinement of Linear Octrees in Parallel*, SIAM J. Sci. Comput. 30(5), 2008. Referenced throughout corpus as benchmark comparison. The indexed `1.12.35` is related but earlier. Required for external performance baseline. Action: index from DEVONthink or arXiv.
3. **Pyramid containment primitive** — open derivation. No paper in corpus derives a pyramid-specific containment test. Required for Approach §3a (precision-first pyramid-DOP); Approach §3b (decompose + reuse tet 12-DOP) avoids this gap.
4. **Sparse t8code paper** (`1.12.11`, 19 chunks) — nearly unreadable from chunk extraction. All Luciferase implementation comparisons to t8code should cite Knapp 2026 (162 chunks, well-indexed) rather than the t8code paper for algorithmic and benchmark detail. Action: attempt re-extraction.
5. **`N_prism(ℓ)` closed form** — not in corpus. Pentahedra paper (`1.12.22`, 2 chunks) insufficient. Required only if `PrismIndex` enters a hybrid forest. Likely derivable by analogy from Knapp 2026 §5 (prism refines to 4 prisms + 4 tets → `N_prism(ℓ) = 4·8^ℓ − ...`; derivation pending).
6. **Large-scale HPC benchmark for Luciferase scale** — no corpus paper covers Luciferase-scale benchmarks directly. Scale projections for a PyramidIndex gate must use existing Octree/Tetree benchmarks as baselines, adjusted by the per-element branching overhead reported in Knapp 2026 §6.

## Open Questions

- **Pyramid containment primitive — 3a, 3b, or 3c?** Decompose-and-reuse (3b) is the lowest-risk default; derive-from-scratch (3a) is correctness-optimal but high effort; AABB-then-decompose (3c) is the perf sweet spot but adds branching. Depends on whether DSOC / Culler / ray-intersect query workloads dominate. **Defer to gate; needs a workload assumption. This is the ONLY load-bearing question the 28-paper corpus cannot close (Research Finding #13).**

- ~~**Direction A, B, or C?**~~ **RESOLVED post-synthesis (2026-05-28).** Direction B confirmed at HIGH confidence given (a) Holke 2019 ghost paper is indexed (`1.14.6`, Research Finding #8), (b) Forest weight pluggability is bounded to `N_shape(ℓ)` callback per shape (Research Finding #4 + #12). Gate may still require `Forest.balancing.fault` audit before locking — see updated Approach §6.

- **`N_prism(ℓ)` closed form.** Not in retrieved chunks. Either derive from Knapp's hybrid mesh discussion (§7's 29,520-prism / 69,431-tet / 3,800-hex / 3,120-pyramid breakdown) or defer until prism enters a hybrid forest scenario. **Open; not blocking unless Direction B + prism-in-hybrid-Forest is in the same scope.**

- **128-bit key representation.** Two-`long` struct, `byte[]`, or `BigInteger`? Two-`long` likely wins on GC + locality; benchmark before locking. **Open; microbenchmark in early implementation.**

- **`min_tet_level` placement.** On the `Pyramid` element (per-element), on the `PyramidKey` (per-key, increases storage), or computed lazily from the key bits? Paper places it as an element field. **Lean: element field, matching paper.**

- **Holke 2018 theorem number.** Handoff says "Theorem 3.5"; retrieved chunks show "Theorem 16 / Prop 4.17 in Chapter 4." Verify on the PDF before final citation. **Open; bibliographic only.**

- ~~**Sequencing with RDR-008 P6 (entity-lifecycle extraction).**~~ **RESOLVED 2026-05-28.** RDR-008 closed today — all six phases shipped, post-mortem written. PyramidIndex implementation arc can proceed without further sequencing wait.

- ~~**Sequencing with `forest.ghost` Holke+Knapp+Burstedde 2019 integration.**~~ **RESOLVED — paper now at catalog 1.14.6 (202 chunks).** The algorithm can be cited directly during implementation. Specification work happens at gate or P1-phase planning.

- **Scope of "hybrid forest" demo target.** Does the eventual implementation arc include a §7-style toy-airplane demo (hex + tet + pyramid + prism)? **Open; affects benchmark / acceptance criteria.**

## Decision

> NOT LOCKED. This RDR is in `draft` status. Per the authoring constraint (don't lock at create-time), the decision will be filled at gate by `/conexus:rdr-gate`. Candidate directions are enumerated in Approach §6.

## Consequences

- **Positive.**
  - Closes the documented t8code partition gap with a published, peer-reviewed, scale-validated construction.
  - Provides a hex↔tet hybrid-mesh transition primitive Luciferase currently lacks.
  - Reuses Tetree's S0-S5 / Bey / 12-DOP machinery unchanged — extension, not rewrite.
  - Aligns Luciferase with the t8code reference implementation and Knapp 2026's published algorithms (parallel partition, balance, ghost).
  - Forest weight pluggability (Direction B item 4) has independent value for Octree/Tetree forests even before any pyramid lands.

- **Cost / risk.**
  - **128-bit PyramidKey** is the largest spatial key in Luciferase. Storage doubles vs Octree; `ConcurrentSkipListMap` comparison overhead increases. Acceptable at typical workloads; needs measurement at scale.
  - **`min_tet_level` field** adds per-element state. Mandatory for O(1) operations; without it parent/child/face_neighbor go O(level).
  - **Pyramid containment derivation (Approach §3)** is an unsolved problem in this RDR. The decompose-and-reuse-tet-12-DOP default is correct but slower than a derived pyramid-DOP. May need its own follow-up RDR if perf gates the choice.
  - **Per-element branching (types 6/7 + tet 0/3)** makes the pyramid the slowest shape per-element in Knapp 2026's benchmarks (§6). For Luciferase render workloads (DSOC, ray, frustum), this is a known cost-baked-in.
  - ~~**Sequencing dependency on RDR-008 P6**~~ **RESOLVED 2026-05-28** — RDR-008 closed; PyramidIndex implementation arc can start any time once Direction A/B/C is locked at gate.

- **Sequencing.**
  - **RDR-008 closed 2026-05-28** (all six phases: SpatialIndexCore, DsocController, GhostCoordinator, KnnSearcher, Culler, CollisionEngine, EntityLifecycleManager). Sequencing dependency satisfied.
  - PyramidIndex implementation arc: can start any time once Direction A/B/C is locked at gate.
  - Forest weight pluggability (Approach §4) can start independently — no PyramidIndex prerequisite, valuable even for current Octree/Tetree forests.
  - `forest.ghost` Holke+Knapp+Burstedde 2019 integration is a separate optional arc; depends on whether Direction B includes distributed pyramid.

- **No external module impact expected** beyond `lucien`. `simulation`, `render`, `sentry`, `portal` consume `AbstractSpatialIndex<Key, ID, Content>` through the generic; a new key type lands cleanly without ripple.

- **Documentation updates required at implementation time** (not now):
  - `CLAUDE.md` partition-gap note — convert "documented limitation" to "closed via PyramidIndex (RDR-010)."
  - `lucien/doc/TETREE_T8CODE_PARTITION_ANALYSIS.md` — append the Pyramid fix.
  - `lucien/doc/LUCIEN_ARCHITECTURE.md` — add `PyramidKey`, `Pyramid`, `PyramidIndex`, `PyramidNeighborDetector` to the architecture map.
