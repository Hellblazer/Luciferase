package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Minimal WebGPU demo following working patterns from jWebGPU reference.
 * This demo addresses the key issues identified in the analysis:
 * 1. Proper async initialization
 * 2. Simplified surface creation  
 * 3. Better error handling
 * 4. Clear state management
 */
public class MinimalWebGPUDemo {
    private static final Logger log = LoggerFactory.getLogger(MinimalWebGPUDemo.class);
    
    // WebGPU objects
    private Instance instance;
    private Surface surface;
    private Adapter adapter;
    private Device device;
    private Queue queue;
    
    // Window management
    private long window;
    private int width = 800;
    private int height = 600;
    
    // State management - following jWebGPU pattern
    private volatile InitState initState = InitState.NOT_STARTED;
    
    enum InitState {
        NOT_STARTED,
        WEBGPU_INITIALIZING,
        WEBGPU_READY,
        SURFACE_CREATING,
        DEVICE_REQUESTING,
        FULLY_INITIALIZED,
        ERROR
    }
    
    public static void main(String[] args) {
        System.out.println("MinimalWebGPUDemo: Starting main method");
        var demo = new MinimalWebGPUDemo();
        demo.run();
    }
    
    public void run() {
        System.out.println("MinimalWebGPUDemo: run() called");
        log.info("Starting minimal WebGPU demo");
        
        try {
            // Phase 1: Initialize WebGPU library
            System.out.println("MinimalWebGPUDemo: About to initialize WebGPU");
            initializeWebGPU();
            System.out.println("MinimalWebGPUDemo: WebGPU initialized");
            
            // Phase 2: Create window
            System.out.println("MinimalWebGPUDemo: About to create window");
            createWindow();
            System.out.println("MinimalWebGPUDemo: Window created");
            
            // Phase 3: Main render loop with state management
            System.out.println("MinimalWebGPUDemo: About to enter main loop");
            mainLoop();
            System.out.println("MinimalWebGPUDemo: Main loop ended");
            
        } catch (Exception e) {
            System.out.println("MinimalWebGPUDemo: Exception caught: " + e.getMessage());
            e.printStackTrace();
            log.error("Demo failed", e);
            initState = InitState.ERROR;
        } finally {
            cleanup();
        }
    }
    
    /**
     * Initialize WebGPU following proper async pattern
     */
    private void initializeWebGPU() {
        System.out.println("MinimalWebGPUDemo: initializeWebGPU() called");
        log.info("Initializing WebGPU library");
        initState = InitState.WEBGPU_INITIALIZING;
        
        try {
            // Initialize the WebGPU FFM bindings
            System.out.println("MinimalWebGPUDemo: About to call WebGPU.initialize()");
            if (!WebGPU.initialize()) {
                throw new RuntimeException("Failed to initialize WebGPU FFM bindings");
            }
            
            log.info("WebGPU FFM bindings initialized");
            initState = InitState.WEBGPU_READY;
            
        } catch (Exception e) {
            log.error("WebGPU initialization failed", e);
            initState = InitState.ERROR;
            throw new RuntimeException("WebGPU initialization failed", e);
        }
    }
    
    /**
     * Create GLFW window with WebGPU configuration
     */
    private void createWindow() {
        System.out.println("MinimalWebGPUDemo: createWindow() called");
        log.info("Creating window");
        
        // Set up error callback
        System.out.println("MinimalWebGPUDemo: Setting up GLFW error callback");
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        System.out.println("MinimalWebGPUDemo: About to initialize GLFW");
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        
        // Configure GLFW for WebGPU - critical settings
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); // No OpenGL context
        
        // Create window
        window = glfwCreateWindow(width, height, "Minimal WebGPU Demo", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create window");
        }
        
        // Center window
        var vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vidMode != null) {
            glfwSetWindowPos(window,
                (vidMode.width() - width) / 2,
                (vidMode.height() - height) / 2
            );
        }
        
        glfwShowWindow(window);
        log.info("Window created successfully");
    }
    
    /**
     * Main render loop with proper state management
     */
    private void mainLoop() {
        System.out.println("MinimalWebGPUDemo: mainLoop() called, state=" + initState);
        log.info("Starting main loop with state: {}", initState);
        
        int loopCount = 0;
        while (!glfwWindowShouldClose(window) && initState != InitState.ERROR) {
            if (loopCount++ < 5 || loopCount % 100 == 0) {
                System.out.println("MinimalWebGPUDemo: Loop iteration " + loopCount + ", state=" + initState);
            }
            glfwPollEvents();
            
            // State machine following jWebGPU pattern
            switch (initState) {
                case WEBGPU_READY -> {
                    // Initialize WebGPU objects
                    initState = InitState.SURFACE_CREATING;
                    initializeWebGPUObjects();
                }
                case FULLY_INITIALIZED -> {
                    // Render frame
                    renderFrame();
                }
                default -> {
                    // Still initializing, wait
                    try {
                        Thread.sleep(10); // Small delay to prevent busy wait
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        log.info("Main loop ended with state: {}", initState);
    }
    
    /**
     * Initialize WebGPU objects - following simplified pattern
     */
    private void initializeWebGPUObjects() {
        try {
            log.info("Initializing WebGPU objects");
            
            // Step 1: Create instance
            instance = new Instance();
            log.info("WebGPU instance created");
            
            // Step 2: Create surface (simplified approach)
            surface = createSurfaceSimplified();
            log.info("Surface created");
            
            // Step 3: Request adapter (simplified)
            adapter = requestAdapterSimplified();
            log.info("Adapter obtained");
            
            initState = InitState.DEVICE_REQUESTING;
            
            // Step 4: Request device (simplified)
            device = requestDeviceSimplified();
            log.info("Device obtained");
            
            // Step 5: Get queue
            queue = device.getQueue();
            log.info("Queue obtained");
            
            // Step 6: Configure surface (simplified)
            configureSurfaceSimplified();
            log.info("Surface configured");
            
            initState = InitState.FULLY_INITIALIZED;
            log.info("WebGPU fully initialized");
            
        } catch (Exception e) {
            log.error("WebGPU object initialization failed", e);
            initState = InitState.ERROR;
        }
    }
    
    /**
     * Create surface using simplified platform detection
     */
    private Surface createSurfaceSimplified() {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            log.info("Creating surface for platform: {}", osName);
            
            if (osName.contains("mac")) {
                // macOS - use Metal
                return createMetalSurface();
            } else if (osName.contains("linux")) {
                // Linux - use X11 or Wayland
                return createLinuxSurface();
            } else if (osName.contains("win")) {
                // Windows - use Win32
                return createWindowsSurface();
            } else {
                throw new RuntimeException("Unsupported platform: " + osName);
            }
            
        } catch (Exception e) {
            log.error("Surface creation failed", e);
            throw new RuntimeException("Failed to create surface", e);
        }
    }
    
    /**
     * Create Metal surface for macOS - simplified approach
     */
    private Surface createMetalSurface() {
        log.info("Creating Metal surface for macOS");
        // Use existing platform manager but with error handling
        try {
            var surfaceManager = new com.hellblazer.luciferase.render.webgpu.platform.PlatformSurfaceManager();
            return surfaceManager.createSurface(instance, window);
        } catch (Exception e) {
            log.error("Metal surface creation failed", e);
            throw new RuntimeException("Failed to create Metal surface", e);
        }
    }
    
    /**
     * Create Linux surface - placeholder for future implementation
     */
    private Surface createLinuxSurface() {
        throw new RuntimeException("Linux surface creation not yet implemented");
    }
    
    /**
     * Create Windows surface - placeholder for future implementation
     */
    private Surface createWindowsSurface() {
        throw new RuntimeException("Windows surface creation not yet implemented");
    }
    
    /**
     * Request adapter using simplified approach
     */
    private Adapter requestAdapterSimplified() {
        try {
            log.info("Requesting adapter (simplified)");
            
            var options = new Instance.AdapterOptions()
                .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE)
                .withForceFallbackAdapter(false);
            
            // Try to get adapter - simplified error handling
            var future = instance.requestAdapter(options);
            var adapter = future.get(); // This may block but simplifies error handling
            
            if (adapter == null) {
                throw new RuntimeException("No suitable adapter found");
            }
            
            // Log adapter info if available
            try {
                var properties = adapter.getProperties();
                log.info("Got adapter: {}", properties.toString());
            } catch (Exception e) {
                log.info("Got adapter (properties not available)");
            }
            
            return adapter;
            
        } catch (Exception e) {
            log.error("Adapter request failed", e);
            throw new RuntimeException("Failed to request adapter", e);
        }
    }
    
    /**
     * Request device using simplified approach
     */
    private Device requestDeviceSimplified() {
        try {
            log.info("Requesting device (simplified)");
            
            var descriptor = new Adapter.DeviceDescriptor()
                .withLabel("Minimal Demo Device");
            
            var future = adapter.requestDevice(descriptor);
            var device = future.get(); // Simplified blocking approach
            
            if (device == null) {
                throw new RuntimeException("Failed to create device");
            }
            
            // Validate device handle
            var deviceHandle = device.getHandle();
            if (deviceHandle == null || deviceHandle.equals(java.lang.foreign.MemorySegment.NULL)) {
                throw new RuntimeException("Device created but handle is null");
            }
            
            log.info("Device created successfully with handle: 0x{}", 
                    Long.toHexString(deviceHandle.address()));
            return device;
            
        } catch (Exception e) {
            log.error("Device request failed", e);
            throw new RuntimeException("Failed to request device", e);
        }
    }
    
    /**
     * Configure surface using simplified approach (following jWebGPU pattern)
     */
    private void configureSurfaceSimplified() {
        try {
            log.info("Configuring surface (simplified)");
            
            // Get surface capabilities
            var capabilities = surface.getCapabilities(adapter);
            
            // Use compatible format for Apple M4 Max - avoid BGRA formats
            if (capabilities.formats.isEmpty()) {
                throw new RuntimeException("No surface formats available");
            }
            
            log.info("Available surface formats: {}", capabilities.formats);
            
            // Priority order for Apple M4 Max compatibility:
            // Use BGRA8Unorm (23) as the standard WebGPU surface format
            // This is the most compatible format for surfaces
            int format = -1;
            
            // Check if BGRA8Unorm (23) is available - it should be
            if (capabilities.formats.contains(23)) {
                format = 23; // BGRA8Unorm
                log.info("Using standard WebGPU surface format: BGRA8Unorm ({})", format);
            } else if (capabilities.formats.contains(24)) {
                format = 24; // BGRA8UnormSrgb
                log.info("Using sRGB surface format: BGRA8UnormSrgb ({})", format);
            } else if (capabilities.formats.contains(18)) {
                format = 18; // RGBA8Unorm
                log.info("Using RGBA8 surface format: RGBA8Unorm ({})", format);
            } else {
                // Use first available format
                format = capabilities.formats.get(0);
                log.warn("Using first available format: {} (may have compatibility issues)", format);
            }
            
            // Simple configuration - render attachment only
            var config = new Surface.Configuration.Builder()
                .withDevice(device)
                .withFormat(format)
                .withUsage(0x10) // TEXTURE_USAGE_RENDER_ATTACHMENT
                .withSize(width, height)
                .withPresentMode(2) // Fifo (2) - standard present mode
                .withAlphaMode(0)   // Opaque (0) - most compatible
                .build();
            
            surface.configure(config);
            log.info("Surface configured: {}x{}, format={}", width, height, format);
            
            // Test surface by getting a texture
            testSurface();
            
        } catch (Exception e) {
            log.error("Surface configuration failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to configure surface", e);
        }
    }
    
    /**
     * Test surface by trying to get a texture
     */
    private void testSurface() {
        try {
            log.info("Testing surface texture acquisition");
            var surfaceTexture = surface.getCurrentTexture();
            
            if (surfaceTexture == null || surfaceTexture.getTexture() == null) {
                log.warn("Surface texture test failed - texture is null");
            } else {
                log.info("Surface texture test passed");
                // Don't present here, just testing
            }
        } catch (Exception e) {
            log.warn("Surface texture test failed", e);
            // Non-fatal, continue
        }
    }
    
    /**
     * Render a frame - minimal implementation
     */
    private void renderFrame() {
        try {
            // Get current surface texture
            var surfaceTexture = surface.getCurrentTexture();
            if (surfaceTexture == null || surfaceTexture.getTexture() == null) {
                log.warn("No surface texture available for rendering");
                return;
            }
            
            // For now, just clear and present
            // TODO: Add actual rendering pipeline
            
            // Present the frame
            surface.present();
            
            // Process WebGPU events
            instance.processEvents();
            
        } catch (Exception e) {
            log.error("Rendering failed", e);
            // Don't set error state for render failures, continue trying
        }
    }
    
    /**
     * Clean up all resources
     */
    private void cleanup() {
        log.info("Cleaning up resources");
        
        // Clean up in reverse order of creation
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
                log.warn("Error cleaning up device", e);
            }
        }
        
        if (surface != null) {
            try {
                surface.close();
            } catch (Exception e) {
                log.warn("Error cleaning up surface", e);
            }
        }
        
        if (instance != null) {
            try {
                instance.close();
            } catch (Exception e) {
                log.warn("Error cleaning up instance", e);
            }
        }
        
        // Clean up GLFW
        if (window != 0) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
        
        var callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
        
        log.info("Cleanup completed");
    }
}