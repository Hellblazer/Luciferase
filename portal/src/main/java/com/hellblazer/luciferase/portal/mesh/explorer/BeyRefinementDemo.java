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

import com.hellblazer.luciferase.lucien.tetree.BeySubdivision;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import javafx.application.Application;
import javafx.geometry.Insets;
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

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Visualization of Bey refinement - how a tetrahedron is subdivided into 8 children. Shows the parent tetrahedron and
 * its 8 children in Bey order.
 */
public class BeyRefinementDemo extends Application {

    private static final double SCENE_WIDTH  = 1200;
    private static final double SCENE_HEIGHT = 800;

    // Colors for the 8 children in Bey order
    private static final Color[] CHILD_COLORS = { Color.RED,      // Child 0
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
    private final CheckBox[]       childVisibility = new CheckBox[8];
    private final Tet[] children = new Tet[8];
    private Group    root3D;
    private SubScene scene3D;
    private double   mouseX, mouseY;
    private Group parentGroup;
    private Group childrenGroup;
    private Group midpointsGroup;
    private       CheckBox         showParentCheckBox;
    private       CheckBox         showMidpointsCheckBox;
    private       Slider           transparencySlider;
    private       ComboBox<String> parentTypeCombo;
    // Current parent tetrahedron
    private       Tet   currentParent;

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
        camera.setTranslateZ(-800);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        scene3D.setCamera(camera);

        // Add transforms
        root3D.getTransforms().addAll(rotateX, rotateY);

        // Scale to make tetrahedra visible
        Scale scale = new Scale(0.01, 0.01, 0.01); // Scale down from Tet coordinates
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

        // Create initial tetrahedron (Type 0 at level 10)
        updateParentTetrahedron(0);

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

    private void addEdge(Group parent, Point3i v1, Point3i v2, double thickness, PhongMaterial material) {
        double dx = v2.x - v1.x;
        double dy = v2.y - v1.y;
        double dz = v2.z - v1.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        Box edge = new Box(length, thickness, thickness);

        // Position at midpoint
        edge.setTranslateX((v1.x + v2.x) / 2.0);
        edge.setTranslateY((v1.y + v2.y) / 2.0);
        edge.setTranslateZ((v1.z + v2.z) / 2.0);

        // Rotate to align with edge direction
        if (length > 0) {
            double angleX = Math.toDegrees(Math.atan2(dz, dy));
            double angleY = Math.toDegrees(Math.atan2(-dx, Math.sqrt(dy * dy + dz * dz)));

            edge.getTransforms().addAll(new Rotate(angleX, Rotate.X_AXIS), new Rotate(angleY, Rotate.Y_AXIS));
        }

        edge.setMaterial(material);
        parent.getChildren().add(edge);
    }

    private void createAxes() {
        double axisLength = 2000;
        double axisThickness = 20;

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

        Label title = new Label("Bey Refinement Visualization");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        // Info text
        Label info = new Label(
        "Bey refinement subdivides a tetrahedron into\n" + "8 children by adding midpoints to each edge\n"
        + "and creating new tetrahedra.\n\n" + "Each child inherits the parent's properties\n"
        + "but at a finer level of detail.");
        info.setWrapText(true);
        info.setStyle("-fx-font-size: 11px;");

        Separator sep1 = new Separator();

        // Parent tetrahedron type selector
        Label parentLabel = new Label("Parent Tetrahedron Type:");
        parentTypeCombo = new ComboBox<>();
        parentTypeCombo.getItems().addAll("Type 0", "Type 1", "Type 2", "Type 3", "Type 4", "Type 5");
        parentTypeCombo.setValue("Type 0");
        parentTypeCombo.setOnAction(e -> {
            int type = parentTypeCombo.getSelectionModel().getSelectedIndex();
            updateParentTetrahedron(type);
        });

        Separator sep2 = new Separator();

        // Parent visibility
        showParentCheckBox = new CheckBox("Show Parent (wireframe)");
        showParentCheckBox.setSelected(true);
        showParentCheckBox.setOnAction(e -> updateVisibility());

        // Midpoints visibility
        showMidpointsCheckBox = new CheckBox("Show Edge Midpoints");
        showMidpointsCheckBox.setSelected(false);
        showMidpointsCheckBox.setOnAction(e -> updateVisibility());

        Separator sep3 = new Separator();

        // Children visibility controls
        Label childLabel = new Label("Show Children (Bey order):");
        VBox childControls = new VBox(3);

        for (int i = 0; i < 8; i++) {
            childVisibility[i] = new CheckBox("Child " + i);
            childVisibility[i].setSelected(true);
            childVisibility[i].setTextFill(CHILD_COLORS[i]);
            final int childIndex = i;
            childVisibility[i].setOnAction(e -> updateChildVisibility(childIndex));
            childControls.getChildren().add(childVisibility[i]);
        }

        Separator sep4 = new Separator();

        // Transparency control
        Label transLabel = new Label("Child Transparency:");
        transparencySlider = new Slider(0, 1, 0.6);
        transparencySlider.setShowTickLabels(true);
        transparencySlider.valueProperty().addListener((obs, oldVal, newVal) -> updateTransparency());

        // Algorithm explanation
        Separator sep5 = new Separator();
        Label algorithmInfo = new Label(
        "\nBey Algorithm:\n" + "1. Add midpoints to all 6 edges\n" + "2. Connect midpoints to create 8 new tetrahedra\n"
        + "3. Each child has 1/8 the volume of parent\n" + "4. Children perfectly tile the parent\n\n"
        + "This creates a hierarchical octree-like\n" + "structure for tetrahedral meshes.");
        algorithmInfo.setWrapText(true);
        algorithmInfo.setStyle("-fx-font-size: 10px; -fx-font-style: italic;");

        // Mouse help
        Label help = new Label("\nMouse: Left drag to rotate, scroll to zoom");
        help.setStyle("-fx-font-size: 9px;");

        controls.getChildren().addAll(title, info, sep1, parentLabel, parentTypeCombo, sep2, showParentCheckBox,
                                      showMidpointsCheckBox, sep3, childLabel, childControls, sep4, transLabel,
                                      transparencySlider, sep5, algorithmInfo, help);

        return controls;
    }

    private void createMidpointVisualization() {
        // Get the 4 vertices of the parent
        Point3i[] vertices = currentParent.coordinates();

        // Calculate midpoints of all 6 edges
        Point3f[] midpoints = new Point3f[6];
        midpoints[0] = midpoint(vertices[0], vertices[1]); // Edge 0-1
        midpoints[1] = midpoint(vertices[0], vertices[2]); // Edge 0-2
        midpoints[2] = midpoint(vertices[0], vertices[3]); // Edge 0-3
        midpoints[3] = midpoint(vertices[1], vertices[2]); // Edge 1-2
        midpoints[4] = midpoint(vertices[1], vertices[3]); // Edge 1-3
        midpoints[5] = midpoint(vertices[2], vertices[3]); // Edge 2-3

        PhongMaterial midpointMaterial = new PhongMaterial(Color.WHITE);

        for (int i = 0; i < 6; i++) {
            Sphere midpointSphere = new Sphere(50); // Visible size
            midpointSphere.setTranslateX(midpoints[i].x);
            midpointSphere.setTranslateY(midpoints[i].y);
            midpointSphere.setTranslateZ(midpoints[i].z);
            midpointSphere.setMaterial(midpointMaterial);
            midpointsGroup.getChildren().add(midpointSphere);
        }
    }

    private MeshView createTetrahedronMesh(Tet tet, Color color) {
        Point3i[] vertices = tet.coordinates();
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices
        for (Point3i vertex : vertices) {
            mesh.getPoints().addAll(vertex.x, vertex.y, vertex.z);
        }

        // Add texture coordinates (required)
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Add faces
        mesh.getFaces().addAll(0, 0, 2, 2, 1, 1,  // Face 0-2-1
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

    private MeshView createTetrahedronWireframe(Tet tet, Color color, double lineWidth) {
        Point3i[] vertices = tet.coordinates();

        // Create wireframe by drawing the 6 edges of the tetrahedron
        // Edges: 0-1, 0-2, 0-3, 1-2, 1-3, 2-3
        Group wireframe = new Group();

        PhongMaterial material = new PhongMaterial(color);

        // Helper method to add an edge
        addEdge(wireframe, vertices[0], vertices[1], lineWidth, material);
        addEdge(wireframe, vertices[0], vertices[2], lineWidth, material);
        addEdge(wireframe, vertices[0], vertices[3], lineWidth, material);
        addEdge(wireframe, vertices[1], vertices[2], lineWidth, material);
        addEdge(wireframe, vertices[1], vertices[3], lineWidth, material);
        addEdge(wireframe, vertices[2], vertices[3], lineWidth, material);

        parentGroup.getChildren().add(wireframe);

        // Return a dummy mesh (we added wireframe directly to parentGroup)
        return new MeshView(new TriangleMesh());
    }

    private Point3f midpoint(Point3i a, Point3i b) {
        return new Point3f((a.x + b.x) / 2.0f, (a.y + b.y) / 2.0f, (a.z + b.z) / 2.0f);
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

    private void updateChildVisibility(int childIndex) {
        if (childIndex < childrenGroup.getChildren().size()) {
            Node child = childrenGroup.getChildren().get(childIndex);
            child.setVisible(childVisibility[childIndex].isSelected());
        }
    }

    private void updateParentTetrahedron(int parentType) {
        // Clear existing visualization
        parentGroup.getChildren().clear();
        childrenGroup.getChildren().clear();
        midpointsGroup.getChildren().clear();

        // Create parent tetrahedron at level 10 for reasonable size
        byte level = 10;
        int cellSize = 1 << (20 - level); // 1024
        currentParent = new Tet(0, 0, 0, level, (byte) parentType);

        // Create parent visualization (wireframe)
        MeshView parentMesh = createTetrahedronWireframe(currentParent, Color.BLACK, 2.0);
        parentGroup.getChildren().add(parentMesh);

        // Get the 8 children using BeySubdivision
        for (int i = 0; i < 8; i++) {
            children[i] = BeySubdivision.getBeyChild(currentParent, i);

            // Create child visualization
            MeshView childMesh = createTetrahedronMesh(children[i], CHILD_COLORS[i]);
            childMesh.setUserData(i); // Store child index
            childrenGroup.getChildren().add(childMesh);
        }

        // Create midpoint spheres
        createMidpointVisualization();

        // Update visibility based on checkboxes
        updateVisibility();
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

    private void updateVisibility() {
        parentGroup.setVisible(showParentCheckBox.isSelected());
        midpointsGroup.setVisible(showMidpointsCheckBox.isSelected());

        // Update children visibility
        for (int i = 0; i < 8; i++) {
            updateChildVisibility(i);
        }
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {
        public static void main(String[] argv) {
            BeyRefinementDemo.main(argv);
        }
    }
}
