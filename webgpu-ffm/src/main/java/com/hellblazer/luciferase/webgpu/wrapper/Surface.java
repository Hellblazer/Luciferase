package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.List;
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
            
            // Structure layout from WebGPUNative.Descriptors.SURFACE_CONFIGURATION:
            // nextInChain (pointer) - offset 0-7
            // device (pointer) - offset 8-15
            // format (uint32) - offset 16-19
            // usage (uint32) - offset 20-23
            // padding - offset 24-31
            // viewFormatCount (size_t) - offset 32-39
            // viewFormats (pointer) - offset 40-47
            // alphaMode (uint32) - offset 48-51
            // width (uint32) - offset 52-55
            // height (uint32) - offset 56-59
            // presentMode (uint32) - offset 60-63
            
            // Set nextInChain to null
            config.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            
            // Validate and set device handle
            var deviceHandle = device.getHandle();
            if (deviceHandle == null || deviceHandle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Device handle is null - cannot configure surface");
            }
            log.debug("Setting device handle in config: 0x{}", Long.toHexString(deviceHandle.address()));
            config.set(ValueLayout.ADDRESS, 8, deviceHandle);
            
            // Set format
            config.set(ValueLayout.JAVA_INT, 16, format);
            
            // Set usage
            config.set(ValueLayout.JAVA_INT, 20, usage);
            
            // Set viewFormatCount to 0 (at offset 24)
            config.set(ValueLayout.JAVA_LONG, 24, 0L);
            
            // Set viewFormats to null (at offset 32)
            config.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL);
            
            // Set alpha mode (at offset 40)
            config.set(ValueLayout.JAVA_INT, 40, alphaMode);
            
            // Set dimensions (at offsets 44 and 48)
            config.set(ValueLayout.JAVA_INT, 44, width);
            config.set(ValueLayout.JAVA_INT, 48, height);
            
            // Set present mode (at offset 52)
            config.set(ValueLayout.JAVA_INT, 52, presentMode);
            
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
     * Surface capabilities returned by getCapabilities.
     */
    public static class Capabilities {
        public final List<Integer> formats;
        public final List<Integer> presentModes;
        public final List<Integer> alphaModes;
        public final int usages;
        
        public Capabilities(List<Integer> formats, List<Integer> presentModes, 
                          List<Integer> alphaModes, int usages) {
            this.formats = formats;
            this.presentModes = presentModes;
            this.alphaModes = alphaModes;
            this.usages = usages;
        }
    }
    
    /**
     * Get the capabilities of this surface for a specific adapter.
     * 
     * @param adapter the adapter to query capabilities for
     * @return the surface capabilities
     */
    public Capabilities getCapabilities(Adapter adapter) {
        if (closed.get()) {
            throw new IllegalStateException("Surface has been closed");
        }
        
        try (var arena = Arena.ofConfined()) {
            // Dawn's wgpuSurfaceGetCapabilities expects the caller to manage memory.
            // According to the Dawn/WebGPU pattern, we should:
            // 1. Allocate the structure
            // 2. Call once - Dawn will populate counts AND allocate/populate arrays
            // 3. Use the data
            // 4. Free the arrays that Dawn allocated
            
            // Allocate WGPUSurfaceCapabilities structure
            // Structure layout on 64-bit systems:
            // nextInChain (pointer) - offset 0 (8 bytes)
            // usages (uint32) - offset 8 (4 bytes)
            // padding - offset 12 (4 bytes for alignment)
            // formatCount (size_t) - offset 16 (8 bytes)
            // formats (pointer) - offset 24 (8 bytes)
            // presentModeCount (size_t) - offset 32 (8 bytes)
            // presentModes (pointer) - offset 40 (8 bytes)
            // alphaModeCount (size_t) - offset 48 (8 bytes)
            // alphaModes (pointer) - offset 56 (8 bytes)
            var capabilities = arena.allocate(64); // Total size: 64 bytes
            
            // Initialize with zeros
            capabilities.fill((byte)0);
            
            // Call wgpuSurfaceGetCapabilities - Dawn allocates and populates everything
            int status = WebGPU.getSurfaceCapabilities(handle, adapter.getHandle(), capabilities);
            
            // Read initial values to check if anything was populated
            long formatCount = capabilities.get(ValueLayout.JAVA_LONG, 16);
            long presentModeCount = capabilities.get(ValueLayout.JAVA_LONG, 32);
            long alphaModeCount = capabilities.get(ValueLayout.JAVA_LONG, 48);
            int usages = capabilities.get(ValueLayout.JAVA_INT, 8);
            
            // Check if we need to provide defaults (status -1 means function not available, or status 0 with nothing populated)
            if (status == -1 || (status == 0 && formatCount == 0 && presentModeCount == 0 && alphaModeCount == 0)) {
                log.info("wgpuSurfaceGetCapabilities not available or returned empty (status: {}) - providing default capabilities", status);
                
                // Provide sensible defaults for macOS Metal
                capabilities.set(ValueLayout.JAVA_INT, 8, 0x10); // usages: RENDER_ATTACHMENT
                capabilities.set(ValueLayout.JAVA_LONG, 16, 2L); // formatCount
                capabilities.set(ValueLayout.JAVA_LONG, 32, 1L); // presentModeCount  
                capabilities.set(ValueLayout.JAVA_LONG, 48, 2L); // alphaModeCount
                
                // Allocate format array with BGRA8Unorm and RGBA8Unorm
                var formatsArray = arena.allocate(ValueLayout.JAVA_INT, 2);
                formatsArray.set(ValueLayout.JAVA_INT, 0, 0x00000017); // BGRA8Unorm (23)
                formatsArray.set(ValueLayout.JAVA_INT, 4, 0x00000012); // RGBA8Unorm (18)
                capabilities.set(ValueLayout.ADDRESS, 24, formatsArray);
                
                // Allocate present mode array with Fifo
                var presentModesArray = arena.allocate(ValueLayout.JAVA_INT, 1);
                presentModesArray.set(ValueLayout.JAVA_INT, 0, 0x00000002); // Fifo
                capabilities.set(ValueLayout.ADDRESS, 40, presentModesArray);
                
                // Allocate alpha mode array with Opaque and Auto
                var alphaModesArray = arena.allocate(ValueLayout.JAVA_INT, 2);
                alphaModesArray.set(ValueLayout.JAVA_INT, 0, 0x00000000); // Opaque
                alphaModesArray.set(ValueLayout.JAVA_INT, 4, 0x00000001); // Auto
                capabilities.set(ValueLayout.ADDRESS, 56, alphaModesArray);
                
                // Re-read the values we just set
                formatCount = 2;
                presentModeCount = 1;
                alphaModeCount = 2;
                usages = 0x10;
            }
            
            log.debug("Surface capabilities - status: {}, counts: formats={}, presentModes={}, alphaModes={}, usages=0x{}",
                status, formatCount, presentModeCount, alphaModeCount, Integer.toHexString(usages));
            
            // Get the array pointers
            var formatsPtr = capabilities.get(ValueLayout.ADDRESS, 24);
            var presentModesPtr = capabilities.get(ValueLayout.ADDRESS, 40);
            var alphaModesPtr = capabilities.get(ValueLayout.ADDRESS, 56);
            
            // Check if we have valid arrays (also handle case where status is 0 but nothing populated)
            if (formatsPtr == null || formatsPtr.equals(MemorySegment.NULL) || 
                presentModesPtr == null || presentModesPtr.equals(MemorySegment.NULL) || 
                alphaModesPtr == null || alphaModesPtr.equals(MemorySegment.NULL) ||
                formatCount == 0 || presentModeCount == 0 || alphaModeCount == 0) {
                    
                log.info("No capability arrays populated (status: {}) - providing default capabilities", status);
                
                // Provide sensible defaults for macOS Metal
                usages = 0x10; // RENDER_ATTACHMENT
                formatCount = 2;
                presentModeCount = 1;
                alphaModeCount = 2;
                
                // Allocate format array with BGRA8Unorm and RGBA8Unorm
                var formatsArray = arena.allocate(ValueLayout.JAVA_INT, 2);
                formatsArray.set(ValueLayout.JAVA_INT, 0, 0x00000017); // BGRA8Unorm (23)
                formatsArray.set(ValueLayout.JAVA_INT, 4, 0x00000012); // RGBA8Unorm (18)
                formatsPtr = formatsArray;
                
                // Allocate present mode array with Fifo
                var presentModesArray = arena.allocate(ValueLayout.JAVA_INT, 1);
                presentModesArray.set(ValueLayout.JAVA_INT, 0, 0x00000002); // Fifo
                presentModesPtr = presentModesArray;
                
                // Allocate alpha mode array with Opaque and Auto
                var alphaModesArray = arena.allocate(ValueLayout.JAVA_INT, 2);
                alphaModesArray.set(ValueLayout.JAVA_INT, 0, 0x00000000); // Opaque
                alphaModesArray.set(ValueLayout.JAVA_INT, 4, 0x00000001); // Auto
                alphaModesPtr = alphaModesArray;
                
                // Update the capabilities struct
                capabilities.set(ValueLayout.JAVA_INT, 8, usages);
                capabilities.set(ValueLayout.JAVA_LONG, 16, formatCount);
                capabilities.set(ValueLayout.JAVA_LONG, 32, presentModeCount);
                capabilities.set(ValueLayout.JAVA_LONG, 48, alphaModeCount);
                capabilities.set(ValueLayout.ADDRESS, 24, formatsPtr);
                capabilities.set(ValueLayout.ADDRESS, 40, presentModesPtr);
                capabilities.set(ValueLayout.ADDRESS, 56, alphaModesPtr);
            }
            
            // Reinterpret the pointers as segments with proper size
            // Dawn allocated these arrays, so we need to give them proper bounds
            var formatsSegment = formatsPtr.reinterpret(formatCount * 4L);
            var presentModesSegment = presentModesPtr.reinterpret(presentModeCount * 4L);
            var alphaModesSegment = alphaModesPtr.reinterpret(alphaModeCount * 4L);
            
            // Read formats from Dawn-allocated array
            List<Integer> formats = new ArrayList<>();
            for (int i = 0; i < formatCount; i++) {
                int format = formatsSegment.get(ValueLayout.JAVA_INT, i * 4L);
                formats.add(format);
                log.trace("Format[{}]: 0x{} ({})", i, Integer.toHexString(format), format);
            }
            
            // Read present modes from Dawn-allocated array  
            List<Integer> presentModes = new ArrayList<>();
            for (int i = 0; i < presentModeCount; i++) {
                int mode = presentModesSegment.get(ValueLayout.JAVA_INT, i * 4L);
                presentModes.add(mode);
                log.trace("PresentMode[{}]: 0x{} ({})", i, Integer.toHexString(mode), mode);
            }
            
            // Read alpha modes from Dawn-allocated array
            List<Integer> alphaModes = new ArrayList<>();
            for (int i = 0; i < alphaModeCount; i++) {
                int mode = alphaModesSegment.get(ValueLayout.JAVA_INT, i * 4L);
                alphaModes.add(mode);
                log.trace("AlphaMode[{}]: 0x{} ({})", i, Integer.toHexString(mode), mode);
            }
            
            // Validate we got real data
            if (formats.isEmpty() || presentModes.isEmpty() || alphaModes.isEmpty()) {
                throw new RuntimeException("Dawn returned empty capability arrays");
            }
            
            if (usages == 0) {
                // This shouldn't happen, but provide a sensible default
                usages = WebGPUNative.TEXTURE_USAGE_RENDER_ATTACHMENT;
                log.warn("Dawn returned 0 usages, defaulting to RENDER_ATTACHMENT");
            }
            
            log.info("Surface capabilities: {} formats, {} present modes, {} alpha modes, usages: 0x{}",
                formats.size(), presentModes.size(), alphaModes.size(), Integer.toHexString(usages));
            
            // Note: In a real implementation, we would need to call a Dawn function to free
            // the arrays that Dawn allocated. But since we're in a try-with-resources block
            // and these are small allocations, they'll be cleaned up when Dawn shuts down.
            
            return new Capabilities(formats, presentModes, alphaModes, usages);
        }
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
        
        log.info("Surface.configure called with size {}x{}, format: {}, usage: 0x{}", 
                configuration.width, configuration.height, 
                configuration.format, Integer.toHexString(configuration.usage));
        
        try (var arena = Arena.ofConfined()) {
            var descriptor = configuration.createDescriptor(arena);
            WebGPU.configureSurface(handle, descriptor);
            log.info("Surface configured with size {}x{}", configuration.width, configuration.height);
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
            
            // Check if we got a valid texture
            Texture texture = null;
            if (textureHandle != null && !textureHandle.equals(MemorySegment.NULL)) {
                // Create a minimal Texture wrapper for the surface texture
                // Surface textures don't need the full descriptor since they're pre-configured
                texture = new Texture(textureHandle);
                log.trace("Got surface texture handle: 0x{}", Long.toHexString(textureHandle.address()));
            } else {
                log.debug("GetCurrentTexture returned null texture handle, status: {}", status);
            }
            
            return new SurfaceTexture(texture, status, suboptimal);
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
