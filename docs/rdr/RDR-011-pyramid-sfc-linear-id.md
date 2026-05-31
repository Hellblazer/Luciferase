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

## References

- Gap analysis 2026-05-31, axes 1 & 2 (T1 scratch `gap-axis-1`, `gap-axis-2`; T2 `rdr/pyramid-t8code-remediation-plan-2026-05-31`).
- t8code `main@76a5347b`: `src/t8_schemes/t8_default/t8_default_pyramid/t8_dpyramid_bits.c`, `t8_dpyramid_connectivity.c`.
- Knapp 2026 (catalog `1.12.7`), Eq 3.5–3.7.
