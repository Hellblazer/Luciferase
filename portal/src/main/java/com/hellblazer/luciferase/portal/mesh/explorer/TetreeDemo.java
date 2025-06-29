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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import javafx.scene.AmbientLight;
import javafx.scene.PointLight;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import com.hellblazer.luciferase.lucien.Constants;

/**
 * Demo application showing Tetree visualization with tetrahedral decomposition.
 *
 * @author hal.hildebrand
 */
public class TetreeDemo extends Application {

    private static final double            SCENE_WIDTH     = 1200;
    private static final double            SCENE_HEIGHT    = 800;
    private static final double            CAMERA_DISTANCE = 800;
    private final        Rotate            rotateX         = new Rotate(0, Rotate.X_AXIS);
    private final        Rotate            rotateY         = new Rotate(0, Rotate.Y_AXIS);
    private              Group             root3D;
    private              SubScene          scene3D;
    private              PerspectiveCamera camera;
    // Visualization controls
    private              CheckBox          showCubeDecomposition;
    private              CheckBox          showTetrahedralTypes;
    private              Slider            levelSlider;
    private              Label             infoLabel;
    // Mouse controls
    private              double            mouseX, mouseY;
    private double mouseOldX, mouseOldY;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Tetree Visualization Demo");

        // Create 3D content
        root3D = create3DContent();

        // Create camera
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000.0);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        camera.setTranslateY(-200);  // Move camera up a bit
        camera.setRotationAxis(Rotate.X_AXIS);
        camera.setRotate(-20);  // Tilt down slightly
        
        System.out.println("Camera setup: Y=" + camera.getTranslateY() + 
                          ", Z=" + camera.getTranslateZ() + ", Near=" + camera.getNearClip() + ", Far=" + camera.getFarClip());

        // Create 3D subscene
        scene3D = new SubScene(root3D, SCENE_WIDTH - 300, SCENE_HEIGHT, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.DARKGRAY);
        scene3D.setCamera(camera);
        
        // Add lighting
        AmbientLight ambientLight = new AmbientLight(Color.rgb(80, 80, 80));
        PointLight pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(400);
        pointLight.setTranslateY(-400);
        pointLight.setTranslateZ(-400);
        
        root3D.getChildren().addAll(ambientLight, pointLight);

        // Main layout
        BorderPane mainLayout = new BorderPane();
        mainLayout.setCenter(scene3D);

        // Control panel
        VBox controlPanel = createControlPanel();
        mainLayout.setRight(controlPanel);

        // Create main scene
        Scene scene = new Scene(mainLayout);

        // Add mouse controls
        setupMouseControls(scene);

        primaryStage.setScene(scene);
        primaryStage.show();

        // Initial visualization
        updateVisualization();
    }

    private Group create3DContent() {
        Group world = new Group();

        // Apply rotations to world
        world.getTransforms().addAll(rotateX, rotateY);

        return world;
    }

    private Group createAxes() {
        Group axes = new Group();

        // Create axes starting from origin (0,0,0) to show positive quadrant
        double axisLength = 500;
        
        // X axis - Red (pointing right)
        Box xAxis = new Box(axisLength, 2, 2);
        xAxis.setMaterial(new PhongMaterial(Color.RED));
        xAxis.setTranslateX(axisLength / 2);  // Position from 0 to axisLength

        // Y axis - Green (pointing up)
        Box yAxis = new Box(2, axisLength, 2);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));
        yAxis.setTranslateY(axisLength / 2);  // Position from 0 to axisLength

        // Z axis - Blue (pointing forward)
        Box zAxis = new Box(2, 2, axisLength);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));
        zAxis.setTranslateZ(axisLength / 2);  // Position from 0 to axisLength
        
        // Add small spheres at origin to mark (0,0,0)
        javafx.scene.shape.Sphere origin = new javafx.scene.shape.Sphere(5);
        origin.setMaterial(new PhongMaterial(Color.WHITE));

        axes.getChildren().addAll(xAxis, yAxis, zAxis, origin);
        return axes;
    }

    private Group createCharacteristicTypes() {
        Group types = new Group();

        // Create 6 separate tetrahedra showing the characteristic types
        // Keep all coordinates positive for Tetree compatibility
        double spacing = 150;
        double size = 60;
        double baseX = 50;  // Start at positive X
        double baseY = 50;  // Start at positive Y

        Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN };

        for (int i = 0; i < 6; i++) {
            double x = baseX + (i % 3) * spacing;
            double y = baseY + (i < 3 ? 0 : 1) * spacing;

            // Create a standard tetrahedron for each type
            Point3f v0 = new Point3f((float) x, (float) y, 0);
            Point3f v1 = new Point3f((float) (x + size), (float) y, 0);
            Point3f v2 = new Point3f((float) (x + size / 2), (float) (y + size * 0.866), 0);
            Point3f v3 = new Point3f((float) (x + size / 2), (float) (y + size * 0.289), (float) (size * 0.816));

            MeshView tet = createTetrahedronMesh(v0, v1, v2, v3, colors[i].deriveColor(0, 1, 1, 0.7));
            types.getChildren().add(tet);
        }

        return types;
    }

    private VBox createControlPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: rgba(255, 255, 255, 0.9);");
        panel.setPrefWidth(300);

        // Title
        Label title = new Label("Tetree Visualization");
        title.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");
        panel.getChildren().add(title);

        panel.getChildren().add(new Separator());

        // Info label
        infoLabel = new Label("Tetrahedral decomposition of space");
        infoLabel.setWrapText(true);
        panel.getChildren().add(infoLabel);

        panel.getChildren().add(new Separator());

        // Visualization options
        Label optionsLabel = new Label("Visualization Options:");
        optionsLabel.setStyle("-fx-font-weight: bold;");
        panel.getChildren().add(optionsLabel);

        showCubeDecomposition = new CheckBox("Show Cube Decomposition");
        showCubeDecomposition.setSelected(true);
        showCubeDecomposition.setOnAction(e -> updateVisualization());

        showTetrahedralTypes = new CheckBox("Show 6 Characteristic Types");
        showTetrahedralTypes.setOnAction(e -> updateVisualization());

        panel.getChildren().addAll(showCubeDecomposition, showTetrahedralTypes);

        panel.getChildren().add(new Separator());

        // Level control
        Label levelLabel = new Label("Subdivision Level:");
        panel.getChildren().add(levelLabel);

        levelSlider = new Slider(-1, 3, 0);
        levelSlider.setShowTickMarks(true);
        levelSlider.setShowTickLabels(true);
        levelSlider.setMajorTickUnit(1);
        levelSlider.setMinorTickCount(0);
        levelSlider.setSnapToTicks(true);
        levelSlider.valueProperty().addListener((obs, old, val) -> updateVisualization());

        Label levelValue = new Label("Level: 0");
        levelSlider.valueProperty().addListener((obs, old, val) -> {
            int level = val.intValue();
            levelValue.setText(level == -1 ? "Debug Mode" : "Level: " + level);
        });

        panel.getChildren().addAll(levelSlider, levelValue);

        panel.getChildren().add(new Separator());

        // Type colors legend
        Label legendLabel = new Label("Tetrahedron Types:");
        legendLabel.setStyle("-fx-font-weight: bold;");
        panel.getChildren().add(legendLabel);

        GridPane legend = new GridPane();
        legend.setHgap(10);
        legend.setVgap(5);

        String[] typeNames = { "S0 (Red)", "S1 (Green)", "S2 (Blue)", "S3 (Yellow)", "S4 (Magenta)", "S5 (Cyan)" };
        Color[] typeColors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN };

        for (int i = 0; i < 6; i++) {
            Box colorBox = new Box(15, 15, 15);
            PhongMaterial material = new PhongMaterial(typeColors[i]);
            colorBox.setMaterial(material);

            Label label = new Label(typeNames[i]);

            legend.add(colorBox, 0, i);
            legend.add(label, 1, i);
        }

        panel.getChildren().add(legend);

        panel.getChildren().add(new Separator());

        // Info text
        TextArea infoText = new TextArea("The Tetree uses tetrahedral decomposition of space.\n\n"
                                         + "A cube can be decomposed into 6 characteristic tetrahedra (S0-S5), "
                                         + "which form the basis for the spatial indexing structure.\n\n"
                                         + "Each tetrahedron can be further subdivided into 8 smaller tetrahedra, "
                                         + "creating a hierarchical structure similar to an octree but using "
                                         + "tetrahedral cells instead of cubic ones.");
        infoText.setWrapText(true);
        infoText.setEditable(false);
        infoText.setPrefRowCount(8);
        panel.getChildren().add(infoText);

        return panel;
    }

    private Group createCubeDecomposition() {
        Group decomposition = new Group();

        // Create wireframe cube in positive quadrant
        double size = 200;
        double offset = 100;  // Position cube at positive coordinates

        // Cube vertices - all in positive quadrant
        Point3f[] cubeVertices = { new Point3f((float) offset, (float) offset, (float) offset),              // 0
                                   new Point3f((float) (offset + size), (float) offset, (float) offset),     // 1
                                   new Point3f((float) (offset + size), (float) (offset + size), (float) offset),  // 2
                                   new Point3f((float) offset, (float) (offset + size), (float) offset),     // 3
                                   new Point3f((float) offset, (float) offset, (float) (offset + size)),     // 4
                                   new Point3f((float) (offset + size), (float) offset, (float) (offset + size)),  // 5
                                   new Point3f((float) (offset + size), (float) (offset + size), (float) (offset + size)), // 6
                                   new Point3f((float) offset, (float) (offset + size), (float) (offset + size))   // 7
        };

        // Create the 6 characteristic tetrahedra using SIMPLEX_STANDARD ordering
        // S0-S5 decomposition of the cube - these completely fill the cube with no overlap
        // From Constants.SIMPLEX_STANDARD - these are the 6 tetrahedra that decompose a cube
        int[][] tetIndices = { 
            { 0, 1, 5, 7 },  // S0: {c0, c1, c5, c7}
            { 0, 7, 3, 1 },  // S1: {c0, c7, c3, c1} 
            { 0, 2, 3, 7 },  // S2: {c0, c2, c3, c7}
            { 0, 7, 6, 2 },  // S3: {c0, c7, c6, c2}
            { 0, 4, 6, 7 },  // S4: {c0, c4, c6, c7}
            { 0, 7, 5, 4 }   // S5: {c0, c7, c5, c4}
        };

        Color[] colors = { Color.RED.deriveColor(0, 1, 1, 0.3), Color.GREEN.deriveColor(0, 1, 1, 0.3),
                           Color.BLUE.deriveColor(0, 1, 1, 0.3), Color.YELLOW.deriveColor(0, 1, 1, 0.3),
                           Color.MAGENTA.deriveColor(0, 1, 1, 0.3), Color.CYAN.deriveColor(0, 1, 1, 0.3) };

        for (int i = 0; i < 6; i++) {
            MeshView tet = createTetrahedronMesh(cubeVertices[tetIndices[i][0]], cubeVertices[tetIndices[i][1]],
                                                 cubeVertices[tetIndices[i][2]], cubeVertices[tetIndices[i][3]],
                                                 colors[i]);
            decomposition.getChildren().add(tet);
        }

        return decomposition;
    }

    private Group createSubdividedTetree(int level) {
        Group container = new Group();
        
        // For debugging, let's create a simple visible tetrahedron first
        if (level == -1) { // Debug mode
            // Create a simple tetrahedron near origin
            Point3f v0 = new Point3f(0, 0, 0);
            Point3f v1 = new Point3f(200, 0, 0);
            Point3f v2 = new Point3f(100, 200, 0);
            Point3f v3 = new Point3f(100, 66, 200);
            
            System.out.println("Creating debug tetrahedron:");
            System.out.println("  v0: " + v0);
            System.out.println("  v1: " + v1);
            System.out.println("  v2: " + v2);
            System.out.println("  v3: " + v3);
            
            MeshView debugTet = createTetrahedronMesh(v0, v1, v2, v3, Color.RED.deriveColor(0, 1, 1, 0.9));
            container.getChildren().add(debugTet);
            
            // Add spheres at each vertex
            for (Point3f v : new Point3f[]{v0, v1, v2, v3}) {
                javafx.scene.shape.Sphere sphere = new javafx.scene.shape.Sphere(10);
                sphere.setMaterial(new PhongMaterial(Color.YELLOW));
                sphere.setTranslateX(v.x);
                sphere.setTranslateY(v.y);
                sphere.setTranslateZ(v.z);
                container.getChildren().add(sphere);
            }
            
            System.out.println("Debug mode: Created tetrahedron with yellow spheres at vertices");
            return container;
        }
        
        // Normal tetree visualization
        // Use a much higher level for smaller tetrahedra that fit in view
        byte workingLevel = 15;  // Level 15 gives us size 64 instead of 2048
        Tet rootTet = new Tet(100, 100, 100, workingLevel, (byte)0);  // Start near origin
        
        System.out.println("Creating subdivided tetree at level " + level);
        System.out.println("Root tet at level " + workingLevel + ": " + rootTet);
        System.out.println("Root tet size: " + rootTet.length());
        
        // Get the coordinates to see the actual size
        Point3i[] coords = rootTet.coordinates();
        System.out.println("Root tet coordinates:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + coords[i]);
        }
        
        // Subdivide using the Tet class methods
        tetreeSubdivideUsingTet(container, rootTet, 0, level, Color.LIGHTBLUE);
        
        System.out.println("Container has " + container.getChildren().size() + " children");
        
        // For smaller tetrahedra, we don't need to center
        
        return container;
    }

    private MeshView createTetrahedronMesh(Point3f v0, Point3f v1, Point3f v2, Point3f v3, Color color) {
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices
        mesh.getPoints().addAll(v0.x, v0.y, v0.z, v1.x, v1.y, v1.z, v2.x, v2.y, v2.z, v3.x, v3.y, v3.z);

        // Add texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Define faces - use consistent winding for all tetrahedra
        // For regular tetrahedron, use counterclockwise when viewed from outside
        mesh.getFaces().addAll(
            0, 0, 1, 1, 2, 2,  // Face 0-1-2
            0, 0, 2, 2, 3, 3,  // Face 0-2-3
            0, 0, 3, 3, 1, 1,  // Face 0-3-1
            1, 1, 3, 3, 2, 2   // Face 1-3-2
        );

        MeshView meshView = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        material.setDiffuseColor(color);
        meshView.setMaterial(material);
        
        // Set opacity for transparency
        meshView.setOpacity(color.getOpacity());
        
        // Disable face culling to see all faces
        meshView.setCullFace(javafx.scene.shape.CullFace.NONE);

        return meshView;
    }


    private void setupMouseControls(Scene scene) {
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
    }

    /**
     * Subdivide a tetrahedron using the Tet class's subdivision logic.
     * This ensures correct child types and positions according to the Tetree algorithm.
     */
    private void tetreeSubdivideUsingTet(Group parent, Tet tet, int currentLevel, int targetLevel, Color baseColor) {
        // Only log first few to avoid spam
        if (parent.getChildren().size() < 10) {
            System.out.println("Subdividing tet at level " + currentLevel + " (target: " + targetLevel + "), tet level: " + tet.l());
        }
        
        // Always visualize the current tetrahedron
        MeshView tetMesh = createTetrahedronFromTet(tet, baseColor, currentLevel);
        parent.getChildren().add(tetMesh);
        
        // Continue subdividing if we haven't reached the target level
        if (currentLevel < targetLevel && tet.l() < Constants.getMaxRefinementLevel()) {
            // Use Morton ordering as intended for the space-filling curve
            System.out.println("Creating children using Morton ordering for parent type " + tet.type());
            
            // Get all 8 children using Morton ordering (standard subdivide method)
            Tet[] children = tet.subdivide();
            
            // Debug: show child positions and how Morton indices map to Bey IDs
            if (currentLevel < 2) {
                for (int i = 0; i < 8; i++) {
                    Tet child = children[i];
                    byte beyId = TetreeConnectivity.getBeyChildId(tet.type(), i);
                    System.out.println("  Morton " + i + " → Bey " + beyId + ": child type " + child.type() + 
                                     " at (" + child.x() + ", " + child.y() + ", " + child.z() + ")");
                }
            }
            
            // Visualize all 8 children in Morton order
            for (int i = 0; i < 8; i++) {
                // Use different colors for different children
                Color childColor = baseColor.deriveColor(i * 45, 1, 0.9, 1);
                tetreeSubdivideUsingTet(parent, children[i], currentLevel + 1, targetLevel, childColor);
            }
        }
    }
    
    /**
     * Create a child tetrahedron using direct Bey ID instead of Morton ordering.
     * This creates more intuitive tetrahedral subdivisions.
     */
    private Tet createChildWithBeyId(Tet parent, int beyId) {
        byte parentType = parent.type();
        byte childLevel = (byte) (parent.l() + 1);
        
        // Get child type directly from Bey ID (no Morton conversion)
        byte childType = TetreeConnectivity.getChildType(parentType, beyId);
        
        // Get vertex for this Bey ID
        byte vertex = TetreeConnectivity.getBeyVertex((byte) beyId);
        
        // Get all parent vertices and select the one we need
        Point3i[] parentVertices = parent.coordinates();
        Point3i vertexCoords;
        
        if (vertex >= 0 && vertex < parentVertices.length) {
            vertexCoords = parentVertices[vertex];
        } else {
            // Fallback to parent anchor if vertex index is invalid
            vertexCoords = new Point3i(parent.x(), parent.y(), parent.z());
        }
        
        // Child anchor is midpoint between parent anchor and the defining vertex
        int childX = (parent.x() + vertexCoords.x) >> 1;  // Bit shift for division by 2
        int childY = (parent.y() + vertexCoords.y) >> 1;
        int childZ = (parent.z() + vertexCoords.z) >> 1;
        
        return new Tet(childX, childY, childZ, childLevel, childType);
    }
    
    /**
     * Create a tetrahedron mesh from a Tet object with correct face winding for its type.
     */
    private MeshView createTetrahedronFromTet(Tet tet, Color baseColor, int level) {
        // Use coordinates() method - it produces correct outward normals for some types
        Point3i[] coords = tet.coordinates();
        
        Point3f v0 = new Point3f(coords[0].x, coords[0].y, coords[0].z);
        Point3f v1 = new Point3f(coords[1].x, coords[1].y, coords[1].z);
        Point3f v2 = new Point3f(coords[2].x, coords[2].y, coords[2].z);
        Point3f v3 = new Point3f(coords[3].x, coords[3].y, coords[3].z);
        
        // Always output for debugging
        System.out.println("Creating mesh for tet type " + tet.type() + " at level " + level);
        System.out.println("  v0: " + v0);
        System.out.println("  v1: " + v1);
        System.out.println("  v2: " + v2);
        System.out.println("  v3: " + v3);
        
        // Calculate bounds
        float minX = Math.min(Math.min(v0.x, v1.x), Math.min(v2.x, v3.x));
        float maxX = Math.max(Math.max(v0.x, v1.x), Math.max(v2.x, v3.x));
        float minY = Math.min(Math.min(v0.y, v1.y), Math.min(v2.y, v3.y));
        float maxY = Math.max(Math.max(v0.y, v1.y), Math.max(v2.y, v3.y));
        float minZ = Math.min(Math.min(v0.z, v1.z), Math.min(v2.z, v3.z));
        float maxZ = Math.max(Math.max(v0.z, v1.z), Math.max(v2.z, v3.z));
        
        System.out.println("  Bounds: X[" + minX + ", " + maxX + "], Y[" + minY + ", " + maxY + "], Z[" + minZ + ", " + maxZ + "]");
        
        double opacity = 0.9 - level * 0.05;  // Very opaque
        Color color = baseColor.deriveColor(0, 1, 1 - level * 0.1, Math.max(0.7, opacity));
        
        // Create mesh
        return createTetrahedronMeshForType(v0, v1, v2, v3, color);
    }
    
    private MeshView createTetrahedronMeshForType(Point3f v0, Point3f v1, Point3f v2, Point3f v3, 
                                                  Color color) {
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices
        mesh.getPoints().addAll(v0.x, v0.y, v0.z, v1.x, v1.y, v1.z, v2.x, v2.y, v2.z, v3.x, v3.y, v3.z);

        // Add texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Use simple face winding that should be visible from all angles
        // since we have CullFace.NONE
        mesh.getFaces().addAll(
            0, 0, 1, 1, 2, 2,  // Face 0-1-2
            0, 0, 2, 2, 3, 3,  // Face 0-2-3
            0, 0, 3, 3, 1, 1,  // Face 0-3-1
            1, 1, 3, 3, 2, 2   // Face 1-3-2
        );

        MeshView meshView = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        material.setDiffuseColor(color);
        meshView.setMaterial(material);
        
        // Set opacity for transparency
        meshView.setOpacity(color.getOpacity());
        
        // Disable face culling to see all faces
        meshView.setCullFace(javafx.scene.shape.CullFace.NONE);

        return meshView;
    }
    

    private void updateVisualization() {
        root3D.getChildren().clear();
        
        // Always re-add lighting when clearing
        AmbientLight ambientLight = new AmbientLight(Color.rgb(80, 80, 80));
        PointLight pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(400);
        pointLight.setTranslateY(-400);
        pointLight.setTranslateZ(-400);
        root3D.getChildren().addAll(ambientLight, pointLight);

        // Add coordinate axes
        root3D.getChildren().add(createAxes());

        if (showCubeDecomposition.isSelected()) {
            root3D.getChildren().add(createCubeDecomposition());
        }

        if (showTetrahedralTypes.isSelected()) {
            root3D.getChildren().add(createCharacteristicTypes());
        }

        // Add subdivided tetrahedra based on level
        int level = (int) levelSlider.getValue();
        // Always show the tetree, even at level 0 (just the root)
        Group tetreeGroup = createSubdividedTetree(level);
        System.out.println("Tetree group bounds: " + tetreeGroup.getBoundsInParent());
        root3D.getChildren().add(tetreeGroup);
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            TetreeDemo.main(argv);
        }
    }
}
