/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.esvt.bridge;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.inspector.SpatialBridge;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridge between portal visualization and ESVT render module.
 *
 * <p>Provides a simplified API for portal components to:
 * <ul>
 *   <li>Build ESVT trees from voxel data</li>
 *   <li>Cast rays and get traversal results</li>
 *   <li>Track performance metrics</li>
 * </ul>
 *
 * <p>This adapter maps the portal's Vector3f-based API to the internal
 * ESVTRay/ESVTResult types used by the render module.
 *
 * @author hal.hildebrand
 */
public class ESVTBridge implements SpatialBridge<ESVTData> {

    private final ESVTBuilder builder;
    private final ThreadLocal<ESVTTraversal> traversalTL;

    // Current ESVT data
    private ESVTData data;

    // Performance metrics
    private final AtomicLong totalRaysCast = new AtomicLong();
    private final AtomicLong totalHits = new AtomicLong();
    private final AtomicLong totalIterations = new AtomicLong();
    private long lastBuildTimeNs;

    /**
     * Create a new ESVTBridge instance.
     */
    public ESVTBridge() {
        this.builder = new ESVTBuilder();
        this.traversalTL = ThreadLocal.withInitial(ESVTTraversal::new);
    }

    /**
     * Build an ESVT tree from voxel coordinates (method chaining).
     *
     * @param voxels List of voxel positions (Point3i x,y,z)
     * @param maxDepth Maximum tree depth (determines resolution)
     * @return This bridge instance for method chaining
     */
    public ESVTBridge buildAndChain(List<Point3i> voxels, int maxDepth) {
        return buildAndChain(voxels, maxDepth, -1);
    }

    /**
     * Build an ESVT tree from voxel coordinates with explicit grid resolution (method chaining).
     *
     * <p>When gridResolution is positive, the voxels are scaled relative to
     * [0, gridResolution-1] bounds, preserving spatial relationships. This is
     * essential for shapes like inscribed spheres where the voxels don't fill
     * the entire grid.
     *
     * @param voxels List of voxel positions (Point3i x,y,z)
     * @param maxDepth Maximum tree depth (determines resolution)
     * @param gridResolution Full grid size (e.g., 64 for 64x64x64), or -1 for auto
     * @return This bridge instance for method chaining
     */
    public ESVTBridge buildAndChain(List<Point3i> voxels, int maxDepth, int gridResolution) {
        long startNs = System.nanoTime();
        this.data = builder.buildFromVoxels(voxels, maxDepth, gridResolution);
        this.lastBuildTimeNs = System.nanoTime() - startNs;
        return this;
    }

    /**
     * Set ESVT data directly (for pre-built trees).
     */
    public ESVTBridge setData(ESVTData data) {
        this.data = data;
        return this;
    }

    /**
     * Get the current ESVT data.
     */
    public ESVTData getData() {
        return data;
    }

    /**
     * Check if tree is available for traversal.
     */
    public boolean hasData() {
        return data != null && data.nodes() != null && data.nodes().length > 0;
    }

    /**
     * Cast a ray through the ESVT structure.
     *
     * @param origin Ray origin
     * @param direction Ray direction (will be normalized)
     * @return Traversal result
     */
    public ESVTResult castRay(Vector3f origin, Vector3f direction) {
        if (!hasData()) {
            return new ESVTResult();
        }

        var ray = new ESVTRay(
            new Point3f(origin.x, origin.y, origin.z),
            new Vector3f(direction)
        );
        ray.normalizeDirection();

        var traversal = traversalTL.get();
        var result = traversal.castRay(ray, data.nodes(), null, data.farPointers(), 0);

        // Update metrics
        totalRaysCast.incrementAndGet();
        if (result.isHit()) {
            totalHits.incrementAndGet();
        }
        totalIterations.addAndGet(result.iterations);

        return result;
    }

    /**
     * Cast a ray using Point3f for origin.
     */
    public ESVTResult castRay(Point3f origin, Vector3f direction) {
        return castRay(new Vector3f(origin.x, origin.y, origin.z), direction);
    }

    /**
     * Cast multiple rays (batch processing).
     *
     * @param origins Ray origins
     * @param directions Ray directions
     * @return Array of results
     */
    public ESVTResult[] castRays(Vector3f[] origins, Vector3f[] directions) {
        if (!hasData() || origins.length != directions.length) {
            return new ESVTResult[0];
        }

        var rays = new ESVTRay[origins.length];
        for (int i = 0; i < origins.length; i++) {
            var ray = new ESVTRay(
                new Point3f(origins[i].x, origins[i].y, origins[i].z),
                new Vector3f(directions[i])
            );
            ray.normalizeDirection();
            rays[i] = ray;
        }

        var traversal = traversalTL.get();
        var results = traversal.castRays(rays, data.nodes(), null, data.farPointers(), 0);

        // Update metrics
        for (var result : results) {
            totalRaysCast.incrementAndGet();
            if (result.isHit()) {
                totalHits.incrementAndGet();
            }
            totalIterations.addAndGet(result.iterations);
        }

        return results;
    }

    /**
     * Cast a ray with contour refinement.
     */
    public ESVTResult castRayWithContours(Vector3f origin, Vector3f direction) {
        if (!hasData()) {
            return new ESVTResult();
        }

        var ray = new ESVTRay(
            new Point3f(origin.x, origin.y, origin.z),
            new Vector3f(direction)
        );
        ray.normalizeDirection();

        var traversal = traversalTL.get();
        // Use the contours array and far pointers if available
        var result = traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);

        totalRaysCast.incrementAndGet();
        if (result.isHit()) {
            totalHits.incrementAndGet();
        }
        totalIterations.addAndGet(result.iterations);

        return result;
    }

    // === Performance Metrics ===

    /**
     * Get total rays cast since creation.
     */
    public long getTotalRaysCast() {
        return totalRaysCast.get();
    }

    /**
     * Get total hits since creation.
     */
    public long getTotalHits() {
        return totalHits.get();
    }

    /**
     * Get hit rate as a percentage.
     */
    public double getHitRate() {
        long total = totalRaysCast.get();
        return total > 0 ? (100.0 * totalHits.get() / total) : 0.0;
    }

    /**
     * Get average iterations per ray.
     */
    public double getAverageIterations() {
        long total = totalRaysCast.get();
        return total > 0 ? ((double) totalIterations.get() / total) : 0.0;
    }

    /**
     * Get last build time in milliseconds.
     */
    public double getLastBuildTimeMs() {
        return lastBuildTimeNs / 1_000_000.0;
    }

    /**
     * Reset performance metrics.
     */
    public void resetMetrics() {
        totalRaysCast.set(0);
        totalHits.set(0);
        totalIterations.set(0);
    }

    /**
     * Get comprehensive performance metrics.
     */
    public PerformanceMetrics getPerformanceMetrics() {
        return new PerformanceMetrics(
            totalRaysCast.get(),
            totalHits.get(),
            getHitRate(),
            getAverageIterations(),
            getLastBuildTimeMs(),
            data != null ? data.nodeCount() : 0,
            data != null ? data.maxDepth() : 0,
            data != null ? data.leafCount() : 0
        );
    }

    /**
     * Performance metrics record.
     */
    public record PerformanceMetrics(
        long totalRays,
        long totalHits,
        double hitRatePercent,
        double avgIterations,
        double buildTimeMs,
        int nodeCount,
        int maxDepth,
        int leafCount
    ) {
        @Override
        public String toString() {
            return String.format(
                "PerformanceMetrics[rays=%d, hits=%d (%.1f%%), avgIters=%.1f, " +
                "buildTime=%.1fms, nodes=%d, depth=%d, leaves=%d]",
                totalRays, totalHits, hitRatePercent, avgIterations,
                buildTimeMs, nodeCount, maxDepth, leafCount
            );
        }
    }

    /**
     * Get a summary of the current ESVT structure.
     */
    public String getDataSummary() {
        if (data == null) {
            return "ESVTBridge: No data loaded";
        }
        return String.format("ESVTBridge: %s, build time: %.2fms",
            data.toString(), getLastBuildTimeMs());
    }

    // ==================== SpatialBridge Interface ====================

    @Override
    public BuildResult<ESVTData> buildFromVoxels(List<Point3i> voxels, int maxDepth, int gridResolution) {
        long startMs = System.currentTimeMillis();
        try {
            long startNs = System.nanoTime();
            this.data = builder.buildFromVoxels(voxels, maxDepth, gridResolution);
            this.lastBuildTimeNs = System.nanoTime() - startNs;
            long buildTimeMs = System.currentTimeMillis() - startMs;
            return new BuildResult<>(data, buildTimeMs, voxels.size(),
                String.format("Built ESVT with %d nodes in %d ms", data.nodeCount(), buildTimeMs));
        } catch (Exception e) {
            long buildTimeMs = System.currentTimeMillis() - startMs;
            return new BuildResult<>(null, buildTimeMs, voxels.size(), "Build failed: " + e.getMessage());
        }
    }

    @Override
    public String getStructureTypeName() {
        return "ESVT";
    }
}
