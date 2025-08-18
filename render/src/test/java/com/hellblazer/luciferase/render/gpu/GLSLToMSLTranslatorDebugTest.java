package com.hellblazer.luciferase.render.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

/**
 * Debug test to see what the translator is actually producing
 */
public class GLSLToMSLTranslatorDebugTest {
    
    private GLSLToMSLTranslator translator;
    
    @BeforeEach
    void setUp() {
        translator = new GLSLToMSLTranslator();
    }
    
    @Test
    void debugBasicTranslation() {
        var glslSource = """
            #version 430 core
            layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
            
            layout(std430, binding = 0) readonly buffer InputBuffer {
                uint data[];
            } inputBuffer;
            
            void main() {
                uint index = gl_GlobalInvocationID.x;
            }
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        System.out.println("=== GLSL SOURCE ===");
        System.out.println(glslSource);
        System.out.println("\n=== MSL RESULT ===");
        System.out.println(mslResult);
        System.out.println("\n=== END ===");
    }
    
    @Test
    void debugBufferTranslation() {
        var glslSource = """
            struct ESVONode {
                uint packedData1;
                uint packedData2;
            };
            
            layout(std430, binding = 0) readonly buffer NodeBuffer {
                ESVONode nodes[];
            } nodeBuffer;
            """;
        
        var mslResult = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        System.out.println("=== BUFFER GLSL SOURCE ===");
        System.out.println(glslSource);
        System.out.println("\n=== BUFFER MSL RESULT ===");
        System.out.println(mslResult);
        System.out.println("\n=== END ===");
    }
}