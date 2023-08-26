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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

import com.hellblazer.luciferase.sentinel.delaunay.Tetrahedralization;
import com.hellblazer.luciferase.sentinel.delaunay.Vertex;

/**
 * Kinetic point cloud tracking
 *
 * @author hal.hildebrand
 */
public class Sentinel {
    public static class OutOfBoundsException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Tuple3f     destination;
        private final Tuple3f     extent;

        public OutOfBoundsException(String message, Tuple3f destination, Tuple3f extent) {
            super(message);
            this.destination = new Point3f(destination);
            this.extent = new Point3f(extent);
        }

        public Tuple3f getDestination() {
            return destination;
        }

        public Tuple3f getExtent() {
            return extent;
        }
    }

    private Tetrahedralization grid;
    private final List<Vertex> tracking = new ArrayList<>();

    public void retriangulate() {
        grid = new Tetrahedralization();
        for (var site : tracking) {
            grid.insert(site);
        }
    }

    public Stream<Vertex> stream() {
        return tracking.stream();
    }

    /**
     * Track the vertex
     *
     * @param s
     */
    public void track(Vertex s) {
        tracking.add(s);
    }
}
