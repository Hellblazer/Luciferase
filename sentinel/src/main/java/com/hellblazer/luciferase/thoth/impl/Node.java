/**
 * Copyright (C) 2008 Hal Hildebrand. All rights reserved.
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

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

import com.hellblazer.luciferase.sentinel.Vertex;
import com.hellblazer.luciferase.thoth.Cursor;
import com.hellblazer.luciferase.thoth.Perceiving;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

abstract public class Node extends Vertex implements Cursor, Cloneable {
    private static final int  BUFFER_MULTIPLIER = 2;
    private static final long serialVersionUID  = 1L;

    protected float      aoiRadius;
    protected float      maximumVelocity;
    protected float      maxRadiusSquared;
    protected Perceiving sim;

    public Node(Perceiving entity, Point3f location, int aoiRadius, int maximumVelocity) {
        super(location);
        this.sim = entity;
        this.aoiRadius = aoiRadius;
        this.maximumVelocity = maximumVelocity;
        int maxExtent = aoiRadius + maximumVelocity * BUFFER_MULTIPLIER;
        this.maxRadiusSquared = maxExtent * maxExtent;
    }

    @Override
    public Node clone() {
        final var clone = (Node) super.clone();
        return clone;
    }

    abstract public void fadeFrom(Node neighbor);

    public float getAoiRadius() {
        return aoiRadius;
    }

    @Override
    public Point3f getLocation() {
        return new Point3f(this);
    }

    public float getMaximumRadiusSquared() {
        return maxRadiusSquared;
    }

    public float getMaximumVelocity() {
        return maximumVelocity;
    }

    public Perceiving getSim() {
        return sim;
    }

    abstract public void leave(Node leaving);

    abstract public void move(Node neighbor);

    abstract public void moveBoundary(Node neighbor);

    abstract public void noticeNodes(Collection<Node> nodes);

    abstract public void perceive(Node neighbor);

    abstract public void query(Node from, Node joiner);

    public void setLocation(Tuple3f location) {
        super.set(location);
    }
}
