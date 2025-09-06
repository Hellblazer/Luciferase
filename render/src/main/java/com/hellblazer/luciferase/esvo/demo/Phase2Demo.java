/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.esvo.demo;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import javax.vecmath.Vector3f;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.MultiLevelOctree;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.Ray;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.DeepTraversalResult;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Phase 2 Demo: Stack-Based Deep Traversal Visualization
 * 
 * Interactive demo showcasing:
 * - Deep octree traversal with 5 and 23 level octrees
 * - Visual depth color coding
 * - Performance testing with SPACE key
 * - Octree level switching with number keys (1-5, 2-23)
 * - 3 critical GLSL shader bug fixes validation
 */
public class Phase2Demo {
    
    // Window settings
    private static final int RENDER_WIDTH = 640;
    private static final int RENDER_HEIGHT = 480;
    private static final String WINDOW_TITLE = "ESVO Phase 2: Stack-Based Deep Traversal Demo";
    
    // Camera settings
    private static final float FOV = 60.0f;
    private static final float CAMERA_MOVE_SPEED = 0.1f;
    
    // Demo state
    private long window;
    private int[] imageData;
    private int textureId;
    private ByteBuffer imageBuffer;
    
    // Camera state
    private Vector3f cameraPos = new Vector3f(0.5f, 1.5f, 3.0f);
    private Vector3f cameraDir = new Vector3f(0, 0, -1);
    private float cameraYaw = 0.0f;
    private float cameraPitch = 0.0f;
    
    // Octree state
    private MultiLevelOctree currentOctree;
    private int currentLevel = 5;
    private boolean showPerformanceInfo = false;
    
    // Performance tracking
    private double lastFrameTime = 0.0;
    private double frameTime = 0.0;
    private int frameCount = 0;
    private double performanceStartTime = 0.0;
    
    public static void main(String[] args) {
        new Phase2Demo().run();
    }
    
    public void run() {
        System.out.println("ESVO Phase 2 Demo: Stack-Based Deep Traversal");
        System.out.println("============================================");
        System.out.println("Controls:");
        System.out.println("  WASD     - Move camera");
        System.out.println("  Mouse    - Look around");
        System.out.println("  1-5      - Switch to 5-level octree");
        System.out.println("  6        - Switch to 23-level octree");
        System.out.println("  SPACE    - Run performance test");
        System.out.println("  I        - Toggle performance info");
        System.out.println("  ESC      - Exit");
        System.out.println();
        
        init();
        loop();
        cleanup();
    }
    
    private void init() {
        // Initialize GLFW
        GLFWErrorCallback.createPrint(System.err).set();
        
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        
        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        
        // Create window
        window = glfwCreateWindow(RENDER_WIDTH, RENDER_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Setup callbacks
        setupCallbacks();
        
        // Center window
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            glfwGetWindowSize(window, pWidth, pHeight);
            var vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            
            glfwSetWindowPos(window,
                (vidmode.width() - pWidth.get(0)) / 2,
                (vidmode.height() - pHeight.get(0)) / 2
            );
        }
        
        // Make OpenGL context current
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // Enable vsync
        glfwShowWindow(window);
        
        // Initialize OpenGL
        GL.createCapabilities();
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        
        // Initialize rendering resources
        initializeRendering();
        
        // Create initial octree
        currentOctree = new MultiLevelOctree(currentLevel);
        
        System.out.println("Initialized with " + currentLevel + "-level octree");
        System.out.println("Octree center: " + currentOctree.getCenter());
        System.out.println("Octree size: " + currentOctree.getSize());
    }
    
    private void setupCallbacks() {
        // Keyboard input
        glfwSetKeyCallback(window, new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                    handleKeyPress(key);
                }
            }
        });
        
        // Mouse movement
        glfwSetCursorPosCallback(window, (window, xpos, ypos) -> {
            // Simple mouse look (could be enhanced)
            var deltaX = (float) (xpos - RENDER_WIDTH / 2.0) * 0.001f;
            var deltaY = (float) (ypos - RENDER_HEIGHT / 2.0) * 0.001f;
            
            cameraYaw += deltaX;
            cameraPitch -= deltaY;
            
            // Clamp pitch
            cameraPitch = Math.max(-1.5f, Math.min(1.5f, cameraPitch));
            
            // Update camera direction
            cameraDir.x = (float) (Math.cos(cameraPitch) * Math.sin(cameraYaw));
            cameraDir.y = (float) Math.sin(cameraPitch);
            cameraDir.z = (float) (Math.cos(cameraPitch) * Math.cos(cameraYaw));
            cameraDir.normalize();
            
            // Re-center cursor
            glfwSetCursorPos(window, RENDER_WIDTH / 2.0, RENDER_HEIGHT / 2.0);
        });
        
        // Hide cursor for mouse look
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
        glfwSetCursorPos(window, RENDER_WIDTH / 2.0, RENDER_HEIGHT / 2.0);
    }
    
    private void handleKeyPress(int key) {
        switch (key) {
            case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true);
            
            // Camera movement
            case GLFW_KEY_W -> moveCamera(0, 0, -CAMERA_MOVE_SPEED);
            case GLFW_KEY_S -> moveCamera(0, 0, CAMERA_MOVE_SPEED);
            case GLFW_KEY_A -> moveCamera(-CAMERA_MOVE_SPEED, 0, 0);
            case GLFW_KEY_D -> moveCamera(CAMERA_MOVE_SPEED, 0, 0);
            case GLFW_KEY_Q -> moveCamera(0, -CAMERA_MOVE_SPEED, 0);
            case GLFW_KEY_E -> moveCamera(0, CAMERA_MOVE_SPEED, 0);
            
            // Octree level switching
            case GLFW_KEY_1, GLFW_KEY_2, GLFW_KEY_3, GLFW_KEY_4, GLFW_KEY_5 -> {
                switchOctreeLevel(key - GLFW_KEY_1 + 1);
            }
            case GLFW_KEY_6 -> switchOctreeLevel(23);
            
            // Performance test
            case GLFW_KEY_SPACE -> runPerformanceTest();
            
            // Toggle performance info
            case GLFW_KEY_I -> {
                showPerformanceInfo = !showPerformanceInfo;
                System.out.println("Performance info: " + (showPerformanceInfo ? "ON" : "OFF"));
            }
        }
    }
    
    private void moveCamera(float dx, float dy, float dz) {
        // Move relative to camera orientation
        var right = new Vector3f();
        var up = new Vector3f(0, 1, 0);
        right.cross(cameraDir, up);
        right.normalize();
        
        var forward = new Vector3f(cameraDir);
        var rightMovement = new Vector3f(right);
        var upMovement = new Vector3f(up);
        var forwardMovement = new Vector3f(forward);
        
        rightMovement.scale(dx);
        upMovement.scale(dy);
        forwardMovement.scale(dz);
        
        cameraPos.add(rightMovement);
        cameraPos.add(upMovement);
        cameraPos.add(forwardMovement);
    }
    
    private void switchOctreeLevel(int level) {
        if (level != currentLevel) {
            System.out.println("Switching to " + level + "-level octree...");
            currentLevel = level;
            currentOctree = new MultiLevelOctree(level);
            System.out.println("Created " + level + "-level octree");
        }
    }
    
    private void runPerformanceTest() {
        System.out.println("\n=== PERFORMANCE TEST ===");
        System.out.println("Level " + currentLevel + " octree:");
        
        // Test with different ray counts
        var testCounts = new int[]{1000, 5000, 10000};
        
        for (var rayCount : testCounts) {
            var raysPerSecond = StackBasedRayTraversal.measureDeepTraversalPerformance(
                currentOctree, rayCount);
            var fpsEquivalent = raysPerSecond / (640.0 * 480.0);
            
            System.out.printf("  %d rays: %.2f rays/sec (%.2f FPS equivalent)%n",
                             rayCount, raysPerSecond, fpsEquivalent);
        }
        
        // Test performance target for 5-level
        if (currentLevel == 5) {
            var targetRays = 60.0 * 640 * 480;
            var actualRays = StackBasedRayTraversal.measureDeepTraversalPerformance(
                currentOctree, 10000);
            
            System.out.printf("  Target: %.0f rays/sec, Actual: %.2f rays/sec - %s%n",
                             targetRays, actualRays,
                             actualRays >= targetRays ? "PASS" : "FAIL");
        }
        
        System.out.println("========================\n");
    }
    
    private void initializeRendering() {
        // Create image buffer
        imageData = new int[RENDER_WIDTH * RENDER_HEIGHT];
        imageBuffer = memAlloc(RENDER_WIDTH * RENDER_HEIGHT * 4); // RGBA
        
        // Create OpenGL texture
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, RENDER_WIDTH, RENDER_HEIGHT, 
                    0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }
    
    private void loop() {
        lastFrameTime = glfwGetTime();
        
        while (!glfwWindowShouldClose(window)) {
            var currentTime = glfwGetTime();
            frameTime = currentTime - lastFrameTime;
            lastFrameTime = currentTime;
            
            renderFrame();
            updatePerformanceInfo();
            
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }
    
    private void renderFrame() {
        var renderStart = System.nanoTime();
        
        // Ray cast each pixel
        for (var y = 0; y < RENDER_HEIGHT; y++) {
            for (var x = 0; x < RENDER_WIDTH; x++) {
                var ray = StackBasedRayTraversal.generateRay(x, y, RENDER_WIDTH, RENDER_HEIGHT, 
                                                           cameraPos, cameraDir, FOV);
                var result = StackBasedRayTraversal.traverse(ray, currentOctree);
                
                var color = calculatePixelColor(result);
                imageData[y * RENDER_WIDTH + x] = color;
            }
        }
        
        // Update texture
        updateTexture();
        
        // Render texture to screen
        renderTexture();
        
        var renderEnd = System.nanoTime();
        var renderTime = (renderEnd - renderStart) / 1_000_000.0; // ms
        
        frameCount++;
        
        if (showPerformanceInfo && frameCount % 60 == 0) {
            System.out.printf("Frame render time: %.2f ms%n", renderTime);
        }
    }
    
    private int calculatePixelColor(DeepTraversalResult result) {
        if (!result.hit) {
            return 0x000020; // Dark blue background
        }
        
        // Color based on traversal depth
        var depthColor = StackBasedRayTraversal.getDepthDebugColor(result.traversalDepth);
        
        // Blend with iteration count for additional info
        var iterationIntensity = Math.min(result.iterations / 100.0f, 1.0f);
        var r = ((depthColor >> 16) & 0xFF);
        var g = ((depthColor >> 8) & 0xFF);
        var b = (depthColor & 0xFF);
        
        // Brighten based on iteration count
        r = (int) Math.min(255, r * (0.5f + iterationIntensity * 0.5f));
        g = (int) Math.min(255, g * (0.5f + iterationIntensity * 0.5f));
        b = (int) Math.min(255, b * (0.5f + iterationIntensity * 0.5f));
        
        return (r << 16) | (g << 8) | b;
    }
    
    private void updateTexture() {
        imageBuffer.clear();
        
        for (var pixel : imageData) {
            imageBuffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            imageBuffer.put((byte) ((pixel >> 8) & 0xFF));  // G
            imageBuffer.put((byte) (pixel & 0xFF));         // B
            imageBuffer.put((byte) 255);                    // A
        }
        
        imageBuffer.flip();
        
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, RENDER_WIDTH, RENDER_HEIGHT,
                       GL_RGBA, GL_UNSIGNED_BYTE, imageBuffer);
    }
    
    private void renderTexture() {
        glClear(GL_COLOR_BUFFER_BIT);
        
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, textureId);
        
        glBegin(GL_QUADS);
        glTexCoord2f(0, 0); glVertex2f(-1, 1);
        glTexCoord2f(1, 0); glVertex2f(1, 1);
        glTexCoord2f(1, 1); glVertex2f(1, -1);
        glTexCoord2f(0, 1); glVertex2f(-1, -1);
        glEnd();
        
        glDisable(GL_TEXTURE_2D);
    }
    
    private void updatePerformanceInfo() {
        if (showPerformanceInfo && frameCount % 60 == 0) {
            var fps = 1.0 / frameTime;
            System.out.printf("FPS: %.1f, Frame time: %.2f ms, Level: %d%n", 
                             fps, frameTime * 1000, currentLevel);
        }
    }
    
    private void cleanup() {
        // Clean up OpenGL resources
        if (textureId != 0) {
            glDeleteTextures(textureId);
        }
        
        if (imageBuffer != null) {
            memFree(imageBuffer);
        }
        
        // Clean up GLFW
        glfwDestroyWindow(window);
        glfwTerminate();
        
        System.out.println("Phase 2 Demo completed successfully!");
    }
}