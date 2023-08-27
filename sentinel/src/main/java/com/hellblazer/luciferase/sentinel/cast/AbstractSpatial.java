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
package com.hellblazer.luciferase.sentinel.cast;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

/**
 * @author hal.hildebrand
 */
public class AbstractSpatial {

    protected Point3f location;

    public AbstractSpatial(Point3f location) {
        this.location = location;
    }

    public Point3f getLocation() {
        return location;
    }

    public Point3f moveBy(Tuple3f delta) {
        location.add(delta);
        return location;
    }

    public void setLocation(Point3f location) {
        this.location = location;
    }
}
