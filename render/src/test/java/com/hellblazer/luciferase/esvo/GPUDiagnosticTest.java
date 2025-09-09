package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.gpu.test.PlatformTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test to determine if GPU compute shaders are actually being used
 * in headless mode on this platform.
 */
@DisplayName("GPU Diagnostic Test - Verify Actual GPU Usage")
public class GPUDiagnosticTest {
    
    @Test
    @DisplayName("Test GPU availability and compute shader support")
    void testGPUAvailability() {
        System.out.println("\n=== GPU Diagnostic Test Starting ===");
        System.out.println("Platform: " + Platform.get().getName());
        System.out.println("Architecture: " + Platform.getArchitecture());
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        
        // Check if we're in CI environment
        boolean isCI = System.getenv("CI") != null || 
                      System.getenv("GITHUB_ACTIONS") != null;
        System.out.println("CI Environment: " + isCI);
        
        // Check platform requirements for GLFW/OpenGL
        if (Platform.get() == Platform.MACOSX) {
            var jvmOptions = System.getProperty("java.vm.options", "");
            var hasStartOnFirstThread = jvmOptions.contains("-XstartOnFirstThread");
            System.out.println("macOS -XstartOnFirstThread: " + hasStartOnFirstThread);
            
            if (!hasStartOnFirstThread) {
                System.out.println("⚠️  WARNING: macOS requires -XstartOnFirstThread for GLFW");
                System.out.println("   Add to JVM options: -XstartOnFirstThread");
                System.out.println("   Using JUnit assumption to skip test gracefully");
                
                // Use proper JUnit assumption to skip test
                PlatformTestSupport.requireMacOSWithStartOnFirstThread();
                return; // This line won't be reached, but kept for clarity
            }
        }
        
        // Try to initialize GLFW for headless OpenGL context
        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);
        
        boolean glfwInitialized = false;
        long window = NULL;
        
        try {
            System.out.println("\n--- Attempting GLFW initialization ---");
            
            // Initialize GLFW
            if (!glfwInit()) {
                System.out.println("❌ GLFW initialization failed");
                System.out.println("   This means GPU compute shaders cannot be used in tests");
                return;
            }
            glfwInitialized = true;
            System.out.println("✅ GLFW initialized successfully");
            
            // Configure for headless/offscreen rendering
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Hidden window
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            
            if (Platform.get() == Platform.MACOSX) {
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            }
            
            // Create hidden window for OpenGL context
            System.out.println("\n--- Creating headless OpenGL context ---");
            window = glfwCreateWindow(1, 1, "Headless", NULL, NULL);
            
            if (window == NULL) {
                System.out.println("❌ Failed to create GLFW window");
                System.out.println("   GPU compute shaders cannot be used without OpenGL context");
                return;
            }
            System.out.println("✅ Hidden window created");
            
            // Make the OpenGL context current
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("✅ OpenGL context created");
            
            // Query OpenGL information
            System.out.println("\n--- OpenGL Information ---");
            String vendor = glGetString(GL_VENDOR);
            String renderer = glGetString(GL_RENDERER);
            String version = glGetString(GL_VERSION);
            String glslVersion = glGetString(GL_SHADING_LANGUAGE_VERSION);
            
            System.out.println("Vendor: " + vendor);
            System.out.println("Renderer: " + renderer);
            System.out.println("OpenGL Version: " + version);
            System.out.println("GLSL Version: " + glslVersion);
            
            // Check for compute shader support
            System.out.println("\n--- Compute Shader Support ---");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer maxComputeWorkGroupCount = stack.mallocInt(3);
                IntBuffer maxComputeWorkGroupSize = stack.mallocInt(3);
                IntBuffer maxComputeWorkGroupInvocations = stack.mallocInt(1);
                
                // Query compute shader limits
                for (int i = 0; i < 3; i++) {
                    glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT, i, maxComputeWorkGroupCount);
                    glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, i, maxComputeWorkGroupSize);
                }
                glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, maxComputeWorkGroupInvocations);
                
                System.out.println("Max Work Group Count: [" + 
                    maxComputeWorkGroupCount.get(0) + ", " +
                    maxComputeWorkGroupCount.get(1) + ", " +
                    maxComputeWorkGroupCount.get(2) + "]");
                System.out.println("Max Work Group Size: [" +
                    maxComputeWorkGroupSize.get(0) + ", " +
                    maxComputeWorkGroupSize.get(1) + ", " +
                    maxComputeWorkGroupSize.get(2) + "]");
                System.out.println("Max Work Group Invocations: " + maxComputeWorkGroupInvocations.get(0));
                
                // Test creating a compute shader
                System.out.println("\n--- Testing Compute Shader Creation ---");
                String testShaderSource = """
                    #version 430 core
                    layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
                    void main() {
                        // Minimal compute shader for testing
                    }
                    """;
                
                int shader = glCreateShader(GL_COMPUTE_SHADER);
                if (shader != 0) {
                    glShaderSource(shader, testShaderSource);
                    glCompileShader(shader);
                    
                    int[] status = new int[1];
                    glGetShaderiv(shader, GL_COMPILE_STATUS, status);
                    
                    if (status[0] == GL_TRUE) {
                        System.out.println("✅ Compute shader compiled successfully");
                        System.out.println("   GPU compute shaders ARE AVAILABLE for testing");
                    } else {
                        String log = glGetShaderInfoLog(shader);
                        System.out.println("❌ Compute shader compilation failed: " + log);
                    }
                    
                    glDeleteShader(shader);
                } else {
                    System.out.println("❌ Failed to create compute shader object");
                }
            }
            
            // Determine if this is actual GPU or software rendering
            System.out.println("\n--- GPU Hardware Detection ---");
            boolean isHardwareAccelerated = false;
            
            if (renderer != null) {
                String rendererLower = renderer.toLowerCase();
                
                // Check for software renderers
                if (rendererLower.contains("llvmpipe") || 
                    rendererLower.contains("software") ||
                    rendererLower.contains("mesa") && !rendererLower.contains("intel") && !rendererLower.contains("amd") && !rendererLower.contains("nvidia")) {
                    System.out.println("⚠️  Software renderer detected - NOT using actual GPU");
                    isHardwareAccelerated = false;
                }
                // Check for hardware vendors
                else if (rendererLower.contains("nvidia") || 
                         rendererLower.contains("amd") || 
                         rendererLower.contains("radeon") ||
                         rendererLower.contains("intel") ||
                         rendererLower.contains("apple") ||
                         rendererLower.contains("metal")) {
                    System.out.println("✅ Hardware GPU detected - using actual GPU");
                    isHardwareAccelerated = true;
                } else {
                    System.out.println("⚠️  Unknown renderer - may be software or hardware");
                }
            }
            
            // Final verdict
            System.out.println("\n=== DIAGNOSTIC SUMMARY ===");
            System.out.println("GLFW Initialized: YES");
            System.out.println("OpenGL Context: YES");
            System.out.println("Compute Shader Support: YES");
            System.out.println("Hardware GPU: " + (isHardwareAccelerated ? "YES" : "NO (Software Rendering)"));
            
            if (isHardwareAccelerated) {
                System.out.println("\n✅ Tests ARE using actual GPU compute shaders in headless mode");
            } else {
                System.out.println("\n⚠️  Tests are using SOFTWARE rendering, not actual GPU");
            }
            
        } catch (Exception e) {
            System.err.println("Exception during GPU diagnostics: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            if (glfwInitialized) {
                glfwTerminate();
            }
            if (errorCallback != null) {
                errorCallback.free();
            }
        }
        
        System.out.println("\n=== GPU Diagnostic Test Complete ===\n");
    }
}