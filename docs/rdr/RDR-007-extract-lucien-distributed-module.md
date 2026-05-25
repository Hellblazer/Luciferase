---
title: "Extract a lucien-distributed Module (gRPC Clients out of lucien)"
id: RDR-007
type: Architecture
status: accepted
priority: medium
author: hal.hildebrand
reviewed-by: self
created: 2026-05-24
accepted_date: 2026-05-25
related_issues: [Luciferase-8cv, RDR-005, RDR-008, Luciferase-aos]
---

# RDR-007: Extract a lucien-distributed Module (gRPC Clients out of lucien)

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

`lucien` is meant to be the core, non-distributed spatial-index library (Octree/Tetree/Prism/SFC). But it currently owns gRPC service clients and servers for distributed coordination:

- `lucien/src/main/java/.../forest/ghost/grpc/` — ghost-layer exchange over gRPC
- `lucien/src/main/java/.../balancing/grpc/` — balance-coordination over gRPC

This means the core spatial-index library compile-depends on gRPC/protobuf/netty and carries distributed-systems concerns (network channels, `usePlaintext`, RPC lifecycle) that have nothing to do with being a spatial index. A consumer who just wants an in-memory Octree pulls the entire gRPC stack. It also blurs the layering: distributed ghost/balancing logic should sit *above* the spatial-index core, not inside it.

The fix is a module split: a new `lucien-distributed` (or `lucien-grpc`) module that depends on `lucien` and the `grpc` proto module, holding the gRPC clients/servers, leaving `lucien` itself free of network/RPC dependencies.

## Context

### Background

360-review architecture finding (`a1542d17`, T2 `luciferase/360-review-2026-05-23-summary`): "lucien owns gRPC service clients (ghost+balancing) that should live in lucien-distributed." This is a clean-layering refactor, not a behavior change.

It is sequenced with RDR-005 (gRPC TLS+auth): the auth wiring touches exactly these gRPC clients, so the auth model should land in a way that survives (or coincides with) the module move — ideally decide the split first, or land auth in `lucien` and move it as a unit.

### Technical Environment

- **Module**: `lucien` (source), new `lucien-distributed` (target), `grpc` (proto dependency), and downstream consumers (`simulation`, forest tests)
- **Key packages to relocate**:
  - `lucien/.../forest/ghost/grpc/` — `GhostServiceClient`, ghost service impl, channel mgmt
  - `lucien/.../balancing/grpc/` — `BalanceCoordinatorClient`, balance service impl
- **What stays in lucien**: the non-distributed ghost layer abstractions (`GhostLayer`, `GhostType`, ghost geometry) and balancing strategy interfaces — only the gRPC transport leaves.
- **Dependency direction after split**: `lucien-distributed` → `lucien` + `grpc`; `lucien` loses its gRPC/netty deps.
- **Related**: RDR-005 (auth on these exact clients); the forest ghost tests (`GhostIntegrationTest`, `GhostCommunicationIntegrationTest`) and Phase4* balancing tests will move or gain a test dependency on the new module.

## Approach

> Candidate directions below; resolved by research (see [Research Findings](#research-findings)) into the phased recommendation that follows.

1. **Draw the seam** — precisely classify each class in `forest/ghost/grpc` and `balancing/grpc` as "transport/RPC" (moves) vs "abstraction" (stays). Identify any back-references from core lucien into the grpc packages (those are the layering violations to sever).
2. **Define `lucien-distributed`** — new Maven module, `lucien-distributed` → `lucien` + `grpc`; strip gRPC/netty/protobuf from `lucien`'s own deps once the move is complete.
3. **Relocate + re-point** — move the gRPC packages, update `simulation` and any forest consumers to depend on `lucien-distributed`, migrate the relevant tests.
4. **Sequence with RDR-005** — decide whether auth lands before the move (in lucien, moved as a unit) or after (in the new module). Avoid doing the auth wiring twice.
5. **Verify** — `mvn dependency:tree -pl lucien` shows no grpc/netty after the split; full build + ghost/balancing integration tests green.

### Recommended direction (pending gate)

**This is not a clean leaf-move — research found 8+ compile-time back-references from `lucien` core *into* the gRPC packages, so a dependency-inversion phase must precede the physical move.** Phased:

- **Phase 0 — dependency inversion (the hard part).** Introduce `lucien`-resident interfaces for the gRPC collaborators that core `lucien` references (`GhostCommunicationManager`, `GhostServiceClient.ServiceDiscovery`, `GrpcGhostChannel`, `BalanceCoordinatorClient`), and **replace the protobuf message types at the balancing boundary** (`RefinementRequest`/`RefinementResponse`/`BalanceViolation` used directly by `CrossPartitionBalancePhase` and `DistributedViolationAggregator`) with domain objects — that proto-type leak is what forces `lucien` to keep the `grpc` compile dependency. Sever the `AbstractSpatialIndex` references at `:5183` / `:5195-5196` / `:5214`.
  - **Also a runtime-type leak, not just proto types:** `DistributedViolationAggregator.java:23` imports `io.grpc.StatusRuntimeException` — a gRPC *runtime* type that replacing proto messages does **not** remove. Either move `DistributedViolationAggregator` wholesale to `lucien-distributed`, or wrap `StatusRuntimeException` in a domain exception at the interface boundary; otherwise `lucien` retains a residual `io.grpc` compile dep even after the proto types are gone. (The `8+` back-reference count is conservative — `BalanceMetrics` and `ButterflyViolationAggregator` also import grpc types; enumerate exhaustively during Phase 0.)
  - **Scope boundary vs RDR-008 (the two are NOT the same PR).** RDR-007 Phase 0 = the *minimum* to unblock the move: introduce the interfaces and sever the FQN references. RDR-008 Phase 2 = the *full* `GhostCoordinator` feature-object extraction of the ~22-method distributed-ghost cluster. They overlap on the `AbstractSpatialIndex` ghost wiring but are different sizes: **RDR-008 Phase 2 depends on RDR-007 Phase 0 landing first.** Both are owned by the single shared bead **`Luciferase-aos`**, where the interface contract is defined before either RDR begins Phase 1+.
- **Phase 1 — create `lucien-distributed`** (`lucien` + `grpc` + `grpc-netty-shaded`); move the 9 transport classes from `forest/ghost/grpc` + `balancing/grpc`, plus the `GrpcGhostChannel` impl.
- **Phase 2 — re-point + strip.** Update consumers (all in `lucien/src/test` — **`simulation`'s production code does not consume these**, so its pom is unchanged), migrate/test-scope the ghost & balancing integration tests, and remove `grpc`/`netty` from `lucien`'s deps. Verify `mvn dependency:tree -pl lucien` shows no grpc/netty.
- **Module name:** `lucien-distributed` (matches the 360-review finding) over the narrower `lucien-grpc`.
- **RDR-005 sequencing (locked, reconciled both ways):** the shared auth helpers (`GrpcCredentialFactory`, `FirefliesAuthInterceptor`) have no `lucien` dependency → land them in `common` independently, any time. Do the **module move (this RDR) before wiring per-client credentials (RDR-005)** so the `.usePlaintext()`→credentials change lands once, in the permanent home. RDR-005's Recommended direction has been updated to match this `move-then-auth` ordering (it previously implied auth-before-move); both RDRs will record it identically in their Decision sections at accept.

## Research Findings

> Investigation 2026-05-25 (`codebase-deep-analyzer`, building on `005-research-1`). Full detail in T2 `luciferase_rdr/007-research-1`.

1. **The seam is clean at the package level: 9 grpc-subpackage classes move.** `forest/ghost/grpc/` (7: `GhostCommunicationManager`, `GhostExchangeServiceImpl`, `GhostServiceClient`, `MortonKeySerde`, `TetreeKeySerde`, `ProtobufConverters`, `SimpleServiceDiscovery`) and `balancing/grpc/` (2: `BalanceCoordinatorClient`, `BalanceCoordinatorServer`). Abstractions (`GhostLayer`, `GhostType`, `GhostElement`, etc.) sit in parent packages with zero grpc imports — they stay. `GrpcGhostChannel` (in `forest/ghost/`, not the subpackage) is borderline and moves with the bundle.
2. **But there are 8+ back-references from `lucien` core into the grpc packages (the real blocker).** Most notably `AbstractSpatialIndex` itself: `:5183` `setupDistributedGhosts(GhostCommunicationManager,…)`, `:5195-5196` `new GrpcGhostChannel<>(…)`, `:5214` `initializeDistributedGhosts(GhostServiceClient.ServiceDiscovery)`. Also `GhostBoundaryDetector`, `ElementGhostManager`, `DistributedGhostManager`, and balancing-parent classes (`RefinementCoordinator`, `CrossPartitionBalancePhase`, `DistributedViolationAggregator`, `DefaultParallelBalancer`). `CrossPartitionBalancePhase` is the worst — it imports the client *and* `ProtobufConverters` *and* proto message types directly.
3. **`simulation` does not consume these in production** (only `GhostZoneManager`, a stay-class, via tests). Every grpc consumer is in `lucien/src/test`. So only `lucien/pom.xml`, the new `lucien-distributed/pom.xml`, and root `pom.xml` change — **`simulation/pom.xml` is untouched.**
4. **`lucien` cannot drop the `grpc` compile dep until the proto-type leak is fixed.** `lucien/pom.xml:36-38` compile-depends on the `grpc` module for proto stubs; `grpc-netty-shaded`/`grpc-testing` are test-scope only. After Phase 0 inverts the proto types at the balancing boundary, `lucien` retains just `common`/`h2-mvstore`/`guava`/`slf4j`; `lucien-distributed` takes `lucien`+`grpc`+`grpc-netty-shaded`.
5. **Cross-RDR coordination:** Phase 0's `AbstractSpatialIndex` inversion is identical to RDR-008's Phase 2 distributed-ghost extraction. Sequence RDR-007 Phase 0 with / before RDR-008 Phase 2.

## Open Questions

- ~~Are there compile-time back-references from `lucien` core into the `*/grpc/` packages that must be inverted first?~~ **Resolved — yes, 8+.** Including `AbstractSpatialIndex` (`:5183/:5195-5196/:5214`), the ghost managers, and the balancing-parent classes. Dependency inversion (Phase 0) is mandatory before any physical move. The proto-type leak in `CrossPartitionBalancePhase`/`DistributedViolationAggregator` is what pins `lucien`'s `grpc` compile dep.
- ~~Module name: `lucien-distributed` vs `lucien-grpc` vs `lucien-ghost-transport`?~~ **Resolved:** `lucien-distributed` (matches the 360-review finding; broader than just gRPC transport).
- ~~Do `simulation`'s distributed paths consume these directly, or only via forest APIs (affects how many poms change)?~~ **Resolved:** Not at all in production — every consumer is in `lucien/src/test`. `simulation/pom.xml` is unchanged; only `lucien`, new `lucien-distributed`, and root poms change.
- ~~RDR-005 ordering: auth-then-move, or move-then-auth?~~ **Resolved:** Move-then-auth. Common auth helpers land in `common` independently; per-client credential wiring (RDR-005) happens after the move, in the permanent home.

## Decision

Accepted 2026-05-25 (gate PASSED, self-reviewed). Locked, phased:

1. **Phase 0 — dependency inversion** (owned by shared bead `Luciferase-aos`; this is RDR-008 Phase 2's prerequisite): lucien-resident interfaces over the gRPC collaborators; sever `AbstractSpatialIndex` `:5183`/`:5195-5196`/`:5214`; replace the balancing-boundary proto message types with domain objects; **and** resolve the `io.grpc.StatusRuntimeException` leak in `DistributedViolationAggregator:23` (move it wholesale or wrap in a domain exception) — proto replacement alone won't drop the `io.grpc` dep. Enumerate all back-references exhaustively (8+ is conservative).
2. **Phase 1 — create `lucien-distributed`** (`lucien` + `grpc` + `grpc-netty-shaded`); move the 9 transport classes + the `GrpcGhostChannel` impl.
3. **Phase 2 — re-point + strip** `grpc`/`netty` from `lucien` (consumers are all in `lucien/src/test`; `simulation/pom.xml` is unchanged); verify `mvn dependency:tree -pl lucien` shows no grpc/netty.

Module name: `lucien-distributed`. **Sequencing:** move-then-auth — RDR-005's per-client credential wiring lands after this move; RDR-005's common helpers are independent. RDR-007 Phase 0 (interfaces + sever, the minimum) is **not** the same PR as RDR-008 Phase 2 (full `GhostCoordinator` extraction), which depends on it.

## Consequences

- **Positive:** `lucien` becomes a true non-distributed spatial-index core (no gRPC/netty/protobuf compile deps); distributed concerns layer cleanly above it; the dependency inversion also unblocks RDR-008's god-class decomposition.
- **Cost / risk:** Phase 0 is the hard part — the proto-type and `StatusRuntimeException` leaks mean inversion is more than a file move; under-enumerating back-references would leave `lucien` still depending on `grpc`. Mitigated by the shared `Luciferase-aos` interface contract defined before Phase 1.
- **Sequencing:** coordinate Phase 0 with RDR-008 via `Luciferase-aos`; do not begin Phase 1 until the interface contract is settled.
