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

import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis;
import com.hellblazer.luciferase.portal.CubicGrid.Neighborhood;
import com.hellblazer.luciferase.portal.mesh.explorer.Abstract3DApp;
import com.hellblazer.luciferase.portal.mesh.explorer.Colors;
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
        final var cubic = new CubicGrid(Neighborhood.EIGHT, new Cube(CUBE_EDGE_LENGTH), 1);
        cubic.addAxes(view, 0.1, 0.2, 0.008, 20);
        Polyhedron polyhedron = new Cuboctahedron(TET_EDGE_LENGTH);
        var dual = polyhedron.dual();
        var dualEdges = dual.getEdges();

        addEdges(dualEdges, Colors.redMaterial, view);
        animus = new Animus<Node>(view);

        animus.getOrientation().set(PrincipalAxis.Z.rotation(0.5f));
        var p = new Vector3f();
        p.z = p.z - 2;
        animus.getPosition().set(p);

        return animus.getAnimated();
    }

    @Override
    protected Group build() {
        var g = new Group();
        g.getChildren().add(animus());
        final var cubic = new CubicGrid(Neighborhood.EIGHT, new Cube(CUBE_EDGE_LENGTH), 1);
        cubic.addAxes(g, 0.1, 0.2, 0.008, 20);
        return g;
    }

    @Override
    protected String title() {
        return "Test Viewer";
    }

}
