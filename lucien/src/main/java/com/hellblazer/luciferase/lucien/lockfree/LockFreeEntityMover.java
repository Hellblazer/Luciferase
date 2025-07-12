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
package com.hellblazer.luciferase.lucien.lockfree;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.lockfree.VersionedEntityState.AtomicVersionedState;
import com.hellblazer.luciferase.lucien.lockfree.VersionedEntityState.MovementState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Lock-free entity movement protocol ensuring entities are always findable during spatial updates.
 * Uses a four-phase atomic movement protocol: PREPARE → INSERT → UPDATE → REMOVE.
 * 
 * <p>This implementation guarantees that entities remain discoverable throughout the movement process,
 * preventing race conditions where entities temporarily disappear from the spatial index.</p>
 * 
 * <h2>Movement Protocol</h2>
 * <ol>
 *   <li><b>PREPARE</b>: Mark entity as MOVING, validate current state</li>
 *   <li><b>INSERT</b>: Add entity to new spatial locations before removing from old</li>
 *   <li><b>UPDATE</b>: Update entity state with new position and locations</li>
 *   <li><b>REMOVE</b>: Clean up old spatial locations, mark entity as STABLE</li>
 * </ol>
 * 
 * @param <Key> The spatial key type
 * @param <ID> The entity ID type  
 * @param <Content> The entity content type
 * @author hal.hildebrand
 */
public class LockFreeEntityMover<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(LockFreeEntityMover.class);

    /**
     * Configuration for movement operations
     */
    public static class MovementConfig {
        public final int maxRetries;
        public final long baseBackoffNanos;
        public final long maxBackoffNanos;
        public final boolean enableStatistics;

        public MovementConfig(int maxRetries, long baseBackoffNanos, long maxBackoffNanos, boolean enableStatistics) {
            this.maxRetries = maxRetries;
            this.baseBackoffNanos = baseBackoffNanos;
            this.maxBackoffNanos = maxBackoffNanos;
            this.enableStatistics = enableStatistics;
        }

        public static MovementConfig defaultConfig() {
            return new MovementConfig(10, 1000, 1_000_000, false);
        }
    }

    /**
     * Result of a movement operation
     */
    public enum MovementResult {
        SUCCESS,
        ENTITY_NOT_FOUND,
        VERSION_CONFLICT,
        MAX_RETRIES_EXCEEDED,
        SPATIAL_CONFLICT,
        INTERRUPTED
    }

    /**
     * Statistics for movement operations
     */
    public static class MovementStats {
        public long successCount;
        public long conflictCount;
        public long retryCount;
        public long averageRetries;
        public long maxRetries;

        public void recordSuccess(int retries) {
            successCount++;
            retryCount += retries;
            maxRetries = Math.max(maxRetries, retries);
            averageRetries = retryCount / successCount;
        }

        public void recordConflict() {
            conflictCount++;
        }

        @Override
        public String toString() {
            return String.format("MovementStats{success=%d, conflicts=%d, avgRetries=%.1f, maxRetries=%d}",
                               successCount, conflictCount, (double) averageRetries, maxRetries);
        }
    }

    private final ConcurrentNavigableMap<Key, AtomicSpatialNode<ID>> spatialIndex;
    private final Function<Point3f, Set<Key>> positionToKeysFunction;
    private final MovementConfig config;
    private final MovementStats stats;

    /**
     * Creates a new lock-free entity mover.
     *
     * @param spatialIndex The spatial index to operate on
     * @param positionToKeysFunction Function to convert positions to spatial keys
     * @param config Movement configuration
     */
    public LockFreeEntityMover(ConcurrentNavigableMap<Key, AtomicSpatialNode<ID>> spatialIndex,
                              Function<Point3f, Set<Key>> positionToKeysFunction,
                              MovementConfig config) {
        this.spatialIndex = spatialIndex;
        this.positionToKeysFunction = positionToKeysFunction;
        this.config = config;
        this.stats = new MovementStats();
    }

    /**
     * Atomically moves an entity to a new position using lock-free protocols.
     * 
     * @param entityState The atomic entity state container
     * @param newPosition The target position
     * @return MovementResult indicating success or failure reason
     */
    public MovementResult moveEntity(AtomicVersionedState<Key, ID, Content> entityState, Point3f newPosition) {
        int attempt = 0;
        
        while (attempt < config.maxRetries) {
            var currentState = entityState.get();
            
            // Phase 1: PREPARE - Validate and mark as moving
            if (!currentState.isStable()) {
                if (config.enableStatistics) {
                    stats.recordConflict();
                }
                return waitAndRetry(attempt++);
            }

            // Calculate new spatial locations
            var newKeys = positionToKeysFunction.apply(newPosition);
            var oldKeys = currentState.getSpatialLocations();

            // Phase 2: INSERT - Add to new locations first
            var insertResult = insertToNewLocations(currentState.getEntityId(), newKeys, oldKeys);
            if (insertResult != MovementResult.SUCCESS) {
                return insertResult;
            }

            // Phase 3: UPDATE - Atomically update entity state
            var movingState = currentState.withMovementState(MovementState.MOVING);
            if (!entityState.compareAndSwap(currentState.getVersion(), movingState)) {
                // Cleanup partial insertions on conflict
                cleanupPartialInsertions(currentState.getEntityId(), newKeys, oldKeys);
                return waitAndRetry(attempt++);
            }

            // Phase 4: REMOVE - Clean up old locations and finalize
            var finalState = movingState.withPosition(newPosition)
                                       .withSpatialLocations(newKeys)
                                       .withMovementState(MovementState.STABLE);

            if (entityState.compareAndSwap(movingState.getVersion(), finalState)) {
                // Success! Clean up old locations
                removeFromOldLocations(currentState.getEntityId(), oldKeys, newKeys);
                
                if (config.enableStatistics) {
                    stats.recordSuccess(attempt);
                }
                return MovementResult.SUCCESS;
            } else {
                // Final update failed - revert everything
                cleanupPartialInsertions(currentState.getEntityId(), newKeys, oldKeys);
                return waitAndRetry(attempt++);
            }
        }

        return MovementResult.MAX_RETRIES_EXCEEDED;
    }

    /**
     * Optimistically updates entity content without changing position.
     * 
     * @param entityState The atomic entity state container
     * @param newContent The new content
     * @return MovementResult indicating success or failure
     */
    public MovementResult updateContent(AtomicVersionedState<Key, ID, Content> entityState, Content newContent) {
        return entityState.optimisticUpdate(
            state -> state.withContent(newContent),
            config.maxRetries
        ) ? MovementResult.SUCCESS : MovementResult.MAX_RETRIES_EXCEEDED;
    }

    /**
     * Atomically removes an entity from the spatial index.
     * 
     * @param entityState The atomic entity state container
     * @return MovementResult indicating success or failure
     */
    public MovementResult removeEntity(AtomicVersionedState<Key, ID, Content> entityState) {
        int attempt = 0;
        
        while (attempt < config.maxRetries) {
            var currentState = entityState.get();
            
            if (!currentState.isStable()) {
                if (config.enableStatistics) {
                    stats.recordConflict();
                }
                return waitAndRetry(attempt++);
            }

            // Mark as removing
            var removingState = currentState.withMovementState(MovementState.REMOVING);
            if (!entityState.compareAndSwap(currentState.getVersion(), removingState)) {
                return waitAndRetry(attempt++);
            }

            // Remove from all spatial locations
            removeFromOldLocations(currentState.getEntityId(), currentState.getSpatialLocations(), Set.of());
            
            if (config.enableStatistics) {
                stats.recordSuccess(attempt);
            }
            return MovementResult.SUCCESS;
        }

        return MovementResult.MAX_RETRIES_EXCEEDED;
    }

    /**
     * Inserts entity into new spatial locations, avoiding duplicates with old locations.
     */
    private MovementResult insertToNewLocations(ID entityId, Set<Key> newKeys, Set<Key> oldKeys) {
        var keysToAdd = newKeys.stream()
                              .filter(key -> !oldKeys.contains(key))
                              .toList();

        for (var key : keysToAdd) {
            var node = spatialIndex.computeIfAbsent(key, k -> 
                new AtomicSpatialNode<>(16, (byte) 0)); // TODO: Get actual config values
            
            if (!node.addEntity(entityId)) {
                // Node rejected the entity (probably subdividing)
                // Clean up partial insertions and retry
                cleanupPartialInsertions(entityId, Set.copyOf(keysToAdd.subList(0, keysToAdd.indexOf(key))), Set.of());
                return MovementResult.SPATIAL_CONFLICT;
            }
        }

        return MovementResult.SUCCESS;
    }

    /**
     * Removes entity from old spatial locations, except those still needed.
     */
    private void removeFromOldLocations(ID entityId, Set<Key> oldKeys, Set<Key> keysToKeep) {
        oldKeys.stream()
               .filter(key -> !keysToKeep.contains(key))
               .forEach(key -> {
                   var node = spatialIndex.get(key);
                   if (node != null) {
                       node.removeEntity(entityId);
                       
                       // Clean up empty nodes
                       if (node.isEmpty() && node.tryMarkForRemoval()) {
                           spatialIndex.remove(key, node);
                       }
                   }
               });
    }

    /**
     * Cleans up partial insertions when a movement operation fails.
     */
    private void cleanupPartialInsertions(ID entityId, Set<Key> insertedKeys, Set<Key> originalKeys) {
        removeFromOldLocations(entityId, insertedKeys, originalKeys);
    }

    /**
     * Implements exponential backoff with jitter for retry attempts.
     */
    private MovementResult waitAndRetry(int attempt) {
        if (attempt >= config.maxRetries) {
            return MovementResult.MAX_RETRIES_EXCEEDED;
        }

        try {
            long backoff = Math.min(config.baseBackoffNanos * (1L << attempt), config.maxBackoffNanos);
            // Add jitter (±25%)
            long jitter = ThreadLocalRandom.current().nextLong(-backoff / 4, backoff / 4);
            long delay = Math.max(0, backoff + jitter);
            
            if (delay > 0) {
                Thread.sleep(delay / 1_000_000, (int) (delay % 1_000_000));
            }
            
            return MovementResult.VERSION_CONFLICT; // Signal to retry
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MovementResult.INTERRUPTED;
        }
    }

    /**
     * Gets movement statistics.
     */
    public MovementStats getStats() {
        return stats;
    }

    /**
     * Resets movement statistics.
     */
    public void resetStats() {
        stats.successCount = 0;
        stats.conflictCount = 0;
        stats.retryCount = 0;
        stats.averageRetries = 0;
        stats.maxRetries = 0;
    }
}