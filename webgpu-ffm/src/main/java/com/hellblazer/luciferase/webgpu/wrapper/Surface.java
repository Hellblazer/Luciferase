package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper for WebGPU Surface - represents a drawable surface for rendering.
 * 
 * A surface is required for presenting rendered images to the screen.
 * It connects WebGPU to the windowing system (e.g., JavaFX, AWT, GLFW).
 */
public class Surface implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Surface.class);
    
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Surface configuration for swap chain setup.
     */
    public static class Configuration {
        private final Device device;
        private final int format;
        private final int usage;
        private final int width;
        private final int height;
        private final int presentMode;
        private final int alphaMode;
        
        private Configuration(Device device, int format, int usage, 
                             int width, int height, int presentMode, int alphaMode) {
            this.device = device;
            this.format = format;
            this.usage = usage;
            this.width = width;
            this.height = height;
            this.presentMode = presentMode;
            this.alphaMode = alphaMode;
        }
        
        /**
         * Builder for surface configuration.
         */
        public static class Builder {
            private Device device;
            private int format = WebGPUNative.TEXTURE_FORMAT_BGRA8_UNORM;
            private int usage = WebGPUNative.TEXTURE_USAGE_RENDER_ATTACHMENT;
            private int width = 800;
            private int height = 600;
            private int presentMode = WebGPUNative.PRESENT_MODE_FIFO; // VSync
            private int alphaMode = WebGPUNative.COMPOSITE_ALPHA_MODE_OPAQUE;
            
            public Builder withDevice(Device device) {
                this.device = device;
                return this;
            }
            
            public Builder withFormat(int format) {
                this.format = format;
                return this;
            }
            
            public Builder withUsage(int usage) {
                this.usage = usage;
                return this;
            }
            
            public Builder withSize(int width, int height) {
                this.width = width;
                this.height = height;
                return this;
            }
            
            public Builder withPresentMode(int presentMode) {
                this.presentMode = presentMode;
                return this;
            }
            
            public Builder withAlphaMode(int alphaMode) {
                this.alphaMode = alphaMode;
                return this;
            }
            
            public Configuration build() {
                if (device == null) {
                    throw new IllegalStateException("Device is required for surface configuration");
                }
                return new Configuration(device, format, usage, width, height, presentMode, alphaMode);
            }
        }
        
        /**
         * Create a native configuration descriptor.
         */
        MemorySegment createDescriptor(Arena arena) {
            var config = arena.allocate(WebGPUNative.Descriptors.SURFACE_CONFIGURATION);
            
            // Set device
            config.set(ValueLayout.ADDRESS, 8, device.getHandle()); // device field offset
            
            // Set format
            config.set(ValueLayout.JAVA_INT, 16, format); // format field offset
            
            // Set usage
            config.set(ValueLayout.JAVA_INT, 20, usage); // usage field offset
            
            // Set dimensions
            config.set(ValueLayout.JAVA_INT, 40, width); // width field offset
            config.set(ValueLayout.JAVA_INT, 44, height); // height field offset
            
            // Set present mode
            config.set(ValueLayout.JAVA_INT, 48, presentMode); // presentMode field offset
            
            // Set alpha mode
            config.set(ValueLayout.JAVA_INT, 36, alphaMode); // alphaMode field offset
            
            return config;
        }
    }
    
    /**
     * Surface texture information returned when acquiring the next texture.
     */
    public static class SurfaceTexture {
        private final Texture texture;
        private final int status;
        private final boolean suboptimal;
        
        SurfaceTexture(Texture texture, int status, boolean suboptimal) {
            this.texture = texture;
            this.status = status;
            this.suboptimal = suboptimal;
        }
        
        public Texture getTexture() {
            return texture;
        }
        
        public int getStatus() {
            return status;
        }
        
        public boolean isSuboptimal() {
            return suboptimal;
        }
        
        public boolean isSuccess() {
            return status == WebGPUNative.SURFACE_GET_CURRENT_TEXTURE_STATUS_SUCCESS;
        }
    }
    
    /**
     * Create a surface from a native handle.
     */
    public Surface(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Surface handle cannot be null");
        }
        this.handle = handle;
        log.debug("Created Surface wrapper for handle: 0x{}", Long.toHexString(handle.address()));
    }
    
    /**
     * Configure the surface for presentation.
     * 
     * @param configuration the surface configuration
     */
    public void configure(Configuration configuration) {
        if (closed.get()) {
            throw new IllegalStateException("Surface has been closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            var descriptor = configuration.createDescriptor(arena);
            WebGPU.configureSurface(handle, descriptor);
            log.debug("Configured surface with size {}x{}", configuration.width, configuration.height);
        }
    }
    
    /**
     * Get the current texture for rendering.
     * 
     * @return the surface texture information
     */
    public SurfaceTexture getCurrentTexture() {
        if (closed.get()) {
            throw new IllegalStateException("Surface has been closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            // Allocate structure for surface texture
            var surfaceTexture = arena.allocate(WebGPUNative.Descriptors.SURFACE_TEXTURE);
            
            // Get current texture
            WebGPU.getCurrentTexture(handle, surfaceTexture);
            
            // Extract texture handle
            var textureHandle = surfaceTexture.get(ValueLayout.ADDRESS, 0);
            
            // Extract status
            var status = surfaceTexture.get(ValueLayout.JAVA_INT, 8);
            
            // Extract suboptimal flag
            var suboptimal = surfaceTexture.get(ValueLayout.JAVA_BOOLEAN, 12);
            
            // Note: We can't create a full Texture wrapper here because we don't have
            // the device and descriptor information. In a real implementation, we would
            // either need to store the device reference or create a simplified TextureView
            // wrapper for surface textures.
            // For now, we just return null for the texture
            
            return new SurfaceTexture(null, status, suboptimal);
        }
    }
    
    /**
     * Present the current frame.
     * Call this after rendering to display the frame.
     */
    public void present() {
        if (closed.get()) {
            throw new IllegalStateException("Surface has been closed");
        }
        
        WebGPU.presentSurface(handle);
        log.trace("Presented surface");
    }
    
    /**
     * Get the preferred texture format for this surface.
     * 
     * @param adapter the adapter to query
     * @return the texture format ID
     */
    public int getPreferredFormat(Adapter adapter) {
        if (closed.get()) {
            throw new IllegalStateException("Surface has been closed");
        }
        
        return WebGPU.getPreferredFormat(handle, adapter.getHandle());
    }
    
    /**
     * Get the native handle.
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    /**
     * Check if the surface is still valid.
     */
    public boolean isValid() {
        return !closed.get() && handle != null && !handle.equals(MemorySegment.NULL);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            WebGPU.releaseSurface(handle);
            log.debug("Released surface");
        }
    }
}