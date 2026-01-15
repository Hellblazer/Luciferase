package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BufferPositionTest {
    
    @Test
    void testBufferPositionEffect() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_buffer_position.txt")) {
            debugFile.write("=== Buffer Position Test ===\n");
            
            UnifiedResourceManager.resetInstance();
            UnifiedResourceManager manager = UnifiedResourceManager.getInstance();
            
            // Allocate buffer
            ByteBuffer buffer = manager.allocateMemory(1024);
            debugFile.write("Buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Buffer position after allocation: " + buffer.position() + "\n");
            
            // Check resource ID
            var resourceId1 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 0: " + resourceId1 + "\n");
            
            // Change position WITHOUT writing data
            debugFile.write("Changing position to 4 without writing...\n");
            buffer.position(4);
            debugFile.write("Buffer position after manual change: " + buffer.position() + "\n");
            
            var resourceId2 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 4: " + resourceId2 + "\n");
            
            // Change position back to 0
            debugFile.write("Changing position back to 0...\n");
            buffer.position(0);
            debugFile.write("Buffer position after reset: " + buffer.position() + "\n");
            
            var resourceId3 = manager.getResourceId(buffer);
            debugFile.write("Resource ID back at position 0: " + resourceId3 + "\n");
            
            // Now try actual putInt (which changes both position and content)
            debugFile.write("Now calling putInt()...\n");
            buffer.putInt(0x12345678);
            debugFile.write("Buffer position after putInt: " + buffer.position() + "\n");
            
            var resourceId4 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after putInt: " + resourceId4 + "\n");
            
            // Reset position again to see if it's position-related
            debugFile.write("Resetting position to 0 after putInt...\n");
            buffer.position(0);
            
            var resourceId5 = manager.getResourceId(buffer);
            debugFile.write("Resource ID after putInt with position reset: " + resourceId5 + "\n");
            
            assertNotNull(resourceId1, "Resource ID should not be null initially");
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}