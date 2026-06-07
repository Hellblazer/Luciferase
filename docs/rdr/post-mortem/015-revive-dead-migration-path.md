# Post-Mortem: RDR-015 — Reconcile Simulation Bubble-Grid Coordinate Space (Revive the Dead Migration Path)

**Closed:** 2026-06-06 · **Reason:** implemented · **Branch:** `feature/j8p8n-rdr-015-coordinate-space` (commits `5a88ec2b..6e251328`)

## What shipped

`MultiBubbleSimulation`'s bubble-to-bubble migration was dead on the live path (`tick()` committed
zero migrations). Under Option B it now commits migrations end-to-end — ≈1982 over 3000 ticks at 200
entities, **0 failures, exact entity conservation**.

The fix had three independent parts:
1. **Coordinate contract (AC1):** entities are WorldBounds-scale Cartesian, placed directly into the
   Tetree absolute coordinate space without rescaling. The aspirational "RDGCS coordinates" javadoc was
   corrected; no world↔RDGCS scale transform was introduced (Option C rejected).
2. **Single-level partition (AC2/AC3, AC4):** `TetreeBubbleGrid.createBubbles(int, WorldBounds, long)`
   builds a same-level adjacent partition tiling the world domain (level chosen by
   `lengthAtLevel(L) <= size/cbrt(N)`, seed at centre, BFS over involution-reciprocal face neighbors,
   cell-AABB-overlap inclusion for boundary coverage). The router queries the partition level directly
   instead of a level-0-first scan.
3. **Fixed-cell containment (AC5/AC6):** escape is tested against each bubble's fixed registration cell
   (`EnhancedBubble.spatialKey`), not the adaptive entity-derived bounds.

## Key divergence — incomplete root-cause analysis (the main lesson)

The RDR's accepted root-cause analysis named **two** causes (coordinate-space mismatch + mixed-level
non-partition grid). After P1 + P2 fixed both, an end-to-end diagnostic still committed **zero
migrations**. A **third, independent cause** surfaced only during P3 implementation (recorded as finding
**F6**): `checkMigrations` tested escape against `EnhancedBubble.bounds()` — the *adaptive* AABB that
`BubbleBoundsTracker` recomputes from the entities every tick. That box always wraps its own entities,
so `!contains(position)` was never true, and the hysteresis gate shared the same false negative.

Why the analysis missed it: the diagnostic evidence in the RDR (`escapedBounds=0`) is equally explained
by *either* the scale mismatch *or* the adaptive bounds. The investigation attributed the symptom to the
first cause it found and stopped. **Lesson:** when a symptom has multiple sufficient explanations, fixing
one and re-measuring is the only way to confirm — a green unit test at one layer (the directed router
probe) masked a dead gate one layer up. The directed regression was consequently upgraded from a
unit-level router probe to a true end-to-end subsystem test.

## Process notes

- The stacked review (code-review-expert + substantive-critic) returned **0 Critical**; both caught real
  Significant items. Applied immediately: boundary-coverage tiling + coverage test, negative-bounds
  guard, seed null-check, doc corrections.
- **F6 was added to an already-accepted RDR** without re-running the gate (critic finding #4). This is a
  process gap: the gate record predates the third cause. It was accepted here because the fix is squarely
  in service of the RDR's title ("revive the dead path") and was documented, not silent — but a stricter
  reading would re-gate.

## Deferred (filed, not silent)

- `Luciferase-9eyqy` — dynamic topology (`BubbleSplitter` inserts `parentLevel+1` children) re-breaks the
  same-level partition; explicitly out of scope per the RDR Scope decision (AC7).
- `Luciferase-6kod9` — escape uses an RDG-AABB outer approximation, not exact tetrahedral containment.
  This is why the directed test asserts the specific *containing* cell (anti-catch-all, non-vacuous) but
  not strict *face*-adjacency; the exact-containment switch also reworks the hysteresis gate.
- `Luciferase-0941e` — the partition over-produces bubbles vs the requested count (~162 for N=8); `count`
  is effectively a granularity hint.

## ACs

All eight satisfied. AC1→P2, AC2→P1, AC3→P0/P1, AC4→P2, AC5→P3, AC6→P3, AC7→`9eyqy` (P4),
AC8→`0frcy.131` closed (P4). phase-review-gate PASSED.
