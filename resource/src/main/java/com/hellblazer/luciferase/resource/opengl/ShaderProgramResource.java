package com.hellblazer.luciferase.resource.opengl;

import com.hellblazer.luciferase.resource.GPUResource.ResourceStatistics;
import com.hellblazer.luciferase.resource.GPUResourceType;
import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL Shader Program Resource
 * Wraps OpenGL shader program objects with automatic lifecycle management.
 */
public class ShaderProgramResource extends ResourceHandle<Integer> implements OpenGLResource {
    private static final Logger log = LoggerFactory.getLogger(ShaderProgramResource.class);
    
    private final String debugName;
    private final List<ShaderResource> attachedShaders;
    private volatile boolean isBound = false;
    
    /**
     * Create a shader program resource
     * 
     * @param programId OpenGL program ID from glCreateProgram()
     * @param debugName Human-readable name for debugging
     */
    public ShaderProgramResource(int programId, String debugName, ResourceTracker tracker) {
        super(programId, tracker);
        this.debugName = debugName != null ? debugName : "Program" + programId;
        this.attachedShaders = new ArrayList<>();
        
        log.debug("Created shader program resource: {} (ID={})", this.debugName, programId);
    }
    
    @Override
    protected void doCleanup(Integer programId) {
        if (programId != null && programId != 0) {
            log.debug("Deleting OpenGL shader program: {} (ID={})", debugName, programId);
            
            // Detach all shaders first
            for (var shader : attachedShaders) {
                if (shader.isValid()) {
                    glDetachShader(programId, shader.getOpenGLId());
                }
            }
            
            glDeleteProgram(programId);
            
            // Check for OpenGL errors
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                log.error("OpenGL error while deleting program {}: 0x{}", debugName, Integer.toHexString(error));
            }
        }
    }
    
    @Override
    public GPUResourceType getType() {
        return GPUResourceType.SHADER_PROGRAM;
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
        return String.format("%s (%d shaders attached)", debugName, attachedShaders.size());
    }
    
    @Override
    public boolean isBound() {
        return isBound;
    }
    
    @Override
    public void bind() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind disposed shader program: " + debugName);
        }
        
        glUseProgram(get());
        isBound = true;
        
        log.trace("Bound shader program: {}", debugName);
    }
    
    @Override
    public void unbind() {
        if (isBound) {
            glUseProgram(0);
            isBound = false;
            log.trace("Unbound shader program: {}", debugName);
        }
    }
    
    @Override
    public int getOpenGLTarget() {
        // Programs don't have targets like buffers/textures
        return 0;
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
        
        // Estimate program size based on attached shaders
        long totalBytes = 0;
        for (var shader : attachedShaders) {
            if (shader.isValid()) {
                totalBytes += shader.getSizeBytes();
            }
        }
        
        // Add some overhead for the program itself (uniforms, etc.)
        return totalBytes + 2048;
    }
    
    @Override
    public ResourceStatistics getStatistics() {
        if (!isValid()) {
            return new ResourceStatistics(0, 0, 0, 0, 0.0);
        }
        
        // Estimate program size based on attached shaders
        long totalBytes = 0;
        for (var shader : attachedShaders) {
            if (shader.isValid()) {
                var shaderStats = shader.getStatistics();
                totalBytes += shaderStats.getAllocatedBytes();
            }
        }
        
        // Add some overhead for the program itself (uniforms, etc.)
        totalBytes += 2048;
        
        return new ResourceStatistics(
            totalBytes,          // allocatedBytes
            totalBytes,          // usedBytes (assume full usage)
            0,                   // accessCount (not tracked for OpenGL)
            System.currentTimeMillis(), // lastAccessTime
            100.0                // utilizationPercent (assume full)
        );
    }
    
    /**
     * Attach a shader to this program
     */
    public void attachShader(ShaderResource shader) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot attach shader to disposed program: " + debugName);
        }
        
        if (!shader.isValid()) {
            throw new IllegalArgumentException("Cannot attach disposed shader to program: " + debugName);
        }
        
        glAttachShader(get(), shader.getOpenGLId());
        attachedShaders.add(shader);
        
        log.debug("Attached shader {} to program {}", shader.getDebugName(), debugName);
    }
    
    /**
     * Link the shader program
     */
    public void link() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot link disposed program: " + debugName);
        }
        
        glLinkProgram(get());
        
        // Check link status
        if (glGetProgrami(get(), GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(get());
            throw new RuntimeException(String.format("Shader program linking failed: %s\\n%s", debugName, log));
        }
        
        log.debug("Linked shader program: {}", debugName);
    }
    
    /**
     * Get program linking status
     */
    public boolean isLinked() {
        if (!isValid()) {
            return false;
        }
        return glGetProgrami(get(), GL_LINK_STATUS) == GL_TRUE;
    }
    
    /**
     * Get program info log (linking errors/warnings)
     */
    public String getInfoLog() {
        if (!isValid()) {
            return "Program disposed";
        }
        return glGetProgramInfoLog(get());
    }
    
    /**
     * Get uniform location
     */
    public int getUniformLocation(String name) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot get uniform location from disposed program: " + debugName);
        }
        
        int location = glGetUniformLocation(get(), name);
        if (location == -1) {
            log.warn("Uniform '{}' not found in program {}", name, debugName);
        }
        return location;
    }
    
    /**
     * Set uniform values
     */
    public void setUniform(String name, int value) {
        int location = getUniformLocation(name);
        if (location != -1) {
            glUniform1i(location, value);
            log.trace("Set uniform int {}.{} = {}", debugName, name, value);
        }
    }
    
    public void setUniform(String name, float value) {
        int location = getUniformLocation(name);
        if (location != -1) {
            glUniform1f(location, value);
            log.trace("Set uniform float {}.{} = {}", debugName, name, value);
        }
    }
    
    public void setUniform(String name, float x, float y) {
        int location = getUniformLocation(name);
        if (location != -1) {
            glUniform2f(location, x, y);
            log.trace("Set uniform vec2 {}.{} = ({}, {})", debugName, name, x, y);
        }
    }
    
    public void setUniform(String name, float x, float y, float z) {
        int location = getUniformLocation(name);
        if (location != -1) {
            glUniform3f(location, x, y, z);
            log.trace("Set uniform vec3 {}.{} = ({}, {}, {})", debugName, name, x, y, z);
        }
    }
    
    public void setUniform(String name, float x, float y, float z, float w) {
        int location = getUniformLocation(name);
        if (location != -1) {
            glUniform4f(location, x, y, z, w);
            log.trace("Set uniform vec4 {}.{} = ({}, {}, {}, {})", debugName, name, x, y, z, w);
        }
    }
    
    public void setUniformMatrix4(String name, boolean transpose, FloatBuffer matrix) {
        int location = getUniformLocation(name);
        if (location != -1) {
            glUniformMatrix4fv(location, transpose, matrix);
            log.trace("Set uniform mat4 {}.{}", debugName, name);
        }
    }
    
    /**
     * Dispatch compute shader (for compute programs only)
     */
    public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot dispatch disposed compute program: " + debugName);
        }
        
        boolean wasBound = isBound;
        if (!wasBound) {
            bind();
        }
        
        try {
            glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
            log.trace("Dispatched compute shader: {} ({}x{}x{})", debugName, numGroupsX, numGroupsY, numGroupsZ);
        } finally {
            if (!wasBound) {
                unbind();
            }
        }
    }
    
    /**
     * Get list of attached shaders (read-only)
     */
    public List<ShaderResource> getAttachedShaders() {
        return List.copyOf(attachedShaders);
    }
    
    /**
     * Static factory methods for shader program creation
     */
    public static class Factory {
        
        /**
         * Create, link and validate a shader program from shaders
         */
        public static ShaderProgramResource createAndLink(String debugName, ShaderResource... shaders) {
            int programId = glCreateProgram();
            if (programId == 0) {
                throw new RuntimeException("Failed to create shader program: " + debugName);
            }
            
            var program = new ShaderProgramResource(programId, debugName, 
                                                   ResourceTracker.getGlobalTracker());
            
            try {
                // Attach all shaders
                for (var shader : shaders) {
                    program.attachShader(shader);
                }
                
                // Link program
                program.link();
                
                return program;
                
            } catch (Exception e) {
                program.close();
                throw new RuntimeException("Failed to create shader program: " + debugName, e);
            }
        }
        
        /**
         * Create a compute shader program
         */
        public static ShaderProgramResource createComputeProgram(ShaderResource computeShader, String debugName) {
            return createAndLink(debugName, computeShader);
        }
        
        /**
         * Create a compute shader program from source
         */
        public static ShaderProgramResource createComputeProgram(String computeSource, String debugName) {
            var computeShader = ShaderResource.Factory.createComputeShader(computeSource, debugName + "_CS");
            return createComputeProgram(computeShader, debugName);
        }
        
        /**
         * Create a render pipeline program (vertex + fragment)
         */
        public static ShaderProgramResource createRenderProgram(ShaderResource vertexShader, 
                                                               ShaderResource fragmentShader, 
                                                               String debugName) {
            return createAndLink(debugName, vertexShader, fragmentShader);
        }
        
        /**
         * Create a render pipeline program from sources
         */
        public static ShaderProgramResource createRenderProgram(String vertexSource, 
                                                               String fragmentSource, 
                                                               String debugName) {
            var vertexShader = ShaderResource.Factory.createVertexShader(vertexSource, debugName + "_VS");
            var fragmentShader = ShaderResource.Factory.createFragmentShader(fragmentSource, debugName + "_FS");
            return createRenderProgram(vertexShader, fragmentShader, debugName);
        }
    }
}