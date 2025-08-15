package com.hellblazer.luciferase.render.lwjgl;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import java.nio.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct LWJGL renderer - no WebGPU emulation, just clean OpenGL.
 */
public class LWJGLRenderer {
    private static final Logger log = LoggerFactory.getLogger(LWJGLRenderer.class);
    
    private long window;
    private int width = 800;
    private int height = 600;
    private String title = "LWJGL Renderer";
    
    public LWJGLRenderer() {
    }
    
    public LWJGLRenderer(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
    }
    
    /**
     * Initialize GLFW and create window
     */
    public void init() {
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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
        
        // Create window
        window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Setup key callback
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
        });
        
        // Get thread stack and push new frame
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            // Get window size
            glfwGetWindowSize(window, pWidth, pHeight);
            
            // Get resolution of primary monitor
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            
            // Center window
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
        
        // Create capabilities
        GL.createCapabilities();
        
        // Setup OpenGL
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        
        log.info("LWJGL Renderer initialized with OpenGL {}", glGetString(GL_VERSION));
    }
    
    /**
     * Main render loop
     */
    public void run() {
        init();
        loop();
        cleanup();
    }
    
    /**
     * Render loop
     */
    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            
            // Render here
            render();
            
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }
    
    /**
     * Override this to render your scene
     */
    protected void render() {
        // Subclasses implement rendering
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        // Free callbacks and destroy window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        
        // Terminate GLFW
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }
    
    /**
     * Check if window should close
     */
    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }
    
    /**
     * Get window handle
     */
    public long getWindow() {
        return window;
    }
    
    /**
     * Update viewport for window resize
     */
    public void updateViewport(int width, int height) {
        this.width = width;
        this.height = height;
        glViewport(0, 0, width, height);
    }
}