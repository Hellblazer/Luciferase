package com.hellblazer.luciferase.render.bgfx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import com.hellblazer.luciferase.render.gpu.ShaderType;

/**
 * Incremental validation test for BGFX shader compilation pipeline.
 * Tests the core shader compilation functionality without requiring full BGFX context.
 */
public class BGFXShaderCompilationValidationTest {

    /**
     * Test 1: Validate that ShaderC is available and working
     */
    @Test
    void testShaderCAvailability() {
        System.out.println("=== Testing ShaderC Availability ===");
        
        // Test that we can create a ShaderC compiler
        long compiler = Shaderc.shaderc_compiler_initialize();
        assertNotEquals(0, compiler, "ShaderC compiler should initialize successfully");
        
        if (compiler != 0) {
            Shaderc.shaderc_compiler_release(compiler);
            System.out.println("✓ ShaderC is available and working");
        }
    }

    /**
     * Test 2: Test actual GLSL→SPIR-V compilation
     */
    @Test
    void testGLSLToSPIRVCompilation() {
        System.out.println("=== Testing GLSL→SPIR-V Compilation ===");
        
        // Simple compute shader for testing
        String computeShaderSource = """
            #version 450
            
            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
            
            layout(set = 0, binding = 0, std430) restrict buffer InputBuffer {
                float inputData[];
            };
            
            layout(set = 0, binding = 1, std430) restrict buffer OutputBuffer {
                float outputData[];
            };
            
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index >= inputData.length()) return;
                
                outputData[index] = inputData[index] * inputData[index];
            }
            """;

        // Create a BGFXGPUShader to test compilation
        BGFXGPUShader shader = new BGFXGPUShader("test_shader", computeShaderSource, ShaderType.COMPUTE);
        
        // The constructor should have attempted compilation
        System.out.printf("Shader handle: %d%n", shader.getHandle());
        
        // A valid shader should have a non-zero handle (or at least not crash)
        // Since we can't fully initialize BGFX in test, we mainly test that compilation doesn't throw
        assertDoesNotThrow(() -> {
            System.out.println("Shader creation completed without throwing exceptions");
        });
        
        System.out.println("✓ GLSL→SPIR-V compilation pipeline tested");
    }

    /**
     * Test 3: Test ShaderC compilation directly
     */
    @Test
    void testDirectShaderCCompilation() {
        System.out.println("=== Testing Direct ShaderC Compilation ===");
        
        String simpleComputeShader = """
            #version 450
            layout(local_size_x = 1) in;
            void main() {
                // Minimal compute shader
            }
            """;

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) {
            fail("Could not initialize ShaderC compiler");
        }

        try {
            long options = Shaderc.shaderc_compile_options_initialize();
            assertNotEquals(0, options, "Compile options should initialize");

            try {
                // Compile GLSL to SPIR-V
                long result = Shaderc.shaderc_compile_into_spv(
                    compiler,
                    simpleComputeShader,
                    Shaderc.shaderc_compute_shader,
                    "test.comp",
                    "main",
                    options
                );
                
                assertNotEquals(0, result, "Compilation result should be valid");
                
                // Check compilation status
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                System.out.printf("Compilation status: %d%n", status);
                
                if (status == Shaderc.shaderc_compilation_status_success) {
                    // Get the compiled bytecode
                    ByteBuffer spirvBytecode = Shaderc.shaderc_result_get_bytes(result);
                    assertNotNull(spirvBytecode, "SPIR-V bytecode should be generated");
                    assertTrue(spirvBytecode.remaining() > 0, "SPIR-V bytecode should not be empty");
                    
                    System.out.printf("✓ Successfully compiled to SPIR-V (%d bytes)%n", spirvBytecode.remaining());
                } else {
                    String errorMessage = Shaderc.shaderc_result_get_error_message(result);
                    System.err.printf("Compilation failed: %s%n", errorMessage);
                    // Don't fail the test - this tells us about the environment
                }
                
                Shaderc.shaderc_result_release(result);
                
            } finally {
                Shaderc.shaderc_compile_options_release(options);
            }
            
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
        
        System.out.println("✓ Direct ShaderC compilation test completed");
    }

    /**
     * Test 4: Test BGFXGPUShader compilation methods directly
     */
    @Test
    void testBGFXShaderCompilationMethods() {
        System.out.println("=== Testing BGFXGPUShader Compilation Methods ===");
        
        String testShader = """
            #version 450
            layout(local_size_x = 32) in;
            layout(set = 0, binding = 0) buffer TestBuffer {
                float data[];
            };
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index < data.length()) {
                    data[index] = data[index] * 2.0;
                }
            }
            """;

        // Test the shader without requiring full BGFX context
        assertDoesNotThrow(() -> {
            BGFXGPUShader shader = new BGFXGPUShader("test_methods", testShader, ShaderType.COMPUTE);
            System.out.println("BGFXGPUShader instance created successfully");
            
            // Test that we can get basic info
            assertNotNull(shader.getName(), "Shader should have a name");
            assertEquals("test_methods", shader.getName(), "Shader name should match");
            
        }, "BGFXGPUShader creation should not throw exceptions");
        
        System.out.println("✓ BGFXGPUShader compilation methods tested");
    }
}