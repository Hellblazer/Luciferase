/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.optimization;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.sparse.optimization.Optimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Ray traversal optimization for ESVT tetrahedral implementations.
 *
 * <p>Analyzes and optimizes ray coherence patterns for improved GPU utilization,
 * with specific optimizations for Moller-Trumbore tetrahedral intersection.
 *
 * <p>Key differences from ESVO traversal:
 * <ul>
 *   <li>Moller-Trumbore intersection (vs AABB slab test for octrees)</li>
 *   <li>6 tetrahedron types with different vertex arrangements</li>
 *   <li>Smaller 8-byte nodes enable better ray packet processing</li>
 *   <li>Tetrahedral child ordering affects traversal front-to-back sorting</li>
 * </ul>
 *
 * <p><b>Note:</b> This optimizer works on ray coherence, not data layout.
 * The {@link #optimize} method returns input unchanged.
 *
 * @author hal.hildebrand
 */
public class ESVTTraversalOptimizer implements Optimizer<ESVTData> {
    private static final Logger log = LoggerFactory.getLogger(ESVTTraversalOptimizer.class);

    private static final float COHERENCE_THRESHOLD = 0.1f;
    private static final int MAX_RAYS_PER_GROUP = 32;
    private static final int TET_TYPE_COUNT = 6; // S0-S5

    /**
     * Returns input unchanged - this optimizer works on ray coherence, not data layout.
     */
    @Override
    public ESVTData optimize(ESVTData input) {
        return input;
    }

    /**
     * Ray coherence analysis result.
     */
    public static class RayCoherence {
        private final float spatialCoherence;
        private final float directionalCoherence;
        private final float overallCoherence;
        private final float tetFaceCoherence;

        public RayCoherence(float spatialCoherence, float directionalCoherence,
                           float tetFaceCoherence) {
            this.spatialCoherence = spatialCoherence;
            this.directionalCoherence = directionalCoherence;
            this.tetFaceCoherence = tetFaceCoherence;
            this.overallCoherence = (spatialCoherence + directionalCoherence + tetFaceCoherence) / 3.0f;
        }

        public float getSpatialCoherence() { return spatialCoherence; }
        public float getDirectionalCoherence() { return directionalCoherence; }
        public float getTetFaceCoherence() { return tetFaceCoherence; }
        public float getOverallCoherence() { return overallCoherence; }
    }

    /**
     * Group of coherent rays for packet processing.
     */
    public static class RayGroup {
        private final int[] rayIndices;
        private final Vector3f centroid;
        private final Vector3f averageDirection;
        private final float coherenceScore;
        private final int dominantTetFace;

        public RayGroup(int[] rayIndices, Vector3f centroid,
                       Vector3f averageDirection, float coherenceScore,
                       int dominantTetFace) {
            this.rayIndices = Arrays.copyOf(rayIndices, rayIndices.length);
            this.centroid = new Vector3f(centroid);
            this.averageDirection = new Vector3f(averageDirection);
            this.coherenceScore = coherenceScore;
            this.dominantTetFace = dominantTetFace;
        }

        public int[] getRayIndices() { return Arrays.copyOf(rayIndices, rayIndices.length); }
        public Vector3f getCentroid() { return new Vector3f(centroid); }
        public Vector3f getAverageDirection() { return new Vector3f(averageDirection); }
        public float getCoherenceScore() { return coherenceScore; }
        public int getDominantTetFace() { return dominantTetFace; }
        public int size() { return rayIndices.length; }
    }

    /**
     * Traversal pattern analysis.
     */
    public static class TraversalPattern {
        private final String patternType;
        private final float efficiency;
        private final int[] tetTypeVisitCounts;
        private final Map<String, Float> metrics;

        public TraversalPattern(String patternType, float efficiency,
                               int[] tetTypeVisitCounts, Map<String, Float> metrics) {
            this.patternType = patternType;
            this.efficiency = efficiency;
            this.tetTypeVisitCounts = Arrays.copyOf(tetTypeVisitCounts, tetTypeVisitCounts.length);
            this.metrics = new HashMap<>(metrics);
        }

        public String getPatternType() { return patternType; }
        public float getEfficiency() { return efficiency; }
        public int[] getTetTypeVisitCounts() { return Arrays.copyOf(tetTypeVisitCounts, tetTypeVisitCounts.length); }
        public Map<String, Float> getMetrics() { return Collections.unmodifiableMap(metrics); }
    }

    /**
     * Moller-Trumbore intersection statistics.
     */
    public static class IntersectionStats {
        private final int totalTests;
        private final int hits;
        private final int culledEarly;
        private final float avgEdgeCrossings;
        private final Map<Integer, Integer> hitsByTetType;

        public IntersectionStats(int totalTests, int hits, int culledEarly,
                                float avgEdgeCrossings, Map<Integer, Integer> hitsByTetType) {
            this.totalTests = totalTests;
            this.hits = hits;
            this.culledEarly = culledEarly;
            this.avgEdgeCrossings = avgEdgeCrossings;
            this.hitsByTetType = new HashMap<>(hitsByTetType);
        }

        public int getTotalTests() { return totalTests; }
        public int getHits() { return hits; }
        public int getCulledEarly() { return culledEarly; }
        public float getAvgEdgeCrossings() { return avgEdgeCrossings; }
        public Map<Integer, Integer> getHitsByTetType() { return Collections.unmodifiableMap(hitsByTetType); }
        public float getHitRate() { return totalTests > 0 ? (float) hits / totalTests : 0.0f; }
        public float getEarlyCullRate() { return totalTests > 0 ? (float) culledEarly / totalTests : 0.0f; }
    }

    /**
     * Analyze ray coherence for tetrahedral traversal.
     */
    public RayCoherence analyzeRayCoherence(Vector3f[] rayOrigins, Vector3f[] rayDirections) {
        if (rayOrigins.length != rayDirections.length || rayOrigins.length == 0) {
            return new RayCoherence(0.0f, 0.0f, 0.0f);
        }

        var spatialCoherence = calculateSpatialCoherence(rayOrigins);
        var directionalCoherence = calculateDirectionalCoherence(rayDirections);
        var tetFaceCoherence = calculateTetFaceCoherence(rayDirections);

        log.debug("Ray coherence: spatial={}, directional={}, tetFace={}",
                String.format("%.2f", spatialCoherence),
                String.format("%.2f", directionalCoherence),
                String.format("%.2f", tetFaceCoherence));

        return new RayCoherence(spatialCoherence, directionalCoherence, tetFaceCoherence);
    }

    /**
     * Group rays for optimal GPU packet processing.
     */
    public List<RayGroup> optimizeRayGrouping(Vector3f[] rayOrigins, Vector3f[] rayDirections) {
        if (rayOrigins.length != rayDirections.length) {
            throw new IllegalArgumentException("Origins and directions arrays must have same length");
        }

        var groups = new ArrayList<RayGroup>();
        var remainingRays = new HashSet<Integer>();

        for (int i = 0; i < rayOrigins.length; i++) {
            remainingRays.add(i);
        }

        while (!remainingRays.isEmpty()) {
            var group = formCoherentGroup(rayOrigins, rayDirections, remainingRays);
            if (group != null) {
                groups.add(group);
                for (int index : group.getRayIndices()) {
                    remainingRays.remove(index);
                }
            } else {
                // Fallback: create single-ray group
                var singleRayIndex = remainingRays.iterator().next();
                var dominantFace = classifyDominantTetFace(rayDirections[singleRayIndex]);
                var singleRayGroup = new RayGroup(
                    new int[]{singleRayIndex},
                    new Vector3f(rayOrigins[singleRayIndex]),
                    new Vector3f(rayDirections[singleRayIndex]),
                    1.0f,
                    dominantFace
                );
                groups.add(singleRayGroup);
                remainingRays.remove(singleRayIndex);
            }
        }

        log.debug("Created {} ray groups from {} rays", groups.size(), rayOrigins.length);

        return groups;
    }

    /**
     * Analyze traversal patterns for tetrahedral trees.
     */
    public TraversalPattern analyzeTraversalPattern(int[] nodeVisitCounts,
                                                   int[] nodeTetTypes,
                                                   float[] nodeVisitTimes) {
        if (nodeVisitCounts.length != nodeTetTypes.length ||
            nodeVisitCounts.length != nodeVisitTimes.length) {
            throw new IllegalArgumentException("Arrays must have same length");
        }

        var metrics = new HashMap<String, Float>();

        // Calculate basic metrics
        var totalVisits = Arrays.stream(nodeVisitCounts).sum();
        var totalTime = 0.0f;
        for (float time : nodeVisitTimes) {
            totalTime += time;
        }
        var avgTimePerVisit = totalVisits > 0 ? totalTime / totalVisits : 0.0f;

        metrics.put("totalVisits", (float) totalVisits);
        metrics.put("totalTime", totalTime);
        metrics.put("avgTimePerVisit", avgTimePerVisit);

        // Count visits by tetrahedron type
        var tetTypeVisitCounts = new int[TET_TYPE_COUNT];
        for (int i = 0; i < nodeVisitCounts.length; i++) {
            var tetType = nodeTetTypes[i];
            if (tetType >= 0 && tetType < TET_TYPE_COUNT) {
                tetTypeVisitCounts[tetType] += nodeVisitCounts[i];
            }
        }

        // Calculate type distribution metrics
        var maxTypeVisits = Arrays.stream(tetTypeVisitCounts).max().orElse(0);
        var minTypeVisits = Arrays.stream(tetTypeVisitCounts).min().orElse(0);
        var typeBalance = maxTypeVisits > 0 ?
            (float) minTypeVisits / maxTypeVisits : 1.0f;
        metrics.put("tetTypeBalance", typeBalance);

        // Analyze visit distribution variance
        var visitVariance = calculateVariance(nodeVisitCounts);
        var timeVariance = calculateVariance(nodeVisitTimes);
        metrics.put("visitVariance", visitVariance);
        metrics.put("timeVariance", timeVariance);

        // Determine pattern type and efficiency
        String patternType;
        float efficiency;

        if (visitVariance < 100.0f && timeVariance < 1000.0f) {
            patternType = "uniform";
            efficiency = 0.9f;
        } else if (visitVariance > 10000.0f) {
            patternType = "hotspot";
            efficiency = 0.4f;
        } else if (typeBalance < 0.3f) {
            patternType = "type_biased";
            efficiency = 0.5f;
        } else {
            patternType = "scattered";
            efficiency = 0.6f;
        }

        return new TraversalPattern(patternType, efficiency, tetTypeVisitCounts, metrics);
    }

    /**
     * Optimize traversal order for better cache utilization.
     */
    public int[] optimizeTraversalOrder(int[] originalOrder,
                                       int[] nodeTetTypes,
                                       Map<Integer, Vector3f> nodePositions) {
        if (originalOrder.length == 0) {
            return new int[0];
        }

        var optimizedOrder = new ArrayList<Integer>();
        var remainingNodes = new HashSet<Integer>();

        for (int nodeId : originalOrder) {
            remainingNodes.add(nodeId);
        }

        // Group by tetrahedron type first
        var nodesByType = new HashMap<Integer, List<Integer>>();
        for (int nodeId : originalOrder) {
            if (nodeId >= 0 && nodeId < nodeTetTypes.length) {
                var tetType = nodeTetTypes[nodeId];
                nodesByType.computeIfAbsent(tetType, k -> new ArrayList<>()).add(nodeId);
            }
        }

        // Process each type group with spatial locality optimization
        for (int type = 0; type < TET_TYPE_COUNT; type++) {
            var typeNodes = nodesByType.get(type);
            if (typeNodes == null || typeNodes.isEmpty()) continue;

            // Sort by spatial locality within type
            var sortedTypeNodes = optimizeSpatialOrder(typeNodes, nodePositions);
            for (int nodeId : sortedTypeNodes) {
                if (remainingNodes.contains(nodeId)) {
                    optimizedOrder.add(nodeId);
                    remainingNodes.remove(nodeId);
                }
            }
        }

        // Add any remaining nodes
        for (int nodeId : remainingNodes) {
            optimizedOrder.add(nodeId);
        }

        return optimizedOrder.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Predict optimal workgroup size for tetrahedral traversal.
     */
    public int predictOptimalWorkgroupSize(int totalRays, RayCoherence coherence,
                                          float memoryBandwidth) {
        // Base workgroup size for ESVT (smaller nodes allow larger groups)
        var baseSize = 64;

        // Adjust based on coherence
        if (coherence.getOverallCoherence() > 0.8f) {
            baseSize = Math.min(256, baseSize * 2);
        } else if (coherence.getOverallCoherence() < 0.4f) {
            baseSize = Math.max(16, baseSize / 2);
        }

        // Adjust based on tetrahedron face coherence
        if (coherence.getTetFaceCoherence() > 0.7f) {
            baseSize = Math.min(256, (int) (baseSize * 1.5f));
        }

        // Adjust based on memory bandwidth
        if (memoryBandwidth < 0.5f) {
            baseSize = Math.max(16, baseSize / 2);
        }

        // Ensure power of 2
        baseSize = Integer.highestOneBit(baseSize);
        return Math.max(16, Math.min(512, baseSize));
    }

    /**
     * Estimate Moller-Trumbore intersection statistics.
     */
    public IntersectionStats estimateIntersectionStats(Vector3f[] rayOrigins,
                                                      Vector3f[] rayDirections,
                                                      int estimatedNodeCount) {
        // Estimate based on ray coherence and typical traversal patterns
        var coherence = analyzeRayCoherence(rayOrigins, rayDirections);

        var totalTests = rayOrigins.length * (int) (Math.log(estimatedNodeCount) / Math.log(4));
        var hitRate = 0.15f + coherence.getOverallCoherence() * 0.1f;
        var hits = (int) (totalTests * hitRate);
        var cullRate = 0.6f + coherence.getTetFaceCoherence() * 0.2f;
        var culledEarly = (int) (totalTests * cullRate);
        var avgEdgeCrossings = 2.5f - coherence.getDirectionalCoherence();

        // Estimate hits by tet type (roughly uniform for coherent rays)
        var hitsByTetType = new HashMap<Integer, Integer>();
        var hitsPerType = hits / TET_TYPE_COUNT;
        for (int i = 0; i < TET_TYPE_COUNT; i++) {
            hitsByTetType.put(i, hitsPerType);
        }

        return new IntersectionStats(totalTests, hits, culledEarly,
                                    avgEdgeCrossings, hitsByTetType);
    }

    // Private helper methods

    private float calculateSpatialCoherence(Vector3f[] origins) {
        if (origins.length < 2) return 1.0f;

        var centroid = new Vector3f(0.0f, 0.0f, 0.0f);
        for (var origin : origins) {
            centroid.add(origin);
        }
        centroid.scale(1.0f / origins.length);

        var totalDistance = 0.0f;
        for (var origin : origins) {
            totalDistance += calculateDistance(origin, centroid);
        }
        var avgDistance = totalDistance / origins.length;

        return Math.max(0.0f, (float) Math.exp(-avgDistance * 10.0f));
    }

    private float calculateDirectionalCoherence(Vector3f[] directions) {
        if (directions.length < 2) return 1.0f;

        var avgDirection = new Vector3f(0.0f, 0.0f, 0.0f);
        for (var direction : directions) {
            avgDirection.add(direction);
        }
        if (avgDirection.length() > 0) {
            avgDirection.normalize();
        }

        var totalDot = 0.0f;
        for (var direction : directions) {
            var normalized = new Vector3f(direction);
            if (normalized.length() > 0) {
                normalized.normalize();
                totalDot += Math.max(0.0f, avgDirection.dot(normalized));
            }
        }

        return totalDot / directions.length;
    }

    private float calculateTetFaceCoherence(Vector3f[] directions) {
        if (directions.length < 2) return 1.0f;

        // Count rays by dominant tetrahedron face
        var faceCountsMap = new HashMap<Integer, Integer>();
        for (var direction : directions) {
            var dominantFace = classifyDominantTetFace(direction);
            faceCountsMap.merge(dominantFace, 1, Integer::sum);
        }

        // Coherence is high if most rays hit same face
        var maxCount = faceCountsMap.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return (float) maxCount / directions.length;
    }

    private int classifyDominantTetFace(Vector3f direction) {
        // Tetrahedron has 4 faces; classify by dominant direction component
        var normalized = new Vector3f(direction);
        if (normalized.length() > 0) {
            normalized.normalize();
        }

        var absX = Math.abs(normalized.x);
        var absY = Math.abs(normalized.y);
        var absZ = Math.abs(normalized.z);

        if (absX >= absY && absX >= absZ) {
            return normalized.x > 0 ? 0 : 1;
        } else if (absY >= absX && absY >= absZ) {
            return normalized.y > 0 ? 2 : 3;
        } else {
            return 0; // Default face
        }
    }

    private RayGroup formCoherentGroup(Vector3f[] origins, Vector3f[] directions,
                                       Set<Integer> availableRays) {
        if (availableRays.isEmpty()) return null;

        var seed = availableRays.iterator().next();
        var groupIndices = new ArrayList<Integer>();
        groupIndices.add(seed);

        var seedOrigin = origins[seed];
        var seedDirection = directions[seed];
        var seedFace = classifyDominantTetFace(seedDirection);

        for (int candidate : availableRays) {
            if (candidate == seed || groupIndices.size() >= MAX_RAYS_PER_GROUP) continue;

            var candidateOrigin = origins[candidate];
            var candidateDirection = directions[candidate];
            var candidateFace = classifyDominantTetFace(candidateDirection);

            // Check spatial coherence
            var spatialDistance = calculateDistance(seedOrigin, candidateOrigin);
            var spatialCoherent = spatialDistance < COHERENCE_THRESHOLD;

            // Check directional coherence
            var normalizedSeed = new Vector3f(seedDirection);
            normalizedSeed.normalize();
            var normalizedCandidate = new Vector3f(candidateDirection);
            normalizedCandidate.normalize();
            var directionalSimilarity = normalizedSeed.dot(normalizedCandidate);
            var directionalCoherent = directionalSimilarity > 0.8f;

            // Check face coherence
            var faceCoherent = seedFace == candidateFace;

            if ((spatialCoherent && faceCoherent) || (directionalCoherent && faceCoherent)) {
                groupIndices.add(candidate);
            }
        }

        var centroid = calculateCentroid(groupIndices, origins);
        var avgDirection = calculateAverageDirection(groupIndices, directions);
        var coherenceScore = calculateGroupCoherence(groupIndices, origins, directions);
        var dominantFace = calculateDominantFace(groupIndices, directions);

        var indices = groupIndices.stream().mapToInt(Integer::intValue).toArray();
        return new RayGroup(indices, centroid, avgDirection, coherenceScore, dominantFace);
    }

    private Vector3f calculateCentroid(List<Integer> indices, Vector3f[] origins) {
        var centroid = new Vector3f(0.0f, 0.0f, 0.0f);
        for (int index : indices) {
            centroid.add(origins[index]);
        }
        centroid.scale(1.0f / indices.size());
        return centroid;
    }

    private Vector3f calculateAverageDirection(List<Integer> indices, Vector3f[] directions) {
        var avgDirection = new Vector3f(0.0f, 0.0f, 0.0f);
        for (int index : indices) {
            avgDirection.add(directions[index]);
        }
        if (avgDirection.length() > 0) {
            avgDirection.normalize();
        }
        return avgDirection;
    }

    private float calculateGroupCoherence(List<Integer> indices,
                                         Vector3f[] origins, Vector3f[] directions) {
        if (indices.size() < 2) return 1.0f;

        var groupOrigins = indices.stream()
            .map(i -> origins[i])
            .toArray(Vector3f[]::new);
        var groupDirections = indices.stream()
            .map(i -> directions[i])
            .toArray(Vector3f[]::new);

        var spatialCoherence = calculateSpatialCoherence(groupOrigins);
        var directionalCoherence = calculateDirectionalCoherence(groupDirections);
        var tetFaceCoherence = calculateTetFaceCoherence(groupDirections);

        return (spatialCoherence + directionalCoherence + tetFaceCoherence) / 3.0f;
    }

    private int calculateDominantFace(List<Integer> indices, Vector3f[] directions) {
        var faceCounts = new int[4];
        for (int index : indices) {
            var face = classifyDominantTetFace(directions[index]);
            if (face >= 0 && face < 4) {
                faceCounts[face]++;
            }
        }

        var maxFace = 0;
        for (int i = 1; i < 4; i++) {
            if (faceCounts[i] > faceCounts[maxFace]) {
                maxFace = i;
            }
        }
        return maxFace;
    }

    private List<Integer> optimizeSpatialOrder(List<Integer> nodeIds,
                                               Map<Integer, Vector3f> nodePositions) {
        if (nodeIds.isEmpty()) return nodeIds;

        var optimizedOrder = new ArrayList<Integer>();
        var remaining = new HashSet<>(nodeIds);

        var currentNode = nodeIds.get(0);
        optimizedOrder.add(currentNode);
        remaining.remove(currentNode);

        while (!remaining.isEmpty()) {
            var currentPos = nodePositions.get(currentNode);
            var closestNode = -1;
            var closestDistance = Float.MAX_VALUE;

            for (int candidate : remaining) {
                var candidatePos = nodePositions.get(candidate);
                if (currentPos != null && candidatePos != null) {
                    var distance = calculateDistance(currentPos, candidatePos);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestNode = candidate;
                    }
                }
            }

            if (closestNode != -1) {
                optimizedOrder.add(closestNode);
                remaining.remove(closestNode);
                currentNode = closestNode;
            } else {
                var nextNode = remaining.iterator().next();
                optimizedOrder.add(nextNode);
                remaining.remove(nextNode);
                currentNode = nextNode;
            }
        }

        return optimizedOrder;
    }

    private float calculateVariance(int[] values) {
        if (values.length == 0) return 0.0f;

        var mean = Arrays.stream(values).average().orElse(0.0);
        var variance = Arrays.stream(values)
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0.0);

        return (float) variance;
    }

    private float calculateVariance(float[] values) {
        if (values.length == 0) return 0.0f;

        var sum = 0.0f;
        for (float value : values) {
            sum += value;
        }
        var mean = sum / values.length;

        var varianceSum = 0.0f;
        for (float value : values) {
            var diff = value - mean;
            varianceSum += diff * diff;
        }

        return varianceSum / values.length;
    }

    private float calculateDistance(Vector3f v1, Vector3f v2) {
        var dx = v1.x - v2.x;
        var dy = v1.y - v2.y;
        var dz = v1.z - v2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
