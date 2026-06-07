---
id: RDR-017
title: Production Node Bootstrap — Compose the Distributed Simulation Node (Lifecycle, WAL, Recovery, Migration)
status: accepted
date: 2026-06-06
accepted_date: 2026-06-07
reviewed-by: self
supersedes: []
related: [RDR-016, RDR-004]
beads: [Luciferase-hwqjk, Luciferase-n6jrh, Luciferase-s23eu]
---

# RDR-017: Production Node Bootstrap — Compose the Distributed Simulation Node

## Status

Draft (2026-06-06; decisions locked 2026-06-07). Spun out of RDR-016 after research found that the simulation
module has **no production node assembly at all** — multiple complete subsystems (lifecycle, WAL persistence,
crash recovery, cross-process migration) exist as library components but nothing composes them into a running
node. **Q0, durability authority, and the scheduler hazard are now decided** (see `## Decision`); research
recorded + verified (`017-research-1`). Ready for `/conexus:rdr-gate`.

## Context / Problem Statement

There is no `main()` / bootstrap in `simulation/src/main`. `von/Manager` (the orchestrator) is never
constructed in production; it builds an empty `LifecycleCoordinator` and registers only
`EnhancedBubbleAdapter`s. Three independent durability/recovery subsystems are each dead in production
(constructed only in tests/javadoc):

1. `PersistenceManager` / `WriteAheadLog` (+ `MigrationRecoveryStateSink`, `EventRecovery`).
2. `MigrationLogPersistence` / `CrossProcessMigration` (RDR-`0frcy.30`).
3. `RecoveryIntegration` (`von/RecoveryIntegration`).

The lifecycle dependency graph is also broken for the persistence case (RDR-016 Q4): bubbles register at
Layer 0 and would start before persistence; `PersistenceManagerAdapter.doStart()` is a no-op; and
`PersistenceManagerAdapter.dependencies()` references an unregistered `SocketConnectionManager` that would
crash `LifecycleCoordinator.computeLayers()`.

## Decision (locked 2026-06-07)

- **Q0 — node `main` lives in THIS repo.** `simulation` owns the production node assembly. Rationale:
  `von/Manager` + `LifecycleCoordinator` + the adapter set already form a partial in-repo assembly, and the
  research treats the missing `main()` as a defect to fix, not a deliberate library boundary. The embedder
  alternative was rejected — it would leave production assembly unsolved here.
- **Durability — Subsystem A (`PersistenceManager`/`WriteAheadLog`) is authoritative.** It is the only
  subsystem that recovers actual entity migration-FSM state (`MigrationRecoveryStateSink` ↔
  `EntityMigrationStateMachine.recoverEntityState()`), has a ready injection seam
  (`BubbleMigrator.setPersistenceManager()`), a lifecycle adapter, and the RDR-016 R2 fail-loud bracket. The
  "three uncoordinated WALs" framing was corrected by research:
  - **Subsystem B** (`MigrationLogPersistence`/`CrossProcessMigration`) records 2PC *transaction*-level state
    at a different layer (different dir/key); `walPersistence` is already a nullable ctor arg. **Kept as
    optional defense-in-depth, off by default** — NOT an alternative authoritative WAL. **Consequence (explicit,
    gate S3):** off-by-default means 2PC transaction crash recovery (PREPARE-without-COMMIT rollback via
    `loadIncomplete()`) requires explicit opt-in of `MigrationLogPersistence` in the assembly; with B off, a
    crash between `recordPrepare()` and `recordCommit()` leaves an orphaned PREPARE with **no automatic
    rollback and no operator signal**. The durability promise of the shipped node therefore covers entity
    migration-FSM state (Subsystem A), not 2PC transaction recovery.
  - **Subsystem C** (`von/RecoveryIntegration`) has **no WAL / no disk I/O** — it is a live VON-topology
    repair bridge. **Orthogonal to durability** and out of scope for P0–P3. **Acknowledged operational gap
    (gate S1, tracked — NOT silent reduction):** `RecoveryIntegration.onNeighborLeave()` →
    `faultHandler.reportSyncFailure()` is the only production path escalating VON neighbor failures to the
    partition fault detector. Unwired, the shipped node has WAL durability but **cannot self-heal under network
    partition** (neighbor failures silently absorbed; no `FAILED` transition; no bubble-rejoin). Tracked in
    **`Luciferase-s23eu`**; productionizing C is a separate decision gated on whether `FaultHandler`/partition
    topology ship.
- **Scheduler-start hazard — deferred to `doStart()`.** `PersistenceManager` currently starts the batch-flush
  + checkpoint schedulers **in its constructor** (`PersistenceManager.java:121-126`), before `recover()` runs —
  a checkpoint could overwrite an unrecovered log. Decision: move scheduler start out of the ctor into
  `PersistenceManagerAdapter.doStart()`, sequenced **after** `recover()`. Eliminates the window cleanly.
- **nodeId source of truth — LOCKED to `FirefliesMemberLookup.digestToUuid(Member.getId())`** (gate C1). This
  production method already exists (`FirefliesMemberLookup.java:168`) and is the **canonical** member→UUID
  derivation across the codebase (deterministic first-16-bytes truncation of `Digest.getBytes()`; used at
  `:94`, `:191`). The rejected alternative `UUID.nameUUIDFromBytes(digest.getBytes())` produces a *different*
  value from the same `Digest` — using it would (a) diverge the WAL directory identity from every other
  member-identity site and (b) risk a node deriving a different `nodeId` on restart, silently finding no WAL
  and starting fresh with no error. Determinism of nodeId across restarts is a **correctness** requirement for
  WAL recovery, not a preference. (The earlier "no bridge exists" claim in research was incorrect.)
  *Note:* `digestToUuid` takes the first 16 bytes of the `Digest` (truncating a ≥32-byte hash to a 128-bit
  UUID) — collision space equivalent to UUIDv4, acceptable for cluster-member identity; the `Member.getId()`
  KERI SAID is stable across restarts from key material.
- **Bootstrap (composed here):** derive `nodeId = digestToUuid(member.getId())`, derive
  `.luciferase/wal/<nodeId>/`, register `SocketConnectionManagerAdapter` (Layer 0) **and**
  `PersistenceManagerAdapter` (**Layer 0** — see lifecycle decision below), then `EnhancedBubbleAdapter`s
  (Layer 1, dep `PersistenceManager`), inject the WAL into `BubbleMigrator`, wire `MigrationRecoveryStateSink`
  to the node's `EntityMigrationStateMachine`.
- **Lifecycle layering — `PersistenceManager` is Layer 0, NOT behind `SocketConnectionManager`** (gate C2). The
  current `PersistenceManagerAdapter.dependencies() = ["SocketConnectionManager"]` (`:77`) is **spurious**:
  `PersistenceManager` imports only `java.io`/`java.nio.file`/`java.util.concurrent`/`Clock` — zero network
  surface — and runs flush/checkpoint/recover entirely from local WAL files. The current crash in
  `computeLayers()` is fixed not by registering SCM ahead of PM, but by **changing
  `PersistenceManagerAdapter.dependencies()` to `List.of()`** so PM sits at Layer 0 alongside SCM (the
  coordinator parallelizes same-layer components). Bubbles (Layer 1) depend on `PersistenceManager`; any bubble
  network dependency on SCM is declared on the bubble adapter, not laundered through PM.

## Approach (phased)

1. **Phase 0 — bootstrap skeleton + lifecycle ordering fix.** Author `main()`/node bootstrap in
   `simulation/src/main`; register `SocketConnectionManagerAdapter` (L0); **change
   `PersistenceManagerAdapter.dependencies()` to `List.of()`** so PM is L0 (gate C2); convert
   `Manager.createBubble()` to the 3-arg `EnhancedBubbleAdapter(bubble, rtc, List.of("PersistenceManager"))`
   (L1); resolve `nodeId = FirefliesMemberLookup.digestToUuid(member.getId())` (gate C1). AC: coordinator
   computes layers (SCM,PM at L0; Bubble at L1) without `LifecycleException`.
2. **Phase 1 — persistence wiring + recover() fail-loud + scheduler relocation.** Register
   `PersistenceManagerAdapter`; move scheduler start from ctor to `doStart()` after `recover()`; `recover()`
   rethrows `IOException` (startup aborts on corrupt WAL). AC: corrupt WAL aborts startup; clean WAL replays;
   **regression that the checkpoint scheduler does not run before `recover()` completes** (proves the
   relocation actually closed the hazard, not just moved code).
3. **Phase 2 — migration WAL bracket live + durability round-trip.** `bubbleMigrator.setPersistenceManager(pm)`
   post-start; verify the RDR-016 R2 `ENTITY_DEPARTURE`/`MIGRATION_COMMIT` bracket fires in the assembled node.
   **AC (gate S2 — kill-semantics pinned):** the automated AC is an *assembled-node* close/reopen round-trip on
   the same WAL directory — migrate, close the node's `PersistenceManager`, construct a fresh one on the same
   `digestToUuid`-derived dir, assert FSM recovers to `MIGRATING_OUT`/`DEPARTED` **and that the reopened node
   resolves the same WAL directory** (WAL-identity pinning, gate C1). This is explicitly distinct from — and
   strengthens — the pre-existing in-process `MigrationRecoveryStateSinkTest.endToEndViaManagerReconstructsFsmState()`
   by going through the bootstrap + the relocated scheduler. A true process-level (fork/exec) restart is a
   **manual/subprocess QA step**, recorded as such, not an automated gate.
4. **Phase 3 — residual cleanup.** Add explicit no-op recovery cases for the four consensus event types
   (`EventRecovery.replayEvents()` `default` arm currently WARNs on every restart); decide + implement the WAL
   **directory lifecycle/cleanup policy** (checkpoint + truncate on clean shutdown; retention across restarts —
   gate O1); document B opt-in path and the C disposition.
5. **Scope boundaries (explicit, tracked):** (a) consensus-event *recovery* (a real sink) is out of scope —
   events are written but no FSM consumes them (Phase 3 only silences the WARN); (b) Subsystem C
   productionization / partition-fault tolerance — tracked `Luciferase-s23eu`; (c) Subsystem B 2PC crash
   recovery is opt-in, off in the default node. Any further deferral gets a tracked bead (phase-review-gate
   discipline).

## Consequences / Risks

- Large blast radius: node startup/shutdown semantics, lifecycle dependency graph, `BubbleMigrator`,
  `EntityMigrationStateMachine` recovery seam, many bubble-construction tests.
- Picking among three durability subsystems may mean deprecating/removing two.
- RDR-016 already fixed the standalone R2 split-brain bug; this RDR makes the WAL that R2 protects actually
  live.

## Open Questions

- ~~Q0: node `main` here vs external embedder (gating).~~ **RESOLVED — here (this repo).**
- ~~Which of the three durability/recovery subsystems is authoritative.~~ **RESOLVED — A authoritative; B
  optional defense-in-depth; C orthogonal (not a WAL).**
- ~~nodeId source of truth.~~ **RESOLVED (gate C1) — `FirefliesMemberLookup.digestToUuid(member.getId())`,
  the existing canonical derivation; deterministic across restarts (WAL-identity correctness).**
- ~~Per-node WAL directory lifecycle/cleanup policy.~~ **RESOLVED (gate O1) — assigned to Phase 3 (checkpoint +
  truncate on clean shutdown; retention policy across restarts).**
- ~~Lifecycle ordering fixes (RDR-016 Q4).~~ **RESOLVED — applied as Phase 0; PM is Layer 0 (not behind SCM —
  gate C2), Bubble Layer 1 depends on PersistenceManager.**

## Research Findings

Full read-only codebase investigation recorded in T2 `Luciferase_rdr/017-research-1` (2026-06-07,
`codebase-deep-analyzer`). Key results:

- **Durability comparison** (§1): A is the only entity-FSM-recovering WAL; B is 2PC transaction-level
  (different layer, nullable & always-null in prod); C has zero disk I/O (topology bridge). All three have
  **zero production construction sites** today.
- **Lifecycle defects** (§2): three confirmed — bubbles at Layer 0 (`Manager.java:183`, 2-arg
  `EnhancedBubbleAdapter`), `PersistenceManagerAdapter.doStart()` no-op (`:62`), `dependencies()` references
  unregistered `SocketConnectionManager` (`:77`) → `computeLayers()` would throw.
- **Assembly gap** (§3): no `main()` in `simulation/src/main` (only demos/benchmarks); `Manager` starts an
  empty coordinator. **Correction (gate C1):** the research's "no `Digest`→`UUID` bridge" claim was wrong —
  `FirefliesMemberLookup.digestToUuid()` exists and is canonical; nodeId is now locked to it.
- **Gate C2 correction:** `PersistenceManager` has zero network imports — the
  `PersistenceManagerAdapter.dependencies()=["SocketConnectionManager"]` declaration is spurious; PM belongs at
  Layer 0. The lifecycle fix is to empty that `dependencies()` list, not to order SCM ahead of PM.
- **Recovery seam** (§4): `BubbleMigrator.java:146` setter; `MigrationRecoveryStateSink.java:83-98`
  ENTITY_DEPARTURE→MIGRATING_OUT, MIGRATION_COMMIT→DEPARTED.
- **Hazards**: schedulers start in `PersistenceManager` ctor before `recover()` (→ Decision: defer to
  `doStart()`); consensus events written but unrecoverable (→ Phase 3 WARN silencing only).
