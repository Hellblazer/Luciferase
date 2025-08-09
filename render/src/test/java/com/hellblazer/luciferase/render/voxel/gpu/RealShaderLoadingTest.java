package com.hellblazer.luciferase.render.voxel.gpu;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test loading and compilation of actual WGSL shaders
 */
public class RealShaderLoadingTest {
    private static final Logger log = LoggerFactory.getLogger(RealShaderLoadingTest.class);
    
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    
    @BeforeEach
    public void setup() throws Exception {
        context = new WebGPUContext();
        if (!context.isAvailable()) {
            return;
        }
        context.initialize().get(5, TimeUnit.SECONDS);
        shaderManager = new ComputeShaderManager(context);
    }
    
    @AfterEach
    public void tearDown() {
        if (shaderManager != null) {
            shaderManager.cleanup();
        }
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    @DisplayName("Load ray_marching.wgsl from resources")
    public void testLoadRayMarchingShader() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Check if shader exists
        InputStream is = getClass().getResourceAsStream("/shaders/esvo/ray_marching.wgsl");
        assertNotNull(is, "ray_marching.wgsl should exist in resources");
        
        String shaderCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(shaderCode.contains("traverseOctree"), "Shader should contain traverseOctree function");
        assertTrue(shaderCode.contains("@compute"), "Shader should be a compute shader");
        
        // Try to load and compile
        var future = shaderManager.loadShaderFromResource("/shaders/esvo/ray_marching.wgsl");
        var module = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(module, "Shader module should be created");
        log.info("Successfully loaded ray_marching.wgsl");
    }
    
    @Test
    @DisplayName("Load voxelization.wgsl from resources")
    public void testLoadVoxelizationShader() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        InputStream is = getClass().getResourceAsStream("/shaders/esvo/voxelization.wgsl");
        assertNotNull(is, "voxelization.wgsl should exist in resources");
        
        String shaderCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(shaderCode.contains("voxelizeTriangle"), "Shader should contain voxelizeTriangle function");
        assertTrue(shaderCode.contains("triangleBoxIntersection"), "Shader should contain SAT intersection");
        
        var future = shaderManager.loadShaderFromResource("/shaders/esvo/voxelization.wgsl");
        var module = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(module, "Shader module should be created");
        log.info("Successfully loaded voxelization.wgsl");
    }
    
    @Test
    @DisplayName("Load sparse_octree.wgsl from resources")
    public void testLoadSparseOctreeShader() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        InputStream is = getClass().getResourceAsStream("/shaders/esvo/sparse_octree.wgsl");
        assertNotNull(is, "sparse_octree.wgsl should exist in resources");
        
        String shaderCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(shaderCode.contains("buildOctreeNode"), "Shader should contain buildOctreeNode function");
        assertTrue(shaderCode.contains("regionHasVoxels"), "Shader should contain voxel checking");
        
        var future = shaderManager.loadShaderFromResource("/shaders/esvo/sparse_octree.wgsl");
        var module = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(module, "Shader module should be created");
        log.info("Successfully loaded sparse_octree.wgsl");
    }
    
    @Test
    @DisplayName("Load visibility.wgsl from resources")
    public void testLoadVisibilityShader() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        InputStream is = getClass().getResourceAsStream("/shaders/esvo/visibility.wgsl");
        assertNotNull(is, "visibility.wgsl should exist in resources");
        
        String shaderCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(shaderCode.contains("frustumCullAABB"), "Shader should contain frustum culling");
        assertTrue(shaderCode.contains("calculateLOD"), "Shader should contain LOD calculation");
        
        var future = shaderManager.loadShaderFromResource("/shaders/esvo/visibility.wgsl");
        var module = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(module, "Shader module should be created");
        log.info("Successfully loaded visibility.wgsl");
    }
    
    @Test
    @DisplayName("Load shading.wgsl from resources")
    public void testLoadShadingShader() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        InputStream is = getClass().getResourceAsStream("/shaders/esvo/shading.wgsl");
        assertNotNull(is, "shading.wgsl should exist in resources");
        
        String shaderCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(shaderCode.contains("calculateBRDF"), "Shader should contain PBR BRDF");
        assertTrue(shaderCode.contains("fresnelSchlick"), "Shader should contain Fresnel calculation");
        assertTrue(shaderCode.contains("distributionGGX"), "Shader should contain GGX distribution");
        
        var future = shaderManager.loadShaderFromResource("/shaders/esvo/shading.wgsl");
        var module = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(module, "Shader module should be created");
        log.info("Successfully loaded shading.wgsl");
    }
    
    @Test
    @DisplayName("Verify all ESVO shaders can be loaded")
    public void testLoadAllESVOShaders() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        var future = shaderManager.loadESVOShaders();
        future.get(5, TimeUnit.SECONDS);
        
        // Verify shaders are cached
        assertNotNull(shaderManager.getShader("ray_marching.wgsl"));
        assertNotNull(shaderManager.getShader("voxelization.wgsl"));
        assertNotNull(shaderManager.getShader("sparse_octree.wgsl"));
        assertNotNull(shaderManager.getShader("visibility.wgsl"));
        
        log.info("All ESVO shaders loaded successfully");
    }
}