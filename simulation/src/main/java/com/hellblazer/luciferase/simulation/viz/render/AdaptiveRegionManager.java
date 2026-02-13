/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the spatial region grid and tracks entity-to-region mapping.
 * <p>
 * The world is divided into a regular octree grid at a configurable
 * level (e.g., level 4 = 16x16x16 = 4096 regions). Each region tracks
 * the entity positions it contains and a dirty flag.
 * <p>
 * <b>CRITICAL FIX C3</b>: Uses epsilon tolerance for region boundary calculations
 * to prevent floating-point precision errors from mapping entities to the wrong region.
 * <p>
 * Thread-safe: entity updates and visibility queries may occur concurrently.
 * Uses ConcurrentHashMap for region storage and CopyOnWriteArrayList for
 * entity lists (frequent reads, infrequent writes).
 *
 * @author hal.hildebrand
 */
public class AdaptiveRegionManager {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRegionManager.class);

    /**
     * C3 FIX: Epsilon tolerance for region boundaries (relative to regionSize).
     * <p>
     * Prevents floating-point precision loss at exact boundaries. For example,
     * an entity at x=127.99999 should map to region 1 (64-127), not region 2 (128-191).
     * Adding epsilon shifts boundary entities into the correct region.
     */
    private static final float BOUNDARY_EPSILON = 1e-5f;

    private final int regionLevel;
    private final float worldMin;
    private final float worldMax;
    private final float regionSize;
    private final int regionsPerAxis;

    // Region state: entity positions, dirty flag, build version
    private final ConcurrentHashMap<RegionId, RegionState> regions = new ConcurrentHashMap<>();

    // Entity-to-region reverse index for efficient updates
    private final ConcurrentHashMap<String, RegionId> entityRegionMap = new ConcurrentHashMap<>();

    private volatile Clock clock = Clock.system();

    /**
     * Create region manager from configuration.
     *
     * @param config Rendering server configuration
     */
    public AdaptiveRegionManager(RenderingServerConfig config) {
        this.regionLevel = config.regionLevel();
        this.worldMin = 0.0f;
        this.worldMax = 1024.0f;  // Default world size
        this.regionsPerAxis = 1 << regionLevel;  // 2^level
        this.regionSize = (worldMax - worldMin) / regionsPerAxis;

        log.info("AdaptiveRegionManager initialized: level={}, regionsPerAxis={}, regionSize={}",
                 regionLevel, regionsPerAxis, regionSize);
    }

    /**
     * Set the clock for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Update or add an entity position.
     * <p>
     * If the entity has moved to a different region, it is removed from the old region
     * and added to the new region. Both regions are marked dirty.
     * <p>
     * Thread-safe: concurrent entity updates are supported.
     *
     * @param entityId Entity identifier (prefixed with upstream label for multi-upstream support)
     * @param x        X coordinate
     * @param y        Y coordinate
     * @param z        Z coordinate
     * @param type     Entity type (e.g., "PREY", "PREDATOR")
     */
    public void updateEntity(String entityId, float x, float y, float z, String type) {
        var newRegion = regionForPosition(x, y, z);
        var oldRegion = entityRegionMap.get(entityId);

        if (oldRegion != null && !oldRegion.equals(newRegion)) {
            // Entity crossed region boundary
            var oldState = regions.get(oldRegion);
            if (oldState != null) {
                oldState.entities.removeIf(e -> e.id().equals(entityId));
                oldState.dirty.set(true);
                oldState.lastModifiedMs = clock.currentTimeMillis();
                log.debug("Entity {} moved from region {} to {}", entityId, oldRegion, newRegion);
            }
        }

        var newState = regions.computeIfAbsent(newRegion, this::createRegionState);

        // Update or add entity in new region
        var position = new EntityPosition(entityId, x, y, z, type);
        newState.entities.removeIf(e -> e.id().equals(entityId));  // Remove old position
        newState.entities.add(position);
        newState.dirty.set(true);
        newState.lastModifiedMs = clock.currentTimeMillis();

        entityRegionMap.put(entityId, newRegion);
    }

    /**
     * Remove an entity from tracking.
     * <p>
     * The region containing the entity is marked dirty.
     *
     * @param entityId Entity identifier
     */
    public void removeEntity(String entityId) {
        var region = entityRegionMap.remove(entityId);
        if (region != null) {
            var state = regions.get(region);
            if (state != null) {
                state.entities.removeIf(e -> e.id().equals(entityId));
                state.dirty.set(true);
                state.lastModifiedMs = clock.currentTimeMillis();
                log.debug("Entity {} removed from region {}", entityId, region);
            }
        }
    }

    /**
     * Determine which region contains a given position.
     * <p>
     * <b>C3 FIX</b>: Uses epsilon-based boundary handling to prevent precision loss.
     * Entities on exact boundaries are shifted into the next region using a small epsilon.
     * <p>
     * Out-of-bounds coordinates are clamped to the valid region range [0, regionsPerAxis-1].
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return RegionId for the containing region
     */
    public RegionId regionForPosition(float x, float y, float z) {
        // C3 FIX: Add epsilon to prevent boundary precision loss
        float epsilon = regionSize * BOUNDARY_EPSILON;

        int rx = (int) ((x - worldMin + epsilon) / regionSize);
        int ry = (int) ((y - worldMin + epsilon) / regionSize);
        int rz = (int) ((z - worldMin + epsilon) / regionSize);

        // Clamp to valid range [0, regionsPerAxis-1] for entities outside world bounds
        rx = Math.max(0, Math.min(regionsPerAxis - 1, rx));
        ry = Math.max(0, Math.min(regionsPerAxis - 1, ry));
        rz = Math.max(0, Math.min(regionsPerAxis - 1, rz));

        long mortonCode = MortonCurve.encode(rx, ry, rz);
        return new RegionId(mortonCode, regionLevel);
    }

    /**
     * Calculate the spatial bounds for a region.
     *
     * @param region Region identifier
     * @return Axis-aligned bounding box for the region
     */
    public RegionBounds boundsForRegion(RegionId region) {
        int[] coords = MortonCurve.decode(region.mortonCode());

        float minX = worldMin + coords[0] * regionSize;
        float minY = worldMin + coords[1] * regionSize;
        float minZ = worldMin + coords[2] * regionSize;

        float maxX = minX + regionSize;
        float maxY = minY + regionSize;
        float maxZ = minZ + regionSize;

        return new RegionBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Get all regions currently marked as dirty.
     *
     * @return Set of dirty region IDs
     */
    public Set<RegionId> dirtyRegions() {
        return regions.entrySet().stream()
                      .filter(e -> e.getValue().dirty.get())
                      .map(e -> e.getKey())
                      .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Get the state for a specific region.
     * <p>
     * Returns null if the region has no entities and has never been updated.
     *
     * @param region Region identifier
     * @return Region state, or null if region is empty
     */
    public RegionState getRegionState(RegionId region) {
        return regions.get(region);
    }

    /**
     * Get all active regions (regions with entities).
     *
     * @return Set of all region IDs
     */
    public Set<RegionId> getAllRegions() {
        return regions.keySet();
    }

    /**
     * Get the size of each region (cubic).
     */
    public float regionSize() {
        return regionSize;
    }

    /**
     * Get the world minimum coordinate.
     */
    public float worldMin() {
        return worldMin;
    }

    /**
     * Get the world maximum coordinate.
     */
    public float worldMax() {
        return worldMax;
    }

    /**
     * Get the number of regions per axis.
     */
    public int regionsPerAxis() {
        return regionsPerAxis;
    }

    private RegionState createRegionState(RegionId regionId) {
        return new RegionState(
            regionId,
            new CopyOnWriteArrayList<>(),
            new AtomicBoolean(false),
            new AtomicLong(0L),
            0L
        );
    }

    /**
     * State for a single spatial region.
     * <p>
     * Thread-safe: uses CopyOnWriteArrayList for entity storage (frequent iteration,
     * infrequent modification) and AtomicBoolean/AtomicLong for flags and counters.
     */
    public static class RegionState {
        private final RegionId id;
        private final CopyOnWriteArrayList<EntityPosition> entities;
        private final AtomicBoolean dirty;
        private final AtomicLong buildVersion;
        private volatile long lastModifiedMs;

        public RegionState(RegionId id,
                           CopyOnWriteArrayList<EntityPosition> entities,
                           AtomicBoolean dirty,
                           AtomicLong buildVersion,
                           long lastModifiedMs) {
            this.id = id;
            this.entities = entities;
            this.dirty = dirty;
            this.buildVersion = buildVersion;
            this.lastModifiedMs = lastModifiedMs;
        }

        public RegionId id() {
            return id;
        }

        public CopyOnWriteArrayList<EntityPosition> entities() {
            return entities;
        }

        public AtomicBoolean dirty() {
            return dirty;
        }

        public AtomicLong buildVersion() {
            return buildVersion;
        }

        public long lastModifiedMs() {
            return lastModifiedMs;
        }
    }
}
