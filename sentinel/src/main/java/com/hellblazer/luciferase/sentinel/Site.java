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
package com.hellblazer.luciferase.sentinel;

import java.math.BigInteger;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

import com.hellblazer.delaunay.Vertex;

/**
 * @author hal.hildebrand
 */
public class Site extends Vertex implements Comparable<Site> {
    private static final long serialVersionUID = 1L;

    private BigInteger hilbert;

    public Site(Point3f p) {
        this(p, null);
    }

    public Site(Point3f initial, BigInteger hilbert) {
        super(initial.x, initial.y, initial.z);
        this.hilbert = hilbert;
    }

    @Override
    public int compareTo(Site o) {
        return hilbert.compareTo(o.hilbert);
    }

    public BigInteger getHilbert() {
        return hilbert;
    }

    public void moveBy(Tuple3f delta) {
        x = x + delta.x;
        y = y + delta.y;
        z = z + delta.z;
    }

    void setHilbert(BigInteger hilbert) {
        this.hilbert = hilbert;
    }
}
