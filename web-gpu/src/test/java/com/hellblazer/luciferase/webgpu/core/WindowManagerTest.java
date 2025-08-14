package com.hellblazer.luciferase.webgpu.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WindowManager GLFW integration.
 */
public class WindowManagerTest {
    private static final Logger log = LoggerFactory.getLogger(WindowManagerTest.class);
    
    private WindowManager windowManager;
    
    @AfterEach
    public void cleanup() {
        if (windowManager != null) {
            windowManager.cleanup();
            windowManager = null;
        }
    }
    
    @Test
    public void testWindowCreation() {
        windowManager = new WindowManager(800, 600, "Test Window");
        
        boolean initialized = windowManager.initialize();
        assertThat(initialized)
            .describedAs("Window should initialize successfully")
            .isTrue();
        
        assertThat(windowManager.isInitialized())
            .describedAs("Window should report as initialized")
            .isTrue();
        
        assertThat(windowManager.getWindow())
            .describedAs("Window handle should not be null")
            .isNotEqualTo(0L);
        
        assertThat(windowManager.getNativeHandle())
            .describedAs("Native handle should not be null")
            .isNotEqualTo(0L);
        
        assertThat(windowManager.getWidth())
            .describedAs("Width should match requested")
            .isEqualTo(800);
        
        assertThat(windowManager.getHeight())
            .describedAs("Height should match requested")
            .isEqualTo(600);
        
        log.info("Window created successfully with handle: {}", windowManager.getWindow());
    }
    
    @Test
    public void testWindowShowHide() {
        windowManager = new WindowManager(640, 480, "Show/Hide Test");
        windowManager.initialize();
        
        // Window starts hidden
        windowManager.show();
        log.info("Window shown");
        
        // Poll events to process the show
        windowManager.pollEvents();
        
        // Hide again
        windowManager.hide();
        log.info("Window hidden");
        
        // Poll events to process the hide
        windowManager.pollEvents();
        
        // No assertions here as we can't easily test visibility in headless env
        // This just verifies the methods don't crash
    }
    
    @Test
    public void testWindowShouldClose() {
        windowManager = new WindowManager(320, 240, "Close Test");
        windowManager.initialize();
        
        // Initially should not want to close
        assertThat(windowManager.shouldClose())
            .describedAs("Window should not want to close initially")
            .isFalse();
        
        // We can't programmatically trigger close without user input
        // Just verify the method works
        log.info("Window close check works");
    }
}