/**
 * Copyright (C) 2008 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.lucien.impl;

import com.hellblazer.luciferase.lucien.Perceiving;
import com.hellblazer.luciferase.lucien.grid.Vertex;

import java.util.UUID;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

abstract public class AbstractNode<E extends Perceiving> extends AbstractCursor<E> implements Node {

    protected final UUID  id;
    protected       float aoiRadius;
    protected       float maximumVelocity;
    protected       float maxRadiusSquared;
    protected       E     sim;

    public AbstractNode(E entity, UUID id, Vertex location, float aoiRadius, float maximumVelocity) {
        super(location);
        this.sim = entity;
        this.aoiRadius = aoiRadius;
        this.id = id;
        this.maximumVelocity = maximumVelocity;
        float maxExtent = aoiRadius + maximumVelocity * BUFFER_MULTIPLIER;
        this.maxRadiusSquared = maxExtent * maxExtent;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AbstractNode)) {
            return false;
        }
        return id.equals(((AbstractNode<?>) obj).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public float getAoiRadius() {
        return aoiRadius;
    }

    public UUID getId() {
        return id;
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
    public String toString() {
        String className = getClass().getCanonicalName();
        int index = className.lastIndexOf('.');
        return className.substring(index + 1) + " [" + id + "] (" + location.x + ", " + location.y + ") aoi: "
        + getAoiRadius();
    }

}
