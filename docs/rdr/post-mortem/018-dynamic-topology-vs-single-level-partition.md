# Post-Mortem: RDR-018 — Dynamic Topology (Split/Merge) vs the RDR-015 Single-Level Migration Partition

**RDR:** RDR-018
**Closed:** 2026-06-07 · **Reason:** implemented
**Decision:** Option B — mixed-level hierarchical router (locked + gate PASSED 2026-06-06)
**Epic:** Luciferase-3iony (parent: Luciferase-0frcy simulation deep-review remediation)

## Outcome

Shipped Option B in full. The migration router was generalized from the RDR-015 flat
single-level tiling to a **refinement forest of leaf bubbles**, resolved by a `TetreeKey`
parent-key up-walk (`maxLeafLevel` watermark, per-step `containsBubble`, terminating toward
L0, `null` on out-of-bounds) — never the rejected level-0-first scan (R1). RDR-015 was **not
reverted**: its single-level partition is the depth-0 base case of the up-walk.

All eight implementation ACs landed, each through stacked review (code-review-expert +
substantive-critic; T2 critique/review memos retained per bead):

| AC | What | Bead |
|----|------|------|
| AC-0 | `TopologyExecutor.execute()` returns documented failure (not exception/no-op) on `SplitProposal` pre-B-core; pinning test | a4ser |
| AC-4 | Merge coverage-hole hard-fenced fail-loud (interim), then superseded by real merge (q37mx) | t8lg1 |
| AC-2.5 | `BubbleSplitter` redesigned as true Bey refinement (parent L leaf → 8 L+1 children, assigned by `contains12DOP`); `SplitPlaneStrategy` no longer selects keys; `AdaptiveSplitPolicy.performSplit` replaced | 6a5o7 |
| AC-3 prereq | Coverage-preserving inverse-Bey sibling-collapse merge (re-tile + atomic parent re-registration; lifts AC-4 fence) | q37mx |
| AC-1 | Option B design validated: leaf-partition invariant (open-interior + `contains12DOP` tie-break), up-walk + `maxLeafLevel` watermark, capacity model RQ-2 stated | 0sxck |
| AC-2 | Router invariants re-stated for Option B; `getPartitionLevel()` → base-level rename at the `## Decision` call sites | pg344 |
| AC-3 | Split+merge migration regression (entities in cell interiors; validated by **involution reciprocity**, never shared-vertex count) | viurt |
| AC-6 | `TetreeGhostSyncAdapter` neighbor finder at each bubble's own level + neighbor-cache invalidation on leaf-set change; refined-leaf ghost reaches coarser neighbor | wu7vn |
| AC-7 | Explicit-deferral discipline (no silent scope reduction) | xtyki (tracker) |

The original defect **Luciferase-9eyqy** (split pushes the grid to mixed-level → flat
`getPartitionLevel` router silently mis-routes/drops the finer cell) is the exact failure
Option B's up-walk router resolves; closed as resolved-by-RDR-018.

## Key findings (validated during implementation)

- **F1** — Split/merge are *off the live tick path*, so the pre-B-core fences were free
  (no production op was lost). This is why AC-0/AC-4 could be interim without behavioral risk.
- **F2** — The original `BubbleSplitter` was a plane-based logical split keyed by a single
  L+1 centroid tet: geometrically incompatible with a refinement forest **and** self-defeating
  under live migration (entities outside the centroid tet route straight back to source next
  tick). Masked only because split is off the live path (F1). This forced the AC-2.5 redesign —
  the plane strategy was obsolete for key selection, not merely suboptimal.
- **F4** — The merge path had a genuine coverage hole (`BubbleMerger` arbitrary two-bubble
  removal). Unlike split (where an RDR-012 D2 doc-only boundary note sufficed), merge required
  real code: first a hard fail-loud fence (AC-4), then the coverage-preserving inverse-Bey
  sibling-collapse re-tile (q37mx) that lifted the fence.

## Divergences from the accepted decision

- **Merge re-tile was a discovered scope gap, not in the original AC set.** AC-3 (viurt)
  silently *assumed* a coverage-preserving merge existed; AC-4 had taken the hard-fence branch
  of its "fix OR fence" choice and AC-2.5 only touched the splitter. The gap (no merge re-tile
  code anywhere, referenced as "lands with B-core" in 5 comment sites) was caught during AC-3
  scoping and closed **explicitly** with a new tracked bead (q37mx) inserted as a viurt
  dependency — rather than letting AC-3 pass vacuously. This is the discipline working as
  intended: the gap surfaced and was tracked, not buried.

## Deferred scope boundaries (carried forward — all explicit on xtyki, none silent)

1. **Observability disambiguation** — fence-rejections are metrically indistinguishable from
   genuine attempt-then-rollback failures (`MergeEvent(success=false)` + `topology_merges_failed_total++`
   for AC-4 fence; AC-0 fenced splits record neither successful nor failed splits — WARN log
   only). A dedicated `MergeBlockedEvent`/reason enum + distinct `topology_merges_fenced_total`
   (and a split fence counter) deferred to Stage-2 observability — no consumer exists yet.
2. **Collapse cooldown reservation** — `TopologyConsensusCoordinator.getAffectedBubbles`
   reserves only the anchor child, not the full 8-sibling set. Safety holds (executionLock
   serializes; the incomplete-sibling-set check fails loud). Under high-frequency concurrent
   collapse on the same set this is wasted serialization / spurious `recordMergeFailure`
   (livelock-flavored inefficiency, not a correctness bug). Full sibling-set reservation deferred.
3. **Ghost-store consistency on a LIVE split/merge** — benign today (split/merge off the live
   tick path, F1/RDR-012 D2). `TetreeGhostSyncAdapter` builds per-bubble ghost infra only at
   construction: (a) split-created children have no ghost infra → neither send nor receive ghosts;
   (b) `removeBubble` doesn't reclaim `ghostsByBubble[removedId]` → stale until TTL. Fix when
   topology is productionized into `tick()`: register on `addBubble`, purge on `removeBubble`.
   The *topological* half of AC-3 Obs3 (no removed-bubble key returned as ghost neighbour) IS
   covered (`TetreeGhostCrossLevelTest.cacheInvalidated_afterRefine_staleParentNotReturned`);
   this is the ghost-STORE half only.

## Pre-declared out-of-scope (re-confirmed still out of scope at close)

- Rebalancing **policy** — *when* to split/merge (this RDR is the mechanism, not the trigger).
- The consensus path certifying topology proposals.
- Distributed / multi-node partition coordination.

## Follow-on work

- **Productionization gate:** the deferred boundaries (1–3) and the related bug class become
  live the moment split/merge moves onto the `tick()` path. That productionization is itself a
  future RDR/bead, not implied by this close. Until then, a steady-state simulation may run the
  topology ops only off the live path.

## References

- RDR: `docs/rdr/RDR-018-dynamic-topology-vs-single-level-partition.md`
- T2: `Luciferase_rdr/018`, `018-research-1`, `018-research-1-verification`, `018-gate-latest`
- Per-AC stacked-review memos: T2 `Luciferase_*` (`*-code-review.md`, `*-critique.md`)
- Scope ledger: bead `Luciferase-xtyki` (5 explicit deferral comments)
- Related: RDR-015 (single-level partition / dead-migration revival), RDR-012 (deep cross-shape D2 boundary)
