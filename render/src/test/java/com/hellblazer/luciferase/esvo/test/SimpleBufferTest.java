package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SimpleBufferTest {
    
    @Test
    void testSimpleAllocation() {
        System.out.println("\n=== Simple Buffer Allocation Test ===");
        
        UnifiedResourceManager.resetInstance();
        UnifiedResourceManager manager = UnifiedResourceManager.getInstance();
        
        System.out.println("Initial count: " + manager.getActiveResourceCount());
        
        ByteBuffer buffer = manager.allocateMemory(1024);
        System.out.println("After allocate: " + manager.getActiveResourceCount());
        
        manager.releaseMemory(buffer);
        System.out.println("After release: " + manager.getActiveResourceCount());
        
        assertEquals(0, manager.getActiveResourceCount(), "Should have 0 resources after release");
    }
}