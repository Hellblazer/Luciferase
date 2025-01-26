/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.portal.mesh.Edge;
import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.ThreeOrthoscheme;
import javafx.scene.Group;
import javafx.scene.paint.Material;
import javafx.scene.shape.MeshView;

import java.util.Set;

/**
 * @author hal.hildebrand
 */
public class GeometryViewer extends Abstract3DApp {

    public static final double CUBE_EDGE_LENGTH = Math.sqrt(2) / 2;
    public static final double TET_EDGE_LENGTH  = 1;
    private final       Group  view             = new Group();

    public static void main(String[] args) {
        launch(args);
    }

    protected void add(final Polyhedron polyhedron, Material material) {
        MeshView v = new MeshView(polyhedron.toTriangleMesh().constructMesh());
        v.setMaterial(material);
        view.getChildren().add(v);
        for (Edge edge : polyhedron.getEdges()) {
            var line = new Line(0.01, edge.getSegment()[0], edge.getSegment()[1]);
            line.setMaterial(Colors.blackMaterial);
            view.getChildren().add(line);
        }
    }

    protected void addEdges(Set<Edge> edges, Material material) {
        final var children = view.getChildren();
        for (var e : edges) {
            var segment = e.getSegment();
            final var line = new Line(0.01, segment[0], segment[1]);
            line.setMaterial(material);
            children.addAll(line);
        }
    }

    protected void addSpheres(Set<Edge> edges) {
        final var children = view.getChildren();
        for (var e : edges) {
            children.addAll(e.getEndpointSpheres(e.length() / 2, Colors.blueMaterial));
        }
    }

    @Override
    protected Group build() {
        add(new ThreeOrthoscheme(0, 2), Colors.blueMaterial);
        add(new ThreeOrthoscheme(1, 2), Colors.redMaterial);
        add(new ThreeOrthoscheme(2, 2), Colors.blueMaterial);
        add(new ThreeOrthoscheme(3, 2), Colors.redMaterial);
        add(new ThreeOrthoscheme(4, 2), Colors.blueMaterial);
        add(new ThreeOrthoscheme(5, 2), Colors.redMaterial);
        return view;
    }

    @Override
    protected String title() {
        return "Geometry Viewer";
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            GeometryViewer.main(argv);
        }
    }
}
