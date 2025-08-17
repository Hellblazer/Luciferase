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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for BeamOptimizer coherent ray processing.
 * Tests beam formation, coherence analysis, and optimization effectiveness.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BeamOptimizerTest {
    private static final Logger log = LoggerFactory.getLogger(BeamOptimizerTest.class);
    
    private BeamOptimizer optimizer;
    private BeamOptimizer.BeamConfig config;
    
    @BeforeEach
    void setUp() {
        config = BeamOptimizer.BeamConfig.defaultConfig();
        optimizer = new BeamOptimizer(config);
    }
    
    @Test
    @Order(1)
    @DisplayName("Ray creation and similarity calculations")
    void testRayOperations() {
        log.info("Testing Ray creation and similarity calculations...");
        
        var origin1 = new Vector3f(0, 0, 0);
        var direction1 = new Vector3f(0, 0, 1);
        var ray1 = new BeamOptimizer.Ray(origin1, direction1, 100, 100, 1);
        
        var origin2 = new Vector3f(1, 1, 0);
        var direction2 = new Vector3f(0, 0, 1);
        var ray2 = new BeamOptimizer.Ray(origin2, direction2, 101, 101, 2);
        
        // Test directional similarity (same direction)
        float similarity = ray1.directionalSimilarity(ray2);
        assertEquals(1.0f, similarity, 0.001f, "Parallel rays should have similarity of 1.0");
        
        // Test spatial distance
        float distance = ray1.spatialDistance(ray2);
        assertEquals(Math.sqrt(2), distance, 0.001f, "Distance should be sqrt(2)");
        
        // Test with different directions
        var direction3 = new Vector3f(1, 0, 0);
        var ray3 = new BeamOptimizer.Ray(origin1, direction3, 100, 100, 3);
        
        float similarity2 = ray1.directionalSimilarity(ray3);
        assertEquals(0.0f, similarity2, 0.001f, "Perpendicular rays should have similarity of 0.0");
        
        log.info("Ray operations test passed. Similarity: {}, Distance: {}", similarity, distance);
    }
    
    @Test
    @Order(2)
    @DisplayName("Beam creation and statistics")
    void testBeamStatistics() {
        log.info("Testing Beam creation and statistics...");
        
        var beam = new BeamOptimizer.Beam();
        assertTrue(beam.isEmpty());
        assertEquals(0, beam.getRayCount());
        
        // Add coherent rays (same direction, close origins)
        var baseOrigin = new Vector3f(0, 0, 0);
        var direction = new Vector3f(0, 0, 1);
        
        for (int i = 0; i < 5; i++) {
            var origin = new Vector3f(i * 0.1f, i * 0.1f, 0);
            var ray = new BeamOptimizer.Ray(origin, direction, 100 + i, 100 + i, i);
            beam.addRay(ray);
        }
        
        assertEquals(5, beam.getRayCount());
        assertFalse(beam.isEmpty());
        
        // Check centroid calculation
        var centroid = beam.getCentroidOrigin();
        assertEquals(0.2f, centroid.x, 0.001f); // Average of 0, 0.1, 0.2, 0.3, 0.4
        assertEquals(0.2f, centroid.y, 0.001f);
        assertEquals(0.0f, centroid.z, 0.001f);
        
        // Check average direction
        var avgDir = beam.getAverageDirection();
        assertEquals(0.0f, avgDir.x, 0.001f);
        assertEquals(0.0f, avgDir.y, 0.001f);
        assertEquals(1.0f, avgDir.z, 0.001f);
        
        // Check coherence (should be high for coherent rays)
        float coherence = beam.getCoherenceScore();
        assertTrue(coherence > 0.8f, "Coherent rays should have high coherence score, got: " + coherence);
        
        log.info("Beam statistics test passed. Coherence: {}, Centroid: {}", coherence, centroid);
    }
    
    @Test
    @Order(3)
    @DisplayName("Beam acceptance criteria")
    void testBeamAcceptance() {
        log.info("Testing Beam acceptance criteria...");
        
        var beam = new BeamOptimizer.Beam();
        var baseOrigin = new Vector3f(0, 0, 0);
        var baseDirection = new Vector3f(0, 0, 1);
        
        // Add seed ray
        var seedRay = new BeamOptimizer.Ray(baseOrigin, baseDirection, 100, 100, 1);
        beam.addRay(seedRay);
        
        // Test coherent ray (should be accepted)
        var coherentOrigin = new Vector3f(0.1f, 0.1f, 0);
        var coherentDirection = new Vector3f(0, 0, 1);
        var coherentRay = new BeamOptimizer.Ray(coherentOrigin, coherentDirection, 101, 101, 2);
        
        assertTrue(beam.wouldAccept(coherentRay, config), "Coherent ray should be accepted");
        
        // Test divergent direction (should be rejected)
        var divergentDirection = new Vector3f(1, 0, 0); // 90 degrees different
        var divergentRay = new BeamOptimizer.Ray(baseOrigin, divergentDirection, 100, 100, 3);
        
        assertFalse(beam.wouldAccept(divergentRay, config), "Divergent ray should be rejected");
        
        // Test distant origin (should be rejected)
        var distantOrigin = new Vector3f(10, 10, 0); // Far away
        var distantRay = new BeamOptimizer.Ray(distantOrigin, baseDirection, 200, 200, 4);
        
        assertFalse(beam.wouldAccept(distantRay, config), "Distant ray should be rejected");
        
        log.info("Beam acceptance test passed.");
    }
    
    @Test
    @Order(4)
    @DisplayName("Beam optimization with coherent rays")
    void testCoherentRayOptimization() {
        log.info("Testing beam optimization with coherent rays...");
        
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        
        // Create a cluster of coherent rays (camera looking forward)
        var cameraPos = new Vector3f(0, 0, 0);
        var forwardDir = new Vector3f(0, 0, 1);
        
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                var origin = new Vector3f(x * 0.1f, y * 0.1f, 0);
                var direction = new Vector3f(forwardDir);
                // Add slight variation to simulate FOV
                direction.x += (x - 1.5f) * 0.01f;
                direction.y += (y - 1.5f) * 0.01f;
                direction.normalize();
                
                var ray = new BeamOptimizer.Ray(origin, direction, x * 100, y * 100, y * 4 + x);
                rays.add(ray);
            }
        }
        
        List<BeamOptimizer.Beam> beams = optimizer.optimizeRays(rays);
        
        assertFalse(beams.isEmpty(), "Should create at least one beam");
        
        int totalRaysInBeams = beams.stream().mapToInt(BeamOptimizer.Beam::getRayCount).sum();
        assertEquals(rays.size(), totalRaysInBeams, "All rays should be assigned to beams");
        
        // Should create fewer beams than rays due to coherence
        assertTrue(beams.size() < rays.size(), "Should create fewer beams than rays");
        
        // Check that beams are sorted by coherence (highest first)
        for (int i = 1; i < beams.size(); i++) {
            assertTrue(beams.get(i-1).getCoherenceScore() >= beams.get(i).getCoherenceScore(),
                    "Beams should be sorted by coherence score");
        }
        
        var analysis = optimizer.analyzeBeams(beams);
        log.info("Coherent optimization result: {}", analysis);
        
        assertTrue(analysis.averageCoherence > 0.7f, "Average coherence should be high for coherent rays");
    }
    
    @Test
    @Order(5)
    @DisplayName("Beam optimization with incoherent rays")
    void testIncoherentRayOptimization() {
        log.info("Testing beam optimization with incoherent rays...");
        
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        Random random = new Random(42); // Deterministic
        
        // Create completely random rays
        for (int i = 0; i < 20; i++) {
            var origin = new Vector3f(
                random.nextFloat() * 10 - 5,
                random.nextFloat() * 10 - 5,
                random.nextFloat() * 10 - 5
            );
            var direction = new Vector3f(
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1
            );
            direction.normalize();
            
            var ray = new BeamOptimizer.Ray(origin, direction, i % 10, i / 10, i);
            rays.add(ray);
        }
        
        List<BeamOptimizer.Beam> beams = optimizer.optimizeRays(rays);
        
        assertFalse(beams.isEmpty(), "Should create beams even for incoherent rays");
        
        int totalRaysInBeams = beams.stream().mapToInt(BeamOptimizer.Beam::getRayCount).sum();
        assertEquals(rays.size(), totalRaysInBeams, "All rays should be assigned to beams");
        
        // Should create more beams due to incoherence
        assertTrue(beams.size() > rays.size() / 4, "Should create many beams for incoherent rays");
        
        var analysis = optimizer.analyzeBeams(beams);
        log.info("Incoherent optimization result: {}", analysis);
        
        assertTrue(analysis.averageCoherence < 1.0f, "Average coherence should be less than perfect for incoherent rays, got: " + analysis.averageCoherence);
    }
    
    @Test
    @Order(6)
    @DisplayName("Beam configuration parameter effects")
    void testBeamConfigurationEffects() {
        log.info("Testing beam configuration parameter effects...");
        
        // Create test rays
        List<BeamOptimizer.Ray> rays = createTestRayGrid(6, 6);
        
        // Test with strict configuration  
        var strictConfig = BeamOptimizer.BeamConfig.defaultConfig()
            .withMaxRays(4)
            .withDirectionalVariance(0.05f)
            .withSpatialVariance(0.5f);
        strictConfig.minRaysPerBeam = 2; // Increase min for meaningful comparison
        
        var strictOptimizer = new BeamOptimizer(strictConfig);
        var strictBeams = strictOptimizer.optimizeRays(rays);
        
        // Test with loose configuration
        var looseConfig = BeamOptimizer.BeamConfig.defaultConfig()
            .withMaxRays(16)
            .withDirectionalVariance(0.5f)
            .withSpatialVariance(5.0f);
        
        var looseOptimizer = new BeamOptimizer(looseConfig);
        var looseBeams = looseOptimizer.optimizeRays(rays);
        
        // Strict config should create more, smaller beams
        assertTrue(strictBeams.size() >= looseBeams.size(), 
                "Strict config should create more or equal beams");
        
        // Loose config should create larger beams on average
        var strictAnalysis = strictOptimizer.analyzeBeams(strictBeams);
        var looseAnalysis = looseOptimizer.analyzeBeams(looseBeams);
        
        assertTrue(looseAnalysis.averageBeamSize >= strictAnalysis.averageBeamSize,
                "Loose config should create larger beams on average");
        
        log.info("Configuration effects - Strict: {}, Loose: {}", strictAnalysis, looseAnalysis);
    }
    
    @Test
    @Order(7)
    @DisplayName("Adaptive beam splitting")
    void testAdaptiveBeamSplitting() {
        log.info("Testing adaptive beam splitting...");
        
        // Create mixed coherence rays
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        
        // First cluster: highly coherent
        for (int i = 0; i < 8; i++) {
            var origin = new Vector3f(i * 0.1f, 0, 0);
            var direction = new Vector3f(0, 0, 1);
            rays.add(new BeamOptimizer.Ray(origin, direction, i, 0, i));
        }
        
        // Second cluster: less coherent
        for (int i = 0; i < 8; i++) {
            var origin = new Vector3f(0, i * 0.2f, 1);
            var direction = new Vector3f(i * 0.1f, 0, 1);
            direction.normalize();
            rays.add(new BeamOptimizer.Ray(origin, direction, 0, i + 10, i + 8));
        }
        
        // Test with adaptive splitting enabled
        var adaptiveConfig = BeamOptimizer.BeamConfig.defaultConfig();
        adaptiveConfig.adaptiveSplitting = true;
        adaptiveConfig.coherenceThreshold = 0.9f;
        
        var adaptiveOptimizer = new BeamOptimizer(adaptiveConfig);
        var adaptiveBeams = adaptiveOptimizer.optimizeRays(rays);
        
        // Test with adaptive splitting disabled
        var nonAdaptiveConfig = BeamOptimizer.BeamConfig.defaultConfig();
        nonAdaptiveConfig.adaptiveSplitting = false;
        
        var nonAdaptiveOptimizer = new BeamOptimizer(nonAdaptiveConfig);
        var nonAdaptiveBeams = nonAdaptiveOptimizer.optimizeRays(rays);
        
        // Adaptive should potentially create more beams to maintain quality
        var adaptiveAnalysis = adaptiveOptimizer.analyzeBeams(adaptiveBeams);
        var nonAdaptiveAnalysis = nonAdaptiveOptimizer.analyzeBeams(nonAdaptiveBeams);
        
        log.info("Adaptive splitting - Adaptive: {}, Non-adaptive: {}", 
                 adaptiveAnalysis, nonAdaptiveAnalysis);
        
        // Both should process all rays
        assertEquals(rays.size(), adaptiveAnalysis.totalRays);
        assertEquals(rays.size(), nonAdaptiveAnalysis.totalRays);
    }
    
    @Test
    @Order(8)
    @DisplayName("Large scale beam optimization performance")
    void testLargeScaleOptimization() {
        log.info("Testing large scale beam optimization performance...");
        
        // Create a realistic camera ray distribution (1024 rays)
        List<BeamOptimizer.Ray> rays = createCameraRays(32, 32);
        
        long startTime = System.nanoTime();
        List<BeamOptimizer.Beam> beams = optimizer.optimizeRays(rays);
        long endTime = System.nanoTime();
        
        double durationMs = (endTime - startTime) / 1_000_000.0;
        
        var analysis = optimizer.analyzeBeams(beams);
        
        log.info("Large scale optimization completed in {:.2f}ms. Result: {}", durationMs, analysis);
        
        // Verify all rays processed
        assertEquals(rays.size(), analysis.totalRays, "All rays should be processed");
        
        // Should achieve reasonable compression
        assertTrue(analysis.compressionRatio < 0.5f, "Should achieve at least 2:1 compression ratio");
        
        // Should complete quickly
        assertTrue(durationMs < 100, "Should complete within 100ms");
        
        // Verify beam quality
        for (var beam : beams) {
            assertTrue(beam.getRayCount() > 0, "All beams should contain rays");
            assertTrue(beam.getCoherenceScore() >= 0.0f && beam.getCoherenceScore() <= 1.0f,
                    "Coherence scores should be in valid range");
        }
    }
    
    @Test
    @Order(9)
    @DisplayName("Edge cases and robustness")
    void testEdgeCases() {
        log.info("Testing edge cases and robustness...");
        
        // Test empty ray list
        List<BeamOptimizer.Beam> emptyResult = optimizer.optimizeRays(Collections.emptyList());
        assertTrue(emptyResult.isEmpty(), "Empty ray list should return empty beam list");
        
        // Test single ray
        var singleRay = new BeamOptimizer.Ray(new Vector3f(), new Vector3f(0, 0, 1), 0, 0, 1);
        var singleResult = optimizer.optimizeRays(Collections.singletonList(singleRay));
        assertEquals(1, singleResult.size(), "Single ray should create one beam");
        assertEquals(1, singleResult.get(0).getRayCount(), "Single ray beam should contain one ray");
        
        // Test identical rays
        List<BeamOptimizer.Ray> identicalRays = new ArrayList<>();
        var origin = new Vector3f(1, 2, 3);
        var direction = new Vector3f(0, 0, 1);
        for (int i = 0; i < 10; i++) {
            identicalRays.add(new BeamOptimizer.Ray(origin, direction, 100, 100, i));
        }
        
        var identicalResult = optimizer.optimizeRays(identicalRays);
        assertEquals(1, identicalResult.size(), "Identical rays should form one beam");
        assertEquals(10, identicalResult.get(0).getRayCount(), "Beam should contain all identical rays");
        assertEquals(1.0f, identicalResult.get(0).getCoherenceScore(), 0.001f, 
                "Identical rays should have perfect coherence");
        
        log.info("Edge cases test passed.");
    }
    
    // Helper methods
    
    private List<BeamOptimizer.Ray> createTestRayGrid(int width, int height) {
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                var origin = new Vector3f(x * 0.2f, y * 0.2f, 0);
                var direction = new Vector3f(0, 0, 1);
                // Add slight variation
                direction.x += (x - width/2f) * 0.02f;
                direction.y += (y - height/2f) * 0.02f;
                direction.normalize();
                
                rays.add(new BeamOptimizer.Ray(origin, direction, x, y, y * width + x));
            }
        }
        
        return rays;
    }
    
    private List<BeamOptimizer.Ray> createCameraRays(int width, int height) {
        List<BeamOptimizer.Ray> rays = new ArrayList<>();
        
        var cameraPos = new Vector3f(0, 0, 0);
        float fov = (float) Math.toRadians(60); // 60 degree FOV
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Normalized screen coordinates [-1, 1]
                float screenX = (x / (float)(width - 1)) * 2.0f - 1.0f;
                float screenY = (y / (float)(height - 1)) * 2.0f - 1.0f;
                
                // Convert to world direction
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
}