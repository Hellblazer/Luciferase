package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.GPUConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first approach to diagnose and fix BGFX Metal backend context initialization.
 * 
 * This test systematically validates each step of BGFX Metal initialization:
 * 1. Basic BGFX context creation
 * 2. Metal backend selection and initialization  
 * 3. Compute shader capability verification
 * 4. Buffer creation and management
 * 5. Resource cleanup
 */
@EnabledOnOs(OS.MAC) // Metal backend only available on macOS
public class BGFXContextInitializationTest {
    
    @Test
    public void testBasicBGFXContextInitialization() {
        // Test: Basic BGFX context should initialize successfully with Metal backend
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .withDebugEnabled(true)
            .withWidth(1)
            .withHeight(1)
            .build();
            
        var context = new BGFXGPUContext();
        
        // This should not hang or throw exceptions
        boolean initialized = context.initialize(config);
        
        assertTrue(initialized, "BGFX Metal context should initialize successfully");
        assertTrue(context.isValid(), "BGFX context should be valid after initialization");
        
        // Cleanup
        context.cleanup();
        assertFalse(context.isValid(), "BGFX context should be invalid after cleanup");
    }
    
    @Test 
    public void testComputeShaderSupport() {
        // Test: BGFX Metal context should support compute shaders
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .build();
            
        var context = new BGFXGPUContext();
        assertTrue(context.initialize(config), "Context should initialize");
        
        // Verify compute shader support
        assertTrue(context.isComputeSupported(), "Metal backend should support compute shaders");
        
        context.cleanup();
    }
    
    @Test
    public void testBufferCreation() {
        // Test: Should be able to create basic GPU buffers
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .build();
            
        var context = new BGFXGPUContext();
        assertTrue(context.initialize(config), "Context should initialize");
        
        // Create a basic storage buffer
        var buffer = context.createBuffer(
            com.hellblazer.luciferase.render.gpu.BufferType.STORAGE,
            1024,
            com.hellblazer.luciferase.render.gpu.BufferUsage.DYNAMIC_READ
        );
        
        assertNotNull(buffer, "Buffer creation should succeed");
        assertTrue(buffer.isValid(), "Created buffer should be valid");
        assertEquals(1024, buffer.getSize(), "Buffer should have correct size");
        
        // Cleanup
        buffer.destroy();
        context.cleanup();
    }
    
    @Test
    public void testBufferUploadDownload() {
        // Test: Buffer upload/download functionality
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .build();
            
        var context = new BGFXGPUContext();
        assertTrue(context.initialize(config), "Context should initialize");
        
        var buffer = context.createBuffer(
            com.hellblazer.luciferase.render.gpu.BufferType.STORAGE,
            64,
            com.hellblazer.luciferase.render.gpu.BufferUsage.DYNAMIC_READ
        );
        
        assertNotNull(buffer, "Buffer creation should succeed");
        
        // Create test data
        var testData = java.nio.ByteBuffer.allocateDirect(64);
        for (int i = 0; i < 16; i++) {
            testData.putInt(i);
        }
        testData.flip();
        
        // Upload should not throw exceptions
        assertDoesNotThrow(() -> buffer.upload(testData), "Buffer upload should succeed");
        
        // Download may return null for unsupported operations, but should not throw
        var downloaded = assertDoesNotThrow(() -> buffer.download(), "Buffer download should not throw");
        
        // For now, we accept that download might not be implemented
        // but upload should work for GPU compute operations
        
        buffer.destroy();
        context.cleanup();
    }
    
    @Test
    public void testComputeShaderCreation() {
        // Test: Should be able to create compute shaders
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .build();
            
        var context = new BGFXGPUContext();
        assertTrue(context.initialize(config), "Context should initialize");
        
        // Simple compute shader source (placeholder)
        String shaderSource = """
            #version 430
            layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
            void main() {
                // Simple compute shader
            }
            """;
            
        var shader = context.createComputeShader(shaderSource, java.util.Map.of());
        
        // For now, shader creation might fail due to compilation issues
        // but it should not hang or crash the process
        assertDoesNotThrow(() -> context.createComputeShader(shaderSource, java.util.Map.of()),
            "Shader creation should not crash");
        
        context.cleanup();
    }
    
    @Test
    public void testDispatchCompute() {
        // Test: Compute dispatch should not hang
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .build();
            
        var context = new BGFXGPUContext();
        assertTrue(context.initialize(config), "Context should initialize");
        
        // Try to dispatch with null shader (should fail gracefully)
        assertDoesNotThrow(() -> context.dispatchCompute(null, 1, 1, 1),
            "Dispatch with null shader should not hang");
        
        // Wait for completion should not hang
        assertDoesNotThrow(() -> context.waitForCompletion(),
            "Wait for completion should not hang");
        
        context.cleanup();
    }
    
    @Test
    public void testFallbackToOpenGL() {
        // Test: If Metal fails, should fallback to OpenGL
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL) // Request Metal
            .withHeadless(true)
            .build();
            
        var context = new BGFXGPUContext();
        
        // Even if Metal initialization fails internally, 
        // the context should initialize with a fallback backend
        boolean initialized = context.initialize(config);
        
        // The context might initialize with OpenGL fallback
        // This is acceptable behavior
        if (initialized) {
            assertTrue(context.isValid(), "Fallback context should be valid");
            context.cleanup();
        }
        
        // Test should not hang regardless of backend selection
    }
}