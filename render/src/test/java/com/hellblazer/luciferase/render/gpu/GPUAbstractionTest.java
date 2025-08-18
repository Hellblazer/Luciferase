package com.hellblazer.luciferase.render.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to validate GPU abstraction interfaces compile correctly
 * and have proper API design. This is part of Phase 1.1 completion.
 */
class GPUAbstractionTest {

    @Test
    @DisplayName("GPU abstraction interfaces should compile and be instantiable")
    void testGPUAbstractionCompilation() {
        // Test that all enums are accessible and have expected values
        var bufferTypes = BufferType.values();
        assertTrue(bufferTypes.length > 0, "BufferType enum should have values");
        assertNotNull(BufferType.STORAGE, "STORAGE buffer type should exist");
        assertNotNull(BufferType.UNIFORM, "UNIFORM buffer type should exist");

        var bufferUsages = BufferUsage.values();
        assertTrue(bufferUsages.length > 0, "BufferUsage enum should have values");
        assertNotNull(BufferUsage.STATIC_READ, "STATIC_READ usage should exist");
        assertNotNull(BufferUsage.DYNAMIC_WRITE, "DYNAMIC_WRITE usage should exist");

        var accessTypes = AccessType.values();
        assertTrue(accessTypes.length > 0, "AccessType enum should have values");
        assertNotNull(AccessType.READ_ONLY, "READ_ONLY access should exist");
        assertNotNull(AccessType.WRITE_ONLY, "WRITE_ONLY access should exist");
        assertNotNull(AccessType.READ_WRITE, "READ_WRITE access should exist");

        var barrierTypes = BarrierType.values();
        assertTrue(barrierTypes.length > 0, "BarrierType enum should have values");
        assertNotNull(BarrierType.SHADER_STORAGE_BARRIER, "SHADER_STORAGE_BARRIER should exist");
        assertNotNull(BarrierType.ALL_BARRIER, "ALL_BARRIER should exist");
    }

    @Test
    @DisplayName("GPUConfig should be buildable with all required parameters")
    void testGPUConfigBuilder() {
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withDebugEnabled(true)
            .withValidationEnabled(false)
            .build();

        assertNotNull(config, "GPUConfig should be buildable");
        assertEquals(GPUConfig.Backend.BGFX_METAL, config.getBackend());
        assertTrue(config.isDebugEnabled());
        assertFalse(config.isValidationEnabled());
    }

    @Test
    @DisplayName("Interface method signatures should be well-designed")
    void testInterfaceDesign() {
        // Test that interfaces can be referenced (compilation test)
        assertDoesNotThrow(() -> {
            // These would normally be implemented by concrete classes
            // We're just testing that the interface methods are accessible
            
            // IGPUContext methods
            Class<?> contextClass = IGPUContext.class;
            assertNotNull(contextClass.getMethod("initialize", GPUConfig.class));
            assertNotNull(contextClass.getMethod("createBuffer", BufferType.class, int.class, BufferUsage.class));
            assertNotNull(contextClass.getMethod("createComputeShader", String.class, Map.class));
            assertNotNull(contextClass.getMethod("dispatch", IGPUShader.class, int.class, int.class, int.class));
            assertNotNull(contextClass.getMethod("cleanup"));
            assertNotNull(contextClass.getMethod("isValid"));

            // IGPUBuffer methods
            Class<?> bufferClass = IGPUBuffer.class;
            assertNotNull(bufferClass.getMethod("upload", java.nio.ByteBuffer.class, int.class));
            assertNotNull(bufferClass.getMethod("download", int.class, int.class));
            assertNotNull(bufferClass.getMethod("bind", int.class, AccessType.class));
            assertNotNull(bufferClass.getMethod("getSize"));
            assertNotNull(bufferClass.getMethod("destroy"));

            // IGPUShader methods
            Class<?> shaderClass = IGPUShader.class;
            assertNotNull(shaderClass.getMethod("isValid"));
            assertNotNull(shaderClass.getMethod("getCompilationLog"));
            assertNotNull(shaderClass.getMethod("setUniform", String.class, Object.class));
            assertNotNull(shaderClass.getMethod("destroy"));

        }, "All interface methods should be accessible via reflection");
    }

    @Test
    @DisplayName("Mock implementation should be possible")
    void testMockImplementation() {
        // Create simple mock implementations to test interface design
        var mockContext = new MockGPUContext();
        var mockConfig = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.OPENGL)
            .build();

        // Test basic mock functionality
        assertFalse(mockContext.isValid(), "Mock context should start invalid");
        assertTrue(mockContext.initialize(mockConfig), "Mock should initialize successfully");
        assertTrue(mockContext.isValid(), "Mock context should be valid after init");

        var mockBuffer = mockContext.createBuffer(BufferType.STORAGE, 1024, BufferUsage.DYNAMIC_WRITE);
        assertNotNull(mockBuffer, "Mock buffer should be created");
        assertEquals(1024, mockBuffer.getSize(), "Mock buffer should have correct size");

        mockContext.cleanup();
        assertFalse(mockContext.isValid(), "Mock context should be invalid after cleanup");
    }

    /**
     * Simple mock implementation for testing interface design
     */
    private static class MockGPUContext implements IGPUContext {
        private boolean valid = false;

        @Override
        public boolean initialize(GPUConfig config) {
            valid = true;
            return true;
        }

        @Override
        public IGPUBuffer createBuffer(BufferType type, int size, BufferUsage usage) {
            return new MockGPUBuffer(size);
        }

        @Override
        public IGPUBuffer createBuffer(int size, BufferUsage usage, AccessType accessType) {
            return new MockGPUBuffer(size);
        }

        @Override
        public IGPUShader createComputeShader(String shaderSource, Map<String, String> defines) {
            return new MockGPUShader();
        }

        @Override
        public void dispatch(IGPUShader shader, int groupsX, int groupsY, int groupsZ) {
            // Mock implementation
        }

        @Override
        public void memoryBarrier(BarrierType barrierType) {
            // Mock implementation
        }

        @Override
        public void cleanup() {
            valid = false;
        }

        @Override
        public boolean isValid() {
            return valid;
        }
        
        @Override
        public IShaderFactory getShaderFactory() {
            return null; // Mock implementation
        }

        @Override
        public IGPUShader createShader(String shaderSource, Map<String, String> defines) {
            return createComputeShader(shaderSource, defines);
        }
        
        @Override
        public boolean dispatchCompute(IGPUShader shader, int groupsX, int groupsY, int groupsZ) {
            return valid; // Mock implementation returns validity status
        }
        
        @Override
        public void waitForCompletion() {
            // Mock implementation - no actual work to wait for
        }
    }

    private static class MockGPUBuffer implements IGPUBuffer {
        private final int size;
        private final BufferType type;
        private final BufferUsage usage;

        MockGPUBuffer(int size) {
            this.size = size;
            this.type = BufferType.STORAGE;
            this.usage = BufferUsage.DYNAMIC_WRITE;
        }

        @Override
        public void upload(java.nio.ByteBuffer data, int offset) {}

        @Override
        public java.nio.ByteBuffer download(int offset, int size) { return null; }

        @Override
        public void bind(int slot, AccessType access) {}

        @Override
        public void unbind() {}

        @Override
        public java.nio.ByteBuffer map(AccessType access) { return null; }

        @Override
        public void unmap() {}

        @Override
        public int getSize() { return size; }

        @Override
        public BufferType getType() { return type; }

        @Override
        public BufferUsage getUsage() { return usage; }

        @Override
        public boolean isMapped() { return false; }

        @Override
        public boolean isValid() { return true; }

        @Override
        public Object getNativeHandle() { return null; }

        @Override
        public void destroy() {}
    }

    private static class MockGPUShader implements IGPUShader {
        @Override
        public boolean compile(String source, Map<String, String> defines) { return true; }

        @Override
        public void setUniform(String name, Object value) {}

        @Override
        public void setUniformVector(String name, float... values) {}

        @Override
        public void setUniformMatrix(String name, float[] matrix) {}

        @Override
        public void setUniformInt(String name, int value) {}

        @Override
        public void setUniformFloat(String name, float value) {}

        @Override
        public boolean isValid() { return true; }

        @Override
        public String getCompilationLog() { return "Mock compile log"; }

        @Override
        public int[] getWorkGroupSize() { return new int[]{32, 1, 1}; }

        @Override
        public Object getNativeHandle() { return null; }

        @Override
        public boolean hasUniform(String name) { return false; }

        @Override
        public Map<String, String> getUniforms() { return new HashMap<>(); }

        @Override
        public void destroy() {}
    }
}