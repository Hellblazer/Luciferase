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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.FrameManager;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.FrustumIntersection.VisibilityType;
import com.hellblazer.luciferase.lucien.SpatialIndexCore;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.cull.FrustumCullProvider;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.internal.ObjectPools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Dynamic Scene Occlusion Culling (DSOC) feature object for a spatial index (RDR-008 P1).
 *
 * <p>Owns the DSOC state — configuration, {@link FrameManager}, {@link VisibilityStateManager},
 * {@link HierarchicalOcclusionCuller}, camera matrices, and the performance-monitoring counters that drive auto-
 * disable — and the occlusion-aware frustum cull, the public DSOC lifecycle/statistics API, and the entity-update
 * hooks. It orchestrates the occlusion machinery in this package; it does not reimplement it.
 *
 * <p>The owning {@code AbstractSpatialIndex} façade constructs one of these when DSOC is enabled and delegates its
 * public DSOC API and three integration seams (frustum-cull entry, entity-update visibility/TBV, occlusion-aware
 * node creation) to it. Shared storage and concurrency come from {@link SpatialIndexCore}; the four façade operations
 * the DSOC machinery still needs (frustum traversal order, the subclass frustum-node test, node bounds, cached entity
 * position) arrive through {@link FrustumGeometry}. The standard non-DSOC cull fallback — the path that runs when
 * DSOC's Z-buffer is inactive or auto-disable engages — lives in the P4 cull cluster and arrives through a separate
 * {@link FrustumCullProvider}, keeping the DSOC consumer surface narrow.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class DsocController<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(DsocController.class);

    // Performance-evaluation thresholds (moved verbatim from AbstractSpatialIndex).
    private static final int    MIN_FRAMES_FOR_EVALUATION       = 10;
    private static final double PERFORMANCE_THRESHOLD_MULTIPLIER = 1.2; // 20% overhead tolerance
    private static final int    EVALUATION_INTERVAL             = 50;   // Check every 50 frames
    private static final int    MIN_ENTITIES_FOR_DSOC           = 50;

    private final SpatialIndexCore<Key, ID, Content>     core;
    private final FrustumGeometry<Key, ID, Content>      callback;
    private final FrustumCullProvider<Key, ID, Content>  cullProvider;
    private final DSOCConfiguration                      config;

    private final FrameManager                              frameManager;
    private final VisibilityStateManager<ID>                visibilityManager;
    private final HierarchicalOcclusionCuller<Key, ID, Content> occlusionCuller;

    private float[] currentViewMatrix;
    private float[] currentProjectionMatrix;

    // Performance monitoring
    private volatile long    dsocFrameCount     = 0;
    private volatile long    dsocTotalTime      = 0;
    private volatile long    standardFrameCount = 0;
    private volatile long    standardTotalTime  = 0;
    private volatile boolean autoDisabled       = false;

    /**
     * Construct and, when the configuration is enabled, initialize the DSOC machinery and wire entity auto-dynamics.
     * Mirrors the former {@code AbstractSpatialIndex.enableDSOC(config, bufferWidth, bufferHeight)}.
     */
    public DsocController(SpatialIndexCore<Key, ID, Content> core, FrustumGeometry<Key, ID, Content> callback,
                          FrustumCullProvider<Key, ID, Content> cullProvider, DSOCConfiguration config,
                          int bufferWidth, int bufferHeight) {
        this.core = core;
        this.callback = callback;
        this.cullProvider = cullProvider;
        this.config = config;
        if (config.isEnabled()) {
            this.frameManager = new FrameManager();
            this.visibilityManager = new VisibilityStateManager<>(config);
            this.occlusionCuller = new HierarchicalOcclusionCuller<>(bufferWidth, bufferHeight, config);
            if (config.isAutoDynamicsEnabled()) {
                core.entityManager().setAutoDynamicsEnabled(true);
                core.entityManager().setFrameManager(frameManager);
            }
        } else {
            this.frameManager = null;
            this.visibilityManager = null;
            this.occlusionCuller = null;
        }
    }

    /** Whether DSOC is enabled by configuration and has not been auto-disabled. */
    public boolean isEnabled() {
        return config != null && config.isEnabled() && !autoDisabled;
    }

    /** Store camera matrices for use in the next occlusion frame (validates 4x4 when enabled). */
    public void updateCamera(float[] viewMatrix, float[] projectionMatrix, Point3f cameraPosition) {
        if (!isEnabled()) {
            return;
        }
        if (viewMatrix == null || projectionMatrix == null) {
            throw new NullPointerException("View and projection matrices cannot be null when DSOC is enabled");
        }
        if (viewMatrix.length != 16) {
            throw new IllegalArgumentException("View matrix must be 4x4 (16 elements), got " + viewMatrix.length);
        }
        if (projectionMatrix.length != 16) {
            throw new IllegalArgumentException(
            "Projection matrix must be 4x4 (16 elements), got " + projectionMatrix.length);
        }
        this.currentViewMatrix = viewMatrix.clone();
        this.currentProjectionMatrix = projectionMatrix.clone();
    }

    /** Advance to the next frame, returning the new frame number (0 when DSOC machinery is absent). */
    public long nextFrame() {
        return frameManager != null ? frameManager.incrementFrame() : 0;
    }

    /** The current frame number (0 when DSOC machinery is absent). */
    public long getCurrentFrame() {
        return frameManager != null ? frameManager.getCurrentFrame() : 0;
    }

    /** DSOC statistics map; {@code {dsocEnabled: false}} when disabled. */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        if (isEnabled()) {
            stats.put("dsocEnabled", true);
            stats.put("currentFrame", getCurrentFrame());
            stats.put("dsocFrameCount", dsocFrameCount);
            stats.put("standardFrameCount", standardFrameCount);
            if (visibilityManager != null) {
                stats.putAll(visibilityManager.getStatistics());
            }
            if (occlusionCuller != null) {
                stats.putAll(occlusionCuller.getStatistics());
            }
            stats.put("totalEntities", (long) core.entityManager().getEntityCount());
        } else {
            stats.put("dsocEnabled", false);
        }
        return stats;
    }

    /** Entities flagged by the occlusion culler as needing position updates. */
    public Set<ID> getEntitiesNeedingUpdate() {
        return occlusionCuller != null ? occlusionCuller.getEntitiesNeedingUpdate() : new HashSet<>();
    }

    /** Reset the occlusion culler's statistics. */
    public void resetStatistics() {
        if (occlusionCuller != null) {
            occlusionCuller.resetStatistics();
        }
    }

    /** Force the Z-buffer active (test hook). */
    public void forceZBufferActivation() {
        if (occlusionCuller != null) {
            occlusionCuller.forceActivate();
        }
    }

    // ---- Entity-update integration seams -------------------------------------------------------------------------

    /**
     * If the entity is hidden behind a still-valid temporal bounding volume, defer its move (update dynamics + refresh
     * the TBV when quality drops) and return {@code true} so the caller skips the normal relocation. Otherwise return
     * {@code false}. Mirrors the DSOC branch formerly inlined at the top of {@code updateEntity}.
     */
    public boolean tryDeferUpdate(ID entityId, Point3f newPosition) {
        if (!isEnabled() || visibilityManager == null) {
            return false;
        }
        long currentFrame = getCurrentFrame();
        var state = visibilityManager.getState(entityId);
        if (state != VisibilityStateManager.VisibilityState.HIDDEN_WITH_TBV) {
            return false;
        }
        var tbv = visibilityManager.getTBV(entityId);
        if (tbv == null || !tbv.isValid((int) currentFrame)) {
            return false;
        }
        var dynamics = core.entityManager().getDynamics(entityId);
        if (dynamics != null) {
            dynamics.updatePosition(newPosition, currentFrame);
            float quality = tbv.getQuality((int) currentFrame);
            if (quality < config.getTbvRefreshThreshold()) {
                var bounds = core.entityManager().getEntityBounds(entityId);
                if (bounds == null) {
                    bounds = new EntityBounds(newPosition, 0.1f);
                }
                visibilityManager.createTBV(entityId, dynamics, bounds, currentFrame);
            }
        }
        return true; // deferred — skip the normal update
    }

    /** Mark the entity visible in the current frame after a relocation (no-op when disabled). */
    public void markVisibleOnUpdate(ID entityId) {
        if (isEnabled() && visibilityManager != null) {
            visibilityManager.updateVisibility(entityId, true, (int) getCurrentFrame());
        }
    }

    // ---- Frustum cull entry seam ---------------------------------------------------------------------------------

    /**
     * The DSOC-aware frustum-cull entry point. Evaluates auto-disable, decides whether DSOC is worthwhile this frame,
     * and measures whichever path runs. Mirrors the DSOC decision block formerly inlined in {@code frustumCullVisible}.
     */
    public List<FrustumIntersection<ID, Content>> frustumCullVisible(Frustum3D frustum, Point3f cameraPosition) {
        // Auto-disable evaluation
        if (isEnabled() && shouldEvaluatePerformance()) {
            if (shouldAutoDisableDSOC()) {
                log.warn("Auto-disabling DSOC due to performance degradation: {}x overhead", getOverheadMultiplier());
                autoDisabled = true;
            }
        }

        // Use DSOC only when still enabled and hierarchical occlusion is configured and worthwhile
        if (isEnabled() && config.isEnableHierarchicalOcclusion()) {
            if (shouldSkipDSOC()) {
                return measureAndExecute(() -> cullProvider.frustumCullVisibleStandard(frustum, cameraPosition), false);
            }
            return measureAndExecute(() -> frustumCullVisibleWithDSOC(frustum, cameraPosition), true);
        }

        // Standard path, still measured so the perf comparison stays meaningful
        return measureAndExecute(() -> cullProvider.frustumCullVisibleStandard(frustum, cameraPosition), false);
    }

    /** Perform frustum culling with occlusion testing. */
    List<FrustumIntersection<ID, Content>> frustumCullVisibleWithDSOC(Frustum3D frustum, Point3f cameraPosition) {
        if (occlusionCuller == null) {
            throw new IllegalStateException("DSOC not enabled");
        }
        // Early exit if Z-buffer is not activated (no occluders)
        if (!occlusionCuller.isActivated()) {
            return cullProvider.frustumCullVisibleStandard(frustum, cameraPosition);
        }

        core.lock().readLock().lock();
        try {
            var intersections = ObjectPools.<FrustumIntersection<ID, Content>>borrowArrayList();
            var visitedEntities = ObjectPools.<ID>borrowHashSet();

            // Use stored camera matrices from updateCamera, or identity matrices if not set
            float[] viewMatrix = currentViewMatrix != null ? currentViewMatrix : createIdentityMatrix();
            float[] projectionMatrix = currentProjectionMatrix != null ? currentProjectionMatrix : createIdentityMatrix();
            occlusionCuller.beginFrame(viewMatrix, projectionMatrix, frustum);

            try {
                var frustumNodes = callback.getFrustumTraversalOrder(frustum, cameraPosition).collect(Collectors.toList());

                for (Key nodeIndex : frustumNodes) {
                    var node = core.spatialIndex().get(nodeIndex);
                    if (node == null || node.isEmpty()) {
                        continue;
                    }
                    if (!callback.doesFrustumIntersectNode(nodeIndex, frustum)) {
                        continue;
                    }

                    EntityBounds nodeBounds = callback.computeNodeBounds(nodeIndex);
                    if (nodeBounds != null && occlusionCuller.isNodeOccluded(nodeBounds)) {
                        // Still check TBVs even if the node is occluded
                        if (node instanceof OcclusionAwareSpatialNode) {
                            OcclusionAwareSpatialNode<ID> occNode = (OcclusionAwareSpatialNode<ID>) node;
                            occNode.markOccluded(getCurrentFrame());
                            for (var tbv : occNode.getTBVs()) {
                                occlusionCuller.isTBVVisible(tbv, frustum, getCurrentFrame());
                            }
                        }
                        continue;
                    }

                    if (node instanceof OcclusionAwareSpatialNode) {
                        ((OcclusionAwareSpatialNode<ID>) node).markVisible(getCurrentFrame());
                    }

                    for (ID entityId : node.getEntityIds()) {
                        if (!visitedEntities.add(entityId)) {
                            continue;
                        }
                        var content = core.entityManager().getEntityContent(entityId);
                        if (content == null) {
                            continue;
                        }
                        var entityPos = callback.getCachedEntityPosition(entityId);
                        if (entityPos == null) {
                            continue;
                        }
                        var entityBounds = core.entityManager().getEntityBounds(entityId);
                        if (entityBounds == null) {
                            entityBounds = new EntityBounds(entityPos, 0.1f);
                        }
                        if (!frustum.intersects(entityBounds)) {
                            occlusionCuller.incrementFrustumCulled();
                            if (visibilityManager != null) {
                                visibilityManager.updateVisibility(entityId, false, (int) getCurrentFrame());
                            }
                            continue;
                        }
                        if (occlusionCuller.isEntityOccluded(entityBounds)) {
                            if (visibilityManager != null) {
                                visibilityManager.updateVisibility(entityId, false, (int) getCurrentFrame());
                            }
                            continue;
                        }
                        float distance = entityPos.distance(cameraPosition);
                        var intersection = new FrustumIntersection<>(entityId, content, distance, entityPos,
                                                                     VisibilityType.INSIDE, entityBounds);
                        intersections.add(intersection);
                        occlusionCuller.incrementEntitiesVisible();
                        if (visibilityManager != null) {
                            visibilityManager.updateVisibility(entityId, true, (int) getCurrentFrame());
                        }
                        if (config.isRenderEntitiesAsOccluders()) {
                            occlusionCuller.renderOccluder(entityBounds);
                        }
                    }

                    if (config.isRenderNodesAsOccluders() && nodeBounds != null) {
                        occlusionCuller.renderOccluder(nodeBounds);
                    }
                }

                intersections.sort(Comparator.comparingDouble(FrustumIntersection::distanceFromCamera));
                return new ArrayList<>(intersections);
            } finally {
                occlusionCuller.endFrame();
                ObjectPools.returnArrayList(intersections);
                ObjectPools.returnHashSet(visitedEntities);
            }
        } finally {
            core.lock().readLock().unlock();
        }
    }

    // ---- Performance monitoring ----------------------------------------------------------------------------------

    private List<FrustumIntersection<ID, Content>> measureAndExecute(
    Supplier<List<FrustumIntersection<ID, Content>>> operation, boolean isDSOC) {
        long startTime = System.nanoTime();
        try {
            return operation.get();
        } finally {
            long duration = System.nanoTime() - startTime;
            if (isDSOC) {
                dsocFrameCount++;
                dsocTotalTime += duration;
            } else {
                standardFrameCount++;
                standardTotalTime += duration;
            }
        }
    }

    private boolean shouldEvaluatePerformance() {
        return (dsocFrameCount + standardFrameCount) % EVALUATION_INTERVAL == 0;
    }

    private boolean shouldAutoDisableDSOC() {
        if (dsocFrameCount < MIN_FRAMES_FOR_EVALUATION || standardFrameCount < MIN_FRAMES_FOR_EVALUATION) {
            return false;
        }
        double dsocAvgTime = (double) dsocTotalTime / dsocFrameCount;
        double standardAvgTime = (double) standardTotalTime / standardFrameCount;
        return dsocAvgTime > PERFORMANCE_THRESHOLD_MULTIPLIER * standardAvgTime;
    }

    private double getOverheadMultiplier() {
        if (dsocFrameCount == 0 || standardFrameCount == 0) {
            return 1.0;
        }
        double dsocAvgTime = (double) dsocTotalTime / dsocFrameCount;
        double standardAvgTime = (double) standardTotalTime / standardFrameCount;
        return dsocAvgTime / standardAvgTime;
    }

    private boolean shouldSkipDSOC() {
        if (core.entityManager().getEntityCount() < MIN_ENTITIES_FOR_DSOC) {
            return true;
        }
        if (occlusionCuller != null && !occlusionCuller.isActivated()) {
            return true;
        }
        return dsocFrameCount >= 5 && getOverheadMultiplier() > PERFORMANCE_THRESHOLD_MULTIPLIER * 2;
    }

    private float[] createIdentityMatrix() {
        float[] matrix = new float[16];
        matrix[0] = 1.0f;
        matrix[5] = 1.0f;
        matrix[10] = 1.0f;
        matrix[15] = 1.0f;
        return matrix;
    }
}
