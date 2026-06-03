---
id: RDR-013
title: gRPC Server DoS Hardening — Explicit Inbound Message-Size Bounds for Ghost/Balance
status: accepted
date: 2026-06-03
supersedes: []
related: [RDR-005, RDR-007]
beads: [Luciferase-06ujn]
---

# RDR-013: gRPC Server DoS Hardening — Explicit Inbound Message-Size Bounds for Ghost/Balance

## Status

Accepted (2026-06-03). Implemented under `Luciferase-06ujn` (critique-2026-06-02 remediation).

## Context

The 2026-06-02 substantive critique flagged the Ghost and Balance gRPC servers as a DoS surface:
"add server-side auth + per-request size bounds."

Code investigation (2026-06-03) refined the picture:

- **Auth already exists for Ghost.** `GhostCommunicationManager` builds the production Ghost gRPC server and
  supports opt-in mTLS via the RDR-005 `GrpcCredentialFactory.ServerAuth` (trust-anchored credentials + a
  `PeerAuthInterceptor` wired as one unit). It is *insecure by default* (plaintext, no auth) for in-process/test
  use — which RDR-005 accepted. So 06ujn does **not** need a new auth mechanism for Ghost; the existing one stands.

- **No explicit inbound size bound anywhere.** Neither the Ghost server nor any client sets
  `maxInboundMessageSize`. The server therefore relies on gRPC's *implicit* 4 MiB inbound default. That default
  is a real bound, but it is invisible, unconfigurable, and undocumented here — an operator cannot tighten it for
  a hostile network, and a future change raising it (or setting `Integer.MAX_VALUE` on a client) would silently
  remove the only DoS protection. A single oversized frame can force a large heap allocation before any
  application code runs.

- **Balance has no production server.** `BalanceCoordinatorServer` is only a `BindableService` impl; the only
  sites that stand up a server for it are tests, using `InProcessServerBuilder` (which does not transit the
  wire). There is therefore no production seam to harden for Balance today — only a future one to guard against.

- **A third production server exists.** RDR-005's research inventory enumerated `GrpcBubbleNetworkChannel`
  (`simulation`, `NettyServerBuilder.forPort`, consumed by `TwoNodeExample`/`SimpleCapacityNode`/
  `SimpleMigrationNode`). It also set no inbound bound. The first revision of this RDR omitted it; it is now in
  scope and hardened with the same helper. (Stacked-review catch, 2026-06-03.)

## Decision

1. **Make the Ghost server's inbound message-size bound explicit and configurable.** Apply
   `serverBuilder.maxInboundMessageSize(...)` in `GhostCommunicationManager` with a safe default
   (`DEFAULT_MAX_INBOUND_MESSAGE_BYTES = 4 MiB`, matching gRPC's implicit default but now explicit). Add one
   overloaded constructor that accepts the bound so operators on hostile networks can tune it; existing
   constructors delegate with the default (non-breaking).

2. **Provide a tiny reusable `GrpcServerHardening` helper** that applies the inbound message bound AND an explicit
   inbound metadata (header) bound to any `ServerBuilder<?>` (validating the value), and documents that auth is
   applied separately via the RDR-005 `ServerAuth` unit. This is the single place ALL production server
   construction applies the DoS bounds consistently — the Ghost server, `GrpcBubbleNetworkChannel`, and any
   future Balance server. Also caps concurrent `StreamGhostUpdates` sessions (`MAX_ACTIVE_STREAMS`), since the
   message-size bound does not limit the NUMBER of open streams.

3. **Reuse RDR-005 auth for Ghost; do not invent new auth.** The insecure-by-default posture is unchanged and
   remains RDR-005's decision.

4. **Document the Balance gap rather than fabricate a server.** `BalanceCoordinatorServer` gets a class-level
   note: it has no production host today; any future host MUST apply `GrpcServerHardening` for the size bound and
   an RDR-005 `ServerAuth` for authentication. No production code is added for a server that does not exist.

**Out of scope (deferred, tracked separately):** per-method authorization / RBAC; rate limiting and request
quotas; the `SyncGhosts` response-amplification path (a small `SyncRequest` can ask the server to build a large
`SyncResponse` — the *inbound* bound does not cap *outbound* size) and the matching client-side
`maxInboundMessageSize`; and changing Ghost's insecure-by-default posture (that is RDR-005's accepted decision,
not reopened here). Auth mechanism is RDR-005's.

## Approach

1. `GrpcServerHardening.applyInboundLimit(ServerBuilder<?>, int maxInboundMessageBytes)` — validates `> 0`,
   calls `maxInboundMessageSize`, returns the builder. (item → bead 06ujn)
2. `GhostCommunicationManager`: `DEFAULT_MAX_INBOUND_MESSAGE_BYTES` constant; new ctor param threaded to the
   server builder via the helper; existing ctors delegate with the default. (item → bead 06ujn)
3. `BalanceCoordinatorServer`: class-level Javadoc documenting the no-production-host contract + hardening
   requirement. (item → bead 06ujn)
4. Tests: a real (Netty, port 0) Ghost server built with a small limit rejects an oversized request with
   `RESOURCE_EXHAUSTED`, and accepts an under-limit request; helper validates its argument. (item → bead 06ujn)

## Consequences

- **Positive:** the DoS bound is explicit, tunable, and tested; a single helper prevents future drift; the
  Balance gap is documented so a future server can't silently ship unhardened.
- **Negative / accepted:** the default bound (4 MiB) is unchanged, so this does not by itself *reduce* the
  attack surface versus gRPC's implicit default — it makes it explicit and tunable. Operators wanting a tighter
  bound must set it. Ghost remains insecure-by-default (RDR-005), unchanged here.
- **Neutral:** no new auth surface; per-method authz remains future work.

## Validation

- `GhostMessageSizeLimitTest`: oversized request → `StatusRuntimeException` with `RESOURCE_EXHAUSTED`; under-limit
  request succeeds; helper rejects non-positive limits.
- Stacked review (code-review-expert + substantive-critic) before close.
