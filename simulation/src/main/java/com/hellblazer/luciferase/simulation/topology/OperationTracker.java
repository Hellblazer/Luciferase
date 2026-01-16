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
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.UUID;

/**
 * Tracks grid operations for rollback capability.
 * <p>
 * Executors (BubbleSplitter, BubbleMerger, BubbleMover) call these methods
 * to record structural changes to the grid. The operations can be reversed
 * during rollback to restore grid consistency.
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 *
 * @author hal.hildebrand
 */
public interface OperationTracker {

    /**
     * Records that a bubble was added to the grid.
     *
     * @param bubbleId the ID of the added bubble
     */
    void recordBubbleAdded(UUID bubbleId);

    /**
     * Records that a bubble was removed from the grid.
     * <p>
     * <b>Important</b>: This must be called BEFORE the bubble is actually removed,
     * so the bubble snapshot can be captured for rollback.
     *
     * @param bubbleId       the ID of the removed bubble
     * @param bubbleSnapshot the current bubble state (before removal)
     * @param key            the TetreeKey for spatial indexing
     */
    void recordBubbleRemoved(UUID bubbleId, EnhancedBubble bubbleSnapshot, TetreeKey<?> key);

    /**
     * No-op implementation for cases where operation tracking is not needed.
     */
    OperationTracker NOOP = new OperationTracker() {
        @Override
        public void recordBubbleAdded(UUID bubbleId) {
            // No-op
        }

        @Override
        public void recordBubbleRemoved(UUID bubbleId, EnhancedBubble bubbleSnapshot, TetreeKey<?> key) {
            // No-op
        }
    };
}
