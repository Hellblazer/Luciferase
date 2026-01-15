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

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;

/**
 * Orchestrates bubble grid creation and spatial index management.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Manage TetreeBubbleGrid instance</li>
 *   <li>Coordinate bubble creation across tree levels</li>
 *   <li>Maintain spatial index for entity tracking</li>
 *   <li>Provide access to ghost sync adapter</li>
 * </ul>
 * <p>
 * This component is extracted from MultiBubbleSimulation as part of the
 * orchestrator pattern refactoring (Sprint B B1).
 *
 * @author hal.hildebrand
 */
public class BubbleGridOrchestrator {

    private final TetreeBubbleGrid bubbleGrid;
    private final Tetree<StringEntityID, EntityDistribution.EntitySpec> spatialIndex;
    private final TetreeGhostSyncAdapter ghostSyncAdapter;
    private final byte maxLevel;

    /**
     * Create bubble grid orchestrator.
     *
     * @param bubbleGrid        Tetree bubble grid
     * @param spatialIndex      Spatial index for entity tracking
     * @param ghostSyncAdapter  Ghost synchronization adapter
     * @param maxLevel          Maximum tree level (0-21)
     */
    public BubbleGridOrchestrator(
        TetreeBubbleGrid bubbleGrid,
        Tetree<StringEntityID, EntityDistribution.EntitySpec> spatialIndex,
        TetreeGhostSyncAdapter ghostSyncAdapter,
        byte maxLevel
    ) {
        this.bubbleGrid = bubbleGrid;
        this.spatialIndex = spatialIndex;
        this.ghostSyncAdapter = ghostSyncAdapter;
        this.maxLevel = maxLevel;
    }

    /**
     * Get bubble grid.
     *
     * @return TetreeBubbleGrid instance
     */
    public TetreeBubbleGrid getBubbleGrid() {
        return bubbleGrid;
    }

    /**
     * Get spatial index.
     *
     * @return Tetree spatial index
     */
    public Tetree<StringEntityID, EntityDistribution.EntitySpec> getSpatialIndex() {
        return spatialIndex;
    }

    /**
     * Get ghost sync adapter.
     *
     * @return TetreeGhostSyncAdapter instance
     */
    public TetreeGhostSyncAdapter getGhostSyncAdapter() {
        return ghostSyncAdapter;
    }

    /**
     * Get maximum tree level.
     *
     * @return Max level (0-21)
     */
    public byte getMaxLevel() {
        return maxLevel;
    }

    /**
     * Create and distribute bubbles across tree levels.
     *
     * @param bubbleCount          Number of bubbles to create
     * @param entityCount          Total entity count (for sizing)
     * @param maxEntitiesPerBubble Maximum entities per bubble
     */
    public void createBubbles(int bubbleCount, int entityCount, int maxEntitiesPerBubble) {
        TetreeBubbleFactory.createBubbles(bubbleGrid, bubbleCount, maxLevel, maxEntitiesPerBubble);
    }
}
