package com.hellblazer.luciferase.render.voxel.gpu;

import com.myworldllc.webgpu.WebGPU;
import com.myworldllc.webgpu.WebGPUTypes.*;
import static com.myworldllc.webgpu.WebGPUTypes.*;
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
        context = new WebGPUContext();
        arena = Arena.ofShared();
        
        if (!isWebGPUAvailable()) {
            return;
        }
        
        context.initialize().get(5, TimeUnit.SECONDS);
        bufferManager = new GPUBufferManager(context.getDevice(), context.getQueue());
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
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        Buffer buffer = bufferManager.createBuffer("test_buffer", 1024, 
            BufferUsage.STORAGE.or(BufferUsage.COPY_DST));
        
        assertNotNull(buffer);
        assertEquals(1024, buffer.getSize());
        assertEquals(1024, bufferManager.getTotalGPUMemory());
    }
    
    @Test
    @Order(2)
    @DisplayName("Create buffer from FFM MemorySegment")
    public void testCreateBufferFromSegment() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Create test data in FFM
        MemorySegment segment = arena.allocate(256);
        for (int i = 0; i < 64; i++) {
            segment.setAtIndex(ValueLayout.JAVA_FLOAT, i, (float) i);
        }
        
        Buffer buffer = bufferManager.createBufferFromSegment(segment, BufferUsage.STORAGE);
        assertNotNull(buffer);
        assertEquals(256, buffer.getSize());
    }
    
    @Test
    @Order(3)
    @DisplayName("Direct upload to GPU buffer")
    public void testDirectUpload() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Small data for direct upload
        MemorySegment data = arena.allocate(128);
        data.fill((byte) 42);
        
        Buffer buffer = bufferManager.createBuffer("direct_upload", 128,
            BufferUsage.STORAGE.or(BufferUsage.COPY_DST).or(BufferUsage.COPY_SRC));
        
        assertDoesNotThrow(() -> bufferManager.uploadToBuffer(buffer, data));
    }
    
    @Test
    @Order(4)
    @DisplayName("Staged upload for large data")
    public void testStagedUpload() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Large data for staged upload (512KB)
        int size = 512 * 1024;
        MemorySegment data = arena.allocate(size);
        
        // Fill with test pattern
        for (int i = 0; i < size / 4; i++) {
            data.setAtIndex(ValueLayout.JAVA_INT, i, i);
        }
        
        Buffer buffer = bufferManager.createBuffer("staged_upload", size,
            BufferUsage.STORAGE.or(BufferUsage.COPY_DST));
        
        assertDoesNotThrow(() -> bufferManager.uploadToBuffer(buffer, data));
    }
    
    @Test
    @Order(5)
    @DisplayName("Create specialized buffers")
    public void testSpecializedBuffers() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Octree buffer
        MemorySegment octreeData = arena.allocate(8192);
        Buffer octreeBuffer = bufferManager.createOctreeBuffer(octreeData);
        assertNotNull(octreeBuffer);
        
        // Ray buffer
        Buffer rayBuffer = bufferManager.createRayBuffer(1000);
        assertNotNull(rayBuffer);
        assertEquals(32000, rayBuffer.getSize()); // 1000 * 32 bytes
        
        // Result buffer
        Buffer resultBuffer = bufferManager.createResultBuffer(1000);
        assertNotNull(resultBuffer);
        assertEquals(32000, resultBuffer.getSize());
        
        // Uniform buffer
        Buffer uniformBuffer = bufferManager.createUniformBuffer("params", 64);
        assertNotNull(uniformBuffer);
        assertEquals(64, uniformBuffer.getSize());
    }
    
    @Test
    @Order(6)
    @DisplayName("Buffer readback")
    public void testBufferReadback() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Create and upload test data
        byte[] testData = new byte[256];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte) (i & 0xFF);
        }
        
        Buffer buffer = bufferManager.createBuffer("readback_test", 256,
            BufferUsage.STORAGE.or(BufferUsage.COPY_DST).or(BufferUsage.COPY_SRC));
        
        context.getQueue().writeBuffer(buffer, 0, testData, 0, testData.length);
        
        // Read back data
        CompletableFuture<byte[]> readFuture = bufferManager.readBuffer(buffer, 0, 256);
        byte[] readData = readFuture.get(5, TimeUnit.SECONDS);
        
        assertNotNull(readData);
        assertEquals(256, readData.length);
        assertArrayEquals(testData, readData);
    }
    
    @Test
    @Order(7)
    @DisplayName("Bind group creation")
    public void testBindGroupCreation() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        // Create layout
        BindGroupLayoutDescriptor layoutDesc = new BindGroupLayoutDescriptor();
        layoutDesc.setLabel("test_layout");
        
        BindGroupLayoutEntry entry = new BindGroupLayoutEntry();
        entry.setBinding(0);
        entry.setVisibility(ShaderStage.COMPUTE);
        BufferBindingLayout bufferBinding = new BufferBindingLayout();
        bufferBinding.setType(BufferBindingType.STORAGE);
        entry.setBuffer(bufferBinding);
        
        layoutDesc.setEntries(entry);
        BindGroupLayout layout = context.getDevice().createBindGroupLayout(layoutDesc);
        
        // Create buffer and bind group
        Buffer buffer = bufferManager.createBuffer("bind_test", 1024, BufferUsage.STORAGE);
        BindGroupEntry bindEntry = bufferManager.createBufferBinding(0, buffer, 0, 1024);
        
        BindGroup bindGroup = bufferManager.createBindGroup(layout, bindEntry);
        assertNotNull(bindGroup);
    }
    
    @Test
    @Order(8)
    @DisplayName("Memory tracking")
    public void testMemoryTracking() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        assertEquals(0, bufferManager.getTotalGPUMemory());
        
        // Create buffers
        bufferManager.createBuffer("buffer1", 1024, BufferUsage.STORAGE);
        assertEquals(1024, bufferManager.getTotalGPUMemory());
        
        bufferManager.createBuffer("buffer2", 2048, BufferUsage.STORAGE);
        assertEquals(3072, bufferManager.getTotalGPUMemory());
        
        // Release buffer
        bufferManager.releaseBuffer("buffer1");
        assertEquals(2048, bufferManager.getTotalGPUMemory());
        
        // Cleanup all
        bufferManager.cleanup();
        assertEquals(0, bufferManager.getTotalGPUMemory());
    }
    
    @Test
    @Order(9)
    @DisplayName("Buffer name collision")
    public void testBufferNameCollision() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available, skipping test");
            return;
        }
        
        Buffer buffer1 = bufferManager.createBuffer("same_name", 512, BufferUsage.STORAGE);
        Buffer buffer2 = bufferManager.createBuffer("same_name", 1024, BufferUsage.STORAGE);
        
        // Should return same buffer
        assertSame(buffer1, buffer2);
        assertEquals(512, buffer2.getSize()); // Original size
    }
    
    private boolean isWebGPUAvailable() {
        try {
            Instance testInstance = WebGPU.createInstance(new InstanceDescriptor());
            if (testInstance != null) {
                testInstance.release();
                return true;
            }
        } catch (Exception | UnsatisfiedLinkError e) {
            log.warn("WebGPU not available: {}", e.getMessage());
        }
        return false;
    }
}