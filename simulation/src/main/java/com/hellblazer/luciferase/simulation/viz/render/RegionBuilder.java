package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CPU-based region builder for ESVO/ESVT sparse voxel structures.
 *
 * <p>Converts entity positions into serialized voxel octree/tetree data for streaming to clients.
 * Uses priority-based build queue with backpressure and circuit breaker for failure handling.
 *
 * <p><strong>Architecture (V2):</strong>
 * <ul>
 *   <li>S1: Renamed from GpuESVOBuilder (100% CPU-based, no GPU acceleration)</li>
 *   <li>S2: ConcurrentSkipListSet for O(log n) invisible build eviction</li>
 *   <li>C3: Circuit breaker scaffolding for build failure handling</li>
 * </ul>
 *
 * <p><strong>Priority Queue:</strong> Visible builds execute before invisible builds.
 * Within same visibility, older requests execute first (FIFO per priority).
 *
 * <p><strong>Backpressure (C1):</strong>
 * <ul>
 *   <li>Invisible build when queue full: reject with BuildQueueFullException</li>
 *   <li>Visible build when queue full: evict lowest-priority invisible build (O(log n) via ConcurrentSkipListSet)</li>
 *   <li>Visible build when queue all visible: reject with BuildQueueFullException</li>
 * </ul>
 */
public class RegionBuilder implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RegionBuilder.class);

    private final PriorityBlockingQueue<BuildRequest> buildQueue;
    private final ConcurrentSkipListSet<BuildRequest> invisibleBuilds; // S2: O(log n) eviction
    private final ConcurrentHashMap<RegionId, CircuitBreakerState> circuitBreakers; // C3
    private final ExecutorService buildPool;
    private final AtomicInteger queueSize;
    private final int maxQueueDepth;
    private final int maxDepth;
    private final int gridResolution;
    private volatile Clock clock;
    private volatile boolean closed;

    /**
     * Create RegionBuilder with specified pool size and queue depth.
     *
     * @param buildPoolSize Number of builder threads (renamed from gpuPoolSize - S1)
     * @param maxQueueDepth Maximum build queue depth
     * @param maxDepth Maximum octree/tetree depth
     * @param gridResolution Voxel grid resolution (e.g., 64 for 64³ grid)
     */
    public RegionBuilder(int buildPoolSize, int maxQueueDepth, int maxDepth, int gridResolution) {
        this.buildQueue = new PriorityBlockingQueue<>(maxQueueDepth);
        this.invisibleBuilds = new ConcurrentSkipListSet<>(); // S2
        this.circuitBreakers = new ConcurrentHashMap<>(); // C3
        this.queueSize = new AtomicInteger(0);
        this.maxQueueDepth = maxQueueDepth;
        this.maxDepth = maxDepth;
        this.gridResolution = gridResolution;
        this.clock = Clock.system();
        this.closed = false;

        // S1: Fixed thread pool with daemon threads named "region-builder-*"
        this.buildPool = Executors.newFixedThreadPool(buildPoolSize, r -> {
            var thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("region-builder-" + thread.threadId());
            return thread;
        });

        log.info("RegionBuilder created: buildPoolSize={}, maxQueueDepth={}, maxDepth={}, gridResolution={}",
                buildPoolSize, maxQueueDepth, maxDepth, gridResolution);
    }

    /**
     * Set the clock for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Convert entity positions to voxel coordinates.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Normalize positions to [0, 1] coordinate space</li>
     *   <li>Quantize to grid resolution (e.g., 64³)</li>
     *   <li>Clamp to valid range [0, gridResolution-1]</li>
     * </ol>
     *
     * @param positions Entity positions in world space
     * @param bounds Region spatial bounds
     * @return Voxel coordinates
     */
    public List<Point3i> positionsToVoxels(List<Point3f> positions, RegionBounds bounds) {
        var voxels = new ArrayList<Point3i>(positions.size());

        for (var pos : positions) {
            // Normalize to [0, 1] coordinate space
            float nx = (pos.x - bounds.minX()) / (bounds.maxX() - bounds.minX());
            float ny = (pos.y - bounds.minY()) / (bounds.maxY() - bounds.minY());
            float nz = (pos.z - bounds.minZ()) / (bounds.maxZ() - bounds.minZ());

            // Quantize to grid
            int x = (int) (nx * gridResolution);
            int y = (int) (ny * gridResolution);
            int z = (int) (nz * gridResolution);

            // Clamp to valid range
            x = Math.max(0, Math.min(gridResolution - 1, x));
            y = Math.max(0, Math.min(gridResolution - 1, y));
            z = Math.max(0, Math.min(gridResolution - 1, z));

            voxels.add(new Point3i(x, y, z));
        }

        return voxels;
    }

    /**
     * Build ESVO octree from voxel positions (basic implementation for Day 2 testing).
     *
     * @param voxels Voxel coordinates
     * @return ESVO octree data
     */
    ESVOOctreeData buildESVO(List<Point3i> voxels) {
        try (var builder = new OctreeBuilder(maxDepth)) {
            return builder.buildFromVoxels(voxels, maxDepth);
        }
    }

    /**
     * Build ESVT tetree from voxel positions (basic implementation for Day 2 testing).
     *
     * @param voxels Voxel coordinates
     * @return ESVT tetree data
     */
    ESVTData buildESVT(List<Point3i> voxels) {
        var builder = new ESVTBuilder();
        return builder.buildFromVoxels(voxels, maxDepth, gridResolution);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            buildPool.shutdown();
            try {
                if (!buildPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    buildPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                buildPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("RegionBuilder closed");
        }
    }

    // ===== Inner Records and Exceptions =====

    /**
     * Build request with priority ordering.
     *
     * <p>Priority: Visible builds execute before invisible builds.
     * Within same visibility, older requests execute first (FIFO).
     */
    public record BuildRequest(
            RegionId regionId,
            List<Point3f> positions,
            RegionBounds bounds,
            int lodLevel,
            boolean visible,
            BuildType type,
            long timestamp
    ) implements Comparable<BuildRequest> {

        @Override
        public int compareTo(BuildRequest other) {
            // Visible builds have priority over invisible
            if (this.visible != other.visible) {
                return this.visible ? -1 : 1;
            }
            // Within same visibility, older requests first (FIFO)
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    /**
     * Built region with serialized data and metadata.
     */
    public record BuiltRegion(
            RegionId regionId,
            int lodLevel,
            BuildType type,
            byte[] serializedData,
            boolean compressed,
            long buildTimeNs,
            long timestamp
    ) {
        /**
         * Estimate memory size of this built region.
         *
         * @return Estimated size in bytes (serialized data + overhead)
         */
        public long estimatedSizeBytes() {
            // Serialized data + 72 bytes overhead for record fields
            return serializedData.length + 72L;
        }
    }

    /**
     * Build type (ESVO octree or ESVT tetree).
     */
    public enum BuildType {
        ESVO,
        ESVT
    }

    /**
     * Exception thrown when build queue is full and cannot accept new builds.
     */
    public static class BuildQueueFullException extends Exception {
        public BuildQueueFullException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when circuit breaker is open for a region.
     */
    public static class CircuitBreakerOpenException extends Exception {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    /**
     * Circuit breaker state for tracking build failures (C3 scaffolding).
     *
     * <p>Future enhancement: Track consecutive failures and block retries.
     */
    static class CircuitBreakerState {
        private int consecutiveFailures;
        private long lastFailureTime;

        CircuitBreakerState() {
            this.consecutiveFailures = 0;
            this.lastFailureTime = 0;
        }

        // C3: Placeholder for future circuit breaker logic
    }
}
