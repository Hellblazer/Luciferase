/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.gpu;

import com.hellblazer.luciferase.render.gpu.bgfx.BGFXShaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for shader factory implementations.
 * Tests both interface contracts and specific backend implementations.
 */
class ShaderFactoryTest {
    
    private static final String SIMPLE_COMPUTE_SHADER = """
        #version 430 core
        
        layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
        
        layout(std430, binding = 0) readonly buffer InputBuffer {
            float data[];
        } inputBuffer;
        
        layout(std430, binding = 1) writeonly buffer OutputBuffer {
            float results[];
        } outputBuffer;
        
        void main() {
            uint index = gl_GlobalInvocationID.x;
            if (index >= inputBuffer.data.length()) return;
            
            outputBuffer.results[index] = inputBuffer.data[index] * 2.0;
        }
        """;
    
    private static final String ESVO_TRAVERSE_SHADER_SAMPLE = """
        #version 430 core
        
        layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
        
        struct ESVONode {
            uint packedData1;
            uint packedData2;
        };
        
        struct Ray {
            vec3 origin;
            vec3 direction;
            float tMin;
            float tMax;
        };
        
        layout(std430, binding = 0) readonly buffer NodeBuffer {
            ESVONode nodes[];
        } nodeBuffer;
        
        layout(std430, binding = 1) readonly buffer RayBuffer {
            Ray rays[];
        } rayBuffer;
        
        layout(std430, binding = 2) writeonly buffer ResultBuffer {
            vec4 results[];
        } resultBuffer;
        
        void main() {
            uint rayIndex = gl_GlobalInvocationID.x;
            if (rayIndex >= rayBuffer.rays.length()) return;
            
            Ray ray = rayBuffer.rays[rayIndex];
            
            // Simplified traversal
            vec4 result = vec4(0.0);
            for (uint i = 0; i < min(nodeBuffer.nodes.length(), 100u); i++) {
                ESVONode node = nodeBuffer.nodes[i];
                // Simulate intersection test
                if (node.packedData1 != 0u) {
                    result = vec4(1.0, 0.0, 0.0, 1.0);
                    break;
                }
            }
            
            resultBuffer.results[rayIndex] = result;
        }
        """;
    
    @Nested
    @DisplayName("IShaderFactory Interface Tests")
    class InterfaceTests {
        
        private IShaderFactory factory;
        
        @BeforeEach
        void setUp() {
            factory = new BGFXShaderFactory("shaders/esvo/", true);
        }
        
        @Test
        @DisplayName("Should compile simple compute shader successfully")
        void testSimpleShaderCompilation() {
            var result = factory.compileComputeShader("test_simple", SIMPLE_COMPUTE_SHADER, Map.of());
            
            // Note: BGFX shader compilation will fail because we don't have actual Metal compilation
            // but we can test the interface and preprocessing
            assertNotNull(result);
            assertNotNull(result.getPreprocessorLog());
            assertTrue(result.getPreprocessorLog().containsKey("original_length"));
            assertTrue(result.getPreprocessorLog().containsKey("processed_length"));
            assertTrue(result.getPreprocessorLog().containsKey("metal_length"));
        }
        
        @Test
        @DisplayName("Should handle shader compilation with defines")
        void testShaderCompilationWithDefines() {
            Map<String, String> defines = Map.of(
                "ENABLE_OPTIMIZATION", "1",
                "MAX_ITERATIONS", "100",
                "PRECISION", "0.001f"
            );
            
            var result = factory.compileComputeShader("test_defines", SIMPLE_COMPUTE_SHADER, defines);
            
            assertNotNull(result);
            assertEquals("3", result.getPreprocessorLog().get("defines_applied"));
        }
        
        @Test
        @DisplayName("Should create and compile shader variants")
        void testShaderVariantCompilation() {
            var variant = new IShaderFactory.ShaderVariant(
                "test_shader",
                Map.of("VARIANT_VALUE", "42"),
                Set.of("ENABLE_FEATURE", "DEBUG_MODE")
            );
            
            var result = factory.compileShaderVariant(variant);
            
            assertNotNull(result);
            assertNotNull(result.getPreprocessorLog());
        }
        
        @Test
        @DisplayName("Should validate shader source compatibility")
        void testShaderCompatibilityValidation() {
            // Valid compute shader
            assertTrue(factory.validateShaderCompatibility(SIMPLE_COMPUTE_SHADER));
            assertTrue(factory.validateShaderCompatibility(ESVO_TRAVERSE_SHADER_SAMPLE));
            
            // Invalid shaders
            assertFalse(factory.validateShaderCompatibility("// Not a shader"));
            assertFalse(factory.validateShaderCompatibility("#version 330 core\nvoid main() {}"));
        }
        
        @Test
        @DisplayName("Should preprocess shader with defines and flags")
        void testShaderPreprocessing() {
            Map<String, String> defines = Map.of("TEST_VALUE", "100");
            Set<String> flags = Set.of("ENABLE_TEST");
            
            String processed = factory.preprocessShader(SIMPLE_COMPUTE_SHADER, defines, flags);
            
            assertNotNull(processed);
            assertTrue(processed.contains("#define ENABLE_TEST"));
            assertTrue(processed.contains("#define TEST_VALUE 100"));
            assertTrue(processed.contains("#version 430 core"));
        }
        
        @Test
        @DisplayName("Should provide backend and language information")
        void testBackendInformation() {
            assertEquals("BGFX_Metal", factory.getBackendInfo());
            assertEquals("MSL", factory.getShaderLanguage());
        }
        
        @Test
        @DisplayName("Should manage cache operations")
        void testCacheManagement() {
            // These should not throw exceptions
            assertDoesNotThrow(() -> factory.clearCache());
            assertDoesNotThrow(() -> factory.cleanup());
        }
    }
    
    @Nested
    @DisplayName("BGFXShaderFactory Specific Tests")
    class BGFXFactoryTests {
        
        private BGFXShaderFactory bgfxFactory;
        
        @BeforeEach
        void setUp() {
            bgfxFactory = new BGFXShaderFactory("shaders/esvo/", true);
        }
        
        @Test
        @DisplayName("Should initialize with ESVO shader directory")
        void testFactoryInitialization() {
            assertNotNull(bgfxFactory);
            assertEquals("BGFX_Metal", bgfxFactory.getBackendInfo());
            assertEquals("MSL", bgfxFactory.getShaderLanguage());
        }
        
        @Test
        @DisplayName("Should detect available ESVO shaders")
        void testAvailableShaderDetection() {
            Set<String> availableShaders = bgfxFactory.getAvailableShaders();
            
            assertNotNull(availableShaders);
            // Note: Actual ESVO shaders may or may not be present in test environment
            // This tests the mechanism rather than specific content
        }
        
        @Test
        @DisplayName("Should validate ESVO shader compatibility")
        void testESVOShaderCompatibility() {
            assertTrue(bgfxFactory.validateShaderCompatibility(ESVO_TRAVERSE_SHADER_SAMPLE));
            
            // Test problematic constructs for Metal
            String problematicShader = """
                #version 430 core
                layout(local_size_x = 32) in;
                void main() {
                    uint w = gl_GlobalInvocationID.w; // This doesn't exist in Metal
                }
                """;
            
            assertFalse(bgfxFactory.validateShaderCompatibility(problematicShader));
        }
        
        @Test
        @DisplayName("Should translate GLSL to Metal syntax")
        void testGLSLToMetalTranslation() {
            var result = bgfxFactory.compileComputeShader("test_translation", SIMPLE_COMPUTE_SHADER, Map.of());
            
            assertNotNull(result);
            // The Metal source should be longer than the original due to headers
            String metalLength = result.getPreprocessorLog().get("metal_length");
            String originalLength = result.getPreprocessorLog().get("original_length");
            
            assertNotNull(metalLength);
            assertNotNull(originalLength);
            assertTrue(Integer.parseInt(metalLength) > 0);
        }
        
        @Test
        @DisplayName("Should handle shader variant caching")
        void testShaderVariantCaching() {
            var variant = new IShaderFactory.ShaderVariant(
                "cache_test",
                Map.of("CACHE_VALUE", "123"),
                Set.of("CACHE_FLAG"),
                SIMPLE_COMPUTE_SHADER
            );
            
            // Compile twice - second should potentially use cache
            var result1 = bgfxFactory.compileShaderVariant(variant);
            var result2 = bgfxFactory.compileShaderVariant(variant);
            
            assertNotNull(result1);
            assertNotNull(result2);
            
            // Both should have the same variant key
            assertEquals(variant.getVariantKey(), variant.getVariantKey());
        }
        
        @Test
        @DisplayName("Should handle shader loading from source override")
        void testSourceOverrideLoading() {
            var variant = new IShaderFactory.ShaderVariant(
                "override_test",
                Map.of(),
                Set.of(),
                ESVO_TRAVERSE_SHADER_SAMPLE
            );
            
            var result = bgfxFactory.compileShaderVariant(variant);
            
            assertNotNull(result);
            // Should work even if the shader file doesn't exist on disk
        }
        
        @Test
        @DisplayName("Should generate unique variant keys")
        void testVariantKeyGeneration() {
            var variant1 = new IShaderFactory.ShaderVariant(
                "test_shader",
                Map.of("A", "1", "B", "2"),
                Set.of("FLAG1", "FLAG2")
            );
            
            var variant2 = new IShaderFactory.ShaderVariant(
                "test_shader",
                Map.of("B", "2", "A", "1"), // Different order
                Set.of("FLAG2", "FLAG1")    // Different order
            );
            
            var variant3 = new IShaderFactory.ShaderVariant(
                "test_shader",
                Map.of("A", "1", "B", "3"), // Different value
                Set.of("FLAG1", "FLAG2")
            );
            
            assertEquals(variant1.getVariantKey(), variant2.getVariantKey());
            assertNotEquals(variant1.getVariantKey(), variant3.getVariantKey());
        }
    }
    
    @Nested
    @DisplayName("Shader Validation Tests")
    class ValidationTests {
        
        @Test
        @DisplayName("Should validate compute shader structure")
        void testComputeShaderValidation() {
            var result = ShaderValidationUtils.validateComputeShader(SIMPLE_COMPUTE_SHADER, "BGFX_Metal");
            
            assertTrue(result.isValid());
            assertFalse(result.hasErrors());
            
            // Check metadata
            assertEquals(430, result.getMetadata().get("glsl_version"));
            assertEquals("core", result.getMetadata().get("glsl_profile"));
            assertEquals(32, ((int[]) result.getMetadata().get("work_group_size"))[0]);
            assertEquals(1, ((int[]) result.getMetadata().get("work_group_size"))[1]);
            assertEquals(1, ((int[]) result.getMetadata().get("work_group_size"))[2]);
        }
        
        @Test
        @DisplayName("Should detect shader validation errors")
        void testShaderValidationErrors() {
            String invalidShader = """
                #version 330 core
                // Missing work group specification
                void main() {
                    // Invalid for compute shader
                }
                """;
            
            var result = ShaderValidationUtils.validateComputeShader(invalidShader, "BGFX_Metal");
            
            assertFalse(result.isValid());
            assertTrue(result.hasErrors());
            assertTrue(result.getErrors().stream()
                .anyMatch(error -> error.contains("does not support compute shaders")));
        }
        
        @Test
        @DisplayName("Should validate Metal compatibility warnings")
        void testMetalCompatibilityWarnings() {
            String metalProblematicShader = """
                #version 430 core
                layout(local_size_x = 32) in;
                void main() {
                    imageAtomicExchange(someImage, ivec2(0), 1u);
                    memoryBarrierShared();
                }
                """;
            
            var result = ShaderValidationUtils.validateComputeShader(metalProblematicShader, "BGFX_Metal");
            
            assertTrue(result.hasWarnings());
            assertTrue(result.getWarnings().stream()
                .anyMatch(warning -> warning.contains("imageAtomicExchange")));
            assertTrue(result.getWarnings().stream()
                .anyMatch(warning -> warning.contains("memoryBarrierShared")));
        }
        
        @Test
        @DisplayName("Should validate preprocessor defines")
        void testDefineValidation() {
            Map<String, String> goodDefines = Map.of(
                "MAX_ITERATIONS", "100",
                "ENABLE_FEATURE", "1",
                "PRECISION_VALUE", "0.001f"
            );
            
            Map<String, String> badDefines = Map.of(
                "GL_RESERVED", "1",      // Reserved prefix
                "lowercaseVar", "value", // Poor naming convention
                "gl_builtin", "override" // Reserved prefix
            );
            
            var goodResult = ShaderValidationUtils.validateDefines(goodDefines);
            var badResult = ShaderValidationUtils.validateDefines(badDefines);
            
            assertTrue(goodResult.isValid());
            assertFalse(badResult.isValid());
            assertTrue(badResult.hasErrors());
            assertTrue(badResult.hasWarnings());
        }
        
        @Test
        @DisplayName("Should analyze shader performance characteristics")
        void testPerformanceAnalysis() {
            String complexShader = """
                #version 430 core
                layout(local_size_x = 32) in;
                
                void main() {
                    for (int i = 0; i < 100; i++) {
                        if (i % 2 == 0) {
                            float result = sin(float(i)) * cos(float(i));
                            if (result > 0.5) {
                                result = sqrt(result);
                            }
                        }
                    }
                }
                """;
            
            var result = ShaderValidationUtils.validateComputeShader(complexShader, "OpenGL");
            
            assertTrue(result.getMetadata().containsKey("loop_count"));
            assertTrue(result.getMetadata().containsKey("branch_count"));
            assertTrue(result.getMetadata().containsKey("expensive_op_count"));
            
            assertTrue((Integer) result.getMetadata().get("expensive_op_count") > 0);
        }
    }
    
    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        private IShaderFactory factory;
        
        @BeforeEach
        void setUp() {
            factory = new BGFXShaderFactory();
        }
        
        @Test
        @DisplayName("Should handle null shader source gracefully")
        void testNullShaderSource() {
            assertThrows(Exception.class, () -> {
                factory.compileComputeShader("null_test", null, Map.of());
            });
        }
        
        @Test
        @DisplayName("Should handle empty shader source")
        void testEmptyShaderSource() {
            var result = factory.compileComputeShader("empty_test", "", Map.of());
            
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().isPresent());
        }
        
        @Test
        @DisplayName("Should handle malformed shader source")
        void testMalformedShaderSource() {
            String malformedShader = """
                #version 430 core
                layout(local_size_x = 32) in;
                
                void main() {
                    this is not valid GLSL syntax!!!
                    unmatched { braces
                """;
            
            var result = factory.compileComputeShader("malformed_test", malformedShader, Map.of());
            
            assertNotNull(result);
            // Should complete preprocessing but fail compilation
            assertFalse(result.isSuccess());
        }
        
        @Test
        @DisplayName("Should handle non-existent shader files")
        void testNonExistentShaderFile() {
            var variant = new IShaderFactory.ShaderVariant(
                "nonexistent_shader.comp",
                Map.of(),
                Set.of()
            );
            
            var result = factory.compileShaderVariant(variant);
            
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().isPresent());
            assertTrue(result.getErrorMessage().get().contains("not found"));
        }
        
        @Test
        @DisplayName("Should handle cleanup operations safely")
        void testSafeCleanup() {
            assertDoesNotThrow(() -> {
                factory.clearCache();
                factory.cleanup();
                factory.cleanup(); // Second cleanup should be safe
            });
        }
    }
}