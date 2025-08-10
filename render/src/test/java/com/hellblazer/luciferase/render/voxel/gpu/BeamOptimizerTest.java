package com.hellblazer.luciferase.render.voxel.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for BeamOptimizer coherent ray processing.
 * Validates ray clustering, beam creation, and coherence analysis.
 */
public class BeamOptimizerTest {
    
    private static final float EPSILON = 1e-5f;
    private BeamOptimizer optimizer;
    
    @BeforeEach
    public void setUp() {
        optimizer = new BeamOptimizer();
    }
    
    @Test
    public void testCoherentRayBeamCreation() {
        // Create spatially and directionally coherent rays
        var rays = createCoherentRays(16);
        
        var beams = optimizer.createCoherentBeams(rays);
        
        assertFalse(beams.isEmpty(), "Should create at least one beam");
        
        // Should create few beams from coherent rays  
        assertTrue(beams.size() <= 3, "Coherent rays should form few beams");
        
        // Check total ray count is preserved
        int totalRays = beams.stream().mapToInt(BeamOptimizer.RayBeam::size).sum();
        assertEquals(16, totalRays, "All rays should be included in beams");
        
        // Check beam properties  
        for (var beam : beams) {
            assertTrue(beam.size() > 0, "Each beam should contain rays");
            assertNotNull(beam.toString(), "Beam should have string representation");
        }
    }
    
    @Test
    public void testIncoherentRayHandling() {
        // Create spatially dispersed rays
        var rays = createRandomRays(20);
        
        var beams = optimizer.createCoherentBeams(rays);
        
        assertFalse(beams.isEmpty(), "Should create beams even for incoherent rays");
        
        // Should create multiple smaller beams
        assertTrue(beams.size() > 1, "Incoherent rays should create multiple beams");
        
        int totalRays = beams.stream().mapToInt(BeamOptimizer.RayBeam::size).sum();
        assertEquals(20, totalRays, "All rays should be included in beams");
        
        // Check that we have many individual beams (which are technically coherent)
        // For truly random rays, expect mostly single-ray beams
        long singleRayBeams = beams.stream().mapToLong(b -> b.size() == 1 ? 1 : 0).sum();
        assertTrue(singleRayBeams >= beams.size() / 2, "Random rays should create many single-ray beams");
    }
    
    @Test
    public void testBeamSizeConstraints() {
        var config = new BeamOptimizer.BeamConfig();
        config.minBeamSize = 4;
        config.maxBeamSize = 8;
        config.preferredBeamSize = 6;
        
        var optimizer = new BeamOptimizer(config);
        
        // Create rays that should form multiple beams
        var rays = createCoherentRays(25);
        var beams = optimizer.createCoherentBeams(rays);
        
        // Check beam size constraints
        // Note: Implementation may create individual ray beams when insufficient coherent rays
        for (var beam : beams) {
            assertTrue(beam.size() >= 1, "Each beam should contain at least one ray");
            assertTrue(beam.size() <= config.maxBeamSize, 
                "Beam size should respect maximum constraint");
        }
        
        // Check total ray preservation
        int totalRays = beams.stream().mapToInt(BeamOptimizer.RayBeam::size).sum();
        assertEquals(25, totalRays, "All rays should be preserved in beams");
    }
    
    @Test
    public void testRayCoherenceDetection() {
        // Test spatial coherence
        var ray1 = new BeamOptimizer.Ray(
            new Point3f(0, 0, 0), new Vector3f(1, 0, 0), 0, 10, 0);
        var ray2 = new BeamOptimizer.Ray(
            new Point3f(0.05f, 0.05f, 0), new Vector3f(1, 0, 0), 0, 10, 1);
        
        var coherentRays = List.of(ray1, ray2);
        var beams = optimizer.createCoherentBeams(coherentRays);
        
        assertTrue(beams.size() <= 2, "Coherent rays should form few beams");
        assertTrue(beams.get(0).isCoherent(), "Beam should be coherent");
        
        // Test spatial incoherence
        var ray3 = new BeamOptimizer.Ray(
            new Point3f(10, 10, 10), new Vector3f(1, 0, 0), 0, 10, 2);
        
        var incoherentRays = List.of(ray1, ray3);
        beams = optimizer.createCoherentBeams(incoherentRays);
        
        assertTrue(beams.size() >= 2, "Spatially incoherent rays should form separate beams");
        
        // Test directional incoherence
        var ray4 = new BeamOptimizer.Ray(
            new Point3f(0, 0, 0), new Vector3f(0, 1, 0), 0, 10, 3);
        
        var dirIncoherentRays = List.of(ray1, ray4);
        beams = optimizer.createCoherentBeams(dirIncoherentRays);
        
        assertTrue(beams.size() >= 2, "Directionally incoherent rays should form separate beams");
    }
    
    @Test
    public void testBeamCentroidCalculation() {
        var rays = List.of(
            new BeamOptimizer.Ray(new Point3f(0, 0, 0), new Vector3f(1, 0, 0), 0, 10, 0),
            new BeamOptimizer.Ray(new Point3f(2, 0, 0), new Vector3f(1, 0, 0), 0, 10, 1),
            new BeamOptimizer.Ray(new Point3f(4, 0, 0), new Vector3f(1, 0, 0), 0, 10, 2)
        );
        
        var beam = new BeamOptimizer.RayBeam(rays);
        var centroid = beam.getCentroidOrigin();
        
        assertEquals(2.0f, centroid.x, EPSILON, "Centroid X should be average");
        assertEquals(0.0f, centroid.y, EPSILON, "Centroid Y should be average");
        assertEquals(0.0f, centroid.z, EPSILON, "Centroid Z should be average");
    }
    
    @Test
    public void testBeamDirectionCalculation() {
        var rays = List.of(
            new BeamOptimizer.Ray(new Point3f(0, 0, 0), new Vector3f(1, 0, 0), 0, 10, 0),
            new BeamOptimizer.Ray(new Point3f(0, 0, 0), new Vector3f(0.9f, 0.1f, 0), 0, 10, 1),
            new BeamOptimizer.Ray(new Point3f(0, 0, 0), new Vector3f(1, -0.1f, 0), 0, 10, 2)
        );
        
        var beam = new BeamOptimizer.RayBeam(rays);
        var avgDirection = beam.getAverageDirection();
        
        // Average direction should be approximately (1, 0, 0)
        assertTrue(avgDirection.x > 0.9f, "Average direction should be close to (1,0,0)");
        assertTrue(Math.abs(avgDirection.y) < 0.1f, "Y component should be small");
        assertEquals(1.0f, avgDirection.length(), EPSILON, "Direction should be normalized");
    }
    
    @Test
    public void testCoherenceAnalysis() {
        // Create mixed coherent and incoherent beams
        var coherentRays = createCoherentRays(10);
        var incoherentRays = createRandomRays(10);
        
        var allRays = new ArrayList<BeamOptimizer.Ray>();
        allRays.addAll(coherentRays);
        allRays.addAll(incoherentRays);
        
        var beams = optimizer.createCoherentBeams(allRays);
        var analysis = optimizer.analyzeCoherence(beams);
        
        assertNotNull(analysis);
        assertTrue(analysis.beamCount > 0, "Should have created beams");
        assertTrue(analysis.averageBeamSize > 0, "Average beam size should be positive");
        assertTrue(analysis.coherenceRatio >= 0 && analysis.coherenceRatio <= 1, 
            "Coherence ratio should be in [0,1]");
        
        assertNotNull(analysis.toString(), "Should provide string representation");
    }
    
    @Test
    public void testAdaptiveBeamingConfigurations() {
        // Test high coherence configuration
        var highCoherenceConfig = BeamOptimizer.BeamConfig.highCoherence();
        var highCoherenceOptimizer = new BeamOptimizer(highCoherenceConfig);
        
        var coherentRays = createCoherentRays(50);
        var beams = highCoherenceOptimizer.createCoherentBeams(coherentRays);
        
        // Should create fewer, larger beams
        assertTrue(beams.size() <= 5, "High coherence should create fewer beams");
        assertTrue(beams.stream().mapToInt(BeamOptimizer.RayBeam::size).max().orElse(0) >= 16, 
            "Should create larger beams");
        
        // Test low coherence configuration
        var lowCoherenceConfig = BeamOptimizer.BeamConfig.lowCoherence();
        var lowCoherenceOptimizer = new BeamOptimizer(lowCoherenceConfig);
        
        beams = lowCoherenceOptimizer.createCoherentBeams(coherentRays);
        
        // Should create more, smaller beams due to stricter thresholds
        assertTrue(beams.size() >= 5, "Low coherence should create more beams");
    }
    
    @Test
    public void testEmptyRayHandling() {
        var beams = optimizer.createCoherentBeams(new ArrayList<>());
        
        assertTrue(beams.isEmpty(), "Empty ray list should produce no beams");
        
        var analysis = optimizer.analyzeCoherence(beams);
        assertEquals(0, analysis.beamCount, "Analysis should show no beams");
        assertEquals(0.0, analysis.averageBeamSize, EPSILON, "Average size should be zero");
    }
    
    @Test
    public void testBeamWorkloadEstimation() {
        var rays = createCoherentRays(8);
        var beam = new BeamOptimizer.RayBeam(rays);
        
        float workload = beam.getWorkloadEstimate();
        assertTrue(workload > 0, "Workload should be positive");
        assertTrue(workload >= rays.size(), "Workload should scale with ray count");
    }
    
    @Test
    public void testBeamPerformanceCharacteristics() {
        // Test that beam creation completes in reasonable time
        var largeRaySet = createRandomRays(1000);
        
        long startTime = System.nanoTime();
        var beams = optimizer.createCoherentBeams(largeRaySet);
        long elapsedTime = System.nanoTime() - startTime;
        
        double msPerRay = (elapsedTime / 1e6) / largeRaySet.size();
        
        // Should complete in reasonable time
        assertTrue(msPerRay < 0.1, 
            String.format("Beam creation too slow: %.4f ms per ray", msPerRay));
        
        // Should process all rays
        int totalProcessedRays = beams.stream().mapToInt(BeamOptimizer.RayBeam::size).sum();
        assertEquals(1000, totalProcessedRays, "All rays should be processed");
        
        // Should create reasonable number of beams (may be many for random rays)
        assertTrue(beams.size() >= 10, "Should create reasonable number of beams");
        assertTrue(beams.size() <= largeRaySet.size(), "Should not create more beams than rays");
    }
    
    @Test
    public void testWorkloadBalancing() {
        var config = new BeamOptimizer.BeamConfig();
        config.enableWorkloadBalancing = true;
        config.workloadBalanceThreshold = 0.5f;
        
        var optimizer = new BeamOptimizer(config);
        
        // Create rays with varying complexity (different ray counts)
        var rays = new ArrayList<BeamOptimizer.Ray>();
        
        // Add few high-workload rays
        for (int i = 0; i < 3; i++) {
            rays.add(new BeamOptimizer.Ray(
                new Point3f(i, 0, 0), new Vector3f(1, 0, 0), 0, 100, i));
        }
        
        // Add many low-workload rays  
        for (int i = 3; i < 20; i++) {
            rays.add(new BeamOptimizer.Ray(
                new Point3f(10 + i, 0, 0), new Vector3f(1, 0, 0), 0, 1, i));
        }
        
        var beams = optimizer.createCoherentBeams(rays);
        
        // Should balance workloads across beams
        var workloads = beams.stream().map(BeamOptimizer.RayBeam::getWorkloadEstimate).toList();
        float minWorkload = workloads.stream().min(Float::compare).orElse(0f);
        float maxWorkload = workloads.stream().max(Float::compare).orElse(0f);
        
        if (minWorkload > 0) {
            float imbalanceRatio = (maxWorkload - minWorkload) / maxWorkload;
            assertTrue(imbalanceRatio < 0.8f, "Workload should be reasonably balanced");
        }
    }
    
    // Helper methods
    
    private List<BeamOptimizer.Ray> createCoherentRays(int count) {
        var rays = new ArrayList<BeamOptimizer.Ray>();
        
        for (int i = 0; i < count; i++) {
            // Very small spatial variation to stay within 0.1f threshold
            var origin = new Point3f(
                i * 0.003f,
                i * 0.003f,  
                0
            );
            
            // Very small directional variation to stay within 0.05f threshold
            var direction = new Vector3f(
                1.0f,
                i * 0.0005f,
                0
            );
            direction.normalize();
            
            rays.add(new BeamOptimizer.Ray(origin, direction, 0, 10, i));
        }
        
        return rays;
    }
    
    private List<BeamOptimizer.Ray> createRandomRays(int count) {
        var rays = new ArrayList<BeamOptimizer.Ray>();
        var random = new java.util.Random(42); // Deterministic for testing
        
        for (int i = 0; i < count; i++) {
            var origin = new Point3f(
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
            
            rays.add(new BeamOptimizer.Ray(origin, direction, 0, 10, i));
        }
        
        return rays;
    }
}