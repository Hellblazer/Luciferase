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
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import javafx.scene.AmbientLight;
import javafx.scene.PointLight;

/**
 * Simple test to debug tetree visualization visibility
 *
 * @author hal.hildebrand
 */
public class SimpleTetreeVisualizationTest extends Application {
    
    private double mouseX, mouseY;
    private double mouseOldX, mouseOldY;
    private final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Simple Tetree Visualization Test");
        
        Group root = new Group();
        
        // Add coordinate axes for reference
        Box xAxis = new Box(300, 2, 2);
        xAxis.setMaterial(new PhongMaterial(Color.RED));
        xAxis.setTranslateX(150);
        
        Box yAxis = new Box(2, 300, 2);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));
        yAxis.setTranslateY(150);
        
        Box zAxis = new Box(2, 2, 300);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));
        zAxis.setTranslateZ(150);
        
        root.getChildren().addAll(xAxis, yAxis, zAxis);
        
        // Create a simple tetrahedron
        TriangleMesh mesh = new TriangleMesh();
        
        // Add vertices
        mesh.getPoints().addAll(
            0f, 0f, 0f,      // v0
            100f, 0f, 0f,    // v1
            50f, 100f, 0f,   // v2
            50f, 33f, 100f   // v3
        );
        
        // Add texture coordinates
        mesh.getTexCoords().addAll(
            0, 0,
            1, 0,
            0.5f, 1,
            0.5f, 0.5f
        );
        
        // Add faces
        mesh.getFaces().addAll(
            0, 0, 2, 2, 1, 1,  // Face 0-2-1
            0, 0, 1, 1, 3, 3,  // Face 0-1-3
            0, 0, 3, 3, 2, 2,  // Face 0-3-2
            1, 1, 2, 2, 3, 3   // Face 1-2-3
        );
        
        MeshView meshView = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial(Color.YELLOW);
        meshView.setMaterial(material);
        meshView.setCullFace(javafx.scene.shape.CullFace.NONE);
        
        root.getChildren().add(meshView);
        
        // Add lighting
        AmbientLight ambientLight = new AmbientLight(Color.rgb(80, 80, 80));
        PointLight pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(200);
        pointLight.setTranslateY(-200);
        pointLight.setTranslateZ(-200);
        
        root.getChildren().addAll(ambientLight, pointLight);
        
        // Apply rotations
        root.getTransforms().addAll(rotateX, rotateY);
        
        // Create camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000.0);
        camera.setTranslateZ(-600);
        
        // Create scene
        Scene scene = new Scene(root, 800, 600);
        scene.setFill(Color.DARKGRAY);
        scene.setCamera(camera);
        
        // Add mouse controls
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
            camera.setTranslateZ(camera.getTranslateZ() + delta * 2);
        });
        
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.out.println("Test visualization running - you should see:");
        System.out.println("- Red X axis");
        System.out.println("- Green Y axis");
        System.out.println("- Blue Z axis");
        System.out.println("- Yellow tetrahedron");
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}