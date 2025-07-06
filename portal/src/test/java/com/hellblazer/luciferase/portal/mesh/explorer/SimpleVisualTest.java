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
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

/**
 * Simple visual test to verify JavaFX 3D rendering is working
 * 
 * @author hal.hildebrand
 */
public class SimpleVisualTest extends Application {
    
    @Override
    public void start(Stage primaryStage) {
        // Create a simple box
        Box box = new Box(50, 50, 50);
        PhongMaterial material = new PhongMaterial(Color.RED);
        box.setMaterial(material);
        
        // Create scene
        Group root = new Group(box);
        SubScene scene3D = new SubScene(root, 800, 600, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.DARKBLUE);
        
        // Setup camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-200);
        scene3D.setCamera(camera);
        
        // Add light
        PointLight light = new PointLight(Color.WHITE);
        light.setTranslateX(100);
        light.setTranslateY(-100);
        light.setTranslateZ(-100);
        root.getChildren().add(light);
        
        // Add rotation
        Rotate rotateX = new Rotate(30, Rotate.X_AXIS);
        Rotate rotateY = new Rotate(30, Rotate.Y_AXIS);
        box.getTransforms().addAll(rotateX, rotateY);
        
        // Mouse controls
        scene3D.setOnMousePressed(event -> {
            System.out.println("Mouse pressed at: " + event.getX() + ", " + event.getY());
        });
        
        Scene scene = new Scene(new Group(scene3D));
        primaryStage.setTitle("Simple Visual Test - Red Box");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.out.println("Simple visual test started - you should see a red box");
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}