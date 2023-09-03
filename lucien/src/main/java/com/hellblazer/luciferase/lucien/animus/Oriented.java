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

    private final Vector3f orientation = new Vector3f(1, 0, 0);

    public Oriented() {
        super();
    }

    public Oriented(float x, float y, float z, float oX, float oY, float oZ) {
        super(x, y, z);
        orientation.x = oX;
        orientation.y = oY;
        orientation.z = oZ;
    }

    public Oriented(float[] v) {
        super(v);
        orientation.x = v[3];
        orientation.y = v[4];
        orientation.z = v[5];
    }

    public Oriented(Tuple3d t1) {
        super(t1);
    }

    public Oriented(Tuple3d location, Tuple3d orientation) {
        super(location);
        this.orientation.set(orientation);
    }

    public Oriented(Tuple3f t1) {
        super(t1);
    }

    public Oriented(Tuple3f location, Tuple3f orientation) {
        super(location);
        this.orientation.set(orientation);
    }

    @Override
    public Oriented clone() {
        final var clone = (Oriented) super.clone();
        clone.orientation.x = orientation.x;
        clone.orientation.y = orientation.y;
        clone.orientation.z = orientation.z;

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

    public Vector3f orientation() {
        return orientation;
    }

    public void reorient(Rotor3f transform) {
        orientation.set(transform.transform(orientation));
    }

    public void transform(Rotor3f transform) {
        set(transform.transform(this));
    }

    public void transform(Rotor3f transform, Rotor3f oTransform) {
        reorient(oTransform);
        transform(transform);
    }
}
