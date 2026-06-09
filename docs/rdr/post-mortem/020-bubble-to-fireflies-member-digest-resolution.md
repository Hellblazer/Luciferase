---
rdr: RDR-020
title: "Post-mortem — Bubble→Fireflies Member Digest Resolution for Migration Consensus"
closed_date: 2026-06-09
outcome: implemented
---

# RDR-020 Post-Mortem

## What shipped

A node-identity resolution boundary (`BubbleOwnershipResolver`) that makes migration
consensus functional against a live, membership-enforcing `ViewCommitteeConsensus`,
replacing the `digestOf(UUID)` hash that the live committee silently rejected.

Phase-1 arc, all on `feature/rdr-020-scope`, each increment stacked-reviewed:

| Step | Bead | Delivered |
|------|------|-----------|
| S1 | bucvb | `SpatialOwnershipFunction` (HRW/rendezvous) + `BubbleOwnershipResolver` port (Fireflies + Stub impls) |
| S2 | epihi | O(1) inverse `UUID→TetreeKey` index in `TetreeBubbleGrid` |
| S3 | e8gl4 | `ProposalKind` discriminator; TOPOLOGY single-owner model; `digestOf` deleted |
| S4 | jf7u6 | `OptimisticMigratorImpl` → resolver; `UnsupportedOperationException` gate removed |
| S5 | kcgj1 | node-UUID hint validation + `isActiveMember` |
| S6 | i4prr | `isNodeInView` → `context.active()` (active-only at the validator) |
| S7 | h3lc6 | opt-in-validation contract pinned (premise obviated by S5's resolver-gated design) |
| MVV | u911y | end-to-end integration over the real wired path (11 scenarios) |
| review | 05ms3 | holistic phase-boundary stacked review |

External Gap bugs closed: `vhbw3` (Gap 2, topology), `l5c8q` (Gap 3, migrator).

## Divergences from the original design (all gated)

1. **TOPOLOGY node-identity model (S3 amendment).** Implementation revealed that a
   topology change is single-region: `source == target == owner(region)`, which the
   entity-migration self-migration reject would kill. Added a `ProposalKind`
   {ENTITY_MIGRATION, TOPOLOGY} discriminator carried on the wire; the self-migration
   reject is skipped only for TOPOLOGY. The amendment was itself gate-BLOCKED twice
   (validateProposal is not proposer-local; cross-region merge) before passing.

2. **`isActiveMember` interface widening (S5).** The spec's 3-method resolver sketch
   understated the interface. `memberDigestForNode` resolves over the all-members
   backing (B4), so the node-hint guard needs a *separate* active-only check to reject
   an evicted-but-not-GC'd member — a 4th method.

3. **Committee-composition active-only via vote-receipt (yagnw.1).** S6 closed the
   active-only gap for source/target *identity*; the committee *composition* (offline
   member as a voter) was a separate gap. Fixed at vote-receipt
   (`CommitteeVotingProtocol.recordVote` drops inactive votes) — the safety-conservative
   choice that keeps the quorum denominator at the original committee size rather than
   shrinking it toward `committeeQuorum(2)==1`.

## What worked

- **Stacked review caught load-bearing bugs the green suite hid every single time**:
  S1 vacuous HRW convergence test; S3 cooldown-orphan on synchronous guard throw; S4
  non-volatile resolver field; MVV's missing owner≠local (Gap-2-closing) path. None
  were visible from passing tests.
- **Verifying review claims against the codebase** rejected three confident false
  positives (VirtualSynchronyTest "NPE", a stale MVV finding, the Step-6 scope
  confusion) — each disproven by actually running the test or reading the committed code.
- **Stopping to amend the RDR when implementation contradicted the design** (S3) rather
  than winging it; the amendment then re-gated.

## Lessons

- A passing suite that mocks the integration boundary (`requestConsensus`) hides the
  exact class of defect this RDR fixed. The MVV — integration over the *real* wired path
  with identity alignment across the consensus context and the resolver view — is what
  actually proves the gap closed. Mock one level up and the gap stays invisible.
- "Fail-loud" is a property to test explicitly (unresolvable target throws; no silent
  approve), not assume — it was the wave-20 requirement and the MVV asserts it directly.
- Critic rationales can be wrong in instructive ways: the claim that filtering
  `bftSubset` "preserves BFT quorum sizing" was backwards — it can shrink the committee
  below the BFT-safe majority. The conservative vote-receipt gate was the right call.

## Named deferrals (tracked, out of scope)

- Production resolver wiring + placement-honors-HRW partition seeding → `s23eu`
  (multi-node bootstrap). Live path is single-process; every proposal is a correctly
  rejected self-migration — correct today, not by coincidence.
- Cross-region merge → fail-loud guard only; a two-node merge protocol is later work.
- Committee-resizing-under-churn liveness (quorum denominator fixed at full committee
  size) → `Luciferase-0frcy.134` (needs Delos `bftSubset(Digest, Predicate)` semantics
  verification).
- Pre-existing `TetreeBubbleGrid` dual-map atomicity → tracked under `0frcy`.

## Validation

Phase-review-gate PASSED (9/9 §Approach items). Full simulation suite: 3241 tests,
0 failures, 11 skipped (pre-existing).
