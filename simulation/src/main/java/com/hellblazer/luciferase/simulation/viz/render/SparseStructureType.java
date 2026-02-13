/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

/**
 * Type of sparse voxel structure to build.
 *
 * @author hal.hildebrand
 */
public enum SparseStructureType {
    /**
     * Efficient Sparse Voxel Octree (cubic subdivision).
     */
    ESVO,

    /**
     * Efficient Sparse Voxel Tetree (tetrahedral subdivision).
     */
    ESVT
}
