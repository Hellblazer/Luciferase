/**
 * Copyright (C) 2008 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Prime Mover Event Driven Simulation Framework.
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

package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.Tetree;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.sentry.Grid;
import com.hellblazer.sentry.MutableGrid;
import com.hellblazer.sentry.Vertex;

import javax.vecmath.Vector3d;
import java.util.logging.Logger;

/**
 * An event controller for a volume of space
 *
 * @author hal.hildebrand
 */
public class VolumeAnimator {
    private static final Logger log = Logger.getLogger(VolumeAnimator.class.getCanonicalName());

    private final RealTimeController controller;
    private final Tetree.Simplex     cell;
    private final Grid               grid;

    public VolumeAnimator(RealTimeController controller, Tetree.Simplex cell) {
        this.controller = controller;
        this.cell = cell;
        this.grid = new MutableGrid(vertices(cell.vertices()));
    }

    public static Vertex[] vertices(Vector3d[] vertices) {
        Vertex[] result = new Vertex[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            var vertex = vertices[i];
            result[i] = new Vertex((float) vertex.x, (float) vertex.y, (float) vertex.z);
        }
        return result;
    }
}
