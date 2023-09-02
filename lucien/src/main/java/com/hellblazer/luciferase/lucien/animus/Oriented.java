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
import javax.vecmath.Tuple4f;
import javax.vecmath.Vector3f;

/**
 * Location + orientation. Orientation represented by a Quaternion
 *
 * @author hal.hildebrand
 */
public class Oriented extends Point3f {
    private static final long serialVersionUID = 1L;

    static int floatToIntBits(float f) {
        // Check for +0 or -0
        if (f == 0.0f) {
            return 0;
        } else {
            return Float.floatToIntBits(f);
        }
    }

    private float i = 0.0f;
    private float j = 0.0f;
    private float k = 0.0f;
    private float w = 1;

    public Oriented() {
        super();
    }

    public Oriented(float x, float y, float z) {
        super(x, y, z);
    }

    public Oriented(float x, float y, float z, float w) {
        this.i = x;
        this.j = y;
        this.k = z;
        this.w = w;
    }

    public Oriented(float x, float y, float z, float w, float i, float j, float k) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.i = i;
        this.j = j;
        this.k = k;
        this.w = w;
    }

    public Oriented(float[] p) {
        super(p);
    }

    public Oriented(final Oriented q) {
        this(q.i, q.j, q.k, q.w);
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

    public Oriented(Tuple3f location, float x, float y, float z, float w) {
        super(location);
        this.i = x;
        this.j = y;
        this.k = z;
        this.w = w;
    }

    public Oriented(Tuple3f location, Tuple4f quaternion) {
        super(location);
        i = quaternion.x;
        j = quaternion.y;
        k = quaternion.z;
        w = quaternion.w;
    }

    public Oriented(Tuple4f quaternion) {
        this(quaternion.x, quaternion.y, quaternion.z, quaternion.w);
    }

    public Oriented(Vector3f axis, float angle) {
        set(axis, angle);
    }

    @Override
    public Oriented clone() {
        final var clone = (Oriented) super.clone();
        clone.i = i;
        clone.j = j;
        clone.k = k;
        clone.w = w;
        return clone;
    }

    public Oriented divThis(double scale) {
        if (scale != 1) {
            w /= scale;
            i /= scale;
            j /= scale;
            k /= scale;
        }
        return this;
    }

    public float dot(Oriented q) {
        return i * q.i + j * q.j + k * q.k + w * q.w;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Oriented q) {
            return super.equals(q) && i == q.i && j == q.j && k == q.k && w == q.w;
        }
        return false;
    }

    public boolean equals(Oriented q) {
        return super.equals(q) && i == q.i && j == q.j && k == q.k && w == q.w;
    }

    public float getI() {
        return i;
    }

    public float getJ() {
        return j;
    }

    public float getK() {
        return k;
    }

    public float getW() {
        return w;
    }

    @Override
    public int hashCode() {
        long bits = 1L;
        bits = 31L * bits + floatToIntBits(x);
        bits = 31L * bits + floatToIntBits(y);
        bits = 31L * bits + floatToIntBits(z);
        bits = 31L * bits + floatToIntBits(i);
        bits = 31L * bits + floatToIntBits(j);
        bits = 31L * bits + floatToIntBits(k);
        bits = 31L * bits + floatToIntBits(w);
        return (int) (bits ^ (bits >> 32));
    }

    public Oriented interpolate(Oriented q, float t) {
        return new Oriented(this).interpolateThis(q, t);
    }

    public Oriented interpolateThis(Oriented q, float t) {
        if (!equals(q)) {
            double d = dot(q);
            double qx, qy, qz, qw;

            if (d < 0f) {
                qx = -q.i;
                qy = -q.j;
                qz = -q.k;
                qw = -q.w;
                d = -d;
            } else {
                qx = q.i;
                qy = q.j;
                qz = q.k;
                qw = q.w;
            }

            double f0, f1;

            if ((1 - d) > 0.1f) {
                double angle = Math.acos(d);
                double s = Math.sin(angle);
                double tAngle = t * angle;
                f0 = Math.sin(angle - tAngle) / s;
                f1 = Math.sin(tAngle) / s;
            } else {
                f0 = 1 - t;
                f1 = t;
            }

            i = (float) (f0 * i + f1 * qx);
            j = (float) (f0 * j + f1 * qy);
            k = (float) (f0 * k + f1 * qz);
            w = (float) (f0 * w + f1 * qw);
        }

        return this;
    }

    public Oriented mulThis(Oriented q) {
        // matrixs = null;
        float nw = w * q.w - i * q.i - j * q.j - k * q.k;
        float nx = w * q.i + i * q.w + j * q.k - k * q.j;
        float ny = w * q.j + j * q.w + k * q.i - i * q.k;
        k = w * q.k + k * q.w + i * q.j - j * q.i;
        w = nw;
        i = nx;
        j = ny;
        return this;
    }

    public double norm() {
        return Math.sqrt(dot(this));
    }

    public Oriented normalizeThis() {
        return divThis(norm());
    }

    public Oriented scaleThis(float scale) {
        if (scale != 1) {
            // matrixs = null;
            w *= scale;
            i *= scale;
            j *= scale;
            k *= scale;
        }
        return this;
    }

    public void set(final Oriented q) {
        // matrixs = null;
        this.i = q.i;
        this.j = q.j;
        this.k = q.k;
        this.w = q.w;
    }

    /**
     * @param axis  rotation axis, unit vector
     * @param angle the rotation angle
     * @return this
     */
    public Oriented set(Vector3f axis, float angle) {
        // matrixs = null;
        double s = Math.sin(angle / 2);
        w = (float) Math.cos(angle / 2);
        i = (float) (axis.getX() * s);
        j = (float) (axis.getY() * s);
        k = (float) (axis.getZ() * s);
        return this;
    }

    /**
     * Converts this Oriented into a matrix, returning it as a float array.
     */
    public float[] toMatrix() {
        float[] matrixs = new float[16];
        toMatrix(matrixs);
        return matrixs;
    }

    /**
     * Converts this Oriented into a matrix, placing the values into the given
     * array.
     *
     * @param matrixs 16-length float array.
     */
    public final void toMatrix(float[] matrixs) {
        matrixs[3] = 0.0f;
        matrixs[7] = 0.0f;
        matrixs[11] = 0.0f;
        matrixs[12] = 0.0f;
        matrixs[13] = 0.0f;
        matrixs[14] = 0.0f;
        matrixs[15] = 1.0f;

        matrixs[0] = 1.0f - (2.0f * ((j * j) + (k * k)));
        matrixs[1] = 2.0f * ((i * j) - (k * w));
        matrixs[2] = 2.0f * ((i * k) + (j * w));

        matrixs[4] = 2.0f * ((i * j) + (k * w));
        matrixs[5] = 1.0f - (2.0f * ((i * i) + (k * k)));
        matrixs[6] = 2.0f * ((j * k) - (i * w));

        matrixs[8] = 2.0f * ((i * k) - (j * w));
        matrixs[9] = 2.0f * ((j * k) + (i * w));
        matrixs[10] = 1.0f - (2.0f * ((i * i) + (j * j)));
    }
}
