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
import java.util.UUID;

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

abstract public class Node<E extends Perceiving> extends Vertex implements Cursor, Cloneable {
    private static final int  BUFFER_MULTIPLIER = 2;
    private static final long serialVersionUID  = 1L;

    protected float      aoiRadius;
    protected final UUID id;
    protected float      maximumVelocity;
    protected float      maxRadiusSquared;
    protected E          sim;

    public Node(E entity, UUID id, Point3f location, int aoiRadius, int maximumVelocity) {
        super(location);
        this.sim = entity;
        this.aoiRadius = aoiRadius;
        this.id = id;
        this.maximumVelocity = maximumVelocity;
        int maxExtent = aoiRadius + maximumVelocity * BUFFER_MULTIPLIER;
        this.maxRadiusSquared = maxExtent * maxExtent;
    }

    @Override
    public Node<E> clone() {
        @SuppressWarnings("unchecked")
        final var clone = (Node<E>) super.clone();
        return clone;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || !(obj instanceof Node)) {
            return false;
        }
        return id.equals(((Node<?>) obj).id);
    }

    abstract public void fadeFrom(Node<?> neighbor);

    public float getAoiRadius() {
        return aoiRadius;
    }

    public UUID getId() {
        return id;
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

    public E getSim() {
        return sim;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    abstract public void leave(Node<?> leaving);

    abstract public void move(Node<?> neighbor);

    abstract public void moveBoundary(Node<?> neighbor);

    abstract public void noticeNodes(Collection<Node<?>> nodes);

    abstract public void perceive(Node<?> neighbor);

    abstract public void query(Node<?> from, Node<?> joiner);

    public void setLocation(Tuple3f location) {
        super.set(location);
    }

    @Override
    public String toString() {
        String className = getClass().getCanonicalName();
        int index = className.lastIndexOf('.');
        return className.substring(index + 1) + " [" + id + "] (" + x + ", " + y + ", " + z + ") aoi: "
        + getAoiRadius();
    }
}
