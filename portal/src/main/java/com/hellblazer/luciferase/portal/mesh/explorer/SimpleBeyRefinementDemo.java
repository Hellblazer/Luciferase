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
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
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

/**
 * Simple visualization of Bey refinement showing how a tetrahedron subdivides into 8 children.
 * Uses a simplified model to demonstrate the concept without dependencies.
 */
public class SimpleBeyRefinementDemo extends Application {
    
    private static final double SCENE_WIDTH = 1200;
    private static final double SCENE_HEIGHT = 800;
    
    // Colors for the 8 children in Bey order
    private static final Color[] CHILD_COLORS = {
        Color.RED,      // Child 0
        Color.GREEN,    // Child 1
        Color.BLUE,     // Child 2
        Color.YELLOW,   // Child 3
        Color.MAGENTA,  // Child 4
        Color.CYAN,     // Child 5
        Color.ORANGE,   // Child 6
        Color.PURPLE    // Child 7
    };
    
    private final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    
    private Group root3D;
    private SubScene scene3D;
    private double mouseX, mouseY;
    
    private Group parentGroup;
    private Group childrenGroup;
    private Group midpointsGroup;
    
    private CheckBox showParentCheckBox;
    private CheckBox showMidpointsCheckBox;
    private CheckBox[] childVisibility = new CheckBox[8];
    private Slider transparencySlider;
    
    // Parent tetrahedron vertices - S0 characteristic tetrahedron
    // Using normalized coordinates (divide by 2048 for unit scale)
    private float[][] parentVertices = {
        {0, 0, 0},        // V0: (0, 0, 0)
        {1, 0, 0},        // V1: (2048, 0, 0) / 2048
        {1, 0, 1},        // V2: (2048, 0, 2048) / 2048
        {0, 1, 1}         // V3: (0, 2048, 2048) / 2048
    };
    
    public static void main(String[] args) {
        launch(args);
    }
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Bey Refinement Visualization");
        
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
        
        // Scale to make tetrahedra visible
        Scale scale = new Scale(200, 200, 200);
        root3D.getTransforms().add(scale);
        
        // Create visualization groups
        parentGroup = new Group();
        childrenGroup = new Group();
        midpointsGroup = new Group();
        root3D.getChildren().addAll(parentGroup, childrenGroup, midpointsGroup);
        
        // Setup mouse controls
        setupMouseControls();
        
        // Create UI controls first (needed for updateVisibility)
        VBox controls = createControls();
        
        // Create visualization
        createVisualization();
        
        // Update visibility based on controls
        updateVisibility();
        
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
        
        // Create axes
        createAxes();
    }
    
    private void createVisualization() {
        // Create parent tetrahedron (wireframe)
        createParentWireframe();
        
        // Create edge midpoints
        createMidpoints();
        
        // Create 8 children (simplified Bey subdivision)
        createChildren();
    }
    
    private void createParentWireframe() {
        PhongMaterial material = new PhongMaterial(Color.BLACK);
        double thickness = 0.01;
        
        // Create the 6 edges of the tetrahedron
        addEdge(parentVertices[0], parentVertices[1], thickness, material, parentGroup);
        addEdge(parentVertices[0], parentVertices[2], thickness, material, parentGroup);
        addEdge(parentVertices[0], parentVertices[3], thickness, material, parentGroup);
        addEdge(parentVertices[1], parentVertices[2], thickness, material, parentGroup);
        addEdge(parentVertices[1], parentVertices[3], thickness, material, parentGroup);
        addEdge(parentVertices[2], parentVertices[3], thickness, material, parentGroup);
    }
    
    private void createMidpoints() {
        PhongMaterial midpointMaterial = new PhongMaterial(Color.WHITE);
        
        // Calculate and show midpoints of all 6 edges
        float[][] midpoints = {
            midpoint(parentVertices[0], parentVertices[1]), // Edge 0-1
            midpoint(parentVertices[0], parentVertices[2]), // Edge 0-2
            midpoint(parentVertices[0], parentVertices[3]), // Edge 0-3
            midpoint(parentVertices[1], parentVertices[2]), // Edge 1-2
            midpoint(parentVertices[1], parentVertices[3]), // Edge 1-3
            midpoint(parentVertices[2], parentVertices[3])  // Edge 2-3
        };
        
        for (float[] mp : midpoints) {
            Sphere midpointSphere = new Sphere(0.02);
            midpointSphere.setTranslateX(mp[0]);
            midpointSphere.setTranslateY(mp[1]);
            midpointSphere.setTranslateZ(mp[2]);
            midpointSphere.setMaterial(midpointMaterial);
            midpointsGroup.getChildren().add(midpointSphere);
        }
    }
    
    private void createChildren() {
        // This is a simplified demonstration of Bey subdivision
        // In practice, the actual algorithm creates 8 specific tetrahedra
        // Here we create 8 smaller tetrahedra to show the concept
        
        float[][][] childVertices = calculateChildVertices();
        
        for (int i = 0; i < 8; i++) {
            MeshView childMesh = createTetrahedronMesh(childVertices[i], CHILD_COLORS[i]);
            childMesh.setUserData(i);
            childrenGroup.getChildren().add(childMesh);
        }
    }
    
    private float[][][] calculateChildVertices() {
        // Simplified child calculation - this approximates Bey subdivision
        // In reality, the children are computed using edge midpoints and connectivity
        
        float[][][] children = new float[8][4][3];
        
        // Calculate midpoints
        float[] m01 = midpoint(parentVertices[0], parentVertices[1]);
        float[] m02 = midpoint(parentVertices[0], parentVertices[2]);
        float[] m03 = midpoint(parentVertices[0], parentVertices[3]);
        float[] m12 = midpoint(parentVertices[1], parentVertices[2]);
        float[] m13 = midpoint(parentVertices[1], parentVertices[3]);
        float[] m23 = midpoint(parentVertices[2], parentVertices[3]);
        float[] center = centroid(parentVertices);
        
        // Child 0: Corner at V0
        children[0][0] = parentVertices[0].clone();
        children[0][1] = m01.clone();
        children[0][2] = m02.clone();
        children[0][3] = m03.clone();
        
        // Child 1: Corner at V1
        children[1][0] = parentVertices[1].clone();
        children[1][1] = m01.clone();
        children[1][2] = m12.clone();
        children[1][3] = m13.clone();
        
        // Child 2: Corner at V2
        children[2][0] = parentVertices[2].clone();
        children[2][1] = m02.clone();
        children[2][2] = m12.clone();
        children[2][3] = m23.clone();
        
        // Child 3: Corner at V3
        children[3][0] = parentVertices[3].clone();
        children[3][1] = m03.clone();
        children[3][2] = m13.clone();
        children[3][3] = m23.clone();
        
        // Children 4-7: Interior tetrahedra (simplified)
        // These connect midpoints to create interior subdivision
        children[4][0] = m01.clone();
        children[4][1] = m02.clone();
        children[4][2] = m12.clone();
        children[4][3] = center.clone();
        
        children[5][0] = m01.clone();
        children[5][1] = m03.clone();
        children[5][2] = m13.clone();
        children[5][3] = center.clone();
        
        children[6][0] = m02.clone();
        children[6][1] = m03.clone();
        children[6][2] = m23.clone();
        children[6][3] = center.clone();
        
        children[7][0] = m12.clone();
        children[7][1] = m13.clone();
        children[7][2] = m23.clone();
        children[7][3] = center.clone();
        
        return children;
    }
    
    private float[] midpoint(float[] a, float[] b) {
        return new float[]{
            (a[0] + b[0]) / 2,
            (a[1] + b[1]) / 2,
            (a[2] + b[2]) / 2
        };
    }
    
    private float[] centroid(float[][] vertices) {
        float x = 0, y = 0, z = 0;
        for (float[] v : vertices) {
            x += v[0];
            y += v[1];
            z += v[2];
        }
        return new float[]{x/4, y/4, z/4};
    }
    
    private MeshView createTetrahedronMesh(float[][] vertices, Color color) {
        TriangleMesh mesh = new TriangleMesh();
        
        // Add vertices
        for (float[] vertex : vertices) {
            mesh.getPoints().addAll(vertex[0], vertex[1], vertex[2]);
        }
        
        // Add texture coordinates (required)
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);
        
        // Add faces
        mesh.getFaces().addAll(
            0, 0, 2, 2, 1, 1,  // Face 0-2-1
            0, 0, 1, 1, 3, 3,  // Face 0-1-3
            0, 0, 3, 3, 2, 2,  // Face 0-3-2
            1, 1, 2, 2, 3, 3   // Face 1-2-3
        );
        
        MeshView meshView = new MeshView(mesh);
        Color transparentColor = color.deriveColor(0, 1, 1, 0.6);
        PhongMaterial material = new PhongMaterial(transparentColor);
        material.setSpecularColor(color.brighter());
        meshView.setMaterial(material);
        meshView.setCullFace(javafx.scene.shape.CullFace.NONE);
        
        return meshView;
    }
    
    private void addEdge(float[] v1, float[] v2, double thickness, PhongMaterial material, Group parent) {
        // Create a cylinder to represent the edge
        double dx = v2[0] - v1[0];
        double dy = v2[1] - v1[1];
        double dz = v2[2] - v1[2];
        double length = Math.sqrt(dx*dx + dy*dy + dz*dz);
        
        if (length < 0.001) return; // Skip zero-length edges
        
        // Use a Cylinder instead of Box for easier rotation
        javafx.scene.shape.Cylinder cylinder = new javafx.scene.shape.Cylinder(thickness/2, length);
        
        // Position at midpoint
        double midX = (v1[0] + v2[0]) / 2.0;
        double midY = (v1[1] + v2[1]) / 2.0;
        double midZ = (v1[2] + v2[2]) / 2.0;
        cylinder.setTranslateX(midX);
        cylinder.setTranslateY(midY);
        cylinder.setTranslateZ(midZ);
        
        // Calculate rotation to align cylinder (Y-axis) with edge direction
        // Default cylinder orientation is along Y axis
        Point3D yAxis = new Point3D(0, 1, 0);
        Point3D edgeDir = new Point3D(dx, dy, dz).normalize();
        
        // Calculate rotation axis (cross product)
        Point3D rotAxis = yAxis.crossProduct(edgeDir);
        
        if (rotAxis.magnitude() > 0.001) {
            // Calculate rotation angle
            double angle = Math.toDegrees(Math.acos(yAxis.dotProduct(edgeDir)));
            
            Rotate rotation = new Rotate(angle, rotAxis);
            cylinder.getTransforms().add(rotation);
        }
        
        cylinder.setMaterial(material);
        parent.getChildren().add(cylinder);
    }
    
    private VBox createControls() {
        VBox controls = new VBox(10);
        controls.setPadding(new Insets(10));
        controls.setPrefWidth(SCENE_WIDTH * 0.3);
        
        Label title = new Label("Bey Refinement Visualization");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        // Info text
        Label info = new Label(
            "Bey refinement subdivides a tetrahedron into\n" +
            "8 children by adding midpoints to each edge\n" +
            "and creating new tetrahedra.\n\n" +
            "Each child has 1/8 the volume of the parent\n" +
            "and they perfectly tile the parent space."
        );
        info.setWrapText(true);
        info.setStyle("-fx-font-size: 11px;");
        
        Separator sep1 = new Separator();
        
        // Parent visibility
        showParentCheckBox = new CheckBox("Show Parent (wireframe)");
        showParentCheckBox.setSelected(true);
        showParentCheckBox.setOnAction(e -> updateVisibility());
        
        // Midpoints visibility
        showMidpointsCheckBox = new CheckBox("Show Edge Midpoints");
        showMidpointsCheckBox.setSelected(true);
        showMidpointsCheckBox.setOnAction(e -> updateVisibility());
        
        Separator sep2 = new Separator();
        
        // Children visibility controls
        Label childLabel = new Label("Show Children (Bey order):");
        VBox childControls = new VBox(3);
        
        String[] childDescriptions = {
            "Corner V0", "Corner V1", "Corner V2", "Corner V3",
            "Interior 1", "Interior 2", "Interior 3", "Interior 4"
        };
        
        for (int i = 0; i < 8; i++) {
            childVisibility[i] = new CheckBox("Child " + i + " (" + childDescriptions[i] + ")");
            childVisibility[i].setSelected(true);
            childVisibility[i].setTextFill(CHILD_COLORS[i]);
            final int childIndex = i;
            childVisibility[i].setOnAction(e -> updateChildVisibility(childIndex));
            childControls.getChildren().add(childVisibility[i]);
        }
        
        Separator sep3 = new Separator();
        
        // Transparency control
        Label transLabel = new Label("Child Transparency:");
        transparencySlider = new Slider(0, 1, 0.6);
        transparencySlider.setShowTickLabels(true);
        transparencySlider.valueProperty().addListener((obs, oldVal, newVal) -> updateTransparency());
        
        // Algorithm explanation
        Separator sep4 = new Separator();
        Label algorithmInfo = new Label(
            "\nBey Algorithm:\n" +
            "1. Add midpoints to all 6 edges\n" +
            "2. Create 4 corner tetrahedra (one at each vertex)\n" +
            "3. Create 4 interior tetrahedra from midpoints\n" +
            "4. Each child has 1/8 the volume\n" +
            "5. Children perfectly tile the parent\n\n" +
            "This creates hierarchical tetrahedral refinement\n" +
            "used in adaptive mesh algorithms."
        );
        algorithmInfo.setWrapText(true);
        algorithmInfo.setStyle("-fx-font-size: 10px; -fx-font-style: italic;");
        
        // Mouse help
        Label help = new Label("\nMouse: Left drag to rotate, scroll to zoom");
        help.setStyle("-fx-font-size: 9px;");
        
        controls.getChildren().addAll(
            title, info, sep1,
            showParentCheckBox, showMidpointsCheckBox, sep2,
            childLabel, childControls, sep3,
            transLabel, transparencySlider, sep4,
            algorithmInfo, help
        );
        
        return controls;
    }
    
    private void updateVisibility() {
        parentGroup.setVisible(showParentCheckBox.isSelected());
        midpointsGroup.setVisible(showMidpointsCheckBox.isSelected());
        
        // Update children visibility
        for (int i = 0; i < 8; i++) {
            updateChildVisibility(i);
        }
    }
    
    private void updateChildVisibility(int childIndex) {
        if (childIndex < childrenGroup.getChildren().size()) {
            Node child = childrenGroup.getChildren().get(childIndex);
            child.setVisible(childVisibility[childIndex].isSelected());
        }
    }
    
    private void updateTransparency() {
        double opacity = transparencySlider.getValue();
        
        for (int i = 0; i < 8 && i < childrenGroup.getChildren().size(); i++) {
            Node node = childrenGroup.getChildren().get(i);
            if (node instanceof MeshView) {
                Color newColor = CHILD_COLORS[i].deriveColor(0, 1, 1, opacity);
                PhongMaterial material = new PhongMaterial(newColor);
                material.setSpecularColor(newColor.brighter());
                ((MeshView) node).setMaterial(material);
            }
        }
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
    
    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {
        public static void main(String[] argv) {
            SimpleBeyRefinementDemo.main(argv);
        }
    }
}