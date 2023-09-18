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

import com.hellblazer.luciferase.portal.RDG;
import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.archimedes.RhombicDodecahedron;

import javafx.scene.Group;
import javafx.scene.paint.Material;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Transform;

/**
 * @author hal.hildebrand
 */
public class RDGridViewer extends Abstract3DApp {

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            RDGridViewer.main(argv);
        }
    }

    public static final double CUBE_EDGE_LENGTH = Math.sqrt(2) / 2;
    public static final double TET_EDGE_LENGTH  = Math.sqrt(2);

    public static void main(String[] args) {
        launch(args);
    }

    private final Group view = new Group();

    public Group neighbors(Point3i cell, Material material, double radius, RDG rdg) {
        var group = new Group();
        final var triangleMesh = RhombicDodecahedron.createRhombicDodecahedron(TET_EDGE_LENGTH);
        for (var location : RDG.faceConnectedNeighbors(cell)) {
            Transform position = rdg.postitionTransform(location.x, location.y, location.z);

            var polyhedron = new MeshView(triangleMesh);
            polyhedron.setMaterial(material);
            polyhedron.setCullFace(CullFace.BACK);
            polyhedron.getTransforms().clear();
            polyhedron.getTransforms().addAll(position);
            group.getChildren().add(polyhedron);
        }
        return group;
    }

    public Group populate(Material material, double radius, RDG rdg) {
        var group = new Group();
        final var triangleMesh = RhombicDodecahedron.createRhombicDodecahedron(TET_EDGE_LENGTH);
        rdg.forEach(location -> {
            Transform position = rdg.postitionTransform(location.x, location.y, location.z);

            var polyhedron = new MeshView(triangleMesh);
            polyhedron.setMaterial(material);
            polyhedron.setCullFace(CullFace.BACK);
            polyhedron.getTransforms().clear();
            polyhedron.getTransforms().addAll(position);
            group.getChildren().add(polyhedron);
        });
        return group;
    }

    public Group vertexNeighbors(Point3i cell, Material material, double radius, RDG rdg) {
        var group = new Group();
        final var triangleMesh = RhombicDodecahedron.createRhombicDodecahedron(TET_EDGE_LENGTH);
        for (var location : RDG.vertexConnectedNeighbors(cell)) {
            Transform position = rdg.postitionTransform(location.x, location.y, location.z);
            var polyhedron = new MeshView(triangleMesh);
            polyhedron.setMaterial(material);
            polyhedron.setCullFace(CullFace.BACK);
            polyhedron.getTransforms().clear();
            polyhedron.getTransforms().addAll(position);
            group.getChildren().add(polyhedron);
        }
        return group;
    }

    protected void add(final Polyhedron polyhedron) {
        MeshView v = new MeshView(polyhedron.toTriangleMesh().constructMesh());
        v.setMaterial(Colors.cyanMaterial);
        v.setCullFace(CullFace.NONE);
        view.getChildren().add(v);
    }

    @Override
    protected Group build() {

        final var rdg = new RDG(CUBE_EDGE_LENGTH, 1);
        rdg.addAxes(view, 0.1f, 0.2f, 0.008f, 20);
//        final var children = view.getChildren();
//
//        final var radius = TET_EDGE_LENGTH / 2;
//        children.add(populate(Colors.redMaterial, radius, rdg));
//        final var cell = new Point3i();
//        children.add(neighbors(cell, Colors.blueMaterial, radius, rdg));
//
//        final var triangleMesh = RhombicDodecahedron.createRhombicDodecahedron(TET_EDGE_LENGTH);
//        Transform p = rdg.postitionTransform(cell.x, cell.y, cell.z);
//
//        var polyhedron = new MeshView(triangleMesh);
//        polyhedron.setMaterial(Colors.greenMaterial);
//        polyhedron.setCullFace(CullFace.BACK);
//        polyhedron.getTransforms().clear();
//        polyhedron.getTransforms().addAll(p);
//        view.getChildren().add(polyhedron);
        return view;
    }

    @Override
    protected String title() {
        return "RDG Viewer";
    }
}
