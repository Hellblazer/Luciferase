/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Thoth Interest Management and Load Balancing
 * Framework.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.thoth.impl;

import java.util.Collection;

import javax.vecmath.Tuple3f;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

public interface SphereOfInteraction {

    /**
     * returns the closest node to a point
     *
     * @param point3f
     * @return
     */
    Node closestTo(Tuple3f point3f);

    /**
     * Answer the Node aliased to the Node
     *
     * @param node
     * @return
     */
    Node getAliased(Node node);

    /**
     * get a list of enclosing neighbors
     *
     * @param id
     * @return
     */
    Collection<Node> getEnclosingNeighbors(Node id);

    Iterable<Node> getNodes();

    /**
     * @param node
     * @return
     */
    boolean includes(Node node);

    /**
     * insert a new site, the first inserted is myself
     *
     * @param id
     */
    void insert(Node id);

    /**
     * check if the node is a boundary neighbor
     *
     * @param node
     * @param center
     * @param radiusSquared
     * @return
     */
    boolean isBoundary(Node node, Tuple3f center, float radiusSquared);

    /**
     * check if the node 'id' is an enclosing neighbor of 'center_node_id'
     *
     * @param node
     * @param center_node_id
     * @return
     */
    boolean isEnclosing(Node node, Node center_node_id);

    /**
     * check if a circle overlaps with a particular node
     *
     * @param node
     * @param center
     * @param radiusSquared
     * @return
     */
    default boolean overlaps(Node node, Tuple3f center, float radiusSquared) {
        return node.distanceSquared(center) <= radiusSquared;
    }

    /**
     * remove a site
     *
     * @param node
     */
    void remove(Node node);

    /**
     * modify the coordinates of a site
     *
     * @param node
     * @param coord
     */
    void update(Node node, Tuple3f coord);

}