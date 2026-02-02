/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Split plane strategy that uses K-means clustering for data-driven split plane selection.
 * <p>
 * This strategy analyzes entity distribution using 2-means clustering:
 * <ol>
 *   <li>Run K-means (k=2) on entity positions</li>
 *   <li>Identify two dominant clusters</li>
 *   <li>Compute split plane perpendicular to line between cluster centroids</li>
 *   <li>Position plane at midpoint between centroids</li>
 * </ol>
 * <p>
 * Falls back to {@link LongestAxisStrategy} when:
 * <ul>
 *   <li>Too few entities (< minClusterSize * 2)</li>
 *   <li>Clusters too close together (< maxDistance threshold)</li>
 *   <li>Uniform distribution (no clear cluster separation)</li>
 * </ul>
 * <p>
 * <b>Benefits</b>:
 * - Optimizes split based on actual entity distribution
 * - Keeps related entities together after split
 * - Better load balancing for clustered workloads
 * <p>
 * Part of P2.4: Clustering Enhancement (bead: Luciferase-cv12).
 *
 * @author hal.hildebrand
 * @see LongestAxisStrategy
 */
public class ClusteringStrategy implements SplitPlaneStrategy {

    private static final int MAX_ITERATIONS = 20;
    private static final float CONVERGENCE_THRESHOLD = 0.001f;

    private final int minClusterSize;
    private final float maxDistance;
    private final SplitPlaneStrategy fallback;

    /**
     * Creates a clustering strategy with specified parameters.
     *
     * @param minClusterSize minimum entities per cluster (recommend 3+)
     * @param maxDistance    maximum intra-cluster distance for cluster validity
     * @throws IllegalArgumentException if parameters invalid
     */
    public ClusteringStrategy(int minClusterSize, float maxDistance) {
        if (minClusterSize <= 0) {
            throw new IllegalArgumentException("Min cluster size must be positive: " + minClusterSize);
        }
        if (maxDistance <= 0.0f) {
            throw new IllegalArgumentException("Max distance must be positive: " + maxDistance);
        }

        this.minClusterSize = minClusterSize;
        this.maxDistance = maxDistance;
        this.fallback = new LongestAxisStrategy();
    }

    /**
     * Creates a clustering strategy with custom fallback.
     *
     * @param minClusterSize minimum entities per cluster
     * @param maxDistance    maximum intra-cluster distance
     * @param fallback       strategy to use when clustering fails
     */
    public ClusteringStrategy(int minClusterSize, float maxDistance, SplitPlaneStrategy fallback) {
        if (minClusterSize <= 0) {
            throw new IllegalArgumentException("Min cluster size must be positive: " + minClusterSize);
        }
        if (maxDistance <= 0.0f) {
            throw new IllegalArgumentException("Max distance must be positive: " + maxDistance);
        }
        if (fallback == null) {
            throw new NullPointerException("Fallback strategy cannot be null");
        }

        this.minClusterSize = minClusterSize;
        this.maxDistance = maxDistance;
        this.fallback = fallback;
    }

    @Override
    public SplitPlane calculate(BubbleBounds bubbleBounds, List<EnhancedBubble.EntityRecord> entities) {
        // Not enough entities for meaningful clustering
        if (entities.size() < minClusterSize * 2) {
            return fallback.calculate(bubbleBounds, entities);
        }

        // Extract positions
        var positions = new ArrayList<Point3f>(entities.size());
        for (var entity : entities) {
            positions.add(entity.position());
        }

        // Run 2-means clustering
        var clusters = kMeansClustering(positions, 2);

        // Check if clusters are valid
        if (!areClustersValid(clusters)) {
            return fallback.calculate(bubbleBounds, entities);
        }

        // Compute split plane between cluster centroids
        var c1 = computeCentroid(clusters.get(0));
        var c2 = computeCentroid(clusters.get(1));

        return computePlaneBetweenClusters(c1, c2);
    }

    /**
     * Run K-means clustering on positions.
     *
     * @param positions entity positions
     * @param k         number of clusters
     * @return list of clusters (each cluster is a list of positions)
     */
    private List<List<Point3f>> kMeansClustering(List<Point3f> positions, int k) {
        if (positions.size() < k) {
            // Return all positions in one cluster
            return List.of(new ArrayList<>(positions));
        }

        // Initialize centroids using positions at extremes
        var centroids = initializeCentroids(positions, k);

        List<List<Point3f>> clusters = null;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            // Assign positions to nearest centroid
            clusters = assignToClusters(positions, centroids);

            // Update centroids
            var newCentroids = new ArrayList<Point3f>(k);
            for (int i = 0; i < clusters.size(); i++) {
                var cluster = clusters.get(i);
                if (cluster.isEmpty()) {
                    // Keep old centroid for empty cluster
                    newCentroids.add(centroids.get(i));
                } else {
                    newCentroids.add(computeCentroid(cluster));
                }
            }

            // Check convergence
            if (hasConverged(centroids, newCentroids)) {
                break;
            }

            centroids = newCentroids;
        }

        return clusters != null ? clusters : List.of(new ArrayList<>(positions));
    }

    /**
     * Initialize centroids using positions at spatial extremes.
     * Uses farthest-first initialization for better cluster separation.
     */
    private List<Point3f> initializeCentroids(List<Point3f> positions, int k) {
        var centroids = new ArrayList<Point3f>(k);

        // First centroid: position farthest from origin
        Point3f first = positions.get(0);
        float maxDist = 0;
        for (var pos : positions) {
            float dist = pos.x * pos.x + pos.y * pos.y + pos.z * pos.z;
            if (dist > maxDist) {
                maxDist = dist;
                first = pos;
            }
        }
        centroids.add(new Point3f(first));

        // Remaining centroids: farthest from existing centroids
        for (int i = 1; i < k; i++) {
            Point3f farthest = positions.get(0);
            float maxMinDist = 0;

            for (var pos : positions) {
                float minDist = Float.MAX_VALUE;
                for (var centroid : centroids) {
                    float dist = distanceSquared(pos, centroid);
                    minDist = Math.min(minDist, dist);
                }
                if (minDist > maxMinDist) {
                    maxMinDist = minDist;
                    farthest = pos;
                }
            }
            centroids.add(new Point3f(farthest));
        }

        return centroids;
    }

    /**
     * Assign positions to nearest centroid.
     */
    private List<List<Point3f>> assignToClusters(List<Point3f> positions, List<Point3f> centroids) {
        var clusters = new ArrayList<List<Point3f>>(centroids.size());
        for (int i = 0; i < centroids.size(); i++) {
            clusters.add(new ArrayList<>());
        }

        for (var pos : positions) {
            int nearestIdx = 0;
            float minDist = Float.MAX_VALUE;

            for (int i = 0; i < centroids.size(); i++) {
                float dist = distanceSquared(pos, centroids.get(i));
                if (dist < minDist) {
                    minDist = dist;
                    nearestIdx = i;
                }
            }

            clusters.get(nearestIdx).add(pos);
        }

        return clusters;
    }

    /**
     * Compute centroid of a cluster.
     */
    private Point3f computeCentroid(List<Point3f> cluster) {
        if (cluster.isEmpty()) {
            return new Point3f(0, 0, 0);
        }

        float sumX = 0, sumY = 0, sumZ = 0;
        for (var pos : cluster) {
            sumX += pos.x;
            sumY += pos.y;
            sumZ += pos.z;
        }

        int count = cluster.size();
        return new Point3f(sumX / count, sumY / count, sumZ / count);
    }

    /**
     * Check if centroids have converged.
     */
    private boolean hasConverged(List<Point3f> old, List<Point3f> current) {
        for (int i = 0; i < old.size(); i++) {
            if (distanceSquared(old.get(i), current.get(i)) > CONVERGENCE_THRESHOLD * CONVERGENCE_THRESHOLD) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if clusters are valid for split plane computation.
     */
    private boolean areClustersValid(List<List<Point3f>> clusters) {
        if (clusters.size() < 2) {
            return false;
        }

        // Both clusters must have minimum size
        for (var cluster : clusters) {
            if (cluster.size() < minClusterSize) {
                return false;
            }
        }

        // Clusters must be separated enough
        var c1 = computeCentroid(clusters.get(0));
        var c2 = computeCentroid(clusters.get(1));
        float separation = (float) Math.sqrt(distanceSquared(c1, c2));

        // Check if separation is meaningful (at least 20% of maxDistance)
        return separation > maxDistance * 0.2f;
    }

    /**
     * Compute split plane between two cluster centroids.
     * <p>
     * The plane is:
     * - Perpendicular to line connecting centroids
     * - Positioned at midpoint between centroids
     */
    private SplitPlane computePlaneBetweenClusters(Point3f c1, Point3f c2) {
        // Direction from c1 to c2
        float dx = c2.x - c1.x;
        float dy = c2.y - c1.y;
        float dz = c2.z - c1.z;

        // Normalize to get plane normal
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) {
            // Clusters are coincident - return X-axis plane
            return SplitPlane.xAxis((c1.x + c2.x) / 2);
        }

        var normal = new Point3f(dx / len, dy / len, dz / len);

        // Midpoint between centroids
        float midX = (c1.x + c2.x) / 2;
        float midY = (c1.y + c2.y) / 2;
        float midZ = (c1.z + c2.z) / 2;

        // Distance = projection of midpoint onto normal
        float distance = normal.x * midX + normal.y * midY + normal.z * midZ;

        // Determine axis type (which component is dominant)
        var axis = inferAxis(normal);

        return new SplitPlane(normal, distance, axis);
    }

    /**
     * Infer split axis from normal vector.
     */
    private SplitPlane.SplitAxis inferAxis(Point3f normal) {
        float absX = Math.abs(normal.x);
        float absY = Math.abs(normal.y);
        float absZ = Math.abs(normal.z);

        if (absX > absY && absX > absZ) {
            return SplitPlane.SplitAxis.X;
        } else if (absY > absX && absY > absZ) {
            return SplitPlane.SplitAxis.Y;
        } else {
            return SplitPlane.SplitAxis.Z;
        }
    }

    /**
     * Squared distance between two points.
     */
    private float distanceSquared(Point3f a, Point3f b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float dz = b.z - a.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Gets the minimum cluster size parameter.
     */
    public int getMinClusterSize() {
        return minClusterSize;
    }

    /**
     * Gets the maximum intra-cluster distance parameter.
     */
    public float getMaxDistance() {
        return maxDistance;
    }
}
