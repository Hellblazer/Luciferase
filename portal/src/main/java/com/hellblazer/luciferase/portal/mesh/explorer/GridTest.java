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

import com.hellblazer.luciferase.portal.mesh.explorer.grid.AdaptiveGrid;
import javafx.application.Application;
import javafx.scene.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.stage.Stage;

/**
 * Simple test for AdaptiveGrid visibility.
 *
 * @author hal.hildebrand
 */
public class GridTest extends Application {
    
    @Override
    public void start(Stage primaryStage) {
        Group root = new Group();
        
        // Create grid
        ScalingStrategy scalingStrategy = new ScalingStrategy();
        AdaptiveGrid adaptiveGrid = new AdaptiveGrid(scalingStrategy);
        
        // Create grid materials
        PhongMaterial xMaterial = new PhongMaterial(Color.RED);
        PhongMaterial yMaterial = new PhongMaterial(Color.GREEN);
        PhongMaterial zMaterial = new PhongMaterial(Color.BLUE);
        
        // Build grid for level 10
        Group gridGroup = adaptiveGrid.constructForLevel(10, xMaterial, yMaterial, zMaterial, null);
        root.getChildren().add(gridGroup);
        
        // Add a reference sphere at origin
        javafx.scene.shape.Sphere origin = new javafx.scene.shape.Sphere(5);
        origin.setMaterial(new PhongMaterial(Color.WHITE));
        root.getChildren().add(origin);
        
        // Create scene
        Scene scene = new Scene(root, 800, 600, true);
        scene.setFill(Color.DARKGRAY);
        
        // Create camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-1000);
        camera.setNearClip(0.1);
        camera.setFarClip(10000.0);
        scene.setCamera(camera);
        
        // Add lighting
        AmbientLight ambientLight = new AmbientLight(Color.WHITE.deriveColor(0, 1, 0.5, 1));
        root.getChildren().add(ambientLight);
        
        // Configure stage
        primaryStage.setTitle("Grid Test");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.out.println("Grid Test running - you should see:");
        System.out.println("- White sphere at origin");
        System.out.println("- Red lines parallel to X axis");
        System.out.println("- Green lines parallel to Y axis"); 
        System.out.println("- Blue lines parallel to Z axis");
    }
    
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(GridTest.class, args);
        }
    }
}