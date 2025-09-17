package com.hellblazer.luciferase.esvo.test;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test to understand why synchronized IdentityHashMap fails with buffer position changes
 */
public class SynchronizedIdentityMapTest {

    @Test
    public void testSynchronizedIdentityMapWithBufferPosition() throws IOException {
        try (var debugFile = new FileWriter("/tmp/synchronized_identity_test.txt")) {
            debugFile.write("=== Synchronized IdentityHashMap Test ===\n");
            
            // Create synchronized IdentityHashMap like in UnifiedResourceManager
            Map<ByteBuffer, UUID> bufferToIdMap = Collections.synchronizedMap(new IdentityHashMap<>());
            
            // Allocate a direct buffer
            var buffer = MemoryUtil.memAlloc(16);
            var resourceId = UUID.randomUUID();
            
            debugFile.write("Initial buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Initial buffer position: " + buffer.position() + "\n");
            debugFile.write("Initial buffer limit: " + buffer.limit() + "\n");
            
            // Test 1: Put and get at position 0
            synchronized (bufferToIdMap) {
                bufferToIdMap.put(buffer, resourceId);
                debugFile.write("Put resourceId at position 0: " + resourceId + "\n");
                debugFile.write("Map size after put: " + bufferToIdMap.size() + "\n");
            }
            
            synchronized (bufferToIdMap) {
                var retrieved1 = bufferToIdMap.get(buffer);
                debugFile.write("Retrieved at position 0: " + retrieved1 + "\n");
                debugFile.write("Retrieved equals original: " + resourceId.equals(retrieved1) + "\n");
            }
            
            // Test 2: Change position and try to get
            buffer.position(4);
            debugFile.write("Changed position to: " + buffer.position() + "\n");
            debugFile.write("Buffer identity after position change: " + System.identityHashCode(buffer) + "\n");
            
            synchronized (bufferToIdMap) {
                var retrieved2 = bufferToIdMap.get(buffer);
                debugFile.write("Retrieved at position 4: " + retrieved2 + "\n");
                debugFile.write("Map size: " + bufferToIdMap.size() + "\n");
                
                // Check if the buffer is still in the keySet
                boolean foundInKeySet = false;
                for (var key : bufferToIdMap.keySet()) {
                    if (key == buffer) { // Identity comparison
                        foundInKeySet = true;
                        debugFile.write("Buffer found in keySet with identity comparison\n");
                        debugFile.write("Key identity: " + System.identityHashCode(key) + "\n");
                        debugFile.write("Key position: " + key.position() + "\n");
                        break;
                    }
                }
                if (!foundInKeySet) {
                    debugFile.write("Buffer NOT found in keySet with identity comparison\n");
                }
            }
            
            // Test 3: Reset position and try again
            buffer.position(0);
            debugFile.write("Reset position to: " + buffer.position() + "\n");
            
            synchronized (bufferToIdMap) {
                var retrieved3 = bufferToIdMap.get(buffer);
                debugFile.write("Retrieved after reset to position 0: " + retrieved3 + "\n");
            }
            
            // Test 4: Write data and try again
            buffer.putInt(42);
            debugFile.write("After putInt, position: " + buffer.position() + "\n");
            debugFile.write("Buffer identity after putInt: " + System.identityHashCode(buffer) + "\n");
            
            synchronized (bufferToIdMap) {
                var retrieved4 = bufferToIdMap.get(buffer);
                debugFile.write("Retrieved after putInt: " + retrieved4 + "\n");
            }
            
            debugFile.write("=== Test Complete ===\n");
            MemoryUtil.memFree(buffer);
        }
    }
    
    @Test
    public void testPlainIdentityMapWithBufferPosition() throws IOException {
        try (var debugFile = new FileWriter("/tmp/plain_identity_test.txt")) {
            debugFile.write("=== Plain IdentityHashMap Test ===\n");
            
            // Create plain IdentityHashMap without synchronization
            Map<ByteBuffer, UUID> bufferToIdMap = new IdentityHashMap<>();
            
            // Allocate a direct buffer
            var buffer = MemoryUtil.memAlloc(16);
            var resourceId = UUID.randomUUID();
            
            debugFile.write("Initial buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Initial buffer position: " + buffer.position() + "\n");
            
            // Test 1: Put and get at position 0
            bufferToIdMap.put(buffer, resourceId);
            debugFile.write("Put resourceId at position 0: " + resourceId + "\n");
            
            var retrieved1 = bufferToIdMap.get(buffer);
            debugFile.write("Retrieved at position 0: " + retrieved1 + "\n");
            
            // Test 2: Change position and try to get
            buffer.position(4);
            debugFile.write("Changed position to: " + buffer.position() + "\n");
            
            var retrieved2 = bufferToIdMap.get(buffer);
            debugFile.write("Retrieved at position 4: " + retrieved2 + "\n");
            
            // Test 3: Reset position and try again
            buffer.position(0);
            debugFile.write("Reset position to: " + buffer.position() + "\n");
            
            var retrieved3 = bufferToIdMap.get(buffer);
            debugFile.write("Retrieved after reset to position 0: " + retrieved3 + "\n");
            
            debugFile.write("=== Test Complete ===\n");
            MemoryUtil.memFree(buffer);
        }
    }
}