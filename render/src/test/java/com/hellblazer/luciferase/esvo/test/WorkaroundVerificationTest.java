package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that the buffer position workaround works correctly
 */
public class WorkaroundVerificationTest {

    @Test
    public void testBufferPositionWorkaround() throws IOException {
        try (var debugFile = new FileWriter("/tmp/workaround_verification.txt")) {
            debugFile.write("=== Workaround Verification Test ===\n");
            
            // Reset and create fresh manager
            UnifiedResourceManager.resetInstance();
            var manager = UnifiedResourceManager.getInstance();
            
            // Allocate buffer
            var buffer = manager.allocateMemory(16);
            debugFile.write("Allocated buffer: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Initial position: " + buffer.position() + "\n");

            // Get resource ID at position 0
            var resourceId1 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 0: " + resourceId1 + "\n");
            assertNotNull(resourceId1, "Resource ID should not be null at position 0");

            // Change position to 4
            buffer.position(4);
            debugFile.write("Changed position to: " + buffer.position() + "\n");

            // Get resource ID at position 4 - should work with workaround
            var resourceId2 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 4: " + resourceId2 + "\n");
            assertNotNull(resourceId2, "Resource ID should not be null at position 4 with workaround");
            assertEquals(resourceId1, resourceId2, "Resource ID should be the same regardless of position");

            // Reset position to 0
            buffer.position(0);
            var resourceId3 = manager.getResourceId(buffer);
            debugFile.write("Resource ID back at position 0: " + resourceId3 + "\n");
            assertEquals(resourceId1, resourceId3, "Resource ID should still be the same after reset");

            // Write data and change position
            buffer.putInt(42);
            debugFile.write("After putInt, position: " + buffer.position() + "\n");

            var resourceId4 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after putInt: " + resourceId4 + "\n");
            assertNotNull(resourceId4, "Resource ID should not be null after putInt with workaround");
            assertEquals(resourceId1, resourceId4, "Resource ID should still be the same after putInt");

            // Reset position and try again
            buffer.position(0);
            var resourceId5 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after putInt with position reset: " + resourceId5 + "\n");
            assertEquals(resourceId1, resourceId5, "Resource ID should still be the same after putInt and reset");

            // Clean up
            manager.releaseMemory(buffer);
            debugFile.write("=== Test Complete - All assertions passed ===\n");
        }
    }
}