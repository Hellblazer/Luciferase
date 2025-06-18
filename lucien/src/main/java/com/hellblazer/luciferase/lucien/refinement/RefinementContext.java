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
import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import java.util.List;

/**
 * Context information provided to refinement criteria for decision making.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public record RefinementContext<ID extends EntityID>(
    long nodeIndex,
    int level,
    VolumeBounds bounds,
    List<EntityData<ID, ?>> entities,
    int entityCount,
    boolean hasChildren,
    Object nodeSpecificData // For implementation-specific data (e.g., tetrahedral type)
) {
    /**
     * Create a context for a leaf node (no children).
     */
    public static <ID extends EntityID> RefinementContext<ID> leaf(
        long nodeIndex,
        int level,
        VolumeBounds bounds,
        List<EntityData<ID, ?>> entities,
        Object nodeSpecificData
    ) {
        return new RefinementContext<>(
            nodeIndex,
            level,
            bounds,
            entities,
            entities.size(),
            false,
            nodeSpecificData
        );
    }

    /**
     * Create a context for an internal node (has children).
     */
    public static <ID extends EntityID> RefinementContext<ID> internal(
        long nodeIndex,
        int level,
        VolumeBounds bounds,
        int entityCount,
        Object nodeSpecificData
    ) {
        return new RefinementContext<>(
            nodeIndex,
            level,
            bounds,
            List.of(), // Internal nodes don't store entities directly
            entityCount,
            true,
            nodeSpecificData
        );
    }

    /**
     * Check if this is a leaf node.
     */
    public boolean isLeaf() {
        return !hasChildren;
    }

    /**
     * Check if this is an internal node.
     */
    public boolean isInternal() {
        return hasChildren;
    }

    /**
     * Get the volume of this node.
     */
    public float volume() {
        return bounds.width() * bounds.height() * bounds.depth();
    }

    /**
     * Get the entity density (entities per unit volume).
     */
    public float entityDensity() {
        float volume = volume();
        return volume > 0 ? entityCount / volume : 0;
    }
}