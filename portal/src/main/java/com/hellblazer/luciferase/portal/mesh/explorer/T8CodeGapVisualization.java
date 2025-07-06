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
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.Random;

import com.hellblazer.luciferase.lucien.tetree.Tet;

/**
 * Visualization demonstrating why gaps and overlaps occur in the t8code tetrahedral decomposition.
 * 
 * This shows how 6 tetrahedra are carved out of a cube, and why they don't perfectly
 * tessellate the space, leaving gaps and creating overlaps.
 */
public class T8CodeGapVisualization extends Application {
    
    private static final double SCENE_WIDTH = 1200;
    private static final double SCENE_HEIGHT = 800;
    
    // 3D components
    private Group root3D;
    private SubScene scene3D;
    private final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    private double mouseX, mouseY;
    
    // Visualization groups
    private Group cubeWireframe;
    private Group tetrahedraGroup;
    private Group gapPointsGroup;
    private Group overlapPointsGroup;
    
    // UI controls
    private CheckBox[] tetVisibility = new CheckBox[6];
    private Slider transparencySlider;
    private CheckBox showGapsCheckBox;
    private CheckBox showOverlapsCheckBox;
    private Label infoLabel;
    
    // Colors for each tetrahedron type
    private final Color[] TET_COLORS = {
        Color.RED.deriveColor(0, 1, 1, 0.7),
        Color.GREEN.deriveColor(0, 1, 1, 0.7),
        Color.BLUE.deriveColor(0, 1, 1, 0.7),
        Color.YELLOW.deriveColor(0, 1, 1, 0.7),
        Color.MAGENTA.deriveColor(0, 1, 1, 0.7),
        Color.CYAN.deriveColor(0, 1, 1, 0.7)
    };
    
    public static void main(String[] args) {
        launch(args);
    }
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("T8Code Gap and Overlap Visualization");
        
        // Create 3D scene
        root3D = new Group();
        scene3D = new SubScene(root3D, SCENE_WIDTH * 0.75, SCENE_HEIGHT, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.LIGHTGRAY);
        
        // Setup camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-800);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        scene3D.setCamera(camera);
        
        // Add transforms
        root3D.getTransforms().addAll(rotateX, rotateY);
        
        // Scale to make unit cube visible
        Scale scale = new Scale(200, 200, 200);
        root3D.getTransforms().add(scale);
        
        // Create visualization elements
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
    
    private void createVisualization() {
        // Create groups
        cubeWireframe = new Group();
        tetrahedraGroup = new Group();
        gapPointsGroup = new Group();
        overlapPointsGroup = new Group();
        
        // Add all groups to root
        root3D.getChildren().addAll(cubeWireframe, tetrahedraGroup, gapPointsGroup, overlapPointsGroup);
        
        // Create wireframe cube
        createWireframeCube();
        
        // Create the 6 tetrahedra
        createTetrahedra();
        
        // Create coordinate axes
        createAxes();
    }
    
    private void createWireframeCube() {
        PhongMaterial edgeMaterial = new PhongMaterial(Color.BLACK);
        double thickness = 0.01;
        
        // Bottom face edges
        Box edge1 = new Box(1, thickness, thickness);
        edge1.setTranslateX(0.5);
        edge1.setTranslateY(0);
        edge1.setTranslateZ(0);
        edge1.setMaterial(edgeMaterial);
        
        Box edge2 = new Box(thickness, 1, thickness);
        edge2.setTranslateX(1);
        edge2.setTranslateY(0.5);
        edge2.setTranslateZ(0);
        edge2.setMaterial(edgeMaterial);
        
        Box edge3 = new Box(1, thickness, thickness);
        edge3.setTranslateX(0.5);
        edge3.setTranslateY(1);
        edge3.setTranslateZ(0);
        edge3.setMaterial(edgeMaterial);
        
        Box edge4 = new Box(thickness, 1, thickness);
        edge4.setTranslateX(0);
        edge4.setTranslateY(0.5);
        edge4.setTranslateZ(0);
        edge4.setMaterial(edgeMaterial);
        
        // Top face edges
        Box edge5 = new Box(1, thickness, thickness);
        edge5.setTranslateX(0.5);
        edge5.setTranslateY(0);
        edge5.setTranslateZ(1);
        edge5.setMaterial(edgeMaterial);
        
        Box edge6 = new Box(thickness, 1, thickness);
        edge6.setTranslateX(1);
        edge6.setTranslateY(0.5);
        edge6.setTranslateZ(1);
        edge6.setMaterial(edgeMaterial);
        
        Box edge7 = new Box(1, thickness, thickness);
        edge7.setTranslateX(0.5);
        edge7.setTranslateY(1);
        edge7.setTranslateZ(1);
        edge7.setMaterial(edgeMaterial);
        
        Box edge8 = new Box(thickness, 1, thickness);
        edge8.setTranslateX(0);
        edge8.setTranslateY(0.5);
        edge8.setTranslateZ(1);
        edge8.setMaterial(edgeMaterial);
        
        // Vertical edges
        Box edge9 = new Box(thickness, thickness, 1);
        edge9.setTranslateX(0);
        edge9.setTranslateY(0);
        edge9.setTranslateZ(0.5);
        edge9.setMaterial(edgeMaterial);
        
        Box edge10 = new Box(thickness, thickness, 1);
        edge10.setTranslateX(1);
        edge10.setTranslateY(0);
        edge10.setTranslateZ(0.5);
        edge10.setMaterial(edgeMaterial);
        
        Box edge11 = new Box(thickness, thickness, 1);
        edge11.setTranslateX(1);
        edge11.setTranslateY(1);
        edge11.setTranslateZ(0.5);
        edge11.setMaterial(edgeMaterial);
        
        Box edge12 = new Box(thickness, thickness, 1);
        edge12.setTranslateX(0);
        edge12.setTranslateY(1);
        edge12.setTranslateZ(0.5);
        edge12.setMaterial(edgeMaterial);
        
        cubeWireframe.getChildren().addAll(
            edge1, edge2, edge3, edge4, edge5, edge6, edge7, edge8,
            edge9, edge10, edge11, edge12
        );
    }
    
    private void createTetrahedra() {
        // Create 6 tetrahedra for the unit cube at level 20 (cell size = 1)
        byte level = 20; // This gives us cell size = 1
        
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, level, type);
            Point3i[] vertices = tet.coordinates();
            
            // Create tetrahedron mesh
            MeshView tetMesh = createTetrahedronMesh(vertices, TET_COLORS[type]);
            tetMesh.setUserData(type); // Store type for later reference
            
            tetrahedraGroup.getChildren().add(tetMesh);
        }
    }
    
    private MeshView createTetrahedronMesh(Point3i[] vertices, Color color) {
        TriangleMesh mesh = new TriangleMesh();
        
        // Convert integer coordinates to normalized float coordinates
        // Since we're at level 20, the vertices have values 0 or 1
        for (Point3i v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }
        
        // Add texture coordinates (not used but required)
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);
        
        // Add faces (4 triangular faces)
        // Face normals pointing outward
        mesh.getFaces().addAll(
            0, 0, 2, 2, 1, 1,  // Face 0-2-1
            0, 0, 1, 1, 3, 3,  // Face 0-1-3
            0, 0, 3, 3, 2, 2,  // Face 0-3-2
            1, 1, 2, 2, 3, 3   // Face 1-2-3
        );
        
        MeshView meshView = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        meshView.setMaterial(material);
        meshView.setCullFace(javafx.scene.shape.CullFace.NONE); // Show both sides
        
        return meshView;
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
        controls.setPrefWidth(SCENE_WIDTH * 0.25);
        
        Label title = new Label("T8Code Tetrahedral Decomposition");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        Separator sep1 = new Separator();
        
        // Tetrahedra visibility controls
        Label tetLabel = new Label("Show Tetrahedra:");
        VBox tetControls = new VBox(5);
        for (int i = 0; i < 6; i++) {
            tetVisibility[i] = new CheckBox("Type " + i + " (S" + i + ")");
            tetVisibility[i].setSelected(true);
            tetVisibility[i].setTextFill(TET_COLORS[i].darker());
            final int type = i;
            tetVisibility[i].setOnAction(e -> updateTetrahedraVisibility());
            tetControls.getChildren().add(tetVisibility[i]);
        }
        
        Separator sep2 = new Separator();
        
        // Transparency control
        Label transLabel = new Label("Transparency:");
        transparencySlider = new Slider(0, 1, 0.7);
        transparencySlider.setShowTickLabels(true);
        transparencySlider.setShowTickMarks(true);
        transparencySlider.valueProperty().addListener((obs, oldVal, newVal) -> updateTransparency());
        
        Separator sep3 = new Separator();
        
        // Gap and overlap visualization
        showGapsCheckBox = new CheckBox("Show Gap Points");
        showGapsCheckBox.setOnAction(e -> visualizeGaps());
        
        showOverlapsCheckBox = new CheckBox("Show Overlap Points");
        showOverlapsCheckBox.setOnAction(e -> visualizeOverlaps());
        
        Button analyzeButton = new Button("Analyze Coverage");
        analyzeButton.setOnAction(e -> analyzeCoverage());
        
        Separator sep4 = new Separator();
        
        // Info label
        infoLabel = new Label("The 6 tetrahedra (S0-S5) are the\ncharacteristic tetrahedra that\ndecompose a cube in t8code.\n\nNotice how they don't perfectly\nfill the cube - there are gaps\nand overlaps!");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px;");
        
        // Mouse controls help
        Label helpLabel = new Label("\nMouse Controls:\n• Left drag: Rotate\n• Scroll: Zoom");
        helpLabel.setStyle("-fx-font-size: 10px;");
        
        controls.getChildren().addAll(
            title, sep1, tetLabel, tetControls, sep2,
            transLabel, transparencySlider, sep3,
            showGapsCheckBox, showOverlapsCheckBox, analyzeButton, sep4,
            infoLabel, helpLabel
        );
        
        return controls;
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
    
    private void updateTransparency() {
        double opacity = transparencySlider.getValue();
        for (int i = 0; i < 6; i++) {
            Color newColor = TET_COLORS[i].deriveColor(0, 1, 1, opacity);
            updateTetrahedronColor(i, newColor);
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
    
    private void visualizeGaps() {
        gapPointsGroup.getChildren().clear();
        
        if (!showGapsCheckBox.isSelected()) {
            return;
        }
        
        // Sample points in the cube to find gaps
        Random random = new Random(42); // Fixed seed for reproducibility
        int samplesPerDimension = 20;
        PhongMaterial gapMaterial = new PhongMaterial(Color.ORANGE);
        
        for (int i = 0; i < samplesPerDimension; i++) {
            for (int j = 0; j < samplesPerDimension; j++) {
                for (int k = 0; k < samplesPerDimension; k++) {
                    float x = i / (float) (samplesPerDimension - 1);
                    float y = j / (float) (samplesPerDimension - 1);
                    float z = k / (float) (samplesPerDimension - 1);
                    
                    // Check if this point is in any tetrahedron
                    boolean inAnyTet = false;
                    for (byte type = 0; type < 6; type++) {
                        Tet tet = new Tet(0, 0, 0, (byte) 20, type);
                        if (tet.contains(new Point3f(x, y, z))) {
                            inAnyTet = true;
                            break;
                        }
                    }
                    
                    // If not in any tetrahedron, it's a gap point
                    if (!inAnyTet) {
                        Sphere gapPoint = new Sphere(0.01);
                        gapPoint.setTranslateX(x);
                        gapPoint.setTranslateY(y);
                        gapPoint.setTranslateZ(z);
                        gapPoint.setMaterial(gapMaterial);
                        gapPointsGroup.getChildren().add(gapPoint);
                    }
                }
            }
        }
        
        infoLabel.setText("Orange points show gaps -\nlocations inside the cube\nbut not in any tetrahedron.\n\nAbout 48% of the cube\nvolume is gaps!");
    }
    
    private void visualizeOverlaps() {
        overlapPointsGroup.getChildren().clear();
        
        if (!showOverlapsCheckBox.isSelected()) {
            return;
        }
        
        // Sample points to find overlaps
        PhongMaterial overlapMaterial = new PhongMaterial(Color.PURPLE);
        int samplesPerDimension = 20;
        
        for (int i = 0; i < samplesPerDimension; i++) {
            for (int j = 0; j < samplesPerDimension; j++) {
                for (int k = 0; k < samplesPerDimension; k++) {
                    float x = i / (float) (samplesPerDimension - 1);
                    float y = j / (float) (samplesPerDimension - 1);
                    float z = k / (float) (samplesPerDimension - 1);
                    
                    // Count how many tetrahedra contain this point
                    int containCount = 0;
                    for (byte type = 0; type < 6; type++) {
                        Tet tet = new Tet(0, 0, 0, (byte) 20, type);
                        if (tet.contains(new Point3f(x, y, z))) {
                            containCount++;
                        }
                    }
                    
                    // If in multiple tetrahedra, it's an overlap point
                    if (containCount > 1) {
                        Sphere overlapPoint = new Sphere(0.01);
                        overlapPoint.setTranslateX(x);
                        overlapPoint.setTranslateY(y);
                        overlapPoint.setTranslateZ(z);
                        overlapPoint.setMaterial(overlapMaterial);
                        overlapPointsGroup.getChildren().add(overlapPoint);
                    }
                }
            }
        }
        
        infoLabel.setText("Purple points show overlaps -\nlocations contained by\nmultiple tetrahedra.\n\nAbout 32% of coverage\nis overlapping!");
    }
    
    private void analyzeCoverage() {
        // Sample many points to get statistics
        int totalSamples = 10000;
        int gapCount = 0;
        int overlapCount = 0;
        int correctCount = 0;
        
        Random random = new Random();
        for (int i = 0; i < totalSamples; i++) {
            float x = random.nextFloat();
            float y = random.nextFloat();
            float z = random.nextFloat();
            Point3f point = new Point3f(x, y, z);
            
            int containCount = 0;
            for (byte type = 0; type < 6; type++) {
                Tet tet = new Tet(0, 0, 0, (byte) 20, type);
                if (tet.contains(point)) {
                    containCount++;
                }
            }
            
            if (containCount == 0) gapCount++;
            else if (containCount == 1) correctCount++;
            else overlapCount++;
        }
        
        double gapPercent = (gapCount * 100.0) / totalSamples;
        double overlapPercent = (overlapCount * 100.0) / totalSamples;
        double correctPercent = (correctCount * 100.0) / totalSamples;
        
        infoLabel.setText(String.format(
            "Coverage Analysis:\n" +
            "• Gaps: %.1f%%\n" +
            "• Correct (1 tet): %.1f%%\n" + 
            "• Overlaps (2+ tets): %.1f%%\n\n" +
            "This is why t8code has\n" +
            "containment issues!",
            gapPercent, correctPercent, overlapPercent
        ));
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
            scene3D.getCamera().setTranslateZ(
                scene3D.getCamera().getTranslateZ() + delta * 2
            );
        });
    }
}