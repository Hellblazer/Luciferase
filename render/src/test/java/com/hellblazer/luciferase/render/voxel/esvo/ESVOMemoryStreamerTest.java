/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.lang.foreign.Arena;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ESVOMemoryStreamer with LZ4 compression and caching.
 * Tests streaming performance, compression efficiency, and cache behavior.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ESVOMemoryStreamerTest {
    private static final Logger log = LoggerFactory.getLogger(ESVOMemoryStreamerTest.class);
    
    private Arena arena;
    private ESVOMemoryStreamer streamer;
    private ESVOMemoryStreamer.StreamingConfig config;
    
    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
        config = ESVOMemoryStreamer.StreamingConfig.defaultConfig()
            .withMaxPages(64)
            .withPrefetching(true);
        streamer = new ESVOMemoryStreamer(config, arena);
    }
    
    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Memory streamer initialization")
    void testStreamerInitialization() {
        log.info("Testing memory streamer initialization...");
        
        assertNotNull(streamer);
        
        var stats = streamer.getStats();
        assertEquals(0, stats.cacheHits);
        assertEquals(0, stats.cacheMisses);
        assertEquals(0, stats.bytesRead);
        assertEquals(0, stats.bytesWritten);
        assertEquals(0, stats.compressionSavings);
        assertEquals(0, stats.cachedPages);
        assertEquals(0, stats.prefetchQueueSize);
        assertEquals(0.0, stats.getHitRate(), 0.001);
        
        log.info("Streamer initialization test passed. Initial stats: {}", stats);
    }
    
    @Test
    @Order(2)
    @DisplayName("Page loading and caching")
    void testPageLoadingAndCaching() {
        log.info("Testing page loading and caching...");
        
        // Create test page data
        var testPage = new ESVOPage(arena);
        for (int i = 0; i < 10; i++) {
            int offset = testPage.allocateNode();
            var node = new ESVONode();
            node.setValidMask((byte) (i * 17));
            node.setChildPointer(i * 100, i % 2 == 0);
            testPage.writeNode(offset, node);
        }
        
        byte[] pageData = testPage.serialize();
        int pageId = 42;
        
        // First load - should be cache miss
        var loadedPage1 = streamer.loadPage(pageId, pageData);
        assertNotNull(loadedPage1);
        
        var stats1 = streamer.getStats();
        assertEquals(0, stats1.cacheHits);
        assertEquals(1, stats1.cacheMisses);
        assertEquals(1, stats1.cachedPages);
        assertTrue(stats1.bytesRead > 0);
        
        // Second load - should be cache hit
        var loadedPage2 = streamer.loadPage(pageId, pageData);
        assertNotNull(loadedPage2);
        
        var stats2 = streamer.getStats();
        assertEquals(1, stats2.cacheHits);
        assertEquals(1, stats2.cacheMisses);
        assertEquals(1, stats2.cachedPages);
        assertEquals(0.5, stats2.getHitRate(), 0.001);
        
        // Verify loaded page content
        assertEquals(testPage.getNodeCount(), loadedPage1.getNodeCount());
        assertEquals(testPage.getFarPointerCount(), loadedPage1.getFarPointerCount());
        
        log.info("Page loading test passed. Stats: {}", stats2);
    }
    
    @Test
    @Order(3)
    @DisplayName("LZ4 compression and decompression")
    void testCompressionEfficiency() {
        log.info("Testing LZ4 compression efficiency...");
        
        // Create compressible test data
        var largePage = new ESVOPage(arena);
        
        // Fill with repetitive data that should compress well
        for (int i = 0; i < 100; i++) {
            int offset = largePage.allocateNode();
            if (offset < 0) break; // Page full
            
            var node = new ESVONode();
            node.setValidMask((byte) 0xFF); // Repetitive pattern
            node.setNonLeafMask((byte) 0xAA);
            node.setChildPointer(1000, false); // Same value
            node.setContourMask((byte) 0x55);
            node.setContourPointer(2000); // Same value
            largePage.writeNode(offset, node);
        }
        
        // Add some far pointers (repetitive)
        for (int i = 0; i < 10; i++) {
            largePage.addFarPointer(0x12345); // Same value
        }
        
        // Add attachment data (repetitive)
        byte[] attachment = new byte[1000];
        Arrays.fill(attachment, (byte) 0x42);
        largePage.addAttachment(attachment);
        
        byte[] pageData = largePage.serialize();
        int pageId = 100;
        
        // Use config that forces compression
        var compressConfig = ESVOMemoryStreamer.StreamingConfig.defaultConfig()
            .withMaxPages(64)
            .withPrefetching(false);
        compressConfig.compressionThreshold = 1024; // Force compression for large pages
        
        var compressStreamer = new ESVOMemoryStreamer(compressConfig, arena);
        
        // Load page (should trigger compression)
        var loadedPage = compressStreamer.loadPage(pageId, pageData);
        assertNotNull(loadedPage);
        
        var stats = compressStreamer.getStats();
        assertTrue(stats.compressionSavings > 0, "Should achieve compression savings");
        assertTrue(stats.getCompressionRatio() > 0.1, "Should achieve at least 10% compression");
        
        // Verify data integrity after compression/decompression
        assertEquals(largePage.getNodeCount(), loadedPage.getNodeCount());
        assertEquals(largePage.getFarPointerCount(), loadedPage.getFarPointerCount());
        assertEquals(largePage.getAttachmentSize(), loadedPage.getAttachmentSize());
        
        log.info("Compression test passed. Compression ratio: {:.1f}%, Savings: {}KB", 
                 stats.getCompressionRatio() * 100, stats.compressionSavings / 1024);
    }
    
    @Test
    @Order(4)
    @DisplayName("Cache eviction with LRU policy")
    void testCacheEviction() {
        log.info("Testing cache eviction with LRU policy...");
        
        // Use small cache for testing eviction
        var smallConfig = ESVOMemoryStreamer.StreamingConfig.defaultConfig()
            .withMaxPages(3)
            .withPrefetching(false);
        
        var evictionStreamer = new ESVOMemoryStreamer(smallConfig, arena);
        
        // Create test pages
        List<byte[]> testPages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var page = new ESVOPage(arena);
            var node = new ESVONode();
            node.setValidMask((byte) i);
            page.writeNode(page.allocateNode(), node);
            testPages.add(page.serialize());
        }
        
        // Load pages 0, 1, 2 - should all fit in cache
        for (int i = 0; i < 3; i++) {
            evictionStreamer.loadPage(i, testPages.get(i));
        }
        
        var stats1 = evictionStreamer.getStats();
        assertEquals(3, stats1.cachedPages);
        assertEquals(0, stats1.cacheHits);
        assertEquals(3, stats1.cacheMisses);
        
        // Access page 1 to make it more recently used
        evictionStreamer.loadPage(1, testPages.get(1));
        
        var stats2 = evictionStreamer.getStats();
        assertEquals(1, stats2.cacheHits);
        assertEquals(3, stats2.cacheMisses);
        
        // Load page 3 - should evict page 0 (least recently used)
        evictionStreamer.loadPage(3, testPages.get(3));
        
        var stats3 = evictionStreamer.getStats();
        assertEquals(3, stats3.cachedPages); // Still 3 pages
        assertEquals(1, stats3.cacheHits);
        assertEquals(4, stats3.cacheMisses);
        
        // Access page 0 again - should be cache miss (evicted)
        evictionStreamer.loadPage(0, testPages.get(0));
        
        var stats4 = evictionStreamer.getStats();
        assertEquals(1, stats4.cacheHits); // No additional hit
        assertEquals(5, stats4.cacheMisses); // Additional miss
        
        // Access page 1 again - should be cache hit (not evicted)
        evictionStreamer.loadPage(1, testPages.get(1));
        
        var stats5 = evictionStreamer.getStats();
        assertEquals(2, stats5.cacheHits); // Additional hit
        assertEquals(5, stats5.cacheMisses); // No additional miss
        
        log.info("Cache eviction test passed. Final stats: {}", stats5);
    }
    
    @Test
    @Order(5)
    @DisplayName("Beam-based prefetching")
    void testBeamPrefetching() {
        log.info("Testing beam-based prefetching...");
        
        var prefetchConfig = ESVOMemoryStreamer.StreamingConfig.defaultConfig()
            .withMaxPages(100)
            .withPrefetching(true);
        prefetchConfig.maxPrefetchQueue = 50;
        
        var prefetchStreamer = new ESVOMemoryStreamer(prefetchConfig, arena);
        
        // Create a test beam
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var origin = new Vector3f(i * 0.1f, 0, 0);
            var direction = new Vector3f(0, 0, 1);
            rays.add(new BeamOptimizer.Ray(origin, direction, i, 0, i));
        }
        
        var beam = new BeamOptimizer.Beam();
        for (var ray : rays) {
            beam.addRay(ray);
        }
        
        // Trigger prefetching
        prefetchStreamer.prefetchForBeam(beam);
        
        var stats = prefetchStreamer.getStats();
        assertTrue(stats.prefetchQueueSize > 0, "Should have prefetch requests queued");
        
        log.info("Prefetching test passed. Prefetch queue size: {}", stats.prefetchQueueSize);
    }
    
    @Test
    @Order(6)
    @DisplayName("Adaptive cache sizing")
    void testAdaptiveCacheSizing() {
        log.info("Testing adaptive cache sizing...");
        
        var adaptiveConfig = ESVOMemoryStreamer.StreamingConfig.defaultConfig()
            .withMaxPages(256)
            .withPrefetching(false);
        adaptiveConfig.adaptiveCacheSize = true;
        adaptiveConfig.minHitRate = 0.8;
        
        var adaptiveStreamer = new ESVOMemoryStreamer(adaptiveConfig, arena);
        
        // Create test data
        List<byte[]> testPages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var page = new ESVOPage(arena);
            var node = new ESVONode();
            node.setValidMask((byte) i);
            page.writeNode(page.allocateNode(), node);
            testPages.add(page.serialize());
        }
        
        // Load pages with poor hit rate (all misses)
        for (int i = 0; i < 10; i++) {
            adaptiveStreamer.loadPage(i, testPages.get(i));
        }
        
        int originalCacheSize = adaptiveConfig.maxCachedPages;
        double lowHitRate = adaptiveStreamer.getStats().getHitRate();
        assertTrue(lowHitRate < 0.5, "Should have low hit rate initially");
        
        // Trigger adaptation
        adaptiveStreamer.adaptCacheSize();
        
        // Should increase cache size due to low hit rate
        assertTrue(adaptiveConfig.maxCachedPages >= originalCacheSize, 
                  "Should maintain or increase cache size for low hit rate");
        
        // Now create high hit rate pattern
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 5; i++) {
                adaptiveStreamer.loadPage(i, testPages.get(i)); // Repeat access
            }
        }
        
        double highHitRate = adaptiveStreamer.getStats().getHitRate();
        assertTrue(highHitRate > 0.7, "Should achieve high hit rate with repeated access");
        
        log.info("Adaptive sizing test passed. Hit rates: low={:.2f}, high={:.2f}", 
                 lowHitRate, highHitRate);
    }
    
    @Test
    @Order(7)
    @DisplayName("Memory bandwidth monitoring")
    void testBandwidthMonitoring() {
        log.info("Testing memory bandwidth monitoring...");
        
        var monitorConfig = ESVOMemoryStreamer.StreamingConfig.defaultConfig()
            .withMaxPages(32)
            .withBandwidthBudget(100.0); // 100 MB/s budget
        
        var monitorStreamer = new ESVOMemoryStreamer(monitorConfig, arena);
        
        // Create and load multiple pages to generate bandwidth usage
        List<byte[]> testPages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            var page = new ESVOPage(arena);
            
            // Add nodes
            for (int j = 0; j < 50; j++) {
                int offset = page.allocateNode();
                if (offset < 0) break;
                
                var node = new ESVONode();
                node.setValidMask((byte) j);
                node.setChildPointer(j * 100, false);
                page.writeNode(offset, node);
            }
            
            testPages.add(page.serialize());
        }
        
        // Load all pages
        for (int i = 0; i < testPages.size(); i++) {
            monitorStreamer.loadPage(i, testPages.get(i));
        }
        
        // Store some pages
        for (int i = 0; i < 5; i++) {
            var page = new ESVOPage(arena);
            var node = new ESVONode();
            node.setValidMask((byte) i);
            page.writeNode(page.allocateNode(), node);
            
            monitorStreamer.storePage(i + 1000, page);
        }
        
        var stats = monitorStreamer.getStats();
        assertTrue(stats.bytesRead > 0, "Should have read bytes");
        assertTrue(stats.bytesWritten > 0, "Should have written bytes");
        assertTrue(stats.getBandwidthMBps() > 0, "Should show bandwidth usage");
        
        log.info("Bandwidth monitoring test passed. Stats: {}", stats);
    }
    
    @Test
    @Order(8)
    @DisplayName("Streaming performance under load")
    void testStreamingPerformanceUnderLoad() {
        log.info("Testing streaming performance under load...");
        
        var performanceConfig = ESVOMemoryStreamer.StreamingConfig.defaultConfig()
            .withMaxPages(128)
            .withPrefetching(true);
        
        var performanceStreamer = new ESVOMemoryStreamer(performanceConfig, arena);
        
        int numPages = 200;
        int numRounds = 5;
        
        // Prepare test data
        List<byte[]> testPages = new ArrayList<>();
        Random random = new Random(42); // Deterministic
        
        for (int i = 0; i < numPages; i++) {
            var page = new ESVOPage(arena);
            
            // Random number of nodes
            int nodeCount = 10 + random.nextInt(90);
            for (int j = 0; j < nodeCount; j++) {
                int offset = page.allocateNode();
                if (offset < 0) break;
                
                var node = new ESVONode();
                node.setValidMask((byte) random.nextInt(256));
                node.setNonLeafMask((byte) random.nextInt(256));
                node.setChildPointer(random.nextInt(10000), random.nextBoolean());
                page.writeNode(offset, node);
            }
            
            // Random far pointers
            for (int j = 0; j < random.nextInt(5); j++) {
                page.addFarPointer(random.nextInt(0x100000));
            }
            
            testPages.add(page.serialize());
        }
        
        long startTime = System.nanoTime();
        
        // Simulate streaming workload
        for (int round = 0; round < numRounds; round++) {
            // Sequential access
            for (int i = 0; i < numPages / 2; i++) {
                performanceStreamer.loadPage(i, testPages.get(i));
            }
            
            // Random access
            for (int i = 0; i < numPages / 4; i++) {
                int pageId = random.nextInt(numPages);
                performanceStreamer.loadPage(pageId, testPages.get(pageId));
            }
            
            // Store some pages
            for (int i = 0; i < 10; i++) {
                var page = new ESVOPage(arena);
                var node = new ESVONode();
                node.setValidMask((byte) i);
                page.writeNode(page.allocateNode(), node);
                
                performanceStreamer.storePage(numPages + round * 10 + i, page);
            }
        }
        
        long endTime = System.nanoTime();
        double durationMs = (endTime - startTime) / 1_000_000.0;
        
        var finalStats = performanceStreamer.getStats();
        
        // Performance validation
        assertTrue(durationMs < 1000, "Should complete within 1 second");
        assertTrue(finalStats.getHitRate() > 0.3, "Should achieve reasonable hit rate");
        
        double operationsPerMs = (finalStats.cacheHits + finalStats.cacheMisses) / durationMs;
        
        log.info("Performance test completed in {:.2f}ms. Operations/ms: {:.1f}, Final stats: {}", 
                 durationMs, operationsPerMs, finalStats);
        
        // Verify data integrity
        assertTrue(finalStats.cachedPages <= performanceConfig.maxCachedPages, 
                  "Should not exceed cache limit");
        assertTrue(finalStats.prefetchQueueSize <= performanceConfig.maxPrefetchQueue, 
                  "Should not exceed prefetch limit");
    }
    
    @Test
    @Order(9)
    @DisplayName("Streamer reset and cleanup")
    void testStreamerResetAndCleanup() {
        log.info("Testing streamer reset and cleanup...");
        
        // Load some test data
        var page = new ESVOPage(arena);
        var node = new ESVONode();
        node.setValidMask((byte) 0xFF);
        page.writeNode(page.allocateNode(), node);
        
        byte[] pageData = page.serialize();
        
        streamer.loadPage(1, pageData);
        streamer.loadPage(2, pageData);
        streamer.storePage(3, page);
        
        var beforeReset = streamer.getStats();
        assertTrue(beforeReset.cachedPages > 0);
        assertTrue(beforeReset.bytesRead > 0);
        assertTrue(beforeReset.bytesWritten > 0);
        
        // Reset streamer
        streamer.reset();
        
        var afterReset = streamer.getStats();
        assertEquals(0, afterReset.cacheHits);
        assertEquals(0, afterReset.cacheMisses);
        assertEquals(0, afterReset.bytesRead);
        assertEquals(0, afterReset.bytesWritten);
        assertEquals(0, afterReset.compressionSavings);
        assertEquals(0, afterReset.cachedPages);
        assertEquals(0, afterReset.prefetchQueueSize);
        
        log.info("Reset test passed. Before: {}, After: {}", beforeReset, afterReset);
    }
}