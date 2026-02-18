# Configuration Guide

**Version:** 1.0.0
**Date:** 2026-02-18
**Status:** Production-Ready

## Overview

`RenderingServerConfig` is an immutable record composed of five sub-records: `SecurityConfig`, `CacheConfig`, `BuildConfig`, `StreamingConfig`, and `PerformanceConfig`. This design makes configuration explicit, type-safe, and testable.

## Quick Start

### Production (Secure)

```java
var config = RenderingServerConfig.secureDefaults("your-api-key-here");
var server = new RenderingServer(config);
server.start();
// Server listening on port 7090 with TLS + API key auth
```

### Development (No Auth)

```java
var config = RenderingServerConfig.defaults();  // @Deprecated - use secureDefaults for production
var server = new RenderingServer(config);
server.start();
```

### Testing (Dynamic Port, Small Parameters)

```java
var config = RenderingServerConfig.testing();
var server = new RenderingServer(config);
server.start();
int port = server.port();  // Dynamic port assignment
```

## Top-Level Config

```java
public record RenderingServerConfig(
    int port,                    // Listen port (0 = dynamic)
    List<UpstreamConfig> upstreams,
    int regionLevel,             // Octree depth (3-6)
    SecurityConfig security,
    CacheConfig cache,
    BuildConfig build,
    int maxEntitiesPerRegion,
    StreamingConfig streaming,
    PerformanceConfig performance
)
```

### Cross-Field Validation

```java
config.validate();  // Called automatically by secureDefaults()
// Throws IllegalArgumentException if API key set but TLS disabled
```

### `regionLevel` Explained

| `regionLevel` | Regions per axis | Total regions | Region size (1024-unit world) |
|--------------|-----------------|---------------|-------------------------------|
| 2 | 4 | 64 | 256 units |
| 3 | 8 | 512 | 128 units |
| **4 (default)** | **16** | **4096** | **64 units** |
| 5 | 32 | 32768 | 32 units |
| 6 | 64 | 262144 | 16 units |

Higher levels = finer spatial granularity, more regions, higher memory and build cost.

### `maxEntitiesPerRegion`

Hard cap on entities per region. When exceeded, new entity updates for that region are dropped. Default: 10,000 (prod), 1,000 (test). Prevents unbounded memory growth in dense scenes.

## SecurityConfig

```java
public record SecurityConfig(
    String apiKey,                   // null = no authentication
    boolean tlsEnabled,
    String keystorePath,             // Path to JKS keystore
    String keystorePassword,
    String keyManagerPassword,
    int globalRateLimitPerMinute,
    int maxMessagesPerSecond,        // Per-client WebSocket rate limit
    int maxAuthAttempts,             // Before lockout (default: 3)
    int authLockoutSeconds,          // Lockout duration (default: 60)
    boolean redactSensitiveInfo      // Redact upstreams in /api/info
)
```

**Factory methods:**

```java
SecurityConfig.secure("api-key", true)    // API key + TLS enabled
SecurityConfig.permissive()               // No auth (dev/test only)
```

| Parameter | Prod (secure) | Test (permissive) |
|-----------|---------------|-------------------|
| `apiKey` | user-provided | `null` |
| `tlsEnabled` | `true` | `false` |
| `maxMessagesPerSecond` | 100 | 1000 |
| `maxAuthAttempts` | 3 | 10 |
| `authLockoutSeconds` | 60 | 5 |
| `redactSensitiveInfo` | `true` | `false` |

## CacheConfig

```java
public record CacheConfig(
    long maxCacheMemoryBytes,  // Total cache memory limit
    long regionCacheTtlMs      // Unpinned region TTL
)
```

**Factory methods:**

```java
CacheConfig.defaults()   // 256 MB, 60s TTL
CacheConfig.testing()    // 16 MB, 5s TTL
```

| Parameter | Production | Test |
|-----------|-----------|------|
| `maxCacheMemoryBytes` | 256 MB | 16 MB |
| `regionCacheTtlMs` | 60000 ms | 5000 ms |

**Tuning guidance:**
- Increase TTL if `caffeineHitRate` is low and scenes don't change frequently
- Increase memory if `memoryPressure` is high and you have available RAM

## BuildConfig

```java
public record BuildConfig(
    int buildPoolSize,       // RegionBuilder thread pool (= concurrent GPU builds)
    int maxQueueDepth,       // Build queue depth before backpressure
    int circuitBreakerThreshold, // Consecutive failures before circuit opens
    long circuitBreakerTimeoutMs // Circuit open duration
)
```

**Factory methods:**

```java
BuildConfig.defaults()   // 4 threads, queue 50, circuit: 5 failures / 60s
BuildConfig.testing()    // 2 threads, queue 10, circuit: 3 failures / 5s
```

| Parameter | Production | Test |
|-----------|-----------|------|
| `buildPoolSize` | 4 | 2 |
| `maxQueueDepth` | 50 | 10 |
| `circuitBreakerThreshold` | 5 | 3 |
| `circuitBreakerTimeoutMs` | 60000 ms | 5000 ms |

**Tuning guidance:**
- Increase `buildPoolSize` if GPU has headroom and `builder.queueDepth` is high
- Decrease `maxQueueDepth` to apply tighter backpressure to clients

## StreamingConfig

```java
public record StreamingConfig(
    long streamingIntervalMs,       // Streaming cycle frequency
    int maxPendingSendsPerClient,   // Backpressure threshold per client
    float nearLodThreshold,         // Distance for highest LOD
    float farLodThreshold           // Distance for lowest LOD
)
```

**Factory methods:**

```java
StreamingConfig.defaults()   // 100ms interval, 100 pending
StreamingConfig.testing()    // 100ms interval, 10 pending
```

| Parameter | Production | Test |
|-----------|-----------|------|
| `streamingIntervalMs` | 100 ms | 100 ms |
| `maxPendingSendsPerClient` | 100 | 10 |
| `nearLodThreshold` | (distance units) | (smaller) |
| `farLodThreshold` | (distance units) | (smaller) |

## PerformanceConfig

```java
public record PerformanceConfig(
    int messageBatchSize,        // Max frames before forced flush
    long batchFlushTimeoutMs,    // Max time before timeout flush
    int maxMessageSizeBytes,     // Max JSON message size (DoS protection)
    int maxClientsPerServer,     // Max concurrent WebSocket clients
    long endpointCacheExpireSec, // REST endpoint cache TTL
    int httpConnectTimeoutSec    // EntityStreamConsumer connect timeout
)
```

**Factory methods:**

```java
PerformanceConfig.defaults()   // Prod: 10 batch, 50ms timeout, 64KB msgs, 1000 clients
PerformanceConfig.testing()    // Test: 5 batch, 20ms timeout, 4KB msgs, 10 clients
```

| Parameter | Production | Test | Description |
|-----------|-----------|------|-------------|
| `messageBatchSize` | 10 | 5 | Max frames per batch |
| `batchFlushTimeoutMs` | 50 ms | 20 ms | Timeout flush interval |
| `maxMessageSizeBytes` | 65536 | 4096 | Max JSON message (UTF-8 bytes) |
| `maxClientsPerServer` | 1000 | 10 | Concurrent WebSocket clients |
| `endpointCacheExpireSec` | 1 s | 1 s | REST endpoint cache TTL |
| `httpConnectTimeoutSec` | 10 s | 5 s | Upstream connect timeout |

## UpstreamConfig

Configures each upstream simulation server that EntityStreamConsumer connects to:

```java
public record UpstreamConfig(
    URI uri,     // WebSocket URI, e.g. ws://simulation:8080/entities
    String label // Human-readable label, used to namespace entity IDs
)
```

**Example:**

```java
var upstreams = List.of(
    new UpstreamConfig(URI.create("ws://sim1:8080/entities"), "bubble-1"),
    new UpstreamConfig(URI.create("ws://sim2:8080/entities"), "bubble-2")
);
var config = new RenderingServerConfig(7090, upstreams, 4, ...);
```

Entity IDs from `bubble-1` are namespaced as `bubble-1:entityId`, preventing collisions across upstreams.

## Complete Configuration Example (Production)

```java
var upstreams = List.of(
    new UpstreamConfig(URI.create("wss://sim1.internal:8080/entities"), "bubble-1"),
    new UpstreamConfig(URI.create("wss://sim2.internal:8080/entities"), "bubble-2")
);

var config = new RenderingServerConfig(
    7090,
    upstreams,
    4,                                  // 16^3 regions
    SecurityConfig.secure("secret-api-key", true)
        .withKeystorePath("/etc/rendering/keystore.jks")
        .withKeystorePassword("keystore-password")
        .withKeyManagerPassword("key-password"),
    CacheConfig.defaults()
        .withMaxCacheMemoryBytes(512L * 1024 * 1024),  // 512 MB
    BuildConfig.defaults()
        .withBuildPoolSize(8),          // 8 concurrent GPU builds
    10_000,                             // Max entities per region
    StreamingConfig.defaults()
        .withStreamingIntervalMs(50),   // 50ms for interactive clients
    PerformanceConfig.defaults()
);
config.validate();

var server = new RenderingServer(config);
server.start();
```

## Environment-Specific Profiles

### Local Development

```java
// No authentication, dynamic port, verbose logging
var config = RenderingServerConfig.testing();
```

### Staging

```java
// Authentication but permissive limits
var config = new RenderingServerConfig(
    7090, upstreams, 3,  // Fewer regions for faster builds
    SecurityConfig.secure("staging-key", false),  // TLS terminated at load balancer
    CacheConfig.testing().withMaxCacheMemoryBytes(64L << 20),  // 64 MB
    BuildConfig.testing().withBuildPoolSize(2),
    1_000,
    StreamingConfig.testing().withMaxPendingSendsPerClient(20),
    PerformanceConfig.testing().withMaxClientsPerServer(100)
);
```

### Production

```java
var config = RenderingServerConfig.secureDefaults("prod-api-key");
// Customize from defaults as needed
```

## References

- **Architecture Overview:** `ARCHITECTURE.md`
- **Security Architecture:** `SECURITY.md`
- **Performance Design:** `PERFORMANCE.md`
- **API Documentation:** `API.md`

---

**Document Version:** 1.0.0
**Last Updated:** 2026-02-18
**Maintained By:** Platform Team
**Review Cycle:** Quarterly
