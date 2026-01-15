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

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns and tracks entities across 4-bubble grid.
 * <p>
 * Responsibilities:
 * - Create 100 entities (25 per bubble initially)
 * - Assign starting positions within bubble tetrahedra
 * - Ensure even distribution across all bubbles
 * - Track entity IDs and bubble ownership
 * - Support scaling to 500 entities
 * <p>
 * DISTRIBUTION STRATEGY:
 * Entities are distributed evenly using round-robin assignment to bubbles.
 * For N entities and 4 bubbles: each bubble gets N/4 entities (remainder distributed to first bubbles).
 * <p>
 * STARTING POSITIONS:
 * Entities are placed randomly within their assigned bubble's tetrahedra.
 * Each bubble owns 2 tetrahedra at Tetree L1.
 * <p>
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for thread-safe entity tracking.
 * <p>
 * Phase 8C Day 1: Behavioral Entity Integration
 *
 * @author hal.hildebrand
 */
public class EntitySpawner {

    private static final Logger log = LoggerFactory.getLogger(EntitySpawner.class);

    private final ConsensusBubbleGrid grid;

    /**
     * Entity to bubble mapping: entityId -> bubbleIndex
     */
    private final ConcurrentHashMap<UUID, Integer> entityToBubble = new ConcurrentHashMap<>();

    /**
     * Bubble to entities mapping: bubbleIndex -> Set of entity IDs
     */
    private final Map<Integer, Set<UUID>> bubbleToEntities = new ConcurrentHashMap<>();

    /**
     * Create EntitySpawner.
     *
     * @param grid ConsensusBubbleGrid for bubble topology
     */
    public EntitySpawner(ConsensusBubbleGrid grid) {
        this.grid = Objects.requireNonNull(grid, "grid must not be null");

        // Initialize bubble entity sets
        for (int i = 0; i < 4; i++) {
            bubbleToEntities.put(i, ConcurrentHashMap.newKeySet());
        }
    }

    /**
     * Spawn entities evenly distributed across all 4 bubbles.
     * <p>
     * Distribution: For N entities, each bubble gets N/4 entities.
     * Remainder entities distributed to first bubbles (round-robin).
     *
     * @param count Number of entities to spawn
     * @return List of spawned entity IDs
     * @throws IllegalArgumentException if count <= 0
     */
    public List<UUID> spawnEntities(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive, got " + count);
        }

        var spawnedIds = new ArrayList<UUID>(count);

        // Calculate entities per bubble
        var perBubble = count / 4;
        var remainder = count % 4;

        log.debug("Spawning {} entities: {} per bubble + {} remainder", count, perBubble, remainder);

        // Spawn entities in each bubble
        for (int bubbleIndex = 0; bubbleIndex < 4; bubbleIndex++) {
            // First 'remainder' bubbles get one extra entity
            var countForBubble = perBubble + (bubbleIndex < remainder ? 1 : 0);

            for (int i = 0; i < countForBubble; i++) {
                var entityId = UUID.randomUUID();
                var location = getRandomLocationInBubble(bubbleIndex);

                // Add entity to bubble
                addEntityToBubble(entityId, bubbleIndex, location);
                spawnedIds.add(entityId);
            }
        }

        log.info("Spawned {} entities across 4 bubbles", count);
        return spawnedIds;
    }

    /**
     * Spawn entities in specific bubble.
     *
     * @param bubbleIndex Bubble index (0-3)
     * @param count       Number of entities to spawn in this bubble
     * @throws IllegalArgumentException if bubbleIndex out of range or count <= 0
     */
    public void spawnInBubble(int bubbleIndex, int count) {
        if (bubbleIndex < 0 || bubbleIndex >= 4) {
            throw new IllegalArgumentException("Bubble index must be 0-3, got " + bubbleIndex);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive, got " + count);
        }

        log.debug("Spawning {} entities in bubble {}", count, bubbleIndex);

        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            var location = getRandomLocationInBubble(bubbleIndex);

            addEntityToBubble(entityId, bubbleIndex, location);
        }

        log.info("Spawned {} entities in bubble {}", count, bubbleIndex);
    }

    /**
     * Get all spawned entity IDs.
     *
     * @return List of all entity IDs (unmodifiable)
     */
    public List<UUID> getAllEntities() {
        return List.copyOf(entityToBubble.keySet());
    }

    /**
     * Get total entity count.
     *
     * @return Number of entities
     */
    public int getEntityCount() {
        return entityToBubble.size();
    }

    /**
     * Get entity count in specific bubble.
     *
     * @param bubbleIndex Bubble index (0-3)
     * @return Entity count in bubble
     * @throws IllegalArgumentException if bubbleIndex out of range
     */
    public int getBubbleEntityCount(int bubbleIndex) {
        if (bubbleIndex < 0 || bubbleIndex >= 4) {
            throw new IllegalArgumentException("Bubble index must be 0-3, got " + bubbleIndex);
        }
        return bubbleToEntities.get(bubbleIndex).size();
    }

    /**
     * Get bubble index for entity.
     *
     * @param entityId Entity ID to lookup
     * @return Optional containing bubble index, or empty if entity not found
     */
    public Optional<Integer> getEntityBubble(UUID entityId) {
        return Optional.ofNullable(entityToBubble.get(entityId));
    }

    /**
     * Add entity to bubble with starting location.
     *
     * @param entityId    Entity ID
     * @param bubbleIndex Bubble index (0-3)
     * @param location    Starting TetreeKey location
     */
    private void addEntityToBubble(UUID entityId, int bubbleIndex, TetreeKey<?> location) {
        // Track entity ownership
        entityToBubble.put(entityId, bubbleIndex);
        bubbleToEntities.get(bubbleIndex).add(entityId);

        // Add entity to bubble node
        var bubble = grid.getBubble(bubbleIndex);
        var sourceNode = grid.getCommitteeMembers().stream().findFirst().orElseThrow();
        bubble.addEntity(entityId, sourceNode);

        log.trace("Added entity {} to bubble {} at location {}", entityId, bubbleIndex, location);
    }

    /**
     * Get random starting location within bubble's tetrahedra.
     * <p>
     * Each bubble owns 2 tetrahedra at L1. Randomly select one of them.
     *
     * @param bubbleIndex Bubble index (0-3)
     * @return Random TetreeKey within bubble's tetrahedra
     */
    private TetreeKey<?> getRandomLocationInBubble(int bubbleIndex) {
        var bubble = grid.getBubble(bubbleIndex);
        var tetrahedra = bubble.getTetrahedra();

        // Randomly select one of the 2 tetrahedra
        var rng = ThreadLocalRandom.current();
        var selectedTet = tetrahedra[rng.nextInt(tetrahedra.length)];

        return selectedTet;
    }
}
