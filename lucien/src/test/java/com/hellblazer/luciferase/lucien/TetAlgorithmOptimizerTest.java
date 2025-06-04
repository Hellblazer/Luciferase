package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.TetAlgorithmOptimizer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TetAlgorithmOptimizer (Phase 5E)
 * Tests advanced tetrahedral algorithm optimizations including SFC improvements,
 * geometric algorithms, adaptive refinement, error correction, and performance profiling.
 */
public class TetAlgorithmOptimizerTest {

    @BeforeEach
    void setUp() {
        // Clear all caches and reset metrics before each test
        TetAlgorithmOptimizer.clearAllCaches();
        TetAlgorithmOptimizer.getGlobalMetrics().reset();
        TetAlgorithmOptimizer.getGlobalProfiler().reset();
    }

    @Test
    @DisplayName("Test optimized SFC encoding and decoding")
    void testOptimizedSFCOperations() {
        // Test SFC encoding - tetrahedral SFC transforms coordinates to canonical form
        int x = 1000, y = 2000, z = 3000;
        byte level = 10, type = 3;
        
        long encoded = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(x, y, z, level, type);
        assertTrue(encoded >= 0, "Encoded SFC index should be non-negative");
        
        // Test SFC decoding
        var decoded = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(encoded);
        assertNotNull(decoded, "Decoded result should not be null");
        assertTrue(decoded.valid(), "Decoded result should be valid");
        
        // Tetrahedral SFC transforms coordinates to canonical form - test round-trip consistency
        long encoded2 = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(
            decoded.x(), decoded.y(), decoded.z(), decoded.level(), decoded.type());
        assertEquals(encoded, encoded2, "Round-trip encoding should be consistent");
        
        // Test cache hit on second decode
        var decoded2 = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(encoded);
        assertEquals(decoded, decoded2, "Second decode should return cached result");
        
        // Verify metrics
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        String summary = metrics.getMetricsSummary();
        assertTrue(summary.contains("SFC Encodings: 2"), "Should record 2 SFC encodings. Actual: " + summary);
        assertTrue(summary.contains("SFC Decodings: 2"), "Should record 2 SFC decodings. Actual: " + summary);
        assertTrue(metrics.getCacheHitRate() > 0.0, "Should have cache hits");
    }

    @Test
    @DisplayName("Test SFC encoding validation")
    void testSFCEncodingValidation() {
        // Test negative coordinate validation
        assertThrows(IllegalArgumentException.class, () -> 
            TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(-1, 0, 0, (byte) 5, (byte) 0),
            "Should reject negative coordinates");
        
        // Test invalid type validation
        assertThrows(IllegalArgumentException.class, () -> 
            TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(0, 0, 0, (byte) 5, (byte) 6),
            "Should reject type > 5");
            
        assertThrows(IllegalArgumentException.class, () -> 
            TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(0, 0, 0, (byte) 5, (byte) -1),
            "Should reject negative type");
    }

    @Test
    @DisplayName("Test SFC range optimization")
    void testSFCRangeOptimization() {
        Point3f min = new Point3f(100.5f, 200.5f, 300.5f); // Use fractional offsets
        Point3f max = new Point3f(500.5f, 600.5f, 700.5f);
        byte level = 8;
        
        var ranges = TetSFCAlgorithmImprovements.TetSFCRangeOptimizer.computeOptimizedRanges(min, max, level);
        
        assertNotNull(ranges, "Ranges should not be null");
        assertFalse(ranges.isEmpty(), "Should compute at least one range");
        
        // Verify ranges are valid and ordered
        for (var range : ranges) {
            assertTrue(range.startIndex() <= range.endIndex(), "Range start should be <= end");
            assertTrue(range.type() >= 0 && range.type() <= 5, "Range type should be valid");
        }
        
        // Verify ranges are sorted
        for (int i = 1; i < ranges.size(); i++) {
            assertTrue(ranges.get(i-1).startIndex() <= ranges.get(i).startIndex(), 
                "Ranges should be sorted by start index");
        }
        
        // Verify metrics
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        assertTrue(metrics.getMetricsSummary().contains("Range Computations: 1"));
    }

    @Test
    @DisplayName("Test tetrahedral volume computation")
    void testVolumeComputation() {
        // Test with a valid tetrahedron (avoid grid vertex coordinates)
        Point3i[] vertices = {
            new Point3i(0, 0, 0),
            new Point3i(100, 0, 0),
            new Point3i(50, 100, 0),
            new Point3i(50, 50, 100)
        };
        
        double volume = TetGeometricAlgorithms.VolumeComputation.computeVolume(vertices);
        assertTrue(volume > 0, "Volume should be positive for valid tetrahedron");
        
        // Test batch volume computation
        var tetrahedra = Arrays.asList(vertices, vertices.clone());
        var volumes = TetGeometricAlgorithms.VolumeComputation.computeVolumes(tetrahedra);
        assertEquals(2, volumes.size(), "Should compute volume for each tetrahedron");
        assertEquals(volume, volumes.get(0), 1e-10, "Volumes should match");
        assertEquals(volume, volumes.get(1), 1e-10, "Volumes should match");
        
        // Test invalid input
        Point3i[] invalidVertices = {new Point3i(0, 0, 0), new Point3i(1, 0, 0)};
        assertThrows(IllegalArgumentException.class, () -> 
            TetGeometricAlgorithms.VolumeComputation.computeVolume(invalidVertices),
            "Should reject tetrahedron with wrong number of vertices");
    }

    @Test
    @DisplayName("Test containment optimization")
    void testContainmentOptimization() {
        // Create a tetrahedron using fractional offsets to avoid vertex ambiguity
        Point3i[] vertices = {
            new Point3i(0, 0, 0),
            new Point3i(1000, 0, 0),
            new Point3i(500, 1000, 0),
            new Point3i(500, 500, 1000)
        };
        
        // Test point inside tetrahedron (use centroid)
        Point3f inside = new Point3f(500.0f, 375.0f, 250.0f);
        assertTrue(TetGeometricAlgorithms.ContainmentOptimization.containsPoint(vertices, inside),
            "Point at centroid should be inside tetrahedron");
        
        // Test point clearly outside
        Point3f outside = new Point3f(2000.0f, 2000.0f, 2000.0f);
        assertFalse(TetGeometricAlgorithms.ContainmentOptimization.containsPoint(vertices, outside),
            "Point far outside should not be contained");
        
        // Test batch containment
        var points = Arrays.asList(inside, outside);
        var results = TetGeometricAlgorithms.ContainmentOptimization.containsPoints(vertices, points);
        assertEquals(2, results.size(), "Should test all points");
        assertTrue(results.get(0), "First point should be inside");
        assertFalse(results.get(1), "Second point should be outside");
        
        // Test with invalid tetrahedron
        Point3i[] invalidTet = {new Point3i(0, 0, 0)};
        assertFalse(TetGeometricAlgorithms.ContainmentOptimization.containsPoint(invalidTet, inside),
            "Should return false for invalid tetrahedron");
    }

    @Test
    @DisplayName("Test AABB intersection algorithms")
    void testAABBIntersection() {
        // Create tetrahedron vertices
        Point3i[] vertices = {
            new Point3i(100, 100, 100),
            new Point3i(200, 100, 100),
            new Point3i(150, 200, 100),
            new Point3i(150, 150, 200)
        };
        
        // Test intersecting AABB
        Point3f min1 = new Point3f(120.0f, 120.0f, 120.0f);
        Point3f max1 = new Point3f(180.0f, 180.0f, 180.0f);
        assertTrue(TetGeometricAlgorithms.IntersectionAlgorithms.intersectsAABB(vertices, min1, max1),
            "AABB should intersect tetrahedron");
        
        // Test non-intersecting AABB
        Point3f min2 = new Point3f(500.0f, 500.0f, 500.0f);
        Point3f max2 = new Point3f(600.0f, 600.0f, 600.0f);
        assertFalse(TetGeometricAlgorithms.IntersectionAlgorithms.intersectsAABB(vertices, min2, max2),
            "Distant AABB should not intersect tetrahedron");
        
        // Test with invalid tetrahedron
        Point3i[] invalidTet = {new Point3i(0, 0, 0)};
        assertFalse(TetGeometricAlgorithms.IntersectionAlgorithms.intersectsAABB(invalidTet, min1, max1),
            "Should return false for invalid tetrahedron");
    }

    @Test
    @DisplayName("Test adaptive level selection")
    void testAdaptiveLevelSelection() {
        Point3f center = new Point3f(1000.0f, 1000.0f, 1000.0f);
        float radius = 100.0f;
        
        // Test with different density scenarios
        byte levelLowDensity = TetAdaptiveRefinement.AdaptiveLevelSelector.selectOptimalLevel(center, radius, 5);
        byte levelMediumDensity = TetAdaptiveRefinement.AdaptiveLevelSelector.selectOptimalLevel(center, radius, 100);
        byte levelHighDensity = TetAdaptiveRefinement.AdaptiveLevelSelector.selectOptimalLevel(center, radius, 2000);
        
        assertTrue(levelLowDensity >= 5 && levelLowDensity <= 20, "Level should be in valid range");
        assertTrue(levelMediumDensity >= 5 && levelMediumDensity <= 20, "Level should be in valid range");
        assertTrue(levelHighDensity >= 5 && levelHighDensity <= 20, "Level should be in valid range");
        
        // High density should generally result in finer levels
        assertTrue(levelHighDensity >= levelLowDensity, "High density should use finer or equal level");
        
        // Verify metrics
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        assertTrue(metrics.getMetricsSummary().contains("Level Selections: 3"));
    }

    @Test
    @DisplayName("Test adaptive refinement strategies")
    void testAdaptiveRefinementStrategies() {
        // Create test points with different densities
        var sparsePoints = Arrays.asList(
            new Point3f(100.0f, 100.0f, 100.0f),
            new Point3f(200.0f, 200.0f, 200.0f)
        );
        
        var densePoints = new ArrayList<Point3f>();
        for (int i = 0; i < 200; i++) {
            densePoints.add(new Point3f(100.0f + i, 100.0f + i, 100.0f + i));
        }
        
        Point3f queryCenter = new Point3f(150.0f, 150.0f, 150.0f);
        float queryRadius = 50.0f;
        
        var sparseLevels = TetAdaptiveRefinement.AdaptiveLevelSelector.adaptiveRefinement(sparsePoints, queryCenter, queryRadius);
        var denseLevels = TetAdaptiveRefinement.AdaptiveLevelSelector.adaptiveRefinement(densePoints, queryCenter, queryRadius);
        
        assertNotNull(sparseLevels, "Sparse levels should not be null");
        assertNotNull(denseLevels, "Dense levels should not be null");
        assertFalse(sparseLevels.isEmpty(), "Should suggest refinement levels");
        assertFalse(denseLevels.isEmpty(), "Should suggest refinement levels");
        
        // Dense areas should generally use finer levels
        double sparseAvg = sparseLevels.stream().mapToInt(Byte::intValue).average().orElse(0);
        double denseAvg = denseLevels.stream().mapToInt(Byte::intValue).average().orElse(0);
        assertTrue(denseAvg >= sparseAvg, "Dense areas should use finer or equal average level");
    }

    @Test
    @DisplayName("Test error-driven refinement")
    void testErrorDrivenRefinement() {
        // Create test tetrahedron
        var tet = new Tet(1000, 1000, 1000, (byte) 5, (byte) 2);
        
        // Test refinement decision with high error
        boolean shouldRefineHighError = TetAdaptiveRefinement.ErrorDrivenRefinement.shouldRefine(tet, 10.0, 1.0);
        assertTrue(shouldRefineHighError, "Should refine when error exceeds threshold");
        
        // Test refinement decision with low error
        boolean shouldRefineLowError = TetAdaptiveRefinement.ErrorDrivenRefinement.shouldRefine(tet, 0.1, 1.0);
        assertFalse(shouldRefineLowError, "Should not refine when error is below threshold");
        
        // Test refinement at maximum level
        var maxLevelTet = new Tet(1000, 1000, 1000, (byte) 20, (byte) 2);
        boolean shouldRefineMaxLevel = TetAdaptiveRefinement.ErrorDrivenRefinement.shouldRefine(maxLevelTet, 10.0, 1.0);
        assertFalse(shouldRefineMaxLevel, "Should not refine at very high levels even with high error");
        
        // Verify metrics
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        assertTrue(metrics.getMetricsSummary().contains("Error Evaluations: 3"));
    }

    @Test
    @DisplayName("Test coordinate correction")
    void testCoordinateCorrection() {
        // Test correction of negative coordinates
        Point3i[] invalidVertices = {
            new Point3i(-10, 5, 10),
            new Point3i(100, -5, 20),
            new Point3i(50, 100, -10),
            new Point3i(75, 75, 75)
        };
        
        var corrected = TetErrorCorrection.CoordinateCorrection.validateAndCorrect(invalidVertices);
        
        assertEquals(4, corrected.length, "Should return 4 corrected vertices");
        for (Point3i vertex : corrected) {
            assertTrue(vertex.x >= 0, "X coordinate should be non-negative");
            assertTrue(vertex.y >= 0, "Y coordinate should be non-negative");
            assertTrue(vertex.z >= 0, "Z coordinate should be non-negative");
        }
        
        // Test with invalid number of vertices
        Point3i[] tooFewVertices = {new Point3i(0, 0, 0), new Point3i(1, 0, 0)};
        assertThrows(IllegalArgumentException.class, () -> 
            TetErrorCorrection.CoordinateCorrection.validateAndCorrect(tooFewVertices),
            "Should reject wrong number of vertices");
        
        // Verify metrics
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        String summary = metrics.getMetricsSummary();
        assertTrue(summary.contains("Coordinate Corrections: 2"), "Should record 2 coordinate corrections (valid call + exception call). Actual: " + summary);
    }

    @Test
    @DisplayName("Test numerical stability")
    void testNumericalStability() {
        // Create tetrahedron for barycentric coordinate test
        Point3i[] vertices = {
            new Point3i(0, 0, 0),
            new Point3i(1000, 0, 0),
            new Point3i(500, 1000, 0),
            new Point3i(500, 500, 1000)
        };
        
        // Test point at centroid
        Point3f point = new Point3f(500.0f, 375.0f, 250.0f);
        float[] barycentrics = TetErrorCorrection.NumericalStability.computeStableBarycentrics(vertices, point);
        
        assertEquals(4, barycentrics.length, "Should return 4 barycentric coordinates");
        
        // Barycentric coordinates should sum to approximately 1
        float sum = 0;
        for (float coord : barycentrics) {
            assertTrue(coord >= -0.1f, "Barycentric coordinates should be approximately non-negative");
            sum += coord;
        }
        assertEquals(1.0f, sum, 0.1f, "Barycentric coordinates should sum to 1");
        
        // Verify metrics
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        assertTrue(metrics.getMetricsSummary().contains("Numerical Stabilizations: 1"));
    }

    @Test
    @DisplayName("Test performance profiling")
    void testPerformanceProfiler() {
        var profiler = TetAlgorithmOptimizer.getGlobalProfiler();
        
        // Profile a simple operation
        String result = profiler.profileOperation("test_operation", () -> {
            // Simulate some work
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "test_result";
        });
        
        assertEquals("test_result", result, "Profiled operation should return correct result");
        
        // Get performance report
        String report = profiler.getPerformanceReport();
        assertNotNull(report, "Performance report should not be null");
        assertTrue(report.contains("test_operation"), "Report should contain profiled operation");
        assertTrue(report.contains("Count: 1"), "Report should show operation count");
    }

    @Test
    @DisplayName("Test algorithm metrics collection")
    void testAlgorithmMetrics() {
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        
        // Trigger various operations to generate metrics
        TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(100, 200, 300, (byte) 8, (byte) 1);
        TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(42L);
        
        Point3i[] vertices = {
            new Point3i(0, 0, 0), new Point3i(100, 0, 0), 
            new Point3i(50, 100, 0), new Point3i(50, 50, 100)
        };
        TetGeometricAlgorithms.VolumeComputation.computeVolume(vertices);
        TetGeometricAlgorithms.ContainmentOptimization.containsPoint(vertices, new Point3f(25, 25, 25));
        
        // Verify metrics summary
        String summary = metrics.getMetricsSummary();
        assertNotNull(summary, "Metrics summary should not be null");
        assertTrue(summary.contains("SFC Encodings: 1"), "Should record SFC encoding");
        assertTrue(summary.contains("SFC Decodings: 1"), "Should record SFC decoding");
        assertTrue(summary.contains("Volume Computations: 1"), "Should record volume computation");
        assertTrue(summary.contains("Containment Tests: 1"), "Should record containment test");
        
        // Test metrics reset
        metrics.reset();
        String resetSummary = metrics.getMetricsSummary();
        assertTrue(resetSummary.contains("SFC Encodings: 0"), "Should reset SFC encodings");
        assertTrue(resetSummary.contains("Volume Computations: 0"), "Should reset volume computations");
    }

    @Test
    @DisplayName("Test optimized execution wrapper")
    void testOptimizedExecution() {
        // Test optimized execution wrapper
        String result = TetAlgorithmOptimizer.executeOptimized("wrapper_test", () -> {
            // Simulate tetrahedral operation
            return "optimized_result";
        });
        
        assertEquals("optimized_result", result, "Optimized execution should return correct result");
        
        // Verify it was profiled
        String report = TetAlgorithmOptimizer.getGlobalProfiler().getPerformanceReport();
        assertTrue(report.contains("wrapper_test"), "Operation should be profiled");
    }

    @Test
    @DisplayName("Test cache management")
    void testCacheManagement() {
        // Generate some cached data
        TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(123L);
        TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(456L);
        
        // Verify caches have data by checking cache hit rate
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        
        // Access same data again to generate cache hits
        TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(123L);
        assertTrue(metrics.getCacheHitRate() > 0.0, "Should have cache hits");
        
        // Clear caches
        TetAlgorithmOptimizer.clearAllCaches();
        
        // Operations should still work after cache clear
        var result = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(789L);
        assertNotNull(result, "Operations should work after cache clear");
    }

    @Test
    @DisplayName("Test comprehensive optimization summary")
    void testOptimizationSummary() {
        // Generate some activity across different components
        TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(500, 600, 700, (byte) 10, (byte) 4);
        
        Point3i[] vertices = {
            new Point3i(100, 100, 100), new Point3i(200, 100, 100),
            new Point3i(150, 200, 100), new Point3i(150, 150, 200)
        };
        TetGeometricAlgorithms.VolumeComputation.computeVolume(vertices);
        
        TetAlgorithmOptimizer.executeOptimized("summary_test", () -> "test");
        
        // Get comprehensive summary
        String summary = TetAlgorithmOptimizer.getOptimizationSummary();
        
        assertNotNull(summary, "Optimization summary should not be null");
        assertTrue(summary.contains("Tetrahedral Algorithm Optimization Summary"), "Should have proper title");
        assertTrue(summary.contains("TetAlgorithm Metrics"), "Should include algorithm metrics");
        assertTrue(summary.contains("Performance Report"), "Should include performance report");
        assertTrue(summary.contains("SFC Encodings"), "Should include SFC metrics");
        assertTrue(summary.contains("Volume Computations"), "Should include geometric metrics");
    }

    @Test
    @DisplayName("Test edge cases and error handling")
    void testEdgeCasesAndErrorHandling() {
        // Test SFC operations with edge case indices
        var result1 = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(0L);
        assertTrue(result1.valid(), "Index 0 should be valid");
        
        var result2 = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(-1L);
        assertFalse(result2.valid(), "Negative index should be invalid");
        
        // Test geometric operations with degenerate cases
        Point3i[] degenerateVertices = {
            new Point3i(0, 0, 0), new Point3i(0, 0, 0),
            new Point3i(0, 0, 0), new Point3i(0, 0, 0)
        };
        
        // Should handle degenerate tetrahedron gracefully
        double volume = TetGeometricAlgorithms.VolumeComputation.computeVolume(degenerateVertices);
        assertEquals(0.0, volume, 1e-10, "Degenerate tetrahedron should have zero volume");
        
        // Test containment with degenerate tetrahedron
        boolean contains = TetGeometricAlgorithms.ContainmentOptimization.containsPoint(
            degenerateVertices, new Point3f(1, 1, 1));
        assertFalse(contains, "Degenerate tetrahedron should not contain points");
    }

    @Test
    @DisplayName("Test algorithm integration and consistency")
    void testAlgorithmIntegration() {
        // Test that all algorithm components work together consistently
        // Create a test tetrahedron that's guaranteed to have positive volume
        Point3i[] testVertices = {
            new Point3i(100, 100, 100),
            new Point3i(200, 100, 100),
            new Point3i(150, 200, 100),
            new Point3i(150, 150, 200)
        };
        
        // Verify this tetrahedron has positive volume
        double testVolume = TetGeometricAlgorithms.VolumeComputation.computeVolume(testVertices);
        assertTrue(testVolume > 0, "Test tetrahedron should have positive volume");
        
        // Test SFC round-trip with a simple case
        long knownValidIndex = 0L; // Use index 0 for basic testing
        var decoded = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.decodeOptimized(knownValidIndex);
        assertTrue(decoded.valid(), "Index 0 should decode successfully");
        
        // Test round-trip consistency: decode -> encode -> decode
        long reencoded = TetSFCAlgorithmImprovements.OptimizedSFCEncoder.encodeOptimized(
            decoded.x(), decoded.y(), decoded.z(), decoded.level(), decoded.type());
        assertEquals(knownValidIndex, reencoded, "Round-trip should preserve SFC index");
        
        // Test geometric operations with our known good tetrahedron
        Point3f centroid = new Point3f(
            (testVertices[0].x + testVertices[1].x + testVertices[2].x + testVertices[3].x) / 4.0f,
            (testVertices[0].y + testVertices[1].y + testVertices[2].y + testVertices[3].y) / 4.0f,
            (testVertices[0].z + testVertices[1].z + testVertices[2].z + testVertices[3].z) / 4.0f
        );
        
        boolean containsCentroid = TetGeometricAlgorithms.ContainmentOptimization.containsPoint(testVertices, centroid);
        assertTrue(containsCentroid, "Tetrahedron should contain its centroid");
        
        // Verify all metrics were recorded
        var metrics = TetAlgorithmOptimizer.getGlobalMetrics();
        assertTrue(metrics.getCacheHitRate() >= 0.0, "Cache hit rate should be valid");
        // Check that we have recorded at least one operation
        String summary = metrics.getMetricsSummary();
        assertTrue(summary.contains("SFC Decodings: ") && 
                  !summary.contains("SFC Decodings: 0"), 
                  "Should have recorded SFC operations");
    }
}