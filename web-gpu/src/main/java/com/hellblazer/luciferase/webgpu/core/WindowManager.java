package com.hellblazer.luciferase.webgpu.core;

import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Manages GLFW window creation and lifecycle.
 * Simplified approach focused on WebGPU integration.
 */
public class WindowManager {
    private static final Logger log = LoggerFactory.getLogger(WindowManager.class);
    
    private long window = NULL;
    private int width;
    private int height;
    private String title;
    private boolean initialized = false;
    
    // Callbacks
    private GLFWErrorCallback errorCallback;
    private GLFWWindowSizeCallback sizeCallback;
    
    public WindowManager(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
    }
    
    /**
     * Initialize GLFW and create the window.
     * @return true if successful
     */
    public boolean initialize() {
        if (initialized) {
            return true;
        }
        
        // Setup error callback
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);
        
        // Initialize GLFW
        if (!glfwInit()) {
            log.error("Failed to initialize GLFW");
            return false;
        }
        
        // Configure GLFW for WebGPU (no OpenGL context)
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); // Important: No OpenGL
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Start hidden
        
        // Create window
        window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window == NULL) {
            log.error("Failed to create GLFW window");
            cleanup();
            return false;
        }
        
        // Setup callbacks
        setupCallbacks();
        
        // Center window on screen
        centerWindow();
        
        initialized = true;
        log.info("Window created: {}x{} - {}", width, height, title);
        return true;
    }
    
    private void setupCallbacks() {
        // Window size callback
        sizeCallback = GLFWWindowSizeCallback.create((window, w, h) -> {
            this.width = w;
            this.height = h;
            log.debug("Window resized: {}x{}", w, h);
        });
        glfwSetWindowSizeCallback(window, sizeCallback);
    }
    
    private void centerWindow() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pWidth = stack.mallocInt(1);
            var pHeight = stack.mallocInt(1);
            
            // Get window size
            glfwGetWindowSize(window, pWidth, pHeight);
            
            // Get primary monitor resolution
            GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidMode != null) {
                // Center window
                glfwSetWindowPos(
                    window,
                    (vidMode.width() - pWidth.get(0)) / 2,
                    (vidMode.height() - pHeight.get(0)) / 2
                );
            }
        }
    }
    
    /**
     * Show the window.
     */
    public void show() {
        if (window != NULL) {
            glfwShowWindow(window);
        }
    }
    
    /**
     * Hide the window.
     */
    public void hide() {
        if (window != NULL) {
            glfwHideWindow(window);
        }
    }
    
    /**
     * Poll for window events.
     */
    public void pollEvents() {
        glfwPollEvents();
    }
    
    /**
     * Check if the window should close.
     */
    public boolean shouldClose() {
        return window != NULL && glfwWindowShouldClose(window);
    }
    
    /**
     * Get the native window handle for surface creation.
     * On macOS, this returns an NSWindow handle.
     * On Windows, this returns an HWND.
     * On Linux, this returns an X11 Window.
     */
    public long getNativeHandle() {
        if (window == NULL) {
            throw new IllegalStateException("Window not created");
        }
        
        // GLFW window handle can be used directly for native access
        // Platform-specific surface factories will know how to interpret it
        return window;
    }
    
    /**
     * Get current window width.
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Get current window height.
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Cleanup and destroy the window.
     */
    public void cleanup() {
        // Free callbacks
        if (sizeCallback != null) {
            sizeCallback.free();
            sizeCallback = null;
        }
        
        // Destroy window
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        
        // Terminate GLFW
        glfwTerminate();
        
        // Free error callback
        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null;
        }
        
        initialized = false;
        log.info("Window cleaned up");
    }
    
    /**
     * Check if the window is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get the GLFW window handle.
     */
    public long getWindow() {
        return window;
    }
}