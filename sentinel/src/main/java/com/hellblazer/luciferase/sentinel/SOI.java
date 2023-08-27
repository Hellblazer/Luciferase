/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sentinel;

import java.util.Collection;

import javax.vecmath.Tuple3f;

import com.hellblazer.luciferase.thoth.Perceiving;
import com.hellblazer.luciferase.thoth.impl.Node;
import com.hellblazer.luciferase.thoth.impl.SphereOfInteraction;

/**
 * Sentinel implementation of the Thoth sphere o' interaction
 *
 * @author hal.hildebrand
 */
public class SOI implements SphereOfInteraction {

    @Override
    public <T extends Perceiving> Node<T> closestTo(Tuple3f point3f) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public <T extends Perceiving> Node<T> getAliased(Node<?> node) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Collection<Node<?>> getEnclosingNeighbors(Node<?> id) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Iterable<Node<?>> getNodes() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Collection<Node<?>> getPeers() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean includes(Node<?> node) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public <T extends Perceiving> void insert(Node<T> id, Tuple3f point3f) {
        // TODO Auto-generated method stub

    }

    @Override
    public <T extends Perceiving> boolean isBoundary(Node<T> node, Tuple3f center, float radiusSquared) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isEnclosing(Node<?> node, Node<?> center_node_id) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean overlaps(Node<?> node, Tuple3f center, float radiusSquared) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void remove(Node<?> node) {
        // TODO Auto-generated method stub

    }

    @Override
    public void update(Node<?> node, Tuple3f coord) {
        // TODO Auto-generated method stub

    }
}
