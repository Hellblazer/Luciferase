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
    long maxCacheMemoryBytes   // Total cache memory limit
)
```

**Factory methods:**

```java
CacheConfig.defaults()   // 256 MB
CacheConfig.testing()    // 16 MB
```

| Parameter | Production | Test |
|-----------|-----------|------|
| `maxCacheMemoryBytes` | 256 MB | 16 MB |

**Tuning guidance:**
- Increase memory if `memoryPressure` is high and you have available RAM
- Emergency eviction triggers at 90% of limit, targets 75%
- Region cache TTL is in `PerformanceConfig.regionCacheTtlMs`

## BuildConfig

```java
public record BuildConfig(
    int buildPoolSize,                  // RegionBuilder thread pool (concurrent builds)
    int maxBuildDepth,                  // Maximum octree/tetree depth within a region
    int gridResolution,                 // Voxel grid resolution per region (e.g., 64 for 64³)
    int maxQueueDepth,                  // Build queue depth before backpressure
    long circuitBreakerTimeoutMs,       // Circuit open duration
    int circuitBreakerFailureThreshold  // Consecutive failures before circuit opens
)
```

**Factory methods:**

```java
BuildConfig.defaults()   // 1 thread, depth 8, 64³ grid, queue 100, circuit: 3 failures / 60s
BuildConfig.testing()    // 1 thread, depth 4, 16³ grid, queue 50,  circuit: 3 failures / 10s
```

| Parameter | Production | Test |
|-----------|-----------|------|
| `buildPoolSize` | 1 | 1 |
| `maxBuildDepth` | 8 | 4 |
| `gridResolution` | 64 | 16 |
| `maxQueueDepth` | 100 | 50 |
| `circuitBreakerFailureThreshold` | 3 | 3 |
| `circuitBreakerTimeoutMs` | 60000 ms | 10000 ms |

**Tuning guidance:**
- Increase `buildPoolSize` if `builder.queueDepth` is high and CPU has headroom
- Decrease `maxQueueDepth` to apply tighter backpressure to clients
- Decrease `maxBuildDepth` or `gridResolution` if builds are too slow

## StreamingConfig

```java
public record StreamingConfig(
    long streamingIntervalMs,       // Streaming cycle frequency (ms, min 16)
    int maxClientsPerServer,        // Maximum concurrent WebSocket clients
    int maxPendingSendsPerClient,   // Backpressure threshold per client
    float[] lodThresholds,          // Distance thresholds in world units (ascending)
    int maxLodLevel,                // Maximum LOD level (must equal lodThresholds.length)
    long clientTimeoutMs,           // Disconnect inactive clients after this duration
    int maxViewportUpdatesPerSecond,// Throttle viewport update frequency
    boolean rateLimitEnabled,       // Enable per-client rate limiting
    int maxMessagesPerSecond,       // Max WebSocket messages per second per client
    int maxMessageSizeBytes         // Max JSON message size in bytes (DoS protection)
)
```

**Factory methods:**

```java
StreamingConfig.defaults()   // 100ms, 50 clients, 50 pending, LOD [100,300,700], 30s timeout
StreamingConfig.testing()    // 50ms,  10 clients, 20 pending, LOD [50,150,350],  5s timeout
```

| Parameter | Production | Test |
|-----------|-----------|------|
| `streamingIntervalMs` | 100 ms | 50 ms |
| `maxClientsPerServer` | 50 | 10 |
| `maxPendingSendsPerClient` | 50 | 20 |
| `lodThresholds` | [100, 300, 700] | [50, 150, 350] |
| `maxLodLevel` | 3 | 3 |
| `clientTimeoutMs` | 30000 ms | 5000 ms |
| `maxViewportUpdatesPerSecond` | 30 | 60 |
| `rateLimitEnabled` | `true` | `false` |
| `maxMessagesPerSecond` | 100 | 100 |
| `maxMessageSizeBytes` | 65536 (64KB) | 65536 (64KB) |

**Note:** `lodThresholds` are in world coordinates and scale-dependent. Default thresholds
assume a [0, 1024] world. Recalibrate proportionally if world scale changes.
`maxLodLevel` must equal `lodThresholds.length` (validated at construction).

## PerformanceConfig

```java
public record PerformanceConfig(
    long regionCacheTtlMs,         // TTL for unpinned regions in RegionCache (ms)
    long endpointCacheExpireSec,   // REST endpoint cache TTL (seconds)
    int endpointCacheMaxSize,      // Maximum number of cached endpoint responses
    long httpConnectTimeoutSec,    // EntityStreamConsumer connect timeout (seconds)
    int decompressionBufferSize    // Buffer size for GZIP decompression (bytes)
)
```

**Factory methods:**

```java
PerformanceConfig.defaults()   // 30s region TTL, 1s endpoint cache, 8KB decomp buffer
PerformanceConfig.testing()    // 5s region TTL,  1s endpoint cache, 4KB decomp buffer
```

| Parameter | Production | Test | Description |
|-----------|-----------|------|-------------|
| `regionCacheTtlMs` | 30000 ms | 5000 ms | Unpinned region TTL in cache |
| `endpointCacheExpireSec` | 1 s | 1 s | REST endpoint response cache TTL |
| `endpointCacheMaxSize` | 10 | 5 | Max cached endpoint responses |
| `httpConnectTimeoutSec` | 10 s | 5 s | Upstream WebSocket connect timeout |
| `decompressionBufferSize` | 8192 (8KB) | 4096 (4KB) | GZIP decompression buffer |

**Tuning guidance:**
- Increase `regionCacheTtlMs` if `caffeineHitRate` is low and scenes change infrequently
- Increase `decompressionBufferSize` if handling large ESVO payloads (> 64KB before compression)

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

All sub-records are immutable. Customize by supplying values directly to constructors:

```java
var upstreams = List.of(
    new UpstreamConfig(URI.create("wss://sim1.internal:8080/entities"), "bubble-1"),
    new UpstreamConfig(URI.create("wss://sim2.internal:8080/entities"), "bubble-2")
);

var config = new RenderingServerConfig(
    7090,
    upstreams,
    4,                                           // 16^3 = 4096 regions
    new SecurityConfig("secret-api-key", true,
        "/etc/rendering/keystore.jks",
        "keystore-password", "key-password",
        1000, 100, 3, 60, true),
    new CacheConfig(512L * 1024 * 1024),         // 512 MB cache
    new BuildConfig(4, 8, 64, 100, 60_000L, 3), // 4 threads, depth 8, 64^3
    10_000,                                      // Max entities per region
    new StreamingConfig(50, 50, 50,              // 50ms interval, 50 clients
        new float[]{100f, 300f, 700f}, 3,
        30_000L, 30, true, 100, 65536),
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
// Authentication but permissive limits; TLS terminated at load balancer
var stagingStreaming = new StreamingConfig(
    100, 100, 20,                     // 100ms, 100 clients, 20 pending
    new float[]{50f, 150f, 350f}, 3,
    5_000L, 60, false, 100, 65536);
var config = new RenderingServerConfig(
    7090, upstreams, 3,               // Fewer regions for faster builds
    SecurityConfig.secure("staging-key", false),
    new CacheConfig(64L << 20),       // 64 MB
    BuildConfig.testing(),
    1_000,
    stagingStreaming,
    PerformanceConfig.testing()
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
