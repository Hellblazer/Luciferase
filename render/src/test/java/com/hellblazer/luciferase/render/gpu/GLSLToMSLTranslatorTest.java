package com.hellblazer.luciferase.render.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GLSL to MSL shader translator
 */
public class GLSLToMSLTranslatorTest {
    
    private GLSLToMSLTranslator translator;
    
    @BeforeEach
    void setUp() {
        translator = new GLSLToMSLTranslator();
    }
    
    @Test
    void testDetectShaderTypes() {
        // Test file extension detection
        assertEquals(GLSLToMSLTranslator.ShaderType.COMPUTE, 
                     GLSLToMSLTranslator.detectShaderType("test.comp", ""));
        assertEquals(GLSLToMSLTranslator.ShaderType.FRAGMENT, 
                     GLSLToMSLTranslator.detectShaderType("test.frag", ""));
        assertEquals(GLSLToMSLTranslator.ShaderType.VERTEX, 
                     GLSLToMSLTranslator.detectShaderType("test.vert", ""));
        
        // Test content analysis
        assertEquals(GLSLToMSLTranslator.ShaderType.COMPUTE,
                     GLSLToMSLTranslator.detectShaderType("test.glsl", "layout(local_size_x = 32)"));
        assertEquals(GLSLToMSLTranslator.ShaderType.FRAGMENT,
                     GLSLToMSLTranslator.detectShaderType("test.glsl", "void main() { gl_FragColor = vec4(1.0); }"));
        assertEquals(GLSLToMSLTranslator.ShaderType.VERTEX,
                     GLSLToMSLTranslator.detectShaderType("test.glsl", "void main() { gl_Position = vec4(0.0); }"));
    }
    
    @Test
    void testBasicComputeShaderTranslation() {
        var glslSource = """
            #version 430 core
            layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
            
            layout(std430, binding = 0) readonly buffer InputBuffer {
                uint data[];
            } inputBuffer;
            
            layout(std430, binding = 1) writeonly buffer OutputBuffer {
                uint results[];
            } outputBuffer;
            
            uniform uint numElements;
            
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index >= numElements) return;
                
                outputBuffer.results[index] = inputBuffer.data[index] * 2u;
            }
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        // Verify MSL header
        assertTrue(mslResult.contains("#include <metal_stdlib>"));
        assertTrue(mslResult.contains("#include <metal_compute>"));
        assertTrue(mslResult.contains("using namespace metal;"));
        
        // Verify work group size comment
        assertTrue(mslResult.contains("local_size_x = 32"));
        
        // Verify built-in variable translation
        assertTrue(mslResult.contains("thread_position_in_grid"));
        assertFalse(mslResult.contains("gl_GlobalInvocationID"));
        
        // Verify buffer structure translation
        assertTrue(mslResult.contains("struct InputBuffer"));
        assertTrue(mslResult.contains("struct OutputBuffer"));
        
        // Verify uniform translation
        assertTrue(mslResult.contains("// uniform uint numElements"));
        
        // Verify main function signature
        assertTrue(mslResult.contains("kernel void main_compute("));
        assertTrue(mslResult.contains("[[buffer(0)]]"));
        assertTrue(mslResult.contains("thread_position_in_grid"));
    }
    
    @Test
    void testBuiltinVariableReplacement() {
        var glslSource = """
            void main() {
                uint index = gl_GlobalInvocationID.x;
                uint local = gl_LocalInvocationID.y;
                uint group = gl_WorkGroupID.z;
            }
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        assertTrue(mslResult.contains("thread_position_in_grid.x"));
        assertTrue(mslResult.contains("thread_position_in_threadgroup.y"));
        assertTrue(mslResult.contains("threadgroup_position_in_grid.z"));
        
        assertFalse(mslResult.contains("gl_GlobalInvocationID"));
        assertFalse(mslResult.contains("gl_LocalInvocationID"));
        assertFalse(mslResult.contains("gl_WorkGroupID"));
    }
    
    @Test
    void testBuiltinFunctionReplacement() {
        var glslSource = """
            void main() {
                uint count = bitCount(mask);
                atomicAdd(counter, 1u);
            }
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        assertTrue(mslResult.contains("popcount(mask)"));
        assertTrue(mslResult.contains("atomic_fetch_add_explicit(counter, 1u, memory_order_relaxed)"));
        
        assertFalse(mslResult.contains("bitCount"));
        assertFalse(mslResult.contains("atomicAdd(counter, 1u);"));
    }
    
    @Test
    void testSharedMemoryTranslation() {
        var glslSource = """
            shared uint sharedData[256];
            shared float sharedValues;
            
            void main() {
                barrier();
            }
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        assertTrue(mslResult.contains("threadgroup uint sharedData[256];"));
        assertTrue(mslResult.contains("threadgroup float sharedValues;"));
        assertTrue(mslResult.contains("threadgroup_barrier(mem_flags::mem_threadgroup)"));
        
        assertFalse(mslResult.contains("shared uint"));
        assertFalse(mslResult.contains("barrier()"));
    }
    
    @Test
    void testFragmentShaderTranslation() {
        var glslSource = """
            #version 430 core
            
            uniform sampler2D tex;
            in vec2 texCoord;
            out vec4 fragColor;
            
            void main() {
                fragColor = texture(tex, texCoord);
            }
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.FRAGMENT);
        
        assertTrue(mslResult.contains("fragment float4 main_fragment("));
        assertTrue(mslResult.contains("tex.sample("));
        assertFalse(mslResult.contains("#version"));
    }
    
    @Test
    void testTranslateShaderFileConvenience() {
        var glslSource = """
            #version 430 core
            layout(local_size_x = 64) in;
            
            void main() {
                uint index = gl_GlobalInvocationID.x;
            }
            """;
        
        var mslResult = translator.translateShaderFile("test.comp", glslSource);
        
        assertTrue(mslResult.contains("kernel void main_compute("));
        assertTrue(mslResult.contains("thread_position_in_grid"));
    }
    
    @Test
    void testComplexESVOStructures() {
        var glslSource = """
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
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        // Verify struct preservation
        assertTrue(mslResult.contains("struct ESVONode"));
        assertTrue(mslResult.contains("struct Ray"));
        assertTrue(mslResult.contains("struct NodeBuffer"));
        
        // Verify buffer binding comments
        assertTrue(mslResult.contains("// Buffer binding 0"));
    }
}