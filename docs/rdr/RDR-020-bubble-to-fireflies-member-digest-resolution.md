---
title: "Bubble→Fireflies Member Digest Resolution for Migration Consensus"
id: RDR-020
type: Bug Fix
status: draft
priority: high
author: self
reviewed-by: self
created: 2026-06-08
accepted_date:
related_issues: [Luciferase-vhbw3, Luciferase-l5c8q, Luciferase-0frcy, Luciferase-s23eu]
---

# RDR-020: Bubble→Fireflies Member Digest Resolution for Migration Consensus

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

Migration consensus votes on **nodes** (Fireflies cluster members, identified by a Delos `Digest`),
but the simulation only has **bubble** identities (`UUID`) and has **no node-identity layer** that maps a
bubble to the cluster member that hosts it. The two consensus entry points paper over this with
`digestOf(UUID) = DigestAlgorithm.DEFAULT.digest(uuid.toString())` — an arbitrary hash that can never equal
a real member `Digest`. Against a live, membership-enforcing `ViewCommitteeConsensus` this makes migration
consensus **non-functional**: every proposal is rejected at the node-in-view validity gate. Unit tests mock
out `requestConsensus`, so the gap is invisible in CI.

This blocks two corroborated deep-review defects (`Luciferase-vhbw3` topology side, `Luciferase-l5c8q`
migrator side), both children of epic `Luciferase-0frcy`. They share one missing primitive: a
bubble/entity-`UUID` → owning-member-`Digest` resolution, plus a fail-loud contract so the gap can never
again be silently approved or silently rejected.

### Enumerated gaps to close

#### Gap 1: No bubble→node identity mapping exists

A node hosts many bubbles (`TetreeBubbleGrid`), but nothing maps a bubble `UUID` to the `Digest` of the
Fireflies member that owns it. `digestOf(bubbleUUID)`
(`TopologyConsensusCoordinator.java:286-287`) produces a hash unrelated to cluster membership;
`ViewCommitteeSelector.isNodeInView(digest)` (`ViewCommitteeSelector.java:82-86`) compares it against real
`Member.getId()` digests and always returns false. The fix must establish a real, cluster-consistent
bubble→owning-member-`Digest` resolution.

#### Gap 2: Topology consensus is non-functional against a live committee

`TopologyConsensusCoordinator.toMigrationProposal` (`TopologyConsensusCoordinator.java:270-278`) builds a
`MigrationProposal` whose `sourceNodeId`/`targetNodeId` are bubble-UUID hashes, so
`ViewCommitteeConsensus.validateProposal` (`ViewCommitteeConsensus.java:332-386`, node-in-view gate
`:371-376`) rejects every real-committee proposal at the validity check. The fix must produce proposals
whose node digests are genuine members of the current view — or **fail loud** if they cannot be resolved,
per the wave-20 review note on `vhbw3` (a membership-enforcing consensus must never be handed a
silently-rejectable proposal).

#### Gap 3: Migrator consensus delegation is unavailable

`OptimisticMigratorImpl.requestMigrationApproval` (`OptimisticMigratorImpl.java:125-149`) correctly throws
`UnsupportedOperationException` when `consensusIntegration` is wired (no silent approve), but cannot
delegate to the committee because it has only `(UUID entityId, UUID targetBubble)` and no way to produce the
`Digest sourceId, Digest targetNodeId` that
`OptimisticMigratorIntegration.requestMigrationApproval(UUID, Digest, Digest)` (`:108`) requires. The fix
must supply those digests (resolve them, or take them from the call site) so the quorum gate becomes live.

## Context

### Background

Surfaced by the 24-agent simulation deep review (2026-06-03, `simulation/doc/DEEP_REVIEW_2026-06-03.md`),
waves 1 (`vhbw3`) and 2 (`l5c8q`). Both were deferred pending a shared identity-mapping decision. The
defect class is "hollow production stub behind a test mock": the consensus path looks wired but is inert
against real membership. It is adjacent to RDR-017's shipped-node scope boundary (`s23eu`: the node is not
yet partition-fault-tolerant; VoN↔membership is only partially wired) and depends on how the spatial
partition (RDR-003/RDR-015) assigns bubbles to processes.

### Technical Environment

- Delos Fireflies membership: `Member.getId() → Digest`; `DynamicContext<Member>.allMembers()/active()`.
- `FirefliesMembershipView` (`delos/fireflies/FirefliesMembershipView.java`): `getMembers` (`:86-92`,
  `allMembers`), `activeMembers` (`:96-102`, `active` — RDR-005 security-critical), `getCurrentViewId`
  (`:117-119`). `MockFirefliesView` mirrors the interface for tests.
- Consensus: `MigrationProposal(UUID proposalId, UUID entityId, Digest sourceNodeId, Digest targetNodeId,
  Digest viewId, long timestamp)` (`consensus/committee/MigrationProposal.java:40-48`);
  `ViewCommitteeConsensus`, `ViewCommitteeSelector`.
- Identity util (reverse direction only): `FirefliesMemberLookup.digestToUuid` (`von/FirefliesMemberLookup.java:168-182`,
  first 16 bytes of a `Digest`) + `getMemberByUuid` (`:92-96`). Assumes the UUID was *derived from* a Digest;
  bubbles are not, so it is **not** reusable as-is.
- Bubble identity: `Bubble.id() → UUID` (`bubble/Bubble.java:24,44`). No node-id concept today; fixtures run
  one process.

## Research Findings

### Investigation

Code seams confirmed by source reading (see T2 `Luciferase/uuid-digest-mapping-research` for the full
file:line map). The consensus layer is already `Digest`-native and correct; the defect is entirely at the
**boundary** where bubble UUIDs are translated to node digests. The translation (`digestOf`) is the only
wrong link.

#### Dependency Source Verification

| Dependency | Source Searched? | Key Findings |
| --- | --- | --- |
| Delos Fireflies (`Member`, `DynamicContext`, `Digest`) | Yes | `Member.getId()` is the `Digest` the committee validates against; members enumerated via `context.active()`. |
| `ViewCommitteeSelector.isNodeInView` | Yes | Compares the proposal digest against `context.allMembers().getId()`; bubble-UUID hash never matches. |

### Key Discoveries

- **Documented** — The consensus principal is the **node**, not the bubble: `MigrationProposal` carries
  node digests and `validateProposal` checks node-in-view. Picking two bubble UUIDs as source/target
  (`TopologyConsensusCoordinator:274-275`) conflates "which bubbles move" with "which nodes vote."
- **Documented** — The source node of a migration is almost always **this process's own member** (the bubble
  currently lives here). Only the *target* node genuinely needs a lookup.
- **Documented** — No cluster-consistent bubble→node ownership map exists; how bubbles are assigned to
  processes (deterministically from the spatial partition vs. a gossiped registry) is **undecided** and is
  the load-bearing question.

### Critical Assumptions

- [x] **A1**: A bubble's owning node can be resolved to a current-view member `Digest` deterministically from
  the spatial partition or an existing registry. — **Status**: **REFUTED** (2026-06-08, Source Search) —
  **Finding**: NO bubble→node ownership mapping exists. `TetreeBubbleGrid` indexes bubbles by `TetreeKey`
  (spatial), not by node (`TetreeBubbleGrid.java:54`); there is no range→member table, ownership registry,
  or consistent-hash assignment anywhere. `TopologyConsensusCoordinator:258-265` even documents that
  bubble-UUID digests are rejected as "not in view." **Implication**: the target-ownership backing of the
  resolver must be *built or supplied by the caller* — it is not a free lookup. This is a scope fork (see
  Revision History 2026-06-08 and the revised Approach).
- [x] **A2**: This process can obtain its own Fireflies member `Digest` for the source node. — **Status**:
  **VERIFIED** (Source Search) — `Delos fireflies/View.getNodeId() → Digest` (`View.java:187-189`);
  `FirefliesMembershipView` holds the `View` (`:65-72`) and can expose it. Source node is free.
- [x] **A3**: `MigrationProposal` node-id semantics are "owning node," and redefining them requires no
  change to the proposal record or vote/quorum logic. — **Status**: **VERIFIED** (Source Search) —
  `sourceNodeId`/`targetNodeId` are read only for null checks, the self-migration check
  (`ViewCommitteeConsensus.java:365`), and `isNodeInView` (`:372-376`). Vote tally
  (`CommitteeBallotBox.java:90-248`) is keyed on `proposalId`; quorum derives from committee size/tolerance,
  not node identity. Changing the digests to real owning-node identities is safe.

**Option β — new assumptions introduced by the deterministic ownership function (verify before accept):**

- [x] **B1**: A deterministic `owner(spatialKey, activeMembers)` is compatible with bubble placement/
  migration. — **Status**: **PARTIAL → resolved by decision** (2026-06-08, Source Search) — Nothing binds a
  bubble to a node today (HRW is free to define ownership; bubbles are position-driven,
  `TetreeBubbleGrid.java:54,241`). BUT `DistributedBubbleNode.initiateRemoteMigration(entityId, targetNodeId)`
  (`:108-126`) takes an **explicit** target node, which a position-derived owner can contradict.
  **Decision**: the HRW `owner(targetBubbleKey, view)` is **authoritative**; an explicitly-supplied
  `targetNodeId` is **validated against** it and **fails loud on mismatch** (no silent re-route). This keeps
  the position-driven model (RDR-015) authoritative and the API as a checked hint. The gate should scrutinize
  this decision.
- [x] **B2**: View-change ownership rebalancing is acceptable. — **Status**: **VERIFIED** (Source Search) —
  `ViewCommitteeConsensus` checks `proposal.viewId() == currentViewId` at submission (`:182`) and before
  execution (`:223`); `onViewChange()` rolls back all pending proposals (`:252-269`). An in-flight proposal
  whose owner changed is invalidated by its own view-binding — recomputed ownership on the new view is
  consistent; worst case is a visible fail-loud reject, never a mis-route.
- [x] **B3**: A bubble `UUID` resolves to its `TetreeKey` at proposal time. — **Status**: **VERIFIED**
  (Source Search) — `TetreeBubbleGrid.getKeyForBubble(UUID)` (`:641-650`) already exists and is used by
  `BubbleSplitter`/`BubbleMerger`/`TopologyExecutor`. The resolver calls it; no gap.

## Proposed Solution

### Approach

> **Revised 2026-06-08 after A1 was REFUTED.** No bubble→node ownership map exists, and the spatial
> partition does not assign bubbles to members — so the resolver's *target* backing is not a free lookup;
> it requires an ownership source that must be built. This forks the scope (see below). The boundary
> design and the source-node/fail-loud parts are unchanged; only the **target-resolution backing** is
> affected.
>
> **Scope decision (2026-06-08): Option β chosen — RDR-020 absorbs the ownership mechanism.**
> The key design move that keeps β tractable is to make ownership a **deterministic function of (bubble
> spatial key, current Fireflies view)** rather than a separately-replicated, gossiped registry. Any node
> derives the owner of any region from the view it already holds — no extra replicated state, no bespoke
> consistency protocol, and ownership **auto-rebalances** when the view changes. (The gossiped-registry
> form is retained as Alternative 2.)
>
> Considered and not chosen: **Option α** (correctness floor only — resolver + fail-loud, splitting the
> ownership mechanism to a follow-up RDR). Rejected by owner decision in favor of solving ownership here.

Introduce a thin **node-identity resolution boundary** and keep the consensus layer `Digest`-native:

1. **Source node = local member.** For both entry points, the source node digest is this process's own
   Fireflies member `Digest` (the bubble being migrated currently lives here). Obtain it from the membership
   view; no per-bubble lookup.
2. **Target node via a `BubbleOwnershipResolver` backed by a deterministic, view-derived owner function.**
   The injected interface `Digest resolveOwningMember(UUID bubbleId)` resolves the bubble's spatial key
   (`bubbleId → TetreeKey`, B3) and computes `owner = assign(spatialKey, view.activeMembers())` where
   `assign` is a **deterministic partition of spatial keys over the current active members** (recommended:
   **rendezvous / highest-random-weight hashing** of `(spatialKey, memberDigest)` — minimal reshuffle on
   membership change, no coordinator, computable identically on every node). Because the view is the single
   source of truth and every node holds it, no separate replicated registry or gossip is needed, and
   ownership rebalances deterministically across view changes.
3. **HRW owner is authoritative; explicit target is a checked hint (B1).** Where a caller supplies an
   explicit `targetNodeId` (`DistributedBubbleNode.initiateRemoteMigration`), it is **validated against**
   `owner(targetBubbleKey, view)` and **throws on mismatch** — never silently re-routed. The position-driven
   owner (RDR-015) is the source of truth; the explicit parameter is a hint the consensus path verifies.
4. **Fail loud, never silent.** If the resolver cannot map a bubble to a current-view member, both
   `toMigrationProposal` and `requestMigrationApproval` **throw** (not produce a silently-rejected proposal
   and not silently approve) — satisfying the `vhbw3` wave-20 requirement and preserving `l5c8q`'s existing
   fail-loud stance.
5. **Delete `digestOf(UUID)`** as a node-id source; it remains valid only where a content-addressed hash of
   a UUID is genuinely wanted (none in the consensus path).

The consensus records, vote tally, and `isNodeInView` gate are unchanged (A3) — this is a boundary fix.

### Technical Design

Interface (signatures, not implementation):

```text
// Resolves the current-view Fireflies member that owns a bubble. Throws (fail-loud) if unresolvable.
interface BubbleOwnershipResolver {
    Digest resolveOwningMember(UUID bubbleId);   // throws IllegalStateException if no current-view owner
    Digest localMember();                         // this process's own member Digest (source node)
}

// Deterministic, view-derived ownership — pure function, identical on every node.
// owner(key) = argmax over m in activeMembers of weight(key, m.getId())   // rendezvous / HRW hashing
interface SpatialOwnershipFunction {
    Digest owner(TetreeKey key, List<Digest> activeMembers);  // empty members -> throws (fail-loud)
}
```

- `BubbleOwnershipResolver` resolves `bubbleId → TetreeKey` (B3), reads `view.activeMembers()` (RDR-005),
  and returns `SpatialOwnershipFunction.owner(key, members)`; throws if the result is not a current-view
  member (e.g. empty view) — fail-loud, never a silently-rejectable digest.
- `localMember()` returns `View.getNodeId()` (A2).
- **Ownership is not stored.** It is recomputed from the view at proposal time; a view change recomputes it.
  In-flight proposals carrying a now-stale owner are rejected by `isNodeInView` (visible), not mis-routed.

- `TopologyConsensusCoordinator.toMigrationProposal(...)`: `sourceNodeId = resolver.localMember()` (or the
  owner of the source bubble when not local), `targetNodeId = resolver.resolveOwningMember(targetBubble)`;
  throw on unresolved.
- `OptimisticMigratorImpl.requestMigrationApproval(entityId, targetBubble)`: resolve `sourceId`/`targetNodeId`
  via the resolver, then delegate to `OptimisticMigratorIntegration.requestMigrationApproval(entityId,
  sourceId, targetNodeId)`. Remove the `UnsupportedOperationException` once the resolver is injected.
- The resolver must use `activeMembers()` (not `allMembers()`) for the in-view determination (RDR-005).

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| `BubbleOwnershipResolver` | `von/FirefliesMemberLookup` | Extend or wrap: reuse its member-index access; add the forward bubble→member direction (the existing `digestToUuid` is reverse-only and assumes Digest-derived UUIDs). |
| Source-node = local member | `FirefliesMembershipView` / `View.getNodeId()` | Reuse: expose the local-member accessor (A2). |
| `SpatialOwnershipFunction` (rendezvous/HRW) | — (none exists; A1 refuted) | New: deterministic view-derived owner; no replicated state. |
| bubble `UUID` → `TetreeKey` | `TetreeBubbleGrid` | Reuse/extend per B3. |
| `digestOf(UUID)` node-id | `TopologyConsensusCoordinator` | Replace: delete as a node-id source. |

### Decision Rationale

The consensus layer is already correct (A3); the only bug is the bubble→node translation. A boundary
resolver plus "source = local member" (A2) fixes both beads with no change to consensus semantics and keeps
the fail-loud contract. A1 refuted the existence of any ownership map, so β builds one — but as a
**deterministic, view-derived function** rather than a replicated registry: ownership becomes a pure
function of `(spatial key, current view)`, which every node computes identically from state it already
holds, eliminating the consistency/convergence burden a gossiped registry would add and rebalancing
automatically on membership change. This is the smallest mechanism that actually makes multi-node consensus
functional.

## Alternatives Considered

### Alternative 1: Push `Digest` to the call site (no resolver)

**Description**: Change both entry points to accept `Digest` source/target from callers that already know the
target node.

**Pros**: No new mapping component; consensus stays Digest-native.

**Cons**: `TopologyConsensusCoordinator` operates on *bubbles* from a topology proposal and does **not** know
the target node — it would still need a resolver. Only covers `l5c8q`, not `vhbw3`.

**Reason for rejection**: Does not close Gap 2; a resolver is needed regardless, so this is a strict subset.

### Alternative 2: Gossiped / replicated ownership registry

**Description**: Maintain an explicit `bubble/region → member` table, replicated across the cluster and
updated on placement, migration, and view change.

**Pros**: Ownership can be arbitrary (not tied to a hash of the spatial key); supports explicit hand-off.

**Cons**: Needs its own consistency protocol (who is authoritative, conflict resolution, convergence under
partition) — a distributed subsystem, larger than the defect it serves; must itself stay consistent with the
Fireflies view it duplicates.

**Reason for rejection**: The deterministic view-derived function obtains the same result as a *pure function
of state every node already holds*, with no extra replicated state or convergence concern. Reconsider only
if B1 shows ownership cannot be a function of the spatial key (e.g. ownership must be explicitly assignable
independent of position).

### Briefly Rejected

- **Topology-native proposal type** (vote on the topology change keyed by view, not node membership): the
  node-in-view check is the security gate against off-cluster proposals — removing it weakens validity. Out
  of scope.
- **Reuse `digestOf(UUID)` but register those synthetic digests as members**: pollutes the membership view
  with non-cluster identities; defeats the security purpose of `isNodeInView`.

## Trade-offs

### Consequences

- (+) Migration consensus becomes functional against a live `ViewCommitteeConsensus`; two corroborated
  defects close.
- (+) Fail-loud everywhere — no silent approve (`l5c8q`) and no silently-rejected proposal (`vhbw3`).
- (−) Introduces a node-identity dependency the single-process fixtures did not need; multi-node tests
  require real/mock membership wiring.
- (−) Couples migration consensus to the spatial-partition↔membership mapping (A1), which may need its own
  follow-up if ownership is dynamic.

### Risks and Mitigations

- **Risk**: Ownership resolution is stale during a view change (target bubble's owner just changed).
  **Mitigation**: Resolve against the current view at proposal time; the node-in-view gate rejects
  stale digests; fail-loud surfaces it rather than mis-routing.
- **Risk**: A1 has no deterministic partition→member map, forcing a gossiped registry (larger scope).
  **Mitigation**: Verify A1 in research before locking; if a registry is required, scope it explicitly or
  split it to a follow-up RDR.

### Failure Modes

- Unresolvable bubble → **throws** (visible) at proposal/approval time; a developer sees the entity/bubble id
  and the empty resolution.
- A correctly-resolved-but-evicted member → rejected by `isNodeInView` (visible WARN), not silent.

## Implementation Plan

### Prerequisites

- [ ] A1, A2, A3 verified (esp. A1: how target ownership is sourced).

### Minimum Viable Validation

An integration test with a real (or mock) multi-member `ViewCommitteeConsensus`: a migration whose target
bubble is owned by a current-view member produces a proposal that **passes** `validateProposal` and reaches
quorum; a migration whose target cannot be resolved **throws** (fail-loud) — proving the gap is closed in
both directions, not mocked away.

### Phase 1: Code Implementation

#### Step 1: Resolver interface + membership-backed implementation (A1/A2)

#### Step 2: Wire `toMigrationProposal` and `requestMigrationApproval` to the resolver; delete `digestOf` as a node-id source; fail-loud on unresolved

#### Step 3: Remove the `UnsupportedOperationException` gate in `OptimisticMigratorImpl` once the resolver is injected

### New Dependencies

None anticipated (reuses Delos membership already on the classpath).

## Test Plan

- **Scenario**: Target bubble owned by a current-view member — **Verify**: proposal passes
  `validateProposal`, reaches quorum (not mocked).
- **Scenario**: Target bubble unresolvable to any current-view member — **Verify**: `toMigrationProposal` and
  `requestMigrationApproval` throw (fail-loud), no silent approve, no silently-rejected proposal.
- **Scenario**: Resolver uses `activeMembers()` not `allMembers()` — **Verify**: an evicted member is not a
  valid owner (RDR-005).
- **Scenario**: Source node defaults to local member for a locally-owned bubble — **Verify**: source digest
  equals this process's member id.

## Validation

### Testing Strategy

Integration over mocks: exercise the real `ViewCommitteeConsensus`/`ViewCommitteeSelector` path with a
mock membership view containing known member digests; assert validity, quorum, and the fail-loud throws.

## Finalization Gate

### Contradiction Check

[To complete at gate.]

### Assumption Verification

A1/A2/A3 to be verified by source search during `/conexus:rdr-research` before acceptance.

### Scope Verification

[To complete at gate — confirm MVV (live-committee pass + fail-loud throw) is in scope, not deferred.]

### Cross-Cutting Concerns

- **Versioning**: N/A (no wire-format change; `MigrationProposal` unchanged).
- **Secret/credential lifecycle**: N/A (uses existing Fireflies identities).
- **Incremental adoption**: resolver is injected; fail-loud preserves current behavior until wired.
- Others: N/A.

### Proportionality

Right-sized as a boundary bug-fix RDR; the one genuinely-open question (A1 ownership sourcing) is isolated.

## References

- T2 `Luciferase/uuid-digest-mapping-research` (full file:line seam map).
- Beads `Luciferase-vhbw3`, `Luciferase-l5c8q`, epic `Luciferase-0frcy`; scope-boundary `Luciferase-s23eu`.
- RDR-005 (Fireflies/KERI identity; `activeMembers()` security), RDR-003/RDR-015 (spatial partition).
- `simulation/doc/DEEP_REVIEW_2026-06-03.md`.

## Revision History

- 2026-06-08: created (draft) — scoped from `vhbw3`+`l5c8q` after seam research; recommends a node-identity
  boundary resolver with fail-loud contract; A1 (ownership sourcing) flagged as the load-bearing
  unverified assumption for `/conexus:rdr-research`.
- 2026-06-08: **research complete** (Source Search; T2 `Luciferase_rdr/rdr-020/...`, `Luciferase/uuid-digest-mapping-research`).
  - **A2 VERIFIED** — `View.getNodeId() → Digest` (`View.java:187-189`); source node is free.
  - **A3 VERIFIED** — proposal node digests used only for null/self-migration/`isNodeInView` checks; vote
    tally keyed on `proposalId` (`CommitteeBallotBox.java:90-248`); redefining them to owning-node identities
    is safe with no consensus-logic change.
  - **A1 REFUTED** — no bubble→node ownership map exists; `TetreeBubbleGrid` keys by `TetreeKey` not member;
    no range→member assignment anywhere. The resolver's target backing must be built or caller-supplied.
    **Consequence**: scope fork (Option α correctness-floor + split the distributed ownership registry to a
    follow-up RDR, vs Option β absorb it). **Recommended: Option α** — closes the silent-broken-consensus
    defect (`vhbw3`/`l5c8q`) via fail-loud + resolver seam, defers the distributed ownership subsystem
    (which belongs with `s23eu` / spatial-partition work) to its own RDR. Pending owner decision before
    `/conexus:rdr-gate`.
- 2026-06-08: **Owner chose Option β** (absorb the ownership mechanism). Design updated: ownership is a
  **deterministic, view-derived function** `owner(spatialKey, activeMembers)` (rendezvous/HRW hashing), not
  a gossiped registry — a pure function of state every node already holds, so no extra replicated state and
  auto-rebalancing on view change. Adds assumptions **B1** (deterministic assignment compatible with the
  bubble placement/migration model), **B2** (view-change rebalancing acceptable), **B3** (bubble→`TetreeKey`
  resolvable) — all Unverified, to be checked in a second `/conexus:rdr-research` pass before the gate.
  Gossiped registry retained as Alternative 2.
- 2026-06-08: **research pass 2** (Source Search; T2 `Luciferase_rdr/020-research-2`). **B2 VERIFIED**
  (`viewId` check at submission `:182` + pre-execution `:223`; `onViewChange` rolls back pending `:252-269`).
  **B3 VERIFIED** (`TetreeBubbleGrid.getKeyForBubble(UUID)` `:641-650`, already used by topology ops).
  **B1 PARTIAL → resolved**: `initiateRemoteMigration(entityId, targetNodeId)` (`:108-126`) takes an explicit
  target, which a position-derived owner can contradict. **Decision**: HRW `owner(targetBubbleKey, view)` is
  authoritative; an explicit `targetNodeId` is validated against it and **fails loud on mismatch**. All
  assumptions now resolved; ready for `/conexus:rdr-gate` (which should scrutinize the B1 decision).
