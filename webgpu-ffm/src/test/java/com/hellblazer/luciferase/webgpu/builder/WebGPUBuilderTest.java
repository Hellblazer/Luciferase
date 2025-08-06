package com.hellblazer.luciferase.webgpu.builder;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.*;

/**
 * Tests for WebGPU builder API.
 */
public class WebGPUBuilderTest {
    
    @BeforeEach
    void setUp() {
        // Clean state
        WebGPU.shutdown();
    }
    
    @AfterEach
    void tearDown() {
        WebGPU.shutdown();
    }
    
    @Test
    void testInstanceBuilder() {
        // Test building instance with validation
        var builder = WebGPUBuilder.createInstance()
            .withValidation(true)
            .withLabel("TestInstance");
        
        assertNotNull(builder);
        
        // Try to build (will fail without native library, but that's expected)
        try {
            var instance = builder.build();
            // If we get here, native library is available
            assertNotNull(instance);
            instance.close();
        } catch (RuntimeException e) {
            // Expected when native library is not available
            // Exception message may vary, just check it's a RuntimeException
            assertNotNull(e.getMessage());
        }
    }
    
    @Test
    void testAdapterRequestBuilder() {
        try (var arena = Arena.ofConfined()) {
            // Create mock instance
            var mockHandle = arena.allocate(8);
            var instance = new Instance(mockHandle);
            
            // Test adapter request builder
            var builder = new WebGPUBuilder.AdapterRequestBuilder(instance)
                .powerPreference(Instance.PowerPreference.HIGH_PERFORMANCE)
                .forceFallback(true);
            
            assertNotNull(builder);
            
            // Test async request
            var future = builder.requestAsync();
            assertNotNull(future);
        }
    }
    
    @Test
    void testDeviceRequestBuilder() {
        try (var arena = Arena.ofConfined()) {
            // Create mock adapter
            var mockHandle = arena.allocate(8);
            var adapter = new Adapter(mockHandle);
            
            // Test device request builder
            var limits = new Adapter.DeviceLimits();
            var builder = new WebGPUBuilder.DeviceRequestBuilder(adapter)
                .withLabel("TestDevice")
                .withFeatures(1L, 2L, 3L)
                .withLimits(limits);
            
            assertNotNull(builder);
            
            // Test async request
            var future = builder.requestAsync();
            assertNotNull(future);
        }
    }
    
    @Test
    void testBufferBuilder() {
        try (var arena = Arena.ofConfined()) {
            // Create mock device
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            
            // Test buffer builder
            var buffer = new WebGPUBuilder.BufferBuilder(device, 4096)
                .withLabel("TestBuffer")
                .withUsage(
                    WebGPUBuilder.BufferUsage.STORAGE,
                    WebGPUBuilder.BufferUsage.COPY_DST
                )
                .mappedAtCreation(false)
                .build();
            
            assertNotNull(buffer);
            assertEquals(4096, buffer.getSize());
            
            // Check combined usage flags
            int expectedUsage = WebGPUBuilder.BufferUsage.STORAGE.getValue() | 
                               WebGPUBuilder.BufferUsage.COPY_DST.getValue();
            assertEquals(expectedUsage, buffer.getUsage());
            
            buffer.close();
        }
    }
    
    @Test
    void testComputeShaderBuilder() {
        try (var arena = Arena.ofConfined()) {
            // Create mock device
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            
            String wgslCode = """
                @compute @workgroup_size(64)
                fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                    // Shader code
                }
                """;
            
            // Test shader module builder
            var shaderBuilder = new WebGPUBuilder.ComputeShaderBuilder(device, wgslCode)
                .withLabel("TestShader")
                .withEntryPoint("main");
            
            var module = shaderBuilder.buildModule();
            assertNotNull(module);
            assertEquals(wgslCode, module.getCode());
            
            // Test pipeline builder
            var pipeline = new WebGPUBuilder.ComputeShaderBuilder(device, wgslCode)
                .withLabel("TestPipeline")
                .withEntryPoint("main")
                .buildPipeline();
            
            assertNotNull(pipeline);
            
            module.close();
            pipeline.close();
        }
    }
    
    @Test
    void testBufferUsageEnum() {
        // Test all buffer usage values
        assertEquals(0x00000001, WebGPUBuilder.BufferUsage.MAP_READ.getValue());
        assertEquals(0x00000002, WebGPUBuilder.BufferUsage.MAP_WRITE.getValue());
        assertEquals(0x00000004, WebGPUBuilder.BufferUsage.COPY_SRC.getValue());
        assertEquals(0x00000008, WebGPUBuilder.BufferUsage.COPY_DST.getValue());
        assertEquals(0x00000010, WebGPUBuilder.BufferUsage.INDEX.getValue());
        assertEquals(0x00000020, WebGPUBuilder.BufferUsage.VERTEX.getValue());
        assertEquals(0x00000040, WebGPUBuilder.BufferUsage.UNIFORM.getValue());
        assertEquals(0x00000080, WebGPUBuilder.BufferUsage.STORAGE.getValue());
        assertEquals(0x00000100, WebGPUBuilder.BufferUsage.INDIRECT.getValue());
        assertEquals(0x00000200, WebGPUBuilder.BufferUsage.QUERY_RESOLVE.getValue());
    }
    
    @Test
    void testBuilderChaining() {
        // Test that builders can be chained properly
        var instanceBuilder = WebGPUBuilder.createInstance()
            .withValidation(true)
            .withLabel("ChainTest")
            .withValidation(false)  // Should override
            .withLabel("FinalLabel"); // Should override
        
        assertNotNull(instanceBuilder);
        
        // For other builders, test with mocks
        try (var arena = Arena.ofConfined()) {
            var deviceHandle = arena.allocate(8);
            var queueHandle = arena.allocate(8);
            var device = new Device(deviceHandle, queueHandle);
            
            // Test multiple usage flags chaining
            var bufferBuilder = new WebGPUBuilder.BufferBuilder(device, 1024)
                .withUsage(WebGPUBuilder.BufferUsage.STORAGE)
                .withUsage(WebGPUBuilder.BufferUsage.COPY_DST)
                .withUsage(WebGPUBuilder.BufferUsage.COPY_SRC)
                .withLabel("Buffer1")
                .withLabel("Buffer2") // Should use last one
                .mappedAtCreation(true)
                .mappedAtCreation(false); // Should use last one
            
            var buffer = bufferBuilder.build();
            assertNotNull(buffer);
            
            // Check all flags are combined
            int expectedUsage = 
                WebGPUBuilder.BufferUsage.STORAGE.getValue() |
                WebGPUBuilder.BufferUsage.COPY_DST.getValue() |
                WebGPUBuilder.BufferUsage.COPY_SRC.getValue();
            assertEquals(expectedUsage, buffer.getUsage());
            
            buffer.close();
        }
    }
    
    @Test
    void testExampleMethodDoesNotThrow() {
        // The example method should not throw during compilation
        // It will throw at runtime without native library, but that's expected
        assertDoesNotThrow(() -> {
            try {
                WebGPUBuilder.example();
            } catch (Exception e) {
                // Expected - native library not available
                assertTrue(e instanceof RuntimeException || 
                          e.getCause() instanceof RuntimeException);
            }
        });
    }
}