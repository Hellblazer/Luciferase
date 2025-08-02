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
import javafx.application.Application;
import javafx.scene.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

import javax.vecmath.Point3f;

/**
 * Simple test to verify transform-based visualization works.
 */
public class TestTransformVisualization extends Application {

    @Override
    public void start(Stage primaryStage) {
        // Create tetree and add some entities
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        
        // Add entities at known positions
        float scale = 500000; // Middle of coordinate space
        tetree.insert(new Point3f(scale, scale, scale), (byte)5, "Entity 1");
        tetree.insert(new Point3f(scale + 100000, scale, scale), (byte)5, "Entity 2");
        tetree.insert(new Point3f(scale, scale + 100000, scale), (byte)5, "Entity 3");
        
        System.out.println("Created tetree with " + tetree.size() + " entities");
        System.out.println("Tetree has " + tetree.nodes().count() + " nodes");
        
        // Create visualization
        TransformBasedTetreeVisualization<LongEntityID, String> viz = new TransformBasedTetreeVisualization<>();
        viz.demonstrateUsage(tetree);
        
        System.out.println("Visualization root has " + viz.getSceneRoot().getChildren().size() + " children");
        
        // Setup scene
        Group root = new Group();
        
        // Add visualization with scale
        Group vizGroup = viz.getSceneRoot();
        vizGroup.setScaleX(0.001);
        vizGroup.setScaleY(0.001);
        vizGroup.setScaleZ(0.001);
        root.getChildren().add(vizGroup);
        
        // Add a reference box at origin for debugging
        Box originBox = new Box(50, 50, 50);
        originBox.setMaterial(new PhongMaterial(Color.WHITE));
        root.getChildren().add(originBox);
        
        // Add axes
        Box xAxis = new Box(1000, 10, 10);
        xAxis.setMaterial(new PhongMaterial(Color.RED));
        xAxis.setTranslateX(500);
        
        Box yAxis = new Box(10, 1000, 10);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));
        yAxis.setTranslateY(500);
        
        Box zAxis = new Box(10, 10, 1000);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));
        zAxis.setTranslateZ(500);
        
        root.getChildren().addAll(xAxis, yAxis, zAxis);
        
        // Setup camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        camera.setTranslateZ(-2000);
        
        // Rotation
        Rotate rotateX = new Rotate(-20, Rotate.X_AXIS);
        Rotate rotateY = new Rotate(-45, Rotate.Y_AXIS);
        root.getTransforms().addAll(rotateX, rotateY);
        
        // Create scene
        Scene scene = new Scene(root, 800, 600, true);
        scene.setFill(Color.DARKGRAY);
        scene.setCamera(camera);
        
        // Add lighting
        AmbientLight ambient = new AmbientLight(Color.WHITE.deriveColor(0, 1, 0.6, 1));
        PointLight point = new PointLight(Color.WHITE);
        point.setTranslateX(1000);
        point.setTranslateY(-1000);
        point.setTranslateZ(-1000);
        root.getChildren().addAll(ambient, point);
        
        primaryStage.setTitle("Transform Visualization Test");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}