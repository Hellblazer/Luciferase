package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.app.ESVOApplication;
import com.hellblazer.luciferase.esvo.core.ESVOContext;
import com.hellblazer.luciferase.esvo.validation.ESVOReferenceComparator.ESVORenderResult;
import com.hellblazer.luciferase.esvo.validation.ESVOReferenceComparator.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.validation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8: Validation tests for ESVO implementation
 */
@DisplayName("ESVO Phase 8: Validation System Tests")
public class ESVOPhase8Tests {

    private ESVOQualityValidator qualityValidator;
    private ESVOPerformanceBenchmark performanceBenchmark;
    private ESVOReferenceComparator referenceComparator;
    private ESVOContext context;

    @BeforeEach
    void setUp() {
        qualityValidator = new ESVOQualityValidator();
        performanceBenchmark = new ESVOPerformanceBenchmark();
        referenceComparator = new ESVOReferenceComparator();
        context = new ESVOContext();
    }

    @Test
    @DisplayName("Test PSNR calculation for identical images")
    void testPSNRIdenticalImages() {
        var width = 256;
        var height = 256;
        var buffer = createTestBuffer(width, height, 128);
        
        var psnr = qualityValidator.calculatePSNR(buffer, buffer, width, height);
        assertEquals(Double.POSITIVE_INFINITY, psnr, "PSNR should be infinite for identical images");
    }

    @Test
    @DisplayName("Test PSNR calculation for different images")
    void testPSNRDifferentImages() {
        var width = 256;
        var height = 256;
        var buffer1 = createTestBuffer(width, height, 100);
        var buffer2 = createTestBuffer(width, height, 150);
        
        var psnr = qualityValidator.calculatePSNR(buffer1, buffer2, width, height);
        assertTrue(psnr > 0 && psnr < 100, "PSNR should be in reasonable range");
    }

    @Test
    @DisplayName("Test SSIM calculation")
    void testSSIMCalculation() {
        var width = 256;
        var height = 256;
        var buffer1 = createGradientBuffer(width, height);
        var buffer2 = createGradientBuffer(width, height);
        
        var ssim = qualityValidator.calculateSSIM(buffer1, buffer2, width, height);
        assertEquals(1.0, ssim, 0.001, "SSIM should be 1.0 for identical images");
        
        // Test with different images
        var buffer3 = createTestBuffer(width, height, 255);
        var ssim2 = qualityValidator.calculateSSIM(buffer1, buffer3, width, height);
        assertTrue(ssim2 < 1.0, "SSIM should be less than 1.0 for different images");
    }

    @Test
    @DisplayName("Test MSE calculation")
    void testMSECalculation() {
        var width = 256;
        var height = 256;
        var buffer1 = createTestBuffer(width, height, 100);
        var buffer2 = createTestBuffer(width, height, 100);
        
        var mse = qualityValidator.calculateMSE(buffer1, buffer2, width, height);
        assertEquals(0.0, mse, 0.001, "MSE should be 0 for identical images");
        
        var buffer3 = createTestBuffer(width, height, 200);
        var mse2 = qualityValidator.calculateMSE(buffer1, buffer3, width, height);
        assertTrue(mse2 > 0, "MSE should be positive for different images");
    }

    @Test
    @DisplayName("Test quality validation with thresholds")
    void testQualityValidation() {
        var width = 512;
        var height = 512;
        var rendered = new ESVORenderResult(createGradientBuffer(width, height), width, height, 
                                           new ESVOOctreeData(new HashMap<>(), new HashMap<>(), 5), new ArrayList<>());
        var reference = new ESVORenderResult(createGradientBuffer(width, height), width, height, 
                                            new ESVOOctreeData(new HashMap<>(), new HashMap<>(), 5), new ArrayList<>());
        
        var report = qualityValidator.validateQuality(rendered, reference);
        assertNotNull(report);
        assertTrue(report.passesAllThresholds(), "Identical images should pass all thresholds");
        assertEquals(1.0, report.getOverallQualityScore(), 0.01, "Overall quality should be near perfect");
    }

    @Test
    @DisplayName("Test performance benchmark warmup")
    void testBenchmarkWarmup() {
        var app = createMockApplication();
        
        // Test warmup doesn't crash
        assertDoesNotThrow(() -> performanceBenchmark.runWarmup(app, 5));
    }

    @Test
    @DisplayName("Test frame time measurement")
    void testFrameTimeMeasurement() {
        var app = createMockApplication();
        
        var frameTime = performanceBenchmark.measureFrameTime(app);
        assertTrue(frameTime > 0, "Frame time should be positive");
        assertTrue(frameTime < 1_000_000_000L, "Frame time should be less than 1 second");
    }

    @Test
    @DisplayName("Test throughput calculation")
    void testThroughputCalculation() {
        var frameTimeNs = 16_666_667L; // ~60 FPS
        var rayCount = 1_920_000; // 1920x1000 rays
        
        var throughput = performanceBenchmark.calculateRayThroughput(frameTimeNs, rayCount);
        assertTrue(throughput > 100_000_000, "Should achieve >100M rays/sec at 60 FPS");
    }

    @Test
    @DisplayName("Test performance comparison")
    void testPerformanceComparison() {
        var baseline = new ESVOPerformanceBenchmark.PerformanceReport(
            new ESVOPerformanceBenchmark.FrameStats(60.0, 16.67, 14.0, 18.0, 17.0, 17.5, 2.0),
            new ESVOPerformanceBenchmark.ThroughputStats(115_000_000, 120_000_000, 60_000_000, 65_000_000),
            new ESVOPerformanceBenchmark.MemoryStats(512, 256, 100, 1024, 25.0),
            new ESVOPerformanceBenchmark.SystemStats(45.0, 55.0, 8, 2L * 1024 * 1024 * 1024, 5000),
            new ESVOPerformanceBenchmark.PerformanceAssessment(ESVOPerformanceBenchmark.PerformanceGrade.GOOD, new ArrayList<>(), List.of("Good baseline performance"))
        );
        
        var current = new ESVOPerformanceBenchmark.PerformanceReport(
            new ESVOPerformanceBenchmark.FrameStats(66.7, 15.0, 13.0, 16.0, 15.5, 16.0, 1.5),
            new ESVOPerformanceBenchmark.ThroughputStats(125_000_000, 130_000_000, 65_000_000, 70_000_000),
            new ESVOPerformanceBenchmark.MemoryStats(480, 240, 80, 1024, 23.4),
            new ESVOPerformanceBenchmark.SystemStats(40.0, 50.0, 8, 2L * 1024 * 1024 * 1024, 4500),
            new ESVOPerformanceBenchmark.PerformanceAssessment(ESVOPerformanceBenchmark.PerformanceGrade.EXCELLENT, new ArrayList<>(), List.of("Excellent performance"))
        );
        
        var comparison = performanceBenchmark.comparePerformance(baseline, current);
        assertNotNull(comparison);
        assertTrue(comparison.fpsChangePercent() > 0, "FPS should show improvement");
        assertTrue(comparison.frameTimeChangePercent() < 0, "Frame time should improve (negative change)");
    }

    @Test
    @DisplayName("Test reference comparator with identical data")
    void testReferenceComparatorIdentical() {
        var width = 256;
        var height = 256;
        var buffer = createTestBuffer(width, height, 128);
        var bufferCopy = createTestBuffer(width, height, 128); // Create a separate buffer for reference
        
        var esvoResult = new ESVORenderResult(buffer, width, height, 
                                             new ESVOOctreeData(new HashMap<>(), new HashMap<>(), 5), new ArrayList<>());
        var refResult = new ESVOReferenceComparator.ReferenceResult(bufferCopy, width, height, 
                                                                  new ESVOReferenceComparator.ReferenceOctreeData(new HashMap<>(), new HashMap<>(), 5), 
                                                                  new ArrayList<>());
        
        var comparison = referenceComparator.compareToReference(esvoResult, refResult);
        assertTrue(comparison.getPixelAccuracy() > 0.99, "Should have >99% pixel accuracy");
        assertTrue(comparison.isValid(), "Comparison should be valid");
    }

    @Test
    @DisplayName("Test voxel structure validation")
    void testVoxelStructureValidation() {
        var esvoData = new ESVOOctreeData(new HashMap<>(), new HashMap<>(), 5);
        var refData = new ESVOReferenceComparator.ReferenceOctreeData(new HashMap<>(), new HashMap<>(), 5);
        
        // Add matching voxels
        for (int i = 0; i < 10; i++) {
            var pos = new float[]{i * 0.1f, i * 0.1f, i * 0.1f};
            // Would add voxels if methods were available
        }
        
        var valid = referenceComparator.validateVoxelStructure(esvoData, refData);
        assertTrue(valid, "Empty structures should validate");
    }

    @Test
    @DisplayName("Test traversal path comparison")
    void testTraversalPathComparison() {
        var esvoPath = new ArrayList<ESVOReferenceComparator.TraversalStep>();
        var refPath = new ArrayList<ESVOReferenceComparator.TraversalStep>();
        
        // Add identical steps
        for (int i = 0; i < 10; i++) {
            var step = new ESVOReferenceComparator.TraversalStep(
                new javax.vecmath.Point3f(i * 0.1f, i * 0.1f, i * 0.1f),
                new javax.vecmath.Vector3f(1.0f, 0.0f, 0.0f),
                i,
                i * 0.5f,
                "TEST_STEP"
            );
            esvoPath.add(step);
            refPath.add(step);
        }
        
        var comparison = referenceComparator.compareTraversalPaths(esvoPath, refPath);
        assertEquals(refPath.size(), comparison.getEsvoPathLength(), "Path lengths should match");
        assertEquals(1.0, comparison.getPathSimilarity(), 0.001, "Paths should be identical");
    }

    @Test
    @DisplayName("Test difference map generation")
    void testDifferenceMapGeneration() {
        var width = 256;
        var height = 256;
        var buffer1 = createGradientBuffer(width, height);
        var buffer2 = createTestBuffer(width, height, 200);
        
        var diffMap = referenceComparator.generateDifferenceMap(buffer1, buffer2);
        assertNotNull(diffMap);
        assertEquals(buffer1.capacity(), diffMap.capacity(), "Difference map should have same size");
        
        // Verify difference map has non-zero values
        boolean hasNonZero = false;
        while (diffMap.hasRemaining()) {
            if (diffMap.get() != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Difference map should have non-zero values");
    }

    @Test
    @DisplayName("Test quality report generation")
    void testQualityReportGeneration() {
        var width = 512;
        var height = 512;
        var rendered = new ESVORenderResult(createGradientBuffer(width, height), width, height, 
                                           new ESVOOctreeData(new HashMap<>(), new HashMap<>(), 5), new ArrayList<>());
        var reference = new ESVORenderResult(createTestBuffer(width, height, 150), width, height, 
                                            new ESVOOctreeData(new HashMap<>(), new HashMap<>(), 5), new ArrayList<>());
        
        var report = qualityValidator.validateQuality(rendered, reference);
        var reportString = qualityValidator.generateQualityReport(report);
        
        assertNotNull(reportString);
        assertTrue(reportString.contains("PSNR"), "Report should contain PSNR");
        assertTrue(reportString.contains("SSIM"), "Report should contain SSIM");
        assertTrue(reportString.contains("MSE"), "Report should contain MSE");
    }

    // Helper methods
    
    private ByteBuffer createTestBuffer(int width, int height, int value) {
        var buffer = MemoryUtil.memAlloc(width * height * 4);
        for (int i = 0; i < width * height; i++) {
            buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
        }
        buffer.rewind();
        return buffer;
    }
    
    private ByteBuffer createGradientBuffer(int width, int height) {
        var buffer = MemoryUtil.memAlloc(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                var r = (byte) ((x * 255) / width);
                var g = (byte) ((y * 255) / height);
                var b = (byte) (((x + y) * 255) / (width + height));
                buffer.put(r).put(g).put(b).put((byte) 255);
            }
        }
        buffer.rewind();
        return buffer;
    }
    
    private ESVOApplication createMockApplication() {
        var app = new ESVOApplication();
        app.initialize(); // Initialize the application
        return app;
    }
}