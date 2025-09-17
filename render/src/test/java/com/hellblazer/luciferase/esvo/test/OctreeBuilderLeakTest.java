package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class OctreeBuilderLeakTest {
    
    @Test
    void testOctreeBuilderDirectly() {
        System.out.println("\n=== Direct OctreeBuilder Test ===");
        
        UnifiedResourceManager.resetInstance();
        UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();
        
        System.out.println("Initial: " + resourceManager.getActiveResourceCount());
        
        OctreeBuilder builder = new OctreeBuilder(10);
        System.out.println("After builder created: " + resourceManager.getActiveResourceCount());
        
        ByteBuffer buffer = builder.allocateBuffer(1024, "test");
        System.out.println("After allocateBuffer: " + resourceManager.getActiveResourceCount());
        
        builder.addVoxel(1, 1, 1, 5, 1.0f);
        System.out.println("After addVoxel: " + resourceManager.getActiveResourceCount());
        
        builder.serialize(buffer);  
        System.out.println("After serialize: " + resourceManager.getActiveResourceCount());
        
        builder.close();
        System.out.println("After close: " + resourceManager.getActiveResourceCount());
        
        assertEquals(0, resourceManager.getActiveResourceCount(), "Should have no resources after close");
    }
    
    @Test
    void testOctreeBuilderWithTryResource() {
        System.out.println("\n=== Try-With-Resources OctreeBuilder Test ===");
        
        UnifiedResourceManager.resetInstance();
        UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();
        
        System.out.println("Initial: " + resourceManager.getActiveResourceCount());
        
        try (OctreeBuilder builder = new OctreeBuilder(10)) {
            System.out.println("After builder created: " + resourceManager.getActiveResourceCount());
            
            ByteBuffer buffer = builder.allocateBuffer(1024, "test");
            System.out.println("After allocateBuffer: " + resourceManager.getActiveResourceCount());
            
            builder.addVoxel(1, 1, 1, 5, 1.0f);
            System.out.println("After addVoxel: " + resourceManager.getActiveResourceCount());
            
            builder.serialize(buffer);  
            System.out.println("After serialize: " + resourceManager.getActiveResourceCount());
        }
        
        System.out.println("After try-with-resources: " + resourceManager.getActiveResourceCount());
        
        assertEquals(0, resourceManager.getActiveResourceCount(), "Should have no resources after close");
    }
}