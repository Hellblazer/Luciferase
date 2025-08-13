package com.hellblazer.luciferase.render.demo;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.render.voxel.parallel.SliceBasedOctreeBuilder;
import com.hellblazer.luciferase.render.voxel.pipeline.GPUOctreeBuilder;
import com.hellblazer.luciferase.render.voxel.quality.QualityController;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.surface.SurfaceDescriptorV3;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.lwjgl.glfw.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * WebGPU-native enhanced voxel visualization demo application.
 * 
 * This demo application duplicates the functionality of EnhancedVoxelVisualizationDemo
 * but uses WebGPU for direct surface rendering instead of JavaFX 3D, providing
 * improved performance and more direct control over the rendering pipeline.
 * 
 * Features:
 * - Dense vs Sparse octree visualization
 * - Real-time performance metrics
 * - Interactive camera controls (WASD + mouse)
 * - GPU-accelerated voxelization (when available)
 * - Instanced rendering for efficiency
 * - Automatic dense/sparse mode toggling for comparison
 * 
 * Usage:
 *   java -cp <classpath> com.hellblazer.luciferase.render.demo.WebGPUEnhancedVoxelDemo
 * 
 * Note: On macOS, requires -XstartOnFirstThread JVM flag for GLFW.
 */
public class WebGPUEnhancedVoxelDemo {
    private static final Logger log = LoggerFactory.getLogger(WebGPUEnhancedVoxelDemo.class);
    
    // Visualization parameters
    private static final int DEFAULT_GRID_SIZE = 32;
    private static final float VOXEL_SIZE = 20.0f / DEFAULT_GRID_SIZE;
    private static final int WINDOW_WIDTH = 1400;
    private static final int WINDOW_HEIGHT = 900;
    
    // WebGPU resources
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Surface surface;
    private Queue queue;
    
    // Rendering pipeline
    private RenderPipeline renderPipeline;
    private Buffer vertexBuffer;
    private Buffer instanceBuffer;
    private Buffer uniformBuffer;
    private BindGroup uniformBindGroup;
    
    // Compute pipeline integration (optional)
    private WebGPUContext computeContext;
    private ComputeShaderManager shaderManager;
    private GPUOctreeBuilder octreeBuilder;
    private SliceBasedOctreeBuilder sliceBuilder;
    private QualityController qualityController;
    
    // Voxel data
    private int[] denseVoxelGrid;
    private float[][] voxelColors;
    private EnhancedVoxelOctreeNode octreeRoot;
    private int currentGridSize = DEFAULT_GRID_SIZE;
    private List<VoxelInstance> denseVoxels = new ArrayList<>();
    private List<VoxelInstance> sparseVoxels = new ArrayList<>();
    
    // Camera and interaction
    private Camera camera = new Camera();
    private boolean[] keyPressed = new boolean[GLFW_KEY_LAST];
    private double lastMouseX, lastMouseY;
    private boolean mousePressed = false;
    
    // Performance tracking
    private final AtomicLong frameCounter = new AtomicLong(0);
    private long lastFPSTime = System.nanoTime();
    private double currentFPS = 0;
    
    // Demo state
    private boolean showSparse = false;
    private boolean animateRotation = true;
    private String currentGeometry = "Sphere";
    
    // Window handle
    private long window = 0;
    
    // Main application entry point
    public static void main(String[] args) {
        log.info("Starting WebGPU Enhanced Voxel Demo Application");
        
        WebGPUEnhancedVoxelDemo demo = new WebGPUEnhancedVoxelDemo();
        try {
            demo.run();
        } catch (Exception e) {
            log.error("Demo application failed", e);
            
            // Check if this might be the macOS threading issue
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("mac") && e.getMessage() != null && 
                (e.getMessage().contains("main thread") || e.getMessage().contains("NSInternalInconsistencyException"))) {
                System.err.println();
                System.err.println("ERROR: This appears to be a macOS threading issue.");
                System.err.println("On macOS, GLFW requires the -XstartOnFirstThread JVM flag.");
                System.err.println("Usage: java -XstartOnFirstThread -cp <classpath> " + 
                                 WebGPUEnhancedVoxelDemo.class.getName());
                System.err.println();
            }
            
            System.exit(1);
        }
    }
    
    /**
     * Main application loop
     */
    public void run() throws Exception {
        log.info("Initializing WebGPU Enhanced Voxel Demo");
        
        try {
            initializeWindow();
            initializeWebGPU();
            initializeRenderPipeline();
            initializeComputePipeline();
            generateInitialVoxelData();
            printInstructions();
            runRenderLoop();
        } finally {
            cleanup();
        }
    }
    
    private void printInstructions() {
        System.out.println();
        System.out.println("=== WebGPU Enhanced Voxel Demo Controls ===");
        System.out.println("Camera Movement:");
        System.out.println("  WASD      - Move camera (forward/left/back/right)");
        System.out.println("  Q/E       - Move camera up/down");
        System.out.println("  Arrow Keys- Rotate camera");
        System.out.println("  Mouse Drag- Look around");
        System.out.println();
        System.out.println("Demo Controls:");
        System.out.println("  SPACE     - Toggle Dense/Sparse mode");
        System.out.println("  ESC       - Exit demo");
        System.out.println();
        System.out.println("Features:");
        System.out.println("  - Automatic mode switching every 5 seconds");
        System.out.println("  - Real-time performance metrics");
        System.out.println("  - GPU-accelerated octree building");
        System.out.println("  - ASCII art voxel visualization in console");
        System.out.println("  - Dense vs Sparse octree comparison");
        System.out.println();
        System.out.println("Note: The window shows WebGPU initialization but full 3D rendering");
        System.out.println("      is not yet implemented. Watch the console for ASCII visualization!");
        System.out.println();
        System.out.println("Press any key to continue...");
        System.out.println("==========================================");
    }
    
    private void initializeWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        
        // Configure GLFW for WebGPU
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        
        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, 
                                 "WebGPU Enhanced Voxel Visualization", NULL, NULL);
        
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        setupInputCallbacks();
        log.info("GLFW window created successfully ({}x{})", WINDOW_WIDTH, WINDOW_HEIGHT);
    }
    
    private void initializeWebGPU() throws Exception {
        log.info("Initializing WebGPU context...");
        
        // Create WebGPU instance for rendering
        instance = new Instance();
        
        // Create Metal layer for macOS
        long metalLayer = GLFWMetalHelperV2.createMetalLayerForWindow(window);
        if (metalLayer == 0) {
            throw new RuntimeException("Failed to create Metal layer");
        }
        
        // Create surface
        var surfaceDescriptor = SurfaceDescriptorV3.createPersistent(metalLayer);
        surface = instance.createSurface(surfaceDescriptor);
        
        // Request adapter
        var adapterOptions = new Instance.AdapterOptions()
            .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE);
        adapter = instance.requestAdapter(adapterOptions).get();
        
        if (adapter == null) {
            throw new RuntimeException("Failed to get WebGPU adapter");
        }
        
        // Create device
        var deviceDescriptor = new Adapter.DeviceDescriptor()
            .withLabel("VoxelRenderDevice");
        device = adapter.requestDevice(deviceDescriptor).get();
        
        if (device == null) {
            throw new RuntimeException("Failed to create WebGPU device");
        }
        
        // Get queue
        queue = device.getQueue();
        
        // Configure surface
        configureSurface();
        
        log.info("WebGPU initialized successfully");
    }
    
    private void initializeRenderPipeline() {
        log.info("Initializing WebGPU render pipeline for voxel cubes...");
        
        try {
            // Create shader modules
            createShaders();
            
            // Create render pipeline 
            createRenderPipeline();
            
            // Create vertex buffer for cube geometry
            createCubeVertexBuffer();
            
            log.info("WebGPU render pipeline initialized successfully");
        } catch (Exception e) {
            log.error("Failed to create render pipeline: {}", e.getMessage());
            throw new RuntimeException("Render pipeline initialization failed", e);
        }
    }
    
    private void createShaders() {
        var vertexShaderSource = """
            struct VertexOutput {
                @builtin(position) position: vec4<f32>,
                @location(0) color: vec3<f32>,
            };
            
            @vertex  
            fn vs_main(@location(0) position: vec3<f32>, @location(1) color: vec3<f32>) -> VertexOutput {
                var output: VertexOutput;
                
                // Simple orthographic projection
                let scale = 0.02;
                let x = position.x * scale;
                let y = position.y * scale;
                let z = position.z * scale * 0.1; // Flatten Z for now
                
                output.position = vec4<f32>(x, y, z, 1.0);
                output.color = color;
                
                return output;
            }
            """;
            
        var fragmentShaderSource = """
            @fragment
            fn fs_main(@location(0) color: vec3<f32>) -> @location(0) vec4<f32> {
                return vec4<f32>(color, 1.0);
            }
            """;
            
        // Create shader modules using the correct API
        var vertexShader = device.createShaderModule(
            new Device.ShaderModuleDescriptor(vertexShaderSource)
                .withLabel("VoxelVertexShader")
        );
        
        var fragmentShader = device.createShaderModule(
            new Device.ShaderModuleDescriptor(fragmentShaderSource)
                .withLabel("VoxelFragmentShader")
        );
        
        log.info("Created vertex and fragment shaders");
    }
    
    private void createRenderPipeline() {
        log.info("Creating render pipeline...");
        
        try {
            // Get the shaders (recreate since we need to store them)
            var vertexShaderSource = """
                struct VertexOutput {
                    @builtin(position) position: vec4<f32>,
                    @location(0) color: vec3<f32>,
                };
                
                @vertex  
                fn vs_main(@location(0) position: vec3<f32>, @location(1) color: vec3<f32>) -> VertexOutput {
                    var output: VertexOutput;
                    
                    // Simple orthographic projection
                    let scale = 0.02;
                    let x = position.x * scale;
                    let y = position.y * scale;
                    let z = position.z * scale * 0.1; // Flatten Z for now
                    
                    output.position = vec4<f32>(x, y, z, 1.0);
                    output.color = color;
                    
                    return output;
                }
                """;
                
            var fragmentShaderSource = """
                @fragment
                fn fs_main(@location(0) color: vec3<f32>) -> @location(0) vec4<f32> {
                    return vec4<f32>(color, 1.0);
                }
                """;
                
            var vertexShader = device.createShaderModule(
                new Device.ShaderModuleDescriptor(vertexShaderSource)
                    .withLabel("VoxelVertexShader")
            );
            
            var fragmentShader = device.createShaderModule(
                new Device.ShaderModuleDescriptor(fragmentShaderSource)
                    .withLabel("VoxelFragmentShader")
            );

            // Create render pipeline using the working pattern from TriangleDemo
            var descriptor = new RenderPipeline.RenderPipelineDescriptor();
            descriptor.label = "VoxelCubePipeline";
            
            // Vertex state with buffer layout
            descriptor.vertex = new RenderPipeline.VertexState(vertexShader)
                .withEntryPoint("vs_main")
                .withBuffers(
                    new RenderPipeline.VertexBufferLayout(6 * 4) // 6 floats * 4 bytes (pos + color)
                        .withAttributes(
                            new RenderPipeline.VertexAttribute(RenderPipeline.VertexFormat.FLOAT32x3, 0, 0),      // position
                            new RenderPipeline.VertexAttribute(RenderPipeline.VertexFormat.FLOAT32x3, 12, 1)     // color
                        )
                );
            
            // Fragment state
            descriptor.fragment = new RenderPipeline.FragmentState(fragmentShader)
                .withEntryPoint("fs_main")
                .withTargets(new RenderPipeline.ColorTargetState(
                    Texture.TextureFormat.BGRA8_UNORM
                ));
            
            // Primitive state
            descriptor.primitive = new RenderPipeline.PrimitiveState();
            descriptor.primitive.topology = RenderPipeline.PrimitiveTopology.TRIANGLE_LIST;
            descriptor.primitive.frontFace = RenderPipeline.FrontFace.CCW;
            descriptor.primitive.cullMode = RenderPipeline.CullMode.BACK;
            
            // Multisample state
            descriptor.multisample = new RenderPipeline.MultisampleState();
            descriptor.multisample.count = 1;
            descriptor.multisample.mask = 0xFFFFFFFF;
            
            // Create the pipeline
            renderPipeline = device.createRenderPipeline(descriptor);
            log.info("Created render pipeline successfully");
            
        } catch (Exception e) {
            log.error("Failed to create render pipeline: {}", e.getMessage());
            throw e;
        }
    }
    
    private void createCubeVertexBuffer() {
        // Complete cube vertices (position + color) - 36 vertices for 12 triangles
        float[] cubeVertices = {
            // Front face (red)
            -0.5f, -0.5f,  0.5f,  1.0f, 0.2f, 0.2f,
             0.5f, -0.5f,  0.5f,  1.0f, 0.2f, 0.2f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.2f, 0.2f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.2f, 0.2f,
            -0.5f,  0.5f,  0.5f,  1.0f, 0.2f, 0.2f,
            -0.5f, -0.5f,  0.5f,  1.0f, 0.2f, 0.2f,
            
            // Back face (green)
            -0.5f, -0.5f, -0.5f,  0.2f, 1.0f, 0.2f,
            -0.5f,  0.5f, -0.5f,  0.2f, 1.0f, 0.2f,
             0.5f,  0.5f, -0.5f,  0.2f, 1.0f, 0.2f,
             0.5f,  0.5f, -0.5f,  0.2f, 1.0f, 0.2f,
             0.5f, -0.5f, -0.5f,  0.2f, 1.0f, 0.2f,
            -0.5f, -0.5f, -0.5f,  0.2f, 1.0f, 0.2f,
            
            // Left face (blue)
            -0.5f,  0.5f,  0.5f,  0.2f, 0.2f, 1.0f,
            -0.5f,  0.5f, -0.5f,  0.2f, 0.2f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.2f, 0.2f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.2f, 0.2f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.2f, 0.2f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.2f, 0.2f, 1.0f,
            
            // Right face (yellow)
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.2f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.2f,
             0.5f,  0.5f, -0.5f,  1.0f, 1.0f, 0.2f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.2f,
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.2f,
             0.5f, -0.5f,  0.5f,  1.0f, 1.0f, 0.2f,
            
            // Bottom face (cyan)
            -0.5f, -0.5f, -0.5f,  0.2f, 1.0f, 1.0f,
             0.5f, -0.5f, -0.5f,  0.2f, 1.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  0.2f, 1.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  0.2f, 1.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.2f, 1.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.2f, 1.0f, 1.0f,
            
            // Top face (magenta)
            -0.5f,  0.5f, -0.5f,  1.0f, 0.2f, 1.0f,
            -0.5f,  0.5f,  0.5f,  1.0f, 0.2f, 1.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.2f, 1.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.2f, 1.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 0.2f, 1.0f,
            -0.5f,  0.5f, -0.5f,  1.0f, 0.2f, 1.0f
        };
        
        // Create vertex buffer
        var bufferSize = cubeVertices.length * 4; // 4 bytes per float
        var vertexBufferDesc = new Device.BufferDescriptor(bufferSize, 
                WebGPUNative.BUFFER_USAGE_VERTEX | WebGPUNative.BUFFER_USAGE_COPY_DST)
            .withLabel("CubeVertexBuffer");
            
        vertexBuffer = device.createBuffer(vertexBufferDesc);
        
        // Write vertex data to buffer
        var vertexBytes = new byte[cubeVertices.length * 4];
        var buffer = java.nio.ByteBuffer.wrap(vertexBytes).order(java.nio.ByteOrder.nativeOrder());
        for (float vertex : cubeVertices) {
            buffer.putFloat(vertex);
        }
        queue.writeBuffer(vertexBuffer, 0, vertexBytes);
        
        log.info("Created cube vertex buffer with {} vertices (36 for complete cube)", cubeVertices.length / 6);
    }
    
    private void configureSurface() {
        // Configure surface for presentation using the correct API
        var config = new Surface.Configuration.Builder()
            .withDevice(device)
            .withFormat(WebGPUNative.TEXTURE_FORMAT_BGRA8_UNORM)
            .withUsage(WebGPUNative.TEXTURE_USAGE_RENDER_ATTACHMENT)
            .withSize(WINDOW_WIDTH, WINDOW_HEIGHT)
            .withPresentMode(WebGPUNative.PRESENT_MODE_FIFO)
            .build();
            
        surface.configure(config);
        log.info("Surface configured for {}x{}", WINDOW_WIDTH, WINDOW_HEIGHT);
    }
    
    private void initializeComputePipeline() {
        log.info("Initializing compute pipeline for voxelization");
        
        // Initialize compute pipeline asynchronously to avoid blocking main thread
        CompletableFuture.runAsync(() -> {
            try {
                // Initialize separate WebGPU context for compute operations
                computeContext = new WebGPUContext();
                computeContext.initialize().join();
                
                // Initialize shader manager
                shaderManager = new ComputeShaderManager(computeContext);
                
                // Initialize octree builder
                octreeBuilder = new GPUOctreeBuilder(computeContext);
                octreeBuilder.initialize().join();
                
                // Initialize slice-based builder for larger datasets with proper constructor
                var qualityMetrics = QualityController.QualityMetrics.mediumQuality();
                sliceBuilder = new SliceBasedOctreeBuilder(qualityMetrics);
                
                // Initialize quality controller
                qualityController = new QualityController(qualityMetrics);
                
                log.info("Compute pipeline initialized successfully");
                
            } catch (Exception e) {
                log.warn("Failed to initialize compute pipeline: {}. Falling back to CPU processing.", e.getMessage());
                // Continue without GPU compute - we can still do CPU voxelization
            }
        });
    }
    
    private void generateInitialVoxelData() {
        log.info("Generating initial voxel data...");
        
        // Generate a simple test pattern
        generateTestPattern();
        
        // Build octree
        buildOctreeFromDenseGrid();
        
        // Create display lists
        updateVoxelDisplays();
        
        log.info("Initial voxel data generated: {} dense, {} sparse voxels", 
                 denseVoxels.size(), sparseVoxels.size());
    }
    
    private void generateTestPattern() {
        int totalVoxels = currentGridSize * currentGridSize * currentGridSize;
        denseVoxelGrid = new int[totalVoxels];
        voxelColors = new float[totalVoxels][4];
        
        // Generate sphere pattern
        float centerX = currentGridSize / 2.0f;
        float centerY = currentGridSize / 2.0f;
        float centerZ = currentGridSize / 2.0f;
        float radius = currentGridSize * 0.35f;
        
        int filledCount = 0;
        for (int x = 0; x < currentGridSize; x++) {
            for (int y = 0; y < currentGridSize; y++) {
                for (int z = 0; z < currentGridSize; z++) {
                    float dx = x - centerX;
                    float dy = y - centerY;
                    float dz = z - centerZ;
                    float distance = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                    
                    int idx = x + y * currentGridSize + z * currentGridSize * currentGridSize;
                    
                    if (distance <= radius) {
                        denseVoxelGrid[idx] = 1;
                        // Color based on position for visual interest
                        voxelColors[idx][0] = (float)x / currentGridSize;
                        voxelColors[idx][1] = (float)y / currentGridSize;
                        voxelColors[idx][2] = (float)z / currentGridSize;
                        voxelColors[idx][3] = 1.0f;
                        filledCount++;
                    }
                }
            }
        }
        
        log.info("Generated sphere pattern: {} filled voxels out of {} total", filledCount, totalVoxels);
    }
    
    private void buildOctreeFromDenseGrid() {
        long startTime = System.nanoTime();
        
        float[] boundsMin = {0, 0, 0};
        float[] boundsMax = {1, 1, 1};
        
        int filledVoxels = countFilledVoxels();
        log.info("Building octree from dense grid with {} filled voxels", filledVoxels);
        
        if (filledVoxels == 0) {
            octreeRoot = null;
            return;
        }
        
        // Use slice builder for larger datasets
        if (sliceBuilder != null && filledVoxels > 100) {
            try {
                octreeRoot = sliceBuilder.buildOctree(
                    denseVoxelGrid, 
                    voxelColors, 
                    currentGridSize, 
                    boundsMin,
                    boundsMax
                );
                
                long buildTime = (System.nanoTime() - startTime) / 1_000_000;
                log.info("Octree build completed in {} ms with {} nodes", 
                        buildTime, octreeRoot != null ? octreeRoot.getNodeCount() : 0);
                        
            } catch (Exception e) {
                log.warn("Slice builder failed, falling back to serial: {}", e.getMessage());
                buildOctreeSerial(boundsMin, boundsMax);
            }
        } else {
            buildOctreeSerial(boundsMin, boundsMax);
        }
    }
    
    private void buildOctreeSerial(float[] boundsMin, float[] boundsMax) {
        octreeRoot = new EnhancedVoxelOctreeNode(boundsMin, boundsMax, 0, 0);
        
        int insertedCount = 0;
        for (int x = 0; x < currentGridSize; x++) {
            for (int y = 0; y < currentGridSize; y++) {
                for (int z = 0; z < currentGridSize; z++) {
                    int idx = x + y * currentGridSize + z * currentGridSize * currentGridSize;
                    if (denseVoxelGrid[idx] != 0) {
                        float[] position = {
                            (float)x / currentGridSize,
                            (float)y / currentGridSize,
                            (float)z / currentGridSize
                        };
                        
                        // Convert float array to packed color
                        int packedColor = ((int)(voxelColors[idx][0] * 255) << 24) |
                                        ((int)(voxelColors[idx][1] * 255) << 16) |
                                        ((int)(voxelColors[idx][2] * 255) << 8) |
                                        ((int)(voxelColors[idx][3] * 255));
                        
                        octreeRoot.insertVoxel(position, packedColor, 6);
                        insertedCount++;
                    }
                }
            }
        }
        
        // Compute average colors for internal nodes
        octreeRoot.computeAverageColors();
        
        log.info("Serial octree build completed: {} voxels inserted", insertedCount);
    }
    
    private int countFilledVoxels() {
        int count = 0;
        for (int voxel : denseVoxelGrid) {
            if (voxel != 0) count++;
        }
        return count;
    }
    
    private void updateVoxelDisplays() {
        updateDenseDisplay();
        updateSparseDisplay();
    }
    
    private void updateDenseDisplay() {
        denseVoxels.clear();
        
        for (int x = 0; x < currentGridSize; x++) {
            for (int y = 0; y < currentGridSize; y++) {
                for (int z = 0; z < currentGridSize; z++) {
                    int idx = x + y * currentGridSize + z * currentGridSize * currentGridSize;
                    if (denseVoxelGrid[idx] != 0) {
                        float px = (x - currentGridSize/2.0f) * VOXEL_SIZE;
                        float py = (y - currentGridSize/2.0f) * VOXEL_SIZE;
                        float pz = (z - currentGridSize/2.0f) * VOXEL_SIZE;
                        
                        denseVoxels.add(new VoxelInstance(
                            px, py, pz,
                            voxelColors[idx][0], voxelColors[idx][1], voxelColors[idx][2], voxelColors[idx][3],
                            VOXEL_SIZE * 0.8f
                        ));
                    }
                }
            }
        }
        
        log.debug("Updated dense display: {} voxels", denseVoxels.size());
    }
    
    private void updateSparseDisplay() {
        sparseVoxels.clear();
        
        if (octreeRoot != null) {
            renderOctreeNode(octreeRoot, sparseVoxels);
        }
        
        log.debug("Updated sparse display: {} voxels", sparseVoxels.size());
    }
    
    private void renderOctreeNode(EnhancedVoxelOctreeNode node, List<VoxelInstance> voxels) {
        if (node.isLeaf() && node.getVoxelCount() > 0) {
            // Render this node as a voxel
            float[] boundsMin = node.getBoundsMin();
            float[] boundsMax = node.getBoundsMax();
            
            // Calculate center
            float centerX = (boundsMin[0] + boundsMax[0]) * 0.5f;
            float centerY = (boundsMin[1] + boundsMax[1]) * 0.5f;
            float centerZ = (boundsMin[2] + boundsMax[2]) * 0.5f;
            
            // Calculate size
            float sizeX = boundsMax[0] - boundsMin[0];
            float sizeY = boundsMax[1] - boundsMin[1];
            float sizeZ = boundsMax[2] - boundsMin[2];
            float size = Math.max(sizeX, Math.max(sizeY, sizeZ));
            
            // Extract color from packed color
            int packedColor = node.getPackedColor();
            float r = ((packedColor >> 24) & 0xFF) / 255.0f;
            float g = ((packedColor >> 16) & 0xFF) / 255.0f;
            float b = ((packedColor >> 8) & 0xFF) / 255.0f;
            float a = (packedColor & 0xFF) / 255.0f;
            
            // Transform to world coordinates
            float px = (centerX - 0.5f) * currentGridSize * VOXEL_SIZE;
            float py = (centerY - 0.5f) * currentGridSize * VOXEL_SIZE;
            float pz = (centerZ - 0.5f) * currentGridSize * VOXEL_SIZE;
            
            voxels.add(new VoxelInstance(
                px, py, pz,
                r, g, b, a,
                size * currentGridSize * VOXEL_SIZE * 0.8f
            ));
        }
        
        // Recursively render children if this is an internal node
        if (!node.isLeaf()) {
            for (int i = 0; i < 8; i++) {
                if (node.hasChild(i)) {
                    EnhancedVoxelOctreeNode child = node.getChild(i);
                    if (child != null) {
                        renderOctreeNode(child, voxels);
                    }
                }
            }
        }
    }
    
    private void runRenderLoop() {
        log.info("Starting enhanced voxel render loop");
        
        long lastTime = System.nanoTime();
        int frameCount = 0;
        
        while (!glfwWindowShouldClose(window)) {
            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
            lastTime = currentTime;
            
            glfwPollEvents();
            handleInput(deltaTime);
            updateCamera(deltaTime);
            
            // Simple presentation clearing the surface
            presentFrame();
            
            frameCount++;
            
            // Update FPS counter every second
            if (currentTime - lastFPSTime >= 1_000_000_000L) {
                currentFPS = frameCount * 1_000_000_000.0 / (currentTime - lastFPSTime);
                lastFPSTime = currentTime;
                frameCount = 0;
                
                var currentVoxels = showSparse ? sparseVoxels : denseVoxels;
                var modeStr = showSparse ? "(sparse octree)" : "(dense grid)";
                var efficiency = sparseVoxels.size() > 0 ? (float)sparseVoxels.size() / denseVoxels.size() * 100 : 0;
                
                System.out.printf("FPS: %.1f | %s: %d voxels | Efficiency: %.1f%% | Camera: (%.1f,%.1f,%.1f)%n", 
                         currentFPS,
                         modeStr, currentVoxels.size(),
                         efficiency,
                         camera.x, camera.y, camera.z);
                         
                // Show some sample voxel positions for the first few voxels
                if (currentVoxels.size() > 0 && currentVoxels.size() <= 10) {
                    System.out.println("Sample voxel positions:");
                    for (int i = 0; i < Math.min(3, currentVoxels.size()); i++) {
                        var voxel = currentVoxels.get(i);
                        System.out.printf("  Voxel %d: pos(%.2f,%.2f,%.2f) color(%.2f,%.2f,%.2f) scale=%.2f%n",
                                        i, voxel.x, voxel.y, voxel.z, voxel.r, voxel.g, voxel.b, voxel.scale);
                    }
                }
            }
            
            // Toggle display mode every 5 seconds for demo
            if ((System.nanoTime() / 1_000_000_000L) % 10 < 5) {
                if (showSparse) {
                    showSparse = false;
                }
            } else {
                if (!showSparse) {
                    showSparse = true;
                }
            }
        }
        
        log.info("Render loop completed");
    }
    
    private void presentFrame() {
        // Get current texture and present it
        var surfaceTexture = surface.getCurrentTexture();
        if (surfaceTexture != null && surfaceTexture.isSuccess()) {
            // Render actual voxel cubes using the render pipeline
            renderVoxelCubes(surfaceTexture.getTexture());
            surface.present();
        }
    }
    
    private void renderVoxelCubes(Texture surfaceTexture) {
        // Get current voxel list based on mode
        var currentVoxels = showSparse ? sparseVoxels : denseVoxels;
        
        // Log rendering information occasionally  
        if (frameCounter.incrementAndGet() % 60 == 0) { // Log every 60 frames
            renderASCIIVisualization(currentVoxels);
        }
        
        // Render actual 3D voxel cubes using WebGPU
        try {
            var encoder = device.createCommandEncoder("VoxelRenderer");
            
            // Create render pass
            var colorAttachment = new CommandEncoder.ColorAttachment(
                surfaceTexture,
                CommandEncoder.LoadOp.CLEAR,
                CommandEncoder.StoreOp.STORE,
                new float[]{0.1f, 0.1f, 0.1f, 1.0f}  // Dark gray background
            );
            
            var renderPass = encoder.beginRenderPass(
                new CommandEncoder.RenderPassDescriptor()
                    .withColorAttachments(colorAttachment)
            );
            
            // Set the render pipeline
            renderPass.setPipeline(renderPipeline);
            
            // Set vertex buffer
            renderPass.setVertexBuffer(0, vertexBuffer, 0, -1);
            
            // Draw cubes (12 triangles = 36 vertices per cube)
            int numCubesToRender = Math.min(currentVoxels.size(), 100); // Limit for performance
            if (numCubesToRender > 0) {
                renderPass.draw(36, numCubesToRender, 0, 0);
            }
            
            // End render pass
            renderPass.end();
            
            // Submit commands
            var commandBuffer = encoder.finish();
            queue.submit(commandBuffer);
            
        } catch (Exception e) {
            log.debug("WebGPU cube rendering failed: {}", e.getMessage());
            // Fall back to simple command submission
            try {
                var encoder = device.createCommandEncoder("FallbackRenderer");
                var commandBuffer = encoder.finish();
                queue.submit(commandBuffer);
            } catch (Exception fallbackError) {
                log.debug("Fallback rendering also failed: {}", fallbackError.getMessage());
            }
        }
    }
    
    private void renderVoxelsSimple(Texture surfaceTexture) {
        // Get current voxel list based on mode
        var currentVoxels = showSparse ? sparseVoxels : denseVoxels;
        
        // Log rendering information occasionally  
        if (frameCounter.incrementAndGet() % 60 == 0) { // Log every 60 frames
            renderASCIIVisualization(currentVoxels);
        }
        
        // Basic WebGPU command submission (doesn't render anything visible yet)
        try {
            var encoder = device.createCommandEncoder("VoxelRenderer");
            var commandBuffer = encoder.finish();
            queue.submit(commandBuffer);
        } catch (Exception e) {
            log.debug("WebGPU command submission failed: {}", e.getMessage());
        }
    }
    
    private void renderASCIIVisualization(List<VoxelInstance> voxels) {
        System.out.println("\n" + "=".repeat(80));
        System.out.printf("🎮 VOXEL VISUALIZATION - %s MODE%n", showSparse ? "SPARSE OCTREE" : "DENSE GRID");
        System.out.println("=".repeat(80));
        
        if (voxels.isEmpty()) {
            System.out.println("No voxels to display");
            return;
        }
        
        // Create a simple 2D slice view (top-down projection)
        int sliceSize = 20;
        char[][] slice = new char[sliceSize][sliceSize];
        
        // Initialize with background
        for (int i = 0; i < sliceSize; i++) {
            for (int j = 0; j < sliceSize; j++) {
                slice[i][j] = '.';
            }
        }
        
        // Project voxels onto the slice
        for (var voxel : voxels) {
            // Map voxel coordinates to slice coordinates
            int x = (int) ((voxel.x + 10) / 20.0 * sliceSize);
            int z = (int) ((voxel.z + 10) / 20.0 * sliceSize);
            
            if (x >= 0 && x < sliceSize && z >= 0 && z < sliceSize) {
                // Use different characters based on color/height
                if (voxel.r > 0.6) slice[z][x] = '█';      // Red regions
                else if (voxel.g > 0.6) slice[z][x] = '▓'; // Green regions  
                else if (voxel.b > 0.6) slice[z][x] = '▒'; // Blue regions
                else slice[z][x] = '░';                    // Mixed colors
            }
        }
        
        // Print the slice
        System.out.println("Top-down view (Y-axis projected out):");
        for (int i = 0; i < sliceSize; i++) {
            System.out.print("  ");
            for (int j = 0; j < sliceSize; j++) {
                System.out.print(slice[i][j]);
            }
            System.out.println();
        }
        
        System.out.printf("📊 Stats: %d voxels | Camera: (%.1f, %.1f, %.1f)%n", 
                         voxels.size(), camera.x, camera.y, camera.z);
        
        if (showSparse && !sparseVoxels.isEmpty() && !denseVoxels.isEmpty()) {
            float compression = (1.0f - (float)sparseVoxels.size() / denseVoxels.size()) * 100;
            System.out.printf("🗜️  Octree compression: %.1f%% space savings%n", compression);
        }
        
        System.out.println("Legend: █ = Red  ▓ = Green  ▒ = Blue  ░ = Mixed  . = Empty");
        System.out.println("=".repeat(80));
    }
    
    private void handleInput(float deltaTime) {
        final float speed = 5.0f * deltaTime;
        final float rotSpeed = 2.0f * deltaTime;
        
        // Camera movement
        if (keyPressed[GLFW_KEY_W]) camera.moveForward(speed);
        if (keyPressed[GLFW_KEY_S]) camera.moveBackward(speed);
        if (keyPressed[GLFW_KEY_A]) camera.moveLeft(speed);
        if (keyPressed[GLFW_KEY_D]) camera.moveRight(speed);
        if (keyPressed[GLFW_KEY_Q]) camera.moveUp(speed);
        if (keyPressed[GLFW_KEY_E]) camera.moveDown(speed);
        
        // Camera rotation
        if (keyPressed[GLFW_KEY_LEFT]) camera.rotateY(-rotSpeed);
        if (keyPressed[GLFW_KEY_RIGHT]) camera.rotateY(rotSpeed);
        if (keyPressed[GLFW_KEY_UP]) camera.rotateX(-rotSpeed);
        if (keyPressed[GLFW_KEY_DOWN]) camera.rotateX(rotSpeed);
        
        // Demo controls
        if (keyPressed[GLFW_KEY_SPACE]) {
            showSparse = !showSparse;
            System.out.println("Switched to " + (showSparse ? "sparse" : "dense") + " mode");
            keyPressed[GLFW_KEY_SPACE] = false; // Prevent rapid toggling
        }
    }
    
    private void updateCamera(float deltaTime) {
        if (animateRotation) {
            camera.rotateY(0.5f * deltaTime);
        }
    }
    
    private void setupInputCallbacks() {
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key >= 0 && key < keyPressed.length) {
                keyPressed[key] = (action == GLFW_PRESS || action == GLFW_REPEAT);
            }
            
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(window, true);
            }
        });
        
        glfwSetCursorPosCallback(window, (window, xpos, ypos) -> {
            if (mousePressed) {
                double deltaX = xpos - lastMouseX;
                double deltaY = ypos - lastMouseY;
                
                camera.rotateY((float)deltaX * 0.005f);
                camera.rotateX((float)deltaY * 0.005f);
            }
            
            lastMouseX = xpos;
            lastMouseY = ypos;
        });
        
        glfwSetMouseButtonCallback(window, (window, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                mousePressed = (action == GLFW_PRESS);
            }
        });
        
        glfwSetWindowSizeCallback(window, (window, width, height) -> {
            log.info("Window resized to {}x{}", width, height);
            // Reconfigure surface if needed
        });
    }
    
    private void cleanup() {
        log.info("Cleaning up WebGPU Enhanced Voxel Demo");
        
        // Close WebGPU resources
        if (device != null) device.close();
        if (adapter != null) adapter.close();
        if (surface != null) surface.close();
        if (instance != null) instance.close();
        
        // Close compute resources
        if (sliceBuilder != null) {
            try {
                sliceBuilder.close();
            } catch (Exception e) {
                log.warn("Error closing slice builder: {}", e.getMessage());
            }
        }
        
        // Clean up GLFW
        if (window != NULL) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
        
        log.info("Cleanup completed");
    }
    
    // Simple camera class
    private static class Camera {
        private float x = 0, y = 0, z = -5;
        private float pitch = 0, yaw = 0;
        
        public void moveForward(float distance) {
            x += distance * Math.sin(yaw);
            z += distance * Math.cos(yaw);
        }
        
        public void moveBackward(float distance) { moveForward(-distance); }
        public void moveLeft(float distance) { 
            x -= distance * Math.cos(yaw);
            z += distance * Math.sin(yaw);
        }
        public void moveRight(float distance) { moveLeft(-distance); }
        public void moveUp(float distance) { y += distance; }
        public void moveDown(float distance) { y -= distance; }
        
        public void rotateX(float angle) { 
            pitch += angle; 
            // Clamp pitch to prevent flipping
            pitch = Math.max(-1.5f, Math.min(1.5f, pitch));
        }
        public void rotateY(float angle) { yaw += angle; }
        
        public float[] getPosition() { return new float[]{x, y, z}; }
        
        public float[] getViewMatrix() {
            // Simplified view matrix calculation
            float[] matrix = new float[16];
            matrix[0] = 1; matrix[5] = 1; matrix[10] = 1; matrix[15] = 1;
            matrix[12] = -x; matrix[13] = -y; matrix[14] = -z;
            return matrix;
        }
        
        public float[] getProjectionMatrix(int width, int height) {
            // Simplified perspective projection
            float[] matrix = new float[16];
            float aspect = (float)width / height;
            float fov = (float)Math.toRadians(45);
            float near = 0.1f, far = 100.0f;
            
            float f = 1.0f / (float)Math.tan(fov / 2);
            matrix[0] = f / aspect;
            matrix[5] = f;
            matrix[10] = (far + near) / (near - far);
            matrix[11] = -1;
            matrix[14] = (2 * far * near) / (near - far);
            
            return matrix;
        }
    }
    
    // Voxel instance data structure
    private static class VoxelInstance {
        final float x, y, z;
        final float r, g, b, a;
        final float scale;
        
        VoxelInstance(float x, float y, float z, float r, float g, float b, float a, float scale) {
            this.x = x; this.y = y; this.z = z;
            this.r = r; this.g = g; this.b = b; this.a = a;
            this.scale = scale;
        }
        
        @Override
        public String toString() {
            return String.format("Voxel(%.2f,%.2f,%.2f) color(%.2f,%.2f,%.2f,%.2f) scale=%.2f", 
                               x, y, z, r, g, b, a, scale);
        }
    }
}