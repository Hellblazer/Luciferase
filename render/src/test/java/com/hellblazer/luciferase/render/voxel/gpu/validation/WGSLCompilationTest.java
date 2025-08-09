package com.hellblazer.luciferase.render.voxel.gpu.validation;

import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.render.voxel.gpu.validation.ShaderValidator.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests WGSL shader compilation and validation.
 * Verifies shader structure, entry points, and resource bindings.
 */
public class WGSLCompilationTest {
    private static final Logger log = LoggerFactory.getLogger(WGSLCompilationTest.class);
    
    private ShaderValidator validator;
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    
    @BeforeEach
    void setUp() {
        validator = new ShaderValidator();
        context = new WebGPUContext();
        context.initialize().join();
        shaderManager = new ComputeShaderManager(context);
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    void testVoxelizationShaderStructure() {
        String shaderPath = "/shaders/esvo/voxelization.wgsl";
        ValidationResult result = validator.validateShaderResource(shaderPath);
        
        assertTrue(result.valid, "Voxelization shader should be valid");
        assertNotNull(result.info);
        
        // Check structs
        assertTrue(result.info.structs.containsKey("Triangle"), "Should have Triangle struct");
        assertTrue(result.info.structs.containsKey("VoxelParams"), "Should have VoxelParams struct");
        
        // Check entry point
        assertTrue(result.info.entryPoints.containsKey("main"), "Should have main entry point");
        EntryPointInfo entryPoint = result.info.entryPoints.get("main");
        assertEquals("compute", entryPoint.stage, "Should be compute shader");
        assertNotNull(entryPoint.workgroupSize, "Should have workgroup size");
        assertEquals(64, entryPoint.workgroupSize[0], "Workgroup X should be 64");
        
        // Check bindings
        assertFalse(result.info.bindings.isEmpty(), "Should have bindings");
        
        // Log warnings if any
        if (!result.warnings.isEmpty()) {
            log.info("Voxelization shader warnings: {}", result.warnings);
        }
    }
    
    @Test
    void testSparseOctreeShaderStructure() {
        String shaderPath = "/shaders/esvo/sparse_octree.wgsl";
        ValidationResult result = validator.validateShaderResource(shaderPath);
        
        assertTrue(result.valid, "Sparse octree shader should be valid");
        assertNotNull(result.info);
        
        // Check structs
        assertTrue(result.info.structs.containsKey("OctreeNode"), "Should have OctreeNode struct");
        assertTrue(result.info.structs.containsKey("OctreeParams"), "Should have OctreeParams struct");
        assertTrue(result.info.structs.containsKey("BuildState"), "Should have BuildState struct");
        
        // Check functions
        assertTrue(result.info.functions.containsKey("buildOctreeNode"), "Should have buildOctreeNode function");
        assertFalse(result.info.functions.get("buildOctreeNode").isRecursive, 
                   "buildOctreeNode should not be recursive");
        
        // Check entry point
        assertTrue(result.info.entryPoints.containsKey("main"), "Should have main entry point");
        
        // Ensure no errors
        assertTrue(result.errors.isEmpty(), "Should have no errors: " + result.errors);
    }
    
    @Test
    void testRayMarchingShaderStructure() {
        String shaderPath = "/shaders/esvo/ray_marching.wgsl";
        ValidationResult result = validator.validateShaderResource(shaderPath);
        
        assertTrue(result.valid, "Ray marching shader should be valid");
        assertNotNull(result.info);
        
        // Check structs - ray_marching.wgsl has Uniforms, OctreeNode, and RayHit
        assertTrue(result.info.structs.containsKey("Uniforms"), "Should have Uniforms struct");
        assertTrue(result.info.structs.containsKey("OctreeNode"), "Should have OctreeNode struct");
        assertTrue(result.info.structs.containsKey("RayHit"), "Should have RayHit struct");
        
        // Check critical functions - these functions actually exist in the shader
        assertTrue(result.info.functions.containsKey("getRayDirection"), 
                   "Should have getRayDirection function");
        assertTrue(result.info.functions.containsKey("traverseOctree"), 
                   "Should have traverseOctree function");
        
        // Check entry point
        assertTrue(result.info.entryPoints.containsKey("main"), "Should have main entry point");
        EntryPointInfo entryPoint = result.info.entryPoints.get("main");
        assertEquals("compute", entryPoint.stage, "Should be compute shader");
        
        // Verify workgroup size
        assertNotNull(entryPoint.workgroupSize, "Should have workgroup size");
        int total = entryPoint.workgroupSize[0] * entryPoint.workgroupSize[1];
        assertEquals(64, total, "Total workgroup size should be 64");
    }
    
    @Test
    void testVisibilityShaderStructure() {
        String shaderPath = "/shaders/esvo/visibility.wgsl";
        ValidationResult result = validator.validateShaderResource(shaderPath);
        
        assertTrue(result.valid, "Visibility shader should be valid");
        assertNotNull(result.info);
        
        // Check structs
        assertTrue(result.info.structs.containsKey("Frustum"), "Should have Frustum struct");
        assertTrue(result.info.structs.containsKey("OctreeNode"), "Should have OctreeNode struct");
        assertTrue(result.info.structs.containsKey("VisibilityResult"), "Should have VisibilityResult struct");
        
        // Check functions
        assertTrue(result.info.functions.containsKey("frustumCullAABB"), 
                   "Should have frustumCullAABB function");
        assertTrue(result.info.functions.containsKey("hierarchicalCull"), 
                   "Should have hierarchicalCull function");
        assertFalse(result.info.functions.get("hierarchicalCull").isRecursive, 
                   "hierarchicalCull should not be recursive after fix");
        
        // Check entry point
        assertTrue(result.info.entryPoints.containsKey("main"), "Should have main entry point");
        
        // Ensure no recursive functions
        for (FunctionInfo func : result.info.functions.values()) {
            assertFalse(func.isRecursive, "No functions should be recursive: " + func.name);
        }
    }
    
    @Test
    void testShadingShaderStructure() {
        String shaderPath = "/shaders/esvo/shading.wgsl";
        ValidationResult result = validator.validateShaderResource(shaderPath);
        
        assertTrue(result.valid, "Shading shader should be valid");
        assertNotNull(result.info);
        
        // Check structs
        assertTrue(result.info.structs.containsKey("LightingParams"), "Should have LightingParams struct");
        assertTrue(result.info.structs.containsKey("GBuffer"), "Should have GBuffer struct");
        
        // Check PBR functions
        assertTrue(result.info.functions.containsKey("calculateBRDF"), 
                   "Should have calculateBRDF function");
        assertTrue(result.info.functions.containsKey("fresnelSchlick"), 
                   "Should have fresnelSchlick function");
        assertTrue(result.info.functions.containsKey("distributionGGX"), 
                   "Should have distributionGGX function");
        
        // Check entry point
        assertTrue(result.info.entryPoints.containsKey("main"), "Should have main entry point");
        EntryPointInfo entryPoint = result.info.entryPoints.get("main");
        assertEquals("compute", entryPoint.stage, "Should be compute shader");
        
        // Verify no forbidden operations
        assertFalse(result.errors.stream().anyMatch(e -> e.contains("textureSample")),
                   "Should not use textureSample in compute shader");
    }
    
    @Test
    void testAllShadersHaveEntryPoints() {
        String[] shaderPaths = {
            "/shaders/esvo/voxelization.wgsl",
            "/shaders/esvo/sparse_octree.wgsl",
            "/shaders/esvo/ray_marching.wgsl",
            "/shaders/esvo/visibility.wgsl",
            "/shaders/esvo/shading.wgsl"
        };
        
        for (String path : shaderPaths) {
            ValidationResult result = validator.validateShaderResource(path);
            String shaderName = path.substring(path.lastIndexOf('/') + 1);
            
            assertFalse(result.info.entryPoints.isEmpty(), 
                       shaderName + " should have at least one entry point");
            
            // All our shaders should be compute shaders
            for (EntryPointInfo ep : result.info.entryPoints.values()) {
                assertEquals("compute", ep.stage, 
                           shaderName + " entry point should be compute stage");
                assertNotNull(ep.workgroupSize, 
                            shaderName + " should have workgroup size");
            }
        }
    }
    
    @Test
    void testBindingUniqueness() {
        String[] shaderPaths = {
            "/shaders/esvo/voxelization.wgsl",
            "/shaders/esvo/sparse_octree.wgsl",
            "/shaders/esvo/ray_marching.wgsl",
            "/shaders/esvo/visibility.wgsl",
            "/shaders/esvo/shading.wgsl"
        };
        
        for (String path : shaderPaths) {
            ValidationResult result = validator.validateShaderResource(path);
            String shaderName = path.substring(path.lastIndexOf('/') + 1);
            
            // Check for duplicate binding errors
            long duplicateErrors = result.errors.stream()
                .filter(e -> e.contains("Duplicate binding"))
                .count();
            
            assertEquals(0, duplicateErrors, 
                        shaderName + " should have no duplicate bindings");
        }
    }
    
    @Test
    void testWorkgroupSizeLimits() {
        String[] shaderPaths = {
            "/shaders/esvo/voxelization.wgsl",
            "/shaders/esvo/sparse_octree.wgsl",
            "/shaders/esvo/ray_marching.wgsl",
            "/shaders/esvo/visibility.wgsl",
            "/shaders/esvo/shading.wgsl"
        };
        
        for (String path : shaderPaths) {
            ValidationResult result = validator.validateShaderResource(path);
            String shaderName = path.substring(path.lastIndexOf('/') + 1);
            
            for (EntryPointInfo ep : result.info.entryPoints.values()) {
                if (ep.workgroupSize != null) {
                    int total = ep.workgroupSize[0] * ep.workgroupSize[1] * ep.workgroupSize[2];
                    
                    // WebGPU minimum limit is 128, recommended is 256
                    assertTrue(total <= 256, 
                              shaderName + " workgroup size " + total + " should be <= 256");
                    assertTrue(total >= 1, 
                              shaderName + " workgroup size should be >= 1");
                    
                    // Check individual dimensions (common limits)
                    assertTrue(ep.workgroupSize[0] <= 256, 
                              shaderName + " workgroup X should be <= 256");
                    assertTrue(ep.workgroupSize[1] <= 256, 
                              shaderName + " workgroup Y should be <= 256");
                    assertTrue(ep.workgroupSize[2] <= 64, 
                              shaderName + " workgroup Z should be <= 64");
                }
            }
        }
    }
    
    @Test
    void testNoRecursiveFunctions() {
        String[] shaderPaths = {
            "/shaders/esvo/voxelization.wgsl",
            "/shaders/esvo/sparse_octree.wgsl",
            "/shaders/esvo/ray_marching.wgsl",
            "/shaders/esvo/visibility.wgsl",
            "/shaders/esvo/shading.wgsl"
        };
        
        for (String path : shaderPaths) {
            ValidationResult result = validator.validateShaderResource(path);
            String shaderName = path.substring(path.lastIndexOf('/') + 1);
            
            for (FunctionInfo func : result.info.functions.values()) {
                assertFalse(func.isRecursive, 
                           shaderName + " function " + func.name + " should not be recursive");
            }
            
            // Check for recursive function errors
            long recursiveErrors = result.errors.stream()
                .filter(e -> e.contains("Recursive function"))
                .count();
            
            assertEquals(0, recursiveErrors, 
                        shaderName + " should have no recursive functions");
        }
    }
    
    @Test
    void testShaderCompilationIntegration() {
        // Test actual compilation with WebGPU context
        String[] shaderPaths = {
            "/shaders/esvo/voxelization.wgsl",
            "/shaders/esvo/sparse_octree.wgsl",
            "/shaders/esvo/ray_marching.wgsl",
            "/shaders/esvo/visibility.wgsl",
            "/shaders/esvo/shading.wgsl"
        };
        
        for (String path : shaderPaths) {
            String shaderName = path.substring(path.lastIndexOf('/') + 1);
            
            // First validate structure
            ValidationResult result = validator.validateShaderResource(path);
            assertTrue(result.valid, shaderName + " validation should pass");
            
            // Then try actual compilation
            assertDoesNotThrow(() -> {
                shaderManager.loadShaderFromResource(path).join();
            }, shaderName + " should compile without errors");
            
            log.info("Successfully validated and compiled {}", shaderName);
        }
    }
}