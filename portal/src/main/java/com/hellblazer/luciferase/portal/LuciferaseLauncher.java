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
package com.hellblazer.luciferase.portal;

import com.hellblazer.luciferase.portal.collision.CollisionDebugViewer;
import com.hellblazer.luciferase.portal.esvo.OctreeInspectorApp;
import com.hellblazer.luciferase.portal.mesh.explorer.GeometryViewer;
import com.hellblazer.luciferase.portal.mesh.explorer.RDGridViewer;
import com.hellblazer.luciferase.portal.mesh.explorer.TetreeInspector;
import com.hellblazer.luciferase.portal.mesh.explorer.grid.GridInspector;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Master launcher application for all Luciferase visualization demos.
 * Provides a central menu to launch individual demonstration applications.
 * 
 * @author hal.hildebrand
 */
public class LuciferaseLauncher extends Application {
    
    private static final Logger log = LoggerFactory.getLogger(LuciferaseLauncher.class);
    
    @Override
    public void start(Stage primaryStage) {
        // Create main layout
        VBox root = new VBox(15);
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #2b2b2b;");
        
        // Title
        Label title = new Label("Luciferase Visualization Suite");
        title.setFont(Font.font("System", FontWeight.BOLD, 24));
        title.setStyle("-fx-text-fill: #e0e0e0;");
        
        Label subtitle = new Label("Select a demonstration to launch");
        subtitle.setFont(Font.font("System", 14));
        subtitle.setStyle("-fx-text-fill: #b0b0b0;");
        
        Separator separator = new Separator();
        separator.setMaxWidth(400);
        
        // Demo buttons
        VBox demoButtons = new VBox(10);
        demoButtons.setAlignment(Pos.CENTER);
        
        // Octree Inspector
        Button octreeBtn = createDemoButton(
            "ESVO Octree Inspector",
            "Interactive octree visualization with ray casting, LOD controls, and recording",
            () -> launchDemo(OctreeInspectorApp.class, "ESVO Octree Inspector")
        );
        
        // Collision Debug Viewer
        Button collisionBtn = createDemoButton(
            "Collision Debug Viewer",
            "Physics and collision detection visualization with contact points and forces",
            () -> launchDemo(CollisionDebugViewer.class, "Collision Debug Viewer")
        );
        
        // Tetree Inspector
        Button tetreeBtn = createDemoButton(
            "Tetree Inspector",
            "Tetrahedral space partitioning tree visualization",
            () -> launchDemo(TetreeInspector.class, "Tetree Inspector")
        );
        
        // Grid Inspector
        Button gridBtn = createDemoButton(
            "Grid Inspector",
            "Cubic grid and neighborhood exploration",
            () -> launchDemo(GridInspector.class, "Grid Inspector")
        );
        
        // Geometry Viewer
        Button geometryBtn = createDemoButton(
            "Geometry Viewer",
            "3D geometry and mesh visualization",
            () -> launchDemo(GeometryViewer.class, "Geometry Viewer")
        );
        
        // RD Grid Viewer
        Button rdGridBtn = createDemoButton(
            "RD Grid Viewer",
            "Reaction-diffusion grid visualization",
            () -> launchDemo(RDGridViewer.class, "RD Grid Viewer")
        );
        
        demoButtons.getChildren().addAll(
            octreeBtn,
            collisionBtn,
            tetreeBtn,
            gridBtn,
            geometryBtn,
            rdGridBtn
        );
        
        // Exit button
        Button exitBtn = new Button("Exit");
        exitBtn.setStyle("-fx-background-color: #d32f2f; -fx-text-fill: white; " +
                        "-fx-font-size: 14px; -fx-padding: 10 30 10 30; " +
                        "-fx-background-radius: 5;");
        exitBtn.setOnAction(e -> {
            log.info("Exiting Luciferase Launcher");
            Platform.exit();
        });
        
        // Add all components to root
        root.getChildren().addAll(
            title,
            subtitle,
            separator,
            demoButtons,
            new Separator(),
            exitBtn
        );
        
        // Create and configure scene
        Scene scene = new Scene(root, 600, 700);
        primaryStage.setScene(scene);
        primaryStage.setTitle("Luciferase - Visualization Suite");
        primaryStage.setResizable(false);
        primaryStage.show();
        
        log.info("Luciferase Launcher started");
    }
    
    /**
     * Create a styled demo button with title and description.
     */
    private Button createDemoButton(String title, String description, Runnable action) {
        Button button = new Button();
        button.setMaxWidth(500);
        button.setMinWidth(500);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setStyle(
            "-fx-background-color: #3c3c3c; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 14px; " +
            "-fx-padding: 15 20 15 20; " +
            "-fx-background-radius: 8; " +
            "-fx-border-color: #555555; " +
            "-fx-border-width: 1; " +
            "-fx-border-radius: 8;"
        );
        
        // Create multi-line text
        VBox content = new VBox(5);
        
        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        titleLabel.setStyle("-fx-text-fill: #ffffff;");
        
        Label descLabel = new Label(description);
        descLabel.setFont(Font.font("System", 11));
        descLabel.setStyle("-fx-text-fill: #b0b0b0;");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(460);
        
        content.getChildren().addAll(titleLabel, descLabel);
        button.setGraphic(content);
        
        // Hover effects
        button.setOnMouseEntered(e -> 
            button.setStyle(
                "-fx-background-color: #4c4c4c; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 15 20 15 20; " +
                "-fx-background-radius: 8; " +
                "-fx-border-color: #888888; " +
                "-fx-border-width: 1; " +
                "-fx-border-radius: 8; " +
                "-fx-cursor: hand;"
            )
        );
        
        button.setOnMouseExited(e -> 
            button.setStyle(
                "-fx-background-color: #3c3c3c; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 15 20 15 20; " +
                "-fx-background-radius: 8; " +
                "-fx-border-color: #555555; " +
                "-fx-border-width: 1; " +
                "-fx-border-radius: 8;"
            )
        );
        
        button.setOnAction(e -> action.run());
        
        return button;
    }
    
    /**
     * Launch a demo application as a separate Maven process.
     * Uses mvn exec:java to ensure all dependencies are on the classpath.
     */
    private void launchDemo(Class<? extends Application> appClass, String demoName) {
        log.info("Launching: {}", demoName);
        
        new Thread(() -> {
            try {
                // Determine the main class to launch
                String mainClass = appClass.getName();
                if (appClass == OctreeInspectorApp.class) {
                    mainClass = "com.hellblazer.luciferase.portal.esvo.OctreeInspectorApp$Launcher";
                }
                
                // Get the project directory (go up from wherever we are to find pom.xml)
                String userDir = System.getProperty("user.dir");
                
                // Build maven command
                ProcessBuilder pb = new ProcessBuilder(
                    "mvn",
                    "exec:java",
                    "-pl", "portal",
                    "-Dexec.mainClass=" + mainClass,
                    "-q"  // Quiet mode
                );
                
                // Set working directory to project root
                pb.directory(new java.io.File(userDir));
                
                // Inherit environment and redirect output
                pb.inheritIO();
                
                // Start the process
                Process process = pb.start();
                
                log.info("Started {} via Maven (PID: {})", demoName, process.pid());
                
            } catch (Exception e) {
                log.error("Failed to launch {}", demoName, e);
                Platform.runLater(() -> {
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR
                    );
                    alert.setTitle("Launch Error");
                    alert.setHeaderText("Failed to launch " + demoName);
                    alert.setContentText("Error: " + e.getMessage() + "\n\nMake sure you run the launcher from the project root directory.");
                    alert.showAndWait();
                });
            }
        }, demoName + "-Thread").start();
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
