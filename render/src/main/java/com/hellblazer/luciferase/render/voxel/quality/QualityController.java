package com.hellblazer.luciferase.render.voxel.quality;

import javax.vecmath.Vector3f;
import javax.vecmath.Color3f;
import javax.vecmath.Point3f;
import java.util.List;
import java.util.ArrayList;

/**
 * Quality-driven subdivision controller for ESVO-style octree building.
 * Determines when voxel nodes need subdivision based on error metrics.
 * 
 * Based on NVIDIA ESVO quality control system that uses color deviation,
 * normal variation, and surface approximation error to drive LOD generation.
 */
public class QualityController {
    
    /**
     * Quality metrics configuration for subdivision decisions.
     */
    public static class QualityMetrics {
        /** Maximum allowable color range in a voxel (0.0-1.0) */
        public float colorDeviation = 0.1f;
        
        /** Maximum allowable normal angle variation in radians */
        public float normalDeviation = 0.2f; // ~11.5 degrees
        
        /** Maximum allowable surface distance error for contours */
        public float contourDeviation = 0.05f;
        
        /** Minimum voxel size to prevent infinite subdivision */
        public float minimumVoxelSize = 0.001f;
        
        public QualityMetrics() {}
        
        public QualityMetrics(float colorDev, float normalDev, float contourDev) {
            this.colorDeviation = colorDev;
            this.normalDeviation = normalDev;
            this.contourDeviation = contourDev;
        }
        
        /**
         * Create high quality settings - more subdivision
         */
        public static QualityMetrics highQuality() {
            return new QualityMetrics(0.05f, 0.1f, 0.025f);
        }
        
        /**
         * Create medium quality settings - balanced
         */
        public static QualityMetrics mediumQuality() {
            return new QualityMetrics(0.1f, 0.2f, 0.05f);
        }
        
        /**
         * Create low quality settings - less subdivision
         */
        public static QualityMetrics lowQuality() {
            return new QualityMetrics(0.2f, 0.4f, 0.1f);
        }
    }
    
    /**
     * Voxel data container for quality analysis.
     */
    public static class VoxelData {
        // Color information
        private Color3f colorMin = new Color3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        private Color3f colorMax = new Color3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        
        // Normal information - track actual extreme vectors
        private Vector3f firstNormal = null;
        private Vector3f mostDifferentNormal = null;
        private float maxNormalSpread = 0.0f;
        
        // Surface approximation
        private boolean hasContour = false;
        private float contourError = 0.0f;
        private ContourExtractor.Contour contour = null;
        
        // Triangle data for contour extraction
        private List<ContourExtractor.Triangle> triangles = new ArrayList<>();
        
        // Spatial information
        private float voxelSize = 1.0f;
        private int sampleCount = 0;
        private int normalSampleCount = 0;
        private ContourExtractor.AABB voxelBounds = null;
        
        public VoxelData(float voxelSize) {
            this.voxelSize = voxelSize;
        }
        
        public VoxelData(float voxelSize, Point3f voxelCenter) {
            this.voxelSize = voxelSize;
            this.voxelBounds = new ContourExtractor.AABB(
                voxelCenter,
                new Vector3f(voxelSize/2, voxelSize/2, voxelSize/2)
            );
        }
        
        /**
         * Add a color sample to the voxel data.
         */
        public void addColorSample(Color3f color) {
            colorMin.x = Math.min(colorMin.x, color.x);
            colorMin.y = Math.min(colorMin.y, color.y);
            colorMin.z = Math.min(colorMin.z, color.z);
            
            colorMax.x = Math.max(colorMax.x, color.x);
            colorMax.y = Math.max(colorMax.y, color.y);
            colorMax.z = Math.max(colorMax.z, color.z);
            
            sampleCount++;
        }
        
        /**
         * Add a normal sample to the voxel data.
         */
        public void addNormalSample(Vector3f normal) {
            // Normalize the input normal
            var normalizedNormal = new Vector3f(normal);
            normalizedNormal.normalize();
            
            if (firstNormal == null) {
                // First normal becomes our reference
                firstNormal = new Vector3f(normalizedNormal);
                mostDifferentNormal = new Vector3f(normalizedNormal);
                maxNormalSpread = 0.0f;
            } else {
                // Calculate angle between this normal and the first normal
                float dotProduct = firstNormal.dot(normalizedNormal);
                dotProduct = Math.max(-1.0f, Math.min(1.0f, dotProduct)); // Clamp
                float angle = (float) Math.acos(dotProduct); // Get full angle between 0 and π
                
                // If this is the most different normal so far, track it
                if (angle > maxNormalSpread) {
                    maxNormalSpread = angle;
                    mostDifferentNormal = new Vector3f(normalizedNormal);
                }
            }
            
            normalSampleCount++;
        }
        
        /**
         * Set contour error for surface approximation quality.
         */
        public void setContourError(float error) {
            this.hasContour = true;
            this.contourError = error;
        }
        
        /**
         * Add a triangle for contour extraction.
         */
        public void addTriangle(Point3f v0, Point3f v1, Point3f v2) {
            triangles.add(new ContourExtractor.Triangle(v0, v1, v2));
        }
        
        /**
         * Set the extracted contour.
         */
        public void setContour(ContourExtractor.Contour contour) {
            this.contour = contour;
            this.hasContour = contour != null;
            if (contour != null) {
                // Contour error will be calculated separately if needed
                this.contourError = 0.0f;
            }
        }
        
        /**
         * Get the list of triangles.
         */
        public List<ContourExtractor.Triangle> getTriangles() {
            return new ArrayList<>(triangles);
        }
        
        /**
         * Get the voxel bounds.
         */
        public ContourExtractor.AABB getVoxelBounds() {
            return voxelBounds;
        }
        
        /**
         * Set the voxel bounds.
         */
        public void setVoxelBounds(ContourExtractor.AABB bounds) {
            this.voxelBounds = bounds;
        }
        
        /**
         * Calculate color range (maximum difference across RGB channels).
         */
        public float getColorRange() {
            if (sampleCount == 0) return 0.0f;
            if (sampleCount == 1) return 0.0f; // Single sample has no range
            
            var colorDiff = new Color3f();
            colorDiff.sub(colorMax, colorMin);
            
            return Math.max(Math.max(colorDiff.x, colorDiff.y), colorDiff.z);
        }
        
        /**
         * Calculate normal spread (angular difference between min/max normals).
         */
        public float getNormalSpread() {
            if (normalSampleCount == 0) return 0.0f;
            if (normalSampleCount == 1) return 0.0f; // Single sample has no spread
            
            // Return the maximum spread we've found
            return maxNormalSpread;
        }
        
        public float getVoxelSize() { return voxelSize; }
        public int getSampleCount() { return sampleCount; }
        public int getNormalSampleCount() { return normalSampleCount; }
        public boolean hasContour() { return hasContour; }
        public float getContourError() { return contourError; }
        public ContourExtractor.Contour getContour() { return contour; }
        
        public Color3f getColorMin() { return new Color3f(colorMin); }
        public Color3f getColorMax() { return new Color3f(colorMax); }
        public Vector3f getNormalMin() { 
            return firstNormal != null ? new Vector3f(firstNormal) : new Vector3f(0, 0, 0); 
        }
        public Vector3f getNormalMax() { 
            return mostDifferentNormal != null ? new Vector3f(mostDifferentNormal) : new Vector3f(0, 0, 0); 
        }
    }
    
    /**
     * Subdivision decision flags.
     */
    public enum SubdivisionReason {
        NONE,
        COLOR_DEVIATION,
        NORMAL_VARIATION, 
        CONTOUR_ERROR,
        MINIMUM_SIZE_REACHED
    }
    
    /**
     * Result of quality analysis.
     */
    public static class QualityAnalysisResult {
        public final boolean needsSubdivision;
        public final SubdivisionReason reason;
        public final float errorValue;
        public final float threshold;
        
        public QualityAnalysisResult(boolean needs, SubdivisionReason reason, 
                                   float error, float threshold) {
            this.needsSubdivision = needs;
            this.reason = reason;
            this.errorValue = error;
            this.threshold = threshold;
        }
        
        @Override
        public String toString() {
            return String.format("QualityAnalysis{subdivision=%s, reason=%s, error=%.4f, threshold=%.4f}",
                    needsSubdivision, reason, errorValue, threshold);
        }
    }
    
    private final QualityMetrics metrics;
    private final ContourExtractor contourExtractor;
    
    public QualityController(QualityMetrics metrics) {
        this.metrics = metrics;
        this.contourExtractor = new ContourExtractor();
    }
    
    public QualityController() {
        this(QualityMetrics.mediumQuality());
    }
    
    /**
     * Extract and analyze contour for a voxel.
     * Updates the voxel data with contour information and error metrics.
     */
    public void extractAndAnalyzeContour(VoxelData voxel) {
        if (voxel.getTriangles().isEmpty() || voxel.getVoxelBounds() == null) {
            return;
        }
        
        // Extract contour
        var contour = contourExtractor.extractContour(
            voxel.getTriangles(), 
            voxel.getVoxelBounds()
        );
        
        if (contour != null) {
            // Calculate error
            float error = contourExtractor.evaluateContourError(
                contour, 
                voxel.getTriangles()
            );
            
            voxel.setContour(contour);
            voxel.setContourError(error);
        }
    }
    
    /**
     * Analyze voxel data and determine if subdivision is needed.
     * 
     * @param voxel The voxel data to analyze
     * @return Quality analysis result with subdivision decision
     */
    public QualityAnalysisResult analyzeQuality(VoxelData voxel) {
        // Check minimum size constraint first
        if (voxel.getVoxelSize() <= metrics.minimumVoxelSize) {
            return new QualityAnalysisResult(false, SubdivisionReason.MINIMUM_SIZE_REACHED,
                    voxel.getVoxelSize(), metrics.minimumVoxelSize);
        }
        
        // If no samples, no subdivision needed
        if (voxel.getSampleCount() == 0) {
            return new QualityAnalysisResult(false, SubdivisionReason.NONE, 0.0f, 0.0f);
        }
        
        // Test color deviation
        float colorRange = voxel.getColorRange();
        if (colorRange > metrics.colorDeviation) {
            return new QualityAnalysisResult(true, SubdivisionReason.COLOR_DEVIATION,
                    colorRange, metrics.colorDeviation);
        }
        
        // Test normal variation
        float normalSpread = voxel.getNormalSpread();
        if (normalSpread > metrics.normalDeviation) {
            return new QualityAnalysisResult(true, SubdivisionReason.NORMAL_VARIATION,
                    normalSpread, metrics.normalDeviation);
        }
        
        // Test contour approximation error
        if (voxel.hasContour() && voxel.getContourError() > metrics.contourDeviation) {
            return new QualityAnalysisResult(true, SubdivisionReason.CONTOUR_ERROR,
                    voxel.getContourError(), metrics.contourDeviation);
        }
        
        // All tests passed - no subdivision needed
        return new QualityAnalysisResult(false, SubdivisionReason.NONE, 0.0f, 0.0f);
    }
    
    /**
     * Simple boolean check for subdivision need.
     */
    public boolean needsSubdivision(VoxelData voxel) {
        return analyzeQuality(voxel).needsSubdivision;
    }
    
    /**
     * Get quality metrics configuration.
     */
    public QualityMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Calculate subdivision depth based on error ratios.
     * Higher error ratios suggest deeper subdivision may be beneficial.
     */
    public int suggestSubdivisionDepth(VoxelData voxel) {
        var analysis = analyzeQuality(voxel);
        
        if (!analysis.needsSubdivision) {
            return 0;
        }
        
        // Calculate error ratio (how much we exceed the threshold)
        float errorRatio = analysis.errorValue / analysis.threshold;
        
        // Suggest depth based on error severity
        if (errorRatio >= 4.0f) return 3; // Deep subdivision for severe errors
        if (errorRatio >= 2.0f) return 2; // Medium subdivision
        return 1; // Shallow subdivision
    }
    
    /**
     * Create adaptive quality metrics based on scene characteristics.
     */
    public static QualityMetrics createAdaptiveMetrics(float sceneComplexity, 
                                                       float targetQuality) {
        // Adjust thresholds based on scene complexity and target quality
        float colorDev = 0.1f * (2.0f - targetQuality) * (1.0f + sceneComplexity * 0.5f);
        float normalDev = 0.2f * (2.0f - targetQuality) * (1.0f + sceneComplexity * 0.3f);
        float contourDev = 0.05f * (2.0f - targetQuality) * (1.0f + sceneComplexity * 0.4f);
        
        // Clamp to reasonable ranges
        colorDev = Math.max(0.01f, Math.min(0.5f, colorDev));
        normalDev = Math.max(0.05f, Math.min(1.0f, normalDev));
        contourDev = Math.max(0.01f, Math.min(0.2f, contourDev));
        
        return new QualityMetrics(colorDev, normalDev, contourDev);
    }
}