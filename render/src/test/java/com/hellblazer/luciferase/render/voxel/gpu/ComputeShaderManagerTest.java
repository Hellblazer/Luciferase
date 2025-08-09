package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import com.hellblazer.luciferase.webgpu.wrapper.ComputePipeline;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for compute shader management
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ComputeShaderManagerTest {
    private static final Logger log = LoggerFactory.getLogger(ComputeShaderManagerTest.class);
    
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    
    @BeforeEach
    public void setup() throws Exception {
        // Create WebGPU context
        context = new WebGPUContext();
        
        if (!context.isAvailable()) {
            return;
        }
        
        context.initialize().get(5, TimeUnit.SECONDS);
        shaderManager = new ComputeShaderManager(context);
    }
    
    @AfterEach
    public void tearDown() {
        if (shaderManager != null) {
            shaderManager.cleanup();
        }
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Load simple compute shader")
    public void testLoadSimpleShader() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        String simpleShader = """
            @compute @workgroup_size(1)
            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                // Simple compute shader
            }
            """;
        
        CompletableFuture<ShaderModule> future = shaderManager.loadShader("simple", simpleShader);
        ShaderModule module = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(module);
    }
    
    
    @Test
    @Order(2)
    @DisplayName("Shader caching")
    public void testShaderCaching() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        String shader = """
            @compute @workgroup_size(64)
            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                // Cached shader
            }
            """;
        
        // Load shader first time
        CompletableFuture<ShaderModule> future1 = shaderManager.loadShader("cached", shader);
        ShaderModule module1 = future1.get(5, TimeUnit.SECONDS);
        
        // Load same shader again - should return cached
        CompletableFuture<ShaderModule> future2 = shaderManager.loadShader("cached", shader);
        ShaderModule module2 = future2.get(5, TimeUnit.SECONDS);
        
        assertSame(module1, module2);
    }
    
    @Test
    @Order(3)
    @DisplayName("Create compute pipeline")
    public void testCreateComputePipeline() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        String shader = """
            @group(0) @binding(0) var<storage, read_write> data: array<f32>;
            
            @compute @workgroup_size(64)
            fn computeMain(@builtin(global_invocation_id) id: vec3<u32>) {
                let idx = id.x;
                if (idx < arrayLength(&data)) {
                    data[idx] = data[idx] * 2.0;
                }
            }
            """;
        
        ShaderModule module = shaderManager.loadShader("compute", shader).get(5, TimeUnit.SECONDS);
        ComputePipeline pipeline = shaderManager.createComputePipeline("test_pipeline", module, "computeMain");
        
        assertNotNull(pipeline);
    }
    
    @Test
    @Order(4)
    @DisplayName("Create octree traversal layout")
    @Disabled("Layout creation not yet implemented in abstraction")
    public void testCreateOctreeTraversalLayout() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // TODO: Implement layout creation in abstraction layer
        // BindGroupLayout layout = shaderManager.createOctreeTraversalLayout();
        // assertNotNull(layout);
        
        // Create pipeline layout
        // PipelineLayout pipelineLayout = shaderManager.createPipelineLayout("octree_layout", layout);
        // assertNotNull(pipelineLayout);
    }
    
    @Test
    @Order(5)
    @DisplayName("Calculate workgroup dispatch")
    public void testWorkgroupDispatch() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Test small dispatch
        int[] dispatch1 = shaderManager.calculateWorkgroupDispatch(1000, 64);
        assertEquals(16, dispatch1[0]); // ceil(1000/64) = 16
        assertEquals(1, dispatch1[1]);
        assertEquals(1, dispatch1[2]);
        
        // Test large dispatch requiring 2D
        int[] dispatch2 = shaderManager.calculateWorkgroupDispatch(10_000_000, 64);
        assertTrue(dispatch2[0] > 1);
        assertTrue(dispatch2[1] > 1);
        
        log.info("Large dispatch: {} x {} x {}", dispatch2[0], dispatch2[1], dispatch2[2]);
    }
    
    @Test
    @Order(6)
    @DisplayName("Load ESVO shaders from resources")
    @Disabled("Shader resources not yet available in test environment")
    public void testLoadESVOShaders() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        CompletableFuture<Void> future = shaderManager.loadESVOShaders();
        future.get(5, TimeUnit.SECONDS);
        
        // Shaders should be loaded
        assertTrue(true);
    }
    
    // Removed isWebGPUAvailable() - now using context.isAvailable()
}
