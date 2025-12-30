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
package com.hellblazer.luciferase.portal.inspector;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.render.inspector.SpatialData;

import java.util.List;

/**
 * Generic bridge interface for building spatial data structures from voxels.
 *
 * Implementations handle the construction of specific spatial structures
 * (octrees, tetrahedra, etc.) from voxel coordinate lists.
 *
 * @param <D> The spatial data type produced by this bridge
 * @author hal.hildebrand
 */
public interface SpatialBridge<D extends SpatialData> {

    /**
     * Build a spatial data structure from voxel coordinates.
     *
     * @param voxels List of voxel coordinates (x, y, z)
     * @param maxDepth Maximum depth of the tree structure
     * @param gridResolution Resolution of the voxel grid
     * @return Build result containing the spatial data
     */
    BuildResult<D> buildFromVoxels(List<Point3i> voxels, int maxDepth, int gridResolution);

    /**
     * Get the name of the spatial structure type (e.g., "Octree", "ESVT").
     * @return structure type name
     */
    String getStructureTypeName();

    /**
     * Result of a build operation, containing the data and build statistics.
     *
     * @param <D> The spatial data type
     */
    record BuildResult<D extends SpatialData>(
        D data,
        long buildTimeMs,
        int voxelCount,
        String message
    ) {
        /**
         * Get the spatial data from this result.
         * Convenience method matching existing ESVOBridge API.
         */
        public D getData() {
            return data;
        }

        /**
         * Check if the build was successful (data is not null).
         */
        public boolean isSuccess() {
            return data != null;
        }
    }
}
