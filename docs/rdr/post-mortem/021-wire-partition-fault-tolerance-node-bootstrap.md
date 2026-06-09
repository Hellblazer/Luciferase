---
rdr: RDR-021
title: "Post-mortem — Wire Partition Fault Tolerance into the Production Node Bootstrap"
closed_date: 2026-06-09
outcome: implemented
---

# RDR-021 Post-Mortem

## What shipped

The partition fault subsystem, wired into the production node bootstrap. The assembled
node now self-heals: a VON neighbor-leave escalates partition health through the real
`SimpleFaultHandler` (two leaves → FAILED), and a later VON join drives
FAILED→HEALTHY → `onPartitionRecovered` → `vonManager.joinAt` bubble rejoin — proven
end-to-end by `Rdr021MvvIntegrationTest`. Closes the `Luciferase-s23eu` fault-tolerance
half (the resolver half remains under its sibling RDR).

Single-phase arc, four PRs, each increment stacked-reviewed:

| Step | Bead | PR | Delivered |
|------|------|----|-----------|
| S0 | 0frcy.135.1 | — | Verify-first probe: partition-id = the node's own identity (`resolveNodeId`); no partition concept exists at the `Manager.createBubble` seam, so the registration seam lives at the composition layer |
| S1 | 0frcy.135.2 | #225 | `NodeBootstrap.assembleFaultTolerance(Manager, Clock)`: started `SimpleFaultHandler` + `InMemoryPartitionTopology` + `RecoveryIntegration`, returned as `FaultSubsystem` (AutoCloseable) |
| S2 | 0frcy.135.3 | #226 | `createRegisteredBubble` / `removeRegisteredBubble` (unregister-before-leave, mutation-verified); unregistered-bubble silent no-op contract tested against a live handler |
| S3 | 0frcy.135.4 | #227 | `RecoveryIntegrationAdapter` with dynamic ordering-only bubble dependencies; `Manager.close()` stops recovery ahead of every bubble adapter (mutation-verified) |
| review | 0frcy.135.5 | — | Phase-boundary stacked review of the cumulative diff: 0 Critical; all 10 locked decisions confirmed as-implemented with file:line |
| MVV | 0frcy.135.6 | #228 | End-to-end Gap 1 + Gap 2 proof on a test-assembled node + the decision-#9 negative oracle (no recovery from SUSPECTED) |
| gate | 0frcy.135.7 | — | phase-review-gate cross-walk PASSED (5/5 items); full suite 3268/0/0 |

## Divergences from the original design (all gated)

1. **`LifecycleComponent.dependenciesAreOrderingOnly()` coordinator extension (S3
   remediation).** Not in the RDR §Technical Design. The locked lifecycle-participant
   approach required dynamic dependencies (bubble adapters come and go), and the
   coordinator's `stopAndUnregister` dependents guard — designed for static
   dependencies — then rejected every live bubble removal; `Manager.leave` silently
   swallowed the exception and fell back to direct close, leaking the bubble adapter
   as RUNNING in the coordinator forever. The fix (a default-false interface method;
   ordering-only dependents do not block removal, and `computeLayers` skips an
   ordering-only dependency that vanishes concurrently) is a bounded extension forced
   by the locked design, recorded at the gate — not silent scope creep. All existing
   components default to unchanged behavior.

## What the stacked review caught (run at every increment + phase boundary)

- **S3 Critical (code-review-expert)**: the live-removal rejection + coordinator leak
  above. Invisible to S2's green tests precisely because `Manager.leave` swallows the
  exception — the degradation was silent.
- **S3 Significant (substantive-critic)**: TOCTOU — an ordering-only dependency
  vanishing between the `dependencies()` snapshot and `computeLayers`' existence check
  (concurrent `leave()`+`close()`) aborted the whole coordinated shutdown, bypassing
  the very ordering invariant S3 exists to enforce.
- **S2 Significant (substantive-critic)**: the original single-bubble ordering test
  could not catch a wrong unregister/leave ordering (no neighbors → `broadcastLeave`
  sends nothing under either ordering). Replaced with a two-bubble in-process-neighbor
  test, then proven by mutation (flipping the order fails it).
- **False positives existed too**: the S3 reviewer claimed the S2 tests would fail
  outright (they passed — the exception is swallowed); the MVV reviewer claimed an SCM
  resource leak (it is closed via the coordinator). Verify-before-acting remains
  essential in both directions.

## Verification discipline that paid off

- **Mutation checks** on both load-bearing orderings: flip unregister/leave → S2 test
  fails; empty the dynamic dependencies → S3 ordering test fails. Both deterministic.
- **Red-first remediation**: every review finding that implied a behavior change got a
  failing test before the fix (`liveBubbleRemovalNotBlockedByRecoveryDependency`,
  `computeLayersToleratesVanishedOrderingOnlyDependency`).
- **Anti-vacuity**: the MVV bubble carries entities at a non-origin position so the
  `joinAt` position assertion cannot pass by both sides defaulting to `(0,0,0)`; the
  silent-no-op test first drives the handler to SUSPECTED so "no escalation" cannot
  pass because nothing was wired.

## Left open / future scope

- Concurrent `leave()`+`close()` stress testing (the TOCTOU fix is defensive; the MVV
  is single-threaded by design).
- Multi-bubble protocol rejoin: the MVV's `joinAt` exercises the real solo-join path;
  network-protocol rejoin is out of single-process scope.
- Resolver wiring (`FirefliesBubbleOwnershipResolver` into the bootstrap) — sibling
  RDR; `Luciferase-s23eu` stays open for that half.
- Live `main()` activation remains a named dependency (RDR-017 P1).
