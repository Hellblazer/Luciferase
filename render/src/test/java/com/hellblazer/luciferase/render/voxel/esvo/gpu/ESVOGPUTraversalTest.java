package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import com.hellblazer.luciferase.render.voxel.esvo.ESVONode;
import com.hellblazer.luciferase.render.voxel.esvo.ESVOPage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIf;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ESVO GPU Traversal Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisabledIf("lacksOpenGLSupport")
class ESVOGPUTraversalTest {
    
    static boolean lacksOpenGLSupport() {
        // On macOS, need -XstartOnFirstThread
        if (Platform.get() == Platform.MACOSX) {
            String prop = System.getProperty("org.lwjgl.util.NoChecks");
            if (!"true".equals(prop)) {
                System.out.println("Skipping GPU tests - need -XstartOnFirstThread on macOS");
                return true;
            }
        }
        return false;
    }
    
    private long window;
    private ESVOGPUTraversal gpuTraversal;
    
    @BeforeAll
    void setupOpenGL() {
        // Initialize GLFW
        if (!GLFW.glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        
        // Configure window for headless OpenGL context
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        
        // Create window
        window = GLFW.glfwCreateWindow(1, 1, "Test", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to create window");
        }
        
        // Make context current
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }
    
    @AfterAll
    void teardownOpenGL() {
        if (gpuTraversal != null) {
            gpuTraversal.dispose();
        }
        if (window != MemoryUtil.NULL) {
            GLFW.glfwDestroyWindow(window);
        }
        GLFW.glfwTerminate();
    }
    
    @Test
    @DisplayName("Should initialize GPU traversal system")
    void testInitialization() {
        gpuTraversal = new ESVOGPUTraversal(64, 64);
        assertNotNull(gpuTraversal);
    }
    
    @Test
    @DisplayName("Should upload node data to GPU")
    void testNodeUpload() {
        gpuTraversal = new ESVOGPUTraversal(32, 32);
        
        // Create test nodes
        List<ESVONode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var node = new ESVONode();
            node.setValidMask((byte)0xFF);
            node.setNonLeafMask((byte)0x00);
            nodes.add(node);
        }
        
        // Should not throw
        assertDoesNotThrow(() -> gpuTraversal.uploadNodes(nodes));
    }
    
    @Test
    @DisplayName("Should upload page data to GPU")
    void testPageUpload() {
        gpuTraversal = new ESVOGPUTraversal(32, 32);
        
        // Create test pages with arena
        List<ESVOPage> pages = new ArrayList<>();
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            for (int i = 0; i < 5; i++) {
                pages.add(new ESVOPage(arena));
            }
            
            // Should not throw
            assertDoesNotThrow(() -> gpuTraversal.uploadPages(pages));
        }
    }
    
    @Test
    @DisplayName("Should set ray data")
    void testRayData() {
        int width = 16;
        int height = 16;
        gpuTraversal = new ESVOGPUTraversal(width, height);
        
        // Create ray data
        float[] origins = new float[width * height * 4];
        float[] directions = new float[width * height * 4];
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 4;
                
                // Origin at camera
                origins[idx] = 0;
                origins[idx + 1] = 0;
                origins[idx + 2] = -5;
                origins[idx + 3] = 1;
                
                // Direction toward scene
                float u = (x - width / 2.0f) / width;
                float v = (y - height / 2.0f) / height;
                directions[idx] = u;
                directions[idx + 1] = v;
                directions[idx + 2] = 1;
                directions[idx + 3] = 0;
            }
        }
        
        assertDoesNotThrow(() -> gpuTraversal.setRays(origins, directions));
    }
    
    @Test
    @DisplayName("Should set octree bounds")
    void testOctreeBounds() {
        gpuTraversal = new ESVOGPUTraversal(32, 32);
        
        assertDoesNotThrow(() -> 
            gpuTraversal.setOctreeBounds(-1, -1, -1, 1, 1, 1)
        );
    }
    
    @Test
    @DisplayName("Should execute traversal")
    void testTraversal() {
        int width = 8;
        int height = 8;
        gpuTraversal = new ESVOGPUTraversal(width, height);
        
        // Setup simple octree
        List<ESVONode> nodes = new ArrayList<>();
        var root = new ESVONode();
        root.setValidMask((byte)0xFF);
        root.setNonLeafMask((byte)0x00); // All leaves
        nodes.add(root);
        
        gpuTraversal.uploadNodes(nodes);
        gpuTraversal.setOctreeBounds(-1, -1, -1, 1, 1, 1);
        
        // Setup rays
        float[] origins = new float[width * height * 4];
        float[] directions = new float[width * height * 4];
        
        for (int i = 0; i < width * height; i++) {
            int idx = i * 4;
            origins[idx] = 0;
            origins[idx + 1] = 0;
            origins[idx + 2] = -2;
            origins[idx + 3] = 1;
            
            directions[idx] = 0;
            directions[idx + 1] = 0;
            directions[idx + 2] = 1;
            directions[idx + 3] = 0;
        }
        
        gpuTraversal.setRays(origins, directions);
        
        // Execute traversal
        assertDoesNotThrow(() -> gpuTraversal.traverse(0));
        
        // Get results
        float[] results = gpuTraversal.getHitResults();
        assertNotNull(results);
        assertEquals(width * height * 4, results.length);
    }
    
    @Test
    @DisplayName("Should handle invalid ray data size")
    void testInvalidRayData() {
        gpuTraversal = new ESVOGPUTraversal(10, 10);
        
        float[] wrongSize = new float[50]; // Should be 10*10*4 = 400
        
        assertThrows(IllegalArgumentException.class, () ->
            gpuTraversal.setRays(wrongSize, wrongSize)
        );
    }
}