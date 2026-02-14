/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks per-client viewports and determines visible regions with LOD levels.
 * <p>
 * Each connected client has a viewport (camera position, direction, FOV).
 * ViewportTracker constructs a Frustum3D from the client's camera parameters
 * and tests each active region's AABB for intersection. Visible regions are
 * assigned LOD levels based on distance from camera.
 * <p>
 * Reuses lucien's Frustum3D.createPerspective() and intersectsAABB() for
 * all geometric calculations. No new geometry code required.
 * <p>
 * Thread-safe: ConcurrentHashMap for client state, immutable records for results.
 *
 * @author hal.hildebrand
 */
public class ViewportTracker {

    private static final Logger log = LoggerFactory.getLogger(ViewportTracker.class);

    /**
     * Per-client viewport state (immutable).
     */
    private record ClientViewportState(
        String clientId,
        ClientViewport viewport,
        Frustum3D frustum,
        List<VisibleRegion> lastVisible,
        long lastUpdateMs
    ) {}

    private final AdaptiveRegionManager regionManager;
    private final StreamingConfig config;
    private final ConcurrentHashMap<String, ClientViewportState> clients;
    private volatile Clock clock = Clock.system();

    /**
     * Create a viewport tracker.
     *
     * @param regionManager Region manager for querying active regions
     * @param config        Streaming configuration (LOD thresholds, etc.)
     */
    public ViewportTracker(AdaptiveRegionManager regionManager, StreamingConfig config) {
        this.regionManager = Objects.requireNonNull(regionManager, "regionManager");
        this.config = Objects.requireNonNull(config, "config");
        this.clients = new ConcurrentHashMap<>();
    }

    /**
     * Register a new client with empty viewport state.
     *
     * @param clientId Client identifier
     */
    public void registerClient(String clientId) {
        clients.put(clientId, new ClientViewportState(
            clientId,
            null,  // No viewport until first update
            null,  // No frustum until first update
            List.of(),
            clock.currentTimeMillis()
        ));
        log.debug("Registered client {}", clientId);
    }

    /**
     * Remove a client and discard its viewport state.
     *
     * @param clientId Client identifier
     */
    public void removeClient(String clientId) {
        clients.remove(clientId);
        log.debug("Removed client {}", clientId);
    }

    /**
     * Get the number of registered clients.
     *
     * @return Client count
     */
    public int clientCount() {
        return clients.size();
    }

    /**
     * Update a client's viewport and reconstruct the frustum.
     * <p>
     * Constructs a Frustum3D using lucien's Frustum3D.createPerspective()
     * with the client's camera parameters. The frustum is cached in client state
     * and reused for all visibility queries until the next viewport update.
     *
     * @param clientId Client identifier
     * @param viewport New viewport parameters
     */
    public void updateViewport(String clientId, ClientViewport viewport) {
        // CRITICAL FIX: Move frustum construction INSIDE compute() lambda.
        // If constructed outside, a race exists: removeClient() could run between
        // frustum construction and compute(), causing a zombie client to be created.

        // Update client state atomically
        clients.compute(clientId, (id, oldState) -> {
            // Construct lucien Frustum3D from camera parameters (NOW atomic with state update)
            var eye = new Point3f(viewport.eye().x, viewport.eye().y, viewport.eye().z);
            var lookAt = new Point3f(viewport.lookAt().x, viewport.lookAt().y, viewport.lookAt().z);
            var up = new Vector3f(viewport.up().x, viewport.up().y, viewport.up().z);

            var frustum = Frustum3D.createPerspective(
                eye, lookAt, up,
                viewport.fovY(), viewport.aspectRatio(),
                viewport.nearPlane(), viewport.farPlane()
            );

            var lastVisible = oldState != null ? oldState.lastVisible : List.<VisibleRegion>of();
            return new ClientViewportState(
                clientId,
                viewport,
                frustum,
                lastVisible,
                clock.currentTimeMillis()
            );
        });

        log.debug("Updated viewport for client {}: eye=({},{},{}), fov={}",
            clientId, viewport.eye().x, viewport.eye().y, viewport.eye().z, viewport.fovY());
    }

    /**
     * Compute visible regions for a client.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Construct Frustum3D from client's camera parameters</li>
     *   <li>For each active region in AdaptiveRegionManager:</li>
     *   <li>  Get RegionBounds (AABB)</li>
     *   <li>  Test frustum.intersectsAABB(min, max)</li>
     *   <li>  If visible: compute distance to region center, assign LOD</li>
     *   <li>Sort by distance (closest first)</li>
     * </ol>
     * <p>
     * Complexity: O(N) where N = number of active regions.
     * Each intersectsAABB test is O(1) (6 plane-vertex tests).
     *
     * @param clientId Client identifier
     * @return Visible regions sorted by distance, empty list if client has no viewport
     */
    public List<VisibleRegion> visibleRegions(String clientId) {
        var state = clients.get(clientId);
        if (state == null || state.frustum == null) {
            return List.of();
        }

        var frustum = state.frustum;
        var viewport = state.viewport;
        var allRegions = regionManager.getAllRegions();
        var visible = new ArrayList<VisibleRegion>(allRegions.size() / 4); // Estimate 25% visibility

        for (var regionId : allRegions) {
            var bounds = regionManager.boundsForRegion(regionId);

            // Frustum-AABB intersection test (6 plane tests, O(1))
            if (frustum.intersectsAABB(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {

                // Compute distance from eye to region center
                var distance = viewport.distanceTo(
                    bounds.centerX(), bounds.centerY(), bounds.centerZ());

                // Assign LOD based on distance
                var lod = lodForDistance(distance);

                visible.add(new VisibleRegion(regionId, lod, distance));
            }
        }

        // Sort by distance (closest first) for priority delivery
        Collections.sort(visible); // Uses VisibleRegion.compareTo (distance-based)

        // Update client state with new visible set
        clients.computeIfPresent(clientId, (id, oldState) ->
            new ClientViewportState(
                id, oldState.viewport, oldState.frustum,
                List.copyOf(visible), oldState.lastUpdateMs
            ));

        return visible;
    }

    /**
     * Determine LOD level for a given distance from camera.
     * <p>
     * Uses configurable distance thresholds from StreamingConfig:
     * <pre>
     *   distance < lodThresholds[0]  -> LOD 0 (highest detail)
     *   distance < lodThresholds[1]  -> LOD 1
     *   distance < lodThresholds[2]  -> LOD 2
     *   distance >= lodThresholds[2] -> LOD 3 (lowest detail)
     * </pre>
     * <p>
     * Default thresholds: [100, 300, 700] world units.
     *
     * @param distance Distance from camera eye to region center
     * @return LOD level [0, maxLodLevel]
     */
    private int lodForDistance(float distance) {
        var thresholds = config.lodThresholds();
        for (int i = 0; i < thresholds.length; i++) {
            if (distance < thresholds[i]) {
                return i;
            }
        }
        return config.maxLodLevel();
    }

    /**
     * Compute the diff between current and previous visibility for a client.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Compute current visible regions (calls visibleRegions())</li>
     *   <li>Build lookup maps for old and new sets</li>
     *   <li>Added = in new set but NOT in old set</li>
     *   <li>Removed = in old set but NOT in new set</li>
     *   <li>LOD Changed = in BOTH sets but LOD differs</li>
     * </ol>
     * <p>
     * Complexity: O(N + M) where N = new visible count, M = old visible count.
     *
     * @param clientId Client identifier
     * @return ViewportDiff with added, removed, and LOD-changed regions
     */
    public ViewportDiff diffViewport(String clientId) {
        var state = clients.get(clientId);
        if (state == null || state.frustum == null) {
            return ViewportDiff.empty();
        }

        var oldVisible = state.lastVisible;

        // Compute new visibility (also updates client state)
        var newVisible = visibleRegions(clientId);

        // Build lookup maps
        var oldMap = new HashMap<RegionId, VisibleRegion>(oldVisible.size());
        for (var vr : oldVisible) {
            oldMap.put(vr.regionId(), vr);
        }

        var newMap = new HashMap<RegionId, VisibleRegion>(newVisible.size());
        for (var vr : newVisible) {
            newMap.put(vr.regionId(), vr);
        }

        // Compute diff
        var added = new HashSet<VisibleRegion>();
        var lodChanged = new HashSet<VisibleRegion>();
        var removed = new HashSet<RegionId>();

        // Find added and LOD-changed
        for (var vr : newVisible) {
            var old = oldMap.get(vr.regionId());
            if (old == null) {
                added.add(vr);
            } else if (old.lodLevel() != vr.lodLevel()) {
                lodChanged.add(vr);
            }
        }

        // Find removed
        for (var vr : oldVisible) {
            if (!newMap.containsKey(vr.regionId())) {
                removed.add(vr.regionId());
            }
        }

        return new ViewportDiff(added, removed, lodChanged);
    }

    /**
     * Get all regions currently visible to at least one client.
     * <p>
     * Union of all client visible sets.
     *
     * @return Set of region IDs visible to any client
     */
    public Set<RegionId> allVisibleRegions() {
        return clients.values().stream()
            .flatMap(state -> state.lastVisible.stream())
            .map(VisibleRegion::regionId)
            .collect(Collectors.toSet());
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }
}
