# Performance Design

**Version:** 1.0.0
**Date:** 2026-02-18
**Status:** Production-Ready

## Overview

The rendering server is designed for high-throughput, low-latency binary frame delivery to many concurrent clients. Performance optimizations operate at four levels: allocation reduction (ByteBuffer pooling), network efficiency (message batching), latency reduction (push callbacks), and cache effectiveness (two-tier region cache).

## Optimization 1: Message Batching (Bead: Luciferase-r2ky)

### Problem

Each binary frame sent as an individual WebSocket message incurs per-message overhead: system call, network packet framing, Javalin/Jetty thread work. With 10-100 regions per viewport update, this overhead dominates latency.

### Solution

Frames are buffered in a per-client `List<ByteBuffer>` and flushed in batch:

**Flush triggers:**
- **Threshold flush:** Buffer reaches 10 messages
- **Timeout flush:** 50ms elapsed since last flush (polled in streaming cycle)
- **Disconnect flush:** Buffer flushed before client disconnect

```
frame1 → buffer
frame2 → buffer
...
frame10 → FLUSH (threshold) → single sendBinary call with batched data

OR

frame1 → buffer
...50ms elapsed...
→ FLUSH (timeout) → sendBinary
```

### Configuration

| Parameter | Default (Prod) | Default (Test) | Description |
|-----------|----------------|----------------|-------------|
| `messageBatchSize` | 10 | 5 | Messages per batch |
| `batchFlushTimeoutMs` | 50 | 20 | Max wait before flush |

### Future Enhancement

- Make batch size and timeout configurable per client
- Track average batch size and flush trigger distribution (threshold vs. timeout) for tuning

## Optimization 2: ByteBuffer Pooling (Bead: Luciferase-8db0)

### Problem

Encoding a binary frame allocates a `ByteBuffer` per frame. With 10-100 frames per streaming cycle per client, and 100-1000 concurrent clients, this generates significant GC pressure.

### Solution

`ByteBufferPool` maintains size-bucketed pools using `ConcurrentLinkedQueue<ByteBuffer>`:

**Size buckets:** 1KB, 4KB, 16KB, 64KB

**Lifecycle:**
```
1. Streaming cycle requests frame encoding
2. ByteBufferPool.borrow(requiredSize) → returns appropriately-sized buffer
3. Frame encoded into buffer
4. sendBinary(buffer) call queued
5. After send completes → ByteBufferPool.returnBuffer(buffer)
6. Buffer available for next use
```

### Thread Safety

`ConcurrentLinkedQueue` is lock-free. `AtomicInteger` tracks pool size and statistics. No synchronization needed for concurrent borrow/return operations (verified by code review).

### Statistics

Pool exposes `getStats()` returning:
- `borrowCount` — total borrows
- `returnCount` — total returns
- `currentSize` — buffers currently in pool

## Optimization 3: Build Completion Callbacks (Push Model)

### Problem

A polling model (check cache every streaming cycle, 100ms interval) has 0-100ms latency from build completion to frame delivery. For regions built on-demand (cache miss), this delays the initial frame.

### Solution

`RegionBuilder` accepts a callback registered by `AdaptiveRegionManager`. On build completion:

```
RegionBuilder.build() completes
  ↓
AdaptiveRegionManager.onBuildComplete(regionId, builtRegion)
  ↓
RegionStreamer.onRegionBuilt(regionId, builtRegion)
  ↓
For each STREAMING client viewing this region:
  - Backpressure check
  - Encode and deliver immediately (no wait for next cycle)
```

**Result:** Latency = build time + encode time + network RTT (not build time + up-to-100ms polling delay)

## Optimization 4: Two-Tier Region Cache

### Architecture

`RegionCache` maintains two tiers with different eviction policies:

**Pinned tier (ConcurrentHashMap):**
- Regions actively viewed by ≥1 client
- Never evicted while pinned
- `pin(regionId)` called when client viewport includes region
- `unpin(regionId)` called when no client views region

**Unpinned tier (Caffeine):**
- Regions recently built but not currently viewed
- Evicted by TTL (default: 60s) or LRU when memory pressure
- Re-pinned if client viewport re-includes region (avoids rebuild)

### Memory Management

```
Total cache memory = pinned tier (exact) + unpinned tier (bounded)
Unpinned tier bound = min(TTL expiration, maxCacheMemoryBytes)
```

Memory pressure is monitored. When approaching `maxCacheMemoryBytes`, the unpinned tier evicts aggressively (Caffeine weight-based eviction).

### Cache Effectiveness

Key metric: `caffeineHitRate` from `/api/metrics`. A rate above 0.8 indicates the unpinned tier is keeping frequently re-requested regions warm. Below 0.5 suggests TTL is too short for your scene change rate.

### Configuration

| Parameter | Default (Prod) | Default (Test) | Description |
|-----------|----------------|----------------|-------------|
| `regionCacheTtlMs` | 60000 | 5000 | Unpinned region TTL |
| `maxCacheMemoryBytes` | 256 MB | 16 MB | Cache memory limit |

## Optimization 5: Endpoint Response Caching (Bead: rp9u)

REST endpoints `/api/health` and `/api/metrics` are cached with a 1-second TTL using Caffeine. This absorbs frequent Prometheus scrapes (typically every 15s) without the overhead of re-computing metrics on each poll.

Cache is invalidated in `RenderingServer.stop()` to prevent stale data after shutdown.

## Streaming Architecture

### Streaming Cycle

A single background thread runs the streaming cycle at `streamingIntervalMs` (default: 100ms):

```
For each STREAMING client:
  1. Compute viewport diff (added/removed/LOD-changed regions)
  2. For added regions:
     a. Backpressure check (skip if pendingSends >= maxPendingSendsPerClient)
     b. Cache lookup (RegionCache.get)
     c. If cached: borrow buffer, encode, add to batch buffer
     d. If not cached: trigger async build (callback delivers on completion)
  3. For removed regions: unpin from cache (if no other clients viewing)
  4. Timeout flush check (flush if 50ms since last flush)
```

### Backpressure

Per-client `AtomicInteger pendingSends` tracks in-flight frames. When `pendingSends >= maxPendingSendsPerClient`, region delivery is skipped for that client in the current cycle. This prevents memory exhaustion from slow or unresponsive clients without affecting other clients.

Backpressure does not close the connection — the client will catch up in subsequent cycles as sends complete.

### Thread Model

| Component | Threads | Description |
|-----------|---------|-------------|
| Javalin server | Jetty pool (200) | HTTP/WS I/O |
| RegionStreamer | 1 background | Streaming cycle |
| RegionBuilder | `buildPoolSize` (4) | GPU builds |
| EntityStreamConsumer | 1 virtual per upstream | Upstream WebSocket I/O |
| Backfill retry | 1 daemon | Dirty region retries |

## Performance Tuning

### For Low Latency (Interactive Camera)

```java
var streaming = StreamingConfig.defaults()
    .withStreamingIntervalMs(50)    // 50ms cycle instead of 100ms
    .withMaxPendingSendsPerClient(50); // Tighter backpressure
var performance = PerformanceConfig.defaults()
    .withBatchFlushTimeoutMs(20);   // More frequent flushes
```

### For High Throughput (Many Static Clients)

```java
var performance = PerformanceConfig.defaults()
    .withMessageBatchSize(20)        // Larger batches
    .withBatchFlushTimeoutMs(100);   // Longer flush window
var cache = CacheConfig.defaults()
    .withRegionCacheTtlMs(300_000)   // 5-minute TTL
    .withMaxCacheMemoryBytes(1L << 30); // 1 GB cache
```

### For GPU-Constrained Environments

```java
var build = BuildConfig.defaults()
    .withBuildPoolSize(2)            // Fewer concurrent GPU builds
    .withMaxQueueDepth(20);          // Tighter queue
```

## Key Metrics

| Metric | Location | Healthy Range | Action if Outside |
|--------|----------|---------------|-------------------|
| `builder.queueDepth` | `/api/metrics` | < 10 | Reduce `regionLevel` or add GPU |
| `builder.avgBuildTimeMs` | `/api/metrics` | < 100ms | Check GPU utilization |
| `cache.caffeineHitRate` | `/api/metrics` | > 0.8 | Increase TTL or cache size |
| `cache.memoryPressure` | `/api/metrics` | < 0.9 | Increase `maxCacheMemoryBytes` |
| `rateLimiter.rejectionCount` | `/api/metrics` | 0 (or low) | Investigate attack or misconfigured client |
| `builder.failedBuilds` | `/api/metrics` | 0 | Check GPU driver, circuit breaker |

## Known Limitations

1. **Caffeine TTL** uses system time internally — deterministic TTL testing requires `Thread.sleep()` even with Clock injection
2. **Streaming interval** is global, not per-client — all clients are served on the same cycle
3. **Horizontal scaling** not yet implemented — single server bottleneck for very large deployments

## References

- **Architecture Overview:** `ARCHITECTURE.md`
- **Configuration Guide:** `CONFIGURATION.md`
- **API Documentation:** `API.md`

---

**Document Version:** 1.0.0
**Last Updated:** 2026-02-18
**Maintained By:** Performance Team
**Review Cycle:** Quarterly
