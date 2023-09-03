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

import javax.vecmath.Matrix4f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

/**
 * @author hal.hildebrand
 */
public class Rotor3f {

    private static float lerp(float from, float to, float t) {
        return t * (to - from);
    }

    private float a, xy, yz, zx;

    public Rotor3f() {
    }

    public Rotor3f(float xy, float yz, float zx) {
        this.xy = xy;
        this.yz = yz;
        this.zx = zx;
    }

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

        final var wedge = new Vector3f((halfway.x * from.y) - (halfway.y * from.x),
                                       (halfway.y * from.z) - (halfway.z * from.y),
                                       (halfway.z * from.x) - (halfway.x * from.z));
        a = from.dot(halfway);
        xy = wedge.x;
        yz = wedge.y;
        zx = wedge.z;
    }

    public Rotor3f combine(Rotor3f lhs, Rotor3f rhs) {
        Rotor3f result = new Rotor3f();
        result.a = lhs.a * rhs.a - lhs.xy * rhs.xy - lhs.yz * rhs.yz - lhs.zx * rhs.zx;
        result.xy = lhs.a * rhs.xy + lhs.xy * rhs.a - lhs.yz * rhs.zx + lhs.zx * rhs.yz;
        result.yz = lhs.a * rhs.yz + lhs.xy * rhs.zx + lhs.yz * rhs.a - lhs.zx * rhs.xy;
        result.zx = lhs.a * rhs.zx - lhs.xy * rhs.yz + lhs.yz * rhs.xy + lhs.zx * rhs.a;
        return result;
    }

    public boolean epsilonEquals(Rotor3f t1, float epsilon) {
        float diff;

        diff = xy - t1.xy;
        if (Float.isNaN(diff))
            return false;
        if ((diff < 0 ? -diff : diff) > epsilon)
            return false;

        diff = yz - t1.yz;
        if (Float.isNaN(diff))
            return false;
        if ((diff < 0 ? -diff : diff) > epsilon)
            return false;

        diff = zx - t1.zx;
        if (Float.isNaN(diff))
            return false;
        if ((diff < 0 ? -diff : diff) > epsilon)
            return false;

        diff = a - t1.a;
        if (Float.isNaN(diff))
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

        var r = new Rotor3f();
        r.a = lerp(a, to.a, t);
        r.xy = lerp(xy, to.xy, t);
        r.yz = lerp(yz, to.yz, t);
        r.zx = lerp(zx, to.zx, t);

        var magnitude = Math.sqrt(r.a * r.a + r.xy * r.xy + r.yz * r.yz + r.zx * r.zx);
        r.a /= magnitude;
        r.xy /= magnitude;
        r.yz /= magnitude;
        r.zx /= magnitude;
        return r;
    }

    /**
     * Normalize to the unit Rotor
     */
    public void normalize() {
        var n = Math.sqrt(a * a + xy * xy + yz * yz + zx * zx);
        a /= n;
        xy /= n;
        yz /= n;
        zx /= n;
    }

    /**
     * Rotate the vector using the receiver
     *
     * @param vec
     * @return the new Vector3f resulting from the rotation
     */
    public Vector3f rotate(Vector3f vec) {
        var u = this;
        var v = vec;
        var q = new Vector3f(u.a * v.x + v.y * u.xy - v.z * u.zx, u.a * v.y + v.z * u.yz - v.x * u.xy,
                             u.a * v.z + v.x * u.zx - v.y * u.yz);

        var qxyz = -v.x * u.yz - v.y * u.zx - v.z * u.xy;
        return new Vector3f(u.a * q.x + q.y * u.xy - q.z * u.zx - qxyz * u.yz,
                            u.a * q.y + q.z * u.yz - q.x * u.xy - qxyz * u.zx,
                            u.a * q.z + q.x * u.zx - q.y * u.yz - qxyz * u.xy);
    }

    /**
     * Spherical Linear Interpolation.
     * <p>
     * Performs a great circle interpolation between the receiver and to returning
     * the new Rotor at t
     *
     * @param t the rotor to interpolate to
     * @param t the alpha interpolation parameter, between 0 and 1
     * @return the Rotor representing the rotation of the receiver to the target at
     *         t
     */
    public Rotor3f slerp(Rotor3f to, float t) {
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

        double theta = Math.acos(cos_theta);
        double from_factor = Math.sin((1.0f - t) * theta) / Math.sin(theta);
        double to_factor = Math.sin(t * theta) / Math.sin(theta);

        Rotor3f result = new Rotor3f();
        result.a = (float) (from_factor * a + to_factor * to.a);
        result.xy = (float) (from_factor * xy + to_factor * to.xy);
        result.yz = (float) (from_factor * yz + to_factor * to.yz);
        result.zx = (float) (from_factor * zx + to_factor * to.zx);
        return result;

    }

    /**
     * @return the conventional rotation matrix corresponding to the receiver
     */
    public Matrix4f toMatrix() {
        var new_x = rotate(new Vector3f(1.0f, 0.0f, 0.0f));
        var new_y = rotate(new Vector3f(0.0f, 1.0f, 0.0f));
        var new_z = rotate(new Vector3f(0.0f, 0.0f, 1.0f));

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

    Rotor3f reverse(Rotor3f r) {
        Rotor3f result = new Rotor3f();
        result.a = r.a;
        result.xy = -r.xy;
        result.yz = -r.yz;
        result.zx = -r.zx;
        return result;
    }

}
