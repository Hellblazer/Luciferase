---
title: "PyramidIndex SFC Linear-ID Primitive — Port t8code dpyramid linear_id, or Accept the Morton-Key Divergence"
id: RDR-011
type: Architecture
status: draft
priority: low
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-31
related_issues: [RDR-010, RDR-002]
---

# RDR-011: PyramidIndex SFC Linear-ID Primitive

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

`PyramidIndex` (RDR-010) ships a `PyramidKey` that is a **fixed 6-bit-per-level Morton key** (128-bit, two `long`s): per level it packs `[Z,Y,X | type2,type1,type0]`, coarse step in the MSB, and `compareTo` sorts level-major then by the 128-bit unsigned value. This is a Knapp-2026 6D-Morton embedding and is internally consistent.

t8code's `t8_dpyramid` SFC is a **different object**: a variable-radix compressed linear id (`uint64`), where a pyramid has 10 children of *unequal subtree sizes* (6 pyramid + 4 tet) and a tet has 8, so each level contributes `num_pyra·(2·8^i − 6^i) + num_tet·8^i` to the id (`t8_dpyramid_bits.c:546-589`, `:462-516`). t8code derives id↔element in **closed form, O(level)**: `t8_dpyramid_linear_id` and `t8_dpyramid_init_linear_id`.

The gap (6-axis gap analysis, 2026-05-31, against t8code `main@76a5347b`):

1. **No linear-id primitive.** Luciferase has no `init_linear_id` / `linear_id` / "i-th element at level ℓ" / locate-by-rank. Encode/decode are path-walks, not closed-form rank/unrank.
2. **`estimateSFCRange` is a stub.** `PyramidKey.estimateSFCRange` (`PyramidKey.java:264-287`) returns a full-band range, so any range-query / SFC-locality optimization that relies on it degenerates.
3. **k-NN falls back to BFS.** Without a usable SFC range, nearest-neighbour search cannot prune by SFC locality the way the Octree/Tetree paths do.
4. **Ordering parity is unproven.** No test establishes whether `PyramidKey.compareTo` order matches t8code's `linear_id` traversal order over a refined hybrid tree. They may differ (level-major key sort vs. within-level rank), which would surprise anyone reasoning from the t8code literature.
5. **Two ranking tables are unported** (gap-axis-2): `type_cid_to_Iloc[8][8]` and `parenttype_iloc_pyra_w_lower_id[2][10]` — the tables that make consecutive-index ranking possible.

None of this is a correctness bug today — it is a **capability gap and an unvalidated divergence** from the reference implementation the rest of `PyramidIndex` was ported from.

## Context

- RDR-010 deliberately chose the 6-bit Morton `PyramidKey` (Knapp Eq 3.5–3.7) over t8code's compressed id. That choice is sound for `SpatialKey` uniformity across Octree/Tetree/Prism/Pyramid. This RDR does **not** relitigate the key representation; it decides whether to add the *operations* t8code's id supports on top of (or alongside) the Morton key.
- Reference: `t8_dpyramid_bits.c` (`linear_id`, `init_linear_id`, `child_id`), `t8_dpyramid_connectivity.c` (`type_cid_to_Iloc`, `parenttype_iloc_pyra_w_lower_id`). Port baseline `main@76a5347b`.
- Luciferase side: `PyramidKey.java`, `PyramidKeyCodec.java`, `PyramidKeyDecoder.java`, `PyramidIndex.elementFromKey`.

## Decision (DRAFT — gate question open)

**Gate question:** Does `PyramidIndex` need closed-form SFC rank/unrank + tight range estimation + SFC-pruned k-NN, or is the Morton-key + BFS-k-NN contract acceptable as the permanent design?

- **Direction A — Port the linear-id primitive.** Add `linear_id(level)` and `fromLinearId(level, id)` (rank/unrank) over the Morton key, a tight `estimateSFCRange`, SFC-pruned k-NN, and the two ranking tables. Add a parity test proving the chosen traversal order matches t8code `linear_id`. Cost: real implementation + the subtle radix arithmetic; benefit: feature parity + range-query/k-NN performance + literature-faithful ordering.
- **Direction B — Formally accept the divergence.** Document that `PyramidKey` is a Morton key, *not* a t8code linear id; that `estimateSFCRange` is intentionally a conservative full-band stub; and that k-NN uses BFS by design. Add a test asserting the documented contract (BFS k-NN correctness; range stub returns valid conservative bounds). Cost: ~0 implementation; benefit: honest contract, no dark capability gap. Risk: leaves a permanent perf gap vs. Octree/Tetree on range/k-NN.
- **Direction C — Hybrid.** Direction B now (document + contract test), but port only `estimateSFCRange` to a *tighter-than-full-band* bound (cheap, biggest single perf win) without full rank/unrank.

**Recommendation pending gate:** Direction C unless a concrete workload needs ranked iteration or locate-by-rank, in which case Direction A.

## Approach (if Direction A or C)

1. Port `parenttype_iloc_pyra_w_lower_id[2][10]` and `type_cid_to_Iloc[8][8]` verbatim into `TetreeConnectivity` (consistent with the no-translation posture).
2. Implement `linear_id`/`fromLinearId` mirroring `t8_dpyramid_bits.c:462-589`, in Morton-key terms.
3. Implement a tight `estimateSFCRange` from the child subtree-size accumulation.
4. (A only) SFC-pruned k-NN sharing the Octree/Tetree lower-bound machinery.
5. **Parity test** (TDD, write first): independent in-test port of `t8_dpyramid_linear_id`; assert ordering equivalence over a whole-domain DFS to level ≥ 6.

## Risks / Open Questions

- The variable-radix arithmetic is the subtle part of t8code's dpyramid; an off-by-one in the subtree-size accumulation silently corrupts ordering. The parity test is the guard and must precede implementation.
- Whether `compareTo` (level-major) and `linear_id` (within-level rank) can be reconciled, or whether reconciliation forces a key-ordering change — must be resolved before committing to Direction A.
- Is there an actual consumer? If no workload needs ranked iteration / tight range / fast k-NN on pyramids, Direction B is the honest answer.

## Research Findings

> Code-verified 2026-05-31 against the current tree (branch `feature/pyramid-t8code-remediation`). All five Problem-Statement claims hold; two stale javadoc rationales materially change the gate calculus.

- **F1 — `estimateSFCRange` is a full-band stub (CONFIRMED).** `PyramidKey.estimateSFCRange:264-286` returns `[(level,0,0), (level,maxLow,maxHigh)]` — every key at the radius-derived level. Verified by read.
- **F2 — `estimateSFCRange` has ZERO production consumers (NEW, decisive for the gate).** Census across `lucien/src/main`: the only references are its own javadoc and definition; the sole caller anywhere is `PyramidKeyTest`. The "is there an actual consumer?" gate question currently answers **no** — nothing in production reads the range, so even tightening it (Direction C) buys nothing measurable today.
- **F3 — k-NN falls back to BFS by design (CONFIRMED).** `PyramidKey` (`:26-28`) implements `SpatialKey` directly and inherits the default **empty** `sfcRangesForKNN`; k-NN cannot prune by SFC locality. `PyramidIndex` provides only `estimateNodeDistance:915` (used for occlusion ordering, `:903-904`), not SFC-range k-NN.
- **F4 — ordering is structurally different from t8code `linear_id`, and unproven (CONFIRMED).** `compareTo:154-167` is level-major, then `(highBits,lowBits)` as a 128-bit unsigned **coarse-dominant** value (Knapp Eq 3.4) — the shallowest step in the MSBs. This is a key-bit total order, NOT t8code's within-level rank traversal. No parity test exists. Reconciling them (Direction A prerequisite) would require either a separate rank function over the key or a key-ordering change.
- **F5 — no linear-id primitive (CONFIRMED).** No `linear_id`/`fromLinearId`/locate-by-rank; encode/decode are path-walks. The two ranking tables (`type_cid_to_Iloc`, `parenttype_iloc_pyra_w_lower_id`) are unported.
- **F6 — both stub rationales are now STALE (NEW).** `estimateSFCRange` javadoc (`:254-255`) and the k-NN-BFS note (`:27-28`) both defer to "until a `locate` primitive exists / lands in a later phase." That primitive **has landed** (RDR-010 — `PyramidIndex` locates points and is the production path). So the "temporary stub pending locate" framing has expired: the stub + BFS are now *deliberate non-implementations*, not blocked work. A decision (B/C/A) is forced; the docs must stop citing an unmet prerequisite that is in fact met.

### Round 2 — range-query consumer research + contract guard (2026-05-31)

> Web research (spatial-index range-query consumers/algorithms/test patterns; report in T3 `582365ee…`) + live-consumer trace + a new guard test. **This round corrects F2 and shifts the gate toward C/A.**

- **F2′ — CORRECTION to F2: a live, public range-query consumer DOES exist.** F2 was scoped too narrowly: the *typed static helper* `PyramidKey.estimateSFCRange` has no production caller, but the **range-query operation** is live and public — `AbstractSpatialIndex.entitiesInRegion()` → `spatialRangeQuery()` → `PyramidIndex.findNodesIntersectingBounds()` (`PyramidIndex.java:437`). Same chain backs `traverseRegion`, `findCollisionsInRegion`. For pyramids this is an **O(n) full scan** (`:437-446`) returning a *superset* of in-region entities. So the gate question "is there an actual consumer?" answers **yes at the operation level** — what is missing is SFC *pruning*, not the consumer. The accelerator (`estimateSFCRange` + a pyramid LITMAX/BIGMIN analogue) is the unbuilt part.
- **F7 — Range queries are THE canonical SFC consumer, and the repo already has the reference pattern (NEW).** Web survey (SQLite R*Tree, PostGIS GiST, Google S2 `RegionCoverer`, Uber H3 polyfill, t8code `t8_forest_search`): box/region queries are the primitive on which sphere, kNN-pruning, frustum cull, collision broad-phase, and spatial joins are built. Standard region→1D-range algorithm is **LITMAX/BIGMIN** (Tropf & Herzog) / recursive octant decomposition; payoff materializes at **large N + high selectivity**, otherwise full-scan wins (PostGIS planner; t8code prune-callback). **Luciferase already implements this for the Morton path** — `lucien/.../sfc/LitmaxBigmin.java`, `MortonTraversal.java`, and `Tetree.java:1789` (small-dataset linear fast path). Direction C/A therefore has a working in-repo template to mirror, not a from-scratch port.
- **F8 — A consumer-contract guard test now exists (NEW).** `PyramidRangeQueryScenarioTest` (6 tests, green against the current O(n) scan) pins the load-bearing invariants the literature tests: **no false negatives** (every in-box entity returned), **exact recovery** (superset ∩ point-filter == brute-force ground truth), a **tightness/FP-ratio** signal, and boundary fixtures (full-domain, sub-voxel, face/corner-inclusive, remote, empty). This is the TDD guard for Direction C/A: any SFC-pruned `findNodesIntersectingBounds` must keep every assertion green while driving the FP ratio down.

**Bearing on the gate (updated).** The Round-1 lean toward **B** rested on "no consumer" (F2) — **now corrected (F2′)**: a live, tested range-query consumer exists, served by an O(n) scan. With a working in-repo LITMAX/BIGMIN reference (F7) and a green contract guard (F8), **Direction C** (port a tight `estimateSFCRange` / pyramid range decomposition behind the existing `findNodesIntersectingBounds`, keeping the small-dataset fast path) is now the cost-justified recommendation — bounded scope, immediate consumer, regression-guarded. **Direction A** (full rank/unrank + SFC-pruned kNN) remains gated on the F4 ordering-reconciliation question and a kNN/ranked-iteration workload that still does not exist. **Direction B** is no longer the default — the "no consumer" premise that motivated it is false.

**Original Round-1 bearing (retained for the record):** F2 + F6 strengthen Direction B as the honest answer absent a workload; F4 shows A is not a drop-in; C's value contingent on a future consumer. *(Superseded by F2′/F7/F8 above.)*

## References

- Gap analysis 2026-05-31, axes 1 & 2 (T1 scratch `gap-axis-1`, `gap-axis-2`; T2 `rdr/pyramid-t8code-remediation-plan-2026-05-31`).
- t8code `main@76a5347b`: `src/t8_schemes/t8_default/t8_default_pyramid/t8_dpyramid_bits.c`, `t8_dpyramid_connectivity.c`.
- Knapp 2026 (catalog `1.12.7`), Eq 3.5–3.7.
