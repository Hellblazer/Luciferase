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
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.spatial.DeadReckoningEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
     * Epoch size in buckets (epoch = bucket / EPOCH_SIZE).
     */
    public static final int EPOCH_SIZE = 100;

    /**
     * Monotonic version counter for ghost versioning.
     */
    private final AtomicLong versionCounter = new AtomicLong(0);

    /**
     * Derive epoch from bucket number.
     * Epoch changes every EPOCH_SIZE buckets (10 seconds @ 100ms/bucket).
     *
     * @param bucket Bucket number
     * @return Epoch number
     */
    private long deriveEpoch(long bucket) {
        return bucket / EPOCH_SIZE;
    }

    /**
     * Per-ghost state tracking (position, velocity for dead reckoning).
     * <p>
     * Note: Timestamps and lifecycle state are now managed by GhostLifecycleStateMachine (Layer 1).
     *
     * @param ghostEntity   SimulationGhostEntity with position and metadata
     * @param velocity      Velocity vector for dead reckoning (units per second)
     */
    private record GhostState(
        SimulationGhostEntity<StringEntityID, EntityData> ghostEntity,
        Vector3f velocity
    ) {
    }

    /**
     * Adapter for DeadReckoningEstimator to work with GhostState.
     */
    private class GhostStateAdapter implements com.hellblazer.luciferase.simulation.ghost.GhostEntity {
        private final GhostState state;
        private final StringEntityID entityId;

        GhostStateAdapter(GhostState state, StringEntityID entityId) {
            this.state = state;
            this.entityId = entityId;
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
            // Get timestamp from lifecycle state machine
            var lifecycleState = lifecycle.getLifecycleState(entityId.toDebugString());
            return lifecycleState != null ? lifecycleState.lastUpdateAt() : 0L;
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
     * Lifecycle state machine for ghost state transitions (Layer 1 Causality).
     */
    private final GhostLifecycleStateMachine lifecycle;

    /**
     * Active ghost state by entity ID (position and velocity only).
     */
    private final Map<StringEntityID, GhostState> ghostStates;

    /**
     * Performance metrics (optional, null-safe).
     */
    private GhostPhysicsMetrics metrics;

    /**
     * Clock for deterministic testing.
     */
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
        this.lifecycle.setClock(clock);
    }

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
        this.lifecycle = new GhostLifecycleStateMachine(); // 500ms TTL, 300ms staleness threshold
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
        var startNanos = clock.nanoTime();  // Metrics: record start time

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

        // Update lifecycle state (creates if new, updates if existing)
        var existingState = ghostStates.get(entityId);
        if (existingState == null) {
            // New ghost: create in lifecycle state machine
            lifecycle.onCreate(entityId.toDebugString(), sourceBubbleId, timestamp);
        }
        lifecycle.onUpdate(entityId.toDebugString(), timestamp);

        // Create SimulationGhostEntity
        var ghostEntity = createGhostEntity(entityId, position, sourceBubbleId, timestamp, event.lamportClock());

        // Create or update ghost state (position + velocity only)
        var newState = new GhostState(ghostEntity, velocity);
        ghostStates.put(entityId, newState);

        // Notify dead reckoning estimator of authoritative update
        var adapter = new GhostStateAdapter(newState, entityId);
        deadReckoning.onAuthoritativeUpdate(adapter, position);

        log.debug("Updated ghost {} from bubble {} at position {} with velocity {}",
                 entityId, sourceBubbleId, position, velocity);

        // Metrics: record latency
        if (metrics != null) {
            metrics.recordUpdateGhost(clock.nanoTime() - startNanos);
        }
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
        var adapter = new GhostStateAdapter(state, entityId);
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
        // Delegate staleness detection to lifecycle state machine

        var expiredCount = lifecycle.expireStaleGhosts(currentTime);

        if (expiredCount > 0) {
            // Remove expired ghosts from our local state
            for (var entityId : ghostStates.keySet()) {
                if (lifecycle.getState(entityId.toDebugString()) == null) {
                    removeGhost(entityId);
                    log.debug("Culled stale ghost {} at time {}", entityId, currentTime);
                }
            }
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
     * Get ghost velocity for a given entity.
     * Returns the velocity tracked in the ghost state, used for dead reckoning.
     *
     * @param entityId Entity ID to lookup
     * @return Velocity vector, or zero vector (0,0,0) if ghost doesn't exist
     */
    public Vector3f getGhostVelocity(StringEntityID entityId) {
        var state = ghostStates.get(entityId);
        if (state == null) {
            return new Vector3f(0.0f, 0.0f, 0.0f);
        }
        return new Vector3f(state.velocity);
    }

    /**
     * Remove ghost from tracking.
     * Clears both state and dead reckoning prediction.
     *
     * @param entityId Entity ID to remove
     */
    public void removeGhost(StringEntityID entityId) {
        var startNanos = clock.nanoTime();  // Metrics: record start time

        var removed = ghostStates.remove(entityId);
        if (removed != null) {
            deadReckoning.clearEntity(entityId);
            lifecycle.remove(entityId.toDebugString());
            log.debug("Removed ghost {}", entityId);
        }

        // Metrics: record latency
        if (metrics != null) {
            metrics.recordRemoveGhost(clock.nanoTime() - startNanos);
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
        // Delegate to lifecycle state machine
        return lifecycle.isStale(entityId.toDebugString(), currentTime);
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
            bucket,
            deriveEpoch(bucket),             // epoch from bucket
            versionCounter.incrementAndGet() // monotonic version
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

    /**
     * Set metrics tracker for performance monitoring.
     * Optional: if not set, operations proceed normally without metrics.
     *
     * @param metrics GhostPhysicsMetrics instance
     */
    public void setMetrics(GhostPhysicsMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Get current metrics (may be null).
     *
     * @return GhostPhysicsMetrics instance or null if not set
     */
    public GhostPhysicsMetrics getMetrics() {
        return metrics;
    }

    @Override
    public String toString() {
        return String.format("GhostStateManager{ghosts=%d, maxGhosts=%d, bounds=%s}",
                            ghostStates.size(), maxGhosts, bounds);
    }
}
