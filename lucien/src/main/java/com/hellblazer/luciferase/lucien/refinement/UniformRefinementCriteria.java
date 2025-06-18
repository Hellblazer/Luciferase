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
 * Refinement criteria for uniform refinement to a target level.
 * Refines all nodes until they reach the specified target level.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class UniformRefinementCriteria<ID extends EntityID> implements RefinementCriteria<ID> {
    private final int targetLevel;

    /**
     * Create uniform refinement criteria.
     *
     * @param targetLevel The target level to refine to
     */
    public UniformRefinementCriteria(int targetLevel) {
        if (targetLevel < 0 || targetLevel > 20) {
            throw new IllegalArgumentException("Target level must be between 0 and 20: " + targetLevel);
        }
        this.targetLevel = targetLevel;
    }

    @Override
    public boolean shouldRefine(RefinementContext<ID> context) {
        // Refine if we haven't reached the target level yet
        return context.level() < targetLevel;
    }

    @Override
    public boolean shouldCoarsen(RefinementContext<ID> context) {
        // Never coarsen in uniform refinement
        return false;
    }

    @Override
    public int getMaxLevel() {
        return targetLevel;
    }

    /**
     * Get the target refinement level.
     */
    public int getTargetLevel() {
        return targetLevel;
    }
}