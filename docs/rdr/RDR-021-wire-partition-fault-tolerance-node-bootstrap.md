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
- [~] The recovery subscription can be created/torn down at a well-defined point in the RDR-017
  lifecycle graph without reordering the existing Layer 0/bubble dependencies. — **Status**:
  **PARTIAL / REFINED** (Source Search) — the subscription start (constructor) / stop (`close()`) maps
  cleanly onto a lifecycle participant stopped BEFORE the VON manager. BUT the bootstrap constructs no
  fault subsystem today, so the real prerequisite is constructing `FaultHandler` + `PartitionTopology`
  + a `PartitionRecovery` strategy in the node (the scope refinement above), and production activation
  is gated on the live `main()` skeleton (RDR-017 P0).

**Method definitions**: Source Search = API verified against dependency source. Spike = behavior
verified by running code. Docs Only = insufficient for load-bearing assumptions.

## Proposed Solution

### Approach

Refined post-research: the escalation/recovery components are all real, so the work is **composition**,
not building a state machine. In (or alongside) `NodeBootstrap.assemble`:
1. Construct the fault subsystem — a `FaultHandler` (decide `DefaultFaultHandler` barrier-timeout vs
   `SimpleFaultHandler` local), a `PartitionTopology` (`InMemoryPartitionTopology` for the
   single-process node), and a per-partition `PartitionRecovery` strategy (Cascading/Barrier/NoOp) —
   or reuse `FaultAwarePartitionRegistry` if it already assembles these coherently (audit below).
2. Construct `RecoveryIntegration(vonManager, topology, faultHandler)` — its constructor subscribes to
   VON events (`addEventListener`) and fault changes (`subscribeToChanges`).
3. Register it as a lifecycle participant whose subscription starts after the VON manager is up and
   whose `close()` runs **before** the manager stops (mirrors the RDR-017 shutdown-ordering contract for
   the migrator-before-WAL hazard).
4. Drive `registerBubble(bubbleId, partitionId)` from the existing bubble-creation path so neighbor
   departures map to a partition.

Open scope decisions for the gate: (a) `DefaultFaultHandler` vs `SimpleFaultHandler` and the default
recovery strategy; (b) whether RDR-020's production resolver wiring (`FirefliesBubbleOwnershipResolver`
into the bootstrap, the other half of `s23eu`) belongs here or in a sibling RDR; (c) production
activation is gated on the live `main()` skeleton (RDR-017 P0) — the MVV runs against a test-assembled
node, but live activation is a named dependency.

### Technical Design

To be completed in research/design. Will specify: the wiring point and lifecycle-layer placement in
`NodeBootstrap.assemble`; the subscription start/stop contract; the bubble→partition registration
seam; and error contracts (what happens when a neighbor leaves for an unregistered bubble — today a
silent no-op).

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| Recovery wiring | `RecoveryIntegration` (simulation/von) | Reuse — verified complete; wire, do not reimplement. |
| Partition fault detection | `DefaultFaultHandler` / `SimpleFaultHandler` (lucien balancing/fault) | Reuse — both verified real; RDR chooses which. |
| Partition topology | `InMemoryPartitionTopology` (lucien balancing/fault) | Reuse for single-process; distributed topology is a later dependency. |
| Recovery strategy | `CascadingRecoveryImpl` / `BarrierRecoveryImpl` / `NoOpRecoveryImpl` | Reuse — RDR chooses the per-partition default. |
| Fault-subsystem assembly | `FaultAwarePartitionRegistry` (lucien) | Audit — reuse as the assembly if it composes FaultHandler+topology coherently; else hand-wire in bootstrap. |
| Lifecycle composition | `NodeBootstrap.assemble` | Extend — construct the fault subsystem + add the recovery participant to the existing graph. |

### Decision Rationale

To be completed after research (notably the stub-vs-real verification of `FaultHandler`).

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

- **Risk**: `FaultHandler`/`PartitionTopology` is itself a hollow stub (deep-review theme `yogvu`), so
  wiring escalates into a no-op. **Mitigation**: verify non-stub status as a Critical Assumption before
  committing to the wiring scope; split the RDR if the fault machine needs productionizing first.
- **Risk**: lifecycle-ordering error leaks/double-fires the subscription. **Mitigation**: model the
  subscription as an explicit lifecycle participant with start-after/stop-before ordering, covered by a test.

### Failure Modes

Today (unwired): a partition failure is **silently absorbed** — no signal, no recovery; diagnosed only
by noticing bubbles never rejoin. After wiring: a misconfigured escalation should **fail loud** (e.g.
neighbor-leave for an unregistered bubble logged, not silently dropped).

## Implementation Plan

### Prerequisites

- [ ] All Critical Assumptions verified (esp. `FaultHandler` non-stub status).
- [ ] Decision on whether RDR-020 resolver wiring is in-scope here or a sibling RDR.

### Minimum Viable Validation

An integration test over the assembled node: inject a VON `Event.Leave` for a registered bubble and
assert the chain `reportSyncFailure → partition FAILED → onPartitionRecovered → bubble rejoin` fires
end-to-end (real `RecoveryIntegration` + real `FaultHandler`, not mocked at the escalation boundary).

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
