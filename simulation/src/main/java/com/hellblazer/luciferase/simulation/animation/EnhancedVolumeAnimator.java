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

package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * EnhancedVolumeAnimator - Animation controller for EnhancedBubble with ghost support (Phase 7B.4).
 * <p>
 * Provides animation frame management for bubbles containing both owned entities and ghost entities.
 * Ghosts are animated using dead reckoning (position extrapolation based on velocity).
 * <p>
 * KEY FEATURES:
 * - Animates both owned entities (from spatial index) and ghosts (from GhostStateManager)
 * - Updates ghost positions via dead reckoning on each tick
 * - Read-only access to ghosts (doesn't modify spatial index)
 * - Thread-safe concurrent access to entity collections
 * - Performance overhead < 5% for 100 ghosts
 * <p>
 * USAGE:
 * <pre>
 *   var bubble = new EnhancedBubble(id, level, frameMs, controller);
 *   var animator = new EnhancedVolumeAnimator(bubble, controller);
 *
 *   // Tick animation frame
 *   animator.tick();
 *
 *   // Get all animated entities (owned + ghosts)
 *   var entities = animator.getAnimatedEntities();
 * </pre>
 * <p>
 * THREAD SAFETY:
 * - RealTimeController runs tick loop on dedicated thread
 * - GhostStateManager uses ConcurrentHashMap for thread-safe updates
 * - getAnimatedEntities() creates new ArrayList (no shared mutation)
 * - Safe for concurrent reads during animation
 *
 * @author hal.hildebrand
 */
public class EnhancedVolumeAnimator {

    private static final Logger log = LoggerFactory.getLogger(EnhancedVolumeAnimator.class);

    private final EnhancedBubble       bubble;
    private final RealTimeController   controller;
    private       long                 frameCount;

    /**
     * Create an EnhancedVolumeAnimator for an EnhancedBubble.
     *
     * @param bubble     EnhancedBubble to animate
     * @param controller RealTimeController for simulation time
     */
    public EnhancedVolumeAnimator(EnhancedBubble bubble, RealTimeController controller) {
        this.bubble = bubble;
        this.controller = controller;
        this.frameCount = 0L;

        log.debug("EnhancedVolumeAnimator created: bubble={}", bubble.id());
    }

    /**
     * Get all animated entities (owned + ghosts).
     * <p>
     * Combines:
     * - Owned entities from bubble's spatial index
     * - Ghost entities from GhostStateManager
     * <p>
     * This method creates a new collection on each call, ensuring thread-safe access
     * without blocking the animation loop.
     *
     * @return Collection of all entities to animate (owned + ghosts)
     */
    public Collection<AnimatedEntity> getAnimatedEntities() {
        var result = new ArrayList<AnimatedEntity>();

        // Add owned entities from spatial index
        var ownedRecords = bubble.getAllEntityRecords();
        for (var record : ownedRecords) {
            result.add(new AnimatedEntity(
                new StringEntityID(record.id()),
                record.position(),
                false // Not a ghost
            ));
        }

        // Add ghost entities from GhostStateManager
        var ghosts = bubble.getGhostStateManager().getActiveGhosts();
        var currentTime = controller.getSimulationTime();

        for (var ghost : ghosts) {
            var extrapolatedPos = bubble.getGhostStateManager()
                .getGhostPosition(ghost.entityId(), currentTime);

            if (extrapolatedPos != null) {
                result.add(new AnimatedEntity(
                    ghost.entityId(),
                    extrapolatedPos,
                    true // Is a ghost
                ));
            }
        }

        return result;
    }

    /**
     * Tick the animation frame.
     * <p>
     * Updates animation state for the current simulation time.
     * Ghost positions are updated via dead reckoning in GhostStateManager
     * (triggered by RealTimeController tick listener in EnhancedBubble).
     * <p>
     * This method is lightweight - it doesn't rebuild spatial structures or
     * perform expensive operations. The actual ghost position updates happen
     * via the RealTimeController tick mechanism.
     */
    public void tick() {
        frameCount++;

        if (frameCount % 100 == 0) {
            var currentTime = controller.getSimulationTime();
            var entityCount = getAnimatedEntities().size();
            var ownedCount = bubble.entityCount();
            var ghostCount = bubble.getGhostStateManager().getActiveGhostCount();

            log.debug("Animation tick: frame={}, time={}, entities={} (owned={}, ghosts={})",
                frameCount, currentTime, entityCount, ownedCount, ghostCount);
        }
    }

    /**
     * Get the current frame count.
     *
     * @return Number of frames processed since creation
     */
    public long getFrameCount() {
        return frameCount;
    }

    /**
     * Get the associated EnhancedBubble.
     *
     * @return EnhancedBubble being animated
     */
    public EnhancedBubble getBubble() {
        return bubble;
    }

    /**
     * Get the RealTimeController.
     *
     * @return RealTimeController managing simulation time
     */
    public RealTimeController getController() {
        return controller;
    }

    /**
     * Animated entity record.
     * <p>
     * Represents an entity in the animation system with its current position
     * and ghost status. This is a lightweight value object created per-frame.
     *
     * @param entityId Entity identifier
     * @param position Current position (extrapolated for ghosts)
     * @param isGhost  True if this is a ghost entity, false if owned
     */
    public record AnimatedEntity(
        StringEntityID entityId,
        Point3f position,
        boolean isGhost
    ) {
        /**
         * Check if this is a ghost entity.
         *
         * @return true if ghost, false if owned
         */
        public boolean isGhost() {
            return isGhost;
        }

        /**
         * Check if this is an owned entity.
         *
         * @return true if owned, false if ghost
         */
        public boolean isOwned() {
            return !isGhost;
        }
    }
}
