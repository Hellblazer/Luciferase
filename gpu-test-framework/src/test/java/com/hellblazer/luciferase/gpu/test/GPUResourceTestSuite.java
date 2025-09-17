package com.hellblazer.luciferase.gpu.test;

import com.hellblazer.luciferase.resource.CompositeResourceManager;
import com.hellblazer.luciferase.resource.GPUResource;
import com.hellblazer.luciferase.resource.GPUResourceType;
import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL46;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Comprehensive test suite for GPU resource management.
 * Tests buffer allocation, texture creation, shader compilation,
 * and concurrent GPU resource operations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GPUResourceTestSuite {

    private static long window;
    private UnifiedResourceManager resourceManager;
    private final List<Integer> allocatedBuffers = new ArrayList<>();
    private final List<Integer> allocatedTextures = new ArrayList<>();
    private final List<Integer> allocatedShaders = new ArrayList<>();
    private final List<Integer> allocatedPrograms = new ArrayList<>();
    private final List<Integer> allocatedVAOs = new ArrayList<>();

    @BeforeAll
    static void initializeOpenGL() {
        // Initialize GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        // Create window
        window = glfwCreateWindow(1, 1, "GPU Resource Test", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Make context current
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll
    static void terminateOpenGL() {
        if (window != NULL) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
    }

    @BeforeEach
    void setUp() {
        resourceManager = UnifiedResourceManager.getInstance();
        resourceManager.reset(); // Clear any previous state
    }

    @AfterEach
    void tearDown() {
        // Clean up OpenGL resources
        if (!allocatedBuffers.isEmpty()) {
            for (int buffer : allocatedBuffers) {
                glDeleteBuffers(buffer);
            }
            allocatedBuffers.clear();
        }

        if (!allocatedTextures.isEmpty()) {
            for (int texture : allocatedTextures) {
                glDeleteTextures(texture);
            }
            allocatedTextures.clear();
        }

        if (!allocatedShaders.isEmpty()) {
            for (int shader : allocatedShaders) {
                glDeleteShader(shader);
            }
            allocatedShaders.clear();
        }

        if (!allocatedPrograms.isEmpty()) {
            for (int program : allocatedPrograms) {
                glDeleteProgram(program);
            }
            allocatedPrograms.clear();
        }

        if (!allocatedVAOs.isEmpty()) {
            for (int vao : allocatedVAOs) {
                glDeleteVertexArrays(vao);
            }
            allocatedVAOs.clear();
        }

        // Reset resource manager
        resourceManager.reset();
    }

    @Test
    @Order(1)
    @DisplayName("Test GPU buffer allocation and deallocation")
    void testGPUBufferAllocation() {
        // Track initial memory
        long initialMemory = resourceManager.getTotalMemoryAllocated();

        // Allocate vertex buffer
        int vbo = glGenBuffers();
        allocatedBuffers.add(vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Allocate data
        int bufferSize = 1024 * 1024; // 1MB
        ByteBuffer data = resourceManager.allocateMemory(bufferSize);
        assertNotNull(data);

        // Fill with test data
        for (int i = 0; i < bufferSize / 4; i++) {
            data.putFloat(i * 0.1f);
        }
        data.flip();

        // Upload to GPU
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);

        // Verify GPU allocation succeeded
        int actualSize = glGetBufferParameteri(GL_ARRAY_BUFFER, GL_BUFFER_SIZE);
        assertEquals(bufferSize, actualSize);

        // Track as GPU resource
        GPUBufferResource bufferResource = new GPUBufferResource(vbo, bufferSize);
        ResourceHandle<GPUBufferResource> handle = resourceManager.add(bufferResource, null);

        // Verify memory tracking
        assertTrue(resourceManager.getTotalMemoryAllocated() > initialMemory);

        // Clean up
        resourceManager.releaseMemory(data);
        handle.close();

        // Verify buffer still exists (manual cleanup in tearDown)
        assertTrue(glIsBuffer(vbo));
    }

    @Test
    @Order(2)
    @DisplayName("Test texture resource management")
    void testTextureResourceManagement() {
        // Create texture
        int textureId = glGenTextures();
        allocatedTextures.add(textureId);
        glBindTexture(GL_TEXTURE_2D, textureId);

        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        // Allocate texture data
        int width = 512, height = 512;
        int dataSize = width * height * 4; // RGBA
        ByteBuffer textureData = resourceManager.allocateMemory(dataSize);

        // Fill with test pattern
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                textureData.put(offset, (byte) (x % 256));     // R
                textureData.put(offset + 1, (byte) (y % 256)); // G
                textureData.put(offset + 2, (byte) 128);       // B
                textureData.put(offset + 3, (byte) 255);       // A
            }
        }

        // Upload to GPU
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, textureData);

        // Track as GPU resource
        GPUTextureResource textureResource = new GPUTextureResource(textureId, width, height, dataSize);
        ResourceHandle<GPUTextureResource> handle = resourceManager.add(textureResource, null);

        // Verify texture is valid
        assertTrue(glIsTexture(textureId));

        // Clean up
        resourceManager.releaseMemory(textureData);
        handle.close();
    }

    @Test
    @Order(3)
    @DisplayName("Test shader compilation and program linking")
    void testShaderProgramManagement() {
        String vertexShaderSource = """
            #version 460 core
            layout (location = 0) in vec3 aPos;
            void main() {
                gl_Position = vec4(aPos, 1.0);
            }
            """;

        String fragmentShaderSource = """
            #version 460 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(1.0, 0.5, 0.2, 1.0);
            }
            """;

        // Compile vertex shader
        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
        allocatedShaders.add(vertexShader);

        // Compile fragment shader
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource);
        allocatedShaders.add(fragmentShader);

        // Link program
        int shaderProgram = glCreateProgram();
        allocatedPrograms.add(shaderProgram);
        glAttachShader(shaderProgram, vertexShader);
        glAttachShader(shaderProgram, fragmentShader);
        glLinkProgram(shaderProgram);

        // Check linking status
        int linkStatus = glGetProgrami(shaderProgram, GL_LINK_STATUS);
        assertEquals(GL_TRUE, linkStatus);

        // Track as GPU resource
        GPUProgramResource programResource = new GPUProgramResource(shaderProgram, vertexShader, fragmentShader);
        ResourceHandle<GPUProgramResource> handle = resourceManager.add(programResource, null);

        // Verify program is valid
        assertTrue(glIsProgram(shaderProgram));

        // Clean up
        handle.close();
    }

    @Test
    @Order(4)
    @DisplayName("Test VAO and multi-buffer setup")
    void testVAOAndMultiBufferSetup() {
        // Create VAO
        int vao = glGenVertexArrays();
        allocatedVAOs.add(vao);
        glBindVertexArray(vao);

        // Create VBO for positions
        int vboPos = glGenBuffers();
        allocatedBuffers.add(vboPos);
        glBindBuffer(GL_ARRAY_BUFFER, vboPos);

        FloatBuffer positions = memAllocFloat(9);
        positions.put(new float[] {
            -0.5f, -0.5f, 0.0f,
             0.5f, -0.5f, 0.0f,
             0.0f,  0.5f, 0.0f
        }).flip();
        glBufferData(GL_ARRAY_BUFFER, positions, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        // Create VBO for colors
        int vboColor = glGenBuffers();
        allocatedBuffers.add(vboColor);
        glBindBuffer(GL_ARRAY_BUFFER, vboColor);

        FloatBuffer colors = memAllocFloat(9);
        colors.put(new float[] {
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        }).flip();
        glBufferData(GL_ARRAY_BUFFER, colors, GL_STATIC_DRAW);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(1);

        // Create EBO
        int ebo = glGenBuffers();
        allocatedBuffers.add(ebo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);

        IntBuffer indices = memAllocInt(3);
        indices.put(new int[] { 0, 1, 2 }).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        // Track as composite GPU resource
        GPUMeshResource meshResource = new GPUMeshResource(vao, vboPos, vboColor, ebo);
        ResourceHandle<GPUMeshResource> handle = resourceManager.add(meshResource, null);

        // Verify setup
        assertTrue(glIsVertexArray(vao));
        assertTrue(glIsBuffer(vboPos));
        assertTrue(glIsBuffer(vboColor));
        assertTrue(glIsBuffer(ebo));

        // Clean up CPU memory
        memFree(positions);
        memFree(colors);
        memFree(indices);

        handle.close();
    }

    @Test
    @Order(5)
    @DisplayName("Test concurrent GPU resource allocation")
    void testConcurrentGPUResourceAllocation() throws InterruptedException {
        int numThreads = 4;
        int resourcesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        ConcurrentLinkedQueue<ResourceHandle<?>> handles = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < resourcesPerThread; i++) {
                        // Allocate memory buffer
                        int size = 1024 * (i + 1);
                        ByteBuffer buffer = resourceManager.allocateMemory(size);
                        assertNotNull(buffer);

                        // Create mock GPU resource
                        MockGPUResource resource = new MockGPUResource(threadId * 100 + i, size);
                        ResourceHandle<MockGPUResource> handle = resourceManager.add(resource, null);
                        handles.add(handle);

                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Verify all allocations succeeded
        assertEquals(numThreads * resourcesPerThread, successCount.get());

        // Clean up all handles
        for (ResourceHandle<?> handle : handles) {
            handle.close();
        }
    }

    @Test
    @Order(6)
    @DisplayName("Test memory pressure and buffer recycling")
    void testMemoryPressureAndRecycling() {
        List<ByteBuffer> buffers = new ArrayList<>();
        List<ResourceHandle<?>> handles = new ArrayList<>();

        // Phase 1: Allocate buffers until we reach a limit
        long maxMemory = 100 * 1024 * 1024; // 100MB limit
        long allocated = 0;
        int bufferSize = 1024 * 1024; // 1MB each

        while (allocated < maxMemory) {
            ByteBuffer buffer = resourceManager.allocateMemory(bufferSize);
            buffers.add(buffer);

            MockGPUResource resource = new MockGPUResource(buffers.size(), bufferSize);
            handles.add(resourceManager.add(resource, null));

            allocated += bufferSize;
        }

        long peakMemory = resourceManager.getTotalMemoryAllocated();
        assertTrue(peakMemory >= maxMemory);

        // Phase 2: Release half the buffers
        int halfSize = buffers.size() / 2;
        for (int i = 0; i < halfSize; i++) {
            resourceManager.releaseMemory(buffers.get(i));
            handles.get(i).close();
        }

        // Phase 3: Allocate new buffers (should reuse released memory)
        List<ByteBuffer> newBuffers = new ArrayList<>();
        for (int i = 0; i < halfSize; i++) {
            ByteBuffer buffer = resourceManager.allocateMemory(bufferSize);
            newBuffers.add(buffer);
        }

        // Verify memory didn't grow significantly
        long currentMemory = resourceManager.getTotalMemoryAllocated();
        assertTrue(currentMemory <= peakMemory * 1.1); // Allow 10% variance

        // Clean up
        for (int i = halfSize; i < buffers.size(); i++) {
            resourceManager.releaseMemory(buffers.get(i));
            handles.get(i).close();
        }
        for (ByteBuffer buffer : newBuffers) {
            resourceManager.releaseMemory(buffer);
        }
    }

    @Test
    @Order(7)
    @DisplayName("Test GPU resource leak detection")
    void testGPUResourceLeakDetection() {
        // Enable leak detection
        resourceManager.setDebugMode(true);

        // Create resources without proper cleanup
        List<ResourceHandle<?>> leakedHandles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MockGPUResource resource = new MockGPUResource(i, 1024);
            ResourceHandle<MockGPUResource> handle = resourceManager.add(resource, null);
            leakedHandles.add(handle);
        }

        // Get initial active count
        int activeCount = resourceManager.getActiveResourceCount();
        assertEquals(5, activeCount);

        // Properly clean up some resources
        for (int i = 0; i < 3; i++) {
            leakedHandles.get(i).close();
        }

        // Verify leak detection
        activeCount = resourceManager.getActiveResourceCount();
        assertEquals(2, activeCount); // 2 resources still leaked

        // Clean up remaining
        for (int i = 3; i < 5; i++) {
            leakedHandles.get(i).close();
        }

        assertEquals(0, resourceManager.getActiveResourceCount());
    }

    @Test
    @Order(8)
    @DisplayName("Test transactional GPU resource allocation")
    void testTransactionalGPUAllocation() {
        CompositeResourceManager composite = new CompositeResourceManager();

        try {
            // Allocate multiple resources as a transaction
            int buffer1 = glGenBuffers();
            var handle1 = resourceManager.add(new GLBufferResource(buffer1), null);
            composite.add(handle1);
            allocatedBuffers.add(buffer1);

            int buffer2 = glGenBuffers();
            var handle2 = resourceManager.add(new GLBufferResource(buffer2), null);
            composite.add(handle2);
            allocatedBuffers.add(buffer2);

            int texture = glGenTextures();
            var handle3 = resourceManager.add(new GLTextureResource(texture), null);
            composite.add(handle3);
            allocatedTextures.add(texture);

            // Verify all resources are valid
            assertTrue(glIsBuffer(buffer1));
            assertTrue(glIsBuffer(buffer2));
            assertTrue(glIsTexture(texture));

            // Simulate failure - rollback should happen in finally
        } finally {
            composite.close();
        }

        // Resources should still exist (manual cleanup in tearDown)
    }

    // Helper method to compile shaders
    private int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        int compileStatus = glGetShaderi(shader, GL_COMPILE_STATUS);
        if (compileStatus != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            throw new RuntimeException("Shader compilation failed: " + log);
        }

        return shader;
    }

    // Mock GPU resource classes for testing
    static class MockGPUResource implements GPUResource {
        private final int id;
        private final long size;
        private boolean closed = false;
        private final long creationTime = System.currentTimeMillis();

        MockGPUResource(int id, long size) {
            this.id = id;
            this.size = size;
        }

        @Override
        public GPUResourceType getType() {
            return GPUResourceType.BUFFER;
        }

        @Override
        public long getSizeBytes() {
            return size;
        }

        @Override
        public boolean isValid() {
            return !closed;
        }

        @Override
        public String getId() {
            return "mock-" + id;
        }

        @Override
        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }

        @Override
        public ResourceStatistics getStatistics() {
            return new ResourceStatistics(size, size, 1, System.currentTimeMillis(), 100.0);
        }

        @Override
        public Object getNativeHandle() {
            return id;
        }

        @Override
        public String getDescription() {
            return "Mock GPU Resource " + id;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static class GPUBufferResource implements GPUResource {
        private final int bufferId;
        private final long size;
        private boolean closed = false;
        private final long creationTime = System.currentTimeMillis();

        GPUBufferResource(int bufferId, long size) {
            this.bufferId = bufferId;
            this.size = size;
        }

        @Override
        public GPUResourceType getType() {
            return GPUResourceType.BUFFER;
        }

        @Override
        public long getSizeBytes() {
            return size;
        }

        @Override
        public boolean isValid() {
            return !closed && glIsBuffer(bufferId);
        }

        @Override
        public String getId() {
            return "buffer-" + bufferId;
        }

        @Override
        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }

        @Override
        public ResourceStatistics getStatistics() {
            return new ResourceStatistics(size, size, 1, System.currentTimeMillis(), 100.0);
        }

        @Override
        public Object getNativeHandle() {
            return bufferId;
        }

        @Override
        public String getDescription() {
            return "GPU Buffer " + bufferId;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (glIsBuffer(bufferId)) {
                    glDeleteBuffers(bufferId);
                }
            }
        }
    }

    static class GPUTextureResource implements GPUResource {
        private final int textureId;
        private final int width, height;
        private final long size;
        private boolean closed = false;
        private final long creationTime = System.currentTimeMillis();

        GPUTextureResource(int textureId, int width, int height, long size) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.size = size;
        }

        @Override
        public GPUResourceType getType() {
            return GPUResourceType.TEXTURE_2D;
        }

        @Override
        public long getSizeBytes() {
            return size;
        }

        @Override
        public boolean isValid() {
            return !closed && glIsTexture(textureId);
        }

        @Override
        public String getId() {
            return "texture-" + textureId;
        }

        @Override
        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }

        @Override
        public ResourceStatistics getStatistics() {
            return new ResourceStatistics(size, size, 1, System.currentTimeMillis(), 100.0);
        }

        @Override
        public Object getNativeHandle() {
            return textureId;
        }

        @Override
        public String getDescription() {
            return "GPU Texture " + textureId + " (" + width + "x" + height + ")";
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (glIsTexture(textureId)) {
                    glDeleteTextures(textureId);
                }
            }
        }
    }

    static class GPUProgramResource implements GPUResource {
        private final int programId;
        private final int vertexShader;
        private final int fragmentShader;
        private boolean closed = false;
        private final long creationTime = System.currentTimeMillis();

        GPUProgramResource(int programId, int vertexShader, int fragmentShader) {
            this.programId = programId;
            this.vertexShader = vertexShader;
            this.fragmentShader = fragmentShader;
        }

        @Override
        public GPUResourceType getType() {
            return GPUResourceType.PROGRAM;
        }

        @Override
        public long getSizeBytes() {
            return 0; // Shaders don't use direct memory
        }

        @Override
        public boolean isValid() {
            return !closed && glIsProgram(programId);
        }

        @Override
        public String getId() {
            return "program-" + programId;
        }

        @Override
        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }

        @Override
        public ResourceStatistics getStatistics() {
            return new ResourceStatistics(0, 0, 1, System.currentTimeMillis(), 100.0);
        }

        @Override
        public Object getNativeHandle() {
            return programId;
        }

        @Override
        public String getDescription() {
            return "GPU Program " + programId;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (glIsProgram(programId)) {
                    glDeleteProgram(programId);
                }
                if (glIsShader(vertexShader)) {
                    glDeleteShader(vertexShader);
                }
                if (glIsShader(fragmentShader)) {
                    glDeleteShader(fragmentShader);
                }
            }
        }
    }

    static class GPUMeshResource implements GPUResource {
        private final int vao;
        private final int vboPos;
        private final int vboColor;
        private final int ebo;
        private boolean closed = false;
        private final long creationTime = System.currentTimeMillis();

        GPUMeshResource(int vao, int vboPos, int vboColor, int ebo) {
            this.vao = vao;
            this.vboPos = vboPos;
            this.vboColor = vboColor;
            this.ebo = ebo;
        }

        @Override
        public GPUResourceType getType() {
            return GPUResourceType.MESH;
        }

        @Override
        public long getSizeBytes() {
            return 0; // Would calculate actual buffer sizes in production
        }

        @Override
        public boolean isValid() {
            return !closed && glIsVertexArray(vao);
        }

        @Override
        public String getId() {
            return "mesh-" + vao;
        }

        @Override
        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }

        @Override
        public ResourceStatistics getStatistics() {
            return new ResourceStatistics(0, 0, 1, System.currentTimeMillis(), 100.0);
        }

        @Override
        public Object getNativeHandle() {
            return vao;
        }

        @Override
        public String getDescription() {
            return "GPU Mesh " + vao;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (glIsVertexArray(vao)) {
                    glDeleteVertexArrays(vao);
                }
                if (glIsBuffer(vboPos)) {
                    glDeleteBuffers(vboPos);
                }
                if (glIsBuffer(vboColor)) {
                    glDeleteBuffers(vboColor);
                }
                if (glIsBuffer(ebo)) {
                    glDeleteBuffers(ebo);
                }
            }
        }
    }

    static class GLBufferResource implements GPUResource {
        private final int bufferId;
        private boolean closed = false;
        private final long creationTime = System.currentTimeMillis();

        GLBufferResource(int bufferId) {
            this.bufferId = bufferId;
        }

        @Override
        public GPUResourceType getType() {
            return GPUResourceType.BUFFER;
        }

        @Override
        public long getSizeBytes() {
            return 0;
        }

        @Override
        public boolean isValid() {
            return !closed;
        }

        @Override
        public String getId() {
            return "gl-buffer-" + bufferId;
        }

        @Override
        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }

        @Override
        public ResourceStatistics getStatistics() {
            return new ResourceStatistics(0, 0, 1, System.currentTimeMillis(), 100.0);
        }

        @Override
        public Object getNativeHandle() {
            return bufferId;
        }

        @Override
        public String getDescription() {
            return "GL Buffer " + bufferId;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            // Cleanup handled externally for this test
            closed = true;
        }
    }

    static class GLTextureResource implements GPUResource {
        private final int textureId;
        private boolean closed = false;
        private final long creationTime = System.currentTimeMillis();

        GLTextureResource(int textureId) {
            this.textureId = textureId;
        }

        @Override
        public GPUResourceType getType() {
            return GPUResourceType.TEXTURE_2D;
        }

        @Override
        public long getSizeBytes() {
            return 0;
        }

        @Override
        public boolean isValid() {
            return !closed;
        }

        @Override
        public String getId() {
            return "gl-texture-" + textureId;
        }

        @Override
        public long getAgeMillis() {
            return System.currentTimeMillis() - creationTime;
        }

        @Override
        public ResourceStatistics getStatistics() {
            return new ResourceStatistics(0, 0, 1, System.currentTimeMillis(), 100.0);
        }

        @Override
        public Object getNativeHandle() {
            return textureId;
        }

        @Override
        public String getDescription() {
            return "GL Texture " + textureId;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            // Cleanup handled externally for this test
            closed = true;
        }
    }
}