# Post-mortem: RDR-010 — Pyramid Spatial Index

**Closed:** 2026-05-31 (implemented). **Accepted:** 2026-05-28. **Epic:** Luciferase-pi1.

## Outcome

The hybrid hex↔tet partition gap is closed via the Knapp 2026 pyramid construction. `PyramidIndex`/`PyramidKey` shipped with §3b containment, full `AbstractSpatialIndex` conformance, cross-shape neighbor finding (shallow **and** deep), distributed ghost wiring, shape-aware Forest weights + Alg-5.1 partitioner + balance-checker shape-router, and forest event coherence. Implemented across pi1.1–pi1.7 plus follow-ons (4pd, q3p, d3z3, uzyd, 7poh, 3y1, 7eb, l4p0, 0utt, juts, cjwr A+B, 2l04).

## What diverged from the plan

### 1. Finding #16 over-scoped deep-tet as a separate RDR (the headline divergence)

Finding #16 (q3p Phase D, 2026-05-29) concluded that Luciferase's Bey subdivision is a *geometrically different tree* from t8code's dtet below the pyramid boundary (25/48, 36/64 child mismatches), so the borrowed t8code deep-boundary tables were "geometrically meaningless" and deep-tet cross-shape was **blocked pending its own tet-tree-reconciliation RDR**.

This was wrong in its forward conclusion. **Luciferase-4pd** (which landed after the finding) re-aligned Luciferase tet type `k` to t8code dtet type `k`, making the subdivision match t8code exactly (`T8codeDtetOracleTest`). Once aligned, the deep tables applied directly. The *actual* blocker was mundane: the t8code dpyramid source (`t8_dpyramid_bits.c`) had never been fetched into `~/git/t8code` (it lives on `origin/main`, the local checkout lagged). Fetching it + porting `t8_dpyramid_tet_boundary` closed the deep path inside RDR-010 (cjwr) with no new RDR.

**Lesson:** a "this needs its own RDR / fundamental divergence" finding should be re-validated after any subsequent alignment work (4pd) before it ossifies into a scope boundary. The fail-loud guard was the right *interim* call; the "separate RDR" framing outlived its premise. Cheap re-check (fetch the reference, run the oracle) beat the assumed blocker.

### 2. Deep-tet arc is correct infrastructure with no production consumer

cjwr A+B + 2l04 delivered deep cross-shape neighbors + deep SFC keys, all validated. But `PyramidIndex`'s locate primitive stops at the shallowest tet, so **nothing inserts deep tet keys** — the entire deep arc is dark machinery. The repeated reviewer signal ("surfaced-but-unused", "dark until Phase D") was correct and under-weighted at the time; I chained follow-on beads (9hse → kyz9) to *register the remainder* rather than recognizing that the remainder had no demand. At close those speculative beads (9hse, kyz9, tjdc) were honestly closed won't-do.

**Lesson:** "register the deferred remainder" is the right reflex for *real* scope; it becomes backlog-inflation when the thing being deferred has no consumer. Distinguish "deferred because out of phase" from "speculative because nothing needs it." The shallow hex↔tet boundary — RDR-010's actual goal — was always the live target.

## What worked

- **Stacked dual review** (code-review-expert + substantive-critic) at every phase caught real issues the green suite hid: the missing `setNeighborDetector` seam (pi1.3), the `tmIndex`/`locatePointS0Tree` downward-trace bug (4pd), the silent-data-loss `MessageConverter` bug (RDR-004 D3), and repeated scope-honesty corrections. The critic's "dark machinery" persistence is what ultimately produced the honest close.
- **Table-independent + cross-implementation validation**: conforming-face geometry + `Pyramid.faceNeighbor` involution, and whole-domain completeness oracles (refinement vs enumerate-and-filter) — these caught symmetric-miss gaps that reciprocity alone could not.
- **Round-trip-self-check as the SFC validity oracle**: `encode → decode → equals` (hardened with `minTetLevel`) let the neighbor enumerator filter geometric candidates to genuine SFC elements without a separate validity table.

## Open after close

- `Luciferase-401t` (backlog) — element-level spanning; optional, cube-granular fallback shipped.
- If a workload ever needs deep-tet insertion or a distributed pyramid deployment, reopen `9hse` / `tjdc` (and `kyz9` for face validation).
