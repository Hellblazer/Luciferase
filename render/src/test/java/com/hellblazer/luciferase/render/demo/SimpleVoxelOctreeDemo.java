package com.hellblazer.luciferase.render.demo;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simple demo showing sparse voxel octree vs dense grid comparison.
 * This demo works without GPU dependencies for testing the octree structure.
 */
public class SimpleVoxelOctreeDemo extends Application {
    
    // Scene parameters
    private static final double SCENE_WIDTH = 1200;
    private static final double SCENE_HEIGHT = 600;
    private static final int DEFAULT_GRID_SIZE = 32;
    
    // 3D components
    private Group denseGroup;
    private Group sparseGroup;
    private SubScene denseScene;
    private SubScene sparseScene;
    
    // Controls
    private Slider densitySlider;
    private Label statsLabel;
    private CheckBox animateCheckBox;
    
    // Data structures
    private boolean[][][] denseGrid;
    private EnhancedVoxelOctreeNode octreeRoot;
    private int currentGridSize = DEFAULT_GRID_SIZE;
    private double voxelDensity = 0.1; // 10% density by default
    
    // Stats
    private int denseVoxelCount = 0;
    private int sparseNodeCount = 0;
    private long denseMemoryBytes = 0;
    private long sparseMemoryBytes = 0;
    
    // Visualization
    private int octreeVisualizationDepth = 4; // Show full depth by default
    
    // Animation
    private Rotate denseRotateY = new Rotate(0, Rotate.Y_AXIS);
    private Rotate sparseRotateY = new Rotate(0, Rotate.Y_AXIS);
    
    public static void main(String[] args) {
        launch(args);
    }
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Simple Voxel Octree Demo - Dense vs Sparse Comparison");
        
        BorderPane root = new BorderPane();
        
        // Create side-by-side 3D scenes
        HBox scenesBox = new HBox(10);
        scenesBox.setPadding(new Insets(10));
        
        VBox denseBox = new VBox(5);
        denseBox.getChildren().add(new Label("Dense Voxel Grid"));
        denseScene = create3DScene(denseGroup = new Group(), denseRotateY);
        denseBox.getChildren().add(denseScene);
        
        VBox sparseBox = new VBox(5);
        sparseBox.getChildren().add(new Label("Sparse Voxel Octree"));
        sparseScene = create3DScene(sparseGroup = new Group(), sparseRotateY);
        sparseBox.getChildren().add(sparseScene);
        
        scenesBox.getChildren().addAll(denseBox, sparseBox);
        root.setCenter(scenesBox);
        
        // Create control panel
        root.setBottom(createControlPanel());
        
        // Create initial voxel data
        generateVoxelData();
        updateVisualization();
        
        // Start animation
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (animateCheckBox != null && animateCheckBox.isSelected()) {
                    denseRotateY.setAngle(denseRotateY.getAngle() + 0.5);
                    sparseRotateY.setAngle(sparseRotateY.getAngle() + 0.5);
                }
            }
        };
        timer.start();
        
        Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT + 150);
        primaryStage.setScene(scene);
        primaryStage.show();
    }
    
    private SubScene create3DScene(Group voxelGroup, Rotate rotateY) {
        // Camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-60);
        camera.setNearClip(0.1);
        camera.setFarClip(1000.0);
        
        // Lighting
        PointLight light = new PointLight(Color.WHITE);
        light.setTranslateX(20);
        light.setTranslateY(-20);
        light.setTranslateZ(-30);
        
        AmbientLight ambientLight = new AmbientLight(Color.gray(0.4));
        
        // Root group
        Group root3D = new Group();
        root3D.getChildren().addAll(voxelGroup, light, ambientLight);
        
        // Add rotation
        Rotate rotateX = new Rotate(20, Rotate.X_AXIS);
        root3D.getTransforms().addAll(rotateX, rotateY);
        
        // Create subscene
        SubScene scene3D = new SubScene(root3D, (SCENE_WIDTH/2) - 20, SCENE_HEIGHT - 50, 
                                       true, SceneAntialiasing.BALANCED);
        scene3D.setCamera(camera);
        scene3D.setFill(Color.DARKSLATEGRAY);
        
        // Add mouse controls with rotation transforms
        setupMouseControls(scene3D, root3D, rotateX, rotateY);
        
        return scene3D;
    }
    
    private VBox createControlPanel() {
        VBox controls = new VBox(10);
        controls.setPadding(new Insets(10));
        controls.setStyle("-fx-background-color: #f0f0f0;");
        
        HBox controlsRow = new HBox(20);
        
        // Density control
        VBox densityBox = new VBox(5);
        densityBox.getChildren().add(new Label("Voxel Density:"));
        densitySlider = new Slider(0.01, 0.5, voxelDensity);
        densitySlider.setShowTickLabels(true);
        densitySlider.setShowTickMarks(true);
        densitySlider.setPrefWidth(200);
        Label densityLabel = new Label(String.format("%.0f%%", voxelDensity * 100));
        densitySlider.valueProperty().addListener((obs, old, val) -> {
            voxelDensity = val.doubleValue();
            densityLabel.setText(String.format("%.0f%%", voxelDensity * 100));
        });
        densityBox.getChildren().addAll(densitySlider, densityLabel);
        
        // Octree depth control
        VBox depthBox = new VBox(5);
        depthBox.getChildren().add(new Label("Octree Display Depth:"));
        Slider depthSlider = new Slider(1, 4, Math.min(4, octreeVisualizationDepth));
        depthSlider.setShowTickLabels(true);
        depthSlider.setShowTickMarks(true);
        depthSlider.setMajorTickUnit(1);
        depthSlider.setMinorTickCount(0);
        depthSlider.setSnapToTicks(true);
        depthSlider.setPrefWidth(150);
        Label depthLabel = new Label("Depth: " + octreeVisualizationDepth);
        depthSlider.valueProperty().addListener((obs, old, val) -> {
            octreeVisualizationDepth = val.intValue();
            depthLabel.setText("Depth: " + octreeVisualizationDepth);
            updateSparseVisualization();
        });
        depthBox.getChildren().addAll(depthSlider, depthLabel);
        
        // Buttons
        VBox buttonsBox = new VBox(5);
        Button generateBtn = new Button("Generate");
        generateBtn.setOnAction(e -> {
            generateVoxelData();
            updateVisualization();
        });
        
        Button sphereBtn = new Button("Sphere Pattern");
        sphereBtn.setOnAction(e -> {
            generateSpherePattern();
            updateVisualization();
        });
        
        Button pyramidBtn = new Button("Pyramid Pattern");
        pyramidBtn.setOnAction(e -> {
            generatePyramidPattern();
            updateVisualization();
        });
        
        buttonsBox.getChildren().addAll(generateBtn, sphereBtn, pyramidBtn);
        
        // Animation checkbox
        animateCheckBox = new CheckBox("Animate");
        animateCheckBox.setSelected(true);
        
        controlsRow.getChildren().addAll(densityBox, depthBox, buttonsBox, animateCheckBox);
        
        // Stats display
        statsLabel = new Label("Statistics will appear here");
        statsLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        
        controls.getChildren().addAll(controlsRow, new Separator(), statsLabel);
        
        return controls;
    }
    
    private void setupMouseControls(SubScene scene, Group root3D, Rotate rotateX, Rotate rotateY) {
        final double[] mouseOldX = {0};
        final double[] mouseOldY = {0};
        
        scene.setOnMousePressed(me -> {
            mouseOldX[0] = me.getSceneX();
            mouseOldY[0] = me.getSceneY();
        });
        
        scene.setOnMouseDragged(me -> {
            double deltaX = me.getSceneX() - mouseOldX[0];
            double deltaY = me.getSceneY() - mouseOldY[0];
            
            if (me.isPrimaryButtonDown()) {
                rotateY.setAngle(rotateY.getAngle() + deltaX);
                rotateX.setAngle(rotateX.getAngle() - deltaY);
            }
            
            mouseOldX[0] = me.getSceneX();
            mouseOldY[0] = me.getSceneY();
        });
        
        scene.setOnScroll(se -> {
            double delta = se.getDeltaY();
            root3D.setTranslateZ(root3D.getTranslateZ() + delta * 0.1);
        });
    }
    
    private void generateVoxelData() {
        // Initialize dense grid
        denseGrid = new boolean[currentGridSize][currentGridSize][currentGridSize];
        Random random = new Random();
        denseVoxelCount = 0;
        
        // Generate clustered voxels instead of random for better octree compression
        // Create several random clusters
        int numClusters = (int)(voxelDensity * 10) + 1;
        
        for (int c = 0; c < numClusters; c++) {
            // Random cluster center
            int cx = random.nextInt(currentGridSize);
            int cy = random.nextInt(currentGridSize);
            int cz = random.nextInt(currentGridSize);
            
            // Cluster radius
            int radius = 2 + random.nextInt(4);
            
            // Fill cluster
            for (int x = Math.max(0, cx - radius); x <= Math.min(currentGridSize - 1, cx + radius); x++) {
                for (int y = Math.max(0, cy - radius); y <= Math.min(currentGridSize - 1, cy + radius); y++) {
                    for (int z = Math.max(0, cz - radius); z <= Math.min(currentGridSize - 1, cz + radius); z++) {
                        double dist = Math.sqrt((x-cx)*(x-cx) + (y-cy)*(y-cy) + (z-cz)*(z-cz));
                        if (dist <= radius && random.nextDouble() < 0.8) {
                            denseGrid[x][y][z] = true;
                            denseVoxelCount++;
                        }
                    }
                }
            }
        }
        
        // Build octree from dense grid
        buildOctreeFromGrid();
    }
    
    private void generateSpherePattern() {
        denseGrid = new boolean[currentGridSize][currentGridSize][currentGridSize];
        denseVoxelCount = 0;
        
        float center = currentGridSize / 2.0f;
        float radius = currentGridSize * 0.4f;
        
        for (int x = 0; x < currentGridSize; x++) {
            for (int y = 0; y < currentGridSize; y++) {
                for (int z = 0; z < currentGridSize; z++) {
                    float dx = x - center;
                    float dy = y - center;
                    float dz = z - center;
                    float dist = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
                    
                    if (dist <= radius) {
                        denseGrid[x][y][z] = true;
                        denseVoxelCount++;
                    }
                }
            }
        }
        
        buildOctreeFromGrid();
    }
    
    private void generatePyramidPattern() {
        denseGrid = new boolean[currentGridSize][currentGridSize][currentGridSize];
        denseVoxelCount = 0;
        
        for (int y = 0; y < currentGridSize; y++) {
            int levelSize = currentGridSize - y;
            int offset = y / 2;
            
            for (int x = offset; x < offset + levelSize && x < currentGridSize; x++) {
                for (int z = offset; z < offset + levelSize && z < currentGridSize; z++) {
                    denseGrid[x][y][z] = true;
                    denseVoxelCount++;
                }
            }
        }
        
        buildOctreeFromGrid();
    }
    
    private void buildOctreeFromGrid() {
        float[] boundsMin = {0, 0, 0};
        float[] boundsMax = {currentGridSize, currentGridSize, currentGridSize};
        octreeRoot = new EnhancedVoxelOctreeNode(boundsMin, boundsMax, 0, 0);
        
        // Insert voxels into octree
        for (int x = 0; x < currentGridSize; x++) {
            for (int y = 0; y < currentGridSize; y++) {
                for (int z = 0; z < currentGridSize; z++) {
                    if (denseGrid[x][y][z]) {
                        float[] pos = {x + 0.5f, y + 0.5f, z + 0.5f};
                        
                        // Create color based on position
                        int r = (x * 255) / currentGridSize;
                        int g = (y * 255) / currentGridSize;
                        int b = (z * 255) / currentGridSize;
                        int color = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                        
                        octreeRoot.insertVoxel(pos, color, 4); // Max depth of 4 for better compression
                    }
                }
            }
        }
        
        // Compute average colors for LOD
        octreeRoot.computeAverageColors();
        
        // Get stats
        sparseNodeCount = octreeRoot.getNodeCount();
        sparseMemoryBytes = octreeRoot.getSubtreeSize();
    }
    
    private void updateVisualization() {
        // Update dense visualization
        updateDenseVisualization();
        
        // Update sparse visualization
        updateSparseVisualization();
        
        // Update statistics
        updateStatistics();
    }
    
    private void updateDenseVisualization() {
        denseGroup.getChildren().clear();
        List<Box> voxels = new ArrayList<>();
        
        float voxelSize = 30.0f / currentGridSize;
        
        for (int x = 0; x < currentGridSize; x++) {
            for (int y = 0; y < currentGridSize; y++) {
                for (int z = 0; z < currentGridSize; z++) {
                    if (denseGrid[x][y][z]) {
                        Box voxel = new Box(voxelSize * 0.9, voxelSize * 0.9, voxelSize * 0.9);
                        
                        voxel.setTranslateX((x - currentGridSize/2.0) * voxelSize);
                        voxel.setTranslateY((y - currentGridSize/2.0) * voxelSize);
                        voxel.setTranslateZ((z - currentGridSize/2.0) * voxelSize);
                        
                        PhongMaterial material = new PhongMaterial();
                        material.setDiffuseColor(Color.hsb(
                            (x * 360.0 / currentGridSize),
                            0.8,
                            0.8
                        ));
                        voxel.setMaterial(material);
                        
                        voxels.add(voxel);
                    }
                }
            }
        }
        
        denseGroup.getChildren().addAll(voxels);
        // More realistic memory calculation: boolean array + metadata
        denseMemoryBytes = (currentGridSize * currentGridSize * currentGridSize) / 8; // 1 bit per voxel
    }
    
    private void updateSparseVisualization() {
        sparseGroup.getChildren().clear();
        List<Node> nodes = new ArrayList<>();
        
        // Visualize octree - show all levels to match dense view
        visualizeOctreeNode(octreeRoot, nodes, 0, octreeVisualizationDepth);
        
        sparseGroup.getChildren().addAll(nodes);
    }
    
    private void visualizeOctreeNode(EnhancedVoxelOctreeNode node, List<Node> nodes, int depth, int maxDepth) {
        if (node == null) return;
        
        // Skip nodes with no voxels
        if (node.getVoxelCount() == 0) return;
        
        float[] min = node.getBoundsMin();
        float[] max = node.getBoundsMax();
        
        float centerX = (min[0] + max[0]) / 2.0f;
        float centerY = (min[1] + max[1]) / 2.0f;
        float centerZ = (min[2] + max[2]) / 2.0f;
        
        float sizeX = max[0] - min[0];
        float sizeY = max[1] - min[1];
        float sizeZ = max[2] - min[2];
        
        float voxelSize = 30.0f / currentGridSize;
        
        if (node.isLeaf() || depth >= maxDepth) {
            // Draw as voxel block only if it has voxels
            if (node.getVoxelCount() > 0) {
                Box voxel = new Box(
                    sizeX * voxelSize * 0.9,
                    sizeY * voxelSize * 0.9,
                    sizeZ * voxelSize * 0.9
                );
                
                voxel.setTranslateX((centerX - currentGridSize/2.0f) * voxelSize);
                voxel.setTranslateY((centerY - currentGridSize/2.0f) * voxelSize);
                voxel.setTranslateZ((centerZ - currentGridSize/2.0f) * voxelSize);
                
                PhongMaterial material = new PhongMaterial();
                int packedColor = node.getPackedColor();
                material.setDiffuseColor(new Color(
                    ((packedColor >> 24) & 0xFF) / 255.0,
                    ((packedColor >> 16) & 0xFF) / 255.0,
                    ((packedColor >> 8) & 0xFF) / 255.0,
                    (packedColor & 0xFF) / 255.0
                ));
                voxel.setMaterial(material);
                
                nodes.add(voxel);
            }
        } else {
            // Recursively visualize children
            for (int i = 0; i < 8; i++) {
                if (node.hasChild(i)) {
                    EnhancedVoxelOctreeNode child = node.getChild(i);
                    if (child != null && child.getVoxelCount() > 0) {
                        visualizeOctreeNode(child, nodes, depth + 1, maxDepth);
                    }
                }
            }
        }
    }
    
    private void updateStatistics() {
        DecimalFormat df = new DecimalFormat("#,###");
        DecimalFormat df2 = new DecimalFormat("#.##");
        
        double compressionRatio = denseMemoryBytes > 0 ? 
            (double)sparseMemoryBytes / denseMemoryBytes * 100 : 0;
        
        String stats = String.format(
            "Dense Grid:  %s voxels, %s KB memory\n" +
            "Sparse Tree: %s nodes, %s KB memory\n" +
            "Compression: %s%% of original size\n" +
            "Memory Savings: %s%%",
            df.format(denseVoxelCount),
            df2.format(denseMemoryBytes / 1024.0),
            df.format(sparseNodeCount),
            df2.format(sparseMemoryBytes / 1024.0),
            df2.format(compressionRatio),
            df2.format(100 - compressionRatio)
        );
        
        statsLabel.setText(stats);
    }
    
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(SimpleVoxelOctreeDemo.class, args);
        }
    }
}