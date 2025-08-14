package com.hellblazer.luciferase.render.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal WebGPU test to verify the async initialization fixes.
 * This follows the jWebGPU pattern for proper async handling.
 */
public class MinimalWebGPUTest {
    private static final Logger log = LoggerFactory.getLogger(MinimalWebGPUTest.class);
    
    public static void main(String[] args) {
        log.info("Starting minimal WebGPU test with async initialization");
        
        try {
            var context = new WebGPUContext(800, 600, "Minimal WebGPU Test");
            
            // Test async initialization with proper callback handling
            context.initializeAsync((state, message, error) -> {
                log.info("WebGPU state change: {} - {}", state, message);
                
                if (error != null) {
                    log.error("WebGPU error during initialization", error);
                }
                
                if (state == WebGPUContext.InitState.READY) {
                    log.info("SUCCESS: WebGPU context fully initialized and ready!");
                    
                    // Test basic operations
                    try {
                        log.info("Testing surface texture acquisition...");
                        var texture = context.getCurrentTexture();
                        if (texture != null) {
                            log.info("SUCCESS: Surface texture acquired successfully");
                        } else {
                            log.warn("Surface texture is null but context is ready");
                        }
                        
                        // Present the frame
                        context.present();
                        log.info("Frame presented successfully");
                        
                    } catch (Exception e) {
                        log.error("Error testing basic operations", e);
                    } finally {
                        // Clean up and exit
                        context.cleanup();
                        log.info("WebGPU context cleaned up");
                        System.exit(0);
                    }
                } else if (state == WebGPUContext.InitState.FAILED) {
                    log.error("FAILURE: WebGPU initialization failed");
                    context.cleanup();
                    System.exit(1);
                }
            });
            
            // Wait for initialization to complete (or timeout)
            int maxWaitSeconds = 10;
            int waited = 0;
            
            while (!context.isReady() && !context.isFailed() && waited < maxWaitSeconds) {
                try {
                    Thread.sleep(100);
                    waited++;
                    if (waited % 10 == 0) { // Log every second
                        log.info("Waiting for WebGPU initialization... ({}/{}s) State: {}", 
                            waited / 10, maxWaitSeconds, context.getInitState());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Test interrupted");
                    System.exit(1);
                }
            }
            
            if (waited >= maxWaitSeconds) {
                log.error("TIMEOUT: WebGPU initialization timed out after {}s", maxWaitSeconds);
                log.error("Final state: {}", context.getInitState());
                context.cleanup();
                System.exit(1);
            }
            
        } catch (Exception e) {
            log.error("Test failed with exception", e);
            System.exit(1);
        }
    }
}