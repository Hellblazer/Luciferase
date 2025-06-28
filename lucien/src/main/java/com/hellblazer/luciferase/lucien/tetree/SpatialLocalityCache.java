/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import javax.vecmath.Vector3f;

/**
 * Spatial locality cache that pre-computes neighborhoods to exploit spatial access patterns. When accessing one
 * tetrahedron, it's likely that nearby tetrahedra will be accessed soon, so we pre-compute and cache their TetreeKeys.
 *
 * @author hal.hildebrand
 */
public class SpatialLocalityCache {

    private final int localityRadius;

    /**
     * Create a spatial locality cache with the specified radius.
     *
     * @param localityRadius number of cells in each direction to pre-cache (e.g., 2 = 5x5x5 neighborhood)
     */
    public SpatialLocalityCache(int localityRadius) {
        this.localityRadius = localityRadius;
    }

    /**
     * Pre-cache neighborhoods for multiple tetrahedra. Useful for operations that will access several regions.
     *
     * @param centers array of center tetrahedra
     */
    public void preCacheMultipleNeighborhoods(Tet[] centers) {
        for (var center : centers) {
            preCacheNeighborhood(center);
        }
    }

    /**
     * Pre-cache the neighborhood around a center tetrahedron. This improves performance for operations that access
     * nearby tetrahedra.
     *
     * @param center the center tetrahedron
     */
    public void preCacheNeighborhood(Tet center) {
        var cellSize = center.length();
        var cacheCount = 0;

        // Pre-cache a cube of tetrahedra around the center
        for (int dx = -localityRadius; dx <= localityRadius; dx++) {
            for (int dy = -localityRadius; dy <= localityRadius; dy++) {
                for (int dz = -localityRadius; dz <= localityRadius; dz++) {
                    var x = center.x() + dx * cellSize;
                    var y = center.y() + dy * cellSize;
                    var z = center.z() + dz * cellSize;

                    // Skip invalid coordinates
                    if (x < 0 || y < 0 || z < 0) {
                        continue;
                    }

                    // Pre-compute all 6 tetrahedron types at this position
                    for (byte type = 0; type < 6; type++) {
                        var tet = new Tet(x, y, z, center.l(), type);
                        // This will compute and cache the TetreeKey
                        tet.tmIndex();
                        cacheCount++;
                    }
                }
            }
        }
    }

    /**
     * Pre-cache along a ray path for ray traversal operations. This anticipates the tetrahedra that will be visited
     * during ray marching.
     *
     * @param start     starting tetrahedron
     * @param direction ray direction (normalized)
     * @param distance  maximum distance to pre-cache
     */
    public void preCacheRayPath(Tet start, Vector3f direction, float distance) {
        var cellSize = start.length();
        var steps = (int) (distance / cellSize);

        for (int i = 0; i <= steps; i++) {
            // Calculate position along ray
            var t = i * cellSize;
            var x = (int) (start.x() + direction.x * t);
            var y = (int) (start.y() + direction.y * t);
            var z = (int) (start.z() + direction.z * t);

            // Align to grid
            x = (x / cellSize) * cellSize;
            y = (y / cellSize) * cellSize;
            z = (z / cellSize) * cellSize;

            if (x >= 0 && y >= 0 && z >= 0) {
                // Pre-cache this position and immediate neighbors
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            var nx = x + dx * cellSize;
                            var ny = y + dy * cellSize;
                            var nz = z + dz * cellSize;

                            if (nx >= 0 && ny >= 0 && nz >= 0) {
                                for (byte type = 0; type < 6; type++) {
                                    new Tet(nx, ny, nz, start.l(), type).tmIndex();
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
