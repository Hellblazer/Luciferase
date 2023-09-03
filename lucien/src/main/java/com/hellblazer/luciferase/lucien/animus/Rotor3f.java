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

import static java.lang.Float.isNaN;
import static java.lang.Math.acos;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;

import javax.vecmath.Matrix4f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

/**
 * Geometric Algebra Rotor 3D
 *
 * @author hal.hildebrand
 */
public class Rotor3f {

    private static float lerp(float from, float to, float t) {
        return t * (to - from);
    }

    private float a = 1, xy = 0, yz = 0, zx = 0;

    public Rotor3f(Quat4f q) {
        a = q.w;
        xy = -q.z;
        yz = -q.x;
        zx = -q.y;
    }

    public Rotor3f(Vector3f from, Vector3f to) {
        final var halfway = new Vector3f(from);
        halfway.add(to);
        halfway.normalize();
        a = from.dot(halfway);
        xy = (halfway.x * from.y) - (halfway.y * from.x);
        yz = (halfway.y * from.z) - (halfway.z * from.y);
        zx = (halfway.z * from.x) - (halfway.x * from.z);
    }

    private Rotor3f(float a, float xy, float yz, float zx) {
        this.a = a;
        this.xy = xy;
        this.yz = yz;
        this.zx = zx;
    }

    private Rotor3f(Rotor3f r) {
        a = r.a;
        xy = r.xy;
        yz = r.yz;
        zx = r.zx;
    }

    public Rotor3f combine(Rotor3f rhs) {
        return new Rotor3f(a * rhs.a - xy * rhs.xy - yz * rhs.yz - zx * rhs.zx,
                           a * rhs.xy + xy * rhs.a - yz * rhs.zx + zx * rhs.yz,
                           a * rhs.yz + xy * rhs.zx + yz * rhs.a - zx * rhs.xy,
                           a * rhs.zx - xy * rhs.yz + yz * rhs.xy + zx * rhs.a);
    }

    public boolean epsilonEquals(Rotor3f t1, float epsilon) {
        float diff;

        diff = xy - t1.xy;
        if (isNaN(diff))
            return false;
        if ((diff < 0 ? -diff : diff) > epsilon)
            return false;

        diff = yz - t1.yz;
        if (isNaN(diff))
            return false;
        if ((diff < 0 ? -diff : diff) > epsilon)
            return false;

        diff = zx - t1.zx;
        if (isNaN(diff))
            return false;
        if ((diff < 0 ? -diff : diff) > epsilon)
            return false;

        diff = a - t1.a;
        if (isNaN(diff))
            return false;
        if ((diff < 0 ? -diff : diff) > epsilon)
            return false;

        return true;
    }

    public Rotor3f nlerp(Rotor3f to, float t) {
        float dot = a * to.a + xy * to.xy + yz * to.yz + zx * to.zx;
        if (dot < 0.0f) {
            to.a = -to.a;
            to.xy = -to.xy;
            to.yz = -to.yz;
            to.zx = -to.zx;
        }

        var r = new Rotor3f(lerp(a, to.a, t), lerp(xy, to.xy, t), lerp(yz, to.yz, t), lerp(zx, to.zx, t));
        r.normalize();
        return r;
    }

    public void normalize() {
        var n = sqrt(a * a + xy * xy + yz * yz + zx * zx);
        a /= n;
        xy /= n;
        yz /= n;
        zx /= n;
    }

    public Rotor3f reverse() {
        return new Rotor3f(a, -xy, -yz, -zx);
    }

    /**
     * Spherical Linear Interpolation.
     */
    public Rotor3f slerp(Rotor3f dest, float t) {
        var to = new Rotor3f(dest);
        double dot = a * to.a + xy * to.xy + yz * to.yz + zx * to.zx;
        if (dot < 0.0f) {
            to.a = -to.a;
            to.xy = -to.xy;
            to.yz = -to.yz;
            to.zx = -to.zx;
            dot = -dot;
        }

        // Avoid numerical stability issues with trig functions when
        // the angle between `from` and `to` is close to zero.
        // Also assumes `from` and `to` both have magnitude 1.
        if (dot > 0.99995f) {
            return nlerp(to, t);
        }

        // Assume that `from` and `to` both have magnitude 1
        // (IE they are the product of two unit vectors)
        // then cos(theta) = dot(from, to)
        double cos_theta = dot;

        double theta = acos(cos_theta);
        double from_factor = sin((1.0f - t) * theta) / sin(theta);
        double to_factor = sin(t * theta) / sin(theta);

        return new Rotor3f((float) (from_factor * a + to_factor * to.a), (float) (from_factor * xy + to_factor * to.xy),
                           (float) (from_factor * yz + to_factor * to.yz),
                           (float) (from_factor * zx + to_factor * to.zx));

    }

    /**
     * @return the conventional rotation matrix corresponding to the receiver
     */
    public Matrix4f toMatrix() {
        var new_x = transform(new Vector3f(1.0f, 0.0f, 0.0f));
        var new_y = transform(new Vector3f(0.0f, 1.0f, 0.0f));
        var new_z = transform(new Vector3f(0.0f, 0.0f, 1.0f));

        Matrix4f result = new Matrix4f();
        result.m00 = new_x.x;
        result.m01 = new_x.y;
        result.m02 = new_x.z;
        result.m03 = 0.0f;

        result.m10 = new_y.x;
        result.m11 = new_y.y;
        result.m12 = new_y.z;
        result.m13 = 0.0f;

        result.m20 = new_z.x;
        result.m21 = new_z.y;
        result.m22 = new_z.z;
        result.m23 = 0.0f;

        result.m30 = 0.0f;
        result.m31 = 0.0f;
        result.m32 = 0.0f;
        result.m33 = 1.0f;
        return result;
    }

    /**
     * Transform the vector using the receiver
     *
     * @param vec
     * @return the new Vector3f resulting from the rotation
     */
    public Vector3f transform(Vector3f vec) {
        var u = this;
        var v = vec;
        var q = new Vector3f(u.a * v.x + v.y * u.xy - v.z * u.zx, u.a * v.y + v.z * u.yz - v.x * u.xy,
                             u.a * v.z + v.x * u.zx - v.y * u.yz);

        var qxyz = -v.x * u.yz - v.y * u.zx - v.z * u.xy;
        return new Vector3f(u.a * q.x + q.y * u.xy - q.z * u.zx - qxyz * u.yz,
                            u.a * q.y + q.z * u.yz - q.x * u.xy - qxyz * u.zx,
                            u.a * q.z + q.x * u.zx - q.y * u.yz - qxyz * u.xy);
    }

}
