---
title: "Wire Partition Fault Tolerance into the Production Node Bootstrap"
id: RDR-021
type: Architecture
status: draft
priority: medium
author: self
reviewed-by: self
created: 2026-06-09
accepted_date:
related_issues: [Luciferase-s23eu, Luciferase-0frcy, Luciferase-n6jrh.1, Luciferase-n6jrh.2]
---

# RDR-021: Wire Partition Fault Tolerance into the Production Node Bootstrap

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

The production node assembled by RDR-017 (`NodeBootstrap.assemble`) has WAL durability for the
migration FSM but **no self-healing under network partition**: it never escalates VON neighbor
failures to the partition fault detector. The escalation component (`RecoveryIntegration`) exists and
is unit-tested, but is **not instantiated or wired** into the production bootstrap graph. This was
acknowledged as an explicit scope boundary at the RDR-017 gate (`Luciferase-s23eu`); this RDR decides
whether and how to close it.

### Enumerated gaps to close

#### Gap 1: VON neighbor-leave never reaches the FaultHandler in production

`RecoveryIntegration.onNeighborLeave(Event.Leave)` is the **only** production path that escalates a
VON neighbor departure to the partition fault detector — it maps the departed bubble to its partition
and calls `faultHandler.reportSyncFailure(partitionId)`
(`simulation/.../von/RecoveryIntegration.java`, `onNeighborLeave`). But `NodeBootstrap.assemble`
(`simulation/.../von/NodeBootstrap.java:137,174`) never constructs a `RecoveryIntegration` nor
subscribes it to the VON `Manager` event stream. Consequence: a partition failure in any VON neighbor
is **silently absorbed** — `FaultHandler` never learns, partition status never transitions to
`FAILED`, and `onPartitionRecovered()` → bubble-rejoin never fires. The node is not
partition-fault-tolerant. The fix wires the escalation path into the production lifecycle so neighbor
failures drive partition status transitions and recovery.

#### Gap 2: No end-to-end proof that a VON leave drives partition FAILED → recovery → bubble rejoin

Even with `RecoveryIntegration` wired, there is today no integration test exercising the full chain
`VON Leave event → reportSyncFailure → partition FAILED → onPartitionRecovered → bubble rejoin`
against the assembled node. Unit tests cover `RecoveryIntegration` in isolation (mocked
`FaultHandler`); the productionization must prove the wired chain end-to-end (mirroring the RDR-020
MVV lesson: mocking the integration boundary hides the gap).

#### Gap 3: Lifecycle ordering / shutdown of the recovery subscription is undefined

`RecoveryIntegration` subscribes to `FaultHandler` changes (`faultHandler.subscribeToChanges(...)`)
and to the VON event stream. The bootstrap must define **where** in the lifecycle graph the
subscription is created and **when** it is torn down on shutdown, relative to the VON `Manager`,
`FaultHandler`/`PartitionTopology`, and the migration/WAL layers (RDR-017 Layer ordering). An
ordering error (e.g. tearing down the VON manager before unsubscribing) leaks or double-fires.

## Context

### Background

RDR-017 (Production Node Bootstrap, implemented) deliberately omitted `RecoveryIntegration` from the
assembled node, correctly classifying it as orthogonal to durability (zero disk I/O — it is a topology
bridge, not a WAL). The substantive-critic flagged the **operational** consequence at the RDR-017 S1
gate and it was filed as the acknowledged boundary `Luciferase-s23eu`: the shipped P0–P3 node has
migration-FSM durability but no partition self-healing. RDR-017's own note: "Productionizing
`RecoveryIntegration` is a separate decision gated on whether `FaultHandler`/partition topology ship."
This RDR is that decision.

This is adjacent to RDR-020 (just landed): RDR-020 made migration consensus functional against a live
committee and named the production resolver wiring (`FirefliesBubbleOwnershipResolver` into the
bootstrap) as a multi-node `s23eu` dependency. Partition fault tolerance and resolver wiring are the
two halves of "make the assembled node actually multi-node-ready"; this RDR scopes the fault-tolerance
half (and should decide whether resolver wiring belongs here or in a sibling RDR).

### Technical Environment

- `RecoveryIntegration` (`simulation/.../von/RecoveryIntegration.java`): constructor
  `(Manager vonManager, PartitionTopology topology, FaultHandler faultHandler[, Clock])`; subscribes
  to `FaultHandler.subscribeToChanges`; consumes VON `Event.Leave/Move/GhostSync`; maps bubble→partition.
- `FaultHandler` (`lucien/.../balancing/fault/FaultHandler`): `reportSyncFailure(partitionId)`,
  `subscribeToChanges(handler)`, partition status model (`FAILED`, recovery callbacks).
- `NodeBootstrap` (`simulation/.../von/NodeBootstrap.java`): `assemble(...)` composes the RDR-017
  lifecycle graph (SocketConnectionManager, PersistenceManager, migration coordinator). The wiring
  point for `RecoveryIntegration` is here.
- Related open RDR-017 follow-ups: `Luciferase-n6jrh.1` (assemble()-before-createBubble guard),
  `n6jrh.2` (BubbleMigrator lifecycle integration) — adjacent lifecycle-ordering work.

## Research Findings

### Investigation

Source Search completed 2026-06-09 (T2 `Luciferase_rdr/021-research-1`). The entire escalation +
recovery chain is **real, substantial logic — not a stub** (refuting the `yogvu` "hollow balancing/fault
recovery stubs" concern for this path). The only gap is composition: `NodeBootstrap.assemble`
constructs neither the fault subsystem nor `RecoveryIntegration`.

Verified chain (all file:line confirmed):
`vonManager.addEventListener` → `RecoveryIntegration.handleVonEvent` → `onNeighborLeave` →
`faultHandler.reportSyncFailure(partitionId)` → `DefaultFaultHandler` HEALTHY→SUSPECTED→FAILED →
`faultHandler.subscribeToChanges` callback → `handleRecoveryEvent` → (FAILED→HEALTHY) →
`onPartitionRecovered` (cooldown + BFS over dependents with cycle prevention) →
`processPartitionRecovery` → per-bubble `vonManager.joinAt(bubble, position)` (the real VON rejoin).

#### Dependency Source Verification

| Dependency | Source Searched? | Key Findings |
| --- | --- | --- |
| `RecoveryIntegration` (simulation/von) | Yes | Complete. Subscribes via `vonManager.addEventListener(Consumer<Event>)` + `faultHandler.subscribeToChanges` in its constructor (`:173-178`); `close()` (`:351+`) unsubscribes both. `onNeighborLeave` → `reportSyncFailure`; `processPartitionRecovery` → `vonManager.joinAt` is the real rejoin. Not constructed in `NodeBootstrap`. |
| `FaultHandler` (lucien balancing/fault) | Yes | Rich interface (290L): `reportSyncFailure`, `subscribeToChanges`, `reportPartitionFailed`, `registerRecovery`/`initiateRecovery`/`notifyRecoveryComplete`, HEALTHY→SUSPECTED→FAILED→HEALTHY. **Two real impls**: `DefaultFaultHandler` (645L, barrier-timeout detection, 0 stub marks), `SimpleFaultHandler` (529L, atomic local escalation). |
| `PartitionTopology` (lucien balancing/fault) | Yes | Interface (104L) + `InMemoryPartitionTopology` (89L), 0 stub marks — real, in-memory single-node topology bridge. |
| `PartitionRecovery` strategies | Yes | `CascadingRecoveryImpl` (383L) and `BarrierRecoveryImpl` (377L) are real; `NoOpRecoveryImpl` (149L) is the explicit no-op variant. RDR must CHOOSE the strategy registered per partition. |
| `FaultAwarePartitionRegistry` (lucien) | Yes | Existing production assembly that constructs `FaultHandler` — a possible composition to reuse rather than hand-wiring in `NodeBootstrap`. |
| VON `Manager` event stream | Yes | `addEventListener(Consumer<Event>)` is the push subscription seam; `getBubble`, `getAllBubbles`, `joinAt(bubble, position)` are the rejoin primitives. No VON internal change needed. |
| `NodeBootstrap.assemble` (simulation/von) | Yes | Wires Layer-0 SocketConnectionManager + PersistenceManager (+ optional BubbleMigrator); **constructs NO `FaultHandler`/`PartitionTopology`**. Live `main()` is a fail-loud Phase-0 skeleton (RDR-017 P0). |

### Key Discoveries

- **Documented (file:line)** — the full escalation→recovery chain is real; `RecoveryIntegration`
  subscribes in-constructor and tears down via `close()`.
- **Documented (file:line)** — `FaultHandler` (Default/Simple), `PartitionTopology` (InMemory), and
  `PartitionRecovery` (Cascading/Barrier) are all real, non-stub. The `yogvu` hollow-stub concern is
  **refuted** for the partition-fault path.
- **Scope refinement (load-bearing)** — `NodeBootstrap.assemble` holds no fault subsystem today, so
  RDR-021 is **"construct + wire the fault subsystem into the node bootstrap"**, not merely "wire
  `RecoveryIntegration`". The RDR must decide: (a) `DefaultFaultHandler` vs `SimpleFaultHandler`;
  (b) the per-partition `PartitionRecovery` strategy (Cascading/Barrier/NoOp); (c) topology source
  (`InMemoryPartitionTopology` for single-process, or a real distributed topology); (d) whether to
  reuse `FaultAwarePartitionRegistry` as the assembly. This is composition of real parts, not building
  a state machine.
- **Coupling** — production activation is coupled to the still-skeletal live `main()` (RDR-017 P0,
  currently fails loud). The MVV can run against an assembled-in-test node without the live `main()`.

### Critical Assumptions

- [x] `FaultHandler`/`PartitionTopology` implement a real (non-stub) partition-status machine with a
  working FAILED→recovered transition and bubble-rejoin callback. — **Status**: **VERIFIED** (Source
  Search) — DefaultFaultHandler 645L / SimpleFaultHandler 529L real state machines;
  `RecoveryIntegration.processPartitionRecovery` → `vonManager.joinAt` is the real rejoin.
- [x] VON `Manager` exposes a neighbor-leave event stream that `RecoveryIntegration` can subscribe to
  in the assembled node without changing VON internals. — **Status**: **VERIFIED** (Source Search) —
  `Manager.addEventListener(Consumer<Event>)`; `getBubble`/`joinAt` for rejoin.
- [x] The recovery subscription can be created/torn down at a well-defined point in the RDR-017
  lifecycle graph without reordering the existing Layer 0/bubble dependencies. — **Status**:
  **VERIFIED** (Source Search + design, research pass 2) — `RecoveryIntegration` subscribes in its
  constructor and tears down via `close()`; the design places it as a lifecycle participant whose
  `close()` runs before the VON manager stop (mirrors the RDR-017 migrator-before-WAL contract). The
  fault subsystem is hand-constructed (`SimpleFaultHandler` + `InMemoryPartitionTopology`); production
  activation is coupled to the live `main()` skeleton but the MVV does not depend on it.
- [x] The recovery model is VON-event-driven (research pass 2): `Leave`→`reportSyncFailure`,
  `Join`/`GhostSync`→`markHealthy`→FAILED→HEALTHY→`onPartitionRecovered`→`vonManager.joinAt`. —
  **Status**: **VERIFIED** (Source Search) — `RecoveryIntegration` does not use
  `initiateRecovery`/`PartitionRecovery` (Phase-4.2 TODO), so no recovery strategy is registered.

**Method definitions**: Source Search = API verified against dependency source. Spike = behavior
verified by running code. Docs Only = insufficient for load-bearing assumptions.

## Proposed Solution

### Approach

Refined post-research-pass-2: the escalation/recovery components are all real, so the work is
**composition**, not building a state machine. The recovery model `RecoveryIntegration` actually
implements is **VON-event-driven** (research-pass-2): a VON `Leave` escalates partition health
(`reportSyncFailure`), and a later VON `Join`/`GhostSync` for a bubble in that partition calls
`markHealthy` — the FAILED→HEALTHY transition that fires `onPartitionRecovered` → bubble rejoin. It does
**not** call `initiateRecovery`/`registerRecovery`, so no `PartitionRecovery` strategy is registered
(that API — which has a Phase-4.2 TODO in the handlers — is bypassed).

Locked decisions:
1. **`FaultHandler` = `SimpleFaultHandler`** (not `DefaultFaultHandler`). `DefaultFaultHandler`'s
   SUSPECTED→FAILED transition is time-gated and fired by a polling `checkTimeouts()` that needs a
   periodic caller the bootstrap does not have (gate S1). `SimpleFaultHandler` escalates atomically per
   `reportSyncFailure` (HEALTHY→SUSPECTED on the first, SUSPECTED→FAILED on the second) with no
   scheduler — correct for the single-process node. Inject `Clock` via `setClock` for deterministic
   tests. **Confirmation threshold = two sync failures** (two VON leaves for the same partition) to
   reach FAILED.
2. **`PartitionTopology` = `InMemoryPartitionTopology`** (no-arg). Distributed topology is a later
   multi-node dependency, out of scope.
3. **No `PartitionRecovery` strategy registered** — the recovery path is `markHealthy`-on-VON-join, per
   the verified VON-event-driven model above.
4. **Assembly: hand-wire in `NodeBootstrap`** — `FaultAwarePartitionRegistry` is a `PartitionRegistry`
   barrier-timeout *decorator*, not a `FaultHandler`/topology assembler (gate S2; audit row corrected).
5. **Resolver wiring → sibling RDR** (gate S3). The RDR-020 `FirefliesBubbleOwnershipResolver`
   bootstrap wiring (the other `s23eu` half) is independent of fault-subsystem construction; it is
   scoped to a follow-on RDR, not here.

Wiring (Technical Design specifies it concretely): construct `SimpleFaultHandler` + `start()`,
`InMemoryPartitionTopology`, then `RecoveryIntegration(vonManager, topology, faultHandler)` (its
constructor subscribes to VON + fault events); register `RecoveryIntegration` as a lifecycle
participant whose `close()` runs **before** the VON manager stops (mirrors the RDR-017 migrator-before-WAL
shutdown contract); drive `registerBubble(bubbleId, partitionId)` from the bubble-creation path.

Named dependency (not a blocker for this RDR's MVV): production activation is coupled to the live
`main()` (currently throws — Fireflies-view construction incomplete). The MVV runs against a
test-assembled node, so it does not depend on `main()`.

### Technical Design

**Construction (a new `NodeBootstrap.assemble` overload or a sibling `assembleFaultTolerance(...)`
helper).** All signatures Verified against source unless marked Assumed.

```text
// Verified ctor signatures (lucien balancing/fault + simulation/von)
FaultConfiguration cfg = FaultConfiguration.defaultConfig();      // Verified — failureConfirmationMs unused by SimpleFaultHandler
SimpleFaultHandler fh   = new SimpleFaultHandler(cfg);            // Verified ctor(FaultConfiguration)
fh.setClock(clock);                                              // Verified — deterministic tests
fh.start();                                                      // Verified — lifecycle
InMemoryPartitionTopology topo = new InMemoryPartitionTopology(); // Verified — no-arg
RecoveryIntegration ri = new RecoveryIntegration(vonManager, topo, fh /*, clock */);
//   ^ Verified ctor(Manager, PartitionTopology, FaultHandler[, Clock]); subscribes to
//     vonManager.addEventListener + fh.subscribeToChanges in the ctor.
ri.registerBubble(bubbleId, partitionId);                        // Verified — per bubble; topo.register(partitionId, rank)
// shutdown: ri.close()  (unsubscribes both) BEFORE vonManager stop; then fh.stop().
```

**Lifecycle-layer placement.** The fault subsystem is orthogonal to the RDR-017 Layer-0
durability graph (SocketConnectionManager, PersistenceManager) — it holds no WAL and gates no bubble
dependency. Place it as follows, after the existing `assemble(...)` wiring:
- Construct + `start()` the `SimpleFaultHandler`, construct the `InMemoryPartitionTopology`, then
  construct `RecoveryIntegration` (which subscribes to the live VON `Manager` already owned by the
  node). The VON `Manager` is up by this point (it is the object `assemble` is called on).
- **Shutdown ordering (mirrors RDR-017 migrator-before-WAL).** `RecoveryIntegration.close()` MUST run
  **before** `vonManager` stops (so no VON event fires into an unsubscribed/half-torn handler) and
  before `faultHandler.stop()`. Concretely: register `RecoveryIntegration` as a lifecycle participant
  ordered to stop ahead of the VON manager, OR (matching the migrator precedent) document that the
  caller invokes `ri.close()` then `fh.stop()` before `Manager.close()`. Recommend the former
  (lifecycle participant) so `Manager.close()` drives it deterministically.

**Recovery model (research-pass-2, Verified).** The wired chain is purely VON-membership-driven:
- `Event.Leave` for a registered bubble → `onNeighborLeave` → `fh.reportSyncFailure(partitionId)`
  (HEALTHY→SUSPECTED→FAILED across two leaves).
- `Event.Join`/`Event.GhostSync` for a bubble in that partition → `onNeighbor{Join}`/`onGhostSync` →
  `fh.markHealthy(partitionId)`; if the partition was FAILED, this fires FAILED→HEALTHY →
  `handleRecoveryEvent` → `onPartitionRecovered` → `processPartitionRecovery` →
  `vonManager.joinAt(bubble, position)` per bubble in the partition.
- The handlers' `initiateRecovery`/`PartitionRecovery`-strategy path (Phase-4.2 TODO) is **not used**;
  no strategy is registered.

**`bubble → partition` registration seam.** Call `ri.registerBubble(bubbleId, partitionId)` from the
node's bubble-creation path (where the bubble's owning partition id is known). `unregisterBubble` on
bubble removal. Partition-id source for the single-process node: Assumed to be the node's own partition
identity (verify the available partition-id at the creation seam during implementation).

**Error contract.** A VON `Leave`/`Join` for a bubble **not** in `bubbleToPartition` is a deliberate
silent no-op (the `if (partitionId != null)` guard) — a VON bubble that is not partition-registered is
legitimately outside this node's fault scope; it is **not** an error and must NOT fail loud (many VON
neighbors are unregistered). The genuine fail-loud cases are wiring misconfiguration (null
`vonManager`/`topology`/`faultHandler`), already guarded by `RecoveryIntegration`'s constructor
`requireNonNull`. This corrects the §Failure Modes "fail-loud on unregistered bubble" framing.

### Decision Rationale

- **`SimpleFaultHandler` over `DefaultFaultHandler`** — the decisive factor is the gate-S1 finding:
  `DefaultFaultHandler` only reaches FAILED when a periodic `checkTimeouts()` runs, and the node has no
  scheduler; wiring it would leave partitions stuck at SUSPECTED and the recovery chain dead — the exact
  failure mode this RDR closes. `SimpleFaultHandler` self-escalates per `reportSyncFailure` with no
  background machinery, which fits the single-process node and keeps the MVV deterministic (inject two
  leaves → FAILED; inject a join → recover). The cost — no time-based confirmation delay — is acceptable
  for a single-process node and revisitable when a distributed deployment needs barrier-timeout
  detection.
- **No `PartitionRecovery` strategy** — `RecoveryIntegration` drives recovery via `markHealthy` on VON
  rejoin, not via `FaultHandler.initiateRecovery`; registering a strategy would be dead wiring (and
  `initiateRecovery` is itself a Phase-4.2 TODO). Choosing among Cascading/Barrier/NoOp is therefore a
  non-decision for this path.
- **Hand-wire, not `FaultAwarePartitionRegistry`** — that class is a `PartitionRegistry` decorator that
  *reports* barrier timeouts to a `FaultHandler`; it does not assemble one. Hand-wiring the three small
  objects is the correct, minimal composition.
- **Resolver wiring deferred to a sibling RDR** — it depends only on RDR-020's resolver, is independent
  of the fault subsystem, and bundling it would widen this RDR's blast radius without coupling benefit.

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| Recovery wiring | `RecoveryIntegration` (simulation/von) | Reuse — verified complete; wire, do not reimplement. |
| Partition fault detection | `DefaultFaultHandler` / `SimpleFaultHandler` (lucien balancing/fault) | Reuse — both verified real; RDR chooses which. |
| Partition topology | `InMemoryPartitionTopology` (lucien balancing/fault) | Reuse for single-process; distributed topology is a later dependency. |
| Recovery strategy | `CascadingRecoveryImpl` / `BarrierRecoveryImpl` / `NoOpRecoveryImpl` | **Not registered** — `RecoveryIntegration` recovers via `markHealthy`-on-VON-join, never `FaultHandler.initiateRecovery()` (Phase-4.2 TODO), so no `PartitionRecovery` strategy is registered in this RDR. |
| Fault-subsystem assembly | `FaultAwarePartitionRegistry` (lucien) | **Not applicable** — it is a `PartitionRegistry` barrier-timeout *decorator* (`ctor(PartitionRegistry, FaultHandler, long)`) that *reports* timeouts to a FaultHandler; it does not construct a `FaultHandler`/`PartitionTopology`. Hand-wire the subsystem in the bootstrap instead. |
| Lifecycle composition | `NodeBootstrap.assemble` | Extend — hand-construct `SimpleFaultHandler` + `InMemoryPartitionTopology` + `RecoveryIntegration` and add the recovery participant to the existing graph. |
| Production resolver wiring | `FirefliesBubbleOwnershipResolver` (RDR-020) | Defer — sibling RDR (the other `s23eu` half; independent of the fault subsystem). |

## Alternatives Considered

### Briefly Rejected

- **Leave it unwired (status quo)**: keeps `s23eu` open indefinitely; the node remains
  partition-fault-intolerant — only acceptable while the live path is single-process.
- **Reimplement escalation inline in the bootstrap**: duplicates the tested `RecoveryIntegration`; rejected.

## Trade-offs

### Consequences

- (+) The assembled node gains partition self-healing (neighbor failure → FAILED → recovery → rejoin).
- (−) Introduces a multi-node failure-handling path into a node whose live path is currently
  single-process; the new path must be exercised by integration tests, not just wired.

### Risks and Mitigations

- **Risk** (retired): `FaultHandler`/`PartitionTopology` is a hollow stub. **Status**: verified non-stub
  (research pass 1); the `yogvu` concern does not apply to this path.
- **Risk**: lifecycle-ordering error leaks/double-fires the subscription. **Mitigation**: model
  `RecoveryIntegration` as an explicit lifecycle participant whose `close()` is ordered before the VON
  manager stop (mirrors RDR-017 migrator-before-WAL), covered by a shutdown-ordering test.
- **Risk**: the recovery trigger is VON-`Join`-driven — if a failed partition never sees a rejoining
  bubble, recovery never fires (the partition stays FAILED). **Mitigation**: this is the correct
  membership-driven semantic (recovery follows VON re-membership); documented in §Technical Design.
  Partition-status metrics make a stuck-FAILED partition visible.
- **Risk** (Assumed): the partition-id available at the bubble-creation seam may not match the
  RecoveryIntegration partition model. **Mitigation**: verify the partition-id source at that seam
  during implementation (the one remaining Assumed item).

### Failure Modes

Today (unwired): a partition failure is **silently absorbed** — no signal, no recovery; diagnosed only
by noticing bubbles never rejoin. After wiring: wiring misconfiguration (null `vonManager`/`topology`/
`faultHandler`) fails loud at construction (`requireNonNull`). A VON leave/join for an
**unregistered** bubble is a deliberate silent no-op (logged at debug), **not** an error — an
unregistered VON neighbor is outside this node's fault scope (see §Technical Design error contract). A
partition that reaches SUSPECTED but never gets a second sync failure stays SUSPECTED (no recovery
fired) — visible in the partition-status metrics, diagnosable.

## Implementation Plan

### Prerequisites

- [x] All Critical Assumptions verified (research pass 1 + 2).
- [x] FaultHandler variant locked (`SimpleFaultHandler`) — Decision Rationale.
- [x] Resolver-wiring scope decided (sibling RDR) — Decision Rationale.

### Minimum Viable Validation

An integration test over a **test-assembled** node (real `RecoveryIntegration` + real
`SimpleFaultHandler` + real `InMemoryPartitionTopology`; `TestClock`; not mocked at the escalation
boundary) exercising the full VON-event-driven chain:
1. `registerBubble(bubbleId, partitionId)` for ≥1 bubble in a partition.
2. Inject **two** VON `Event.Leave` for the **registered** `bubbleId` → assert `SimpleFaultHandler`
   status for `partitionId` reaches **FAILED** (the two-sync-failure confirmation threshold).
3. Inject a VON `Event.Join` for a bubble in that partition → `markHealthy` → FAILED→HEALTHY →
   `onPartitionRecovered` → assert `vonManager.joinAt(...)` is invoked for the partition's bubbles
   (the rejoin).

This proves Gap 1 (escalation reaches the FaultHandler) and Gap 2 (the FAILED→recovery→rejoin chain
fires end-to-end against the assembled node), and does not depend on the live `main()`.

### Phase 1: Code Implementation

To be decomposed after research/gate (`/conexus:rdr-research` → `/conexus:rdr-gate` → `/conexus:rdr-accept`).

## Revision History

- 2026-06-09: created (draft) — scoped from `Luciferase-s23eu` (RDR-017 acknowledged boundary).
- 2026-06-09: **research pass 1** (Source Search; T2 `Luciferase_rdr/021-research-1`). **CA#1 VERIFIED**
  (`DefaultFaultHandler` 645L / `SimpleFaultHandler` 529L real machines; `PartitionTopology`/`Recovery`
  real; `RecoveryIntegration.processPartitionRecovery → vonManager.joinAt` is the real rejoin — the
  `yogvu` hollow-stub concern is **refuted** for this path). **CA#2 VERIFIED**
  (`Manager.addEventListener(Consumer<Event>)` subscription seam; `getBubble`/`joinAt` rejoin). **CA#3
  PARTIAL/REFINED** — subscription start/stop maps cleanly onto a lifecycle participant, but
  `NodeBootstrap.assemble` constructs no fault subsystem today, so the scope is **construct + wire the
  fault subsystem**, not just wire `RecoveryIntegration`; live activation gated on the `main()`
  skeleton (RDR-017 P0). Approach + audit refined accordingly. Next: `/conexus:rdr-gate` (which should
  scrutinize the FaultHandler-variant + recovery-strategy choices and the resolver-wiring scope split).
- 2026-06-09: **gate BLOCKED** (substantive-critic; 1 Critical, 3 Significant). Critical: Technical
  Design + Decision Rationale were placeholders. S1: `DefaultFaultHandler` SUSPECTED→FAILED needs a
  periodic `checkTimeouts()` scheduler the bootstrap lacks (not a free choice vs `SimpleFaultHandler`).
  S2: `FaultAwarePartitionRegistry` is a decorator, not an assembler. S3: resolver-scope must be
  recorded in the RDR. Cross-RDR consistency (RDR-017/020) passed.
- 2026-06-09: **research pass 2 + remediation** (Source Search; T2 `Luciferase_rdr/021-research-2`).
  Verified the recovery model is **VON-event-driven**: `Leave`→`reportSyncFailure`,
  `Join`/`GhostSync`→`markHealthy`→FAILED→HEALTHY→`onPartitionRecovered`→`vonManager.joinAt`;
  `initiateRecovery`/`PartitionRecovery` (Phase-4.2 TODO) is **bypassed**. Locked all decisions:
  `SimpleFaultHandler` (no scheduler; two-leaf FAILED threshold), `InMemoryPartitionTopology`, no
  recovery strategy, hand-wire (not `FaultAwarePartitionRegistry`), resolver-wiring → sibling RDR.
  Filled §Technical Design (verified ctor signatures, lifecycle placement + shutdown ordering, the
  registration seam, the unregistered-bubble silent-no-op error contract) and §Decision Rationale;
  corrected the audit row and §Failure Modes; rewrote the MVV to the two-leaves-then-join sequence.
  Re-gate next.
- 2026-06-09: **re-gate PASSED** (substantive-critic; 0 Critical). All four first-gate findings verified
  closed (Technical Design/Decision Rationale filled; SimpleFaultHandler locked with the
  scheduler/VON-event-driven rationale; FaultAwarePartitionRegistry decorator correction; resolver
  scope → sibling RDR). One follow-up Significant (stale "Recovery strategy" audit row contradicting the
  no-strategy decision) fixed; MVV step-2 clarified to the registered bubbleId. Ready for
  `/conexus:rdr-accept`.
