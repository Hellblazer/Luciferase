# Post-Mortem: RDR-023 — Real gRPC mTLS Handshake over Shaded-Only Netty

**Closed:** 2026-06-13 (implemented) · **Beads:** Luciferase-7m9kh (transport fix), Luciferase-l9dny (bubble-channel consumer) — both closed.

## Outcome

The repo's gRPC mTLS — wired (`GrpcCredentialFactory` + `PeerAuthInterceptor` + `FirefliesPeerVerifier`)
but **never exercised with a real handshake** — now completes a real TLS handshake and authenticates peers,
proven by a regression test. Fixed in one type change; consumed by the bubble channel; Ghost/Balance inherit
it automatically (same `GrpcCredentialFactory`).

## Predicted vs Realized

| | Predicted (at create) | Realized |
|---|---|---|
| Root cause | Suspected shaded-vs-non-shaded netty provider / ALPN / credential-translation defect | **Client TLS hostname verification** — cert CN ≠ dialed authority (`No name matching localhost`). Not a netty defect at all; native tcnative/OpenSslEngine handshakes fine. |
| Fix scope | Possibly add non-shaded `grpc-netty` + tcnative (Strategy B) or shaded `GrpcSslContexts` (Strategy A) — feared a dependency/architecture change | **Strategy C**: `ACCEPT_ANY_CERT` plain `X509TrustManager` → `X509ExtendedTrustManager` with no-op endpoint-identification overloads. One type change, **no dependency change**. |
| Effort | RDR-scale; feared multi-session netty surgery | Research diagnostic (3 probes) cracked it; fix + test + consumer wiring all landed same day. |

The research phase (a throwaway 3-probe diagnostic capturing the real exception chain) was decisive: it
turned a feared netty re-architecture into a configuration fix. **Lesson: capture the actual exception
before theorizing about the transport stack** — the `UNAVAILABLE` surfaced with no cause at the gRPC layer
and misdirected the initial l9dny attempt toward "shaded netty is broken."

## Divergences from the RDR

- **Real-handshake test location.** The RDR said "test in `common` (the credential-factory home)."
  Realized: `common` is grpc-api-only (no netty/service to stand up a real server), so the test lives in
  `simulation` (`GrpcCredentialFactoryHandshakeTest`, using `BubbleMigrationServiceGrpc`). Documented at
  implementation; no impact on what is proven.
- **Four extended overloads, not two.** The RDR Decision named the two `checkServerTrusted` overloads;
  `X509ExtendedTrustManager` requires all four (client + server × Socket + SSLEngine). All four are no-ops —
  bytecode review confirmed the server-side client-cert handling is unchanged (the tcnative server callback
  dispatches the 2-arg `checkClientTrusted`, already a no-op).

## Security posture (as accepted)

Disabling client hostname verification is safe in the RDR-005 no-CA/KERI model: the cert CN is not a trust
anchor; the **server authenticates the client** via the KERL-backed `PeerVerifier`. The real-handshake test
proves that direction (authorized → OK, rejected → `UNAUTHENTICATED`). **Client-side *server* authentication
remains a pre-existing, out-of-scope RDR-005 gap** — neither improved nor regressed by this work.

## Follow-ups

- None opened by this RDR. The server→client auth gap is RDR-005's; credential rotation is RDR-005 / `ah3`.
