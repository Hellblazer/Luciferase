package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

public class SimpleResourceTest {
    
    @Test
    void testSimpleAllocationAndRelease() {
        UnifiedResourceManager.resetInstance();
        var manager = UnifiedResourceManager.getInstance();
        
        System.out.println("Initial active count: " + manager.getActiveResourceCount());
        assertEquals(0, manager.getActiveResourceCount(), "Should start with no active resources");
        
        // Allocate a buffer
        ByteBuffer buffer = manager.allocateMemory(1024);
        assertNotNull(buffer);
        System.out.println("After allocation - active count: " + manager.getActiveResourceCount());
        assertEquals(1, manager.getActiveResourceCount(), "Should have 1 active resource after allocation");
        
        // Release the buffer
        manager.releaseMemory(buffer);
        System.out.println("After release - active count: " + manager.getActiveResourceCount());
        assertEquals(0, manager.getActiveResourceCount(), "Should have 0 active resources after release");
        
        // Close manager
        manager.close();
    }
    
    @Test
    void testMultipleAllocationsAndReleases() {
        UnifiedResourceManager.resetInstance();
        var manager = UnifiedResourceManager.getInstance();
        
        System.out.println("Initial active count: " + manager.getActiveResourceCount());
        assertEquals(0, manager.getActiveResourceCount(), "Should start with no active resources");
        
        // Allocate two buffers
        ByteBuffer buffer1 = manager.allocateMemory(1024);
        assertNotNull(buffer1);
        System.out.println("After first allocation - active count: " + manager.getActiveResourceCount());
        assertEquals(1, manager.getActiveResourceCount(), "Should have 1 active resource");
        
        ByteBuffer buffer2 = manager.allocateMemory(2048);
        assertNotNull(buffer2);
        System.out.println("After second allocation - active count: " + manager.getActiveResourceCount());
        assertEquals(2, manager.getActiveResourceCount(), "Should have 2 active resources");
        
        // Release first buffer
        manager.releaseMemory(buffer1);
        System.out.println("After first release - active count: " + manager.getActiveResourceCount());
        assertEquals(1, manager.getActiveResourceCount(), "Should have 1 active resource");
        
        // Release second buffer
        manager.releaseMemory(buffer2);
        System.out.println("After second release - active count: " + manager.getActiveResourceCount());
        assertEquals(0, manager.getActiveResourceCount(), "Should have 0 active resources");
        
        // Close manager
        manager.close();
    }
}