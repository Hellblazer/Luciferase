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
import javafx.scene.shape.Sphere;
import javafx.stage.Stage;

/**
 * Simple test for axis visibility.
 *
 * @author hal.hildebrand
 */
public class AxisTest extends Application {
    
    @Override
    public void start(Stage primaryStage) {
        Group root = new Group();
        
        // Add reference sphere at origin
        Sphere origin = new Sphere(10);
        origin.setMaterial(new PhongMaterial(Color.WHITE));
        root.getChildren().add(origin);
        
        // Add axis lines
        double axisLength = 300;
        double axisThickness = 5;
        
        // X axis (red)
        Box xAxis = new Box(axisLength, axisThickness, axisThickness);
        xAxis.setMaterial(new PhongMaterial(Color.RED));
        root.getChildren().add(xAxis);
        
        // Y axis (green)
        Box yAxis = new Box(axisThickness, axisLength, axisThickness);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));
        root.getChildren().add(yAxis);
        
        // Z axis (blue)
        Box zAxis = new Box(axisThickness, axisThickness, axisLength);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));
        root.getChildren().add(zAxis);
        
        // Add axis labels
        addAxisLabel(root, "X", axisLength/2 + 20, 0, 0, Color.RED);
        addAxisLabel(root, "Y", 0, axisLength/2 + 20, 0, Color.GREEN);
        addAxisLabel(root, "Z", 0, 0, axisLength/2 + 20, Color.BLUE);
        
        // Create scene
        Scene scene = new Scene(root, 800, 600, true);
        scene.setFill(Color.DARKGRAY);
        
        // Create camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-500);
        camera.setNearClip(0.1);
        camera.setFarClip(10000.0);
        scene.setCamera(camera);
        
        // Add lighting
        AmbientLight ambientLight = new AmbientLight(Color.WHITE.deriveColor(0, 1, 0.5, 1));
        root.getChildren().add(ambientLight);
        
        // Make scene interactive
        scene.setOnMousePressed(event -> {
            System.out.println("Mouse clicked at: " + event.getSceneX() + ", " + event.getSceneY());
        });
        
        // Configure stage
        primaryStage.setTitle("Axis Test - You should see colored axes");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.out.println("Axis Test running - you should see:");
        System.out.println("- White sphere at origin");
        System.out.println("- Red X axis");
        System.out.println("- Green Y axis");
        System.out.println("- Blue Z axis");
        System.out.println("Root has " + root.getChildren().size() + " children");
    }
    
    private void addAxisLabel(Group root, String text, double x, double y, double z, Color color) {
        Sphere label = new Sphere(8);
        label.setMaterial(new PhongMaterial(color));
        label.setTranslateX(x);
        label.setTranslateY(y);
        label.setTranslateZ(z);
        root.getChildren().add(label);
    }
    
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(AxisTest.class, args);
        }
    }
}