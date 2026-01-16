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
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Represents a reversible grid operation for rollback.
 * <p>
 * Grid operations track structural changes (bubble additions/removals) during
 * topology operations. Each operation can be undone to restore grid consistency.
 * <p>
 * <b>Rollback Strategy</b>:
 * <ul>
 *   <li>BubbleAdded: Remove the bubble from grid</li>
 *   <li>BubbleRemoved: Re-add the bubble with saved state</li>
 * </ul>
 * <p>
 * <b>Limitation</b>: This only reverses grid structure changes. Entity movements
 * within bubbles are NOT reversed (requires EntityAccountant API extension).
 * EntityAccountant guarantees entity tracking consistency, so rollback primarily
 * addresses grid structure cleanup.
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 *
 * @author hal.hildebrand
 */
sealed interface GridOperation permits BubbleAdded, BubbleRemoved {

    /**
     * Undoes this operation on the grid.
     *
     * @param grid the bubble grid to modify
     */
    void undo(TetreeBubbleGrid grid);

    /**
     * Gets a description of this operation for logging.
     *
     * @return operation description
     */
    String description();
}

/**
 * Records a bubble addition that can be reversed.
 *
 * @param bubbleId the ID of the added bubble
 */
record BubbleAdded(UUID bubbleId) implements GridOperation {

    private static final Logger log = LoggerFactory.getLogger(BubbleAdded.class);

    @Override
    public void undo(TetreeBubbleGrid grid) {
        log.debug("Rolling back: Removing bubble {}", bubbleId);
        boolean removed = grid.removeBubble(bubbleId);
        if (!removed) {
            log.warn("Failed to remove bubble {} during rollback (already removed?)", bubbleId);
        }
    }

    @Override
    public String description() {
        return "Added bubble " + bubbleId;
    }
}

/**
 * Records a bubble removal that can be reversed.
 * <p>
 * <b>Important</b>: This stores a snapshot of the removed bubble so it can be
 * restored during rollback. The bubble must be captured BEFORE removal.
 *
 * @param bubbleId     the ID of the removed bubble
 * @param bubbleSnapshot the snapshot of the bubble state before removal
 * @param key          the TetreeKey for spatial indexing
 */
record BubbleRemoved(UUID bubbleId, EnhancedBubble bubbleSnapshot, TetreeKey<?> key) implements GridOperation {

    private static final Logger log = LoggerFactory.getLogger(BubbleRemoved.class);

    @Override
    public void undo(TetreeBubbleGrid grid) {
        log.debug("Rolling back: Restoring bubble {} at key {}", bubbleId, key);
        try {
            grid.addBubble(bubbleSnapshot, key);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to restore bubble {} during rollback: {}", bubbleId, e.getMessage());
        }
    }

    @Override
    public String description() {
        return "Removed bubble " + bubbleId + " at key " + key;
    }
}
