package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deep dive into the buffer tracking issue
 */
public class BufferTrackingDeepDive {

    @Test
    public void testExactUnifiedManagerBehavior() throws IOException {
        try (var debugFile = new FileWriter("/tmp/deep_dive_test.txt")) {
            debugFile.write("=== Deep Dive Test ===\n");
            
            // Reset and create fresh manager
            UnifiedResourceManager.resetInstance();
            var manager = UnifiedResourceManager.getInstance();
            
            // Allocate buffer
            var buffer = manager.allocateMemory(16);
            debugFile.write("Allocated buffer: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Initial position: " + buffer.position() + "\n");

            // Get resource ID immediately
            var resourceId1 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 0: " + resourceId1 + "\n");

            // Change position
            buffer.position(4);
            debugFile.write("Changed position to: " + buffer.position() + "\n");

            // Try to get resource ID again
            var resourceId2 = manager.getResourceId(buffer);
            debugFile.write("Resource ID at position 4: " + resourceId2 + "\n");

            // Check if they're the same
            debugFile.write("Same resource ID? " + (resourceId1 != null && resourceId1.equals(resourceId2)) + "\n");

            // Reset position
            buffer.position(0);
            var resourceId3 = manager.getResourceId(buffer);
            debugFile.write("Resource ID back at position 0: " + resourceId3 + "\n");
            
            // Now let's manually check the map
            debugFile.write("=== Manual Map Check ===\n");
            
            // Access the private field via reflection to see what's in the map
            try {
                var field = UnifiedResourceManager.class.getDeclaredField("bufferToIdMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<ByteBuffer, UUID> map = (Map<ByteBuffer, UUID>) field.get(manager);
                
                debugFile.write("Map size: " + map.size() + "\n");
                debugFile.write("Map contains our buffer? " + map.containsKey(buffer) + "\n");
                
                // Manual get
                var manualGet = map.get(buffer);
                debugFile.write("Manual map.get(buffer): " + manualGet + "\n");
                
                // Check all keys
                debugFile.write("All keys in map:\n");
                for (var key : map.keySet()) {
                    debugFile.write("  Key: " + System.identityHashCode(key) + 
                                   " (position: " + key.position() + 
                                   ") -> " + map.get(key) + "\n");
                    debugFile.write("    key == buffer? " + (key == buffer) + "\n");
                }
                
            } catch (Exception e) {
                debugFile.write("Error accessing map: " + e.getMessage() + "\n");
            }
            
            manager.releaseMemory(buffer);
            debugFile.write("=== Test Complete ===\n");
        }
    }
    
    @Test
    public void testDirectMapAccess() throws IOException {
        try (var debugFile = new FileWriter("/tmp/direct_map_test.txt")) {
            debugFile.write("=== Direct Map Test ===\n");
            
            // Create the exact same map structure as UnifiedResourceManager
            Map<ByteBuffer, UUID> map = Collections.synchronizedMap(new IdentityHashMap<>());
            
            // Create a buffer
            var buffer = ByteBuffer.allocateDirect(16);
            var uuid = UUID.randomUUID();
            
            debugFile.write("Buffer identity: " + System.identityHashCode(buffer) + "\n");
            debugFile.write("Initial position: " + buffer.position() + "\n");
            
            // Put in map
            synchronized (map) {
                map.put(buffer, uuid);
                debugFile.write("Put in map, size: " + map.size() + "\n");
            }
            
            // Get from map
            synchronized (map) {
                var retrieved1 = map.get(buffer);
                debugFile.write("Retrieved at position 0: " + retrieved1 + "\n");
            }
            
            // Change position
            buffer.position(4);
            debugFile.write("Changed position to: " + buffer.position() + "\n");
            
            // Get from map again
            synchronized (map) {
                var retrieved2 = map.get(buffer);
                debugFile.write("Retrieved at position 4: " + retrieved2 + "\n");
                
                // Check containsKey
                debugFile.write("map.containsKey(buffer): " + map.containsKey(buffer) + "\n");
                
                // Check keys
                for (var key : map.keySet()) {
                    debugFile.write("Key in map: " + System.identityHashCode(key) + 
                                   " (position: " + key.position() + ")\n");
                    debugFile.write("key == buffer? " + (key == buffer) + "\n");
                }
            }
            
            debugFile.write("=== Direct Map Test Complete ===\n");
        }
    }
}