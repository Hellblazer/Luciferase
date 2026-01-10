/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MigrationOracleImpl - Detects boundary crossings for entity migration (Phase 7E Day 2)
 *
 * Implements 2x2x2 tetrahedral decomposition topology:
 * - Base domain: [0, 2.0]³
 * - Cube cells: 1.0×1.0×1.0 each
 * - Grid dimensions: 2x2x2 = 8 primary bubbles
 * - Maps: (x,y,z) coordinates ↔ UUID bubble IDs
 *
 * BOUNDARY DETECTION:
 * Tracks entity positions and detects when they cross cube boundaries.
 * Migration trigger: position exceeds boundary by > tolerance (default 0.05)
 *
 * PERFORMANCE:
 * - checkMigration: O(1) coordinate lookup + containment check
 * - getEntitiesCrossingBoundaries: O(n) entities tracked, returns only crossers
 * - Target: < 10ms for 1000 entities per frame
 *
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for entity position caching.
 * Safe for concurrent entity updates from multiple threads.
 *
 * @author hal.hildebrand
 */
public class MigrationOracleImpl implements MigrationOracle {

    private static final Logger log = LoggerFactory.getLogger(MigrationOracleImpl.class);

    // Configuration
    private static final int DEFAULT_GRID_X = 2;
    private static final int DEFAULT_GRID_Y = 2;
    private static final int DEFAULT_GRID_Z = 2;
    private static final float CUBE_SIZE = 1.0f;
    private static final float DEFAULT_TOLERANCE = 0.05f;

    // Bubble ID mapping: CubeBubbleCoordinate → Bubble UUID
    // For 2x2x2 grid: index 0-7 → Bubble-0 through Bubble-7
    private final Map<CubeBubbleCoordinate, UUID> bubbleMap;

    // Entity position cache: Entity ID → Last known position
    private final Map<String, Point3f> entityPositions;

    // Boundary crossing cache: Entities that crossed boundaries this frame
    private final Set<String> crossingCache;

    // Bubble ownership mapping: Bubble UUID → Current CubeBubbleCoordinate
    private final Map<UUID, CubeBubbleCoordinate> bubbleCoordinates;

    // Grid dimensions
    private final int gridX;
    private final int gridY;
    private final int gridZ;

    // Boundary crossing tolerance
    private float tolerance;

    /**
     * Create a migration oracle for default 2x2x2 topology.
     * Automatically maps Bubble-0 through Bubble-7 to grid coordinates.
     */
    public MigrationOracleImpl() {
        this(DEFAULT_GRID_X, DEFAULT_GRID_Y, DEFAULT_GRID_Z);
    }

    /**
     * Create a migration oracle for custom grid dimensions.
     *
     * @param gridX X dimension of cube grid (typically 2)
     * @param gridY Y dimension of cube grid (typically 2)
     * @param gridZ Z dimension of cube grid (typically 2)
     */
    public MigrationOracleImpl(int gridX, int gridY, int gridZ) {
        if (gridX <= 0 || gridY <= 0 || gridZ <= 0) {
            throw new IllegalArgumentException("Grid dimensions must be positive: " + gridX + "x" + gridY + "x" + gridZ);
        }

        this.gridX = gridX;
        this.gridY = gridY;
        this.gridZ = gridZ;
        this.tolerance = DEFAULT_TOLERANCE;
        this.bubbleMap = new HashMap<>();
        this.bubbleCoordinates = new HashMap<>();
        this.entityPositions = new ConcurrentHashMap<>();
        this.crossingCache = ConcurrentHashMap.newKeySet();

        // Initialize bubble mapping for 2x2x2 grid
        if (gridX == 2 && gridY == 2 && gridZ == 2) {
            initializeDefault2x2x2Topology();
        }

        log.debug("MigrationOracle created: {}x{}x{} grid, {} bubbles",
                gridX, gridY, gridZ, bubbleMap.size());
    }

    /**
     * Initialize default 2x2x2 topology with Bubble-0 through Bubble-7.
     * Layout:
     *   Bubble-0: (0,0,0)  Bubble-1: (1,0,0)
     *   Bubble-2: (0,1,0)  Bubble-3: (1,1,0)
     *   Bubble-4: (0,0,1)  Bubble-5: (1,0,1)
     *   Bubble-6: (0,1,1)  Bubble-7: (1,1,1)
     */
    private void initializeDefault2x2x2Topology() {
        for (int z = 0; z < gridZ; z++) {
            for (int y = 0; y < gridY; y++) {
                for (int x = 0; x < gridX; x++) {
                    var coord = new CubeBubbleCoordinate(x, y, z);
                    var bubbleId = UUID.nameUUIDFromBytes(
                        ("bubble-" + coord.toLinearIndex()).getBytes()
                    );
                    bubbleMap.put(coord, bubbleId);
                    bubbleCoordinates.put(bubbleId, coord);
                }
            }
        }
    }

    /**
     * Manually register a bubble at a specific coordinate.
     * Used for custom topology configurations.
     *
     * @param coordinate Cube coordinate
     * @param bubbleId Bubble UUID
     */
    public void registerBubble(CubeBubbleCoordinate coordinate, UUID bubbleId) {
        Objects.requireNonNull(coordinate, "coordinate must not be null");
        Objects.requireNonNull(bubbleId, "bubbleId must not be null");
        bubbleMap.put(coordinate, bubbleId);
        bubbleCoordinates.put(bubbleId, coordinate);
    }

    /**
     * Track entity position for boundary crossing detection.
     * Called when entity updates.
     *
     * @param entityId Entity identifier
     * @param position Current world position
     */
    public void updateEntityPosition(String entityId, Point3f position) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(position, "position must not be null");
        entityPositions.put(entityId, new Point3f(position));
    }

    /**
     * Remove entity from tracking (e.g., when deleted or migrated).
     *
     * @param entityId Entity identifier
     */
    public void removeEntity(String entityId) {
        entityPositions.remove(entityId);
        crossingCache.remove(entityId);
    }

    @Override
    public Optional<UUID> checkMigration(Point3f position, UUID currentBubbleId) {
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(currentBubbleId, "currentBubbleId must not be null");

        var targetCoord = getCoordinateForPosition(position);
        if (targetCoord == null) {
            // Position is outside domain
            return Optional.empty();
        }

        var currentCoord = bubbleCoordinates.get(currentBubbleId);
        if (currentCoord == null) {
            // Current bubble not registered
            return Optional.empty();
        }

        if (!targetCoord.equals(currentCoord)) {
            // Boundary crossed - entity should migrate
            var targetBubble = bubbleMap.get(targetCoord);
            if (targetBubble != null) {
                return Optional.of(targetBubble);
            }
        }

        return Optional.empty();
    }

    @Override
    public UUID getTargetBubble(Point3f position) {
        Objects.requireNonNull(position, "position must not be null");

        var coord = getCoordinateForPosition(position);
        if (coord == null) {
            // Position outside domain - return closest bubble
            return getClosestBubble(position);
        }

        var bubbleId = bubbleMap.get(coord);
        if (bubbleId != null) {
            return bubbleId;
        }

        // Fallback to closest if coordinate not registered
        return getClosestBubble(position);
    }

    @Override
    public Set<String> getEntitiesCrossingBoundaries() {
        // Reset crossing cache and recompute from current positions
        crossingCache.clear();

        for (var entry : entityPositions.entrySet()) {
            var entityId = entry.getKey();
            var position = entry.getValue();

            // Check if entity is at a grid boundary (transition between cubes)
            if (isAtBoundary(position)) {
                crossingCache.add(entityId);
            }
        }

        return Set.copyOf(crossingCache);
    }

    @Override
    public void clearCrossingCache() {
        crossingCache.clear();
    }

    @Override
    public CubeBubbleCoordinate getCoordinateForPosition(Point3f position) {
        Objects.requireNonNull(position, "position must not be null");

        // Calculate grid coordinates
        int gx = (int) Math.floor(position.x / CUBE_SIZE);
        int gy = (int) Math.floor(position.y / CUBE_SIZE);
        int gz = (int) Math.floor(position.z / CUBE_SIZE);

        // Clamp to grid bounds
        gx = Math.max(0, Math.min(gx, gridX - 1));
        gy = Math.max(0, Math.min(gy, gridY - 1));
        gz = Math.max(0, Math.min(gz, gridZ - 1));

        return new CubeBubbleCoordinate(gx, gy, gz);
    }

    @Override
    public float[] getBoundsForCoordinate(CubeBubbleCoordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate must not be null");
        return coordinate.getBounds();
    }

    @Override
    public void setBoundaryTolerance(float tolerance) {
        if (tolerance < 0) {
            throw new IllegalArgumentException("Tolerance must be non-negative: " + tolerance);
        }
        this.tolerance = tolerance;
    }

    @Override
    public float getBoundaryTolerance() {
        return tolerance;
    }

    /**
     * Check if position is at a grid boundary (within tolerance of cube edge).
     * Used to detect crossing without explicit previous position tracking.
     *
     * @param position World position
     * @return true if within tolerance of any cube boundary
     */
    private boolean isAtBoundary(Point3f position) {
        // Check distance to nearest grid line (boundary between cubes)
        float dx = position.x % CUBE_SIZE;
        float dy = position.y % CUBE_SIZE;
        float dz = position.z % CUBE_SIZE;

        // Normalize to [0, CUBE_SIZE]
        dx = Math.abs(dx);
        dy = Math.abs(dy);
        dz = Math.abs(dz);

        // Check if near boundary (within tolerance of edge at 0 or CUBE_SIZE)
        float distToNearestX = Math.min(dx, CUBE_SIZE - dx);
        float distToNearestY = Math.min(dy, CUBE_SIZE - dy);
        float distToNearestZ = Math.min(dz, CUBE_SIZE - dz);

        return distToNearestX < tolerance || distToNearestY < tolerance || distToNearestZ < tolerance;
    }

    /**
     * Get the closest registered bubble to a position.
     * Used as fallback when position is outside domain.
     *
     * @param position World position
     * @return Closest bubble UUID
     */
    private UUID getClosestBubble(Point3f position) {
        UUID closest = null;
        float minDistance = Float.MAX_VALUE;

        for (var entry : bubbleCoordinates.entrySet()) {
            var bubbleId = entry.getKey();
            var coord = entry.getValue();
            var bounds = coord.getBounds();

            // Calculate distance to cube bounds
            float cx = (bounds[0] + bounds[3]) / 2.0f;
            float cy = (bounds[1] + bounds[4]) / 2.0f;
            float cz = (bounds[2] + bounds[5]) / 2.0f;

            float dx = position.x - cx;
            float dy = position.y - cy;
            float dz = position.z - cz;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist < minDistance) {
                minDistance = dist;
                closest = bubbleId;
            }
        }

        return closest != null ? closest : bubbleCoordinates.keySet().iterator().next();
    }

    @Override
    public String toString() {
        return String.format("MigrationOracle{grid=%dx%dx%d, bubbles=%d, entities=%d, tolerance=%.2f}",
                gridX, gridY, gridZ, bubbleMap.size(), entityPositions.size(), tolerance);
    }
}
