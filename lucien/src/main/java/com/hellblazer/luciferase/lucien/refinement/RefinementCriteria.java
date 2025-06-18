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
package com.hellblazer.luciferase.lucien.refinement;

import com.hellblazer.luciferase.lucien.entity.EntityID;

/**
 * Interface for specifying refinement conditions in adaptive mesh refinement.
 * Implementations determine whether a node should be refined, coarsened, or left unchanged.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public interface RefinementCriteria<ID extends EntityID> {

    /**
     * Determine whether a node should be refined.
     *
     * @param context The context information about the node
     * @return true if the node should be refined (split into children)
     */
    boolean shouldRefine(RefinementContext<ID> context);

    /**
     * Determine whether a node should be coarsened.
     *
     * @param context The context information about the node
     * @return true if the node's children should be merged back into this node
     */
    default boolean shouldCoarsen(RefinementContext<ID> context) {
        return false; // By default, don't coarsen
    }

    /**
     * Get the minimum level at which refinement should stop.
     *
     * @return the minimum refinement level
     */
    default int getMinLevel() {
        return 0;
    }

    /**
     * Get the maximum level at which refinement should stop.
     *
     * @return the maximum refinement level
     */
    default int getMaxLevel() {
        return 20; // Default to level 20
    }

    /**
     * Optional callback invoked before refinement begins.
     */
    default void onRefinementStart() {
    }

    /**
     * Optional callback invoked after refinement completes.
     *
     * @param statistics Statistics about the refinement operation
     */
    default void onRefinementComplete(RefinementStatistics statistics) {
    }
}