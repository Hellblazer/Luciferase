# Post-Mortem: RDR-012 — PyramidIndex Domain Contract & Deep Cross-Shape

**RDR:** [RDR-012](../RDR-012-pyramid-deep-cross-shape-productionization.md)
**Status:** closed (implemented) — 2026-05-31
**Accepted:** 2026-05-31 · **Closed:** 2026-05-31 (same-day execution)
**Type:** Architecture
**Port baseline:** t8code `main@76a5347b` (Feb 2024)

## What was decided

Two gate questions, both resolved:

- **Gate-Q0 (domain contract) → D0.** Specified the reachable-SFC predicate (§D0.1 C-1..C-5) and confirmed `PyramidKeyCodec.encode()` is the correct, complete characterization of PyramidIndex's **cube-rooted** domain (root pyramids 6+7 + root tets). `encode()` is *not* `is_inside_root`-equivalent and must not be made so — `is_inside_root` is a single-root-pyramid (per-tree) test; PyramidIndex spans the whole cube. **D_fix not triggered**: the 26569 over / 17208 under oracle split is fully explained by the cube-vs-single-root domain difference, not a residual `encode()` reachability bug.
- **Gate-Q1 (deep-path fate) → D2 + D3.** Deep pyramid-rooted tet cross-shape machinery documented **infrastructure-only** (D2.1) and boundary-pinned (`PyramidBoundaryPinningTest`, D2.2 — locate/insert never emits a tet with `0 ≤ minTetLevel < level`). Shipped the independent deep tet-source t8code parity oracle (`T8codeDpyramidTetBoundaryOracleTest`, D3.1), closing gap-axis-4. **D1 (productionize) explicitly deferred, reopen-only** — `Luciferase-l8nz` / `Luciferase-9hse` reopen when a concrete deep-insertion workload appears.

## Outcome vs. plan

Executed as accepted. No scope reduction — every §Decision direction (D0.1, D0.2, D2.1, D2.2, D3.1) closed with a named test artifact. The ex-remediation-P1 face-neighbor oracle was successfully folded in: its data is what *forced* the domain-contract framing, retiring the original B1/B2 directions as mis-framed.

## What went well

- **Fold-in caught a wrong premise early.** The P1 oracle data (over/under split) collapsed P1's "make `encode()` match `is_inside_root`" goal: B1 would have nulled ~half the valid cube-domain neighbors, B2's premise (encode ≈ is_inside_root) was simply false. Building the oracle before committing to a direction prevented implementing the wrong fix.
- **Fence-don't-productionize was the right call.** Deep cross-shape is validated topology with no live consumer. D2's boundary-pinning test converts the "dark machinery rots undetected" risk into a tripwire; D3's parity oracle replaces self-consistency (involution-only) with independent t8code table parity. Cheaper and lower-risk than D1's high-blast-radius locate change.

## What went wrong / lessons

- **A review finding was fabricated, then retracted.** During the D3.1 stacked review (bead `twaf`), a "BEYID table divergence" finding asserted specific divergent Luciferase values that did not exist — they were invented, not read from the code. Caught and retracted same-day (T2 `rdr/012-d3.1-twaf-FINDING-beyid-table-divergence`, marked RETRACTED). **Lesson:** review findings that cite concrete divergent values MUST quote them from a real read of both sources (file:line on each side); a critique mandate to *independently verify against the full source* is the guard, and it was added to the second critique pass.
- **"Counted, not validated" is a real correctness blind spot.** The deep tet-return branch had been *counted* (involution DFS) but never asserted face-by-face against an independent port. Self-consistency tests pass even when both sides share the same bug. The D3.1 oracle (independent transcription, not a re-use of production tables) is the correct discipline for any ported table.
- **Gate critique caught a transcription risk.** The gate flagged that `oracleIsInsideRoot()` applied the apex-face tie-break to tet-typed candidates; verification against `t8_dpyramid_is_inside_root:895-896` confirmed t8code applies it as a flat `type`-field check with no shape gate — transcription faithful, counts stand. Ground-truth anchor cases were added to lock it. **Lesson:** anchor-lock ported predicates with hand-verified ground-truth cases, not just aggregate counts.

## Follow-ups (open by design)

- `Luciferase-l8nz` — D1 productionize deep tet insert/query. **Deferred, escalation-only.** Reopen `Luciferase-9hse` (locate-deep-tet) + `kyz9` (deep oracle) only on a concrete deep-insertion workload. High blast radius: changes index cardinality + locate invariants; spanning/balancing/ghost all assume shallow-only.
- Open question carried forward: does the hybrid-mesh / CFD use case require deep tet *queries*, or only deep *geometry* at construction time? The answer is the D1 trigger.

## Key artifacts

- Tests: `T8codeDpyramidFaceOracleTest` (D0.2, pyramid-source parity), `PyramidBoundaryPinningTest` (D2.2, shallow-only-live contract), `T8codeDpyramidTetBoundaryOracleTest` (D3.1, tet-source parity).
- T2: `Luciferase_rdr/012`, `rdr/rdr-010-8xus-oracle-findings-2026-05-31`, `rdr/012-d2d3-deep-critique-2026-05-31`.
- Reference: t8code `main@76a5347b` `t8_dpyramid_bits.c` (`is_inside_root:883`, `face_neighbour:599`, `tet_boundary:822`).
