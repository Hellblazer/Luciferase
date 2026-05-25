---
title: "Harden Network Deserialization on the VoN SocketServer"
id: RDR-004
type: Security
status: draft
priority: high
author: hal.hildebrand
reviewed-by: pending
created: 2026-05-24
related_issues: [Luciferase-irh, RDR-003]
---

# RDR-004: Harden Network Deserialization on the VoN SocketServer

> Revise during planning; lock at implementation.
> If wrong, abandon code and iterate RDR.

## Problem Statement

The VoN transport reads Java-serialized objects directly off a network socket with **no `ObjectInputFilter`**. `SocketServer.handleClient` (`simulation/src/main/java/com/hellblazer/luciferase/simulation/von/transport/SocketServer.java:134`) constructs `new ObjectInputStream(clientSocket.getInputStream())` and loops on `readObject()`, casting to `TransportVonMessage`. `SocketClient.java:107-109` does the same on the response path.

Unfiltered `ObjectInputStream.readObject()` on **untrusted network input** is the canonical Java deserialization RCE vector: an attacker who can connect to the listening socket can deliver a serialized gadget chain that executes during deserialization, *before* the `(TransportVonMessage)` cast is ever reached. This is materially more dangerous than the file-based deserialization sites already hardened with allow-list filters in Tranche D-1 (PR #88) — those require a malicious file on disk; this requires only network reach to the VoN port.

This is explicitly a **threat-model decision, not a tactical filter add** (the reason it was deferred from D-1): the right fix depends on the trust boundary of the VoN socket and whether Java serialization should remain the wire format at all.

## Context

### Background

The 360-review pass (2026-05-23, T2 `luciferase/360-review-2026-05-23-summary`) flagged this as a CRITICAL finding alongside four file-input `ObjectInputStream` sites. The file sites were hardened in Tranche D-1 (PR #88) with strict `ObjectInputFilter` allow-lists. The network site was deferred to this RDR because:

1. The fix interacts with the VoN transport's trust assumptions (is the socket loopback-only? cluster-internal? exposed?).
2. An allow-list filter is the *minimum*; replacing Java serialization with a schema-based wire format (the project already uses protobuf/gRPC elsewhere) may be the correct long-term answer.
3. `SocketServer` does not currently enforce a bind constraint (separate MEDIUM finding `review-finding-security/network-bind`), compounding the exposure.

### Technical Environment

- **Module**: `simulation` (von/transport package)
- **Key files**:
  - `simulation/src/main/java/.../von/transport/SocketServer.java:134` — unfiltered `ObjectInputStream` on the inbound network path; `readObject()` at :136
  - `simulation/src/main/java/.../von/transport/SocketClient.java:107,109` — unfiltered `ObjectInputStream` on the response path
  - `simulation/src/main/java/.../von/TransportVonMessage.java` — the expected payload record (serializable)
  - `simulation/src/main/java/.../von/transport/SocketTransport.java` — Fireflies-virtual-synchrony ACK wrapper (CLAUDE.md documents the transport semantics)
- **Prior art in-repo**: Tranche D-1 `ObjectInputFilter.Config.createFilter("<type>;java.util.*;java.lang.*;...;!*")` pattern in `render` deserializers — directly reusable shape if an allow-list is the chosen direction.
- **Related security findings** (T2 scratch, 2026-05-23): `review-finding-security/network-bind` (no loopback enforcement), `review-finding-security/grpc-auth` (RDR-005).

## Approach

> To be completed in `/nx:rdr-research` + design. Initial candidate directions:

1. **Threat-model the VoN socket** — determine the actual trust boundary: loopback-only dev, cluster-internal (mutually authenticated peers via Fireflies view), or untrusted. The answer gates how much hardening is warranted.
2. **Option A — ObjectInputFilter allow-list** (minimum): restrict to `TransportVonMessage` + the JDK/vecmath types it transitively carries, mirroring the D-1 file-site pattern. Cheap, reversible, but Java serialization remains the format.
3. **Option B — replace the wire format with protobuf** (structural): the project already depends on gRPC/protobuf; a `TransportVonMessage` proto eliminates Java serialization entirely on this path. Larger change; aligns with RDR-005 (gRPC) and removes the gadget surface rather than filtering it.
4. **Option C — bind/peer-identity constraint**: combine a filter with a loopback or Fireflies-view-authenticated bind so only known peers can connect (overlaps RDR-005's auth model).
5. Decide single direction; sequence against RDR-005 (shared auth/transport concerns).

## Open Questions

- Is the VoN socket ever exposed beyond loopback / a trusted cluster subnet in any deployment?
- Should VoN transport migrate to gRPC entirely (converging with RDR-005), making this moot?
- Does the Fireflies view already give us a peer-identity primitive we can gate `accept()` on?

## Decision

_Pending research + gate._

## Consequences

_Pending._
