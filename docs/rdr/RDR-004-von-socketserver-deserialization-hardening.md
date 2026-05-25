---
title: "Harden Network Deserialization on the VoN SocketServer"
id: RDR-004
type: Security
status: accepted
priority: high
author: hal.hildebrand
reviewed-by: self
created: 2026-05-24
accepted_date: 2026-05-25
related_issues: [Luciferase-irh, RDR-003, Luciferase-ah3]
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

> Candidate directions below; resolved by research (see [Research Findings](#research-findings)) into the layered recommendation that follows.

1. **Threat-model the VoN socket** — determine the actual trust boundary: loopback-only dev, cluster-internal (mutually authenticated peers via Fireflies view), or untrusted. The answer gates how much hardening is warranted.
2. **Option A — ObjectInputFilter allow-list** (minimum): restrict to `TransportVonMessage` + the JDK/vecmath types it transitively carries, mirroring the D-1 file-site pattern. Cheap, reversible, but Java serialization remains the format.
3. **Option B — replace the wire format with protobuf** (structural): the project already depends on gRPC/protobuf; a `TransportVonMessage` proto eliminates Java serialization entirely on this path. Larger change; aligns with RDR-005 (gRPC) and removes the gadget surface rather than filtering it.
4. **Option C — bind/peer-identity constraint**: combine a filter with a loopback or Fireflies-view-authenticated bind so only known peers can connect (overlaps RDR-005's auth model).
5. Decide single direction; sequence against RDR-005 (shared auth/transport concerns).

### Recommended direction (pending gate)

Research showed the three options are not mutually exclusive — they are **layers on different time horizons**:

- **Now — Direction A, unconditionally (defense-in-depth).** Add an `ObjectInputFilter` allow-list at the inbound site (`SocketServer.java:134`) and the response site (`SocketClient.java:107-109`), reusing the D-1 pattern but **narrowed to the concrete types actually on the wire** — do *not* carry the broad `java.util.*` wildcard, which admits gadget-bearing collections (`PriorityQueue`, `TreeMap`, `LinkedList` — the ysoserial surface). See the corrected filter string in [Research Findings](#research-findings) §3, and ship a unit test asserting a `PriorityQueue` payload is **rejected**.
  - **Bind hardening — precise enforcement points (both required, gating independently):** (1) In `SocketServer.start()`, immediately after `InetAddress.getByName(bindAddress.hostname())` (`:97`) and **before** `new ServerSocket(...)` (`:98`), assert `addr.isLoopbackAddress()` and throw `IllegalArgumentException` otherwise — `SocketServer` currently performs *zero* loopback check on the resolved address, so direct instantiation is ungated. (2) Replace the string-equality `isLoopback()` in `SocketConnectionManager.java:186` with `InetAddress.getByName(hostname).isLoopbackAddress()` (with DNS-resolution exception handling) so `"127.0.0.2"` or an off-loopback name no longer passes. Cheap, reversible, lands this RDR.
- **Reject Direction C as the primary fix.** The Fireflies view exposes no per-peer cryptographic identity wired into the transport (only a view-epoch `Digest`); a bespoke socket→member authorization gate is high-cost net-new integration and duplicates RDR-005's auth model. The peer-identity/auth question belongs to **RDR-005**, not a one-off gate here.
- **Direction B is the structural endgame, sequenced with RDR-005, and a hard gate on "Inc 7+".** `TransportVonMessage` is a flat struct that maps trivially to protobuf, the `grpc` module already covers the overlapping ghost types, and transport latency is dominated by the 300 ms Fireflies view-stability ACK (protobuf overhead is irrelevant). Replacing Java serialization eliminates the gadget surface rather than filtering it. **Critical constraint:** B (or at minimum the Direction-A filter, retained) MUST land before the planned "Inc 7+" work removes the loopback restriction — that is the moment the latent vuln becomes network-reachable.
  - **Enforcement artifact (not just a doc note):** this gate is tracked by bead **`Luciferase-ah3`**, which BLOCKS the Inc 7+ loopback-removal work. Reference that bead from `SocketConnectionManager.isLoopback()` and consider a CI-enforced `@Disabled` guard test so the loopback restriction cannot be silently deleted under development pressure. (The `ProcessAddress.java:27-29` javadoc alone is insufficient.)

## Research Findings

> Code investigation 2026-05-25 (`codebase-deep-analyzer`). Full detail in T2 `luciferase_rdr/004-research-1`.

1. **Trust boundary — loopback-only today, but weakly and temporarily.** Bind is `new ServerSocket(port, 50, addr)` at `SocketServer.java:97-98`; loopback is enforced only in `SocketConnectionManager.java:95-99` via **string equality** (`=="127.0.0.1"/"::1"/"localhost"`, `:185-186`) — not `InetAddress.isLoopbackAddress()`, so `127.0.0.2` or an off-loopback DNS name passes. `SocketServer` has no bind guard of its own. `ProcessAddress.java:27-29`: "In Inc 6, only localhost supported. In Inc 7+, remote hosts will be allowed." → the RCE surface is **latent now, network-reachable once Inc 7+ ships.**
2. **Fireflies gives no usable peer identity.** `FirefliesMembershipView.getCurrentViewId()` (`:83-85`) returns a view-epoch `Digest` (proves a view exists, not who is connecting). The `accept()` loop (`SocketServer.java:107-109`) has no auth gate. Delos `Member` carries a certificate (Delos uses mTLS internally) but nothing maps an inbound socket to a `Member`. Direction C requires net-new integration.
3. **D-1 filter pattern is reusable but must be narrowed for the network path.** Four sites from PR #88: `render` `ESVODeserializer.java:81-84`, `ESVTDeserializer.java:282-285`, `SparseVoxelIOUtils.java:208-211` (parameterized), `portal` `CollisionEventRecorder.java:285-291`, all using the broad `…;java.util.*;java.lang.*;…;!*` shape. **On an untrusted *network* socket that broad shape is a residual gadget risk** — `java.util.*` admits `PriorityQueue`/`TreeMap`/`LinkedList`, which appear in published gadget chains. The actual wire payload is records of `String`/`float`/`long`/`Long` plus `List<TransportGhostData>`/`List<TransportNeighborInfo>` (concrete type `java.util.ArrayList`); there is **no `javax.vecmath` type on the wire** (the `Point3D` in `TransportNeighborInfo` is conversion-only, never serialized), so that token is dropped. Corrected VoN filter: `createFilter("com.hellblazer.luciferase.simulation.von.TransportVonMessage;com.hellblazer.luciferase.simulation.von.TransportGhostData;com.hellblazer.luciferase.simulation.von.TransportNeighborInfo;java.util.ArrayList;java.util.Collections$UnmodifiableList;java.util.Arrays$ArrayList;java.lang.*;java.time.*;java.math.*;!*")` — with a test that a `PriorityQueue` is rejected.
4. **protobuf migration is feasible.** `TransportVonMessage.java:48-63` is flat (String ids, decomposed `float posX/Y/Z`, `long timestamp`, `Long bucket`, `List<TransportGhostData>`, `List<TransportNeighborInfo>`) — no `Object` fields, no polymorphism. `grpc/.../lucien/ghost.proto` already defines `Point3f`/`EntityBounds`/`SpatialKey`/`GhostElement`/`GhostBatch`, overlapping the GhostSync payload. Latency floor is the 300 ms Fireflies ACK (`SocketTransport.java:198-199`); protobuf cost is sub-µs.
5. **Bind constraint = the same loopback finding.** Confirms the separate MEDIUM `network-bind` finding: no interface restriction enforced in `SocketServer`, no security TODO present.

## Open Questions

- ~~Is the VoN socket ever exposed beyond loopback / a trusted cluster subnet in any deployment?~~ **Resolved:** Not today (loopback-only, `SocketConnectionManager.java:95-99`), but `ProcessAddress.java:27-29` schedules remote-host exposure for "Inc 7+". The loopback guard is a bypassable string check, not enforced in `SocketServer`.
- ~~Should VoN transport migrate to gRPC entirely (converging with RDR-005), making this moot?~~ **Resolved (recommended):** Yes, as the structural endgame (Direction B) — `TransportVonMessage` is flat and protobuf-mappable, `grpc` already covers the overlapping ghost types, and latency is dominated by the Fireflies ACK. Sequenced with RDR-005, gated on Inc 7+.
- ~~Does the Fireflies view already give us a peer-identity primitive we can gate `accept()` on?~~ **Resolved:** No — only a view-epoch `Digest`, no per-peer cryptographic identity wired into the transport. A peer-identity gate is net-new work that belongs to RDR-005's auth model.

## Decision

Accepted 2026-05-25 (gate PASSED, self-reviewed). The layered direction in [Recommended direction](#recommended-direction-pending-gate) is locked:

1. **Direction A now (defense-in-depth), unconditionally.** A narrowed `ObjectInputFilter` allow-list on `SocketServer.java:134` and `SocketClient.java:107-109` (concrete wire types only — no `java.util.*` wildcard, no `javax.vecmath`), with a `PriorityQueue`-rejection test; plus bind hardening at both enforcement points (`SocketServer.start()` `isLoopbackAddress()` guard throwing `IllegalArgumentException`, and `SocketConnectionManager` switched to `InetAddress.getByName(host).isLoopbackAddress()`).
2. **Direction B (protobuf wire format) is the structural endgame**, sequenced with RDR-005 and converging on one mTLS + cryptographically-verified-identity model — **hard-gated on "Inc 7+" via bead `Luciferase-ah3`**.
3. **Direction C (bespoke Fireflies peer-identity gate) is rejected** as the primary fix; the peer-identity/auth question lives in RDR-005.

Implementation is sequenced per T2 `luciferase_rdr/tranche-d-research-synthesis-2026-05-25` (Direction A is independent and immediate; Direction B pairs with RDR-005 and gates Inc 7+).

## Consequences

- **Positive:** closes the network-deserialization RCE surface immediately (A); establishes the Inc 7+ enforcement gate so the latent vuln cannot silently become reachable; sets up a single peer-identity model across the VoN data path and the gRPC control plane (B + RDR-005).
- **Cost / risk:** the narrowed allow-list must track any future change to `TransportVonMessage`'s field types (a too-narrow filter breaks legit traffic — covered by tests); Direction B is a larger migration whose timing is bound to RDR-005 and the Inc 7+ milestone.
- **Follow-on:** `Luciferase-ah3` blocks Inc 7+ loopback removal until A (retained) or B is in place.
