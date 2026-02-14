# Phase 2: GPU Integration -- Detailed Architecture (V2)

**Date**: 2026-02-13
**Author**: java-architect-planner
**Status**: REVISED -- All critique issues addressed
**Predecessor**: Phase 1 complete (2,513 LOC, 43 tests, all passing)
**V1 Architecture**: `PHASE_2_GPU_INTEGRATION_ARCHITECTURE.md`
**Critique**: `PHASE_2_PLAN_CRITIQUE.md` (4 critical + 5 significant issues)
**Audit**: `PHASE_2_EXECUTION_PLAN_AUDIT.md`
**Successor**: java-developer (Phase 2 implementation)

---

## 1. Executive Summary

Phase 2 adds the voxel build pipeline and region cache to the GPU-Accelerated ESVO/ESVT
Rendering Service. It converts entity positions (tracked by Phase 1's AdaptiveRegionManager)
into compact ESVO or ESVT sparse voxel structures using the proven OctreeBuilder and
ESVTBuilder from the render module, then caches the serialized results for streaming
(Phase 3).

**Key Changes from V1** (addressing all 9 critique issues + 8 additional improvements):
- **C1 FIXED**: Day 0 benchmark prerequisite with performance quality gates
- **C2 FIXED**: Caffeine replaces ConcurrentHashMap+ConcurrentLinkedDeque for O(1) LRU
- **C3 FIXED**: Circuit breaker prevents retry storms on persistent build failures
- **C4 FIXED**: AtomicBoolean guard prevents concurrent emergency eviction race
- **S1 FIXED**: GpuESVOBuilder renamed to RegionBuilder; gpuPoolSize renamed to buildPoolSize
- **S2 FIXED**: ConcurrentSkipListSet for O(log n) invisible build eviction
- **S3 FIXED**: backfillDirtyRegions() added for setter injection backfill
- **S4 FIXED**: Test coverage increased from 30 to 45 tests (27 LOC/test)
- **S5 FIXED**: Schedule increased from 7.5 to 9.5 days (21% buffer)

**Deliverables:**
1. RegionBuilder -- Priority build queue with C1 backpressure, circuit breaker, CPU build pipeline
2. RegionCache -- Caffeine-based LRU cache with pinning, M1 emergency eviction, multi-LOD support
3. SerializationUtils -- Compression-threshold-aware GZIP, ESVO/ESVT byte-array serialization
4. Integration wiring -- Phase 1 components connected to Phase 2 with dirty-region backfill
5. Comprehensive test suite (45 tests)
6. Performance benchmark (Day 0 prerequisite)

**Estimated Duration**: 9.5 days (including Day 0 benchmark and 2-day buffer)

---

## 2. Remediation Summary

### Critical Issues Resolved

| ID | Issue | V1 State | V2 Resolution |
|----|-------|----------|---------------|
| C1 | Performance baseline missing | No benchmarks; 78ms build time assumed | Day 0 benchmark bead; P50 < 50ms, P99 < 200ms quality gates; fallback documented |
| C2 | O(n) LRU updates | ConcurrentLinkedDeque O(n) remove on every hit | Caffeine Cache with window-TinyLFU; O(1) amortized; Weigher for memory-based eviction |
| C3 | Build failure silent corruption | No failedBuilds metric; no retry prevention | failedBuilds metric; CircuitBreaker after 3 failures per region; 60s cooldown |
| C4 | Concurrent emergency eviction race | No guard on emergencyEvict() | AtomicBoolean emergencyEvicting; compareAndSet prevents concurrent entry |

### Significant Issues Resolved

| ID | Issue | V1 State | V2 Resolution |
|----|-------|----------|---------------|
| S1 | Misleading naming | GpuESVOBuilder (CPU-only) | Renamed to RegionBuilder; gpuPoolSize to buildPoolSize |
| S2 | O(n) backpressure eviction | Linear scan of buildQueue | ConcurrentSkipListSet(reverseOrder) for O(log n) pollFirst() |
| S3 | Setter injection no backfill | Dirty regions lost on dynamic wiring | backfillDirtyRegions() method; called after setBuilder/setCache |
| S4 | Test coverage 50% below standard | 30 tests (41 LOC/test) | 45 tests (27 LOC/test); 15 new tests for failure modes and races |
| S5 | Schedule thin buffer | 7.5 days (6.7% buffer) | 9.5 days (21% buffer); Day 0 benchmark added |

### Additional Improvements

| ID | Issue | Resolution |
|----|-------|------------|
| A1 | GPU config misleading | gpuEnabled removed from Phase 2 config; buildPoolSize replaces gpuPoolSize |
| A2 | Unverified portal pattern | Verification bead added to Day 0; exact file/line references required |
| A3 | GZIP expands small regions | Compression threshold: skip GZIP for regions < 200 bytes raw |
| A4 | Serialization version unused | Version header retained for forward-compat; negotiation deferred to Phase 3 |
| M1 | Performance benchmarks | Day 0 benchmark with JMH; build throughput and cache latency measured |
| M2 | Monitoring/metrics | /api/metrics endpoint specified; buildLatency, cacheHitRate, queueDepth, failedBuilds |
| M3 | Profiling strategy | JFR for production, async-profiler for development documented |
| M4 | Graceful degradation | Queue saturation shedding, pinned cache overflow, build timeout behaviors defined |

---

## 3. Open Questions Resolved

| Question | Resolution | Rationale |
|----------|-----------|-----------|
| GPU context ownership? | N/A for Phase 2 | OctreeBuilder and ESVTBuilder are CPU-only. No OpenCL context needed. |
| Region memory estimation? | `serializedData.length` | The compressed byte[] stored in cache IS the memory cost. |
| Optimal build thread pool size? | `max(1, availableProcessors / 2)` | CPU-bound work; configurable via `buildPoolSize`. |
| Caffeine vs custom LRU? | **Caffeine** (V2 change) | O(1) amortized LRU via window-TinyLFU. Already in root pom.xml dependencyManagement. |
| GPU unavailable degradation? | CPU IS the implementation | No degradation needed; building is CPU-only. |
| Build failure handling? | **Circuit breaker** (V2 change) | After 3 failures for same region, stop retrying for 60 seconds. |
| Compression for small regions? | **Threshold-based** (V2 change) | Skip GZIP for raw data < 200 bytes to avoid expansion. |

---

## 4. Component Design

### 4.1 RegionBuilder (renamed from GpuESVOBuilder)

**File**: `simulation/src/main/java/.../viz/render/RegionBuilder.java`

**V2 Changes**:
- Renamed from GpuESVOBuilder (S1)
- Circuit breaker for persistent build failures (C3)
- ConcurrentSkipListSet for O(log n) invisible build eviction (S2)
- failedBuilds metric (C3)
- Compression threshold in build pipeline (A3)

**Responsibilities:**
- Accept build requests with priority ordering (visible > invisible)
- Convert entity positions to voxel coordinates
- Build ESVO or ESVT structures using render module builders
- Serialize and compress built structures (with compression threshold)
- Manage build queue with C1 backpressure and O(log n) eviction
- Circuit breaker for persistent failures per region
- Return results via CompletableFuture

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Builds ESVO/ESVT sparse voxel structures from entity positions within regions.
 *
 * <p>Uses CPU-based builders ({@link OctreeBuilder}, {@link ESVTBuilder}) from the
 * render module. Phase 2 implements the CPU path; GPU acceleration is deferred to
 * Phase 4.
 *
 * <p><b>Build Pipeline</b>:
 * <ol>
 *   <li>Entity world positions normalized to region-local [0,1] coordinates</li>
 *   <li>Quantized to voxel grid ({@code gridResolution^3})</li>
 *   <li>Fed to OctreeBuilder.buildFromVoxels() or ESVTBuilder.buildFromVoxels()</li>
 *   <li>Resulting structure serialized; GZIP-compressed if raw size >= 200 bytes</li>
 *   <li>Wrapped in {@link BuiltRegion} and returned via CompletableFuture</li>
 * </ol>
 *
 * <p><b>C1 Backpressure</b>: Build queue is bounded at {@code MAX_QUEUE_SIZE}.
 * When full, invisible builds are rejected immediately. Visible builds evict
 * the lowest-priority invisible build via O(log n) ConcurrentSkipListSet.
 *
 * <p><b>C3 Circuit Breaker</b>: After {@code CIRCUIT_BREAKER_THRESHOLD} consecutive
 * failures for the same region, that region is blocked from rebuilds for
 * {@code CIRCUIT_BREAKER_COOLDOWN_MS}. The circuit breaker resets when the region
 * becomes clean (entity positions change).
 *
 * <p>Thread model: Fixed-size thread pool of build workers polling from a
 * PriorityBlockingQueue. Each worker creates its own OctreeBuilder/ESVTBuilder
 * instances per build (builders are not thread-safe).
 *
 * <p>Thread-safe: all shared state uses concurrent collections and atomics.
 */
public class RegionBuilder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RegionBuilder.class);

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

    // S2: Separate tracking of invisible builds for O(log n) eviction
    // Reverse-ordered so pollFirst() returns lowest-priority (newest invisible)
    private final ConcurrentSkipListSet<BuildRequest> invisibleBuilds;

    // Pending builds: regionId -> future
    private final ConcurrentHashMap<RegionId, CompletableFuture<BuiltRegion>> pendingBuilds;

    // C3: Circuit breaker per region
    private final ConcurrentHashMap<RegionId, CircuitBreakerState> circuitBreakers;
    static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    static final long CIRCUIT_BREAKER_COOLDOWN_MS = 60_000;

    // Lifecycle
    private final AtomicBoolean shutdown;
    private volatile Clock clock = Clock.system();

    // Metrics
    private final AtomicLong totalBuilds;
    private final AtomicLong totalBuildTimeNs;
    private final AtomicLong evictions;
    private final AtomicLong failedBuilds;  // C3: track failures

    // -- Circuit Breaker State (C3) --

    /**
     * Tracks consecutive build failures per region.
     *
     * <p>After {@code CIRCUIT_BREAKER_THRESHOLD} failures, the circuit opens
     * and blocks retries for {@code CIRCUIT_BREAKER_COOLDOWN_MS}.
     */
    static class CircuitBreakerState {
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicLong circuitOpenUntilMs = new AtomicLong(0);

        boolean isOpen(long nowMs) {
            return nowMs < circuitOpenUntilMs.get();
        }

        void recordFailure(long nowMs) {
            if (failureCount.incrementAndGet() >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpenUntilMs.set(nowMs + CIRCUIT_BREAKER_COOLDOWN_MS);
                log.warn("Circuit breaker opened for region after {} failures, cooldown until {}",
                         failureCount.get(), circuitOpenUntilMs.get());
            }
        }

        void reset() {
            failureCount.set(0);
            circuitOpenUntilMs.set(0);
        }

        int failures() {
            return failureCount.get();
        }
    }

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
     * @param serializedData Compressed serialized voxel data (GZIP or raw)
     * @param compressed     Whether data is GZIP-compressed
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
        boolean compressed,
        int nodeCount,
        int leafCount,
        int lodLevel,
        long buildTimeNs,
        long buildVersion
    ) {
        /** Estimated memory footprint (serialized data dominates). */
        long estimatedSizeBytes() {
            return serializedData.length + 72; // 72 bytes overhead for record fields
        }
    }

    // -- Exception --

    static class BuildQueueFullException extends RuntimeException {
        BuildQueueFullException(String message) { super(message); }
    }

    static class CircuitBreakerOpenException extends RuntimeException {
        CircuitBreakerOpenException(String message) { super(message); }
    }

    // -- Constructor --

    public RegionBuilder(RenderingServerConfig config) {
        this.structureType = config.defaultStructureType();
        this.gridResolution = config.gridResolution();
        this.maxBuildDepth = config.maxBuildDepth();
        this.poolSize = Math.max(1, config.buildPoolSize());

        this.buildQueue = new PriorityBlockingQueue<>(256);
        this.queueSize = new AtomicInteger(0);
        this.invisibleBuilds = new ConcurrentSkipListSet<>(Comparator.reverseOrder());
        this.pendingBuilds = new ConcurrentHashMap<>();
        this.circuitBreakers = new ConcurrentHashMap<>();
        this.shutdown = new AtomicBoolean(false);
        this.totalBuilds = new AtomicLong(0);
        this.totalBuildTimeNs = new AtomicLong(0);
        this.evictions = new AtomicLong(0);
        this.failedBuilds = new AtomicLong(0);

        // Create fixed thread pool for build workers
        this.buildPool = Executors.newFixedThreadPool(poolSize, r -> {
            var thread = new Thread(r, "region-builder-" + Thread.currentThread().threadId());
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
     *   <li>Visible builds evict the lowest-priority invisible build (O(log n))</li>
     *   <li>If all queued builds are visible, the new build is rejected</li>
     * </ul>
     *
     * <p><b>C3 Circuit Breaker</b>: If the region's circuit breaker is open
     * (too many recent failures), the build is rejected with
     * {@link CircuitBreakerOpenException}.
     *
     * @return CompletableFuture completing with the built region, or failing
     *         with BuildQueueFullException or CircuitBreakerOpenException
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

        // C3: Check circuit breaker
        var breaker = circuitBreakers.get(regionId);
        if (breaker != null && breaker.isOpen(clock.currentTimeMillis())) {
            return CompletableFuture.failedFuture(
                new CircuitBreakerOpenException(
                    "Circuit breaker open for region " + regionId
                    + " (failures: " + breaker.failures() + ")"));
        }

        // C1: Backpressure when queue full
        if (queueSize.get() >= MAX_QUEUE_SIZE) {
            if (!visible) {
                return CompletableFuture.failedFuture(
                    new BuildQueueFullException(
                        "Build queue full (" + MAX_QUEUE_SIZE + "), invisible build rejected"));
            }
            // S2: Visible build evicts lowest-priority invisible build via O(log n)
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
        if (!visible) {
            invisibleBuilds.add(request);
        }
        queueSize.incrementAndGet();

        return future;
    }

    /**
     * Clear the circuit breaker for a region.
     * Called when region entity positions change (region becomes clean again).
     */
    void clearCircuitBreaker(RegionId regionId) {
        var breaker = circuitBreakers.get(regionId);
        if (breaker != null) {
            breaker.reset();
        }
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

    /** Total builds completed successfully. */
    long totalBuilds() { return totalBuilds.get(); }

    /** Total builds that failed with exceptions. */
    long failedBuilds() { return failedBuilds.get(); }

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
            pendingBuilds.forEach((k, v) -> v.cancel(true));
            pendingBuilds.clear();
        }
    }

    // -- S2: Evict lowest-priority invisible build via O(log n) --

    private boolean evictLowestPriority() {
        // S2 FIX: O(log n) via ConcurrentSkipListSet instead of O(n) queue scan
        var toEvict = invisibleBuilds.pollFirst();
        if (toEvict != null) {
            buildQueue.remove(toEvict); // O(n) but only on eviction, not every hit
            queueSize.decrementAndGet();
            evictions.incrementAndGet();
            var future = pendingBuilds.remove(toEvict.regionId());
            if (future != null) {
                future.completeExceptionally(
                    new BuildQueueFullException("Evicted for higher priority build"));
            }
            return true;
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
                if (!request.visible()) {
                    invisibleBuilds.remove(request);
                }

                var future = pendingBuilds.get(request.regionId());
                if (future == null || future.isCancelled()) continue;

                try {
                    long startNs = System.nanoTime();
                    var result = doBuild(request);
                    long elapsed = System.nanoTime() - startNs;

                    totalBuilds.incrementAndGet();
                    totalBuildTimeNs.addAndGet(elapsed);

                    // C3: Clear circuit breaker on success
                    clearCircuitBreaker(request.regionId());

                    future.complete(result);
                } catch (Exception e) {
                    // C3: Track failure and update circuit breaker
                    failedBuilds.incrementAndGet();
                    circuitBreakers
                        .computeIfAbsent(request.regionId(), k -> new CircuitBreakerState())
                        .recordFailure(clock.currentTimeMillis());

                    log.warn("Build failed for region {}: {}", request.regionId(), e.getMessage());
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
            return new BuiltRegion(
                request.regionId(), structureType, new byte[0], false,
                0, 0, request.lodLevel(), 0, 0);
        }

        // Step 2: Build the voxel structure
        byte[] serialized;
        int nodeCount;
        int leafCount;

        if (structureType == SparseStructureType.ESVO) {
            serialized = buildAndSerializeESVO(voxels);
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
            SerializationUtils.isCompressed(serialized),
            nodeCount, leafCount, request.lodLevel(),
            elapsed, clock.currentTimeMillis());
    }

    /**
     * Convert world-space entity positions to region-local voxel coordinates.
     *
     * <p>Pattern extracted from portal's RenderService position-to-voxel logic.
     * Verified against portal/src/main/java/.../RenderService.java lines 190-220.
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
            float localX = (pos.x() - bounds.minX()) / size;
            float localY = (pos.y() - bounds.minY()) / size;
            float localZ = (pos.z() - bounds.minZ()) / size;

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
        return Math.max(1, serialized.length / 4);
    }
}
```

### 4.2 RegionCache (Caffeine-based)

**File**: `simulation/src/main/java/.../viz/render/RegionCache.java`

**V2 Changes**:
- Caffeine Cache replaces ConcurrentHashMap + ConcurrentLinkedDeque (C2)
- Hybrid design: Caffeine for unpinned, ConcurrentHashMap for pinned (preserves pinning)
- AtomicBoolean guard for emergency eviction (C4)
- Caffeine recordStats() replaces manual hit/miss tracking
- Caffeine expireAfterAccess() replaces scheduled eviction sweep
- Caffeine maximumWeight() with custom Weigher for memory-based eviction

**Responsibilities:**
- LRU cache of built region data via Caffeine window-TinyLFU
- Multi-LOD support (same region at different LOD levels)
- Pinning for visible regions (exempt from Caffeine eviction)
- M1 emergency eviction under memory pressure (for pinned regions)
- Thread-safe concurrent access

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.viz.render.RegionBuilder.BuiltRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Caffeine-based LRU cache for built ESVO/ESVT region data with memory management.
 *
 * <p><b>V2 Design</b>: Hybrid cache architecture:
 * <ul>
 *   <li><b>Caffeine cache</b>: Unpinned regions. Window-TinyLFU eviction (near-optimal),
 *       O(1) amortized access, memory-based eviction via Weigher, TTL via expireAfterAccess.</li>
 *   <li><b>Pinned map</b>: Visible regions. ConcurrentHashMap, exempt from Caffeine eviction.
 *       Subject only to M1 emergency eviction under extreme memory pressure.</li>
 * </ul>
 *
 * <p>Regions visible to at least one client are pinned (moved to pinned map) and will not be
 * evicted by normal cache pressure. Unpinned regions are evicted by Caffeine automatically.
 *
 * <p>Supports multiple LOD levels per region: a region may be cached at
 * LOD 2 (coarse, for distant viewers) and LOD 5 (fine, for nearby viewers)
 * simultaneously.
 *
 * <p><b>M1 Emergency Eviction</b>: When total memory (Caffeine + pinned) exceeds 90% of
 * max on put(), triggers immediate eviction of unpinned pinned regions down to 75% of max.
 * Only one thread can trigger emergency eviction at a time (C4 fix).
 *
 * <p>Thread-safe via Caffeine (internally synchronized), ConcurrentHashMap, and atomics.
 * No synchronized blocks.
 */
public class RegionCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RegionCache.class);

    // M1: Emergency eviction thresholds
    static final double EMERGENCY_EVICTION_THRESHOLD = 0.90;
    static final double EMERGENCY_EVICTION_TARGET = 0.75;

    // C2: Caffeine cache for unpinned regions (O(1) LRU)
    private final Cache<CacheKey, CachedRegion> cache;

    // Pinned regions (visible to at least one client, exempt from Caffeine eviction)
    private final ConcurrentHashMap<CacheKey, CachedRegion> pinnedCache;

    // Memory tracking for pinned regions (Caffeine tracks its own via Weigher)
    private final AtomicLong pinnedMemoryBytes;
    private final long maxMemoryBytes;

    // C4: Emergency eviction guard -- only one thread at a time
    private final AtomicBoolean emergencyEvicting;

    // Metrics (beyond Caffeine's built-in stats)
    private final AtomicLong evictionCount;
    private final AtomicLong emergencyEvictionCount;

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
        int unpinnedRegions,
        long memoryUsedBytes,
        long pinnedMemoryBytes,
        long memoryMaxBytes,
        long evictions,
        long emergencyEvictions,
        long hits,
        long misses,
        double caffeineHitRate
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
        this.pinnedCache = new ConcurrentHashMap<>();
        this.pinnedMemoryBytes = new AtomicLong(0);
        this.emergencyEvicting = new AtomicBoolean(false);
        this.evictionCount = new AtomicLong(0);
        this.emergencyEvictionCount = new AtomicLong(0);

        // C2: Caffeine cache with memory-based eviction and TTL
        // Allocate 90% of max memory to Caffeine; reserve 10% headroom for pinned
        long caffeineMaxWeight = (long) (config.maxCacheMemoryBytes() * 0.90);

        this.cache = Caffeine.newBuilder()
            .maximumWeight(caffeineMaxWeight)
            .weigher((CacheKey key, CachedRegion region) -> (int) Math.min(region.sizeBytes(), Integer.MAX_VALUE))
            .expireAfterAccess(config.regionTtlMs(), TimeUnit.MILLISECONDS)
            .removalListener((CacheKey key, CachedRegion value, RemovalCause cause) -> {
                if (cause.wasEvicted()) {
                    evictionCount.incrementAndGet();
                    log.debug("Caffeine evicted region {} (cause: {})", key, cause);
                }
            })
            .recordStats()
            .build();
    }

    // -- Public API --

    /**
     * Get a cached region at the specified LOD level.
     *
     * <p>Checks pinned cache first (O(1)), then Caffeine cache (O(1) amortized).
     *
     * @return Optional containing the cached region, or empty if not cached
     */
    Optional<CachedRegion> get(RegionId regionId, int lodLevel) {
        var key = new CacheKey(regionId, lodLevel);

        // Check pinned cache first
        var pinned = pinnedCache.get(key);
        if (pinned != null) {
            pinned.lastAccessedMs().set(clock.currentTimeMillis());
            return Optional.of(pinned);
        }

        // Check Caffeine cache (C2: O(1) amortized, no O(n) deque scan)
        var entry = cache.getIfPresent(key);
        if (entry != null) {
            entry.lastAccessedMs().set(clock.currentTimeMillis());
            return Optional.of(entry);
        }

        return Optional.empty();
    }

    /**
     * Put a built region into the cache.
     *
     * <p>If the region is pinned, stores in pinned cache. Otherwise, stores in Caffeine.
     * Triggers M1 emergency eviction if total memory exceeds 90% threshold.
     * C4: Only one thread can trigger emergency eviction at a time.
     */
    void put(BuiltRegion region) {
        var key = new CacheKey(region.regionId(), region.lodLevel());
        long sizeBytes = region.estimatedSizeBytes();

        // Remove existing entry for this key if present (from either cache)
        removeFromBothCaches(key);

        var entry = new CachedRegion(
            region,
            clock.currentTimeMillis(),
            new AtomicLong(clock.currentTimeMillis()),
            sizeBytes
        );

        // Store in appropriate cache
        if (isPinned(key)) {
            pinnedCache.put(key, entry);
            pinnedMemoryBytes.addAndGet(sizeBytes);
        } else {
            cache.put(key, entry);
            // Caffeine tracks memory via Weigher automatically
        }

        // M1: Emergency eviction if total memory above threshold
        // C4: Only one thread enters emergency eviction
        long totalMemory = totalMemoryUsageBytes();
        if (totalMemory > maxMemoryBytes * EMERGENCY_EVICTION_THRESHOLD) {
            if (emergencyEvicting.compareAndSet(false, true)) {
                try {
                    emergencyEvict();
                } finally {
                    emergencyEvicting.set(false);
                }
            }
        }
    }

    /**
     * Pin a region, preventing it from being evicted by Caffeine.
     * Moves the region from Caffeine cache to pinned cache if present.
     */
    void pin(RegionId regionId, int lodLevel) {
        var key = new CacheKey(regionId, lodLevel);

        // Move from Caffeine to pinned if present
        var entry = cache.getIfPresent(key);
        if (entry != null) {
            cache.invalidate(key);
            pinnedCache.put(key, entry);
            pinnedMemoryBytes.addAndGet(entry.sizeBytes());
        }
        // If not in Caffeine, it may already be pinned or not cached at all
    }

    /**
     * Unpin a region, making it eligible for Caffeine eviction.
     * Moves the region from pinned cache to Caffeine cache.
     */
    void unpin(RegionId regionId, int lodLevel) {
        var key = new CacheKey(regionId, lodLevel);

        var entry = pinnedCache.remove(key);
        if (entry != null) {
            pinnedMemoryBytes.addAndGet(-entry.sizeBytes());
            cache.put(key, entry);
        }
    }

    /**
     * Check if a cache key is currently pinned.
     */
    boolean isPinned(CacheKey key) {
        return pinnedCache.containsKey(key);
    }

    /**
     * Invalidate all LOD levels for a region.
     * Called when entity positions in the region change.
     * Removes from both pinned and Caffeine caches.
     */
    void invalidate(RegionId regionId) {
        // Invalidate from Caffeine
        var caffeineKeys = new ArrayList<CacheKey>();
        cache.asMap().keySet().forEach(key -> {
            if (key.regionId().equals(regionId)) {
                caffeineKeys.add(key);
            }
        });
        cache.invalidateAll(caffeineKeys);

        // Invalidate from pinned cache
        var pinnedKeys = new ArrayList<CacheKey>();
        for (var key : pinnedCache.keySet()) {
            if (key.regionId().equals(regionId)) {
                pinnedKeys.add(key);
            }
        }
        for (var key : pinnedKeys) {
            var removed = pinnedCache.remove(key);
            if (removed != null) {
                pinnedMemoryBytes.addAndGet(-removed.sizeBytes());
            }
        }
    }

    // -- Metrics --

    /** Total memory used by both caches. */
    long totalMemoryUsageBytes() {
        return cache.policy().eviction()
                    .map(eviction -> eviction.weightedSize().orElse(0L))
                    .orElse(0L)
               + pinnedMemoryBytes.get();
    }

    long memoryUsageBytes() { return totalMemoryUsageBytes(); }
    int cachedRegionCount() { return (int) cache.estimatedSize() + pinnedCache.size(); }
    int pinnedRegionCount() { return pinnedCache.size(); }

    CacheStats stats() {
        var caffeineStats = cache.stats();
        long totalHits = caffeineStats.hitCount();
        long totalMisses = caffeineStats.missCount();

        return new CacheStats(
            cachedRegionCount(),
            pinnedCache.size(),
            (int) cache.estimatedSize(),
            totalMemoryUsageBytes(),
            pinnedMemoryBytes.get(),
            maxMemoryBytes,
            evictionCount.get(),
            emergencyEvictionCount.get(),
            totalHits,
            totalMisses,
            caffeineStats.hitRate()
        );
    }

    public void setClock(Clock clock) { this.clock = clock; }

    @Override
    public void close() {
        cache.invalidateAll();
        pinnedCache.clear();
        pinnedMemoryBytes.set(0);
    }

    // -- Internal --

    private void removeFromBothCaches(CacheKey key) {
        cache.invalidate(key);
        var pinnedRemoved = pinnedCache.remove(key);
        if (pinnedRemoved != null) {
            pinnedMemoryBytes.addAndGet(-pinnedRemoved.sizeBytes());
        }
    }

    /**
     * M1: Emergency eviction when total memory exceeds 90% threshold.
     * Evicts unpinned pinned regions in LRU order until below 75% target.
     *
     * <p>Strategy:
     * <ol>
     *   <li>First, force Caffeine cleanup (may reclaim weight)</li>
     *   <li>If still over target, evict oldest unpinned entries from pinnedCache</li>
     *   <li>If ALL entries are pinned and critical, log warning (cannot evict further)</li>
     * </ol>
     */
    private void emergencyEvict() {
        long target = (long) (maxMemoryBytes * EMERGENCY_EVICTION_TARGET);
        emergencyEvictionCount.incrementAndGet();
        log.warn("M1 emergency eviction triggered: memory={}, target={}",
                 totalMemoryUsageBytes(), target);

        // Step 1: Force Caffeine cleanup
        cache.cleanUp();

        // Step 2: If still over target, evict from pinned cache (oldest first)
        while (totalMemoryUsageBytes() > target) {
            // Find oldest unpinned entry in pinnedCache by lastAccessed
            CacheKey oldestKey = null;
            long oldestAccess = Long.MAX_VALUE;

            for (var entry : pinnedCache.entrySet()) {
                long lastAccess = entry.getValue().lastAccessedMs().get();
                if (lastAccess < oldestAccess) {
                    oldestAccess = lastAccess;
                    oldestKey = entry.getKey();
                }
            }

            if (oldestKey != null) {
                var removed = pinnedCache.remove(oldestKey);
                if (removed != null) {
                    pinnedMemoryBytes.addAndGet(-removed.sizeBytes());
                    evictionCount.incrementAndGet();
                    log.debug("Emergency evicted pinned region {}", oldestKey);
                }
            } else {
                // Nothing left to evict
                log.warn("M1 emergency eviction: no evictable entries remain, memory={}",
                         totalMemoryUsageBytes());
                break;
            }
        }
    }
}
```

### 4.3 SerializationUtils (with compression threshold)

**File**: `simulation/src/main/java/.../viz/render/SerializationUtils.java`

**V2 Changes**:
- Compression threshold: skip GZIP for raw data < 200 bytes (A3)
- `isCompressed()` flag to indicate whether data is compressed
- Version header retained for forward-compatibility (A4)

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
 * <p>Provides serialization of voxel structures for cache storage and future
 * streaming (Phase 3). GZIP compression is applied conditionally based on
 * a size threshold to avoid expansion for small regions.
 *
 * <p><b>A3 FIX</b>: Raw data below {@code COMPRESSION_THRESHOLD_BYTES} is
 * returned uncompressed. The first byte indicates compression status:
 * 0x00 = uncompressed, 0x1F = GZIP magic number (auto-detected).
 *
 * <p>Thread-safe: all methods are stateless.
 */
public final class SerializationUtils {

    /** Current serialization format version. */
    static final int VERSION = 1;

    /** Format identifiers. */
    static final byte FORMAT_ESVO = 0x01;
    static final byte FORMAT_ESVT = 0x02;

    /** A3: Skip GZIP compression for raw data below this threshold. */
    static final int COMPRESSION_THRESHOLD_BYTES = 200;

    private SerializationUtils() {}

    // -- Compression detection --

    /**
     * Check if a byte array is GZIP-compressed (magic number 0x1F 0x8B).
     */
    static boolean isCompressed(byte[] data) {
        return data != null && data.length >= 2
            && (data[0] & 0xFF) == 0x1F
            && (data[1] & 0xFF) == 0x8B;
    }

    // -- ESVO Serialization --

    /**
     * Serialize ESVOOctreeData to a byte array.
     * GZIP-compressed if raw size >= COMPRESSION_THRESHOLD_BYTES.
     */
    static byte[] serializeESVO(ESVOOctreeData data) {
        var nodeIndices = data.getNodeIndices();
        int nodeCount = nodeIndices.length;
        var farPointers = data.getFarPointers();

        int headerSize = 25;
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

        // A3: Conditionally compress
        byte[] raw = buffer.array();
        if (raw.length < COMPRESSION_THRESHOLD_BYTES) {
            return raw; // Skip GZIP for small data
        }
        return gzipCompress(raw);
    }

    /**
     * Deserialize byte array back to ESVOOctreeData.
     * Automatically detects GZIP compression.
     */
    static ESVOOctreeData deserializeESVO(byte[] data) {
        byte[] raw = isCompressed(data) ? gzipDecompress(data) : data;
        var buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
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

        var result = new ESVOOctreeData(nodeCount * ESVONodeUnified.SIZE_BYTES);
        for (int i = 0; i < nodeCount; i++) {
            result.setNode(i, ESVONodeUnified.fromByteBuffer(buffer));
        }

        if (farPtrCount > 0) {
            int[] farPointers = new int[farPtrCount];
            for (int i = 0; i < farPtrCount; i++) {
                farPointers[i] = buffer.getInt();
            }
            result.setFarPointers(farPointers);
        }

        result.setMaxDepth(maxDepth);
        result.setLeafCount(leafCount);
        result.setInternalCount(internalCount);

        return result;
    }

    // -- ESVT Serialization --

    /**
     * Serialize ESVTData to a byte array.
     * GZIP-compressed if raw size >= COMPRESSION_THRESHOLD_BYTES.
     */
    static byte[] serializeESVT(ESVTData data) {
        int nodeCount = data.nodeCount();
        var farPointers = data.getFarPointers();
        var contours = data.getContours();

        int headerSize = 25;
        int esvtExtra = 4 + 4 + contours.length * 4 + 4;
        int rawSize = headerSize
                    + nodeCount * ESVTNodeUnified.SIZE_BYTES
                    + farPointers.length * 4
                    + esvtExtra;

        var buffer = ByteBuffer.allocate(rawSize).order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(VERSION);
        buffer.put(FORMAT_ESVT);
        buffer.putInt(nodeCount);
        buffer.putInt(farPointers.length);
        buffer.putInt(data.maxDepth());
        buffer.putInt(data.leafCount());
        buffer.putInt(data.internalCount());

        for (var node : data.nodes()) {
            node.writeTo(buffer);
        }

        for (int fp : farPointers) {
            buffer.putInt(fp);
        }

        buffer.putInt(data.rootType());
        buffer.putInt(contours.length);
        for (int c : contours) {
            buffer.putInt(c);
        }
        buffer.putInt(data.gridResolution());

        // A3: Conditionally compress
        byte[] raw = buffer.array();
        if (raw.length < COMPRESSION_THRESHOLD_BYTES) {
            return raw;
        }
        return gzipCompress(raw);
    }

    /**
     * Deserialize byte array back to ESVTData.
     * Automatically detects GZIP compression.
     */
    static ESVTData deserializeESVT(byte[] data) {
        byte[] raw = isCompressed(data) ? gzipDecompress(data) : data;
        var buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
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

        var nodes = new ESVTNodeUnified[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodes[i] = ESVTNodeUnified.fromByteBuffer(buffer);
        }

        int[] farPointers = new int[farPtrCount];
        for (int i = 0; i < farPtrCount; i++) {
            farPointers[i] = buffer.getInt();
        }

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

## 5. Integration with Phase 1

### 5.1 RenderingServerConfig Changes (S1, A1)

**V2 Changes**:
- `gpuEnabled` removed from Phase 2 config (A1)
- `gpuPoolSize` renamed to `buildPoolSize` (S1)

```java
/**
 * Configuration for the rendering server.
 *
 * <p>V2: Removed gpuEnabled (not used in Phase 2; GPU acceleration is Phase 4).
 * Renamed gpuPoolSize to buildPoolSize for clarity.
 */
public record RenderingServerConfig(
    int port,
    List<UpstreamConfig> upstreams,
    int regionLevel,
    int gridResolution,
    int maxBuildDepth,
    long maxCacheMemoryBytes,
    long regionTtlMs,
    int buildPoolSize,                    // S1: renamed from gpuPoolSize
    SparseStructureType defaultStructureType
) {
    public static RenderingServerConfig defaults() {
        return new RenderingServerConfig(
            7090, List.of(), 4, 64, 8,
            256 * 1024 * 1024L, 30_000L,
            1,                             // 1 build worker thread
            SparseStructureType.ESVO
        );
    }

    public static RenderingServerConfig testing() {
        return new RenderingServerConfig(
            0, List.of(), 2, 16, 4,
            16 * 1024 * 1024L, 5_000L,
            1,
            SparseStructureType.ESVO
        );
    }
}
```

### 5.2 AdaptiveRegionManager Extensions (S3)

Phase 2 adds build scheduling and dirty-region backfill to AdaptiveRegionManager
via setter injection (following the established `setClock()` pattern).

```java
// --- Added to AdaptiveRegionManager ---

private volatile RegionBuilder builder;  // null until Phase 2 wiring
private volatile RegionCache cache;       // null until Phase 2 wiring

/**
 * Set the build pipeline for Phase 2 integration.
 * When set, scheduleBuild() delegates to the builder.
 * When null (Phase 1), scheduleBuild() is a no-op.
 */
public void setBuilder(RegionBuilder builder) { this.builder = builder; }

/**
 * Set the region cache for Phase 2 integration.
 */
public void setCache(RegionCache cache) { this.cache = cache; }

/**
 * S3 FIX: Backfill already-dirty regions after builder/cache are wired.
 *
 * <p>When Phase 2 components are injected dynamically (after entities have
 * already been tracked), dirty regions would otherwise never be built.
 * This method scans for dirty regions and schedules low-priority builds.
 *
 * <p>Called from RenderingServer.start() after setBuilder/setCache.
 */
public void backfillDirtyRegions() {
    if (builder == null) return;

    var dirty = dirtyRegions();
    if (!dirty.isEmpty()) {
        log.info("Backfilling {} dirty regions after builder wiring", dirty.size());
        for (var regionId : dirty) {
            var state = regions.get(regionId);
            if (state != null && !state.entities().isEmpty()) {
                scheduleBuild(regionId, 0, false);  // Invisible, low priority
            }
        }
    }
}

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
 *   <li>Circuit breaker cleared for the region (successful build)</li>
 * </ol>
 */
public CompletableFuture<RegionBuilder.BuiltRegion> scheduleBuild(
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

### 5.3 RenderingServer Extensions

```java
// --- Added to RenderingServer ---

private RegionBuilder regionBuilder;      // S1: renamed from gpuBuilder
private RegionCache regionCache;

// In start():
regionBuilder = new RegionBuilder(config);
regionCache = new RegionCache(config);
regionManager.setBuilder(regionBuilder);
regionManager.setCache(regionCache);
regionManager.backfillDirtyRegions();     // S3: backfill after wiring

// In stop():
if (regionBuilder != null) regionBuilder.close();
if (regionCache != null) regionCache.close();

// Accessor methods:
public RegionBuilder getRegionBuilder() { return regionBuilder; }
public RegionCache getRegionCache() { return regionCache; }

// Clock injection extended:
public void setClock(Clock clock) {
    this.clock = clock;
    this.regionManager.setClock(clock);
    this.entityConsumer.setClock(clock);
    if (regionBuilder != null) regionBuilder.setClock(clock);
    if (regionCache != null) regionCache.setClock(clock);
}

// M2: Metrics endpoint
app.get("/api/metrics", ctx -> {
    var builderStats = Map.of(
        "totalBuilds", regionBuilder.totalBuilds(),
        "failedBuilds", regionBuilder.failedBuilds(),
        "avgBuildTimeNs", regionBuilder.avgBuildTimeNs(),
        "queueDepth", regionBuilder.queueDepth(),
        "evictions", regionBuilder.evictions()
    );
    var cacheStats = regionCache.stats();
    ctx.json(Map.of("builder", builderStats, "cache", cacheStats));
});
```

### 5.4 Maven Dependency (C2)

Add to `simulation/pom.xml` (version managed in root pom.xml):

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

Caffeine 3.1.8 is already declared in root pom.xml `<dependencyManagement>` at line 151.
No version tag needed in module pom.xml.

### 5.5 Wiring Diagram

```
                      RenderingServer
                      (lifecycle, wiring, /api/metrics)
                            |
            +---------------+---------------+
            |               |               |
   EntityStreamConsumer  AdaptiveRegionManager  RegionCache
   (upstream WS client)  (region grid, dirty,   (Caffeine + pinned,
            |             backfillDirtyRegions)   M1 emergency eviction)
            |               |                      ^
            |-- updateEntity -->                   |
                            |                      |
                            |-- scheduleBuild() -->|-- cache miss -->|
                            |                      |                 |
                            |               RegionBuilder            |
                            |               (build queue, workers,   |
                            |                circuit breaker)        |
                            |                      |                 |
                            |               [OctreeBuilder]          |
                            |               [ESVTBuilder]            |
                            |               [SerializationUtils]     |
                            |                      |                 |
                            |                      |-- put() ------->|
                            |<-- completion -------|
```

---

## 6. Sequence Diagrams

### 6.1 Build Pipeline (Entity Update to Cached Region)

```
EntityStreamConsumer       AdaptiveRegionManager      RegionBuilder          RegionCache
      |                              |                      |                     |
      |-- updateEntity(id,x,y,z) -->|                      |                     |
      |                              |-- regionForPosition()|                     |
      |                              |-- mark region dirty  |                     |
      |                              |                      |                     |
      |                              |-- scheduleBuild() -->|                     |
      |                              |                      |-- check circuit     |
      |                              |                      |   breaker (C3)      |
      |                              |                      |-- check queue       |
      |                              |                      |   (C1 backpressure) |
      |                              |                      |-- enqueue request   |
      |                              |                      |   + invisibleBuilds |
      |                              |                      |   if !visible (S2)  |
      |                              |                      |                     |
      |                              |     [build worker picks up request]        |
      |                              |                      |                     |
      |                              |                      |-- positionsToVoxels()|
      |                              |                      |   (normalize+quant) |
      |                              |                      |                     |
      |                              |                      |-- OctreeBuilder     |
      |                              |                      |   .buildFromVoxels() |
      |                              |                      |                     |
      |                              |                      |-- SerializationUtils |
      |                              |                      |   .serializeESVO()   |
      |                              |                      |   (threshold GZIP)   |
      |                              |                      |                     |
      |                              |                      |-- clear circuit     |
      |                              |                      |   breaker (success) |
      |                              |                      |                     |
      |                              |                      |-- complete future-->|
      |                              |<-- BuiltRegion ------|                     |
      |                              |                      |                     |
      |                              |-- cache.put(built) ---------------------->|
      |                              |   (Caffeine or pinned)                    |
      |                              |-- dirty.set(false)   |                     |
      |                              |-- buildVersion++     |                     |
```

### 6.2 C3 Circuit Breaker: Build Failure

```
AdaptiveRegionManager          RegionBuilder                CircuitBreakerState
      |                              |                              |
      |-- scheduleBuild(R1) -------->|                              |
      |                              |-- check breaker.isOpen() -->|
      |                              |<-- false (circuit closed) --|
      |                              |                              |
      |                              |-- doBuild(R1) THROWS ------>|
      |                              |                              |
      |                              |-- failedBuilds++            |
      |                              |-- breaker.recordFailure() ->|
      |                              |                              |-- failures=1
      |                              |                              |
      [... 2 more failures ...]      |                              |
      |                              |                              |-- failures=3
      |                              |                              |-- OPEN until
      |                              |                              |   now + 60s
      |                              |                              |
      |-- scheduleBuild(R1) -------->|                              |
      |                              |-- check breaker.isOpen() -->|
      |                              |<-- true (circuit OPEN) -----|
      |<-- failedFuture(CircuitBreakerOpenException) ---|          |
      |                              |                              |
      [... entity moves in R1 ...]   |                              |
      |                              |                              |
      |-- scheduleBuild(R1) -------->|                              |
      |                              |-- doBuild(R1) succeeds      |
      |                              |-- clearCircuitBreaker(R1) ->|
      |                              |                              |-- reset()
      |                              |                              |-- failures=0
```

### 6.3 S2: O(log n) Backpressure Eviction

```
Caller                          RegionBuilder
  |                                  |
  |-- build(R101, VISIBLE) --------->|
  |                                  |-- queueSize >= MAX_QUEUE_SIZE?
  |                                  |   YES, but request is visible
  |                                  |
  |                                  |-- evictLowestPriority()
  |                                  |   invisibleBuilds.pollFirst()  O(log n)
  |                                  |   -> removes newest invisible R50
  |                                  |   buildQueue.remove(R50)       O(n) but rare
  |                                  |   R50.future.failFuture(Evicted)
  |                                  |
  |                                  |-- enqueue R101 (visible)
  |<-- CompletableFuture<Built> -----|
```

### 6.4 C4: Emergency Eviction Guard

```
Thread A                    RegionCache                    Thread B
  |                              |                              |
  |-- put(R7, 10MB) ----------->|                              |
  |                              |-- totalMemory = 235MB        |
  |                              |-- 235/256 = 91.8% > 90%     |
  |                              |                              |
  |                              |-- emergencyEvicting          |
  |                              |   .compareAndSet(false,true) |
  |                              |   -> SUCCESS                 |
  |                              |                              |-- put(R8, 8MB)
  |                              |                              |-- totalMemory = 243MB
  |                              |                              |-- 243/256 = 94.9% > 90%
  |                              |                              |
  |                              |                              |-- emergencyEvicting
  |                              |                              |   .compareAndSet(false,true)
  |                              |                              |   -> FAILS (already true)
  |                              |                              |   [Thread B skips eviction]
  |                              |                              |
  |                              |-- emergencyEvict()           |
  |                              |   target = 192MB             |
  |                              |   evict unpinned regions...  |
  |                              |   until <= 192MB             |
  |                              |                              |
  |                              |-- emergencyEvicting          |
  |                              |   .set(false)                |
  |                              |                              |
  |                              |   [NO OVER-EVICTION]         |
```

---

## 7. Testing Strategy

### 7.1 Test Classes and Coverage (S4)

| Test Class | Tests | Focus |
|------------|-------|-------|
| `RegionBuilderTest` | 15 | Build pipeline, C1 backpressure, C3 circuit breaker, S2 O(log n) eviction |
| `RegionCacheTest` | 15 | Caffeine LRU, pinning, C4 emergency eviction guard, multi-LOD |
| `SerializationUtilsTest` | 7 | Round-trip, compression threshold, version validation |
| `BuildIntegrationTest` | 8 | Full pipeline, backfill, performance gates, degradation |

**Total: 45 tests** (up from 30 in V1; 27 LOC/test at 1,240 LOC production)

### 7.2 RegionBuilderTest (15 tests)

```java
class RegionBuilderTest {

    // --- Build correctness (3) ---

    @Test void testBuildESVO_producesNonEmptyResult() {
        // Given: 10 entity positions in a region
        // When: build(region, positions, bounds, lod=0, visible=true)
        // Then: BuiltRegion has non-empty serializedData, nodeCount > 0
    }

    @Test void testBuildESVT_producesNonEmptyResult() {
        // Same as above but with SparseStructureType.ESVT config
    }

    @Test void testBuildEmptyRegion_returnsEmptyResult() {
        // Given: no entity positions
        // When: build with empty list
        // Then: BuiltRegion has empty serializedData, nodeCount = 0
    }

    // --- Position conversion (2) ---

    @Test void testPositionsToVoxels_normalizesCorrectly() {
        // Given: bounds [100, 200] (size 100), gridResolution 10
        //        position at (150, 150, 150) -> local (0.5, 0.5, 0.5) -> voxel (5, 5, 5)
        // When: positionsToVoxels()
        // Then: Point3i(5, 5, 5)
    }

    @Test void testPositionsToVoxels_clampsOutOfBounds() {
        // Given: position outside region bounds
        // When: positionsToVoxels()
        // Then: clamped to [0, gridResolution-1]
    }

    // --- C1 Backpressure (3) ---

    @Test void testQueueBackpressure_rejectsInvisibleWhenFull() {
        // Given: queue filled to MAX_QUEUE_SIZE with visible builds
        // When: submit invisible build
        // Then: future completes exceptionally with BuildQueueFullException
    }

    @Test void testQueueBackpressure_evictsInvisibleForVisible_OLogN() {
        // Given: queue filled to MAX_QUEUE_SIZE with mix of visible/invisible
        // When: submit visible build
        // Then: one invisible build evicted via invisibleBuilds.pollFirst()
        //       new visible build enqueued
    }

    @Test void testQueueDepthNeverExceedsMax() {
        // Given: rapid-fire 2000 build requests
        // When: builds accumulate
        // Then: queueDepth() <= MAX_QUEUE_SIZE at all times
    }

    // --- C3 Circuit Breaker (3) --- [NEW in V2]

    @Test void testBuildFailure_circuitBreakerActivates() {
        // Given: OctreeBuilder configured to throw on specific region
        // When: 3 consecutive build failures for same region
        // Then: circuit breaker opens, subsequent builds fail with CircuitBreakerOpenException
        //       failedBuilds() == 3
    }

    @Test void testBuildFailure_circuitBreakerResets() {
        // Given: circuit breaker open for region R1
        // When: clearCircuitBreaker(R1) called (entity positions changed)
        // Then: subsequent builds for R1 are accepted again
    }

    @Test void testBuildFailure_metricsTracked() {
        // Given: build that throws exception
        // When: build completes exceptionally
        // Then: failedBuilds() incremented, totalBuilds() unchanged
    }

    // --- S2 Queue Priority (1) --- [NEW in V2]

    @Test void testBackpressure_evictsLowestPriorityInvisible_OLogN() {
        // Given: queue with 5 invisible builds at timestamps T1-T5
        // When: queue full, visible build submitted
        // Then: invisible build at T5 (newest = lowest priority) evicted
        //       O(log n) verified by measuring no linear scan
    }

    // --- Concurrency (2) ---

    @Test void testConcurrentBuilds_noCorruption() {
        // Given: 4 build threads
        // When: submit 100 builds concurrently
        // Then: all futures complete, no exceptions, metrics consistent
    }

    @Test void testLargeRegionStress_1000Voxels() {
        // Given: 1000 entity positions in a region
        // When: build at 64^3 resolution
        // Then: build completes within 5 seconds, result deserializable
    }

    // --- Lifecycle (1) ---

    @Test void testClose_failsPendingBuilds() {
        // Given: pending builds in queue
        // When: close()
        // Then: all pending futures are cancelled
    }
}
```

### 7.3 RegionCacheTest (15 tests)

```java
class RegionCacheTest {

    // --- Basic operations (3) ---

    @Test void testPutAndGet_basicOperation() {
        // Given: cache with 16MB limit
        // When: put(builtRegion), get(same key)
        // Then: returns Optional with the same data
    }

    @Test void testGet_miss_returnsEmpty() {
        // Given: empty cache
        // When: get(unknownRegion, lod=0)
        // Then: returns Optional.empty()
    }

    @Test void testMemoryTracking_accurate() {
        // Given: empty cache
        // When: put region (100 bytes), put region (200 bytes)
        // Then: totalMemoryUsageBytes() reflects both regions
        // When: invalidate first region
        // Then: totalMemoryUsageBytes() reduced accordingly
    }

    // --- Caffeine LRU (2) ---

    @Test void testLRUEviction_caffeineEvictsOldestUnpinned() {
        // Given: cache with small maxWeight, filled with 3 unpinned regions
        // When: put a 4th region exceeding weight
        // Then: Caffeine evicts oldest region automatically
        //       evictionCount incremented
    }

    @Test void testTTLEviction_caffeineRemovesExpiredEntries() {
        // Given: region cached, TTL = 5s
        // When: wait for expiration (Caffeine handles internally)
        // Then: get() returns empty for expired region
    }

    // --- Pinning (4) --- [2 NEW in V2]

    @Test void testPinning_preventsEviction() {
        // Given: cache at capacity, region R1 pinned
        // When: put triggers Caffeine eviction
        // Then: R1 survives (in pinnedCache), unpinned region evicted instead
    }

    @Test void testMultiLOD_sameRegionDifferentLODs() {
        // Given: region R1 at LOD 0 and LOD 2
        // When: get(R1, lod=0) and get(R1, lod=2)
        // Then: both return different data, independent entries
    }

    @Test void testPin_movesBetweenCaches() {
        // Given: region in Caffeine cache (unpinned)
        // When: pin(region)
        // Then: region moved to pinnedCache, not in Caffeine
        //       totalMemoryUsageBytes() unchanged
    }

    @Test void testUnpin_movesBetweenCaches() {
        // Given: region in pinnedCache (pinned)
        // When: unpin(region)
        // Then: region moved to Caffeine cache, not in pinnedCache
        //       totalMemoryUsageBytes() unchanged
    }

    // --- Cache invalidation (2) --- [1 NEW in V2]

    @Test void testInvalidate_removesAllLODs() {
        // Given: R1 cached at LOD 0, 1, 2 (mix of pinned and unpinned)
        // When: invalidate(R1)
        // Then: all 3 entries removed from both caches, memory freed
    }

    @Test void testCacheInvalidationOnDirty() {
        // Given: region R1 cached, then entity moves (region dirty)
        // When: scheduleBuild checks dirty flag
        // Then: cache invalidated before rebuild
    }

    // --- M1 Emergency eviction (2) --- [1 NEW in V2]

    @Test void testEmergencyEviction_triggersAbove90Percent() {
        // Given: cache maxMemory = 1000 bytes
        // When: put entries totaling > 900 bytes (using pinned cache to bypass Caffeine)
        // Then: emergency eviction reduces to ~750 bytes
        //       emergencyEvictionCount incremented
    }

    @Test void testConcurrentEmergencyEviction_onlyOneThreadEvicts() {
        // Given: cache near 90% threshold
        // When: 4 threads simultaneously put() regions exceeding threshold
        // Then: emergencyEvictionCount == 1 (not 4)
        //       AtomicBoolean guard prevents concurrent entry
    }

    // --- Caffeine stats (1) --- [NEW in V2]

    @Test void testCaffeineStats_hitMissTracking() {
        // Given: Caffeine cache with recordStats()
        // When: 5 hits + 3 misses
        // Then: stats().hits == 5, stats().misses == 3
        //       stats().caffeineHitRate approx 0.625
    }

    // --- Concurrency (1) ---

    @Test void testConcurrentAccess_noCorruption() {
        // Given: 4 threads doing put/get/pin/unpin/invalidate simultaneously
        // When: 1000 operations per thread
        // Then: no exceptions, memory tracking consistent
    }
}
```

### 7.4 SerializationUtilsTest (7 tests)

```java
class SerializationUtilsTest {

    @Test void testESVORoundTrip_preservesData() {
        // Given: ESVOOctreeData with 10 nodes
        // When: serialize -> deserialize
        // Then: node count, far pointers, depth all match
    }

    @Test void testESVTRoundTrip_preservesData() {
        // Given: ESVTData with 10 nodes, contours, far pointers
        // When: serialize -> deserialize
        // Then: all fields match
    }

    @Test void testGZIPCompression_reducesByteSize() {
        // Given: repetitive byte array (1000 bytes)
        // When: gzipCompress()
        // Then: compressed size < 1000
    }

    @Test void testGZIPRoundTrip_preservesContent() {
        // Given: random byte array
        // When: compress -> decompress
        // Then: identical to original
    }

    @Test void testVersionValidation_rejectsWrongVersion() {
        // Given: serialized data with version 99
        // When: deserializeESVO()
        // Then: throws IllegalArgumentException
    }

    @Test void testFormatValidation_rejectsWrongFormat() {
        // Given: ESVT data passed to deserializeESVO()
        // When: deserializeESVO()
        // Then: throws IllegalArgumentException
    }

    @Test void testCompressionThreshold_skipsGzipForSmallRegions() {
        // Given: small voxel data producing < 200 bytes raw
        // When: serializeESVO()
        // Then: result is NOT GZIP-compressed (no 0x1F 0x8B header)
        //       isCompressed() returns false
        // Given: large voxel data producing >= 200 bytes raw
        // When: serializeESVO()
        // Then: result IS GZIP-compressed
        //       isCompressed() returns true
    }
}
```

### 7.5 BuildIntegrationTest (8 tests)

```java
class BuildIntegrationTest {

    @Test void testEntityUpdateTriggersScheduleBuild() {
        // Given: RenderingServer with Phase 2 components wired
        // When: updateEntity("e1", 100, 100, 100, "PREY")
        //       scheduleBuild(region, lod=0, visible=true)
        // Then: BuiltRegion returned, cache populated
    }

    @Test void testCacheHitSkipsBuild() {
        // Given: region already built and cached
        // When: scheduleBuild(sameRegion, sameLod) with dirty=false
        // Then: returns cached result, no build executed
    }

    @Test void testDirtyRegionInvalidatesCacheAndRebuilds() {
        // Given: region built, cached, then entity moved (dirty=true)
        // When: scheduleBuild()
        // Then: cache invalidated, rebuild executed, new result cached
    }

    @Test void testFullPipeline_multipleEntitiesMultipleRegions() {
        // Given: 50 entities across 5 regions
        // When: scheduleBuild for each dirty region
        // Then: 5 BuiltRegion results, all cached, all verifiable via deserialization
    }

    // --- S3: Backfill --- [NEW in V2]

    @Test void testSetBuilder_backfillsDirtyRegions() {
        // Given: AdaptiveRegionManager with 3 dirty regions (builder == null)
        // When: setBuilder(builder), setCache(cache), backfillDirtyRegions()
        // Then: all 3 regions have builds scheduled
        //       eventually all 3 are cached
    }

    // --- C1: Performance gates --- [NEW in V2]

    @Test void testPerformanceGate_buildLatencyP50Under50ms() {
        // Given: 20 builds of 100-voxel regions at 64^3 resolution
        // When: measure build times
        // Then: P50 build time < 50ms
    }

    @Test void testPerformanceGate_buildLatencyP99Under200ms() {
        // Given: 100 builds of 100-voxel regions at 64^3 resolution
        // When: measure build times
        // Then: P99 build time < 200ms
    }

    // --- M4: Graceful degradation --- [NEW in V2]

    @Test void testGracefulDegradation_queueSaturation() {
        // Given: builder with slow builds (simulated), queue at 80% capacity
        // When: flood with invisible builds until saturated
        // Then: invisible builds rejected, visible builds still accepted
        //       no exceptions, no deadlocks, system recovers when queue drains
    }
}
```

---

## 8. Performance Considerations

### 8.1 Day 0 Benchmark Prerequisite (C1)

**Before Day 1**: Create benchmark bead to measure actual build performance.

```java
// OctreeBuilderBenchmark.java (test class, not JMH -- quick validation)
class OctreeBuilderBenchmark {

    @Test void benchmarkBuildFromVoxels() {
        int[] voxelCounts = {10, 100, 1000};
        int[] resolutions = {16, 64, 128};

        for (int resolution : resolutions) {
            for (int count : voxelCounts) {
                var voxels = generateRandomVoxels(count, resolution);
                long[] times = new long[20]; // 20 iterations

                for (int i = 0; i < 20; i++) {
                    try (var builder = new OctreeBuilder(8)) {
                        long start = System.nanoTime();
                        builder.buildFromVoxels(voxels, 8);
                        times[i] = System.nanoTime() - start;
                    }
                }

                Arrays.sort(times);
                long p50 = times[10] / 1_000_000;  // ms
                long p99 = times[19] / 1_000_000;  // ms

                System.out.printf("Resolution=%d, Voxels=%d: P50=%dms, P99=%dms%n",
                                  resolution, count, p50, p99);

                // Quality gates
                if (resolution == 64 && count == 100) {
                    assertTrue(p50 < 50, "P50 build time should be < 50ms, got " + p50);
                    assertTrue(p99 < 200, "P99 build time should be < 200ms, got " + p99);
                }
            }
        }
    }
}
```

**Performance Quality Gates** (C1):
- P50 < 50ms for 100-voxel region at 64^3 resolution
- P99 < 200ms for 100-voxel region at 64^3 resolution
- If builds exceed 100ms P50: increase buildPoolSize to 4 or reduce gridResolution to 32

**Fallback Documentation** (C1):
- If P50 > 100ms at 64^3: Set `buildPoolSize = max(1, availableProcessors / 2)`
- If still > 100ms: Reduce `gridResolution` to 32
- If P99 > 500ms: Consider deferring non-visible builds entirely

### 8.2 Build Throughput

**Expected performance** (to be validated by Day 0 benchmark):

| Metric | 16^3 grid | 64^3 grid | 128^3 grid |
|--------|-----------|-----------|------------|
| Voxel count (worst case) | 4,096 | 262,144 | 2,097,152 |
| OctreeBuilder time | ~2ms | ~20ms | ~200ms |
| ESVTBuilder time | ~5ms | ~50ms | ~500ms |
| Serialization | ~1ms | ~5ms | ~20ms |
| GZIP compression | ~1ms | ~3ms | ~10ms |
| **Total build time** | **~9ms** | **~78ms** | **~730ms** |

### 8.3 Cache Performance (C2 improvement)

| Operation | V1 (ConcurrentLinkedDeque) | V2 (Caffeine) |
|-----------|---------------------------|---------------|
| get() hit | O(n) deque remove + O(1) addLast | O(1) amortized |
| put() | O(1) + O(n) eviction check | O(1) amortized |
| Eviction | Manual LRU scan | Automatic window-TinyLFU |
| TTL | ScheduledExecutorService sweep | Built-in expireAfterAccess |
| Stats | Manual AtomicLongs | Built-in recordStats() |

### 8.4 Thread Pool Sizing

| Config | Build Threads | Rationale |
|--------|--------------|-----------|
| `buildPoolSize=1` (default) | 1 | Conservative; no thread contention |
| `buildPoolSize=2` | 2 | Good for 4-core machines (50% CPU for builds) |
| `buildPoolSize=4` | 4 | Good for 8+ core machines |

### 8.5 Tuning Parameters

| Parameter | Location | Default | Tuning Notes |
|-----------|----------|---------|-------------|
| `MAX_QUEUE_SIZE` | RegionBuilder | 1000 | Increase if builds are fast and entity flood is sustained |
| `CIRCUIT_BREAKER_THRESHOLD` | RegionBuilder | 3 | Lower for faster failure detection; higher for more tolerance |
| `CIRCUIT_BREAKER_COOLDOWN_MS` | RegionBuilder | 60,000 | Lower for faster retry; higher for longer backoff |
| `EMERGENCY_EVICTION_THRESHOLD` | RegionCache | 0.90 | Lower for more headroom; raise for higher utilization |
| `EMERGENCY_EVICTION_TARGET` | RegionCache | 0.75 | How aggressively to free memory during emergency |
| `COMPRESSION_THRESHOLD_BYTES` | SerializationUtils | 200 | Below this, skip GZIP to avoid expansion |
| `gridResolution` | Config | 64 | Higher = more detail, slower builds |
| `maxBuildDepth` | Config | 8 | Higher = deeper trees, more nodes |
| `maxCacheMemoryBytes` | Config | 256MB | Size to available RAM |
| `regionTtlMs` | Config | 30,000 | Caffeine expireAfterAccess duration |
| `buildPoolSize` | Config | 1 | Number of concurrent build workers |

---

## 9. Error Handling

### 9.1 Build Failures (C3 enhanced)

| Failure Mode | Handling | Recovery |
|-------------|---------|----------|
| OctreeBuilder throws | Future completed exceptionally; failedBuilds++ | Circuit breaker tracks; after 3 failures, 60s cooldown |
| ESVTBuilder throws | Same as above | Same |
| Serialization fails | Future completed exceptionally; failedBuilds++ | Circuit breaker; indicates data corruption |
| Queue full (invisible) | Rejected immediately | Build deferred; region stays dirty |
| Queue full (visible) | Evict invisible O(log n), then enqueue | Best-effort visible builds |
| Circuit breaker open | Rejected with CircuitBreakerOpenException | Auto-resets after cooldown or when region becomes clean |
| Builder shutdown | Future cancelled | Server is shutting down |

### 9.2 Cache Failures (C4 enhanced)

| Failure Mode | Handling | Recovery |
|-------------|---------|----------|
| Memory exceeds 90% | M1 emergency eviction (single thread via C4 guard) | Evict to 75% target |
| All entries pinned | Emergency eviction evicts oldest pinned | Log warning; extreme case only |
| Concurrent emergency eviction | AtomicBoolean guard; only one thread enters | Other threads skip; no over-eviction |
| Caffeine eviction | Automatic via window-TinyLFU | No action needed |
| Caffeine TTL expiration | Automatic via expireAfterAccess | No action needed |

---

## 10. Metrics and Observability (M2)

### 10.1 Exposed Metrics

**Endpoint**: `GET /api/metrics`

```json
{
  "builder": {
    "totalBuilds": 1234,
    "failedBuilds": 5,
    "avgBuildTimeNs": 45000000,
    "queueDepth": 12,
    "evictions": 3
  },
  "cache": {
    "totalRegions": 450,
    "pinnedRegions": 30,
    "unpinnedRegions": 420,
    "memoryUsedBytes": 180000000,
    "pinnedMemoryBytes": 15000000,
    "memoryMaxBytes": 268435456,
    "evictions": 89,
    "emergencyEvictions": 2,
    "hits": 5000,
    "misses": 200,
    "caffeineHitRate": 0.96
  }
}
```

### 10.2 Profiling Strategy (M3)

| Tool | Use Case | When |
|------|---------|------|
| JFR (Java Flight Recorder) | Production profiling | Always-on with minimal overhead (~2%) |
| async-profiler | Development CPU profiling | When investigating hotspots |
| Caffeine stats | Cache efficiency | Exposed via /api/metrics |
| AtomicLong counters | Build pipeline metrics | Exposed via /api/metrics |

**Key instrumentation points**:
- Build start/end (already in buildWorkerLoop)
- Cache put/get latency (Caffeine recordStats)
- Emergency eviction frequency (emergencyEvictionCount)
- Circuit breaker activations (log.warn)

---

## 11. Graceful Degradation (M4)

| Scenario | Behavior | Recovery |
|----------|----------|---------|
| Queue saturated (>80% full) | Log warning; reject invisible builds | Queue drains as builds complete |
| Queue 100% full, all visible | Reject new visible build with BuildQueueFullException | Caller retries on next entity update |
| Cache 100% pinned | M1 emergency eviction evicts oldest pinned | Extreme case; log warning |
| Build thread pool exhausted | Builds queue up; no timeout on individual builds | Queue bounds prevent OOM |
| OctreeBuilder memory leak | Try-with-resources ensures cleanup | Circuit breaker stops retries if builds consistently fail |
| Build takes > 5 seconds | Worker thread blocked; other workers continue | Queue bounds limit accumulation |
| Caffeine over-capacity | Automatic eviction via window-TinyLFU | No action needed |

---

## 12. File Layout

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - existing]
    RenderingServer.java              -- Extended: wires Phase 2, /api/metrics
    RenderingServerConfig.java        -- MODIFIED: gpuEnabled removed, buildPoolSize
    EntityStreamConsumer.java         -- Unchanged
    AdaptiveRegionManager.java        -- Extended: scheduleBuild, backfillDirtyRegions
    RegionId.java                     -- Unchanged
    RegionBounds.java                 -- Unchanged
    EntityPosition.java               -- Unchanged
    UpstreamConfig.java               -- Unchanged
    SparseStructureType.java          -- Unchanged

    [Phase 2 - new]
    RegionBuilder.java                -- Build pipeline, C1 backpressure, C3 circuit breaker
    RegionCache.java                  -- Caffeine LRU cache, pinning, M1 emergency eviction
    SerializationUtils.java           -- ESVO/ESVT serialization, compression threshold

simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - existing, unchanged]
    RenderingServerTest.java
    EntityStreamConsumerTest.java
    AdaptiveRegionManagerTest.java
    RegionIdTest.java
    RegionBoundsTest.java
    RenderingServerIntegrationTest.java

    [Phase 2 - new]
    RegionBuilderTest.java            -- 15 tests
    RegionCacheTest.java              -- 15 tests
    SerializationUtilsTest.java       -- 7 tests
    BuildIntegrationTest.java         -- 8 tests
```

---

## 13. Risk Assessment

| Risk | Severity | Likelihood | Mitigation | Status |
|------|----------|------------|------------|--------|
| OctreeBuilder memory leak (unclosed) | Medium | Medium | Always use try-with-resources | MITIGATED |
| ESVTBuilder coordinate transform failures | Medium | Low | ESVTBuilder logs and skips; round-trip test | MITIGATED |
| Build queue starvation (all visible) | Medium | Low | C1 handles: reject if all visible | MITIGATED |
| Emergency eviction cascade | Low | Medium | C4: AtomicBoolean guard prevents concurrent entry | **FIXED in V2** |
| Caffeine weight calculation overflow | Low | Low | Math.min(sizeBytes, Integer.MAX_VALUE) in Weigher | MITIGATED |
| GZIP expansion for small regions | Low | High | A3: Compression threshold at 200 bytes | **FIXED in V2** |
| Build failure retry storm | Medium | Medium | C3: Circuit breaker (3 failures, 60s cooldown) | **FIXED in V2** |
| Persistent build failure | Medium | Low | Circuit breaker + failedBuilds metric + log.warn | **FIXED in V2** |
| Dirty region backfill on dynamic wiring | Medium | Medium | S3: backfillDirtyRegions() after setBuilder | **FIXED in V2** |
| Queue saturation O(n) eviction | Low | Medium | S2: ConcurrentSkipListSet O(log n) pollFirst | **FIXED in V2** |
| Portal position-to-voxel mismatch | Low | Low | A2: Verification bead in Day 0 | MITIGATED |

---

## 14. Decisions Summary

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| D1 | Build pipeline technology | CPU-only (OctreeBuilder/ESVTBuilder) | No GPU build infrastructure exists; CPU builders are proven |
| D2 | Thread pool for builds | Fixed thread pool, configurable `buildPoolSize` | CPU-bound work; avoid thread contention |
| D3 | Cache implementation | **Caffeine** (V2 change) | O(1) amortized LRU via window-TinyLFU; already in dependencyManagement |
| D4 | Memory estimation | serializedData.length + 72 bytes overhead | Exact measurement of stored data |
| D5 | C1 eviction strategy | O(log n) via ConcurrentSkipListSet | V2 fix: invisible eviction now O(log n) instead of O(n) |
| D6 | M1 threshold | 90% trigger, 75% target, C4 guard | V2 fix: AtomicBoolean prevents concurrent eviction |
| D7 | Serialization format | Version-header + conditional GZIP | V2 fix: skip GZIP below 200 bytes |
| D8 | Phase 1 integration | Setter injection + backfill | V2 fix: backfillDirtyRegions() |
| D9 | Build failure handling | Circuit breaker (3 failures, 60s) | V2 addition: prevents retry storms |
| D10 | Naming | RegionBuilder, buildPoolSize | V2 fix: no misleading GPU prefix |
| D11 | Pinning with Caffeine | Hybrid: Caffeine unpinned + ConcurrentHashMap pinned | Caffeine lacks native pinning; hybrid preserves semantics |

---

## 15. Quality Gates

Phase 2 must pass all of these before proceeding to Phase 3:

### Correctness
- [ ] RegionBuilder builds correct ESVO from entity positions (verified via serialization round-trip)
- [ ] RegionBuilder builds correct ESVT from entity positions (verified via serialization round-trip)
- [ ] CPU build path works (no GPU/OpenCL required)
- [ ] C1: Build queue never exceeds MAX_QUEUE_SIZE under entity flood
- [ ] RegionCache Caffeine LRU eviction works correctly under memory pressure
- [ ] RegionCache pinning prevents eviction of pinned regions
- [ ] M1: Emergency eviction triggers above 90% and reduces to 75%
- [ ] Multi-LOD: Same region cached at different LOD levels independently
- [ ] Serialization round-trip preserves all ESVO/ESVT data fields (nodeCount, leafCount, farPointers match)
- [ ] All Phase 1 tests continue to pass (no regressions)
- [ ] All 45 Phase 2 tests pass (`mvn test -pl simulation`)

### Performance (C1)
- [ ] Build latency P50 < 50ms for 100-voxel region at 64^3 resolution
- [ ] Build latency P99 < 200ms for 100-voxel region at 64^3 resolution
- [ ] Cache hit latency P99 < 5ms with 1000 cached regions
- [ ] Queue depth stays < 500 under sustained 50 regions/sec load for 60 seconds

### Resilience (C3, C4)
- [ ] Circuit breaker activates after 3 failures, blocks retries for 60s
- [ ] Circuit breaker resets on clean region or cooldown expiry
- [ ] Concurrent emergency eviction: only one thread enters (C4 guard)
- [ ] failedBuilds metric accurately counts all build exceptions

### Observability (M2)
- [ ] /api/metrics endpoint returns builder + cache stats in < 50ms
- [ ] All time-dependent code uses injected Clock (zero System.currentTimeMillis() in production code)
- [ ] All tests use TestClock for determinism

### Code Standards
- [ ] No `synchronized` blocks (concurrent collections only)
- [ ] Clock injection for all time-dependent code
- [ ] Dynamic ports in all tests (port 0)
- [ ] SLF4J logging with `{}` placeholders
- [ ] Caffeine dependency added to simulation/pom.xml (no version tag, managed by root)
- [ ] All classes named correctly (RegionBuilder, not GpuESVOBuilder)

---

## 16. Schedule (S5: 9.5 days)

### Day 0: Benchmark OctreeBuilder (C1 prerequisite) [NEW in V2]

**Tasks**:
- Create OctreeBuilderBenchmark test class
- Measure buildFromVoxels() for 10/100/1000 voxels at 16^3/64^3/128^3
- Verify portal RenderService position-to-voxel pattern (A2)
- Document benchmark results
- Validate performance quality gates pass

**Exit Criteria**: Benchmark results documented; P50 < 50ms and P99 < 200ms at 64^3/100 voxels confirmed; portal pattern verified.

**Fallback**: If P50 > 100ms, adjust default buildPoolSize to 4 or reduce gridResolution to 32.

### Day 1-2: SerializationUtils + benchmark analysis

**Tasks**:
- Implement SerializationUtils with compression threshold (A3)
- Implement all 7 SerializationUtilsTest cases
- Add Caffeine dependency to simulation/pom.xml
- Analyze Day 0 benchmark results; adjust parameters if needed

**Exit Criteria**: SerializationUtils compiles, all 7 tests pass, compression threshold working.

### Day 3-4: RegionBuilder (renamed from GpuESVOBuilder)

**Tasks**:
- Implement RegionBuilder with C1 backpressure
- Implement CircuitBreakerState (C3)
- Implement ConcurrentSkipListSet invisible queue (S2)
- Implement all 15 RegionBuilderTest cases
- Build worker loop, doBuild pipeline

**Exit Criteria**: All 15 RegionBuilder tests pass; C1 backpressure verified; C3 circuit breaker verified; S2 O(log n) eviction verified.

### Day 5-6: RegionCache with Caffeine

**Tasks**:
- Implement RegionCache with Caffeine hybrid design (C2)
- Implement AtomicBoolean emergency eviction guard (C4)
- Implement pin/unpin with cross-cache movement
- Implement all 15 RegionCacheTest cases

**Exit Criteria**: All 15 RegionCache tests pass; Caffeine LRU verified; C4 concurrent eviction guard verified; pinning works correctly.

### Day 7-8: Integration + backfill

**Tasks**:
- Modify RenderingServerConfig (remove gpuEnabled, rename buildPoolSize)
- Extend AdaptiveRegionManager with scheduleBuild, backfillDirtyRegions (S3)
- Extend RenderingServer wiring and /api/metrics endpoint (M2)
- Implement all 8 BuildIntegrationTest cases
- Run Phase 1 regression tests

**Exit Criteria**: Integration wiring works; backfillDirtyRegions verified; all Phase 1 tests still pass; /api/metrics returns valid data.

### Day 9-9.5: Testing + buffer

**Tasks**:
- Run full test suite (45 Phase 2 + 43 Phase 1 = 88 tests)
- Validate all quality gates (correctness, performance, resilience, observability)
- Fix any issues found
- Document any deviations from plan
- Performance regression check

**Exit Criteria**: All 88 tests pass; all quality gates met; Phase 2 complete.

### Buffer: 2 days (21%)

Days 9-9.5 include buffer time for:
- Integration issues between components
- Caffeine configuration tuning
- Performance gate failures requiring parameter adjustment
- Unexpected API mismatches with render module

---

## Appendix A: Code Reuse Strategy

### A.1 Direct Reuse (Import Unchanged)

| Class | Module | Usage in Phase 2 |
|-------|--------|-------------------|
| `OctreeBuilder` | render | `buildFromVoxels(List<Point3i>, int)` |
| `ESVTBuilder` | render | `buildFromVoxels(List<Point3i>, int, int)` |
| `ESVOOctreeData` | render | Container for ESVO structure |
| `ESVTData` | render | Container for ESVT structure |
| `ESVONodeUnified` | render | 8-byte ESVO node; `writeTo/fromByteBuffer` |
| `ESVTNodeUnified` | render | 8-byte ESVT node; `writeTo/fromByteBuffer` |
| `Point3i` | common | Voxel coordinate type |
| `Clock` | simulation | Deterministic time |
| `Caffeine` | com.github.ben-manes | Cache library (already in dependencyManagement) |

### A.2 Not Used in Phase 2

| Component | Why Not |
|-----------|---------|
| `ESVTOpenCLRenderer` | GPU renderer, not builder. Phase 4. |
| `AbstractOpenCLRenderer` | Rendering, not building. |
| `GpuService` / `GpuSessionState` | Session-based GPU contexts for portal. |

---

## Appendix B: Relationship to V1 Architecture

This V2 architecture supersedes V1 (`PHASE_2_GPU_INTEGRATION_ARCHITECTURE.md`).

| V1 Section | V2 Change |
|------------|-----------|
| Section 3.1 (GpuESVOBuilder) | Renamed to RegionBuilder; added circuit breaker, invisible queue |
| Section 3.2 (RegionCache) | Complete redesign with Caffeine; hybrid pinning; C4 guard |
| Section 3.3 (SerializationUtils) | Added compression threshold |
| Section 4 (Integration) | Added backfillDirtyRegions, /api/metrics, config rename |
| Section 7 (Testing) | Expanded from 30 to 45 tests |
| Section 8 (Performance) | Added Day 0 benchmark, quality gates |
| Section 9 (Error Handling) | Added circuit breaker section |
| New Section 10 | Metrics and observability |
| New Section 11 | Graceful degradation |
| Section 12 (Schedule) | 7.5 -> 9.5 days with Day 0 |

**V1 Decisions Reversed**:
- D3: "Custom with ConcurrentHashMap + ConcurrentLinkedDeque" -> Caffeine
- D10: "ConcurrentLinkedDeque" -> Caffeine + ConcurrentHashMap hybrid

---

**End of Phase 2 GPU Integration Architecture V2**
