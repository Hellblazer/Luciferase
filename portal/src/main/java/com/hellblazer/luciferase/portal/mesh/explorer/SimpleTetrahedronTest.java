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
import javafx.scene.shape.Box;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import javax.vecmath.Point3f;

/**
 * Simplest possible test of tetrahedron rendering.
 */
public class SimpleTetrahedronTest extends Application {

    @Override
    public void start(Stage primaryStage) {
        Group root = new Group();
        
        // Create transform manager
        PrimitiveTransformManager manager = new PrimitiveTransformManager();
        
        // Create a simple box to verify scene works
        Box testBox = new Box(50, 50, 50);
        testBox.setMaterial(new PhongMaterial(Color.WHITE));
        testBox.setTranslateX(-100);
        root.getChildren().add(testBox);
        System.out.println("Added test box");
        
        // Create a large tetrahedron at origin
        PhongMaterial tetMaterial = new PhongMaterial(Color.RED);
        MeshView tet = manager.createTetrahedron(
            0, // Type 0
            new Point3f(0, 0, 0), // At origin
            100, // Large size
            tetMaterial
        );
        
        System.out.println("Created tetrahedron");
        root.getChildren().add(tet);
        
        // Add axes for reference
        Box xAxis = new Box(200, 5, 5);
        xAxis.setMaterial(new PhongMaterial(Color.RED));
        xAxis.setTranslateX(100);
        
        Box yAxis = new Box(5, 200, 5);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));
        yAxis.setTranslateY(100);
        
        Box zAxis = new Box(5, 5, 200);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));
        zAxis.setTranslateZ(100);
        
        root.getChildren().addAll(xAxis, yAxis, zAxis);
        
        // Setup camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        camera.setTranslateZ(-500);
        
        // Rotation
        root.getTransforms().addAll(
            new Rotate(-20, Rotate.X_AXIS),
            new Rotate(-45, Rotate.Y_AXIS)
        );
        
        // Create scene
        Scene scene = new Scene(root, 800, 600, true);
        scene.setFill(Color.DARKGRAY);
        scene.setCamera(camera);
        
        // Add lighting
        AmbientLight ambient = new AmbientLight(Color.WHITE.deriveColor(0, 1, 0.6, 1));
        PointLight point = new PointLight(Color.WHITE);
        point.setTranslateX(200);
        point.setTranslateY(-200);
        point.setTranslateZ(-200);
        root.getChildren().addAll(ambient, point);
        
        primaryStage.setTitle("Simple Tetrahedron Test");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.out.println("Scene children: " + root.getChildren().size());
    }

    public static void main(String[] args) {
        launch(args);
    }
}