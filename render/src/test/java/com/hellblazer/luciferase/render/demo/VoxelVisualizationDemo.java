package com.hellblazer.luciferase.render.demo;

import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.CommandEncoder;
import com.hellblazer.luciferase.webgpu.wrapper.Device;
import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX visualization demo for voxelized 3D geometry.
 * Shows the output of the voxelization compute shader in an interactive 3D view.
 */
public class VoxelVisualizationDemo extends Application {
    private static final Logger log = LoggerFactory.getLogger(VoxelVisualizationDemo.class);
    
    // Visualization parameters
    private static final int GRID_SIZE = 32;
    private static final float VOXEL_SIZE = 20.0f / GRID_SIZE;
    private static final double SCENE_WIDTH = 800;
    private static final double SCENE_HEIGHT = 600;
    
    // WebGPU components
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    private ShaderModule voxelizationShader;
    
    // JavaFX components
    private Group voxelGroup;
    private SubScene scene3D;
    private Label statusLabel;
    private Slider rotationSpeed;
    private Rotate rotateX;
    private Rotate rotateY;
    
    // Voxel data
    private int[] voxelGrid;
    private float[][] voxelColors;
    
    public static void main(String[] args) {
        launch(args);
    }
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Voxel Visualization Demo");
        
        // Create UI
        BorderPane root = new BorderPane();
        root.setCenter(create3DScene());
        root.setBottom(createControlPanel());
        root.setTop(createStatusBar());
        
        Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT + 100);
        scene.setFill(Color.LIGHTGRAY);
        
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Initialize WebGPU in background
        CompletableFuture.runAsync(this::initializeWebGPU)
            .thenRun(() -> Platform.runLater(this::generateInitialVoxels));
    }
    
    private SubScene create3DScene() {
        // Create 3D scene components
        voxelGroup = new Group();
        
        // Camera
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-50);
        camera.setNearClip(0.1);
        camera.setFarClip(1000.0);
        
        // Add lighting
        PointLight light = new PointLight(Color.WHITE);
        light.setTranslateX(20);
        light.setTranslateY(-20);
        light.setTranslateZ(-20);
        
        AmbientLight ambientLight = new AmbientLight(Color.gray(0.3));
        
        // Create root group with transforms
        Group root3D = new Group();
        root3D.getChildren().addAll(voxelGroup, light, ambientLight);
        
        // Add rotation transforms
        rotateX = new Rotate(0, Rotate.X_AXIS);
        rotateY = new Rotate(0, Rotate.Y_AXIS);
        root3D.getTransforms().addAll(rotateX, rotateY);
        
        // Create subscene
        scene3D = new SubScene(root3D, SCENE_WIDTH, SCENE_HEIGHT, true, SceneAntialiasing.BALANCED);
        scene3D.setCamera(camera);
        scene3D.setFill(Color.DARKGRAY);
        
        // Add mouse controls
        setupMouseControls(scene3D, root3D);
        
        return scene3D;
    }
    
    private VBox createControlPanel() {
        VBox controls = new VBox(10);
        controls.setStyle("-fx-padding: 10; -fx-background-color: #f0f0f0;");
        
        // Buttons
        HBox buttons = new HBox(10);
        
        Button voxelizeTriangle = new Button("Voxelize Triangle");
        voxelizeTriangle.setOnAction(e -> voxelizeTriangle());
        
        Button voxelizeCube = new Button("Voxelize Cube");
        voxelizeCube.setOnAction(e -> voxelizeCube());
        
        Button voxelizeSphere = new Button("Voxelize Sphere");
        voxelizeSphere.setOnAction(e -> voxelizeSphere());
        
        Button clearVoxels = new Button("Clear");
        clearVoxels.setOnAction(e -> clearVoxelDisplay());
        
        buttons.getChildren().addAll(voxelizeTriangle, voxelizeCube, voxelizeSphere, clearVoxels);
        
        // Rotation speed slider
        HBox sliderBox = new HBox(10);
        sliderBox.getChildren().add(new Label("Rotation Speed:"));
        rotationSpeed = new Slider(0, 5, 1);
        rotationSpeed.setShowTickLabels(true);
        rotationSpeed.setShowTickMarks(true);
        sliderBox.getChildren().add(rotationSpeed);
        
        controls.getChildren().addAll(buttons, sliderBox);
        
        // Start rotation animation
        startRotationAnimation();
        
        return controls;
    }
    
    private HBox createStatusBar() {
        HBox statusBar = new HBox();
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #e0e0e0;");
        
        statusLabel = new Label("Initializing WebGPU...");
        statusBar.getChildren().add(statusLabel);
        
        return statusBar;
    }
    
    private void setupMouseControls(SubScene scene, Group root3D) {
        final double[] mouseOldX = {0};
        final double[] mouseOldY = {0};
        
        scene.setOnMousePressed(me -> {
            mouseOldX[0] = me.getSceneX();
            mouseOldY[0] = me.getSceneY();
        });
        
        scene.setOnMouseDragged(me -> {
            double mouseX = me.getSceneX();
            double mouseY = me.getSceneY();
            double deltaX = mouseX - mouseOldX[0];
            double deltaY = mouseY - mouseOldY[0];
            
            if (me.isPrimaryButtonDown()) {
                rotateX.setAngle(rotateX.getAngle() - deltaY);
                rotateY.setAngle(rotateY.getAngle() + deltaX);
            }
            
            mouseOldX[0] = mouseX;
            mouseOldY[0] = mouseY;
        });
        
        scene.setOnScroll(se -> {
            double delta = se.getDeltaY();
            root3D.setTranslateZ(root3D.getTranslateZ() + delta * 0.1);
        });
    }
    
    private void startRotationAnimation() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double speed = rotationSpeed.getValue();
                if (speed > 0) {
                    rotateY.setAngle(rotateY.getAngle() + speed * 0.5);
                }
            }
        };
        timer.start();
    }
    
    private void initializeWebGPU() {
        try {
            log.info("Initializing WebGPU context...");
            context = new WebGPUContext();
            context.initialize().join();
            
            shaderManager = new ComputeShaderManager(context);
            voxelizationShader = shaderManager.loadShaderFromResource(
                "/shaders/esvo/voxelization.wgsl"
            ).get();
            
            Platform.runLater(() -> statusLabel.setText("WebGPU Ready - Click buttons to voxelize geometry"));
            log.info("WebGPU initialization complete");
            
        } catch (Exception e) {
            log.error("Failed to initialize WebGPU", e);
            Platform.runLater(() -> statusLabel.setText("WebGPU initialization failed: " + e.getMessage()));
        }
    }
    
    private void generateInitialVoxels() {
        // Create a simple test pattern
        voxelGrid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        voxelColors = new float[voxelGrid.length][4];
        
        // Create a 3D cross pattern
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    int idx = getVoxelIndex(x, y, z);
                    
                    // Create cross pattern in center
                    boolean isCenter = (x == GRID_SIZE/2 || y == GRID_SIZE/2 || z == GRID_SIZE/2);
                    boolean nearCenter = Math.abs(x - GRID_SIZE/2) <= 1 && 
                                        Math.abs(y - GRID_SIZE/2) <= 1 && 
                                        Math.abs(z - GRID_SIZE/2) <= 1;
                    
                    if (isCenter && nearCenter) {
                        voxelGrid[idx] = 1;
                        voxelColors[idx][0] = 1.0f; // Red
                        voxelColors[idx][1] = 0.0f;
                        voxelColors[idx][2] = 0.0f;
                        voxelColors[idx][3] = 1.0f;
                    }
                }
            }
        }
        
        updateVoxelDisplay();
    }
    
    private void voxelizeTriangle() {
        statusLabel.setText("Voxelizing triangle...");
        
        CompletableFuture.runAsync(() -> {
            try {
                float[][] triangle = {
                    {0.3f, 0.3f, 0.5f},
                    {0.7f, 0.3f, 0.5f},
                    {0.5f, 0.7f, 0.5f}
                };
                
                var result = executeGPUVoxelization(new float[][][] {triangle});
                voxelGrid = result.voxelGrid;
                voxelColors = result.colors;
                
                Platform.runLater(() -> {
                    updateVoxelDisplay();
                    int count = countFilledVoxels();
                    statusLabel.setText("Triangle voxelized: " + count + " voxels");
                });
                
            } catch (Exception e) {
                log.error("Voxelization failed", e);
                Platform.runLater(() -> statusLabel.setText("Voxelization failed: " + e.getMessage()));
            }
        });
    }
    
    private void voxelizeCube() {
        statusLabel.setText("Voxelizing cube...");
        
        CompletableFuture.runAsync(() -> {
            try {
                // Create cube faces as triangles
                float[][][] triangles = createCubeTriangles(0.25f, 0.25f, 0.25f, 0.5f);
                
                var result = executeGPUVoxelization(triangles);
                voxelGrid = result.voxelGrid;
                voxelColors = result.colors;
                
                Platform.runLater(() -> {
                    updateVoxelDisplay();
                    int count = countFilledVoxels();
                    statusLabel.setText("Cube voxelized: " + count + " voxels");
                });
                
            } catch (Exception e) {
                log.error("Voxelization failed", e);
                Platform.runLater(() -> statusLabel.setText("Voxelization failed: " + e.getMessage()));
            }
        });
    }
    
    private void voxelizeSphere() {
        statusLabel.setText("Voxelizing sphere...");
        
        CompletableFuture.runAsync(() -> {
            try {
                // Create sphere approximation with triangles
                float[][][] triangles = createSphereTriangles(0.5f, 0.5f, 0.5f, 0.3f, 16);
                
                var result = executeGPUVoxelization(triangles);
                voxelGrid = result.voxelGrid;
                voxelColors = result.colors;
                
                Platform.runLater(() -> {
                    updateVoxelDisplay();
                    int count = countFilledVoxels();
                    statusLabel.setText("Sphere voxelized: " + count + " voxels");
                });
                
            } catch (Exception e) {
                log.error("Voxelization failed", e);
                Platform.runLater(() -> statusLabel.setText("Voxelization failed: " + e.getMessage()));
            }
        });
    }
    
    private void clearVoxelDisplay() {
        voxelGroup.getChildren().clear();
        statusLabel.setText("Display cleared");
    }
    
    private void updateVoxelDisplay() {
        voxelGroup.getChildren().clear();
        
        List<Box> voxels = new ArrayList<>();
        
        // Create voxel boxes
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    int idx = getVoxelIndex(x, y, z);
                    
                    if (voxelGrid[idx] != 0) {
                        Box voxel = new Box(VOXEL_SIZE * 0.9, VOXEL_SIZE * 0.9, VOXEL_SIZE * 0.9);
                        
                        // Position voxel
                        voxel.setTranslateX((x - GRID_SIZE/2.0) * VOXEL_SIZE);
                        voxel.setTranslateY((y - GRID_SIZE/2.0) * VOXEL_SIZE);
                        voxel.setTranslateZ((z - GRID_SIZE/2.0) * VOXEL_SIZE);
                        
                        // Set material based on color
                        PhongMaterial material = new PhongMaterial();
                        if (voxelColors != null && voxelColors[idx] != null) {
                            material.setDiffuseColor(new Color(
                                voxelColors[idx][0],
                                voxelColors[idx][1],
                                voxelColors[idx][2],
                                voxelColors[idx][3]
                            ));
                        } else {
                            // Default color based on position
                            material.setDiffuseColor(Color.hsb(
                                (x * 360.0 / GRID_SIZE),
                                0.8,
                                0.8
                            ));
                        }
                        material.setSpecularColor(Color.WHITE);
                        voxel.setMaterial(material);
                        
                        voxels.add(voxel);
                    }
                }
            }
        }
        
        voxelGroup.getChildren().addAll(voxels);
    }
    
    // Voxelization result container
    static class VoxelizationResult {
        int[] voxelGrid;
        float[][] colors;
        
        VoxelizationResult(int[] grid, float[][] cols) {
            this.voxelGrid = grid;
            this.colors = cols;
        }
    }
    
    // GPU voxelization method
    private VoxelizationResult executeGPUVoxelization(float[][][] triangles) throws Exception {
        var device = context.getDevice();
        
        // Create triangle buffer
        int triangleSize = 80; // 20 floats per triangle
        int triangleBufferSize = triangles.length * triangleSize;
        var triangleBuffer = context.createBuffer(triangleBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Pack triangle data
        ByteBuffer triangleData = ByteBuffer.allocateDirect(triangleBufferSize);
        triangleData.order(ByteOrder.nativeOrder());
        
        for (float[][] triangle : triangles) {
            // Pack vertices (3x vec4)
            for (float[] vertex : triangle) {
                triangleData.putFloat(vertex[0]);
                triangleData.putFloat(vertex[1]);
                triangleData.putFloat(vertex[2]);
                triangleData.putFloat(0.0f); // Padding
            }
            
            // Calculate and pack normal
            float[] v0 = triangle[0];
            float[] v1 = triangle[1];
            float[] v2 = triangle[2];
            float[] edge1 = {v1[0]-v0[0], v1[1]-v0[1], v1[2]-v0[2]};
            float[] edge2 = {v2[0]-v0[0], v2[1]-v0[1], v2[2]-v0[2]};
            float[] normal = {
                edge1[1]*edge2[2] - edge1[2]*edge2[1],
                edge1[2]*edge2[0] - edge1[0]*edge2[2],
                edge1[0]*edge2[1] - edge1[1]*edge2[0]
            };
            float len = (float)Math.sqrt(normal[0]*normal[0] + normal[1]*normal[1] + normal[2]*normal[2]);
            if (len > 0) {
                normal[0] /= len;
                normal[1] /= len;
                normal[2] /= len;
            }
            triangleData.putFloat(normal[0]);
            triangleData.putFloat(normal[1]);
            triangleData.putFloat(normal[2]);
            triangleData.putFloat(0.0f);
            
            // Pack color
            triangleData.putFloat((float)Math.random()); // R
            triangleData.putFloat((float)Math.random()); // G
            triangleData.putFloat((float)Math.random()); // B
            triangleData.putFloat(1.0f); // A
        }
        
        triangleData.flip();
        byte[] triangleBytes = new byte[triangleData.remaining()];
        triangleData.get(triangleBytes);
        context.writeBuffer(triangleBuffer, triangleBytes, 0);
        
        // Create voxel grid buffer
        int voxelGridSize = GRID_SIZE * GRID_SIZE * GRID_SIZE * 4;
        var voxelGridBuffer = context.createBuffer(voxelGridSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        byte[] zeroData = new byte[voxelGridSize];
        context.writeBuffer(voxelGridBuffer, zeroData, 0);
        
        // Create parameters buffer
        byte[] paramBytes = new byte[64];
        ByteBuffer params = ByteBuffer.wrap(paramBytes).order(ByteOrder.nativeOrder());
        params.putInt(GRID_SIZE);
        params.putInt(GRID_SIZE);
        params.putInt(GRID_SIZE);
        params.putInt(0);
        params.putFloat(1.0f / GRID_SIZE);
        params.putFloat(0);
        params.putFloat(0);
        params.putFloat(0);
        params.putFloat(0.0f);
        params.putFloat(0.0f);
        params.putFloat(0.0f);
        params.putFloat(0);
        params.putFloat(1.0f);
        params.putFloat(1.0f);
        params.putFloat(1.0f);
        params.putFloat(0);
        
        var paramsBuffer = context.createBuffer(64,
            WebGPUNative.BUFFER_USAGE_UNIFORM | WebGPUNative.BUFFER_USAGE_COPY_DST);
        context.writeBuffer(paramsBuffer, paramBytes, 0);
        
        // Create color buffer
        int colorBufferSize = GRID_SIZE * GRID_SIZE * GRID_SIZE * 16;
        var colorBuffer = context.createBuffer(colorBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC);
        
        // Create bind group layout
        var bindGroupLayoutDesc = new Device.BindGroupLayoutDescriptor()
            .withLabel("VoxelizationBindGroupLayout")
            .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(2, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_UNIFORM)))
            .withEntry(new Device.BindGroupLayoutEntry(3, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)));
        
        var bindGroupLayout = device.createBindGroupLayout(bindGroupLayoutDesc);
        
        // Create bind group
        var bindGroupDescriptor = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("VoxelizationBindGroup")
            .withEntry(new Device.BindGroupEntry(0).withBuffer(triangleBuffer, 0, triangleBufferSize))
            .withEntry(new Device.BindGroupEntry(1).withBuffer(voxelGridBuffer, 0, voxelGridSize))
            .withEntry(new Device.BindGroupEntry(2).withBuffer(paramsBuffer, 0, 64))
            .withEntry(new Device.BindGroupEntry(3).withBuffer(colorBuffer, 0, colorBufferSize));
        
        var bindGroup = device.createBindGroup(bindGroupDescriptor);
        
        // Create pipeline
        var pipelineLayout = device.createPipelineLayout(
            new Device.PipelineLayoutDescriptor()
                .withLabel("VoxelizationPipelineLayout")
                .addBindGroupLayout(bindGroupLayout)
        );
        
        var pipelineDescriptor = new Device.ComputePipelineDescriptor(voxelizationShader)
            .withLabel("voxelization_pipeline")
            .withLayout(pipelineLayout)
            .withEntryPoint("main");
        
        var pipeline = device.createComputePipeline(pipelineDescriptor);
        
        // Execute
        var commandEncoder = device.createCommandEncoder("voxelization_encoder");
        var computePass = commandEncoder.beginComputePass(new CommandEncoder.ComputePassDescriptor());
        computePass.setPipeline(pipeline);
        computePass.setBindGroup(0, bindGroup);
        
        int numWorkgroups = (triangles.length + 63) / 64;
        computePass.dispatchWorkgroups(Math.max(1, numWorkgroups), 1, 1);
        computePass.end();
        
        var commandBuffer = commandEncoder.finish();
        device.getQueue().submit(commandBuffer);
        device.getQueue().onSubmittedWorkDone();
        
        // Read back results
        int[] voxelGrid = readGPUBuffer(voxelGridBuffer, voxelGridSize);
        float[][] colors = readGPUColorBuffer(colorBuffer, colorBufferSize);
        
        return new VoxelizationResult(voxelGrid, colors);
    }
    
    private int[] readGPUBuffer(Buffer buffer, int size) throws Exception {
        var stagingBuffer = context.createBuffer(size,
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        var encoder = context.getDevice().createCommandEncoder("readback_encoder");
        encoder.copyBufferToBuffer(buffer, 0, stagingBuffer, 0, size);
        var commands = encoder.finish();
        context.getDevice().getQueue().submit(commands);
        context.getDevice().getQueue().onSubmittedWorkDone();
        
        var mappedSegment = stagingBuffer.mapAsync(Buffer.MapMode.READ, 0, size).get();
        
        byte[] resultData = new byte[(int)mappedSegment.byteSize()];
        mappedSegment.asByteBuffer().get(resultData);
        ByteBuffer mapped = ByteBuffer.wrap(resultData).order(ByteOrder.nativeOrder());
        
        int[] grid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = mapped.getInt();
        }
        
        stagingBuffer.unmap();
        return grid;
    }
    
    private float[][] readGPUColorBuffer(Buffer buffer, int size) throws Exception {
        var stagingBuffer = context.createBuffer(size,
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        var encoder = context.getDevice().createCommandEncoder("color_readback_encoder");
        encoder.copyBufferToBuffer(buffer, 0, stagingBuffer, 0, size);
        var commands = encoder.finish();
        context.getDevice().getQueue().submit(commands);
        context.getDevice().getQueue().onSubmittedWorkDone();
        
        var mappedSegment = stagingBuffer.mapAsync(Buffer.MapMode.READ, 0, size).get();
        
        byte[] resultData = new byte[(int)mappedSegment.byteSize()];
        mappedSegment.asByteBuffer().get(resultData);
        ByteBuffer mapped = ByteBuffer.wrap(resultData).order(ByteOrder.nativeOrder());
        
        float[][] colors = new float[GRID_SIZE * GRID_SIZE * GRID_SIZE][4];
        for (int i = 0; i < colors.length; i++) {
            colors[i][0] = mapped.getFloat();
            colors[i][1] = mapped.getFloat();
            colors[i][2] = mapped.getFloat();
            colors[i][3] = mapped.getFloat();
        }
        
        stagingBuffer.unmap();
        return colors;
    }
    
    private int getVoxelIndex(int x, int y, int z) {
        return z * GRID_SIZE * GRID_SIZE + y * GRID_SIZE + x;
    }
    
    private int countFilledVoxels() {
        int count = 0;
        for (int val : voxelGrid) {
            if (val != 0) count++;
        }
        return count;
    }
    
    private float[][][] createCubeTriangles(float x, float y, float z, float size) {
        float[][] vertices = {
            {x, y, z},
            {x+size, y, z},
            {x+size, y+size, z},
            {x, y+size, z},
            {x, y, z+size},
            {x+size, y, z+size},
            {x+size, y+size, z+size},
            {x, y+size, z+size}
        };
        
        int[][] faces = {
            {0,1,2}, {0,2,3}, // Front
            {4,6,5}, {4,7,6}, // Back
            {0,4,5}, {0,5,1}, // Bottom
            {2,6,7}, {2,7,3}, // Top
            {0,3,7}, {0,7,4}, // Left
            {1,5,6}, {1,6,2}  // Right
        };
        
        float[][][] triangles = new float[faces.length][3][3];
        for (int i = 0; i < faces.length; i++) {
            for (int j = 0; j < 3; j++) {
                triangles[i][j] = vertices[faces[i][j]];
            }
        }
        
        return triangles;
    }
    
    private float[][][] createSphereTriangles(float cx, float cy, float cz, float radius, int segments) {
        List<float[][]> triangles = new ArrayList<>();
        
        // Simple UV sphere generation
        for (int lat = 0; lat < segments; lat++) {
            for (int lon = 0; lon < segments; lon++) {
                float lat0 = (float)Math.PI * lat / segments;
                float lat1 = (float)Math.PI * (lat + 1) / segments;
                float lon0 = 2 * (float)Math.PI * lon / segments;
                float lon1 = 2 * (float)Math.PI * (lon + 1) / segments;
                
                float[] v0 = {
                    cx + radius * (float)(Math.sin(lat0) * Math.cos(lon0)),
                    cy + radius * (float)(Math.sin(lat0) * Math.sin(lon0)),
                    cz + radius * (float)Math.cos(lat0)
                };
                float[] v1 = {
                    cx + radius * (float)(Math.sin(lat1) * Math.cos(lon0)),
                    cy + radius * (float)(Math.sin(lat1) * Math.sin(lon0)),
                    cz + radius * (float)Math.cos(lat1)
                };
                float[] v2 = {
                    cx + radius * (float)(Math.sin(lat1) * Math.cos(lon1)),
                    cy + radius * (float)(Math.sin(lat1) * Math.sin(lon1)),
                    cz + radius * (float)Math.cos(lat1)
                };
                float[] v3 = {
                    cx + radius * (float)(Math.sin(lat0) * Math.cos(lon1)),
                    cy + radius * (float)(Math.sin(lat0) * Math.sin(lon1)),
                    cz + radius * (float)Math.cos(lat0)
                };
                
                // First triangle
                triangles.add(new float[][] {v0, v1, v2});
                // Second triangle
                triangles.add(new float[][] {v0, v2, v3});
            }
        }
        
        return triangles.toArray(new float[0][0][0]);
    }
    
    @Override
    public void stop() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    // Support launching from non-JavaFX thread
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(VoxelVisualizationDemo.class, args);
        }
    }
}