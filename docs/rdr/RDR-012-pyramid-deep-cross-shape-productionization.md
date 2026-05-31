---
title: "PyramidIndex Domain Contract & Deep Cross-Shape — Define the Reachable-SFC Set, Productionize the Deep-Tet Path or Fence It"
id: RDR-012
type: Architecture
status: draft
priority: medium
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-31
related_issues: [RDR-010, Luciferase-8xus, Luciferase-ogm2]
---

# RDR-012: PyramidIndex Deep Cross-Shape Productionization

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

RDR-010 implemented deep pyramid-rooted tet cross-shape connectivity (`l > minTetLevel`) — `Tet.faceNeighborElement` via the ported `t8_dpyramid_tet_boundary` corner-walk (cjwr A), deep tet SFC keys (`encode(Tet)`/`elementFromKey` accept `minTetLevel < level`, cjwr B), and full-depth edge/vertex neighbors (`allShapeNeighbors`, 2l04). This machinery is implemented and **involution-tested**.

But it is **dark machinery**: `PyramidIndex`'s locate primitive stops at the shallowest tet leaf, so **deep tet keys are never inserted into the live index**. The deep neighbor code is reachable only via direct refinement and tests, never through normal index operation. RDR-010's Close section states this honestly. Two consequences:

1. **Capability with no consumer.** Validated infrastructure that production never exercises is a maintenance liability and a correctness blind spot — it can rot undetected because no live path covers it.
2. **No independent t8code oracle for the deep path** (gap-axis-4). The deep tet-return branch of `tetBoundary` is *counted*, not validated face-by-face against an independent `t8_dpyramid_tet_boundary` port. The planned oracle bead `Luciferase-kyz9` was closed won't-do. So the deep corner-walk rests on self-consistency (involution), never on table parity with t8code.

This RDR decides the deep path's fate: make it live, or formally fence it off.

### Folded in: the PyramidIndex domain contract (ex-remediation-P1, bead `Luciferase-8xus`)

Remediation P1 set out to build the missing t8code face-neighbor oracle and resolve an `is_inside_root` divergence (raw `Pyramid.faceNeighbor` gates on a cube AABB `[0, MAX_COORD]`; t8code gates on the root-pyramid simplex `x>=z, y>=z` + apex-face tie-breaks). The oracle was built (`T8codeDpyramidFaceOracleTest`, an independent transcription of t8code `main@76a5347b`), and its data **collapses P1's framing into this RDR's domain question.** Over 18,660 pyramids (level ≤ 5):

| Measurement | matched | over-permissive | under-permissive |
|---|---|---|---|
| Raw `Pyramid.faceNeighbor` (cube-AABB gate) | 48071 | 45229 | 0 |
| Effective (through the `encode()` filter the detector uses) | 49523 | 26569 | 17208 |

Findings:
3. **The `encode()` filter is NOT `is_inside_root`-equivalent** — it diverges in *both* directions (over 26569 / under 17208). The gap-analysis "de-facto filter" assumption is false.
4. **The divergence is a domain-model difference, not a localized bug.** t8code's `is_inside_root` tests membership in a *single root-pyramid simplex*; `PyramidIndex` is *cube-rooted* (root pyramids 6 **and** 7, plus root tets). Neighbors leaving root-pyramid-6 legitimately enter root-pyramid-7 or a root tet in Luciferase's cube partition — valid elements that live in a *different t8code tree*. A probe reconstructing the 17,208 under-permissive tet neighbors with the corrected `minTetLevel = level` **still** failed `encode()`, ruling out a missing-metadata bug: they are genuinely unreachable in Luciferase's pyramid SFC.

**Consequence — the original B1/B2 directions are both wrong as framed.** B1 (embed `is_inside_root` in the primitives) would null ~half the valid cube-domain neighbors; B2's premise (encode ≈ `is_inside_root`) is false. The real, prerequisite question is **what *is* `PyramidIndex`'s domain and reachable-SFC set, and is `encode()` the correct characterization of it** — which is the same "what is live" question the deep-tet decision below turns on. Hence the fold-in. (Full data: T2 `rdr/rdr-010-8xus-oracle-findings-2026-05-31`.)

## Context

- The live, shipping capability is the **shallow hex↔tet boundary** (`l == minTetLevel`) — fully exercised and sufficient for RDR-010's stated need.
- "Closed won't-do" beads from RDR-010 that bound this decision: `Luciferase-9hse` (locate-deep-tet primitive), `Luciferase-kyz9` (deep-FACE completeness oracle), `Luciferase-tjdc` (production distributed pyramid bootstrap). They are reopen-if-needed.
- Reference: `t8_dpyramid_bits.c` `t8_dpyramid_tet_boundary:822`, `t8_dpyramid_is_inside_root:883`, `t8_dpyramid_face_neighbour:599`. Luciferase: `Tet.tetBoundary:1899`, `PyramidNeighborDetector`.
- The P1 face-neighbor oracle (`T8codeDpyramidFaceOracleTest`) is **already built** (bead `Luciferase-8xus`, branch `feature/Luciferase-8xus-t8code-faceneighbor-oracle`), committed `@Disabled` as a characterization harness — asserting raw t8code parity is the wrong target until the domain contract below is defined. It covers the pyramid-source branch; extending it to the deep tet-return branch subsumes the `kyz9` gap.
- **Precedence vs RDR-011 (not coupling):** RDR-011's Direction A (port linear-id) would *benefit from* D0's reachable-SFC predicate as context, but is **not blocked by it** — RDR-011 may gate and be accepted independently. They share the SFC as subject matter, not a dependency.
- **Oracle transcription verified (critical refuted at gate):** the gate critique flagged that `oracleIsInsideRoot()` applies the `(type∈{3,5})`/`(type∈{0,4})` tie-break to tet-typed candidates. Checked against `t8_dpyramid_is_inside_root` (`t8_dpyramid_bits.c:895-896`): t8code applies that tie-break as a **flat check on the element's `type` field with no shape gate**, and calls it on the neighbor (tet type 0/3 or pyramid 6/7). The transcription is therefore faithful; the 26569/17208 counts stand. Ground-truth anchor cases were added to the oracle to lock this.

## Decision (DRAFT — gate questions open)

**Gate question 0 (prerequisite — domain contract):** What is `PyramidIndex`'s domain and reachable-SFC set, and is `PyramidKeyCodec.encode()` the correct, complete characterization of it? Specifically: is the index cube-rooted (pyramids 6+7 + root tets) by design, and what is the intended single-tree↔cube mapping between Luciferase's reachability and t8code's `is_inside_root`? This must be answered first — it determines what the oracle should assert and whether the `encode()` filter has correctness gaps (the 26569/17208 split is unexplained until the contract is pinned).

- **Direction D0 — Define & validate the domain contract (mandatory prerequisite, decoupled).** Write down the reachable-SFC predicate, reframe `T8codeDpyramidFaceOracleTest` to assert against Luciferase's reachable set with an explicit single-tree↔cube mapping, and either confirm `encode()` is correct against that contract or pin the gap. **D0 is mandatory regardless of which of D1/D2/D3 is selected, and inherits P1-level urgency from the fold-in — it must proceed independently of (and need not wait on) the deep-path gate decision.** It gets its own bead and its own enable-the-oracle deliverable. Scope note: the existing oracle sweeps **pyramid-source** faces only (`Pyramid.faceNeighbor`); tet-source cross-shape neighbors (`Tet.faceNeighborElement` from a pyramid-child tet) are NOT yet covered and are picked up in D3 step 4. Closes ex-P1.
- **Direction D_fix — If D0 finds a live gap (conditional, blocks D2/D3).** If D0 concludes the intended contract *requires* neighbors `encode()` currently drops (some of the 17208 under-permissive set), that is live detector remediation, not a characterization mismatch: define and implement the correct shallow-boundary reachability gate, with the reframed oracle as the acceptance test. This **blocks the D1/D2/D3 choice** until resolved — you cannot decide the deep path's fate on top of a broken shallow contract.

**Gate question 1 (deep path):** Is there (or will there be) a workload that needs deep tet elements *inserted into and queried through* the live `PyramidIndex`?

- **Direction D1 — Productionize.** Extend the locate primitive (reopen `Luciferase-9hse`) so refinement past `minTetLevel` inserts deep tet keys; wire k-NN / range / neighbor queries to traverse them; build the deep-path t8code oracle (reopen `kyz9`) as the acceptance gate. Cost: touches `PyramidIndex` insert/locate invariants — significant and risk-bearing. Benefit: the deep machinery becomes real, covered, and useful.
- **Direction D2 — Mark infrastructure-only (recommended absent a consumer).** Formally document (architecture docs + a class-level marker / `@ApiStatus`-style note) that deep cross-shape is validated *topology infrastructure* with no live consumer, not a production query path. Add a single guard test pinning the boundary (locate does not emit deep keys) so the "shallow-only live" contract can't silently drift. Keep the involution tests as the deep-path regression guard. Cost: ~minimal. Benefit: removes the blind-spot ambiguity; honest scope.
- **Direction D3 — Minimal hardening, defer productionization.** D2 + build the deep-path t8code parity oracle anyway (the P1 oracle), so the dark machinery is at least *correct* against t8code even while unconsumed. Splits the difference: no productionization risk, but closes the correctness blind spot.

**Recommendation pending gate:** **D0 first** (define the domain contract — non-optional; the oracle is built but blocked on it), then **D3** — reframe the existing oracle to assert against Luciferase's reachable set (validating both shallow and deep), and document infrastructure-only status. Escalate to D1 only when a concrete deep-insertion workload appears.

## Approach (D0, then D2/D3)

1. **D0:** Specify `PyramidIndex`'s reachable-SFC predicate and the single-tree↔cube mapping to t8code. Reframe `T8codeDpyramidFaceOracleTest` to assert against Luciferase's reachable set; confirm `encode()` is correct against the contract or pin the gap (explains the 26569 over / 17208 under split). Re-enable the test as a pass/fail gate.
2. Document deep cross-shape as infrastructure-only in `CLAUDE.md` (the stale "fail-loud guarded" text is already corrected — remediation P0), RDR-010 cross-refs, and a class-level note on the deep-path methods.
3. Add a boundary-pinning test with the **correct** invariant: every tet element `PyramidIndex` locate/insert emits has `minTetLevel == -1` (pure-Tetree) **or** `minTetLevel == level` (shallow pyramid-boundary); no element with `0 <= minTetLevel < level` (a deep pyramid-rooted tet) appears under normal refinement. (Naive `minTetLevel < level` is wrong — it fires on pyramids and pure-Tetree tets, whose `minTetLevel == -1`.)
4. (D3) Extend the reframed oracle to cover the deep tet-return branch — closing the `kyz9` gap without productionizing.

## Risks / Open Questions

- **D0 risk:** if the domain contract turns out to require neighbors `encode()` currently drops (the 17208 under-permissive set), that *is* a live correctness gap in the detector, not just a characterization mismatch — D0 may surface real work. Conversely if the cube-domain reachability is correct as-is, D0 is mostly specification + oracle reframing.
- **D1 risk:** deep insertion changes index cardinality and locate invariants; spanning, balancing, and ghost wiring all assume shallow-only today. High blast radius.
- **D2/D3 risk:** infrastructure that stays unconsumed may still rot; the boundary-pinning + involution + (D3) parity tests are the mitigation.
- Open: does the hybrid-mesh / CFD use case RDR-010 motivates ever require deep tet *queries*, or only deep *geometry* at construction time? The answer decides D1 vs D3.
- Open (D0): are the 26569 over-permissive / 17208 under-permissive neighbors fully explained by the cube-vs-single-root domain difference, or does a residual subset indicate an `encode()` reachability bug? The reframed oracle answers this.

## References

- Gap analysis 2026-05-31, axes 3, 4, 6 (T1 scratch `gap-axis-3/4/6`; T2 `rdr/pyramid-t8code-remediation-plan-2026-05-31`).
- **Oracle findings (ex-P1):** T2 `rdr/rdr-010-8xus-oracle-findings-2026-05-31`; `T8codeDpyramidFaceOracleTest` on branch `feature/Luciferase-8xus-t8code-faceneighbor-oracle`.
- RDR-010 Close section (honest scope caveat); post-mortem `docs/rdr/post-mortem/010-pyramid-spatial-index.md`.
- t8code `main@76a5347b`: `t8_dpyramid_bits.c` (`is_inside_root:883`, `face_neighbour:599`, `tet_boundary:822`).
