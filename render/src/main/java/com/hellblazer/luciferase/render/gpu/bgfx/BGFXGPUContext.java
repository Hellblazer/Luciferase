package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.*;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatformData;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BGFX implementation of IGPUContext for Metal backend on macOS.
 * Provides compute shader execution using BGFX Metal renderer.
 */
public class BGFXGPUContext implements IGPUContext {
    private static final Logger log = LoggerFactory.getLogger(BGFXGPUContext.class);
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private final Map<Integer, BGFXGPUBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<Integer, BGFXGPUShader> shaders = new ConcurrentHashMap<>();
    
    private GPUConfig config;
    private int nextBufferId = 1;
    private int nextShaderId = 1;
    private long initTime;
    private long glfwWindow = 0; // For macOS context requirements
    
    @Override
    public boolean initialize(GPUConfig config) {
        if (initialized.get()) {
            return true; // Already initialized
        }
        
        this.config = config;
        this.initTime = System.nanoTime();
        
        log.debug("Initializing BGFX GPU context with backend: {}, headless: {}", 
                 config.getBackend(), config.isHeadless());
        
        // Try Metal first, fallback to OpenGL if Metal fails
        GPUConfig.Backend[] backendOrder = {
            config.getBackend(),
            GPUConfig.Backend.BGFX_OPENGL,  // Fallback
            GPUConfig.Backend.AUTO           // Let BGFX choose
        };
        
        for (var backend : backendOrder) {
            log.debug("Attempting BGFX initialization with backend: {}", backend);
            if (initializeBGFX(backend)) {
                log.info("Successfully initialized BGFX with backend: {}", backend);
                initialized.set(true);
                return true;
            } else {
                log.debug("Failed to initialize BGFX with backend: {}", backend);
            }
        }
        
        log.error("Failed to initialize BGFX with any supported backend");
        return false;
    }
    
    private boolean initializeBGFX(GPUConfig.Backend backend) {
        // On macOS, BGFX requires a window context even for compute-only operations
        if (!initializeGLFWIfNeeded()) {
            log.debug("Failed to initialize GLFW context for backend: {}", backend);
            return false;
        }
        
        try (var stack = MemoryStack.stackPush()) {
            // Initialize BGFX
            var init = BGFXInit.malloc(stack);
            BGFX.bgfx_init_ctor(init);
            
            // Configure renderer type
            init.type(getRendererType(backend));
            
            // Configure resolution - use minimal for headless compute
            int width = config.isHeadless() ? 1 : config.getWidth();
            int height = config.isHeadless() ? 1 : config.getHeight();
            init.resolution().width(width);
            init.resolution().height(height);
            
            // For headless compute, we still need platform data on macOS
            if (config.isHeadless()) {
                init.resolution().reset(BGFX.BGFX_RESET_NONE);
            } else {
                init.resolution().reset(BGFX.BGFX_RESET_VSYNC);
            }
            
            // Set platform data if we have a window
            if (glfwWindow != 0) {
                var platformData = BGFXPlatformData.malloc(stack);
                // BGFX will extract the Metal layer from the GLFW window
                init.platformData(platformData);
            }
            
            // Debug and validation settings
            if (config.isDebugEnabled()) {
                init.debug(true);
            }
            
            // Limit memory usage for compute-only scenarios
            init.limits().transientVbSize(0);  // No vertex buffers needed
            init.limits().transientIbSize(0);  // No index buffers needed
            
            // Initialize BGFX
            log.debug("Calling bgfx_init with renderer type: {}", getRendererType(backend));
            boolean result = BGFX.bgfx_init(init);
            if (!result) {
                log.debug("bgfx_init returned false for backend: {}", backend);
                return false;
            }
            log.debug("bgfx_init succeeded for backend: {}", backend);
            
            // Verify compute shader support
            var caps = BGFX.bgfx_get_caps();
            if (caps == null) {
                BGFX.bgfx_shutdown();
                return false;
            }
            
            // Check compute shader support
            long supported = caps.supported();
            if ((supported & BGFX.BGFX_CAPS_COMPUTE) == 0) {
                BGFX.bgfx_shutdown();
                return false;
            }
            
            // Set which view to use for compute
            BGFX.bgfx_reset(width, height, BGFX.BGFX_RESET_NONE, init.resolution().format());
            
            return true;
            
        } catch (Exception e) {
            // Clean up on any exception
            try {
                BGFX.bgfx_shutdown();
            } catch (Exception ignored) {
            }
            return false;
        }
    }
    
    /**
     * Initialize GLFW context if needed for BGFX on macOS.
     * BGFX requires a window context even for compute-only operations.
     */
    private boolean initializeGLFWIfNeeded() {
        if (glfwWindow != 0) {
            return true; // Already initialized
        }
        
        // Check if we're on macOS and need GLFW context
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("mac")) {
            return true; // Not needed on other platforms
        }
        
        try {
            // Initialize GLFW
            if (!GLFW.glfwInit()) {
                log.debug("Failed to initialize GLFW");
                return false;
            }
            
            // Create minimal hidden window for context
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API); // For Metal/Vulkan
            
            glfwWindow = GLFW.glfwCreateWindow(1, 1, "BGFX Context", MemoryUtil.NULL, MemoryUtil.NULL);
            if (glfwWindow == MemoryUtil.NULL) {
                log.debug("Failed to create GLFW window");
                GLFW.glfwTerminate();
                return false;
            }
            
            log.debug("Created GLFW window for BGFX context");
            return true;
            
        } catch (Exception e) {
            log.debug("Exception during GLFW initialization: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public IGPUBuffer createBuffer(BufferType type, int size, BufferUsage usage) {
        if (!initialized.get()) {
            throw new IllegalStateException("GPU context not initialized");
        }
        
        var buffer = new BGFXGPUBuffer(nextBufferId++, type, size, usage);
        if (buffer.initialize()) {
            buffers.put(buffer.getId(), buffer);
            return buffer;
        }
        return null;
    }
    
    @Override
    public IGPUShader createComputeShader(String shaderSource, Map<String, String> defines) {
        if (!initialized.get()) {
            throw new IllegalStateException("GPU context not initialized");
        }
        
        var shader = new BGFXGPUShader(nextShaderId++);
        if (shader.compile(shaderSource, defines)) {
            shaders.put(shader.getId(), shader);
            return shader;
        }
        return null;
    }
    
    @Override
    public IGPUBuffer createBuffer(int size, BufferUsage usage, AccessType accessType) {
        // Default to STORAGE buffer type for the overloaded method
        return createBuffer(BufferType.STORAGE, size, usage);
    }
    
    @Override
    public IGPUShader createShader(String shaderSource, Map<String, String> defines) {
        return createComputeShader(shaderSource, defines);
    }
    
    @Override
    public IShaderFactory getShaderFactory() {
        // For Phase 1.5, return a simple factory - will be enhanced in later phases
        return new BGFXShaderFactory();
    }
    
    @Override
    public void dispatch(IGPUShader shader, int groupsX, int groupsY, int groupsZ) {
        dispatchCompute(shader, groupsX, groupsY, groupsZ);
    }
    
    @Override
    public boolean dispatchCompute(IGPUShader shader, int groupsX, int groupsY, int groupsZ) {
        if (!initialized.get()) {
            log.error("GPU context not initialized");
            return false;
        }
        
        if (!(shader instanceof BGFXGPUShader bgfxShader)) {
            log.error("Shader must be BGFXGPUShader instance");
            return false;
        }
        
        if (!bgfxShader.isValid()) {
            log.error("Shader is not valid");
            return false;
        }
        
        try {
            // Dispatch compute shader
            BGFX.bgfx_dispatch(0, bgfxShader.getHandle(), groupsX, groupsY, groupsZ, BGFX.BGFX_DISCARD_ALL);
            frameCounter.incrementAndGet();
            return true;
        } catch (Exception e) {
            log.error("Failed to dispatch compute shader: {}", e.getMessage());
            return false;
        }
    }
    
    @Override
    public void waitForCompletion() {
        if (!initialized.get()) {
            return;
        }
        
        try {
            // BGFX frame call waits for GPU completion
            BGFX.bgfx_frame(false);
        } catch (Exception e) {
            log.error("Failed to wait for GPU completion: {}", e.getMessage());
        }
    }
    
    @Override
    public void memoryBarrier(BarrierType type) {
        if (!initialized.get()) {
            return;
        }
        
        // BGFX handles memory barriers automatically in most cases
        // For explicit control, we would use bgfx_set_compute_buffer
        // This is a placeholder for future implementation
    }
    
    @Override
    public void cleanup() {
        if (!initialized.compareAndSet(true, false)) {
            return; // Already cleaned up or never initialized
        }
        
        // Clean up all buffers
        buffers.values().forEach(BGFXGPUBuffer::destroy);
        buffers.clear();
        
        // Clean up all shaders
        shaders.values().forEach(BGFXGPUShader::destroy);
        shaders.clear();
        
        // Shutdown BGFX
        BGFX.bgfx_shutdown();
        
        // Clean up GLFW window if we created one
        if (glfwWindow != 0) {
            try {
                GLFW.glfwDestroyWindow(glfwWindow);
                GLFW.glfwTerminate();
                log.debug("Cleaned up GLFW window and context");
            } catch (Exception e) {
                log.debug("Exception during GLFW cleanup: {}", e.getMessage());
            } finally {
                glfwWindow = 0;
            }
        }
    }
    
    @Override
    public boolean isValid() {
        return initialized.get();
    }
    
    /**
     * Get BGFX renderer type from GPU backend enum.
     */
    private int getRendererType(GPUConfig.Backend backend) {
        return switch (backend) {
            case BGFX_METAL -> BGFX.BGFX_RENDERER_TYPE_METAL;
            case BGFX_VULKAN -> BGFX.BGFX_RENDERER_TYPE_VULKAN;
            case BGFX_OPENGL -> BGFX.BGFX_RENDERER_TYPE_OPENGL;
            case OPENGL -> BGFX.BGFX_RENDERER_TYPE_OPENGL;
            case AUTO -> BGFX.BGFX_RENDERER_TYPE_COUNT; // Let BGFX choose
        };
    }
    
    /**
     * Get current GPU statistics.
     */
    public GPUStats getStats() {
        var frameTime = System.nanoTime() - initTime;
        return GPUStats.builder()
                .withFrameTime(frameTime)
                .withDispatchCount(frameCounter.get())
                .withBufferCount(buffers.size())
                .withShaderCount(shaders.size())
                .build();
    }
    
    /**
     * Get the current configuration.
     */
    public GPUConfig getConfig() {
        return config;
    }
    
    /**
     * Check if compute shaders are supported.
     */
    public boolean isComputeSupported() {
        if (!initialized.get()) {
            return false;
        }
        
        var caps = BGFX.bgfx_get_caps();
        if (caps == null) {
            return false;
        }
        
        return (caps.supported() & BGFX.BGFX_CAPS_COMPUTE) != 0;
    }
}