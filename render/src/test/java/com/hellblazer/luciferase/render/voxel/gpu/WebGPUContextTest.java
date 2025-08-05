package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.*;
import static com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebGPU context initialization and management
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WebGPUContextTest {
    private static final Logger log = LoggerFactory.getLogger(WebGPUContextTest.class);
    
    private WebGPUContext context;
    
    @BeforeEach
    public void setup() {
        context = new WebGPUContext();
    }
    
    @AfterEach
    public void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("WebGPU context initialization")
    public void testInitialization() throws Exception {
        // Skip if no WebGPU support
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available on this system, skipping test");
            return;
        }
        
        CompletableFuture<Void> initFuture = context.initialize();
        assertNotNull(initFuture);
        
        // Wait for initialization
        initFuture.get(5, TimeUnit.SECONDS);
        
        // Verify initialization
        assertTrue(context.isInitialized());
        assertNotNull(context.getDevice());
        assertNotNull(context.getQueue());
        assertNotNull(context.getAdapter());
    }
    
    @Test
    @Order(2)
    @DisplayName("Feature detection")
    public void testFeatureDetection() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available on this system, skipping test");
            return;
        }
        
        context.initialize().get(5, TimeUnit.SECONDS);
        
        // Check common features
        log.info("Float32 filterable: {}", context.hasFeature(FeatureName.FLOAT32_FILTERABLE));
        log.info("Timestamp query: {}", context.hasFeature(FeatureName.TIMESTAMP_QUERY));
        log.info("Texture compression BC: {}", context.hasFeature(FeatureName.TEXTURE_COMPRESSION_BC));
    }
    
    @Test
    @Order(3)
    @DisplayName("Command encoder creation")
    public void testCommandEncoderCreation() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available on this system, skipping test");
            return;
        }
        
        context.initialize().get(5, TimeUnit.SECONDS);
        
        CommandEncoder encoder = context.createCommandEncoder();
        assertNotNull(encoder);
        
        // Finish and submit empty command buffer
        CommandBuffer commandBuffer = encoder.finish();
        assertNotNull(commandBuffer);
        
        assertDoesNotThrow(() -> context.submit(commandBuffer));
    }
    
    @Test
    @Order(4)
    @DisplayName("Queue synchronization")
    public void testQueueSync() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available on this system, skipping test");
            return;
        }
        
        context.initialize().get(5, TimeUnit.SECONDS);
        
        // Submit empty command
        CommandEncoder encoder = context.createCommandEncoder();
        context.submit(encoder.finish());
        
        // Wait for completion
        CompletableFuture<Void> waitFuture = context.waitIdle();
        assertNotNull(waitFuture);
        
        waitFuture.get(5, TimeUnit.SECONDS);
    }
    
    @Test
    @Order(5)
    @DisplayName("Multiple initialization attempts")
    public void testMultipleInitialization() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available on this system, skipping test");
            return;
        }
        
        // First initialization
        CompletableFuture<Void> first = context.initialize();
        first.get(5, TimeUnit.SECONDS);
        assertTrue(context.isInitialized());
        
        // Second initialization should return immediately
        CompletableFuture<Void> second = context.initialize();
        assertTrue(second.isDone());
        assertFalse(second.isCompletedExceptionally());
    }
    
    @Test
    @Order(6)
    @DisplayName("Context shutdown")
    public void testShutdown() throws Exception {
        if (!isWebGPUAvailable()) {
            log.warn("WebGPU not available on this system, skipping test");
            return;
        }
        
        context.initialize().get(5, TimeUnit.SECONDS);
        assertTrue(context.isInitialized());
        
        context.shutdown();
        assertFalse(context.isInitialized());
        assertNull(context.getDevice());
        assertNull(context.getQueue());
        assertNull(context.getAdapter());
    }
    
    @Test
    @Order(7)
    @DisplayName("Operations on uninitialized context")
    public void testUninitializedOperations() {
        assertFalse(context.isInitialized());
        
        assertThrows(IllegalStateException.class, () -> context.createCommandEncoder());
        
        CommandBuffer dummyBuffer = null;
        assertThrows(IllegalStateException.class, () -> context.submit(dummyBuffer));
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
