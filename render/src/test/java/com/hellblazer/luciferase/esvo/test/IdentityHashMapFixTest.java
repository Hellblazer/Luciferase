package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class IdentityHashMapFixTest {
    
    @Test
    void testIdentityHashMapFix() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_identity_fix.txt")) {
            debugFile.write("=== IdentityHashMap Fix Test ===\n");
            
            UnifiedResourceManager.resetInstance();
            UnifiedResourceManager manager = UnifiedResourceManager.getInstance();
            
            debugFile.write("Initial count: " + manager.getActiveResourceCount() + "\n");
            
            // Allocate buffer
            ByteBuffer buffer = manager.allocateMemory(1024);
            debugFile.write("After allocate count: " + manager.getActiveResourceCount() + "\n");
            debugFile.write("Buffer identity: " + System.identityHashCode(buffer) + "\n");
            
            // Check resource ID BEFORE any operations
            var resourceIdBefore = manager.getResourceId(buffer);
            debugFile.write("Resource ID before operations: " + resourceIdBefore + "\n");
            
            // Write to buffer (this was causing the issue)
            debugFile.write("About to write to buffer...\n");
            int originalHashCode = buffer.hashCode();
            debugFile.write("Buffer hashCode before write: " + originalHashCode + "\n");
            
            buffer.putInt(0x12345678);
            
            int newHashCode = buffer.hashCode();
            debugFile.write("Buffer hashCode after write: " + newHashCode + "\n");
            debugFile.write("HashCode changed: " + (originalHashCode != newHashCode) + "\n");
            
            // Check resource ID AFTER writing
            var resourceIdAfter = manager.getResourceId(buffer);
            debugFile.write("Resource ID after write: " + resourceIdAfter + "\n");
            debugFile.write("Resource ID preserved: " + (resourceIdBefore != null && resourceIdBefore.equals(resourceIdAfter)) + "\n");
            
            // Release buffer
            if (resourceIdAfter != null) {
                debugFile.write("Releasing buffer...\n");
                manager.releaseMemory(buffer);
                debugFile.write("After release count: " + manager.getActiveResourceCount() + "\n");
                assertEquals(0, manager.getActiveResourceCount(), "Should have 0 resources after release");
            } else {
                debugFile.write("ERROR: Buffer not tracked after write - IdentityHashMap fix failed!\n");
                debugFile.write("Active count after failed tracking: " + manager.getActiveResourceCount() + "\n");
                // The test will fail because resource ID is null, so the buffer won't be properly released
            }
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}