package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ManagerInstanceTest {
    
    @Test
    void testManagerInstance() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_manager_instance.txt")) {
            debugFile.write("=== Manager Instance Test ===\n");
            
            UnifiedResourceManager.resetInstance();
            
            // Get manager instance and store reference
            UnifiedResourceManager manager1 = UnifiedResourceManager.getInstance();
            debugFile.write("Manager1 instance: " + System.identityHashCode(manager1) + "\n");
            
            // Allocate buffer using manager1
            ByteBuffer buffer = manager1.allocateMemory(1024);
            debugFile.write("Buffer allocated using manager1\n");
            debugFile.write("Buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Active count after allocate: " + manager1.getActiveResourceCount() + "\n");
            
            // Get another manager instance
            UnifiedResourceManager manager2 = UnifiedResourceManager.getInstance();
            debugFile.write("Manager2 instance: " + System.identityHashCode(manager2) + "\n");
            debugFile.write("Are managers the same: " + (manager1 == manager2) + "\n");
            
            // Check resource ID using manager1 (should work)
            var resourceId1 = manager1.getResourceId(buffer);
            debugFile.write("Resource ID via manager1: " + resourceId1 + "\n");
            
            // Check resource ID using manager2 (should be the same if singleton works)
            var resourceId2 = manager2.getResourceId(buffer);
            debugFile.write("Resource ID via manager2: " + resourceId2 + "\n");
            
            // Check if they're the same
            debugFile.write("Resource IDs equal: " + (resourceId1 != null && resourceId1.equals(resourceId2)) + "\n");
            
            // Release using manager2
            debugFile.write("Releasing buffer using manager2...\n");
            manager2.releaseMemory(buffer);
            debugFile.write("Active count after release: " + manager2.getActiveResourceCount() + "\n");
            
            assertEquals(0, manager2.getActiveResourceCount(), "Should have 0 resources after release");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}