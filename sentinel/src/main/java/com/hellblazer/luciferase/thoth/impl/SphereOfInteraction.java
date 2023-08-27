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

import com.hellblazer.luciferase.thoth.Perceiving;

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
    <T extends Perceiving> Node<T> closestTo(Tuple3f point3f);

    /**
     * Answer the Node aliased to the Node
     *
     * @param node
     * @return
     */
    <T extends Perceiving> Node<T> getAliased(Node<?> node);

    /**
     * get a list of enclosing neighbors
     *
     * @param id
     * @return
     */
    Collection<Node<?>> getEnclosingNeighbors(Node<?> id);

    Iterable<Node<?>> getNodes();

    Collection<Node<?>> getPeers();

    /**
     * @param node
     * @return
     */
    boolean includes(Node<?> node);

    /**
     * insert a new site, the first inserted is myself
     *
     * @param id
     * @param point3f
     */
    <T extends Perceiving> void insert(Node<T> id, Tuple3f point3f);

    /**
     * check if the node is a boundary neighbor
     *
     * @param node
     * @param center
     * @param radiusSquared
     * @return
     */
    <T extends Perceiving> boolean isBoundary(Node<T> node, Tuple3f center, float radiusSquared);

    /**
     * check if the node 'id' is an enclosing neighbor of 'center_node_id'
     *
     * @param node
     * @param center_node_id
     * @return
     */
    boolean isEnclosing(Node<?> node, Node<?> center_node_id);

    /**
     * check if a circle overlaps with a particular node
     *
     * @param node
     * @param center
     * @param radiusSquared
     * @return
     */
    boolean overlaps(Node<?> node, Tuple3f center, float radiusSquared);

    /**
     * remove a site
     *
     * @param node
     */
    void remove(Node<?> node);

    /**
     * modify the coordinates of a site
     *
     * @param node
     * @param coord
     */
    void update(Node<?> node, Tuple3f coord);

}
