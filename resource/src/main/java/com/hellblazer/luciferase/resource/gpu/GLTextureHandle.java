package com.hellblazer.luciferase.resource.gpu;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * RAII handle for OpenGL texture objects.
 * Ensures proper cleanup of GPU texture resources.
 */
public class GLTextureHandle extends ResourceHandle<Integer> {
    private static final Logger log = LoggerFactory.getLogger(GLTextureHandle.class);
    
    public enum TextureType {
        TEXTURE_1D(GL45.GL_TEXTURE_1D, "1D"),
        TEXTURE_2D(GL45.GL_TEXTURE_2D, "2D"),
        TEXTURE_3D(GL45.GL_TEXTURE_3D, "3D"),
        TEXTURE_CUBE_MAP(GL45.GL_TEXTURE_CUBE_MAP, "Cubemap"),
        TEXTURE_1D_ARRAY(GL45.GL_TEXTURE_1D_ARRAY, "1D Array"),
        TEXTURE_2D_ARRAY(GL45.GL_TEXTURE_2D_ARRAY, "2D Array"),
        TEXTURE_CUBE_MAP_ARRAY(GL45.GL_TEXTURE_CUBE_MAP_ARRAY, "Cubemap Array"),
        TEXTURE_2D_MULTISAMPLE(GL45.GL_TEXTURE_2D_MULTISAMPLE, "2D Multisample"),
        TEXTURE_2D_MULTISAMPLE_ARRAY(GL45.GL_TEXTURE_2D_MULTISAMPLE_ARRAY, "2D Multisample Array");
        
        public final int glTarget;
        public final String displayName;
        
        TextureType(int glTarget, String displayName) {
            this.glTarget = glTarget;
            this.displayName = displayName;
        }
    }
    
    public enum Format {
        R8(GL45.GL_R8, GL45.GL_RED, GL45.GL_UNSIGNED_BYTE, 1),
        RG8(GL45.GL_RG8, GL45.GL_RG, GL45.GL_UNSIGNED_BYTE, 2),
        RGB8(GL45.GL_RGB8, GL45.GL_RGB, GL45.GL_UNSIGNED_BYTE, 3),
        RGBA8(GL45.GL_RGBA8, GL45.GL_RGBA, GL45.GL_UNSIGNED_BYTE, 4),
        R16F(GL45.GL_R16F, GL45.GL_RED, GL45.GL_HALF_FLOAT, 2),
        RG16F(GL45.GL_RG16F, GL45.GL_RG, GL45.GL_HALF_FLOAT, 4),
        RGB16F(GL45.GL_RGB16F, GL45.GL_RGB, GL45.GL_HALF_FLOAT, 6),
        RGBA16F(GL45.GL_RGBA16F, GL45.GL_RGBA, GL45.GL_HALF_FLOAT, 8),
        R32F(GL45.GL_R32F, GL45.GL_RED, GL45.GL_FLOAT, 4),
        RG32F(GL45.GL_RG32F, GL45.GL_RG, GL45.GL_FLOAT, 8),
        RGB32F(GL45.GL_RGB32F, GL45.GL_RGB, GL45.GL_FLOAT, 12),
        RGBA32F(GL45.GL_RGBA32F, GL45.GL_RGBA, GL45.GL_FLOAT, 16),
        DEPTH16(GL45.GL_DEPTH_COMPONENT16, GL45.GL_DEPTH_COMPONENT, GL45.GL_UNSIGNED_SHORT, 2),
        DEPTH24(GL45.GL_DEPTH_COMPONENT24, GL45.GL_DEPTH_COMPONENT, GL45.GL_UNSIGNED_INT, 3),
        DEPTH32F(GL45.GL_DEPTH_COMPONENT32F, GL45.GL_DEPTH_COMPONENT, GL45.GL_FLOAT, 4),
        DEPTH24_STENCIL8(GL45.GL_DEPTH24_STENCIL8, GL45.GL_DEPTH_STENCIL, GL45.GL_UNSIGNED_INT_24_8, 4);
        
        public final int internalFormat;
        public final int format;
        public final int type;
        public final int bytesPerPixel;
        
        Format(int internalFormat, int format, int type, int bytesPerPixel) {
            this.internalFormat = internalFormat;
            this.format = format;
            this.type = type;
            this.bytesPerPixel = bytesPerPixel;
        }
    }
    
    private final TextureType type;
    private final Format format;
    private final int width;
    private final int height;
    private final int depth;
    private final int levels;
    private volatile boolean bound;
    private volatile int boundUnit = -1;
    
    /**
     * Create a 2D texture.
     * 
     * @param width Width in pixels
     * @param height Height in pixels
     * @param format Texture format
     * @param levels Number of mipmap levels (0 for auto)
     * @param tracker Optional resource tracker
     * @return New texture handle
     */
    public static GLTextureHandle create2D(int width, int height, Format format, int levels, ResourceTracker tracker) {
        int texture = GL45.glCreateTextures(GL45.GL_TEXTURE_2D);
        
        if (levels == 0) {
            levels = calculateMipLevels(width, height);
        }
        
        GL45.glTextureStorage2D(texture, levels, format.internalFormat, width, height);
        
        log.trace("Created 2D texture {} ({}x{}, {} levels, format {})", 
            texture, width, height, levels, format.name());
        
        return new GLTextureHandle(texture, TextureType.TEXTURE_2D, format, width, height, 1, levels, tracker);
    }
    
    /**
     * Create a 3D texture.
     * 
     * @param width Width in pixels
     * @param height Height in pixels
     * @param depth Depth in pixels
     * @param format Texture format
     * @param levels Number of mipmap levels
     * @param tracker Optional resource tracker
     * @return New texture handle
     */
    public static GLTextureHandle create3D(int width, int height, int depth, Format format, int levels, ResourceTracker tracker) {
        int texture = GL45.glCreateTextures(GL45.GL_TEXTURE_3D);
        
        if (levels == 0) {
            levels = calculateMipLevels(Math.max(width, Math.max(height, depth)));
        }
        
        GL45.glTextureStorage3D(texture, levels, format.internalFormat, width, height, depth);
        
        log.trace("Created 3D texture {} ({}x{}x{}, {} levels, format {})", 
            texture, width, height, depth, levels, format.name());
        
        return new GLTextureHandle(texture, TextureType.TEXTURE_3D, format, width, height, depth, levels, tracker);
    }
    
    /**
     * Create a cubemap texture.
     * 
     * @param size Size of each face in pixels
     * @param format Texture format
     * @param levels Number of mipmap levels
     * @param tracker Optional resource tracker
     * @return New cubemap handle
     */
    public static GLTextureHandle createCubemap(int size, Format format, int levels, ResourceTracker tracker) {
        int texture = GL45.glCreateTextures(GL45.GL_TEXTURE_CUBE_MAP);
        
        if (levels == 0) {
            levels = calculateMipLevels(size);
        }
        
        GL45.glTextureStorage2D(texture, levels, format.internalFormat, size, size);
        
        log.trace("Created cubemap texture {} ({}x{}, {} levels, format {})", 
            texture, size, size, levels, format.name());
        
        return new GLTextureHandle(texture, TextureType.TEXTURE_CUBE_MAP, format, size, size, 6, levels, tracker);
    }
    
    /**
     * Create a 2D texture array.
     * 
     * @param width Width in pixels
     * @param height Height in pixels
     * @param layers Number of layers
     * @param format Texture format
     * @param levels Number of mipmap levels
     * @param tracker Optional resource tracker
     * @return New texture array handle
     */
    public static GLTextureHandle create2DArray(int width, int height, int layers, Format format, int levels, ResourceTracker tracker) {
        int texture = GL45.glCreateTextures(GL45.GL_TEXTURE_2D_ARRAY);
        
        if (levels == 0) {
            levels = calculateMipLevels(width, height);
        }
        
        GL45.glTextureStorage3D(texture, levels, format.internalFormat, width, height, layers);
        
        log.trace("Created 2D array texture {} ({}x{}x{} layers, {} levels, format {})", 
            texture, width, height, layers, levels, format.name());
        
        return new GLTextureHandle(texture, TextureType.TEXTURE_2D_ARRAY, format, width, height, layers, levels, tracker);
    }
    
    private GLTextureHandle(Integer texture, TextureType type, Format format, 
                           int width, int height, int depth, int levels, ResourceTracker tracker) {
        super(texture, tracker);
        this.type = type;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.levels = levels;
        this.bound = false;
    }
    
    /**
     * Calculate number of mipmap levels for given dimensions.
     */
    private static int calculateMipLevels(int... dimensions) {
        int maxDim = 0;
        for (int dim : dimensions) {
            maxDim = Math.max(maxDim, dim);
        }
        return (int) (Math.log(maxDim) / Math.log(2)) + 1;
    }
    
    /**
     * Get the OpenGL texture ID.
     */
    public int getTextureId() {
        return get();
    }
    
    /**
     * Get texture type.
     */
    public TextureType getType() {
        return type;
    }
    
    /**
     * Get texture format.
     */
    public Format getFormat() {
        return format;
    }
    
    /**
     * Get width in pixels.
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Get height in pixels.
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Get depth/layers.
     */
    public int getDepth() {
        return depth;
    }
    
    /**
     * Get number of mipmap levels.
     */
    public int getLevels() {
        return levels;
    }
    
    /**
     * Bind texture to a texture unit.
     * 
     * @param unit Texture unit (0-based)
     */
    public void bind(int unit) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind closed texture");
        }
        GL45.glBindTextureUnit(unit, get());
        bound = true;
        boundUnit = unit;
    }
    
    /**
     * Unbind texture from its unit.
     */
    public void unbind() {
        if (bound && boundUnit >= 0) {
            GL45.glBindTextureUnit(boundUnit, 0);
            bound = false;
            boundUnit = -1;
        }
    }
    
    /**
     * Upload data to 2D texture.
     * 
     * @param level Mipmap level
     * @param x X offset
     * @param y Y offset
     * @param width Width
     * @param height Height
     * @param data Pixel data
     */
    public void upload2D(int level, int x, int y, int width, int height, ByteBuffer data) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot upload to closed texture");
        }
        GL45.glTextureSubImage2D(get(), level, x, y, width, height, 
            format.format, format.type, data);
    }
    
    /**
     * Upload data to 3D texture.
     * 
     * @param level Mipmap level
     * @param x X offset
     * @param y Y offset
     * @param z Z offset
     * @param width Width
     * @param height Height
     * @param depth Depth
     * @param data Pixel data
     */
    public void upload3D(int level, int x, int y, int z, int width, int height, int depth, ByteBuffer data) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot upload to closed texture");
        }
        GL45.glTextureSubImage3D(get(), level, x, y, z, width, height, depth,
            format.format, format.type, data);
    }
    
    /**
     * Generate mipmaps.
     */
    public void generateMipmaps() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot generate mipmaps for closed texture");
        }
        GL45.glGenerateTextureMipmap(get());
    }
    
    /**
     * Set texture parameter.
     * 
     * @param param Parameter name
     * @param value Parameter value
     */
    public void setParameter(int param, int value) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set parameter on closed texture");
        }
        GL45.glTextureParameteri(get(), param, value);
    }
    
    /**
     * Set texture parameter.
     * 
     * @param param Parameter name
     * @param value Parameter value
     */
    public void setParameter(int param, float value) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot set parameter on closed texture");
        }
        GL45.glTextureParameterf(get(), param, value);
    }
    
    /**
     * Set default filtering parameters for sampling.
     * 
     * @param minFilter Minification filter
     * @param magFilter Magnification filter
     */
    public void setFiltering(int minFilter, int magFilter) {
        setParameter(GL45.GL_TEXTURE_MIN_FILTER, minFilter);
        setParameter(GL45.GL_TEXTURE_MAG_FILTER, magFilter);
    }
    
    /**
     * Set wrapping mode.
     * 
     * @param wrapS S wrapping
     * @param wrapT T wrapping
     */
    public void setWrapping(int wrapS, int wrapT) {
        setParameter(GL45.GL_TEXTURE_WRAP_S, wrapS);
        setParameter(GL45.GL_TEXTURE_WRAP_T, wrapT);
    }
    
    /**
     * Set wrapping mode for 3D textures.
     * 
     * @param wrapS S wrapping
     * @param wrapT T wrapping
     * @param wrapR R wrapping
     */
    public void setWrapping(int wrapS, int wrapT, int wrapR) {
        setParameter(GL45.GL_TEXTURE_WRAP_S, wrapS);
        setParameter(GL45.GL_TEXTURE_WRAP_T, wrapT);
        setParameter(GL45.GL_TEXTURE_WRAP_R, wrapR);
    }
    
    /**
     * Get estimated memory usage in bytes.
     */
    public long getEstimatedMemoryUsage() {
        long pixelCount = (long) width * height * depth;
        long totalPixels = 0;
        
        // Sum up all mipmap levels
        for (int level = 0; level < levels; level++) {
            totalPixels += pixelCount;
            pixelCount /= 4; // Each level has 1/4 the pixels
        }
        
        return totalPixels * format.bytesPerPixel;
    }
    
    @Override
    protected void doCleanup(Integer texture) {
        unbind();
        GL45.glDeleteTextures(texture);
        log.trace("Deleted {} texture {} ({}x{}x{}, {} bytes estimated)", 
            type.displayName, texture, width, height, depth, getEstimatedMemoryUsage());
    }
    
    @Override
    public String toString() {
        return String.format("GLTextureHandle[id=%d, type=%s, size=%dx%dx%d, format=%s, state=%s]",
            get(), type.displayName, width, height, depth, format.name(), getState());
    }
}