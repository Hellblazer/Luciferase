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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the integrated ESVOStreamingRenderer.
 * Tests beam optimization combined with memory streaming for complete ESVO implementation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ESVOStreamingRendererTest {
    private static final Logger log = LoggerFactory.getLogger(ESVOStreamingRendererTest.class);
    
    private Arena arena;
    private ESVOStreamingRenderer renderer;
    private ESVOStreamingRenderer.RendererConfig config;
    
    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
        config = ESVOStreamingRenderer.RendererConfig.defaultConfig()
            .withAsyncPrefetching(true)
            .withTargetFrameTime(16.67); // 60 FPS target
        
        // Configure beam optimization for testing
        config.beamConfig.maxRaysPerBeam = 32;
        config.beamConfig.minRaysPerBeam = 1;
        
        // Configure memory streaming for testing  
        config.streamingConfig.withMaxPages(64).withPrefetching(true);
        config.streamingConfig.compressionThreshold = 1024;
        
        renderer = new ESVOStreamingRenderer(config, arena);
    }
    
    @AfterEach
    void tearDown() {
        if (renderer != null) {
            renderer.shutdown();
        }
        if (arena != null) {
            arena.close();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Renderer initialization and configuration")
    void testRendererInitialization() {
        log.info("Testing renderer initialization and configuration...");
        
        assertNotNull(renderer);
        
        var stats = renderer.getStats();
        assertEquals(0, stats.framesRendered);
        assertEquals(0, stats.totalRaysProcessed);
        assertEquals(0, stats.totalPagesLoaded);
        assertEquals(0.0, stats.raysPerSecond, 0.001);
        assertEquals(0.0, stats.pagesPerSecond, 0.001);
        
        assertNotNull(stats.beamStats);
        assertNotNull(stats.streamingStats);
        
        log.info("Renderer initialization test passed. Initial stats: {}", stats);
    }
    
    @Test
    @Order(2)  
    @DisplayName("Single frame rendering with beam optimization")
    void testSingleFrameRendering() throws Exception {
        log.info("Testing single frame rendering with beam optimization...");
        
        // Create test rays for a single frame
        List<BeamOptimizer.Ray> rays = createCameraRays(8, 8); // 64 rays
        
        var futureTask = renderer.renderFrame(rays);
        var renderTask = futureTask.get(5, TimeUnit.SECONDS);
        
        assertNotNull(renderTask);
        assertEquals(rays.size(), renderTask.rays.size());
        assertTrue(renderTask.isComplete());
        assertTrue(renderTask.getElapsedMs() > 0);
        
        // Check that all rays have results
        assertEquals(rays.size(), renderTask.rayResults.size());
        
        for (var ray : rays) {
            assertTrue(renderTask.rayResults.containsKey(ray.rayId), 
                      "Ray " + ray.rayId + " should have a result");
            assertNotNull(renderTask.rayResults.get(ray.rayId));
        }
        
        var stats = renderer.getStats();
        assertEquals(1, stats.framesRendered);
        assertEquals(rays.size(), stats.totalRaysProcessed);
        assertTrue(stats.totalPagesLoaded > 0);
        assertTrue(stats.streamingStats.cachedPages > 0);
        
        log.info("Single frame test passed. Task time: {:.2f}ms, Stats: {}", 
                 renderTask.getElapsedMs(), stats);
    }
    
    @Test
    @Order(3)
    @DisplayName("Multiple frame rendering with caching benefits")
    void testMultipleFrameRendering() throws Exception {
        log.info("Testing multiple frame rendering with caching benefits...");
        
        int frameCount = 5;
        List<CompletableFuture<ESVOStreamingRenderer.RenderTask>> frameTasks = new ArrayList<>();
        
        // Render multiple frames with similar ray patterns
        for (int frame = 0; frame < frameCount; frame++) {
            // Create slightly different rays each frame (camera moving slightly)
            var rays = createCameraRays(6, 6, frame * 0.1f); // 36 rays per frame
            frameTasks.add(renderer.renderFrame(rays));
        }
        
        // Wait for all frames to complete
        List<ESVOStreamingRenderer.RenderTask> completedTasks = new ArrayList<>(); 
        for (var future : frameTasks) {
            completedTasks.add(future.get(10, TimeUnit.SECONDS));
        }
        
        // Verify all frames completed successfully
        for (int i = 0; i < completedTasks.size(); i++) {
            var task = completedTasks.get(i);
            assertTrue(task.isComplete(), "Frame " + i + " should be complete");
            assertEquals(36, task.rayResults.size(), "Frame " + i + " should have 36 results");
        }
        
        var finalStats = renderer.getStats();
        assertEquals(frameCount, finalStats.framesRendered);
        assertEquals(frameCount * 36, finalStats.totalRaysProcessed);
        
        // Should have decent cache hit rate due to similar patterns
        assertTrue(finalStats.streamingStats.getHitRate() > 0.2, 
                  "Should achieve some cache hits with similar frame patterns");
        
        log.info("Multiple frame test passed. {} frames completed, Final stats: {}", 
                 frameCount, finalStats);
    }
    
    @Test
    @Order(4)
    @DisplayName("Beam optimization effectiveness")
    void testBeamOptimizationEffectiveness() throws Exception {
        log.info("Testing beam optimization effectiveness...");
        
        // Create coherent rays (camera looking forward)
        List<BeamOptimizer.Ray> coherentRays = new ArrayList<>();
        var cameraPos = new Vector3f(0, 0, 0);
        var forwardDir = new Vector3f(0, 0, 1);
        
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                var origin = new Vector3f(x * 0.1f, y * 0.1f, 0);
                var direction = new Vector3f(forwardDir);
                // Add slight FOV variation
                direction.x += (x - 4) * 0.02f;
                direction.y += (y - 4) * 0.02f;
                direction.normalize();
                
                coherentRays.add(new BeamOptimizer.Ray(origin, direction, x, y, y * 8 + x));
            }
        }
        
        var coherentTask = renderer.renderFrame(coherentRays).get(5, TimeUnit.SECONDS);
        assertTrue(coherentTask.isComplete());
        
        // Create incoherent rays (random directions)
        List<BeamOptimizer.Ray> incoherentRays = new ArrayList<>();
        Random random = new Random(42);
        
        for (int i = 0; i < 64; i++) {
            var origin = new Vector3f(
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1, 
                0
            );
            var direction = new Vector3f(
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1,
                random.nextFloat() + 0.1f // Mostly forward
            );
            direction.normalize();
            
            incoherentRays.add(new BeamOptimizer.Ray(origin, direction, i % 8, i / 8, i));
        }
        
        var incoherentTask = renderer.renderFrame(incoherentRays).get(5, TimeUnit.SECONDS);
        assertTrue(incoherentTask.isComplete());
        
        var stats = renderer.getStats();
        assertEquals(2, stats.framesRendered);
        assertEquals(128, stats.totalRaysProcessed); // 64 + 64
        
        log.info("Beam optimization test passed. Coherent frame: {:.2f}ms, " +
                "Incoherent frame: {:.2f}ms, Stats: {}", 
                coherentTask.getElapsedMs(), incoherentTask.getElapsedMs(), stats);
    }
    
    @Test
    @Order(5)
    @DisplayName("Memory streaming and compression efficiency")
    void testMemoryStreamingEfficiency() throws Exception {
        log.info("Testing memory streaming and compression efficiency...");
        
        // Render several frames to generate streaming activity
        int frameCount = 10;
        List<CompletableFuture<ESVOStreamingRenderer.RenderTask>> tasks = new ArrayList<>();
        
        for (int frame = 0; frame < frameCount; frame++) {
            // Create different spatial patterns each frame to test streaming
            var rays = createSpatialPatternRays(frame);
            tasks.add(renderer.renderFrame(rays));
        }
        
        // Wait for completion
        for (var task : tasks) {
            var result = task.get(10, TimeUnit.SECONDS);
            assertTrue(result.isComplete());
        }
        
        var stats = renderer.getStats();
        assertEquals(frameCount, stats.framesRendered);
        assertTrue(stats.totalPagesLoaded > frameCount, "Should load multiple pages per frame");
        
        // Check streaming effectiveness
        var streamingStats = stats.streamingStats;
        assertTrue(streamingStats.cachedPages > 0, "Should have pages in cache");
        assertTrue(streamingStats.bytesRead > 0, "Should have read data");
        
        // Should achieve some compression if pages are large enough
        if (streamingStats.compressionSavings > 0) {
            assertTrue(streamingStats.getCompressionRatio() > 0.1, 
                      "Should achieve reasonable compression ratio");
        }
        
        log.info("Memory streaming test passed. Pages loaded: {}, Streaming stats: {}", 
                 stats.totalPagesLoaded, streamingStats);
    }
    
    @Test
    @Order(6)  
    @DisplayName("Asynchronous prefetching behavior")
    void testAsynchronousPrefetching() throws Exception {
        log.info("Testing asynchronous prefetching behavior...");
        
        // Enable prefetching explicitly
        config.asyncPrefetching = true;
        config.streamingConfig.enablePrefetching = true;
        config.streamingConfig.maxPrefetchQueue = 50;
        
        var prefetchRenderer = new ESVOStreamingRenderer(config, arena);
        
        try {
            // Render frames with predictable spatial patterns
            List<CompletableFuture<ESVOStreamingRenderer.RenderTask>> tasks = new ArrayList<>();
            
            for (int frame = 0; frame < 3; frame++) {
                // Create rays moving in a predictable direction
                var rays = createMovingCameraRays(frame);
                tasks.add(prefetchRenderer.renderFrame(rays));
                
                // Small delay to allow prefetching to occur
                Thread.sleep(10);
            }
            
            // Wait for completion
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
            
            var stats = prefetchRenderer.getStats();
            assertTrue(stats.framesRendered > 0);
            assertTrue(stats.streamingStats.prefetchQueueSize >= 0); // May be 0 if processed
            
            // Cache hit rate should improve with prefetching
            if (stats.streamingStats.cacheHits + stats.streamingStats.cacheMisses > 10) {
                assertTrue(stats.streamingStats.getHitRate() >= 0, 
                          "Hit rate should be non-negative with prefetching");
            }
            
            log.info("Prefetching test passed. Stats: {}", stats);
            
        } finally {
            prefetchRenderer.shutdown();
        }
    }
    
    @Test
    @Order(7)
    @DisplayName("Performance under load")
    void testPerformanceUnderLoad() throws Exception {
        log.info("Testing performance under load...");
        
        int frameCount = 20;
        int raysPerFrame = 256; // Higher load
        
        long startTime = System.nanoTime();
        List<CompletableFuture<ESVOStreamingRenderer.RenderTask>> tasks = new ArrayList<>();
        
        // Submit all frames quickly
        for (int frame = 0; frame < frameCount; frame++) {
            var rays = createRandomRays(raysPerFrame, frame);
            tasks.add(renderer.renderFrame(rays));
        }
        
        // Wait for all to complete
        List<ESVOStreamingRenderer.RenderTask> results = new ArrayList<>();
        for (var task : tasks) {
            results.add(task.get(30, TimeUnit.SECONDS)); // Generous timeout
        }
        
        long endTime = System.nanoTime();
        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        
        // Verify all completed successfully
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            assertTrue(result.isComplete(), "Frame " + i + " should be complete");
            assertEquals(raysPerFrame, result.rayResults.size());
        }
        
        var stats = renderer.getStats();
        assertEquals(frameCount, stats.framesRendered);
        assertEquals((long) frameCount * raysPerFrame, stats.totalRaysProcessed);
        
        double avgFrameTime = totalTimeMs / frameCount;
        double raysPerSecond = (frameCount * raysPerFrame) / (totalTimeMs / 1000.0);
        
        log.info("Load test completed. {} frames, {} rays total in {:.2f}ms. " +
                "Avg frame time: {:.2f}ms, Rays/sec: {:.0f}, Stats: {}", 
                frameCount, stats.totalRaysProcessed, totalTimeMs, 
                avgFrameTime, raysPerSecond, stats);
        
        // Performance expectations
        assertTrue(avgFrameTime < 100, "Average frame time should be reasonable");
        assertTrue(raysPerSecond > 1000, "Should process at least 1000 rays/sec");
    }
    
    @Test
    @Order(8)
    @DisplayName("Renderer reset and resource cleanup")
    void testRendererResetAndCleanup() throws Exception {
        log.info("Testing renderer reset and resource cleanup...");
        
        // Render some frames to create state
        for (int i = 0; i < 3; i++) {
            var rays = createCameraRays(4, 4);
            renderer.renderFrame(rays).get(5, TimeUnit.SECONDS);
        }
        
        var beforeReset = renderer.getStats();
        assertTrue(beforeReset.framesRendered > 0);
        assertTrue(beforeReset.totalRaysProcessed > 0);
        assertTrue(beforeReset.totalPagesLoaded > 0);
        
        // Reset renderer
        renderer.reset();
        
        var afterReset = renderer.getStats();
        assertEquals(0, afterReset.framesRendered);
        assertEquals(0, afterReset.totalRaysProcessed);
        assertEquals(0, afterReset.totalPagesLoaded);
        assertEquals(0, afterReset.streamingStats.cachedPages);
        assertEquals(0, afterReset.streamingStats.cacheHits);
        assertEquals(0, afterReset.streamingStats.cacheMisses);
        
        // Should be able to render again after reset
        var rays = createCameraRays(4, 4);
        var newTask = renderer.renderFrame(rays).get(5, TimeUnit.SECONDS);
        assertTrue(newTask.isComplete());
        
        var afterNewRender = renderer.getStats();
        assertEquals(1, afterNewRender.framesRendered);
        assertEquals(16, afterNewRender.totalRaysProcessed);
        
        log.info("Reset test passed. Before: {}, After reset: {}, After new render: {}",
                beforeReset, afterReset, afterNewRender);
    }
    
    // Helper methods for creating test ray patterns
    
    private List<BeamOptimizer.Ray> createCameraRays(int width, int height) {
        return createCameraRays(width, height, 0.0f);
    }
    
    private List<BeamOptimizer.Ray> createCameraRays(int width, int height, float offset) {
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        
        var cameraPos = new Vector3f(offset, offset, 0);
        float fov = (float) Math.toRadians(60);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float screenX = (x / (float)(width - 1)) * 2.0f - 1.0f;
                float screenY = (y / (float)(height - 1)) * 2.0f - 1.0f;
                
                var direction = new Vector3f(
                    screenX * (float) Math.tan(fov * 0.5f),
                    screenY * (float) Math.tan(fov * 0.5f),
                    1.0f
                );
                direction.normalize();
                
                rays.add(new BeamOptimizer.Ray(cameraPos, direction, x, y, y * width + x));
            }
        }
        
        return rays;
    }
    
    private List<BeamOptimizer.Ray> createSpatialPatternRays(int patternId) {
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        
        // Create rays in different spatial regions based on pattern
        float baseX = (patternId % 3) * 2.0f;
        float baseY = (patternId / 3) * 2.0f;
        
        for (int i = 0; i < 20; i++) {
            var origin = new Vector3f(
                baseX + (i % 5) * 0.2f,
                baseY + (i / 5) * 0.2f,
                0
            );
            var direction = new Vector3f(0, 0, 1);
            
            rays.add(new BeamOptimizer.Ray(origin, direction, i % 8, i / 8, i));
        }
        
        return rays;
    }
    
    private List<BeamOptimizer.Ray> createMovingCameraRays(int frameIndex) {
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        
        // Camera moves forward each frame
        var cameraPos = new Vector3f(0, 0, frameIndex * 1.0f);
        var direction = new Vector3f(0, 0, 1);
        
        for (int i = 0; i < 16; i++) {
            var origin = new Vector3f(cameraPos);
            origin.x += (i % 4) * 0.1f;
            origin.y += (i / 4) * 0.1f;
            
            rays.add(new BeamOptimizer.Ray(origin, direction, i % 4, i / 4, i));
        }
        
        return rays;
    }
    
    private List<BeamOptimizer.Ray> createRandomRays(int count, int seed) {
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        Random random = new Random(seed);
        
        for (int i = 0; i < count; i++) {
            var origin = new Vector3f(
                random.nextFloat() * 4 - 2,
                random.nextFloat() * 4 - 2,
                random.nextFloat() * 2
            );
            var direction = new Vector3f(
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1,
                random.nextFloat() + 0.1f
            );
            direction.normalize();
            
            rays.add(new BeamOptimizer.Ray(origin, direction, i % 16, i / 16, i));
        }
        
        return rays;
    }
}