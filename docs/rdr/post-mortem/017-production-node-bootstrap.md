# Post-Mortem: RDR-017 — Production Node Bootstrap

**RDR:** RDR-017
**Closed:** 2026-06-07 · **Reason:** implemented
**Decision:** node `main` lives in this repo; Subsystem A (`PersistenceManager`/WAL) authoritative;
schedulers relocated to `doStart()`; nodeId = `FirefliesMemberLookup.digestToUuid(member.getId())`; PM at
Layer 0 (gate C2) — all locked 2026-06-07, gate PASSED (2 rounds).
**Epic:** Luciferase-n6jrh (parent: Luciferase-0frcy simulation deep-review remediation)

## Outcome

The simulation module had **no production node assembly** — lifecycle, WAL persistence, crash recovery, and
cross-process migration each existed as library components that nothing composed into a running node, and the
lifecycle dependency graph would have crashed `computeLayers()` if wired as-was. RDR-017 built the assembly
seam and made the durability path real, in four phases, each through stacked review (code-review-expert +
substantive-critic; T2 review memos retained per phase).

| Phase | What | Bead | PR |
|-------|------|------|----|
| P0 | `NodeBootstrap` skeleton (`resolveNodeId`/`walDir`/`assemble`); `PersistenceManagerAdapter.dependencies()`→`List.of()` (gate C2, PM at Layer 0); `Manager` configurable `bubbleDependencies` + 3-arg `createBubble` + fail-loud on missing dep; `computeLayers()` test seam | vhhu0 | #209 |
| P1 | `doStart()` `recover()` fail-loud (corrupt WAL aborts startup) then `startSchedulers()`; **both** batch-flush + checkpoint schedulers relocated out of the PM ctor; `persistenceAdapter` wires the real `MigrationRecoveryStateSink` | pf1iu | #210 |
| P2 | 4-arg `assemble(...migrator)` wires `setPersistenceManager` after start → RDR-016 R2 WAL bracket fires in the assembled node; durability close/reopen round-trip (DEPARTED + MIGRATING_OUT, WAL-identity pinning) through the real bootstrap path | 1693b | #211 |
| P3 | Consensus no-op recovery (4 event types — WARN-silencing only); WAL lifecycle policy (`closeClean` checkpoint+truncate on clean shutdown, crash-safe `close()` retains); Subsystem B opt-in + C disposition docs | skaui | #212 |

## What went right

- **Locked-spec gates held.** C1 (deterministic nodeId via `digestToUuid`), C2 (PM Layer 0), S2
  (WAL-identity-pinned durability round-trip), and the both-schedulers relocation regression each landed as
  written; the gate markers in the RDR mapped 1:1 to ACs.
- **Stacked review caught what green tests did not, every phase.** P0: the silent `createBubble` swallow
  (fixed in-phase). P1: a corrupt-WAL-abort resource leak, a restart-from-FAILED double-replay, a racy
  scheduler-count assertion (all fixed). P2: missing MIGRATING_OUT coverage + the migrator shutdown race. P3:
  the pre-existing checkpoint/recovery data-loss. Code-review and critic caught *different* classes each time.
- **Documented deviation beat literal compliance.** The bead said "unconditionally make `createBubble` depend
  on `PersistenceManager`"; doing so literally would have silently dropped coordinator registration for every
  non-bootstrap `Manager`. The configurable-`bubbleDependencies` deviation delivered the RDR's intent without
  the regression — both reviewers endorsed it.

## Divergences from the plan

- **`createBubble` dependency made configurable, not unconditional** (P0) — see above. Honored the intent,
  avoided a regression.
- **P2 durability round-trip reframed as crash recovery** (P3) — once P3 made a clean shutdown compact the WAL
  (`closeClean` checkpoint+truncate), a clean `close/reopen` correctly recovers nothing. The round-trip is
  fundamentally crash recovery, so P3 rewrote node #1's teardown to crash-close. This was a semantic
  correction, not a weakening — the substantive-critic confirmed it.
- **`main()` left throwing.** Wiring a live, self-starting entry point needs Fireflies-view construction and
  was always out of the locked P0–P3 scope. The assembly seam (`assemble`, `persistenceAdapter`) is the
  delivered, tested API; `main()` is a fenced stub.

## What this leaves for later (tracked, not silent)

- **`Luciferase-n6jrh.3` (P1, CRITICAL, RDR-004-class):** pre-existing WAL checkpoint/recovery silent
  data-loss — recovery replays only post-checkpoint events, but a checkpoint here is a bare sequence marker
  with no durable snapshot (recover() does not re-checkpoint; the periodic checkpoint marks unapplied events
  skippable; `PersistenceManager.eventCounter` resets on restart while the WAL sequence is restored). **Not
  introduced or triggered by RDR-017** (clean shutdown truncates the whole log — nothing retained to
  mis-filter; the crash path's `restoreSequenceCounter` is log-dominated) and **not reachable** until `main()`
  is live. The naive "use the global sequence" fix changes which events are dropped and worsens the crash
  case, so it was deliberately filed rather than half-applied. Must be fixed before any live node relies on
  crash recovery.
- **`Luciferase-n6jrh.1` (P2):** assemble-before-createBubble guard — close the empty-deps race window before
  concurrent live bubble creation is enabled.
- **`Luciferase-n6jrh.2` (P2):** lifecycle-integrate `BubbleMigrator` (a `BubbleMigratorAdapter` stopped ahead
  of the persistence layer) so an in-flight migration cannot commit after the WAL closes (RDR-016 R2 split-brain
  precondition). Interim contract documented on the 4-arg `assemble()` javadoc.
- **`Luciferase-s23eu` (P2):** Subsystem C (`von/RecoveryIntegration`) partition-fault tolerance — the shipped
  node has WAL durability but cannot self-heal under network partition. Pre-declared scope boundary.

All three `main()`-preconditions must land before `NodeBootstrap.main()` is wired live.

## Lessons

- **Pre-existing ≠ ignore, but pre-existing ≠ in-scope-to-fix.** P3 review surfaced a real CRITICAL data-loss
  in code RDR-017 only *touched*. The right move was to verify provenance (pre-existing), prove
  non-reachability (`main()` throws), reject a fix that would worsen behavior, and file it loudly as a
  precondition — not to scope-creep a redesign into a cleanup phase, nor to wave it away because tests were
  green.
- **A no-op `main()` that throws is honest fencing.** Across all four phases the live entry point stayed a
  loud stub, which is what kept every "latent in production" finding genuinely latent — the preconditions are
  unreachable, not unhandled.
- **Cross-phase semantics interact.** P3's clean-shutdown compaction invalidated an assumption baked into P2's
  test; catching that required reading P2's intent, not just re-running it green.
