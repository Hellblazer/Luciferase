/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.tetree.Tet;

import javax.vecmath.Vector3d;

/**
 * A simplex representing a tetrahedron in the tetrahedral mesh with associated data. This record combines a tetrahedral
 * SFC index with the content stored at that location.
 *
 * @param <Data> the type of data associated with this simplex
 * @author hal.hildebrand
 */
public record Simplex<Data>(long index, Data cell) implements Spatial {
    @Override
    public boolean containedBy(aabt aabt) {
        return false;
    }

    /**
     * Get the 3D coordinates of the tetrahedron vertices
     *
     * @return array of 4 vertices defining the tetrahedron
     */
    public Vector3d[] coordinates() {
        var tet = Tet.tetrahedron(index);
        var coords = tet.coordinates();
        var vertices = new Vector3d[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Vector3d(coords[i].x, coords[i].y, coords[i].z);
        }
        return vertices;
    }

    @Override
    public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                              float extentZ) {
        return false;
    }
}
