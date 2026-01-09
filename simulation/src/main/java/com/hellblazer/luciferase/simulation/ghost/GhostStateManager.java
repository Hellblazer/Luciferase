/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.spatial.DeadReckoningEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ghost state manager for tracking and updating ghost entities with dead reckoning (Phase 7B.3).
 * <p>
 * GhostStateManager provides:
 * - Track SimulationGhostEntity + velocity per entity
 * - Handle incoming EntityUpdateEvent (update position + velocity)
 * - Dead reckoning extrapolation between updates
 * - Staleness detection and ghost removal
 * - Thread-safe concurrent access
 * <p>
 * <strong>Architecture:</strong>
 * <ul>
 *   <li>Uses DeadReckoningEstimator for position extrapolation</li>
 *   <li>Uses GhostCullPolicy for staleness detection</li>
 *   <li>Integrates with DelosSocketTransport for network updates</li>
 *   <li>Coordinates with RealTimeController for tick updates</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var bounds = BubbleBounds.fromTetreeKey(rootKey);
 * var manager = new GhostStateManager(bounds, 1000);
 *
 * // Handle incoming ghost update from network
 * manager.updateGhost(sourceBubbleId, entityUpdateEvent);
 *
 * // Tick on simulation step (dead reckoning)
 * manager.tick(currentTime);
 *
 * // Get extrapolated position
 * var position = manager.getGhostPosition(entityId, currentTime);
 *
 * // Remove stale ghosts
 * if (manager.isStale(entityId, currentTime)) {
 *     manager.removeGhost(entityId);
 * }
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> All operations are thread-safe using ConcurrentHashMap.
 *
 * @author hal.hildebrand
 */
public class GhostStateManager {

    private static final Logger log = LoggerFactory.getLogger(GhostStateManager.class);

    /**
     * Per-ghost state tracking (position, velocity, timestamps).
     *
     * @param ghostEntity   SimulationGhostEntity with position and metadata
     * @param velocity      Velocity vector for dead reckoning (units per second)
     * @param createdAt     When ghost was first seen (milliseconds)
     * @param lastUpdateAt  When ghost was last updated (milliseconds)
     * @param sourceBubbleId Source bubble that owns this ghost
     */
    private record GhostState(
        SimulationGhostEntity<StringEntityID, EntityData> ghostEntity,
        Vector3f velocity,
        long createdAt,
        long lastUpdateAt,
        UUID sourceBubbleId
    ) {
    }

    /**
     * Adapter for DeadReckoningEstimator to work with GhostState.
     */
    private static class GhostStateAdapter implements com.hellblazer.luciferase.simulation.ghost.GhostEntity {
        private final GhostState state;

        GhostStateAdapter(GhostState state) {
            this.state = state;
        }

        @Override
        public com.hellblazer.luciferase.lucien.entity.EntityID id() {
            return state.ghostEntity.entityId();
        }

        @Override
        public Point3f position() {
            return state.ghostEntity.position();
        }

        @Override
        public Vector3f velocity() {
            return state.velocity;
        }

        @Override
        public long timestamp() {
            return state.lastUpdateAt;
        }
    }

    /**
     * Spatial bounds for ghost position clamping (dead reckoning boundary).
     */
    private final BubbleBounds bounds;

    /**
     * Maximum number of ghosts to track (memory limit).
     */
    private final int maxGhosts;

    /**
     * Dead reckoning estimator for position extrapolation.
     */
    private final DeadReckoningEstimator deadReckoning;

    /**
     * Culling policy for staleness detection (default 500ms).
     */
    private final GhostCullPolicy cullPolicy;

    /**
     * Active ghost state by entity ID.
     */
    private final Map<StringEntityID, GhostState> ghostStates;

    /**
     * Create GhostStateManager with specified bounds and ghost limit.
     *
     * @param bounds     Spatial bounds for extrapolation clamping
     * @param maxGhosts  Maximum number of ghosts to track
     */
    public GhostStateManager(BubbleBounds bounds, int maxGhosts) {
        this.bounds = Objects.requireNonNull(bounds, "bounds must not be null");
        this.maxGhosts = maxGhosts;
        this.deadReckoning = new DeadReckoningEstimator();
        this.cullPolicy = new GhostCullPolicy();
        this.ghostStates = new ConcurrentHashMap<>();

        log.debug("GhostStateManager initialized with bounds {} and max ghosts {}", bounds, maxGhosts);
    }

    /**
     * Handle incoming ghost update from network (via DelosSocketTransport).
     * Creates new ghost or updates existing ghost with new position and velocity.
     *
     * @param sourceBubbleId Source bubble that sent this update
     * @param event          EntityUpdateEvent with position, velocity, timestamp
     */
    public void updateGhost(UUID sourceBubbleId, EntityUpdateEvent event) {
        Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");
        Objects.requireNonNull(event, "event must not be null");

        var entityId = (StringEntityID) event.entityId();
        var position = event.position();
        var velocity = new Vector3f(event.velocity());
        var timestamp = event.timestamp();

        // Check max ghost limit
        if (ghostStates.size() >= maxGhosts && !ghostStates.containsKey(entityId)) {
            log.warn("Max ghost limit ({}) reached, dropping update for {}", maxGhosts, entityId);
            return;
        }

        // Get or create ghost state
        var existingState = ghostStates.get(entityId);
        long createdAt = existingState != null ? existingState.createdAt : timestamp;

        // Create SimulationGhostEntity
        var ghostEntity = createGhostEntity(entityId, position, sourceBubbleId, timestamp, event.lamportClock());

        // Create or update ghost state
        var newState = new GhostState(ghostEntity, velocity, createdAt, timestamp, sourceBubbleId);
        ghostStates.put(entityId, newState);

        // Notify dead reckoning estimator of authoritative update
        var adapter = new GhostStateAdapter(newState);
        deadReckoning.onAuthoritativeUpdate(adapter, position);

        log.debug("Updated ghost {} from bubble {} at position {} with velocity {}",
                 entityId, sourceBubbleId, position, velocity);
    }

    /**
     * Get current ghost position with dead reckoning extrapolation.
     * Position is extrapolated from last known position using velocity and time delta.
     *
     * @param entityId    Entity to get position for
     * @param currentTime Current simulation time (milliseconds)
     * @return Extrapolated position (clamped to bounds), or null if ghost doesn't exist
     */
    public Point3f getGhostPosition(StringEntityID entityId, long currentTime) {
        var state = ghostStates.get(entityId);
        if (state == null) {
            return null;
        }

        // Use dead reckoning to extrapolate position
        var adapter = new GhostStateAdapter(state);
        var predictedPosition = deadReckoning.predict(adapter, currentTime);

        // Check for null prediction
        if (predictedPosition == null) {
            log.warn("Dead reckoning returned null for entity {}, using last known position", entityId);
            return new Point3f(state.ghostEntity.position());
        }

        // Clamp to bounds
        return clampToBounds(predictedPosition);
    }

    /**
     * Tick ghost state on simulation step.
     * Updates dead reckoning estimates and culls stale ghosts.
     *
     * @param currentTime Current simulation time (milliseconds)
     */
    public void tick(long currentTime) {
        // Dead reckoning estimator handles prediction internally
        // We just need to identify and remove stale ghosts

        var staleGhosts = new ArrayList<StringEntityID>();

        for (var entry : ghostStates.entrySet()) {
            var entityId = entry.getKey();
            var state = entry.getValue();

            if (cullPolicy.isStale(state.lastUpdateAt, currentTime)) {
                staleGhosts.add(entityId);
            }
        }

        // Remove stale ghosts
        for (var entityId : staleGhosts) {
            removeGhost(entityId);
            log.debug("Culled stale ghost {} at time {}", entityId, currentTime);
        }
    }

    /**
     * Get all active ghosts.
     *
     * @return Collection of active ghost entities
     */
    public Collection<SimulationGhostEntity<StringEntityID, EntityData>> getActiveGhosts() {
        return ghostStates.values().stream()
            .map(GhostState::ghostEntity)
            .toList();
    }

    /**
     * Get ghost entity by ID.
     *
     * @param entityId Entity ID to lookup
     * @return SimulationGhostEntity or null if not found
     */
    public SimulationGhostEntity<StringEntityID, EntityData> getGhost(StringEntityID entityId) {
        var state = ghostStates.get(entityId);
        return state != null ? state.ghostEntity : null;
    }

    /**
     * Remove ghost from tracking.
     * Clears both state and dead reckoning prediction.
     *
     * @param entityId Entity ID to remove
     */
    public void removeGhost(StringEntityID entityId) {
        var removed = ghostStates.remove(entityId);
        if (removed != null) {
            deadReckoning.clearEntity(entityId);
            log.debug("Removed ghost {}", entityId);
        }
    }

    /**
     * Check if ghost is stale based on last update time.
     *
     * @param entityId    Entity to check
     * @param currentTime Current simulation time (milliseconds)
     * @return true if ghost is stale and should be culled
     */
    public boolean isStale(StringEntityID entityId, long currentTime) {
        var state = ghostStates.get(entityId);
        if (state == null) {
            return false;
        }

        return cullPolicy.isStale(state.lastUpdateAt, currentTime);
    }

    /**
     * Get number of active ghosts.
     *
     * @return Ghost count
     */
    public int getActiveGhostCount() {
        return ghostStates.size();
    }

    // ========== Internal Helper Methods ==========

    /**
     * Create SimulationGhostEntity from EntityUpdateEvent.
     */
    @SuppressWarnings("rawtypes")
    private SimulationGhostEntity<StringEntityID, EntityData> createGhostEntity(
        StringEntityID entityId,
        Point3f position,
        UUID sourceBubbleId,
        long timestamp,
        long bucket
    ) {
        // Create EntityData (content)
        EntityData content = new EntityData<>(entityId, position, (byte) 10, null);

        // Create GhostEntity wrapper (from lucien.forest.ghost.GhostZoneManager)
        var ghostEntity = new GhostZoneManager.GhostEntity<>(
            entityId,
            content,
            position,
            new EntityBounds(position, 0.1f), // small radius
            "remote-" + sourceBubbleId // sourceTreeId
        );

        // Wrap in SimulationGhostEntity with metadata
        return new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            bucket,     // bucket (using lamport clock as bucket)
            0L,         // epoch (not transmitted in Phase 7B.2)
            1L          // version
        );
    }

    /**
     * Clamp position to bubble bounds.
     * Uses BubbleBounds.contains() to check if position is within bounds.
     * If outside bounds, clamps to RDGCS min/max in Cartesian space.
     */
    private Point3f clampToBounds(Point3f position) {
        // Check if already within bounds
        if (bounds.contains(position)) {
            return position;
        }

        // Convert to RDGCS for clamping
        var rdgPos = bounds.toRDG(position);
        var rdgMin = bounds.rdgMin();
        var rdgMax = bounds.rdgMax();

        // Clamp RDGCS coordinates
        var clampedRdg = new javax.vecmath.Point3i(
            Math.max(rdgMin.x, Math.min(rdgMax.x, rdgPos.x)),
            Math.max(rdgMin.y, Math.min(rdgMax.y, rdgPos.y)),
            Math.max(rdgMin.z, Math.min(rdgMax.z, rdgPos.z))
        );

        // Convert back to Cartesian
        var clampedCartesian = bounds.toCartesian(clampedRdg);

        return new Point3f((float) clampedCartesian.getX(),
                          (float) clampedCartesian.getY(),
                          (float) clampedCartesian.getZ());
    }

    @Override
    public String toString() {
        return String.format("GhostStateManager{ghosts=%d, maxGhosts=%d, bounds=%s}",
                            ghostStates.size(), maxGhosts, bounds);
    }
}
