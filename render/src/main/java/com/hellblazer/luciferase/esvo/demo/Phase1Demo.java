package com.hellblazer.luciferase.esvo.demo;

import com.hellblazer.luciferase.esvo.core.CoordinateSpace;
import com.hellblazer.luciferase.esvo.core.OctreeNode;
import com.hellblazer.luciferase.esvo.traversal.BasicRayTraversal;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Phase 1 Demo Application for ESVO Basic Ray Traversal
 * 
 * This demonstrates the fundamental ESVO ray traversal implementation
 * with single-level octree testing, meeting Phase 1 requirements:
 * 
 * - Ray generation in octree space [1,2]
 * - Single-level intersection testing  
 * - Child index calculation with mirroring
 * - Visual debug output (color = octant)
 * - Performance measurement (target: >100 FPS)
 * 
 * Press SPACE to run performance test
 * Press R to reset camera
 * Press ESC to exit
 */
public final class Phase1Demo {
    private static final Logger log = LoggerFactory.getLogger(Phase1Demo.class);
    
    // Demo constants
    private static final int WINDOW_WIDTH = 800;
    private static final int WINDOW_HEIGHT = 600;
    private static final String WINDOW_TITLE = "ESVO Phase 1: Basic Ray Traversal Demo";
    
    // Render constants
    private static final int RENDER_WIDTH = 256;  
    private static final int RENDER_HEIGHT = 256;
    private static final float FOV = (float) Math.PI / 4; // 45 degrees
    
    // Performance test constants
    private static final int PERFORMANCE_RAY_COUNT = 100_000;
    private static final double TARGET_FPS = 100.0;
    private static final double TARGET_RAYS_PER_SECOND = TARGET_FPS * RENDER_WIDTH * RENDER_HEIGHT;
    
    private long window;
    private BasicRayTraversal.SimpleOctree octree;
    private ByteBuffer imageBuffer;
    private int[] imageData;
    
    // Camera state
    private Vector3f cameraPos;
    private Vector3f cameraDir;
    private Vector3f cameraUp;
    
    private Phase1Demo() {
        cameraPos = new Vector3f(0.5f, 1.5f, 1.5f); // Left of octree
        cameraDir = new Vector3f(1.0f, 0.0f, 0.0f);  // Point toward octree
        cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);   // Up vector
    }
    
    public static void main(String[] args) {
        log.info("Starting ESVO Phase 1 Demo...");
        new Phase1Demo().run();
    }
    
    private void run() {
        try {
            init();
            loop();
        } catch (Exception e) {
            log.error("Demo failed", e);
        } finally {
            cleanup();
        }
    }
    
    private void init() {
        // Setup error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        
        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        
        // Create window
        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Setup key callback
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            } else if (key == GLFW_KEY_SPACE && action == GLFW_RELEASE) {
                runPerformanceTest();
            } else if (key == GLFW_KEY_R && action == GLFW_RELEASE) {
                resetCamera();
            }
        });
        
        // Center window on screen
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            glfwGetWindowSize(window, pWidth, pHeight);
            
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            
            glfwSetWindowPos(
                window,
                (vidmode.width() - pWidth.get(0)) / 2,
                (vidmode.height() - pHeight.get(0)) / 2
            );
        }
        
        // Make OpenGL context current
        glfwMakeContextCurrent(window);
        
        // Enable v-sync
        glfwSwapInterval(1);
        
        // Make window visible
        glfwShowWindow(window);
        
        // Initialize OpenGL
        GL.createCapabilities();
        
        // Setup OpenGL viewport
        glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glOrtho(0, WINDOW_WIDTH, WINDOW_HEIGHT, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        
        // Create test octree
        createTestOctree();
        
        // Initialize image buffer
        imageData = new int[RENDER_WIDTH * RENDER_HEIGHT];
        imageBuffer = BufferUtils.createByteBuffer(RENDER_WIDTH * RENDER_HEIGHT * 3); // RGB
        
        log.info("Demo initialized successfully");
        log.info("Controls:");
        log.info("  SPACE - Run performance test");
        log.info("  R - Reset camera");
        log.info("  ESC - Exit");
    }
    
    private void createTestOctree() {
        // Create test octree with interesting geometry pattern
        // Valid mask = 0b11110000 (octants 4,5,6,7 have geometry - upper half)
        byte validMask = (byte) 0xF0;
        byte nonLeafMask = 0; // All leaves for Phase 1
        int childPointer = 0;
        
        OctreeNode rootNode = new OctreeNode(nonLeafMask, validMask, false, childPointer, (byte)0, 0);
        octree = new BasicRayTraversal.SimpleOctree(rootNode);
        
        log.info("Created test octree: valid mask = 0x{:02X} (octants 4,5,6,7)", validMask & 0xFF);
    }
    
    private void resetCamera() {
        cameraPos.set(0.5f, 1.5f, 1.5f);
        cameraDir.set(1.0f, 0.0f, 0.0f);
        cameraUp.set(0.0f, 1.0f, 0.0f);
        log.info("Camera reset to default position");
    }
    
    private void loop() {
        log.info("Starting render loop...");
        
        long lastTime = System.nanoTime();
        int frameCount = 0;
        double totalRenderTime = 0;
        
        while (!glfwWindowShouldClose(window)) {
            long frameStart = System.nanoTime();
            
            // Render frame
            renderFrame();
            
            // Update display
            displayFrame();
            
            // Swap buffers and poll events
            glfwSwapBuffers(window);
            glfwPollEvents();
            
            // Calculate timing
            long frameEnd = System.nanoTime();
            double frameTime = (frameEnd - frameStart) / 1_000_000.0; // ms
            totalRenderTime += frameTime;
            frameCount++;
            
            // Print FPS every second
            if (frameEnd - lastTime >= 1_000_000_000L) {
                double avgFrameTime = totalRenderTime / frameCount;
                double fps = frameCount / ((frameEnd - lastTime) / 1_000_000_000.0);
                
                log.info("FPS: {:.1f}, Avg Frame Time: {:.2f}ms", fps, avgFrameTime);
                
                lastTime = frameEnd;
                frameCount = 0;
                totalRenderTime = 0;
            }
        }
    }
    
    private void renderFrame() {
        // Clear image buffer
        for (int i = 0; i < imageData.length; i++) {
            imageData[i] = 0x000000; // Black
        }
        
        // Ray trace each pixel
        for (int y = 0; y < RENDER_HEIGHT; y++) {
            for (int x = 0; x < RENDER_WIDTH; x++) {
                // Generate ray for this pixel
                BasicRayTraversal.Ray ray = BasicRayTraversal.generateRay(
                    x, y, RENDER_WIDTH, RENDER_HEIGHT, cameraPos, cameraDir, FOV);
                
                // Traverse octree
                BasicRayTraversal.TraversalResult result = BasicRayTraversal.traverse(ray, octree);
                
                // Set pixel color based on result
                int color;
                if (result.hit) {
                    color = BasicRayTraversal.getOctantDebugColor(result.octant);
                } else {
                    color = 0x000000; // Black (no hit)
                }
                
                imageData[y * RENDER_WIDTH + x] = color;
            }
        }
        
        // Convert to byte buffer for display
        imageBuffer.clear();
        for (int color : imageData) {
            imageBuffer.put((byte) ((color >> 16) & 0xFF)); // R
            imageBuffer.put((byte) ((color >> 8) & 0xFF));  // G
            imageBuffer.put((byte) (color & 0xFF));         // B
        }
        imageBuffer.flip();
    }
    
    private void displayFrame() {
        glClear(GL_COLOR_BUFFER_BIT);
        
        // Calculate display scaling to fit window
        float scaleX = (float) WINDOW_WIDTH / RENDER_WIDTH;
        float scaleY = (float) WINDOW_HEIGHT / RENDER_HEIGHT;
        float scale = Math.min(scaleX, scaleY);
        
        int displayWidth = (int) (RENDER_WIDTH * scale);
        int displayHeight = (int) (RENDER_HEIGHT * scale);
        int offsetX = (WINDOW_WIDTH - displayWidth) / 2;
        int offsetY = (WINDOW_HEIGHT - displayHeight) / 2;
        
        // Display rendered image
        glRasterPos2f(offsetX, offsetY + displayHeight);
        glPixelZoom(scale, -scale);
        glDrawPixels(RENDER_WIDTH, RENDER_HEIGHT, GL_RGB, GL_UNSIGNED_BYTE, imageBuffer);
        
        // Reset pixel zoom
        glPixelZoom(1.0f, 1.0f);
    }
    
    private void runPerformanceTest() {
        log.info("\n=== PHASE 1 PERFORMANCE TEST ===");
        log.info("Target: >100 FPS at {}x{} = {:.1f}M rays/sec", 
                RENDER_WIDTH, RENDER_HEIGHT, TARGET_RAYS_PER_SECOND / 1_000_000.0);
        
        double raysPerSecond = BasicRayTraversal.measureTraversalPerformance(
            octree, PERFORMANCE_RAY_COUNT);
        
        double fps = raysPerSecond / (RENDER_WIDTH * RENDER_HEIGHT);
        
        log.info("\nRESULTS:");
        log.info("  Ray Performance: {:.0f} rays/second ({:.1f}M rays/sec)", 
                raysPerSecond, raysPerSecond / 1_000_000.0);
        log.info("  Equivalent FPS:  {:.1f} FPS at {}x{}", fps, RENDER_WIDTH, RENDER_HEIGHT);
        
        boolean targetMet = fps >= TARGET_FPS;
        log.info("  Target Status:   {} (target: {:.0f} FPS)", 
                targetMet ? "PASSED" : "FAILED", TARGET_FPS);
        
        if (targetMet) {
            log.info("\n✅ Phase 1 performance target achieved!");
        } else {
            log.warn("\n❌ Phase 1 performance target not met.");
            log.warn("   Current: {:.1f} FPS, Required: {:.0f} FPS", fps, TARGET_FPS);
        }
        
        // Test different ray counts for scaling analysis
        log.info("\n=== SCALING ANALYSIS ===");
        int[] testCounts = {1_000, 10_000, 100_000, 1_000_000};
        for (int count : testCounts) {
            double rps = BasicRayTraversal.measureTraversalPerformance(octree, count);
            log.info("{:7d} rays: {:8.0f} rays/sec ({:5.1f}M rays/sec)", 
                    count, rps, rps / 1_000_000.0);
        }
        
        log.info("\n=== COORDINATE SPACE VALIDATION ===");
        Vector3f center = octree.getCenter();
        float halfSize = octree.getHalfSize();
        log.info("Octree center: ({:.1f}, {:.1f}, {:.1f})", center.x, center.y, center.z);
        log.info("Octree half-size: {:.1f}", halfSize);
        log.info("Octree bounds: [{:.1f}, {:.1f}] in all dimensions", 
                CoordinateSpace.OCTREE_MIN, CoordinateSpace.OCTREE_MAX);
        
        log.info("\n=== OCTANT DEBUG COLORS ===");
        String[] octantNames = {"000", "001", "010", "011", "100", "101", "110", "111"};
        for (int i = 0; i < 8; i++) {
            int color = BasicRayTraversal.getOctantDebugColor(i);
            boolean hasGeometry = (octree.getRootNode().getValidMask() & (1 << i)) != 0;
            log.info("Octant {} ({}): 0x{:06X} {}", i, octantNames[i], color, 
                    hasGeometry ? "[HAS GEOMETRY]" : "[EMPTY]");
        }
        
        log.info("\n=== TEST COMPLETED ===\n");
    }
    
    private void cleanup() {
        // Free callbacks and destroy window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        
        // Terminate GLFW and free error callback
        glfwTerminate();
        glfwSetErrorCallback(null).free();
        
        log.info("Demo cleanup completed");
    }
}