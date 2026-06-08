# Post-Mortem: RDR-019 — WAL Checkpoint/Recovery Durability

**Closed:** 2026-06-08 (implemented) · **Driving bug:** `Luciferase-n6jrh.3` (CRITICAL silent data loss)
**PRs:** #214 (acceptance + Phase 1), #215 (Phase 2 compaction) — both merged to `main`.

## What the problem was

The simulation node's WAL recovery silently dropped in-flight migrations on restart. The periodic
5 s checkpoint recorded the live event sequence with **no durable snapshot** behind it, and
`EventRecovery.recover()` replayed only events *after* the last checkpoint. A migration written and
then checkpointed (but not committed) was treated as already-captured and skipped — the FSM
reconstructed `null` instead of `MIGRATING_OUT`. Pure data loss, invisible from green tests.

## What shipped

**Phase 1 — correctness floor (PR #214).**

- Removed the periodic checkpoint scheduler as a replay bound (gate O1: the only checkpoint is
  `closeClean()`'s, structurally always followed by `truncate()` — every checkpoint is
  truncation-backed).
- `checkpoint()` now sources `WriteAheadLog.getSequence()` (the restored high-water mark), not the
  session-local `eventCounter` that reset to 0 each restart (Gap 4).
- `recover()` FULL-replays the retained log. Closes the `MIGRATING_OUT`/`DEPARTED` loss.

**Phase 2 — bounded WAL (PR #215).**

- `WriteAheadLog.compactCompletedMigrations()`: seal the active segment first, then two-pass stream
  over **sealed** segments only, pruning whole completed migration cycles **per entity**, crash-safe
  via write-new-then-rename, with a `.compaction.meta` watermark (diagnostics only) and mid-file
  corruption abort. `PersistenceManager.compact()` + a size auto-trigger (16 MB) with a churn guard.
- Gate S1 (`null→GHOST` forward path) resolved as an **accepted gap** (decision B), tracked as
  `Luciferase-gg28h`.
- **Bounded replay via physical removal + full replay** — deliberately *not* watermark-filtered
  replay, so the RDR-019 data-loss class is never re-introduced. `EventRecovery` unchanged from Phase 1.

## What went well

- **Reproduce-before-fix.** The Phase 1 RED regression (`EventRecoveryDataLossRegressionTest`)
  pinned the exact failure (`null` vs `MIGRATING_OUT`) before any production change.
- **Stacked review caught what tests and I missed — twice.** Phase 1's review surfaced a pre-existing
  re-migration dedup bug. Phase 2's review found **two CRITICALs**: (1) cycle-blind compaction pruning
  would drop an in-flight re-migration departure — the *exact* RDR-004 data-loss class this RDR exists
  to kill — and (2) a non-prunable log above the size trigger would re-seal every batch-flush tick,
  exhausting inodes. Both were invisible from the green suite (no test exercised a two-cycle entity).
- **Design simplification under review.** The accepted plan sketched watermark-filtered bounded
  replay; the as-built design achieves the same bound by *physically* removing pruned events and
  keeping full replay — strictly safer (no logical replay filter to get wrong) and simpler.

## What to watch / carry forward

- **`Luciferase-gg28h` (open, accepted gap).** After compaction prunes a completed pair, a restarted
  source node has `getState == null` for that entity, so the live `DEPARTED→GHOST` path can't fire.
  Benign today (entity owned at target; ghost adjacency re-derivable by the neighbor layer). If a
  workload surfaces lost source-side ghosts, add a neighbor-layer ghost re-derivation on recovery —
  **not** a `null→GHOST` FSM transition (that would weaken the single-owner invariant).
- **Test the multi-cycle entity.** The cycle-blind prune bug existed because every compaction test
  used distinct UUIDs per role. Any future migration-replay/compaction work should include a
  same-entity re-migration case.
- **`checkpoint()` is public with comment-only defense.** If a future phase re-introduces any
  bounded replay, every `checkpoint()` call site must be audited (it can again become a silent
  replay bound). Consider narrowing the surface (package-private / a dedicated `compact()` API) then.

## Lessons

1. A green suite is not coverage — both CRITICALs lived in untested scenario *shapes* (two-cycle
   entity; all-in-flight log above threshold), not in untested lines. The substantive-critic's
   mandate to "pressure-test that compaction cannot prune a recovery-needed event" found the worst one.
2. When an optimization (here: checkpoint-bounded replay, from `Luciferase-0frcy.37`) has no durable
   state behind its bound, it is a data-loss waiting to happen. Prefer physical bounding over logical
   filtering.
