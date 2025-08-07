package com.hellblazer.luciferase.render.webgpu;

import org.junit.jupiter.api.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for WebGPU backend abstraction layer.
 */
class WebGPUBackendTest {
    
    private WebGPUBackend backend;
    
    @BeforeEach
    void setUp() {
        // Use stub backend for reliable testing
        backend = WebGPUBackendFactory.createStubBackend();
    }
    
    @AfterEach
    void tearDown() {
        if (backend != null && backend.isInitialized()) {
            backend.shutdown();
        }
    }
    
    @Test
    void testBackendFactory() {
        // Test factory methods
        var stubBackend = WebGPUBackendFactory.createStubBackend();
        assertTrue(stubBackend.isAvailable());
        assertEquals("Stub WebGPU (Development)", stubBackend.getBackendName());
        
        var ffmBackend = WebGPUBackendFactory.createFFMBackend();
        assertEquals("FFM WebGPU Backend (using wrapper API)", ffmBackend.getBackendName());
        
        var autoBackend = WebGPUBackendFactory.createBackend();
        assertNotNull(autoBackend);
    }
    
    @Test
    void testInitialization() throws Exception {
        assertFalse(backend.isInitialized());
        
        var initFuture = backend.initialize();
        assertTrue(initFuture.get(5, TimeUnit.SECONDS));
        assertTrue(backend.isInitialized());
        
        // Double initialization should work
        var initFuture2 = backend.initialize();
        assertTrue(initFuture2.get(1, TimeUnit.SECONDS));
    }
    
    @Test
    void testShutdown() throws Exception {
        backend.initialize().get(5, TimeUnit.SECONDS);
        assertTrue(backend.isInitialized());
        
        backend.shutdown();
        assertFalse(backend.isInitialized());
    }
    
    @Test
    void testBufferOperations() throws Exception {
        backend.initialize().get(5, TimeUnit.SECONDS);
        
        var buffer = backend.createBuffer(1024, 0x10 | 0x40); // STORAGE | COPY_DST
        assertNotNull(buffer);
        assertEquals(1024, buffer.getSize());
        assertEquals(0x10 | 0x40, buffer.getUsage());
        assertTrue(buffer.isValid());
        
        // Test buffer write/read
        var testData = "Hello WebGPU!".getBytes();
        backend.writeBuffer(buffer, testData, 0);
        
        var readData = backend.readBuffer(buffer, testData.length, 0);
        assertArrayEquals(testData, readData);
        
        // Test buffer release
        buffer.release();
        assertFalse(buffer.isValid());
    }
    
    @Test
    void testShaderOperations() throws Exception {
        backend.initialize().get(5, TimeUnit.SECONDS);
        
        var wgslSource = """
            @compute @workgroup_size(1)
            fn main() {
                // Simple compute shader
            }
            """;
        
        var shader = backend.createComputeShader(wgslSource);
        assertNotNull(shader);
        assertEquals(wgslSource, shader.getWgslSource());
        assertTrue(shader.isValid());
        
        // Test compute dispatch
        assertDoesNotThrow(() -> {
            backend.dispatchCompute(shader, 1, 1, 1);
        });
        
        // Test shader release
        shader.release();
        assertFalse(shader.isValid());
    }
    
    @Test
    void testWaitIdle() throws Exception {
        backend.initialize().get(5, TimeUnit.SECONDS);
        
        assertDoesNotThrow(() -> {
            backend.waitIdle();
        });
    }
    
    @Test
    void testOperationsWithoutInitialization() {
        // Operations should fail before initialization
        assertThrows(IllegalStateException.class, () -> {
            backend.createBuffer(1024, 0);
        });
        
        assertThrows(IllegalStateException.class, () -> {
            backend.createComputeShader("test");
        });
    }
    
    @Test
    void testDefaultBackend() {
        var defaultBackend = WebGPUBackendFactory.getDefaultBackend();
        assertNotNull(defaultBackend);
        
        // Should return same instance
        var defaultBackend2 = WebGPUBackendFactory.getDefaultBackend();
        assertSame(defaultBackend, defaultBackend2);
        
        // Test shutdown
        WebGPUBackendFactory.shutdownDefaultBackend();
        
        // Should create new instance after shutdown
        var defaultBackend3 = WebGPUBackendFactory.getDefaultBackend();
        assertNotSame(defaultBackend, defaultBackend3);
        
        // Clean up
        WebGPUBackendFactory.shutdownDefaultBackend();
    }
    
    @Test
    void testCustomDefaultBackend() {
        var customBackend = WebGPUBackendFactory.createStubBackend();
        WebGPUBackendFactory.setDefaultBackend(customBackend);
        
        var retrievedBackend = WebGPUBackendFactory.getDefaultBackend();
        assertSame(customBackend, retrievedBackend);
        
        // Clean up
        WebGPUBackendFactory.shutdownDefaultBackend();
    }
    
    @Test
    void testInvalidBufferOperations() throws Exception {
        backend.initialize().get(5, TimeUnit.SECONDS);
        
        var buffer = backend.createBuffer(1024, 0);
        buffer.release();
        
        // Operations on invalid buffer should fail
        assertThrows(IllegalArgumentException.class, () -> {
            backend.writeBuffer(buffer, new byte[10], 0);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            backend.readBuffer(buffer, 10, 0);
        });
    }
    
    @Test
    void testInvalidShaderOperations() throws Exception {
        backend.initialize().get(5, TimeUnit.SECONDS);
        
        var shader = backend.createComputeShader("test");
        shader.release();
        
        // Operations on invalid shader should fail
        assertThrows(IllegalArgumentException.class, () -> {
            backend.dispatchCompute(shader, 1, 1, 1);
        });
    }
}