package com.hellblazer.luciferase.resource.opengl;

import com.hellblazer.luciferase.resource.GPUResource.ResourceStatistics;
import com.hellblazer.luciferase.resource.GPUResourceType;
import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL Texture Resource
 * Wraps OpenGL texture objects with automatic lifecycle management.
 */
public class TextureResource extends ResourceHandle<Integer> implements OpenGLResource {
    private static final Logger log = LoggerFactory.getLogger(TextureResource.class);
    
    private final int target;
    private final int internalFormat;
    private final int width;
    private final int height;
    private final int depth;
    private final String debugName;
    private volatile boolean isBound = false;
    
    /**
     * Create a texture resource
     * 
     * @param textureId OpenGL texture ID from glGenTextures()
     * @param target OpenGL texture target (GL_TEXTURE_2D, GL_TEXTURE_3D, etc.)
     * @param internalFormat OpenGL internal format (GL_RGBA8, GL_R32F, etc.)
     * @param width Texture width in pixels
     * @param height Texture height in pixels (1 for 1D textures)
     * @param depth Texture depth in pixels (1 for 2D textures)
     * @param debugName Human-readable name for debugging
     */
    public TextureResource(int textureId, int target, int internalFormat, 
                          int width, int height, int depth, String debugName, ResourceTracker tracker) {
        super(textureId, tracker);
        this.target = target;
        this.internalFormat = internalFormat;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.debugName = debugName != null ? debugName : "Texture" + textureId;
        
        log.debug("Created texture resource: {} (ID={}, target=0x{}, {}x{}x{}, format=0x{})", 
                 this.debugName, textureId, Integer.toHexString(target), 
                 width, height, depth, Integer.toHexString(internalFormat));
    }
    
    @Override
    protected void doCleanup(Integer textureId) {
        if (textureId != null && textureId != 0) {
            log.debug("Deleting OpenGL texture: {} (ID={})", debugName, textureId);
            glDeleteTextures(textureId);
            
            // Check for OpenGL errors
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                log.error("OpenGL error while deleting texture {}: 0x{}", debugName, Integer.toHexString(error));
            }
        }
    }
    
    @Override
    public GPUResourceType getType() {
        return switch (target) {
            case GL_TEXTURE_1D -> GPUResourceType.TEXTURE_1D;
            case GL_TEXTURE_2D -> GPUResourceType.TEXTURE_2D;
            case GL_TEXTURE_3D -> GPUResourceType.TEXTURE_3D;
            case GL_TEXTURE_CUBE_MAP -> GPUResourceType.TEXTURE_CUBE;
            default -> GPUResourceType.TEXTURE;
        };
    }
    
    @Override
    public int getOpenGLId() {
        return get();
    }
    
    @Override
    public Object getNativeHandle() {
        return get();
    }
    
    @Override
    public String getDescription() {
        return String.format("%s %dx%dx%d (format=0x%s)", 
                           debugName, width, height, depth, 
                           Integer.toHexString(internalFormat));
    }
    
    @Override
    public boolean isBound() {
        return isBound;
    }
    
    @Override
    public void bind() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind disposed texture: " + debugName);
        }
        
        glBindTexture(target, get());
        isBound = true;
        
        log.trace("Bound texture: {} to target 0x{}", debugName, Integer.toHexString(target));
    }
    
    @Override
    public void unbind() {
        if (isBound) {
            glBindTexture(target, 0);
            isBound = false;
            log.trace("Unbound texture: {}", debugName);
        }
    }
    
    @Override
    public int getOpenGLTarget() {
        return target;
    }
    
    /**
     * Get texture dimensions
     */
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    
    /**
     * Get internal format
     */
    public int getInternalFormat() {
        return internalFormat;
    }
    
    /**
     * Get debug name
     */
    public String getDebugName() {
        return debugName;
    }
    
    @Override
    public long getSizeBytes() {
        if (!isValid()) {
            return 0;
        }
        
        // Calculate texture size based on dimensions and format
        int bytesPerPixel = estimateBytesPerPixel(internalFormat);
        long totalPixels = (long) width * height * depth;
        return totalPixels * bytesPerPixel;
    }
    
    @Override
    public ResourceStatistics getStatistics() {
        if (!isValid()) {
            return new ResourceStatistics(0, 0, 0, 0, 0.0);
        }
        
        // Calculate texture size based on dimensions and format
        // This is a rough estimate - actual GPU memory usage may vary
        int bytesPerPixel = estimateBytesPerPixel(internalFormat);
        long totalPixels = (long) width * height * depth;
        long estimatedBytes = totalPixels * bytesPerPixel;
        
        return new ResourceStatistics(
            estimatedBytes,      // allocatedBytes
            estimatedBytes,      // usedBytes (assume full usage)
            0,                   // accessCount (not tracked for OpenGL)
            System.currentTimeMillis(), // lastAccessTime
            100.0                // utilizationPercent (assume full)
        );
    }
    
    private int estimateBytesPerPixel(int internalFormat) {
        return switch (internalFormat) {
            case GL_R8, GL_R8_SNORM -> 1;
            case GL_RG8, GL_RG8_SNORM, GL_R16, GL_R16F -> 2;
            case GL_RGB8, GL_RGB8_SNORM -> 3;
            case GL_RGBA8, GL_RGBA8_SNORM, GL_RG16, GL_RG16F, GL_R32F -> 4;
            case GL_RGB16, GL_RGB16F -> 6;
            case GL_RGBA16, GL_RGBA16F, GL_RG32F -> 8;
            case GL_RGB32F -> 12;
            case GL_RGBA32F, GL_RGB32I, GL_RGB32UI -> 16;
            case GL_DEPTH_COMPONENT32F -> 4;
            case GL_DEPTH_COMPONENT24 -> 3;
            case GL_DEPTH_COMPONENT16 -> 2;
            default -> 4; // Conservative default
        };
    }
    
    /**
     * Bind texture to an image unit (for compute shaders)
     * 
     * @param unit Image unit index
     * @param access Access mode (GL_READ_ONLY, GL_WRITE_ONLY, GL_READ_WRITE)
     */
    public void bindToImageUnit(int unit, int access) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind disposed texture: " + debugName);
        }
        
        glBindImageTexture(unit, get(), 0, false, 0, access, internalFormat);
        log.trace("Bound texture {} to image unit {} with access 0x{}", 
                 debugName, unit, Integer.toHexString(access));
    }
    
    /**
     * Update texture data (2D textures only)
     * 
     * @param level Mipmap level
     * @param xOffset X offset in pixels
     * @param yOffset Y offset in pixels
     * @param width Width in pixels
     * @param height Height in pixels
     * @param format Pixel format (GL_RGBA, GL_RGB, etc.)
     * @param type Pixel type (GL_UNSIGNED_BYTE, GL_FLOAT, etc.)
     * @param data Pixel data
     */
    public void updateData2D(int level, int xOffset, int yOffset, int width, int height,
                           int format, int type, ByteBuffer data) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot update disposed texture: " + debugName);
        }
        
        if (target != GL_TEXTURE_2D) {
            throw new UnsupportedOperationException("updateData2D only supports GL_TEXTURE_2D, not: 0x" + 
                                                  Integer.toHexString(target));
        }
        
        boolean wasBound = isBound;
        if (!wasBound) {
            bind();
        }
        
        try {
            glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, data);
            
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                throw new RuntimeException(String.format(
                    "Failed to update texture %s: OpenGL error 0x%X", debugName, error));
            }
            
            log.trace("Updated texture: {} ({}x{} at {},{} level {})", 
                     debugName, width, height, xOffset, yOffset, level);
            
        } finally {
            if (!wasBound) {
                unbind();
            }
        }
    }
    
    /**
     * Set texture parameters
     */
    public void setParameter(int paramName, int paramValue) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set parameters on disposed texture: " + debugName);
        }
        
        boolean wasBound = isBound;
        if (!wasBound) {
            bind();
        }
        
        try {
            glTexParameteri(target, paramName, paramValue);
            log.trace("Set texture parameter: {} param=0x{} value=0x{}", 
                     debugName, Integer.toHexString(paramName), Integer.toHexString(paramValue));
        } finally {
            if (!wasBound) {
                unbind();
            }
        }
    }
    
    /**
     * Static factory methods for common texture types
     */
    public static class Factory {
        
        /**
         * Create a 2D texture
         */
        public static TextureResource create2D(int width, int height, int internalFormat, String debugName) {
            int textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            
            // Allocate storage
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
            
            // Set default parameters
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            
            return new TextureResource(textureId, GL_TEXTURE_2D, internalFormat, width, height, 1, debugName, 
                                      ResourceTracker.getGlobalTracker());
        }
        
        /**
         * Create a 2D texture with initial data
         */
        public static TextureResource create2D(int width, int height, int internalFormat, 
                                             int format, int type, ByteBuffer data, String debugName) {
            int textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            
            // Upload data
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, data);
            
            // Set default parameters
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            
            return new TextureResource(textureId, GL_TEXTURE_2D, internalFormat, width, height, 1, debugName, 
                                      ResourceTracker.getGlobalTracker());
        }
        
        /**
         * Create a 3D texture
         */
        public static TextureResource create3D(int width, int height, int depth, int internalFormat, String debugName) {
            int textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_3D, textureId);
            
            // Allocate storage
            glTexImage3D(GL_TEXTURE_3D, 0, internalFormat, width, height, depth, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
            
            // Set default parameters
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
            
            return new TextureResource(textureId, GL_TEXTURE_3D, internalFormat, width, height, depth, debugName, 
                                      ResourceTracker.getGlobalTracker());
        }
        
        /**
         * Create a render target texture (common format RGBA8)
         */
        public static TextureResource createRenderTarget(int width, int height, String debugName) {
            return create2D(width, height, GL_RGBA8, debugName);
        }
        
        /**
         * Create a depth texture
         */
        public static TextureResource createDepthTexture(int width, int height, String debugName) {
            return create2D(width, height, GL_DEPTH_COMPONENT32F, debugName);
        }
    }
}