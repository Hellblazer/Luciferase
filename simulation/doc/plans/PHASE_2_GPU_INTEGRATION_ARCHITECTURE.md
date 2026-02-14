# Phase 2: GPU Integration -- Detailed Architecture

**Date**: 2026-02-13
**Author**: java-architect-planner
**Status**: READY FOR AUDIT
**Predecessor**: Phase 1 complete (2,513 LOC, 43 tests, all passing)
**Architecture**: `simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE.md`
**Audit Report**: `simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE_AUDIT.md`
**Successor**: java-developer (Phase 2 implementation)

---

## 1. Executive Summary

Phase 2 adds the voxel build pipeline and region cache to the GPU-Accelerated ESVO/ESVT
Rendering Service. It converts entity positions (tracked by Phase 1's AdaptiveRegionManager)
into compact ESVO or ESVT sparse voxel structures using the proven OctreeBuilder and
ESVTBuilder from the render module, then caches the serialized results for streaming
(Phase 3).

**Key Insight from Code Analysis**: The existing OctreeBuilder and ESVTBuilder are
CPU-based builders. There is no GPU-accelerated build path in the codebase. The "GPU"
in "GpuESVOBuilder" reflects the architectural intent (future GPU acceleration); the
Phase 2 implementation uses CPU building with a fixed thread pool. This is explicitly
consistent with the Phase 2 exit criterion: "CPU fallback works when OpenCL is unavailable."

**Deliverables:**
1. GpuESVOBuilder -- Priority build queue with C1 backpressure, CPU build pipeline
2. RegionCache -- LRU cache with pinning, M1 emergency eviction, multi-LOD support
3. SerializationUtils -- GZIP compression, ESVO/ESVT byte-array serialization
4. Integration wiring -- Phase 1 components connected to Phase 2
5. Comprehensive test suite (estimated 30+ tests)

**Estimated Duration**: 1.5 weeks

---

## 2. Open Questions Resolved

| Question | Resolution | Rationale |
|----------|-----------|-----------|
| GPU context ownership? | N/A for Phase 2 | OctreeBuilder and ESVTBuilder are CPU-only. No OpenCL context needed for building. GPU rendering is Phase 4. |
| Region memory estimation? | `serializedData.length` | The GZIP-compressed byte[] stored in cache IS the memory cost. Exact, no estimation needed. |
| Optimal build thread pool size? | `max(1, availableProcessors / 2)` | CPU-bound work; don't starve entity consumption and server I/O threads. Configurable via `gpuPoolSize`. |
| Caffeine vs custom LRU? | Custom with ConcurrentHashMap + ConcurrentLinkedDeque | No external dependency added. Project pattern: build from java.util.concurrent primitives. ConcurrentLinkedDeque provides O(1) LRU ordering. |
| GPU unavailable degradation? | CPU IS the implementation | No degradation needed since building is CPU-only. Future GPU acceleration would be additive. |

---

## 3. Component Design

### 3.1 GpuESVOBuilder

**File**: `simulation/src/main/java/.../viz/render/GpuESVOBuilder.java`

**Responsibilities:**
- Accept build requests with priority ordering (visible > invisible)
- Convert entity positions to voxel coordinates
- Build ESVO or ESVT structures using render module builders
- Serialize and compress built structures
- Manage build queue with C1 backpressure
- Return results via CompletableFuture

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Builds ESVO/ESVT sparse voxel structures from entity positions within regions.
 *
 * <p>Uses CPU-based builders ({@link OctreeBuilder}, {@link ESVTBuilder}) from the
 * render module. The name "GpuESVOBuilder" reflects architectural intent for future
 * GPU acceleration; Phase 2 implements the CPU path.
 *
 * <p><b>Build Pipeline</b>:
 * <ol>
 *   <li>Entity world positions normalized to region-local [0,1] coordinates</li>
 *   <li>Quantized to voxel grid ({@code gridResolution^3})</li>
 *   <li>Fed to OctreeBuilder.buildFromVoxels() or ESVTBuilder.buildFromVoxels()</li>
 *   <li>Resulting structure serialized and GZIP-compressed</li>
 *   <li>Wrapped in {@link BuiltRegion} and returned via CompletableFuture</li>
 * </ol>
 *
 * <p><b>C1 Backpressure</b>: Build queue is bounded at {@code MAX_QUEUE_SIZE}.
 * When full, invisible builds are rejected immediately. Visible builds evict
 * the lowest-priority invisible build. If no invisible builds exist, the visible
 * build is also rejected.
 *
 * <p>Thread model: Fixed-size thread pool of build workers polling from a
 * PriorityBlockingQueue. Each worker creates its own OctreeBuilder/ESVTBuilder
 * instances per build (builders are not thread-safe).
 *
 * <p>Thread-safe: all shared state uses concurrent collections and atomics.
 */
public class GpuESVOBuilder implements AutoCloseable {

    private final SparseStructureType structureType;
    private final int gridResolution;
    private final int maxBuildDepth;

    // Build thread pool
    private final ExecutorService buildPool;
    private final int poolSize;

    // C1: Priority-based build queue with backpressure
    private final PriorityBlockingQueue<BuildRequest> buildQueue;
    private final AtomicInteger queueSize;
    static final int MAX_QUEUE_SIZE = 1000;

    // Pending builds: regionId -> future
    private final ConcurrentHashMap<RegionId, CompletableFuture<BuiltRegion>> pendingBuilds;

    // Lifecycle
    private final AtomicBoolean shutdown;
    private volatile Clock clock = Clock.system();

    // Metrics
    private final AtomicLong totalBuilds;
    private final AtomicLong totalBuildTimeNs;
    private final AtomicLong evictions;

    // -- Build Request (C1 priority ordering) --

    /**
     * A request to build a sparse voxel structure for a region.
     *
     * <p>Priority: visible builds sort before invisible. Among equal visibility,
     * older requests (lower timestamp) sort first (FIFO within priority class).
     */
    record BuildRequest(
        RegionId regionId,
        List<EntityPosition> positions,
        RegionBounds bounds,
        int lodLevel,
        boolean visible,
        long timestamp
    ) implements Comparable<BuildRequest> {
        @Override
        public int compareTo(BuildRequest other) {
            // Visible first, then oldest first
            if (this.visible != other.visible) return this.visible ? -1 : 1;
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    // -- Built Region Result --

    /**
     * The result of building a sparse voxel structure for a region.
     *
     * @param regionId       Region identifier
     * @param type           ESVO or ESVT
     * @param serializedData GZIP-compressed serialized voxel data
     * @param nodeCount      Number of nodes in the structure
     * @param leafCount      Number of leaf nodes
     * @param lodLevel       Level of detail
     * @param buildTimeNs    Build duration in nanoseconds
     * @param buildVersion   Monotonically increasing build version
     */
    record BuiltRegion(
        RegionId regionId,
        SparseStructureType type,
        byte[] serializedData,
        int nodeCount,
        int leafCount,
        int lodLevel,
        long buildTimeNs,
        long buildVersion
    ) {
        /** Estimated memory footprint (serialized data dominates). */
        long estimatedSizeBytes() {
            return serializedData.length + 64; // 64 bytes overhead for record fields
        }
    }

    // -- Exception --

    static class BuildQueueFullException extends RuntimeException {
        BuildQueueFullException(String message) { super(message); }
    }

    // -- Constructor --

    public GpuESVOBuilder(RenderingServerConfig config) {
        this.structureType = config.defaultStructureType();
        this.gridResolution = config.gridResolution();
        this.maxBuildDepth = config.maxBuildDepth();
        this.poolSize = Math.max(1, config.gpuPoolSize());

        this.buildQueue = new PriorityBlockingQueue<>(256);
        this.queueSize = new AtomicInteger(0);
        this.pendingBuilds = new ConcurrentHashMap<>();
        this.shutdown = new AtomicBoolean(false);
        this.totalBuilds = new AtomicLong(0);
        this.totalBuildTimeNs = new AtomicLong(0);
        this.evictions = new AtomicLong(0);

        // Create fixed thread pool for build workers
        this.buildPool = Executors.newFixedThreadPool(poolSize, r -> {
            var thread = new Thread(r, "esvo-builder-" + Thread.currentThread().threadId());
            thread.setDaemon(true);
            return thread;
        });

        // Start worker threads
        for (int i = 0; i < poolSize; i++) {
            buildPool.submit(this::buildWorkerLoop);
        }
    }

    // -- Public API --

    /**
     * Submit a build request.
     *
     * <p><b>C1 Backpressure</b>: When the queue is full:
     * <ul>
     *   <li>Invisible builds are rejected immediately</li>
     *   <li>Visible builds evict the lowest-priority invisible build</li>
     *   <li>If all queued builds are visible, the new build is rejected</li>
     * </ul>
     *
     * @return CompletableFuture completing with the built region, or failing
     *         with BuildQueueFullException if rejected
     */
    CompletableFuture<BuiltRegion> build(
            RegionId regionId,
            List<EntityPosition> positions,
            RegionBounds bounds,
            int lodLevel,
            boolean visible) {

        if (shutdown.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Builder is shut down"));
        }

        // C1: Backpressure when queue full
        if (queueSize.get() >= MAX_QUEUE_SIZE) {
            if (!visible) {
                return CompletableFuture.failedFuture(
                    new BuildQueueFullException(
                        "Build queue full (" + MAX_QUEUE_SIZE + "), invisible build rejected"));
            }
            // Visible build: evict lowest-priority invisible build
            if (!evictLowestPriority()) {
                return CompletableFuture.failedFuture(
                    new BuildQueueFullException(
                        "Build queue full with all visible builds, cannot evict"));
            }
        }

        // Cancel any existing pending build for this region
        var existingFuture = pendingBuilds.get(regionId);
        if (existingFuture != null && !existingFuture.isDone()) {
            existingFuture.cancel(false);
        }

        var request = new BuildRequest(regionId, List.copyOf(positions),
                                        bounds, lodLevel, visible,
                                        clock.currentTimeMillis());
        var future = new CompletableFuture<BuiltRegion>();
        pendingBuilds.put(regionId, future);
        buildQueue.offer(request);
        queueSize.incrementAndGet();

        return future;
    }

    /** Check if a build is pending for the given region. */
    boolean isBuildPending(RegionId regionId) {
        var f = pendingBuilds.get(regionId);
        return f != null && !f.isDone();
    }

    /** Cancel a pending build. */
    void cancelBuild(RegionId regionId) {
        var f = pendingBuilds.remove(regionId);
        if (f != null) f.cancel(false);
    }

    /** Current build queue depth (C1 monitoring). */
    int queueDepth() { return queueSize.get(); }

    /** Total builds completed. */
    long totalBuilds() { return totalBuilds.get(); }

    /** Average build time in nanoseconds. */
    long avgBuildTimeNs() {
        var total = totalBuilds.get();
        return total > 0 ? totalBuildTimeNs.get() / total : 0;
    }

    /** Total evictions due to C1 backpressure. */
    long evictions() { return evictions.get(); }

    public void setClock(Clock clock) { this.clock = clock; }

    @Override
    public void close() {
        if (shutdown.compareAndSet(false, true)) {
            buildPool.shutdownNow();
            // Fail all pending builds
            pendingBuilds.forEach((k, v) -> v.cancel(true));
            pendingBuilds.clear();
        }
    }

    // -- C1: Evict lowest-priority invisible build --

    private boolean evictLowestPriority() {
        BuildRequest toEvict = null;
        for (var request : buildQueue) {
            if (!request.visible()) {
                if (toEvict == null || request.compareTo(toEvict) > 0) {
                    toEvict = request;
                }
            }
        }
        if (toEvict != null) {
            if (buildQueue.remove(toEvict)) {
                queueSize.decrementAndGet();
                evictions.incrementAndGet();
                var future = pendingBuilds.remove(toEvict.regionId());
                if (future != null) {
                    future.completeExceptionally(
                        new BuildQueueFullException(
                            "Evicted for higher priority build"));
                }
                return true;
            }
        }
        return false;
    }

    // -- Build Worker --

    private void buildWorkerLoop() {
        while (!shutdown.get()) {
            try {
                var request = buildQueue.poll(1, TimeUnit.SECONDS);
                if (request == null) continue;
                queueSize.decrementAndGet();

                var future = pendingBuilds.get(request.regionId());
                if (future == null || future.isCancelled()) continue;

                try {
                    long startNs = System.nanoTime();
                    var result = doBuild(request);
                    long elapsed = System.nanoTime() - startNs;

                    totalBuilds.incrementAndGet();
                    totalBuildTimeNs.addAndGet(elapsed);

                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    pendingBuilds.remove(request.regionId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // -- Build Pipeline --

    private BuiltRegion doBuild(BuildRequest request) {
        long startNs = System.nanoTime();

        // Step 1: Convert entity positions to voxel coordinates
        var voxels = positionsToVoxels(request.positions(), request.bounds());

        if (voxels.isEmpty()) {
            // Empty region: return empty result
            return new BuiltRegion(
                request.regionId(), structureType, new byte[0],
                0, 0, request.lodLevel(), 0, 0);
        }

        // Step 2: Build the voxel structure
        byte[] serialized;
        int nodeCount;
        int leafCount;

        if (structureType == SparseStructureType.ESVO) {
            serialized = buildAndSerializeESVO(voxels);
            // Node count from the uncompressed data
            // (header: 24 bytes metadata + N*8 bytes nodes)
            nodeCount = estimateNodeCount(serialized);
            leafCount = voxels.size();
        } else {
            serialized = buildAndSerializeESVT(voxels);
            nodeCount = estimateNodeCount(serialized);
            leafCount = voxels.size();
        }

        long elapsed = System.nanoTime() - startNs;

        return new BuiltRegion(
            request.regionId(), structureType, serialized,
            nodeCount, leafCount, request.lodLevel(),
            elapsed, clock.currentTimeMillis());
    }

    /**
     * Convert world-space entity positions to region-local voxel coordinates.
     *
     * <p>Extracted from portal's RenderService position-to-voxel pattern:
     * <ol>
     *   <li>Normalize position to region-local [0,1]: (pos - bounds.min) / bounds.size()</li>
     *   <li>Quantize to voxel grid: (int)(local * gridResolution)</li>
     *   <li>Clamp to [0, gridResolution-1]</li>
     * </ol>
     */
    List<Point3i> positionsToVoxels(List<EntityPosition> positions, RegionBounds bounds) {
        var voxels = new java.util.ArrayList<Point3i>(positions.size());
        float size = bounds.size();
        if (size <= 0) return voxels;

        for (var pos : positions) {
            // Normalize to [0,1] within region
            float localX = (pos.x() - bounds.minX()) / size;
            float localY = (pos.y() - bounds.minY()) / size;
            float localZ = (pos.z() - bounds.minZ()) / size;

            // Quantize to voxel grid
            int vx = Math.max(0, Math.min(gridResolution - 1,
                                           (int) (localX * gridResolution)));
            int vy = Math.max(0, Math.min(gridResolution - 1,
                                           (int) (localY * gridResolution)));
            int vz = Math.max(0, Math.min(gridResolution - 1,
                                           (int) (localZ * gridResolution)));

            voxels.add(new Point3i(vx, vy, vz));
        }

        return voxels;
    }

    // -- ESVO Build --

    private byte[] buildAndSerializeESVO(List<Point3i> voxels) {
        try (var builder = new OctreeBuilder(maxBuildDepth)) {
            var octreeData = builder.buildFromVoxels(voxels, maxBuildDepth);
            return SerializationUtils.serializeESVO(octreeData);
        }
    }

    // -- ESVT Build --

    private byte[] buildAndSerializeESVT(List<Point3i> voxels) {
        var builder = new ESVTBuilder();
        var esvtData = builder.buildFromVoxels(voxels, maxBuildDepth, gridResolution);
        return SerializationUtils.serializeESVT(esvtData);
    }

    private int estimateNodeCount(byte[] serialized) {
        // After deserialization, we'd know exact count.
        // For metrics, use an estimate: each 8-byte node compresses to ~3-4 bytes
        return Math.max(1, serialized.length / 4);
    }
}
```

### 3.2 RegionCache

**File**: `simulation/src/main/java/.../viz/render/RegionCache.java`

**Responsibilities:**
- LRU cache of built region data
- Multi-LOD support (same region at different LOD levels)
- Pinning for visible regions (exempt from eviction)
- M1 emergency eviction under memory pressure
- Scheduled TTL-based eviction for invisible regions
- Thread-safe concurrent access

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.viz.render.GpuESVOBuilder.BuiltRegion;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * LRU cache for built ESVO/ESVT region data with memory management.
 *
 * <p>Regions visible to at least one client are pinned and will not be
 * evicted. Invisible regions are evicted after TTL expiration or when
 * memory pressure exceeds the configured limit.
 *
 * <p>Supports multiple LOD levels per region: a region may be cached at
 * LOD 2 (coarse, for distant viewers) and LOD 5 (fine, for nearby viewers)
 * simultaneously.
 *
 * <p><b>M1 Emergency Eviction</b>: When memory exceeds 90% of max on put(),
 * triggers immediate eviction of unpinned regions down to 75% of max.
 *
 * <p><b>Eviction Policy</b> (in priority order):
 * <ol>
 *   <li>Pinned regions are NEVER evicted</li>
 *   <li>Regions past TTL are evicted in scheduled sweep (every 10s)</li>
 *   <li>When memory exceeds limit, evict least-recently-accessed unpinned regions</li>
 *   <li>M1: Emergency eviction when memory exceeds 90% threshold on put()</li>
 * </ol>
 *
 * <p>Thread-safe via ConcurrentHashMap and atomic operations. No synchronized blocks.
 */
public class RegionCache implements AutoCloseable {

    // M1: Emergency eviction thresholds
    static final double EMERGENCY_EVICTION_THRESHOLD = 0.90;
    static final double EMERGENCY_EVICTION_TARGET = 0.75;

    // Eviction sweep interval
    private static final long EVICTION_SWEEP_INTERVAL_MS = 10_000;

    // Cache storage
    private final ConcurrentHashMap<CacheKey, CachedRegion> cache;

    // LRU ordering: head = least recently used, tail = most recently used
    private final ConcurrentLinkedDeque<CacheKey> lruOrder;

    // Pinned regions (visible to at least one client)
    private final ConcurrentHashMap.KeySetView<CacheKey, Boolean> pinnedRegions;

    // Memory tracking
    private final AtomicLong currentMemoryBytes;
    private final long maxMemoryBytes;
    private final long regionTtlMs;

    // Metrics
    private final AtomicLong hits;
    private final AtomicLong misses;
    private final AtomicLong evictionCount;
    private final AtomicLong emergencyEvictionCount;

    // Eviction scheduler
    private final ScheduledExecutorService evictionScheduler;

    // Clock for deterministic testing
    private volatile Clock clock = Clock.system();

    // -- Cache Key --

    /**
     * Cache key combining region ID and LOD level.
     * A single region may be cached at multiple LOD levels.
     */
    record CacheKey(RegionId regionId, int lodLevel)
            implements Comparable<CacheKey> {
        @Override
        public int compareTo(CacheKey other) {
            var cmp = this.regionId.compareTo(other.regionId);
            return cmp != 0 ? cmp : Integer.compare(this.lodLevel, other.lodLevel);
        }
    }

    /**
     * Cached region entry with access tracking.
     */
    record CachedRegion(
        BuiltRegion data,
        long cachedAtMs,
        AtomicLong lastAccessedMs,
        long sizeBytes
    ) {}

    /**
     * Cache statistics snapshot.
     */
    record CacheStats(
        int totalRegions,
        int pinnedRegions,
        long memoryUsedBytes,
        long memoryMaxBytes,
        long evictions,
        long emergencyEvictions,
        long hits,
        long misses
    ) {
        double hitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }

        double memoryUsagePercent() {
            return memoryMaxBytes > 0
                ? (double) memoryUsedBytes / memoryMaxBytes * 100.0
                : 0.0;
        }
    }

    // -- Constructor --

    public RegionCache(RenderingServerConfig config) {
        this.maxMemoryBytes = config.maxCacheMemoryBytes();
        this.regionTtlMs = config.regionTtlMs();

        this.cache = new ConcurrentHashMap<>();
        this.lruOrder = new ConcurrentLinkedDeque<>();
        this.pinnedRegions = ConcurrentHashMap.newKeySet();
        this.currentMemoryBytes = new AtomicLong(0);
        this.hits = new AtomicLong(0);
        this.misses = new AtomicLong(0);
        this.evictionCount = new AtomicLong(0);
        this.emergencyEvictionCount = new AtomicLong(0);

        // Schedule periodic eviction sweep
        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "region-cache-evictor");
            thread.setDaemon(true);
            return thread;
        });
        evictionScheduler.scheduleAtFixedRate(
            this::evictionSweep,
            EVICTION_SWEEP_INTERVAL_MS,
            EVICTION_SWEEP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    // -- Public API --

    /**
     * Get a cached region at the specified LOD level.
     *
     * @return Optional containing the cached region, or empty if not cached
     */
    Optional<CachedRegion> get(RegionId regionId, int lodLevel) {
        var key = new CacheKey(regionId, lodLevel);
        var entry = cache.get(key);
        if (entry != null) {
            entry.lastAccessedMs().set(clock.currentTimeMillis());
            // Move to tail of LRU (most recently used)
            lruOrder.remove(key);
            lruOrder.addLast(key);
            hits.incrementAndGet();
            return Optional.of(entry);
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Put a built region into the cache.
     *
     * <p>Triggers M1 emergency eviction if memory exceeds 90% threshold.
     * Triggers standard eviction if memory exceeds max.
     */
    void put(BuiltRegion region) {
        var key = new CacheKey(region.regionId(), region.lodLevel());
        long sizeBytes = region.estimatedSizeBytes();

        // Remove existing entry for this key if present
        var existing = cache.remove(key);
        if (existing != null) {
            currentMemoryBytes.addAndGet(-existing.sizeBytes());
            lruOrder.remove(key);
        }

        // Create cached entry
        var entry = new CachedRegion(
            region,
            clock.currentTimeMillis(),
            new AtomicLong(clock.currentTimeMillis()),
            sizeBytes
        );

        cache.put(key, entry);
        lruOrder.addLast(key);
        currentMemoryBytes.addAndGet(sizeBytes);

        // M1: Emergency eviction if above threshold
        if (currentMemoryBytes.get() > maxMemoryBytes * EMERGENCY_EVICTION_THRESHOLD) {
            emergencyEvict();
        }

        // Standard eviction if over max
        while (currentMemoryBytes.get() > maxMemoryBytes) {
            if (!evictLeastRecentlyUsed()) break;
        }
    }

    /**
     * Pin a region, preventing it from being evicted.
     * Pinned regions are visible to at least one client.
     */
    void pin(RegionId regionId, int lodLevel) {
        pinnedRegions.add(new CacheKey(regionId, lodLevel));
    }

    /**
     * Unpin a region, making it eligible for eviction.
     */
    void unpin(RegionId regionId, int lodLevel) {
        pinnedRegions.remove(new CacheKey(regionId, lodLevel));
    }

    /**
     * Invalidate all LOD levels for a region.
     * Called when entity positions in the region change.
     */
    void invalidate(RegionId regionId) {
        var keysToRemove = new java.util.ArrayList<CacheKey>();
        for (var key : cache.keySet()) {
            if (key.regionId().equals(regionId)) {
                keysToRemove.add(key);
            }
        }
        for (var key : keysToRemove) {
            var removed = cache.remove(key);
            if (removed != null) {
                currentMemoryBytes.addAndGet(-removed.sizeBytes());
                lruOrder.remove(key);
                evictionCount.incrementAndGet();
            }
        }
    }

    // -- Metrics --

    long memoryUsageBytes() { return currentMemoryBytes.get(); }
    int cachedRegionCount() { return cache.size(); }
    int pinnedRegionCount() { return pinnedRegions.size(); }

    CacheStats stats() {
        return new CacheStats(
            cache.size(),
            pinnedRegions.size(),
            currentMemoryBytes.get(),
            maxMemoryBytes,
            evictionCount.get(),
            emergencyEvictionCount.get(),
            hits.get(),
            misses.get()
        );
    }

    public void setClock(Clock clock) { this.clock = clock; }

    @Override
    public void close() {
        evictionScheduler.shutdownNow();
        cache.clear();
        lruOrder.clear();
        pinnedRegions.clear();
    }

    // -- Eviction --

    /**
     * M1: Emergency eviction when memory exceeds 90% threshold.
     * Evicts unpinned regions in LRU order until below 75% target.
     */
    private void emergencyEvict() {
        long target = (long) (maxMemoryBytes * EMERGENCY_EVICTION_TARGET);
        emergencyEvictionCount.incrementAndGet();

        while (currentMemoryBytes.get() > target) {
            if (!evictLeastRecentlyUsed()) break;
        }
    }

    /**
     * Evict the least recently used unpinned region.
     *
     * @return true if a region was evicted, false if nothing to evict
     */
    private boolean evictLeastRecentlyUsed() {
        // Walk LRU from head (least recently used)
        var iterator = lruOrder.iterator();
        while (iterator.hasNext()) {
            var key = iterator.next();
            if (!pinnedRegions.contains(key)) {
                var removed = cache.remove(key);
                if (removed != null) {
                    iterator.remove();
                    currentMemoryBytes.addAndGet(-removed.sizeBytes());
                    evictionCount.incrementAndGet();
                    return true;
                }
            }
        }
        return false; // All entries are pinned
    }

    /**
     * Scheduled eviction sweep: remove unpinned regions past TTL.
     */
    private void evictionSweep() {
        long now = clock.currentTimeMillis();
        var expired = new java.util.ArrayList<CacheKey>();

        for (var entry : cache.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();

            if (!pinnedRegions.contains(key)) {
                long age = now - value.lastAccessedMs().get();
                if (age > regionTtlMs) {
                    expired.add(key);
                }
            }
        }

        for (var key : expired) {
            var removed = cache.remove(key);
            if (removed != null) {
                currentMemoryBytes.addAndGet(-removed.sizeBytes());
                lruOrder.remove(key);
                evictionCount.incrementAndGet();
            }
        }
    }
}
```

### 3.3 SerializationUtils

**File**: `simulation/src/main/java/.../viz/render/SerializationUtils.java`

**Responsibilities:**
- Serialize ESVOOctreeData and ESVTData to GZIP-compressed byte arrays
- Deserialize back for verification and future streaming
- Version header for protocol evolution

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.*;

/**
 * Serialization utilities for ESVO/ESVT voxel data.
 *
 * <p>Provides GZIP-compressed serialization of voxel structures for cache
 * storage and future streaming (Phase 3).
 *
 * <p>Binary format (before GZIP compression):
 * <pre>
 * Offset  Size    Field
 * 0       4       Version (currently 1)
 * 4       1       Format: 0x01=ESVO, 0x02=ESVT
 * 5       4       Node count
 * 9       4       Far pointer count
 * 13      4       Max depth
 * 17      4       Leaf count
 * 21      4       Internal count
 * 25      N*8     Node data (8 bytes per node)
 * 25+N*8  M*4     Far pointers (4 bytes each)
 * --- ESVT only ---
 * +0      4       Root type
 * +4      4       Contour count
 * +8      C*4     Contour data (4 bytes each)
 * +8+C*4  4       Grid resolution
 * </pre>
 *
 * <p>Thread-safe: all methods are stateless.
 */
public final class SerializationUtils {

    /** Current serialization format version. */
    static final int VERSION = 1;

    /** Format identifiers. */
    static final byte FORMAT_ESVO = 0x01;
    static final byte FORMAT_ESVT = 0x02;

    private SerializationUtils() {} // Utility class

    // -- ESVO Serialization --

    /**
     * Serialize ESVOOctreeData to a GZIP-compressed byte array.
     */
    static byte[] serializeESVO(ESVOOctreeData data) {
        var nodeIndices = data.getNodeIndices();
        int nodeCount = nodeIndices.length;
        var farPointers = data.getFarPointers();

        // Calculate raw buffer size
        int headerSize = 25; // version(4) + format(1) + nodeCount(4) + farPtrCount(4) +
                             // maxDepth(4) + leafCount(4) + internalCount(4)
        int rawSize = headerSize
                    + nodeCount * ESVONodeUnified.SIZE_BYTES
                    + farPointers.length * 4;

        var buffer = ByteBuffer.allocate(rawSize).order(ByteOrder.BIG_ENDIAN);

        // Header
        buffer.putInt(VERSION);
        buffer.put(FORMAT_ESVO);
        buffer.putInt(nodeCount);
        buffer.putInt(farPointers.length);
        buffer.putInt(data.maxDepth());
        buffer.putInt(data.leafCount());
        buffer.putInt(data.internalCount());

        // Node data
        for (int idx : nodeIndices) {
            var node = data.getNode(idx);
            if (node != null) {
                node.writeTo(buffer);
            } else {
                buffer.putInt(0);
                buffer.putInt(0);
            }
        }

        // Far pointers
        for (int fp : farPointers) {
            buffer.putInt(fp);
        }

        return gzipCompress(buffer.array());
    }

    /**
     * Deserialize GZIP-compressed byte array back to ESVOOctreeData.
     */
    static ESVOOctreeData deserializeESVO(byte[] compressed) {
        var raw = gzipDecompress(compressed);
        var buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        // Header
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException(
                "Unsupported serialization version: " + version);
        }
        byte format = buffer.get();
        if (format != FORMAT_ESVO) {
            throw new IllegalArgumentException(
                "Expected ESVO format (0x01), got: 0x" + Integer.toHexString(format));
        }
        int nodeCount = buffer.getInt();
        int farPtrCount = buffer.getInt();
        int maxDepth = buffer.getInt();
        int leafCount = buffer.getInt();
        int internalCount = buffer.getInt();

        // Node data
        var data = new ESVOOctreeData(nodeCount * ESVONodeUnified.SIZE_BYTES);
        for (int i = 0; i < nodeCount; i++) {
            data.setNode(i, ESVONodeUnified.fromByteBuffer(buffer));
        }

        // Far pointers
        if (farPtrCount > 0) {
            int[] farPointers = new int[farPtrCount];
            for (int i = 0; i < farPtrCount; i++) {
                farPointers[i] = buffer.getInt();
            }
            data.setFarPointers(farPointers);
        }

        data.setMaxDepth(maxDepth);
        data.setLeafCount(leafCount);
        data.setInternalCount(internalCount);

        return data;
    }

    // -- ESVT Serialization --

    /**
     * Serialize ESVTData to a GZIP-compressed byte array.
     */
    static byte[] serializeESVT(ESVTData data) {
        int nodeCount = data.nodeCount();
        var farPointers = data.getFarPointers();
        var contours = data.getContours();

        // Calculate raw buffer size
        int headerSize = 25; // Same base header as ESVO
        int esvtExtra = 4 + 4 + contours.length * 4 + 4;
            // rootType(4) + contourCount(4) + contours(C*4) + gridRes(4)
        int rawSize = headerSize
                    + nodeCount * ESVTNodeUnified.SIZE_BYTES
                    + farPointers.length * 4
                    + esvtExtra;

        var buffer = ByteBuffer.allocate(rawSize).order(ByteOrder.BIG_ENDIAN);

        // Header
        buffer.putInt(VERSION);
        buffer.put(FORMAT_ESVT);
        buffer.putInt(nodeCount);
        buffer.putInt(farPointers.length);
        buffer.putInt(data.maxDepth());
        buffer.putInt(data.leafCount());
        buffer.putInt(data.internalCount());

        // Node data
        for (var node : data.nodes()) {
            node.writeTo(buffer);
        }

        // Far pointers
        for (int fp : farPointers) {
            buffer.putInt(fp);
        }

        // ESVT-specific fields
        buffer.putInt(data.rootType());
        buffer.putInt(contours.length);
        for (int c : contours) {
            buffer.putInt(c);
        }
        buffer.putInt(data.gridResolution());

        return gzipCompress(buffer.array());
    }

    /**
     * Deserialize GZIP-compressed byte array back to ESVTData.
     */
    static ESVTData deserializeESVT(byte[] compressed) {
        var raw = gzipDecompress(compressed);
        var buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        // Header
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException(
                "Unsupported serialization version: " + version);
        }
        byte format = buffer.get();
        if (format != FORMAT_ESVT) {
            throw new IllegalArgumentException(
                "Expected ESVT format (0x02), got: 0x" + Integer.toHexString(format));
        }
        int nodeCount = buffer.getInt();
        int farPtrCount = buffer.getInt();
        int maxDepth = buffer.getInt();
        int leafCount = buffer.getInt();
        int internalCount = buffer.getInt();

        // Node data
        var nodes = new ESVTNodeUnified[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodes[i] = ESVTNodeUnified.fromByteBuffer(buffer);
        }

        // Far pointers
        int[] farPointers = new int[farPtrCount];
        for (int i = 0; i < farPtrCount; i++) {
            farPointers[i] = buffer.getInt();
        }

        // ESVT-specific
        int rootType = buffer.getInt();
        int contourCount = buffer.getInt();
        int[] contours = new int[contourCount];
        for (int i = 0; i < contourCount; i++) {
            contours[i] = buffer.getInt();
        }
        int gridResolution = buffer.getInt();

        return new ESVTData(nodes, contours, farPointers,
                            rootType, maxDepth, leafCount, internalCount,
                            gridResolution, new int[0]);
    }

    // -- GZIP Compression --

    /**
     * GZIP-compress a byte array.
     * Typical compression ratio for voxel data: 40-60% of original size.
     */
    static byte[] gzipCompress(byte[] raw) {
        try (var baos = new ByteArrayOutputStream(raw.length / 2);
             var gzip = new GZIPOutputStream(baos)) {
            gzip.write(raw);
            gzip.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("GZIP compression failed", e);
        }
    }

    /**
     * GZIP-decompress a byte array.
     */
    static byte[] gzipDecompress(byte[] compressed) {
        try (var bais = new ByteArrayInputStream(compressed);
             var gzip = new GZIPInputStream(bais);
             var baos = new ByteArrayOutputStream(compressed.length * 2)) {
            var buffer = new byte[8192];
            int len;
            while ((len = gzip.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("GZIP decompression failed", e);
        }
    }
}
```

---

## 4. Integration with Phase 1

### 4.1 AdaptiveRegionManager Extensions

Phase 2 adds build scheduling to AdaptiveRegionManager via setter injection
(following the established `setClock()` pattern). No Phase 1 constructor changes.

```java
// --- Added to AdaptiveRegionManager ---

private volatile GpuESVOBuilder builder;  // null until Phase 2 wiring
private volatile RegionCache cache;       // null until Phase 2 wiring

/**
 * Set the build pipeline for Phase 2 integration.
 * When set, scheduleBuild() delegates to the builder.
 * When null (Phase 1), scheduleBuild() is a no-op.
 */
public void setBuilder(GpuESVOBuilder builder) { this.builder = builder; }

/**
 * Set the region cache for Phase 2 integration.
 */
public void setCache(RegionCache cache) { this.cache = cache; }

/**
 * Schedule a build for a dirty region.
 *
 * <p>Checks cache first. If cached and not dirty, returns cached result.
 * Otherwise, submits a build request to the builder.
 *
 * <p>On build completion:
 * <ol>
 *   <li>Result stored in cache</li>
 *   <li>Region dirty flag cleared</li>
 *   <li>Build version incremented</li>
 * </ol>
 *
 * @param region   Region to build
 * @param lodLevel Desired LOD level (0 = highest detail)
 * @param visible  Whether this region is visible to a client (affects priority)
 * @return CompletableFuture with built region, or null if no builder configured
 */
public CompletableFuture<GpuESVOBuilder.BuiltRegion> scheduleBuild(
        RegionId region, int lodLevel, boolean visible) {

    if (builder == null) {
        return CompletableFuture.completedFuture(null);
    }

    // Check cache first
    if (cache != null) {
        var cached = cache.get(region, lodLevel);
        if (cached.isPresent()) {
            var state = regions.get(region);
            if (state != null && !state.dirty().get()) {
                return CompletableFuture.completedFuture(cached.get().data());
            }
            // Dirty: invalidate cached version
            cache.invalidate(region);
        }
    }

    var state = regions.get(region);
    if (state == null || state.entities().isEmpty()) {
        return CompletableFuture.completedFuture(null);
    }

    var bounds = boundsForRegion(region);
    return builder.build(region, List.copyOf(state.entities()),
                         bounds, lodLevel, visible)
                  .thenApply(built -> {
                      if (cache != null && built != null) {
                          cache.put(built);
                      }
                      state.dirty().set(false);
                      state.buildVersion().incrementAndGet();
                      return built;
                  });
}
```

### 4.2 RenderingServer Extensions

RenderingServer creates and wires Phase 2 components.

```java
// --- Added to RenderingServer ---

private GpuESVOBuilder gpuBuilder;
private RegionCache regionCache;

// In start():
gpuBuilder = new GpuESVOBuilder(config);
regionCache = new RegionCache(config);
regionManager.setBuilder(gpuBuilder);
regionManager.setCache(regionCache);

// In stop():
if (gpuBuilder != null) gpuBuilder.close();
if (regionCache != null) regionCache.close();

// New accessor methods:
public GpuESVOBuilder getGpuBuilder() { return gpuBuilder; }
public RegionCache getRegionCache() { return regionCache; }

// Clock injection extended:
public void setClock(Clock clock) {
    this.clock = clock;
    this.regionManager.setClock(clock);
    this.entityConsumer.setClock(clock);
    if (gpuBuilder != null) gpuBuilder.setClock(clock);
    if (regionCache != null) regionCache.setClock(clock);
}
```

### 4.3 Wiring Diagram

```
                      RenderingServer
                      (lifecycle, wiring)
                            |
            +---------------+---------------+
            |               |               |
   EntityStreamConsumer  AdaptiveRegionManager  RegionCache
   (upstream WS client)  (region grid, dirty)  (LRU, pinning)
            |               |                      ^
            |-- updateEntity -->                   |
                            |                      |
                            |-- scheduleBuild() -->|-- cache miss -->|
                            |                      |                 |
                            |               GpuESVOBuilder           |
                            |               (build queue, workers)   |
                            |                      |                 |
                            |               [OctreeBuilder]          |
                            |               [ESVTBuilder]            |
                            |               [SerializationUtils]     |
                            |                      |                 |
                            |                      |-- put() ------->|
                            |<-- completion -------|
```

---

## 5. Code Reuse Strategy

### 5.1 Direct Reuse (Import Unchanged)

| Class | Module | Usage in Phase 2 |
|-------|--------|-------------------|
| `OctreeBuilder` | render | `buildFromVoxels(List<Point3i>, int)` -- builds ESVO octree from voxel positions |
| `ESVTBuilder` | render | `buildFromVoxels(List<Point3i>, int, int)` -- builds ESVT from voxel positions |
| `ESVOOctreeData` | render | Container for ESVO structure; provides `getNodeIndices()`, `getNode()`, `getFarPointers()` |
| `ESVTData` | render | Container for ESVT structure; provides `nodes()`, `getFarPointers()`, `getContours()` |
| `ESVONodeUnified` | render | 8-byte ESVO node; `writeTo(ByteBuffer)`, `fromByteBuffer()` |
| `ESVTNodeUnified` | render | 8-byte ESVT node; `writeTo(ByteBuffer)`, `fromByteBuffer()` |
| `Point3i` | common | Voxel coordinate type |
| `MortonCurve` | common | Already used by AdaptiveRegionManager (Phase 1) |
| `Clock` | simulation | Already used by Phase 1 components |

### 5.2 Pattern Extraction (Same Logic, New Context)

| Pattern | Source | Extraction |
|---------|--------|------------|
| Position-to-voxel conversion | portal's RenderService lines 190-220 | `GpuESVOBuilder.positionsToVoxels()`: normalize to [0,1], quantize to grid |
| GZIP compression | Standard Java library | `SerializationUtils.gzipCompress/Decompress()` |
| Binary serialization with header | ESVTData.nodesToByteBuffer() | `SerializationUtils.serializeESVO/ESVT()`: add version header, pack nodes |

### 5.3 Important: What Is NOT Used

| Component | Why Not |
|-----------|---------|
| `ESVTOpenCLRenderer` | GPU ray-casting renderer, not a builder. Phase 4 component. |
| `AbstractOpenCLRenderer` | Same: rendering, not building. |
| `UnifiedResourceManager` | Used internally by OctreeBuilder; we don't interact with it directly. |
| `GpuService` / `GpuSessionState` | Session-based GPU contexts for portal interactive rendering. Our build pipeline doesn't need this. |

---

## 6. Sequence Diagrams

### 6.1 Build Pipeline (Entity Update to Cached Region)

```
EntityStreamConsumer          AdaptiveRegionManager       GpuESVOBuilder         RegionCache
      |                              |                         |                     |
      |-- updateEntity(id,x,y,z) -->|                         |                     |
      |                              |-- regionForPosition()   |                     |
      |                              |-- mark region dirty     |                     |
      |                              |                         |                     |
      |                              |-- scheduleBuild() ----->|                     |
      |                              |                         |-- check queue       |
      |                              |                         |   (C1 backpressure) |
      |                              |                         |-- enqueue request   |
      |                              |                         |                     |
      |                              |         [build worker picks up request]       |
      |                              |                         |                     |
      |                              |                         |-- positionsToVoxels()|
      |                              |                         |   (normalize + quantize)
      |                              |                         |                     |
      |                              |                         |-- OctreeBuilder     |
      |                              |                         |   .buildFromVoxels() |
      |                              |                         |                     |
      |                              |                         |-- SerializationUtils |
      |                              |                         |   .serializeESVO()   |
      |                              |                         |   (GZIP compress)    |
      |                              |                         |                     |
      |                              |                         |-- complete future -->|
      |                              |<-- BuiltRegion ---------|                     |
      |                              |                         |                     |
      |                              |-- cache.put(built) ----------------------->  |
      |                              |-- dirty.set(false)      |                     |
      |                              |-- buildVersion++        |                     |
```

### 6.2 Cache Hit Path

```
Caller                  AdaptiveRegionManager           RegionCache
  |                              |                          |
  |-- scheduleBuild(R1,LOD2) -->|                          |
  |                              |-- cache.get(R1,LOD2) -->|
  |                              |<-- CachedRegion --------|
  |                              |                          |
  |                              |-- dirty flag check       |
  |                              |   (dirty=false -> hit)   |
  |                              |                          |
  |<-- CompletableFuture.       |                          |
  |    completedFuture(cached)  |                          |
```

### 6.3 C1 Backpressure: Queue Saturation

```
Caller                          GpuESVOBuilder
  |                                  |
  |-- build(R100, invisible) ------->|
  |                                  |-- queueSize >= MAX_QUEUE_SIZE?
  |                                  |   YES, and request is invisible
  |<-- failedFuture(QueueFull) ------|
  |                                  |
  |-- build(R101, VISIBLE) --------->|
  |                                  |-- queueSize >= MAX_QUEUE_SIZE?
  |                                  |   YES, but request is visible
  |                                  |-- evictLowestPriority()
  |                                  |   (scan for invisible build)
  |                                  |   (remove invisible R50)
  |                                  |   R50.future.failFuture(Evicted)
  |                                  |-- enqueue R101 (visible)
  |<-- CompletableFuture<Built> -----|
```

### 6.4 M1 Emergency Eviction

```
GpuESVOBuilder              RegionCache
      |                          |
      |-- build completes ------>|
      |                          |-- cache.put(builtR7)
      |                          |   currentMemory = 235MB
      |                          |   maxMemory = 256MB
      |                          |   235/256 = 91.8% > 90% threshold
      |                          |
      |                          |-- emergencyEvict()
      |                          |   target = 256MB * 0.75 = 192MB
      |                          |
      |                          |-- evict unpinned LRU regions:
      |                          |   evict R_old1 (12KB) -> 234MB
      |                          |   evict R_old2 (8KB)  -> 234MB
      |                          |   ...continue until <= 192MB
      |                          |
      |                          |-- log: "Emergency eviction complete"
```

---

## 7. Testing Strategy

### 7.1 Test Classes and Coverage

| Test Class | Tests | Phase | Focus |
|------------|-------|-------|-------|
| `GpuESVOBuilderTest` | 10 | 2 | Build pipeline, C1 backpressure, position conversion |
| `RegionCacheTest` | 10 | 2 | LRU, pinning, M1 emergency eviction, multi-LOD |
| `SerializationUtilsTest` | 6 | 2 | Round-trip, compression, version validation |
| `BuildIntegrationTest` | 4 | 2 | Full pipeline: entity -> build -> cache |

**Estimated: 30 tests total**

### 7.2 GpuESVOBuilderTest

```java
class GpuESVOBuilderTest {

    // --- Build correctness ---

    @Test
    void testBuildESVO_producesNonEmptyResult() {
        // Given: 10 entity positions in a region
        // When: build(region, positions, bounds, lod=0, visible=true)
        // Then: BuiltRegion has non-empty serializedData, nodeCount > 0
    }

    @Test
    void testBuildESVT_producesNonEmptyResult() {
        // Same as above but with SparseStructureType.ESVT config
    }

    @Test
    void testBuildEmptyRegion_returnsEmptyResult() {
        // Given: no entity positions
        // When: build with empty list
        // Then: BuiltRegion has empty serializedData, nodeCount = 0
    }

    // --- Position conversion ---

    @Test
    void testPositionsToVoxels_normalizesCorrectly() {
        // Given: bounds [100, 200] (size 100), gridResolution 10
        //        position at (150, 150, 150) -> local (0.5, 0.5, 0.5) -> voxel (5, 5, 5)
        // When: positionsToVoxels()
        // Then: Point3i(5, 5, 5)
    }

    @Test
    void testPositionsToVoxels_clampsOutOfBounds() {
        // Given: position outside region bounds
        // When: positionsToVoxels()
        // Then: clamped to [0, gridResolution-1]
    }

    // --- C1 Backpressure ---

    @Test
    void testQueueBackpressure_rejectsInvisibleWhenFull() {
        // Given: queue filled to MAX_QUEUE_SIZE with visible builds
        // When: submit invisible build
        // Then: future completes exceptionally with BuildQueueFullException
    }

    @Test
    void testQueueBackpressure_evictsInvisibleForVisible() {
        // Given: queue filled to MAX_QUEUE_SIZE with mix of visible/invisible
        // When: submit visible build
        // Then: one invisible build evicted, new visible build enqueued
    }

    @Test
    void testQueueDepthNeverExceedsMax() {
        // Given: rapid-fire 2000 build requests
        // When: builds accumulate
        // Then: queueDepth() <= MAX_QUEUE_SIZE at all times
    }

    // --- Concurrency ---

    @Test
    void testConcurrentBuilds_noCorruption() {
        // Given: 4 build threads
        // When: submit 100 builds concurrently
        // Then: all futures complete (successfully or with queue full), no exceptions
    }

    // --- Lifecycle ---

    @Test
    void testClose_failsPendingBuilds() {
        // Given: pending builds in queue
        // When: close()
        // Then: all pending futures are cancelled
    }
}
```

### 7.3 RegionCacheTest

```java
class RegionCacheTest {

    @Test
    void testPutAndGet_basicOperation() {
        // Given: cache with 16MB limit
        // When: put(builtRegion), get(same key)
        // Then: returns Optional with the same data
    }

    @Test
    void testGet_miss_returnsEmpty() {
        // Given: empty cache
        // When: get(unknownRegion, lod=0)
        // Then: returns Optional.empty()
    }

    @Test
    void testLRUEviction_evictsOldestUnpinned() {
        // Given: cache filled to maxMemory with 3 regions
        // When: put a 4th region
        // Then: oldest unpinned region evicted, newest remains
    }

    @Test
    void testPinning_preventsEviction() {
        // Given: cache at capacity, region R1 pinned
        // When: put triggers eviction
        // Then: R1 survives, unpinned region evicted instead
    }

    @Test
    void testMultiLOD_sameRegionDifferentLODs() {
        // Given: region R1 at LOD 0 and LOD 2
        // When: get(R1, lod=0) and get(R1, lod=2)
        // Then: both return different data, independent entries
    }

    @Test
    void testInvalidate_removesAllLODs() {
        // Given: R1 cached at LOD 0, 1, 2
        // When: invalidate(R1)
        // Then: all 3 entries removed, memory freed
    }

    @Test
    void testEmergencyEviction_triggersAbove90Percent() {
        // Given: cache maxMemory = 1000 bytes
        // When: put entries totaling > 900 bytes
        // Then: emergency eviction reduces to ~750 bytes
        //       emergencyEvictionCount incremented
    }

    @Test
    void testTTLEviction_removesExpiredEntries() {
        // Given: region cached, TTL = 5s
        // When: advance clock by 10s, trigger sweep
        // Then: region evicted
    }

    @Test
    void testMemoryTracking_accurate() {
        // Given: empty cache
        // When: put region (100 bytes), put region (200 bytes)
        // Then: memoryUsageBytes() = ~364 (300 data + overhead)
        // When: invalidate first region
        // Then: memoryUsageBytes() = ~164
    }

    @Test
    void testConcurrentAccess_noCorruption() {
        // Given: 4 threads doing put/get/invalidate simultaneously
        // When: 1000 operations per thread
        // Then: no exceptions, memory tracking consistent
    }
}
```

### 7.4 SerializationUtilsTest

```java
class SerializationUtilsTest {

    @Test
    void testESVORoundTrip_preservesData() {
        // Given: ESVOOctreeData with 10 nodes
        // When: serialize -> deserialize
        // Then: node count, far pointers, depth all match
    }

    @Test
    void testESVTRoundTrip_preservesData() {
        // Given: ESVTData with 10 nodes, contours, far pointers
        // When: serialize -> deserialize
        // Then: all fields match
    }

    @Test
    void testGZIPCompression_reducesByteSize() {
        // Given: repetitive byte array (1000 bytes)
        // When: gzipCompress()
        // Then: compressed size < 1000
    }

    @Test
    void testGZIPRoundTrip_preservesContent() {
        // Given: random byte array
        // When: compress -> decompress
        // Then: identical to original
    }

    @Test
    void testVersionValidation_rejectsWrongVersion() {
        // Given: serialized data with version 99
        // When: deserializeESVO()
        // Then: throws IllegalArgumentException
    }

    @Test
    void testFormatValidation_rejectsWrongFormat() {
        // Given: ESVT data passed to deserializeESVO()
        // When: deserializeESVO()
        // Then: throws IllegalArgumentException
    }
}
```

### 7.5 BuildIntegrationTest

```java
class BuildIntegrationTest {

    @Test
    void testEntityUpdateTriggersScheduleBuild() {
        // Given: RenderingServer with Phase 2 components wired
        //        AdaptiveRegionManager with builder and cache
        // When: updateEntity("e1", 100, 100, 100, "PREY")
        //       scheduleBuild(region, lod=0, visible=true)
        // Then: BuiltRegion returned, cache populated
    }

    @Test
    void testCacheHitSkipsBuild() {
        // Given: region already built and cached
        // When: scheduleBuild(sameRegion, sameLod, visible=true) with dirty=false
        // Then: returns cached result, no build executed
    }

    @Test
    void testDirtyRegionInvalidatesCacheAndRebuilds() {
        // Given: region built, cached, then entity moved (dirty=true)
        // When: scheduleBuild()
        // Then: cache invalidated, rebuild executed, new result cached
    }

    @Test
    void testFullPipeline_multipleEntitiesMultipleRegions() {
        // Given: 50 entities across 5 regions
        // When: scheduleBuild for each dirty region
        // Then: 5 BuiltRegion results, all cached, all verifiable via deserialization
    }
}
```

### 7.6 GPU Tests (Phase 2: N/A)

There are no GPU-specific tests for Phase 2. The build pipeline uses CPU-only
builders (OctreeBuilder, ESVTBuilder). GPU tests will be added in Phase 4
when ESVTOpenCLRenderer is integrated, with `@DisabledIfEnvironmentVariable(named="CI")`.

---

## 8. Performance Considerations

### 8.1 Build Throughput

**Expected performance** (based on existing OctreeBuilder/ESVTBuilder benchmarks):

| Metric | 16^3 grid | 64^3 grid | 128^3 grid |
|--------|-----------|-----------|------------|
| Voxel count (worst case) | 4,096 | 262,144 | 2,097,152 |
| OctreeBuilder time | ~2ms | ~20ms | ~200ms |
| ESVTBuilder time | ~5ms | ~50ms | ~500ms |
| Serialization | ~1ms | ~5ms | ~20ms |
| GZIP compression | ~1ms | ~3ms | ~10ms |
| **Total build time** | **~9ms** | **~78ms** | **~730ms** |

ESVTBuilder is slower due to Tetree construction and coordinate transform.

**Build throughput** (with default config: 64^3 grid, 1 build thread):
- ~12 regions/second per build thread
- With gpuPoolSize=4: ~48 regions/second

### 8.2 Cache Memory Budget

| Config | Regions/Axis | Total Regions | Avg Region Size | Max Cached |
|--------|-------------|---------------|-----------------|------------|
| Test (16MB, 16^3) | 4 | 64 | ~10KB | ~1,600 (all) |
| Dev (256MB, 64^3) | 16 | 4,096 | ~96KB | ~2,700 (66%) |
| Prod (1GB, 128^3) | 32 | 32,768 | ~384KB | ~2,700 (8%) |

Production configurations need effective LOD and viewport culling (Phase 3)
to keep the visible working set within cache budget.

### 8.3 Thread Pool Sizing

| Config | Build Threads | Rationale |
|--------|--------------|-----------|
| `gpuPoolSize=1` (default) | 1 | Conservative; no thread contention |
| `gpuPoolSize=2` | 2 | Good for 4-core machines (50% CPU for builds) |
| `gpuPoolSize=4` | 4 | Good for 8+ core machines |

**Recommendation**: Default of 1 for development (matches architecture doc).
Production: `max(1, availableProcessors / 2)`.

### 8.4 Tuning Parameters

| Parameter | Location | Default | Tuning Notes |
|-----------|----------|---------|-------------|
| `MAX_QUEUE_SIZE` | GpuESVOBuilder | 1000 | Increase if builds are fast and entity flood is sustained |
| `EMERGENCY_EVICTION_THRESHOLD` | RegionCache | 0.90 | Lower for more headroom; raise for higher cache utilization |
| `EMERGENCY_EVICTION_TARGET` | RegionCache | 0.75 | How aggressively to free memory during emergency |
| `EVICTION_SWEEP_INTERVAL_MS` | RegionCache | 10,000 | Lower for more responsive TTL; higher for less CPU overhead |
| `gridResolution` | Config | 64 | Higher = more detail, slower builds, larger cache entries |
| `maxBuildDepth` | Config | 8 | Higher = deeper trees, more nodes, slower builds |
| `maxCacheMemoryBytes` | Config | 256MB | Size to available RAM minus other component needs |
| `regionTtlMs` | Config | 30,000 | How long invisible regions remain cached |
| `gpuPoolSize` | Config | 1 | Number of concurrent build workers |

---

## 9. Error Handling

### 9.1 Build Failures

| Failure Mode | Handling | Recovery |
|-------------|---------|----------|
| OctreeBuilder throws | Future completed exceptionally | Caller retries on next entity update |
| ESVTBuilder throws | Future completed exceptionally | Caller retries on next entity update |
| Serialization fails | Future completed exceptionally | Indicates data corruption; log error |
| Queue full (invisible) | Rejected immediately | Build deferred; region stays dirty |
| Queue full (visible) | Evict invisible, then enqueue | Best-effort visible builds |
| Builder shutdown | Future cancelled | Server is shutting down |

### 9.2 Cache Failures

| Failure Mode | Handling | Recovery |
|-------------|---------|----------|
| OOM during put | Emergency eviction triggered | Evict to 75% target |
| All entries pinned | Cannot evict | Log warning; put proceeds (may exceed limit temporarily) |
| Concurrent modification | ConcurrentHashMap handles | No action needed |
| Eviction scheduler fails | Daemon thread restarts | ScheduledExecutorService handles internally |

---

## 10. File Layout

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - existing]
    RenderingServer.java              -- Extended: wires Phase 2 components
    RenderingServerConfig.java        -- Unchanged
    EntityStreamConsumer.java         -- Unchanged
    AdaptiveRegionManager.java        -- Extended: scheduleBuild(), setBuilder(), setCache()
    RegionId.java                     -- Unchanged
    RegionBounds.java                 -- Unchanged
    EntityPosition.java               -- Unchanged
    UpstreamConfig.java               -- Unchanged
    SparseStructureType.java          -- Unchanged

    [Phase 2 - new]
    GpuESVOBuilder.java               -- Build pipeline with C1 backpressure
    RegionCache.java                  -- LRU cache with M1 emergency eviction
    SerializationUtils.java           -- ESVO/ESVT serialization + GZIP

simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - existing]
    RenderingServerTest.java          -- Unchanged
    EntityStreamConsumerTest.java     -- Unchanged
    AdaptiveRegionManagerTest.java    -- Unchanged
    RegionIdTest.java                 -- Unchanged
    RegionBoundsTest.java             -- Unchanged
    RenderingServerIntegrationTest.java -- Unchanged

    [Phase 2 - new]
    GpuESVOBuilderTest.java           -- Build pipeline, C1 backpressure, position conversion
    RegionCacheTest.java              -- LRU, pinning, M1 emergency eviction, multi-LOD
    SerializationUtilsTest.java       -- Round-trip, compression, version validation
    BuildIntegrationTest.java         -- Full pipeline: entity -> build -> cache
```

---

## 11. Risk Assessment

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| OctreeBuilder memory leak (unclosed) | Medium | Medium | Always use try-with-resources; builder tracks allocations |
| ESVTBuilder Tetree coordinate transform failures | Medium | Low | ESVTBuilder logs and skips failed voxels; verify with round-trip test |
| Build queue starvation (all visible, queue full) | Medium | Low | C1 handles: reject if all visible; visible builds rare to saturate |
| Emergency eviction cascade (many puts in burst) | Low | Medium | Emergency eviction target (75%) provides headroom; single eviction call per put |
| ConcurrentLinkedDeque O(n) LRU remove | Low | Low | Cache entries ~2,700 max; O(n) on 2,700 entries is ~microseconds |
| Serialization version mismatch in future | Low | Low | Version header validated on deserialize; clear error messages |
| GZIP overhead for small regions | Low | High | For <100 byte regions, GZIP may expand; acceptable tradeoff for consistency |

---

## 12. Decisions Summary

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| D1 | Build pipeline technology | CPU-only (OctreeBuilder/ESVTBuilder) | No GPU build infrastructure exists; CPU builders are proven and sufficient |
| D2 | Thread pool for builds | Fixed thread pool, configurable size | CPU-bound work; avoid thread contention |
| D3 | Cache implementation | ConcurrentHashMap + ConcurrentLinkedDeque | No external dependencies; project pattern |
| D4 | Memory estimation | serializedData.length + 64 bytes overhead | Exact measurement of stored data; no estimation needed |
| D5 | C1 eviction strategy | Reject invisible; evict invisible for visible | Correct priority: visible builds always preferred |
| D6 | M1 threshold | 90% trigger, 75% target | Aggressive enough to prevent OOM; enough headroom for burst |
| D7 | Serialization format | Version-header + GZIP | Forward-compatible via version; GZIP for bandwidth |
| D8 | Phase 1 integration | Setter injection (setBuilder/setCache) | No constructor changes; backward compatible |
| D9 | ESVTBuilder usage | buildFromVoxels(voxels, maxDepth, gridResolution) | Uses the gridResolution variant for explicit coordinate scaling |
| D10 | LRU tracking | ConcurrentLinkedDeque | O(1) add/remove-last, O(n) remove-by-key (acceptable for cache sizes) |

---

## 13. Quality Gates

Phase 2 must pass all of these before proceeding to Phase 3:

- [ ] GpuESVOBuilder builds correct ESVO from entity positions (verified via serialization round-trip)
- [ ] GpuESVOBuilder builds correct ESVT from entity positions (verified via serialization round-trip)
- [ ] CPU build path works (no GPU/OpenCL required)
- [ ] C1: Build queue never exceeds MAX_QUEUE_SIZE under entity flood
- [ ] RegionCache LRU eviction works correctly under memory pressure
- [ ] RegionCache pinning prevents eviction of pinned regions
- [ ] M1: Emergency eviction triggers above 90% and reduces to 75%
- [ ] Multi-LOD: Same region cached at different LOD levels independently
- [ ] Serialization round-trip preserves all ESVO/ESVT data fields
- [ ] GZIP compression reduces serialized data size
- [ ] All Phase 1 tests continue to pass (no regressions)
- [ ] All Phase 2 tests pass (`mvn test -pl simulation`)
- [ ] No `synchronized` blocks (concurrent collections only)
- [ ] Clock injection for all time-dependent code
- [ ] Dynamic ports in all tests (port 0)
- [ ] SLF4J logging with `{}` placeholders

---

## Appendix A: ESVTNodeUnified.fromByteBuffer Verification

**VERIFIED**: `ESVTNodeUnified.fromByteBuffer(ByteBuffer)` exists at
`render/src/main/java/.../esvt/core/ESVTNodeUnified.java:382`.

Both `ESVONodeUnified.fromByteBuffer()` and `ESVTNodeUnified.fromByteBuffer()`
are available as static factory methods. The SerializationUtils code uses these
directly for deserialization.

**Dependency chain verified**: simulation -> portal -> render -> common.
All required classes (`Point3i`, `OctreeBuilder`, `ESVTBuilder`, `ESVOOctreeData`,
`ESVTData`, `ESVONodeUnified`, `ESVTNodeUnified`, `MortonCurve`) are available
in the simulation module's classpath.

---

## Appendix B: Integration Test Infrastructure

Phase 2 integration tests build on Phase 1 infrastructure:

```java
// Standard test setup
var config = RenderingServerConfig.testing(); // port 0, CPU only, 16^3 grid
var server = new RenderingServer(config);
server.setClock(testClock);
server.start();

// Phase 2 components are wired automatically in start()
var builder = server.getGpuBuilder();
var cache = server.getRegionCache();
var manager = server.getRegionManager();

// Add entities
manager.updateEntity("test:e1", 100, 100, 100, "PREY");

// Trigger build
var future = manager.scheduleBuild(
    manager.regionForPosition(100, 100, 100),
    0, true);

var built = future.get(5, TimeUnit.SECONDS);
assertNotNull(built);
assertTrue(built.serializedData().length > 0);

// Verify cache
var cached = cache.get(built.regionId(), 0);
assertTrue(cached.isPresent());
```

---

## Appendix C: Relationship to Architecture Document

This Phase 2 architecture refines the following sections of the parent document:

| Parent Section | Refinement |
|---------------|------------|
| Section 3.5 (GpuESVOBuilder) | Clarified: CPU-only building, corrected C1 eviction logic, detailed position-to-voxel conversion |
| Section 3.6 (RegionCache) | Added: ConcurrentLinkedDeque LRU, M1 emergency eviction implementation, CacheStats record |
| Section 6.1 (Direct Reuse) | Verified: all listed classes exist with expected APIs |
| Section 6.3 (Not Reused) | Added: ESVTOpenCLRenderer, AbstractOpenCLRenderer, GpuService |
| Section 8 (Phase 2) | Expanded: detailed test list, quality gates, file layout |
| Appendix A.1 (RegionBuilder) | Decision: not used; GpuESVOBuilder directly implements build logic |

**Corrections to parent architecture:**
1. C1 eviction logic: `PriorityBlockingQueue.poll()` removes HEAD (highest priority), not tail. Fixed by scanning queue for lowest-priority invisible build.
2. GPU vs CPU: Build pipeline is CPU-only. No OpenCL context management needed in Phase 2.
3. ESVTOpenCLRenderer: Is a rendering component (Phase 4), not a building component (Phase 2).
