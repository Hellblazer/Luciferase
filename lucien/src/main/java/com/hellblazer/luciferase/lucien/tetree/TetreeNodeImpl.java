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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.AbstractSpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3i;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tetree node implementation.
 *
 * <h3>Thread Safety</h3>
 * <p>This class is NOT thread-safe on its own. It relies on external synchronization provided by
 * AbstractSpatialIndex's read-write lock. All access to node instances must be performed within
 * the appropriate lock context.</p>
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class TetreeNodeImpl<ID extends EntityID> extends AbstractSpatialNode<ID> {



    /**
     * Create a node with default max entities (10)
     */
    public TetreeNodeImpl() {
        super();
    }

    /**
     * Create a node with specified max entities before split
     *
     * @param maxEntitiesBeforeSplit threshold for subdivision
     */
    public TetreeNodeImpl(int maxEntitiesBeforeSplit) {
        super(maxEntitiesBeforeSplit);
    }






}
