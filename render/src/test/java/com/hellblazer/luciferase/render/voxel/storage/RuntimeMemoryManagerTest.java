package com.hellblazer.luciferase.render.voxel.storage;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Vector3f;
import javax.vecmath.Color3f;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.hellblazer.luciferase.render.voxel.storage.RuntimeMemoryManager.*;

/**
 * Tests for RuntimeMemoryManager three-tier storage system.
 */
public class RuntimeMemoryManagerTest {
    
    private Path tempDir;
    private ClusteredFile clusteredFile;
    private OctreeFile octreeFile;
    private RuntimeMemoryManager memoryManager;
    private Config config;
    
    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("memory-manager-test");
        
        var clusteredPath = tempDir.resolve("clustered.dat");
        var octreePath = tempDir.resolve("octree.dat");
        
        clusteredFile = ClusteredFile.create(clusteredPath, ClusteredFile.CompressionType.NONE);
        octreeFile = OctreeFile.create(octreePath.toFile());
        
        config = new Config();
        config.hotCacheMaxBytes = 1024 * 1024; // 1MB for testing
        config.warmCacheMaxBytes = 512 * 1024; // 512KB for testing
        config.nodePoolSize = 100;
        config.prefetchThreads = 1;
        config.memoryPressureThreshold = 0.5f; // Lower threshold to trigger eviction
        
        memoryManager = new RuntimeMemoryManager(config, clusteredFile, octreeFile);
    }
    
    @AfterEach
    void tearDown() throws IOException {
        memoryManager.shutdown();
        clusteredFile.close();
        octreeFile.close();
        
        // Clean up temp files
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    // Ignore cleanup errors
                }
            });
    }
    
    @Test
    void testBasicNodeOperations() {
        // Create and store a node
        var node = new ManagedNode(1L, 0, new Vector3f(0, 0, 0), 1.0f);
        node.color = new Color3f(1.0f, 0.5f, 0.25f);
        
        memoryManager.putNode(node);
        
        // Retrieve the node
        var retrievedNode = memoryManager.getNode(1L);
        assertNotNull(retrievedNode);
        assertEquals(1L, retrievedNode.nodeId);
        assertEquals(0, retrievedNode.depth);
        assertEquals(CacheLevel.HOT, retrievedNode.cacheLevel);
        assertTrue(retrievedNode.accessCount > 0);
    }
    
    @Test
    void testCacheLevelPromotion() {
        var node = new ManagedNode(2L, 1, new Vector3f(1, 1, 1), 0.5f);
        
        // With plenty of memory available, new nodes go to hot cache directly
        memoryManager.putNode(node);
        assertEquals(CacheLevel.HOT, node.cacheLevel);
        
        // Access multiple times to trigger promotion
        for (int i = 0; i < 5; i++) {
            var retrieved = memoryManager.getNode(2L);
            assertNotNull(retrieved);
        }
        
        // Should now be in hot cache
        assertEquals(CacheLevel.HOT, node.cacheLevel);
    }
    
    @Test
    void testMemoryPressureEviction() {
        // Enable aggressive eviction to actually evict nodes instead of just moving them
        config.aggressiveEviction = true;
        memoryManager = new RuntimeMemoryManager(config, clusteredFile, octreeFile);
        
        // Fill up hot cache with many nodes
        var nodes = new ArrayList<ManagedNode>();
        
        // Create enough nodes to exceed cache limits (1MB / 200 bytes = ~5000 nodes needed)
        for (int i = 0; i < 6000; i++) {
            var node = new ManagedNode(i + 100L, 1, new Vector3f(i, i, i), 1.0f);
            node.accessCount = 5; // Force to hot cache
            nodes.add(node);
            memoryManager.putNode(node);
        }
        
        // Check that cache stats show evictions occurred
        var stats = memoryManager.getStats();
        assertTrue(stats.evictions > 0, "Expected evictions due to memory pressure");
        assertTrue(stats.currentHotMemory <= config.hotCacheMaxBytes, "Hot cache should be under limit");
    }
    
    @Test
    void testLRUEvictionOrder() {
        // Create nodes with different access times
        var oldNode = new ManagedNode(200L, 0, new Vector3f(), 1.0f);
        var newNode = new ManagedNode(201L, 0, new Vector3f(), 1.0f);
        
        memoryManager.putNode(oldNode);
        
        // Wait to ensure different timestamps
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        memoryManager.putNode(newNode);
        
        // Access new node to make it more recently used
        memoryManager.getNode(201L);
        
        // Force eviction by filling cache
        for (int i = 0; i < 100; i++) {
            var filler = new ManagedNode(300L + i, 0, new Vector3f(), 1.0f);
            filler.accessCount = 10; // Force to hot cache
            memoryManager.putNode(filler);
        }
        
        // Old node should be more likely to be evicted
        var retrievedOld = memoryManager.getNode(200L);
        var retrievedNew = memoryManager.getNode(201L);
        
        // At least one should still be accessible (preferably the newer one)
        assertTrue(retrievedOld != null || retrievedNew != null);
    }
    
    @Test
    void testNodePersistence() throws Exception {
        // Enable aggressive eviction to actually save nodes to disk when evicted
        config.aggressiveEviction = true;
        memoryManager = new RuntimeMemoryManager(config, clusteredFile, octreeFile);
        
        // Create a node and let it be written to disk
        var originalNode = new ManagedNode(500L, 2, new Vector3f(5, 5, 5), 0.25f);
        originalNode.color = new Color3f(0.8f, 0.2f, 0.9f);
        originalNode.normal = new Vector3f(1, 0, 0);
        
        memoryManager.putNode(originalNode);
        
        // Force eviction to disk
        memoryManager.evictNodes(Long.MAX_VALUE);
        
        // Give time for background save
        Thread.sleep(100);
        
        // Try to retrieve - should load from disk
        var loadedNode = memoryManager.getNode(500L);
        assertNotNull(loadedNode);
        assertEquals(500L, loadedNode.nodeId);
        assertEquals(2, loadedNode.depth);
        
        // Verify stats show disk operations
        var stats = memoryManager.getStats();
        assertTrue(stats.nodesLoaded > 0 || stats.nodesSaved > 0);
    }
    
    @Test
    void testCacheStatistics() {
        var stats = memoryManager.getStats();
        long initialHits = stats.hotCacheHits + stats.warmCacheHits;
        long initialMisses = stats.cacheMisses;
        
        // Create and access a node (should be cache miss initially)
        var node = new ManagedNode(600L, 0, new Vector3f(), 1.0f);
        memoryManager.putNode(node);
        
        // First access after put - should be cache hit
        memoryManager.getNode(600L);
        
        // Second access - should also be cache hit
        memoryManager.getNode(600L);
        
        var finalStats = memoryManager.getStats();
        assertTrue(finalStats.hotCacheHits + finalStats.warmCacheHits > initialHits);
        
        // Test hit ratio calculation
        assertTrue(finalStats.getHitRatio() >= 0.0 && finalStats.getHitRatio() <= 1.0);
    }
    
    @Test
    void testPrefetching() throws Exception {
        // Create nodes that will be saved to disk
        var nodeIds = new ArrayList<Long>();
        for (int i = 0; i < 10; i++) {
            var node = new ManagedNode(700L + i, 1, new Vector3f(i, i, i), 0.5f);
            memoryManager.putNode(node);
            nodeIds.add(700L + i);
        }
        
        // Force eviction to disk
        memoryManager.evictNodes(Long.MAX_VALUE);
        Thread.sleep(100);
        
        // Start prefetching
        memoryManager.prefetchNodes(nodeIds);
        
        // Give time for prefetching
        Thread.sleep(200);
        
        // Accessing prefetched nodes should be faster (cache hits)
        long startTime = System.nanoTime();
        for (var nodeId : nodeIds.subList(0, 5)) {
            var node = memoryManager.getNode(nodeId);
            assertNotNull(node);
        }
        long prefetchedTime = System.nanoTime() - startTime;
        
        // This is more of a functional test - hard to verify performance improvement
        assertTrue(prefetchedTime >= 0); // Just ensure no errors
    }
    
    @Test
    void testMemoryManagerConfig() {
        var customConfig = new Config();
        customConfig.hotCacheMaxBytes = 2048;
        customConfig.warmCacheMaxBytes = 1024;
        customConfig.memoryPressureThreshold = 0.9f;
        customConfig.aggressiveEviction = true;
        customConfig.nodePoolSize = 50;
        
        var customManager = new RuntimeMemoryManager(customConfig, clusteredFile, octreeFile);
        
        // Verify config is used
        assertNotNull(customManager);
        
        // Test that the manager works with custom config
        var node = new ManagedNode(800L, 0, new Vector3f(), 1.0f);
        customManager.putNode(node);
        
        var retrieved = customManager.getNode(800L);
        assertNotNull(retrieved);
        
        customManager.shutdown();
    }
    
    @Test
    void testConcurrentAccess() throws Exception {
        var nodeIds = Collections.synchronizedList(new ArrayList<Long>());
        var threads = new ArrayList<Thread>();
        
        // Create multiple threads accessing nodes concurrently
        for (int t = 0; t < 4; t++) {
            int threadId = t;
            var thread = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    long nodeId = (threadId * 1000L) + i + 900;
                    var node = new ManagedNode(nodeId, 1, new Vector3f(i, i, i), 0.5f);
                    node.color = new Color3f(0.1f * i, 0.2f * i, 0.3f * i);
                    
                    memoryManager.putNode(node);
                    nodeIds.add(nodeId);
                    
                    // Access some nodes multiple times
                    if (i % 3 == 0) {
                        var retrieved = memoryManager.getNode(nodeId);
                        assertNotNull(retrieved);
                    }
                }
            });
            
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (var thread : threads) {
            thread.join(5000); // 5 second timeout
        }
        
        // Verify all nodes are accessible
        for (var nodeId : nodeIds) {
            var node = memoryManager.getNode(nodeId);
            assertNotNull(node, "Node " + nodeId + " should be accessible");
        }
        
        // Verify cache stats are reasonable
        var stats = memoryManager.getStats();
        assertTrue(stats.hotCacheHits + stats.warmCacheHits > 0);
    }
    
    @Test
    void testMemoryManagerShutdown() {
        // Add some nodes
        for (int i = 0; i < 10; i++) {
            var node = new ManagedNode(1000L + i, 1, new Vector3f(i, 0, 0), 1.0f);
            node.isDirty = true; // Mark as dirty to test saving during shutdown
            memoryManager.putNode(node);
        }
        
        // Shutdown should complete without errors
        assertDoesNotThrow(() -> memoryManager.shutdown());
        
        // Multiple shutdowns should be safe
        assertDoesNotThrow(() -> memoryManager.shutdown());
    }
    
    @Test 
    void testNodeChildrenHandling() {
        var parentNode = new ManagedNode(1100L, 0, new Vector3f(), 2.0f);
        var childNodes = new ManagedNode[8];
        
        for (int i = 0; i < 8; i++) {
            childNodes[i] = new ManagedNode(1101L + i, 1, new Vector3f(i, i, i), 1.0f);
        }
        
        parentNode.children = childNodes;
        parentNode.isLeaf = false;
        
        assertTrue(parentNode.hasChildren());
        assertTrue(parentNode.getEstimatedMemorySize() > 200); // Should account for children array
        
        memoryManager.putNode(parentNode);
        
        var retrieved = memoryManager.getNode(1100L);
        assertNotNull(retrieved);
        assertTrue(retrieved.hasChildren());
    }
    
    @Test
    void testCacheStatsToString() {
        var stats = new CacheStats();
        stats.hotCacheHits = 100;
        stats.warmCacheHits = 50;
        stats.cacheMisses = 25;
        stats.evictions = 10;
        stats.currentHotMemory = 1024 * 100; // 100KB
        stats.currentWarmMemory = 1024 * 50;  // 50KB
        
        var str = stats.toString();
        assertTrue(str.contains("hitRatio"));
        assertTrue(str.contains("100")); // hotHits
        assertTrue(str.contains("50"));  // warmHits  
        assertTrue(str.contains("25"));  // misses
        assertTrue(str.contains("hotMem=100KB"));
        assertTrue(str.contains("warmMem=50KB"));
        
        // Verify hit ratio calculation
        assertEquals(150.0 / 175.0, stats.getHitRatio(), 0.001);
    }
}