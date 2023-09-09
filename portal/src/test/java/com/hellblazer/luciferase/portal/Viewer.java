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
package com.hellblazer.luciferase.portal;

import static com.hellblazer.luciferase.portal.TestPortal.CUBE_EDGE_LENGTH;
import static com.hellblazer.luciferase.portal.TestPortal.TET_EDGE_LENGTH;
import static com.hellblazer.luciferase.portal.TestPortal.addEdges;

import com.hellblazer.luciferase.portal.CubicGrid.Neighborhood;
import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.archimedes.Cuboctahedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;

import javafx.scene.Group;
import javafx.scene.Node;

/**
 * 
 */
public class Viewer extends Abstract3DApp {

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            Viewer.main(argv);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private Animus<Node> animus;

    protected Node animus() {
        var view = new Group();
        view.getChildren()
            .add(new CubicGrid(Neighborhood.EIGHT, new Cube(CUBE_EDGE_LENGTH), 1).construct(Colors.blackMaterial,
                                                                                            Colors.blackMaterial,
                                                                                            Colors.blackMaterial,
                                                                                            true));
        Polyhedron polyhedron = new Cuboctahedron(TET_EDGE_LENGTH);
        var dual = polyhedron.dual();
        var dualEdges = dual.getEdges();

        addEdges(dualEdges, Colors.redMaterial, view);
        animus = new Animus<Node>(view);
        return animus.getAnimated();
    }

    @Override
    protected Group build() {
        var g = new Group();
        g.getChildren().add(animus());
        return g;
    }

    @Override
    protected String title() {
        // TODO Auto-generated method stub
        return null;
    }

}
