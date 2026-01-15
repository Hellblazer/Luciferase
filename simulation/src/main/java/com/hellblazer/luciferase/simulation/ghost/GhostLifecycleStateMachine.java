/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 1 Causality component for ghost lifecycle state transitions (M2 Phase 2).
 * <p>
 * GhostLifecycleStateMachine manages ghost entity lifecycle independently of physics (dead reckoning)
 * or VON integration concerns. It provides pure state transition logic:
 * <ul>
 *   <li>State transitions: CREATED → ACTIVE → STALE → EXPIRED</li>
 *   <li>TTL management (configurable, default 500ms)</li>
 *   <li>Staleness detection (configurable threshold, default 300ms)</li>
 *   <li>Thread-safe concurrent operations (ConcurrentHashMap)</li>
 *   <li>Clock injection for deterministic testing</li>
 *   <li>Metrics hooks (optional callback interface)</li>
 * </ul>
 * <p>
 * <strong>State Transitions:</strong>
 * <pre>
 * CREATED → ACTIVE (on first update)
 * ACTIVE → STALE (when lastUpdate + stalenessThreshold < currentTime)
 * STALE → EXPIRED (when lastUpdate + TTL < currentTime)
 * EXPIRED → removed (cleanup via expireStaleGhosts)
 * </pre>
 * <p>
 * <strong>Architecture Alignment (M1 ADR):</strong>
 * <ul>
 *   <li>Layer 1 Causality: Pure state transitions without side effects</li>
 *   <li>No physics (dead reckoning belongs in GhostStateManager)</li>
 *   <li>No VON integration (ExternalBubbleTracker belongs in BubbleGhostManager)</li>
 *   <li>Thread-safe: ConcurrentHashMap pattern from B2</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var lifecycle = new GhostLifecycleStateMachine();
 * lifecycle.setClock(testClock); // Optional: for deterministic testing
 *
 * // Create ghost
 * lifecycle.onCreate(entityId, sourceBubbleId, timestamp);
 *
 * // Update ghost (CREATED → ACTIVE)
 * lifecycle.onUpdate(entityId, timestamp);
 *
 * // Check staleness
 * if (lifecycle.isStale(entityId, currentTime)) {
 *     // Handle stale ghost
 * }
 *
 * // Expire stale ghosts
 * int expiredCount = lifecycle.expireStaleGhosts(currentTime);
 * </pre>
 *
 * @author hal.hildebrand
 */
public class GhostLifecycleStateMachine {

    private static final Logger log = LoggerFactory.getLogger(GhostLifecycleStateMachine.class);

    /**
     * Default TTL in milliseconds (500ms, matching GhostCullPolicy and GhostBoundarySync).
     */
    public static final long DEFAULT_TTL_MS = 500L;

    /**
     * Default staleness threshold in milliseconds (500ms, same as TTL for compatibility).
     * Can be configured separately for gradual warning states.
     */
    public static final long DEFAULT_STALENESS_THRESHOLD_MS = 500L;

    /**
     * Ghost lifecycle state enum.
     */
    public enum State {
        /** Ghost just created, waiting for first update */
        CREATED,
        /** Ghost actively receiving updates */
        ACTIVE,
        /** Ghost hasn't received updates within staleness threshold (warning state) */
        STALE,
        /** Ghost exceeded TTL, ready for removal */
        EXPIRED
    }

    /**
     * Per-ghost lifecycle state tracking.
     *
     * @param entityId       Entity identifier
     * @param state          Current lifecycle state
     * @param createdAt      When ghost was first created (milliseconds)
     * @param lastUpdateAt   When ghost was last updated (milliseconds)
     * @param sourceBubbleId Source bubble that owns this ghost
     */
    public record GhostLifecycleState(
        String entityId,
        State state,
        long createdAt,
        long lastUpdateAt,
        UUID sourceBubbleId
    ) {
    }

    /**
     * Optional metrics callback interface for lifecycle events.
     */
    public interface GhostLifecycleMetrics {
        /**
         * Called when a ghost is created.
         *
         * @param entityId Entity that was created
         */
        void onGhostCreated(String entityId);

        /**
         * Called when a ghost is updated.
         *
         * @param entityId Entity that was updated
         */
        void onGhostUpdated(String entityId);

        /**
         * Called when a ghost expires and is removed.
         *
         * @param entityId Entity that expired
         */
        void onGhostExpired(String entityId);
    }

    /**
     * TTL in milliseconds (default 500ms).
     */
    private final long ttlMillis;

    /**
     * Staleness threshold in milliseconds (default 300ms).
     */
    private final long stalenessThresholdMillis;

    /**
     * Ghost lifecycle states by entity ID (thread-safe).
     */
    private final Map<String, GhostLifecycleState> states;

    /**
     * Clock for deterministic testing.
     */
    private volatile Clock clock = Clock.system();

    /**
     * Optional metrics callback.
     */
    private volatile GhostLifecycleMetrics metrics;

    /**
     * Create lifecycle state machine with default TTL (500ms) and staleness threshold (300ms).
     */
    public GhostLifecycleStateMachine() {
        this(DEFAULT_TTL_MS, DEFAULT_STALENESS_THRESHOLD_MS);
    }

    /**
     * Create lifecycle state machine with custom TTL and staleness threshold.
     *
     * @param ttlMillis                  TTL in milliseconds
     * @param stalenessThresholdMillis   Staleness threshold in milliseconds
     * @throws IllegalArgumentException if ttlMillis or stalenessThresholdMillis is negative
     */
    public GhostLifecycleStateMachine(long ttlMillis, long stalenessThresholdMillis) {
        if (ttlMillis < 0) {
            throw new IllegalArgumentException("ttlMillis must be non-negative: " + ttlMillis);
        }
        if (stalenessThresholdMillis < 0) {
            throw new IllegalArgumentException("stalenessThresholdMillis must be non-negative: " + stalenessThresholdMillis);
        }

        this.ttlMillis = ttlMillis;
        this.stalenessThresholdMillis = stalenessThresholdMillis;
        this.states = new ConcurrentHashMap<>();

        log.debug("GhostLifecycleStateMachine initialized with TTL {}ms, staleness threshold {}ms",
                 ttlMillis, stalenessThresholdMillis);
    }

    /**
     * Create new ghost in CREATED state.
     *
     * @param entityId       Entity identifier
     * @param sourceBubbleId Source bubble that owns this ghost
     * @param timestamp      Creation timestamp (milliseconds)
     */
    public void onCreate(String entityId, UUID sourceBubbleId, long timestamp) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");

        var state = new GhostLifecycleState(entityId, State.CREATED, timestamp, timestamp, sourceBubbleId);
        states.put(entityId, state);

        log.debug("Ghost created: {} from bubble {} at time {}", entityId, sourceBubbleId, timestamp);

        if (metrics != null) {
            metrics.onGhostCreated(entityId);
        }
    }

    /**
     * Update ghost state (CREATED → ACTIVE on first update, refresh timestamp).
     *
     * @param entityId  Entity identifier
     * @param timestamp Update timestamp (milliseconds)
     */
    public void onUpdate(String entityId, long timestamp) {
        Objects.requireNonNull(entityId, "entityId must not be null");

        states.computeIfPresent(entityId, (id, existing) -> {
            var newState = existing.state == State.CREATED ? State.ACTIVE : existing.state;
            var updated = new GhostLifecycleState(
                entityId,
                newState,
                existing.createdAt,
                timestamp,
                existing.sourceBubbleId
            );

            log.trace("Ghost updated: {} state {} → {} at time {}",
                     entityId, existing.state, newState, timestamp);

            return updated;
        });

        if (metrics != null) {
            metrics.onGhostUpdated(entityId);
        }
    }

    /**
     * Get current state for a ghost.
     *
     * @param entityId Entity identifier
     * @return Current state, or null if ghost doesn't exist
     */
    public State getState(String entityId) {
        var state = states.get(entityId);
        return state != null ? state.state : null;
    }

    /**
     * Get full lifecycle state for a ghost.
     *
     * @param entityId Entity identifier
     * @return GhostLifecycleState or null if ghost doesn't exist
     */
    public GhostLifecycleState getLifecycleState(String entityId) {
        return states.get(entityId);
    }

    /**
     * Check if ghost is stale based on time since last update.
     * <p>
     * A ghost is stale if: (currentTime - lastUpdateTime) > stalenessThresholdMillis
     * <p>
     * Clock skew handling: If currentTime < lastUpdateTime (negative time delta),
     * the ghost is considered NOT stale.
     *
     * @param entityId    Entity identifier
     * @param currentTime Current time (milliseconds)
     * @return true if ghost is stale
     */
    public boolean isStale(String entityId, long currentTime) {
        var state = states.get(entityId);
        if (state == null) {
            return false;
        }

        long timeSinceUpdate = currentTime - state.lastUpdateAt;

        // Handle clock skew: if time went backward, ghost is not stale
        if (timeSinceUpdate < 0) {
            return false;
        }

        return timeSinceUpdate > stalenessThresholdMillis;
    }

    /**
     * Check if ghost is expired based on TTL.
     * <p>
     * A ghost is expired if: (currentTime - lastUpdateTime) > ttlMillis
     * <p>
     * Clock skew handling: If currentTime < lastUpdateTime (negative time delta),
     * the ghost is considered NOT expired.
     *
     * @param entityId    Entity identifier
     * @param currentTime Current time (milliseconds)
     * @return true if ghost is expired and should be removed
     */
    public boolean isExpired(String entityId, long currentTime) {
        var state = states.get(entityId);
        if (state == null) {
            return false;
        }

        long timeSinceUpdate = currentTime - state.lastUpdateAt;

        // Handle clock skew: if time went backward, ghost is not expired
        if (timeSinceUpdate < 0) {
            return false;
        }

        return timeSinceUpdate > ttlMillis;
    }

    /**
     * Find all expired ghosts at current time.
     *
     * @param currentTime Current time (milliseconds)
     * @return List of expired entity IDs
     */
    public List<String> findExpired(long currentTime) {
        var expired = new ArrayList<String>();

        for (var entry : states.entrySet()) {
            var entityId = entry.getKey();
            if (isExpired(entityId, currentTime)) {
                expired.add(entityId);
            }
        }

        return expired;
    }

    /**
     * Expire and remove all stale ghosts beyond TTL.
     *
     * @param currentTime Current time (milliseconds)
     * @return Number of ghosts expired
     */
    public int expireStaleGhosts(long currentTime) {
        var expired = findExpired(currentTime);
        int expiredCount = 0;

        for (var entityId : expired) {
            var removed = states.remove(entityId);
            if (removed != null) {
                expiredCount++;
                log.debug("Ghost expired and removed: {} at time {}", entityId, currentTime);

                if (metrics != null) {
                    metrics.onGhostExpired(entityId);
                }
            }
        }

        return expiredCount;
    }

    /**
     * Remove ghost from tracking (manual removal, not expiration).
     *
     * @param entityId Entity identifier
     */
    public void remove(String entityId) {
        var removed = states.remove(entityId);
        if (removed != null) {
            log.debug("Ghost removed: {}", entityId);
        }
    }

    /**
     * Set clock source for deterministic testing.
     *
     * @param clock Clock implementation
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Set metrics callback for lifecycle events.
     *
     * @param metrics Metrics callback (null to disable)
     */
    public void setMetrics(GhostLifecycleMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Get TTL in milliseconds.
     *
     * @return TTL
     */
    public long getTtlMillis() {
        return ttlMillis;
    }

    /**
     * Get staleness threshold in milliseconds.
     *
     * @return Staleness threshold
     */
    public long getStalenessThresholdMillis() {
        return stalenessThresholdMillis;
    }

    /**
     * Get number of active ghosts tracked.
     *
     * @return Ghost count
     */
    public int getActiveGhostCount() {
        return states.size();
    }

    @Override
    public String toString() {
        return String.format("GhostLifecycleStateMachine{activeGhosts=%d, ttl=%dms, stalenessThreshold=%dms}",
                            states.size(), ttlMillis, stalenessThresholdMillis);
    }
}
