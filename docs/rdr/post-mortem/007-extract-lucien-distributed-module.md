# Post-Mortem: RDR-007 — Extract a lucien-distributed Module

**Closed:** 2026-05-26 — implemented
**Outcome:** `lucien` is now a non-distributed spatial-index core; the gRPC transport lives in a new `lucien-distributed` module.

## What shipped

| Phase | Work | PRs |
|-------|------|-----|
| P0 — dependency inversion | lucien-resident interfaces over the gRPC collaborators (`GhostChannel`/`ServiceDiscovery`, `GhostExchange`, split `RefinementExchange`/`ViolationExchange`); balancing-boundary proto messages → domain records; `io.grpc.StatusRuntimeException` → domain `BalanceExchangeException`. All ghost + balancing core classes grep-clean of grpc/proto. | #96 (Inc1), #97 (Inc2a), #98 (Inc2b), #99 (Inc3) |
| P1+P2 — module move | PR-A: ServiceLoader-ize `SpatialKeySerdeRegistry` + de-grpc shared test fixtures to the `GhostChannel` interface. PR-B: create `lucien-distributed`; move 11 transport classes + 7 integration tests + the `META-INF/services` serde file; strip grpc/netty/grpc-proto-module from `lucien`. | #100 (PR-A), #101 (PR-B) |

Final state: `mvn dependency:tree -pl lucien` shows no `grpc-netty-shaded`/`grpc-testing`/`grpc` proto-module; `lucien-distributed` 34 tests green (serde discovery via ServiceLoader); full reactor (10 modules) green; `simulation/pom.xml` untouched as predicted.

## What went well

- **Research front-loaded the hard part.** The pre-accept analysis correctly identified that this was *not* a clean leaf-move — 8+ compile-time back-references from `lucien` core into the grpc packages forced a dependency-inversion phase (P0) before any physical move. That framing held.
- **Incremental, behavior-preserving increments.** P0 split into Inc1/Inc2a/Inc2b/Inc3, each a small, independently-reviewed, behavior-preserving inversion with the full lucien suite green at every step. The two-PR split of P1/P2 (prep-then-move) kept each PR green despite the module-wide compile coupling.
- **Stacked review caught real issues before merge** at every increment (per `feedback_review-stacking`): a per-element resilience regression (Inc2b), the SIG-1 convergence-vote change (Inc3), the `flushToTarget` mock NPE trap and the test-jar coupling (PR-A/PR-B).

## Lessons / what was tricky

1. **Classpath `ServiceLoader` needs a public no-arg constructor.** The serdes used a private-ctor `INSTANCE` singleton with static self-registration. The Java-9 `provider()` static-method form only works on the *modulepath*; on the classpath (this project — only `dyada-java` has `module-info`), `ServiceLoader` instantiates via the public no-arg ctor and throws `ServiceConfigurationError` otherwise. Fix: drop the singleton, public ctor, registry's `ServiceLoader.forEach(register)` as the single registration path.
2. **A `META-INF/services` file must move *with* its providers.** A services file left behind in `lucien` naming the (now-moved) serde classes makes `ServiceLoader` hit `ClassNotFoundException` and *silently skip* the provider — an empty registry with no compile error. Caught in PR-A review, tracked, and verified-relocated in PR-B.
3. **"Extract gRPC" conflated transport with serialization coupling.** `lucien` could not become fully proto-free: `ContentSerializer`'s public API uses `com.google.protobuf.ByteString`, consumed by `AbstractSpatialIndex`. The RDR extracted the *transport* (clients/netty/proto-generated-message-types) and accepts proto serialization in core; `lucien` retains a direct `protobuf-java` dep. The audit (F1) surfaced this; the cleaner `ByteString`→`byte[]` migration is a tracked follow-up.
4. **Module-wide test compilation couples "independent" beads.** Inverting a class signature broke tests in the same module that couldn't be split out, so increments that looked independent (e.g. C3 violation chain vs C6 `Phase4E2ETest`) were compile-coupled and had to land together.
5. **Trust-but-verify on subagent output paid off repeatedly.** A reviewer's CRITICAL "9 tests will NPE" was empirically false (tests green); a reviewer's `@Override`-missing finding was a false positive; the "dead reflection path" concern dissolved once the actual caller was checked. The empirical suite + reading the real source settled each.

## Follow-ups (tracked, out of RDR-007 scope)

- Migrate `ContentSerializer` off `ByteString` (→ `byte[]` or a lucien-native container) to drop `protobuf-java` from `lucien` entirely. (P3)
- Pre-existing `GrpcGhostChannel.queueGhost` TOCTOU race on the auto-flush path. (P3 bug)
- `io.grpc:grpc-api` remains transitive in `lucien` via `common` (RDR-005 `GrpcAuth` helpers) — a common-module concern, not lucien's transport.
- **RDR-005 per-client mTLS wiring** — the MOVE-THEN-AUTH step, now unblocked.
