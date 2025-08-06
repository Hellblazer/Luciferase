package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.webgpu.*;
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
        // Use stub backend for reliable testing
        WebGPUBackend backend = WebGPUBackendFactory.createStubBackend();
        context = new WebGPUContext(backend);
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
        assertEquals("Stub WebGPU (Development)", context.getBackendName());
    }
    
    @Test
    @Order(2)
    @DisplayName("Buffer creation")
    public void testBufferCreation() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        BufferHandle buffer = context.createBuffer(1024, 0x10);
        assertNotNull(buffer);
        assertEquals(1024, buffer.getSize());
        assertEquals(0x10, buffer.getUsage());
        assertTrue(buffer.isValid());
        
        buffer.release();
        assertFalse(buffer.isValid());
    }
    
    @Test
    @Order(3)
    @DisplayName("Shader creation")
    public void testShaderCreation() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        String wgslSource = "@compute @workgroup_size(1) fn main() {}";
        ShaderHandle shader = context.createComputeShader(wgslSource);
        assertNotNull(shader);
        assertEquals(wgslSource, shader.getWgslSource());
        assertTrue(shader.isValid());
        
        shader.release();
        assertFalse(shader.isValid());
    }
    
    @Test
    @Order(4)
    @DisplayName("Buffer operations")
    public void testBufferOperations() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        BufferHandle buffer = context.createBuffer(256, 0x10);
        byte[] testData = "Hello WebGPU".getBytes();
        
        // Write data
        assertDoesNotThrow(() -> context.writeBuffer(buffer, testData, 0));
        
        // Read data
        byte[] readData = context.readBuffer(buffer, testData.length, 0);
        assertNotNull(readData);
        assertEquals(testData.length, readData.length);
        
        buffer.release();
    }
    
    @Test
    @Order(5)
    @DisplayName("Compute dispatch")
    public void testComputeDispatch() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        ShaderHandle shader = context.createComputeShader("@compute @workgroup_size(1) fn main() {}");
        
        assertDoesNotThrow(() -> context.dispatchCompute(shader, 1, 1, 1));
        
        shader.release();
    }
    
    @Test
    @Order(6)
    @DisplayName("Queue synchronization")
    public void testQueueSync() throws Exception {
        context.initialize().get(5, TimeUnit.SECONDS);
        
        ShaderHandle shader = context.createComputeShader("@compute @workgroup_size(1) fn main() {}");
        context.dispatchCompute(shader, 1, 1, 1);
        
        // Wait for completion
        CompletableFuture<Void> waitFuture = context.waitIdle();
        assertNotNull(waitFuture);
        
        waitFuture.get(5, TimeUnit.SECONDS);
        
        shader.release();
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
        
        // Test legacy buffer creation
        BufferHandle buffer = context.createBuffer(512, 0x20);
        assertNotNull(buffer);
        
        // Test legacy buffer operations
        byte[] data = "Legacy test".getBytes();
        assertDoesNotThrow(() -> context.writeBuffer((Object) buffer, 0, data));
        
        java.nio.ByteBuffer readBuffer = context.readBuffer((Object) buffer);
        assertNotNull(readBuffer);
        
        // Test legacy compute dispatch
        ShaderHandle shader = context.createComputeShader("test");
        assertDoesNotThrow(() -> context.dispatchCompute((Object) shader, 1, 1, 1));
        
        buffer.release();
        shader.release();
    }
}