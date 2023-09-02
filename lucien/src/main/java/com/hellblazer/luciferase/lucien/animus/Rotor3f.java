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

import javax.vecmath.Vector3f;

/**
 * @author hal.hildebrand
 */
public class Rotor3f {

    /**
     * The Wedge operator on 3D vectors
     *
     * @param u
     * @param v
     * @return
     */
    public static Rotor3f wedge(Vector3f u, Vector3f v) {
        return new Rotor3f(u.x * v.y - v.x * u.y, u.y * v.z - v.y * u.z, u.z * v.x - v.z * u.x);
    }

    public static Rotor3f wedge(Vector3f u, Vector3f v, Rotor3f r) {
        r.xy = u.x * v.y - v.x * u.y;
        r.yz = u.y * v.z - v.y * u.z;
        r.zx = u.z * v.x - v.z * u.x;
        return r;
    }

    float a, xy, yz, zx;

    public Rotor3f() {
    }

    public Rotor3f(float xy, float yz, float zx) {
        this.xy = xy;
        this.yz = yz;
        this.zx = zx;
    }

    public Rotor3f(Vector3f from, Vector3f to) {
        a = 1 + to.dot(from);
        wedge(to, from, this);
        normalize();
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
     * Performs a great circle interpolation between the current vector state and
     * the resulting vector state
     *
     * @param v     the vector to interpolate
     * @param alpha the alpha interpolation parameter
     * @return the Rotor representing the rotation of the vector
     */
    public Rotor3f slerp(Vector3f v, float alpha) {
        var r = new Rotor3f(v, v);
        var d = a * r.a + xy * r.xy + yz * r.yz + zx * r.zx;
        var a0 = Math.acos(d);
        var sa0 = Math.sin(a0);
        var at = a0 * alpha;
        var sat = Math.sin(at);

        var s0 = Math.cos(at) - d * sat / sa0;
        var s1 = sat / sa0;

        r.a = (float) (s0 * r.a + s1 * a);
        r.xy = (float) (s0 * r.xy + s1 * xy);
        r.yz = (float) (s0 * r.yz + s1 * yz);
        r.zx = (float) (s0 * r.zx + s1 * zx);

        return r;
    }
}
