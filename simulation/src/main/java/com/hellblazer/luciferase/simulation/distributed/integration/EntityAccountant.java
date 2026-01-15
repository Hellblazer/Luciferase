/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks entity locations across bubbles and validates the invariant that each
 * entity exists in exactly one bubble.
 * <p>
 * Thread-safe: All operations are atomic and use concurrent data structures.
 *
 * @author hal.hildebrand
 */
public class EntityAccountant {

    private final ConcurrentHashMap<UUID, UUID>                       entityToBubble;
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<UUID>> bubbleToEntities;
    private final AtomicLong                                          totalOperations;
    private final Lock                                                lock;

    /**
     * Creates a new EntityAccountant with empty state.
     */
    public EntityAccountant() {
        this.entityToBubble = new ConcurrentHashMap<>();
        this.bubbleToEntities = new ConcurrentHashMap<>();
        this.totalOperations = new AtomicLong(0);
        this.lock = new ReentrantLock();
    }

    /**
     * Registers an entity with a bubble.
     *
     * @param bubbleId the bubble identifier
     * @param entityId the entity identifier
     */
    public void register(UUID bubbleId, UUID entityId) {
        entityToBubble.put(entityId, bubbleId);
        bubbleToEntities.computeIfAbsent(bubbleId, k -> new CopyOnWriteArraySet<>()).add(entityId);
        totalOperations.incrementAndGet();
    }

    /**
     * Unregisters an entity from a bubble.
     *
     * @param bubbleId the bubble identifier
     * @param entityId the entity identifier
     */
    public void unregister(UUID bubbleId, UUID entityId) {
        entityToBubble.remove(entityId);
        var entities = bubbleToEntities.get(bubbleId);
        if (entities != null) {
            entities.remove(entityId);
        }
        totalOperations.incrementAndGet();
    }

    /**
     * Moves an entity from one bubble to another atomically.
     * <p>
     * Validates that the entity is currently in the source bubble before proceeding.
     * This prevents race conditions where multiple concurrent migrations could duplicate
     * an entity across bubbles.
     *
     * @param entityId   the entity identifier
     * @param fromBubble the source bubble identifier
     * @param toBubble   the target bubble identifier
     * @return true if migration succeeded, false if entity was not in source bubble
     */
    public boolean moveBetweenBubbles(UUID entityId, UUID fromBubble, UUID toBubble) {
        lock.lock();
        try {
            // Validate entity is actually in source bubble (prevents TOCTOU race condition)
            var currentBubble = entityToBubble.get(entityId);
            if (!fromBubble.equals(currentBubble)) {
                // Entity not in expected source - migration is invalid
                return false;
            }

            // Remove from source bubble
            var sourceEntities = bubbleToEntities.get(fromBubble);
            if (sourceEntities != null) {
                sourceEntities.remove(entityId);
            }

            // Add to target bubble
            bubbleToEntities.computeIfAbsent(toBubble, k -> new CopyOnWriteArraySet<>()).add(entityId);

            // Update mapping
            entityToBubble.put(entityId, toBubble);

            totalOperations.incrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Validates the invariant that each entity exists in exactly one bubble.
     * <p>
     * Uses explicit locking to prevent reading intermediate state during concurrent migrations.
     * Without locking, CopyOnWriteArraySet snapshots could show an entity
     * in both source and destination bubbles during migration.
     *
     * @return validation result with success status and error details
     */
    public EntityValidationResult validate() {
        lock.lock();
        try {
            var errors = new ArrayList<String>();
            var seenEntities = new HashSet<UUID>();

            // Check each bubble's entities
            for (var entry : bubbleToEntities.entrySet()) {
                var bubbleId = entry.getKey();
                var entities = entry.getValue();

                for (var entityId : entities) {
                    if (seenEntities.contains(entityId)) {
                        errors.add("Entity " + entityId + " found in multiple bubbles (including " + bubbleId + ")");
                    }
                    seenEntities.add(entityId);

                    // Verify bidirectional consistency
                    var mappedBubble = entityToBubble.get(entityId);
                    if (mappedBubble == null) {
                        errors.add("Entity " + entityId + " in bubble " + bubbleId + " but not in entity-to-bubble map");
                    } else if (!mappedBubble.equals(bubbleId)) {
                        errors.add("Entity " + entityId + " in bubble " + bubbleId
                                   + " but entity-to-bubble map shows " + mappedBubble);
                    }
                }
            }

            // Check for entities in map but not in any bubble
            for (var entry : entityToBubble.entrySet()) {
                var entityId = entry.getKey();
                var bubbleId = entry.getValue();

                if (!seenEntities.contains(entityId)) {
                    var entities = bubbleToEntities.get(bubbleId);
                    if (entities == null || !entities.contains(entityId)) {
                        errors.add("Entity " + entityId + " in entity-to-bubble map with bubble " + bubbleId
                                   + " but not in bubble's entity set");
                    }
                }
            }

            return new EntityValidationResult(errors.isEmpty(), errors.size(), errors);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns all entities in a specific bubble.
     *
     * @param bubbleId the bubble identifier
     * @return set of entity identifiers (empty if bubble has no entities)
     */
    public Set<UUID> entitiesInBubble(UUID bubbleId) {
        var entities = bubbleToEntities.get(bubbleId);
        return entities != null ? new HashSet<>(entities) : Set.of();
    }

    /**
     * Returns the distribution of entities across bubbles.
     * <p>
     * Uses explicit locking for consistency with migration operations.
     *
     * @return map of bubble ID to entity count
     */
    public Map<UUID, Integer> getDistribution() {
        lock.lock();
        try {
            var distribution = new HashMap<UUID, Integer>();
            for (var entry : bubbleToEntities.entrySet()) {
                distribution.put(entry.getKey(), entry.getValue().size());
            }
            return distribution;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the current location (bubble) of an entity.
     *
     * @param entityId the entity identifier
     * @return the bubble ID containing the entity, or null if not found
     */
    public UUID getLocationOfEntity(UUID entityId) {
        return entityToBubble.get(entityId);
    }

    /**
     * Returns the total number of operations performed.
     *
     * @return total operation count
     */
    public long getTotalOperations() {
        return totalOperations.get();
    }

    /**
     * Resets all state to empty.
     */
    public void reset() {
        entityToBubble.clear();
        bubbleToEntities.clear();
        totalOperations.set(0);
    }
}

