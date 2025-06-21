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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.AbstractSpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Collections;
import java.util.List;

/**
 * Octree node implementation. Now simplified as most functionality has been hoisted to AbstractSpatialNode.
 *
 * Thread Safety: This class is NOT thread-safe on its own. It relies on external synchronization provided by
 * AbstractSpatialIndex's read-write lock. All access to node instances must be performed within the appropriate lock
 * context.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class OctreeNode<ID extends EntityID> extends AbstractSpatialNode<ID> {

    /**
     * Create a node with default max entities (10)
     */
    public OctreeNode() {
        super();
    }

    /**
     * Create a node with specified max entities before split
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    public OctreeNode(int maxEntitiesBeforeSplit) {
        super(maxEntitiesBeforeSplit);
    }

    /**
     * Clear a bit in the children mask when a child is removed (octant-specific naming for backward compatibility)
     *
     * @param octant the octant index (0-7)
     */
    public void clearChildBit(int octant) {
        super.clearChildBit(octant);
    }

    /**
     * Get all entity IDs as a list (for backward compatibility)
     *
     * @return unmodifiable list view of entity IDs
     */
    public List<ID> getEntityIdsAsList() {
        return Collections.unmodifiableList(entityIds);
    }

    /**
     * Check if a specific octant has a child (octant-specific naming for backward compatibility)
     *
     * @param octant the octant index (0-7)
     */
    public boolean hasChild(int octant) {
        return super.hasChild(octant);
    }

    /**
     * Set a bit in the children mask to indicate a child exists (octant-specific naming for backward compatibility)
     *
     * @param octant the octant index (0-7)
     */
    public void setChildBit(int octant) {
        super.setChildBit(octant);
    }
}
