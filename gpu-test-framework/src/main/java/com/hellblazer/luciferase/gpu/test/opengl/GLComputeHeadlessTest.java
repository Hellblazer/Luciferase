package com.hellblazer.luciferase.gpu.test.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Base class for headless OpenGL compute shader testing.
 * Provides OpenGL context creation without visible windows for CI/CD compatibility.
 */
public abstract class GLComputeHeadlessTest {
    
    private static final Logger log = LoggerFactory.getLogger(GLComputeHeadlessTest.class);
    
    protected long window;
    protected boolean glInitialized = false;
    protected static boolean gpuAvailable = false;
    private final List<Integer> allocatedBuffers = new ArrayList<>();
    private final List<Integer> allocatedPrograms = new ArrayList<>();
    private final List<Integer> allocatedShaders = new ArrayList<>();
    
    /**
     * GPU context information for debugging and validation.
     */
    public static class GLContext {
        public final String vendor;
        public final String renderer;
        public final String version;
        public final String glslVersion;
        public final int maxComputeWorkGroupSize[];
        public final int maxComputeWorkGroupInvocations;
        public final int maxSharedMemorySize;
        
        public GLContext() {
            this.vendor = glGetString(GL_VENDOR);
            this.renderer = glGetString(GL_RENDERER);
            this.version = glGetString(GL_VERSION);
            this.glslVersion = glGetString(GL_SHADING_LANGUAGE_VERSION);
            
            this.maxComputeWorkGroupSize = new int[3];
            int[] temp = new int[1];
            glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, temp);
            maxComputeWorkGroupSize[0] = temp[0];
            glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1, temp);
            maxComputeWorkGroupSize[1] = temp[0];
            glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, 2, temp);
            maxComputeWorkGroupSize[2] = temp[0];
            
            this.maxComputeWorkGroupInvocations = glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
            this.maxSharedMemorySize = glGetInteger(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE);
        }
        
        @Override
        public String toString() {
            return String.format(
                "OpenGL Context:\n" +
                "  Vendor: %s\n" +
                "  Renderer: %s\n" +
                "  Version: %s\n" +
                "  GLSL: %s\n" +
                "  Max Work Group Size: [%d, %d, %d]\n" +
                "  Max Work Group Invocations: %d\n" +
                "  Max Shared Memory: %d bytes",
                vendor, renderer, version, glslVersion,
                maxComputeWorkGroupSize[0], maxComputeWorkGroupSize[1], maxComputeWorkGroupSize[2],
                maxComputeWorkGroupInvocations, maxSharedMemorySize
            );
        }
    }
    
    @BeforeEach
    public void setupGL() {
        try {
            initializeOpenGL();
        } catch (Exception e) {
            log.warn("Failed to initialize OpenGL: {}", e.getMessage());
            gpuAvailable = false;
        }
    }
    
    @AfterEach
    public void teardownGL() {
        cleanupResources();
        
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        
        if (glInitialized) {
            glfwTerminate();
            glInitialized = false;
        }
    }
    
    private void initializeOpenGL() {
        // Configure for headless operation
        if (Platform.get() == Platform.MACOSX) {
            Configuration.GLFW_CHECK_THREAD0.set(false);
        }
        
        // Set error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        
        // Configure window hints for hidden window
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        
        if (Platform.get() == Platform.MACOSX) {
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        }
        
        // Create hidden window
        window = glfwCreateWindow(1, 1, "GLComputeTest", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Make context current
        glfwMakeContextCurrent(window);
        
        // Create capabilities
        GL.createCapabilities();
        
        // Verify compute shader support
        String version = glGetString(GL_VERSION);
        if (version == null || !supportsComputeShaders(version)) {
            throw new RuntimeException("OpenGL 4.3+ required for compute shaders");
        }
        
        glInitialized = true;
        gpuAvailable = true;
        
        // Log context info
        GLContext context = new GLContext();
        log.info("OpenGL initialized:\n{}", context);
    }
    
    private boolean supportsComputeShaders(String version) {
        try {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].split(" ")[0]) : 0;
            return major > 4 || (major == 4 && minor >= 3);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Compiles a compute shader from source.
     */
    protected int compileComputeShader(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        allocatedShaders.add(shader);
        
        glShaderSource(shader, source);
        glCompileShader(shader);
        
        // Check compilation
        int[] status = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, status);
        
        if (status[0] == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Compute shader compilation failed:\n" + log);
        }
        
        return shader;
    }
    
    /**
     * Creates a compute program from a shader.
     */
    protected int createComputeProgram(int computeShader) {
        int program = glCreateProgram();
        allocatedPrograms.add(program);
        
        glAttachShader(program, computeShader);
        glLinkProgram(program);
        
        // Check linking
        int[] status = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, status);
        
        if (status[0] == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new RuntimeException("Program linking failed:\n" + log);
        }
        
        return program;
    }
    
    /**
     * Creates an SSBO (Shader Storage Buffer Object).
     */
    protected int createSSBO(long size, int usage) {
        int buffer = glGenBuffers();
        allocatedBuffers.add(buffer);
        
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, size, usage);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        
        return buffer;
    }
    
    /**
     * Uploads data to an SSBO.
     */
    protected void uploadToSSBO(int buffer, java.nio.ByteBuffer data) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, data);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
    
    /**
     * Downloads data from an SSBO.
     */
    protected void downloadFromSSBO(int buffer, java.nio.ByteBuffer data) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, data);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
    
    /**
     * Dispatches a compute shader.
     */
    protected void dispatchCompute(int program, int workGroupsX, int workGroupsY, int workGroupsZ) {
        glUseProgram(program);
        glDispatchCompute(workGroupsX, workGroupsY, workGroupsZ);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glUseProgram(0);
    }
    
    /**
     * Binds an SSBO to a binding point.
     */
    protected void bindSSBO(int buffer, int bindingPoint) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, buffer);
    }
    
    /**
     * Waits for all GPU operations to complete.
     */
    protected void waitForGPU() {
        glFinish();
    }
    
    /**
     * Executes a test with GPU context if available.
     */
    protected void withGPU(Consumer<GLContext> test) {
        if (!gpuAvailable) {
            log.info("Skipping test - no GPU available");
            return;
        }
        
        GLContext context = new GLContext();
        test.accept(context);
    }
    
    /**
     * Checks if GPU is available for testing.
     */
    protected static boolean isGPUAvailable() {
        return gpuAvailable;
    }
    
    /**
     * Cleans up allocated OpenGL resources.
     */
    private void cleanupResources() {
        // Delete buffers
        for (int buffer : allocatedBuffers) {
            glDeleteBuffers(buffer);
        }
        allocatedBuffers.clear();
        
        // Delete programs
        for (int program : allocatedPrograms) {
            glDeleteProgram(program);
        }
        allocatedPrograms.clear();
        
        // Delete shaders
        for (int shader : allocatedShaders) {
            glDeleteShader(shader);
        }
        allocatedShaders.clear();
    }
    
    /**
     * Utility to check OpenGL errors.
     */
    protected void checkGLError(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new RuntimeException(String.format("OpenGL error during %s: 0x%x", operation, error));
        }
    }
    
    /**
     * Creates work group sizes optimized for the current GPU.
     */
    protected int[] calculateOptimalWorkGroups(int totalWork, GLContext context) {
        int maxWorkGroupSize = context.maxComputeWorkGroupSize[0];
        int workGroupSize = Math.min(64, maxWorkGroupSize); // Common optimal size
        
        int numWorkGroups = (totalWork + workGroupSize - 1) / workGroupSize;
        
        return new int[] { numWorkGroups, 1, 1 };
    }
    
    /**
     * Loads a compute shader from resources.
     */
    protected String loadComputeShader(String resourcePath) {
        try (var stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new RuntimeException("Shader not found: " + resourcePath);
            }
            return new String(stream.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + resourcePath, e);
        }
    }
    
    /**
     * Creates a timer query for performance measurement.
     */
    protected static class GPUTimer {
        private final int query;
        
        public GPUTimer() {
            query = glGenQueries();
        }
        
        public void start() {
            glBeginQuery(GL_TIME_ELAPSED, query);
        }
        
        public void stop() {
            glEndQuery(GL_TIME_ELAPSED);
        }
        
        public long getElapsedNanos() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer result = stack.mallocInt(1);
                glGetQueryObjectiv(query, GL_QUERY_RESULT, result);
                return result.get(0);
            }
        }
        
        public double getElapsedMillis() {
            return getElapsedNanos() / 1_000_000.0;
        }
        
        public void cleanup() {
            glDeleteQueries(query);
        }
    }
}