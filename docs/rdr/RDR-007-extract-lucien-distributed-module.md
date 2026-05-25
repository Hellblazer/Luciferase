---
title: "Extract a lucien-distributed Module (gRPC Clients out of lucien)"
id: RDR-007
type: Architecture
status: draft
priority: medium
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-24
related_issues: [Luciferase-8cv, RDR-005]
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

> To be completed in `/nx:rdr-research` + design. Initial candidate directions:

1. **Draw the seam** — precisely classify each class in `forest/ghost/grpc` and `balancing/grpc` as "transport/RPC" (moves) vs "abstraction" (stays). Identify any back-references from core lucien into the grpc packages (those are the layering violations to sever).
2. **Define `lucien-distributed`** — new Maven module, `lucien-distributed` → `lucien` + `grpc`; strip gRPC/netty/protobuf from `lucien`'s own deps once the move is complete.
3. **Relocate + re-point** — move the gRPC packages, update `simulation` and any forest consumers to depend on `lucien-distributed`, migrate the relevant tests.
4. **Sequence with RDR-005** — decide whether auth lands before the move (in lucien, moved as a unit) or after (in the new module). Avoid doing the auth wiring twice.
5. **Verify** — `mvn dependency:tree -pl lucien` shows no grpc/netty after the split; full build + ghost/balancing integration tests green.

## Open Questions

- Are there compile-time back-references from `lucien` core into the `*/grpc/` packages that must be inverted first?
- Module name: `lucien-distributed` vs `lucien-grpc` vs `lucien-ghost-transport`?
- Do `simulation`'s distributed paths consume these directly, or only via forest APIs (affects how many poms change)?
- RDR-005 ordering: auth-then-move, or move-then-auth?

## Decision

_Pending research + gate._

## Consequences

_Pending._
