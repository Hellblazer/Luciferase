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

import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.primeMover.Controller;
import com.hellblazer.primeMover.runtime.Kairos;
import com.hellblazer.sentry.Grid;
import com.hellblazer.sentry.MutableGrid;
import com.hellblazer.sentry.Vertex;

import java.util.logging.Logger;

/**
 * An event controller for a volume of space
 *
 * @author hal.hildebrand
 */
public class VolumeAnimator {
    private static final Logger log = Logger.getLogger(VolumeAnimator.class.getCanonicalName());

    private final Tet        cell;
    private final Grid       grid;
    private final Controller controller;

    public VolumeAnimator(Controller controller, Tet cell) {
        this.controller = controller;
        this.cell = cell;
        this.grid = new MutableGrid(Vertex.vertices(cell.vertices()));
        Kairos.setController(controller);
    }
}
