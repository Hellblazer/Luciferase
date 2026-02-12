/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

/**
 * Enumeration of spatial region shapes in the forest.
 *
 * This enum is used in forest events to indicate the geometric shape
 * of spatial regions, enabling downstream systems (like Tumbler) to
 * make shape-aware decisions.
 *
 * @author hal.hildebrand
 */
public enum RegionShape {
    /**
     * Axis-aligned bounding box (AABB) / cubic region.
     * Used for octree-based trees and root cubic regions.
     */
    CUBIC,

    /**
     * Tetrahedral region.
     * Used for tetree-based trees after tetrahedral subdivision.
     */
    TETRAHEDRAL
}
