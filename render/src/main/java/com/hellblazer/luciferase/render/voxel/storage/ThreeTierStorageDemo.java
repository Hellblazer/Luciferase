package com.hellblazer.luciferase.render.voxel.storage;

import javax.vecmath.Vector3f;
import javax.vecmath.Color3f;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.hellblazer.luciferase.render.voxel.storage.RuntimeMemoryManager.*;

/**
 * Demonstration of the three-tier storage architecture for ESVO voxel data.
 * 
 * Shows integration between:
 * - ClusteredFile: Efficient disk storage with 64KB clusters
 * - OctreeFile: Hierarchical octree metadata and structure
 * - RuntimeMemoryManager: In-memory caching with LRU eviction
 * 
 * This demo creates a sample octree, stores it using the three-tier system,
 * and demonstrates various access patterns and memory management features.
 */
public class ThreeTierStorageDemo {
    
    private static final int DEMO_OCTREE_DEPTH = 4;
    private static final int NODES_PER_LEVEL = 8;
    
    public static void main(String[] args) {
        try {
            runDemo();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void runDemo() throws Exception {
        System.out.println("=== ESVO Three-Tier Storage Architecture Demo ===\n");
        
        // Setup storage files
        Path clusteredPath = Paths.get("demo_clustered_storage.dat");
        Path octreePath = Paths.get("demo_octree_structure.dat");
        
        try (var clusteredFile = ClusteredFile.create(clusteredPath, ClusteredFile.CompressionType.NONE);
             var octreeFile = OctreeFile.create(octreePath.toFile())) {
            
            // Configure runtime memory manager
            var config = new Config();
            config.hotCacheMaxBytes = 256 * 1024;  // 256KB hot cache
            config.warmCacheMaxBytes = 128 * 1024; // 128KB warm cache
            config.memoryPressureThreshold = 0.8f;
            config.prefetchThreads = 2;
            config.aggressiveEviction = true;
            
            var memoryManager = new RuntimeMemoryManager(config, clusteredFile, octreeFile);
            
            System.out.println("1. Creating sample octree structure...");
            createSampleOctree(memoryManager);
            
            System.out.println("\n2. Demonstrating cache behavior...");
            demonstrateCacheBehavior(memoryManager);
            
            System.out.println("\n3. Testing memory pressure and eviction...");
            testMemoryPressure(memoryManager);
            
            System.out.println("\n4. Demonstrating prefetching...");
            demonstratePrefetching(memoryManager);
            
            System.out.println("\n5. Testing concurrent access...");
            testConcurrentAccess(memoryManager);
            
            System.out.println("\n6. Final statistics:");
            printFinalStats(memoryManager, clusteredFile, octreeFile);
            
            memoryManager.shutdown();
        }
        
        // Cleanup demo files
        clusteredPath.toFile().delete();
        octreePath.toFile().delete();
        
        System.out.println("\n=== Demo completed successfully ===");
    }
    
    private static void createSampleOctree(RuntimeMemoryManager memoryManager) {
        int nodeCount = 0;
        
        // Create octree nodes at different levels
        for (int depth = 0; depth <= DEMO_OCTREE_DEPTH; depth++) {
            int nodesAtLevel = (int) Math.pow(NODES_PER_LEVEL, depth);
            
            for (int i = 0; i < Math.min(nodesAtLevel, 50); i++) { // Limit for demo
                long nodeId = (depth * 1000L) + i + 1;
                
                float size = 1.0f / (1 << depth); // Size decreases with depth
                var position = new Vector3f(
                    (i % 4) * size,
                    ((i / 4) % 4) * size,
                    ((i / 16) % 4) * size
                );
                
                var node = new ManagedNode(nodeId, depth, position, size);
                
                // Add realistic voxel data
                node.color = new Color3f(
                    (float) Math.random(),
                    (float) Math.random(),
                    (float) Math.random()
                );
                
                node.normal = new Vector3f(
                    (float) Math.random() - 0.5f,
                    (float) Math.random() - 0.5f,
                    (float) Math.random() - 0.5f
                );
                node.normal.normalize();
                
                node.isLeaf = (depth == DEMO_OCTREE_DEPTH);
                
                // Create children for non-leaf nodes
                if (!node.isLeaf && Math.random() > 0.3) { // 70% chance of having children
                    node.children = new ManagedNode[8];
                    // Children will be created in subsequent levels
                }
                
                memoryManager.putNode(node);
                nodeCount++;
            }
            
            System.out.printf("  Created %d nodes at depth %d\n", 
                Math.min((int) Math.pow(NODES_PER_LEVEL, depth), 50), depth);
        }
        
        System.out.printf("Total nodes created: %d\n", nodeCount);
    }
    
    private static void demonstrateCacheBehavior(RuntimeMemoryManager memoryManager) {
        var stats = memoryManager.getStats();
        System.out.printf("Initial cache state: %s\n", stats);
        
        // Access some nodes multiple times (should promote to hot cache)
        System.out.println("Accessing nodes repeatedly to trigger promotion...");
        
        long[] frequentNodes = {1001, 1002, 1003, 2001, 2002};
        for (int round = 0; round < 5; round++) {
            for (long nodeId : frequentNodes) {
                var node = memoryManager.getNode(nodeId);
                if (node != null) {
                    System.out.printf("  Round %d: Node %d access count = %d, cache level = %s\n",
                        round + 1, nodeId, node.accessCount, node.cacheLevel);
                }
            }
        }
        
        stats = memoryManager.getStats();
        System.out.printf("After frequent access: %s\n", stats);
    }
    
    private static void testMemoryPressure(RuntimeMemoryManager memoryManager) {
        var initialStats = memoryManager.getStats();
        System.out.printf("Before memory pressure: %s\n", initialStats);
        
        // Create many large nodes to trigger memory pressure
        System.out.println("Creating large nodes to trigger eviction...");
        
        for (int i = 0; i < 200; i++) {
            long nodeId = 5000L + i;
            var node = new ManagedNode(nodeId, 2, new Vector3f(i, i, i), 0.25f);
            
            // Make nodes "larger" by giving them children
            node.children = new ManagedNode[8];
            for (int j = 0; j < 8; j++) {
                node.children[j] = new ManagedNode(nodeId * 10 + j, 3, 
                    new Vector3f(i + j, i + j, i + j), 0.125f);
            }
            
            node.accessCount = 10; // Try to force to hot cache
            memoryManager.putNode(node);
            
            if (i % 50 == 0) {
                var currentStats = memoryManager.getStats();
                System.out.printf("  After %d nodes: evictions = %d, hot mem = %dKB\n", 
                    i + 1, currentStats.evictions, currentStats.currentHotMemory / 1024);
            }
        }
        
        var finalStats = memoryManager.getStats();
        System.out.printf("After memory pressure: %s\n", finalStats);
        System.out.printf("Total evictions: %d\n", finalStats.evictions - initialStats.evictions);
    }
    
    private static void demonstratePrefetching(RuntimeMemoryManager memoryManager) {
        // First, force some nodes to disk by creating memory pressure
        memoryManager.evictNodes(Long.MAX_VALUE); // Force all to disk
        
        try {
            Thread.sleep(100); // Let saves complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        var stats = memoryManager.getStats();
        System.out.printf("After forced eviction: %s\n", stats);
        
        // Prepare nodes for prefetching
        var prefetchIds = Arrays.asList(1001L, 1002L, 1003L, 2001L, 2002L, 2003L);
        
        System.out.println("Starting prefetch of likely-needed nodes...");
        memoryManager.prefetchNodes(prefetchIds);
        
        try {
            Thread.sleep(200); // Let prefetch complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Access prefetched nodes - should be cache hits
        System.out.println("Accessing prefetched nodes:");
        long startTime = System.nanoTime();
        
        for (var nodeId : prefetchIds) {
            var node = memoryManager.getNode(nodeId);
            if (node != null) {
                System.out.printf("  Node %d: cache level = %s\n", nodeId, node.cacheLevel);
            }
        }
        
        long accessTime = System.nanoTime() - startTime;
        System.out.printf("Access time for prefetched nodes: %.2f ms\n", accessTime / 1_000_000.0);
        
        var finalStats = memoryManager.getStats();
        System.out.printf("After prefetch test: %s\n", finalStats);
    }
    
    private static void testConcurrentAccess(RuntimeMemoryManager memoryManager) {
        var threads = new ArrayList<Thread>();
        var accessCounts = Collections.synchronizedMap(new HashMap<Long, Integer>());
        
        // Create threads that access nodes concurrently
        for (int t = 0; t < 4; t++) {
            int threadId = t;
            var thread = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    long nodeId = 1001 + (i % 10); // Access existing nodes
                    
                    var node = memoryManager.getNode(nodeId);
                    if (node != null) {
                        accessCounts.merge(nodeId, 1, Integer::sum);
                        
                        // Occasionally create new nodes
                        if (i % 10 == 0) {
                            long newNodeId = (threadId * 1000L) + i + 6000;
                            var newNode = new ManagedNode(newNodeId, 1, 
                                new Vector3f(i, threadId, i), 0.5f);
                            memoryManager.putNode(newNode);
                        }
                    }
                    
                    try {
                        Thread.sleep(1); // Small delay
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "AccessThread-" + threadId);
            
            threads.add(thread);
            thread.start();
        }
        
        // Wait for completion
        for (var thread : threads) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        System.out.println("Concurrent access results:");
        accessCounts.entrySet().stream()
            .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(entry -> System.out.printf("  Node %d accessed %d times\n", 
                entry.getKey(), entry.getValue()));
        
        var stats = memoryManager.getStats();
        System.out.printf("After concurrent test: %s\n", stats);
    }
    
    private static void printFinalStats(RuntimeMemoryManager memoryManager, 
                                       ClusteredFile clusteredFile, 
                                       OctreeFile octreeFile) {
        var memStats = memoryManager.getStats();
        System.out.printf("Memory Manager: %s\n", memStats);
        
        // TODO: Add statistics support to ClusteredFile
        // var clusteredStats = clusteredFile.getStats();
        // System.out.printf("Clustered File: %s\n", clusteredStats);
        
        var octreeStats = octreeFile.getStats();
        System.out.printf("Octree File: %s\n", octreeStats);
        
        // Calculate some derived metrics
        double totalAccesses = memStats.hotCacheHits + memStats.warmCacheHits + memStats.cacheMisses;
        double hitRatio = totalAccesses > 0 ? 
            (memStats.hotCacheHits + memStats.warmCacheHits) / totalAccesses * 100 : 0;
        
        System.out.printf("\nSummary Metrics:\n");
        System.out.printf("  Cache Hit Ratio: %.1f%%\n", hitRatio);
        System.out.printf("  Total Memory Usage: %dKB\n", 
            (memStats.currentHotMemory + memStats.currentWarmMemory) / 1024);
        System.out.printf("  Eviction Rate: %.1f%%\n", 
            totalAccesses > 0 ? memStats.evictions / totalAccesses * 100 : 0);
        System.out.printf("  Disk I/O Operations: %d reads, %d writes\n", 
            memStats.nodesLoaded, memStats.nodesSaved);
    }
}