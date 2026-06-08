---
title: "WAL Checkpoint/Recovery Durability — Bound Replay Safely (Snapshot or Compaction)"
id: RDR-019
type: Bug Fix
status: accepted
priority: high
author: self
reviewed-by: self
created: 2026-06-07
accepted_date: 2026-06-07
related_issues: [Luciferase-n6jrh.3, Luciferase-n6jrh]
---

# RDR-019: WAL Checkpoint/Recovery Durability — Bound Replay Safely (Snapshot or Compaction)

> Revise during planning; lock at implementation.

## Problem Statement

The simulation WAL recovery (`EventRecovery.recover`) bounds replay at the last checkpoint
(`readEventsSinceResult(checkpoint.sequenceNumber())`, `EventRecovery.java:106-123`), but a checkpoint is a
**bare sequence marker with no durable snapshot** of the applied state it claims is captured. The applied
state — the `EntityMigrationStateMachine` (FSM) migration-state map — lives only in memory and is lost on
crash. So any event whose only application was into the now-lost FSM is silently skipped on the next
recovery. This is an RDR-004-class silent data loss (`Luciferase-n6jrh.3`, CRITICAL).

It is **pre-existing** (the post-checkpoint replay bound was introduced by `0frcy.37`) and **not reachable in
production yet** (`NodeBootstrap.main()` throws). It is a hard precondition for wiring a live node that relies
on crash recovery. RDR-017 surfaced it; this RDR decides the durable fix.

### Enumerated gaps to close

#### Gap 1: Recovery silently drops checkpointed-but-not-snapshotted events

`recover()` replays only `seq > checkpoint.sequenceNumber`. The 5s periodic checkpoint scheduler
(`PersistenceManager.startCheckpointScheduler`, `:469-476`) advances that boundary mid-run with no snapshot.
A node that recovers, runs, takes a periodic checkpoint, then crashes loses every pre-checkpoint
`MIGRATING_OUT`/`DEPARTED` reconstruction on the next restart — no error, no WARN. Reproduced: log a
departure → `checkpoint()` → close → recover into a fresh FSM yields `getState == null` (expected
`MIGRATING_OUT`).

#### Gap 2: No durable snapshot, and recover() never re-checkpoints

The checkpoint contract assumed by `readEventsSince` ("state ≤ seq is durable, skip it") is never
established: there is no FSM snapshot, and `recover()` does not re-checkpoint after applying the tail. The
only checkpoint that is safe today is `closeClean()`'s, and only because it is immediately followed by
`truncate()` (nothing precedes the boundary).

#### Gap 3: Bound WAL growth without reintroducing the data loss

The naive correctness fix (remove mid-run checkpoints, replay the full retained log) eliminates Gap 1 but
lets the WAL grow unbounded between clean shutdowns and makes crash recovery O(total events). The fix must
bound replay/WAL **and** stay correct.

#### Gap 4: `eventCounter` resets on restart, diverging from the restored WAL sequence

`PersistenceManager.eventCounter` is `new AtomicLong(0)` and never restored, while
`WriteAheadLog.sequenceCounter` is restored from `max(log, checkpoint)`. `checkpoint()` records
`eventCounter`, so after any restart the recorded checkpoint sequence is wrong relative to the WAL sequence
space `readEventsSince` filters on.

## Context

### Background

Discovered in RDR-017 P3 stacked review (code-review C1). The WAL persists `ENTITY_DEPARTURE`,
`MIGRATION_COMMIT`, `VIEW_SYNC_ACK`, `DEFERRED_UPDATE`, plus four consensus event types. Only the migration
FSM is reconstructed on recovery (`MigrationRecoveryStateSink`: `ENTITY_DEPARTURE → MIGRATING_OUT`,
`MIGRATION_COMMIT → DEPARTED`). Consensus-event recovery is intentionally out of scope (RDR-017 §5a). DSOC,
deferred updates, and view-sync acks are logged but no sink reconstructs them today.

### Technical Environment

`simulation` module. `PersistenceManager` (`persistence/`), `WriteAheadLog`, `EventRecovery`,
`MigrationRecoveryStateSink`, `EntityMigrationStateMachine` (`causality/`). Recovery wired through
`PersistenceManagerAdapter.doStart()` → `recover()` (RDR-017 P1). Clean shutdown compacts via
`closeClean()` → checkpoint + `WriteAheadLog.truncate()` (RDR-017 P3).

## Research Findings

### Investigation

- **Reproduced the data loss** deterministically (characterization test, not committed): one
  `logEntityDeparture` + `checkpoint()` + close, then a fresh-FSM `recover()` returns `null` instead of
  `MIGRATING_OUT`.
- **Checkpoint callers (production):** only `startCheckpointScheduler` (periodic, the hazard) and
  `closeClean()` (clean shutdown — safe, truncates). `CausalRollback.checkpoint()` and
  `EventRecovery.getLastCheckpoint()` are unrelated.
- **Recovery boundary:** `EventRecovery.recover` uses `getLastCheckpoint` → `readEventsSinceResult(seq)` when
  a checkpoint exists, else `readAllEventsResult()`. No checkpoint ⇒ full replay (correct).
- **The only recovery-relevant state is the migration FSM.** Whether *terminal* (`DEPARTED`) state must be
  reconstructed on restart is the load-bearing open question for the compaction approach (Gap 3) — a
  `DEPARTED` entity has left this node; if nothing on restart depends on knowing it was `DEPARTED`, committed
  migration event-pairs are prunable.

### Key Discoveries

- **Verified** — the silent skip is real and triggered by a checkpoint preceding a crash (reproduction:
  log departure → `checkpoint()` → close → fresh-FSM `recover()` yields `null`, not `MIGRATING_OUT`).
- **Verified (source search, 2026-06-07)** — **`DEPARTED` reconstruction is NOT load-bearing.** No consumer
  reads back a recovered `DEPARTED` entry. `GhostStateListener.reconcileGhostState`
  (`GhostStateListener.java:207-214`) treats `fsm.getState(id) == null` (untracked) identically to any
  non-`GHOST` state, so a `DEPARTED` entity's *absence* from the recovered FSM is functionally equivalent to
  its presence. `recoverEntityState` mutates the map directly without firing listeners, so a recovered
  `DEPARTED` never drives a `DEPARTED→GHOST` transition. No idempotency guard depends on seeing a prior
  `DEPARTED`. → committed migration pairs may be pruned.
- **Verified (source search, 2026-06-07)** — replay is **idempotent** (`EventRecovery` `seenMigrations`
  dedup `:145-150` + `recoverEntityState` `put`), but **order-dependent**: `ENTITY_DEPARTURE` must precede
  `MIGRATION_COMMIT` for an entity (`recoverEntityState` overwrites; out-of-order `COMMIT,DEPARTURE` would
  wrongly leave `MIGRATING_OUT`). The append-only WAL write path guarantees this order. **Constraint for
  compaction:** prune the *whole* `DEPARTURE`+`COMMIT` pair for a committed migration — never a partial pair
  — and preserve the relative order of retained (in-flight) events.
- **Documented** — `MigrationRecoveryStateSink.java:83-98` is the sole reconstruction sink;
  `EntityMigrationStateMachine.recoverEntityState` bypasses the view-stability guard for pre-view recovery.

### Critical Assumptions

- [x] Crash recovery only needs to reconstruct **in-flight** (`MIGRATING_OUT`) migrations; terminal
  `DEPARTED` reconstruction is not load-bearing across a restart — **Status**: **Verified (scoped)** —
  **Method**: Source Search. Verified for the recovery/reconcile path (no consumer reads recovered
  `DEPARTED`; `GhostStateListener.reconcileGhostState:207-214` treats `null` == non-`GHOST`). **Scope caveat
  (gate S1):** the *forward live* path `GhostStateListener.onEntityStateTransition:164` accepts a
  `DEPARTED→GHOST` transition, and `EntityMigrationStateMachine.isValidTransition:687-689` has
  `DEPARTED→GHOST` but routes `null→GHOST` to `default → false`. After restart with committed migrations
  pruned, `getState(id)` is `null`, so a *subsequent* `DEPARTED→GHOST` ghost notification for a
  recently-departed, still-spatially-adjacent entity would be silently rejected — a ghost-tracking gap on the
  source node (not an ownership-invariant violation; the entity is owned at the target). **Phase 2 must
  resolve this**: either add `null→GHOST` as a valid transition (accept a ghost for an untracked, already-
  departed entity) or document it as an accepted gap with a tracked bead. Until resolved, the assumption holds
  for recovery correctness but the forward-path behavior is an explicit open item, not silently assumed away.
- [x] Replaying the retained migration log into a fresh FSM is idempotent; order-dependence
  (`DEPARTURE` before `COMMIT`) is guaranteed by the append-only write path and preserved by pair-wise
  compaction — **Status**: **Verified** — **Method**: Source Search (`EventRecovery.java:143-161,361-368`).

## Proposed Solution

### Approach

Two layers:

1. **Correctness floor (always):** a recovery checkpoint may bound replay **only** when the state it covers
   is durable independent of the in-memory FSM. Remove the unsafe mid-run periodic checkpoint as a
   replay-bounding operation. With no safe mid-run checkpoint, crash recovery replays the full retained log
   (correct, idempotent). Fix Gap 4 by sourcing the checkpoint sequence from the WAL global sequence.
2. **Bound WAL/replay — DECISION LOCKED: log compaction keyed on migration completion.** Both Critical
   Assumptions are **verified** (terminal `DEPARTED` is not load-bearing; replay is idempotent with
   write-path-guaranteed order), so compaction is the chosen design (not the snapshot fallback). A
   `MIGRATION_COMMIT` makes a migration terminal; compaction prunes its **complete**
   `ENTITY_DEPARTURE`+`COMMIT` pair (never a partial pair), retaining only in-flight (`MIGRATING_OUT`)
   departures and other not-yet-terminal events, preserving their relative order. Recovery replays the
   compacted (small) log in full — no boundary-skip, bounded WAL. Durable in-flight snapshot
   (Alternative 1) is retained only as the documented fallback had the assumption been false.

### Technical Design

- `EventRecovery.recover`: a checkpoint bounds replay only if backed by truncation (clean shutdown) or
  compaction metadata; otherwise full replay. Interface unchanged externally
  (`recover() → RecoveredState`).
- Compaction (primary): rewrite the retained log dropping completed migration pairs (the whole
  `DEPARTURE`+`COMMIT` pair, never a partial pair — Q2 order constraint), advancing a compaction watermark.
  Runs on a bounded trigger (size/age) and on `closeClean`. Crash-safe via write-new-then-rename.
  **Concurrent-write safety (gate S2):** the batch-flush scheduler keeps writing the *active* segment during
  compaction, so compaction MUST NOT cover the active segment — otherwise a concurrently-written in-flight
  `ENTITY_DEPARTURE` would be lost under the rename (the exact RDR-004 class this RDR closes). Design:
  **seal the active segment** (roll to a new one) before the compaction scan, and compact only *sealed*
  segments older than the watermark; the live segment is never rewritten. (Alternatives — a write-exclusion
  lock spanning scan→rename, or per-segment compaction — are inferior for an append-only log.)
- **DEFERRED_UPDATE / VIEW_SYNC_ACK / consensus events** have no reconstruction consumer today
  (`MigrationRecoveryStateSink.onDeferredUpdate` is a stub; consensus recovery is out of scope per
  RDR-017 §5a). Compaction retains them conservatively (does not assume them prunable) unless a follow-up
  establishes they are safe to drop. Entity-position recovery via `DEFERRED_UPDATE` is **out of scope**
  (gate O3) — pre-existing gap, separate work if ever needed.
- Remove `startCheckpointScheduler` / `CHECKPOINT_INTERVAL_MS` as a recovery-bounding scheduler. Keep the
  batch-flush scheduler (durability).
- `checkpoint()` sequence ← `WriteAheadLog.getSequence()` (new getter), not `eventCounter` (Gap 4).

```text
// Illustrative — verify during implementation
// recover(nodeId): if (compactionWatermark or truncated) replay tail else replay all → into RecoveryStateSink
// compact(): retain events where migrationKey not in committedSet; atomic rename; bump watermark
```

### Existing Infrastructure Audit

| Proposed Component | Existing Module | Decision |
| --- | --- | --- |
| Compaction / safe replay bound | `WriteAheadLog.truncate()` (clean-shutdown only) | Extend: generalize to mid-run compaction keyed on terminal migrations |
| Checkpoint sequence source | `PersistenceManager.eventCounter` | Replace with `WriteAheadLog.getSequence()` |
| Periodic checkpoint scheduler | `PersistenceManager.startCheckpointScheduler` | Remove (unsafe as a replay bound) |

### Decision Rationale

Compaction bounds the WAL without a new serialization surface and matches the domain: the only
recovery-relevant state is in-flight migrations, which a `MIGRATION_COMMIT` retires. It keeps recovery a
pure log replay (no snapshot/replay consistency seam). The durable-snapshot alternative is more general but
adds FSM serialization, snapshot atomicity, and a snapshot-source abstraction `PersistenceManager` lacks.

## Alternatives Considered

### Alternative 1: Durable in-flight FSM snapshot at checkpoint

**Description**: Serialize the in-flight migration-state map at each checkpoint; `recover()` loads the
snapshot then replays the tail.

**Pros**: General (works even if terminal state must survive); fast recovery; bounded WAL.

**Cons**: New FSM serialization + snapshot atomicity; a snapshot-source abstraction PM does not have;
snapshot/tail consistency seam.

**Reason for rejection (provisional)**: Heavier than compaction for the same outcome, *if* the
terminal-state assumption holds. Promoted to primary if that assumption is false.

### Briefly Rejected

- **Full-replay only, no bound (correctness floor alone)**: fixes the data loss but leaves Gap 3 (unbounded
  WAL, O(total) recovery) — acceptable only as the floor beneath compaction, not the end state.
- **Checkpoint at WAL global sequence without removing mid-run checkpoints**: still skips un-snapshotted
  events; changes which events are dropped and can drop the whole prefix. Rejected (does not fix Gap 1).

## Trade-offs

### Consequences

- Eliminates the silent data loss (positive, the point).
- Recovery becomes a full replay of the *compacted* log — bounded by concurrent in-flight migrations
  (positive).
- Compaction adds log-rewrite machinery with crash-safety requirements (negative — must be carefully tested).

### Risks and Mitigations

- **Risk**: compaction drops an event still needed for recovery. **Mitigation**: prune only migration pairs
  with a durable `MIGRATION_COMMIT`; verify the terminal-state assumption first; crash-safe write-then-rename;
  regression that an in-flight departure across a compaction survives recovery.
- **Risk**: removing the periodic checkpoint regresses recovery latency on a long-running node.
  **Mitigation**: compaction caps retained-log size; measure replay on a representative log.

### Failure Modes

- **Visible**: corrupt WAL still aborts startup (RDR-017 P1 fail-loud, unchanged).
- **Silently** (the class this RDR closes): an event needed for recovery is skipped/pruned. Closed by:
  full replay of retained log + compaction that only prunes durably-terminal migrations + a multi-session
  regression.

## Implementation Plan

### Prerequisites

- [ ] Critical Assumptions verified (esp. terminal-`DEPARTED` necessity).

### Minimum Viable Validation

Two proofs (gate O2 — Phase 1 and Phase 2 prove different things):

- **Phase 1 MVV:** the reproduced scenario — log an in-flight departure, take a checkpoint, crash, recover
  into a fresh FSM → the departure reconstructs as `MIGRATING_OUT` (today: `null`). Plus: a committed
  migration replays to `DEPARTED` (harmless, not pruned in Phase 1). Must fail on current code, pass after
  Phase 1.
- **Phase 2 MVV:** multi-session through the assembled node — session 1 logs an in-flight departure + a
  completed (committed) migration, crash; session 2 recovers (in-flight → `MIGRATING_OUT`), runs, compaction
  fires (committed pair pruned, active segment sealed first), crash; session 3 recovers and **all in-flight
  migrations are reconstructed, none silently dropped, and the WAL is bounded**.

### Phase 1: Correctness floor

Remove the periodic checkpoint as a replay bound (its removal makes the only writable checkpoint
`closeClean`'s, which is structurally always truncation-backed — gate O1: no metadata flag needed; do NOT
re-add a periodic checkpoint without re-establishing the snapshot/truncation invariant); recovery
full-replays the retained log when no truncation applies; `checkpoint()` uses the WAL global sequence
(`WriteAheadLog.getSequence()`, Gap 4). Regression: the reproduced data-loss scenario recovers correctly.

### Phase 2: Bounded WAL (compaction)

Implement crash-safe, **active-segment-sealed** compaction keyed on terminal migrations + watermark (gate
S2); wire a bounded trigger and `closeClean`. Resolve the `null→GHOST` forward-path item (gate S1) — add the
valid transition or document + track the accepted gap. Regression: in-flight departure survives a compaction
(including one concurrent with live writes); WAL stays bounded under churn.

### Day 2 Operations

| Resource | List | Info | Delete | Verify | Backup |
| --- | --- | --- | --- | --- | --- |
| Per-node WAL dir `.luciferase/wal/<nodeId>/` | N/A (single dir) | N/A | closeClean truncate / compaction | recover() integrity gate | N/A |

## Test Plan

- **Scenario**: log departure → checkpoint → crash → recover — **Verify**: `MIGRATING_OUT` reconstructed
  (today: `null`).
- **Scenario**: recover → run → (compaction) → crash → recover — **Verify**: all in-flight migrations
  survive; committed ones handled per the verified terminal-state contract.
- **Scenario**: in-flight departure present when compaction runs — **Verify**: it is retained and recovered.
- **Scenario**: corrupt WAL — **Verify**: startup still aborts (fail-loud unchanged).
- **Scenario**: WAL size under sustained migration churn — **Verify**: bounded (compaction effective).

## Validation

### Testing Strategy

TDD: the reproduction becomes the Phase 1 regression; the multi-session MVV is the Phase 2 gate. Deterministic
(TestClock; no reliance on the 5s wall-clock scheduler). Stacked review (code-review-expert +
substantive-critic) at each phase boundary, with the critic specifically pressure-testing that compaction
cannot prune an event needed for recovery.

## Finalization Gate

### Contradiction Check

No contradictions between Research Findings and the Proposed Solution. Cross-RDR (gate Layer 3, P7):
consistent with RDR-017 §Operational notes (gate O1) — RDR-019 removes mid-run checkpoints, leaving
`closeClean`'s checkpoint as the only one, which is structurally truncation-backed, so the "checkpoint bounds
replay only when state is durable" invariant holds across both RDRs. Orthogonal to RDR-016 R2 (the
`BubbleMigrator` fail-loud WAL bracket) — unchanged. Corrupt-WAL fail-loud abort (RDR-017 P1) is preserved.

### Assumption Verification

Both Critical Assumptions verified by source search (`019-research-1`). Assumption (a) is **scoped**: verified
for the recovery/reconcile path; the forward `DEPARTED→GHOST` live path is an explicit Phase 2 open item
(gate S1), not an unverified load-bearing assumption — Phase 2 resolves it before compaction ships.

#### API Verification

| API Call | Library | Verification |
| --- | --- | --- |
| `EntityMigrationStateMachine.recoverEntityState` / `getState` / `isValidTransition` | in-repo | Source Search |
| `GhostStateListener.reconcileGhostState` / `onEntityStateTransition` | in-repo | Source Search |
| `EventRecovery.replayEvents` / `extractMigrationKey` / `readEventsSinceResult` | in-repo | Source Search |
| `WriteAheadLog.truncate` / `getSequence` (new) | in-repo | Source Search |

### Scope Verification

Phase 1 MVV (reproduced no-loss recovery) and Phase 2 MVV (multi-session bounded no-loss) are both in scope,
not deferred. Out of scope, explicit: consensus-event recovery (RDR-017 §5a), entity-position recovery via
`DEFERRED_UPDATE` (gate O3, pre-existing), Subsystem B/C.

### Cross-Cutting Concerns

- **Versioning**: WAL/compaction on-disk change — not in prod (no live node; `main()` throws). Phase 2 stamps
  a format note in the compaction watermark metadata.
- **Memory management**: compaction streams sealed segments (no full in-memory rewrite).
- **Incremental adoption**: Phase 1 (correctness floor) is independently shippable and fully closes the data
  loss; Phase 2 (compaction) restores bounded WAL/fast recovery.
- Others: N/A.

### Proportionality

Right-sized: a CRITICAL data-loss correctness fix (Phase 1) plus a bounded-WAL design (Phase 2), two phases.

## References

- `Luciferase-n6jrh.3` (bead), RDR-017 §Operational notes (gate O1), T2 `Luciferase/skaui-p3-stacked-review`.
- `EventRecovery.java:106-123` (replay bound), `PersistenceManager.java:274-283,469-476` (checkpoint +
  scheduler), `WriteAheadLog.java` (truncate, restoreSequenceCounter), `MigrationRecoveryStateSink.java:83-98`.
- `0frcy.37` (introduced post-checkpoint replay).

## Revision History

- 2026-06-07: created (draft) from `Luciferase-n6jrh.3` after brainstorming-gate selected the RDR path
  (B/C bounded-WAL design).
- 2026-06-07: research recorded (`019-research-1`) — both Critical Assumptions verified; design locked to
  compaction. **Gate PASSED** (Layer 1 structural ✓, Layer 2 assumptions ✓, Layer 3 substantive-critic: 0
  Critical, 2 Significant, 3 Observations). The 2 Significant + 3 Observations folded into the design:
  - **S1** — `DEPARTED→GHOST` forward-path coverage hole → assumption (a) scoped; Phase 2 resolves
    `null→GHOST` (add transition or document+track accepted gap).
  - **S2** — compaction concurrent-write race with the batch-flush scheduler → seal the active segment and
    compact only sealed segments older than the watermark.
  - **O1** — Phase 1 "checkpoint only via truncation" is structural (no metadata flag); documented.
  - **O2** — MVV split into distinct Phase 1 and Phase 2 proofs.
  - **O3** — `DEFERRED_UPDATE` entity-position recovery explicitly out of scope.
