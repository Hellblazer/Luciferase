package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Texture.
 * Represents a texture resource on the GPU.
 */
public class Texture implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Texture.class);
    
    private final MemorySegment handle;
    private final Device device;
    private final TextureDescriptor descriptor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a texture wrapper.
     * 
     * @param handle the native texture handle
     * @param device the device that created this texture
     * @param descriptor the texture descriptor
     */
    Texture(MemorySegment handle, Device device, TextureDescriptor descriptor) {
        this.handle = handle;
        this.device = device;
        this.descriptor = descriptor;
        log.debug("Created texture: {}x{}x{} format={}", 
            descriptor.width, descriptor.height, descriptor.depth, descriptor.format);
    }
    
    /**
     * Get the native handle.
     * 
     * @return the native handle
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    /**
     * Get the texture width.
     * 
     * @return the width in pixels
     */
    public int getWidth() {
        return descriptor.width;
    }
    
    /**
     * Get the texture height.
     * 
     * @return the height in pixels
     */
    public int getHeight() {
        return descriptor.height;
    }
    
    /**
     * Get the texture depth.
     * 
     * @return the depth in pixels
     */
    public int getDepth() {
        return descriptor.depth;
    }
    
    /**
     * Get the texture format.
     * 
     * @return the texture format
     */
    public TextureFormat getFormat() {
        return descriptor.format;
    }
    
    /**
     * Create a view of this texture.
     * 
     * @param descriptor the view descriptor (optional)
     * @return the texture view
     */
    public TextureView createView(TextureViewDescriptor descriptor) {
        if (closed.get()) {
            throw new IllegalStateException("Texture is closed");
        }
        
        // TODO: Implement native texture view creation
        log.debug("Creating texture view");
        return new TextureView(MemorySegment.NULL, this);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // TODO: Release native texture
            log.debug("Released texture");
        }
    }
    
    /**
     * Texture descriptor for creating textures.
     */
    public static class TextureDescriptor {
        private String label;
        private TextureUsage usage = TextureUsage.TEXTURE_BINDING;
        private TextureDimension dimension = TextureDimension.D2;
        public int width = 1;
        public int height = 1;
        public int depth = 1;
        private TextureFormat format = TextureFormat.RGBA8_UNORM;
        private int mipLevelCount = 1;
        private int sampleCount = 1;
        
        public TextureDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public TextureDescriptor withSize(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }
        
        public TextureDescriptor withSize(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            return this;
        }
        
        public TextureDescriptor withFormat(TextureFormat format) {
            this.format = format;
            return this;
        }
        
        public TextureDescriptor withUsage(TextureUsage usage) {
            this.usage = usage;
            return this;
        }
        
        public TextureDescriptor withDimension(TextureDimension dimension) {
            this.dimension = dimension;
            return this;
        }
        
        public TextureDescriptor withMipLevelCount(int count) {
            this.mipLevelCount = count;
            return this;
        }
        
        public TextureDescriptor withSampleCount(int count) {
            this.sampleCount = count;
            return this;
        }
    }
    
    /**
     * Texture view descriptor.
     */
    public static class TextureViewDescriptor {
        private String label;
        private TextureFormat format;
        private TextureViewDimension dimension;
        private int baseMipLevel = 0;
        private int mipLevelCount = 1;
        private int baseArrayLayer = 0;
        private int arrayLayerCount = 1;
        
        public TextureViewDescriptor withLabel(String label) {
            this.label = label;
            return this;
        }
        
        public TextureViewDescriptor withFormat(TextureFormat format) {
            this.format = format;
            return this;
        }
        
        public TextureViewDescriptor withDimension(TextureViewDimension dimension) {
            this.dimension = dimension;
            return this;
        }
        
        public TextureViewDescriptor withMipLevels(int base, int count) {
            this.baseMipLevel = base;
            this.mipLevelCount = count;
            return this;
        }
        
        public TextureViewDescriptor withArrayLayers(int base, int count) {
            this.baseArrayLayer = base;
            this.arrayLayerCount = count;
            return this;
        }
    }
    
    /**
     * Texture view wrapper.
     */
    public static class TextureView implements AutoCloseable {
        private final MemorySegment handle;
        private final Texture texture;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        
        TextureView(MemorySegment handle, Texture texture) {
            this.handle = handle;
            this.texture = texture;
        }
        
        public MemorySegment getHandle() {
            return handle;
        }
        
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // TODO: Release native texture view
                log.debug("Released texture view");
            }
        }
    }
    
    /**
     * Texture formats.
     */
    public enum TextureFormat {
        // 8-bit formats
        R8_UNORM(1),
        R8_SNORM(2),
        R8_UINT(3),
        R8_SINT(4),
        
        // 16-bit formats
        R16_UINT(5),
        R16_SINT(6),
        R16_FLOAT(7),
        RG8_UNORM(8),
        RG8_SNORM(9),
        RG8_UINT(10),
        RG8_SINT(11),
        
        // 32-bit formats
        R32_UINT(12),
        R32_SINT(13),
        R32_FLOAT(14),
        RG16_UINT(15),
        RG16_SINT(16),
        RG16_FLOAT(17),
        RGBA8_UNORM(18),
        RGBA8_UNORM_SRGB(19),
        RGBA8_SNORM(20),
        RGBA8_UINT(21),
        RGBA8_SINT(22),
        BGRA8_UNORM(23),
        BGRA8_UNORM_SRGB(24),
        
        // 64-bit formats
        RG32_UINT(25),
        RG32_SINT(26),
        RG32_FLOAT(27),
        RGBA16_UINT(28),
        RGBA16_SINT(29),
        RGBA16_FLOAT(30),
        
        // 128-bit formats
        RGBA32_UINT(31),
        RGBA32_SINT(32),
        RGBA32_FLOAT(33),
        
        // Depth/stencil formats
        DEPTH32_FLOAT(34),
        DEPTH24_PLUS(35),
        DEPTH24_PLUS_STENCIL8(36);
        
        private final int value;
        
        TextureFormat(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    /**
     * Texture usage flags.
     */
    public enum TextureUsage {
        COPY_SRC(0x01),
        COPY_DST(0x02),
        TEXTURE_BINDING(0x04),
        STORAGE_BINDING(0x08),
        RENDER_ATTACHMENT(0x10);
        
        private final int value;
        
        TextureUsage(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static int combine(TextureUsage... usages) {
            int result = 0;
            for (var usage : usages) {
                result |= usage.value;
            }
            return result;
        }
    }
    
    /**
     * Texture dimensions.
     */
    public enum TextureDimension {
        D1(0),
        D2(1),
        D3(2);
        
        private final int value;
        
        TextureDimension(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    /**
     * Texture view dimensions.
     */
    public enum TextureViewDimension {
        D1(0),
        D2(1),
        D2_ARRAY(2),
        CUBE(3),
        CUBE_ARRAY(4),
        D3(5);
        
        private final int value;
        
        TextureViewDimension(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
}