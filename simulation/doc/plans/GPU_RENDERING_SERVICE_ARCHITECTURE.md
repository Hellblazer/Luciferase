# GPU-Accelerated ESVO/ESVT Rendering Service -- Detailed Architecture

**Date**: 2026-02-13
**Status**: ✅ APPROVED WITH CRITICAL FIXES APPLIED (Quality Score: 82/100)
**Author**: java-architect-planner
**Auditor**: plan-auditor
**Predecessor**: `simulation/doc/plans/2026-02-13-gpu-rendering-service-design.md`
**Audit Report**: `simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE_AUDIT.md`
**Successor**: java-developer (Phase 1 implementation)

**Critical Fixes Applied**:
- **C1**: GPU build queue backpressure (PriorityBlockingQueue, MAX_QUEUE_SIZE=1000, eviction)
- **C2**: EntityStreamConsumer circuit breaker (max 10 reconnect attempts, 5 min timeout)
- **C3**: Region boundary epsilon tolerance (fixes float precision at boundaries)

---

## 1. Executive Summary

This document specifies the detailed Java architecture for a GPU-accelerated rendering
service that sits between existing simulation WebSocket servers and browser clients.
The service consumes raw entity position streams from `EntityVisualizationServer` (port
7080) and `MultiBubbleVisualizationServer` (port 7081), builds compact ESVO/ESVT voxel
structures using GPU acceleration, and streams region-based voxel data to clients via a
new WebSocket endpoint (port 7090).

**Key Objectives:**
- Reuse proven GPU/render infrastructure from the portal and render modules
- Maintain backward compatibility with existing entity streaming clients
- Enable independent scaling of the rendering tier
- Support progressive client rendering (WebGPU > WebGL > CPU fallback)
- Deterministic testing via Clock injection (project standard)

---

## 2. Architectural Overview

### 2.1 System Context

```
 Simulation Layer (unchanged)
 +-------------------------------+     +----------------------------------+
 | EntityVisualizationServer     |     | MultiBubbleVisualizationServer   |
 | port 7080                     |     | port 7081                        |
 | ws://.../ws/entities           |     | ws://.../ws/entities              |
 +----------|--------------------+     +----------|------------------------+
            |  JSON entity frames                 |  JSON entity frames
            +------------------+------------------+
                               |
                               v
 Rendering Layer (NEW)
 +-------------------------------------------------------------+
 | RenderingServer  (port 7090)                                  |
 |                                                               |
 |  EntityStreamConsumer ------> AdaptiveRegionManager           |
 |  (upstream WS client)         (region octree, dirty tracking) |
 |                                      |                        |
 |                                      v                        |
 |                               GpuESVOBuilder                  |
 |                               (GPU/CPU builds per region)     |
 |                                      |                        |
 |                                      v                        |
 |                               RegionCache                     |
 |                               (LRU, multi-LOD)                |
 |                                      |                        |
 |  ViewportTracker <--- client ------->|                        |
 |  (frustum cull, LOD)                 |                        |
 |                                      v                        |
 |                               RegionStreamer                  |
 |                               (binary WS frames)              |
 +---------|------|------|---------------------------------------+
           |      |      |
           v      v      v
      Client A  Client B  Client C
      (WebGPU)  (WebGL)   (CPU)
```

### 2.2 Module Placement

New code resides in the **simulation** module under:
```
com.hellblazer.luciferase.simulation.viz.render
```

**Rationale:** The simulation module already depends on portal (which depends on render
and lucien). This gives full access to GpuService, RenderService, ESVTBuilder,
OctreeBuilder, ESVTOpenCLRenderer, and the entire spatial indexing stack without
introducing new inter-module dependencies.

### 2.3 Dependency Chain

```
common <- lucien <- render <- portal <- simulation
                                          ^
                                          | (new code here)
```

No new module is needed. No circular dependencies are introduced.

---

## 3. Component Design

### 3.1 Core Data Types

All data types use Java records for immutability and clarity.

```java
package com.hellblazer.luciferase.simulation.viz.render;

// ---- Region Identification ----

/**
 * Identifies a spatial region by its Morton code at a given octree level.
 * The Morton code encodes the 3D position as a space-filling curve index.
 */
record RegionId(long mortonCode, int level) implements Comparable<RegionId> {
    @Override
    public int compareTo(RegionId other) {
        var cmp = Integer.compare(this.level, other.level);
        return cmp != 0 ? cmp : Long.compare(this.mortonCode, other.mortonCode);
    }
}

// ---- Region Bounds ----

record RegionBounds(float minX, float minY, float minZ,
                    float maxX, float maxY, float maxZ) {
    float centerX() { return (minX + maxX) * 0.5f; }
    float centerY() { return (minY + maxY) * 0.5f; }
    float centerZ() { return (minZ + maxZ) * 0.5f; }
    float size()    { return maxX - minX; }

    boolean contains(float x, float y, float z) {
        return x >= minX && x < maxX
            && y >= minY && y < maxY
            && z >= minZ && z < maxZ;
    }

    boolean intersectsFrustum(Frustum frustum) {
        // Delegate to lucien's existing AABB-frustum intersection
        return frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

// ---- Client Viewport ----

record ClientViewport(
    float posX, float posY, float posZ,
    float dirX, float dirY, float dirZ,
    float upX, float upY, float upZ,
    float fov, float nearPlane, float farPlane
) {}

// ---- Upstream Configuration ----

record UpstreamConfig(java.net.URI uri, String label) {}

// ---- Server Configuration ----

record RenderingServerConfig(
    int port,                        // 0 for dynamic (testing)
    List<UpstreamConfig> upstreams,  // sim server URIs
    int regionLevel,                 // octree depth for region subdivision (3-6)
    int gridResolution,              // voxel grid resolution per region (32-128)
    int maxBuildDepth,               // max ESVO/ESVT tree depth within a region
    long maxCacheMemoryBytes,        // region cache memory limit
    long regionTtlMs,                // TTL for invisible cached regions
    boolean gpuEnabled,              // attempt GPU acceleration
    int gpuPoolSize,                 // number of concurrent GPU build slots
    SparseStructureType defaultStructureType  // ESVO or ESVT
) {
    static RenderingServerConfig defaults() {
        return new RenderingServerConfig(
            7090,
            List.of(),
            4,        // 2^4 = 16 regions per axis = 4096 regions total
            64,       // 64^3 voxel resolution per region
            8,        // max 8 levels deep within each region
            256 * 1024 * 1024L,  // 256 MB cache
            30_000L,  // 30 second TTL
            true,     // try GPU
            1,        // 1 GPU build slot
            SparseStructureType.ESVO
        );
    }
}

enum SparseStructureType { ESVO, ESVT }
```

### 3.2 RenderingServer (Main Class)

**Responsibility:** Lifecycle management, Javalin setup, component wiring.

```java
package com.hellblazer.luciferase.simulation.viz.render;

/**
 * GPU-accelerated ESVO/ESVT rendering server.
 * Consumes entity streams from simulation servers, builds compact voxel
 * structures, and streams them to browser clients per-region.
 *
 * <p>Port 7090 by default. Use port 0 for dynamic assignment in tests.
 *
 * <p>Endpoints:
 * - GET  /api/health        - Health check with component status
 * - GET  /api/info          - Server capabilities and statistics
 * - GET  /api/regions       - List active regions with metadata
 * - WS   /ws/render         - Region streaming WebSocket
 */
public class RenderingServer implements AutoCloseable {

    private final RenderingServerConfig config;
    private final Javalin app;
    private final EntityStreamConsumer entityConsumer;
    private final AdaptiveRegionManager regionManager;
    private final GpuESVOBuilder gpuBuilder;
    private final RegionCache regionCache;
    private final RegionStreamer regionStreamer;
    private volatile Clock clock = Clock.system();

    public RenderingServer(RenderingServerConfig config) { ... }

    // -- Lifecycle --
    public void start() { ... }   // Start Javalin, connect upstreams
    public void stop()  { ... }   // Graceful shutdown
    @Override public void close() { stop(); }

    // -- Testing --
    public void setClock(Clock clock) { ... }
    public int port()   { ... }     // Actual port after start
    public Javalin app() { ... }    // For test access
}
```

**Javalin Configuration:**
- Static files from `/web/render` (client JS library)
- JSON default content type
- WebSocket at `/ws/render` wired to RegionStreamer
- REST endpoints for health, info, region metadata

### 3.3 EntityStreamConsumer

**Responsibility:** Connect as WebSocket client to upstream simulation servers,
parse entity position JSON, and feed updates to the region manager.

```java
/**
 * Consumes entity position streams from upstream simulation servers.
 *
 * <p>Connects as a WebSocket CLIENT using java.net.http.HttpClient.
 * Handles reconnection with exponential backoff.
 * Parses the JSON format produced by EntityVisualizationServer and
 * MultiBubbleVisualizationServer.
 *
 * <p>Thread model: One virtual thread per upstream connection.
 * Entity updates are forwarded to AdaptiveRegionManager on the
 * consuming thread.
 */
public class EntityStreamConsumer implements AutoCloseable {

    // Upstream connections managed as virtual threads
    private final List<UpstreamConfig> upstreams;
    private final AdaptiveRegionManager regionManager;
    private final ConcurrentHashMap<URI, UpstreamState> connections;
    private final ExecutorService virtualThreadPool;  // newVirtualThreadPerTaskExecutor

    // -- Connection lifecycle --
    void connect(UpstreamConfig upstream) { ... }
    void disconnect(URI upstream) { ... }
    void reconnectWithBackoff(URI upstream) { ... }

    // -- Message parsing --
    // Parses: {"entities":[{"id":"e1","x":1.0,"y":2.0,"z":3.0,"type":"PREY"},...]}
    void onMessage(URI source, String json) { ... }

    // State per upstream
    private record UpstreamState(
        URI uri,
        String label,
        java.net.http.WebSocket webSocket,
        AtomicBoolean connected,
        AtomicInteger reconnectAttempts,
        AtomicLong lastAttemptMs,        // C2: Track last reconnection attempt
        AtomicBoolean circuitBreakerOpen  // C2: Circuit breaker state
    ) {}

    // C2: Reconnection limits to prevent resource exhaustion
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long CIRCUIT_BREAKER_TIMEOUT_MS = 300_000; // 5 minutes
    private static final long MAX_BACKOFF_MS = 60_000; // Cap backoff at 1 minute
}

/**
 * CRITICAL FIX C2: Reconnection with circuit breaker.
 *
 * <p>Prevents unbounded reconnection attempts when upstream is down for
 * extended periods. After MAX_RECONNECT_ATTEMPTS, enters circuit breaker
 * state and only retries after CIRCUIT_BREAKER_TIMEOUT_MS.
 *
 * <p>See: simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE_AUDIT.md §C2
 */
void reconnectWithBackoff(URI upstream) {
    var state = connections.get(upstream);

    // Check circuit breaker
    if (state.circuitBreakerOpen.get()) {
        long timeSinceLastAttempt = clock.currentTimeMillis() - state.lastAttemptMs.get();
        if (timeSinceLastAttempt < CIRCUIT_BREAKER_TIMEOUT_MS) {
            log.debug("Circuit breaker open for {}, skipping reconnect", upstream);
            return;
        } else {
            log.info("Circuit breaker timeout expired for {}, attempting reconnect", upstream);
            state.circuitBreakerOpen.set(false);
            state.reconnectAttempts.set(0);
        }
    }

    int attempts = state.reconnectAttempts.incrementAndGet();
    if (attempts > MAX_RECONNECT_ATTEMPTS) {
        log.error("Max reconnection attempts ({}) reached for {}, entering circuit breaker",
                  MAX_RECONNECT_ATTEMPTS, upstream);
        state.circuitBreakerOpen.set(true);
        state.lastAttemptMs.set(clock.currentTimeMillis());
        scheduleCircuitBreakerCheck(upstream);
        return;
    }

    // Exponential backoff with cap
    long backoffMs = Math.min((1L << attempts) * 1000, MAX_BACKOFF_MS);
    state.lastAttemptMs.set(clock.currentTimeMillis());

    virtualThreadPool.submit(() -> {
        try {
            Thread.sleep(backoffMs);
            connect(upstreams.stream()
                .filter(u -> u.uri().equals(upstream))
                .findFirst()
                .orElseThrow());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
}
```

**Key Design Decisions:**
- Uses `java.net.http.HttpClient` WebSocket client (Java 11+, stable in Java 25)
- Virtual threads for connection management (Java 21+ feature)
- Parses the exact JSON format already produced by existing servers
- No upstream protocol changes required

### 3.4 AdaptiveRegionManager

**Responsibility:** Maintains a spatial octree of regions, receives entity updates,
tracks which regions are dirty, and schedules GPU builds.

```java
/**
 * Manages the spatial region grid and tracks entity-to-region mapping.
 *
 * <p>The world is divided into a regular octree grid at a configurable
 * level (e.g., level 4 = 16x16x16 = 4096 regions). Each region tracks
 * the entity positions it contains and a dirty flag.
 *
 * <p>When entities update, affected regions are marked dirty. The manager
 * schedules GPU builds for dirty regions that are currently visible to
 * at least one client.
 *
 * <p>Thread-safe: entity updates and visibility queries may occur concurrently.
 */
public class AdaptiveRegionManager {

    private final int regionLevel;
    private final float worldMin;
    private final float worldMax;
    private final float regionSize;
    private final int regionsPerAxis;

    // Region state: entity positions, dirty flag, build version
    private final ConcurrentHashMap<RegionId, RegionState> regions;

    // Entity-to-region reverse index for efficient updates
    private final ConcurrentHashMap<String, RegionId> entityRegionMap;

    // Build scheduling
    private final GpuESVOBuilder builder;
    private final RegionCache cache;

    // -- Entity updates from EntityStreamConsumer --
    void updateEntity(String entityId, float x, float y, float z, String type) { ... }
    void removeEntity(String entityId) { ... }
    void bulkUpdate(List<EntityPosition> positions) { ... }

    // -- Region queries --
    RegionId regionForPosition(float x, float y, float z) { ... }
    RegionBounds boundsForRegion(RegionId region) { ... }
    Set<RegionId> dirtyRegions() { ... }

    // -- Build scheduling --
    void scheduleBuild(RegionId region, int lodLevel) { ... }
    void scheduleVisibleBuilds(Set<RegionId> visibleRegions) { ... }

    // Internal state per region
    static class RegionState {
        final RegionId id;
        final CopyOnWriteArrayList<EntityPosition> entities;  // thread-safe iteration
        final AtomicBoolean dirty;
        final AtomicLong buildVersion;
        volatile long lastModifiedMs;
    }

    record EntityPosition(String id, float x, float y, float z, String type) {}
}
```

**Region Coordinate Mapping (C3: With epsilon tolerance to prevent boundary precision loss):**
```java
// C3 FIX: Add epsilon to prevent floating-point precision errors at boundaries
// Example: Entity at x=127.99999 correctly maps to region 2 (128-191), not region 1
private static final float BOUNDARY_EPSILON = 1e-5f;  // Relative to regionSize

RegionId regionForPosition(float x, float y, float z) {
    float epsilon = regionSize * BOUNDARY_EPSILON;

    int rx = (int) ((x - worldMin + epsilon) / regionSize);
    int ry = (int) ((y - worldMin + epsilon) / regionSize);
    int rz = (int) ((z - worldMin + epsilon) / regionSize);

    // Clamp to valid range [0, regionsPerAxis-1] for entities outside world bounds
    rx = Math.max(0, Math.min(regionsPerAxis - 1, rx));
    ry = Math.max(0, Math.min(regionsPerAxis - 1, ry));
    rz = Math.max(0, Math.min(regionsPerAxis - 1, rz));

    long mortonCode = MortonKey.encode(rx, ry, rz);
    return new RegionId(mortonCode, regionLevel);
}
```

This reuses the existing `MortonKey` encoding from lucien for consistent spatial indexing.
**See**: simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE_AUDIT.md §C3 for detailed precision analysis.

### 3.5 GpuESVOBuilder

**Responsibility:** Build ESVO/ESVT structures from entity positions within a region,
using GPU acceleration when available, falling back to CPU.

```java
/**
 * Builds ESVO/ESVT sparse voxel structures for regions.
 *
 * <p>Reuses the proven building pipeline from portal's RenderService:
 * entity positions -> voxel coordinates -> ESVTBuilder/OctreeBuilder.
 *
 * <p>GPU acceleration uses ESVTOpenCLRenderer for data upload when
 * available; the CPU path uses ESVOCPUBuilder.
 *
 * <p>Thread model: Fixed-size pool of build workers. Each worker has
 * exclusive access to one GPU context (if GPU enabled).
 */
public class GpuESVOBuilder implements AutoCloseable {

    private final SparseStructureType structureType;
    private final int gridResolution;
    private final int maxBuildDepth;
    private final boolean gpuEnabled;

    // Build thread pool
    private final ExecutorService buildPool;

    // C1 FIX: Priority-based build queue with backpressure
    private final PriorityBlockingQueue<BuildRequest> buildQueue;
    private final AtomicInteger queueSize;
    private static final int MAX_QUEUE_SIZE = 1000;

    // Pending builds
    private final ConcurrentHashMap<RegionId, CompletableFuture<BuiltRegion>> pendingBuilds;

    // C1: Build request with priority (visible regions first)
    record BuildRequest(
        RegionId regionId,
        List<EntityPosition> positions,
        RegionBounds bounds,
        int lodLevel,
        boolean visible,  // C1: Track visibility for prioritization
        long timestamp
    ) implements Comparable<BuildRequest> {
        @Override
        public int compareTo(BuildRequest other) {
            // Priority: visible > invisible, then oldest first
            if (this.visible != other.visible) return this.visible ? -1 : 1;
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    // -- Public API --
    CompletableFuture<BuiltRegion> build(RegionId regionId,
                                          List<EntityPosition> positions,
                                          RegionBounds bounds,
                                          int lodLevel,
                                          boolean visible) {  // C1: Added visibility parameter
        // C1: Backpressure - evict lowest priority build if queue full
        if (queueSize.get() >= MAX_QUEUE_SIZE) {
            var evicted = buildQueue.poll();  // Remove least important build
            if (evicted != null) {
                log.warn("Build queue full ({} builds), evicting region {}", MAX_QUEUE_SIZE, evicted.regionId);
                pendingBuilds.get(evicted.regionId).completeExceptionally(
                    new BuildQueueFullException("Build queue saturated, evicted low-priority build")
                );
                pendingBuilds.remove(evicted.regionId);
            }
        }

        var request = new BuildRequest(regionId, positions, bounds, lodLevel, visible,
                                       clock.currentTimeMillis());
        buildQueue.offer(request);
        queueSize.incrementAndGet();

        var future = new CompletableFuture<BuiltRegion>();
        pendingBuilds.put(regionId, future);
        return future;
    }

    boolean isBuildPending(RegionId regionId) { ... }
    void cancelBuild(RegionId regionId) { ... }
    int queueDepth() { return queueSize.get(); }  // C1: Expose for monitoring

    // -- Build pipeline (internal) --
    private BuiltRegion buildESVO(RegionId regionId, List<EntityPosition> positions,
                                   RegionBounds bounds, int lodLevel) {
        // 1. Convert world positions to region-local [0,1] coordinates
        // 2. Quantize to voxel grid: position * gridResolution -> Point3i
        // 3. Call OctreeBuilder.buildFromVoxels(voxels, maxBuildDepth)
        // 4. Serialize using ESVOCompressedSerializer pattern (to byte[])
        // 5. Return BuiltRegion with serialized data
    }

    private BuiltRegion buildESVT(RegionId regionId, List<EntityPosition> positions,
                                   RegionBounds bounds, int lodLevel) {
        // 1. Convert world positions to region-local [0,1] coordinates
        // 2. Quantize to voxel grid
        // 3. Call ESVTBuilder.buildFromVoxels(voxels, maxBuildDepth, gridResolution)
        // 4. Serialize to byte[]
        // 5. Return BuiltRegion
    }

    record BuiltRegion(
        RegionId regionId,
        SparseStructureType type,
        byte[] serializedData,
        int nodeCount,
        int leafCount,
        int lodLevel,
        long buildTimeNs,
        long buildVersion
    ) {}
}
```

**Code Reuse from Portal:**
The build pipeline directly instantiates the same builders used by portal's
`RenderService`:
- `OctreeBuilder` from `com.hellblazer.luciferase.esvo.core`
- `ESVTBuilder` from `com.hellblazer.luciferase.esvt.builder`

The critical position-to-voxel conversion is extracted from
`RenderService.createESVO/createESVT`:
```java
// Position normalization (world -> region-local [0,1])
float localX = (pos.x - bounds.minX()) / bounds.size();
float localY = (pos.y - bounds.minY()) / bounds.size();
float localZ = (pos.z - bounds.minZ()) / bounds.size();

// Voxelization
int vx = Math.max(0, Math.min(gridResolution - 1, (int)(localX * gridResolution)));
int vy = Math.max(0, Math.min(gridResolution - 1, (int)(localY * gridResolution)));
int vz = Math.max(0, Math.min(gridResolution - 1, (int)(localZ * gridResolution)));
voxels.add(new Point3i(vx, vy, vz));
```

### 3.6 RegionCache

**Responsibility:** LRU cache of built region data with memory management and
multi-LOD support.

```java
/**
 * LRU cache for built ESVO/ESVT region data.
 *
 * <p>Regions visible to at least one client are pinned and will not be
 * evicted. Invisible regions are evicted after TTL expiration or when
 * memory pressure exceeds the configured limit.
 *
 * <p>Supports multiple LOD levels per region: a region may be cached at
 * LOD 2 (coarse, for distant viewers) and LOD 5 (fine, for nearby viewers)
 * simultaneously.
 *
 * <p>Thread-safe via ConcurrentHashMap and atomic operations.
 */
public class RegionCache implements AutoCloseable {

    // Cache key: (RegionId, lodLevel)
    private final ConcurrentHashMap<CacheKey, CachedRegion> cache;

    // Memory tracking
    private final AtomicLong currentMemoryBytes;
    private final long maxMemoryBytes;
    private final long regionTtlMs;

    // Eviction
    private final ScheduledExecutorService evictionScheduler;

    // Pinned regions (visible to at least one client)
    private final ConcurrentHashMap.KeySetView<CacheKey, Boolean> pinnedRegions;

    // -- Public API --
    Optional<CachedRegion> get(RegionId regionId, int lodLevel) { ... }
    void put(BuiltRegion region) { ... }
    void pin(RegionId regionId, int lodLevel) { ... }
    void unpin(RegionId regionId, int lodLevel) { ... }
    void invalidate(RegionId regionId) { ... }  // All LOD levels

    // -- Metrics --
    long memoryUsageBytes() { ... }
    int cachedRegionCount() { ... }
    CacheStats stats() { ... }

    record CacheKey(RegionId regionId, int lodLevel) {}

    record CachedRegion(
        BuiltRegion data,
        long cachedAtMs,
        AtomicLong lastAccessedMs,
        long sizeBytes
    ) {}

    record CacheStats(
        int totalRegions,
        int pinnedRegions,
        long memoryUsedBytes,
        long memoryMaxBytes,
        long evictions,
        long hits,
        long misses
    ) {}
}
```

**Eviction Policy:**
1. Pinned regions are never evicted
2. Regions exceeding TTL are evicted in scheduled sweep (every 10 seconds)
3. When memory exceeds limit, evict least-recently-accessed unpinned regions
4. Invalidation on entity update removes all LOD levels for affected region

### 3.7 ViewportTracker

**Responsibility:** Per-client viewport state management, frustum culling, LOD
determination, and region priority ordering.

```java
/**
 * Tracks client viewports and determines visible regions with LOD levels.
 *
 * <p>Each connected client has a viewport (camera position, direction, FOV).
 * ViewportTracker uses frustum culling to determine which regions are
 * visible and assigns LOD levels based on distance from camera.
 *
 * <p>Reuses lucien's existing frustum culling and AABB intersection
 * utilities for geometric calculations.
 */
public class ViewportTracker {

    private final ConcurrentHashMap<String, ClientState> clients;
    private final AdaptiveRegionManager regionManager;

    // -- Client management --
    void registerClient(String clientId, ClientViewport viewport) { ... }
    void updateViewport(String clientId, ClientViewport viewport) { ... }
    void removeClient(String clientId) { ... }

    // -- Visibility queries --

    /** Returns visible regions for a client, ordered by priority (closest first). */
    List<VisibleRegion> visibleRegions(String clientId) { ... }

    /** Returns the set of all regions visible to any client. */
    Set<RegionId> allVisibleRegions() { ... }

    /** Returns the diff from the last visibility query for a client. */
    ViewportDiff diffViewport(String clientId) { ... }

    // LOD determination
    int lodForDistance(float distance) {
        // Configurable LOD breakpoints
        // distance < 50  -> LOD 0 (highest detail)
        // distance < 200 -> LOD 1
        // distance < 500 -> LOD 2
        // distance >= 500 -> LOD 3 (lowest detail)
    }

    record ClientState(
        String clientId,
        ClientViewport viewport,
        Frustum frustum,
        List<VisibleRegion> lastVisibleRegions
    ) {}

    record VisibleRegion(
        RegionId regionId,
        int lodLevel,
        float distance,
        float priority  // Lower = higher priority (distance-based)
    ) {}

    record ViewportDiff(
        List<VisibleRegion> added,
        List<RegionId> removed,
        List<VisibleRegion> lodChanged
    ) {}
}
```

### 3.8 RegionStreamer

**Responsibility:** WebSocket handler for client connections, protocol negotiation,
region data streaming with binary frames.

```java
/**
 * WebSocket handler for streaming ESVO/ESVT region data to clients.
 *
 * <p>Protocol:
 * - JSON control messages for viewport updates, subscribe, capability negotiation
 * - Binary WebSocket frames for ESVO/ESVT region data
 *
 * <p>Follows the pattern established by EntityVisualizationServer and
 * MultiBubbleVisualizationServer, adapted for binary region streaming.
 */
public class RegionStreamer {

    private static final int PROTOCOL_VERSION = 1;

    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ClientSession> sessions;
    private final ViewportTracker viewportTracker;
    private final RegionCache regionCache;
    private final AdaptiveRegionManager regionManager;
    private final ScheduledExecutorService streamScheduler;
    private final AtomicBoolean streaming = new AtomicBoolean(false);

    // -- Javalin WebSocket handlers --
    void onConnect(WsContext ctx) { ... }
    void onMessage(WsContext ctx, String message) { ... }
    void onClose(WsContext ctx) { ... }
    void onError(WsContext ctx) { ... }

    // -- Message handlers --
    private void handleSubscribe(ClientSession session, JsonObject msg) { ... }
    private void handleViewportUpdate(ClientSession session, JsonObject msg) { ... }
    private void handlePing(ClientSession session) { ... }

    // -- Streaming --
    private void streamRegionsToClient(ClientSession session) { ... }
    private void sendRegionData(WsContext ctx, BuiltRegion region) { ... }
    private void sendRegionEvict(WsContext ctx, RegionId regionId) { ... }

    // -- Notification from build pipeline --
    void onRegionBuilt(RegionId regionId, int lodLevel) { ... }

    record ClientSession(
        String sessionId,
        WsContext wsContext,
        SparseStructureType preferredFormat,
        String quality,
        AtomicLong lastViewportUpdateMs,
        ConcurrentHashMap.KeySetView<RegionId, Boolean> sentRegions
    ) {}
}
```

---

## 4. WebSocket Protocol Specification

### 4.1 Client-to-Server Messages (JSON)

**Capability Negotiation (on connect):**
```json
{
  "type": "subscribe",
  "mode": "esvo",
  "quality": "medium",
  "protocolVersion": 1,
  "clientCapabilities": ["webgpu", "webgl2"]
}
```

**Viewport Update:**
```json
{
  "type": "viewport",
  "position": [x, y, z],
  "direction": [dx, dy, dz],
  "up": [ux, uy, uz],
  "fov": 60.0,
  "near": 0.1,
  "far": 1000.0
}
```

**Ping:**
```json
{"type": "ping", "timestamp": 1707840000000}
```

### 4.2 Server-to-Client Messages

**Capabilities Response (JSON):**
```json
{
  "type": "capabilities",
  "protocolVersion": 1,
  "formats": ["esvo", "esvt"],
  "maxRegionNodes": 65536,
  "regionLevel": 4,
  "gpuAccelerated": true
}
```

**Region Metadata (JSON, sent before binary data):**
```json
{
  "type": "region_meta",
  "regionId": "4-1234567890",
  "bounds": {"min": [x,y,z], "max": [x,y,z]},
  "lodLevel": 2,
  "nodeCount": 4096,
  "buildVersion": 42
}
```

**Region Data (Binary Frame):**
```
Offset  Size    Field
0       4       Magic: 0x45535652 ("ESVR")
4       1       Format: 0x01=ESVO, 0x02=ESVT
5       1       LOD level
6       2       Reserved (padding)
8       8       Region Morton code
16      4       Node count
20      4       Compressed data size
24      N       GZIP-compressed ESVO/ESVT node data
```

**Region Evict (JSON):**
```json
{
  "type": "region_evict",
  "regionId": "4-1234567890",
  "reason": "entity_update"
}
```

**Pong (JSON):**
```json
{"type": "pong", "timestamp": 1707840000000, "serverTime": 1707840000005}
```

### 4.3 Protocol Versioning

The `protocolVersion` field in the subscribe message enables forward compatibility.
Server selects the highest version both sides support. Breaking changes (new binary
format, removed fields) increment the version. Additive changes (new JSON fields,
new message types) do not.

---

## 5. Threading Model

### 5.1 Thread Pools

| Pool | Type | Size | Purpose |
|------|------|------|---------|
| Entity Consumer | Virtual thread executor | 1 per upstream | WebSocket client connections |
| GPU Build | Fixed thread pool | `gpuPoolSize` (default 1) | ESVO/ESVT construction |
| Stream Scheduler | ScheduledExecutor | 1 | Periodic region streaming |
| Cache Eviction | ScheduledExecutor | 1 | LRU eviction sweep |
| Javalin | Javalin default | Javalin-managed | WebSocket I/O and REST |

### 5.2 Concurrency Patterns

Following project standards (no `synchronized`, use concurrent collections):

- **ConcurrentHashMap** for all shared maps (regions, clients, cache, connections)
- **CopyOnWriteArrayList** for entity lists per region (frequent reads, infrequent writes)
- **AtomicBoolean/AtomicLong** for flags and counters
- **StampedLock** for streaming start/stop (following EntityVisualizationServer pattern)
- **CompletableFuture** for async GPU build results
- **Virtual threads** for upstream WebSocket I/O (Java 21+ standard)

### 5.3 Data Flow Synchronization

```
Entity arrives (consumer thread)
  -> ConcurrentHashMap.compute on RegionState (atomic update)
  -> AtomicBoolean.set(true) on dirty flag
  -> CompletableFuture.runAsync(build) on buildPool
     -> build completes, cache.put() (ConcurrentHashMap)
     -> regionStreamer.onRegionBuilt() (notification)
        -> iterate clients (ConcurrentHashMap.values)
        -> send to visible clients (Javalin WS thread)
```

No blocking between stages. The build pool naturally throttles GPU utilization.

---

## 6. Code Reuse Strategy

### 6.1 Direct Reuse (Import, No Modification)

| Class | Module | Usage |
|-------|--------|-------|
| `OctreeBuilder` | render | Build ESVO from voxels |
| `ESVTBuilder` | render | Build ESVT from voxels |
| `ESVOCompressedSerializer` | render | Serialize ESVO to bytes |
| `ESVTCompressedSerializer` | render | Serialize ESVT to bytes |
| `ESVOOctreeData` | render | ESVO data container |
| `ESVTData` | render | ESVT data container |
| `ESVTOpenCLRenderer` | render | GPU data upload and rendering |
| `MortonKey` | lucien | Region identification via SFC |
| `Point3i` | lucien | Voxel coordinates |

### 6.2 Pattern Reuse (Same Design, New Implementation)

| Pattern | Source | Adaptation |
|---------|--------|------------|
| Javalin WebSocket server | `EntityVisualizationServer` | Add binary frame support |
| Session management | `SpatialInspectorServer` | Per-client viewport sessions |
| GPU session state | `GpuService.GpuSessionState` | Per-region GPU contexts |
| Streaming lifecycle | `EntityVisualizationServer` | StampedLock start/stop |
| Clock injection | All simulation classes | Deterministic testing |
| Port 0 for tests | All servers | Dynamic port assignment |

### 6.3 What is NOT Reused

- `RenderService.getPositions()` -- requires a live spatial index; we work with raw positions
- `SpatialIndexService` -- we do not maintain spatial indices per client session
- `GpuService.enableGpu()` -- session-based; we manage GPU pool differently

---

## 7. Sequence Diagrams

### 7.1 Client Connection and Initial Region Load

```
Client                    RenderingServer         RegionManager      Cache
  |                            |                       |               |
  |-- WS connect ------------->|                       |               |
  |<-- capabilities ---------- |                       |               |
  |-- subscribe(esvo,medium) ->|                       |               |
  |-- viewport(pos,dir,fov) -->|                       |               |
  |                            |-- visibleRegions() -->|               |
  |                            |<-- [R1,R2,R3,...] ----|               |
  |                            |-- get(R1,LOD2) ------>|------------>  |
  |                            |<-- CachedRegion ------|<-- hit ----   |
  |<-- region_meta(R1) --------|                       |               |
  |<-- binary(R1 data) --------|                       |               |
  |                            |-- get(R2,LOD2) ------>|------------>  |
  |                            |<-- miss --------------|<-- miss ----  |
  |                            |-- scheduleBuild(R2) ->|               |
  |                            |   ...build async...   |               |
  |                            |<-- onRegionBuilt(R2) -|               |
  |<-- region_meta(R2) --------|                       |               |
  |<-- binary(R2 data) --------|                       |               |
```

### 7.2 Viewport Update with Region Diff

```
Client                    RegionStreamer         ViewportTracker     Cache
  |                            |                       |               |
  |-- viewport(new_pos) ------>|                       |               |
  |                            |-- diffViewport() ---->|               |
  |                            |<-- ViewportDiff ------|               |
  |                            |   added: [R5,R6]      |               |
  |                            |   removed: [R1,R2]    |               |
  |                            |   lodChanged: [R3]    |               |
  |<-- region_evict(R1) -------|                       |               |
  |<-- region_evict(R2) -------|                       |               |
  |                            |-- unpin(R1), unpin(R2)|               |
  |                            |-- get(R5) ----------->|               |
  |<-- binary(R5 data) --------|<-- cached ------------|               |
  |                            |-- get(R6) ----------->|               |
  |                            |<-- miss --------------|               |
  |                            |-- scheduleBuild(R6) ->|               |
  |                            |   ...build R3 at new LOD...           |
  |<-- binary(R3 data, newLOD) |                       |               |
```

### 7.3 Entity Update Triggering Region Rebuild

```
SimServer           EntityConsumer     RegionManager      Builder       Cache
  |                      |                  |                |            |
  |-- entity JSON ------>|                  |                |            |
  |                      |-- updateEntity ->|                |            |
  |                      |                  |-- mark dirty   |            |
  |                      |                  |-- invalidate ->|----------->|
  |                      |                  |-- build(R7) -->|            |
  |                      |                  |                |-- build    |
  |                      |                  |                |-- put ---->|
  |                      |                  |<-- built(R7) --|            |
  |                      |                  |                             |
  |                      |                  |-- notify streamer           |
  |                      |                  |   (R7 visible to Client A)  |
  |                      |                  |   -> send binary to A       |
```

---

## 8. Phase Breakdown

### Phase 1: Basic Infrastructure (Estimated: 1.5 weeks)

**Entry Criteria:** Design document approved by plan-auditor.

**Deliverables:**
1. `RenderingServer` with Javalin lifecycle, health/info endpoints
2. `EntityStreamConsumer` connecting to upstream simulation servers via WebSocket
3. `AdaptiveRegionManager` with fixed-grid region model (no adaptive yet)
4. `RegionId` and `RegionBounds` data types with Morton code encoding
5. `RenderingServerConfig` with defaults
6. Unit tests with mock upstream WebSocket server

**Exit Criteria:**
- [ ] RenderingServer starts/stops cleanly on dynamic port
- [ ] EntityStreamConsumer connects to EntityVisualizationServer, receives entity updates
- [ ] AdaptiveRegionManager maps entities to regions correctly
- [ ] All tests pass; code compiles without warnings
- [ ] Clock injection works for deterministic time in tests

**Dependencies:** None (builds on existing infrastructure)

### Phase 2: GPU Integration (Estimated: 1.5 weeks)

**Entry Criteria:** Phase 1 tests pass.

**Deliverables:**
1. `GpuESVOBuilder` with ESVO and ESVT build pipelines
2. `RegionCache` with LRU eviction and memory tracking
3. CPU fallback path (no GPU required)
4. Integration tests building actual ESVO/ESVT from entity data
5. Serialization to binary format for streaming

**Exit Criteria:**
- [ ] GpuESVOBuilder builds correct ESVO from entity positions (verified against OctreeBuilder)
- [ ] GpuESVOBuilder builds correct ESVT from entity positions (verified against ESVTBuilder)
- [ ] CPU fallback works when OpenCL is unavailable
- [ ] RegionCache evicts correctly under memory pressure
- [ ] GPU tests gated with `@DisabledIfEnvironmentVariable` for CI

**Dependencies:** Phase 1 complete, render module on classpath

### Phase 3: Viewport Tracking and Adaptive LOD (Estimated: 1 week)

**Entry Criteria:** Phase 2 tests pass.

**Deliverables:**
1. `ViewportTracker` with frustum culling and LOD assignment
2. `RegionStreamer` WebSocket handler with binary frame protocol
3. Viewport diff calculation (added/removed/LOD-changed regions)
4. Region pinning in cache based on visibility
5. Priority-ordered region delivery (closest first)

**Exit Criteria:**
- [ ] Frustum culling correctly identifies visible regions for test viewports
- [ ] LOD levels assigned based on distance match expected values
- [ ] ViewportDiff correctly identifies added/removed regions on camera move
- [ ] Binary WebSocket frames serialize/deserialize correctly
- [ ] Region pinning prevents eviction of visible regions

**Dependencies:** Phase 2 complete

### Phase 4: Client Rendering (Estimated: 2 weeks)

**Entry Criteria:** Phase 3 tests pass.

**Deliverables:**
1. JavaScript WebSocket client library (`esvo-client.js`)
2. WebGPU ray traversal shader (WGSL) for ESVO rendering
3. WebGL fallback renderer using fragment shader
4. CPU canvas fallback using TypedArrays
5. Capability negotiation between client and server
6. Demo HTML page with camera controls

**Exit Criteria:**
- [ ] WebGPU client renders ESVO regions correctly (visual validation)
- [ ] WebGL fallback produces comparable output
- [ ] CPU fallback works in browsers without GPU support
- [ ] Capability negotiation selects best available renderer
- [ ] Demo page shows entities from a running simulation

**Dependencies:** Phase 3 complete

### Phase 5: Optimization (Estimated: 1 week)

**Entry Criteria:** Phase 4 demo works end-to-end.

**Deliverables:**
1. Delta compression for region updates (only changed nodes)
2. Client reconnection with region version tracking
3. Multi-simulation support (namespace per upstream)
4. JMH benchmarks for build pipeline and streaming throughput
5. Stress test with configurable client/region/entity counts

**Exit Criteria:**
- [ ] Delta compression reduces bandwidth by measured percentage
- [ ] Client reconnection resumes without full region reload
- [ ] Multi-simulation mode correctly isolates entity namespaces
- [ ] Benchmarks establish baseline performance numbers
- [ ] Stress test with 10 clients, 1000 entities, 100 visible regions passes

**Dependencies:** Phase 4 complete

### Phase Dependency Graph

```
Phase 1 (Infrastructure)
    |
    v
Phase 2 (GPU Integration)
    |
    v
Phase 3 (Viewport/LOD)
    |
    v
Phase 4 (Client Rendering)
    |
    v
Phase 5 (Optimization)
```

Strictly sequential. Each phase builds on the previous.

---

## 9. Testing Strategy

### 9.1 Unit Tests (Phase 1-3)

| Test Class | Phase | Validates |
|------------|-------|-----------|
| `RenderingServerTest` | 1 | Server lifecycle, health endpoint, port assignment |
| `EntityStreamConsumerTest` | 1 | JSON parsing, upstream connection/reconnection |
| `AdaptiveRegionManagerTest` | 1 | Entity-to-region mapping, dirty tracking |
| `RegionIdTest` | 1 | Morton code encoding, comparison, bounds calculation |
| `GpuESVOBuilderTest` | 2 | ESVO/ESVT build correctness, CPU fallback |
| `RegionCacheTest` | 2 | LRU eviction, memory tracking, pinning |
| `ViewportTrackerTest` | 3 | Frustum culling, LOD assignment, diff calculation |
| `RegionStreamerTest` | 3 | Binary frame format, JSON message handling |

### 9.2 Integration Tests (Phase 2-3)

| Test Class | Phase | Validates |
|------------|-------|-----------|
| `EntityToESVOIntegrationTest` | 2 | Full pipeline: entity JSON -> ESVO binary data |
| `UpstreamConnectionTest` | 2 | EntityStreamConsumer + real EntityVisualizationServer |
| `EndToEndStreamingTest` | 3 | Client connect -> viewport -> receive regions |

### 9.3 GPU-Specific Tests (Phase 2)

```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "GPU tests require OpenCL hardware"
)
class GpuAccelerationTest {
    // Tests that verify GPU-accelerated builds produce identical
    // results to CPU builds
}
```

### 9.4 Performance Tests (Phase 5)

| Benchmark | Metric | Target |
|-----------|--------|--------|
| `RegionBuildBenchmark` | Build time per region (CPU) | < 50ms for 64^3 grid |
| `RegionBuildBenchmark` | Build time per region (GPU) | < 10ms for 64^3 grid |
| `StreamingThroughputBenchmark` | Regions/sec to 10 clients | > 100 regions/sec |
| `CacheEvictionBenchmark` | Eviction under memory pressure | < 1ms per eviction |
| `ViewportCullingBenchmark` | Frustum cull 4096 regions | < 0.5ms |

### 9.5 Testing Patterns

All tests follow project standards:
- **Dynamic ports:** `new RenderingServer(configWithPort(0))`
- **Clock injection:** `server.setClock(testClock)`
- **GPU gating:** `@DisabledIfEnvironmentVariable(named="CI")`
- **Retry in CI:** Surefire `rerunFailingTestsCount` for flaky network tests

---

## 10. Risk Assessment

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| GPU context sharing causes resource contention | High | Medium | Pool of exclusive GPU contexts, one per build thread |
| Entity-to-voxel precision loss at region boundaries | Medium | High | Overlap region bounds by 1 voxel; deduplicate in build |
| WebSocket binary frames exceed browser limits | Medium | Low | Cap region node count at 65536; use GZIP compression |
| Multi-bubble entity deduplication | Medium | Medium | Entity ID uniqueness guaranteed by simulation layer |
| OpenCL unavailable on CI/CD runners | Low | High | CPU fallback path; GPU tests gated in CI |
| Upstream sim server unavailable | Medium | Medium | Reconnection with exponential backoff; health endpoint reports |
| Cache memory leak from unpinned regions | Medium | Low | Scheduled eviction sweep; max memory enforcement |
| Protocol version mismatch client/server | Low | Medium | Version negotiation in subscribe; graceful rejection |

---

## 11. Open Questions Resolved

| Question | Resolution |
|----------|------------|
| Region cache strategy? | LRU with configurable max memory (256MB default), 30s TTL for invisible regions, visible regions pinned |
| Client reconnection? | Client sends last-known region buildVersions on reconnect; server diffs and sends updates only |
| Multi-simulation support? | Each upstream connection has a label/namespace; RegionId includes namespace; clients subscribe to specific sim |
| GPU resource management? | Fixed pool of N GPU build threads (default 1); each has exclusive OpenCL context access |
| Protocol versioning? | Version field in subscribe message; server selects highest mutually supported version |

---

## 12. File Layout

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/
    RenderingServer.java              -- Main server class
    RenderingServerConfig.java        -- Configuration record
    EntityStreamConsumer.java         -- Upstream WebSocket client
    AdaptiveRegionManager.java        -- Region grid and dirty tracking
    GpuESVOBuilder.java               -- GPU/CPU ESVO/ESVT building
    RegionCache.java                  -- LRU region cache
    ViewportTracker.java              -- Per-client viewport and visibility
    RegionStreamer.java               -- WebSocket handler for region streaming
    RegionId.java                     -- Region identification (Morton code)
    RegionBounds.java                 -- Spatial bounds per region
    ClientViewport.java               -- Client camera state
    UpstreamConfig.java               -- Upstream connection config
    SparseStructureType.java          -- ESVO/ESVT enum
    protocol/
        ProtocolConstants.java        -- Magic numbers, version, frame format
        BinaryFrameEncoder.java       -- Encode BuiltRegion to binary frame
        BinaryFrameDecoder.java       -- Decode binary frame (for tests)
        JsonMessageHandler.java       -- Parse/generate JSON control messages

simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/
    RenderingServerTest.java
    EntityStreamConsumerTest.java
    AdaptiveRegionManagerTest.java
    RegionIdTest.java
    GpuESVOBuilderTest.java
    RegionCacheTest.java
    ViewportTrackerTest.java
    RegionStreamerTest.java
    EndToEndStreamingTest.java
    protocol/
        BinaryFrameCodecTest.java
        JsonMessageHandlerTest.java

simulation/src/main/resources/web/render/
    esvo-client.js                    -- JavaScript WebSocket client library
    esvo-renderer-webgpu.js           -- WebGPU ESVO renderer
    esvo-renderer-webgl.js            -- WebGL fallback renderer
    esvo-renderer-cpu.js              -- CPU canvas fallback
    render-demo.html                  -- Demo visualization page
```

---

## 13. Metrics and Success Criteria

### 13.1 Functional Success

- [ ] Clients receive ESVO region data within 500ms of connecting with a viewport
- [ ] Entity position updates propagate to clients within 200ms (2 build cycles)
- [ ] Existing entity streaming clients (port 7080/7081) continue working unchanged
- [ ] GPU and CPU build paths produce byte-identical serialized output

### 13.2 Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| Region build latency (CPU, 64^3) | < 50ms | JMH benchmark |
| Region build latency (GPU, 64^3) | < 10ms | JMH benchmark |
| Viewport cull (4096 regions) | < 1ms | JMH benchmark |
| Memory per cached region | < 64KB average | Runtime metric |
| Max clients per server | >= 50 | Stress test |
| Streaming bandwidth per client | < 1 MB/s | Network measurement |

### 13.3 Quality Gates

Every phase must pass before advancing:
1. All unit tests pass (`mvn test -pl simulation`)
2. Code compiles without warnings
3. No `synchronized` blocks (use concurrent collections)
4. Clock injection for all time-dependent code
5. Dynamic ports in all tests (port 0)
6. SLF4J logging with `{}` placeholders (no Python-style)

---

## Appendix A: Key Interface Contracts

### A.1 Region Builder Interface

```java
/**
 * Abstraction for building sparse voxel structures from entity positions.
 * Implementations may use GPU or CPU.
 */
public interface RegionBuilder extends AutoCloseable {
    /**
     * Build a sparse voxel structure for the given region.
     *
     * @param regionId   the region to build
     * @param positions  entity positions within the region (world coordinates)
     * @param bounds     the region's spatial bounds
     * @param lodLevel   desired level of detail (0=highest)
     * @return future completing with the built region data
     */
    CompletableFuture<BuiltRegion> build(
        RegionId regionId,
        List<EntityPosition> positions,
        RegionBounds bounds,
        int lodLevel
    );

    /** Check if this builder supports GPU acceleration. */
    boolean isGpuAccelerated();
}
```

### A.2 Region Data Consumer Interface

```java
/**
 * Receives notifications when regions are built or invalidated.
 * Implemented by RegionStreamer to push data to clients.
 */
public interface RegionDataConsumer {
    void onRegionBuilt(RegionId regionId, int lodLevel, BuiltRegion data);
    void onRegionInvalidated(RegionId regionId);
}
```

---

## Appendix B: Configuration Examples

### B.1 Development (Single Machine)

```java
var config = new RenderingServerConfig(
    7090,                                    // fixed port for dev
    List.of(
        new UpstreamConfig(URI.create("ws://localhost:7080/ws/entities"), "single-bubble"),
        new UpstreamConfig(URI.create("ws://localhost:7081/ws/entities"), "multi-bubble")
    ),
    4,                                       // 16^3 = 4096 regions
    64,                                      // 64^3 voxels per region
    8,                                       // 8 levels deep
    256 * 1024 * 1024L,                      // 256 MB cache
    30_000L,                                 // 30s TTL
    true,                                    // try GPU
    1,                                       // 1 GPU thread
    SparseStructureType.ESVO                 // ESVO format
);
```

### B.2 Testing

```java
var config = new RenderingServerConfig(
    0,                                       // dynamic port
    List.of(
        new UpstreamConfig(URI.create("ws://localhost:" + simPort + "/ws/entities"), "test")
    ),
    2,                                       // 4^3 = 64 regions (fast tests)
    16,                                      // 16^3 voxels (small)
    4,                                       // shallow tree
    16 * 1024 * 1024L,                       // 16 MB cache
    5_000L,                                  // 5s TTL
    false,                                   // CPU only
    1,
    SparseStructureType.ESVO
);
```

### B.3 Production (Separate GPU Machine)

```java
var config = new RenderingServerConfig(
    7090,
    List.of(
        new UpstreamConfig(URI.create("ws://sim-1:7081/ws/entities"), "simulation-1"),
        new UpstreamConfig(URI.create("ws://sim-2:7081/ws/entities"), "simulation-2")
    ),
    5,                                       // 32^3 = 32768 regions (high detail)
    128,                                     // 128^3 voxels per region
    10,                                      // 10 levels deep
    1024 * 1024 * 1024L,                     // 1 GB cache
    60_000L,                                 // 60s TTL
    true,                                    // GPU enabled
    4,                                       // 4 GPU build threads
    SparseStructureType.ESVO
);
```

---

## Appendix C: Decision Summary for Plan-Auditor

| # | Decision | Choice | Key Rationale |
|---|----------|--------|---------------|
| D1 | Module placement | simulation module, new package | No new module; reuses existing dependency chain |
| D2 | Region identification | Morton-coded octree at configurable level | Consistent with lucien's MortonKey; O(1) lookup |
| D3 | Build pipeline | Compose OctreeBuilder/ESVTBuilder directly | Exact same builders as portal RenderService |
| D4 | GPU management | Fixed thread pool with exclusive contexts | Avoids OpenCL context contention |
| D5 | Cache strategy | LRU with pinning for visible regions | Balances memory with client responsiveness |
| D6 | WebSocket protocol | JSON control + binary data frames | JSON for flexibility, binary for bandwidth |
| D7 | Upstream consumption | java.net.http WebSocket client | Standard library, virtual thread compatible |
| D8 | Threading | Virtual threads for I/O, ForkJoinPool for builds | Project standard (no synchronized) |
| D9 | Client rendering | Progressive: WebGPU > WebGL > CPU | Broad browser support, best quality when available |
| D10 | Backward compatibility | Existing servers unchanged | New service is additive, not modifying |

**Integration Points to Validate:**
1. EntityVisualizationServer JSON format compatibility (parsed by EntityStreamConsumer)
2. MultiBubbleVisualizationServer JSON format compatibility
3. OctreeBuilder.buildFromVoxels() contract (Point3i list + maxDepth)
4. ESVTBuilder.buildFromVoxels() contract (Point3i list + maxDepth + gridResolution)
5. MortonKey encoding for region identification
6. Javalin WebSocket binary frame support
7. ESVTOpenCLRenderer.isOpenCLAvailable() for GPU detection
