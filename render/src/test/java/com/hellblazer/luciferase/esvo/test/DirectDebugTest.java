package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DirectDebugTest {
    
    @Test
    void testDirectBufferTracking() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_direct_test.txt")) {
            debugFile.write("=== Direct Debug Test ===\n");
            
            UnifiedResourceManager.resetInstance();
            UnifiedResourceManager manager = UnifiedResourceManager.getInstance();
            
            debugFile.write("Initial count: " + manager.getActiveResourceCount() + "\n");
            debugFile.flush();
            
            // Allocate
            ByteBuffer buffer = manager.allocateMemory(1024);
            debugFile.write("After allocate count: " + manager.getActiveResourceCount() + "\n");
            debugFile.write("Buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Buffer capacity: " + buffer.capacity() + "\n");
            debugFile.write("Buffer isDirect: " + buffer.isDirect() + "\n");
            debugFile.flush();
            
            // Check if the buffer is tracked
            var resourceId = manager.getResourceId(buffer);
            debugFile.write("Resource ID for buffer: " + resourceId + "\n");
            debugFile.flush();
            
            // Now release
            debugFile.write("About to call releaseMemory...\n");
            debugFile.flush();
            
            manager.releaseMemory(buffer);
            
            debugFile.write("After release count: " + manager.getActiveResourceCount() + "\n");
            debugFile.flush();
            
            // Check again if the buffer is still tracked
            var resourceIdAfter = manager.getResourceId(buffer);
            debugFile.write("Resource ID after release: " + resourceIdAfter + "\n");
            debugFile.flush();
            
            assertEquals(0, manager.getActiveResourceCount(), "Should have 0 resources after release");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}