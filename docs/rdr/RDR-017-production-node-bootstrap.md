---
id: RDR-017
title: Production Node Bootstrap — Compose the Distributed Simulation Node (Lifecycle, WAL, Recovery, Migration)
status: draft
date: 2026-06-06
reviewed-by: self
supersedes: []
related: [RDR-016, RDR-004]
beads: [Luciferase-hwqjk]
---

# RDR-017: Production Node Bootstrap — Compose the Distributed Simulation Node

## Status

Draft (2026-06-06). Spun out of RDR-016 after research found that the simulation module has **no production
node assembly at all** — multiple complete subsystems (lifecycle, WAL persistence, crash recovery, cross-
process migration) exist as library components but nothing composes them into a running node.

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

## Decision (candidate options — NOT yet locked)

- **Q0 — Does the node `main` belong in this repo?** Or is `simulation` a library composed by an external
  embedder/application that owns the bootstrap? This is the gating question.
- **Which durability subsystem survives?** Consolidate to one of the three (or define their division of
  responsibility) — running multiple uncoordinated WALs is a latent inconsistency hazard.
- **If composed here:** define the node bootstrap (nodeId resolution from the Fireflies member UUID,
  `.luciferase/wal/<nodeId>/` log dir), register `SocketConnectionManagerAdapter` →
  `PersistenceManagerAdapter` (with `recover()` fail-loud in `doStart()`) → `EnhancedBubbleAdapter`s
  (Layer 2, depending on `PersistenceManager`), inject the WAL into `BubbleMigrator`, and wire the
  `MigrationRecoveryStateSink` to the node's `EntityMigrationStateMachine`.

## Approach (proposed)

1. **Research / decide Q0** with the user — is this repo the node host?
2. If yes: design the bootstrap + lifecycle ordering; pick the surviving durability subsystem; phase the
   implementation (bootstrap skeleton → persistence wiring + recover() fail-loud → migration WAL bracket →
   integration tests: corrupt WAL aborts startup, clean WAL replays, durability round-trip).
3. If no: document the embedder contract (what a host must construct/register) and fence accordingly.

## Consequences / Risks

- Large blast radius: node startup/shutdown semantics, lifecycle dependency graph, `BubbleMigrator`,
  `EntityMigrationStateMachine` recovery seam, many bubble-construction tests.
- Picking among three durability subsystems may mean deprecating/removing two.
- RDR-016 already fixed the standalone R2 split-brain bug; this RDR makes the WAL that R2 protects actually
  live.

## Open Questions

- Q0: node `main` here vs external embedder (gating).
- Which of the three durability/recovery subsystems is authoritative; what happens to the other two.
- Per-node WAL directory ownership/lifecycle/cleanup; nodeId source of truth.
- Lifecycle ordering fixes (RDR-016 Q4) — apply as part of the bootstrap.
