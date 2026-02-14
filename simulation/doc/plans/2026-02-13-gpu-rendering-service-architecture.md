# GPU-Accelerated ESVO/ESVT Rendering Service - Detailed Architecture

**Date**: 2026-02-13
**Last Updated**: 2026-02-14
**Status**: Architecture Complete - Pending Plan Audit
**Author**: java-architect-planner
**Parent**: `simulation/doc/plans/2026-02-13-gpu-rendering-service-design.md`

---

## 1. Executive Summary

This document defines the detailed Java architecture for the GPU-Accelerated ESVO/ESVT
Rendering Service. The service sits between simulation servers (entity producers) and
browser clients (renderers), transforming raw entity position streams into compact
ESVO/ESVT spatial structures organized by viewport-aware regions.

**Key Architectural Decisions**:
- Service lives in the `simulation` module (already depends on portal and render)
- Follows existing Javalin + WebSocket patterns from MultiBubbleVisualizationServer
- Reuses portal's GPU infrastructure (ESVTOpenCLRenderer, ESVTBuilder, OctreeBuilder)
- Region-based spatial organization using MortonKey at coarse octree levels
- Binary WebSocket protocol for ESVO/ESVT data streaming to clients
- Clock injection throughout for deterministic testing

---

## 2. Architectural Overview

### 2.1 Data Flow

```
Simulation Servers                 RenderingServer                    Browser Clients
==================                 ===============                    ===============

EntityVizServer       -------->  EntityStreamConsumer
  /ws/entities          JSON       (WebSocket client)
  (port 7080)                           |
                                        v
MultiBubbleVizServer  -------->  AdaptiveOctreeRegionManager
  /ws/entities          JSON     - Assigns entities to regions
  (port 7081)                    - Tracks dirty regions
                                 - Resolves viewport visibility
                                        |
                                        v
                                 RegionBuildScheduler
                                 - Prioritizes dirty regions       Client A (WebGPU)
                                 - Builds ESVO/ESVT per region  <--- /ws/regions
                                 - Caches built structures          Viewport 1
                                        |
                                        v                          Client B (WebGL)
                                 RegionStreamer                 <--- /ws/regions
                                 - Binary WebSocket protocol        Viewport 2
                                 - Per-client viewport tracking
                                 - Delta compression               Client C (CPU)
                                                                <--- /ws/regions
                                                                    Viewport 3
```

### 2.2 Module Placement

The RenderingServer resides in the **simulation** module for these reasons:

1. `simulation` already depends on `portal` (access to GpuService, RenderService, DTOs)
2. `simulation` already depends on `render` (transitively via portal; access to ESVTBuilder, ESVTData, etc.)
3. `simulation` already has Javalin, WebSocket infrastructure, Clock injection
4. `simulation` already has the entity streaming servers being consumed
5. No new Maven module required; no new dependency introduced

### 2.3 Package Structure

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/
  rendering/
    RenderingServer.java                 # Main server orchestrator
    RenderingServerConfig.java           # Configuration record
    RenderingSession.java                # Per-client session with viewport
    consumer/
      EntityStreamConsumer.java          # WebSocket client to simulation servers
      SimulationEndpoint.java            # Simulation server address record
      EntityBatch.java                   # Parsed entity positions from stream
    region/
      Region.java                        # Region data container
      RegionId.java                      # MortonKey-based region identifier
      AdaptiveOctreeRegionManager.java   # Spatial region management
      ViewportState.java                 # Client viewport state
      ViewportRegionResolver.java        # Frustum-based region visibility
    builder/
      RegionBuilder.java                 # Interface for ESVO/ESVT construction
      EsvoRegionBuilder.java            # ESVO implementation
      EsvtRegionBuilder.java            # ESVT implementation
      RegionBuildScheduler.java          # Prioritized background builder
      RegionCache.java                   # Built region cache with invalidation
    stream/
      RegionStreamProtocol.java          # Binary wire protocol definition
      RegionStreamer.java                # WebSocket binary data sender
      RegionMessage.java                 # Protocol message sealed interface
```

---

## 3. Detailed Component Design

### 3.1 RenderingServer

The main orchestrator. Owns the Javalin server instance and coordinates all subsystems.

```java
/**
 * GPU-accelerated rendering server that transforms entity position streams
 * into compact ESVO/ESVT spatial structures, streamed to browser clients
 * as viewport-aware regions.
 *
 * Follows the MultiBubbleVisualizationServer pattern for Javalin + WebSocket.
 * Clock-injectable for deterministic testing.
 */
public class RenderingServer {

    private static final Logger log = LoggerFactory.getLogger(RenderingServer.class);

    private final RenderingServerConfig config;
    private final Javalin app;
    private final EntityStreamConsumer entityConsumer;
    private final AdaptiveOctreeRegionManager regionManager;
    private final RegionBuildScheduler buildScheduler;
    private final RegionStreamer regionStreamer;
    private final Map<String, RenderingSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Clock clock = Clock.system();

    // --- Lifecycle ---
    public void start() { ... }
    public void stop()  { ... }
    public int port()   { ... }
    public void setClock(Clock clock) { ... }

    // --- Endpoints ---
    // REST:
    //   GET  /api/health         - Health check with component status
    //   GET  /api/info           - Server capabilities and GPU info
    //   GET  /api/regions        - Region metadata (count, sizes, dirty)
    //   GET  /api/sessions       - Active client sessions
    //
    // WebSocket:
    //   /ws/regions              - Binary ESVO/ESVT region data stream
    //   (viewport updates sent by client as JSON text frames on same connection)
}
```

**Lifecycle**: `start()` initializes Javalin, starts EntityStreamConsumer connections,
starts RegionBuildScheduler. `stop()` gracefully shuts down all subsystems in reverse order.

### 3.2 RenderingServerConfig

Immutable configuration record with sensible defaults.

```java
/**
 * Configuration for RenderingServer. All parameters have sensible defaults.
 * Use the builder for customization.
 */
public record RenderingServerConfig(
    int port,                          // Server port (default: 7090, 0 for dynamic)
    List<SimulationEndpoint> endpoints, // Simulation servers to consume
    int regionDepth,                   // Octree depth for region subdivision (default: 3 = 8^3 = 512 regions)
    int maxBuildConcurrency,           // Max parallel region builds (default: 4)
    int esvoMaxDepth,                  // Max ESVO/ESVT tree depth per region (default: 8)
    int gridResolution,                // Voxel grid resolution per region (default: 64)
    long regionTtlMs,                  // Inactive region cache TTL (default: 60_000)
    long buildIntervalMs,              // Minimum interval between region rebuilds (default: 100)
    long streamIntervalMs,             // Region stream broadcast interval (default: 33 = ~30fps)
    boolean gpuEnabled,                // Enable GPU acceleration (default: true, graceful fallback)
    int maxRegionsPerFrame,            // Max regions sent per client per frame (default: 16)
    boolean compressionEnabled         // Enable binary compression (default: false initially)
) {
    public static RenderingServerConfig defaults() {
        return new RenderingServerConfig(
            7090, List.of(), 3, 4, 8, 64,
            60_000L, 100L, 33L, true, 16, false
        );
    }

    // Builder pattern for ergonomic construction
    public static Builder builder() { return new Builder(); }
    public static final class Builder { ... }
}
```

### 3.3 RenderingSession

Per-client session state tracking viewport and render capabilities.

```java
/**
 * Session state for a connected rendering client.
 * Tracks viewport, subscribed regions, and client capabilities.
 *
 * Thread-safe: accessed from WebSocket handler threads and streaming scheduler.
 */
public record RenderingSession(
    String id,
    WsContext wsContext,
    volatile ViewportState viewport,      // Updated by client messages
    RenderCapability capability,          // WebGPU, WebGL, or CPU
    Set<RegionId> subscribedRegions,      // Regions currently being streamed
    Map<RegionId, Long> regionVersions,   // Last version sent per region
    Instant created,
    volatile Instant lastActivity
) implements AutoCloseable {

    public enum RenderCapability { WEBGPU, WEBGL, CPU }

    public static RenderingSession create(WsContext ctx, RenderCapability capability) { ... }
    public RenderingSession withViewport(ViewportState viewport) { ... }
    public boolean isStale(long timeoutMs, Clock clock) { ... }
    @Override public void close() { ... }
}
```

### 3.4 EntityStreamConsumer

WebSocket client that connects to simulation servers and feeds entity data
into the region manager.

```java
/**
 * Consumes entity position streams from simulation WebSocket servers.
 * Connects to one or more SimulationEndpoints, parses JSON entity data,
 * and delivers EntityBatch updates to the AdaptiveOctreeRegionManager.
 *
 * Features:
 * - Reconnection with exponential backoff (1s, 2s, 4s, ... max 30s)
 * - Multiple simultaneous simulation connections
 * - JSON parsing of existing entity stream format
 * - Clock-injectable for deterministic testing
 */
public class EntityStreamConsumer {

    @FunctionalInterface
    public interface EntityBatchListener {
        void onEntityBatch(EntityBatch batch);
    }

    private final List<SimulationEndpoint> endpoints;
    private final EntityBatchListener listener;
    private final ScheduledExecutorService reconnectScheduler;
    private final Map<SimulationEndpoint, WebSocketClient> connections = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.system();

    public void start()  { ... }  // Connect to all endpoints
    public void stop()   { ... }  // Disconnect all
    public void setClock(Clock clock) { ... }

    // Connection state per endpoint
    public record ConnectionState(
        SimulationEndpoint endpoint,
        boolean connected,
        int reconnectAttempts,
        Instant lastConnected,
        long entitiesReceived
    ) {}

    public List<ConnectionState> getConnectionStates() { ... }
}
```

```java
/**
 * Simulation server address.
 */
public record SimulationEndpoint(
    String host,       // e.g., "localhost"
    int port,          // e.g., 7080 or 7081
    String path,       // e.g., "/ws/entities"
    String label       // Human-readable label (optional)
) {
    public String toUri() {
        return "ws://" + host + ":" + port + path;
    }
}
```

```java
/**
 * Batch of entity positions parsed from a simulation WebSocket message.
 * Matches the JSON format: {"entities": [{id, x, y, z, type, bubbleId?}, ...]}
 */
public record EntityBatch(
    List<EntityPosition> entities,
    Instant receivedAt
) {
    public record EntityPosition(
        String id,
        float x,
        float y,
        float z,
        String type,
        String bubbleId   // null for single-bubble streams
    ) {}
}
```

### 3.5 AdaptiveOctreeRegionManager

Core spatial organization component. Divides world space into octree regions
and tracks entity membership per region.

```java
/**
 * Manages spatial regions as a coarse octree over the simulation world.
 * Assigns incoming entities to regions, tracks dirty state, and resolves
 * viewport-visible regions for each client.
 *
 * Phase 2 starts with fixed-grid regions (e.g., 4x4x4 = 64 regions).
 * Phase 5 adds adaptive subdivision based on entity density.
 *
 * Thread-safe: concurrent entity updates + concurrent viewport queries.
 */
public class AdaptiveOctreeRegionManager {

    private final ConcurrentHashMap<RegionId, Region> regions = new ConcurrentHashMap<>();
    private final int regionDepth;            // Octree level for regions
    private final float worldSize;            // World bounds (assumed cubic)
    private final float regionSize;           // Size of one region cell
    private final ViewportRegionResolver viewportResolver;
    private volatile Clock clock = Clock.system();

    /**
     * Process a batch of entity positions from the stream consumer.
     * Assigns each entity to the appropriate region, marking regions dirty.
     */
    public void processEntityBatch(EntityBatch batch) { ... }

    /**
     * Get all regions that are visible from the given viewport.
     * Uses frustum culling against region bounding boxes.
     */
    public List<Region> getVisibleRegions(ViewportState viewport) { ... }

    /**
     * Get all dirty regions (entities changed since last build).
     * Returns regions sorted by priority (most entities first).
     */
    public List<Region> getDirtyRegions() { ... }

    /**
     * Mark a region as clean after successful ESVO/ESVT build.
     */
    public void markClean(RegionId regionId, long version) { ... }

    /**
     * Get region statistics for monitoring.
     */
    public RegionStats getStats() { ... }

    public record RegionStats(
        int totalRegions,
        int activeRegions,    // Have at least one entity
        int dirtyRegions,     // Need rebuild
        long totalEntities
    ) {}
}
```

```java
/**
 * A spatial region containing entity positions.
 * Regions are the unit of ESVO/ESVT construction and client streaming.
 */
public record Region(
    RegionId id,
    float originX, float originY, float originZ,  // Region origin in world space
    float size,                                     // Region extent (cubic)
    List<EntityBatch.EntityPosition> entities,      // Current entities in this region
    long version,                                   // Incremented on entity change
    boolean dirty,                                  // True if entities changed since last build
    Instant lastModified
) {
    public int entityCount() { return entities.size(); }
    public boolean isEmpty()  { return entities.isEmpty(); }
}
```

```java
/**
 * Region identifier based on MortonKey at a coarse octree level.
 * Provides spatial locality ordering for cache-friendly access.
 */
public record RegionId(long mortonCode, byte level) implements Comparable<RegionId> {

    /**
     * Compute the RegionId for a world-space position.
     */
    public static RegionId fromPosition(float x, float y, float z,
                                         float worldSize, int regionDepth) { ... }

    @Override
    public int compareTo(RegionId other) {
        return Long.compare(this.mortonCode, other.mortonCode);
    }
}
```

### 3.6 ViewportState and ViewportRegionResolver

```java
/**
 * Client viewport state for determining visible regions.
 * Sent by clients as JSON text frames on the /ws/regions connection.
 */
public record ViewportState(
    float cameraPosX, float cameraPosY, float cameraPosZ,
    float lookAtX, float lookAtY, float lookAtZ,
    float upX, float upY, float upZ,
    float fov,         // Field of view in degrees
    float nearPlane,
    float farPlane
) {
    /** Default viewport looking at origin. */
    public static ViewportState defaults() {
        return new ViewportState(
            2f, 2f, 2f,     // camera position
            0f, 0f, 0f,     // look at origin
            0f, 1f, 0f,     // up vector
            60f, 0.1f, 100f // fov, near, far
        );
    }
}
```

```java
/**
 * Resolves which regions are visible from a viewport using frustum culling.
 * Tests region bounding boxes against the view frustum planes.
 *
 * Reuses the FrustumIntersection pattern from lucien's occlusion module.
 */
public class ViewportRegionResolver {

    /**
     * Determine visible regions from the given viewport.
     * Returns regions sorted by distance from camera (closest first, for LOD).
     */
    public List<RegionId> resolveVisibleRegions(
        ViewportState viewport,
        Collection<Region> allRegions
    ) { ... }

    /**
     * Test if a single region's AABB intersects the view frustum.
     */
    public boolean isRegionVisible(
        ViewportState viewport,
        float originX, float originY, float originZ, float size
    ) { ... }
}
```

### 3.7 RegionBuilder (Build Pipeline)

```java
/**
 * Builds ESVO or ESVT spatial data for a single region from entity positions.
 *
 * Adapts portal's RenderService pattern:
 * entity positions -> voxel coordinates -> ESVO/ESVT structure
 *
 * @param <D> The spatial data type produced (ESVOOctreeData or ESVTData)
 */
public sealed interface RegionBuilder<D extends SpatialData>
    permits EsvoRegionBuilder, EsvtRegionBuilder {

    /**
     * Build spatial data from entity positions within a region.
     *
     * @param entities   Entity positions (already in region-local coordinates)
     * @param maxDepth   Maximum tree depth
     * @param gridResolution Voxel grid resolution
     * @return Built spatial data ready for serialization
     */
    D build(List<EntityBatch.EntityPosition> entities,
            float regionOriginX, float regionOriginY, float regionOriginZ,
            float regionSize,
            int maxDepth, int gridResolution);

    /**
     * The type of spatial data this builder produces.
     */
    String typeName();
}
```

```java
/**
 * Builds ESVO octree data for a region.
 * Delegates to render module's OctreeBuilder.
 */
public final class EsvoRegionBuilder implements RegionBuilder<ESVOOctreeData> {

    @Override
    public ESVOOctreeData build(List<EntityBatch.EntityPosition> entities,
                                 float regionOriginX, float regionOriginY, float regionOriginZ,
                                 float regionSize,
                                 int maxDepth, int gridResolution) {
        // 1. Convert entity positions to region-local [0,1] normalized coordinates
        // 2. Quantize to voxel grid (Point3i)
        // 3. Delegate to OctreeBuilder.buildFromVoxels()
        // (Same pattern as portal's RenderService.createESVO())
    }

    @Override
    public String typeName() { return "ESVO"; }
}
```

```java
/**
 * Builds ESVT tetrahedral data for a region.
 * Delegates to render module's ESVTBuilder.
 */
public final class EsvtRegionBuilder implements RegionBuilder<ESVTData> {

    @Override
    public ESVTData build(List<EntityBatch.EntityPosition> entities,
                           float regionOriginX, float regionOriginY, float regionOriginZ,
                           float regionSize,
                           int maxDepth, int gridResolution) {
        // 1. Convert entity positions to region-local [0,1] normalized coordinates
        // 2. Quantize to voxel grid (Point3i)
        // 3. Delegate to ESVTBuilder.buildFromVoxels()
        // (Same pattern as portal's RenderService.createESVT())
    }

    @Override
    public String typeName() { return "ESVT"; }
}
```

### 3.8 RegionBuildScheduler

```java
/**
 * Schedules and prioritizes ESVO/ESVT builds for dirty regions.
 * Runs builds in a bounded thread pool to control GPU/CPU resource usage.
 *
 * Build priority:
 * 1. Regions visible to the most clients (highest demand)
 * 2. Regions with the most entities (highest visual impact)
 * 3. Regions closest to any client camera (highest LOD priority)
 *
 * Thread-safe: called from streaming scheduler and entity consumer threads.
 */
public class RegionBuildScheduler {

    private final RegionBuilder<?> builder;
    private final RegionCache cache;
    private final AdaptiveOctreeRegionManager regionManager;
    private final ExecutorService buildExecutor;     // Bounded thread pool
    private final ScheduledExecutorService scheduler; // Periodic dirty check
    private final int maxConcurrency;
    private final long buildIntervalMs;
    private volatile Clock clock = Clock.system();

    public void start() { ... }  // Start periodic dirty region scanning
    public void stop()  { ... }  // Graceful shutdown, wait for in-flight builds

    /**
     * Force immediate rebuild of a specific region.
     */
    public CompletableFuture<Void> rebuildRegion(RegionId regionId) { ... }

    /**
     * Get build statistics.
     */
    public BuildStats getStats() { ... }

    public record BuildStats(
        long totalBuilds,
        long buildErrorCount,
        double avgBuildTimeMs,
        int pendingBuilds,
        int activeBuilds
    ) {}
}
```

### 3.9 RegionCache

```java
/**
 * Caches built ESVO/ESVT data per region with version tracking.
 * Supports TTL-based eviction of inactive regions.
 *
 * Thread-safe: ConcurrentHashMap with atomic version checks.
 */
public class RegionCache {

    private final ConcurrentHashMap<RegionId, CachedRegion> cache = new ConcurrentHashMap<>();
    private final long ttlMs;
    private volatile Clock clock = Clock.system();

    /**
     * Get cached data for a region, or null if not cached or stale.
     */
    public CachedRegion get(RegionId regionId) { ... }

    /**
     * Store built data for a region. Only stores if version is newer.
     */
    public void put(RegionId regionId, SpatialData data, long version) { ... }

    /**
     * Invalidate a specific region (e.g., on entity change).
     */
    public void invalidate(RegionId regionId) { ... }

    /**
     * Evict expired entries.
     */
    public int evictExpired() { ... }

    /**
     * Cached region data with version and timestamp.
     */
    public record CachedRegion(
        RegionId regionId,
        SpatialData data,          // ESVOOctreeData or ESVTData
        long version,              // Must match region version to be valid
        byte[] serialized,         // Pre-serialized binary for streaming
        Instant builtAt
    ) {
        public int sizeInBytes() { return data.sizeInBytes(); }
    }
}
```

### 3.10 RegionStreamProtocol and RegionMessage

```java
/**
 * Binary wire protocol for ESVO/ESVT region streaming.
 *
 * Frame format (all values little-endian):
 *
 *   Header (16 bytes):
 *   [0-1]   uint16  messageType     (0=REGION_DATA, 1=REGION_UPDATE, 2=REGION_REMOVE, 3=VIEWPORT_ACK)
 *   [2-3]   uint16  flags           (bit 0=compressed, bit 1=ESVO/ESVT, bits 2-3=reserved)
 *   [4-11]  int64   regionMortonCode
 *   [12-15] uint32  payloadLength
 *
 *   Payload (variable):
 *   For REGION_DATA: [version:int64][maxDepth:int32][leafCount:int32][nodeCount:int32][nodeBytes...]
 *   For REGION_UPDATE: [version:int64][changedNodeCount:int32][changedNodes...]
 *   For REGION_REMOVE: empty payload
 *   For VIEWPORT_ACK: [frameId:int64]
 *
 * Client-to-server messages use JSON text frames:
 *   {"type":"viewport", "camera":{...}, "capability":"webgpu"}
 */
public class RegionStreamProtocol {

    public static final int HEADER_SIZE = 16;

    // Message types
    public static final int MSG_REGION_DATA    = 0;
    public static final int MSG_REGION_UPDATE  = 1;
    public static final int MSG_REGION_REMOVE  = 2;
    public static final int MSG_VIEWPORT_ACK   = 3;

    // Flags
    public static final int FLAG_COMPRESSED = 0x01;
    public static final int FLAG_ESVT       = 0x02;  // 0=ESVO, 1=ESVT

    /**
     * Serialize a region's spatial data into a binary frame.
     */
    public static ByteBuffer serializeRegionData(
        RegionId regionId, SpatialData data, long version, boolean isEsvt
    ) { ... }

    /**
     * Deserialize a binary frame header. Used for testing and client reference.
     */
    public static RegionMessageHeader deserializeHeader(ByteBuffer buffer) { ... }

    public record RegionMessageHeader(
        int messageType, int flags, long regionMortonCode, int payloadLength
    ) {}
}
```

```java
/**
 * Protocol messages as a sealed interface hierarchy.
 * Used internally for type-safe message handling.
 */
public sealed interface RegionMessage {

    record RegionData(
        RegionId regionId,
        SpatialData data,
        long version,
        boolean isEsvt
    ) implements RegionMessage {}

    record RegionUpdate(
        RegionId regionId,
        long version,
        byte[] changedNodes
    ) implements RegionMessage {}

    record RegionRemove(
        RegionId regionId
    ) implements RegionMessage {}

    record ViewportAck(
        long frameId
    ) implements RegionMessage {}
}
```

### 3.11 RegionStreamer

```java
/**
 * Streams built ESVO/ESVT region data to connected clients via binary WebSocket.
 *
 * Per-client behavior:
 * 1. Receives viewport updates from client (JSON text frames)
 * 2. Resolves visible regions for that viewport
 * 3. Sends REGION_DATA for new visible regions
 * 4. Sends REGION_UPDATE for changed visible regions
 * 5. Sends REGION_REMOVE for regions that left the viewport
 *
 * Rate limiting: max N regions per client per frame (configurable).
 * Backpressure: skips slow clients, catches up on next frame.
 *
 * Follows streaming pattern from MultiBubbleVisualizationServer:
 * ScheduledExecutorService + StampedLock for state management.
 */
public class RegionStreamer {

    private final AdaptiveOctreeRegionManager regionManager;
    private final RegionCache cache;
    private final ViewportRegionResolver viewportResolver;
    private final Map<String, RenderingSession> sessions;
    private final ScheduledExecutorService scheduler;
    private final StampedLock streamingLock = new StampedLock();
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final int maxRegionsPerFrame;
    private volatile Clock clock = Clock.system();

    public void start() { ... }  // Start periodic streaming
    public void stop()  { ... }  // Graceful stop

    /**
     * Handle incoming WebSocket text message (viewport update from client).
     */
    public void handleViewportUpdate(String sessionId, String jsonMessage) { ... }

    /**
     * Main streaming loop (called periodically).
     * For each session: resolve visible regions, diff against sent regions,
     * send new/updated/removed region messages.
     */
    private void broadcastRegions() { ... }

    /**
     * Send binary region data to a specific client.
     */
    private void sendRegionData(RenderingSession session, RegionMessage message) { ... }
}
```

---

## 4. Sequence Diagrams

### 4.1 Entity Ingestion Flow

```
SimServer            EntityStreamConsumer        RegionManager          RegionCache
   |                        |                        |                      |
   |--- WS: entity JSON --->|                        |                      |
   |                        |-- parse JSON           |                      |
   |                        |-- EntityBatch --------->|                      |
   |                        |                        |-- assign to regions   |
   |                        |                        |-- mark dirty          |
   |                        |                        |-- bump version        |
   |                        |                        |-- invalidate -------->|
   |                        |                        |                      |-- remove cached
```

### 4.2 Region Build Flow

```
BuildScheduler          RegionManager          RegionBuilder          RegionCache
   |                        |                      |                      |
   |-- scan dirty --------->|                      |                      |
   |<-- dirty regions ------|                      |                      |
   |-- prioritize           |                      |                      |
   |-- submit to pool       |                      |                      |
   |   (per region)         |                      |                      |
   |                        |                      |                      |
   |--- build(entities) ----|--------------------->|                      |
   |                        |                      |-- normalize coords   |
   |                        |                      |-- quantize to voxels |
   |                        |                      |-- ESVTBuilder/       |
   |                        |                      |   OctreeBuilder      |
   |                        |                      |                      |
   |<-- SpatialData --------|<---------------------|                      |
   |                        |                      |                      |
   |-- store built data ----|--------------------------------------------->|
   |                        |                      |                      |-- serialize
   |                        |                      |                      |-- cache
   |-- markClean ---------->|                      |                      |
   |                        |-- clear dirty flag   |                      |
```

### 4.3 Client Streaming Flow

```
BrowserClient          RegionStreamer          RegionManager          RegionCache
   |                        |                      |                      |
   |-- WS connect --------->|                      |                      |
   |                        |-- create session     |                      |
   |                        |                      |                      |
   |-- viewport JSON ------>|                      |                      |
   |                        |-- update session     |                      |
   |                        |   viewport           |                      |
   |                        |                      |                      |
   |     (periodic broadcast loop)                 |                      |
   |                        |                      |                      |
   |                        |-- getVisibleRegions-->|                      |
   |                        |<-- visible list ------|                      |
   |                        |                      |                      |
   |                        |-- diff vs sent       |                      |
   |                        |   regions            |                      |
   |                        |                      |                      |
   |                        |-- get cached ---------|--------------------->|
   |                        |<-- CachedRegion ------|<---------------------|
   |                        |                      |                      |
   |<-- binary REGION_DATA -|                      |                      |
   |                        |                      |                      |
   |  (region left viewport)|                      |                      |
   |<-- binary REGION_REMOVE|                      |                      |
```

### 4.4 GPU Fallback Flow

```
RegionBuildScheduler     GpuAvailability           Builder Choice
   |                         |                         |
   |-- check GPU available -->|                        |
   |                         |-- ESVTOpenCLRenderer    |
   |                         |   .isOpenCLAvailable()  |
   |                         |                         |
   |   [GPU available]       |                         |
   |<-- true ----------------|                         |
   |-- use EsvtRegionBuilder |--(GPU-accelerated)----->|
   |                         |                         |
   |   [GPU unavailable]     |                         |
   |<-- false ---------------|                         |
   |-- use EsvoRegionBuilder |--(CPU only)------------>|
   |   or EsvtRegionBuilder  |  (no GPU accel)         |
   |   (CPU mode)            |                         |
```

---

## 5. Code Reuse Inventory

### 5.1 Direct Reuse (No Modification)

| Component | Source Module | Path |
|-----------|-------------|------|
| ESVTBuilder | render | `render/.../esvt/builder/ESVTBuilder.java` |
| OctreeBuilder | render | `render/.../esvo/core/OctreeBuilder.java` |
| ESVTData | render | `render/.../esvt/core/ESVTData.java` |
| ESVOOctreeData | render | `render/.../esvo/core/ESVOOctreeData.java` |
| ESVTOpenCLRenderer | render | `render/.../esvt/gpu/ESVTOpenCLRenderer.java` |
| AbstractOpenCLRenderer | render | `render/.../sparse/gpu/AbstractOpenCLRenderer.java` |
| SparseVoxelData\<N\> | render | `render/.../sparse/core/SparseVoxelData.java` |
| SpatialData | render | `render/.../inspector/SpatialData.java` |
| GpuInfo DTO | portal | `portal/.../web/dto/GpuInfo.java` |
| GpuStats DTO | portal | `portal/.../web/dto/GpuStats.java` |
| Clock interface | simulation | `simulation/.../distributed/integration/Clock.java` |
| TestClock | simulation | `simulation/.../distributed/integration/TestClock.java` |
| Point3i | common | geometry utility for voxel coordinates |

### 5.2 Adapted (Pattern Reused, New Implementation)

| Pattern Source | New Component | Adaptation |
|---------------|---------------|------------|
| GpuService session pattern | RegionCache + RegionBuildScheduler | Session -> Region granularity; pool instead of per-session GPU |
| RenderService.createESVT() | EsvtRegionBuilder.build() | Same voxel->ESVT pipeline, different coordinate normalization (region-local) |
| RenderService.createESVO() | EsvoRegionBuilder.build() | Same voxel->ESVO pipeline, region-local coordinates |
| MultiBubbleVizServer lifecycle | RenderingServer lifecycle | Same Javalin + StampedLock + ScheduledExecutor pattern |
| MultiBubbleVizServer streaming | RegionStreamer | Text JSON -> Binary protocol; entity broadcast -> region diff broadcast |
| EntityVizServer entity parsing | EntityStreamConsumer | Same JSON format, now parsed as client rather than produced as server |
| SpatialSession | RenderingSession | Extended with viewport, capability, region subscriptions |

### 5.3 Net New Code

| Component | Responsibility | Est. Lines |
|-----------|---------------|------------|
| RenderingServer | Orchestrator, Javalin endpoints | 250-350 |
| RenderingServerConfig | Configuration record + builder | 80-100 |
| RenderingSession | Per-client state | 60-80 |
| EntityStreamConsumer | WebSocket client, reconnection | 200-250 |
| SimulationEndpoint | Address record | 20 |
| EntityBatch | Parsed entity data | 30 |
| Region | Region data container | 40-50 |
| RegionId | Morton-based ID | 50-70 |
| AdaptiveOctreeRegionManager | Spatial region management | 300-400 |
| ViewportState | Viewport record | 30 |
| ViewportRegionResolver | Frustum culling | 100-150 |
| RegionBuilder + impls | Build pipeline | 150-200 |
| RegionBuildScheduler | Prioritized builder | 200-250 |
| RegionCache | Cache with TTL | 100-120 |
| RegionStreamProtocol | Binary protocol | 150-200 |
| RegionStreamer | Binary WebSocket streaming | 250-300 |
| RegionMessage | Sealed message types | 40-50 |
| **Total** | | **~2000-2600** |

---

## 6. Phased Implementation Plan

### Phase 1: RenderingServer Shell + Entity Stream Consumer

**Objective**: Establish the server skeleton and prove entity ingestion from simulation streams.

**Components**:
- `RenderingServer` (skeleton with health/info endpoints)
- `RenderingServerConfig`
- `EntityStreamConsumer` with reconnection logic
- `SimulationEndpoint`, `EntityBatch`
- Basic `RenderingSession` (no viewport yet)

**Entry Criteria**: Design document approved (this document).

**Exit Criteria**:
- [ ] RenderingServer starts on configured port, responds to /api/health
- [ ] EntityStreamConsumer connects to a running simulation server via WebSocket
- [ ] Entity JSON messages are parsed into EntityBatch records
- [ ] Reconnection works after simulation server restart
- [ ] Clock injection works for all components
- [ ] All tests pass (unit: parsing, reconnection; integration: end-to-end with simulation)

**Tests**:
- `RenderingServerLifecycleTest`: start/stop, health endpoint, dynamic port
- `EntityStreamConsumerTest`: JSON parsing, reconnection backoff (with TestClock)
- `EntityBatchParsingTest`: JSON format validation, edge cases

**Dependencies**: None (ground floor).

**Estimated Effort**: 3-4 days.

---

### Phase 2: Region Management

**Objective**: Organize entity positions into spatial regions with viewport-based visibility.

**Components**:
- `Region`, `RegionId`
- `AdaptiveOctreeRegionManager` (fixed grid mode)
- `ViewportState`, `ViewportRegionResolver`
- WebSocket /ws/regions endpoint (skeleton)

**Entry Criteria**: Phase 1 complete, entities flowing into system.

**Exit Criteria**:
- [ ] Entities correctly assigned to regions based on position
- [ ] Dirty tracking marks regions when entity membership changes
- [ ] Version counter increments on region modification
- [ ] Viewport frustum culling correctly identifies visible regions
- [ ] Region stats available via /api/regions endpoint
- [ ] All tests pass (unit: region assignment, frustum culling; integration: with entity stream)

**Tests**:
- `RegionIdTest`: Morton code computation, position-to-region mapping
- `AdaptiveOctreeRegionManagerTest`: entity assignment, dirty tracking, version management
- `ViewportRegionResolverTest`: frustum culling accuracy, edge cases (camera inside region)
- `RegionManagerIntegrationTest`: entity stream -> regions with dirty state

**Dependencies**: Phase 1.

**Estimated Effort**: 4-5 days.

---

### Phase 3: GPU ESVO/ESVT Builder Integration

**Objective**: Build ESVO/ESVT spatial data from dirty regions using existing render pipeline.

**Components**:
- `RegionBuilder` (sealed interface), `EsvoRegionBuilder`, `EsvtRegionBuilder`
- `RegionBuildScheduler`
- `RegionCache`

**Entry Criteria**: Phase 2 complete, regions with entities available.

**Exit Criteria**:
- [ ] Dirty regions trigger ESVO/ESVT construction
- [ ] Built structures match portal's RenderService output for same input
- [ ] RegionCache stores and retrieves built data with version tracking
- [ ] Cache invalidation works on region dirty flag
- [ ] GPU-optional: falls back to CPU-only builders when OpenCL unavailable
- [ ] Build concurrency bounded by configuration
- [ ] All tests pass (unit: builder output, cache; integration: full pipeline)

**Tests**:
- `EsvoRegionBuilderTest`: entity positions -> ESVOOctreeData, validate node structure
- `EsvtRegionBuilderTest`: entity positions -> ESVTData, validate node structure
- `RegionBuildSchedulerTest`: priority ordering, concurrency bounds, dirty scanning
- `RegionCacheTest`: put/get/invalidate, TTL eviction, version check
- `BuildPipelineIntegrationTest`: entity stream -> regions -> ESVO/ESVT

**Dependencies**: Phase 2, render module (ESVTBuilder, OctreeBuilder).

**Estimated Effort**: 5-6 days.

---

### Phase 4: Client Region Streaming Protocol

**Objective**: Stream built ESVO/ESVT data to browser clients via binary WebSocket protocol.

**Components**:
- `RegionStreamProtocol`
- `RegionMessage` (sealed interface)
- `RegionStreamer`
- Complete `RenderingSession` with viewport tracking
- Client-side reference implementation (HTML/JS for testing)

**Entry Criteria**: Phase 3 complete, cached ESVO/ESVT data available.

**Exit Criteria**:
- [ ] Clients connect to /ws/regions, send viewport JSON, receive binary region data
- [ ] Binary protocol correctly encodes/decodes ESVO/ESVT data
- [ ] Region subscription management: new regions sent, removed regions signaled
- [ ] Rate limiting prevents overwhelming slow clients
- [ ] Multiple concurrent clients with different viewports work correctly
- [ ] All tests pass (unit: protocol encode/decode; integration: full stack)

**Tests**:
- `RegionStreamProtocolTest`: serialize/deserialize round-trip for all message types
- `RegionStreamerTest`: viewport update -> region diff -> correct messages sent
- `MultiClientStreamingTest`: multiple clients, different viewports, correct isolation
- `BackpressureTest`: slow client doesn't block fast clients

**Dependencies**: Phase 3.

**Estimated Effort**: 5-6 days.

---

### Phase 5: Production Features

**Objective**: Hardening, adaptive regions, LOD, delta compression, multi-simulation.

**Components**:
- Adaptive octree region subdivision (density-aware split/merge)
- LOD: multiple detail levels per region based on camera distance
- Delta compression: only send changed nodes between frames
- Multi-simulation support (one RenderingServer, multiple simulation sources)
- Metrics and monitoring endpoints
- Graceful degradation (GPU failure recovery)
- Configuration hot-reload

**Entry Criteria**: Phase 4 complete, basic streaming working end-to-end.

**Exit Criteria**:
- [ ] Regions adaptively subdivide based on entity density
- [ ] Distant regions use lower detail levels
- [ ] Delta updates reduce bandwidth for slowly-changing regions
- [ ] Multiple simulation sources can be consumed simultaneously
- [ ] Monitoring exposes build latency, cache hit rate, bandwidth per client
- [ ] GPU failure doesn't crash server (graceful CPU fallback)
- [ ] All tests pass including load tests

**Tests**:
- `AdaptiveSubdivisionTest`: density-triggered split and merge
- `LODTest`: distance-based detail selection
- `DeltaCompressionTest`: only changed nodes transmitted
- `MultiSimulationTest`: two simulation sources, correct region merging
- `LoadTest`: 10+ concurrent clients, 10k+ entities, sustained throughput
- `GPUFailureRecoveryTest`: simulate OpenCL failure, verify fallback

**Dependencies**: Phase 4.

**Estimated Effort**: 8-10 days.

---

## 7. Integration Points

### 7.1 Simulation Server Discovery

**Phase 1-4 (Configuration-based)**:
```java
var config = RenderingServerConfig.builder()
    .port(7090)
    .endpoint(new SimulationEndpoint("localhost", 7081, "/ws/entities", "multi-bubble"))
    .build();
```

**Phase 5 (Dynamic discovery)**:
- Poll `/api/health` endpoints on configured host range
- Use Fireflies membership view if available (simulation already uses it)
- Hot-add/remove simulation sources

### 7.2 Simulation -> RenderingServer Protocol

Reuses existing entity WebSocket JSON format from MultiBubbleVisualizationServer:

```json
{
  "entities": [
    {"id": "entity-0", "x": 1.5, "y": 2.3, "z": 0.8, "type": "PREY", "bubbleId": "uuid-here"},
    {"id": "entity-1", "x": 3.1, "y": 1.7, "z": 4.2, "type": "PREDATOR", "bubbleId": "uuid-here"}
  ]
}
```

No changes to simulation servers required. RenderingServer is a passive consumer.

### 7.3 RenderingServer -> Client Protocol

**Server-to-client**: Binary WebSocket frames (RegionStreamProtocol)
- REGION_DATA: full ESVO/ESVT region binary data
- REGION_UPDATE: delta changes (Phase 5)
- REGION_REMOVE: region left viewport

**Client-to-server**: JSON text frames
```json
{
  "type": "viewport",
  "camera": {
    "posX": 2.0, "posY": 2.0, "posZ": 2.0,
    "lookAtX": 0, "lookAtY": 0, "lookAtZ": 0,
    "upX": 0, "upY": 1, "upZ": 0,
    "fov": 60, "nearPlane": 0.1, "farPlane": 100
  },
  "capability": "webgpu"
}
```

---

## 8. Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| GPU memory exhaustion (too many regions building simultaneously) | Medium | High | Bounded thread pool in RegionBuildScheduler; configurable maxBuildConcurrency; GPU memory budget monitoring |
| Entity stream JSON parsing bottleneck at high entity counts (>10k) | Low | Medium | JSON is fine for initial scale; future: binary entity protocol or sampling |
| Binary WebSocket protocol complexity (client parsing errors) | Medium | Medium | Extensive protocol unit tests; reference JS client; version field in protocol |
| Region granularity mismatch (too fine or too coarse) | Medium | Medium | Configurable regionDepth; start at depth 3 (512 regions); empirical tuning |
| Clock injection gaps (some component misses Clock) | Low | Low | Follow existing patterns exactly; test with TestClock in integration tests |
| OpenCL availability varies across platforms | Low | Medium | GPU is optional; isOpenCLAvailable() check; CPU fallback always available |
| WebSocket reconnection storms (many clients reconnecting after server restart) | Low | Medium | Exponential backoff with jitter; connection rate limiting |
| Cache invalidation race (build completes for stale version) | Medium | Low | Version-based cache puts (only store if version matches); idempotent updates |

---

## 9. Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Entity ingestion latency | < 10ms (parse + assign to region) | Clock-based measurement in EntityStreamConsumer |
| Region build time | < 50ms for 1000-entity region (CPU), < 10ms (GPU) | Timer in RegionBuildScheduler |
| Client streaming latency | < 100ms (entity change -> client receives region update) | End-to-end measurement with TestClock |
| Concurrent clients | >= 10 clients with independent viewports | Load test |
| Entity capacity | >= 10,000 entities across all regions | Load test |
| Cache hit rate | >= 80% for stable scenes | RegionCache metrics |
| GPU fallback time | < 1 second to detect and switch to CPU | GpuFailureRecoveryTest |
| Binary protocol overhead | < 5% vs raw ESVO/ESVT data size | Protocol serialization test |

---

## 10. Open Questions Resolution

| Question | Resolution |
|----------|------------|
| Region cache strategy (how long to keep inactive regions?) | TTL-based: 60s default, configurable via RenderingServerConfig.regionTtlMs |
| Client reconnection handling (resume from cached regions?) | Phase 1-4: fresh start on reconnect. Phase 5: send session ID, resume from cached region versions |
| Multi-simulation support? | Phase 1-4: single simulation source. Phase 5: multiple SimulationEndpoints, entities tagged by source |
| GPU resource management (share GPU across regions)? | Bounded thread pool (maxBuildConcurrency), one GPU context shared, sequential kernel dispatch |
| Protocol versioning? | Version byte in protocol header (future); initial protocol is v1 |

---

## 11. Documentation Strategy

**ChromaDB Storage**:
- `decision::architect::rendering-server-module-placement` - Why simulation module
- `decision::architect::rendering-region-protocol` - Binary protocol design
- `decision::architect::rendering-code-reuse` - Reuse inventory and rationale

**Memory Bank**:
- `Luciferase_active/rendering-service-progress.md` - Phase completion tracking

---

## Appendix A: Dependency Graph

```
Phase 1: Server Shell + Consumer
    |
    v
Phase 2: Region Management
    |
    v
Phase 3: ESVO/ESVT Builder
    |
    v
Phase 4: Client Streaming
    |
    v
Phase 5: Production Hardening
```

Each phase is strictly sequential. No phase can start until the previous phase's
exit criteria are met. Within each phase, components can be developed in parallel
where noted in the individual phase descriptions.

## Appendix B: Module Dependency Verification

The simulation module already has all required dependencies:

```xml
<!-- simulation/pom.xml already declares: -->
<dependency><artifactId>portal</artifactId></dependency>     <!-- GpuService, RenderService, DTOs -->
<dependency><artifactId>lucien</artifactId></dependency>     <!-- SpatialKey, MortonKey -->
<dependency><artifactId>javalin</artifactId></dependency>    <!-- Web framework -->
<dependency><artifactId>jackson-databind</artifactId></dependency>  <!-- JSON parsing -->

<!-- portal/pom.xml already declares: -->
<dependency><artifactId>render</artifactId></dependency>     <!-- ESVTBuilder, OctreeBuilder, ESVTData -->
<dependency><artifactId>lucien</artifactId></dependency>     <!-- Spatial index infrastructure -->
```

No new Maven dependencies required. The transitive dependency chain
`simulation -> portal -> render` provides access to all GPU and spatial data components.
