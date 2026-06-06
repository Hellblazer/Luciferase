---
id: RDR-016
title: Productionize WAL Persistence and Recovery in the Simulation Node Lifecycle
status: closed
date: 2026-06-06
accepted_date: 2026-06-06
closed_date: 2026-06-06
close_reason: implemented
reviewed-by: self
supersedes: []
related: [RDR-004, RDR-017]
beads: [Luciferase-hwqjk]
---

# RDR-016: Productionize WAL Persistence and Recovery in the Simulation Node Lifecycle

## Status

Draft (2026-06-06). Created from `Luciferase-hwqjk` after investigation revealed the bead's premise was
understated: the gap is not merely that `PersistenceManager.recover()` has no production caller — the
**entire WAL persistence subsystem is never instantiated in production**. This RDR scopes whether and how
to wire write-ahead-log persistence + crash recovery into the simulation node lifecycle.

## Context

The `simulation/persistence` package implements a complete write-ahead-log subsystem:
- `PersistenceManager` (nodeId, logDirectory, RecoveryStateSink) — append-only event logging
  (`logElectionStart`, vote, migration commit, …), batch flush, checkpointing, and `recover()`.
- `WriteAheadLog` — the on-disk log.
- `EventRecovery` / `RecoveredState` — replays logged events into a `RecoveryStateSink` on `recover()`.
- `MigrationRecoveryStateSink` — applies recovered migration events to `EntityMigrationStateMachine`
  (via a caller-guarded `recoverEntityState`).
- `PersistenceManagerAdapter` (lifecycle) — wraps `PersistenceManager` as a `LifecycleComponent`;
  `doStart()` is a documented no-op, `doStop()` calls `close()`.

Wave-14 (2026-06-04) made `recover()` correct and **fail-loud** on mid-file WAL corruption
(throw `IOException` when `skippedCorrupt > 0`; `validateRecoveryIntegrity`).

## Problem Statement

**Nothing in production constructs or starts the persistence subsystem.** Evidence (2026-06-06):

1. `new PersistenceManager(...)` appears **only in test code** (`PersistenceTest`,
   `PersistenceManagerAdapterTest`, `MigrationRecoveryStateSinkTest`) and one javadoc comment. Zero
   production construction sites.
2. The production node orchestrator `von/Manager` builds a `LifecycleCoordinator` and registers only
   `EnhancedBubbleAdapter`s (`Manager.java:132,183,222`). It never constructs a `PersistenceManager`,
   never registers `PersistenceManagerAdapter`.
3. `BubbleMigrator.setPersistenceManager(...)` (`BubbleMigrator.java:147`) is an optional injection with
   **no production caller** — migration commits are never WAL-bracketed in production.
4. `PersistenceManagerAdapter.doStart()` is a no-op, so even if an adapter were registered, recovery
   would not run.

Consequences:
- **No durability:** node events (elections, votes, migration commits) are never persisted in
  production, so there is nothing to recover.
- **Dead fail-loud guard:** Wave-14's mid-file-corruption `IOException` (the RDR-004-class silent-data-
  loss defense) can never fire, because `recover()` is never called on a populated log.
- A node restart after a crash silently starts fresh with no state replay and no integrity check.

## Decision (candidate options — NOT yet locked; pending research + gate)

- **(A) Fence / document as non-production.** Mark the persistence subsystem explicitly test-only /
  experimental; remove or `@Deprecated`-document `PersistenceManagerAdapter`'s lifecycle pretense. Cheapest;
  abandons durability as a feature. Must confirm no consumer assumes durability.
- **(B) Wire persistence into the node lifecycle (productionize).** `von/Manager` constructs a
  `PersistenceManager` (with a resolved log directory + the `MigrationRecoveryStateSink` bound to the
  node's `EntityMigrationStateMachine`), registers a `PersistenceManagerAdapter` with the
  `LifecycleCoordinator` at the correct dependency layer, and `doStart()` calls `recover()` letting
  `IOException` abort startup. Also inject the `PersistenceManager` into `BubbleMigrator` so migration
  commits are WAL-bracketed. Restores durability + crash recovery + fail-loud.
- **(C) Minimal seam-only.** Implement `recover()` in `PersistenceManagerAdapter.doStart()` + fail-loud
  integration test, but do not assemble persistence in `Manager`. Makes the designed seam correct but
  leaves production with no persistence (recover() still never runs live). Half-measure.

## Approach (proposed, pending acceptance)

1. **Research:** confirm the intended durability model — is WAL persistence meant to be a production
   feature? Determine the correct log-directory ownership (per-node vs per-bubble), the authoritative
   `RecoveryStateSink` wiring (node `EntityMigrationStateMachine`), the dependency-layer ordering for the
   `PersistenceManagerAdapter` in the `LifecycleCoordinator`, and what state besides migrations must be
   recovered (consensus/election events are logged but have no sink). Audit IOException
   swallowing on any path above `PersistenceManager`.
2. **Decide A vs B vs C** at the gate.
3. **Implement** the chosen path: lifecycle wiring in `von/Manager`, `doStart()` recovery, BubbleMigrator
   injection, fail-loud on corruption.
4. **Integration test:** corrupt WAL → node refuses to start (not degraded-start); clean WAL → events
   replay into the sink; happy-path durability round-trip (log → restart → recover → state restored).

## Consequences / Risks

- **Blast radius:** `von/Manager` startup/shutdown, `LifecycleCoordinator` dependency graph,
  `BubbleMigrator`, `EntityMigrationStateMachine` recovery seam, and many bubble-construction tests that
  do not expect recovery to run/throw.
- **Startup semantics change:** a corrupt WAL will now abort node startup (intended fail-loud), which
  tests and operators must expect.
- **Design unknowns:** log-dir lifecycle (creation, per-node isolation, cleanup), recovery of
  consensus/election events (currently logged, no sink), interaction with the distributed membership view.
- **Doing nothing** keeps a complete-but-dead subsystem and a non-functional durability guarantee.

## Research Findings (2026-06-06, code-verified)

Investigation (codebase-deep-analyzer + direct verification) **reframed the problem**: the gap is not
"persistence is unwired" but "**there is no production node assembly at all**". Full detail in T2
`Luciferase_rdr/016-research-1`.

- **F1 — `RecoveredState` has zero production consumers.** All `recover()` callers are tests.
- **F2 — No node bootstrap exists.** There is no `main()` in `simulation/src/main`; `von/Manager` (the
  orchestrator) has no nodeId, no log directory, no restart path, and is itself never constructed in
  production. It builds an empty `LifecycleCoordinator` and registers only `EnhancedBubbleAdapter`s.
- **F3 — `BubbleMigrator.setPersistenceManager()` has no production caller** (WAL bracket skipped).
- **F4 — `PersistenceManagerAdapter.doStart()` is a no-op;** `recover()` never runs in the lifecycle.
- **Q1 → aspirational scaffolding.** The WAL subsystem is well-built and internally consistent but 100%
  disconnected from any running node.
- **Q2 → per-node WAL**, natural directory `.luciferase/wal/<nodeId>/` (mirrors `MigrationLogPersistence`);
  no configuration path exists; nodeId would come from the Fireflies member UUID.
- **Q3 → migration state is the only recoverable state.** Consensus/election events are logged but
  silently dropped in `EventRecovery.replayEvents` (default `WARN`); no consensus sink is needed.
- **Q4 → lifecycle dependency ordering is broken three ways** (bubbles register at Layer 0 and start
  before persistence; `doStart()` no-op; `PersistenceManagerAdapter.dependencies()` references an
  unregistered `SocketConnectionManager`, which would crash `computeLayers()`).
- **Three uncoordinated, all-dead durability/recovery subsystems** exist: `PersistenceManager`/WAL,
  `MigrationLogPersistence`/`CrossProcessMigration` (RDR-`0frcy.30`), and `RecoveryIntegration` — each
  constructed only in tests/javadoc.
- **R2 (real latent bug, fixed here):** `BubbleMigrator` swallowed a `MIGRATION_COMMIT` WAL-write failure
  (`BubbleMigrator.java:322–330`); on a live WAL this causes split-brain entity ownership after a restart.

## Decision (updated post-research — LOCKED: Option A, fence + scope a bootstrap RDR)

**Option B (full wiring) is out of proportion to this bead:** it would require authoring an entire
production node bootstrap that composes `Manager` + a chosen WAL (of three) + recovery + corrected
lifecycle ordering, and it is unclear whether the node `main` belongs in this repo or an external embedder.
That is a separate, larger architecture decision.

**This RDR therefore (A) fences the persistence subsystem as library scaffolding** that is intentionally
not composed into a production node, documents that fact at the code seam, and **fixes the one standalone
latent correctness bug (R2)** that is independent of the wiring question. The full productionization —
including whether a node bootstrap lives here, which of the three durability subsystems survives, and the
Q4 lifecycle-ordering fixes — is **deferred to RDR-017 (Production Node Bootstrap)**. Options B and C are
explicitly deferred to RDR-017, not rejected.

## Scope decision — what this RDR does and does not do

- **Does:** fix R2 (fail-loud on WAL bracket-write failure in `BubbleMigrator`, with a regression test);
  document the persistence/recovery subsystems as uncomposed library scaffolding; scope RDR-017.
- **Does NOT:** construct a `PersistenceManager` in production; author a node bootstrap; choose among the
  three durability subsystems; fix the Q4 lifecycle-ordering defects (those are RDR-017's, since they only
  matter once a node assembles persistence).

## Acceptance Criteria

1. R2 fixed: `BubbleMigrator` aborts the migration (rolls back staging, leaves source authoritative) when
   a WAL `ENTITY_DEPARTURE`/`MIGRATION_COMMIT` write fails with persistence enabled; regression test proves
   no split-brain. **(done — `BubbleMigratorTest.migrationCommitWalFailure_abortsMigration_noSplitBrain`)**
2. The persistence/recovery scaffolding is documented at the code seam as not-composed-in-production.
3. RDR-017 (Production Node Bootstrap) is created, capturing F2/F4/Q1–Q4 + R3 (three dead subsystems) and
   the open question of where the node `main` lives.
4. `Luciferase-hwqjk` is closed against this decision (fenced; real wiring → RDR-017).
