# Security Architecture

**Version:** 1.0.0
**Date:** 2026-02-18
**Status:** Production-Ready

## Overview

The rendering server implements defense-in-depth security with five independent layers: TLS transport security, API key authentication, triple-layer rate limiting, DoS protection, and input validation. Each layer operates independently so that a failure in one does not cascade to others.

## Layer 1: Transport Security (TLS/HTTPS)

### Configuration (Beads: jc5f, wwi6)

TLS is required when API key authentication is enabled (enforced at startup):

```java
if (security.apiKey() != null && !security.tlsEnabled()) {
    throw new IllegalArgumentException(
        "TLS must be enabled when using API key authentication"
    );
}
```

**Keystore setup:**

```bash
keytool -genkeypair -alias rendering-server \
    -keyalg RSA -keysize 2048 \
    -validity 365 -keystore keystore.jks \
    -storepass changeit -keypass changeit \
    -dname "CN=render.example.com"
```

**Server configuration:**

```java
var config = RenderingServerConfig.secureDefaults("your-api-key-here");
// Then configure keystore before calling server.start()
```

**Production recommendation:** Use Let's Encrypt certificates issued by a trusted CA, not self-signed keystores.

### Fail-Fast Keystore Validation

On startup, the server validates that the keystore path exists and is readable. A missing or unreadable keystore fails immediately, before binding the listen port. This prevents silent security degradation at runtime.

### WebSocket TLS

Use `wss://` (not `ws://`) for all production clients:

```javascript
const ws = new WebSocket('wss://server:7090/ws/render');
```

## Layer 2: API Key Authentication (Bead: biom)

### Protocol

Bearer token in the HTTP Upgrade request header:

```http
GET /ws/render HTTP/1.1
Host: server:7090
Upgrade: websocket
Authorization: Bearer YOUR_API_KEY_HERE
```

The same header authenticates REST endpoints:

```http
GET /api/health HTTP/1.1
Authorization: Bearer YOUR_API_KEY_HERE
```

### Timing-Attack Prevention

API key comparison uses `MessageDigest.isEqual()` (constant-time) rather than `String.equals()`. This prevents an attacker from inferring correct key bytes by measuring comparison latency.

### Optional Authentication

When no API key is configured (`SecurityConfig.permissive()`), authentication is skipped. This is only appropriate for local development — never in production.

## Layer 3: Rate Limiting

Three independent rate limiters operate at different scope levels:

### 3a. Global HTTP Rate Limiting (Bead: w1tk)

**Scope:** All HTTP requests from a given client IP
**Mechanism:** `RateLimiter` per client IP (token bucket algorithm)
**Default:** Configurable requests/minute
**Response on violation:** `429 Too Many Requests`

```json
{"error": "Too Many Requests"}
```

### 3b. Per-Client WebSocket Rate Limiting (Bead: Luciferase-heam)

**Scope:** JSON messages from a single WebSocket session
**Default:** 100 messages/second
**Response on violation:** ERROR message (connection remains open)

```json
{"type": "ERROR", "message": "Rate limit exceeded"}
```

### 3c. Auth Attempt Rate Limiting (Bead: vyik)

**Scope:** Authentication failures per client host
**Default:** 3 failed attempts → 60-second lockout
**Response on lockout:** Close with code 4003 (Unauthorized)

**Memory management:** Auth limiters are stored in a Caffeine cache with 1-hour idle expiration and a 10,000-entry hard cap. This prevents the slow memory leak that would occur with a plain `ConcurrentHashMap` in long-running deployments.

## Layer 4: DoS Protection

### Message Size Limits (Bead: Luciferase-heam)

**Default:** 64 KB per JSON message
**Counting:** UTF-8 byte-accurate (not Java character count) — Bead: Luciferase-us4t
**Response:** Close with code 4002

```
4002: Message size limit exceeded
```

### Client Connection Limits

**Default:** 1000 concurrent WebSocket clients
**Enforcement:** Atomic synchronized check during `onConnect` — prevents TOCTOU race (Bead: Luciferase-1026)

```
4001: Server full
```

### Build Queue Depth Limits

The `RegionBuilder` enforces a maximum build queue depth. Requests beyond the limit are dropped with backpressure signaling rather than queued indefinitely, preventing memory exhaustion from fast-moving clients.

### Per-Client Backpressure

Each client session tracks `pendingSends` (AtomicInteger). Region delivery is skipped when this exceeds `maxPendingSendsPerClient` (default: 100). Fast clients do not accumulate unbounded in-flight frames.

## Layer 5: Input Validation

### JSON Message Validation (Bead: mppj)

All required fields are null-checked on receipt. Nested fields are validated individually:

```
Missing 'type' field
Missing 'clientId' field
Missing 'viewport' field
Invalid viewport: missing eye.x
Invalid viewport: missing eye.y
... (each coordinate validated)
```

Unknown message types are rejected:

```json
{"type": "ERROR", "message": "Unknown message type: XYZ"}
```

### Safe JSON Serialization (Bead: fr0y)

Server-side JSON responses (ERROR messages, REST responses) use Jackson's `ObjectMapper` with safe defaults. String values are escaped before serialization, preventing JSON injection attacks.

### Sensitive Info Redaction (Bead: 1sa4)

The `/api/info` endpoint redacts sensitive configuration when `redactSensitiveInfo=true` (default in production):

- **Redacted:** Full upstream URIs
- **Exposed:** Only upstream count (`upstreamCount: 3`)

This prevents leaking internal network topology to authenticated-but-untrusted clients.

## Security Configuration Reference

### SecurityConfig Record

| Field | Type | Description |
|-------|------|-------------|
| `apiKey` | `String?` | API key for Bearer token auth (`null` = no auth) |
| `tlsEnabled` | `boolean` | Whether TLS is enabled |
| `keystorePath` | `String?` | Path to JKS keystore file |
| `keystorePassword` | `String?` | Keystore password |
| `keyManagerPassword` | `String?` | Key manager password |
| `globalRateLimitPerMinute` | `int` | Global HTTP rate limit |
| `maxMessagesPerSecond` | `int` | Per-client WebSocket rate limit (default: 100) |
| `maxAuthAttempts` | `int` | Auth failures before lockout (default: 3) |
| `authLockoutSeconds` | `int` | Lockout duration in seconds (default: 60) |
| `redactSensitiveInfo` | `boolean` | Redact upstreams from /api/info |

### Factory Methods

```java
// Production: API key + TLS required
SecurityConfig.secure("your-api-key", true)

// Development: no authentication
SecurityConfig.permissive()
```

## Threat Model

| Threat | Mitigations |
|--------|-------------|
| Eavesdropping | TLS (Layer 1) |
| API key brute force | Auth rate limiting (Layer 3c), constant-time comparison (Layer 2) |
| Message flood (HTTP) | Global rate limit (Layer 3a) |
| Message flood (WS) | Per-client rate limit (Layer 3b) |
| Large message attack | Message size limit (Layer 4) |
| Connection flood | Client limit (Layer 4) |
| Memory exhaustion (builds) | Build queue depth limit (Layer 4) |
| Memory exhaustion (slow clients) | Per-client backpressure (Layer 4) |
| JSON injection | Safe serialization (Layer 5) |
| Internal network exposure | Sensitive info redaction (Layer 5) |
| IP-based auth limiter leak | Caffeine-bounded auth limiter map |

## Security Best Practices

1. **Always use TLS in production** (`wss://`, HTTPS)
2. **Rotate API keys regularly** (recommended: 90-day cycle)
3. **Monitor `rejectionCount`** in `/api/metrics` — sustained rejections indicate attack
4. **Use strong API keys** (≥ 32 bytes of cryptographic randomness)
5. **Do not expose `/api/info` upstream URIs** (`redactSensitiveInfo=true` is default)
6. **Set appropriate `maxMessageSizeBytes`** for your use case; 64 KB may be excessive
7. **Configure `maxClientsPerServer`** based on available server memory and thread capacity

## References

- **Architecture Overview:** `ARCHITECTURE.md`
- **Configuration Guide:** `CONFIGURATION.md`
- **Bead Traceability:** See `ARCHITECTURE.md#bead-traceability`

---

**Document Version:** 1.0.0
**Last Updated:** 2026-02-18
**Maintained By:** Security Team
**Review Cycle:** Quarterly
