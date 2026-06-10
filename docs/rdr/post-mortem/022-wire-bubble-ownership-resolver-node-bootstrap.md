---
rdr: RDR-022
title: "Post-mortem — Wire Bubble Ownership Resolution into the Production Node Bootstrap"
closed_date: 2026-06-10
outcome: implemented
---

# RDR-022 Post-Mortem

## What shipped

The bubble→node ownership resolution seam, wired into the production node bootstrap.
`NodeBootstrap.assembleOwnershipResolver` (two overloads: production form over a live
`FirefliesMemberLookup`, narrow-seam form for no-live-View assembly) is the canonical
factory: it fixes `RendezvousOwnershipFunction` (HRW) internally, takes the
`bubbleKeyResolver` as a caller-supplied seam, and returns a lifecycle-passive resolver
with the consumer injection contract documented normatively. Proven end-to-end by
`Rdr022MvvIntegrationTest`: the previously-dead `TopologyConsensusCoordinator` path
(`ownershipResolver not set`) reaches real committee quorum through a factory-assembled
resolver. Closes the `Luciferase-s23eu` resolver half — the second and final half of that
boundary (the fault half closed with RDR-021).

Single-phase arc, one PR (#230, merged 884712f0), deliberately smaller than RDR-021:

| Step | Bead | Delivered |
|------|------|-----------|
| S1 | 0frcy.136.1 | `assembleOwnershipResolver` overloads + `NodeBootstrapOwnershipResolverTest` (9 unit tests: seam threading, HRW-fixed-internally, active-only invariant, fail-loud, lazy production seams) |
| MVV | 0frcy.136.2 | `Rdr022MvvIntegrationTest`: negative control → HRW probe → committee quorum; non-vacuous supplier threading; canonical nodeId round-trip; fail-loud survival |
| review | 0frcy.136.3 | Stacked review: 0 Critical both reviewers; all 5 locked decisions verified as-implemented char-by-char |
| gate | 0frcy.136.4 | phase-review-gate cross-walk PASSED (5/5; Item5 placement = explicit `none` deferral); full suite 3279/0/0 |

## Research finding that set the scope

The load-bearing pre-gate discovery (research pass 1): **all three resolver consumers are
test-only constructions** — zero `src/main` construction sites for
`TopologyConsensusCoordinator`, `OptimisticMigratorImpl`, `DistributedBubbleNode` — and
the bootstrap/`Manager` domain owns no `TetreeBubbleGrid` (A4 refuted). The RDR title's
"wire into the bootstrap" therefore correctly resolved to **factory-exposes-resolver**:
there is no production consumer to inject into, and assembling consumers just to wire
them would have smuggled the multi-node consensus-stack assembly into this RDR. The gate
critique explicitly pressure-tested this as potential silent scope reduction and
confirmed it as the honest scope.

## Divergences from the original design

None. All five locked decisions shipped as gated; the two gate Significants were
MVV-spec defects fixed in the RDR before acceptance (see below), not implementation
divergences.

## What the gate + stacked review caught

- **Gate S1 (substantive-critic, spec-level)**: the MVV spec omitted the **HRW probe
  step** — with ≥2 members the ownership guard throws unless `localMemberSupplier`
  returns the HRW owner of the bubble's region; a test written from the spec as-was
  would have failed on first run. The probe pattern (from `Rdr020MvvIntegrationTest`)
  was made mandatory in the spec.
- **Gate S2 (substantive-critic, spec-level)**: the canonical round-trip assertion was
  **vacuously true as specified** (`resolveNodeId(member) ==
  digestToUuid(resolver.localMember())` — both sides reduce to
  `digestToUuid(member.getId())`). Restated as a supplier-threading assertion against
  the specific member passed. The mutation check later confirmed the corrected assertion
  is the one that catches wrong-member assembly.
- **Implementation review (0 Critical)**: one Significant (null-guard layering between
  factory and resolver ctor undocumented) + two Mediums (javadoc claimed the production
  overload needs a live view "at call time" — contradicted by the passing lazy-seam
  test; un-commented listener drop in a test double). All documentation; all fixed
  pre-merge. No reviewer false positives this round (claims verified in both
  directions).

## Verification discipline that paid off

- **Mutation check**: assembling the MVV resolver with a non-owner member fails
  deterministically — on the non-vacuous supplier-threading assertion, exactly the
  assertion the gate forced into the spec.
- **Negative control**: the MVV first proves the gap exists (uninjected coordinator
  throws `ownershipResolver not set`) before proving the factory closes it — "gap
  closed by factory" is distinguished from "gap was never there".
- **Lazy-seam contract test**: `productionOverloadAssemblesLazily` pins that the factory
  wires method references without dereferencing the view — ruling out an
  eager-call-and-cache implementation and documenting when the live-View requirement
  actually bites (first use, not assembly).

## Left open / future scope (named deferrals, carried to the successor bead)

- **Consumer assembly**: the three consumers remain test-only; whichever multi-node arc
  assembles them owns the `setOwnershipResolver` injection (contract documented on the
  factory; enforcement is the consumers' existing fail-loud guards).
- **Placement-honors-HRW**: partition-layer seeding/re-homing of physical placement by
  `owner(bubbleKey, view)` — deferred by RDR-020, re-affirmed out here (A5: resolver
  wiring is purely enabling without it).
- **Live `main()` activation** (RDR-017 P1): the production overload needs the live
  Fireflies `View`; the MVV runs on the narrow-seam form.
- `StreamingE2ETest.phaseCAndBFullFlow` CI flake (unrelated to this arc) filed as its
  own P3 bug bead.
