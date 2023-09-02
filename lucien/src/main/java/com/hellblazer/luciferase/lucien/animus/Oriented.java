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
package com.hellblazer.luciferase.lucien.animus;

import javax.vecmath.Point3d;
import javax.vecmath.Point3f;
import javax.vecmath.Tuple3d;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;

/**
 * An oriented vector
 *
 * @author hal.hildebrand
 */
public class Oriented extends Vector3f {
    private static final long serialVersionUID = 1L;

    private final Rotor3f orientation = new Rotor3f();

    public Oriented() {
        super();
    }

    public Oriented(float x, float y, float z) {
        super(x, y, z);
    }

    public Oriented(Point3d p1) {
        super(p1);
    }

    public Oriented(Point3f p1) {
        super(p1);
    }

    public Oriented(Tuple3d t1) {
        super(t1);
    }

    public Oriented(Tuple3f t1) {
        super(t1);
    }

    @Override
    public Oriented clone() {
        final var clone = (Oriented) super.clone();
        clone.orientation.a = orientation.a;
        clone.orientation.xy = orientation.xy;
        clone.orientation.yz = orientation.yz;
        clone.orientation.zx = orientation.zx;

        return clone;
    }

    public boolean epsilonEquals(Oriented t1, float epsilon) {
        return super.epsilonEquals(t1, epsilon) && orientation.epsilonEquals(t1.orientation, epsilon);
    }

    @Override
    public boolean equals(Object t1) {
        return (t1 instanceof Oriented o) ? equals(o) : false;
    }

    public boolean equals(Oriented t1) {
        return super.equals(t1) && orientation.equals(t1.orientation);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + orientation.hashCode();
    }

    public Rotor3f orientation() {
        return orientation;
    }
}
