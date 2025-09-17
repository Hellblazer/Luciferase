package com.hellblazer.luciferase.resource.opengl;

import com.hellblazer.luciferase.resource.GPUResource.ResourceStatistics;
import com.hellblazer.luciferase.resource.GPUResourceType;
import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL Shader Resource
 * Wraps OpenGL shader objects with automatic lifecycle management.
 */
public class ShaderResource extends ResourceHandle<Integer> implements OpenGLResource {
    private static final Logger log = LoggerFactory.getLogger(ShaderResource.class);
    
    private final int shaderType;
    private final String debugName;
    private final String source;
    
    /**
     * Create a shader resource
     * 
     * @param shaderId OpenGL shader ID from glCreateShader()
     * @param shaderType OpenGL shader type (GL_VERTEX_SHADER, GL_COMPUTE_SHADER, etc.)
     * @param source Shader source code (for debugging)
     * @param debugName Human-readable name for debugging
     */
    public ShaderResource(int shaderId, int shaderType, String source, String debugName, ResourceTracker tracker) {
        super(shaderId, tracker);
        this.shaderType = shaderType;
        this.source = source;
        this.debugName = debugName != null ? debugName : "Shader" + shaderId;
        
        log.debug("Created shader resource: {} (ID={}, type=0x{})", 
                 this.debugName, shaderId, Integer.toHexString(shaderType));
    }
    
    @Override
    protected void doCleanup(Integer shaderId) {
        if (shaderId != null && shaderId != 0) {
            log.debug("Deleting OpenGL shader: {} (ID={})", debugName, shaderId);
            glDeleteShader(shaderId);
            
            // Check for OpenGL errors
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                log.error("OpenGL error while deleting shader {}: 0x{}", debugName, Integer.toHexString(error));
            }
        }
    }
    
    @Override
    public GPUResourceType getType() {
        return switch (shaderType) {
            case GL_VERTEX_SHADER -> GPUResourceType.VERTEX_SHADER;
            case GL_FRAGMENT_SHADER -> GPUResourceType.FRAGMENT_SHADER;
            case GL_COMPUTE_SHADER -> GPUResourceType.COMPUTE_SHADER;
            case GL_GEOMETRY_SHADER -> GPUResourceType.GEOMETRY_SHADER;
            case GL_TESS_CONTROL_SHADER -> GPUResourceType.TESSELLATION_CONTROL_SHADER;
            case GL_TESS_EVALUATION_SHADER -> GPUResourceType.TESSELLATION_EVALUATION_SHADER;
            default -> GPUResourceType.SHADER;
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
        return String.format("%s (type=0x%s)", debugName, 
                           Integer.toHexString(shaderType));
    }
    
    @Override
    public boolean isBound() {
        // Shaders don't have a "bound" state - they're attached to programs
        return false;
    }
    
    @Override
    public void bind() {
        // Shaders can't be bound directly - they must be attached to programs
        throw new UnsupportedOperationException("Shaders cannot be bound directly. Use ShaderProgramResource instead.");
    }
    
    @Override
    public void unbind() {
        // Shaders can't be bound directly
        throw new UnsupportedOperationException("Shaders cannot be unbound directly. Use ShaderProgramResource instead.");
    }
    
    @Override
    public int getOpenGLTarget() {
        // Shaders don't have targets like buffers/textures
        return 0;
    }
    
    /**
     * Get shader type
     */
    public int getShaderType() {
        return shaderType;
    }
    
    /**
     * Get shader source code
     */
    public String getSource() {
        return source;
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
        
        // Estimate shader size based on source code length
        return source != null ? source.length() * 2L : 1024L;
    }
    
    @Override
    public ResourceStatistics getStatistics() {
        if (!isValid()) {
            return new ResourceStatistics(0, 0, 0, 0, 0.0);
        }
        
        // Estimate shader size based on source code length
        // This is a very rough approximation - actual GPU usage varies significantly
        long estimatedBytes = source != null ? source.length() * 2L : 1024L; // Assume compiled code is ~2x source size
        
        return new ResourceStatistics(
            estimatedBytes,      // allocatedBytes
            estimatedBytes,      // usedBytes (assume full usage)
            0,                   // accessCount (not tracked for OpenGL)
            System.currentTimeMillis(), // lastAccessTime
            100.0                // utilizationPercent (assume full)
        );
    }
    
    /**
     * Get shader compilation status
     */
    public boolean isCompiled() {
        if (!isValid()) {
            return false;
        }
        return glGetShaderi(get(), GL_COMPILE_STATUS) == GL_TRUE;
    }
    
    /**
     * Get shader info log (compilation errors/warnings)
     */
    public String getInfoLog() {
        if (!isValid()) {
            return "Shader disposed";
        }
        return glGetShaderInfoLog(get());
    }
    
    /**
     * Static factory methods for shader creation and compilation
     */
    public static class Factory {
        
        /**
         * Create and compile a shader from source
         * 
         * @param shaderType OpenGL shader type
         * @param source Shader source code
         * @param debugName Debug name for logging
         * @return Compiled shader resource
         * @throws RuntimeException if compilation fails
         */
        public static ShaderResource createAndCompile(int shaderType, String source, String debugName) {
            int shaderId = glCreateShader(shaderType);
            if (shaderId == 0) {
                throw new RuntimeException("Failed to create shader: " + debugName);
            }
            
            // Set source and compile
            glShaderSource(shaderId, source);
            glCompileShader(shaderId);
            
            // Check compilation status
            if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == GL_FALSE) {
                String log = glGetShaderInfoLog(shaderId);
                glDeleteShader(shaderId);
                throw new RuntimeException(String.format("Shader compilation failed: %s\\n%s", debugName, log));
            }
            
            return new ShaderResource(shaderId, shaderType, source, debugName, 
                                      ResourceTracker.getGlobalTracker());
        }
        
        /**
         * Create a vertex shader
         */
        public static ShaderResource createVertexShader(String source, String debugName) {
            return createAndCompile(GL_VERTEX_SHADER, source, debugName);
        }
        
        /**
         * Create a fragment shader
         */
        public static ShaderResource createFragmentShader(String source, String debugName) {
            return createAndCompile(GL_FRAGMENT_SHADER, source, debugName);
        }
        
        /**
         * Create a compute shader
         */
        public static ShaderResource createComputeShader(String source, String debugName) {
            return createAndCompile(GL_COMPUTE_SHADER, source, debugName);
        }
        
        /**
         * Create a geometry shader
         */
        public static ShaderResource createGeometryShader(String source, String debugName) {
            return createAndCompile(GL_GEOMETRY_SHADER, source, debugName);
        }
        
        /**
         * Load shader source from classpath resource
         */
        public static String loadShaderSource(String resourcePath) {
            try (var is = ShaderResource.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new RuntimeException("Shader resource not found: " + resourcePath);
                }
                return new String(is.readAllBytes());
            } catch (Exception e) {
                throw new RuntimeException("Failed to load shader source: " + resourcePath, e);
            }
        }
        
        /**
         * Create shader from classpath resource
         */
        public static ShaderResource createFromResource(int shaderType, String resourcePath, String debugName) {
            String source = loadShaderSource(resourcePath);
            return createAndCompile(shaderType, source, debugName);
        }
    }
}