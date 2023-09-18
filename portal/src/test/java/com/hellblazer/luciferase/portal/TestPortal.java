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

import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.X;
import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.Y;
import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.Z;

import java.util.Set;

import com.hellblazer.luciferase.portal.CubicGrid.Neighborhood;
import com.hellblazer.luciferase.portal.mesh.Edge;
import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.explorer.Colors;
import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.archimedes.Cuboctahedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Material;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;

/**
 * @author hal.hildebrand
 */
public class TestPortal extends MagicMirror {

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            TestPortal.main(argv);
        }
    }

    public static void add(final Polyhedron polyhedron, Group view) {
        MeshView v = new MeshView(polyhedron.toTriangleMesh().constructMesh());
        v.setMaterial(Colors.cyanMaterial);
        v.setCullFace(CullFace.NONE);
        view.getChildren().add(v);
    }

    public static void addEdges(Set<Edge> edges, Material material, Group view) {
        final var children = view.getChildren();
        for (var e : edges) {
            var segment = e.getSegment();
            final var line = new Line(0.01, segment[0], segment[1]);
            line.setMaterial(material);
            children.addAll(line);
        }
    }

    public static void addSpheres(Set<Edge> edges, Group view) {
        final var children = view.getChildren();
        for (var e : edges) {
            children.addAll(e.getEndpointSpheres(e.length() / 2, Colors.blueMaterial));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    protected Node content() {
        OrientedTxfm txfm = new OrientedTxfm();
        txfm.translation().setZ(2);
        txfm.orientation().set(Z.angle(45).combine(Y.angle(45)).combine(X.angle(45)));
        var view = new OrientedGroup(txfm);

        final var cubic = new CubicGrid(Neighborhood.EIGHT, new Cube(CUBE_EDGE_LENGTH), 1);
        cubic.addAxes(view, 0.1f, 0.2f, 0.008f, 20);
        Polyhedron polyhedron = new Cuboctahedron(TET_EDGE_LENGTH);
        var dual = polyhedron.dual();
        var dualEdges = dual.getEdges();
        addEdges(dualEdges, Colors.redMaterial, view);
        return view;
    }

    @Override
    protected String title() {
        return "Test Portal";
    }
}
