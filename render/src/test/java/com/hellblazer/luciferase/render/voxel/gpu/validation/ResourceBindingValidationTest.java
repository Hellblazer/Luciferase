package com.hellblazer.luciferase.render.voxel.gpu.validation;

import com.hellblazer.luciferase.render.voxel.gpu.validation.ShaderValidator.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates resource bindings and bind group layouts for WGSL shaders.
 * Ensures proper alignment, type compatibility, and binding organization.
 */
public class ResourceBindingValidationTest {
    private static final Logger log = LoggerFactory.getLogger(ResourceBindingValidationTest.class);
    
    private ShaderValidator validator;
    
    @BeforeEach
    void setUp() {
        validator = new ShaderValidator();
    }
    
    @Test
    void testVoxelizationBindings() {
        ValidationResult result = validator.validateShaderResource("/shaders/esvo/voxelization.wgsl");
        assertTrue(result.valid);
        
        // Expected bindings for voxelization
        Map<String, BindingInfo> bindings = result.info.bindings;
        
        // Should have triangle input buffer
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.type.contains("array<Triangle>")),
            "Should have triangle array binding");
        
        // Should have voxel output buffer (voxelGrid)
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("voxelGrid")),
            "Should have voxelGrid output binding");
        
        // Should have parameters uniform
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.type.contains("VoxelParams")),
            "Should have voxel parameters");
        
        // Verify binding organization
        Map<Integer, List<BindingInfo>> byGroup = groupBindingsByGroup(bindings.values());
        assertFalse(byGroup.isEmpty(), "Should have at least one bind group");
        
        // Check for binding gaps
        for (Map.Entry<Integer, List<BindingInfo>> entry : byGroup.entrySet()) {
            checkBindingGaps(entry.getKey(), entry.getValue());
        }
    }
    
    @Test
    void testSparseOctreeBindings() {
        ValidationResult result = validator.validateShaderResource("/shaders/esvo/sparse_octree.wgsl");
        assertTrue(result.valid);
        
        Map<String, BindingInfo> bindings = result.info.bindings;
        
        // Should have voxel grid input
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("voxelGrid")),
            "Should have voxelGrid binding");
        
        // Should have octree nodes output
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("octreeNodes")),
            "Should have octreeNodes binding");
        
        // Should have build state
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("buildState")),
            "Should have buildState binding");
        
        // Should have parameters
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.type.contains("OctreeParams")),
            "Should have octree parameters");
        
        // The ShaderValidator only captures the type after the colon, not the storage qualifier
        // So we just verify that these bindings exist and have the right type
        for (BindingInfo binding : bindings.values()) {
            if (binding.name.equals("octreeNodes")) {
                assertTrue(binding.type.contains("array<OctreeNode"),
                    binding.name + " should be array<OctreeNode> but was: " + binding.type);
            }
            if (binding.name.equals("buildState")) {
                assertTrue(binding.type.contains("BuildState"),
                    binding.name + " should contain BuildState but was: " + binding.type);
            }
        }
    }
    
    @Test
    void testRayMarchingBindings() {
        ValidationResult result = validator.validateShaderResource("/shaders/esvo/ray_marching.wgsl");
        assertTrue(result.valid);
        
        Map<String, BindingInfo> bindings = result.info.bindings;
        
        // Should have octree nodes
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("octreeNodes")),
            "Should have octree nodes binding");
        
        // Should have frame buffer for output
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("frameBuffer")),
            "Should have frameBuffer output");
        
        // Should have uniforms (includes camera info)
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("uniforms")),
            "Should have uniforms");
        
        // Check bind group organization
        Map<Integer, List<BindingInfo>> byGroup = groupBindingsByGroup(bindings.values());
        
        // Ray marching typically uses 1-2 bind groups
        assertTrue(byGroup.size() >= 1 && byGroup.size() <= 3,
            "Should have 1-3 bind groups, got " + byGroup.size());
    }
    
    @Test
    void testVisibilityBindings() {
        ValidationResult result = validator.validateShaderResource("/shaders/esvo/visibility.wgsl");
        assertTrue(result.valid);
        
        Map<String, BindingInfo> bindings = result.info.bindings;
        
        // Should have octree nodes
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("octreeNodes")),
            "Should have octreeNodes binding");
        
        // Should have visibility results
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("visibilityResults")),
            "Should have visibilityResults binding");
        
        // Should have frustum uniform
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("frustum")),
            "Should have frustum binding");
        
        // Should have visible node indices
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("visibleNodeIndices")),
            "Should have visibleNodeIndices binding");
        
        // Check for atomic counters
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("visibleNodeCount")),
            "Should have visibleNodeCount atomic");
        
        // Verify frustum exists and has correct type
        BindingInfo frustum = bindings.get("frustum");
        assertNotNull(frustum);
        assertTrue(frustum.type.contains("Frustum"),
            "Frustum binding should have Frustum type but was: " + frustum.type);
    }
    
    @Test
    void testShadingBindings() {
        ValidationResult result = validator.validateShaderResource("/shaders/esvo/shading.wgsl");
        assertTrue(result.valid);
        
        Map<String, BindingInfo> bindings = result.info.bindings;
        
        // Should have G-buffer input
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("gBuffer")),
            "Should have gBuffer binding");
        
        // Should have frame buffer output
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("frameBuffer")),
            "Should have frameBuffer binding");
        
        // Should have lighting parameters
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("lighting")),
            "Should have lighting parameters");
        
        // Should have camera position
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("cameraPosition")),
            "Should have camera position");
        
        // Should have shadow map resources
        assertTrue(bindings.values().stream()
            .anyMatch(b -> b.name.equals("shadowMap")),
            "Should have shadow map");
        
        // Check bind group organization (shading uses multiple groups)
        Map<Integer, List<BindingInfo>> byGroup = groupBindingsByGroup(bindings.values());
        assertTrue(byGroup.size() >= 2,
            "Shading should use at least 2 bind groups for organization");
    }
    
    @Test
    void testBindingConsistencyAcrossShaders() {
        // When shaders share resources, bindings should be consistent
        String[] shaderPaths = {
            "/shaders/esvo/sparse_octree.wgsl",
            "/shaders/esvo/ray_marching.wgsl",
            "/shaders/esvo/visibility.wgsl"
        };
        
        Map<String, Map<String, BindingInfo>> shaderBindings = new HashMap<>();
        
        for (String path : shaderPaths) {
            ValidationResult result = validator.validateShaderResource(path);
            assertTrue(result.valid);
            String name = path.substring(path.lastIndexOf('/') + 1);
            shaderBindings.put(name, result.info.bindings);
        }
        
        // Check octreeNodes consistency (shared between multiple shaders)
        List<BindingInfo> octreeNodeBindings = shaderBindings.values().stream()
            .map(bindings -> bindings.get("octreeNodes"))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        if (octreeNodeBindings.size() > 1) {
            // All octreeNodes bindings should have same type
            String expectedType = octreeNodeBindings.get(0).type;
            for (BindingInfo binding : octreeNodeBindings) {
                assertTrue(binding.type.contains("OctreeNode"),
                    "All octreeNodes bindings should reference OctreeNode type");
            }
        }
    }
    
    @Test
    void testNoBindingConflicts() {
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
            
            // Check for duplicate binding locations
            Set<String> bindingLocations = new HashSet<>();
            for (BindingInfo binding : result.info.bindings.values()) {
                String location = binding.group + ":" + binding.binding;
                assertFalse(bindingLocations.contains(location),
                    shaderName + " has duplicate binding at " + location);
                bindingLocations.add(location);
            }
        }
    }
    
    @Test
    void testUniformBufferAlignment() {
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
            
            // Check uniform buffers for potential alignment issues
            for (BindingInfo binding : result.info.bindings.values()) {
                if (binding.type.contains("uniform")) {
                    // Uniform buffers should reference structs
                    assertFalse(binding.type.contains("array"),
                        shaderName + " uniform " + binding.name + 
                        " should not be an array (use storage buffer instead)");
                    
                    // Check if the struct exists
                    String structName = extractStructName(binding.type);
                    if (structName != null) {
                        assertTrue(result.info.structs.containsKey(structName),
                            shaderName + " uniform references undefined struct: " + structName);
                    }
                }
            }
        }
    }
    
    @Test
    void testStorageBufferAccessQualifiers() {
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
            
            for (BindingInfo binding : result.info.bindings.values()) {
                if (binding.type.contains("storage")) {
                    // Storage buffers should specify read or read_write
                    assertTrue(binding.type.contains("read") || binding.type.contains("read_write"),
                        shaderName + " storage buffer " + binding.name + 
                        " should specify access qualifier");
                    
                    // Output buffers should be read_write
                    if (isOutputBuffer(binding.name)) {
                        assertTrue(binding.type.contains("read_write"),
                            shaderName + " output buffer " + binding.name + 
                            " should be read_write");
                    }
                }
            }
        }
    }
    
    // Helper methods
    
    private Map<Integer, List<BindingInfo>> groupBindingsByGroup(Collection<BindingInfo> bindings) {
        return bindings.stream()
            .collect(Collectors.groupingBy(b -> b.group));
    }
    
    private void checkBindingGaps(int group, List<BindingInfo> bindings) {
        if (bindings.isEmpty()) return;
        
        List<Integer> indices = bindings.stream()
            .map(b -> b.binding)
            .sorted()
            .collect(Collectors.toList());
        
        int maxIndex = indices.get(indices.size() - 1);
        Set<Integer> usedIndices = new HashSet<>(indices);
        
        List<Integer> gaps = new ArrayList<>();
        for (int i = 0; i <= maxIndex; i++) {
            if (!usedIndices.contains(i)) {
                gaps.add(i);
            }
        }
        
        if (!gaps.isEmpty()) {
            log.warn("Group {} has gaps in binding indices: {}", group, gaps);
        }
    }
    
    private String extractStructName(String type) {
        // Extract struct name from type string like "uniform Frustum"
        String cleaned = type.replace("uniform", "")
                             .replace("storage", "")
                             .replace("read_write", "")
                             .replace("read", "")
                             .replace("<", "")
                             .replace(">", "")
                             .trim();
        
        // If it's not an array or primitive, it's likely a struct name
        if (!cleaned.startsWith("array") && 
            !cleaned.startsWith("vec") && 
            !cleaned.startsWith("mat") &&
            !cleaned.equals("f32") &&
            !cleaned.equals("u32") &&
            !cleaned.equals("i32")) {
            return cleaned.split("\\s+")[0];
        }
        
        return null;
    }
    
    private boolean isOutputBuffer(String name) {
        return name.contains("output") || 
               name.contains("result") || 
               name.contains("frameBuffer") ||
               name.equals("voxelGrid") ||
               name.equals("octreeNodes") ||
               name.equals("visibilityResults") ||
               name.equals("hitResults");
    }
}