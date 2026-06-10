---
title: "Wire Bubble Ownership Resolution into the Production Node Bootstrap"
id: RDR-022
type: Architecture
status: accepted
priority: medium
author: self
reviewed-by: self
created: 2026-06-10
accepted_date: 2026-06-10
related_issues: [Luciferase-s23eu, Luciferase-0frcy]
---

# RDR-022: Wire Bubble Ownership Resolution into the Production Node Bootstrap

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

RDR-020 built the bubble→node ownership primitive — `FirefliesBubbleOwnershipResolver` (HRW over the
active Fireflies view) — and gave its three production consumers explicit injection seams. But **no
production code path constructs the resolver**: a project-wide search finds
`new FirefliesBubbleOwnershipResolver` only in tests. The production bootstrap (`NodeBootstrap`,
RDR-017/RDR-021) never assembles it, so in any assembled node every consensus entry point that needs
node identity is **fail-loud dead**: it throws on first use rather than resolving ownership. This is
the second half of the `Luciferase-s23eu` boundary ("make the assembled node actually
multi-node-ready"); RDR-021 closed the fault-tolerance half and explicitly scoped this half to a
sibling RDR (RDR-021 gate decision S3 / locked decision 5). This RDR decides whether and how to close it.

### Enumerated gaps to close

#### Gap 1: No production construction or injection of the resolver

`FirefliesBubbleOwnershipResolver` (`simulation/.../consensus/ownership/FirefliesBubbleOwnershipResolver.java`)
is constructed nowhere in `src/main`. Its three consumers each hold a `volatile BubbleOwnershipResolver`
that is never set in production:

- `TopologyConsensusCoordinator.setOwnershipResolver(...)` (`topology/TopologyConsensusCoordinator.java:194`);
  unset → `toMigrationProposal` throws `IllegalStateException("ownershipResolver not set")` (`:329`),
  so **every topology consensus proposal fails** in an assembled node.
- `OptimisticMigratorImpl.setOwnershipResolver(...)` (`distributed/migration/OptimisticMigratorImpl.java:130`);
  unset → consensus-gated migration approval throws (`:169`), so **the quorum gate is dead**.
- `DistributedBubbleNode.setOwnershipResolver(...)` (`distributed/network/DistributedBubbleNode.java:124`);
  unset → the explicit-target-node hint validation path (RDR-020 B1/B4) is unavailable.

The fail-loud contract is working as designed (RDR-020: never silently approve/reject) — but the
production wiring that makes the paths *live* was deferred to `s23eu` and does not exist.

#### Gap 2: The bootstrap lacks the resolver's construction dependencies at the assembly seam

The production convenience constructor needs `(FirefliesMemberLookup, MembershipView<Member>,
TetreeBubbleGrid, SpatialOwnershipFunction)`. `NodeBootstrap.assemble`/`assembleFaultTolerance` hold
none of these today: `FirefliesMemberLookup` requires a live Fireflies `View`
(`von/FirefliesMemberLookup.java:58`) — the same dependency that keeps the live `main()` a fail-loud
skeleton (RDR-017 P1) — and no `TetreeBubbleGrid` or `MembershipView` flows through the assemble
signatures. The RDR must define the assembly seam (an `assembleOwnership(...)` sibling of RDR-021's
`assembleFaultTolerance`, or parameters on `assemble`), and decide what is constructed vs injected.
The narrow-seam primary constructor (function seams; no live `View` needed) is the likely MVV path,
mirroring RDR-021's test-assembled-node strategy.

#### Gap 3: The resolver's consumers may not be part of the assembled node at all

`NodeBootstrap.assemble` wires `SocketConnectionManagerAdapter` + `PersistenceManagerAdapter`
(+ optional migrator adapter, RDR-017 P2; + fault subsystem, RDR-021). It does **not** construct
`TopologyConsensusCoordinator`, `OptimisticMigratorImpl`, or `DistributedBubbleNode`. "Wire the
resolver into the bootstrap" is therefore under-specified until research establishes **which
consumers the assembled node actually owns** — the scope may refine to "construct + wire the
ownership subsystem into the consumers the node assembles," exactly as RDR-021's scope refined from
"wire RecoveryIntegration" to "construct + wire the fault subsystem." It may equally refine to a
narrower contract: provide the assembled resolver and the injection helper, leaving consumer
construction to the (separate) arcs that assemble those consumers.

#### Gap 4: No end-to-end proof that an assembled node resolves ownership

Existing coverage is unit/integration at the resolver and consumer level (`OwnershipTest`,
`Rdr020MvvIntegrationTest`) with test doubles or directly-constructed objects. Nothing proves that a
**bootstrap-assembled** node produces a resolver whose `localMember()`/`resolveOwningMember()`
agree with the node identity used everywhere else (`NodeBootstrap.resolveNodeId` =
`FirefliesMemberLookup.digestToUuid(member.getId())`, the canonical derivation — gate C1/B4 of
RDR-020). The MVV must exercise the wired chain against the assembled node, mirroring the RDR-020/021
MVV lesson (mocking the integration boundary hides the gap).

### Explicitly in question (decide, do not assume)

- **Placement-honors-HRW**: RDR-020 named the contract — the partition/placement layer must seed each
  bubble onto `owner(bubbleKey, view)` and re-home on view change — and deferred it to the
  `s23eu`/RDR-015 follow-on. Is it this RDR's scope, or does it remain a named deferral here too?
  (Without it, multi-node steady-state correctness is still gated; with it, this RDR's blast radius
  grows substantially.)

## Context

### Background

RDR-020 (implemented 2026-06-09) made migration/topology consensus functional against a live
committee by replacing the bogus `digestOf(UUID)` node identities with a real ownership resolution:
source = local member (possession), target = HRW owner of the destination region over the
active-only view. Its post-mortem names "placement-honors-HRW partition seeding and production
resolver wiring → `s23eu`" as the explicit deferral. RDR-021 (implemented 2026-06-09) closed the
fault-tolerance half of `s23eu` — `NodeBootstrap.assembleFaultTolerance` constructs and
lifecycle-integrates the partition fault subsystem — and locked decision 5: "Resolver wiring →
sibling RDR." This RDR is that sibling.

The live production path today is single-process: every consensus proposal is a correctly-rejected
self-migration (RDR-020 §Approach, two-node-roles analysis). Resolver wiring is what makes the
multi-node consensus path *reachable* from an assembled node; multi-node steady-state correctness
additionally depends on the placement contract (Gap/question above).

### Technical Environment

- `FirefliesBubbleOwnershipResolver` (`simulation/.../consensus/ownership/`): primary narrow-seam
  constructor `(Supplier<Member> localMemberSupplier, Function<UUID,Optional<Member>> nodeResolver,
  Function<UUID,TetreeKey<?>> bubbleKeyResolver, MembershipView<Member>, SpatialOwnershipFunction)`;
  convenience constructor `(FirefliesMemberLookup, MembershipView<Member>, TetreeBubbleGrid,
  SpatialOwnershipFunction)`. Fail-loud (`IllegalStateException`) on unresolvable inputs.
- `RendezvousOwnershipFunction` — the HRW `SpatialOwnershipFunction` implementation (stateless).
- `StubBubbleOwnershipResolver` — seeded deterministic double living in `src/main` (used by tests and
  available for single-process assembly decisions).
- Consumers + seams: `TopologyConsensusCoordinator.setOwnershipResolver` (`:194`),
  `OptimisticMigratorImpl.setOwnershipResolver` (`:130`), `DistributedBubbleNode.setOwnershipResolver`
  (`:124`).
- `NodeBootstrap` (`simulation/.../von/NodeBootstrap.java`, 369L): `resolveNodeId` (canonical
  member→UUID), `assemble(...)` overloads, `assembleFaultTolerance(Manager, Clock)` → `FaultSubsystem`
  record + `RecoveryIntegrationAdapter` lifecycle participant (the RDR-021 pattern to mirror),
  `createRegisteredBubble`/`removeRegisteredBubble` (the bubble-creation seam), fail-loud `main()`.
- `FirefliesMemberLookup(View view[, Random])` (`von/FirefliesMemberLookup.java:58-71`): requires a
  live Fireflies `View`; `getLocalMember()` → `view.getNode()`; `getMemberByUuid` (all-members backed
  — misnamed accessor caveat, RDR-020 B4); `digestToUuid` (canonical).
- Adjacent open work: `Luciferase-n6jrh.1` (assemble-before-createBubble guard), `n6jrh.2`
  (BubbleMigrator lifecycle integration) — same bootstrap, potential merge-conflict neighbors.

## Research Findings

### Investigation

Research pass 1 completed 2026-06-10 (Source Search; T2 `Luciferase_rdr/022-research-1`). All five
assumptions resolved. The decisive finding: **all three resolver consumers are test-only
constructions** — zero `new TopologyConsensusCoordinator/OptimisticMigratorImpl/DistributedBubbleNode`
sites exist in `src/main` (the latter two carry explicit comments: "no bootstrap assembly calls this
yet — the live path is single-process; multi-node wiring lands with bead Luciferase-s23eu"). So the
bootstrap has no production consumer to inject into; the correct scope is **expose the assembled
resolver via a bootstrap factory** (mirroring `assembleFaultTolerance`), with consumer injection
owned by whatever future arc assembles each consumer.

#### Dependency Source Verification

| Dependency | Source Searched? | Key Findings |
| --- | --- | --- |
| `FirefliesBubbleOwnershipResolver` | Yes | All five fields `final`, set at construction, never mutated; no `AutoCloseable`/`close()`, no threads, no subscriptions; `resolveOwningMember` snapshots `activeMembers()` fresh per call (no cached view data). Lifecycle-passive. |
| `RendezvousOwnershipFunction` | Yes | Stateless (zero instance fields); pure Murmur3 deterministic function. |
| Consumer injection seams | Yes | Exactly three consumers (exhaustive search): `TopologyConsensusCoordinator:194`, `OptimisticMigratorImpl:130`, `DistributedBubbleNode:124`; all `volatile` setter injection, fail-loud when unset (coordinator `:329`, migrator `:169`). |
| Consumer construction sites | Yes | **Zero in `src/main` for all three** — every ctor call is in tests (`TopologyConsensusCoordinatorTest:61`, `Rdr020MvvIntegrationTest:278,467`, 20+ migrator test sites, 14+ node test sites). No production injection target exists. |
| `NodeBootstrap` assembly surface | Yes | No resolver, no `TetreeBubbleGrid`, no `MembershipView`, no consumer construction. `assembleFaultTolerance(Manager, Clock)` is the factory pattern to mirror; resolver needs no lifecycle registration (A2). |
| `Manager` / `createRegisteredBubble` ↔ grid | Yes | `Manager` fields: `Map<UUID,Bubble>`, transport registry, clock, coordinator — **no `TetreeBubbleGrid`**. `Manager.createBubble` never calls `grid.addBubble`; `createRegisteredBubble` (`:311-324`) touches no grid. Grid construction in `src/main`: `MultiBubbleSimulation:129` (separate orchestrator) and `PredatorPreyGridDemo:73` (demo) only. |
| Test membership infrastructure | Yes | `MockFirefliesView` lives in `src/main` (`delos/mock/MockFirefliesView.java:39`, addMember/removeMember/markInactive); `MockMember` from Delos (`new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i))`). Exact wiring proven in `Rdr020MvvIntegrationTest:157-174`. |
| Consensus validity vs resolver wiring | Yes | `ViewCommitteeConsensus.validateProposal:352-410`: null guards, self-migration reject (`:388-392`), `isNodeInView` (`:396,:403`) all operate on `Digest`s/committee selector, independent of resolver wiring. `OptimisticMigratorImpl.requestMigrationApproval:166` and `DistributedBubbleNode.initiateRemoteMigration:157` branch on null integration/resolver — wiring is purely enabling. |
| Placement seeding blast radius | Yes | Placement key selection happens at `TetreeBubbleGrid.addBubble(bubble, key)` call sites (`MultiBubbleSimulation` + tests). Absorbing HRW seeding would force `NodeBootstrap` to own a grid and intercept key selection — large, cross-cutting. Out. |

### Key Discoveries

- **Documented (file:line)** — the resolver and HRW function are real, tested, and **lifecycle-passive**;
  the gap is pure composition (no new mechanism, no lifecycle participant), simpler than RDR-021's.
- **Scope resolution (Gap 3, load-bearing)** — no production code constructs any of the three
  consumers; therefore RDR-022 **cannot and should not wire consumers**. It owns one factory:
  assemble the resolver in `NodeBootstrap`, return it, document the injection contract for future
  consumer-assembly arcs.
- **Seam correction (Gap 2/A4)** — `NodeBootstrap`/`Manager` have **no `TetreeBubbleGrid`**; the
  `bubbleKeyResolver` function must be a caller-supplied parameter of the factory, not bound
  internally. The resolver's convenience ctor (taking a grid) is for callers that own one.
- **Documented** — the consumers fail loud when unwired, so the current assembled node cannot
  silently mis-resolve; the cost of the gap is *unavailability* of consensus paths, not corruption.

### Critical Assumptions

- [x] **A1**: The production consumer set is exactly the three found, and their construction sites
  determine scope. — **Status**: **PARTIAL → resolved by finding** (Source Search) — consumer set
  VERIFIED exhaustive (3); construction sites REFUTED the "wire consumers" framing: all three are
  test-only constructions with explicit `s23eu` markers. Scope shape: factory-exposes-resolver, no
  consumer wiring (no target exists).
- [x] **A2**: The resolver is lifecycle-passive — no `LifecycleComponent` participant, no shutdown
  ordering. — **Status**: **VERIFIED** (Source Search) — all-final fields, no close/threads/
  subscriptions/caching; `RendezvousOwnershipFunction` stateless.
- [x] **A3**: The MVV can assemble the resolver without a live Fireflies `View`. — **Status**:
  **VERIFIED** (Source Search) — `MockFirefliesView` (in `src/main`) + Delos `MockMember` satisfy
  the narrow seams; the pattern is already proven in `Rdr020MvvIntegrationTest:157-174` and the
  no-live-View bootstrap test pattern in `Rdr021MvvIntegrationTest`.
- [x] **A4**: The grid backing `bubbleKeyResolver` is reachable at the assembly seam. — **Status**:
  **REFUTED** (Source Search) — `TetreeBubbleGrid` is absent from the `NodeBootstrap`/`Manager`
  domain (grid lives in `MultiBubbleSimulation`/demo/caller code). Consequence: `bubbleKeyResolver`
  (or a grid) is a **factory parameter**, caller-supplied.
- [x] **A5**: Placement-honors-HRW is separable; resolver wiring is purely enabling. — **Status**:
  **VERIFIED** (Source Search) — validity gates (`validateProposal:352-410`) are
  resolver-independent; null-integration/null-resolver branches mean wiring alone changes no
  behavior. Placement seeding stays **out** (named deferral, partition layer / `s23eu` follow-on).

**Method definitions**: Source Search = API verified against dependency source. Spike = behavior
verified by running code. Docs Only = insufficient for load-bearing assumptions.

## Proposed Solution

### Approach

Refined post-research-pass-1: no production consumer exists to inject into (A1), the resolver is
lifecycle-passive (A2), and the bootstrap has no grid (A4). The work is therefore a **single
bootstrap factory + the end-to-end proof**, deliberately smaller than RDR-021.

Locked decisions:
1. **One factory: `NodeBootstrap.assembleOwnershipResolver(...)`** mirroring the
   `assembleFaultTolerance` precedent — external deps as parameters, the method does the wiring,
   returns the assembled `BubbleOwnershipResolver`. It constructs the `RendezvousOwnershipFunction`
   internally (the one canonical HRW choice, RDR-020) and the `FirefliesBubbleOwnershipResolver`
   around the supplied seams.
2. **`bubbleKeyResolver` is caller-supplied** (`Function<UUID, TetreeKey<?>>`). The bootstrap/`Manager`
   domain has no `TetreeBubbleGrid` (A4 refuted); callers that own a grid pass `grid::getKeyForBubble`.
   No grid is constructed or owned by `NodeBootstrap`.
3. **No consumer wiring in this RDR.** All three consumers are test-only constructions; the factory's
   javadoc documents the injection contract (`setOwnershipResolver` at each consumer's assembly
   point), and the consumer-assembly arcs (future multi-node work under `s23eu`/RDR-017 P1) perform
   the injection. Wiring a consumer here would require first assembling the consumer — silent scope
   creep in the other direction.
4. **No lifecycle participant.** The resolver is stateless/subscription-free (A2 verified); it is
   returned, not registered. No shutdown ordering exists for it.
5. **Placement-honors-HRW stays OUT** (A5) — re-affirmed named deferral to the partition layer
   (`s23eu`/RDR-015 follow-on), as in RDR-020. Resolver wiring is purely enabling and does not
   depend on it.

Named dependency (not a blocker): production activation — a live `FirefliesMemberLookup` requires
the live Fireflies `View` that `main()` cannot yet build (RDR-017 P1). The MVV runs against a
test-assembled node (`MockFirefliesView` + `MockMember`), so it does not depend on `main()` —
identical to the RDR-021 MVV posture.

### Technical Design

**Factory (in `NodeBootstrap`, sibling of `assembleFaultTolerance`).** All signatures Verified
against source.

```text
// Production form — memberLookup supplies localMember + node-UUID resolution (needs a live View):
public static BubbleOwnershipResolver assembleOwnershipResolver(
        FirefliesMemberLookup memberLookup,            // Verified ctor(View[, Random]); getLocalMember(), getMemberByUuid(UUID)
        MembershipView<Member> membershipView,         // Verified — active-only member stream (RDR-020 B4 / RDR-005)
        Function<UUID, TetreeKey<?>> bubbleKeyResolver // caller-supplied (A4: bootstrap owns no grid)
) {
    return new FirefliesBubbleOwnershipResolver(
        memberLookup::getLocalMember,                  // Supplier<Member>
        memberLookup::getMemberByUuid,                 // Function<UUID, Optional<Member>>
        bubbleKeyResolver,
        membershipView,
        new RendezvousOwnershipFunction());            // Verified — stateless HRW
}
// Narrow-seam overload (test assembly / no live View) — matches the resolver's primary ctor
// minus SpatialOwnershipFunction, which the factory fixes internally (locked decision 1):
public static BubbleOwnershipResolver assembleOwnershipResolver(
        Supplier<Member> localMemberSupplier,
        Function<UUID, Optional<Member>> nodeResolver,
        Function<UUID, TetreeKey<?>> bubbleKeyResolver,
        MembershipView<Member> membershipView) { ... } // same body, seams direct
```

Notes:
- The factory adds over a bare `new`: the canonical assembly point in the bootstrap (discoverable
  next to `assembleFaultTolerance`), the locked HRW-function choice, and the documented injection
  contract. `requireNonNull` validation lives in the resolver's ctor already (fail-loud preserved).
- **No record wrapper** — unlike `FaultSubsystem` there is exactly one object and nothing to close.
- **Injection contract (javadoc, normative):** a consumer assembly point that constructs
  `TopologyConsensusCoordinator`, `OptimisticMigratorImpl`, or `DistributedBubbleNode` MUST call
  `setOwnershipResolver(resolver)` with a bootstrap-assembled resolver before first consensus use;
  the consumers' existing fail-loud guards (coordinator `:329`, migrator `:169`) enforce it.
- **Active-only invariant**: the `membershipView` parameter is the *active-members* source
  (RDR-020 B4); the factory must NOT derive membership from
  `FirefliesMemberLookup.getActiveMembers()` (misnamed, all-members backed).

**MVV shape (Verified seams).** Test-assembled node, mirroring `Rdr020MvvIntegrationTest:157-174`:
`MockFirefliesView<Member>` seeded with ≥2 `MockMember`s; a real `TetreeBubbleGrid` with bubbles at
real `TetreeKey`s, `grid::getKeyForBubble` as the key seam; factory-assembled resolver injected into
a real `TopologyConsensusCoordinator`. Assertions: (1) canonical identity round-trip —
`NodeBootstrap.resolveNodeId(member) == FirefliesMemberLookup.digestToUuid(resolver.localMember())`;
(2) the previously-dead path produces a valid, `validateProposal`-passing proposal (vs today's
`IllegalStateException("ownershipResolver not set")`); (3) fail-loud survives wiring — an
unresolvable bubble still throws.

### Decision Rationale

- **Factory-exposes-resolver, not consumer wiring** — there is no production consumer to wire (A1):
  every `setOwnershipResolver` target is constructed only in tests, each marked "multi-node wiring
  lands with `s23eu`". Assembling consumers just to inject into them would smuggle the multi-node
  consensus-stack assembly into this RDR; exposing the resolver at the canonical bootstrap seam is
  the entire real gap.
- **Caller-supplied `bubbleKeyResolver`** — forced by A4: the bootstrap/`Manager` own no grid, and
  inventing grid ownership in `NodeBootstrap` would be a topology decision this RDR has no mandate
  for. A function parameter keeps the seam narrow and testable.
- **No lifecycle participant** — A2 verified the resolver holds no state, threads, or
  subscriptions; registering it would be cargo-culting the RDR-021 adapter onto a passive object.
- **`RendezvousOwnershipFunction` fixed inside the factory** — RDR-020 locked HRW as the ownership
  function; making it a parameter invites divergent ownership functions across a cluster, which
  breaks the every-node-computes-identically property HRW exists to provide.
- **Placement seeding out** — A5: validity gates are resolver-independent and wiring is purely
  enabling, so deferral costs nothing now; absorbing it would require bootstrap grid ownership
  (large, cross-cutting with `MultiBubbleSimulation`).

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| Ownership resolution | `FirefliesBubbleOwnershipResolver` + `RendezvousOwnershipFunction` (RDR-020) | Reuse — wire, do not reimplement. |
| Assembly seam pattern | `NodeBootstrap.assembleFaultTolerance` (RDR-021) | Mirror the factory shape; **no** record wrapper, **no** lifecycle registration (one passive object). |
| Lifecycle participation | `RecoveryIntegrationAdapter` (RDR-021) | **N/A** — A2 verified the resolver is lifecycle-passive. |
| Single-process double | `StubBubbleOwnershipResolver` (src/main) | Test double only — never assembled in production (locked decision; wiring it would silently degrade ownership semantics). |
| Consumer construction | `TopologyConsensusCoordinator` / `OptimisticMigratorImpl` / `DistributedBubbleNode` | **Out of scope** — all test-only today (A1); injection contract documented on the factory; assembly belongs to future `s23eu` multi-node arcs. |
| Placement seeding | `TetreeBubbleGrid` add paths | **Out (named deferral)** — partition layer (`s23eu`/RDR-015 follow-on), per RDR-020 and A5. |

## Alternatives Considered

### Briefly Rejected

- **Leave it unwired (status quo)**: consensus paths stay fail-loud dead in the assembled node;
  `s23eu` never closes. Acceptable only while the live path is single-process — which is exactly the
  condition this RDR-pair exists to end.
- **Wire `StubBubbleOwnershipResolver` in production as a placeholder**: silently degrades the
  ownership semantics (fixed seeded maps) — violates the fail-loud contract's purpose. Test double
  stays a test double.

(Full alternatives analysis after research.)

## Trade-offs

### Consequences

- (+) The assembled node gains live node-identity resolution; the consensus entry points become
  reachable instead of fail-loud dead; `s23eu` can close.
- (−) Widens the bootstrap's dependency surface toward membership (`MembershipView`/`View`) and the
  bubble grid; the wiring must be proven by integration tests against the assembled node, not just
  compiled.

### Risks and Mitigations

- **Risk**: Gap-3 scope creep — "wire the resolver" balloons into "assemble the entire distributed
  consensus stack." **Mitigation**: A1 research pins the consumer set and ownership; anything beyond
  injection seams goes to named follow-on work.
- **Risk**: the production convenience ctor needs a live `View`, which `main()` cannot build yet
  (RDR-017 P1). **Mitigation**: mirror RDR-021 — MVV on a test-assembled node via the narrow-seam
  ctor; live activation remains the named `main()` dependency.
- **Risk**: placement-honors-HRW ambiguity leaks in as silent scope. **Mitigation**: §Explicitly in
  question forces an explicit in/out decision at research; the gate checks it.

### Failure Modes

Today (unwired): every assembled-node consensus path throws on first use (`ownershipResolver not
set` / UOE) — fail-loud, visible, but **dead**. After wiring: unresolvable bubbles / empty views
still throw per the RDR-020 contract (correct); wiring misconfiguration fails loud at construction
(`requireNonNull`). The new failure mode to guard: a resolver wired against the *wrong* grid or a
non-canonical member identity would resolve *successfully but inconsistently* — the MVV's canonical
round-trip assertion (`resolveNodeId` ↔ `localMember()`) exists to catch exactly this.

## Implementation Plan

### Prerequisites

- [x] A1–A5 resolved via `/conexus:rdr-research` pass 1 (A1 decided scope shape:
  factory-exposes-resolver).
- [x] Placement-honors-HRW decision recorded: **out** (named deferral, A5).
- [ ] Gate (`/conexus:rdr-gate`) → accept.

### Minimum Viable Validation

An integration test over a **test-assembled** node (factory-assembled resolver via the narrow-seam
overload; real `RendezvousOwnershipFunction`; `MockFirefliesView` seeded with ≥2 `MockMember`s; a
real `TetreeBubbleGrid` supplying `grid::getKeyForBubble`; a real `TopologyConsensusCoordinator` —
not mocked at the resolution boundary) proving:
1. **Supplier threading (non-vacuous form — gate finding)**: the factory-assembled resolver's
   `localMember()` `Digest` equals `ownerMember.getId()` for the **specific** `MockMember` passed
   as `localMemberSupplier` (catching swapped/mis-threaded factory parameters), and
   `NodeBootstrap.resolveNodeId(ownerMember)` equals the expected canonical node UUID. Do NOT
   assert `resolveNodeId(member) == digestToUuid(resolver.localMember())` alone — both sides reduce
   to `digestToUuid(member.getId())`, which is trivially true regardless of wiring.
2. **Gap closure with negative control (gate observation)**: an **uninjected** coordinator first
   throws `IllegalStateException("ownershipResolver not set")` (`:329`) — the baseline — then the
   same scenario with the factory-assembled resolver injected produces a **valid,
   `validateProposal`-passing TOPOLOGY proposal** (≥2-member seeded view; single-owner model,
   RDR-020 S3 amendment). **HRW probe step required (gate finding)**: with ≥2 members, HRW assigns
   the bubble's region to one specific member; `localMemberSupplier` must return **that** member or
   the ownership guard (`:347-352`) throws. Use the probe-resolver pattern from
   `Rdr020MvvIntegrationTest:262-275` — probe to discover the HRW owner of the bubble's key, then
   build the real resolver with `localMember = ownerMember`.
3. The fail-loud contract survives wiring: an unresolvable bubble still throws.

### Phase 1: Code Implementation

To be decomposed after research/gate (`/conexus:rdr-research` → `/conexus:rdr-gate` →
`/conexus:rdr-accept`).

## Revision History

- 2026-06-10: created (draft) — scoped from `Luciferase-s23eu` resolver half, per RDR-021 gate
  decision S3 / locked decision 5 ("Resolver wiring → sibling RDR"). Pre-creation reconnaissance
  confirmed: zero production construction sites for `FirefliesBubbleOwnershipResolver`; three
  fail-loud consumer injection seams; `NodeBootstrap` holds neither the resolver's dependencies nor
  its consumers (Gap 3 is the load-bearing scope question, A1). Next: `/conexus:rdr-research`.
- 2026-06-10: **research pass 1** (Source Search; T2 `Luciferase_rdr/022-research-1`). **A1
  PARTIAL→resolved**: consumer set verified exhaustive (3), but all three are **test-only
  constructions** (zero `src/main` ctor sites; explicit "multi-node wiring lands with s23eu"
  comments) — scope shape locked to **factory-exposes-resolver, no consumer wiring**. **A2
  VERIFIED** (all-final fields, no close/threads/subscriptions; HRW function stateless → no
  lifecycle participant). **A3 VERIFIED** (`MockFirefliesView` in `src/main` + Delos `MockMember`;
  pattern proven in `Rdr020MvvIntegrationTest:157-174`). **A4 REFUTED** (`Manager`/`NodeBootstrap`
  own no `TetreeBubbleGrid`; grid lives in `MultiBubbleSimulation`/demos) — `bubbleKeyResolver`
  becomes a caller-supplied factory parameter. **A5 VERIFIED** (validity gates
  resolver-independent; null-integration/null-resolver branches make wiring purely enabling) —
  placement-honors-HRW stays **out**, named deferral re-affirmed. Locked decisions 1–5 recorded;
  §Technical Design + §Decision Rationale filled. Next: `/conexus:rdr-gate`.
- 2026-06-10: **gate PASSED** (substantive-critic; 0 Critical, 2 Significant, 3 Observations).
  Design, scope honesty (factory-exposes-resolver is the right conclusion, not silent reduction),
  and cross-RDR consistency with RDR-020/021 all confirmed against source. Both Significants were
  MVV-spec defects, fixed in-place: (S1) the HRW **probe step** was missing — with ≥2 members the
  ownership guard (`toMigrationProposal:347-352`) throws unless `localMemberSupplier` returns the
  HRW owner; MVV now mandates the `Rdr020MvvIntegrationTest:262-275` probe pattern. (S2) the
  canonical round-trip assertion was **vacuously true** as written (both sides reduce to
  `digestToUuid(member.getId())`); restated as a supplier-threading assertion against the specific
  `ownerMember`. Observations folded in: negative control (uninjected coordinator throws
  `ownershipResolver not set` baseline before asserting gap closure); "mirrors primary ctor"
  wording corrected to "minus `SpatialOwnershipFunction` (fixed internally)". Ready for
  `/conexus:rdr-accept`.
