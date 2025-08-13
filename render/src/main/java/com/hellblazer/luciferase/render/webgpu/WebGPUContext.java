package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.render.webgpu.platform.PlatformSurfaceManager;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import java.util.concurrent.CompletableFuture;
import com.hellblazer.luciferase.webgpu.wrapper.Surface.SurfaceTexture;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main WebGPU context manager that handles initialization and lifecycle.
 * Follows the standard WebGPU initialization sequence:
 * Instance -> Surface -> Adapter -> Device -> Queue -> Surface Configuration
 */
public class WebGPUContext {
    private static final Logger log = LoggerFactory.getLogger(WebGPUContext.class);
    
    // Core WebGPU objects
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Queue queue;
    private Surface surface;
    
    // Window and platform management
    private long window;
    private PlatformSurfaceManager surfaceManager;
    
    // Configuration
    private int width;
    private int height;
    private String title;
    private boolean vsync = true;
    private boolean debug = true;
    
    // Surface configuration
    private int surfaceFormat;
    private int surfaceUsage;
    
    // State
    private boolean initialized = false;
    private boolean windowCreated = false;
    
    public WebGPUContext(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
        this.surfaceManager = new PlatformSurfaceManager();
    }
    
    /**
     * Initialize the complete WebGPU context including window creation.
     */
    public void initialize() {
        if (initialized) {
            log.warn("WebGPU context already initialized");
            return;
        }
        
        log.info("Initializing WebGPU context");
        
        // Validate platform requirements
        surfaceManager.validatePlatformRequirements();
        
        // Create window
        createWindow();
        
        // Initialize WebGPU
        initializeWebGPU();
        
        initialized = true;
        log.info("WebGPU context initialized successfully");
    }
    
    /**
     * Creates the GLFW window configured for WebGPU.
     */
    private void createWindow() {
        log.info("Creating GLFW window: {}x{} - {}", width, height, title);
        
        // Set up error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        
        // Configure GLFW for WebGPU (no OpenGL/Vulkan context)
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); // Critical for WebGPU
        
        // Create window
        window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Center window on screen
        var vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vidMode != null) {
            glfwSetWindowPos(window,
                (vidMode.width() - width) / 2,
                (vidMode.height() - height) / 2
            );
        }
        
        // Set up resize callback
        glfwSetFramebufferSizeCallback(window, this::onWindowResize);
        
        // Show window
        glfwShowWindow(window);
        
        windowCreated = true;
        log.info("GLFW window created successfully");
    }
    
    /**
     * Initialize WebGPU following the standard sequence.
     */
    private void initializeWebGPU() {
        log.info("Initializing WebGPU");
        
        try {
            // Step 1: Create Instance
            createInstance();
            
            // Step 2: Create Surface (platform-specific)
            createSurface();
            
            // Step 3: Request Adapter
            requestAdapter();
            
            // Step 4: Request Device
            requestDevice();
            
            // Step 5: Get Queue
            queue = device.getQueue();
            log.info("Got device queue");
            
            // Step 6: Configure Surface
            configureSurface();
            
            log.info("WebGPU initialization complete");
            
        } catch (Exception e) {
            log.error("WebGPU initialization failed", e);
            cleanup();
            throw new RuntimeException("Failed to initialize WebGPU", e);
        }
    }
    
    private void createInstance() {
        log.info("Creating WebGPU instance");
        
        instance = new Instance();
        
        if (debug) {
            // Debug logging callback not available in current wrapper
            log.info("Debug mode enabled for WebGPU");
        }
        
        log.info("WebGPU instance created");
    }
    
    private void createSurface() {
        log.info("Creating WebGPU surface");
        surface = surfaceManager.createSurface(instance, window);
        log.info("WebGPU surface created for platform: {}", surfaceManager.getPlatformName());
    }
    
    private void requestAdapter() {
        log.info("Requesting WebGPU adapter");
        
        Instance.AdapterOptions options = new Instance.AdapterOptions()
            .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE)
            .withForceFallbackAdapter(false);
        
        try {
            adapter = instance.requestAdapter(options).get();
            
            if (adapter == null) {
                throw new RuntimeException("No suitable GPU adapter found");
            }
            
            // Log adapter info
            Adapter.AdapterProperties info = adapter.getProperties();
            log.info("Got adapter: {}", info);
        } catch (Exception e) {
            throw new RuntimeException("Failed to request adapter", e);
        }
    }
    
    private void requestDevice() {
        log.info("Requesting WebGPU device");
        
        Adapter.DeviceDescriptor descriptor = new Adapter.DeviceDescriptor()
            .withLabel("Main Device");
        
        // Set limits if needed
        Adapter.DeviceLimits limits = new Adapter.DeviceLimits();
        descriptor.withRequiredLimits(limits);
        
        try {
            device = adapter.requestDevice(descriptor).get();
            
            if (device == null) {
                throw new RuntimeException("Failed to create GPU device");
            }
            
            // Error handlers not available in current wrapper
            
            log.info("WebGPU device created");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create device", e);
        }
    }
    
    private void configureSurface() {
        log.info("Configuring surface");
        
        // Get surface capabilities
        Surface.Capabilities capabilities = surface.getCapabilities(adapter);
        
        // Select format (usually BGRA8Unorm or RGBA8Unorm)
        if (!capabilities.formats.isEmpty()) {
            surfaceFormat = capabilities.formats.get(0);
        } else {
            surfaceFormat = 23; // Default to BGRA8_UNORM
        }
        log.info("Selected surface format: {}", surfaceFormat);
        
        // Configure surface
        Surface.Configuration config = new Surface.Configuration.Builder()
            .withDevice(device)
            .withFormat(surfaceFormat)
            .withUsage(0x10) // RENDER_ATTACHMENT
            .withSize(width, height)
            .withPresentMode(vsync ? 1 : 0) // 1=FIFO, 0=IMMEDIATE
            .withAlphaMode(0) // AUTO
            .build();
        
        surface.configure(config);
        surfaceUsage = 0x10; // RENDER_ATTACHMENT
        
        log.info("Surface configured: {}x{}", width, height);
    }
    
    /**
     * Handle window resize events.
     */
    private void onWindowResize(long window, int newWidth, int newHeight) {
        if (newWidth == 0 || newHeight == 0) {
            return; // Minimized
        }
        
        log.info("Window resized: {}x{} -> {}x{}", width, height, newWidth, newHeight);
        
        this.width = newWidth;
        this.height = newHeight;
        
        if (initialized) {
            // Reconfigure surface with new size
            configureSurface();
        }
    }
    
    /**
     * Get the next surface texture for rendering.
     */
    public SurfaceTexture getCurrentTexture() {
        return surface.getCurrentTexture();
    }
    
    /**
     * Present the rendered frame.
     */
    public void present() {
        surface.present();
    }
    
    /**
     * Poll window events.
     */
    public void pollEvents() {
        glfwPollEvents();
    }
    
    /**
     * Check if the window should close.
     */
    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }
    
    /**
     * Clean up all resources.
     */
    public void cleanup() {
        log.info("Cleaning up WebGPU context");
        
        if (device != null) {
            // Device cleanup handled by wrapper
            device = null;
        }
        
        if (adapter != null) {
            adapter = null;
        }
        
        if (surface != null) {
            surface = null;
        }
        
        if (instance != null) {
            instance = null;
        }
        
        if (windowCreated) {
            glfwDestroyWindow(window);
            glfwTerminate();
            glfwSetErrorCallback(null).free();
            windowCreated = false;
        }
        
        initialized = false;
        log.info("WebGPU context cleaned up");
    }
    
    // Getters
    public Instance getInstance() { return instance; }
    public Adapter getAdapter() { return adapter; }
    public Device getDevice() { return device; }
    public Queue getQueue() { return queue; }
    public Surface getSurface() { return surface; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getSurfaceFormat() { return surfaceFormat; }
    public boolean isInitialized() { return initialized; }
    public long getWindow() { return window; }
}