---
title: "Bubble→Fireflies Member Digest Resolution for Migration Consensus"
id: RDR-020
type: Bug Fix
status: accepted
priority: high
author: self
reviewed-by: self
created: 2026-06-08
accepted_date: 2026-06-08
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
  **VERIFIED** (Source Search; re-confirmed at gate 2026-06-08) — an **exposed accessor already exists**:
  `FirefliesMemberLookup.getLocalMember() → Member` (`von/FirefliesMemberLookup.java:131-133`, returns
  `view.getNode()`); `view.getNode().getId()` is the local member `Digest`. No new method on the
  `MembershipView` interface is required — the resolver is backed by `FirefliesMemberLookup`, not by
  `MembershipView` (gate C1 corrected: the original RDR named `View.getNodeId()` on `FirefliesMembershipView`,
  which does **not** expose it; the working seam is `FirefliesMemberLookup.getLocalMember()`). Source node is free.
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
  (`:108-126`) takes an **explicit** target **node** UUID, which a position-derived owner can contradict.
  **Decision**: the HRW `owner(targetBubbleKey, view)` is **authoritative**; an explicitly-supplied
  `targetNodeId` is **validated against** it and **fails loud on mismatch** (no silent re-route). This keeps
  the position-driven model (RDR-015) authoritative and the API as a checked hint. **Implementability of the
  comparison is established by B4** (the node-UUID↔member-`Digest` mapping the validation needs already
  exists). Note this hint path is *secondary*: `initiateRemoteMigration` does not itself reach committee
  consensus (it calls `initiateOptimisticMigration`, not `requestMigrationApproval`); the two live consensus
  entry points are **bubble-keyed**, so HRW `owner(bubbleKey)` applies to them directly without any
  node-UUID comparison.
- [x] **B4** (gate C2): A network node `UUID` can be mapped back to its Fireflies member `Digest`, so the
  B1 hint comparison is implementable. — **Status**: **VERIFIED** (gate Source Search, 2026-06-08) — node
  UUIDs are **canonically** `FirefliesMemberLookup.digestToUuid(member.getId())`: `NodeBootstrap.resolveNodeId`
  (`von/NodeBootstrap.java:62-74`) is documented as "the canonical member→UUID derivation across the codebase…
  deterministic across restarts" (WAL directory identity depends on it). The reverse —
  `FirefliesMemberLookup.getMemberByUuid(UUID)` (`:85-90`) — recovers the `Member` (hence `Digest`) by matching
  `digestToUuid(m.getId()).equals(uuid)`. So `targetNodeId (UUID) → Digest` is a real, existing lookup; the
  critic's premise that "no UUID→Digest mapping exists" held only for *bubble* UUIDs, not *node* UUIDs.
  **Caveat folded in**: `getMemberByUuid` is built on `getActiveMembers()`, which in `FirefliesMemberLookup`
  is **misnamed** — it delegates to `context.allMembers()`, not `active()`. The resolver MUST perform the
  in-view determination against the active-only set (RDR-005), so it consults `MembershipView.activeMembers()`
  / `context.active()` for ownership and never trusts the misnamed accessor's all-members backing.
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

**Two distinct node roles (gate C3 — the load-bearing clarification).** A migration proposal carries two
node digests that mean *different things*, and conflating them is the original bug:

- **`sourceNodeId` = current holder = local member.** The process that initiates a migration **physically
  holds** the entity/bubble, so the source is authoritative *by possession*, not by HRW. It is always a
  current-view member (this process is in its own view), so it always passes `isNodeInView`. This is
  deliberately **not** `owner(sourceKey)` — "who holds it now" is a different question from "who should own
  the region," and possession is the correct answer for the source.
- **`targetNodeId` = intended owner of the destination region = `owner(destinationKey, activeMembers)`** (HRW).
  HRW **defines** which node should host a region; a migration's purpose is to move the entity to that node.

These two roles also explain the **single-process / single-member case**: with one member, source = local =
HRW owner of every key = target, so `source == target` and `validateProposal` **rejects it as a
self-migration** (`ViewCommitteeConsensus.java:365-369`). That is correct, not a gap: a single-node "migration"
is a *local* move with nothing to vote on, and committee consensus is a **cross-node** mechanism by
construction. The HRW-owner-vs-physical-host invariant is therefore **maintained, not assumed**: migrations
route entities *to* their HRW owner, and the partition layer must seed initial placement by the same function
(see the explicit contract below). The production-live path today is single-process (`s23eu`: not yet
partition-fault-tolerant, VoN↔membership unwired), where every proposal is a self-migration / local move —
multi-node correctness is gated on placement honoring HRW, stated as a named dependency rather than silently
assumed.

> **Placement-honors-HRW contract (C3).** This RDR makes HRW `owner(key, view)` *authoritative for target
> ownership*. For physical hosting to track ownership at steady state, the partition/placement layer must
> (a) seed each bubble onto `owner(bubbleKey, view)` at construction and (b) re-home on view change. That
> placement work is **out of scope here** and belongs with the spatial-partition↔membership binding
> (`s23eu`, RDR-003/RDR-015 follow-on); this RDR depends on it for multi-node steady-state correctness and
> records the dependency explicitly. Until it lands, the only live path is the single-member/local case
> above, which is correct under the self-migration semantics.

Introduce a thin **node-identity resolution boundary** and keep the consensus layer `Digest`-native:

1. **Source node = local member, always (possession).** For both consensus entry points the source digest is
   this process's own Fireflies member `Digest` via `FirefliesMemberLookup.getLocalMember().getId()` (A2). The
   source bubble is locally hosted by construction at both call sites (you only propose topology/migration for
   bubbles you hold), so there is no "non-local source" path — that ambiguity is removed (gate S4).
2. **Target node via a `BubbleOwnershipResolver` backed by a deterministic, view-derived owner function.**
   The injected interface `Digest resolveOwningMember(UUID bubbleId)` resolves the bubble's spatial key
   (`bubbleId → TetreeKey`, B3) and computes `owner = assign(spatialKey, view.activeMembers())` where
   `assign` is a **deterministic partition of spatial keys over the current active members** (recommended:
   **rendezvous / highest-random-weight hashing** of `(spatialKey, memberDigest)` — minimal reshuffle on
   membership change, no coordinator, computable identically on every node). The in-view set is the
   **active-only** members (`MembershipView.activeMembers()` / `context.active()`), per RDR-005 and the B4
   caveat — never the misnamed all-members accessor. Because the view is the single source of truth and every
   node holds it, no separate replicated registry or gossip is needed, and ownership rebalances
   deterministically across view changes.
3. **HRW owner is authoritative; an explicit node hint is checked, not trusted (B1/B4).** The two live
   consensus entry points are **bubble-keyed**, so HRW `owner(bubbleKey)` applies directly with no node-UUID
   comparison. Separately, where a caller supplies an explicit `targetNodeId` *node UUID*
   (`DistributedBubbleNode.initiateRemoteMigration`), it is mapped to its member `Digest` via the canonical
   `FirefliesMemberLookup.getMemberByUuid(targetNodeId)` (B4) and **validated against** the HRW owner of the
   destination region, **throwing on mismatch** — never silently re-routed. (This path does not itself invoke
   committee consensus; the validation is a consistency guard wherever the node hint meets the ownership
   function.)
4. **Fail loud, never silent.** If the resolver cannot map a bubble to a current-view member (empty active
   set, or a hint that resolves to no member), both `toMigrationProposal` and `requestMigrationApproval`
   **throw** (not produce a silently-rejected proposal and not silently approve) — satisfying the `vhbw3`
   wave-20 requirement and preserving `l5c8q`'s existing fail-loud stance.
5. **Delete `digestOf(UUID)`** as a node-id source. Confirmed scope (gate S3): `digestOf` is a `private
   static` method (`:286-288`) with **3 invocations, all inside the single method
   `TopologyConsensusCoordinator.toMigrationProposal`** (`:274-275`) — a project-wide grep finds no other
   caller. Deleting it as a node-id source is fully contained to that one class.
6. **Tighten `isNodeInView` to active-only (re-gate finding).** `ViewCommitteeSelector.isNodeInView`
   (`consensus/committee/ViewCommitteeSelector.java:82-86`) currently matches against `context.allMembers()`,
   not `context.active()`. The resolver enforces the active-only set (RDR-005 / B4) for *ownership*, but the
   *validator* that gates the resolved digest still admits evicted-but-not-GC'd members — the invariant would
   be enforced at the wrong layer. Change `isNodeInView` to `context.active().anyMatch(...)` in tandem with
   resolver wiring so target/source in-view checks are active-only end-to-end. Implementation note:
   `FirefliesMemberLookup.getMemberByUuid` is built on the misnamed all-members `getActiveMembers()`, so it is
   used **only** for the node-UUID→`Digest` hint mapping (B4) — never for the active-membership ownership set,
   which comes from `MembershipView.activeMembers()` / `context.active()`.

The consensus records, vote tally, and `isNodeInView` gate are unchanged (A3) — this is a boundary fix at the
two entry points plus the new ownership function.

### Technical Design

Interface (signatures, not implementation):

```text
// Resolves the current-view Fireflies member that owns a bubble. Throws (fail-loud) if unresolvable.
interface BubbleOwnershipResolver {
    Digest resolveOwningMember(UUID bubbleId);   // throws IllegalStateException if no current-view owner
    Digest localMember();                         // this process's own member Digest (source node)
    Digest memberDigestForNode(UUID nodeId);      // canonical node-UUID -> member Digest (B4); throws if unknown
}

// Deterministic, view-derived ownership — pure function, identical on every node.
// owner(key) = argmax over m in activeMembers of weight(key, m.getId())   // rendezvous / HRW hashing
interface SpatialOwnershipFunction {
    Digest owner(TetreeKey key, List<Digest> activeMembers);  // empty members -> throws (fail-loud)
}
```

- The resolver depends on its **own small port** (the three methods above), not on the `MembershipView`
  interface — so no contract surgery to `MembershipView`/`MockFirefliesView` is needed (gate C1). It has two
  implementations: a production one over `FirefliesMemberLookup` (which already exposes `getLocalMember()`,
  the active-member set, and `getMemberByUuid`), and a test double seeded with known member digests + a
  fixed key→member map for deterministic HRW-convergence tests.
- `resolveOwningMember` resolves `bubbleId → TetreeKey` (B3), reads the **active** members (RDR-005 / B4 —
  the active-only set, not the misnamed `getActiveMembers()` all-members backing), and returns
  `SpatialOwnershipFunction.owner(key, members)`; throws if the result is not a current-view member (e.g.
  empty view) — fail-loud, never a silently-rejectable digest.
- `localMember()` returns `FirefliesMemberLookup.getLocalMember().getId()` (A2) — an existing accessor.
- `memberDigestForNode(nodeId)` is `getMemberByUuid(nodeId).map(Member::getId)`, throwing if absent (B4);
  used only to validate an explicit node-UUID hint.
- **Ownership is not stored.** It is recomputed from the view at proposal time; a view change recomputes it.
  In-flight proposals carrying a now-stale owner are rejected by `isNodeInView` (visible), not mis-routed.

- `TopologyConsensusCoordinator.toMigrationProposal(...)`: `sourceNodeId = resolver.localMember()` (the
  source bubble is locally hosted by construction — no non-local-source path, gate S4),
  `targetNodeId = resolver.resolveOwningMember(targetBubble)`; throw on unresolved. Delete `digestOf` (S3).
- `OptimisticMigratorImpl.requestMigrationApproval(entityId, targetBubble)`: note `targetBubble` is a
  **bubble** UUID, so `targetNodeId = resolver.resolveOwningMember(targetBubble)` (HRW direct — no node-UUID
  lookup here); `sourceId = resolver.localMember()`. Then delegate to
  `OptimisticMigratorIntegration.requestMigrationApproval(entityId, sourceId, targetNodeId)` and remove the
  `UnsupportedOperationException` (`OptimisticMigratorImpl.java:135-141`) once the resolver is injected.
- **Hot-path index (gate S1).** `resolveOwningMember` calls `TetreeBubbleGrid.getKeyForBubble(UUID)`, which is
  an **O(N) linear scan** of `bubblesByKey` (`TetreeBubbleGrid.java:641-650`) — run on every proposal.
  Add an inverse `ConcurrentHashMap<UUID, TetreeKey>` maintained in the grid's add/remove paths so the lookup
  is O(1); the scan is acceptable only for the single-bubble fixtures, not at cluster scale.

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| `BubbleOwnershipResolver` (resolver port) | `von/FirefliesMemberLookup` | Wrap: it already exposes `getLocalMember()`, the member set, and `getMemberByUuid` — the resolver owns a 3-method port over it; no `MembershipView` change (gate C1). |
| Source-node = local member | `FirefliesMemberLookup.getLocalMember()` (`:131-133`, → `view.getNode()`) | Reuse the **existing** accessor (A2). The original `FirefliesMembershipView`/`View.getNodeId()` path was wrong — corrected. |
| Node `UUID` → member `Digest` (hint check) | `FirefliesMemberLookup.getMemberByUuid` + `NodeBootstrap.resolveNodeId` | Reuse: node UUIDs are canonically `digestToUuid(memberId)`, so the reverse lookup exists (B4, gate C2). |
| `SpatialOwnershipFunction` (rendezvous/HRW) | — (none exists; A1 refuted) | New: deterministic view-derived owner; no replicated state. |
| bubble `UUID` → `TetreeKey` | `TetreeBubbleGrid.getKeyForBubble` (`:641-650`) | Reuse per B3; add an O(1) inverse index (gate S1, currently an O(N) scan). |
| `digestOf(UUID)` node-id | `TopologyConsensusCoordinator` | Replace: delete (2 call sites, `private static`, no other caller — gate S3). |

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
- (−) Multi-node *steady-state* correctness depends on the partition layer seeding/re-homing physical
  placement by the same HRW function (the placement-honors-HRW contract, `s23eu`/RDR-015 follow-on). Named as
  a dependency; the consensus-record correctness (source = possession, target = HRW owner) does not wait on it.

### Risks and Mitigations

- **Risk**: Ownership resolution is stale during a view change (target bubble's owner just changed).
  **Mitigation**: Resolve against the current view at proposal time; the node-in-view gate rejects
  stale digests; fail-loud surfaces it rather than mis-routing.
- **Risk**: A1 has no deterministic partition→member map, forcing a gossiped registry (larger scope).
  **Mitigation**: **Resolved** — Option β supplies a deterministic view-derived HRW function, no registry.
- **Risk** (gate C3): HRW ownership and physical bubble placement diverge, so a resolved digest names a node
  that does not host the bubble. **Mitigation**: the two node roles are defined to make this benign for the
  consensus *records* (source = possession, always in-view; target = HRW owner, the migration destination by
  definition); steady-state physical alignment is a named **placement-honors-HRW contract** owned by the
  partition layer (`s23eu`/RDR-015 follow-on), not assumed here. The only live path until then is the
  single-member/local case, which is correct under self-migration semantics.

### Failure Modes

- Unresolvable bubble → **throws** (visible) at proposal/approval time; a developer sees the entity/bubble id
  and the empty resolution.
- A correctly-resolved-but-evicted member → rejected by `isNodeInView` (visible WARN), not silent.

## Implementation Plan

### Prerequisites

- [ ] A1, A2, A3 verified (esp. A1: how target ownership is sourced).

### Minimum Viable Validation

An integration test with a real (or mock) **≥2-member** `ViewCommitteeConsensus`: a migration whose target
bubble is owned by a *different* current-view member produces a proposal that **passes** `validateProposal`
and reaches quorum; a migration whose target cannot be resolved **throws** (fail-loud) — proving the gap is
closed in both directions, not mocked away. The MVV must also assert **HRW convergence** (two resolver
instances on the same view agree on `owner(K)`); single-member self-migration rejection is a unit-level
companion check.

### Phase 1: Code Implementation

#### Step 1: `SpatialOwnershipFunction` (rendezvous/HRW) + `BubbleOwnershipResolver` port over `FirefliesMemberLookup` (A2/B3/B4), plus the test double

#### Step 2: Add the O(1) inverse `UUID → TetreeKey` index to `TetreeBubbleGrid` add/remove paths (gate S1)

#### Step 3: Wire `toMigrationProposal` (source = `localMember()`, target = `resolveOwningMember`) and delete `digestOf` (gate S3); fail-loud on unresolved

#### Step 4: Wire `OptimisticMigratorImpl.requestMigrationApproval` to the resolver (target bubble → HRW; source = local) and remove the `UnsupportedOperationException` gate

#### Step 5: Add the explicit node-UUID hint validation in `DistributedBubbleNode.initiateRemoteMigration` via `memberDigestForNode` (B1/B4), throw on mismatch

#### Step 6: Change `ViewCommitteeSelector.isNodeInView` to match `context.active()` (not `allMembers()`) so the active-only invariant is enforced at the validator too (re-gate finding)

#### Step 7: Update the test callers that pass random node UUIDs (see Test Plan / observation) so they use canonical (`digestToUuid`-derived) node identities or assert the fail-loud path

### New Dependencies

None anticipated (reuses Delos membership already on the classpath).

## Test Plan

- **Scenario**: Target bubble owned by a *different* current-view member (≥2-member view) — **Verify**:
  proposal passes `validateProposal`, reaches quorum (not mocked).
- **Scenario** (gate S2 — HRW convergence): two `SpatialOwnershipFunction`/resolver instances seeded from the
  **same** active-member set independently compute `owner(K, members)` for the same key — **Verify**: they
  return the **identical** member `Digest`. This is the property that makes the view-derived function a valid
  substitute for a replicated registry; without it Option β is unfounded.
- **Scenario** (single-member / local move): a one-member view yields `source == target` — **Verify**:
  `validateProposal` rejects it as a self-migration (`:365`), confirming committee consensus is cross-node
  only and the single-process live path is correct, not silently broken.
- **Scenario**: Target bubble unresolvable to any current-view member — **Verify**: `toMigrationProposal` and
  `requestMigrationApproval` throw (fail-loud), no silent approve, no silently-rejected proposal.
- **Scenario** (gate C2/B1): explicit `targetNodeId` node hint that disagrees with `owner(destKey)` —
  **Verify**: `initiateRemoteMigration` throws on mismatch; a hint that *agrees* passes.
- **Scenario**: Resolver uses the **active-only** set (not the misnamed all-members backing) — **Verify**: an
  evicted-but-not-GC'd member is not a valid owner (RDR-005 / B4).
- **Scenario**: Source node defaults to local member for a locally-owned bubble — **Verify**: source digest
  equals this process's member id (`getLocalMember().getId()`).

## Validation

### Testing Strategy

Integration over mocks: exercise the real `ViewCommitteeConsensus`/`ViewCommitteeSelector` path with a
mock membership view containing known member digests; assert validity, quorum, and the fail-loud throws.

## Finalization Gate

### Contradiction Check

No internal contradiction after the C3 clarification: `sourceNodeId` (possession/local) and `targetNodeId`
(HRW owner) are defined as distinct notions, so "source = local" and "target = HRW owner" do not compete.
The B1 explicit-hint path is consistent with HRW authority (hint is *validated against* HRW, never overrides
it). No contradiction with RDR-015's position-driven model: HRW is the *target-ownership* authority and the
partition layer is required to seed placement by it (stated as a dependency, not assumed satisfied).

### Assumption Verification

A1 (refuted — drives Option β), A2/A3 (verified), B1 (resolved by decision), B2/B3 (verified), B4 (verified at
gate — the node-UUID↔Digest mapping the B1 hint needs already exists). Each carries an explicit status and
risk note in §Research Findings.

### Scope Verification

In scope and not deferred: resolver + HRW function + both consensus entry points + fail-loud throws + the MVV
(≥2-member live-committee pass, fail-loud throw, **HRW convergence**, single-member self-migration). Honestly
**out of scope and named**: the placement-honors-HRW physical re-homing (partition layer, `s23eu`/RDR-015
follow-on) on which multi-node *steady-state* correctness depends — this is a declared dependency, not a
silent reduction.

### Cross-Cutting Concerns

- **Versioning**: N/A (no wire-format change; `MigrationProposal` unchanged).
- **Secret/credential lifecycle**: N/A (uses existing Fireflies identities).
- **Incremental adoption**: resolver is injected; fail-loud preserves current behavior until wired.
- **Test-caller impact (gate observation)**: `PerformanceResilienceValidationTest` (9 `initiateRemoteMigration`
  call sites passing random `UUID` targets) and similar will hit the new throw-on-mismatch once the hint
  validation lands, because random UUIDs are not `digestToUuid`-derived and resolve to no member. Step 6
  updates these callers to canonical node identities or re-points them at the fail-loud assertion — planned,
  not incidental breakage.
- Others: N/A.

### Proportionality

Honestly framed as a **boundary fix + a small ownership primitive** (not a pure one-line bug fix): the
`digestOf` deletion and the two-entry-point rewiring are the boundary fix; the deterministic HRW
`SpatialOwnershipFunction` is a genuinely new (but self-contained, stateless, ~one-function) mechanism the
A1 refutation forced. It is *not* a distributed subsystem — no replicated state, no consistency protocol,
no convergence concern — because ownership is a pure function of state every node already holds. The one
larger thing it touches (physical placement honoring HRW) is explicitly pushed to the partition layer rather
than absorbed here.

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
- 2026-06-08: **gate BLOCKED then remediated** (substantive-critic, 3 Critical / 4 Significant). All addressed
  with code-grounded evidence: **C1** — A2's exposure path corrected (`FirefliesMemberLookup.getLocalMember()`
  already exists; resolver owns a 3-method port, no `MembershipView` surgery). **C2** — added **B4**: node
  UUIDs are canonically `digestToUuid(memberId)` (`NodeBootstrap.resolveNodeId`), so `getMemberByUuid` is the
  UUID→`Digest` mapping the B1 hint needs; critic's "no mapping" held only for *bubble* UUIDs. **C3** — defined
  the two node roles (source = possession/local, target = HRW owner); self-migration reject (`:365`) makes the
  single-member live path correct, and physical placement honoring HRW is a *named* partition-layer contract
  (`s23eu`), not a silent assumption. **S1** O(1) inverse `UUID→TetreeKey` index added to the plan; **S2** HRW
  convergence test added to MVV; **S3** `digestOf` deletion scope enumerated (2 sites, one class); **S4**
  non-local-source path removed (source = local, always). Test-caller impact (random-UUID migrations) folded
  into the plan as Step 7.
- 2026-06-08: **re-gate PASSED** (substantive-critic; 0 Critical, 2 implementation-scoped Significant). Folded
  in: `ViewCommitteeSelector.isNodeInView` uses `allMembers()` not `active()` — the active-only RDR-005
  invariant must also be enforced at the validator (new Step 6 / Approach item 6); and the `digestOf` scope
  count corrected to "3 invocations in one method." No design changes required.
- 2026-06-08: **research pass 2** (Source Search; T2 `Luciferase_rdr/020-research-2`). **B2 VERIFIED**
  (`viewId` check at submission `:182` + pre-execution `:223`; `onViewChange` rolls back pending `:252-269`).
  **B3 VERIFIED** (`TetreeBubbleGrid.getKeyForBubble(UUID)` `:641-650`, already used by topology ops).
  **B1 PARTIAL → resolved**: `initiateRemoteMigration(entityId, targetNodeId)` (`:108-126`) takes an explicit
  target, which a position-derived owner can contradict. **Decision**: HRW `owner(targetBubbleKey, view)` is
  authoritative; an explicit `targetNodeId` is validated against it and **fails loud on mismatch**. All
  assumptions now resolved; ready for `/conexus:rdr-gate` (which should scrutinize the B1 decision).
