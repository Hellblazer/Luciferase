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

package com.hellblazer.luciferase.simulation;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Interface for entity movement tracking in the simulation.
 * <p>
 * Provides methods for querying position, moving entities, and accessing neighbors.
 * Implementations may use different spatial data structures (Tetree, Delaunay, etc.)
 * for tracking and neighbor queries.
 *
 * @author hal.hildebrand
 */
public interface Cursor {

    /**
     * Get the current location of this entity.
     *
     * @return the current position
     */
    Point3f getLocation();

    /**
     * Move the entity by a relative delta.
     *
     * @param delta the relative movement vector
     */
    void moveBy(Tuple3f delta);

    /**
     * Move the entity to an absolute position.
     *
     * @param position the target position
     */
    void moveTo(Tuple3f position);

    /**
     * Get a stream of neighboring entities.
     *
     * @return stream of neighboring cursors
     */
    Stream<Cursor> neighbors();

    /**
     * Visit all neighboring entities with a consumer.
     *
     * @param consumer the visitor function
     */
    void visitNeighbors(Consumer<Cursor> consumer);
}
