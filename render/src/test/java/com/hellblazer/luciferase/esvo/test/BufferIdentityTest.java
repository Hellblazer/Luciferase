package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BufferIdentityTest {
    
    @Test
    void testBufferIdentityAfterWrites() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_buffer_identity.txt")) {
            debugFile.write("=== Buffer Identity Test ===\n");
            
            UnifiedResourceManager.resetInstance();
            UnifiedResourceManager manager = UnifiedResourceManager.getInstance();
            
            // Allocate
            ByteBuffer buffer = manager.allocateMemory(1024);
            debugFile.write("Original buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Original buffer reference: " + buffer + "\n");
            debugFile.write("Active count after allocate: " + manager.getActiveResourceCount() + "\n");
            
            // Check resource ID BEFORE adding to list
            var resourceIdBeforeList = manager.getResourceId(buffer);
            debugFile.write("Resource ID BEFORE adding to list: " + resourceIdBeforeList + "\n");
            
            // Add to list (like MimicOctreeBuilderTest does)
            List<ByteBuffer> allocatedBuffers = new ArrayList<>();
            allocatedBuffers.add(buffer);
            debugFile.write("Buffer identity after adding to list: " + System.identityHashCode(buffer) + "\n");
            
            // Check resource ID AFTER adding to list
            var resourceIdAfterList = manager.getResourceId(buffer);
            debugFile.write("Resource ID AFTER adding to list: " + resourceIdAfterList + "\n");
            
            // Check resource ID BEFORE writes
            var resourceIdBeforeWrites = manager.getResourceId(buffer);
            debugFile.write("Resource ID BEFORE writes: " + resourceIdBeforeWrites + "\n");
            
            // Write to buffer (like MimicOctreeBuilderTest does)
            debugFile.write("About to write to buffer...\n");
            buffer.putInt(0x4553564F);
            debugFile.write("After putInt(1): Resource ID = " + manager.getResourceId(buffer) + "\n");
            buffer.putInt(10);
            debugFile.write("After putInt(2): Resource ID = " + manager.getResourceId(buffer) + "\n");
            buffer.putInt(1);
            debugFile.write("After putInt(3): Resource ID = " + manager.getResourceId(buffer) + "\n");
            
            debugFile.write("Buffer identity after writes: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Buffer reference after writes: " + buffer + "\n");
            debugFile.write("Buffer hashCode after writes: " + buffer.hashCode() + "\n");
            
            // Get from list (like MimicOctreeBuilderTest does)
            for (ByteBuffer buf : allocatedBuffers) {
                debugFile.write("Buffer identity from list: " + System.identityHashCode(buf) + "\n");
                debugFile.write("Buffer reference from list: " + buf + "\n");
                debugFile.write("Are references equal: " + (buffer == buf) + "\n");
                debugFile.write("Are identities equal: " + (System.identityHashCode(buffer) == System.identityHashCode(buf)) + "\n");
                
                // Check if it's tracked BEFORE release
                var resourceId = manager.getResourceId(buf);
                debugFile.write("Resource ID before release: " + resourceId + "\n");
                
                debugFile.write("About to release buffer from list...\n");
                manager.releaseMemory(buf);
                debugFile.write("Active count after release: " + manager.getActiveResourceCount() + "\n");
                
                // Check if it's tracked AFTER release
                var resourceIdAfter = manager.getResourceId(buf);
                debugFile.write("Resource ID after release: " + resourceIdAfter + "\n");
            }
            
            assertEquals(0, manager.getActiveResourceCount(), "Should have 0 resources after release");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}