package com.hellblazer.luciferase.resource.gpu;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAII handle for OpenGL shader programs.
 * Manages compilation, linking, and cleanup of shader programs.
 */
public class GLProgramHandle extends ResourceHandle<Integer> {
    private static final Logger log = LoggerFactory.getLogger(GLProgramHandle.class);
    
    public enum ShaderType {
        VERTEX(GL45.GL_VERTEX_SHADER, "Vertex"),
        FRAGMENT(GL45.GL_FRAGMENT_SHADER, "Fragment"),
        GEOMETRY(GL45.GL_GEOMETRY_SHADER, "Geometry"),
        TESS_CONTROL(GL45.GL_TESS_CONTROL_SHADER, "Tessellation Control"),
        TESS_EVALUATION(GL45.GL_TESS_EVALUATION_SHADER, "Tessellation Evaluation"),
        COMPUTE(GL45.GL_COMPUTE_SHADER, "Compute");
        
        public final int glType;
        public final String displayName;
        
        ShaderType(int glType, String displayName) {
            this.glType = glType;
            this.displayName = displayName;
        }
    }
    
    private final List<Integer> shaders;
    private final Map<String, Integer> uniformLocations;
    private final Map<String, Integer> attributeLocations;
    private volatile boolean linked;
    private volatile boolean inUse;
    
    /**
     * Create a new shader program.
     * 
     * @param tracker Optional resource tracker
     */
    public GLProgramHandle(ResourceTracker tracker) {
        super(GL45.glCreateProgram(), tracker);
        this.shaders = new ArrayList<>();
        this.uniformLocations = new HashMap<>();
        this.attributeLocations = new HashMap<>();
        this.linked = false;
        this.inUse = false;
        
        log.trace("Created shader program {}", get());
    }
    
    /**
     * Create a program from vertex and fragment shader sources.
     * 
     * @param vertexSource Vertex shader source
     * @param fragmentSource Fragment shader source
     * @param tracker Optional resource tracker
     * @return New program handle
     * @throws ShaderCompilationException if compilation fails
     */
    public static GLProgramHandle createFromSources(String vertexSource, String fragmentSource, ResourceTracker tracker) 
            throws ShaderCompilationException {
        var program = new GLProgramHandle(tracker);
        
        try {
            program.attachShader(ShaderType.VERTEX, vertexSource);
            program.attachShader(ShaderType.FRAGMENT, fragmentSource);
            program.link();
            
            return program;
            
        } catch (Exception e) {
            program.close();
            throw e;
        }
    }
    
    /**
     * Create a compute program from source.
     * 
     * @param computeSource Compute shader source
     * @param tracker Optional resource tracker
     * @return New program handle
     * @throws ShaderCompilationException if compilation fails
     */
    public static GLProgramHandle createComputeProgram(String computeSource, ResourceTracker tracker) 
            throws ShaderCompilationException {
        var program = new GLProgramHandle(tracker);
        
        try {
            program.attachShader(ShaderType.COMPUTE, computeSource);
            program.link();
            
            return program;
            
        } catch (Exception e) {
            program.close();
            throw e;
        }
    }
    
    /**
     * Attach and compile a shader.
     * 
     * @param type Shader type
     * @param source Shader source code
     * @throws ShaderCompilationException if compilation fails
     */
    public void attachShader(ShaderType type, String source) throws ShaderCompilationException {
        if (!isValid()) {
            throw new IllegalStateException("Cannot attach shader to closed program");
        }
        
        if (linked) {
            throw new IllegalStateException("Cannot attach shader to linked program");
        }
        
        int shader = GL45.glCreateShader(type.glType);
        shaders.add(shader);
        
        GL45.glShaderSource(shader, source);
        GL45.glCompileShader(shader);
        
        // Check compilation status
        int status = GL45.glGetShaderi(shader, GL45.GL_COMPILE_STATUS);
        if (status == GL45.GL_FALSE) {
            String infoLog = GL45.glGetShaderInfoLog(shader);
            GL45.glDeleteShader(shader);
            shaders.remove(Integer.valueOf(shader));
            
            throw new ShaderCompilationException(
                type.displayName + " shader compilation failed: " + infoLog
            );
        }
        
        GL45.glAttachShader(get(), shader);
        
        log.trace("Attached {} shader {} to program {}", type.displayName, shader, get());
    }
    
    /**
     * Link the shader program.
     * 
     * @throws ShaderCompilationException if linking fails
     */
    public void link() throws ShaderCompilationException {
        if (!isValid()) {
            throw new IllegalStateException("Cannot link closed program");
        }
        
        if (linked) {
            throw new IllegalStateException("Program already linked");
        }
        
        if (shaders.isEmpty()) {
            throw new IllegalStateException("No shaders attached");
        }
        
        GL45.glLinkProgram(get());
        
        // Check link status
        int status = GL45.glGetProgrami(get(), GL45.GL_LINK_STATUS);
        if (status == GL45.GL_FALSE) {
            String infoLog = GL45.glGetProgramInfoLog(get());
            throw new ShaderCompilationException("Program linking failed: " + infoLog);
        }
        
        linked = true;
        
        // Clean up shader objects after successful link
        for (int shader : shaders) {
            GL45.glDetachShader(get(), shader);
            GL45.glDeleteShader(shader);
        }
        shaders.clear();
        
        // Cache active uniforms and attributes
        cacheActiveUniforms();
        cacheActiveAttributes();
        
        log.trace("Linked program {} with {} uniforms and {} attributes", 
            get(), uniformLocations.size(), attributeLocations.size());
    }
    
    /**
     * Cache active uniform locations.
     */
    private void cacheActiveUniforms() {
        int count = GL45.glGetProgrami(get(), GL45.GL_ACTIVE_UNIFORMS);
        
        for (int i = 0; i < count; i++) {
            IntBuffer size = IntBuffer.allocate(1);
            IntBuffer type = IntBuffer.allocate(1);
            String name = GL45.glGetActiveUniform(get(), i, size, type);
            
            // Remove array suffix if present
            if (name.endsWith("[0]")) {
                name = name.substring(0, name.length() - 3);
            }
            
            int location = GL45.glGetUniformLocation(get(), name);
            if (location >= 0) {
                uniformLocations.put(name, location);
            }
        }
    }
    
    /**
     * Cache active attribute locations.
     */
    private void cacheActiveAttributes() {
        int count = GL45.glGetProgrami(get(), GL45.GL_ACTIVE_ATTRIBUTES);
        
        for (int i = 0; i < count; i++) {
            IntBuffer size = IntBuffer.allocate(1);
            IntBuffer type = IntBuffer.allocate(1);
            String name = GL45.glGetActiveAttrib(get(), i, size, type);
            
            int location = GL45.glGetAttribLocation(get(), name);
            if (location >= 0) {
                attributeLocations.put(name, location);
            }
        }
    }
    
    /**
     * Get the OpenGL program ID.
     */
    public int getProgramId() {
        return get();
    }
    
    /**
     * Check if the program is linked.
     */
    public boolean isLinked() {
        return linked;
    }
    
    /**
     * Use this program for rendering.
     */
    public void use() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot use closed program");
        }
        
        if (!linked) {
            throw new IllegalStateException("Cannot use unlinked program");
        }
        
        GL45.glUseProgram(get());
        inUse = true;
    }
    
    /**
     * Stop using this program.
     */
    public void unuse() {
        if (inUse) {
            GL45.glUseProgram(0);
            inUse = false;
        }
    }
    
    /**
     * Get uniform location (cached).
     * 
     * @param name Uniform name
     * @return Location, or -1 if not found
     */
    public int getUniformLocation(String name) {
        return uniformLocations.getOrDefault(name, -1);
    }
    
    /**
     * Get attribute location (cached).
     * 
     * @param name Attribute name
     * @return Location, or -1 if not found
     */
    public int getAttributeLocation(String name) {
        return attributeLocations.getOrDefault(name, -1);
    }
    
    /**
     * Set uniform value.
     * 
     * @param name Uniform name
     * @param value Value
     */
    public void setUniform1i(String name, int value) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL45.glProgramUniform1i(get(), location, value);
        }
    }
    
    /**
     * Set uniform value.
     * 
     * @param name Uniform name
     * @param value Value
     */
    public void setUniform1f(String name, float value) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL45.glProgramUniform1f(get(), location, value);
        }
    }
    
    /**
     * Set uniform vec2.
     * 
     * @param name Uniform name
     * @param x X value
     * @param y Y value
     */
    public void setUniform2f(String name, float x, float y) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL45.glProgramUniform2f(get(), location, x, y);
        }
    }
    
    /**
     * Set uniform vec3.
     * 
     * @param name Uniform name
     * @param x X value
     * @param y Y value
     * @param z Z value
     */
    public void setUniform3f(String name, float x, float y, float z) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL45.glProgramUniform3f(get(), location, x, y, z);
        }
    }
    
    /**
     * Set uniform vec4.
     * 
     * @param name Uniform name
     * @param x X value
     * @param y Y value
     * @param z Z value
     * @param w W value
     */
    public void setUniform4f(String name, float x, float y, float z, float w) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL45.glProgramUniform4f(get(), location, x, y, z, w);
        }
    }
    
    /**
     * Set uniform matrix4.
     * 
     * @param name Uniform name
     * @param transpose Transpose matrix
     * @param values Matrix values (16 floats)
     */
    public void setUniformMatrix4fv(String name, boolean transpose, float[] values) {
        if (values.length != 16) {
            throw new IllegalArgumentException("Matrix must have 16 values");
        }
        
        int location = getUniformLocation(name);
        if (location >= 0) {
            GL45.glProgramUniformMatrix4fv(get(), location, transpose, values);
        }
    }
    
    /**
     * Bind a uniform block to a binding point.
     * 
     * @param blockName Block name
     * @param bindingPoint Binding point
     */
    public void bindUniformBlock(String blockName, int bindingPoint) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot bind uniform block on closed program");
        }
        
        int blockIndex = GL45.glGetUniformBlockIndex(get(), blockName);
        if (blockIndex != GL45.GL_INVALID_INDEX) {
            GL45.glUniformBlockBinding(get(), blockIndex, bindingPoint);
        }
    }
    
    /**
     * Validate the program for the current OpenGL state.
     * 
     * @return true if valid
     */
    public boolean validate() {
        if (!linked) {
            return false;
        }
        
        GL45.glValidateProgram(get());
        int status = GL45.glGetProgrami(get(), GL45.GL_VALIDATE_STATUS);
        
        if (status == GL45.GL_FALSE) {
            String infoLog = GL45.glGetProgramInfoLog(get());
            log.warn("Program {} validation failed: {}", get(), infoLog);
        }
        
        return status == GL45.GL_TRUE;
    }
    
    @Override
    protected void doCleanup(Integer program) {
        unuse();
        
        // Delete any remaining attached shaders
        for (int shader : shaders) {
            GL45.glDetachShader(program, shader);
            GL45.glDeleteShader(shader);
        }
        
        GL45.glDeleteProgram(program);
        log.trace("Deleted shader program {}", program);
    }
    
    /**
     * Exception thrown when shader compilation or linking fails.
     */
    public static class ShaderCompilationException extends Exception {
        public ShaderCompilationException(String message) {
            super(message);
        }
    }
    
    @Override
    public String toString() {
        return String.format("GLProgramHandle[id=%d, linked=%b, uniforms=%d, attributes=%d, state=%s]",
            get(), linked, uniformLocations.size(), attributeLocations.size(), getState());
    }
}