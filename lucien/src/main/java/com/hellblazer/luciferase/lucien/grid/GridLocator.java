/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.grid;

import javax.vecmath.Tuple3f;
import java.util.Random;

/**
 * @author hal.hildebrand
 */
public class GridLocator {
    private Tetrahedron last;

    public GridLocator(Tetrahedron initial) {
        last = initial;
    }

    /**
     * Locate the tetrahedron which contains the query point via a stochastic walk through the delaunay triangulation.
     * This location algorithm is a slight variation of the 3D jump and walk algorithm found in: "Fast randomized point
     * location without preprocessing in two- and three-dimensional Delaunay triangulations", Computational Geometry 12
     * (1999) 63-83. z
     *
     * @param query - the query point
     * @return the Tetrahedron containing the query
     */
    public Tetrahedron locate(Tuple3f query, Random entropy) {
        return last = last.locate(query, entropy);
    }
}
