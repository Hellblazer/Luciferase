/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 * 
 * This code is licensed under the GNU Affero General Public License v3.0 
 * (AGPLv3). See the LICENSE file in the root directory for license terms.
 */

package com.hellblazer.luciferase.esvo.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * ESVOReferenceComparator provides comprehensive functionality for comparing ESVO
 * output against reference implementations or ground truth data. It supports
 * pixel-by-pixel comparisons, voxel data structure validation, ray traversal
 * path verification, and detailed difference reporting.
 * 
 * This class is essential for validating ESVO implementation correctness and
 * identifying regressions during development.
 * 
 * @author hal.hildebrand
 */
public class ESVOReferenceComparator {
    private static final Logger log = LoggerFactory.getLogger(ESVOReferenceComparator.class);
    
    // Tolerance constants for various comparison types
    public static final double PIXEL_TOLERANCE = 0.01;           // 1% difference
    public static final double VOXEL_POSITION_TOLERANCE = 0.001; // 0.1% position tolerance
    public static final double PATH_LENGTH_TOLERANCE = 0.05;     // 5% path length tolerance
    public static final int MAX_PATH_LENGTH_DIFF = 10;           // Maximum absolute path length difference
    
    /**
     * Result of comparing ESVO output against reference implementation
     */
    public static class ComparisonResult {
        private final boolean isValid;
        private final double pixelDifference;
        private final int differingPixels;
        private final int totalPixels;
        private final boolean voxelStructureValid;
        private final TraversalComparison traversalComparison;
        private final List<String> issues;
        private final Map<String, Object> metrics;
        
        public ComparisonResult(boolean isValid, double pixelDifference, int differingPixels, 
                              int totalPixels, boolean voxelStructureValid, 
                              TraversalComparison traversalComparison, List<String> issues,
                              Map<String, Object> metrics) {
            this.isValid = isValid;
            this.pixelDifference = pixelDifference;
            this.differingPixels = differingPixels;
            this.totalPixels = totalPixels;
            this.voxelStructureValid = voxelStructureValid;
            this.traversalComparison = traversalComparison;
            this.issues = new ArrayList<>(issues);
            this.metrics = new HashMap<>(metrics);
        }
        
        public boolean isValid() { return isValid; }
        public double getPixelDifference() { return pixelDifference; }
        public int getDifferingPixels() { return differingPixels; }
        public int getTotalPixels() { return totalPixels; }
        public boolean isVoxelStructureValid() { return voxelStructureValid; }
        public TraversalComparison getTraversalComparison() { return traversalComparison; }
        public List<String> getIssues() { return new ArrayList<>(issues); }
        public Map<String, Object> getMetrics() { return new HashMap<>(metrics); }
        
        public double getPixelAccuracy() {
            if (totalPixels == 0) return 1.0;
            return 1.0 - ((double) differingPixels / totalPixels);
        }
        
        @Override
        public String toString() {
            return String.format("ComparisonResult{valid=%s, pixelDiff=%.4f, accuracy=%.2f%%, " +
                               "voxelValid=%s, issues=%d}", 
                               isValid, pixelDifference, getPixelAccuracy() * 100, 
                               voxelStructureValid, issues.size());
        }
    }
    
    /**
     * Result of comparing ray traversal paths
     */
    public static class TraversalComparison {
        private final boolean pathsMatch;
        private final double pathSimilarity;
        private final int esvoPathLength;
        private final int referencePathLength;
        private final List<PathDifference> differences;
        private final Map<String, Double> pathMetrics;
        
        public TraversalComparison(boolean pathsMatch, double pathSimilarity, 
                                 int esvoPathLength, int referencePathLength,
                                 List<PathDifference> differences, Map<String, Double> pathMetrics) {
            this.pathsMatch = pathsMatch;
            this.pathSimilarity = pathSimilarity;
            this.esvoPathLength = esvoPathLength;
            this.referencePathLength = referencePathLength;
            this.differences = new ArrayList<>(differences);
            this.pathMetrics = new HashMap<>(pathMetrics);
        }
        
        public boolean pathsMatch() { return pathsMatch; }
        public double getPathSimilarity() { return pathSimilarity; }
        public int getEsvoPathLength() { return esvoPathLength; }
        public int getReferencePathLength() { return referencePathLength; }
        public List<PathDifference> getDifferences() { return new ArrayList<>(differences); }
        public Map<String, Double> getPathMetrics() { return new HashMap<>(pathMetrics); }
        
        @Override
        public String toString() {
            return String.format("TraversalComparison{match=%s, similarity=%.3f, lengths=[%d,%d], diffs=%d}",
                               pathsMatch, pathSimilarity, esvoPathLength, referencePathLength, differences.size());
        }
    }
    
    /**
     * Represents a difference in traversal paths
     */
    public static class PathDifference {
        private final int stepIndex;
        private final TraversalStep esvoStep;
        private final TraversalStep referenceStep;
        private final String differenceType;
        private final double magnitude;
        
        public PathDifference(int stepIndex, TraversalStep esvoStep, TraversalStep referenceStep,
                            String differenceType, double magnitude) {
            this.stepIndex = stepIndex;
            this.esvoStep = esvoStep;
            this.referenceStep = referenceStep;
            this.differenceType = differenceType;
            this.magnitude = magnitude;
        }
        
        public int getStepIndex() { return stepIndex; }
        public TraversalStep getEsvoStep() { return esvoStep; }
        public TraversalStep getReferenceStep() { return referenceStep; }
        public String getDifferenceType() { return differenceType; }
        public double getMagnitude() { return magnitude; }
        
        @Override
        public String toString() {
            return String.format("PathDifference{step=%d, type=%s, magnitude=%.4f}", 
                               stepIndex, differenceType, magnitude);
        }
    }
    
    /**
     * Represents a step in ray traversal
     */
    public static class TraversalStep {
        private final Point3f position;
        private final Vector3f direction;
        private final int voxelId;
        private final float t;
        private final String stepType;
        
        public TraversalStep(Point3f position, Vector3f direction, int voxelId, float t, String stepType) {
            this.position = new Point3f(position);
            this.direction = new Vector3f(direction);
            this.voxelId = voxelId;
            this.t = t;
            this.stepType = stepType;
        }
        
        public Point3f getPosition() { return new Point3f(position); }
        public Vector3f getDirection() { return new Vector3f(direction); }
        public int getVoxelId() { return voxelId; }
        public float getT() { return t; }
        public String getStepType() { return stepType; }
        
        public double distanceTo(TraversalStep other) {
            return position.distance(other.position);
        }
        
        @Override
        public String toString() {
            return String.format("TraversalStep{pos=(%.3f,%.3f,%.3f), voxel=%d, t=%.3f, type=%s}",
                               position.x, position.y, position.z, voxelId, t, stepType);
        }
    }
    
    /**
     * Placeholder for ESVO render result
     */
    public static class ESVORenderResult {
        private final ByteBuffer pixelData;
        private final int width;
        private final int height;
        private final ESVOOctreeData octreeData;
        private final List<TraversalStep> traversalPath;
        
        public ESVORenderResult(ByteBuffer pixelData, int width, int height,
                              ESVOOctreeData octreeData, List<TraversalStep> traversalPath) {
            this.pixelData = pixelData;
            this.width = width;
            this.height = height;
            this.octreeData = octreeData;
            this.traversalPath = new ArrayList<>(traversalPath);
        }
        
        public ByteBuffer getPixelData() { return pixelData; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public ESVOOctreeData getOctreeData() { return octreeData; }
        public List<TraversalStep> getTraversalPath() { return new ArrayList<>(traversalPath); }
    }
    
    /**
     * Placeholder for reference implementation result
     */
    public static class ReferenceResult {
        private final ByteBuffer pixelData;
        private final int width;
        private final int height;
        private final ReferenceOctreeData octreeData;
        private final List<TraversalStep> traversalPath;
        
        public ReferenceResult(ByteBuffer pixelData, int width, int height,
                             ReferenceOctreeData octreeData, List<TraversalStep> traversalPath) {
            this.pixelData = pixelData;
            this.width = width;
            this.height = height;
            this.octreeData = octreeData;
            this.traversalPath = new ArrayList<>(traversalPath);
        }
        
        public ByteBuffer getPixelData() { return pixelData; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public ReferenceOctreeData getOctreeData() { return octreeData; }
        public List<TraversalStep> getTraversalPath() { return new ArrayList<>(traversalPath); }
    }
    
    /**
     * Placeholder for ESVO octree data
     */
    public static class ESVOOctreeData {
        private final Map<Integer, Point3f> voxelPositions;
        private final Map<Integer, Float> voxelSizes;
        private final int maxDepth;
        
        public ESVOOctreeData(Map<Integer, Point3f> voxelPositions, 
                            Map<Integer, Float> voxelSizes, int maxDepth) {
            this.voxelPositions = new HashMap<>(voxelPositions);
            this.voxelSizes = new HashMap<>(voxelSizes);
            this.maxDepth = maxDepth;
        }
        
        public Map<Integer, Point3f> getVoxelPositions() { return new HashMap<>(voxelPositions); }
        public Map<Integer, Float> getVoxelSizes() { return new HashMap<>(voxelSizes); }
        public int getMaxDepth() { return maxDepth; }
        public int getVoxelCount() { return voxelPositions.size(); }
    }
    
    /**
     * Placeholder for reference octree data
     */
    public static class ReferenceOctreeData {
        private final Map<Integer, Point3f> voxelPositions;
        private final Map<Integer, Float> voxelSizes;
        private final int maxDepth;
        
        public ReferenceOctreeData(Map<Integer, Point3f> voxelPositions, 
                                 Map<Integer, Float> voxelSizes, int maxDepth) {
            this.voxelPositions = new HashMap<>(voxelPositions);
            this.voxelSizes = new HashMap<>(voxelSizes);
            this.maxDepth = maxDepth;
        }
        
        public Map<Integer, Point3f> getVoxelPositions() { return new HashMap<>(voxelPositions); }
        public Map<Integer, Float> getVoxelSizes() { return new HashMap<>(voxelSizes); }
        public int getMaxDepth() { return maxDepth; }
        public int getVoxelCount() { return voxelPositions.size(); }
    }
    
    /**
     * Compare ESVO output against reference implementation
     * 
     * @param esvo The ESVO render result
     * @param reference The reference implementation result
     * @return Comprehensive comparison result
     */
    public ComparisonResult compareToReference(ESVORenderResult esvo, ReferenceResult reference) {
        log.debug("Starting comprehensive ESVO vs reference comparison");
        
        var issues = new ArrayList<String>();
        var metrics = new HashMap<String, Object>();
        
        // Validate input dimensions
        if (esvo.getWidth() != reference.getWidth() || esvo.getHeight() != reference.getHeight()) {
            issues.add(String.format("Dimension mismatch: ESVO(%dx%d) vs Reference(%dx%d)",
                                    esvo.getWidth(), esvo.getHeight(),
                                    reference.getWidth(), reference.getHeight()));
        }
        
        // Pixel comparison
        double pixelDifference = calculatePixelDifference(esvo.getPixelData(), reference.getPixelData());
        int[] pixelStats = countDifferingPixels(esvo.getPixelData(), reference.getPixelData());
        int differingPixels = pixelStats[0];
        int totalPixels = pixelStats[1];
        
        metrics.put("pixelDifference", pixelDifference);
        metrics.put("pixelAccuracy", 1.0 - ((double) differingPixels / totalPixels));
        
        if (pixelDifference > PIXEL_TOLERANCE) {
            issues.add(String.format("Pixel difference %.4f exceeds tolerance %.4f", 
                                    pixelDifference, PIXEL_TOLERANCE));
        }
        
        // Voxel structure validation
        boolean voxelStructureValid = validateVoxelStructure(esvo.getOctreeData(), reference.getOctreeData());
        if (!voxelStructureValid) {
            issues.add("Voxel structure validation failed");
        }
        
        // Traversal path comparison
        var traversalComparison = compareTraversalPaths(esvo.getTraversalPath(), reference.getTraversalPath());
        if (!traversalComparison.pathsMatch()) {
            issues.add(String.format("Traversal paths don't match (similarity: %.3f)", 
                                    traversalComparison.getPathSimilarity()));
        }
        
        metrics.put("traversalSimilarity", traversalComparison.getPathSimilarity());
        metrics.put("voxelCount", esvo.getOctreeData().getVoxelCount());
        metrics.put("referenceVoxelCount", reference.getOctreeData().getVoxelCount());
        
        boolean isValid = issues.isEmpty();
        
        log.debug("Comparison completed: valid={}, issues={}, pixelDiff={:.4f}", 
                 isValid, issues.size(), pixelDifference);
        
        return new ComparisonResult(isValid, pixelDifference, differingPixels, totalPixels,
                                  voxelStructureValid, traversalComparison, issues, metrics);
    }
    
    /**
     * Validate ESVO voxel structure against reference
     * 
     * @param esvo ESVO octree data
     * @param reference Reference octree data
     * @return true if structures match within tolerance
     */
    public boolean validateVoxelStructure(ESVOOctreeData esvo, ReferenceOctreeData reference) {
        log.debug("Validating voxel structure: ESVO={} voxels, Reference={} voxels",
                 esvo.getVoxelCount(), reference.getVoxelCount());
        
        var esvoPositions = esvo.getVoxelPositions();
        var referencePositions = reference.getVoxelPositions();
        var esvoSizes = esvo.getVoxelSizes();
        var referenceSizes = reference.getVoxelSizes();
        
        // Check voxel count
        if (Math.abs(esvo.getVoxelCount() - reference.getVoxelCount()) > 
            Math.max(1, (int)(Math.max(esvo.getVoxelCount(), reference.getVoxelCount()) * 0.1))) {
            log.warn("Significant voxel count difference: {} vs {}", 
                    esvo.getVoxelCount(), reference.getVoxelCount());
            return false;
        }
        
        // Check max depth
        if (esvo.getMaxDepth() != reference.getMaxDepth()) {
            log.warn("Max depth mismatch: {} vs {}", esvo.getMaxDepth(), reference.getMaxDepth());
            return false;
        }
        
        // Find matching voxels by position
        int matchedVoxels = 0;
        for (var entry : esvoPositions.entrySet()) {
            int esvoId = entry.getKey();
            var esvoPos = entry.getValue();
            
            // Find closest reference voxel
            var closestRef = findClosestVoxel(esvoPos, referencePositions);
            if (closestRef != null) {
                double distance = esvoPos.distance(closestRef.getValue());
                if (distance <= VOXEL_POSITION_TOLERANCE) {
                    // Check size match
                    float esvoSize = esvoSizes.get(esvoId);
                    float refSize = referenceSizes.get(closestRef.getKey());
                    if (Math.abs(esvoSize - refSize) <= VOXEL_POSITION_TOLERANCE) {
                        matchedVoxels++;
                    }
                }
            }
        }
        
        double matchRatio = (double) matchedVoxels / Math.max(esvo.getVoxelCount(), reference.getVoxelCount());
        log.debug("Voxel structure validation: {}/{} matched ({}%)", 
                 matchedVoxels, Math.max(esvo.getVoxelCount(), reference.getVoxelCount()),
                 (int)(matchRatio * 100));
        
        return matchRatio >= 0.9; // 90% match required
    }
    
    /**
     * Compare ray traversal paths between ESVO and reference
     * 
     * @param esvoPath ESVO traversal steps
     * @param referencePath Reference traversal steps
     * @return Detailed traversal comparison
     */
    public TraversalComparison compareTraversalPaths(List<TraversalStep> esvoPath, 
                                                    List<TraversalStep> referencePath) {
        log.debug("Comparing traversal paths: ESVO={} steps, Reference={} steps",
                 esvoPath.size(), referencePath.size());
        
        var differences = new ArrayList<PathDifference>();
        var pathMetrics = new HashMap<String, Double>();
        
        int maxLength = Math.max(esvoPath.size(), referencePath.size());
        int minLength = Math.min(esvoPath.size(), referencePath.size());
        
        // Check path length difference
        double lengthDiff = Math.abs(esvoPath.size() - referencePath.size()) / (double) maxLength;
        pathMetrics.put("lengthDifference", lengthDiff);
        pathMetrics.put("lengthRatio", (double) minLength / maxLength);
        
        boolean pathsMatch = true;
        double totalSimilarity = 0.0;
        int comparedSteps = 0;
        
        // Compare overlapping steps
        for (int i = 0; i < minLength; i++) {
            var esvoStep = esvoPath.get(i);
            var refStep = referencePath.get(i);
            
            double positionDiff = esvoStep.distanceTo(refStep);
            double tDiff = Math.abs(esvoStep.getT() - refStep.getT());
            
            if (positionDiff > VOXEL_POSITION_TOLERANCE) {
                pathsMatch = false;
                differences.add(new PathDifference(i, esvoStep, refStep, "position", positionDiff));
            }
            
            if (esvoStep.getVoxelId() != refStep.getVoxelId()) {
                pathsMatch = false;
                differences.add(new PathDifference(i, esvoStep, refStep, "voxelId", 
                                                Math.abs(esvoStep.getVoxelId() - refStep.getVoxelId())));
            }
            
            if (tDiff > VOXEL_POSITION_TOLERANCE) {
                differences.add(new PathDifference(i, esvoStep, refStep, "parameter", tDiff));
            }
            
            // Calculate step similarity
            double stepSimilarity = 1.0 - Math.min(1.0, positionDiff + tDiff * 0.1);
            totalSimilarity += stepSimilarity;
            comparedSteps++;
        }
        
        // Penalize for length differences
        if (lengthDiff > PATH_LENGTH_TOLERANCE || 
            Math.abs(esvoPath.size() - referencePath.size()) > MAX_PATH_LENGTH_DIFF) {
            pathsMatch = false;
        }
        
        double pathSimilarity = comparedSteps > 0 ? totalSimilarity / comparedSteps : 0.0;
        pathSimilarity *= (1.0 - lengthDiff * 0.5); // Penalize length differences
        
        pathMetrics.put("pathSimilarity", pathSimilarity);
        pathMetrics.put("positionAccuracy", 1.0 - differences.stream()
            .filter(d -> d.getDifferenceType().equals("position"))
            .mapToDouble(PathDifference::getMagnitude)
            .average().orElse(0.0));
        
        log.debug("Path comparison: match={}, similarity={:.3f}, differences={}", 
                 pathsMatch, pathSimilarity, differences.size());
        
        return new TraversalComparison(pathsMatch, pathSimilarity, esvoPath.size(), 
                                     referencePath.size(), differences, pathMetrics);
    }
    
    /**
     * Calculate pixel-by-pixel difference between two images
     * 
     * @param esvoData ESVO pixel data
     * @param referenceData Reference pixel data
     * @return Average pixel difference (0.0 to 1.0)
     */
    public double calculatePixelDifference(ByteBuffer esvoData, ByteBuffer referenceData) {
        if (esvoData.remaining() != referenceData.remaining()) {
            log.warn("Pixel data size mismatch: {} vs {} bytes", 
                    esvoData.remaining(), referenceData.remaining());
            return 1.0; // Maximum difference
        }
        
        esvoData.rewind();
        referenceData.rewind();
        
        double totalDifference = 0.0;
        int pixelCount = esvoData.remaining() / 4; // Assuming RGBA format
        
        for (int i = 0; i < pixelCount; i++) {
            // Read RGBA values
            int esvoR = esvoData.get() & 0xFF;
            int esvoG = esvoData.get() & 0xFF;
            int esvoB = esvoData.get() & 0xFF;
            int esvoA = esvoData.get() & 0xFF;
            
            int refR = referenceData.get() & 0xFF;
            int refG = referenceData.get() & 0xFF;
            int refB = referenceData.get() & 0xFF;
            int refA = referenceData.get() & 0xFF;
            
            // Calculate per-channel differences
            double rDiff = Math.abs(esvoR - refR) / 255.0;
            double gDiff = Math.abs(esvoG - refG) / 255.0;
            double bDiff = Math.abs(esvoB - refB) / 255.0;
            double aDiff = Math.abs(esvoA - refA) / 255.0;
            
            // Use weighted average (RGB more important than alpha)
            double pixelDiff = (rDiff * 0.3 + gDiff * 0.3 + bDiff * 0.3 + aDiff * 0.1);
            totalDifference += pixelDiff;
        }
        
        double avgDifference = totalDifference / pixelCount;
        log.debug("Pixel comparison: {} pixels, average difference: {:.4f}", pixelCount, avgDifference);
        
        return avgDifference;
    }
    
    /**
     * Generate a visual difference map between two images
     * 
     * @param esvoData ESVO pixel data
     * @param referenceData Reference pixel data
     * @return ByteBuffer containing difference visualization
     */
    public ByteBuffer generateDifferenceMap(ByteBuffer esvoData, ByteBuffer referenceData) {
        if (esvoData.remaining() != referenceData.remaining()) {
            log.error("Cannot generate difference map: size mismatch");
            return ByteBuffer.allocate(0);
        }
        
        esvoData.rewind();
        referenceData.rewind();
        
        int dataSize = esvoData.remaining();
        var differenceMap = ByteBuffer.allocate(dataSize);
        int pixelCount = dataSize / 4;
        
        for (int i = 0; i < pixelCount; i++) {
            // Read RGBA values
            int esvoR = esvoData.get() & 0xFF;
            int esvoG = esvoData.get() & 0xFF;
            int esvoB = esvoData.get() & 0xFF;
            int esvoA = esvoData.get() & 0xFF;
            
            int refR = referenceData.get() & 0xFF;
            int refG = referenceData.get() & 0xFF;
            int refB = referenceData.get() & 0xFF;
            int refA = referenceData.get() & 0xFF;
            
            // Calculate absolute differences
            int rDiff = Math.abs(esvoR - refR);
            int gDiff = Math.abs(esvoG - refG);
            int bDiff = Math.abs(esvoB - refB);
            int aDiff = Math.abs(esvoA - refA);
            
            // Enhance differences for visibility
            int enhancedR = Math.min(255, rDiff * 3);
            int enhancedG = Math.min(255, gDiff * 3);
            int enhancedB = Math.min(255, bDiff * 3);
            int enhancedA = Math.max(128, aDiff * 2); // Ensure visibility
            
            differenceMap.put((byte) enhancedR);
            differenceMap.put((byte) enhancedG);
            differenceMap.put((byte) enhancedB);
            differenceMap.put((byte) enhancedA);
        }
        
        differenceMap.flip();
        log.debug("Generated difference map: {} bytes", differenceMap.remaining());
        return differenceMap;
    }
    
    /**
     * Count pixels that differ beyond tolerance
     * 
     * @param esvoData ESVO pixel data
     * @param referenceData Reference pixel data
     * @return Array containing [differingPixels, totalPixels]
     */
    private int[] countDifferingPixels(ByteBuffer esvoData, ByteBuffer referenceData) {
        if (esvoData.remaining() != referenceData.remaining()) {
            return new int[]{esvoData.remaining() / 4, esvoData.remaining() / 4}; // All pixels differ
        }
        
        esvoData.rewind();
        referenceData.rewind();
        
        int differingPixels = 0;
        int totalPixels = esvoData.remaining() / 4;
        
        for (int i = 0; i < totalPixels; i++) {
            // Read RGBA values
            int esvoR = esvoData.get() & 0xFF;
            int esvoG = esvoData.get() & 0xFF;
            int esvoB = esvoData.get() & 0xFF;
            int esvoA = esvoData.get() & 0xFF;
            
            int refR = referenceData.get() & 0xFF;
            int refG = referenceData.get() & 0xFF;
            int refB = referenceData.get() & 0xFF;
            int refA = referenceData.get() & 0xFF;
            
            // Calculate per-channel differences
            double rDiff = Math.abs(esvoR - refR) / 255.0;
            double gDiff = Math.abs(esvoG - refG) / 255.0;
            double bDiff = Math.abs(esvoB - refB) / 255.0;
            double aDiff = Math.abs(esvoA - refA) / 255.0;
            
            double pixelDiff = (rDiff * 0.3 + gDiff * 0.3 + bDiff * 0.3 + aDiff * 0.1);
            if (pixelDiff > PIXEL_TOLERANCE) {
                differingPixels++;
            }
        }
        
        return new int[]{differingPixels, totalPixels};
    }
    
    /**
     * Find the closest voxel to a given position
     * 
     * @param targetPos Target position
     * @param voxelPositions Map of voxel positions
     * @return Map entry of closest voxel, or null if none found
     */
    private Map.Entry<Integer, Point3f> findClosestVoxel(Point3f targetPos, 
                                                        Map<Integer, Point3f> voxelPositions) {
        Map.Entry<Integer, Point3f> closest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (var entry : voxelPositions.entrySet()) {
            double distance = targetPos.distance(entry.getValue());
            if (distance < minDistance) {
                minDistance = distance;
                closest = entry;
            }
        }
        
        return closest;
    }
}