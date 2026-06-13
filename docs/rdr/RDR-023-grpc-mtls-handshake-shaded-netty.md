---
id: RDR-023
title: Real gRPC mTLS Handshake over Shaded-Only Netty — Verify and Fix the Unproven TLS Transport
status: draft
date: 2026-06-13
supersedes: []
related: [RDR-005, RDR-013, RDR-007]
beads: [Luciferase-7m9kh, Luciferase-l9dny]
---

# RDR-023: Real gRPC mTLS Handshake over Shaded-Only Netty

## Status

Draft (2026-06-13). Research in progress under `Luciferase-7m9kh`. One Critical Assumption (the actual
handshake-failure cause) is **unverified** — it requires capturing the live `SslHandshakeException` (see
§Critical Assumptions). Do not accept until that is resolved.

## Context

The repo's gRPC mTLS is **wired but never exercised end-to-end**:

- `GrpcCredentialFactory` (common) builds `TlsServerCredentials`/`TlsChannelCredentials` (both
  `ACCEPT_ANY_CERT` at the TLS layer) plus a `PeerAuthInterceptor` + `PeerVerifier` for cryptographic
  peer-identity auth (RDR-005). `FirefliesPeerVerifier` implements the real KERI/Fireflies verification.
- `GhostCommunicationManager` (lucien-distributed) and `BalanceCoordinatorServer`/`Client` consume the
  credential factory via `Grpc.newServerBuilderForPort(port, creds)` / `Grpc.newChannelBuilder(...)`.

But **no test anywhere performs a real TLS handshake**:
- `CredentialPlumbingTest` exercises only the `insecureChannel()`/`insecureServer()` branches and verifies
  the `PeerAuthInterceptor` is *installed* — not that TLS negotiates.
- `GrpcAuthTest` mocks the SSL session (`Grpc.TRANSPORT_ATTR_SSL_SESSION`) — no socket, no handshake.

When a real handshake is attempted (the `Luciferase-l9dny` prototype: server via
`Grpc.newServerBuilderForPort(port, serverAuth.credentials())` + client via
`Grpc.newChannelBuilderForAddress(host, port, mtlsChannel(key, cert))`, both over the repo's
**grpc-netty-shaded 1.77.0** — the only netty on the classpath), **both the authorized round-trip and a
direct blocking `HealthCheck` fail with `Status UNAVAILABLE`** — the handshake never completes. The
prototype was reverted (shipping unverifiable security code is worse than the honest plaintext-opt-in).

This means the entire repo's gRPC mTLS story (Ghost, Balance, and the proposed Bubble channel) is
**handshake-unproven**. `l9dny` (Bubble-channel mTLS) is blocked on it.

### Research so far (2026-06-13, `7m9kh` notes)

Two common causes are **ruled out**:

1. **Missing TLS provider — NO.** `grpc-netty-shaded-1.77.0.jar` bundles native tcnative/boringssl
   (`META-INF/native/libio_grpc_netty_shaded_netty_tcnative_osx_aarch_64.jnilib`, plus linux x86_64/aarch64),
   so a working native TLS provider is present on the dev platform (macOS arm64).
2. **Client rejects the self-signed server cert — NO.** `GrpcCredentialFactory.mtlsServer` *and*
   `mtlsChannel` both install `ACCEPT_ANY_CERT`, so neither side rejects an unchained cert; `clientAuth`
   is `REQUIRE`.

**Remaining suspect:** the non-shaded `io.grpc.Grpc.newServerBuilderForPort` /
`newChannelBuilderForAddress` factories resolve the *shaded* netty provider (registered via
`META-INF/services/io.grpc.ServerProvider`), and the credential / ALPN / HTTP-2 negotiation between the
non-shaded `TlsServerCredentials` and the shaded provider is where it breaks. **Unconfirmed** — the
`UNAVAILABLE` surfaced with no cause in the test output; the netty/SSL handshake exception is logged below
the gRPC layer.

## Critical Assumptions

- [ ] **The handshake-failure root cause** — **Status: UNVERIFIED.** **Method to verify:** stand up a
  minimal real grpc service over shaded netty with `GrpcCredentialFactory.serverAuth(...)` +
  `mtlsChannel(...)`, attempt one RPC, and capture the actual exception with
  `-Djavax.net.debug=ssl:handshake` and netty/grpc DEBUG logging. Distinguish: ALPN failure (no `h2`
  negotiated), cipher/EC-curve mismatch, shaded-vs-non-shaded credential translation gap, or
  `-XstartOnFirstThread`/event-loop issue. The fix strategy depends entirely on this.
- [ ] **The fix is local to credential construction, not a netty-flavor change** — **Status: UNVERIFIED.**
  If the cause is the non-shaded `Grpc.*` factory mishandling shaded credentials, building a shaded
  `GrpcSslContexts` `SslContext` directly on the shaded `NettyServerBuilder.sslContext(...)` /
  `NettyChannelBuilder.sslContext(...)` may fix it without adding non-shaded `grpc-netty` + tcnative
  (which risks shaded/non-shaded coexistence problems).

## Decision

**DEFERRED pending research** (Critical Assumption 1). The decision is the **fix strategy**, chosen once the
handshake cause is known. Candidate strategies (to be evaluated against the captured exception):

- **A — Shaded SslContext path:** build the mTLS `SslContext` via the shaded
  `io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts` and apply it directly to the shaded
  `NettyServerBuilder.sslContext(...)` / `NettyChannelBuilder.sslContext(...)`, deriving key/trust managers
  from `GrpcCredentialFactory`'s key+cert (reusing `PeerAuthInterceptor` unchanged). Keeps the single
  shaded-netty dependency. *Provisional preference* — no new deps, stays on the proven bundled tcnative.
- **B — Non-shaded grpc-netty + tcnative:** add `grpc-netty` (non-shaded) + `netty-tcnative-boringssl-static`
  so `Grpc.newServerBuilderForPort` resolves a non-shaded provider matching the non-shaded
  `TlsServerCredentials`. Heavier; shaded+non-shaded coexistence on one classpath is a known hazard.
- **C — other**, if the cause is unrelated to netty flavor (e.g. an ALPN/JDK config fix).

Whichever wins must land a **real-handshake test** (authorized round-trip succeeds; a `PeerVerifier`-rejected
peer gets `UNAUTHENTICATED`) so the mTLS path can never silently regress again.

## Approach

1. **Diagnose (CA-1):** minimal real-handshake harness + capture the `SslHandshakeException`.
2. **Decide strategy** (A/B/C) from the diagnosis; record here.
3. **Implement** the chosen strategy in `GrpcCredentialFactory` (and/or the server/channel builders).
4. **Real-handshake test** in `common` (the credential-factory home) — authorized round-trip +
   forged/rejected-peer `UNAUTHENTICATED`. This is the artifact that proves the mTLS transport works.
5. **Adopt repo-wide:** confirm Ghost/Balance use the now-proven path; unblock `l9dny` (Bubble channel).

## Consequences

- Closes the gap that the entire repo's gRPC mTLS is unproven; gives operators a real authenticated
  transport instead of plaintext-opt-in.
- Unblocks `Luciferase-l9dny`.
- Risk: a netty-flavor dependency change (strategy B) could destabilize the gRPC stack; strategy A avoids it.

## Validation

- Real-handshake test green on the dev platform (macOS arm64, bundled tcnative).
- No regression in existing Ghost/Balance gRPC tests.
- CI green across modules (the real-handshake test must run in CI — verify the CI runner's TLS provider).
