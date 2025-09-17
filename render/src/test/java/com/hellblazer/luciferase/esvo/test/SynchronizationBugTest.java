package com.hellblazer.luciferase.esvo.test;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Test to reproduce the exact synchronization bug in UnifiedResourceManager
 */
public class SynchronizationBugTest {

    @Test
    public void testUnifiedResourceManagerSynchronization() throws IOException {
        try (var debugFile = new FileWriter("/tmp/sync_bug_test.txt")) {
            debugFile.write("=== UnifiedResourceManager Synchronization Bug Test ===\n");
            
            // Reset manager instance to start fresh
            UnifiedResourceManager.resetInstance();
            var manager = UnifiedResourceManager.getInstance();
            
            // Allocate a buffer
            var buffer = manager.allocateMemory(16);
            debugFile.write("Allocated buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Initial position: " + buffer.position() + "\n");

            // Test 1: Get resource ID at position 0
            var resourceId1 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 0: " + resourceId1 + "\n");

            // Test 2: Change position and try again
            buffer.position(4);
            debugFile.write("Changed position to: " + buffer.position() + "\n");

            var resourceId2 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 4: " + resourceId2 + "\n");

            // Test 3: Reset position
            buffer.position(0);
            debugFile.write("Reset position to: " + buffer.position() + "\n");

            var resourceId3 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after reset: " + resourceId3 + "\n");

            // Test 4: Write data
            buffer.putInt(42);
            debugFile.write("After putInt, position: " + buffer.position() + "\n");

            var resourceId4 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after putInt: " + resourceId4 + "\n");

            // Test 5: Reset position after write
            buffer.position(0);
            var resourceId5 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after putInt with position reset: " + resourceId5 + "\n");

            // Clean up
            manager.releaseMemory(buffer);
            debugFile.write("=== Test Complete ===\n");
        }
    }
}