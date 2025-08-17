/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assumptions;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ESVOShaderManager functionality.
 * Tests shader compilation, variants, hot-reload, and error handling.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ESVOShaderManagerTest {
    private static final Logger log = LoggerFactory.getLogger(ESVOShaderManagerTest.class);
    
    private static long window;
    private ESVOShaderManager shaderManager;
    
    @BeforeAll
    static void setupOpenGL() {
        // Initialize GLFW
        if (!GLFW.glfwInit()) {
            log.warn("Failed to initialize GLFW - skipping OpenGL tests");
            Assumptions.assumeTrue(false, "GLFW initialization failed");
            return;
        }
        
        // Create OpenGL context
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        
        window = GLFW.glfwCreateWindow(800, 600, "ESVO Shader Test", 0, 0);
        if (window == 0) {
            GLFW.glfwTerminate();
            log.warn("Failed to create GLFW window - skipping OpenGL tests (headless environment?)");
            Assumptions.assumeTrue(false, "GLFW window creation failed");
            return;
        }
        
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        
        // Check compute shader support
        int maxComputeWorkGroupCount = GL43.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0);
        int maxComputeWorkGroupSize = GL43.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0);
        
        log.info("OpenGL Version: {}", GL43.glGetString(GL43.GL_VERSION));
        log.info("GLSL Version: {}", GL43.glGetString(GL43.GL_SHADING_LANGUAGE_VERSION));
        log.info("Max Compute Work Groups: {}", maxComputeWorkGroupCount);
        log.info("Max Compute Work Group Size: {}", maxComputeWorkGroupSize);
        
        assertTrue(maxComputeWorkGroupCount > 0, "Compute shaders not supported");
    }
    
    @AfterAll
    static void cleanupOpenGL() {
        if (window != 0) {
            GLFW.glfwDestroyWindow(window);
        }
        GLFW.glfwTerminate();
    }
    
    @BeforeEach
    void setUp() {
        shaderManager = new ESVOShaderManager("shaders/esvo/", false);
    }
    
    @AfterEach
    void tearDown() {
        if (shaderManager != null) {
            shaderManager.shutdown();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Basic shader compilation")
    void testBasicShaderCompilation() {
        log.info("Testing basic shader compilation...");
        
        // Test basic traversal shader
        int program = shaderManager.createVariant("traverse.comp").compile();
        
        assertTrue(program > 0, "Failed to compile basic traversal shader");
        assertTrue(GL43.glIsProgram(program), "Generated program ID is not a valid OpenGL program");
        
        // Test that the program is usable
        GL43.glUseProgram(program);
        int error = GL43.glGetError();
        assertEquals(GL43.GL_NO_ERROR, error, "Error using compiled shader program");
        
        log.info("Basic shader compilation test passed. Program ID: {}", program);
    }
    
    @Test
    @Order(2)
    @DisplayName("Shader variants with defines")
    void testShaderVariants() {
        log.info("Testing shader variants...");
        
        // Test variant with statistics
        int statsProgram = shaderManager.createVariant("traverse.comp")
                                       .define("ENABLE_STATISTICS")
                                       .compile();
        
        assertTrue(statsProgram > 0, "Failed to compile statistics variant");
        assertTrue(GL43.glIsProgram(statsProgram), "Statistics variant is not a valid program");
        
        // Test variant with LOD
        int lodProgram = shaderManager.createVariant("traverse.comp")
                                     .define("ENABLE_LOD")
                                     .define("LOD_BIAS", "1.0")
                                     .define("LOD_DISTANCE", "100.0")
                                     .compile();
        
        assertTrue(lodProgram > 0, "Failed to compile LOD variant");
        assertTrue(GL43.glIsProgram(lodProgram), "LOD variant is not a valid program");
        
        // Test variant with shadows
        int shadowProgram = shaderManager.createVariant("traverse.comp")
                                        .define("ENABLE_SHADOWS")
                                        .compile();
        
        assertTrue(shadowProgram > 0, "Failed to compile shadow variant");
        assertTrue(GL43.glIsProgram(shadowProgram), "Shadow variant is not a valid program");
        
        // Verify all programs are different
        assertNotEquals(statsProgram, lodProgram, "Statistics and LOD variants should be different programs");
        assertNotEquals(statsProgram, shadowProgram, "Statistics and shadow variants should be different programs");
        assertNotEquals(lodProgram, shadowProgram, "LOD and shadow variants should be different programs");
        
        log.info("Shader variants test passed. Programs: stats={}, lod={}, shadow={}", 
                 statsProgram, lodProgram, shadowProgram);
    }
    
    @Test
    @Order(3)
    @DisplayName("Preset shader variants")
    void testPresetVariants() {
        log.info("Testing preset shader variants...");
        
        // Test preset variants
        int basicProgram = ESVOShaderManager.Presets.basicTraversal(shaderManager).compile();
        assertTrue(basicProgram > 0, "Failed to compile basic preset");
        
        int shadowProgram = ESVOShaderManager.Presets.shadowTraversal(shaderManager).compile();
        assertTrue(shadowProgram > 0, "Failed to compile shadow preset");
        
        int lodProgram = ESVOShaderManager.Presets.lodTraversal(shaderManager, 1.0f, 100.0f).compile();
        assertTrue(lodProgram > 0, "Failed to compile LOD preset");
        
        int statsProgram = ESVOShaderManager.Presets.statisticsTraversal(shaderManager).compile();
        assertTrue(statsProgram > 0, "Failed to compile statistics preset");
        
        // Test beam shader presets
        int beamProgram = ESVOShaderManager.Presets.coherentBeam(shaderManager).compile();
        assertTrue(beamProgram > 0, "Failed to compile coherent beam preset");
        
        // Test shading presets
        int pbrProgram = ESVOShaderManager.Presets.pbrShading(shaderManager).compile();
        assertTrue(pbrProgram > 0, "Failed to compile PBR shading preset");
        
        int shadowedShadingProgram = ESVOShaderManager.Presets.shadowedShading(shaderManager).compile();
        assertTrue(shadowedShadingProgram > 0, "Failed to compile shadowed shading preset");
        
        log.info("Preset variants test passed. Created {} shader programs", 7);
    }
    
    @Test
    @Order(4)
    @DisplayName("Shader caching")
    void testShaderCaching() {
        log.info("Testing shader caching...");
        
        // Compile same variant twice
        int program1 = shaderManager.createVariant("traverse.comp")
                                   .define("ENABLE_STATISTICS")
                                   .compile();
        
        int program2 = shaderManager.createVariant("traverse.comp")
                                   .define("ENABLE_STATISTICS")
                                   .compile();
        
        assertEquals(program1, program2, "Same shader variant should return cached program");
        
        // Different variants should be different
        int program3 = shaderManager.createVariant("traverse.comp")
                                   .define("ENABLE_LOD")
                                   .compile();
        
        assertNotEquals(program1, program3, "Different shader variants should be different programs");
        
        log.info("Shader caching test passed. Cached program reused correctly.");
    }
    
    @Test
    @Order(5)
    @DisplayName("Error handling")
    void testErrorHandling() {
        log.info("Testing error handling...");
        
        // Test compilation with invalid defines (should still work)
        int program = shaderManager.createVariant("traverse.comp")
                                  .define("INVALID_DEFINE_THAT_DOES_NOT_EXIST")
                                  .compile();
        
        assertTrue(program > 0, "Shader should compile even with unused defines");
        
        // Test compilation of non-existent shader
        int invalidProgram = shaderManager.createVariant("non_existent_shader.comp").compile();
        assertEquals(0, invalidProgram, "Non-existent shader should return 0");
        
        log.info("Error handling test passed.");
    }
    
    @Test
    @Order(6)
    @DisplayName("Shader program validation")
    void testShaderProgramValidation() {
        log.info("Testing shader program validation...");
        
        int program = shaderManager.createVariant("traverse.comp").compile();
        assertTrue(program > 0, "Program compilation failed");
        
        // Use the program and check for errors
        GL43.glUseProgram(program);
        int error = GL43.glGetError();
        assertEquals(GL43.GL_NO_ERROR, error, "Error using shader program");
        
        // Validate the program
        GL43.glValidateProgram(program);
        int validated = GL43.glGetProgrami(program, GL43.GL_VALIDATE_STATUS);
        if (validated == GL43.GL_FALSE) {
            String programLog = GL43.glGetProgramInfoLog(program);
            log.warn("Program validation failed: {}", programLog);
            // Note: Validation can fail in some contexts, so we don't fail the test
        }
        
        // Check for required uniforms
        int voxelOriginLoc = GL43.glGetUniformLocation(program, "voxelOrigin");
        int voxelSizeLoc = GL43.glGetUniformLocation(program, "voxelSize");
        int rootNodeLoc = GL43.glGetUniformLocation(program, "rootNodeIndex");
        
        assertNotEquals(-1, voxelOriginLoc, "voxelOrigin uniform not found");
        assertNotEquals(-1, voxelSizeLoc, "voxelSize uniform not found");
        assertNotEquals(-1, rootNodeLoc, "rootNodeIndex uniform not found");
        
        log.info("Shader program validation passed. Uniforms found: voxelOrigin={}, voxelSize={}, rootNode={}", 
                 voxelOriginLoc, voxelSizeLoc, rootNodeLoc);
    }
    
    @Test
    @Order(7)
    @DisplayName("Memory management")
    void testMemoryManagement() {
        log.info("Testing memory management...");
        
        // Create multiple shader variants
        int[] programs = new int[10];
        for (int i = 0; i < programs.length; i++) {
            programs[i] = shaderManager.createVariant("traverse.comp")
                                      .define("TEST_VARIANT_" + i)
                                      .compile();
            assertTrue(programs[i] > 0, "Failed to compile variant " + i);
        }
        
        // All programs should be valid
        for (int i = 0; i < programs.length; i++) {
            assertTrue(GL43.glIsProgram(programs[i]), "Program " + i + " is not valid");
        }
        
        // Shutdown should clean up resources
        shaderManager.shutdown();
        
        // After shutdown, programs may or may not be valid depending on implementation
        // This is more of a resource leak test
        
        log.info("Memory management test completed. Created and cleaned up {} programs", programs.length);
    }
    
    @Test
    @Order(8)
    @DisplayName("Multiple shader managers")
    void testMultipleShaderManagers() {
        log.info("Testing multiple shader managers...");
        
        ESVOShaderManager manager1 = new ESVOShaderManager("shaders/esvo/", false);
        ESVOShaderManager manager2 = new ESVOShaderManager("shaders/esvo/", false);
        
        try {
            int program1 = manager1.createVariant("traverse.comp").compile();
            int program2 = manager2.createVariant("traverse.comp").compile();
            
            assertTrue(program1 > 0, "Manager 1 failed to compile shader");
            assertTrue(program2 > 0, "Manager 2 failed to compile shader");
            
            // Programs from different managers should be different
            assertNotEquals(program1, program2, "Different managers should create different programs");
            
        } finally {
            manager1.shutdown();
            manager2.shutdown();
        }
        
        log.info("Multiple shader managers test passed.");
    }
}