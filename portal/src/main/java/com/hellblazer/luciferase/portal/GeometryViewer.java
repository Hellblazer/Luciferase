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

import static com.hellblazer.luciferase.portal.Colors.blackMaterial;

import com.hellblazer.luciferase.portal.CubicGrid.Neighborhood;
import com.hellblazer.luciferase.portal.mesh.polyhedra.archimedes.Cuboctahedron;

import javafx.scene.Group;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;

/**
 * @author hal.hildebrand
 */
public class GeometryViewer extends Abstract3DApp {

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            GeometryViewer.main(argv);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private Group view;

    @Override
    protected void build() {
        view = new CubicGrid(Neighborhood.SIX).construct(blackMaterial, blackMaterial, blackMaterial);
        transformingGroup.getChildren().add(view);
        world.getChildren().addAll(transformingGroup);

        MeshView v = new MeshView(new Cuboctahedron(2).dual().toTriangleMesh().constructMesh());
        v.setCullFace(CullFace.NONE);
        transformingGroup.getChildren().add(v);
    }

    @Override
    protected String title() {
        return "Geometry Viewer";
    }
}
