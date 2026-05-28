---
title: "Post-Mortem: Prism Full-Cube Coverage via Two-Prism Cover"
rdr: RDR-009
status: implemented
implemented_date: 2026-05-27
duration: 1 day (accept → implement, 2026-05-26 → 2026-05-27)
epic_bead: Luciferase-b3k
author: hal.hildebrand
backfill_note: |
  This post-mortem was reconstructed 2026-05-28 from the T2 entry
  Luciferase_rdr/009 and the merged PRs (#112-#124). The original close-
  ceremony shipped the T2 metadata but missed writing this file. No
  semantic divergence from the T2 record.
---

# RDR-009 Post-Mortem

## Outcome

`PrismKey` now covers the full unit cube via a two-prism cover. Two prism families — **S0** (lower-right half, `y <= x`) and **S1** (upper-left, `y >= x`, reflection of S0 across `y = x`) — distinguished by a `half` bit on `Triangle`. The SFC is per-half tetrahedral-Morton `consecutiveIndex` (3 bits/level, 63-bit at `MAX_LEVEL=21`, no overflow); `PrismKey.compareTo` is half-major (two contiguous SFC blocks). Cross-diagonal neighbors via `Triangle.faceNeighbor`; subdivision tiles both halves; ray/range/k-NN/collision traverse both families.

13 PRs shipped in two days (P1 through P7 + 6 follow-ups). Epic bead `Luciferase-b3k` closed; originating feature bead `Luciferase-fzm` closed as fulfilled.

## Phase shipping ledger

| Phase | Bead | PR | Scope |
|-------|------|----|-------|
| P1 | `al5` | #112 | Prism-half encoding (initial) |
| P2 | `4ky` | #113 | SFC ordering + `compareTo` (63-bit, no overflow at level 21) |
| P3 | `7iu` | #114 | Prism-half encoding refinement |
| P4 | `ner` | #115 | Cross-diagonal neighbors (`Triangle.faceNeighbor`) |
| P5 | `dvk` | #116 | Subdivision tiles both halves |
| P6 | `fok` | #117 | Query ops traverse both halves |
| h65 | — | #118 | Sparse-k-NN/region completeness (touched `AbstractSpatialIndex.getCellSizeAtLevel` int→float) |
| Gate-B cleanup | — | #119 | Phase-review-gate fallout |
| P7 | `3hw` | #120 | Versioned `PrismKeySerde` (backward-compat + migration) |
| q8z | — | #121 | `StackBuilder` + Prism test |
| d41 | — | #122 | Removed vestigial `Triangle.n` + 5-arg ctor + `getN` |
| a7r | — | #123 | Flaky `testGhostSync` root-cause fix (cold-start measurement → warmup + best-of-N) |
| 1k9 | — | #124 | Triangle centroid = vertex mean, not cell center |

## §Approach cross-walk

All 7 numbered §Approach items delivered (PHASE-REVIEW-GATE Pass 2 cross-walk passed):

1. **Prism-half encoding** → P1 (`al5`) + P3 (`7iu`)
2. **SFC ordering + `compareTo`** → P2 (`4ky`)
3. **Cross-diagonal neighbors** → P4 (`ner`)
4. **Subdivision** → P5 (`dvk`)
5. **Query ops both halves** → P6 (`fok`)
6. **`consecutiveIndex` level-11 overflow** → P2 (`4ky`, 63-bit)
7. **Backward-compat / migration** → P7 (`3hw`, versioned `PrismKeySerde`)

## Option B (document-as-half-cube-specialist fallback)

**NOT taken.** The fallback trigger fired once at `h65` — `getCellSizeAtLevel` int→float, which is an `AbstractSpatialIndex` change (and so violated the "no facade churn" preference for the Prism work). The change was surfaced to the user, the trade-off was re-weighed (versus the maintenance cost of a parallel half-cube-specialist documentation path), and the decision was to **proceed with Option A + the contained ASI change**. The change shipped in #118 and propagated correctly through the four subclasses (Octree, Tetree, Prism, SFCArrayIndex) — the int→float widening is exact for the integer-coordinate indices and meaningful (non-truncated) for Prism's normalized `[0, 1)` coordinates.

## Follow-ups completed in the arc

- **`h65`** — sparse-k-NN/region completeness. The `getCellSizeAtLevel` int→float surfaced gaps in the k-NN expanding-radius search at the unit-cell boundary; Prism's normalized coordinates required the change. Now `knnRequiresFullDomainSweep()` returns `true` for Prism so the safety sweep catches what the SFC-range pruning misses.
- **`q8z`** — `StackBuilder` + Prism integration test (the bulk-insert path was missing Prism coverage).
- **`d41`** — vestigial `Triangle.n` removed; the 5-arg ctor and `getN` accessor went with it (the `n` field was a stale subdivision artifact from the pre-half design).
- **`a7r`** — flaky `testGhostSync` root-cause = cold-start measurement on small workloads. Fixed via `PerfMeasure.warmup(N)` + `PerfMeasure.bestNanos(K)` (later codified at RDR-008 P5/P6 as the canonical perf-test pattern).
- **`1k9`** — Triangle centroid is the vertex mean, not the cell center. Subtle: for a non-equilateral triangular base the centroid (1/3, 1/3, 1/3 in barycentric) deviates from the cell center; the bug was using the cell center as a stand-in.

## Lessons

1. **Mechanical-extension Option A beats specialist Option B when a single shared invariant survives.** The `half` bit + per-half SFC kept the existing `SpatialKey<K>` contract intact and reused every `AbstractSpatialIndex` path; Option B would have forked the documentation + traversal logic for a half-cube specialist. The single ASI change at `h65` was a tractable cost.
2. **Cold-start measurement is the perf-flake default cause.** `a7r`'s pattern (warmup + best-of-N) became the standard pattern across the arc and propagated to RDR-008 P5/P6 and the `Luciferase-tlb`/`Luciferase-4zf` perf-flake cleanups. Lock it in early as the project's perf-test idiom.
3. **A Triangle's centroid is not its cell center.** Subtle geometric truth that's easy to gloss over in a "prism is a half-cube" mental model.

## Knowledge artifacts

- T2 `Luciferase_rdr/009` — close metadata + outcome summary
- T2 `Luciferase/rdr-009-continuation` (if present) — session resume pointers
- Bead `Luciferase-b3k` (epic, closed) + child beads `al5`, `4ky`, `7iu`, `ner`, `dvk`, `fok`, `3hw` (phase beads, closed) + follow-up beads `h65`, `q8z`, `d41`, `a7r`, `1k9`
- This post-mortem at `docs/rdr/post-mortem/009-prism-full-cube-coverage.md`
