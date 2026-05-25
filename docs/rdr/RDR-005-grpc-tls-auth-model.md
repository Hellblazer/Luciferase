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

> Candidate directions below; resolved by research (see [Research Findings](#research-findings)) into the recommendation that follows.

1. **Inventory the gRPC surface** — enumerate every server/client builder and RPC, and which run cross-process vs in-process (`InProcessServerBuilder` test usages should stay plaintext).
2. **Auth model decision** — primary candidates:
   - **mTLS with Fireflies/Delos certificates**: reuse the existing member certs as gRPC transport credentials; peer identity = cert subject = cluster member. Strongest binding; leverages existing PKI.
   - **Token / bearer-credential**: simpler to wire but needs a token issuer + rotation; weaker peer binding.
   - **Channel credentials + server interceptor** that validates the caller against the current Fireflies view.
3. **Cert/secret distribution + rotation** — how members obtain and refresh credentials; tie to Delos lifecycle.
4. **In-process / test carve-out** — keep `InProcessServerBuilder` and CI plaintext for tests without weakening production (profile/config gate, not code-branch on an env var).
5. **Sequencing with RDR-007** — land the auth wiring so the lucien→lucien-distributed module split doesn't have to redo it.

### Recommended direction (pending gate)

**Two-layer model: mTLS using Delos member certificates at the transport layer + a Fireflies-view `ServerInterceptor` for authorization.** Reject the token/bearer option — it requires a net-new issuer and rotation infrastructure for *weaker* peer binding, when the project already ships a certificate-backed identity substrate.

- **Transport layer (mTLS).** Build `TlsServerCredentials` / `TlsChannelCredentials` from the local member's `CertificateWithPrivateKey` (Delos), requiring client certificates. **There is no shared CA**, so the TLS layer cannot CA-pin; it accepts structurally-valid certs (the Delos `CertificateValidator.NONE` no-op) and defers the *real* trust decision to the interceptor.
- **Authorization layer (`FirefliesAuthInterceptor`) — must CRYPTOGRAPHICALLY VERIFY, not string-match.** ⚠️ **This is the security crux and the original design was forgeable.** `Member.getMemberIdentifier(X509Certificate)` is a *pure DN parser* — it decodes the `UID` field from the cert's X500 DN and hashes it, with **no signature check**. Member `Digest`s are *public* in a KERI system. So "accept any structurally-valid cert at TLS, then check `getMemberIdentifier(cert) ∈ view`" authenticates **nothing**: an attacker mints a self-signed cert carrying a legitimate member's `Digest` as its DN UID, passes the no-op TLS validator, and the interceptor's membership lookup admits it. The interceptor MUST instead cryptographically prove the peer holds the private key the member identifier commits to. Delos provides the binding: `ControlledIdentifier.provision(...)` signs the provisioned cert with the member's controlled (KERI-anchored) key. The interceptor must therefore, per inbound peer cert: (1) parse the candidate member `Digest` from the DN UID; (2) confirm that `Digest` is in the current Fireflies view; **and (3) verify the cert's signature against that member's committed public key obtained from the KERL** (or, equivalently, verify the cert against the presenter's own committed key to prove key-possession). Reject if any step fails. Peer identity = cryptographically-verified cluster member — the binding the problem statement requires. **The exact key-material source for step (3) (local KERL lookup vs. presenter-key verification) is a pre-implementation spike deliverable** (see below).
- **Test carve-out (no env-var branch).** Add an optional `ChannelCredentials` / `ServerCredentials` constructor parameter to each client/manager, defaulting to `InsecureChannelCredentials.create()` / `InsecureServerCredentials.create()`. In-process balancing tests are unaffected; only `CommitteeP2PIntegrationTest` (a real Netty test) needs the cred gate.
- **Placement + sequencing (RDR-007-proof; reconciled with RDR-007).** Put the shared `GrpcCredentialFactory` + `FirefliesAuthInterceptor` in the **`common`** module (already transitive to both `lucien` and `simulation`). These helpers have no `lucien` dependency, so they land **independently, at any time**. The **per-client credential wiring** (replacing each `.usePlaintext()`) waits until **after** the RDR-007 module move (`move-then-auth`), so the change lands once in the permanent home (`lucien-distributed`) rather than being made in `lucien` and then relocated. This supersedes the earlier "land auth before the move" framing and matches RDR-007's resolution; it will be recorded in this RDR's Decision section at accept.
- **Convergence with RDR-004.** If VoN transport migrates to gRPC (RDR-004 Direction B), it inherits this exact mTLS + interceptor model — **one peer-identity mechanism across both the VoN data path and the control plane.** Strong reason to pair RDR-004-B and RDR-005.

> **Pre-implementation spike required** before locking the Decision: (a) prove the cert-reachability workaround — thread the `ControlledIdentifierMember` from the `View` construction site to the credential factory, since `View.Node` does not expose it publicly; (b) **prove the interceptor's cryptographic peer verification** — establish the key-material source (local KERL public-key lookup keyed by member `Digest`, vs. verifying the presented cert against the presenter's own committed key) and demonstrate that a forged cert (legit member `Digest` in the DN UID, attacker key) is **rejected**. Credential **rotation** is also open (Delos provisions certs on demand with caller-chosen validity and no auto-rotation hook) and is a **hard blocker on the "Inc 7+" milestone** tracked by bead **`Luciferase-ah3`**, not on this gate.

## Research Findings

> Code + dependency investigation 2026-05-25 (`codebase-deep-analyzer`, Delos inspected via Serena `search_deps`). Full detail in T2 `luciferase_rdr/005-research-1`.

1. **gRPC surface is entirely cross-process in production.** Servers: `GhostCommunicationManager.java:107` (`ServerBuilder.forPort`), `GrpcBubbleNetworkChannel.java:84` (`NettyServerBuilder.forPort`). Clients (all `.usePlaintext()`): `GhostServiceClient.java:378`, `BalanceCoordinatorClient.java:460`, `GrpcBubbleNetworkChannel.java:348`. `BalanceCoordinatorServer` has no production builder (test-only in-process). All production builders need real credentials.
2. **Delos cert + private key exist but aren't reachable through `View.Node`'s public API.** `ControlledIdentifierMember.getCertificateWithPrivateKey(Instant, Duration, SignatureAlgorithm)` → `CertificateWithPrivateKey.getX509Certificate()` / `getPrivateKey()`, but `View.Node.wrapped` (the `ControlledIdentifierMember`) is private with no getter. Workaround without a Delos change: retain the `ControlledIdentifierMember` reference at `View` construction and feed it to a credential factory. **No shared CA** — certs are provisioned on demand via KERI/KERL; trust must be "cert DN UID → member id in view," not CA pinning.
3. **View-based authorization is feasible but membership lookup ALONE is forgeable.** `FirefliesMembershipView.getMembers()` (`:62`) yields the member set (`Member.getId()` → `Digest`). The static `Member.getMemberIdentifier(X509Certificate)` **only parses** the `UID` from the cert's X500 DN and hashes it — **it performs no signature verification**, and the available TLS validator (`CertificateValidator.NONE`) is a no-op. Since member `Digest`s are public in KERI, a `getMemberIdentifier(cert) ∈ view` check authenticates nothing (any self-signed cert bearing a member's `Digest` passes). The `ServerInterceptor` must additionally verify the cert's signature against the member's KERI-committed key — the cert is signed by the member's controlled key via `ControlledIdentifier.provision(...)`. Not yet implemented anywhere.
4. **Test carve-out is clean.** In-process balancing tests (`BalanceCoordinatorIntegrationTest.java:60-68`, `Phase4E2ETest.java:561-565`) need zero change; only `CommitteeP2PIntegrationTest.java:75,93` (real Netty) needs the cred gate. An optional credentials constructor param defaulting to insecure gates production vs test without an env-var code branch.
5. **RDR-007 split is known.** `GhostServiceClient`, `GhostCommunicationManager`, `BalanceCoordinatorClient`, `BalanceCoordinatorServer` move to `lucien-distributed`; `GrpcBubbleNetworkChannel` and `CommitteeServiceImpl` stay in `simulation`. Shared auth helpers belong in `common` to survive the move.

## Open Questions

- ~~Can Delos/Fireflies member certificates be used directly as gRPC `TlsChannelCredentials` / `TlsServerCredentials`?~~ **Resolved (with a caveat):** Yes — `ControlledIdentifierMember.getCertificateWithPrivateKey(...)` yields an `X509Certificate` + `PrivateKey`. Caveat: it isn't reachable through `View.Node`'s public API (private `wrapped` field), so the reference must be threaded from the `View` construction site. Spike to confirm.
- ~~Is there a deployment where these services are reachable outside a trusted subnet (raising urgency)?~~ **Partially resolved:** Not yet found in code, but the control plane is unauthenticated *regardless* of subnet — any process reaching the port can call ghost/balance RPCs. Urgency is intrinsic, not deployment-gated. (Mirrors RDR-004's "Inc 7+" exposure trajectory.)
- ~~Should the bubble network channel (simulation) and the ghost/balancing channels (lucien) share one credential mechanism, or do they have different trust boundaries?~~ **Resolved:** Share one mechanism — all are cross-process cluster RPCs whose peers are Fireflies members. Shared helpers (`GrpcCredentialFactory` + `FirefliesAuthInterceptor`) live in `common`.
- ~~How do tests run without real certs — in-process transport, a dev credential, or a test profile?~~ **Resolved:** Optional credentials constructor param defaulting to `Insecure*Credentials.create()`. In-process tests unaffected; only `CommitteeP2PIntegrationTest` (real Netty) needs the gate. No env-var branch.

**New open question from research:** Credential **rotation** — Delos provisions certs on demand with caller-chosen validity and exposes no auto-rotation hook. The rotation strategy (validity window, re-provision trigger, channel rebuild on rotation) must be settled before implementation.

## Decision

_Pending research + gate._

## Consequences

_Pending._
