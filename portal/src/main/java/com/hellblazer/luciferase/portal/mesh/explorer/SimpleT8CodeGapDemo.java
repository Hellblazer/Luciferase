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
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

/**
 * Simple demonstration of why t8code tetrahedral decomposition has gaps. Shows the 6 characteristic tetrahedra and how
 * they don't fill the cube.
 */
public class SimpleT8CodeGapDemo extends Application {

    private static final double    SCENE_WIDTH   = 1000;
    private static final double    SCENE_HEIGHT  = 700;
    private static final Color[]   TET_COLORS    = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA,
                                                     Color.CYAN };
    private final        Rotate    rotateX       = new Rotate(0, Rotate.X_AXIS);
    private final        Rotate    rotateY       = new Rotate(0, Rotate.Y_AXIS);
    private final CheckBox[] tetVisibility = new CheckBox[6];
    private              Group     root3D;
    private              SubScene  scene3D;
    private              double    mouseX, mouseY;
    private       Slider     transparencySlider;
    private       Group      tetrahedraGroup;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("T8Code Tetrahedral Decomposition - Why Gaps Exist");

        // Create 3D scene
        root3D = new Group();
        scene3D = new SubScene(root3D, SCENE_WIDTH * 0.7, SCENE_HEIGHT, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.LIGHTGRAY);

        // Setup camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-600);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        scene3D.setCamera(camera);

        // Add transforms
        root3D.getTransforms().addAll(rotateX, rotateY);

        // Scale to make unit cube visible
        Scale scale = new Scale(200, 200, 200);
        root3D.getTransforms().add(scale);

        // Create visualization
        createVisualization();

        // Setup mouse controls
        setupMouseControls();

        // Create UI controls
        VBox controls = createControls();

        // Layout
        BorderPane mainLayout = new BorderPane();
        mainLayout.setCenter(scene3D);
        mainLayout.setRight(controls);

        Scene scene = new Scene(mainLayout, SCENE_WIDTH, SCENE_HEIGHT);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Initial rotation for better view
        rotateX.setAngle(-20);
        rotateY.setAngle(45);
    }

    private void addEdge(double x1, double y1, double z1, double x2, double y2, double z2, double thickness,
                         PhongMaterial material) {
        double length = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2) + Math.pow(z2 - z1, 2));
        Box edge = new Box(length, thickness, thickness);

        // Position at midpoint
        edge.setTranslateX((x1 + x2) / 2);
        edge.setTranslateY((y1 + y2) / 2);
        edge.setTranslateZ((z1 + z2) / 2);

        // Rotate to align with edge direction
        if (x2 != x1) {
            edge.setRotate(90);
            edge.setRotationAxis(Rotate.Z_AXIS);
        } else if (y2 != y1) {
            // Already aligned
        } else if (z2 != z1) {
            edge.setRotate(90);
            edge.setRotationAxis(Rotate.X_AXIS);
        }

        edge.setMaterial(material);
        root3D.getChildren().add(edge);
    }

    private void createAxes() {
        double axisLength = 1.5;
        double axisThickness = 0.005;

        // X axis - Red
        Box xAxis = new Box(axisLength, axisThickness, axisThickness);
        xAxis.setTranslateX(axisLength / 2);
        xAxis.setMaterial(new PhongMaterial(Color.RED));

        // Y axis - Green
        Box yAxis = new Box(axisThickness, axisLength, axisThickness);
        yAxis.setTranslateY(axisLength / 2);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));

        // Z axis - Blue
        Box zAxis = new Box(axisThickness, axisThickness, axisLength);
        zAxis.setTranslateZ(axisLength / 2);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));

        root3D.getChildren().addAll(xAxis, yAxis, zAxis);
    }

    private VBox createControls() {
        VBox controls = new VBox(10);
        controls.setPadding(new Insets(10));
        controls.setPrefWidth(SCENE_WIDTH * 0.3);

        Label title = new Label("T8Code Tetrahedral Decomposition");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        // Info text
        Label info = new Label("This shows the 6 characteristic tetrahedra\n" + "types from t8code at level 10.\n\n"
                               + "These are the actual tetrahedra produced\n"
                               + "by Tet.coordinates() using the t8code\n"
                               + "vertex computation algorithm.\n\n" + "Toggle types to see gaps and overlaps:");
        info.setWrapText(true);
        info.setStyle("-fx-font-size: 11px;");

        Separator sep1 = new Separator();

        // Tetrahedra visibility controls
        Label tetLabel = new Label("Show Tetrahedra:");
        VBox tetControls = new VBox(5);

        String[] vertexDescriptions = { "Type 0", "Type 1", "Type 2", "Type 3", "Type 4", "Type 5" };

        for (int i = 0; i < 6; i++) {
            tetVisibility[i] = new CheckBox("Type " + i + " (" + vertexDescriptions[i] + ")");
            tetVisibility[i].setSelected(true);
            tetVisibility[i].setTextFill(TET_COLORS[i]);
            final int type = i;
            tetVisibility[i].setOnAction(e -> updateTetrahedraVisibility());
            tetControls.getChildren().add(tetVisibility[i]);
        }

        Separator sep2 = new Separator();

        // Transparency control
        Label transLabel = new Label("Transparency:");
        transparencySlider = new Slider(0, 1, 0.7);
        transparencySlider.setShowTickLabels(true);
        transparencySlider.valueProperty().addListener((obs, oldVal, newVal) -> updateTransparency());

        // Gap explanation
        Separator sep3 = new Separator();
        Label gapInfo = new Label(
        "\nWhy Gaps Exist:\n" + "The 6 tetrahedra are formed by connecting\n" + "V0 and V7 with 4 edges of the cube.\n"
        + "This creates 'wedge' shapes that don't\n" + "perfectly tessellate the cube.\n\n"
        + "Result: ~48% gaps, ~32% overlaps!");
        gapInfo.setWrapText(true);
        gapInfo.setStyle("-fx-font-size: 10px; -fx-font-style: italic;");

        // Mouse help
        Label help = new Label("\nMouse: Left drag to rotate, scroll to zoom");
        help.setStyle("-fx-font-size: 9px;");

        controls.getChildren().addAll(title, info, sep1, tetLabel, tetControls, sep2, transLabel, transparencySlider,
                                      sep3, gapInfo, help);

        return controls;
    }

    private MeshView createTetrahedron(int type) {
        TriangleMesh mesh = new TriangleMesh();

        // Create actual Tet and get its coordinates
        com.hellblazer.luciferase.lucien.tetree.Tet tet = 
            new com.hellblazer.luciferase.lucien.tetree.Tet(0, 0, 0, (byte)10, (byte)type);
        javax.vecmath.Point3i[] coords = tet.coordinates();
        
        // Add the 4 vertices of this tetrahedron
        for (int i = 0; i < 4; i++) {
            // Scale down for visualization
            mesh.getPoints().addAll(coords[i].x / 2048.0f, coords[i].y / 2048.0f, coords[i].z / 2048.0f);
        }

        // Add texture coordinates (required but not used)
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Add the 4 triangular faces
        // We need to ensure correct winding for outward-facing normals
        mesh.getFaces().addAll(0, 0, 2, 2, 1, 1,  // Face 1
                               0, 0, 1, 1, 3, 3,  // Face 2
                               0, 0, 3, 3, 2, 2,  // Face 3
                               1, 1, 2, 2, 3, 3   // Face 4
                              );

        MeshView meshView = new MeshView(mesh);
        Color color = TET_COLORS[type].deriveColor(0, 1, 1, 0.7);
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        meshView.setMaterial(material);
        meshView.setCullFace(javafx.scene.shape.CullFace.NONE);
        meshView.setUserData(type);

        return meshView;
    }

    private void createVisualization() {
        tetrahedraGroup = new Group();
        root3D.getChildren().add(tetrahedraGroup);

        // Create the 6 tetrahedra using actual Tet coordinates
        for (int i = 0; i < 6; i++) {
            MeshView tet = createTetrahedron(i);
            tetrahedraGroup.getChildren().add(tet);
        }

        // Create axes
        createAxes();
    }


    private void setupMouseControls() {
        scene3D.setOnMousePressed(event -> {
            mouseX = event.getSceneX();
            mouseY = event.getSceneY();
        });

        scene3D.setOnMouseDragged(event -> {
            double deltaX = event.getSceneX() - mouseX;
            double deltaY = event.getSceneY() - mouseY;
            mouseX = event.getSceneX();
            mouseY = event.getSceneY();

            if (event.isPrimaryButtonDown()) {
                rotateX.setAngle(rotateX.getAngle() - (deltaY * 0.2));
                rotateY.setAngle(rotateY.getAngle() + (deltaX * 0.2));
            }
        });

        scene3D.setOnScroll(event -> {
            double delta = event.getDeltaY();
            scene3D.getCamera().setTranslateZ(scene3D.getCamera().getTranslateZ() + delta * 2);
        });
    }

    private void updateTetrahedraVisibility() {
        int index = 0;
        for (Node node : tetrahedraGroup.getChildren()) {
            if (node instanceof MeshView && index < 6) {
                node.setVisible(tetVisibility[index].isSelected());
                index++;
            }
        }
    }

    private void updateTetrahedronColor(int type, Color color) {
        for (Node node : tetrahedraGroup.getChildren()) {
            if (node instanceof MeshView && node.getUserData() instanceof Integer) {
                if ((Integer) node.getUserData() == type) {
                    PhongMaterial material = new PhongMaterial(color);
                    material.setSpecularColor(color.brighter());
                    ((MeshView) node).setMaterial(material);
                }
            }
        }
    }

    private void updateTransparency() {
        double opacity = transparencySlider.getValue();
        for (int i = 0; i < 6; i++) {
            Color newColor = TET_COLORS[i].deriveColor(0, 1, 1, opacity);
            updateTetrahedronColor(i, newColor);
        }
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            SimpleT8CodeGapDemo.main(argv);
        }
    }
}
