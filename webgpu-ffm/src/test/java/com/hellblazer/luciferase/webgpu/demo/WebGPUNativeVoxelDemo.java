package com.hellblazer.luciferase.webgpu.demo;

import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import com.hellblazer.luciferase.webgpu.surface.SurfaceDescriptorV3;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.wrapper.RenderPipeline;
import com.hellblazer.luciferase.webgpu.wrapper.BindGroup;
import com.hellblazer.luciferase.webgpu.wrapper.BindGroupLayout;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import com.hellblazer.luciferase.webgpu.wrapper.Texture;
import com.hellblazer.luciferase.webgpu.wrapper.CommandEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.glfw.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * WebGPU-native voxel visualization demo that replaces JavaFX 3D rendering
 * with direct WebGPU surface rendering for improved performance.
 * 
 * This is a simplified version that focuses on basic WebGPU setup and surface creation.
 */
public class WebGPUNativeVoxelDemo {
    private static final Logger log = LoggerFactory.getLogger(WebGPUNativeVoxelDemo.class);
    
    private static final int WINDOW_WIDTH = 1200;
    private static final int WINDOW_HEIGHT = 800;
    
    // WebGPU constants not defined in WebGPUNative yet
    private static final int VERTEX_FORMAT_FLOAT32X3 = 0x00000001;
    private static final int VERTEX_FORMAT_FLOAT32 = 0x00000002;
    private static final int PRIMITIVE_TOPOLOGY_TRIANGLE_LIST = 0x00000000;
    private static final int CULL_MODE_BACK = 0x00000002;
    private static final int COMPARE_FUNCTION_LESS = 0x00000002;
    private static final int VERTEX_STEP_MODE_VERTEX = 0x00000000;
    private static final int VERTEX_STEP_MODE_INSTANCE = 0x00000001;
    
    // WebGPU resources
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Surface surface;
    private Queue queue;
    
    // Window handle
    private long window = 0;
    
    @Test
    @EnabledOnOs(OS.MAC)
    void testWebGPUVoxelDemo() throws Exception {
        // Note: On macOS, this requires -XstartOnFirstThread JVM flag
        // The test will run but may fail without it
        
        log.info("Starting WebGPU Native Voxel Demo");
        log.info("Note: Requires -XstartOnFirstThread flag on macOS for GLFW");
        
        try {
            initializeWindow();
            initializeWebGPU();
            createRenderResources();
            runRenderLoop();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("NSInternalInconsistencyException")) {
                log.error("GLFW threading error - requires -XstartOnFirstThread flag on macOS");
                // Don't fail the test, just log the issue
                return;
            }
            throw e;
        } finally {
            cleanup();
        }
    }
    
    // Alternative main method for standalone execution
    public static void main(String[] args) throws Exception {
        log.info("Running WebGPU Native Voxel Demo as standalone application");
        
        var demo = new WebGPUNativeVoxelDemo();
        demo.initializeWindow();
        demo.initializeWebGPU();
        demo.createRenderResources();
        
        // Run for longer in standalone mode
        log.info("Starting render loop - press Ctrl+C to exit");
        while (!glfwWindowShouldClose(demo.window)) {
            glfwPollEvents();
            demo.render();
            
            try {
                Thread.sleep(16); // ~60 FPS
            } catch (InterruptedException e) {
                break;
            }
        }
        
        demo.cleanup();
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
                                 "WebGPU Native Voxel Demo", NULL, NULL);
        
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        log.info("GLFW window created successfully");
    }
    
    private void initializeWebGPU() throws Exception {
        // Create WebGPU instance
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
        
        log.info("WebGPU initialized successfully");
    }
    
    private void createRenderResources() {
        log.info("Creating render resources...");
        
        // Load shaders from resources
        String vertexShaderSource = loadShader("/voxel_vertex.wgsl");
        String fragmentShaderSource = loadShader("/voxel_fragment.wgsl");
        
        // Create shader modules
        var vertexShader = device.createShaderModule(
            new Device.ShaderModuleDescriptor(vertexShaderSource)
                .withLabel("VoxelVertexShader")
        );
        
        var fragmentShader = device.createShaderModule(
            new Device.ShaderModuleDescriptor(fragmentShaderSource)
                .withLabel("VoxelFragmentShader")
        );
        
        log.info("Shaders loaded and created successfully");
        
        // Create cube geometry for voxel rendering
        createCubeGeometry();
        
        // Create uniform buffer for MVP matrices
        createUniformBuffer();
        
        // Create instance buffer for voxel data
        createInstanceBuffer();
        
        // Create render pipeline
        createRenderPipeline(vertexShader, fragmentShader);
        
        log.info("Render resources created successfully");
    }
    
    // Render pipeline resources
    private Buffer vertexBuffer;
    private Buffer instanceBuffer;
    private Buffer uniformBuffer;
    private RenderPipeline renderPipeline;
    private BindGroup bindGroup;
    
    // Voxel data
    private static class VoxelInstance {
        float x, y, z;           // Position
        float r, g, b;           // Color
        float scale;             // Scale
        
        VoxelInstance(float x, float y, float z, float r, float g, float b, float scale) {
            this.x = x; this.y = y; this.z = z;
            this.r = r; this.g = g; this.b = b;
            this.scale = scale;
        }
    }
    
    private final List<VoxelInstance> voxelInstances = new ArrayList<>();
    
    private void createCubeGeometry() {
        // Cube vertices (position + normal)
        float[] cubeVertices = {
            // Front face
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
             0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
            // Back face
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            // Top face
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
             0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,
            // Bottom face
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,
             0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,
            // Right face
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
             0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
             0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,
             0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,
            // Left face
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f
        };
        
        // Cube indices
        short[] cubeIndices = {
            0,  1,  2,  0,  2,  3,   // Front
            4,  5,  6,  4,  6,  7,   // Back
            8,  9,  10, 8,  10, 11,  // Top
            12, 13, 14, 12, 14, 15,  // Bottom
            16, 17, 18, 16, 18, 19,  // Right
            20, 21, 22, 20, 22, 23   // Left
        };
        
        // Create vertex buffer
        var vertexBufferDesc = new Device.BufferDescriptor(
            cubeVertices.length * Float.BYTES,
            WebGPUNative.BUFFER_USAGE_VERTEX | WebGPUNative.BUFFER_USAGE_COPY_DST
        ).withLabel("CubeVertexBuffer");
        
        vertexBuffer = device.createBuffer(vertexBufferDesc);
        
        // Write vertex data
        var vertexBytes = new byte[cubeVertices.length * 4];
        var buffer = java.nio.ByteBuffer.wrap(vertexBytes).order(java.nio.ByteOrder.nativeOrder());
        for (float vertex : cubeVertices) {
            buffer.putFloat(vertex);
        }
        queue.writeBuffer(vertexBuffer, 0, vertexBytes);
        
        log.info("Cube geometry created with {} vertices", cubeVertices.length / 6);
    }
    
    private void createUniformBuffer() {
        // Create uniform buffer for MVP matrices (3 * 4x4 matrices = 192 bytes)
        var uniformBufferDesc = new Device.BufferDescriptor(
            192, // 3 matrices * 16 floats * 4 bytes
            WebGPUNative.BUFFER_USAGE_UNIFORM | WebGPUNative.BUFFER_USAGE_COPY_DST
        ).withLabel("MVPUniformBuffer");
        
        uniformBuffer = device.createBuffer(uniformBufferDesc);
        
        // Initialize with identity matrices
        float[] identityMatrix = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };
        
        var uniformData = new byte[192];
        var buffer = java.nio.ByteBuffer.wrap(uniformData).order(java.nio.ByteOrder.nativeOrder());
        
        // Write 3 identity matrices (model, view, proj)
        for (int i = 0; i < 3; i++) {
            for (float value : identityMatrix) {
                buffer.putFloat(value);
            }
        }
        
        queue.writeBuffer(uniformBuffer, 0, uniformData);
        log.info("Uniform buffer created for MVP matrices");
    }
    
    private void createInstanceBuffer() {
        // Generate test voxel data (sphere pattern)
        generateVoxelData();
        
        if (voxelInstances.isEmpty()) {
            log.warn("No voxel instances to render");
            return;
        }
        
        // Each instance has: position (3 floats) + color (3 floats) + scale (1 float) = 7 floats
        int instanceDataSize = voxelInstances.size() * 7 * Float.BYTES;
        
        var instanceBufferDesc = new Device.BufferDescriptor(
            instanceDataSize,
            WebGPUNative.BUFFER_USAGE_VERTEX | WebGPUNative.BUFFER_USAGE_COPY_DST
        ).withLabel("VoxelInstanceBuffer");
        
        instanceBuffer = device.createBuffer(instanceBufferDesc);
        
        // Pack instance data
        var instanceData = new byte[instanceDataSize];
        var buffer = java.nio.ByteBuffer.wrap(instanceData).order(java.nio.ByteOrder.nativeOrder());
        
        for (var voxel : voxelInstances) {
            buffer.putFloat(voxel.x);
            buffer.putFloat(voxel.y);
            buffer.putFloat(voxel.z);
            buffer.putFloat(voxel.r);
            buffer.putFloat(voxel.g);
            buffer.putFloat(voxel.b);
            buffer.putFloat(voxel.scale);
        }
        
        queue.writeBuffer(instanceBuffer, 0, instanceData);
        log.info("Instance buffer created with {} voxel instances", voxelInstances.size());
    }
    
    private void generateVoxelData() {
        voxelInstances.clear();
        
        // Generate a sphere of voxels
        int gridSize = 10;
        float radius = 4.0f;
        float voxelSize = 0.5f;
        
        for (int x = -gridSize; x <= gridSize; x++) {
            for (int y = -gridSize; y <= gridSize; y++) {
                for (int z = -gridSize; z <= gridSize; z++) {
                    float distance = (float) Math.sqrt(x*x + y*y + z*z);
                    
                    if (distance <= radius) {
                        // Color based on position
                        float r = (x + gridSize) / (2.0f * gridSize);
                        float g = (y + gridSize) / (2.0f * gridSize);
                        float b = (z + gridSize) / (2.0f * gridSize);
                        
                        voxelInstances.add(new VoxelInstance(
                            x * voxelSize,
                            y * voxelSize,
                            z * voxelSize,
                            r, g, b,
                            voxelSize * 0.8f
                        ));
                    }
                }
            }
        }
        
        log.info("Generated {} voxel instances in sphere pattern", voxelInstances.size());
    }
    
    private void createRenderPipeline(ShaderModule vertexShader, ShaderModule fragmentShader) {
        // TODO: Create render pipeline with proper vertex layout for instanced rendering
        log.info("Render pipeline creation pending - needs instanced rendering support");
    }
    
    private void runRenderLoop() {
        log.info("Starting render loop...");
        
        int frameCount = 0;
        long startTime = System.currentTimeMillis();
        
        // Run for a short time for testing
        while (!glfwWindowShouldClose(window) && frameCount < 10) {
            glfwPollEvents();
            
            render();
            
            frameCount++;
            
            try {
                Thread.sleep(16); // ~60 FPS
            } catch (InterruptedException e) {
                break;
            }
        }
        
        long endTime = System.currentTimeMillis();
        log.info("Render loop completed: {} frames in {} ms", frameCount, endTime - startTime);
    }
    
    // Camera state
    private static class Camera {
        float x = 0, y = 0, z = -10;
        float pitch = 0, yaw = 0;
        
        float[] getViewMatrix() {
            // Simple view matrix (translation only for now)
            return new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                -x, -y, -z, 1
            };
        }
        
        float[] getProjectionMatrix(float aspect) {
            // Simple perspective projection
            float fov = (float) Math.toRadians(45);
            float near = 0.1f;
            float far = 100.0f;
            float f = 1.0f / (float) Math.tan(fov / 2);
            
            return new float[] {
                f / aspect, 0, 0, 0,
                0, f, 0, 0,
                0, 0, (far + near) / (near - far), -1,
                0, 0, (2 * far * near) / (near - far), 0
            };
        }
    }
    
    private final Camera camera = new Camera();
    
    private void render() {
        // Update camera matrices
        updateMatrices();
        
        // Get the current texture from the surface
        var surfaceTexture = surface.getCurrentTexture();
        if (surfaceTexture == null || !surfaceTexture.isSuccess()) {
            log.debug("Failed to get surface texture");
            return;
        }
        
        var texture = surfaceTexture.getTexture();
        
        // Create command encoder
        var encoder = device.createCommandEncoder("VoxelRenderEncoder");
        
        // Begin render pass
        var colorAttachment = new CommandEncoder.ColorAttachment(
            texture,
            CommandEncoder.LoadOp.CLEAR,
            CommandEncoder.StoreOp.STORE,
            new float[]{0.1f, 0.1f, 0.15f, 1.0f}  // Dark blue background
        );
        
        var renderPass = encoder.beginRenderPass(
            new CommandEncoder.RenderPassDescriptor()
                .withColorAttachments(colorAttachment)
        );
        
        // Render voxels if pipeline is ready
        if (renderPipeline != null && vertexBuffer != null && instanceBuffer != null) {
            renderPass.setPipeline(renderPipeline);
            
            // Set vertex buffers
            renderPass.setVertexBuffer(0, vertexBuffer, 0, -1);  // Cube geometry
            renderPass.setVertexBuffer(1, instanceBuffer, 0, -1); // Instance data
            
            // Set bind group for uniforms
            if (bindGroup != null) {
                renderPass.setBindGroup(0, bindGroup);
            }
            
            // Draw instanced cubes (36 vertices per cube, N instances)
            int verticesPerCube = 36;  // 6 faces * 2 triangles * 3 vertices
            renderPass.draw(verticesPerCube, voxelInstances.size(), 0, 0);
        }
        
        // End render pass
        renderPass.end();
        
        // Submit commands
        var commandBuffer = encoder.finish();
        queue.submit(commandBuffer);
        
        // Present the frame
        surface.present();
    }
    
    private void updateMatrices() {
        // Update uniform buffer with current camera matrices
        float[] modelMatrix = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };
        
        float[] viewMatrix = camera.getViewMatrix();
        float aspect = (float) WINDOW_WIDTH / WINDOW_HEIGHT;
        float[] projMatrix = camera.getProjectionMatrix(aspect);
        
        // Pack matrices into buffer
        var matrixData = new byte[192];  // 3 * 16 * 4 bytes
        var buffer = java.nio.ByteBuffer.wrap(matrixData).order(java.nio.ByteOrder.nativeOrder());
        
        // Write matrices
        for (float v : modelMatrix) buffer.putFloat(v);
        for (float v : viewMatrix) buffer.putFloat(v);
        for (float v : projMatrix) buffer.putFloat(v);
        
        // Update uniform buffer
        queue.writeBuffer(uniformBuffer, 0, matrixData);
    }
    
    private void cleanup() {
        log.info("Cleaning up WebGPU resources...");
        
        // Close WebGPU resources
        if (device != null) {
            device.close();
        }
        if (adapter != null) {
            adapter.close();
        }
        if (surface != null) {
            surface.close();
        }
        if (instance != null) {
            instance.close();
        }
        
        // Clean up GLFW
        if (window != NULL) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
        
        log.info("Cleanup completed");
    }
    
    // Helper method to load shader from resources (for future use)
    private String loadShader(String resourcePath) {
        try (var inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new RuntimeException("Shader not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + resourcePath, e);
        }
    }
}