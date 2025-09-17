package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class IsolatedTrackingTest {
    
    @Test
    void testIsolatedTracking() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_isolated_tracking.txt")) {
            debugFile.write("=== Isolated Tracking Test ===\n");
            
            UnifiedResourceManager.resetInstance();
            UnifiedResourceManager manager = UnifiedResourceManager.getInstance();
            
            debugFile.write("Initial count: " + manager.getActiveResourceCount() + "\n");
            
            // Allocate buffer
            ByteBuffer buffer = manager.allocateMemory(1024);
            debugFile.write("After allocate count: " + manager.getActiveResourceCount() + "\n");
            debugFile.write("Buffer identity: " + System.identityHashCode(buffer) + "\n");
            
            // Check resource ID immediately after allocation
            var resourceId1 = manager.getResourceId(buffer);
            debugFile.write("Resource ID immediately after allocation: " + resourceId1 + "\n");
            
            // Wait a tiny bit (in case there's some async cleanup)
            Thread.sleep(1);
            
            // Check resource ID again
            var resourceId2 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after 1ms: " + resourceId2 + "\n");
            
            // Check if resource ID is still there after a longer wait
            Thread.sleep(10);
            var resourceId3 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after 11ms: " + resourceId3 + "\n");
            
            // Check if performMaintenance affects it
            debugFile.write("Calling performMaintenance()...\n");
            manager.performMaintenance();
            var resourceId4 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after performMaintenance(): " + resourceId4 + "\n");
            
            // Verify all resource IDs are the same and not null
            assertNotNull(resourceId1, "Resource ID should not be null immediately after allocation");
            assertNotNull(resourceId2, "Resource ID should not be null after 1ms");
            assertNotNull(resourceId3, "Resource ID should not be null after 11ms");
            assertNotNull(resourceId4, "Resource ID should not be null after performMaintenance()");
            
            debugFile.write("Test completed successfully - all resource IDs found\n");
            
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}