package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import com.hellblazer.luciferase.webgpu.wrapper.ComputePipeline;
import com.hellblazer.luciferase.webgpu.wrapper.Device;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebGPU context initialization and management
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WebGPUContextTest {
    private static final Logger log = LoggerFactory.getLogger(WebGPUContextTest.class);
    
    private WebGPUContext context;
    
    @BeforeEach
    public void setup() {
        // Create WebGPU context
        context = new WebGPUContext();
    }
    
    @AfterEach
    public void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("WebGPU context initialization")
    public void testInitialization() throws Exception {
        CompletableFuture<Void> initFuture = context.initialize();
        assertNotNull(initFuture);
        
        // Wait for initialization
        initFuture.get(5, TimeUnit.SECONDS);
        
        // Verify initialization
        assertTrue(context.isInitialized());
        assertEquals("WebGPU-FFM", context.getBackendName());
    }
    
    @Test
    @Order(2)
    @DisplayName("Buffer creation")
    public void testBufferCreation() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        Buffer buffer = context.createBuffer(1024, 0x10 | 0x08); // INDEX | COPY_DST
        assertNotNull(buffer);
        assertEquals(1024, buffer.getSize());
        
        buffer.close();
    }
    
    @Test
    @Order(3)
    @DisplayName("Shader creation")
    public void testShaderCreation() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        String wgslSource = "@compute @workgroup_size(1) fn main() {}";
        ShaderModule shader = context.createComputeShader(wgslSource);
        assertNotNull(shader);
        
        shader.close();
    }
    
    @Test
    @Order(4)
    @DisplayName("Buffer operations")
    public void testBufferOperations() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        // Test copy-based buffer reading with GPU-writable buffer
        Buffer gpuBuffer = context.createBuffer(256, 0x08 | 0x04 | 0x80); // COPY_DST | COPY_SRC | STORAGE
        byte[] testData = "Hello WebGPU".getBytes();
        
        // Write data to GPU buffer
        assertDoesNotThrow(() -> context.writeBuffer(gpuBuffer, testData, 0));
        
        // Read data using copy-based approach (this should work without mock fallbacks)
        byte[] readData = context.readBuffer(gpuBuffer, testData.length, 0);
        assertNotNull(readData);
        assertEquals(testData.length, readData.length);
        
        gpuBuffer.close();
    }
    
    @Test
    @Order(5)
    @DisplayName("Compute dispatch")
    public void testComputeDispatch() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        ShaderModule shader = context.createComputeShader("@compute @workgroup_size(1) fn main() {}");
        
        // Create a compute pipeline first
        var pipelineDesc = new Device.ComputePipelineDescriptor(shader)
            .withEntryPoint("main");
        ComputePipeline pipeline = context.getDevice().createComputePipeline(pipelineDesc);
        
        assertDoesNotThrow(() -> context.dispatchCompute(pipeline, 1, 1, 1));
        
        pipeline.close();
        shader.close();
    }
    
    @Test
    @Order(6)
    @DisplayName("Queue synchronization")
    public void testQueueSync() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        ShaderModule shader = context.createComputeShader("@compute @workgroup_size(1) fn main() {}");
        
        // Create a compute pipeline first
        var pipelineDesc = new Device.ComputePipelineDescriptor(shader)
            .withEntryPoint("main");
        ComputePipeline pipeline = context.getDevice().createComputePipeline(pipelineDesc);
        
        context.dispatchCompute(pipeline, 1, 1, 1);
        
        // Wait for completion
        CompletableFuture<Void> waitFuture = context.waitIdle();
        assertNotNull(waitFuture);
        
        waitFuture.get(5, TimeUnit.SECONDS);
        
        pipeline.close();
        shader.close();
    }
    
    @Test
    @Order(7)
    @DisplayName("Multiple initialization attempts")
    public void testMultipleInitialization() throws Exception {
        // First initialization
        CompletableFuture<Void> first = context.initialize();
        first.get(5, TimeUnit.SECONDS);
        assertTrue(context.isInitialized());
        
        // Second initialization should return immediately
        CompletableFuture<Void> second = context.initialize();
        assertTrue(second.isDone());
        assertFalse(second.isCompletedExceptionally());
    }
    
    @Test
    @Order(8)
    @DisplayName("Context shutdown")
    public void testShutdown() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        assertTrue(context.isInitialized());
        
        context.shutdown();
        assertFalse(context.isInitialized());
    }
    
    @Test
    @Order(9)
    @DisplayName("Operations on uninitialized context")
    public void testUninitializedOperations() {
        assertFalse(context.isInitialized());
        
        assertThrows(IllegalStateException.class, () -> context.createBuffer(1024, 0));
        assertThrows(IllegalStateException.class, () -> context.createComputeShader("test"));
    }
    
    @Test
    @Order(10)
    @DisplayName("Legacy compatibility methods")
    public void testLegacyCompatibility() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        // Test legacy buffer creation with proper GPU buffer flags
        Buffer buffer = context.createBuffer(512, 0x08 | 0x04 | 0x80); // COPY_DST | COPY_SRC | STORAGE
        assertNotNull(buffer);
        
        // Test legacy buffer operations
        byte[] data = "Legacy test!".getBytes(); // 12 bytes - aligned to 4
        assertDoesNotThrow(() -> context.writeBuffer((Object) buffer, 0, data));
        
        java.nio.ByteBuffer readBuffer = context.readBuffer((Object) buffer);
        assertNotNull(readBuffer);
        
        // Test legacy compute dispatch
        ShaderModule shader = context.createComputeShader("@compute @workgroup_size(1) fn main() {}");
        var pipelineDesc = new Device.ComputePipelineDescriptor(shader)
            .withEntryPoint("main");
        ComputePipeline pipeline = context.getDevice().createComputePipeline(pipelineDesc);
        assertDoesNotThrow(() -> context.dispatchCompute((Object) pipeline, 1, 1, 1));
        
        pipeline.close();
        shader.close();
        buffer.close();
    }
}