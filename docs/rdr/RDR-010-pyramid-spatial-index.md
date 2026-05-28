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

- **Holke+Knapp+Burstedde 2019** (arXiv:1910.10641, NOT yet indexed in T3 — the foundation-papers run stopped at Tier 1/2 boundary) — "An optimized, parallel computation of the ghost layer for adaptive hybrid forest meshes." This is the published scalable ghost algorithm that `forest.ghost` would lift from if this RDR scopes ghost integration. Resume the index run via the T2 memory `dt-foundation-papers-indexed-2026-05-28` to make this paper searchable before the gate.

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

- **In-flight constraint**: RDR-008 (god-class decomposition, epic `Luciferase-x5i`) is mid-execution. Cull extraction (P4, PR #136) merged 2026-05-28; collision extraction (P5+, branch `feature/Luciferase-x5i.10-collision-extraction`) is uncommitted in another worktree. **PyramidIndex must NOT land before RDR-008's final phase closes** — coordinate-ordering risk if the AbstractSpatialIndex surface is still moving.

### What this RDR does and does not do

- **In scope**: propose the architectural shape of a `PyramidIndex` (key, primitive, index, forest integration, ghost integration). Decide which candidate direction to lock at gate. NOT implement.
- **Out of scope**: pyramid 12-DOP containment derivation (open question, may be its own bead), Holke+Knapp+Burstedde 2019 ghost paper deep-dive (resume the foundation-paper index run first), prism N_shape(ℓ) formula derivation (separate research item).

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

5. **Ghost-layer integration: `PyramidNeighborDetector`.** Implement `NeighborDetector` for pyramid topology (4-face quadrilateral base + 4 triangular side faces). Cross-shape neighbor finding (pyramid↔tet at the 6/7-boundary, hex↔pyramid at the cubic-tile boundary) follows Knapp §4.3-4.4 "Construct pyramid from face" + Table 4.2. Register with `GhostCoordinator.setNeighborDetector()`. **Holke+Knapp+Burstedde 2019** (the published parallel ghost-layer paper) is the proven distributed algorithm — pull it into `forest.ghost` here if the scope includes distributed pyramid; defer otherwise.

6. **Three candidate directions (the gate question).** Pick one (or hybrid) at accept:
   - **Direction A — Full element-level integration only.** Items 1, 2, 3, 5 above. Add PyramidIndex as a peer of Octree/Tetree. No Forest changes. Pyramid trees can be added to a Forest but use the default 1:8 weight (slightly inaccurate for `Forest.balancing.*`, but workable). **Smallest change**, ~2-3 RDR-008-phase-sized arc.
   - **Direction B — Element-level + Forest weight pluggability.** Direction A plus item 4. Closes Algorithm 5.1's "hybrid forest partition" loop. **Recommended end state.** ~4-5 RDR-008-phase-sized arc.
   - **Direction C — Defer-and-document.** Update `CLAUDE.md` and `TETREE_T8CODE_PARTITION_ANALYSIS.md` to cite Knapp 2026 as the documented published fix; do not implement. Revisit when a concrete use case demands hex↔tet hybrid meshes. **Smallest action**, leaves the gap open as a documented known issue.

   Default lean (subject to research findings): **Direction B**, sequenced after RDR-008 close. Direction A is the fallback if Forest weight-pluggability turns out to be entangled with `Forest.balancing.fault`. Direction C is the safety net if implementation cost exceeds the strategic value of hex↔tet capability.

## Research Findings

> Investigation 2026-05-28. Mined sources: Knapp 2026 (catalog `1.12.7`), Burstedde+Holke 2016 (`1.12.8`), Holke 2018 PhD (`1.12.9`), p4est 2011 (`1.12.10`), t8code v1.0 (`1.12.11`). Architecture survey via Serena symbol navigation in worktree `/Users/hal.hildebrand/git/Luciferase-rdr-pyramid`. Full dossier in T1 scratch `b7c8bed9` and T2 `Luciferase/dt-foundation-papers-indexed-2026-05-28`.

1. **The pyramid SFC is a 6D Morton embedding, not a flat 6-bit-per-level concat.** Knapp Eq 3.5–3.7: the pyramid index `m_P(P)` is defined to equal the cube Morton index `m_Q(Θ(P))` of an embedding `Θ: P → Q` into a 6D cube whose axes are `(B², B¹, B⁰, x, y, z)` — three "type-representing tuple" axes + three spatial axes. Per-level layout at bit position `L−(l+1)`: 3 type bits ‖ 3 coord bits. Consequence: PyramidKey ≠ "TetreeKey + 1 extra bit." The encoding math is fundamentally different (6D bijection-with-cube-Morton vs 3D), even though the per-level bit budget happens to be the same.

2. **`min_tet_level` is mandatory for O(1), not optional.** Algorithm 4.1 (parent) and the face_neighbor algorithm (§4.3-4.4) both branch on whether the current element's ancestor chain crosses a pyramid-to-tet boundary. The cached `min_tet_level` field (= "smallest level at which an ancestor is a tetrahedron"; sentinel −1 for pyramids and for tets that descend from pure-tet roots) makes the boundary-crossing decision O(1). Without it, the type-recovery walk is O(level) — bounded by 21 in Luciferase, but called on every parent/child/neighbor query. **Implication: PyramidKey storage = 128-bit Morton index + ~1-byte `min_tet_level` field per element.** The field is per-element, not per-key, so it lives on the `Pyramid` primitive (analogous to `Tet`), not the `PyramidKey`.

3. **Tet types 1, 2, 4, 5 reuse the 2016 algorithms unchanged.** Knapp 2026 explicitly states that `t8_tet_parent`, `t8_tet_child`, and tet `face_neighbor` for tet types 1, 2, 4, 5 are reused from Burstedde+Holke 2016 verbatim. Only types 0 and 3 get pyramid-aware branches. **Implication: `Tet.java`'s S1/S2/S4/S5 handling is correct as-is and need not change.** S0/S3 (Luciferase naming for tet types 0/3) get new code paths *when called from within a pyramidal tree*; Tet code called from a pure Tetree continues to work unchanged.

4. **Luciferase's `Forest` is already heterogeneous in API.** Contrary to the handoff's framing of "Forest is homogeneous today," `Forest.addTree(AbstractSpatialIndex<Key, ID, Content>)` already accepts heterogeneous trees. The missing piece for Algorithm 5.1 is purely the `N_shape(ℓ)` weight hook — `TreeNode.java:76` tracks only `entityCount`, not shape-aware element-count. **This is a smaller fix than expected** and is a strong tailwind for Direction B (Element + Forest).

5. **`AbstractSpatialIndex` is concrete post-RDR-008 P4.** No abstract methods to implement; subclass-and-initialize-collaborators is the extension pattern. PyramidIndex follows Octree/Tetree as a template. **However**: the residual surface is still mutating (RDR-008 P5+ in flight). PyramidIndex must wait for RDR-008 close to avoid coordinate-ordering risk.

6. **`N(ℓ) = 2·8^ℓ − 6^ℓ` for pyramid root** (Knapp Eq 5.1). Counts pyramids + tets descended from one root pyramid after ℓ uniform refinement levels. The `−6^ℓ` term corrects for the fact that pyramidal children refine to 10 (= 6 pyramid + 4 tet) while tet children refine to 8 — non-uniform mixing accumulates the correction. For `N_hex` and `N_tet` (both `= 8^ℓ`) the formula is trivial; `N_prism` is not in the retrieved chunks (the paper's hybrid mesh in §7 uses prism but the supplementary material SM1 deriving N(ℓ) for prism was not captured).

7. **Storage and comparison overhead at 128-bit keys.** PyramidKey at MAX_LEVEL=21 needs 126 bits → 128-bit (two `long`s) storage. Larger than MortonKey (64-bit) and compact TetreeKey (64-bit at ≤level 10). `ConcurrentSkipListMap<PyramidKey, ...>` comparisons are now 2-long comparisons + branching, vs single-long for Octree. At 40·10⁹ elements (Knapp's §7 demo scale) this matters; at Luciferase's typical workload (≤10⁵ elements) it does not. Worth a microbenchmark before locking representation. Alternative: `byte[]`-backed key (less locality, more GC) or `BigInteger` (heap allocation per key — likely too costly).

8. **`forest.ghost` integration path.** Holke+Knapp+Burstedde 2019 (arXiv:1910.10641) is the published scalable algorithm for hybrid-forest ghost layers. **Not yet in T3** — the foundation-papers run stopped at the Tier 1 boundary. Resume the index run (resume command in T2 `Luciferase/dt-foundation-papers-indexed-2026-05-28`) before the gate if scope includes distributed pyramid; otherwise mark as future work.

9. **`nx_answer` routing rough edge surfaced during paper mining.** Two specific analytical questions (Knapp §3 geometric definition; cross-paper delta) returned `inputs: [""]` envelopes from `nx_answer`, while other queries against the same scope worked. The paper-mining agent finished the dossier via direct `search(structured=true) → store_get_many(structured=true)`. Diagnostic note in T1 scratch `c5826c7d`. Not a Luciferase issue; file in nexus repo as a separate concern if it persists.

## Open Questions

- **Pyramid containment primitive — 3a, 3b, or 3c?** Decompose-and-reuse (3b) is the lowest-risk default; derive-from-scratch (3a) is correctness-optimal but high effort; AABB-then-decompose (3c) is the perf sweet spot but adds branching. Depends on whether DSOC / Culler / ray-intersect query workloads dominate. **Defer to gate; needs a workload assumption.**

- **Direction A, B, or C?** The recommended lean is B (element + Forest weight pluggability), but the cost of Forest weight integration depends on how entangled `Forest.balancing.fault`'s redistribution algorithm is with the implicit-1:8-weight assumption. **Decide at gate after a targeted `Forest.balancing` audit.**

- **`N_prism(ℓ)` closed form.** Not in retrieved chunks. Either derive from Knapp's hybrid mesh discussion (§7's 29,520-prism / 69,431-tet / 3,800-hex / 3,120-pyramid breakdown) or defer until prism enters a hybrid forest scenario. **Open; not blocking unless Direction B + prism-in-hybrid-Forest is in the same scope.**

- **128-bit key representation.** Two-`long` struct, `byte[]`, or `BigInteger`? Two-`long` likely wins on GC + locality; benchmark before locking. **Open; microbenchmark in early implementation.**

- **`min_tet_level` placement.** On the `Pyramid` element (per-element), on the `PyramidKey` (per-key, increases storage), or computed lazily from the key bits? Paper places it as an element field. **Lean: element field, matching paper.**

- **Holke 2018 theorem number.** Handoff says "Theorem 3.5"; retrieved chunks show "Theorem 16 / Prop 4.17 in Chapter 4." Verify on the PDF before final citation. **Open; bibliographic only.**

- **Sequencing with RDR-008 P6 (entity-lifecycle extraction).** PyramidIndex implementation must wait for the residual `AbstractSpatialIndex` surface to stabilize. **Hard constraint; mark in any implementation epic.**

- **Sequencing with `forest.ghost` Holke+Knapp+Burstedde 2019 integration.** If Direction B includes distributed pyramid, the 2019 paper should be indexed and its algorithm specified before locking the ghost integration. **Open; depends on direction.**

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
  - **Sequencing dependency on RDR-008 P6** means PyramidIndex is *not* a near-term implementation candidate. Strategic capability, not patch work.

- **Sequencing.**
  - RDR-008 P5 (collision extraction) currently in flight in `feature/Luciferase-x5i.10-collision-extraction`. RDR-008 P6 (entity-lifecycle extraction) still to plan.
  - PyramidIndex implementation arc: start after RDR-008 close.
  - Forest weight pluggability (Approach §4) can start independently — no PyramidIndex prerequisite, valuable even for current Octree/Tetree forests.
  - `forest.ghost` Holke+Knapp+Burstedde 2019 integration is a separate optional arc; depends on whether Direction B includes distributed pyramid.

- **No external module impact expected** beyond `lucien`. `simulation`, `render`, `sentry`, `portal` consume `AbstractSpatialIndex<Key, ID, Content>` through the generic; a new key type lands cleanly without ripple.

- **Documentation updates required at implementation time** (not now):
  - `CLAUDE.md` partition-gap note — convert "documented limitation" to "closed via PyramidIndex (RDR-010)."
  - `lucien/doc/TETREE_T8CODE_PARTITION_ANALYSIS.md` — append the Pyramid fix.
  - `lucien/doc/LUCIEN_ARCHITECTURE.md` — add `PyramidKey`, `Pyramid`, `PyramidIndex`, `PyramidNeighborDetector` to the architecture map.
