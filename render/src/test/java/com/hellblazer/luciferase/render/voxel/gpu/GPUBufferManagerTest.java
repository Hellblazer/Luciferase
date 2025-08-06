package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.webgpu.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GPU buffer management and FFM integration
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GPUBufferManagerTest {
    private static final Logger log = LoggerFactory.getLogger(GPUBufferManagerTest.class);
    
    private WebGPUContext context;
    private GPUBufferManager bufferManager;
    private Arena arena;
    
    @BeforeEach
    public void setup() throws Exception {
        // Use stub backend for testing
        WebGPUBackend backend = WebGPUBackendFactory.createStubBackend();
        context = new WebGPUContext(backend);
        arena = Arena.ofShared();
        
        if (!context.isAvailable()) {
            return;
        }
        
        context.initialize().get(5, TimeUnit.SECONDS);
        bufferManager = new GPUBufferManager(context);
    }
    
    @AfterEach
    public void tearDown() {
        if (bufferManager != null) {
            bufferManager.cleanup();
        }
        if (context != null) {
            context.shutdown();
        }
        if (arena != null) {
            arena.close();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Create basic GPU buffer")
    public void testCreateBuffer() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        long bufferSize = 1024;
        BufferHandle buffer = bufferManager.createBuffer(
            bufferSize,
            BufferUsage.STORAGE | BufferUsage.COPY_DST
        );
        
        assertNotNull(buffer);
        
        // Clean up
        bufferManager.releaseBuffer(buffer);
    }
    
    @Test
    @Order(2)
    @DisplayName("Write data to GPU buffer")
    public void testWriteToBuffer() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        long bufferSize = 256;
        BufferHandle buffer = bufferManager.createBuffer(
            bufferSize,
            BufferUsage.STORAGE | BufferUsage.COPY_DST | BufferUsage.COPY_SRC
        );
        
        // Create test data using FFM
        MemorySegment data = arena.allocate(bufferSize);
        for (int i = 0; i < bufferSize / 4; i++) {
            data.set(ValueLayout.JAVA_FLOAT, (long) i * 4, (float) i);
        }
        
        // Write to GPU
        bufferManager.writeBuffer(buffer, 0, data);
        
        // Clean up
        bufferManager.releaseBuffer(buffer);
    }
    
    @Test
    @Order(3)
    @DisplayName("Read data from GPU buffer")
    public void testReadFromBuffer() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        long bufferSize = 256;
        BufferHandle buffer = bufferManager.createBuffer(
            bufferSize,
            BufferUsage.STORAGE | BufferUsage.COPY_DST | BufferUsage.COPY_SRC
        );
        
        // Create and write test data
        MemorySegment writeData = arena.allocate(bufferSize);
        for (int i = 0; i < bufferSize / 4; i++) {
            writeData.set(ValueLayout.JAVA_FLOAT, (long) i * 4, (float) i * 2.0f);
        }
        bufferManager.writeBuffer(buffer, 0, writeData);
        
        // Read back from GPU
        CompletableFuture<MemorySegment> readFuture = bufferManager.readBuffer(
            buffer,
            0,
            bufferSize
        );
        
        MemorySegment readData = readFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(readData);
        
        // Verify data
        for (int i = 0; i < bufferSize / 4; i++) {
            float expected = (float) i * 2.0f;
            float actual = readData.get(ValueLayout.JAVA_FLOAT, (long) i * 4);
            assertEquals(expected, actual, 0.001f);
        }
        
        // Clean up
        bufferManager.releaseBuffer(buffer);
    }
    
    @Test
    @Order(4)
    @DisplayName("Buffer pool management")
    public void testBufferPooling() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        long bufferSize = 1024;
        
        // Allocate several buffers
        BufferHandle buffer1 = bufferManager.allocateFromPool(bufferSize);
        BufferHandle buffer2 = bufferManager.allocateFromPool(bufferSize);
        BufferHandle buffer3 = bufferManager.allocateFromPool(bufferSize);
        
        assertNotNull(buffer1);
        assertNotNull(buffer2);
        assertNotNull(buffer3);
        
        // Return buffers to pool
        bufferManager.returnToPool(buffer1);
        bufferManager.returnToPool(buffer2);
        
        // Allocate again - should reuse pooled buffers
        BufferHandle buffer4 = bufferManager.allocateFromPool(bufferSize);
        BufferHandle buffer5 = bufferManager.allocateFromPool(bufferSize);
        
        assertNotNull(buffer4);
        assertNotNull(buffer5);
        
        // Clean up
        bufferManager.returnToPool(buffer3);
        bufferManager.returnToPool(buffer4);
        bufferManager.returnToPool(buffer5);
    }
    
    @Test
    @Order(5)
    @DisplayName("Staging buffer for uploads")
    public void testStagingBuffer() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        long dataSize = 512;
        
        // Create staging buffer
        BufferHandle stagingBuffer = bufferManager.createStagingBuffer(dataSize);
        assertNotNull(stagingBuffer);
        
        // Write data to staging buffer
        MemorySegment data = arena.allocate(dataSize);
        for (int i = 0; i < dataSize / 8; i++) {
            data.set(ValueLayout.JAVA_DOUBLE, (long) i * 8, Math.PI * i);
        }
        
        bufferManager.writeToStagingBuffer(stagingBuffer, 0, data);
        
        // Create destination buffer
        BufferHandle destBuffer = bufferManager.createBuffer(
            dataSize,
            BufferUsage.STORAGE | BufferUsage.COPY_DST
        );
        
        // Copy from staging to destination
        bufferManager.copyBuffer(stagingBuffer, destBuffer, dataSize);
        
        // Clean up
        bufferManager.releaseBuffer(stagingBuffer);
        bufferManager.releaseBuffer(destBuffer);
    }
    
    @Test
    @Order(6)
    @DisplayName("Dynamic buffer resizing")
    public void testDynamicResize() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Start with small buffer
        long initialSize = 256;
        BufferHandle buffer = bufferManager.createDynamicBuffer(initialSize);
        assertNotNull(buffer);
        
        // Resize to larger size
        long newSize = 1024;
        BufferHandle resizedBuffer = bufferManager.resizeBuffer(buffer, newSize);
        assertNotNull(resizedBuffer);
        
        // Write data to resized buffer
        MemorySegment data = arena.allocate(newSize);
        bufferManager.writeBuffer(resizedBuffer, 0, data);
        
        // Clean up
        bufferManager.releaseBuffer(resizedBuffer);
    }
    
    @Test
    @Order(7)
    @DisplayName("Multi-buffering support")
    public void testMultiBuffering() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        int bufferCount = 3;
        long bufferSize = 512;
        
        GPUBufferManager.MultiBufferHandle multiBuffer = bufferManager.createMultiBuffer(
            bufferCount,
            bufferSize,
            BufferUsage.STORAGE | BufferUsage.COPY_DST
        );
        
        assertNotNull(multiBuffer);
        assertEquals(bufferCount, multiBuffer.getBufferCount());
        
        // Write to each buffer in sequence
        for (int i = 0; i < bufferCount; i++) {
            BufferHandle current = multiBuffer.getCurrentBuffer();
            assertNotNull(current);
            
            MemorySegment data = arena.allocate(bufferSize);
            data.set(ValueLayout.JAVA_INT, 0L, i);
            bufferManager.writeBuffer(current, 0, data);
            
            multiBuffer.swapBuffers();
        }
        
        // Clean up
        multiBuffer.release();
    }
    
    @Test
    @Order(8)
    @DisplayName("Buffer memory statistics")
    public void testMemoryStatistics() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Get initial stats
        GPUBufferManager.MemoryStatistics initialStats = bufferManager.getMemoryStatistics();
        long initialAllocated = initialStats.getTotalAllocated();
        
        // Allocate some buffers
        BufferHandle buffer1 = bufferManager.createBuffer(1024, BufferUsage.STORAGE);
        BufferHandle buffer2 = bufferManager.createBuffer(2048, BufferUsage.STORAGE);
        
        // Check stats increased
        GPUBufferManager.MemoryStatistics afterAlloc = bufferManager.getMemoryStatistics();
        assertTrue(afterAlloc.getTotalAllocated() > initialAllocated);
        assertTrue(afterAlloc.getActiveBuffers() >= 2);
        
        // Release buffers
        bufferManager.releaseBuffer(buffer1);
        bufferManager.releaseBuffer(buffer2);
        
        // Check stats decreased
        GPUBufferManager.MemoryStatistics afterRelease = bufferManager.getMemoryStatistics();
        assertTrue(afterRelease.getActiveBuffers() < afterAlloc.getActiveBuffers());
        
        log.info("Memory statistics - Allocated: {}, Active: {}, Pooled: {}",
                afterRelease.getTotalAllocated(),
                afterRelease.getActiveBuffers(),
                afterRelease.getPooledBuffers());
    }
    
    @Test
    @Order(9)
    @DisplayName("Error handling for invalid operations")
    public void testErrorHandling() throws Exception {
        if (!context.isAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Test invalid buffer size
        assertThrows(IllegalArgumentException.class, () -> {
            bufferManager.createBuffer(-1, BufferUsage.STORAGE);
        });
        
        // Test null data write
        BufferHandle buffer = bufferManager.createBuffer(256, BufferUsage.STORAGE | BufferUsage.COPY_DST);
        assertThrows(NullPointerException.class, () -> {
            bufferManager.writeBuffer(buffer, 0, null);
        });
        
        // Clean up
        bufferManager.releaseBuffer(buffer);
    }
}