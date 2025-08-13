package com.hellblazer.luciferase.webgpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for surface capabilities.
 */
public class SurfaceCapabilitiesHelper {
    private static final Logger log = LoggerFactory.getLogger(SurfaceCapabilitiesHelper.class);
    
    /**
     * Provide default surface capabilities when native call is not available.
     */
    public static void provideDefaultCapabilities(MemorySegment capabilities, Arena arena) {
        // Set usage flags to RENDER_ATTACHMENT
        capabilities.set(ValueLayout.JAVA_INT, 8, 0x10);
        
        // Set counts
        capabilities.set(ValueLayout.JAVA_LONG, 16, 1L); // formatCount
        capabilities.set(ValueLayout.JAVA_LONG, 32, 1L); // presentModeCount  
        capabilities.set(ValueLayout.JAVA_LONG, 48, 1L); // alphaModeCount
        
        // Allocate format array with BGRA8Unorm
        var formatsArray = arena.allocate(ValueLayout.JAVA_INT, 1);
        formatsArray.set(ValueLayout.JAVA_INT, 0, 0x00000017); // BGRA8Unorm
        capabilities.set(ValueLayout.ADDRESS, 24, formatsArray);
        
        // Allocate present mode array with Fifo
        var presentModesArray = arena.allocate(ValueLayout.JAVA_INT, 1);
        presentModesArray.set(ValueLayout.JAVA_INT, 0, 0x00000002); // Fifo
        capabilities.set(ValueLayout.ADDRESS, 40, presentModesArray);
        
        // Allocate alpha mode array with Opaque
        var alphaModesArray = arena.allocate(ValueLayout.JAVA_INT, 1);
        alphaModesArray.set(ValueLayout.JAVA_INT, 0, 0x00000000); // Opaque
        capabilities.set(ValueLayout.ADDRESS, 56, alphaModesArray);
        
        log.debug("Provided default surface capabilities");
    }
}