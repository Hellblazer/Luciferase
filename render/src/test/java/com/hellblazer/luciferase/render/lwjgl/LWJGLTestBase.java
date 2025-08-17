package com.hellblazer.luciferase.render.lwjgl;

import org.junit.jupiter.api.*;
import org.lwjgl.opengl.GL;

import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Base class for all LWJGL/OpenGL tests.
 * 
 * This class handles the platform-specific initialization requirements,
 * especially the macOS -XstartOnFirstThread constraint.
 * 
 * All OpenGL-related test classes should extend this base class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class LWJGLTestBase {
    
    private static final Logger log = Logger.getLogger(LWJGLTestBase.class.getName());
    
    protected long testWindow;
    private static boolean globalInitialized = false;
    
    @BeforeAll
    void initializeOpenGL() {
        if (!globalInitialized) {
            try {
                // Use the centralized initializer
                boolean success = GLFWInitializer.initializeForTesting();
                if (!success) {
                    handleInitializationFailure();
                    return;
                }
                
                globalInitialized = true;
                log.info("GLFW initialized for testing");
                
            } catch (GLFWInitializer.GLFWInitializationException e) {
                handleInitializationFailure();
                return;
            }
        }
        
        // Create test window
        createTestWindow();
    }
    
    @AfterAll
    void cleanupOpenGL() {
        if (testWindow != 0) {
            glfwDestroyWindow(testWindow);
            testWindow = 0;
        }
        
        // Note: We don't terminate GLFW here as other test classes might still need it
        // GLFW termination happens in a shutdown hook or explicit cleanup
    }
    
    /**
     * Create a hidden window for OpenGL context during testing.
     */
    protected void createTestWindow() {
        if (testWindow != 0) {
            return; // Already created
        }
        
        // Configure window
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Hidden for tests
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1); // macOS supports up to 4.1
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        
        // Create window
        testWindow = glfwCreateWindow(640, 480, "Test Window", 0, 0);
        if (testWindow == 0) {
            throw new RuntimeException("Failed to create test window");
        }
        
        // Make context current
        glfwMakeContextCurrent(testWindow);
        GL.createCapabilities();
        
        // Log OpenGL info
        String glVersion = glGetString(GL_VERSION);
        String glRenderer = glGetString(GL_RENDERER);
        log.fine("OpenGL: " + glVersion + ", Renderer: " + glRenderer);
    }
    
    /**
     * Handle initialization failure gracefully.
     * On macOS without -XstartOnFirstThread, skip tests with a helpful message.
     */
    private void handleInitializationFailure() {
        String message = """
            
            ================================================
            SKIPPING OPENGL TESTS - INITIALIZATION FAILED
            ================================================
            """ + GLFWInitializer.getPlatformRunInstructions() + """
            
            To run these tests, use one of the solutions above.
            ================================================
            """;
        
        log.warning(message);
        
        // Mark all tests as skipped
        Assumptions.assumeTrue(false, 
            "OpenGL tests skipped - GLFW initialization failed. " +
            "On macOS, add -XstartOnFirstThread to JVM arguments.");
    }
    
    /**
     * Check if OpenGL is available for testing.
     * Tests can use this to conditionally skip if OpenGL is not available.
     */
    protected boolean isOpenGLAvailable() {
        return testWindow != 0 && GLFWInitializer.isInitialized();
    }
    
    /**
     * Utility method to make the test window current.
     * Useful when tests need to ensure the correct context.
     */
    protected void makeContextCurrent() {
        if (testWindow != 0) {
            glfwMakeContextCurrent(testWindow);
        }
    }
}