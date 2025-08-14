package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.render.webgpu.platform.PlatformSurfaceManager;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
    
    /**
     * Initialization state following jWebGPU pattern
     */
    public enum InitState {
        NOT_STARTED,    // Initial state
        STARTING,       // Window created, WebGPU init starting
        ADAPTER_READY,  // Adapter obtained
        DEVICE_READY,   // Device created
        READY,          // Fully initialized and ready for rendering
        FAILED          // Initialization failed
    }
    
    /**
     * Callback interface for async initialization
     */
    @FunctionalInterface
    public interface InitCallback {
        void onStateChange(InitState state, String message, Throwable error);
    }
    
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
    
    // State management following jWebGPU pattern  
    private volatile InitState initState = InitState.NOT_STARTED;
    private boolean windowCreated = false;
    private InitCallback initCallback;
    
    public WebGPUContext(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
        this.surfaceManager = new PlatformSurfaceManager();
    }
    
    /**
     * Initialize the complete WebGPU context including window creation.
     * Legacy synchronous method - use initializeAsync for better control.
     */
    public void initialize() {
        initializeAsync((state, message, error) -> {
            if (state == InitState.FAILED && error != null) {
                throw new RuntimeException("WebGPU initialization failed: " + message, error);
            }
        });
        
        // Wait for completion (blocking)
        while (initState != InitState.READY && initState != InitState.FAILED) {
            try {
                Thread.sleep(10); // Small delay to prevent busy waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Initialization interrupted", e);
            }
        }
        
        if (initState == InitState.FAILED) {
            throw new RuntimeException("WebGPU initialization failed");
        }
    }
    
    /**
     * Initialize asynchronously with proper state management following jWebGPU pattern.
     * This is the preferred initialization method.
     */
    public void initializeAsync(InitCallback callback) {
        if (initState != InitState.NOT_STARTED) {
            log.warn("WebGPU context initialization already started or complete: {}", initState);
            if (callback != null) {
                callback.onStateChange(initState, "Already initialized", null);
            }
            return;
        }
        
        this.initCallback = callback;
        log.info("Starting async WebGPU context initialization");
        
        setState(InitState.STARTING, "Starting initialization");
        
        try {
            // Validate platform requirements
            surfaceManager.validatePlatformRequirements();
            
            // Create window first
            createWindow();
            
            // Start async WebGPU initialization
            initializeWebGPUAsync();
            
        } catch (Exception e) {
            setState(InitState.FAILED, "Initialization startup failed", e);
        }
    }
    
    /**
     * Helper method to update state and notify callback
     */
    private void setState(InitState newState, String message) {
        setState(newState, message, null);
    }
    
    /**
     * Helper method to update state and notify callback
     */
    private void setState(InitState newState, String message, Throwable error) {
        initState = newState;
        log.info("WebGPU init state: {} - {}", newState, message);
        
        if (initCallback != null) {
            try {
                initCallback.onStateChange(newState, message, error);
            } catch (Exception e) {
                log.error("Error in init callback", e);
            }
        }
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
     * Initialize WebGPU asynchronously following jWebGPU pattern.
     */
    private void initializeWebGPUAsync() {
        // Execute async to avoid blocking
        CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Create Instance
                createInstance();
                
                // Step 2: Create Surface (platform-specific)
                createSurface();
                
                // Step 3: Request Adapter (async with proper state management)
                requestAdapterAsync();
                
            } catch (Exception e) {
                setState(InitState.FAILED, "WebGPU initialization failed: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Legacy synchronous WebGPU init - kept for compatibility
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
    
    /**
     * Request adapter asynchronously with proper state management
     */
    private void requestAdapterAsync() {
        log.info("Requesting WebGPU adapter asynchronously");
        
        Instance.AdapterOptions options = new Instance.AdapterOptions()
            .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE)
            .withForceFallbackAdapter(false);
        
        // Use async pattern instead of .get() blocking call
        instance.requestAdapter(options)
            .thenAccept(requestedAdapter -> {
                if (requestedAdapter == null) {
                    setState(InitState.FAILED, "No suitable GPU adapter found");
                    return;
                }
                
                this.adapter = requestedAdapter;
                
                // Log adapter info
                try {
                    Adapter.AdapterProperties info = adapter.getProperties();
                    log.info("Got adapter: {}", info.toString());
                    setState(InitState.ADAPTER_READY, "Adapter ready: " + info.toString());
                    
                    // Continue with device request
                    requestDeviceAsync();
                } catch (Exception e) {
                    setState(InitState.FAILED, "Failed to get adapter properties", e);
                }
            })
            .exceptionally(throwable -> {
                setState(InitState.FAILED, "Failed to request adapter", throwable);
                return null;
            });
    }
    
    /**
     * Legacy synchronous adapter request - kept for compatibility
     */
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
            log.info("Got adapter: {}", info.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to request adapter", e);
        }
    }
    
    /**
     * Request device asynchronously with proper state management
     */
    private void requestDeviceAsync() {
        log.info("Requesting WebGPU device asynchronously");
        
        Adapter.DeviceDescriptor descriptor = new Adapter.DeviceDescriptor()
            .withLabel("Main Device");
        
        // Set limits if needed
        Adapter.DeviceLimits limits = new Adapter.DeviceLimits();
        descriptor.withRequiredLimits(limits);
        
        // Use async pattern instead of .get() blocking call
        adapter.requestDevice(descriptor)
            .thenAccept(requestedDevice -> {
                if (requestedDevice == null) {
                    setState(InitState.FAILED, "Failed to create GPU device");
                    return;
                }
                
                this.device = requestedDevice;
                
                try {
                    // Set up error handlers if available in wrapper
                    // TODO: Add device error callback when wrapper supports it
                    
                    setState(InitState.DEVICE_READY, "Device ready");
                    
                    // Get queue and continue initialization
                    this.queue = device.getQueue();
                    log.info("Got device queue");
                    
                    // Configure surface and complete initialization
                    configureSurfaceAsync();
                    
                } catch (Exception e) {
                    setState(InitState.FAILED, "Failed to configure device", e);
                }
            })
            .exceptionally(throwable -> {
                setState(InitState.FAILED, "Failed to request device", throwable);
                return null;
            });
    }
    
    /**
     * Legacy synchronous device request - kept for compatibility
     */
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
    
    /**
     * Configure surface asynchronously with simplified jWebGPU pattern
     */
    private void configureSurfaceAsync() {
        log.info("Configuring surface asynchronously");
        
        try {
            // Get surface capabilities
            Surface.Capabilities capabilities = surface.getCapabilities(adapter);
            log.info("Got surface capabilities: {} formats available", capabilities.formats.size());
            
            // SIMPLIFIED SURFACE FORMAT SELECTION - following jWebGPU pattern
            // Use first available format - this is more reliable than complex selection
            if (capabilities.formats.isEmpty()) {
                setState(InitState.FAILED, "No surface formats available");
                return;
            }
            
            surfaceFormat = capabilities.formats.get(0);
            log.info("Using first available surface format: {} (following jWebGPU reliable pattern)", surfaceFormat);
            
            // Simple, working configuration following jWebGPU pattern
            int presentMode = vsync ? 2 : 0; // FIFO : Immediate
            int alphaMode = 1; // Auto
            int usage = 0x10; // WGPUTextureUsage.RenderAttachment
            
            Surface.Configuration config = new Surface.Configuration.Builder()
                .withDevice(device)
                .withFormat(surfaceFormat)
                .withUsage(usage) 
                .withSize(width, height)
                .withPresentMode(presentMode)
                .withAlphaMode(alphaMode)
                .build();
            
            log.info("Configuring surface: format={}, size={}x{}", surfaceFormat, width, height);
            surface.configure(config);
            surfaceUsage = usage;
            
            // Process events and complete initialization
            instance.processEvents();
            
            setState(InitState.READY, "WebGPU initialization complete - ready for rendering");
            log.info("Surface configured successfully - WebGPU context is ready");
            
        } catch (Exception e) {
            setState(InitState.FAILED, "Surface configuration failed", e);
        }
    }
    
    /**
     * Legacy synchronous surface configuration - kept for compatibility
     */
    private void configureSurface() {
        log.info("Configuring surface");
        
        try {
            // Get surface capabilities - following jWebGPU pattern
            Surface.Capabilities capabilities = surface.getCapabilities(adapter);
            log.info("Got surface capabilities: {} formats, {} present modes, {} alpha modes", 
                capabilities.formats.size(), capabilities.presentModes.size(), capabilities.alphaModes.size());
            
            // Log all available options for debugging
            log.info("Available surface formats: {}", capabilities.formats);
            log.info("Available present modes: {}", capabilities.presentModes);
            log.info("Available alpha modes: {}", capabilities.alphaModes);
            log.info("Surface usages: 0x{}", Integer.toHexString(capabilities.usages));
            
            // Apple M4 Max adapter format compatibility: try formats in order of known compatibility
            if (!capabilities.formats.isEmpty()) {
                // Available: [23, 24, 34, 26] - try each until we find one that works
                // Priority order: avoid BGRA formats (23, 24), try others first
                Integer[] tryOrder = {26, 34, 18, 24, 23}; // Try 26 and 34 first, avoid problematic BGRA formats
                
                surfaceFormat = -1;
                for (int format : tryOrder) {
                    if (capabilities.formats.contains(format)) {
                        surfaceFormat = format;
                        log.info("Trying surface format: {} (avoiding BGRA8 variants for Apple M4 Max compatibility)", format);
                        break;
                    }
                }
                
                if (surfaceFormat == -1) {
                    // Fallback to first available if none of our preferred formats work
                    surfaceFormat = capabilities.formats.get(0);
                    log.warn("No preferred formats available, using first available: {} (may fail on Apple M4 Max)", surfaceFormat);
                }
            } else {
                surfaceFormat = 26; // Try format 26 as fallback instead of BGRA variants
                log.warn("No surface formats available, using format 26 fallback");
            }
            
            // Follow jWebGPU pattern: use FIFO for vsync, Immediate otherwise
            int presentMode = vsync ? 2 : 0; // FIFO : Immediate
            log.info("Using present mode: {} ({})", presentMode, vsync ? "FIFO" : "Immediate");
            
            // Follow jWebGPU pattern: use Auto alpha mode
            int alphaMode = 1; // Auto
            log.info("Using alpha mode: {} (Auto)", alphaMode);
            
            // Follow jWebGPU pattern: use RenderAttachment usage only
            int usage = 0x10; // WGPUTextureUsage.RenderAttachment
            log.info("Using texture usage: 0x{} (RenderAttachment)", Integer.toHexString(usage));
            
            // Configure surface with jWebGPU-style configuration
            Surface.Configuration config = new Surface.Configuration.Builder()
                .withDevice(device)
                .withFormat(surfaceFormat)
                .withUsage(usage) // Always use RenderAttachment like jWebGPU
                .withSize(width, height)
                .withPresentMode(presentMode)
                .withAlphaMode(alphaMode)
                .build();
            
            log.info("Configuring surface with: format={}, usage=0x{}, size={}x{}, presentMode={}, alphaMode={}",
                surfaceFormat, Integer.toHexString(usage), width, height, presentMode, alphaMode);
                
            surface.configure(config);
            surfaceUsage = usage;
            
            log.info("Surface configured successfully: {}x{}", width, height);
            
            // Process any pending events after configuration
            instance.processEvents();
            
            // Test surface by trying to get a texture
            var testTexture = surface.getCurrentTexture();
            if (testTexture == null || testTexture.getTexture() == null) {
                log.warn("Surface texture acquisition test failed - this may be normal on first try");
                log.warn("Surface will be tested again during actual rendering");
            } else {
                log.info("Surface configuration verified - texture acquisition works");
            }
            
        } catch (Exception e) {
            log.error("Failed to configure surface", e);
            throw new RuntimeException("Surface configuration failed", e);
        }
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
        
        if (initState == InitState.READY) {
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
        
        initState = InitState.NOT_STARTED;
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
    public boolean isInitialized() { return initState == InitState.READY; }
    public InitState getInitState() { return initState; }
    public boolean isReady() { return initState == InitState.READY; }
    public boolean isFailed() { return initState == InitState.FAILED; }
    public long getWindow() { return window; }
}