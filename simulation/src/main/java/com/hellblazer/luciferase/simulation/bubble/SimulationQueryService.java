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

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides read-only query operations for the simulation.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Query all entities (real and ghosts)</li>
 *   <li>Query real entities only</li>
 *   <li>Query ghost count</li>
 *   <li>Access bubbles by key or all bubbles</li>
 *   <li>Access simulation metrics</li>
 * </ul>
 * <p>
 * This component is extracted from MultiBubbleSimulation as part of the
 * orchestrator pattern refactoring (Sprint B B1). It provides a pure
 * read-only facade over the bubble grid and ghost sync adapter.
 *
 * @author hal.hildebrand
 */
public class SimulationQueryService {

    /**
     * Entity snapshot for visualization and queries.
     *
     * @param id        Entity identifier
     * @param position  Current position
     * @param bubbleKey Key of containing bubble
     * @param isGhost   True if this is a ghost entity (not authoritative)
     */
    public record EntitySnapshot(String id, Point3f position, TetreeKey<?> bubbleKey, boolean isGhost) {}

    private final TetreeBubbleGrid bubbleGrid;
    private final TetreeGhostSyncAdapter ghostSyncAdapter;
    private final EntityPopulationManager populationManager;
    private final SimulationMetrics metrics;

    /**
     * Create simulation query service.
     *
     * @param bubbleGrid         Bubble grid
     * @param ghostSyncAdapter   Ghost synchronization adapter
     * @param populationManager  Entity population manager
     * @param metrics            Simulation metrics
     */
    public SimulationQueryService(
        TetreeBubbleGrid bubbleGrid,
        TetreeGhostSyncAdapter ghostSyncAdapter,
        EntityPopulationManager populationManager,
        SimulationMetrics metrics
    ) {
        this.bubbleGrid = bubbleGrid;
        this.ghostSyncAdapter = ghostSyncAdapter;
        this.populationManager = populationManager;
        this.metrics = metrics;
    }

    /**
     * Get simulation metrics.
     *
     * @return SimulationMetrics instance
     */
    public SimulationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Get all entities in the simulation (both real and ghosts).
     *
     * @return List of entity snapshots
     */
    public List<EntitySnapshot> getAllEntities() {
        var snapshots = new ArrayList<EntitySnapshot>();

        // Add real entities from bubbles
        for (var bubble : bubbleGrid.getAllBubbles()) {
            var records = bubble.getAllEntityRecords();
            TetreeKey<?> fallbackKey = null;

            for (var record : records) {
                var key = populationManager.getDistribution().getEntityToBubbleMapping().get(record.id());
                if (key == null) {
                    // Entity not in mapping - use fallback key if available
                    if (fallbackKey != null) {
                        key = fallbackKey;
                    } else {
                        // Skip only if no fallback key exists yet
                        continue;
                    }
                }
                if (fallbackKey == null) {
                    fallbackKey = key;
                }
                snapshots.add(new EntitySnapshot(record.id(), record.position(), key, false));
            }

            // Add ghost entities for this bubble
            var ghosts = ghostSyncAdapter.getGhostsForBubble(bubble.id());
            for (var ghost : ghosts) {
                // Determine key for ghost (use fallback or root key)
                var ghostKey = fallbackKey != null ? fallbackKey :
                              TetreeKey.create((byte) 0, 0L, 0L);
                snapshots.add(new EntitySnapshot(
                    ghost.entityId().toString(),
                    ghost.position(),
                    ghostKey,
                    true  // isGhost = true
                ));
            }
        }

        return snapshots;
    }

    /**
     * Get real entities (exclude ghosts).
     * <p>
     * Until Phase 5C, all entities are real (no ghost distinction).
     *
     * @return List of real entity snapshots
     */
    public List<EntitySnapshot> getRealEntities() {
        return getAllEntities().stream()
                               .filter(e -> !e.isGhost())
                               .collect(Collectors.toList());
    }

    /**
     * Get count of ghost entities.
     * <p>
     * Phase 5C: Returns actual ghost count from ghost sync adapter.
     *
     * @return Number of ghost entities
     */
    public int getGhostCount() {
        return ghostSyncAdapter.getTotalGhostCount();
    }

    /**
     * Get a specific bubble by its TetreeKey.
     *
     * @param key TetreeKey of bubble
     * @return EnhancedBubble instance
     * @throws java.util.NoSuchElementException if no bubble exists at key
     */
    public EnhancedBubble getBubble(TetreeKey<?> key) {
        return bubbleGrid.getBubble(key);
    }

    /**
     * Get all bubbles in the grid.
     *
     * @return Collection of all EnhancedBubbles
     */
    public Collection<EnhancedBubble> getAllBubbles() {
        return bubbleGrid.getAllBubbles();
    }
}
