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
 * Refinement criteria based on entity density.
 * Refines nodes that have high entity density and coarsens nodes with low density.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class EntityDensityRefinementCriteria<ID extends EntityID> implements RefinementCriteria<ID> {
    private final float refineDensityThreshold;
    private final float coarsenDensityThreshold;
    private final int minLevel;
    private final int maxLevel;
    private final int maxEntitiesPerNode;

    /**
     * Create entity density refinement criteria.
     *
     * @param refineDensityThreshold   Density above which to refine (entities per unit volume)
     * @param coarsenDensityThreshold  Density below which to coarsen
     * @param maxEntitiesPerNode       Maximum entities allowed per node
     * @param minLevel                 Minimum refinement level
     * @param maxLevel                 Maximum refinement level
     */
    public EntityDensityRefinementCriteria(float refineDensityThreshold, float coarsenDensityThreshold,
                                         int maxEntitiesPerNode, int minLevel, int maxLevel) {
        if (refineDensityThreshold <= coarsenDensityThreshold) {
            throw new IllegalArgumentException("Refine threshold must be greater than coarsen threshold");
        }
        this.refineDensityThreshold = refineDensityThreshold;
        this.coarsenDensityThreshold = coarsenDensityThreshold;
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
    }

    /**
     * Simple constructor with default levels.
     */
    public EntityDensityRefinementCriteria(float refineDensityThreshold, float coarsenDensityThreshold,
                                         int maxEntitiesPerNode) {
        this(refineDensityThreshold, coarsenDensityThreshold, maxEntitiesPerNode, 0, 20);
    }

    @Override
    public boolean shouldRefine(RefinementContext<ID> context) {
        // Don't refine beyond max level
        if (context.level() >= maxLevel) {
            return false;
        }

        // Always refine if we have too many entities
        if (context.entityCount() > maxEntitiesPerNode) {
            return true;
        }

        // Refine based on density
        return context.entityDensity() > refineDensityThreshold;
    }

    @Override
    public boolean shouldCoarsen(RefinementContext<ID> context) {
        // Don't coarsen below min level
        if (context.level() <= minLevel) {
            return false;
        }

        // Only coarsen internal nodes
        if (!context.isInternal()) {
            return false;
        }

        // Coarsen if density is low
        return context.entityDensity() < coarsenDensityThreshold;
    }

    @Override
    public int getMinLevel() {
        return minLevel;
    }

    @Override
    public int getMaxLevel() {
        return maxLevel;
    }
}