package com.hellblazer.luciferase.esvo.app;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.gpu.OctreeGPUMemory;
import com.hellblazer.luciferase.esvo.traversal.AdvancedRayTraversal;
import org.lwjgl.system.MemoryUtil;

import javax.vecmath.Vector3f;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main ESVO Application class that coordinates all components
 * Provides lifecycle management and high-level API
 */
public class ESVOApplication {
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private ESVOScene scene;
    private ESVOCamera camera;
    private ESVOPerformanceMonitor performanceMonitor;
    private AdvancedRayTraversal rayTraversal;
    private OctreeGPUMemory gpuMemory;
    private ExecutorService backgroundExecutor;
    
    // Configuration
    private int renderWidth = 800;
    private int renderHeight = 600;
    private boolean enableGPU = true;
    private int maxThreads = Runtime.getRuntime().availableProcessors();
    
    public ESVOApplication() {
        // Components will be initialized in initialize()
    }
    
    /**
     * Initialize the application and all components
     */
    public void initialize() {
        if (initialized.get()) {
            return;
        }
        
        try {
            // Initialize core components
            scene = new ESVOScene();
            camera = new ESVOCamera();
            performanceMonitor = new ESVOPerformanceMonitor();
            
            // Initialize ray traversal
            rayTraversal = new AdvancedRayTraversal();
            
            // Initialize GPU memory if enabled
            if (enableGPU) {
                gpuMemory = new OctreeGPUMemory(8 * 1024 * 1024); // 8M nodes (64MB total)
            }
            
            // Initialize background executor
            backgroundExecutor = Executors.newFixedThreadPool(Math.min(maxThreads, 8));
            
            // Set default camera position
            camera.setPosition(new Vector3f(1.5f, 1.5f, 3.0f));
            camera.setOrientation(0.0f, 0.0f); // Set yaw and pitch instead
            camera.setFieldOfView(60.0f);
            camera.setClippingPlanes(0.1f, 100.0f);
            camera.updateProjectionMatrix(renderWidth, renderHeight);
            
            initialized.set(true);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ESVO application", e);
        }
    }
    
    /**
     * Shutdown the application and clean up resources
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }
        
        running.set(false);
        
        // Shutdown background executor
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
        }
        
        // Clean up GPU memory
        if (gpuMemory != null) {
            gpuMemory.dispose(); // Use dispose() method
        }
        
        // Clear scene
        if (scene != null) {
            scene.clear();
        }
        
        initialized.set(false);
    }
    
    /**
     * Load an octree from file into the scene
     */
    public void loadOctreeFile(String name, Path filePath) throws IOException {
        if (!initialized.get()) {
            throw new IllegalStateException("Application not initialized");
        }
        
        scene.loadOctreeFromFile(name, filePath);
        
        // Upload to GPU if enabled
        if (enableGPU && gpuMemory != null) {
            ESVOOctreeData octree = scene.getOctree(name);
            uploadToGPU(name, octree);
        }
    }
    
    /**
     * Add an octree directly to the scene
     */
    public void addOctree(String name, ESVOOctreeData octree) {
        if (!initialized.get()) {
            throw new IllegalStateException("Application not initialized");
        }
        
        scene.loadOctree(name, octree);
        
        // Upload to GPU if enabled
        if (enableGPU && gpuMemory != null) {
            uploadToGPU(name, octree);
        }
    }
    
    /**
     * Render a frame
     */
    public void renderFrame() {
        if (!initialized.get()) {
            throw new IllegalStateException("Application not initialized");
        }
        
        performanceMonitor.startFrame();
        
        try {
            // Perform ray traversal for each pixel
            int raysTraversed = performRayTraversal();
            int nodesVisited = raysTraversed * 5; // Estimate
            int voxelsHit = raysTraversed / 2; // Estimate
            performanceMonitor.recordTraversal(raysTraversed, nodesVisited, voxelsHit);
            
        } finally {
            // End frame is handled by next startFrame() call
        }
    }
    
    /**
     * Set render resolution
     */
    public void setResolution(int width, int height) {
        this.renderWidth = width;
        this.renderHeight = height;
        
        if (camera != null) {
            camera.updateProjectionMatrix(width, height);
        }
    }
    
    /**
     * Enable or disable GPU acceleration
     */
    public void setGPUEnabled(boolean enabled) {
        this.enableGPU = enabled;
    }
    
    /**
     * Get the scene manager
     */
    public ESVOScene getScene() {
        return scene;
    }
    
    /**
     * Get the camera
     */
    public ESVOCamera getCamera() {
        return camera;
    }
    
    /**
     * Get the performance monitor
     */
    public ESVOPerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }
    
    /**
     * Check if application is initialized
     */
    public boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Check if application is running
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Start the application main loop
     */
    public void start() {
        if (!initialized.get()) {
            throw new IllegalStateException("Application not initialized");
        }
        
        running.set(true);
    }
    
    /**
     * Stop the application main loop
     */
    public void stop() {
        running.set(false);
    }
    
    // Private helper methods
    
    private void uploadToGPU(String name, ESVOOctreeData octree) {
        backgroundExecutor.submit(() -> {
            try {
                // Convert octree to GPU format and upload
                // This would typically involve converting to the GPU memory layout
                // and uploading via OpenCL/CUDA/Compute Shader
                
                // For now, we just reserve space
                int nodeCount = octree.getNodeIndices().length;
                long memoryRequired = nodeCount * 8L; // 8 bytes per node
                
                if (!gpuMemory.isDisposed()) {
                    // Upload octree nodes to GPU
                    int[] nodeIndices = octree.getNodeIndices();
                    for (int i = 0; i < Math.min(nodeIndices.length, gpuMemory.getNodeCount()); i++) {
                        var node = octree.getNode(nodeIndices[i]);
                        if (node != null) {
                            gpuMemory.writeNode(i, node.getChildMask(), node.getContourPtr());
                        }
                    }
                    gpuMemory.uploadToGPU();
                }
                
            } catch (Exception e) {
                // Log error but don't crash the application
                System.err.println("Failed to upload octree to GPU: " + e.getMessage());
            }
        });
    }
    
    private int performRayTraversal() {
        // Simple ray traversal for demonstration
        // In a real implementation, this would render to a framebuffer
        
        Vector3f rayOrigin = camera.getPosition();
        int raysTraversed = 0;
        
        // Traverse a sample of rays
        int sampleRays = Math.min(1000, renderWidth * renderHeight / 100);
        
        for (int i = 0; i < sampleRays; i++) {
            // Generate ray direction for this pixel
            float u = (float)Math.random();
            float v = (float)Math.random();
            
            // Generate ray direction from camera forward vector
            Vector3f rayDir = camera.getForward();
            
            // Traverse ray through scene octrees
            for (String octreeName : scene.getOctreeNames()) {
                ESVOOctreeData octree = scene.getOctree(octreeName);
                
                // Simple traversal - in practice would use GPU or optimized CPU traversal
                if (octree.getNode(0) != null) {
                    raysTraversed++;
                }
            }
        }
        
        return raysTraversed;
    }
}