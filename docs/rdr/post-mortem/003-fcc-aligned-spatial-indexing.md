# Post-Mortem: RDR-003 — FCC-Aligned Spatial Indexing for Luciferase

**Closed**: 2026-05-23 | **Reason**: Implemented | **Phases**: Phase 0 shipped; Phase 1 + 2 deferred per measurement

## What Was Delivered

**Phase 0 (mandatory) shipped end-to-end across 7 merged PRs**:

| PR | Outcome |
|---|---|
| #74 (Luciferase-hic) | Spatial-level heuristic + Manager/BubbleBounds level threading |
| #75 (d8a + jp7) | JMH baseline at original + corrected levels |
| #76 (mj7) | Tetree-backed SpatialNeighborIndex (Step 2 initial design) |
| #77 (2mn + sc4 + fv5 + ma7.1 + ma7) | Step 3 validation + Step 2.1 fix + dual-store dispatcher + Phase 0 close |
| #78 (b3v + x0t) | Flake fixes surfaced during Phase 0 work |
| #79 (lgs) | `Forest.findEntitiesInRegion` stub fix (latent correctness bug) |
| #80 (2py, yyb, xnf, 7jk, etb, 3xa, bhc, f2z) | Portal RD/FCC math cleanup batch (Phase 1 prerequisites) |
| #81 (546) | SpatialKey serde registry (extensibility for future key types) |
| #82 (6oa) | Portal RD/FCC test coverage + `Tetrahedral.toRDG` precision bug fix |
| #83 (Phase 0 Step 5) | Cold-cache k-NN measurement → findKNearest reverts to linear scan |

**Net SpatialNeighborIndex final form**: dual-store (Tetree mirror + ConcurrentHashMap), with ALL read paths routed through the flat-map linear scan. Tetree retained as architectural option but no read path consumes it.

**Phase 1 (RD overlay on Tetree) and Phase 2 (TetOctree via Greiner-Grosso) deferred indefinitely** per measurement-driven evaluation of their go/no-go triggers.

## What Went Right

1. **Conditional phasing actually worked.** The RDR explicitly scoped Phase 1 + 2 as conditional, gated on Phase 0 outcomes. When Phase 0's measurement contradicted the original projection (10-100× speedup expected; 5-20× regression observed), the conditional gates fired correctly: Phase 1 stayed deferred. The "deferred" outcome was a feature of the plan, not its failure.

2. **Measurement-driven course correction.** Five sequential investigations during Step 3 (`mj7` → `2mn` → level-sweep → imperative variant → dual-store → cold-cache benchmark) each invalidated a successive assumption. Each pivot was anchored to a JMH-measured number, not theory. The RDR's Revision History captured each pivot as a separate dated entry, producing a self-contained audit trail.

3. **The 360° prior-art survey paid off.** Five parallel research agents (web, mixedbread, lucien code-read, portal audit, mathematical rigor) converged on the same three-camp classification (MSP-tree / Greiner-Grosso / t8code super-lattice). No false starts on already-rejected paths.

4. **Step 4 thresholds were pre-committed before Step 3 ran.** This made the Phase 1 go/no-go decision a mechanical check against fixed criteria, not a post-hoc rationalization. When the cold-cache measurement later revealed the original threshold assumed cache-hit Tetree, amending the threshold required an explicit RDR Revision History entry — preserving the audit trail.

5. **Audit found and fixed dormant bugs en route.** The Phase 1-prereq cleanup batch (PR #80) surfaced and fixed:
   - `RDG.faceConnectedNeighbors[2]` z-typo (8 of 12 face neighbors were wrong cell.z values away)
   - `Tetrahedral.vertexConnectedNeighbors` mixed shell (4 face + 2 third-shell instead of 6 second-shell)
   - `Tetrahedral.dot` metric tensor coefficient errors
   - `Tetrahedral.toRDG` precision bug (int truncation rounding `1.0-ε` to `0`)
   - `Forest.findEntitiesInRegion` silently returning origin cube regardless of input

   The portal RD math subsystem went from 0 tests to 31 covering tests across 3 test classes.

6. **The k-NN cache discovery during Step 3 was a load-bearing measurement.** When `findKNearest` results came back as 0.5 μs across all N, it would have been easy to declare victory. Recognizing this as cache-hit-dominated (cycled 128 query centres against ~64 level-15 cache buckets) led to the cold-cache benchmark, which revealed the actual production risk (688 ms at N=100K). Without that recognition, Phase 0 would have shipped a hidden cliff.

## What Went Wrong

1. **The original §Performance Expectations were wrong by direction.** The RDR projected "10-100× speedup for AoI ball queries on N ≥ 10K" for Phase 0. Actual measurement: Tetree-backed `findWithinRadius` is **5-20× SLOWER** than linear-scan ConcurrentHashMap at every tested (N, r) at level=18. The projection assumed cell-touch count was the bottleneck. Step 3 isolated the actual bottleneck as per-candidate `tetree.getEntity` cost (~500 ns × ~12,500 candidates at N=100K r=50 ≈ 6 ms minimum, vs linear-scan's ~15 ns × 100K = 1.5 ms). The RDR's mental model of "spatial pruning beats linear" was the wrong abstraction for VoN's typical radii.

2. **Step 2's initial implementation routed range queries through the k-NN cache.** `SpatialNeighborIndex.findWithinRadius` was wired to `findNeighborsIncludingGhosts(center, radius)`, which is internally implemented as `kNearestNeighbors(position, Integer.MAX_VALUE, radius)`. Routing unbounded range queries through a cache designed for bounded k stored 6500-id lists per cache entry, causing 271 ms mean with 158% relative stdev (vs the 5 ms threshold). Step 2.1 (PR #77) switched to `bounding(Spatial.Sphere)` + radius post-filter, which dropped the cost to 8 ms but still failed the threshold. Symptom of insufficient code review at Step 2 design time — the documented primitive (`findNeighborsIncludingGhosts`) had a misleading name.

3. **One false-positive bug burned investigation time.** Step 3's audit test for `RDG.symmetry()` reported "24 distinct images instead of 48 — RDG.symmetry table is broken" and filed Luciferase-yai as a follow-up. Root-cause investigation later showed the symmetry table is correct; the test had used RDG point `(1,2,3)` which maps to Cartesian `(0,1,3)` — a non-generic point whose Oh orbit is correctly 24 (the 0 component creates a reflection stabilizer). Cost: ~1 hour of additional investigation. Mitigation in the corrected test: parametric correspondence check over 48 groups × 5 generic-Cartesian-image points (0 mismatches confirmed).

4. **u88.1's `bd close` lagged the actual prerequisite completion.** The 8 portal-rdfcc-quality prereqs were closed via PRs #80 and #82 over multiple commits, but the umbrella coordinator bead `u88.1` was not closed in lockstep. Discovered during the RDR-close decision flow when the bead-status advisory showed u88.1 still open. Fixed by manually closing it after the fact. Process gap: no automatic propagation from "all 8 prereqs closed" to "u88.1 closes too".

## Divergences from Plan

| Planned | Actual |
|---|---|
| Phase 0: "10-100× speedup for AoI ball queries on N ≥ 10K" | 5-20× regression at typical radii, dispatcher chose linear scan for all read paths |
| Phase 0 architecture: "replace ConcurrentHashMap with Tetree" | "Dual-store (Tetree mirror + ConcurrentHashMap), all reads via flat map" |
| Phase 0 close criterion: sub-linear scaling required for findWithinRadius | Criterion retired post-measurement (linear scan exceeds it but wins on absolute latency) |
| Phase 0 close criterion: findKNearest ≤ 5 ms at N=100K stress | Amended to "linear-scan baseline + ≤ 50% margin = 33 ms" after cold-cache measurement showed the 5 ms target assumed cache-hit Tetree |
| Phase 1 conditional on "Phase 0 confirms VoN still bottleneck-bound" | Phase 0 confirmed bottleneck is per-entity cost, not cell-touch count — Phase 1's mechanism does not address it, deferred indefinitely |
| Phase 2 conditional on Phase 1 shipping | Cascaded to deferred |

## Knowledge Banked for Future Re-engagement

**T2** (`luciferase_rdr/003-research-NNN`):
- `003-research-001`: SFC feasibility for hybrid tet+oct (Strategy B = 4 bits/level, ships at 88 bits, dual-long at L=20)
- `003-research-002`: 4-body-diagonal 14-DOP derivation for GG octahedra + regular tets (17 ops point-contains, 26 ops AABB-vs-oct, zero multiplications)
- `003-research-003`: VoN AoI radius empirical distribution, level-10 refuted, level 17-18 recommended
- `003-research-004`: Permutohedral lattice = FCC in 3D; shell-expansion k-NN unpublished (open territory)

**T3** (`nx` knowledge store, prefix `architecture-luciferase-`):
- `openquestions`, `fcc-prior-art`, `fcc-prior-art-deep`, `mixedbread-spatial-index-inventory`
- `tetoct-integration-map`, `portal-rd-audit`, `fcc-mathematical-foundations`

**Cold-cache benchmark preserved** (`TetreeKNearestColdCacheBenchmark`) for re-running if cost profile changes.

**Bead state**: u88 epic + sub-items + 9vb epic + sub-items all deferred until 2027-01-01; can be reactivated by changing the defer date.

## Re-engagement Triggers

Phase 1 reactivation requires ONE of:

1. A workload with a cell-touch-bound cost profile (where Phase 1's mechanism — tighter cell sphericity → fewer cells touched — IS the bottleneck remover). The current VoN workload is per-entity-bound, not cell-touch-bound.
2. A second use case beyond AoI: FCC ghost layers, FCC collision broad-phase, native FCC k-NN where shell ordering matters.
3. A material change in VoN workload that invalidates the cold-cache + per-entity-cost finding.

Phase 2 additionally requires Phase 1 success AND a second use case driving native FCC hierarchy.

## Process Lessons

- **Pre-commit thresholds before measurement.** Step 4 thresholds were locked in PR #77's RDR Revision History entry BEFORE Step 3 ran, removing post-hoc rationalization from the go/no-go decision.
- **Cache-mediated measurements need explicit cold-path benchmarks.** A 4150× "speedup" that comes from cache lookups, not from the algorithm under test, is a metric trap. Always measure cold-path cost when a cache sits between the benchmark and the operation.
- **Conditional plans require explicit "defer" outcomes as first-class success states.** Phase 1+2 deferred is the correct outcome of measurement-driven gating, not a failure to ship.
- **Symptom-vs-cause separation**: the dispatcher decision evolved through 5 stages because each pivot fixed the immediate symptom (k-NN cache routing, level mismatch, stream overhead, cell pruning, cache-cold cliff) without addressing the root (per-entity cost). The final root-cause fix (linear scan everywhere) was simpler than any of the intermediate fixes.
