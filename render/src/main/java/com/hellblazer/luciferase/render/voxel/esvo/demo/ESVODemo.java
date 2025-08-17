package com.hellblazer.luciferase.render.voxel.esvo.demo;

import com.hellblazer.luciferase.render.lwjgl.GLFWInitializer;
import com.hellblazer.luciferase.render.lwjgl.LWJGLRenderer;
import com.hellblazer.luciferase.render.lwjgl.Shader;
import com.hellblazer.luciferase.render.memory.GPUMemoryManager;
import com.hellblazer.luciferase.render.voxel.esvo.*;
import com.hellblazer.luciferase.render.voxel.esvo.gpu.ESVOGPUIntegration;
import com.hellblazer.luciferase.render.voxel.esvo.voxelization.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import java.nio.*;
import java.util.*;
import java.util.logging.Logger;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * ESVO Demo Application - Demonstrates GPU-accelerated voxel octree rendering.
 * This demo creates a voxelized scene, builds an ESVO octree, uploads it to GPU,
 * and renders it using ray-casting shaders.
 */
public class ESVODemo {
    
    private static final Logger log = Logger.getLogger(ESVODemo.class.getName());
    
    // Window settings
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final String WINDOW_TITLE = "ESVO GPU Demo - Luciferase Render";
    
    // Rendering components
    private long window;
    private Shader raycastShader;
    private GPUMemoryManager gpuMemoryManager;
    private ESVOGPUIntegration gpuIntegration;
    
    // Scene data
    private Octree octree;
    private int vaoFullscreenQuad;
    private int vboFullscreenQuad;
    
    // Camera
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projMatrix = new Matrix4f();
    private final Matrix4f invViewMatrix = new Matrix4f();
    private final Matrix4f invProjMatrix = new Matrix4f();
    private final Vector3f cameraPos = new Vector3f(3.0f, 3.0f, 3.0f);
    private final Vector3f cameraTarget = new Vector3f(0.0f, 0.0f, 0.0f);
    private final Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    
    // Input state
    private float cameraDistance = 5.0f;
    private float cameraYaw = 45.0f;
    private float cameraPitch = 30.0f;
    private boolean mousePressed = false;
    private double lastMouseX, lastMouseY;
    
    // Performance tracking
    private long frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();
    private int fps = 0;
    
    public void run() {
        log.info("Starting ESVO GPU Demo");
        
        init();
        loop();
        
        // Cleanup
        cleanup();
    }
    
    private void init() {
        // Use centralized GLFW initialization
        try {
            if (!GLFWInitializer.initialize()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }
        } catch (GLFWInitializer.GLFWInitializationException e) {
            log.severe("GLFW initialization failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
        
        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1); // macOS supports up to 4.1
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        
        // Create window
        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Setup callbacks
        setupCallbacks();
        
        // Center window
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            
            glfwGetWindowSize(window, pWidth, pHeight);
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            
            glfwSetWindowPos(
                window,
                (vidmode.width() - pWidth.get(0)) / 2,
                (vidmode.height() - pHeight.get(0)) / 2
            );
        }
        
        // Make OpenGL context current
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // Enable v-sync
        glfwShowWindow(window);
        
        // Initialize OpenGL
        GL.createCapabilities();
        
        log.info("OpenGL Version: " + glGetString(GL_VERSION));
        log.info("GLSL Version: " + glGetString(GL_SHADING_LANGUAGE_VERSION));
        
        // Setup OpenGL state
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glClearColor(0.1f, 0.1f, 0.2f, 1.0f);
        
        // Initialize components
        initializeGPUResources();
        createTestScene();
        createFullscreenQuad();
        loadShaders();
        
        // Upload octree to GPU
        uploadOctreeToGPU();
    }
    
    private void setupCallbacks() {
        // Key callback
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
            if (key == GLFW_KEY_R && action == GLFW_RELEASE) {
                reloadShaders();
            }
            if (key == GLFW_KEY_SPACE && action == GLFW_RELEASE) {
                log.info("FPS: " + fps + ", Nodes: " + octree.getNodeCount());
            }
        });
        
        // Mouse button callback
        glfwSetMouseButtonCallback(window, (window, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                mousePressed = action == GLFW_PRESS;
                if (mousePressed) {
                    glfwGetCursorPos(window, new double[]{lastMouseX}, new double[]{lastMouseY});
                }
            }
        });
        
        // Cursor position callback
        glfwSetCursorPosCallback(window, (window, xpos, ypos) -> {
            if (mousePressed) {
                float deltaX = (float)(xpos - lastMouseX);
                float deltaY = (float)(ypos - lastMouseY);
                
                cameraYaw += deltaX * 0.5f;
                cameraPitch = Math.max(-89.0f, Math.min(89.0f, cameraPitch - deltaY * 0.5f));
                
                lastMouseX = xpos;
                lastMouseY = ypos;
            }
        });
        
        // Scroll callback
        glfwSetScrollCallback(window, (window, xoffset, yoffset) -> {
            cameraDistance = Math.max(1.0f, Math.min(20.0f, cameraDistance - (float)yoffset * 0.5f));
        });
        
        // Window resize callback
        glfwSetFramebufferSizeCallback(window, (window, width, height) -> {
            glViewport(0, 0, width, height);
            updateProjectionMatrix(width, height);
        });
    }
    
    private void initializeGPUResources() {
        // Create GPU memory manager with 256MB pool
        gpuMemoryManager = new GPUMemoryManager(256 * 1024 * 1024, 16 * 1024 * 1024);
        
        // Create GPU integration
        gpuIntegration = new ESVOGPUIntegration(gpuMemoryManager);
    }
    
    private void createTestScene() {
        log.info("Creating test voxel scene");
        
        // Create a test mesh - simple sphere approximation
        List<float[]> vertices = new ArrayList<>();
        List<int[]> triangles = new ArrayList<>();
        
        // Generate sphere vertices
        int segments = 16;
        int rings = 12;
        
        for (int ring = 0; ring <= rings; ring++) {
            float phi = (float)(Math.PI * ring / rings);
            float y = (float)Math.cos(phi);
            float r = (float)Math.sin(phi);
            
            for (int seg = 0; seg <= segments; seg++) {
                float theta = (float)(2 * Math.PI * seg / segments);
                float x = r * (float)Math.cos(theta);
                float z = r * (float)Math.sin(theta);
                
                vertices.add(new float[]{x * 2, y * 2, z * 2});
            }
        }
        
        // Generate triangles
        for (int ring = 0; ring < rings; ring++) {
            for (int seg = 0; seg < segments; seg++) {
                int current = ring * (segments + 1) + seg;
                int next = current + segments + 1;
                
                triangles.add(new int[]{current, next, current + 1});
                triangles.add(new int[]{current + 1, next, next + 1});
            }
        }
        
        // Convert to arrays
        float[][] vertArray = vertices.toArray(new float[0][]);
        int[][] triArray = triangles.toArray(new int[0][]);
        
        // Create triangle mesh
        TriangleMesh mesh = new TriangleMesh(vertArray, triArray);
        
        // Voxelize the mesh
        TriangleVoxelizer voxelizer = new TriangleVoxelizer();
        VoxelizationConfig voxelConfig = new VoxelizationConfig()
            .withResolution(32)
            .withBounds(-3, -3, -3, 3, 3, 3)
            .withGenerateOctree(true)
            .withMaxOctreeDepth(5);
        
        VoxelizationResult result = voxelizer.voxelizeMesh(mesh, voxelConfig);
        
        // Get the octree
        octree = result.getOctree();
        
        log.info("Created octree with " + octree.getNodeCount() + " nodes, " +
                 octree.getLeafCount() + " leaves, depth " + octree.getMaxDepth());
    }
    
    private void createFullscreenQuad() {
        // Create VAO
        vaoFullscreenQuad = glGenVertexArrays();
        glBindVertexArray(vaoFullscreenQuad);
        
        // Create VBO with fullscreen quad vertices
        float[] vertices = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        };
        
        vboFullscreenQuad = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboFullscreenQuad);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        
        // Setup vertex attributes
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        
        glBindVertexArray(0);
    }
    
    private void loadShaders() {
        try {
            // Try to load shader files from resources
            String vertexPath = getClass().getResource("/shaders/esvo/voxel_raycast.vert").getFile();
            String fragmentPath = getClass().getResource("/shaders/esvo/voxel_raycast.frag").getFile();
            
            raycastShader = Shader.loadFromFiles(vertexPath, fragmentPath);
            log.info("Shaders loaded successfully");
        } catch (Exception e) {
            log.warning("Could not load shader files, using fallback: " + e.getMessage());
            // Fallback to simple shader
            createFallbackShader();
        }
    }
    
    private void createFallbackShader() {
        // Simple fallback shader that just renders a gradient
        String vertexSource = """
            #version 410 core
            layout(location = 0) in vec2 position;
            out vec2 texCoord;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                texCoord = position * 0.5 + 0.5;
            }
            """;
        
        String fragmentSource = """
            #version 410 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform float time;
            void main() {
                vec3 color = vec3(texCoord, sin(time) * 0.5 + 0.5);
                fragColor = vec4(color, 1.0);
            }
            """;
        
        raycastShader = new Shader(vertexSource, fragmentSource);
    }
    
    private void reloadShaders() {
        log.info("Reloading shaders...");
        if (raycastShader != null) {
            raycastShader.cleanup();
        }
        loadShaders();
    }
    
    private void uploadOctreeToGPU() {
        if (octree != null && gpuIntegration != null) {
            boolean success = gpuIntegration.uploadOctree(octree);
            if (success) {
                log.info("Octree uploaded to GPU: " + 
                        gpuIntegration.getTotalNodesUploaded() + " nodes in " +
                        gpuIntegration.getUploadTimeMs() + "ms");
            } else {
                log.severe("Failed to upload octree to GPU");
            }
        }
    }
    
    private void updateCamera() {
        // Calculate camera position from spherical coordinates
        float yawRad = (float)Math.toRadians(cameraYaw);
        float pitchRad = (float)Math.toRadians(cameraPitch);
        
        cameraPos.x = cameraDistance * (float)(Math.cos(pitchRad) * Math.sin(yawRad));
        cameraPos.y = cameraDistance * (float)Math.sin(pitchRad);
        cameraPos.z = cameraDistance * (float)(Math.cos(pitchRad) * Math.cos(yawRad));
        
        // Update view matrix
        viewMatrix.lookAt(cameraPos, cameraTarget, cameraUp);
        viewMatrix.invert(invViewMatrix);
    }
    
    private void updateProjectionMatrix(int width, int height) {
        float aspect = (float)width / height;
        projMatrix.setPerspective((float)Math.toRadians(60.0f), aspect, 0.1f, 100.0f);
        projMatrix.invert(invProjMatrix);
    }
    
    private void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        // Update camera
        updateCamera();
        
        // Use raycast shader
        raycastShader.use();
        
        // Set uniforms
        float[] viewMatrixArray = new float[16];
        float[] projMatrixArray = new float[16];
        float[] invViewMatrixArray = new float[16];
        float[] invProjMatrixArray = new float[16];
        
        viewMatrix.get(viewMatrixArray);
        projMatrix.get(projMatrixArray);
        invViewMatrix.get(invViewMatrixArray);
        invProjMatrix.get(invProjMatrixArray);
        
        raycastShader.setMat4("viewMatrix", viewMatrixArray);
        raycastShader.setMat4("projMatrix", projMatrixArray);
        raycastShader.setMat4("invViewMatrix", invViewMatrixArray);
        raycastShader.setMat4("invProjMatrix", invProjMatrixArray);
        raycastShader.setVec3("cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);
        raycastShader.setFloat("time", (float)glfwGetTime());
        
        // Bind GPU buffers
        if (gpuIntegration != null && gpuIntegration.isUploaded()) {
            gpuIntegration.bindForRendering();
        }
        
        // Render fullscreen quad
        glBindVertexArray(vaoFullscreenQuad);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        
        // Update FPS counter
        updateFPS();
    }
    
    private void updateFPS() {
        frameCount++;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFpsTime >= 1000) {
            fps = (int)(frameCount * 1000 / (currentTime - lastFpsTime));
            frameCount = 0;
            lastFpsTime = currentTime;
            
            glfwSetWindowTitle(window, WINDOW_TITLE + " - FPS: " + fps);
        }
    }
    
    private void loop() {
        // Initial projection matrix
        updateProjectionMatrix(WINDOW_WIDTH, WINDOW_HEIGHT);
        
        while (!glfwWindowShouldClose(window)) {
            render();
            
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }
    
    private void cleanup() {
        log.info("Cleaning up resources");
        
        // Delete OpenGL resources
        if (raycastShader != null) {
            raycastShader.cleanup();
        }
        glDeleteVertexArrays(vaoFullscreenQuad);
        glDeleteBuffers(vboFullscreenQuad);
        
        // Release GPU resources
        if (gpuIntegration != null) {
            gpuIntegration.release();
        }
        
        // Cleanup GLFW
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        
        // Use centralized termination
        GLFWInitializer.terminate();
    }
    
    public static void main(String[] args) {
        new ESVODemo().run();
    }
}