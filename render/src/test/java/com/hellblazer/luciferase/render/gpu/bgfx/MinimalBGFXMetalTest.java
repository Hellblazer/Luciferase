package com.hellblazer.luciferase.render.gpu.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/**
 * Minimal test to isolate BGFX Metal initialization issues.
 * Tests each step incrementally with timeouts and detailed logging.
 */
public class MinimalBGFXMetalTest {
    
    public static void main(String[] args) {
        System.out.println("=== Minimal BGFX Metal Initialization Test ===");
        System.out.println("Timestamp: " + System.currentTimeMillis());
        
        try {
            // Step 1: GLFW init (we know this works)
            System.out.println("[STEP 1] Initializing GLFW...");
            long startTime = System.nanoTime();
            
            if (!GLFW.glfwInit()) {
                throw new RuntimeException("Failed to initialize GLFW");
            }
            
            long glfwTime = System.nanoTime() - startTime;
            System.out.println("✅ GLFW initialized in " + (glfwTime / 1_000_000) + "ms");
            
            // Step 2: BGFX init structure
            System.out.println("[STEP 2] Creating BGFX init structure...");
            startTime = System.nanoTime();
            
            try (var stack = MemoryStack.stackPush()) {
                var init = BGFXInit.malloc(stack);
                BGFX.bgfx_init_ctor(init);
                
                // Set Metal backend explicitly
                init.type(BGFX.BGFX_RENDERER_TYPE_METAL);
                init.resolution().width(1).height(1).reset(BGFX.BGFX_RESET_NONE);
                
                long structTime = System.nanoTime() - startTime;
                System.out.println("✅ BGFX init structure created in " + (structTime / 1_000_000) + "ms");
                
                // Step 3: The critical BGFX initialization call
                System.out.println("[STEP 3] Calling BGFX.bgfx_init() with Metal backend...");
                System.out.println("This is where hanging typically occurs if Metal is unavailable");
                startTime = System.nanoTime();
                
                // Create timeout thread to detect hangs
                Thread timeoutThread = new Thread(() -> {
                    try {
                        Thread.sleep(10000); // 10 second timeout
                        System.err.println("❌ TIMEOUT: bgfx_init() hung for 10+ seconds");
                        System.err.println("This indicates Metal backend initialization failure");
                        System.exit(1);
                    } catch (InterruptedException e) {
                        // Normal completion, timeout thread interrupted
                    }
                });
                timeoutThread.start();
                
                boolean success = BGFX.bgfx_init(init);
                timeoutThread.interrupt(); // Cancel timeout
                
                long initTime = System.nanoTime() - startTime;
                
                if (success) {
                    System.out.println("🎉 BGFX Metal backend initialized successfully in " + (initTime / 1_000_000) + "ms");
                    
                    // Step 4: Get renderer info
                    System.out.println("[STEP 4] Querying renderer information...");
                    int rendererType = BGFX.bgfx_get_renderer_type();
                    System.out.println("Renderer type: " + rendererType + " (Metal=" + BGFX.BGFX_RENDERER_TYPE_METAL + ")");
                    
                    // Step 5: Clean shutdown
                    System.out.println("[STEP 5] Shutting down BGFX...");
                    BGFX.bgfx_shutdown();
                    System.out.println("✅ Clean shutdown completed");
                    
                } else {
                    System.err.println("❌ BGFX Metal initialization failed");
                    System.err.println("bgfx_init() returned false");
                    System.exit(1);
                }
            }
            
        } catch (Exception e) {
            System.err.println("❌ Exception during test: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            // Cleanup GLFW
            try {
                GLFW.glfwTerminate();
                System.out.println("✅ GLFW terminated");
            } catch (Exception e) {
                System.err.println("Warning: GLFW termination failed: " + e.getMessage());
            }
        }
        
        System.out.println("");
        System.out.println("🎉 ALL TESTS PASSED - BGFX Metal backend is working!");
        System.out.println("The hang issue must be in higher-level integration code.");
    }
}