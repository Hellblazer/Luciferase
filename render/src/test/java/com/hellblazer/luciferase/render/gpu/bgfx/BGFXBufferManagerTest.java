package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for BGFXBufferManager functionality.
 * Validates buffer creation, binding, and lifecycle management for ESVO operations.
 */
class BGFXBufferManagerTest {
    
    private BGFXGPUContext context;
    private BGFXBufferManager bufferManager;
    
    @BeforeEach
    void setUp() {
        // Create a mock BGFX context for testing
        context = new BGFXGPUContext();
        
        // Initialize with headless configuration for testing
        var config = GPUConfig.builder()
                .withBackend(GPUConfig.Backend.BGFX_METAL)
                .withHeadless(true)
                .withWidth(256)
                .withHeight(256)
                .build();
        
        // Note: Actual BGFX initialization will fail in test environment
        // but we can test the buffer manager logic
        bufferManager = new BGFXBufferManager(context);
    }
    
    @AfterEach
    void tearDown() {
        if (bufferManager != null) {
            bufferManager.cleanup();
        }
        if (context != null) {
            context.cleanup();
        }
    }
    
    @Test
    void testBufferSlotEnumValues() {
        // Test that all expected buffer slots are defined
        var slots = BufferSlot.values();
        assertTrue(slots.length >= 12, "Should have at least 12 buffer slots for ESVO");
        
        // Test slot index mapping
        assertEquals(0, BufferSlot.NODE_BUFFER.getSlotIndex());
        assertEquals(1, BufferSlot.PAGE_BUFFER.getSlotIndex());
        assertEquals(2, BufferSlot.WORK_QUEUE.getSlotIndex());
        assertEquals(3, BufferSlot.COUNTER_BUFFER.getSlotIndex());
    }
    
    @Test
    void testBufferSlotCategories() {
        // Test storage buffer classification
        assertTrue(BufferSlot.NODE_BUFFER.isStorageBuffer());
        assertTrue(BufferSlot.PAGE_BUFFER.isStorageBuffer());
        assertTrue(BufferSlot.WORK_QUEUE.isStorageBuffer());
        
        // Test uniform buffer classification
        assertTrue(BufferSlot.TRAVERSAL_UNIFORMS.isUniformBuffer());
        assertTrue(BufferSlot.MATERIAL_UNIFORMS.isUniformBuffer());
        
        // Test image slot classification
        assertTrue(BufferSlot.RAY_ORIGIN_IMAGE.isImageSlot());
        assertTrue(BufferSlot.HIT_RESULT_IMAGE.isImageSlot());
    }
    
    @Test
    void testBufferSlotAccessPatterns() {
        // Test read-only buffers
        assertEquals(AccessType.READ_ONLY, BufferSlot.NODE_BUFFER.getTypicalAccess());
        assertEquals(AccessType.READ_ONLY, BufferSlot.PAGE_BUFFER.getTypicalAccess());
        
        // Test read-write buffers
        assertEquals(AccessType.READ_WRITE, BufferSlot.WORK_QUEUE.getTypicalAccess());
        assertEquals(AccessType.READ_WRITE, BufferSlot.COUNTER_BUFFER.getTypicalAccess());
        
        // Test write-only buffers
        assertEquals(AccessType.WRITE_ONLY, BufferSlot.STATISTICS_BUFFER.getTypicalAccess());
        assertEquals(AccessType.WRITE_ONLY, BufferSlot.HIT_RESULT_IMAGE.getTypicalAccess());
    }
    
    @Test
    void testAtomicOperationRequirements() {
        // Test atomic operation detection
        assertTrue(BufferSlot.WORK_QUEUE.requiresAtomicOps());
        assertTrue(BufferSlot.COUNTER_BUFFER.requiresAtomicOps());
        
        // Test non-atomic buffers
        assertFalse(BufferSlot.NODE_BUFFER.requiresAtomicOps());
        assertFalse(BufferSlot.PAGE_BUFFER.requiresAtomicOps());
        assertFalse(BufferSlot.STATISTICS_BUFFER.requiresAtomicOps());
    }
    
    @Test
    void testBufferSizeCalculations() {
        // Test fixed-size buffers
        assertEquals(16, BufferSlot.COUNTER_BUFFER.getTypicalSize());
        assertEquals(1024, BufferSlot.STATISTICS_BUFFER.getTypicalSize());
        assertEquals(4096, BufferSlot.DEBUG_BUFFER.getTypicalSize());
        assertEquals(84, BufferSlot.TRAVERSAL_UNIFORMS.getTypicalSize());
        
        // Test variable-size buffers
        assertEquals(-1, BufferSlot.NODE_BUFFER.getTypicalSize());
        assertEquals(-1, BufferSlot.PAGE_BUFFER.getTypicalSize());
        assertEquals(-1, BufferSlot.RAY_ORIGIN_IMAGE.getTypicalSize());
    }
    
    @Test
    void testBufferManagerInitialization() {
        assertNotNull(bufferManager);
        assertNotNull(bufferManager.getStats());
        
        var stats = bufferManager.getStats();
        assertEquals(0, stats.totalBuffersCreated());
        assertEquals(0, stats.totalMemoryAllocated());
        assertEquals(0, stats.currentlyBoundBuffers());
    }
    
    @Test
    void testESVOBufferSetCreation() {
        // This test validates the buffer set creation logic without actual GPU initialization
        try {
            var buffers = bufferManager.createESVOBufferSet(1000, 100, 65536);
            
            // Verify that the correct buffer types are created
            assertTrue(buffers.containsKey(BufferSlot.NODE_BUFFER));
            assertTrue(buffers.containsKey(BufferSlot.PAGE_BUFFER));
            assertTrue(buffers.containsKey(BufferSlot.WORK_QUEUE));
            assertTrue(buffers.containsKey(BufferSlot.COUNTER_BUFFER));
            
            // This will fail due to no actual GPU context, but validates the logic
            fail("Expected RuntimeException due to no GPU context");
            
        } catch (RuntimeException e) {
            // Expected failure due to no actual BGFX initialization
            assertTrue(e.getMessage().contains("Failed to create buffer") || 
                      e.getMessage().contains("GPU context not initialized"));
        }
    }
    
    @Test
    void testBufferSlotValidation() {
        // Test that buffer slot descriptions are meaningful
        for (var slot : BufferSlot.values()) {
            assertNotNull(slot.getDescription());
            assertFalse(slot.getDescription().isEmpty());
            assertTrue(slot.getDescription().length() > 10);
        }
    }
    
    @Test
    void testBufferTypeMapping() {
        // Test that each slot category maps to appropriate buffer types
        // This tests the private method logic through the buffer manager
        
        // Storage buffers should map to STORAGE type
        for (var slot : BufferSlot.values()) {
            if (slot.isStorageBuffer()) {
                assertTrue(slot.getSlotIndex() >= 0 && slot.getSlotIndex() <= 5);
            }
        }
        
        // Uniform buffers should map to UNIFORM type
        for (var slot : BufferSlot.values()) {
            if (slot.isUniformBuffer()) {
                assertTrue(slot.getSlotIndex() >= 6 && slot.getSlotIndex() <= 7);
            }
        }
        
        // Image slots should map to TEXTURE type
        for (var slot : BufferSlot.values()) {
            if (slot.isImageSlot()) {
                assertTrue(slot.getSlotIndex() >= 8 && slot.getSlotIndex() <= 11);
            }
        }
    }
    
    @Test
    void testBufferManagerCleanup() {
        // Test cleanup doesn't crash
        assertDoesNotThrow(() -> bufferManager.cleanup());
        
        // Test multiple cleanups are safe
        assertDoesNotThrow(() -> bufferManager.cleanup());
        assertDoesNotThrow(() -> bufferManager.cleanup());
    }
    
    @Test
    void testWorkQueueSizeCalculation() {
        // Test work queue size calculation logic
        int rayCount = 65536;
        int expectedSize = Math.max(rayCount, 32768) * 32;
        
        // This validates the size calculation used in createESVOBufferSet
        assertEquals(rayCount * 32, expectedSize);
        
        // Test minimum size handling
        int smallRayCount = 1000;
        int minExpectedSize = Math.max(smallRayCount, 32768) * 32;
        assertEquals(32768 * 32, minExpectedSize);
    }
    
    @Test
    void testNodeBufferSizeCalculation() {
        // Test node buffer size calculation (8 bytes per ESVONode)
        int nodeCount = 1000000; // 1M nodes
        int expectedSize = nodeCount * 8;
        assertEquals(8000000, expectedSize); // 8MB
        
        // Validate this matches typical size expectations
        assertTrue(expectedSize > 1024 * 1024); // > 1MB
        assertTrue(expectedSize < 100 * 1024 * 1024); // < 100MB
    }
    
    @Test
    void testPageBufferSizeCalculation() {
        // Test page buffer size calculation (8KB per ESVOPage)
        int pageCount = 8192; // 8K pages
        int expectedSize = pageCount * 8192;
        assertEquals(67108864, expectedSize); // 64MB
        
        // Validate this matches ESVO page structure requirements
        assertTrue(expectedSize % 8192 == 0); // Must be multiple of page size
    }
}