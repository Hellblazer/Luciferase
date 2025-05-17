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

package com.hellblazer.luciferase.lucien.von.impl;

import com.hellblazer.luciferase.lucien.von.Node;
import com.hellblazer.luciferase.lucien.von.Perceiving;
import com.hellblazer.sentry.Cursor;
import com.hellblazer.sentry.Vertex;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

abstract public class AbstractNode<E extends Perceiving> extends Vertex implements Node, Cursor {
    protected float aoiRadius;
    protected float maxRadiusSquared;

    public AbstractNode(E entity, Vertex location, float aoiRadius, float maxExtent) {
        super(location);
        this.aoiRadius = aoiRadius;
        this.maxRadiusSquared = maxExtent * maxExtent;
    }

    @Override
    public float getAoiRadius() {
        return aoiRadius;
    }

    @Override
    public float getMaximumRadiusSquared() {
        return maxRadiusSquared;
    }

    @Override
    public String toString() {
        String className = getClass().getCanonicalName();
        int index = className.lastIndexOf('.');
        return className.substring(index + 1) + " (" + x + ", " + y + ", " + z + ") aoi: " + getAoiRadius();
    }
}
