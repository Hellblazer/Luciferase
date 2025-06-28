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
package com.hellblazer.luciferase.lucien;

/**
 * Estimates the number of nodes that will be created for a given entity count and distribution. Based on the C++
 * implementation's EstimateNodeNumber algorithm.
 *
 * @author hal.hildebrand
 */
public class NodeEstimator {

    private static int calculateMaxNodes(byte maxDepth) {
        // For octree: sum of 8^i for i from 0 to maxDepth
        // This is (8^(maxDepth+1) - 1) / 7
        if (maxDepth >= 21) {
            return Integer.MAX_VALUE; // Avoid overflow
        }

        long maxNodes = (powerOf8(maxDepth + 1) - 1) / 7;
        return (int) Math.min(maxNodes, Integer.MAX_VALUE);
    }

    /**
     * Estimate nodes needed based on sample positions
     */
    public static int estimateFromSamples(int totalEntityCount, int sampleSize, int uniqueCellsInSample,
                                          int maxEntitiesPerNode) {
        if (sampleSize <= 0 || uniqueCellsInSample <= 0) {
            return estimateNodeCount(totalEntityCount, maxEntitiesPerNode, (byte) 21, SpatialDistribution.UNIFORM);
        }

        // Extrapolate from sample
        float cellRatio = (float) uniqueCellsInSample / sampleSize;
        int estimatedCells = (int) Math.ceil(totalEntityCount * cellRatio);

        // Add overhead for subdivision
        float subdivisionOverhead = 1.2f; // 20% overhead for partial subdivisions
        return (int) Math.ceil(estimatedCells * subdivisionOverhead);
    }

    /**
     * Calculate memory requirements for the estimated nodes
     */
    public static long estimateMemoryUsage(int nodeCount, int avgEntitiesPerNode) {
        // Approximate memory per node:
        // - HashMap entry: ~32 bytes
        // - Node object: ~24 bytes base + collection overhead
        // - Per entity in node: ~16 bytes (reference + set overhead)

        long baseMemoryPerNode = 56; // HashMap entry + node object base
        long perEntityMemory = 16;

        long totalMemory = nodeCount * (baseMemoryPerNode + avgEntitiesPerNode * perEntityMemory);

        // Add overhead for sorted indices set
        long sortedSetOverhead = nodeCount * 24; // TreeSet entry overhead

        return totalMemory + sortedSetOverhead;
    }

    /**
     * Estimate the number of nodes needed for the given parameters.
     *
     * @param entityCount        Number of entities to insert
     * @param maxEntitiesPerNode Maximum entities allowed per node before subdivision
     * @param maxDepth           Maximum tree depth
     * @param distribution       Spatial distribution of entities
     * @return Estimated number of nodes
     */
    public static int estimateNodeCount(int entityCount, int maxEntitiesPerNode, byte maxDepth,
                                        SpatialDistribution distribution) {
        if (entityCount <= 0 || maxEntitiesPerNode <= 0) {
            return 0;
        }

        // Base estimate: minimum nodes needed if perfectly distributed
        int minNodes = (entityCount + maxEntitiesPerNode - 1) / maxEntitiesPerNode;

        // Adjust based on distribution type
        float distributionFactor = getDistributionFactor(distribution);

        // Account for tree structure overhead
        float treeOverhead = estimateTreeOverhead(entityCount, maxEntitiesPerNode, maxDepth);

        // Calculate final estimate
        int estimate = (int) Math.ceil(minNodes * distributionFactor * treeOverhead);

        // Cap at theoretical maximum (full tree to maxDepth)
        int maxPossibleNodes = calculateMaxNodes(maxDepth);
        return Math.min(estimate, maxPossibleNodes);
    }

    private static float estimateTreeOverhead(int entityCount, int maxEntitiesPerNode, byte maxDepth) {
        // Estimate tree depth utilization
        float avgEntitiesPerNode = (float) Math.min(entityCount / 10.0f, maxEntitiesPerNode * 0.7f);
        float depthUtilization = (float) (Math.log(entityCount / avgEntitiesPerNode) / Math.log(8));
        float normalizedDepth = Math.min(depthUtilization / maxDepth, 1.0f);

        // More depth = more intermediate nodes
        return 1.0f + normalizedDepth * 0.5f;
    }

    // Private helper methods

    /**
     * Estimate nodes for a uniform grid distribution at a specific level
     */
    public static int estimateUniformGridNodes(byte level, int nodesPerDimension) {
        // For octree: 8^level maximum nodes
        int maxNodesAtLevel = 1 << (3 * level); // 2^(3*level) = 8^level

        // User requested nodes
        int requestedNodes = nodesPerDimension * nodesPerDimension * nodesPerDimension;

        // Return the minimum of requested and maximum possible
        return Math.min(requestedNodes, maxNodesAtLevel);
    }

    private static float getDistributionFactor(SpatialDistribution distribution) {
        switch (distribution.getType()) {
            case UNIFORM:
                // Uniform distribution: minimal overhead
                return 1.1f;

            case CLUSTERED:
                // Clustered: more nodes due to uneven distribution
                // Higher clustering = more empty nodes + more subdivisions
                return 1.5f + distribution.getClusteringFactor() * 2.0f;

            case SURFACE_ALIGNED:
                // Surface aligned: many empty nodes in 3D space
                return 2.0f + (1.0f - distribution.getSurfaceThickness()) * 3.0f;

            case CUSTOM:
                // Use clustering factor as general distribution factor
                return 1.2f + distribution.getClusteringFactor() * 2.5f;

            default:
                return 1.5f; // Conservative default
        }
    }

    private static long powerOf8(int exponent) {
        return 1L << (3 * exponent); // 8^n = 2^(3n)
    }

    /**
     * Spatial distribution patterns for entity placement
     */
    public static class SpatialDistribution {
        // Predefined common distributions
        public static final SpatialDistribution UNIFORM            = new SpatialDistribution(Type.UNIFORM);
        public static final SpatialDistribution CLUSTERED_MODERATE = new SpatialDistribution(Type.CLUSTERED, 0.5f, 10,
                                                                                             0.1f);
        public static final SpatialDistribution CLUSTERED_HIGH     = new SpatialDistribution(Type.CLUSTERED, 0.8f, 20,
                                                                                             0.1f);
        public static final SpatialDistribution SURFACE_THIN       = new SpatialDistribution(Type.SURFACE_ALIGNED, 0.3f,
                                                                                             1, 0.01f);
        private final Type  type;
        private final float clusteringFactor; // 0.0 = perfectly uniform, 1.0 = highly clustered
        private final int   clusterCount;       // For CLUSTERED type
        private final float surfaceThickness; // For SURFACE_ALIGNED type

        public SpatialDistribution(Type type) {
            this(type, 0.0f, 10, 0.1f);
        }

        public SpatialDistribution(Type type, float clusteringFactor, int clusterCount, float surfaceThickness) {
            this.type = type;
            this.clusteringFactor = Math.max(0.0f, Math.min(1.0f, clusteringFactor));
            this.clusterCount = Math.max(1, clusterCount);
            this.surfaceThickness = Math.max(0.0f, surfaceThickness);
        }

        public int getClusterCount() {
            return clusterCount;
        }

        public float getClusteringFactor() {
            return clusteringFactor;
        }

        public float getSurfaceThickness() {
            return surfaceThickness;
        }

        public Type getType() {
            return type;
        }
        public enum Type {
            UNIFORM,        // Entities randomly distributed throughout space
            CLUSTERED,      // Entities grouped in clusters
            SURFACE_ALIGNED,// Entities aligned on surfaces (2.5D)
            CUSTOM          // Custom distribution with user-defined parameters
        }
    }
}
