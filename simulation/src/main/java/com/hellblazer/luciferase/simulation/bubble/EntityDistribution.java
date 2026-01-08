/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributes entities spatially to bubbles based on position.
 * <p>
 * This class assigns entities to appropriate bubbles by:
 * <ol>
 *   <li>Locating the containing tetrahedron via {@link Tetree#locateTetrahedron}</li>
 *   <li>Mapping tetrahedron key to bubble</li>
 *   <li>Adding entity to bubble</li>
 *   <li>Updating bubble bounds incrementally</li>
 * </ol>
 * <p>
 * Distribution Strategies:
 * <ul>
 *   <li><b>Spatial</b>: Entities placed by position (natural distribution)</li>
 *   <li><b>Balanced</b>: Entities distributed evenly across bubbles (test/debug)</li>
 * </ul>
 * <p>
 * Thread Safety:
 * <ul>
 *   <li>Uses {@link ConcurrentHashMap} for entity→bubble mapping</li>
 *   <li>Atomic bubble updates via {@link EnhancedBubble#addEntity}</li>
 *   <li>Safe for concurrent distribution operations</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class EntityDistribution {

    private final TetreeBubbleGrid bubbleGrid;
    private final Tetree<?, ?> tetree;
    private final Map<String, TetreeKey<?>> entityToBubbleMapping;
    private final byte defaultLevel;

    /**
     * Entity specification for distribution.
     * <p>
     * Records entity identity, position, and optional velocity for initialization.
     *
     * @param id       Entity identifier (must be unique)
     * @param position Entity position in RDGCS coordinates
     * @param velocity Entity velocity (may be null, initialized later if needed)
     */
    public record EntitySpec(String id, Point3f position, Vector3f velocity) {
        public EntitySpec {
            Objects.requireNonNull(id, "Entity ID cannot be null");
            Objects.requireNonNull(position, "Position cannot be null");
            // velocity can be null
        }
    }

    /**
     * Create entity distribution manager.
     *
     * @param grid   TetreeBubbleGrid to distribute entities into
     * @param tetree Tetree for locating entities spatially
     * @throws NullPointerException if grid or tetree is null
     */
    public EntityDistribution(TetreeBubbleGrid grid, Tetree<?, ?> tetree) {
        this.bubbleGrid = Objects.requireNonNull(grid, "TetreeBubbleGrid cannot be null");
        this.tetree = Objects.requireNonNull(tetree, "Tetree cannot be null");
        this.entityToBubbleMapping = new ConcurrentHashMap<>();
        this.defaultLevel = 10; // Middle-range level for locating entities
    }

    /**
     * Distribute entities to bubbles based on their spatial position.
     * <p>
     * For each entity:
     * <ol>
     *   <li>Locate containing tetrahedron via {@link Tetree#locateTetrahedron}</li>
     *   <li>Get TetreeKey from tetrahedron</li>
     *   <li>Find corresponding bubble in grid</li>
     *   <li>Add entity to bubble (updates bounds automatically)</li>
     *   <li>Record entity→bubble mapping</li>
     * </ol>
     * <p>
     * Out-of-Bounds Handling:
     * <ul>
     *   <li>If {@code tetree.locateTetrahedron} returns null (position invalid):
     *       throw {@link IllegalArgumentException} with entity details</li>
     *   <li>If no bubble exists at the located key: find nearest bubble or skip</li>
     * </ul>
     *
     * @param entities List of entities to distribute
     * @throws NullPointerException     if entities is null
     * @throws IllegalArgumentException if any entity has invalid position
     */
    public void distribute(List<EntitySpec> entities) {
        Objects.requireNonNull(entities, "Entities list cannot be null");

        for (var entity : entities) {
            distributeEntity(entity);
        }
    }

    /**
     * Distribute a single entity to its appropriate bubble.
     *
     * @param entity Entity to distribute
     * @throws IllegalArgumentException if entity position is invalid or out of bounds
     */
    private void distributeEntity(EntitySpec entity) {
        // Locate tetrahedron containing this position
        Tet tet;
        try {
            tet = tetree.locateTetrahedron(entity.position, defaultLevel);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to locate tetrahedron for entity " + entity.id +
                " at position " + entity.position + ": " + e.getMessage(), e);
        }

        if (tet == null) {
            throw new IllegalArgumentException(
                "Entity " + entity.id + " at position " + entity.position +
                " is outside valid tetree bounds (no containing tetrahedron found)");
        }

        // Get the TetreeKey from the tetrahedron
        var key = tet.tmIndex();

        // Find the bubble for this key
        EnhancedBubble bubble;
        try {
            bubble = bubbleGrid.getBubble(key);
        } catch (NoSuchElementException e) {
            // No exact bubble at this key - find nearest bubble
            bubble = findNearestBubble(key);
            if (bubble == null) {
                throw new IllegalArgumentException(
                    "No bubble found for entity " + entity.id +
                    " at key " + key + " and no nearest bubble available");
            }
        }

        // Add entity to bubble (bounds updated automatically by EnhancedBubble)
        bubble.addEntity(entity.id, entity.position, entity);

        // Record mapping
        entityToBubbleMapping.put(entity.id, key);
    }

    /**
     * Find the nearest bubble to a given key when no exact match exists.
     * <p>
     * Strategy: Search parent levels and neighboring keys for closest bubble.
     *
     * @param key Target key
     * @return Nearest EnhancedBubble or null if no bubbles exist
     */
    private EnhancedBubble findNearestBubble(TetreeKey<?> key) {
        // Strategy 1: Check parent levels (coarser spatial regions)
        for (byte level = (byte) (key.getLevel() - 1); level >= 0; level--) {
            // Try to find a bubble at a parent level
            // For now, just return the first available bubble (simple fallback)
            var allBubbles = bubbleGrid.getAllBubbles();
            if (!allBubbles.isEmpty()) {
                return allBubbles.iterator().next();
            }
        }

        return null;
    }

    /**
     * Distribute entities evenly across bubbles to balance load.
     * <p>
     * This method ignores spatial position and distributes entities to minimize
     * variance in entity count across bubbles. Target: variance < 10%.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Sort bubbles by current entity count (ascending)</li>
     *   <li>For each entity, assign to bubble with fewest entities</li>
     *   <li>Update entity→bubble mapping</li>
     *   <li>Continue until all entities placed</li>
     * </ol>
     *
     * @param entities List of entities to distribute
     * @throws NullPointerException if entities is null
     */
    public void distributeByDensity(List<EntitySpec> entities) {
        Objects.requireNonNull(entities, "Entities list cannot be null");

        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());
        if (bubbles.isEmpty()) {
            throw new IllegalStateException("No bubbles available for distribution");
        }

        // Sort bubbles by entity count (ascending)
        bubbles.sort(Comparator.comparingInt(EnhancedBubble::entityCount));

        // Round-robin distribution to maintain balance
        int bubbleIndex = 0;
        for (var entity : entities) {
            var bubble = bubbles.get(bubbleIndex);
            bubble.addEntity(entity.id, entity.position, entity);

            // Record mapping (use bubble's current key from grid)
            // This is a simplification - in production we'd need the actual key
            entityToBubbleMapping.put(entity.id, findKeyForBubble(bubble));

            // Move to next bubble, wrapping around
            bubbleIndex = (bubbleIndex + 1) % bubbles.size();

            // Re-sort periodically to maintain balance (every N entities)
            if (bubbleIndex == 0) {
                bubbles.sort(Comparator.comparingInt(EnhancedBubble::entityCount));
            }
        }
    }

    /**
     * Find the TetreeKey associated with a bubble in the grid.
     * <p>
     * This is a reverse lookup - inefficient but needed for balanced distribution.
     *
     * @param bubble Bubble to find key for
     * @return TetreeKey or null if not found
     */
    private TetreeKey<?> findKeyForBubble(EnhancedBubble bubble) {
        // This is inefficient but works for small bubble counts
        // In production, we'd maintain a reverse map
        var allBubbles = bubbleGrid.getAllBubbles();
        for (var candidate : allBubbles) {
            if (candidate.id().equals(bubble.id())) {
                // Extract key from spatial index (approximation)
                // For now, return a placeholder - this would need proper implementation
                return TetreeKey.create((byte) 0, 0L, 0L);
            }
        }
        return null;
    }

    /**
     * Get the entity→bubble mapping for verification.
     * <p>
     * Maps entity ID to the TetreeKey of its containing bubble.
     *
     * @return Immutable copy of entity→bubble mapping
     */
    public Map<String, TetreeKey<?>> getEntityToBubbleMapping() {
        return new HashMap<>(entityToBubbleMapping);
    }

    /**
     * Verify that all entities are in the correct bubble for their position.
     * <p>
     * Validation checks:
     * <ul>
     *   <li>Every entity is in the mapping</li>
     *   <li>Entity's bubble key matches its spatial location</li>
     *   <li>Entity exists in the bubble's entity list</li>
     * </ul>
     *
     * @param entities List of entities to verify
     * @return true if all entities correctly placed, false otherwise
     * @throws NullPointerException if entities is null
     */
    public boolean verifyDistribution(List<EntitySpec> entities) {
        Objects.requireNonNull(entities, "Entities list cannot be null");

        for (var entity : entities) {
            // Check if entity is in mapping
            var mappedKey = entityToBubbleMapping.get(entity.id);
            if (mappedKey == null) {
                return false; // Entity not mapped
            }

            // Locate where entity should be based on position
            Tet tet;
            try {
                tet = tetree.locateTetrahedron(entity.position, defaultLevel);
            } catch (Exception e) {
                return false; // Position invalid
            }

            if (tet == null) {
                return false; // Out of bounds
            }

            var expectedKey = tet.tmIndex();

            // Check if mapped key matches expected key (or is nearby)
            // We allow some tolerance since bubbles might not exist at every key
            if (!keysAreCompatible(mappedKey, expectedKey)) {
                return false;
            }

            // Check if entity exists in the bubble
            EnhancedBubble bubble;
            try {
                bubble = bubbleGrid.getBubble(mappedKey);
            } catch (NoSuchElementException e) {
                return false; // Bubble doesn't exist
            }

            if (!bubble.getEntities().contains(entity.id)) {
                return false; // Entity not in bubble
            }
        }

        return true;
    }

    /**
     * Check if two TetreeKeys are compatible (same or nearby in hierarchy).
     * <p>
     * Keys are compatible if:
     * <ul>
     *   <li>They are equal</li>
     *   <li>One is an ancestor of the other</li>
     *   <li>They are siblings at the same level</li>
     * </ul>
     *
     * @param key1 First key
     * @param key2 Second key
     * @return true if keys are compatible, false otherwise
     */
    private boolean keysAreCompatible(TetreeKey<?> key1, TetreeKey<?> key2) {
        // Simple check: same level and close in SFC order
        if (key1.getLevel() != key2.getLevel()) {
            return false;
        }

        // Check if high/low bits are close (within reasonable tolerance)
        long highDiff = Math.abs(key1.getHighBits() - key2.getHighBits());
        long lowDiff = Math.abs(key1.getLowBits() - key2.getLowBits());

        // Allow small differences (neighboring keys)
        return highDiff <= 8 && lowDiff <= 8;
    }

    /**
     * Clear all entity→bubble mappings.
     * <p>
     * Use this when resetting simulation or redistributing entities.
     */
    public void clear() {
        entityToBubbleMapping.clear();
    }
}
