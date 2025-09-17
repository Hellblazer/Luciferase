package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MimicOctreeBuilderTest {
    
    @Test
    void testMimicOctreeBuilder() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_resource_leak.txt")) {
            debugFile.write("\n=== Mimic OctreeBuilder Test ===\n");
            
            UnifiedResourceManager.resetInstance();
            UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();
            
            debugFile.write("Initial: " + resourceManager.getActiveResourceCount() + "\n");
            debugFile.flush();
            
            // Mimic what OctreeBuilder does
            List<ByteBuffer> allocatedBuffers = new ArrayList<>();
            
            // Allocate a buffer like OctreeBuilder.allocateBuffer() does
            ByteBuffer buffer = resourceManager.allocateMemory(1024);
            allocatedBuffers.add(buffer);
            debugFile.write("After allocate (like OctreeBuilder): " + resourceManager.getActiveResourceCount() + "\n");
            debugFile.write("Buffer identity hash: " + System.identityHashCode(buffer) + "\n");
            debugFile.flush();
            
            // Serialize (just write to buffer like OctreeBuilder.serialize())
            buffer.putInt(0x4553564F);
            buffer.putInt(10);
            buffer.putInt(1);
            debugFile.write("After serialize: " + resourceManager.getActiveResourceCount() + "\n");
            debugFile.flush();
            
            // Close (like OctreeBuilder.close())
            for (ByteBuffer buf : allocatedBuffers) {
                debugFile.write("Releasing buffer: " + System.identityHashCode(buf) + "\n");
                debugFile.flush();
                resourceManager.releaseMemory(buf);
                debugFile.write("After release: " + resourceManager.getActiveResourceCount() + "\n");
                debugFile.flush();
            }
            
            debugFile.write("Final: " + resourceManager.getActiveResourceCount() + "\n");
            debugFile.flush();
            assertEquals(0, resourceManager.getActiveResourceCount(), "Should have 0 resources after releasing all");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}