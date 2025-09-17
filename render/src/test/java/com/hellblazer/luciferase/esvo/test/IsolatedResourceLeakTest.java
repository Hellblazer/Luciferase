package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvo.traversal.AdvancedRayTraversal;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Isolated test to identify resource leak source
 */
public class IsolatedResourceLeakTest {
    
    private UnifiedResourceManager resourceManager;
    
    @BeforeEach
    void setUp() {
        UnifiedResourceManager.resetInstance();
        resourceManager = UnifiedResourceManager.getInstance();
    }
    
    @Test
    void testOctreeBuilderAlone() {
        System.out.println("\n=== Testing OctreeBuilder Alone ===");
        int initialCount = resourceManager.getActiveResourceCount();
        System.out.println("Initial active resources: " + initialCount);
        
        try (OctreeBuilder builder = new OctreeBuilder(10)) {
            // Allocate a buffer
            ByteBuffer buffer = builder.allocateBuffer(1024, "test");
            System.out.println("After allocate in builder: " + resourceManager.getActiveResourceCount());
            
            // Add some voxels
            builder.addVoxel(1, 1, 1, 5, 1.0f);
            System.out.println("After add voxel: " + resourceManager.getActiveResourceCount());
        }
        
        int finalCount = resourceManager.getActiveResourceCount();
        System.out.println("Final active resources: " + finalCount);
        assertEquals(initialCount, finalCount, "OctreeBuilder should release all resources");
    }
    
    @Test
    void testAdvancedRayTraversalAlone() {
        System.out.println("\n=== Testing AdvancedRayTraversal Alone ===");
        int initialCount = resourceManager.getActiveResourceCount();
        System.out.println("Initial active resources: " + initialCount);
        
        try (AdvancedRayTraversal traversal = new AdvancedRayTraversal()) {
            // Allocate a buffer
            ByteBuffer buffer = traversal.allocateBuffer(1024, "test");
            System.out.println("After allocate in traversal: " + resourceManager.getActiveResourceCount());
            
            // Do a simple traversal
            Vector3f origin = new Vector3f(0, 0, 0);
            Vector3f direction = new Vector3f(1, 0, 0);
            traversal.traverse(origin, direction, buffer);
            System.out.println("After traverse: " + resourceManager.getActiveResourceCount());
        }
        
        int finalCount = resourceManager.getActiveResourceCount();
        System.out.println("Final active resources: " + finalCount);
        assertEquals(initialCount, finalCount, "AdvancedRayTraversal should release all resources");
    }
    
    @Test
    void testBothComponentsSequentially() {
        System.out.println("\n=== Testing Both Components Sequentially ===");
        int initialCount = resourceManager.getActiveResourceCount();
        System.out.println("Initial active resources: " + initialCount);
        
        // First OctreeBuilder
        try (OctreeBuilder builder = new OctreeBuilder(10)) {
            ByteBuffer buffer = builder.allocateBuffer(1024, "builder");
            System.out.println("After builder allocate: " + resourceManager.getActiveResourceCount());
            builder.addVoxel(1, 1, 1, 5, 1.0f);
        }
        System.out.println("After builder close: " + resourceManager.getActiveResourceCount());
        
        // Then AdvancedRayTraversal
        try (AdvancedRayTraversal traversal = new AdvancedRayTraversal()) {
            ByteBuffer buffer = traversal.allocateBuffer(1024, "traversal");
            System.out.println("After traversal allocate: " + resourceManager.getActiveResourceCount());
            Vector3f origin = new Vector3f(0, 0, 0);
            Vector3f direction = new Vector3f(1, 0, 0);
            traversal.traverse(origin, direction, buffer);
        }
        System.out.println("After traversal close: " + resourceManager.getActiveResourceCount());
        
        int finalCount = resourceManager.getActiveResourceCount();
        System.out.println("Final active resources: " + finalCount);
        assertEquals(initialCount, finalCount, "Both components should release all resources");
    }
    
    @Test
    void testMultipleBuffersInSameComponent() {
        System.out.println("\n=== Testing Multiple Buffers in Same Component ===");
        int initialCount = resourceManager.getActiveResourceCount();
        System.out.println("Initial active resources: " + initialCount);
        
        try (OctreeBuilder builder = new OctreeBuilder(10)) {
            // Allocate multiple buffers
            ByteBuffer buffer1 = builder.allocateBuffer(1024, "buffer1");
            System.out.println("After buffer1: " + resourceManager.getActiveResourceCount());
            
            ByteBuffer buffer2 = builder.allocateBuffer(2048, "buffer2");
            System.out.println("After buffer2: " + resourceManager.getActiveResourceCount());
            
            ByteBuffer buffer3 = builder.allocateBuffer(512, "buffer3");
            System.out.println("After buffer3: " + resourceManager.getActiveResourceCount());
            
            builder.addVoxel(1, 1, 1, 5, 1.0f);
        }
        
        int finalCount = resourceManager.getActiveResourceCount();
        System.out.println("Final active resources: " + finalCount);
        assertEquals(initialCount, finalCount, "OctreeBuilder should release all allocated buffers");
    }
}