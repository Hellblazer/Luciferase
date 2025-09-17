package com.hellblazer.luciferase.esvo.test;

import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DirectIdentityMapTest {
    
    @Test
    void testDirectIdentityMapBehavior() {
        try (FileWriter debugFile = new FileWriter("/tmp/debug_direct_identity_map.txt")) {
            debugFile.write("=== Direct IdentityHashMap Test ===\n");
            
            // Create a synchronized IdentityHashMap just like in UnifiedResourceManager
            Map<ByteBuffer, UUID> testMap = Collections.synchronizedMap(new IdentityHashMap<>());
            
            // Allocate a direct ByteBuffer
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
            UUID testId = UUID.randomUUID();
            
            debugFile.write("Buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Buffer hashCode before write: " + buffer.hashCode() + "\n");
            
            // Put in map
            testMap.put(buffer, testId);
            debugFile.write("Added to map: " + testId + "\n");
            
            // Verify it's there
            UUID retrieved1 = testMap.get(buffer);
            debugFile.write("Retrieved before write: " + retrieved1 + "\n");
            debugFile.write("Found in map before write: " + (retrieved1 != null) + "\n");
            
            // Write to buffer (this changes hashCode)
            buffer.putInt(0x12345678);
            debugFile.write("Buffer hashCode after write: " + buffer.hashCode() + "\n");
            
            // Try to retrieve again (should still work with IdentityHashMap)
            UUID retrieved2 = testMap.get(buffer);
            debugFile.write("Retrieved after write: " + retrieved2 + "\n");
            debugFile.write("Found in map after write: " + (retrieved2 != null) + "\n");
            debugFile.write("Same ID: " + (testId.equals(retrieved2)) + "\n");
            
            // Verify the behavior
            assertNotNull(retrieved1, "Should find buffer before write");
            assertNotNull(retrieved2, "Should find buffer after write with IdentityHashMap");
            assertEquals(testId, retrieved2, "Should be the same ID");
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}