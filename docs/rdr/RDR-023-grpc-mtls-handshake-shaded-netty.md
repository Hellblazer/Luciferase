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

Draft (2026-06-13). **Research complete** under `Luciferase-7m9kh` — Critical Assumption 1 is **VERIFIED**
(see §Research Findings): the failure is client-side TLS hostname verification, NOT a shaded-netty problem.
The fix is **Strategy C** (no dependency change). Ready for gate once the §Decision/§Approach below are
finalized to the confirmed fix.

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

- [x] **The handshake-failure root cause** — **Status: VERIFIED** (2026-06-13, §Research Findings). The
  failure is **client-side TLS hostname verification**: cert CN `luciferase-test` ≠ the dialed authority
  `localhost`, yielding `CertificateException: No name matching localhost found` →
  `SSLHandshakeException` → `UNAVAILABLE`. It is **NOT** a shaded-netty / provider / ALPN problem — the
  native OpenSslEngine (bundled tcnative) handshakes fine.
- [x] **The fix is local to credential construction, not a netty-flavor change** — **Status: VERIFIED.**
  Both the non-shaded `Grpc.*` factory path (with `overrideAuthority` matching the CN) and the shaded
  `GrpcSslContexts` path handshake successfully; no dependency change is needed. The minimal fix is in
  `GrpcCredentialFactory` (Strategy C below).

## Research Findings

**2026-06-13 (Luciferase-7m9kh, throwaway diagnostic over grpc-netty-shaded 1.77.0, macOS arm64).** Three
real-handshake probes against a minimal `BubbleMigrationService` with `GrpcCredentialFactory` creds +
`PeerAuthInterceptor`:

| Probe | Config | Result |
|-------|--------|--------|
| REPRO + `overrideAuthority("luciferase-test")` | `Grpc.newServerBuilderForPort` / `newChannelBuilderForAddress` + non-shaded `Tls*Credentials` | **HANDSHAKE OK** |
| NO_OVERRIDE (the l9dny config) | same, but no `overrideAuthority` (authority defaults to `localhost`) | **FAILED** |
| STRATEGY_A | shaded `GrpcSslContexts` `SslContext` on `NettyServerBuilder/NettyChannelBuilder` | **HANDSHAKE OK** |

The NO_OVERRIDE failure cause chain:
```
StatusRuntimeException: UNAVAILABLE: io exception
  → SSLHandshakeException: General OpenSslEngine problem
  → CertificateException: No name matching localhost found
```

**Conclusions:**
1. There is **no shaded-netty / provider / ALPN defect.** The native OpenSslEngine (bundled tcnative)
   negotiates `h2` over TLS fine — the same non-shaded `Grpc.*` factory path the l9dny prototype used
   handshakes once the authority matches.
2. The l9dny `UNAVAILABLE` was **client-side TLS hostname (endpoint-identification) verification**: the
   self-signed cert CN `luciferase-test` ≠ the dialed authority `localhost`.
3. `ACCEPT_ANY_CERT` (a plain `X509TrustManager`) bypasses **chain** validation but **NOT** hostname
   verification — grpc/netty applies endpoint identification separately for `X509TrustManager` (only the
   `X509ExtendedTrustManager` overloads participate in / can suppress it).

## Decision

**Strategy C — fix `GrpcCredentialFactory`'s trust manager; no dependency or netty-flavor change.** Make
`ACCEPT_ANY_CERT` an `X509ExtendedTrustManager` whose `checkServerTrusted(chain, authType, SSLEngine)` /
`(…, Socket)` overloads are no-ops, so the client's "trust any" genuinely bypasses hostname verification
too. This is correct for the RDR-005 model: there is no CA and the cert CN is **not** the trust anchor —
peer identity is proven cryptographically by `PeerAuthInterceptor` + `FirefliesPeerVerifier` against the
KERL. Hostname verification adds nothing here (a hostile cert with a matching CN still fails the KERI check;
a legitimate member whose CN ≠ its dialed address would be wrongly rejected). Strategies A (shaded
`GrpcSslContexts`) and B (non-shaded `grpc-netty` + tcnative) are **rejected** — both work but add
shaded-coupling or a dependency for a problem that is purely a trust-manager configuration bug.

Issuing certs with a CN/SAN matching the dialed address (or setting `overrideAuthority` per-connection)
would also fix the immediate handshake, but is **architecturally inapplicable**: a Delos/KERI member cert's
subject encodes the member's *cryptographic identity* (KERI-derived), not its *network topology*, and the
dialed IP/host is not a valid principal in the trust model (RDR-005: trust is "cert-DN-UID → member-id in
view," not CA/hostname pinning). `overrideAuthority` would also require knowing the remote peer's cert CN
before dialing — a chicken-and-egg problem when certs are provisioned on demand from the KERL. Hostname
verification therefore has no meaningful semantics in this system regardless of implementation path.

> **Security note (gate-reviewed 2026-06-13).** Disabling client hostname verification does **not** weaken
> the *effective* posture: hostname verification against a trust-any cert was already nominal-only — a MITM
> attacker can trivially present a self-signed cert with CN = the dialed address, which passed the old check
> while failing no real TLS check. The genuine open gap is **client-side server authentication**, which is
> **NOT implemented** (RDR-005: `mtlsChannel` is "encrypt + present my identity, NOT authenticate the
> server"); that gap existed before Strategy C, is unchanged by it, is out of scope here, and remains
> tracked under RDR-005. Strategy C is safe because the load-bearing invariant is the **client→server**
> direction: the SERVER authenticates the CLIENT via the KERL-verified `PeerVerifier`. The fix MUST keep
> trust-any TLS paired with the interceptor (the `ServerAuth` invariant), and the real-handshake test MUST
> include a `PeerVerifier`-rejected-peer → `UNAUTHENTICATED` case so the client→server auth gate is proven,
> not just encryption.

## Approach

1. ~~Diagnose (CA-1)~~ — **done** (§Research Findings).
2. **Implement Strategy C:** change `ACCEPT_ANY_CERT` in `GrpcCredentialFactory` to an
   `X509ExtendedTrustManager` with no-op endpoint-identification overloads (both client and server side).
3. **Real-handshake test** in `common`, proving: (1) the TLS handshake completes (encryption); (2) the
   **server authenticates the client** — an authorized client succeeds AND a `PeerVerifier`-rejected client
   gets `UNAUTHENTICATED`. NB this proves only the **client→server** direction; client-side server
   authentication is not implemented (RDR-005) and is explicitly not proven here. This is the artifact that
   proves the mTLS transport + the client→server auth gate works and can never silently regress.
4. **Unblock `l9dny`:** wire the Bubble-channel mTLS (the reverted prototype) now that the transport works;
   its test no longer needs `overrideAuthority` workarounds.
5. **Confirm Ghost/Balance** consume the same fixed path (they already use `GrpcCredentialFactory`).

## Consequences

- Closes the gap that the entire repo's gRPC **client→server** mTLS auth was unproven; gives operators a
  real encrypted + client-authenticated transport instead of plaintext-opt-in.
- Unblocks `Luciferase-l9dny`.
- Does **not** add client-side server authentication — that remains an open RDR-005 item; this RDR neither
  improves nor regresses it (hostname verification against trust-any was never real server auth).
- Risk: Strategy C deliberately disables client hostname verification; safe only while `PeerVerifier`
  remains the mandatory client→server gate (enforced by the `ServerAuth` pairing invariant + the test).

## Validation

- Real-handshake test green on the dev platform (macOS arm64, bundled tcnative) AND green in CI (the
  real-handshake test must run in CI — the green run is the verification that the CI runner's TLS provider
  works; do not leave it dev-only).
- The test proves client→server auth (authorized succeeds, rejected → `UNAUTHENTICATED`), not server→client.
- No regression in existing Ghost/Balance gRPC tests.
