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
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

/**
 * Simple demo showing the foundation for spatial index visualization. This demo creates a basic 3D scene with sample
 * octree nodes and entities.
 *
 * @author hal.hildebrand
 */
public class SpatialIndexDemo extends Application {
    private static final double            SCENE_WIDTH     = 800;
    private static final double            SCENE_HEIGHT    = 600;
    private static final double            CAMERA_DISTANCE = 1000;
    private final        Rotate            rotateX         = new Rotate(0, Rotate.X_AXIS);
    private final        Rotate            rotateY         = new Rotate(0, Rotate.Y_AXIS);
    private              Group             root3D;
    private              SubScene          scene3D;
    private              PerspectiveCamera camera;
    // Mouse controls
    private              double            mouseX, mouseY;
    private double mouseOldX, mouseOldY;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Spatial Index Visualization Foundation");

        // Create 3D content
        root3D = create3DContent();

        // Create camera
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000.0);
        camera.setTranslateZ(-CAMERA_DISTANCE);

        // Create 3D subscene
        scene3D = new SubScene(root3D, SCENE_WIDTH, SCENE_HEIGHT, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.DARKGRAY);
        scene3D.setCamera(camera);

        // Main layout
        BorderPane mainLayout = new BorderPane();
        mainLayout.setCenter(scene3D);

        // Info panel
        Label infoLabel = new Label(
        "Spatial Index Visualization Demo\n" + "- Blue wireframes: Octree nodes at different levels\n"
        + "- Green spheres: Entities in the spatial index\n" + "- Drag mouse to rotate view\n" + "- Scroll to zoom");
        infoLabel.setStyle("-fx-padding: 10; -fx-background-color: rgba(255,255,255,0.9);");
        mainLayout.setTop(infoLabel);

        // Create main scene
        Scene scene = new Scene(mainLayout);

        // Add mouse controls
        setupMouseControls(scene);

        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private Group create3DContent() {
        Group world = new Group();

        // Add coordinate axes
        world.getChildren().add(createAxes());

        // Create sample octree visualization
        world.getChildren().add(createSampleOctreeNodes());

        // Create sample entities
        world.getChildren().add(createSampleEntities());

        // Apply rotations to world
        world.getTransforms().addAll(rotateX, rotateY);

        return world;
    }

    private Group createAxes() {
        Group axes = new Group();

        // X axis - Red
        Box xAxis = new Box(200, 1, 1);
        xAxis.setMaterial(new PhongMaterial(Color.RED));
        xAxis.setTranslateX(100);

        // Y axis - Green  
        Box yAxis = new Box(1, 200, 1);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));
        yAxis.setTranslateY(100);

        // Z axis - Blue
        Box zAxis = new Box(1, 1, 200);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));
        zAxis.setTranslateZ(100);

        axes.getChildren().addAll(xAxis, yAxis, zAxis);
        return axes;
    }

    private Group createSampleEntities() {
        Group entities = new Group();

        // Create some sample entity positions
        double[][] positions = { { -120, -120, -120 }, { -80, -100, -140 }, { 60, 70, 80 }, { 40, 90, 60 },
                                 { 160, 140, 170 }, { 140, 160, 150 }, { 0, 0, 0 } };

        // Create spheres for entities
        for (double[] pos : positions) {
            Sphere entity = new Sphere(5);
            entity.setTranslateX(pos[0]);
            entity.setTranslateY(pos[1]);
            entity.setTranslateZ(pos[2]);

            PhongMaterial material = new PhongMaterial(Color.LIME);
            material.setSpecularColor(Color.LIGHTGREEN);
            entity.setMaterial(material);

            entities.getChildren().add(entity);
        }

        return entities;
    }

    private Group createSampleOctreeNodes() {
        Group nodes = new Group();

        // Level 0 - Root node (large)
        nodes.getChildren().add(createWireframeCube(0, 0, 0, 400, Color.BLUE.deriveColor(0, 1, 1, 0.3)));

        // Level 1 - Some child nodes (medium)
        nodes.getChildren().add(createWireframeCube(-100, -100, -100, 200, Color.CYAN.deriveColor(0, 1, 1, 0.3)));
        nodes.getChildren().add(createWireframeCube(100, 100, 100, 200, Color.CYAN.deriveColor(0, 1, 1, 0.3)));

        // Level 2 - Smaller nodes
        nodes.getChildren().add(createWireframeCube(-150, -150, -150, 100, Color.LIGHTBLUE.deriveColor(0, 1, 1, 0.3)));
        nodes.getChildren().add(createWireframeCube(50, 50, 50, 100, Color.LIGHTBLUE.deriveColor(0, 1, 1, 0.3)));
        nodes.getChildren().add(createWireframeCube(150, 150, 150, 100, Color.LIGHTBLUE.deriveColor(0, 1, 1, 0.3)));

        return nodes;
    }

    private Group createWireframeCube(double x, double y, double z, double size, Color color) {
        Group cube = new Group();

        // Create transparent box for the face
        Box box = new Box(size, size, size);
        box.setTranslateX(x);
        box.setTranslateY(y);
        box.setTranslateZ(z);

        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        box.setMaterial(material);
        box.setOpacity(0.2);

        // Note: Full wireframe edges would be added here using Line class
        // For now, just showing the transparent cube face

        cube.getChildren().add(box);
        return cube;
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
                rotateX.setAngle(rotateX.getAngle() - (deltaY * 0.2));
                rotateY.setAngle(rotateY.getAngle() + (deltaX * 0.2));
            }
        });

        scene.setOnScroll(event -> {
            double delta = event.getDeltaY();
            camera.setTranslateZ(camera.getTranslateZ() + delta);
        });
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            SpatialIndexDemo.main(argv);
        }
    }
}
