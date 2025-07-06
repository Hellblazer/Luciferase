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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
// KuhnTetree import removed - using base Tetree with positive volume correction
import javafx.application.Application;
import javafx.scene.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

import javax.vecmath.Point3f;

/**
 * Simple test to visualize a single tetrahedron
 *
 * @author hal.hildebrand
 */
public class SingleTetVisualizationTest extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // Create tetree and visualization - now with built-in positive volume correction
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        TransformBasedTetreeVisualization<LongEntityID, String> viz = new TransformBasedTetreeVisualization<>();

        // Insert a single entity at a reasonable position
        tetree.insert(new Point3f(0, 0, 0), (byte) 10, "Test entity at origin");

        // Demonstrate usage which adds all tetrahedra from the tetree
        viz.demonstrateUsage(tetree);

        // Create scene
        Group root = viz.getSceneRoot();

        // Add rotation for better view
        Rotate rotateX = new Rotate(30, Rotate.X_AXIS);
        Rotate rotateY = new Rotate(30, Rotate.Y_AXIS);
        root.getTransforms().addAll(rotateX, rotateY);

        SubScene scene3D = new SubScene(root, 800, 600, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.SKYBLUE);

        // Setup camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-5000);  // Pull back to see the tetrahedron
        scene3D.setCamera(camera);

        // Add light
        PointLight light = new PointLight(Color.WHITE);
        light.setTranslateX(1000);
        light.setTranslateY(-1000);
        light.setTranslateZ(-1000);
        root.getChildren().add(light);

        AmbientLight ambient = new AmbientLight(Color.WHITE.deriveColor(0, 1, 0.5, 1));
        root.getChildren().add(ambient);

        Scene scene = new Scene(new Group(scene3D));
        primaryStage.setTitle("Single Tet Visualization Test");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Print debug info
        System.out.println("\n=== Single Tet Visualization Test ===");
        System.out.println("Tetree nodes: " + tetree.nodeCount());
        System.out.println("Scene root children: " + root.getChildren().size());

        // Check what was created
        Group sceneRoot = viz.getSceneRoot();
        if (!sceneRoot.getChildren().isEmpty()) {
            MeshView mesh = (MeshView) sceneRoot.getChildren().get(0);
            System.out.println("Mesh bounds: " + mesh.getBoundsInParent());
            System.out.println("Mesh transforms: " + mesh.getTransforms());
        }
    }
}
