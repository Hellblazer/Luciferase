# Post-Mortem: RDR-001 — Axis-Aligned Bounding Tetrahedra (AABT)

**Closed**: 2026-03-22 | **Reason**: Implemented | **Epic**: Luciferase-o3w (8/8 tasks)

## What Went Right

1. **Bug fixes were the biggest win.** Two real intersection bugs fixed in Phase 1 — false negatives in `tetrahedronIntersectsVolume()` and false positives in `tetrahedronIntersectsVolumeBounds()`. These affect correctness for all Tetree users, independent of AABT.

2. **Type system cleanup.** The `aabt` record was a 6-float AABB stub masquerading as tetrahedral geometry. Replacing it with an interface backed by `Tet` using `containsUltraFast()` and SAT intersection is architecturally correct. Simplex and VolumeBounds.from() bugs also fixed.

3. **Pre-migration test suite.** 43 tests covering all 9 callsites before the atomic migration prevented regressions during the interface change.

4. **Benchmark-driven pivot.** The P3.2 benchmark disproved the original AABT-traversal hypothesis before we committed to it in production code. The pivot to post-filter was data-driven.

## What Went Wrong

1. **Original thesis disproven.** The core hypothesis — that AABT traversal would be faster by pruning 50-80% of candidates — was wrong. SAT per-tet cost (~100 ops) is 10-100x more expensive than AABB (6 comparisons). The candidate reduction is real but the per-test overhead swamps it at every query size tested.

2. **Paper application was superficial.** The arxiv paper (2603.18458) on axis-aligned relaxations for MINLP was inspirational but the transfer to spatial indexing was weaker than claimed. The corner-point sufficiency result was standard convexity, not novel. The voxelization convergence result was relevant but the practical benefit was limited by SAT cost. Three rounds of gate critique didn't catch this because the mathematical analogy was plausible.

3. **Representation concern raised late.** The question "is a single Tet sufficient as a bounding volume?" was raised by the project owner after acceptance. Deep analysis confirmed Tet is sufficient for the current architecture (tree-node-bound only), but this fundamental design question should have been addressed during research, not after implementation started.

## Divergences from Plan

| Planned | Actual |
|---------|--------|
| Sealed interface (`sealed`) | Non-sealed interface (no `module-info.java` for JPMS) |
| AABT replaces AABB traversal | AABB traversal + AABT post-filter |
| 6x candidate reduction | 50-80% for tet queries, 0% for box queries |
| Phase 4: broad collision/frustum migration | Phase 4: narrow — only `findCollisionsInRegion` for aabt regions |

## Metrics

| Metric | Value |
|--------|-------|
| Tests added | 67 (from 2420 to 2487) |
| Bugs fixed | 2 (intersection false negatives + false positives) |
| Callsites migrated | 9 (atomic commit) |
| Candidate reduction (tet queries) | 50-80% |
| Candidate reduction (box queries) | ~0% |
| AABT traversal speedup | None (10-100x slower) |
| Post-filter benefit | Narrow (tet-shaped collision queries only) |

## Lessons

1. **Benchmark before committing to a traversal strategy.** The spike should have been Phase 1, not Phase 3. We built the type system infrastructure before knowing if the performance hypothesis held.

2. **Per-test cost matters more than candidate count.** Reducing candidates by 80% is useless if each candidate test costs 16x more. The crossover analysis should have been done on paper before implementation.

3. **Bug fixes and type cleanup are independently valuable.** Even though the grand vision didn't materialize, Phases 1-2 delivered real value. Separate "fix what's broken" from "add new capability."

4. **Gate critique has blind spots for performance hypotheses.** Three rounds caught structural issues (callsite inventory, migration atomicity, method naming) but none questioned whether SAT cost would offset candidate reduction. Performance claims need benchmarks, not just architectural review.
