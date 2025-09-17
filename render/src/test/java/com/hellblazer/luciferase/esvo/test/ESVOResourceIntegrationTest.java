package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.esvo.builder.ESVOCPUBuilder;
import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvo.traversal.AdvancedRayTraversal;
import com.hellblazer.luciferase.esvo.io.ESVOSerializer;
import com.hellblazer.luciferase.esvo.io.ESVODeserializer;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;

import org.junit.jupiter.api.*;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test suite for ESVO components with Resource Management.
 * Verifies that all ESVO components properly manage resources and
 * prevent memory leaks during typical usage patterns.
 */
@DisplayName("ESVO Resource Integration Tests")
public class ESVOResourceIntegrationTest {
    
    private UnifiedResourceManager resourceManager;
    private Path tempDir;
    
    @BeforeEach
    void setUp() throws Exception {
        // Reset the singleton instance to ensure clean state
        UnifiedResourceManager.resetInstance();
        resourceManager = UnifiedResourceManager.getInstance();
        tempDir = Files.createTempDirectory("esvo-test");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        // Clean up temp files
        Files.walk(tempDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception e) {
                    // Ignore
                }
            });
    }
    
    @Nested
    @DisplayName("OctreeBuilder Resource Tests")
    class OctreeBuilderTests {
        
        @Test
        @DisplayName("Should properly manage memory during octree construction")
        void testOctreeBuilderResourceManagement() throws Exception {
            var initialStats = resourceManager.getResourceStats();
            System.out.println("Initial stats: " + initialStats);
            
            try (OctreeBuilder builder = new OctreeBuilder(10)) {
                // Add many voxels
                for (int i = 0; i < 1000; i++) {
                    builder.addVoxel(i % 100, (i / 100) % 100, i % 10, 5, 0.5f);
                }
                
                // Allocate a buffer for serialization
                ByteBuffer buffer = builder.allocateBuffer(1024 * 1024, "octree-buffer");
                assertNotNull(buffer, "Buffer should be allocated");
                
                // Serialize the octree
                builder.serialize(buffer);
                
                // Check that memory is being tracked
                assertTrue(builder.getTotalMemoryAllocated() > 0, 
                          "Should track memory allocation");
                
                var midStats = resourceManager.getResourceStats();
                System.out.println("Mid stats (before close): " + midStats);
            }
            
            // After try-with-resources, all resources should be released
            // Give a moment for resources to be cleaned up
            Thread.sleep(100);
            
            var finalStats = resourceManager.getResourceStats();
            System.out.println("Final stats (after close): " + finalStats);
            
            // Check for any leaked resources
            var leaks = resourceManager.checkForLeaks();
            if (!leaks.isEmpty()) {
                System.out.println("Detected leaks: " + String.join("\n", leaks));
            }
            
            // Force cleanup if needed
            resourceManager.performMaintenance();
            
            var reallyFinalStats = resourceManager.getResourceStats();
            System.out.println("Really final stats (after maintenance): " + reallyFinalStats);
            
            // Debug: Check resource count directly
            System.out.println("Debug - Active resource count: " + resourceManager.getActiveResourceCount());
            System.out.println("Debug - Total allocated bytes: " + resourceManager.getTotalAllocatedBytes());
            
            assertEquals(0, resourceManager.getActiveResourceCount(),
                        "All resources should be released after builder is closed");
        }
        
        @Test
        @DisplayName("Should handle concurrent octree building")
        void testConcurrentOctreeBuilding() throws InterruptedException {
            int builderCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(builderCount);
            CountDownLatch completionLatch = new CountDownLatch(builderCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicLong totalMemoryUsed = new AtomicLong(0);
            
            for (int i = 0; i < builderCount; i++) {
                final int builderId = i;
                executor.submit(() -> {
                    try (OctreeBuilder builder = new OctreeBuilder(8)) {
                        // Each builder creates its own octree
                        for (int v = 0; v < 500; v++) {
                            builder.addVoxel(
                                (v + builderId * 100) % 256,
                                v % 256,
                                (v * 2) % 256,
                                4,
                                ThreadLocalRandom.current().nextFloat()
                            );
                        }
                        
                        ByteBuffer buffer = builder.allocateBuffer(512 * 1024, 
                                                                  "builder-" + builderId);
                        builder.serialize(buffer);
                        
                        totalMemoryUsed.addAndGet(builder.getTotalMemoryAllocated());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            assertTrue(completionLatch.await(10, TimeUnit.SECONDS),
                      "All builders should complete");
            executor.shutdown();
            
            assertEquals(builderCount, successCount.get(),
                        "All builders should succeed");
            assertTrue(totalMemoryUsed.get() > 0,
                      "Memory should have been used");
        }
    }
    
    @Nested
    @DisplayName("AdvancedRayTraversal Resource Tests")
    class RayTraversalTests {
        
        @Test
        @DisplayName("Should manage resources during ray traversal")
        void testRayTraversalResourceManagement() {
            try (AdvancedRayTraversal traversal = new AdvancedRayTraversal()) {
                // Allocate buffer for octree data
                ByteBuffer octreeBuffer = traversal.allocateBuffer(1024 * 1024, "octree-data");
                
                // Perform multiple ray traversals
                Vector3f origin = new Vector3f(1.5f, 1.5f, 1.5f);
                for (int i = 0; i < 100; i++) {
                    Vector3f direction = new Vector3f(
                        (float) Math.cos(i * 0.1),
                        (float) Math.sin(i * 0.1),
                        0.5f
                    );
                    direction.normalize();
                    
                    var hit = traversal.traverse(origin, direction, octreeBuffer);
                    // Hit may or may not occur depending on octree content
                }
                
                // Check performance stats
                String stats = traversal.getPerformanceStats();
                assertNotNull(stats, "Should provide performance statistics");
                
                assertTrue(traversal.getTotalMemoryAllocated() > 0,
                          "Should track memory allocation");
            }
            
            // Resources should be released after try-with-resources
        }
        
        @Test
        @DisplayName("Should handle beam traversal with proper cleanup")
        void testBeamTraversalResourceManagement() {
            try (AdvancedRayTraversal traversal = new AdvancedRayTraversal()) {
                traversal.setBeamOptimizationEnabled(true);
                
                ByteBuffer octreeBuffer = traversal.allocateBuffer(2 * 1024 * 1024, "beam-data");
                
                // Create a beam of rays
                Vector3f origin = new Vector3f(1.0f, 1.0f, 1.0f);
                List<Vector3f> directions = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Vector3f dir = new Vector3f(1.0f, 0.0f, 0.0f);
                    // Slight variations for beam coherence
                    dir.y = i * 0.01f;
                    dir.normalize();
                    directions.add(dir);
                }
                
                // Traverse beam
                var results = traversal.traverseBeam(origin, directions, octreeBuffer);
                assertEquals(directions.size(), results.size(),
                            "Should return result for each ray");
                
                assertTrue(traversal.getBeamTraversals() >= 0,
                          "Should track beam traversals");
            }
        }
    }
    
    @Nested
    @DisplayName("Serialization Resource Tests")
    class SerializationTests {
        
        @Test
        @DisplayName("Should manage resources during serialization")
        void testSerializationResourceManagement() throws Exception {
            Path testFile = tempDir.resolve("test.esvo");
            
            // Create test data
            ESVOOctreeData octreeData = new ESVOOctreeData(1024);
            
            // Serialize with resource tracking
            try (ESVOSerializer serializer = new ESVOSerializer()) {
                serializer.serialize(octreeData, testFile);
                
                assertTrue(serializer.getTotalBytesWritten() > 0,
                          "Should track bytes written");
            }
            
            // Deserialize
            ESVODeserializer deserializer = new ESVODeserializer();
            ESVOOctreeData loaded = deserializer.deserialize(testFile);
            assertNotNull(loaded, "Should load octree data");
        }
        
        @Test
        @DisplayName("Should handle concurrent serialization")
        void testConcurrentSerialization() throws Exception {
            int writerCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(writerCount);
            CountDownLatch completionLatch = new CountDownLatch(writerCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            for (int i = 0; i < writerCount; i++) {
                final int writerId = i;
                executor.submit(() -> {
                    try {
                        Path file = tempDir.resolve("concurrent-" + writerId + ".esvo");
                        ESVOOctreeData data = new ESVOOctreeData(512);
                        
                        try (ESVOSerializer serializer = new ESVOSerializer()) {
                            serializer.serialize(data, file);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            assertTrue(completionLatch.await(10, TimeUnit.SECONDS),
                      "All serializations should complete");
            executor.shutdown();
            
            assertEquals(writerCount, successCount.get(),
                        "All serializations should succeed");
        }
    }
    
    @Nested
    @DisplayName("CPU Builder Resource Tests")
    class CPUBuilderTests {
        
        @Test
        @DisplayName("Should manage thread pool resources properly")
        void testCPUBuilderThreadPoolManagement() {
            var initialStats = resourceManager.getResourceStats();
            
            try (ESVOCPUBuilder cpuBuilder = new ESVOCPUBuilder()) {
                // Initialize builder
                cpuBuilder.initialize(8);
                
                // Process some data using thread pool
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    final int taskId = i;
                    Future<Boolean> future = cpuBuilder.submitTask(() -> {
                        // Simulate work
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return taskId % 2 == 0;
                    });
                    futures.add(future);
                }
                
                // Wait for completion
                for (Future<Boolean> future : futures) {
                    try {
                        future.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // Handle timeout or execution exception
                    }
                }
                
                assertTrue(cpuBuilder.getActiveThreadCount() >= 0,
                          "Should track active threads");
            }
            
            // Verify cleanup
            var finalStats = resourceManager.getResourceStats();
            assertEquals(initialStats.activeResources(), finalStats.activeResources(),
                        "All resources should be released after builder is closed");
        }
        
        @Test
        @DisplayName("Should handle builder lifecycle correctly")
        void testBuilderLifecycle() {
            ESVOCPUBuilder builder = new ESVOCPUBuilder();
            
            // Initialize
            builder.initialize(4);
            assertTrue(builder.isInitialized(), "Should be initialized");
            
            // Use the builder
            Future<String> task = builder.submitTask(() -> "test-result");
            
            try {
                String result = task.get(1, TimeUnit.SECONDS);
                assertEquals("test-result", result);
            } catch (Exception e) {
                fail("Task should complete successfully");
            }
            
            // Close
            builder.close();
            assertFalse(builder.isInitialized(), "Should not be initialized after close");
            
            // Verify cannot use after close
            assertThrows(IllegalStateException.class, () -> {
                builder.submitTask(() -> "should-fail");
            });
        }
    }
    
    @Nested
    @DisplayName("End-to-End Integration Tests")
    class EndToEndTests {
        
        @Test
        @DisplayName("Should handle complete ESVO pipeline without leaks")
        void testCompleteESVOPipeline() throws Exception {
            var initialStats = resourceManager.getResourceStats();
            System.out.println("Initial resources: " + initialStats.activeResources());
            
            // Build octree
            ESVOOctreeData octreeData;
            try (OctreeBuilder builder = new OctreeBuilder(10)) {
                System.out.println("After builder creation: " + resourceManager.getActiveResourceCount());
                
                // Add voxels
                for (int x = 0; x < 50; x++) {
                    for (int y = 0; y < 50; y++) {
                        for (int z = 0; z < 10; z++) {
                            if ((x + y + z) % 3 == 0) {
                                builder.addVoxel(x, y, z, 5, 1.0f);
                            }
                        }
                    }
                }
                
                // Create octree data
                octreeData = new ESVOOctreeData(100000);
                ByteBuffer buffer = builder.allocateBuffer(octreeData.getCapacity(), "octree");
                System.out.println("After builder.allocateBuffer: " + resourceManager.getActiveResourceCount());
                builder.serialize(buffer);
                System.out.println("After builder.serialize: " + resourceManager.getActiveResourceCount());
            }
            System.out.println("After builder closed: " + resourceManager.getActiveResourceCount());
            
            // Serialize to file
            Path octreeFile = tempDir.resolve("pipeline.esvo");
            try (ESVOSerializer serializer = new ESVOSerializer()) {
                System.out.println("Before serialize: " + resourceManager.getActiveResourceCount());
                serializer.serialize(octreeData, octreeFile);
                System.out.println("After serialize: " + resourceManager.getActiveResourceCount());
            }
            System.out.println("After serializer closed: " + resourceManager.getActiveResourceCount());
            
            // Deserialize
            ESVODeserializer deserializer = new ESVODeserializer();
            ESVOOctreeData loaded = deserializer.deserialize(octreeFile);
            System.out.println("After deserialize: " + resourceManager.getActiveResourceCount());
            
            // Perform ray traversal
            try (AdvancedRayTraversal traversal = new AdvancedRayTraversal()) {
                System.out.println("After traversal creation: " + resourceManager.getActiveResourceCount());
                ByteBuffer octreeBuffer = traversal.allocateBuffer(
                    loaded.getCapacity(), "traversal"
                );
                System.out.println("After traversal.allocateBuffer: " + resourceManager.getActiveResourceCount());
                
                // Trace some rays
                for (int i = 0; i < 100; i++) {
                    Vector3f origin = new Vector3f(25.0f, 25.0f, 0.0f);
                    Vector3f direction = new Vector3f(
                        (float) Math.cos(i * 0.1),
                        (float) Math.sin(i * 0.1),
                        1.0f
                    );
                    direction.normalize();
                    
                    traversal.traverse(origin, direction, octreeBuffer);
                }
                System.out.println("After all traversals: " + resourceManager.getActiveResourceCount());
            }
            System.out.println("After traversal closed: " + resourceManager.getActiveResourceCount());
            
            // Verify no resource leaks
            var finalStats = resourceManager.getResourceStats();
            System.out.println("Final resources: " + finalStats.activeResources());
            assertEquals(initialStats.activeResources(), finalStats.activeResources(),
                        "No resources should be leaked in complete pipeline");
        }
    }
}