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
package com.hellblazer.luciferase.portal.mesh.explorer;

import javax.vecmath.Point3i;

import com.hellblazer.luciferase.portal.CubicGrid;
import com.hellblazer.luciferase.portal.CubicGrid.Neighborhood;
import com.hellblazer.luciferase.portal.IvmGrid;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;

import javafx.scene.Group;

/**
 * @author hal.hildebrand
 */
public class IvmGridViewer extends Abstract3DApp {

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            IvmGridViewer.main(argv);
        }
    }

    public static final double CUBE_EDGE_LENGTH = Math.sqrt(2) / 2;
    public static final double TET_EDGE_LENGTH  = 1;

    public static void main(String[] args) {
        launch(args);
    }

    private final Group view = new Group();

    @Override
    protected Group build() {
        final var cube = new Cube(TET_EDGE_LENGTH);
        final var cubic = new CubicGrid(Neighborhood.SIX, cube, 2);
        view.getChildren().add(cubic.construct(Colors.blackMaterial, Colors.blackMaterial, Colors.blackMaterial));
        IvmGrid g = new IvmGrid(new Point3i(5, 5, 5), false);
        view.getChildren().add(g.populate(Colors.redMaterial, TET_EDGE_LENGTH / 2, cubic));

        IvmGrid inverse = new IvmGrid(new Point3i(5, 5, 5), true);
        view.getChildren().add(inverse.populate(Colors.blueMaterial, TET_EDGE_LENGTH / 2, cubic));
        return view;
    }

    @Override
    protected String title() {
        return "IVM Viewer";
    }
}
