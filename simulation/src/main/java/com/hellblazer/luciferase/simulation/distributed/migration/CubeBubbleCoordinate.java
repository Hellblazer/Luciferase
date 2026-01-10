/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import java.util.Objects;

/**
 * 3D cube grid position for bubble placement in tetrahedral decomposition (Phase 7E Day 2)
 *
 * Represents a position in a 3D grid of cubic cells, each 1.0×1.0×1.0.
 * Used for 2x2x2 tetrahedral decomposition topology where:
 * - Base domain: [0, 2.0] × [0, 2.0] × [0, 2.0]
 * - Cube coordinates: {0, 1} in each dimension
 * - Total bubbles: 8 primary + optional replicas
 *
 * ADJACENCY:
 * - Face-adjacent only (6 neighbors per cube in interior)
 * - Corners and edges have fewer neighbors
 *
 * TOPOLOGY:
 * ```
 * Z=0 plane:       Z=1 plane:
 *  Cube(0,1,0) ... Cube(0,1,1) ...
 *    ↑ ↕ →          ↑ ↕ →
 *  Cube(0,0,0) ... Cube(0,0,1) ...
 *
 * Similarly for X=1 plane (Y=0,1)
 * ```
 *
 * @param x X coordinate (grid cell, typically 0-1 for 2x2x2)
 * @param y Y coordinate (grid cell, typically 0-1 for 2x2x2)
 * @param z Z coordinate (grid cell, typically 0-1 for 2x2x2)
 *
 * @author hal.hildebrand
 */
public record CubeBubbleCoordinate(int x, int y, int z) {

    /**
     * Validate that coordinates are non-negative.
     */
    public CubeBubbleCoordinate {
        if (x < 0) {
            throw new IllegalArgumentException("X must be non-negative: " + x);
        }
        if (y < 0) {
            throw new IllegalArgumentException("Y must be non-negative: " + y);
        }
        if (z < 0) {
            throw new IllegalArgumentException("Z must be non-negative: " + z);
        }
    }

    /**
     * Get the world-space origin of this cube.
     * For 1.0×1.0×1.0 cubes, origin = (x × 1.0, y × 1.0, z × 1.0)
     *
     * @return Array [x_origin, y_origin, z_origin]
     */
    public float[] getOrigin() {
        return new float[]{x * 1.0f, y * 1.0f, z * 1.0f};
    }

    /**
     * Get the world-space bounds of this cube.
     * For 1.0×1.0×1.0 cubes from origin to origin + 1.0
     *
     * @return Array [x_min, y_min, z_min, x_max, y_max, z_max]
     */
    public float[] getBounds() {
        float x_min = x * 1.0f;
        float y_min = y * 1.0f;
        float z_min = z * 1.0f;
        float x_max = x_min + 1.0f;
        float y_max = y_min + 1.0f;
        float z_max = z_min + 1.0f;
        return new float[]{x_min, y_min, z_min, x_max, y_max, z_max};
    }

    /**
     * Check if a world position is within this cube's bounds (with tolerance).
     * Used for determining entity ownership.
     *
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @param tolerance Tolerance for boundary (typically 0.05)
     * @return true if position is inside this cube (within tolerance)
     */
    public boolean contains(float worldX, float worldY, float worldZ, float tolerance) {
        float x_min = x * 1.0f - tolerance;
        float y_min = y * 1.0f - tolerance;
        float z_min = z * 1.0f - tolerance;
        float x_max = x_min + 1.0f + tolerance;
        float y_max = y_min + 1.0f + tolerance;
        float z_max = z_min + 1.0f + tolerance;

        return worldX >= x_min && worldX <= x_max &&
               worldY >= y_min && worldY <= y_max &&
               worldZ >= z_min && worldZ <= z_max;
    }

    /**
     * Get the Manhattan distance to another coordinate.
     * Used for finding neighbors without diagonals.
     *
     * @param other The other coordinate
     * @return Manhattan distance (|x1-x2| + |y1-y2| + |z1-z2|)
     */
    public int manhattanDistance(CubeBubbleCoordinate other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    /**
     * Check if this coordinate is face-adjacent to another (no diagonals).
     * Face-adjacent means they differ by 1 in exactly one dimension.
     *
     * @param other The other coordinate
     * @return true if face-adjacent
     */
    public boolean isFaceAdjacentTo(CubeBubbleCoordinate other) {
        int dx = Math.abs(x - other.x);
        int dy = Math.abs(y - other.y);
        int dz = Math.abs(z - other.z);
        return (dx + dy + dz) == 1;  // Exactly one dimension differs by 1
    }

    /**
     * Get all 6 face-adjacent neighbors (no diagonals) that exist in a given grid.
     *
     * @param maxX Maximum X coordinate in grid (exclusive)
     * @param maxY Maximum Y coordinate in grid (exclusive)
     * @param maxZ Maximum Z coordinate in grid (exclusive)
     * @return Array of valid neighbor coordinates
     */
    public CubeBubbleCoordinate[] getFaceNeighbors(int maxX, int maxY, int maxZ) {
        var neighbors = new java.util.ArrayList<CubeBubbleCoordinate>();

        // Check all 6 face directions
        int[][] directions = {
            {1, 0, 0}, {-1, 0, 0},  // X directions
            {0, 1, 0}, {0, -1, 0},  // Y directions
            {0, 0, 1}, {0, 0, -1}   // Z directions
        };

        for (var dir : directions) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            int nz = z + dir[2];

            if (nx >= 0 && nx < maxX && ny >= 0 && ny < maxY && nz >= 0 && nz < maxZ) {
                neighbors.add(new CubeBubbleCoordinate(nx, ny, nz));
            }
        }

        return neighbors.toArray(new CubeBubbleCoordinate[0]);
    }

    /**
     * Convert to linear index for 2x2x2 grid (suitable for bubble ID mapping).
     * Maps (x,y,z) to index: z*4 + y*2 + x
     *
     * @return Linear index (0-7 for 2x2x2 grid)
     */
    public int toLinearIndex() {
        return z * 4 + y * 2 + x;
    }

    /**
     * Create from linear index for 2x2x2 grid.
     *
     * @param index Linear index (0-7 for 2x2x2)
     * @return CubeBubbleCoordinate
     * @throws IllegalArgumentException if index < 0 or > 7
     */
    public static CubeBubbleCoordinate fromLinearIndex(int index) {
        if (index < 0 || index > 7) {
            throw new IllegalArgumentException("Index must be 0-7 for 2x2x2 grid: " + index);
        }
        int z = (index >> 2) & 1;
        int y = (index >> 1) & 1;
        int x = index & 1;
        return new CubeBubbleCoordinate(x, y, z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
