package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.render.webgpu.resources.BufferPool;
import com.hellblazer.luciferase.render.webgpu.resources.CommandBufferManager;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.wrapper.CommandEncoder.*;
import com.hellblazer.luciferase.webgpu.wrapper.Texture.*;
import com.hellblazer.luciferase.webgpu.wrapper.Surface.SurfaceTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;

/**
 * WebGPU-native voxel visualization demo application.
 * Demonstrates efficient instanced rendering of voxels using WebGPU.
 */
public class WebGPUVoxelDemo {
    private static final Logger log = LoggerFactory.getLogger(WebGPUVoxelDemo.class);
    
    // Window settings
    private static final int WINDOW_WIDTH = 1400;
    private static final int WINDOW_HEIGHT = 900;
    private static final String WINDOW_TITLE = "WebGPU Voxel Demo";
    
    // Voxel settings
    private static final int GRID_SIZE = 32;
    private static final float VOXEL_SIZE = 1.0f;
    private static final float GRID_SPACING = VOXEL_SIZE * 1.2f;
    
    // Components
    private WebGPUContext context;
    private InstancedVoxelRenderer voxelRenderer;
    private CommandBufferManager commandManager;
    private Texture depthTexture;
    
    // Camera
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewProjectionMatrix = new Matrix4f();
    private final Matrix4f modelMatrix = new Matrix4f().identity();
    private final Vector3f cameraPosition = new Vector3f(50, 50, 50);
    private final Vector3f cameraTarget = new Vector3f(0, 0, 0);
    private final Vector3f cameraUp = new Vector3f(0, 1, 0);
    
    // Lighting
    private final Vector3f lightDirection = new Vector3f(-0.5f, -0.7f, -0.5f).normalize();
    
    // Animation
    private float time = 0.0f;
    private float rotationSpeed = 0.5f;
    private boolean autoRotate = true;
    
    // Input state
    private double lastMouseX, lastMouseY;
    private boolean mousePressed = false;
    private float cameraDistance = 100.0f;
    private float cameraYaw = 0.0f;
    private float cameraPitch = 0.3f;
    
    // Performance tracking
    private long frameCount = 0;
    private long lastFpsTime = System.nanoTime();
    private double currentFps = 0.0;
    
    // Voxel data
    private final List<InstancedVoxelRenderer.VoxelInstance> voxels = new ArrayList<>();
    
    public static void main(String[] args) {
        log.info("Starting WebGPU Voxel Demo");
        
        try {
            WebGPUVoxelDemo demo = new WebGPUVoxelDemo();
            demo.run();
        } catch (Exception e) {
            log.error("Demo failed", e);
            System.exit(1);
        }
    }
    
    public void run() {
        try {
            initialize();
            runRenderLoop();
        } finally {
            cleanup();
        }
    }
    
    private void initialize() {
        log.info("Initializing WebGPU Voxel Demo");
        
        // Create and initialize context
        context = new WebGPUContext(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE);
        context.initialize();
        
        // Create command buffer manager
        commandManager = new CommandBufferManager(context.getDevice(), context.getQueue());
        
        // Create voxel renderer
        voxelRenderer = new InstancedVoxelRenderer(context);
        voxelRenderer.initialize();
        
        // Create depth texture
        createDepthTexture();
        
        // Set up input callbacks
        setupInputCallbacks();
        
        // Generate initial voxel data
        generateVoxelData();
        
        // Update camera matrices
        updateCameraMatrices();
        
        log.info("Initialization complete");
    }
    
    private void createDepthTexture() {
        TextureDescriptor desc = new TextureDescriptor()
            .withLabel("Depth Texture")
            .withSize(context.getWidth(), context.getHeight())
            .withFormat(TextureFormat.DEPTH24_PLUS)
            .withUsage(TextureUsage.RENDER_ATTACHMENT)
            .withDimension(TextureDimension.D2)
            .withMipLevelCount(1)
            .withSampleCount(1);
        
        depthTexture = context.getDevice().createTexture(desc);
    }
    
    private void setupInputCallbacks() {
        long window = context.getWindow();
        
        // Mouse button callback
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                mousePressed = action == GLFW_PRESS;
                if (mousePressed) {
                    double[] xpos = new double[1];
                    double[] ypos = new double[1];
                    glfwGetCursorPos(window, xpos, ypos);
                    lastMouseX = xpos[0];
                    lastMouseY = ypos[0];
                }
            }
        });
        
        // Mouse move callback
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (mousePressed) {
                double dx = xpos - lastMouseX;
                double dy = ypos - lastMouseY;
                
                cameraYaw += (float) dx * 0.01f;
                cameraPitch += (float) dy * 0.01f;
                cameraPitch = Math.max(-1.5f, Math.min(1.5f, cameraPitch));
                
                lastMouseX = xpos;
                lastMouseY = ypos;
                updateCameraPosition();
            }
        });
        
        // Scroll callback for zoom
        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            cameraDistance -= (float) yoffset * 5.0f;
            cameraDistance = Math.max(10.0f, Math.min(500.0f, cameraDistance));
            updateCameraPosition();
        });
        
        // Key callback
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS) {
                switch (key) {
                    case GLFW_KEY_SPACE:
                        autoRotate = !autoRotate;
                        log.info("Auto-rotate: {}", autoRotate);
                        break;
                    case GLFW_KEY_R:
                        generateVoxelData();
                        log.info("Regenerated voxel data");
                        break;
                    case GLFW_KEY_ESCAPE:
                        glfwSetWindowShouldClose(window, true);
                        break;
                }
            }
        });
    }
    
    private void generateVoxelData() {
        voxels.clear();
        Random random = new Random();
        
        // Generate a sphere of voxels
        float radius = GRID_SIZE / 2.0f;
        Vector3f center = new Vector3f(0, 0, 0);
        
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    Vector3f pos = new Vector3f(
                        (x - GRID_SIZE / 2.0f) * GRID_SPACING,
                        (y - GRID_SIZE / 2.0f) * GRID_SPACING,
                        (z - GRID_SIZE / 2.0f) * GRID_SPACING
                    );
                    
                    // Check if inside sphere
                    float distance = pos.distance(center);
                    if (distance <= radius * GRID_SPACING) {
                        InstancedVoxelRenderer.VoxelInstance voxel = 
                            new InstancedVoxelRenderer.VoxelInstance();
                        
                        voxel.position = pos;
                        voxel.scale = new Vector3f(VOXEL_SIZE, VOXEL_SIZE, VOXEL_SIZE);
                        
                        // Color based on position
                        float r = (x / (float) GRID_SIZE);
                        float g = (y / (float) GRID_SIZE);
                        float b = (z / (float) GRID_SIZE);
                        voxel.color = new Vector4f(r, g, b, 1.0f);
                        
                        // Random material ID for variety
                        voxel.materialId = random.nextInt(5);
                        
                        voxels.add(voxel);
                    }
                }
            }
        }
        
        voxelRenderer.setVoxelInstances(voxels);
        log.info("Generated {} voxels", voxels.size());
    }
    
    private void updateCameraPosition() {
        float x = cameraDistance * (float) Math.cos(cameraPitch) * (float) Math.sin(cameraYaw);
        float y = cameraDistance * (float) Math.sin(cameraPitch);
        float z = cameraDistance * (float) Math.cos(cameraPitch) * (float) Math.cos(cameraYaw);
        
        cameraPosition.set(x, y, z);
        updateCameraMatrices();
    }
    
    private void updateCameraMatrices() {
        // View matrix
        viewMatrix.lookAt(cameraPosition, cameraTarget, cameraUp);
        
        // Projection matrix
        float aspect = (float) context.getWidth() / context.getHeight();
        projectionMatrix.setPerspective(
            (float) Math.toRadians(45.0),
            aspect,
            0.1f,
            1000.0f
        );
        
        // Combined view-projection
        projectionMatrix.mul(viewMatrix, viewProjectionMatrix);
    }
    
    private void runRenderLoop() {
        log.info("Starting render loop");
        
        while (!context.shouldClose()) {
            // Poll events
            context.pollEvents();
            
            // Update animation
            update();
            
            // Render frame
            render();
            
            // Update FPS
            updateFps();
        }
        
        log.info("Render loop ended");
    }
    
    private void update() {
        time += 0.016f; // ~60 FPS
        
        if (autoRotate) {
            cameraYaw += rotationSpeed * 0.016f;
            updateCameraPosition();
        }
        
        // Update uniforms
        voxelRenderer.updateUniforms(
            viewProjectionMatrix,
            modelMatrix,
            lightDirection,
            cameraPosition,
            time
        );
    }
    
    private void render() {
        // Get surface texture
        SurfaceTexture surfaceTexture = context.getCurrentTexture();
        if (surfaceTexture == null) {
            log.warn("getCurrentTexture returned null - surface may not be configured properly");
            clearWindow();
            return;
        }
        
        if (!surfaceTexture.isSuccess()) {
            log.warn("Surface texture status not SUCCESS: status={}, suboptimal={}", 
                surfaceTexture.getStatus(), surfaceTexture.isSuboptimal());
            clearWindow();
            return;
        }
        
        if (surfaceTexture.getTexture() == null) {
            log.warn("Surface texture handle is null despite SUCCESS status");
            clearWindow();
            return;
        }
        
        // Create texture view
        TextureViewDescriptor viewDesc = new TextureViewDescriptor()
            .withFormat(getTextureFormat(context.getSurfaceFormat()))
            .withDimension(TextureViewDimension.D2);
        TextureView targetView = surfaceTexture.getTexture().createView(viewDesc);
        
        // Create depth view
        TextureView depthView = depthTexture.createView(new TextureViewDescriptor());
        
        // Begin command encoding
        CommandEncoder encoder = commandManager.beginFrame("Frame " + frameCount);
        
        // Create render pass
        RenderPassDescriptor renderPassDesc = new RenderPassDescriptor()
            .withLabel("Main Render Pass");
        
        // Color attachment
        float[] clearColor = {0.1f, 0.1f, 0.15f, 1.0f};
        CommandEncoder.ColorAttachment colorAttachment = new CommandEncoder.ColorAttachment(
            surfaceTexture.getTexture(),
            CommandEncoder.LoadOp.CLEAR,
            CommandEncoder.StoreOp.STORE,
            clearColor
        );
        renderPassDesc.withColorAttachments(colorAttachment);
        
        // Depth attachment
        CommandEncoder.DepthStencilAttachment depthAttachment = new CommandEncoder.DepthStencilAttachment(
            depthTexture,
            CommandEncoder.LoadOp.CLEAR,
            CommandEncoder.StoreOp.STORE,
            1.0f
        );
        renderPassDesc.withDepthStencilAttachment(depthAttachment);
        
        // Begin render pass
        RenderPassEncoder renderPass = encoder.beginRenderPass(renderPassDesc);
        
        // Render voxels
        voxelRenderer.render(renderPass);
        
        // End render pass
        renderPass.end();
        
        // Submit commands
        commandManager.submit();
        
        // Present
        context.present();
        
        frameCount++;
    }
    
    private void updateFps() {
        long currentTime = System.nanoTime();
        long elapsed = currentTime - lastFpsTime;
        
        if (elapsed >= 1_000_000_000) { // 1 second
            currentFps = frameCount / (elapsed / 1_000_000_000.0);
            log.info("FPS: {:.1f}, Voxels: {}", currentFps, voxels.size());
            frameCount = 0;
            lastFpsTime = currentTime;
        }
    }
    
    /**
     * Clear the window with a magenta color to indicate surface issues.
     */
    private void clearWindow() {
        // Since WebGPU surface isn't working, we can't render anything
        // Just skip this frame - the window will remain black
        // This avoids GLFW errors since we don't have an OpenGL context
        log.debug("Skipping frame due to surface configuration issues");
    }
    
    private void cleanup() {
        log.info("Cleaning up");
        
        if (voxelRenderer != null) {
            voxelRenderer.cleanup();
        }
        
        if (commandManager != null) {
            commandManager.cleanup();
        }
        
        if (depthTexture != null) {
            // Texture cleanup - destroy() not available in current wrapper
        }
        
        if (context != null) {
            context.cleanup();
        }
        
        log.info("Cleanup complete");
    }
    
    /**
     * Convert integer surface format to TextureFormat enum.
     */
    private static TextureFormat getTextureFormat(int formatValue) {
        for (TextureFormat format : TextureFormat.values()) {
            if (format.getValue() == formatValue) {
                return format;
            }
        }
        // Default to BGRA8_UNORM if not found
        return TextureFormat.BGRA8_UNORM;
    }
}