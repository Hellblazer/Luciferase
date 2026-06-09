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

To be completed in `/conexus:rdr-research`. Initial source reading confirms `RecoveryIntegration` is a
complete, unit-tested component whose production wiring is absent from `NodeBootstrap.assemble`.

#### Dependency Source Verification

| Dependency | Source Searched? | Key Findings |
| --- | --- | --- |
| `RecoveryIntegration` (simulation) | Partial | Component complete; `onNeighborLeave → faultHandler.reportSyncFailure` is the escalation seam; not constructed in `NodeBootstrap`. |
| `FaultHandler` / `PartitionTopology` (lucien balancing/fault) | No | Status model, recovery-callback contract, and thread-safety to verify (`subscribeToChanges`, `reportSyncFailure`, partition FAILED→recovered transitions). |
| VON `Manager` event stream | No | How `RecoveryIntegration` subscribes to `Event.Leave` (push vs poll), and the lifecycle ordering vs `NodeBootstrap`. |

### Key Discoveries

- **Documented** — `RecoveryIntegration.onNeighborLeave` is the sole production escalation path; absent
  from the bootstrap graph.
- **Assumed** — the `FaultHandler`/`PartitionTopology` partition-status machine is production-ready and
  only the wiring is missing (needs source verification — it may itself be a stub, cf. the deep-review
  "hollow balancing/fault recovery stubs" theme, `Luciferase-yogvu`).

### Critical Assumptions

- [ ] `FaultHandler`/`PartitionTopology` implement a real (non-stub) partition-status machine with a
  working FAILED→recovered transition and bubble-rejoin callback. — **Status**: Unverified —
  **Method**: Source Search
- [ ] VON `Manager` exposes a neighbor-leave event stream that `RecoveryIntegration` can subscribe to
  in the assembled node without changing VON internals. — **Status**: Unverified — **Method**: Source Search
- [ ] The recovery subscription can be created/torn down at a well-defined point in the RDR-017
  lifecycle graph without reordering the existing Layer 0/bubble dependencies. — **Status**: Unverified
  — **Method**: Source Search

**Method definitions**: Source Search = API verified against dependency source. Spike = behavior
verified by running code. Docs Only = insufficient for load-bearing assumptions.

## Proposed Solution

### Approach

Preliminary (to be refined post-research): construct `RecoveryIntegration` inside
`NodeBootstrap.assemble` once the VON `Manager`, `PartitionTopology`, and `FaultHandler` are available;
register it as a lifecycle participant so its VON-event subscription starts after the manager is up and
is torn down before the manager stops; and drive the bubble→partition registration from the existing
bubble-creation path. Decide whether the RDR-020 production resolver wiring
(`FirefliesBubbleOwnershipResolver`) is in-scope here or a sibling RDR.

### Technical Design

To be completed in research/design. Will specify: the wiring point and lifecycle-layer placement in
`NodeBootstrap.assemble`; the subscription start/stop contract; the bubble→partition registration
seam; and error contracts (what happens when a neighbor leaves for an unregistered bubble — today a
silent no-op).

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| Recovery wiring | `RecoveryIntegration` (simulation/von) | Reuse — wire the existing component, do not reimplement. |
| Partition fault detection | `FaultHandler`/`PartitionTopology` (lucien balancing/fault) | Reuse if non-stub (verify); else this RDR's scope expands or splits. |
| Lifecycle composition | `NodeBootstrap.assemble` | Extend — add the recovery participant to the existing graph. |

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

- 2026-06-09: created (draft) — scoped from `Luciferase-s23eu` (RDR-017 acknowledged boundary). Next:
  `/conexus:rdr-research` to verify the Critical Assumptions (esp. `FaultHandler`/`PartitionTopology`
  non-stub status and the VON event-subscription seam), then `/conexus:rdr-gate`.
