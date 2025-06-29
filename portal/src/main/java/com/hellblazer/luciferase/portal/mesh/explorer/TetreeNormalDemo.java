/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.application.Application;
import javafx.scene.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

import javax.vecmath.Point3f;

/**
 * Demo to verify correct face normal orientation for tetrahedral meshes.
 * Shows a single tetrahedron with proper lighting to demonstrate normals.
 *
 * @author hal.hildebrand
 */
public class TetreeNormalDemo extends Application {

    private final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    private double mouseX, mouseY;
    private double mouseOldX, mouseOldY;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Tetrahedral Normal Verification");

        // Create 3D content
        Group root3D = create3DContent();

        // Create camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000.0);
        camera.setTranslateZ(-500);

        // Create 3D scene with proper lighting
        SubScene scene3D = new SubScene(root3D, 800, 600, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.DARKGRAY);
        scene3D.setCamera(camera);

        // Add ambient and point lights
        AmbientLight ambient = new AmbientLight(Color.color(0.3, 0.3, 0.3));
        PointLight pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(200);
        pointLight.setTranslateY(-200);
        pointLight.setTranslateZ(-200);
        root3D.getChildren().addAll(ambient, pointLight);

        // Create main scene
        Scene scene = new Scene(new Group(scene3D));

        // Add mouse controls
        setupMouseControls(scene);

        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private Group create3DContent() {
        Group world = new Group();

        // Apply rotations to world
        world.getTransforms().addAll(rotateX, rotateY);

        // Create a single large tetrahedron
        Point3f v0 = new Point3f(0, 0, 0);
        Point3f v1 = new Point3f(200, 0, 0);
        Point3f v2 = new Point3f(100, 173, 0);
        Point3f v3 = new Point3f(100, 58, 163);

        MeshView tetrahedron = createTetrahedronMesh(v0, v1, v2, v3, 
            Color.DODGERBLUE.deriveColor(0, 1, 1, 0.8));
        
        world.getChildren().add(tetrahedron);

        return world;
    }

    private MeshView createTetrahedronMesh(Point3f v0, Point3f v1, Point3f v2, Point3f v3, Color color) {
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices
        mesh.getPoints().addAll(
            v0.x, v0.y, v0.z,
            v1.x, v1.y, v1.z,
            v2.x, v2.y, v2.z,
            v3.x, v3.y, v3.z
        );

        // Add texture coordinates
        mesh.getTexCoords().addAll(
            0, 0,
            1, 0,
            0.5f, 1,
            0.5f, 0.5f
        );

        // Define faces with correct winding order for outward-facing normals
        // Using right-hand rule: when fingers curl in vertex order, thumb points outward
        mesh.getFaces().addAll(
            0, 0, 2, 2, 1, 1,  // Face 0-2-1 (base, viewed from below)
            0, 0, 1, 1, 3, 3,  // Face 0-1-3 (front right)
            0, 0, 3, 3, 2, 2,  // Face 0-3-2 (back left)
            1, 1, 2, 2, 3, 3   // Face 1-2-3 (top, viewed from above)
        );

        MeshView meshView = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        material.setSpecularPower(32);
        meshView.setMaterial(material);

        return meshView;
    }

    private void setupMouseControls(Scene scene) {
        scene.setOnMousePressed(event -> {
            mouseOldX = mouseX = event.getSceneX();
            mouseOldY = mouseY = event.getSceneY();
        });

        scene.setOnMouseDragged(event -> {
            mouseOldX = mouseX;
            mouseOldY = mouseY;
            mouseX = event.getSceneX();
            mouseY = event.getSceneY();

            double deltaX = mouseX - mouseOldX;
            double deltaY = mouseY - mouseOldY;

            if (event.isPrimaryButtonDown()) {
                rotateX.setAngle(rotateX.getAngle() - (deltaY * 0.5));
                rotateY.setAngle(rotateY.getAngle() + (deltaX * 0.5));
            }
        });

        scene.setOnScroll(event -> {
            double delta = event.getDeltaY();
            scene.getRoot().setTranslateZ(scene.getRoot().getTranslateZ() + delta);
        });
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {
        public static void main(String[] argv) {
            TetreeNormalDemo.main(argv);
        }
    }
}