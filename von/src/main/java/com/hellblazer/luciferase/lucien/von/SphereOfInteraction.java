/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Thoth Interest Management and Load Balancing Framework.
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

package com.hellblazer.luciferase.lucien.von;

import com.hellblazer.luciferase.lucien.grid.Vertex;

import javax.vecmath.Point3d;
import javax.vecmath.Tuple3d;
import java.util.Collection;
import java.util.List;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public interface SphereOfInteraction {

    /**
     * returns the closest node to a point
     *
     * @param coord
     * @return
     */
    Node closestTo(Point3d coord);

    /**
     * get a list of enclosing neighbors
     *
     * @param id
     * @return
     */
    List<Node> getEnclosingNeighbors(Node id);

    Iterable<Node> getPeers();

    /**
     * @param peer
     * @return
     */
    boolean includes(Node peer);

    /**
     * insert a new site, the first inserted is myself
     *
     * @param id
     * @param coord
     */
    void insert(Node id, Point3d coord);

    /**
     * check if the node is a boundary neighbor
     *
     * @param peer
     * @param center
     * @param radiusSquared
     * @return
     */
    boolean isBoundary(Node peer, Vertex center, float radiusSquared);

    /**
     * check if the node 'id' is an enclosing neighbor of 'center_node_id'
     *
     * @param peer
     * @param center_node_id
     * @return
     */
    boolean isEnclosing(Node peer, Node center_node_id);

    /**
     * check if a circle overlaps with a particular node
     *
     * @param peer
     * @param center
     * @param radiusSquared
     * @return
     */
    boolean overlaps(Node peer, Point3d center, float radiusSquared);

    /**
     * remove a site
     *
     * @param peer
     */
    boolean remove(Node peer);

    /**
     * modify the coordinates of a site
     *
     * @param peer
     * @param coord
     */
    void update(Node peer, Point3d coord);

}
