package com.hellblazer.luciferase.render.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Integration test for GLSL to MSL shader translation within the GPU abstraction layer
 */
public class ShaderTranslationIntegrationTest {
    
    private GLSLToMSLTranslator translator;
    private MockGPUContext openglContext;
    private MockGPUContext metalContext;
    
    @BeforeEach
    void setUp() {
        translator = new GLSLToMSLTranslator();
        openglContext = new MockGPUContext(GPUConfig.Backend.OPENGL);
        metalContext = new MockGPUContext(GPUConfig.Backend.BGFX_METAL);
        
        // Initialize contexts
        var openglConfig = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.OPENGL)
            .withHeadless(true)
            .build();
        var metalConfig = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .build();
        
        assertTrue(openglContext.initialize(openglConfig));
        assertTrue(metalContext.initialize(metalConfig));
    }
    
    @Test
    void testESVOTraverseShaderTranslation() {
        // Load the GLSL shader
        var glslPath = "src/main/resources/shaders/esvo/traverse.comp";
        
        String glslSource;
        try {
            glslSource = Files.readString(Paths.get(glslPath));
        } catch (Exception e) {
            // Use a mock source if file isn't found
            glslSource = """
                #version 430 core
                layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
                
                layout(std430, binding = 0) readonly buffer NodeBuffer {
                    uint nodes[];
                } nodeBuffer;
                
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    // Mock traversal logic
                }
                """;
        }
        
        // Translate to MSL
        var mslSource = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        // Validate MSL source
        assertTrue(mslSource.contains("#include <metal_stdlib>"));
        assertTrue(mslSource.contains("using namespace metal;"));
        assertTrue(mslSource.contains("kernel void main_compute"));
        assertTrue(mslSource.contains("thread_position_in_grid"));
        assertFalse(mslSource.contains("gl_GlobalInvocationID"));
        
        // Test compilation with appropriate context
        var openglFactory = openglContext.getShaderFactory();
        var metalFactory = metalContext.getShaderFactory();
        
        // OpenGL context should accept GLSL
        var openglResult = openglFactory.compileComputeShader("traverse", glslSource, Map.of());
        assertTrue(openglResult.isSuccess());
        
        // Metal context should accept MSL
        var metalResult = metalFactory.compileComputeShader("traverse", mslSource, Map.of());
        assertTrue(metalResult.isSuccess());
        
        // Metal context should reject GLSL (backend-specific validation)
        var invalidResult = metalFactory.compileComputeShader("traverse", glslSource, Map.of());
        assertFalse(invalidResult.isSuccess());
    }
    
    @Test
    void testESVOBeamShaderTranslation() {
        var glslSource = """
            #version 430 core
            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
            
            struct Beam {
                vec3 origin;
                vec3 direction;
                float coherence;
            };
            
            layout(std430, binding = 0) readonly buffer BeamBuffer {
                Beam beams[];
            } beamBuffer;
            
            shared uint sharedData[256];
            
            void main() {
                uint beamIndex = gl_GlobalInvocationID.x;
                uint localIndex = gl_LocalInvocationIndex;
                
                barrier();
                atomicAdd(sharedData[0], 1u);
            }
            """;
        
        var mslSource = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        // Validate MSL transformations
        assertTrue(mslSource.contains("kernel void main_compute"));
        assertTrue(mslSource.contains("thread_position_in_grid"));
        assertTrue(mslSource.contains("thread_index_in_threadgroup"));
        assertTrue(mslSource.contains("threadgroup uint sharedData[256]"));
        assertTrue(mslSource.contains("threadgroup_barrier"));
        assertTrue(mslSource.contains("atomic_fetch_add_explicit"));
        assertTrue(mslSource.contains("memory_order_relaxed"));
        
        // Should not contain GLSL-specific elements
        assertFalse(mslSource.contains("gl_GlobalInvocationID"));
        assertFalse(mslSource.contains("gl_LocalInvocationIndex"));
        assertFalse(mslSource.contains("shared uint"));
        assertFalse(mslSource.contains("barrier()"));
        assertFalse(mslSource.contains("atomicAdd("));
        
        // Test with factories
        var metalFactory = metalContext.getShaderFactory();
        var result = metalFactory.compileComputeShader("beam", mslSource, Map.of());
        assertTrue(result.isSuccess());
    }
    
    @Test
    void testFragmentShaderTranslation() {
        var glslSource = """
            #version 450 core
            
            in vec2 texCoord;
            out vec4 fragColor;
            
            uniform sampler2D tex;
            uniform vec3 cameraPos;
            
            void main() {
                vec4 color = texture(tex, texCoord);
                fragColor = color * vec4(cameraPos, 1.0);
            }
            """;
        
        var mslSource = translator.translateShader(glslSource, GLSLToMSLTranslator.ShaderType.FRAGMENT);
        
        // Validate fragment shader translation
        assertTrue(mslSource.contains("fragment float4 main_fragment"));
        assertTrue(mslSource.contains("VertexOut in [[stage_in]]"));
        assertTrue(mslSource.contains("tex.sample"));
        
        // Should not contain GLSL elements
        assertFalse(mslSource.contains("#version"));
        assertFalse(mslSource.contains("in vec2"));
        assertFalse(mslSource.contains("out vec4"));
        assertFalse(mslSource.contains("uniform sampler2D"));
        
        // Should not contain GLSL texture() function calls, but MSL [[texture(n)]] attributes are OK
        assertFalse(mslSource.contains("= texture("), "GLSL texture() function call should be replaced");
        assertFalse(mslSource.contains("texture(tex,"), "GLSL texture() function call should be replaced");
    }
    
    @Test
    void testShaderCompatibilityValidation() {
        var openglFactory = openglContext.getShaderFactory();
        var metalFactory = metalContext.getShaderFactory();
        
        // GLSL source should be compatible with OpenGL
        var glslSource = """
            #version 430 core
            layout(local_size_x = 32) in;
            void main() { }
            """;
        
        assertTrue(openglFactory.validateShaderCompatibility(glslSource));
        assertFalse(metalFactory.validateShaderCompatibility(glslSource));
        
        // MSL source should be compatible with Metal
        var mslSource = """
            #include <metal_stdlib>
            using namespace metal;
            kernel void main_compute() { }
            """;
        
        assertFalse(openglFactory.validateShaderCompatibility(mslSource));
        assertTrue(metalFactory.validateShaderCompatibility(mslSource));
    }
    
    @Test
    void testBackendInfoAndLanguageReporting() {
        var openglFactory = openglContext.getShaderFactory();
        var metalFactory = metalContext.getShaderFactory();
        
        // Verify backend info
        assertTrue(openglFactory.getBackendInfo().contains("OpenGL"));
        assertTrue(metalFactory.getBackendInfo().contains("Metal"));
        
        // Verify shader languages
        assertEquals("GLSL", openglFactory.getShaderLanguage());
        assertEquals("Metal Shading Language", metalFactory.getShaderLanguage());
    }
    
    @Test
    void testFullESVORenderingPipeline() {
        // Test the complete pipeline: GLSL -> MSL -> Compilation
        
        // 1. Load GLSL traverse shader
        var traverseGLSL = """
            #version 430 core
            layout(local_size_x = 32) in;
            
            struct ESVONode { uint data; };
            struct Ray { vec3 origin, direction; };
            
            layout(std430, binding = 0) readonly buffer Nodes { ESVONode nodes[]; };
            layout(std430, binding = 1) readonly buffer Rays { Ray rays[]; };
            
            void main() {
                uint rayIndex = gl_GlobalInvocationID.x;
                // Traversal logic here
            }
            """;
        
        // 2. Translate to MSL
        var traverseMSL = translator.translateShader(traverseGLSL, GLSLToMSLTranslator.ShaderType.COMPUTE);
        
        // 3. Compile for Metal backend
        var metalFactory = metalContext.getShaderFactory();
        var result = metalFactory.compileComputeShader("esvo_traverse", traverseMSL, Map.of(
            "ENABLE_STATISTICS", "1",
            "MAX_STACK_DEPTH", "23"
        ));
        
        assertTrue(result.isSuccess(), "MSL compilation should succeed");
        
        // 4. Verify the compiled shader has expected properties
        var shaderOpt = result.getShader();
        assertTrue(shaderOpt.isPresent(), "Shader compilation should produce a valid shader");
        var shader = shaderOpt.get();
        assertTrue(shader.isValid(), "Compiled shader should be valid");
        
        // Cast to MockShader to access test-specific methods
        if (shader instanceof MockShader mockShader) {
            assertTrue(mockShader.getDefines().containsKey("ENABLE_STATISTICS"));
            assertEquals("1", mockShader.getDefines().get("ENABLE_STATISTICS"));
        }
    }
}