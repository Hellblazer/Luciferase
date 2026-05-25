---
title: "gRPC TLS + Authentication Model for Ghost and Balancing Services"
id: RDR-005
type: Security
status: draft
priority: high
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-24
related_issues: [Luciferase-va5, RDR-004]
---

# RDR-005: gRPC TLS + Authentication Model for Ghost and Balancing Services

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

Every gRPC channel in the distributed stack is created with `.usePlaintext()` and no authentication or peer-identity binding:

- `lucien/.../forest/ghost/grpc/GhostServiceClient.java:379` — `.usePlaintext() // For development - use TLS in production`
- `lucien/.../balancing/grpc/BalanceCoordinatorClient.java:461` — same
- `simulation/.../distributed/network/GrpcBubbleNetworkChannel.java:349` — `.usePlaintext() // For testing; use TLS in production`

Plaintext gRPC means: (a) traffic is unencrypted and tamperable on the wire; (b) any process that can reach the port can call ghost-exchange and balance-coordination RPCs with no credential; (c) there is no binding between an RPC caller and a known cluster member. For a system whose whole point is distributed spatial coordination across processes, this is an unauthenticated control plane.

The "use TLS in production" comments acknowledge the gap but there is no production path: no cert provisioning, no trust store, no peer-identity check. This RDR must decide the **auth model** (not just "turn on TLS"), because the mechanism choice (mTLS vs. token vs. Fireflies-derived identity) drives cert/secret distribution, rotation, and how an RPC is bound to a cluster member.

## Context

### Background

Flagged HIGH in the 360-review (T2 `luciferase/360-review-2026-05-23-summary`, `review-finding-security/grpc-auth`). Deferred from Tranche D-1 because it is a design decision spanning three modules and intersecting the Delos/Fireflies membership layer the project already uses for view management.

The project already has a membership/identity substrate: **Fireflies** (Delos) provides authenticated cluster views (see CLAUDE.md "Fireflies Virtual Synchrony"; `FirefliesViewMonitor`, `FirefliesMembershipView`). Delos ships certificate-backed member identities. This is the most promising basis for peer identity rather than inventing a parallel auth scheme.

### Technical Environment

- **Modules**: `lucien` (ghost + balancing gRPC clients/servers), `simulation` (bubble network channel), `grpc` (proto defs)
- **Key files**:
  - `lucien/.../forest/ghost/grpc/GhostServiceClient.java:379` and the corresponding `GhostServiceImpl`/server builder
  - `lucien/.../balancing/grpc/BalanceCoordinatorClient.java:461` and its server
  - `simulation/.../distributed/network/GrpcBubbleNetworkChannel.java:349`
  - `grpc/` — protobuf service definitions
- **Identity substrate**: Delos `fireflies` + `memberships` artifacts (already dependencies of `simulation`); certificate-backed member identities.
- **Related**: RDR-004 (VoN transport hardening) — shares the "what is the trust boundary / peer identity" question and should be sequenced together; D7/RDR-007 (lucien-distributed split) will relocate these gRPC clients, so the auth wiring should land in a way that survives that move.

## Approach

> To be completed in `/nx:rdr-research` + design. Initial candidate directions:

1. **Inventory the gRPC surface** — enumerate every server/client builder and RPC, and which run cross-process vs in-process (`InProcessServerBuilder` test usages should stay plaintext).
2. **Auth model decision** — primary candidates:
   - **mTLS with Fireflies/Delos certificates**: reuse the existing member certs as gRPC transport credentials; peer identity = cert subject = cluster member. Strongest binding; leverages existing PKI.
   - **Token / bearer-credential**: simpler to wire but needs a token issuer + rotation; weaker peer binding.
   - **Channel credentials + server interceptor** that validates the caller against the current Fireflies view.
3. **Cert/secret distribution + rotation** — how members obtain and refresh credentials; tie to Delos lifecycle.
4. **In-process / test carve-out** — keep `InProcessServerBuilder` and CI plaintext for tests without weakening production (profile/config gate, not code-branch on an env var).
5. **Sequencing with RDR-007** — land the auth wiring so the lucien→lucien-distributed module split doesn't have to redo it.

## Open Questions

- Can Delos/Fireflies member certificates be used directly as gRPC `TlsChannelCredentials` / `TlsServerCredentials`?
- Is there a deployment where these services are reachable outside a trusted subnet (raising urgency)?
- Should the bubble network channel (simulation) and the ghost/balancing channels (lucien) share one credential mechanism, or do they have different trust boundaries?
- How do tests run without real certs — in-process transport, a dev credential, or a test profile?

## Decision

_Pending research + gate._

## Consequences

_Pending._
