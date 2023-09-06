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

    private final Rotor3f orientation = new Rotor3f();

    public Oriented() {
        super();
    }

    public Oriented(float x, float y, float z, Rotor3f orientation) {
        super(x, y, z);
        orientation.set(orientation);
    }

    public Oriented(float[] v) {
        super(v);
    }

    public Oriented(Tuple3d t1) {
        super(t1);
    }

    public Oriented(Tuple3d location, Rotor3f orientation) {
        super(location);
        this.orientation.set(orientation);
    }

    public Oriented(Tuple3f t1) {
        super(t1);
    }

    public Oriented(Tuple3f location, Rotor3f orientation) {
        super(location);
        this.orientation.set(orientation);
    }

    @Override
    public Oriented clone() {
        final var clone = (Oriented) super.clone();
        clone.orientation.set(orientation);

        return clone;
    }

    /**
     * Returns true if the L-infinite distance between this tuple and tuple t1 is
     * less than or equal to the epsilon parameter, otherwise returns false. The
     * L-infinite distance is equal to MAX[abs(x1-x2), abs(y1-y2), abs(z1-z2)].
     *
     * @param t1      the tuple to be compared to this tuple
     * @param epsilon the threshold value
     * @return true or false
     */
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

    /**
     * Transform the receiver's state with the supplied Rotor transform
     *
     * @param transform
     */
    public void transform(Rotor3f transform) {
        set(transform.transform(this));
    }
}
