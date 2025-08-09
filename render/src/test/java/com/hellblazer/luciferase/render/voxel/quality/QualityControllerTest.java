package com.hellblazer.luciferase.render.voxel.quality;

import com.hellblazer.luciferase.render.voxel.quality.QualityController.QualityMetrics;
import com.hellblazer.luciferase.render.voxel.quality.QualityController.VoxelData;
import com.hellblazer.luciferase.render.voxel.quality.QualityController.SubdivisionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

/**
 * Tests for the quality-driven subdivision controller.
 */
public class QualityControllerTest {
    
    private QualityController controller;
    private QualityMetrics metrics;
    
    @BeforeEach
    void setUp() {
        metrics = QualityMetrics.mediumQuality();
        controller = new QualityController(metrics);
    }
    
    @Test
    void testNoSubdivisionForEmptyVoxel() {
        var voxel = new VoxelData(1.0f);
        var result = controller.analyzeQuality(voxel);
        
        assertFalse(result.needsSubdivision);
        assertEquals(SubdivisionReason.NONE, result.reason);
    }
    
    @Test
    void testColorDeviationTrigger() {
        var voxel = new VoxelData(1.0f);
        
        // Add colors with large deviation
        voxel.addColorSample(new Color3f(0.0f, 0.0f, 0.0f)); // Black
        voxel.addColorSample(new Color3f(1.0f, 1.0f, 1.0f)); // White
        
        var result = controller.analyzeQuality(voxel);
        
        assertTrue(result.needsSubdivision);
        assertEquals(SubdivisionReason.COLOR_DEVIATION, result.reason);
        assertTrue(result.errorValue > metrics.colorDeviation);
    }
    
    @Test
    void testColorDeviationWithinThreshold() {
        var voxel = new VoxelData(1.0f);
        
        // Add colors with small deviation (within threshold)
        voxel.addColorSample(new Color3f(0.5f, 0.5f, 0.5f));
        voxel.addColorSample(new Color3f(0.55f, 0.55f, 0.55f));
        
        var result = controller.analyzeQuality(voxel);
        
        assertFalse(result.needsSubdivision);
        assertEquals(SubdivisionReason.NONE, result.reason);
    }
    
    @Test
    void testNormalVariationTrigger() {
        var voxel = new VoxelData(1.0f);
        
        // Add sample to avoid empty voxel
        voxel.addColorSample(new Color3f(0.5f, 0.5f, 0.5f));
        
        // Add normals with large variation
        voxel.addNormalSample(new Vector3f(1.0f, 0.0f, 0.0f));  // X-axis
        voxel.addNormalSample(new Vector3f(-1.0f, 0.0f, 0.0f)); // -X-axis (180° difference)
        
        var result = controller.analyzeQuality(voxel);
        
        assertTrue(result.needsSubdivision);
        assertEquals(SubdivisionReason.NORMAL_VARIATION, result.reason);
        assertTrue(result.errorValue > metrics.normalDeviation);
    }
    
    @Test
    void testNormalVariationWithinThreshold() {
        var voxel = new VoxelData(1.0f);
        
        // Add sample to avoid empty voxel
        voxel.addColorSample(new Color3f(0.5f, 0.5f, 0.5f));
        
        // Add normals with small variation
        voxel.addNormalSample(new Vector3f(1.0f, 0.0f, 0.0f));
        voxel.addNormalSample(new Vector3f(0.9f, 0.1f, 0.0f)); // Small angle difference
        
        var result = controller.analyzeQuality(voxel);
        
        // Should not trigger normal variation (color should be fine too)
        assertFalse(result.needsSubdivision);
        assertEquals(SubdivisionReason.NONE, result.reason);
    }
    
    @Test
    void testContourErrorTrigger() {
        var voxel = new VoxelData(1.0f);
        
        // Add sample to avoid empty voxel
        voxel.addColorSample(new Color3f(0.5f, 0.5f, 0.5f));
        
        // Set large contour error
        voxel.setContourError(metrics.contourDeviation * 2.0f);
        
        var result = controller.analyzeQuality(voxel);
        
        assertTrue(result.needsSubdivision);
        assertEquals(SubdivisionReason.CONTOUR_ERROR, result.reason);
        assertEquals(metrics.contourDeviation * 2.0f, result.errorValue, 0.001f);
    }
    
    @Test
    void testMinimumSizeConstraint() {
        var metrics = new QualityMetrics(0.01f, 0.01f, 0.01f);
        metrics.minimumVoxelSize = 0.5f;
        var controller = new QualityController(metrics);
        
        var voxel = new VoxelData(0.1f); // Below minimum size
        
        // Add samples that would normally trigger subdivision
        voxel.addColorSample(new Color3f(0.0f, 0.0f, 0.0f));
        voxel.addColorSample(new Color3f(1.0f, 1.0f, 1.0f));
        
        var result = controller.analyzeQuality(voxel);
        
        assertFalse(result.needsSubdivision);
        assertEquals(SubdivisionReason.MINIMUM_SIZE_REACHED, result.reason);
    }
    
    @Test
    void testQualityLevels() {
        var highQuality = QualityMetrics.highQuality();
        var mediumQuality = QualityMetrics.mediumQuality();
        var lowQuality = QualityMetrics.lowQuality();
        
        // High quality should have stricter thresholds
        assertTrue(highQuality.colorDeviation < mediumQuality.colorDeviation);
        assertTrue(highQuality.normalDeviation < mediumQuality.normalDeviation);
        assertTrue(highQuality.contourDeviation < mediumQuality.contourDeviation);
        
        // Medium quality should be stricter than low quality
        assertTrue(mediumQuality.colorDeviation < lowQuality.colorDeviation);
        assertTrue(mediumQuality.normalDeviation < lowQuality.normalDeviation);
        assertTrue(mediumQuality.contourDeviation < lowQuality.contourDeviation);
    }
    
    @Test
    void testSubdivisionDepthSuggestion() {
        var voxel = new VoxelData(1.0f);
        voxel.addColorSample(new Color3f(0.5f, 0.5f, 0.5f));
        
        // Test no subdivision
        assertEquals(0, controller.suggestSubdivisionDepth(voxel));
        
        // Test moderate error (2x threshold)
        voxel.setContourError(metrics.contourDeviation * 2.0f);
        assertEquals(2, controller.suggestSubdivisionDepth(voxel));
        
        // Test severe error (5x threshold)
        voxel.setContourError(metrics.contourDeviation * 5.0f);
        assertEquals(3, controller.suggestSubdivisionDepth(voxel));
    }
    
    @Test
    void testAdaptiveMetricsCreation() {
        // Test low complexity, high quality
        var adaptive1 = QualityController.createAdaptiveMetrics(0.2f, 0.9f);
        
        // Test high complexity, low quality
        var adaptive2 = QualityController.createAdaptiveMetrics(0.8f, 0.3f);
        
        // High complexity, low quality should have more relaxed thresholds
        assertTrue(adaptive2.colorDeviation > adaptive1.colorDeviation);
        assertTrue(adaptive2.normalDeviation > adaptive1.normalDeviation);
        assertTrue(adaptive2.contourDeviation > adaptive1.contourDeviation);
    }
    
    @Test
    void testVoxelDataColorRange() {
        var voxel = new VoxelData(1.0f);
        
        // No samples should give zero range
        assertEquals(0.0f, voxel.getColorRange(), 0.001f);
        
        // Single sample should give zero range
        voxel.addColorSample(new Color3f(0.5f, 0.3f, 0.7f));
        assertEquals(0.0f, voxel.getColorRange(), 0.001f);
        
        // Two different colors should give non-zero range
        voxel.addColorSample(new Color3f(0.8f, 0.1f, 0.9f));
        assertTrue(voxel.getColorRange() > 0.0f);
        
        // Range should be the maximum difference across all channels
        // First color: (0.5f, 0.3f, 0.7f), Second: (0.8f, 0.1f, 0.9f)
        // Ranges: R: 0.8-0.5=0.3, G: 0.3-0.1=0.2, B: 0.9-0.7=0.2
        // Max range is 0.3 in red channel
        assertEquals(0.3f, voxel.getColorRange(), 0.001f);
    }
    
    @Test
    void testVoxelDataNormalSpread() {
        var voxel = new VoxelData(1.0f);
        
        // No samples should give zero spread
        assertEquals(0.0f, voxel.getNormalSpread(), 0.001f);
        
        // Single sample should give zero spread
        voxel.addNormalSample(new Vector3f(1.0f, 0.0f, 0.0f));
        assertEquals(0.0f, voxel.getNormalSpread(), 0.001f);
        
        // Two identical normals should give zero spread
        voxel.addNormalSample(new Vector3f(1.0f, 0.0f, 0.0f));
        assertEquals(0.0f, voxel.getNormalSpread(), 0.001f);
        
        // Two perpendicular normals should give 90° spread
        var voxel2 = new VoxelData(1.0f);
        voxel2.addNormalSample(new Vector3f(1.0f, 0.0f, 0.0f));
        voxel2.addNormalSample(new Vector3f(0.0f, 1.0f, 0.0f));
        assertEquals(Math.PI / 2.0f, voxel2.getNormalSpread(), 0.01f);
    }
    
    @Test
    void testQualityAnalysisResultString() {
        var result = new QualityController.QualityAnalysisResult(
            true, SubdivisionReason.COLOR_DEVIATION, 0.15f, 0.1f);
        
        var str = result.toString();
        assertTrue(str.contains("subdivision=true"));
        assertTrue(str.contains("COLOR_DEVIATION"));
        assertTrue(str.contains("error=0.1500"));
        assertTrue(str.contains("threshold=0.1000"));
    }
}