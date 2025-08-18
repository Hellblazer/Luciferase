package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Validate the BGFX implementation fixes without requiring full GPU initialization.
 * Tests the architectural improvements to buffers and shaders.
 */
@EnabledOnOs(OS.MAC) // BGFX Metal backend only on macOS
public class BGFXFixValidationTest {
    
    @Test
    public void testBGFXBufferCreation() {
        // Test: BGFXGPUBuffer should be creatable with proper parameters
        var buffer = new BGFXGPUBuffer(1, BufferType.STORAGE, 1024, BufferUsage.DYNAMIC_READ);
        
        assertNotNull(buffer, "Buffer should be created");
        assertEquals(1, buffer.getId(), "Buffer should have correct ID");
        assertEquals(1024, buffer.getSize(), "Buffer should have correct size");
        assertEquals(BufferType.STORAGE, buffer.getType(), "Buffer should have correct type");
        assertEquals(BufferUsage.DYNAMIC_READ, buffer.getUsage(), "Buffer should have correct usage");
        
        // Buffer should not be valid until initialized
        assertFalse(buffer.isValid(), "Buffer should not be valid before initialization");
        assertFalse(buffer.isMapped(), "Buffer should not be mapped initially");
    }
    
    @Test
    public void testBGFXShaderCreation() {
        // Test: BGFXGPUShader should be creatable and handle compilation attempts
        var shader = new BGFXGPUShader(1);
        
        assertNotNull(shader, "Shader should be created");
        assertEquals(1, shader.getId(), "Shader should have correct ID");
        assertFalse(shader.isValid(), "Shader should not be valid before compilation");
        
        // Test compilation with dummy source
        String dummyShader = """
            #version 430
            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
            void main() {
                // Test compute shader
            }
            """;
        
        // Compilation should not crash (may fail, but should not throw)
        assertDoesNotThrow(() -> {
            boolean compiled = shader.compile(dummyShader, java.util.Map.of());
            // We don't assert success since we need BGFX initialized for actual compilation
            // but the method should complete without exceptions
        }, "Shader compilation should not crash");
        
        // Work group size should be extracted from source
        int[] workGroupSize = shader.getWorkGroupSize();
        assertNotNull(workGroupSize, "Work group size should be available");
        assertEquals(3, workGroupSize.length, "Work group size should have 3 dimensions");
        
        // Should have extracted local_size_x = 8, local_size_y = 8 from source
        assertEquals(8, workGroupSize[0], "Work group size X should be extracted from shader");
        assertEquals(8, workGroupSize[1], "Work group size Y should be extracted from shader");
        assertEquals(1, workGroupSize[2], "Work group size Z should be extracted from shader");
    }
    
    @Test
    public void testBGFXGPUContextCreation() {
        // Test: BGFXGPUContext should be creatable
        var context = new BGFXGPUContext();
        
        assertNotNull(context, "Context should be created");
        assertFalse(context.isValid(), "Context should not be valid before initialization");
        
        // Context should handle initialization attempts gracefully
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .withDebugEnabled(false) // Reduce debug overhead
            .withWidth(1)
            .withHeight(1)
            .build();
        
        // Initialization may fail (due to Metal unavailability) but should not crash
        assertDoesNotThrow(() -> {
            boolean initialized = context.initialize(config);
            // Don't assert success - Metal may not be available in test environment
            
            // If it did initialize, test basic operations
            if (initialized && context.isValid()) {
                assertTrue(context.isComputeSupported(), "Initialized context should support compute");
                
                // Test buffer creation through context
                var buffer = context.createBuffer(BufferType.STORAGE, 64, BufferUsage.DYNAMIC_READ);
                if (buffer != null) {
                    assertTrue(buffer.isValid(), "Context-created buffer should be valid");
                    buffer.destroy();
                }
                
                context.cleanup();
                assertFalse(context.isValid(), "Context should be invalid after cleanup");
            }
        }, "Context initialization should not crash");
    }
    
    @Test
    public void testBufferManagerCreation() {
        // Test: BGFXBufferManager should be creatable and handle operations
        var mockContext = new BGFXGPUContext();
        var bufferManager = new BGFXBufferManager(mockContext);
        
        assertNotNull(bufferManager, "Buffer manager should be created");
        
        // Buffer manager should handle requests gracefully even with uninitialized context
        assertDoesNotThrow(() -> {
            var stats = bufferManager.getStats();
            assertNotNull(stats, "Buffer manager should provide stats");
            assertEquals(0, stats.totalBuffersCreated(), "No buffers should be created initially");
        }, "Buffer manager stats should be available");
        
        // Cleanup should not crash
        assertDoesNotThrow(() -> bufferManager.cleanup(), "Buffer manager cleanup should not crash");
    }
    
    @Test
    public void testShaderPreprocessing() {
        // Test: Shader preprocessing should work correctly
        var shader = new BGFXGPUShader(1);
        
        String sourceWithoutDefines = """
            #version 430
            layout(local_size_x = 1) in;
            void main() {
                // Test
            }
            """;
        
        var defines = java.util.Map.of(
            "MAX_ITERATIONS", "100",
            "ENABLE_DEBUG", "1"
        );
        
        // Test preprocessing through compilation (won't succeed but should preprocess)
        assertDoesNotThrow(() -> {
            shader.compile(sourceWithoutDefines, defines);
            // The preprocessing should add #define statements even if compilation fails
        }, "Shader preprocessing should not crash");
    }
    
    @Test
    public void testMetalShaderBytecodeGeneration() {
        // Test: Dummy Metal bytecode generation should work
        var shader = new BGFXGPUShader(1);
        
        // Use Java reflection to test the private createDummyMetalBytecode method
        assertDoesNotThrow(() -> {
            var method = BGFXGPUShader.class.getDeclaredMethod("createDummyMetalBytecode");
            method.setAccessible(true);
            byte[] bytecode = (byte[]) method.invoke(shader);
            
            assertNotNull(bytecode, "Dummy bytecode should not be null");
            assertTrue(bytecode.length > 0, "Dummy bytecode should have content");
            
            // Should contain Metal shader code
            String bytecodeString = new String(bytecode, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(bytecodeString.contains("#include <metal_stdlib>"), 
                "Bytecode should contain Metal includes");
            assertTrue(bytecodeString.contains("kernel void compute_main"), 
                "Bytecode should contain compute kernel");
                
        }, "Dummy Metal bytecode generation should work");
    }
}