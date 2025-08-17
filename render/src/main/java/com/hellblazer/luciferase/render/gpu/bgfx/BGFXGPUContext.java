package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.*;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatformData;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BGFX implementation of IGPUContext for Metal backend on macOS.
 * Provides compute shader execution using BGFX Metal renderer.
 */
public class BGFXGPUContext implements IGPUContext {
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private final Map<Integer, BGFXGPUBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<Integer, BGFXGPUShader> shaders = new ConcurrentHashMap<>();
    
    private GPUConfig config;
    private int nextBufferId = 1;
    private int nextShaderId = 1;
    private long initTime;
    
    @Override
    public boolean initialize(GPUConfig config) {
        if (initialized.get()) {
            return true; // Already initialized
        }
        
        this.config = config;
        this.initTime = System.nanoTime();
        
        try (var stack = MemoryStack.stackPush()) {
            // Initialize BGFX
            var init = BGFXInit.malloc(stack);
            BGFX.bgfx_init_ctor(init);
            
            // Configure for Metal backend on macOS
            init.type(getRendererType(config.getBackend()));
            init.resolution().width(config.getWidth());
            init.resolution().height(config.getHeight());
            init.resolution().reset(BGFX.BGFX_RESET_NONE);
            
            // Platform data (can be null for headless)
            if (!config.isHeadless()) {
                var platformData = BGFXPlatformData.malloc(stack);
                // Would set window handle here for windowed mode
                init.platformData(platformData);
            }
            
            // Debug and validation settings
            if (config.isDebugEnabled()) {
                init.debug(true);
            }
            
            // Initialize BGFX
            boolean result = BGFX.bgfx_init(init);
            if (!result) {
                return false;
            }
            
            // Verify compute shader support
            var caps = BGFX.bgfx_get_caps();
            if (caps == null) {
                cleanup();
                return false;
            }
            
            // Check compute shader support
            long supported = caps.supported();
            if ((supported & BGFX.BGFX_CAPS_COMPUTE) == 0) {
                cleanup();
                return false;
            }
            
            initialized.set(true);
            return true;
            
        } catch (Exception e) {
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
        if (!initialized.get()) {
            throw new IllegalStateException("GPU context not initialized");
        }
        
        if (!(shader instanceof BGFXGPUShader bgfxShader)) {
            throw new IllegalArgumentException("Shader must be BGFXGPUShader instance");
        }
        
        if (!bgfxShader.isValid()) {
            throw new IllegalStateException("Shader is not valid");
        }
        
        // Dispatch compute shader
        BGFX.bgfx_dispatch(0, bgfxShader.getHandle(), groupsX, groupsY, groupsZ, BGFX.BGFX_DISCARD_ALL);
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